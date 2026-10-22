package com.axon.core_service.campaign;

import com.axon.core_service.service.MarketingActionExecutionService;

import com.axon.core_service.campaign.domain.CampaignActivity;
import com.axon.core_service.campaign.domain.CampaignActivityPurpose;
import com.axon.core_service.campaign.domain.CampaignActivityRun;
import com.axon.core_service.campaign.domain.CampaignActivityRunStatus;
import com.axon.core_service.campaign.domain.CampaignActivityRunTarget;
import com.axon.core_service.campaign.domain.CampaignActivityRunTargetGroup;
import com.axon.core_service.coupon.domain.Coupon;
import com.axon.core_service.campaign.dto.CampaignActivityRunAnalysisRequest;
import com.axon.core_service.campaign.dto.CampaignActivityRunClaimResponse;
import com.axon.core_service.campaign.dto.CampaignActivityRunDecisionRequest;
import com.axon.core_service.campaign.dto.CampaignActivityRunResponse;
import com.axon.core_service.exception.BusinessConflictException;
import com.axon.core_service.exception.ResourceNotFoundException;
import com.axon.core_service.repository.AudienceSegmentRepository;
import com.axon.core_service.repository.UserSummaryRepository;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CampaignActivityRunService {

    private static final int ANALYSIS_CLAIM_TTL_SECONDS = 600;
    private static final List<CampaignActivityRunStatus> CAPACITY_RESERVING_STATUSES = List.of(
            CampaignActivityRunStatus.PENDING_ANALYSIS,
            CampaignActivityRunStatus.ANALYZING,
            CampaignActivityRunStatus.AWAITING_APPROVAL,
            CampaignActivityRunStatus.ANALYSIS_FAILED,
            CampaignActivityRunStatus.DISPATCHING,
            CampaignActivityRunStatus.DISPATCHED);

    private final CampaignActivityRepository activityRepository;
    private final CampaignActivityRunRepository runRepository;
    private final CampaignActivityRunTargetRepository targetRepository;
    private final AudienceSegmentRepository audienceSegmentRepository;
    private final UserSummaryRepository userSummaryRepository;
    private final CampaignActivityRunDispatchClaimService dispatchClaimService;
    private final MarketingActionExecutionService executionService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public CampaignActivityRunResponse prepare(Long activityId) {
        CampaignActivity activity = activityRepository.findWithCampaignCouponAndActionByIdForUpdate(activityId)
                .orElseThrow(() -> new ResourceNotFoundException("campaign activity", activityId));
        ValidationResult validation = validateBeforeSnapshot(activity, LocalDateTime.now());
        if (validation.blockedReason() != null) {
            return CampaignActivityRunResponse.from(runRepository.save(CampaignActivityRun.blocked(
                    activity, 0, 0, 0, validation.blockedReason(), validation.facts())));
        }

        long targetCount = userSummaryRepository.countCouponCampaignTargets(
                validation.segment().getTargetRfmSegment(), activity.getCoupon().getId());
        long reservedTargetCount = runRepository.sumReservedTargetCount(activityId, CAPACITY_RESERVING_STATUSES);
        Map<String, Object> facts = facts(activity, targetCount, reservedTargetCount);
        facts.put("targetSegment", validation.segment().getTargetRfmSegment().name());
        if (targetCount < 2) {
            return CampaignActivityRunResponse.from(runRepository.save(CampaignActivityRun.blocked(
                    activity, boundedTargetCount(targetCount), 0, 0,
                    "Coupon campaign requires at least two targets for a holdout comparison", facts)));
        }

        List<Long> userIds = userSummaryRepository.findCouponCampaignTargetUserIds(
                validation.segment().getTargetRfmSegment(), activity.getCoupon().getId(),
                PageRequest.of(0, boundedTargetCount(targetCount)));
        long snapshotTargetCount = userIds.size();
        facts.put("countedTargetCount", targetCount);
        facts.put("targetCount", snapshotTargetCount);
        if (snapshotTargetCount < 2) {
            return CampaignActivityRunResponse.from(runRepository.save(CampaignActivityRun.blocked(
                    activity, boundedTargetCount(snapshotTargetCount), 0, 0,
                    "Coupon campaign requires at least two targets for a holdout comparison", facts)));
        }
        TargetAssignment assignment = assignTargets(userIds, activity.getHoldoutRatePercent());
        facts.put("assignmentSeed", assignment.seed());
        facts.put("treatmentCount", assignment.treatmentCount());
        facts.put("holdoutCount", assignment.holdoutCount());
        facts.put("holdoutRatePercent", activity.getHoldoutRatePercent());

        String blockedReason = validateTargetCount(activity, snapshotTargetCount, assignment.treatmentCount(),
                reservedTargetCount, facts);
        if (blockedReason != null) {
            return CampaignActivityRunResponse.from(runRepository.save(CampaignActivityRun.blocked(
                    activity, boundedTargetCount(snapshotTargetCount), assignment.treatmentCount(), assignment.holdoutCount(),
                    blockedReason, facts)));
        }
        facts.put("codeFlags", codeFlags(activity, validation.segment().getTargetRfmSegment(), targetCount));

        CampaignActivityRun run = runRepository.save(CampaignActivityRun.pendingAnalysis(
                activity, boundedTargetCount(snapshotTargetCount), assignment.treatmentCount(), assignment.holdoutCount(), facts));
        targetRepository.saveAll(assignment.targets().stream()
                .map(target -> CampaignActivityRunTarget.builder()
                        .runId(run.getId())
                        .userId(target.userId())
                        .assignmentGroup(target.group())
                        .baselineRfmSegment(validation.segment().getTargetRfmSegment())
                        .build())
                .toList());
        return CampaignActivityRunResponse.from(run);
    }

    @Transactional
    public CampaignActivityRunClaimResponse claim(Long requestedRunId) {
        LocalDateTime now = LocalDateTime.now();
        List<CampaignActivityRun> candidates = requestedRunId == null
                ? runRepository.findAnalysisClaimCandidates(CampaignActivityRunStatus.PENDING_ANALYSIS,
                        CampaignActivityRunStatus.ANALYZING, now)
                : runRepository.findByIdForUpdate(requestedRunId)
                        .filter(this::canReanalyze)
                        .stream().toList();
        for (CampaignActivityRun candidate : candidates) {
            CampaignActivityRun run = runRepository.findByIdForUpdate(candidate.getId()).orElse(null);
            if (run == null || (requestedRunId == null && !canClaim(run, now))) {
                continue;
            }
            run.claimAnalysis(UUID.randomUUID().toString(), now.plusSeconds(ANALYSIS_CLAIM_TTL_SECONDS));
            return CampaignActivityRunClaimResponse.from(run);
        }
        return null;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> context(Long runId) {
        CampaignActivityRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("campaign activity run", runId));
        List<Map<String, Object>> recentRuns = runRepository
                .findByCampaignActivityIdOrderByCreatedAtDesc(run.getCampaignActivity().getId())
                .stream()
                .limit(10)
                .map(item -> Map.<String, Object>of(
                        "status", item.getStatus().name(),
                        "targetCount", item.getTargetCount(),
                        "recommendation", item.getRecommendation() == null ? "" : item.getRecommendation()))
                .toList();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("runId", run.getId());
        context.put("activityId", run.getCampaignActivity().getId());
        context.put("status", run.getStatus().name());
        context.put("facts", run.getFactSnapshot());
        context.put("operatorFeedback", run.getOperatorFeedback());
        context.put("recentRuns", recentRuns);
        return context;
    }

    @Transactional
    public CampaignActivityRunResponse saveAnalysis(Long runId, CampaignActivityRunAnalysisRequest request) {
        CampaignActivityRun run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new ResourceNotFoundException("campaign activity run", runId));
        if (!run.hasValidAnalysisClaim(request.claimToken(), LocalDateTime.now())) {
            throw new BusinessConflictException("Campaign run analysis claim is invalid or expired");
        }
        if (!List.of("NO_FLAG", "FLAG", "REVIEW_REQUIRED").contains(request.recommendation())) {
            throw new BusinessConflictException("Unsupported campaign run recommendation");
        }
        String recommendation = hasCodeFlags(run.getFactSnapshot()) ? "FLAG" : request.recommendation();
        run.saveAnalysis(request.summary(), recommendation);
        return CampaignActivityRunResponse.from(run);
    }

    @Transactional
    public CampaignActivityRunResponse failAnalysis(Long runId, String claimToken, String reason) {
        CampaignActivityRun run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new ResourceNotFoundException("campaign activity run", runId));
        if (!run.hasValidAnalysisClaim(claimToken, LocalDateTime.now())) {
            throw new BusinessConflictException("Campaign run analysis claim is invalid or expired");
        }
        run.failAnalysis(reason);
        return CampaignActivityRunResponse.from(run);
    }

    @Transactional
    public CampaignActivityRunResponse recordSlackMessage(Long runId, String messageTs) {
        CampaignActivityRun run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new ResourceNotFoundException("campaign activity run", runId));
        if (run.getSlackMessageTs() == null) {
            run.setSlackMessageTs(messageTs);
        }
        return CampaignActivityRunResponse.from(run);
    }

    @Transactional
    public CampaignActivityRunResponse decide(Long runId, CampaignActivityRunDecisionRequest request) {
        if ("CLOSE".equals(request.decision())) {
            return close(runId, request);
        }
        if (!"APPROVE".equals(request.decision())) {
            throw new BusinessConflictException("Unsupported campaign run decision");
        }
        CampaignActivityRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("campaign activity run", runId));
        if (!"FLAG".equals(run.getRecommendation())
                && !"REVIEW_REQUIRED".equals(run.getRecommendation())) {
            throw new BusinessConflictException("Campaign activity run does not require operator approval");
        }
        List<CampaignActivityKafkaProducerDto> messages = dispatchClaimService.claim(runId, request.decidedBy());
        for (CampaignActivityKafkaProducerDto message : messages) {
            kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, message)
                    .whenComplete((result, failure) -> {
                        if (failure == null) {
                            executionService.markDispatched(message.getDispatchId());
                        } else {
                            executionService.markDispatchFailed(message.getDispatchId(), failure.getMessage());
                        }
                    });
        }
        dispatchClaimService.markDispatched(runId);
        entityManager.clear();
        return get(runId);
    }

    @Transactional
    public CampaignActivityRunResponse autoDispatch(Long runId) {
        CampaignActivityRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("campaign activity run", runId));
        if (run.getStatus() != CampaignActivityRunStatus.AWAITING_APPROVAL
                || !"NO_FLAG".equals(run.getRecommendation())
                || hasCodeFlags(run.getFactSnapshot())) {
            throw new BusinessConflictException("Campaign activity run is not eligible for automatic dispatch");
        }
        List<CampaignActivityKafkaProducerDto> messages = dispatchClaimService.claim(runId, "system:campaign-review");
        for (CampaignActivityKafkaProducerDto message : messages) {
            kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, message)
                    .whenComplete((result, failure) -> {
                        if (failure == null) {
                            executionService.markDispatched(message.getDispatchId());
                        } else {
                            executionService.markDispatchFailed(message.getDispatchId(), failure.getMessage());
                        }
                    });
        }
        dispatchClaimService.markDispatched(runId);
        entityManager.clear();
        return get(runId);
    }

    @Transactional
    public CampaignActivityRunResponse addFeedback(Long runId, String feedback) {
        CampaignActivityRun run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new ResourceNotFoundException("campaign activity run", runId));
        if (!canReanalyze(run)) {
            throw new BusinessConflictException("Campaign activity run cannot be re-analyzed");
        }
        run.addOperatorFeedback(feedback);
        return CampaignActivityRunResponse.from(run);
    }

    @Transactional(readOnly = true)
    public CampaignActivityRunResponse get(Long runId) {
        return CampaignActivityRunResponse.from(runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("campaign activity run", runId)));
    }

    private CampaignActivityRunResponse close(Long runId, CampaignActivityRunDecisionRequest request) {
        CampaignActivityRun run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new ResourceNotFoundException("campaign activity run", runId));
        if (run.getStatus() == CampaignActivityRunStatus.CLOSED) {
            return CampaignActivityRunResponse.from(run);
        }
        if (run.getStatus() != CampaignActivityRunStatus.AWAITING_APPROVAL) {
            throw new BusinessConflictException("Campaign activity run is not awaiting approval");
        }
        run.close(request.decidedBy(), request.reason());
        return CampaignActivityRunResponse.from(run);
    }

    private ValidationResult validateBeforeSnapshot(CampaignActivity activity, LocalDateTime now) {
        if (activity.getActivityType() != com.axon.messaging.CampaignActivityType.COUPON
                || activity.getCouponDeliveryMode() != com.axon.core_service.campaign.domain.CouponDeliveryMode.DIRECT
                || activity.getStatus() != com.axon.core_service.campaign.dto.CampaignActivityStatus.ACTIVE
                || activity.getCoupon() == null
                || activity.getMarketingAction() == null
                || activity.getCampaign().getTargetSegmentId() == null
                || activity.getHoldoutRatePercent() == null
                || activity.getHoldoutRatePercent() < 1
                || activity.getHoldoutRatePercent() > 50
                || now.isBefore(activity.getStartDate()) || now.isAfter(activity.getEndDate())
                || now.isBefore(activity.getCoupon().getStartDate()) || now.isAfter(activity.getCoupon().getEndDate())) {
            return ValidationResult.blocked("Campaign activity, coupon, or holdout policy is not ready for dispatch",
                    facts(activity, 0, 0));
        }
        return audienceSegmentRepository.findById(activity.getCampaign().getTargetSegmentId())
                .map(segment -> ValidationResult.valid(segment))
                .orElseGet(() -> ValidationResult.blocked("Campaign audience segment is unavailable", facts(activity, 0, 0)));
    }

    private String validateTargetCount(CampaignActivity activity, long targetCount, long treatmentCount,
                                       long reservedTargetCount,
                                       Map<String, Object> facts) {
        if (activity.getLimitCount() == null || activity.getLimitCount() <= 0) {
            return "Coupon campaign requires a positive issuance limit";
        }
        if (activity.getMaxRecipientCount() != null && targetCount > activity.getMaxRecipientCount()) {
            return "Target count exceeds maximum recipient count";
        }
        long projectedRecipientCount = reservedTargetCount + treatmentCount;
        facts.put("projectedRecipientCount", projectedRecipientCount);
        if (projectedRecipientCount > activity.getLimitCount()) {
            return "Target count exceeds remaining coupon issuance limit";
        }
        Coupon coupon = activity.getCoupon();
        if (coupon.getDiscountAmount() != null && activity.getBudget() != null) {
            BigDecimal projectedCost = coupon.getDiscountAmount().multiply(BigDecimal.valueOf(projectedRecipientCount));
            facts.put("predictedFixedCouponCost", projectedCost.toPlainString());
            if (projectedCost.compareTo(activity.getBudget()) > 0) {
                return "Predicted coupon cost exceeds campaign budget";
            }
        }
        return null;
    }

    private Map<String, Object> facts(CampaignActivity activity, long targetCount, long reservedTargetCount) {
        Map<String, Object> facts = new LinkedHashMap<>();
        Coupon coupon = activity.getCoupon();
        facts.put("targetCount", targetCount);
        facts.put("reservedRecipientCount", reservedTargetCount);
        facts.put("expectedRecipientCount", activity.getExpectedRecipientCount());
        facts.put("campaignPurpose", activity.getPurpose() == null ? null : activity.getPurpose().name());
        facts.put("operatorMemo", activity.getOperatorMemo());
        facts.put("issuanceLimit", activity.getLimitCount());
        facts.put("maxRecipientCount", activity.getMaxRecipientCount());
        facts.put("holdoutRatePercent", activity.getHoldoutRatePercent());
        facts.put("budget", activity.getBudget() == null ? null : activity.getBudget().toPlainString());
        facts.put("couponId", coupon == null ? null : coupon.getId());
        facts.put("couponDiscountAmount", coupon == null || coupon.getDiscountAmount() == null
                ? null : coupon.getDiscountAmount().toPlainString());
        facts.put("couponDiscountRate", coupon == null ? null : coupon.getDiscountRate());
        facts.put("activityStartAt", activity.getStartDate() == null ? null : activity.getStartDate().toString());
        facts.put("activityEndAt", activity.getEndDate() == null ? null : activity.getEndDate().toString());
        facts.put("targetSegmentId", activity.getCampaign().getTargetSegmentId());
        return facts;
    }

    private List<String> codeFlags(CampaignActivity activity,
                                   com.axon.core_service.domain.user.RfmSegment targetSegment,
                                   long targetCount) {
        List<String> flags = new ArrayList<>();
        if (activity.getExpectedRecipientCount() == null) {
            flags.add("EXPECTED_RECIPIENT_COUNT_MISSING");
        } else if (targetCount > activity.getExpectedRecipientCount()) {
            flags.add("TARGET_COUNT_EXCEEDS_EXPECTATION");
        }
        if (activity.getPurpose() == null) {
            flags.add("CAMPAIGN_PURPOSE_MISSING");
        } else if (purposeDoesNotMatchSegment(activity.getPurpose(), targetSegment)) {
            flags.add("PURPOSE_AND_TARGET_SEGMENT_MISMATCH");
        }
        if (activity.getOperatorMemo() == null || activity.getOperatorMemo().isBlank()) {
            flags.add("OPERATOR_MEMO_MISSING");
        }
        return flags;
    }

    private boolean purposeDoesNotMatchSegment(CampaignActivityPurpose purpose,
                                               com.axon.core_service.domain.user.RfmSegment targetSegment) {
        return switch (purpose) {
            case VIP_REWARD -> targetSegment != com.axon.core_service.domain.user.RfmSegment.VIP;
            case LOYALTY_REWARD -> targetSegment != com.axon.core_service.domain.user.RfmSegment.VIP
                    && targetSegment != com.axon.core_service.domain.user.RfmSegment.LOYAL;
            case REENGAGEMENT -> targetSegment != com.axon.core_service.domain.user.RfmSegment.AT_RISK
                    && targetSegment != com.axon.core_service.domain.user.RfmSegment.DORMANT;
            case GENERAL_PROMOTION, OTHER -> false;
        };
    }

    private boolean hasCodeFlags(Map<String, Object> facts) {
        Object flags = facts == null ? null : facts.get("codeFlags");
        return flags instanceof List<?> values && !values.isEmpty();
    }

    private int boundedTargetCount(long targetCount) {
        return Math.toIntExact(Math.min(targetCount, Integer.MAX_VALUE));
    }

    private TargetAssignment assignTargets(List<Long> userIds, int holdoutRatePercent) {
        List<Long> shuffled = new ArrayList<>(userIds);
        String seed = UUID.randomUUID().toString();
        Collections.shuffle(shuffled, new Random(seed.hashCode()));
        int holdoutCount = Math.max(1, (int) Math.round(shuffled.size() * (holdoutRatePercent / 100.0)));
        List<AssignedTarget> targets = new ArrayList<>(shuffled.size());
        for (int index = 0; index < shuffled.size(); index++) {
            CampaignActivityRunTargetGroup group = index < holdoutCount
                    ? CampaignActivityRunTargetGroup.HOLDOUT
                    : CampaignActivityRunTargetGroup.TREATMENT;
            targets.add(new AssignedTarget(shuffled.get(index), group));
        }
        return new TargetAssignment(seed, targets, shuffled.size() - holdoutCount, holdoutCount);
    }

    private boolean canClaim(CampaignActivityRun run, LocalDateTime now) {
        return run.getStatus() == CampaignActivityRunStatus.PENDING_ANALYSIS
                || (run.getStatus() == CampaignActivityRunStatus.ANALYZING
                && run.getAnalysisClaimExpiresAt() != null
                && run.getAnalysisClaimExpiresAt().isBefore(now));
    }

    private boolean canReanalyze(CampaignActivityRun run) {
        return run.getStatus() == CampaignActivityRunStatus.AWAITING_APPROVAL
                || run.getStatus() == CampaignActivityRunStatus.ANALYSIS_FAILED;
    }

    private record ValidationResult(com.axon.core_service.domain.marketing.AudienceSegment segment,
                                    String blockedReason,
                                    Map<String, Object> facts) {
        static ValidationResult valid(com.axon.core_service.domain.marketing.AudienceSegment segment) {
            return new ValidationResult(segment, null, null);
        }

        static ValidationResult blocked(String blockedReason, Map<String, Object> facts) {
            return new ValidationResult(null, blockedReason, facts);
        }
    }

    private record AssignedTarget(Long userId, CampaignActivityRunTargetGroup group) {
    }

    private record TargetAssignment(String seed, List<AssignedTarget> targets, int treatmentCount, int holdoutCount) {
    }
}
