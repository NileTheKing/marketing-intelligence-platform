package com.axon.core_service.service;

import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.campaignactivity.CampaignActivityRun;
import com.axon.core_service.domain.campaignactivity.CampaignActivityRunStatus;
import com.axon.core_service.domain.campaignactivity.CampaignActivityRunTarget;
import com.axon.core_service.domain.coupon.Coupon;
import com.axon.core_service.domain.marketing.MarketingActionDispatch;
import com.axon.core_service.domain.marketing.RewardType;
import com.axon.core_service.exception.BusinessConflictException;
import com.axon.core_service.repository.CampaignActivityRunRepository;
import com.axon.core_service.repository.CampaignActivityRunTargetRepository;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.AudienceSegmentRepository;
import com.axon.core_service.repository.UserSummaryRepository;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CampaignActivityRunDispatchClaimService {

    private static final List<CampaignActivityRunStatus> CAPACITY_RESERVING_STATUSES = List.of(
            CampaignActivityRunStatus.PENDING_ANALYSIS,
            CampaignActivityRunStatus.ANALYZING,
            CampaignActivityRunStatus.AWAITING_APPROVAL,
            CampaignActivityRunStatus.ANALYSIS_FAILED,
            CampaignActivityRunStatus.DISPATCHING,
            CampaignActivityRunStatus.DISPATCHED);

    private final CampaignActivityRunRepository runRepository;
    private final CampaignActivityRunTargetRepository targetRepository;
    private final CampaignActivityRepository activityRepository;
    private final UserSummaryRepository userSummaryRepository;
    private final AudienceSegmentRepository audienceSegmentRepository;
    private final MarketingActionExecutionService executionService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<CampaignActivityKafkaProducerDto> claim(Long runId, String decidedBy) {
        CampaignActivityRun run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new BusinessConflictException("Campaign activity run not found"));
        if (run.getStatus() != CampaignActivityRunStatus.AWAITING_APPROVAL) {
            throw new BusinessConflictException("Campaign activity run is not awaiting approval");
        }

        CampaignActivity activity = activityRepository.findWithCampaignCouponAndActionByIdForUpdate(
                        run.getCampaignActivity().getId())
                .orElseThrow(() -> new BusinessConflictException("Campaign activity no longer exists"));
        Coupon coupon = activity.getCoupon();
        String policyChangeReason = currentPolicyChangeReason(run, activity, coupon);
        if (policyChangeReason != null) {
            run.hold(policyChangeReason);
            return List.of();
        }

        List<CampaignActivityRunTarget> targets = targetRepository
                .findAllByRunIdAndExecutionIdIsNullOrderByUserIdAsc(runId);
        var audienceSegment = audienceSegmentRepository.findById(activity.getCampaign().getTargetSegmentId())
                .orElseThrow(() -> new BusinessConflictException("Campaign audience segment is no longer available"));
        Set<Long> eligibleUserIds = Set.copyOf(userSummaryRepository.findCouponCampaignTargetUserIdsByUserIdIn(
                audienceSegment.getTargetRfmSegment(),
                coupon.getId(),
                targets.stream().map(CampaignActivityRunTarget::getUserId).toList()));

        run.markDispatching(decidedBy);
        return targets.stream()
                .filter(target -> eligibleUserIds.contains(target.getUserId()))
                .map(target -> createMessage(run, activity, target))
                .toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDispatched(Long runId) {
        CampaignActivityRun run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new BusinessConflictException("Campaign activity run not found"));
        if (run.getStatus() == CampaignActivityRunStatus.DISPATCHING) {
            run.markDispatched();
        }
    }

    private CampaignActivityKafkaProducerDto createMessage(CampaignActivityRun run,
                                                             CampaignActivity activity,
                                                             CampaignActivityRunTarget target) {
        MarketingActionDispatch dispatch = executionService.createCampaignRunPending(
                activity.getMarketingAction().getId(),
                activity.getCoupon().getId(),
                target.getUserId(),
                run.getId(),
                RewardType.COUPON);
        target.assignExecution(dispatch.getExecution().getId());
        if (!executionService.markDispatching(dispatch.getId())) {
            throw new BusinessConflictException("Campaign activity dispatch could not be claimed");
        }
        return CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(CampaignActivityType.COUPON)
                .userId(target.getUserId())
                .marketingActionId(activity.getMarketingAction().getId())
                .actionReferenceId(activity.getCoupon().getId())
                .executionId(dispatch.getExecution().getId())
                .dispatchId(dispatch.getId())
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private String currentPolicyChangeReason(CampaignActivityRun run, CampaignActivity activity, Coupon coupon) {
        LocalDateTime now = LocalDateTime.now();
        if (activity.getActivityType() != CampaignActivityType.COUPON
                || activity.getStatus() != com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus.ACTIVE
                || coupon == null
                || activity.getMarketingAction() == null
                || activity.getCampaign().getTargetSegmentId() == null
                || now.isBefore(activity.getStartDate()) || now.isAfter(activity.getEndDate())
                || now.isBefore(coupon.getStartDate()) || now.isAfter(coupon.getEndDate())) {
            return "Campaign activity or coupon is no longer active for dispatch";
        }

        Map<String, Object> facts = run.getFactSnapshot();
        if (!same(facts, "couponId", coupon.getId())
                || !same(facts, "issuanceLimit", activity.getLimitCount())
                || !same(facts, "maxRecipientCount", activity.getMaxRecipientCount())
                || !same(facts, "expectedRecipientCount", activity.getExpectedRecipientCount())
                || !same(facts, "campaignPurpose", activity.getPurpose() == null ? null : activity.getPurpose().name())
                || !same(facts, "operatorMemo", activity.getOperatorMemo())
                || !same(facts, "budget", activity.getBudget() == null ? null : activity.getBudget().toPlainString())
                || !same(facts, "couponDiscountAmount", coupon.getDiscountAmount() == null
                        ? null : coupon.getDiscountAmount().toPlainString())
                || !same(facts, "couponDiscountRate", coupon.getDiscountRate())
                || !same(facts, "targetSegmentId", activity.getCampaign().getTargetSegmentId())) {
            return "Campaign activity configuration changed after review";
        }

        if (activity.getMaxRecipientCount() != null && run.getTargetCount() > activity.getMaxRecipientCount()) {
            return "Target count exceeds current maximum recipient count";
        }
        long reservedTargetCount = runRepository.sumReservedTargetCount(
                activity.getId(), CAPACITY_RESERVING_STATUSES);
        if (reservedTargetCount > activity.getLimitCount()) {
            return "Campaign activity issuance limit is already reserved";
        }
        if (coupon.getDiscountAmount() != null && activity.getBudget() != null) {
            BigDecimal predictedCost = coupon.getDiscountAmount().multiply(BigDecimal.valueOf(reservedTargetCount));
            if (predictedCost.compareTo(activity.getBudget()) > 0) {
                return "Predicted coupon cost exceeds current budget";
            }
        }
        return null;
    }

    private boolean same(Map<String, Object> facts, String key, Object value) {
        return facts != null && String.valueOf(value).equals(String.valueOf(facts.get(key)));
    }
}
