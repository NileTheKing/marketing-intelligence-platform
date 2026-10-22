package com.axon.core_service.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.core_service.domain.marketing.DormantCustomerCampaign;
import com.axon.core_service.domain.marketing.MarketingAction;
import com.axon.core_service.domain.marketing.MarketingActionDispatch;
import com.axon.core_service.domain.marketing.MarketingActionExecution;
import com.axon.core_service.domain.marketing.RewardType;
import java.time.LocalDateTime;
import com.axon.core_service.repository.DormantCustomerCampaignRepository;
import com.axon.core_service.repository.MarketingActionExecutionRepository;
import com.axon.core_service.repository.UserSummaryRepository;
import com.axon.core_service.service.MarketingActionExecutionService;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class DormantCustomerCampaignSchedulerTest {

    @Mock private DormantCustomerCampaignRepository campaignRepository;
    @Mock private UserSummaryRepository userSummaryRepository;
    @Mock private MarketingActionExecutionRepository executionRepository;
    @Mock private MarketingActionExecutionService executionService;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private SchedulerExecutionLock schedulerExecutionLock;
    @InjectMocks private DormantCustomerCampaignScheduler scheduler;

    @Test
    void dispatchesDormantUsersOnceThroughExistingCouponPipeline() {
        DormantCustomerCampaign campaign = campaign(7L, 20L, 3L, RewardType.COUPON);
        when(campaignRepository.findExecutableCampaigns(any(), any(), any())).thenReturn(List.of(campaign));
        when(campaignRepository.existsExecutableById(eq(7L), any(), any(), any())).thenReturn(true);
        when(userSummaryRepository.findDormantUserIdsWithoutCampaignExecution(eq(7L), any(), any(Pageable.class)))
                .thenReturn(List.of(101L, 102L), List.of());
        when(executionRepository.existsByReengagementCampaignIdAndUserId(7L, 101L)).thenReturn(false);
        when(executionRepository.existsByReengagementCampaignIdAndUserId(7L, 102L)).thenReturn(false);
        MarketingActionDispatch first = dispatch(301L, 401L);
        MarketingActionDispatch second = dispatch(302L, 402L);
        when(executionService.createPending(20L, null, 3L, 101L, null, 7L, RewardType.COUPON)).thenReturn(first);
        when(executionService.createPending(20L, null, 3L, 102L, null, 7L, RewardType.COUPON)).thenReturn(second);
        when(executionService.markDispatching(301L)).thenReturn(true);
        when(executionService.markDispatching(302L)).thenReturn(true);
        when(kafkaTemplate.send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), any(CampaignActivityKafkaProducerDto.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        scheduler.runLocked();

        verify(executionService).createPending(20L, null, 3L, 101L, null, 7L, RewardType.COUPON);
        verify(executionService).createPending(20L, null, 3L, 102L, null, 7L, RewardType.COUPON);
        verify(executionService).markDispatched(301L);
        verify(executionService).markDispatched(302L);
    }

    @Test
    void skipsUserWhoseCampaignExecutionAlreadyExists() {
        DormantCustomerCampaign campaign = existingExecutionCampaign(7L);
        when(campaignRepository.findExecutableCampaigns(any(), any(), any())).thenReturn(List.of(campaign));
        when(campaignRepository.existsExecutableById(eq(7L), any(), any(), any())).thenReturn(true);
        when(userSummaryRepository.findDormantUserIdsWithoutCampaignExecution(eq(7L), any(), any(Pageable.class)))
                .thenReturn(List.of(101L), List.of());
        when(executionRepository.existsByReengagementCampaignIdAndUserId(7L, 101L)).thenReturn(true);

        scheduler.runLocked();

        verify(executionService, never()).createPending(any(), any(), any(), any(), any(), any(), any());
        verify(kafkaTemplate, never()).send(any(String.class), any());
    }

    @Test
    void skipsKafkaPublishWhenUniqueKeyAbsorbsConcurrentDuplicate() {
        DormantCustomerCampaign campaign = campaign(7L, 20L, 3L, RewardType.COUPON);
        when(campaignRepository.findExecutableCampaigns(any(), any(), any())).thenReturn(List.of(campaign));
        when(campaignRepository.existsExecutableById(eq(7L), any(), any(), any())).thenReturn(true);
        when(userSummaryRepository.findDormantUserIdsWithoutCampaignExecution(eq(7L), any(), any(Pageable.class)))
                .thenReturn(List.of(101L), List.of());
        when(executionRepository.existsByReengagementCampaignIdAndUserId(7L, 101L)).thenReturn(false);
        when(executionService.createPending(20L, null, 3L, 101L, null, 7L, RewardType.COUPON))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        scheduler.runLocked();

        verify(kafkaTemplate, never()).send(any(String.class), any());
    }

    @Test
    void observesPauseDuringBatchAndDoesNotDispatchRemainingUsersOrSpin() {
        DormantCustomerCampaign campaign = campaign(7L, 20L, 3L, RewardType.COUPON);
        when(campaignRepository.findExecutableCampaigns(any(), any(), any())).thenReturn(List.of(campaign));
        when(campaignRepository.existsExecutableById(eq(7L), any(), any(), any()))
                .thenReturn(true, true, false);
        when(userSummaryRepository.findDormantUserIdsWithoutCampaignExecution(eq(7L), any(), any(Pageable.class)))
                .thenReturn(List.of(101L, 102L));
        when(executionRepository.existsByReengagementCampaignIdAndUserId(7L, 101L)).thenReturn(false);
        MarketingActionDispatch first = dispatch(301L, 401L);
        when(executionService.createPending(20L, null, 3L, 101L, null, 7L, RewardType.COUPON)).thenReturn(first);
        when(executionService.markDispatching(301L)).thenReturn(true);
        when(kafkaTemplate.send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), any(CampaignActivityKafkaProducerDto.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        scheduler.runLocked();

        verify(executionService).createPending(20L, null, 3L, 101L, null, 7L, RewardType.COUPON);
        verify(executionService, never()).createPending(20L, null, 3L, 102L, null, 7L, RewardType.COUPON);
        verify(userSummaryRepository).findDormantUserIdsWithoutCampaignExecution(eq(7L), any(), any(Pageable.class));
    }

    private DormantCustomerCampaign campaign(Long campaignId, Long actionId, Long referenceId, RewardType type) {
        MarketingAction action = MarketingAction.builder()
                .actionType(type).referenceId(referenceId).isActive(true).build();
        org.springframework.test.util.ReflectionTestUtils.setField(action, "id", actionId);
        DormantCustomerCampaign campaign = DormantCustomerCampaign.builder()
                .marketingAction(action).dormantDays(90).isActive(true).build();
        org.springframework.test.util.ReflectionTestUtils.setField(campaign, "id", campaignId);
        return campaign;
    }

    private DormantCustomerCampaign existingExecutionCampaign(Long campaignId) {
        return campaign(campaignId, 20L, 3L, RewardType.COUPON);
    }

    private MarketingActionDispatch dispatch(Long dispatchId, Long executionId) {
        MarketingActionDispatch dispatch = org.mockito.Mockito.mock(MarketingActionDispatch.class);
        MarketingActionExecution execution = org.mockito.Mockito.mock(MarketingActionExecution.class);
        when(dispatch.getId()).thenReturn(dispatchId);
        when(dispatch.getExecution()).thenReturn(execution);
        when(execution.getId()).thenReturn(executionId);
        return dispatch;
    }
}
