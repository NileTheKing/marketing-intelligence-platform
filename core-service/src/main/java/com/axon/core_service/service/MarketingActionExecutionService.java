package com.axon.core_service.service;

import com.axon.core_service.domain.marketing.MarketingActionDispatch;
import com.axon.core_service.domain.marketing.MarketingActionDispatchInitiatedBy;
import com.axon.core_service.domain.marketing.MarketingActionExecution;
import com.axon.core_service.domain.marketing.MarketingActionExecutionStatus;
import com.axon.core_service.domain.marketing.RewardType;
import com.axon.core_service.repository.MarketingActionDispatchRepository;
import com.axon.core_service.repository.MarketingActionExecutionRepository;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.MarketingActionFailureCategory;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MarketingActionExecutionService {

    private final MarketingActionExecutionRepository executionRepository;
    private final MarketingActionDispatchRepository dispatchRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MarketingActionExecutionRetryClaimService retryClaimService;
    private MarketingActionTriageService triageService;

    public MarketingActionExecutionService(MarketingActionExecutionRepository executionRepository,
                                           MarketingActionDispatchRepository dispatchRepository,
                                           KafkaTemplate<String, Object> kafkaTemplate,
                                           MarketingActionExecutionRetryClaimService retryClaimService) {
        this.executionRepository = executionRepository;
        this.dispatchRepository = dispatchRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.retryClaimService = retryClaimService;
    }

    @Autowired(required = false)
    public void setTriageService(MarketingActionTriageService triageService) {
        this.triageService = triageService;
    }

    @Transactional
    public MarketingActionDispatch createPending(Long actionId, Long ruleId, Long actionReferenceId,
                                                  Long userId, Long productId, RewardType channel) {
        MarketingActionExecution execution = executionRepository.save(MarketingActionExecution.builder()
                .actionId(actionId)
                .ruleId(ruleId)
                .actionReferenceId(actionReferenceId)
                .userId(userId)
                .productId(productId)
                .channel(channel)
                .build());
        return dispatchRepository.saveAndFlush(MarketingActionDispatch.builder()
                .execution(execution)
                .sequence(1L)
                .initiatedBy(MarketingActionDispatchInitiatedBy.SYSTEM)
                .build());
    }

    @Transactional
    public boolean markDispatching(Long dispatchId) {
        return dispatchRepository.markDispatching(dispatchId,
                MarketingActionExecutionStatus.PENDING,
                MarketingActionExecutionStatus.DISPATCHING) == 1;
    }

    @Transactional
    public boolean markDispatched(Long dispatchId) {
        return dispatchRepository.markDispatched(dispatchId,
                MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.DISPATCHED,
                LocalDateTime.now()) == 1;
    }

    @Transactional
    public boolean recordAttempt(Long dispatchId) {
        if (dispatchId == null) {
            return false;
        }
        return dispatchRepository.recordAttempt(dispatchId,
                MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.DISPATCHED,
                MarketingActionExecutionStatus.PROCESSING,
                MarketingActionExecutionStatus.RETRYING) == 1;
    }

    @Transactional
    public boolean markSucceeded(Long dispatchId) {
        if (dispatchId == null) {
            return false;
        }
        return dispatchRepository.markSucceeded(dispatchId,
                MarketingActionExecutionStatus.PROCESSING,
                MarketingActionExecutionStatus.RETRYING,
                MarketingActionExecutionStatus.SUCCEEDED,
                LocalDateTime.now()) == 1;
    }

    @Transactional
    public boolean markDispatchFailed(Long dispatchId, String reason) {
        if (dispatchId == null) {
            return false;
        }
        return dispatchRepository.markDispatchFailed(dispatchId,
                MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.FAILED_FINAL,
                reason,
                LocalDateTime.now()) == 1;
    }

    @Transactional
    public boolean markDltFinal(Long dispatchId, String reason) {
        return markDltFinal(dispatchId, MarketingActionFailureCategory.UNKNOWN, reason);
    }

    @Transactional
    public boolean markDltFinal(Long dispatchId, MarketingActionFailureCategory category, String reason) {
        if (dispatchId == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean updated = dispatchRepository.markDltFinal(dispatchId,
                MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.DISPATCHED,
                MarketingActionExecutionStatus.PROCESSING,
                MarketingActionExecutionStatus.RETRYING,
                MarketingActionExecutionStatus.FAILED_FINAL,
                reason,
                now,
                now) == 1;
        if (updated && triageService != null) {
            triageService.createForFinalFailure(dispatchId, category, reason);
        }
        return updated;
    }

    @Transactional(readOnly = true)
    public List<MarketingActionDispatch> findFinalFailures() {
        return dispatchRepository.findLatestFinalFailures(MarketingActionExecutionStatus.FAILED_FINAL);
    }

    public MarketingActionDispatch retryApproved(Long executionId) {
        MarketingActionDispatch dispatchToPublish = retryClaimService.claim(executionId);
        MarketingActionExecution execution = dispatchToPublish.getExecution();

        CampaignActivityKafkaProducerDto message = CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(toCampaignActivityType(execution.getChannel()))
                .userId(execution.getUserId())
                .productId(execution.getProductId())
                .marketingRuleId(execution.getRuleId())
                .marketingActionId(execution.getActionId())
                .actionReferenceId(execution.getActionReferenceId())
                .executionId(execution.getId())
                .dispatchId(dispatchToPublish.getId())
                .timestamp(System.currentTimeMillis())
                .build();

        try {
            kafkaTemplate.send(commandTopic(execution.getChannel()), message)
                    .whenComplete((result, failure) -> {
                        if (failure == null) {
                            markDispatched(dispatchToPublish.getId());
                        } else {
                            markDispatchFailed(dispatchToPublish.getId(), failure.getMessage());
                        }
                    });
            return dispatchToPublish;
        } catch (Exception failure) {
            markDispatchFailed(dispatchToPublish.getId(), failure.getMessage());
            throw failure;
        }
    }

    private String commandTopic(RewardType channel) {
        return channel == RewardType.WEBHOOK
                ? KafkaTopics.WEBHOOK_COMMAND
                : KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND;
    }

    private CampaignActivityType toCampaignActivityType(RewardType channel) {
        return channel == RewardType.WEBHOOK
                ? CampaignActivityType.WEBHOOK
                : CampaignActivityType.COUPON;
    }
}
