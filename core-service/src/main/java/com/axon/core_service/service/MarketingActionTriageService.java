package com.axon.core_service.service;

import com.axon.core_service.domain.dto.marketing.triage.TriageAnalysisRequest;
import com.axon.core_service.domain.dto.marketing.triage.TriageCaseResponse;
import com.axon.core_service.domain.dto.marketing.triage.TriageDecisionRequest;
import com.axon.core_service.domain.dto.marketing.triage.TriageDispatchContextResponse;
import com.axon.core_service.domain.dto.marketing.triage.TriageFailureHistoryResponse;
import com.axon.core_service.domain.marketing.MarketingActionDispatch;
import com.axon.core_service.domain.marketing.MarketingActionTriageCase;
import com.axon.core_service.domain.marketing.MarketingActionTriageRecommendation;
import com.axon.core_service.domain.marketing.MarketingActionTriageStatus;
import com.axon.core_service.exception.BusinessConflictException;
import com.axon.core_service.exception.ResourceNotFoundException;
import com.axon.core_service.repository.MarketingActionDispatchRepository;
import com.axon.core_service.repository.MarketingActionExecutionRepository;
import com.axon.core_service.repository.MarketingActionRepository;
import com.axon.core_service.repository.MarketingActionTriageCaseRepository;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.MarketingActionFailureCategory;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MarketingActionTriageService {

    private final MarketingActionTriageCaseRepository triageCaseRepository;
    private final MarketingActionDispatchRepository dispatchRepository;
    private final MarketingActionExecutionRepository executionRepository;
    private final MarketingActionRepository actionRepository;
    private final MarketingActionExecutionRetryClaimService retryClaimService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TriageEvidenceRenderer evidenceRenderer;

    @Value("${axon.triage.analysis-claim-ttl-seconds:600}")
    private long claimTtlSeconds;

    @Transactional
    public void createForFinalFailure(Long dispatchId,
                                      MarketingActionFailureCategory category,
                                      String failureReason) {
        if (dispatchId == null || triageCaseRepository.findByDispatch_Id(dispatchId).isPresent()) {
            return;
        }
        MarketingActionDispatch dispatch = dispatchRepository.findByIdWithExecution(dispatchId)
                .orElseThrow(() -> new ResourceNotFoundException("Marketing action dispatch", dispatchId));
        triageCaseRepository.save(MarketingActionTriageCase.pending(
                dispatch, category, failureReason, operatorGuidance(category, dispatch.getExecution().getChannel())));
    }

    @Transactional
    public TriageCaseResponse claim() {
        return claim(null);
    }

    @Transactional
    public TriageCaseResponse claim(Long requestedCaseId) {
        LocalDateTime now = LocalDateTime.now();
        List<MarketingActionTriageCase> candidates = requestedCaseId == null
                ? triageCaseRepository.findClaimCandidates(
                        MarketingActionTriageStatus.PENDING, MarketingActionTriageStatus.ANALYZING, now,
                        PageRequest.of(0, 1))
                : triageCaseRepository.findByIdForUpdate(requestedCaseId)
                        .filter(item -> item.getStatus() == MarketingActionTriageStatus.AWAITING_APPROVAL
                                || item.getStatus() == MarketingActionTriageStatus.ANALYSIS_FAILED)
                        .map(List::of).orElseGet(List::of);
        if (candidates.isEmpty()) {
            return null;
        }
        MarketingActionTriageCase triageCase = candidates.get(0);
        triageCase.claim(UUID.randomUUID().toString(), now.plusSeconds(claimTtlSeconds));
        return TriageCaseResponse.from(triageCaseRepository.saveAndFlush(triageCase));
    }

    @Transactional(readOnly = true)
    public TriageDispatchContextResponse dispatchContext(Long dispatchId) {
        MarketingActionDispatch dispatch = dispatchRepository.findByIdWithExecution(dispatchId)
                .orElseThrow(() -> new ResourceNotFoundException("Marketing action dispatch", dispatchId));
        MarketingActionTriageCase triageCase = triageCaseRepository.findByDispatch_Id(dispatchId).orElse(null);
        List<MarketingActionDispatch> history = dispatchRepository
                .findAllByExecution_IdOrderBySequenceAsc(dispatch.getExecution().getId());
        String guidance = triageCase == null
                ? operatorGuidance(MarketingActionFailureCategory.UNKNOWN, dispatch.getExecution().getChannel())
                : triageCase.getOperatorGuidance();
        return TriageDispatchContextResponse.from(dispatch, triageCase, history, guidance,
                actionRepository.findById(dispatch.getExecution().getActionId()).orElse(null));
    }

    @Transactional(readOnly = true)
    public TriageFailureHistoryResponse failureHistory(Long actionId, int days) {
        int boundedDays = Math.max(1, Math.min(days, 30));
        LocalDateTime since = LocalDateTime.now().minusDays(boundedDays);
        List<MarketingActionTriageCaseRepository.FailureCategoryCount> counts =
                triageCaseRepository.countFailuresByActionSince(actionId, since);
        long total = counts.stream().mapToLong(MarketingActionTriageCaseRepository.FailureCategoryCount::getCount).sum();
        return new TriageFailureHistoryResponse(actionId, boundedDays, total,
                counts.stream().map(item -> new TriageFailureHistoryResponse.FailureCategoryCountResponse(
                        item.getCategory().name(), item.getCount())).toList());
    }

    @Transactional(readOnly = true)
    public List<com.axon.core_service.domain.dto.marketing.triage.TriageDispatchResponse> dispatchHistory(
            Long executionId) {
        executionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Marketing action execution", executionId));
        return dispatchRepository.findAllByExecution_IdOrderBySequenceAsc(executionId).stream()
                .map(com.axon.core_service.domain.dto.marketing.triage.TriageDispatchResponse::from).toList();
    }

    @Transactional
    public TriageCaseResponse saveAnalysis(Long caseId, TriageAnalysisRequest request) {
        MarketingActionTriageCase triageCase = triageCaseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new ResourceNotFoundException("Marketing action triage case", caseId));
        if (!triageCase.hasValidClaim(request.claimToken(), LocalDateTime.now())) {
            throw new BusinessConflictException("Triage analysis claim is invalid or expired");
        }
        MarketingActionTriageRecommendation recommendation;
        try {
            recommendation = MarketingActionTriageRecommendation.valueOf(request.recommendation());
        } catch (IllegalArgumentException exception) {
            throw new BusinessConflictException("Unsupported triage recommendation");
        }
        List<String> evidence = evidenceRenderer.render(request.factSnapshot(), request.evidenceRefs());
        triageCase.saveAnalysis(request.factSnapshot(), recommendation, request.confidence(), request.summary(),
                evidence, request.operatorNextStep(), request.llmModel());
        return TriageCaseResponse.from(triageCaseRepository.save(triageCase));
    }

    @Transactional
    public TriageCaseResponse recordSlackMessage(Long caseId, String messageTs) {
        MarketingActionTriageCase triageCase = triageCaseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new ResourceNotFoundException("Marketing action triage case", caseId));
        if (triageCase.getSlackMessageTs() == null) {
            triageCase.setSlackMessageTs(messageTs);
            triageCaseRepository.save(triageCase);
        }
        return TriageCaseResponse.from(triageCase);
    }

    @Transactional
    public TriageCaseResponse failAnalysis(Long caseId, String claimToken, String reason) {
        MarketingActionTriageCase triageCase = triageCaseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new ResourceNotFoundException("Marketing action triage case", caseId));
        if (!triageCase.hasValidClaim(claimToken, LocalDateTime.now())) {
            throw new BusinessConflictException("Triage analysis claim is invalid or expired");
        }
        triageCase.markAnalysisFailed(reason);
        return TriageCaseResponse.from(triageCaseRepository.save(triageCase));
    }

    @Transactional
    public TriageCaseResponse decide(Long caseId, TriageDecisionRequest request) {
        MarketingActionTriageCase triageCase = triageCaseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new ResourceNotFoundException("Marketing action triage case", caseId));
        if ("CLOSE".equalsIgnoreCase(request.decision())) {
            if (triageCase.getStatus() == MarketingActionTriageStatus.CLOSED
                    || triageCase.getStatus() == MarketingActionTriageStatus.APPROVED) {
                return TriageCaseResponse.from(triageCase);
            }
            triageCase.close(request.decidedBy(), request.reason());
            return TriageCaseResponse.from(triageCaseRepository.save(triageCase));
        }
        if (!"APPROVE".equalsIgnoreCase(request.decision())) {
            throw new BusinessConflictException("Decision must be APPROVE or CLOSE");
        }
        if (triageCase.getStatus() == MarketingActionTriageStatus.APPROVED) {
            return TriageCaseResponse.from(triageCase);
        }
        if (triageCase.getStatus() != MarketingActionTriageStatus.AWAITING_APPROVAL) {
            throw new BusinessConflictException("Triage case is not awaiting approval: " + triageCase.getStatus());
        }

        MarketingActionDispatch retry = retryClaimService.claim(triageCase.getDispatch().getExecution().getId());
        publishRetry(retry);
        triageCase.approve(request.decidedBy());
        return TriageCaseResponse.from(triageCaseRepository.save(triageCase));
    }

    private void publishRetry(MarketingActionDispatch dispatch) {
        var execution = dispatch.getExecution();
        CampaignActivityKafkaProducerDto message = CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(execution.getChannel() == com.axon.core_service.domain.marketing.RewardType.WEBHOOK
                        ? CampaignActivityType.WEBHOOK : CampaignActivityType.COUPON)
                .userId(execution.getUserId())
                .productId(execution.getProductId())
                .marketingRuleId(execution.getRuleId())
                .marketingActionId(execution.getActionId())
                .actionReferenceId(execution.getActionReferenceId())
                .executionId(execution.getId())
                .dispatchId(dispatch.getId())
                .timestamp(System.currentTimeMillis())
                .build();
        try {
            kafkaTemplate.send(execution.getChannel() == com.axon.core_service.domain.marketing.RewardType.WEBHOOK
                            ? KafkaTopics.WEBHOOK_COMMAND : KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, message)
                    .whenComplete((result, failure) -> {
                        if (failure == null) {
                            dispatchRepository.markDispatched(dispatch.getId(),
                                    com.axon.core_service.domain.marketing.MarketingActionExecutionStatus.DISPATCHING,
                                    com.axon.core_service.domain.marketing.MarketingActionExecutionStatus.DISPATCHED,
                                    LocalDateTime.now());
                        } else {
                            dispatchRepository.markDispatchFailed(dispatch.getId(),
                                    com.axon.core_service.domain.marketing.MarketingActionExecutionStatus.DISPATCHING,
                                    com.axon.core_service.domain.marketing.MarketingActionExecutionStatus.FAILED_FINAL,
                                    failure.getMessage(), LocalDateTime.now());
                        }
                    });
        } catch (Exception failure) {
            dispatchRepository.markDispatchFailed(dispatch.getId(),
                    com.axon.core_service.domain.marketing.MarketingActionExecutionStatus.DISPATCHING,
                    com.axon.core_service.domain.marketing.MarketingActionExecutionStatus.FAILED_FINAL,
                    failure.getMessage(), LocalDateTime.now());
            throw failure;
        }
    }

    private String operatorGuidance(MarketingActionFailureCategory category,
                                    com.axon.core_service.domain.marketing.RewardType channel) {
        return switch (category) {
            case INVALID_TARGET -> "대상 또는 쿠폰 설정을 수정한 뒤 필요하면 새 액션을 생성하세요. 같은 Dispatch 재실행은 권하지 않습니다.";
            case RATE_LIMITED -> "외부 수신자 제한과 재시도 시점을 확인한 뒤 재실행을 검토하세요.";
            case TRANSIENT_DELIVERY -> "최근 같은 " + channel.name() + " 전달 실패율과 정상화 여부를 확인한 뒤 재실행을 검토하세요.";
            case AUTHORIZATION -> "Webhook 인증 정보와 수신 endpoint 권한을 확인한 뒤 재실행을 검토하세요.";
            case UNKNOWN -> "실패 원인과 대상 상태를 확인한 뒤 재실행 여부를 수동으로 판단하세요.";
        };
    }
}
