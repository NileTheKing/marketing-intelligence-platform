package com.axon.core_service.scheduler;

import com.axon.core_service.domain.marketing.DormantCustomerCampaign;
import com.axon.core_service.domain.marketing.MarketingActionDispatch;
import com.axon.core_service.domain.marketing.RewardType;
import com.axon.core_service.campaign.domain.CouponDeliveryMode;
import com.axon.core_service.campaign.dto.CampaignActivityStatus;
import com.axon.core_service.repository.DormantCustomerCampaignRepository;
import com.axon.core_service.repository.MarketingActionExecutionRepository;
import com.axon.core_service.repository.UserSummaryRepository;
import com.axon.core_service.service.MarketingActionExecutionService;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DormantCustomerCampaignScheduler {

    private static final int TARGET_BATCH_SIZE = 500;

    private final DormantCustomerCampaignRepository campaignRepository;
    private final UserSummaryRepository userSummaryRepository;
    private final MarketingActionExecutionRepository executionRepository;
    private final MarketingActionExecutionService executionService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final SchedulerExecutionLock schedulerExecutionLock;

    @Scheduled(cron = "0 0 10 * * *")
    public void runDormantCustomerCampaigns() {
        schedulerExecutionLock.runIfAcquired("dormant-customer-campaign", this::runLocked);
    }

    void runLocked() {
        LocalDateTime now = LocalDateTime.now();
        for (DormantCustomerCampaign campaign : campaignRepository.findExecutableCampaigns(
                CampaignActivityStatus.ACTIVE, CouponDeliveryMode.DORMANT_CUSTOMER, now)) {
            dispatchCampaign(campaign, now);
        }
    }

    private void dispatchCampaign(DormantCustomerCampaign campaign, LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(campaign.getDormantDays());
        List<Long> userIds;
        do {
            if (!isExecutable(campaign, LocalDateTime.now())) {
                break;
            }
            userIds = userSummaryRepository.findDormantUserIdsWithoutCampaignExecution(
                    campaign.getId(), cutoff, PageRequest.of(0, TARGET_BATCH_SIZE));
            for (Long userId : userIds) {
                if (!dispatch(campaign, userId, LocalDateTime.now())) {
                    return;
                }
            }
        } while (!userIds.isEmpty());
    }

    private boolean dispatch(DormantCustomerCampaign campaign, Long userId, LocalDateTime now) {
        if (!isExecutable(campaign, now)) {
            return false;
        }
        if (executionRepository.existsByReengagementCampaignIdAndUserId(campaign.getId(), userId)) {
            return true;
        }

        var action = campaign.getMarketingAction();
        MarketingActionDispatch dispatch;
        try {
            dispatch = executionService.createPending(
                    action.getId(), null, action.getReferenceId(), userId, null, campaign.getId(), action.getActionType());
        } catch (DataIntegrityViolationException ignored) {
            log.debug("Skipping duplicate dormant campaign execution: campaignId={}, userId={}", campaign.getId(), userId);
            return true;
        }
        if (!executionService.markDispatching(dispatch.getId())) {
            return true;
        }

        CampaignActivityKafkaProducerDto message = CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(toCampaignActivityType(action.getActionType()))
                .userId(userId)
                .marketingActionId(action.getId())
                .actionReferenceId(action.getReferenceId())
                .executionId(dispatch.getExecution().getId())
                .dispatchId(dispatch.getId())
                .timestamp(System.currentTimeMillis())
                .build();
        kafkaTemplate.send(commandTopic(action.getActionType()), message)
                .whenComplete((result, failure) -> {
                    if (failure == null) {
                        executionService.markDispatched(dispatch.getId());
                    } else {
                        executionService.markDispatchFailed(dispatch.getId(), failure.getMessage());
                    }
                });
        return true;
    }

    private boolean isExecutable(DormantCustomerCampaign config, LocalDateTime now) {
        return campaignRepository.existsExecutableById(config.getId(), CampaignActivityStatus.ACTIVE,
                CouponDeliveryMode.DORMANT_CUSTOMER, now);
    }

    private CampaignActivityType toCampaignActivityType(RewardType actionType) {
        return actionType == RewardType.WEBHOOK ? CampaignActivityType.WEBHOOK : CampaignActivityType.COUPON;
    }

    private String commandTopic(RewardType actionType) {
        return actionType == RewardType.WEBHOOK ? KafkaTopics.WEBHOOK_COMMAND : KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND;
    }
}
