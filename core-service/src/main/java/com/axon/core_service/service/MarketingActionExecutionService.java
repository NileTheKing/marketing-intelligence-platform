package com.axon.core_service.service;

import com.axon.core_service.domain.marketing.MarketingActionExecution;
import com.axon.core_service.domain.marketing.MarketingActionExecutionStatus;
import com.axon.core_service.domain.marketing.RewardType;
import com.axon.core_service.exception.BusinessConflictException;
import com.axon.core_service.exception.ResourceNotFoundException;
import com.axon.core_service.repository.MarketingActionExecutionRepository;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MarketingActionExecutionService {

    private final MarketingActionExecutionRepository executionRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    public MarketingActionExecution createPending(Long actionId, Long ruleId, Long actionReferenceId,
                                                    Long userId, Long productId, RewardType channel) {
        return executionRepository.save(MarketingActionExecution.builder()
                .actionId(actionId)
                .ruleId(ruleId)
                .actionReferenceId(actionReferenceId)
                .userId(userId)
                .productId(productId)
                .channel(channel)
                .build());
    }

    @Transactional
    public boolean markDispatching(Long executionId, Long dispatchVersion) {
        return executionRepository.markDispatching(
                executionId, dispatchVersion,
                MarketingActionExecutionStatus.PENDING,
                MarketingActionExecutionStatus.DISPATCHING) == 1;
    }

    @Transactional
    public boolean markDispatched(Long executionId, Long dispatchVersion) {
        return executionRepository.markDispatched(
                executionId, dispatchVersion,
                MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.DISPATCHED,
                LocalDateTime.now()) == 1;
    }

    @Transactional
    public boolean recordAttempt(Long executionId, Long dispatchVersion) {
        if (executionId == null || dispatchVersion == null) {
            return false;
        }
        return executionRepository.recordAttempt(
                executionId, dispatchVersion,
                MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.DISPATCHED,
                MarketingActionExecutionStatus.PROCESSING,
                MarketingActionExecutionStatus.RETRYING) == 1;
    }

    @Transactional
    public boolean markSucceeded(Long executionId, Long dispatchVersion) {
        if (executionId == null || dispatchVersion == null) {
            return false;
        }
        return executionRepository.markSucceeded(
                executionId, dispatchVersion,
                MarketingActionExecutionStatus.PROCESSING,
                MarketingActionExecutionStatus.RETRYING,
                MarketingActionExecutionStatus.SUCCEEDED,
                LocalDateTime.now()) == 1;
    }

    @Transactional
    public boolean markDispatchFailed(Long executionId, Long dispatchVersion, String reason) {
        if (executionId == null || dispatchVersion == null) {
            return false;
        }
        return executionRepository.markDispatchFailed(
                executionId, dispatchVersion,
                MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.FAILED_FINAL,
                reason,
                LocalDateTime.now()) == 1;
    }

    @Transactional
    public boolean markDltFinal(Long executionId, Long dispatchVersion, String reason) {
        if (executionId == null || dispatchVersion == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        return executionRepository.markDltFinal(
                executionId, dispatchVersion,
                MarketingActionExecutionStatus.DISPATCHING,
                MarketingActionExecutionStatus.DISPATCHED,
                MarketingActionExecutionStatus.PROCESSING,
                MarketingActionExecutionStatus.RETRYING,
                MarketingActionExecutionStatus.FAILED_FINAL,
                reason,
                now,
                now) == 1;
    }

    @Transactional(readOnly = true)
    public List<MarketingActionExecution> findFinalFailures() {
        return executionRepository.findAllByStatusOrderByDltAtDesc(MarketingActionExecutionStatus.FAILED_FINAL);
    }

    @Transactional
    public MarketingActionExecution retryApproved(Long executionId) {
        int claimed = executionRepository.claimFinalFailureForRetry(
                executionId,
                MarketingActionExecutionStatus.FAILED_FINAL,
                MarketingActionExecutionStatus.DISPATCHING);
        if (claimed == 0) {
            MarketingActionExecution existing = executionRepository.findById(executionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Marketing action execution", executionId));
            throw new BusinessConflictException(
                    "Execution is not a final failure or is already being retried: " + existing.getStatus());
        }

        MarketingActionExecution execution = find(executionId);
        CampaignActivityKafkaProducerDto message = CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(toCampaignActivityType(execution.getChannel()))
                .userId(execution.getUserId())
                .productId(execution.getProductId())
                .marketingRuleId(execution.getRuleId())
                .marketingActionId(execution.getActionId())
                .actionReferenceId(execution.getActionReferenceId())
                .executionId(execution.getId())
                .executionDispatchVersion(execution.getDispatchVersion())
                .timestamp(System.currentTimeMillis())
                .build();

        try {
            kafkaTemplate.send(commandTopic(execution.getChannel()), message)
                    .whenComplete((result, failure) -> {
                        if (failure == null) {
                            markDispatched(executionId, execution.getDispatchVersion());
                        } else {
                            markDispatchFailed(executionId, execution.getDispatchVersion(), failure.getMessage());
                        }
                    });
            return execution;
        } catch (Exception failure) {
            markDispatchFailed(executionId, execution.getDispatchVersion(), failure.getMessage());
            throw failure;
        }
    }

    private MarketingActionExecution find(Long executionId) {
        return executionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Marketing action execution", executionId));
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
