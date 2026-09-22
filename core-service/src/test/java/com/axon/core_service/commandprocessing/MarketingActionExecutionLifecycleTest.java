package com.axon.core_service.commandprocessing;

import com.axon.core_service.client.WebhookClient;
import com.axon.core_service.domain.coupon.Coupon;
import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.core_service.repository.CouponRepository;
import com.axon.core_service.repository.UserCouponRepository;
import com.axon.core_service.service.MarketingActionExecutionService;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.anyList;

@ExtendWith(MockitoExtension.class)
class MarketingActionExecutionLifecycleTest {

    @Mock private WebhookClient webhookClient;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private CorePipelineMetrics pipelineMetrics;
    @Mock private WebhookRetryBackoff retryBackoff;
    @Mock private MarketingActionExecutionService executionService;
    @Mock private UserCouponRepository userCouponRepository;
    @Mock private CouponRepository couponRepository;

    @Test
    void webhookRecoveryRecordsBothActualAttemptsAndSucceeds() {
        WebhookStrategy strategy = new WebhookStrategy(
                webhookClient, kafkaTemplate, pipelineMetrics, retryBackoff, executionService);
        CampaignActivityKafkaProducerDto message = webhookMessage();

        doThrow(new ResourceAccessException("timeout"))
                .doNothing()
                .when(webhookClient).send(any());

        strategy.processBatch(List.of(message));

        verify(executionService, org.mockito.Mockito.times(2)).recordAttempt(11L);
        verify(executionService).markSucceeded(11L);
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    @Test
    void webhookFinalFailureRecordsDltOnlyAfterDltPublish() {
        WebhookStrategy strategy = new WebhookStrategy(
                webhookClient, kafkaTemplate, pipelineMetrics, retryBackoff, executionService);
        when(kafkaTemplate.send(eq(KafkaTopics.WEBHOOK_FAILED_DLT), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        doThrow(new HttpServerErrorException(org.springframework.http.HttpStatus.BAD_GATEWAY))
                .when(webhookClient).send(any());

        strategy.processBatch(List.of(webhookMessage()));

        verify(executionService, org.mockito.Mockito.times(3)).recordAttempt(11L);
        ArgumentCaptor<Object> dltCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.WEBHOOK_FAILED_DLT), dltCaptor.capture());
        WebhookFailedDelivery envelope = (WebhookFailedDelivery) dltCaptor.getValue();
        new MarketingActionExecutionDltConsumer(executionService).consumeWebhookDlt(List.of(envelope));
        verify(executionService).markDltFinal(11L, envelope.getFailureReason());
        verify(pipelineMetrics).recordDltRouted("webhook", 1);
    }

    @Test
    void couponSuccessCompletesExecutionAfterDuplicateSafeWrite() {
        CouponStrategy strategy = new CouponStrategy(userCouponRepository, couponRepository, executionService);
        Coupon coupon = mock(Coupon.class);
        when(coupon.getId()).thenReturn(10L);
        when(couponRepository.findAllById(List.of(10L))).thenReturn(List.of(coupon));
        when(userCouponRepository.findAllByUserIdInAndCouponIdIn(List.of(1L), List.of(10L)))
                .thenReturn(List.of());

        strategy.processBatch(List.of(CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(CampaignActivityType.COUPON)
                .userId(1L)
                .actionReferenceId(10L)
                .dispatchId(11L)
                .dispatchId(11L)
                .build()));

        verify(executionService).recordAttempt(11L);
        verify(executionService).markSucceeded(11L);
    }

    @Test
    void couponSaveFailurePublishesCommandDltAndDltConsumerMarksFinalFailure() {
        Coupon coupon = mock(Coupon.class);
        when(coupon.getId()).thenReturn(10L);
        when(couponRepository.findAllById(List.of(10L))).thenReturn(List.of(coupon));
        when(userCouponRepository.findAllByUserIdInAndCouponIdIn(List.of(1L), List.of(10L)))
                .thenReturn(List.of());
        doThrow(new IllegalStateException("coupon persistence failed"))
                .when(userCouponRepository).saveAll(anyList());
        CouponStrategy couponStrategy = new CouponStrategy(
                userCouponRepository, couponRepository, executionService, kafkaTemplate);
        CampaignActivityCommandDispatcher dispatcher = new CampaignActivityCommandDispatcher(
                List.of(couponStrategy), kafkaTemplate, pipelineMetrics);
        CampaignActivityKafkaProducerDto message = CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(CampaignActivityType.COUPON)
                .userId(1L)
                .actionReferenceId(10L)
                .executionId(42L)
                .dispatchId(11L)
                .build();
        when(kafkaTemplate.send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        dispatcher.dispatch(List.of(message));

        ArgumentCaptor<CampaignActivityKafkaProducerDto> dltCaptor =
                ArgumentCaptor.forClass(CampaignActivityKafkaProducerDto.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT), dltCaptor.capture());
        CampaignActivityKafkaProducerDto dltMessage = dltCaptor.getValue();
        new MarketingActionExecutionDltConsumer(executionService)
                .consumeCampaignCommandDlt(List.of(dltMessage));
        verify(executionService).markDltFinal(11L, "coupon persistence failed");
    }

    @Test
    void invalidCouponDoesNotBecomeSucceeded() {
        when(couponRepository.findAllById(List.of(999L))).thenReturn(List.of());
        when(kafkaTemplate.send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        new CouponStrategy(userCouponRepository, couponRepository, executionService, kafkaTemplate)
                .processBatch(List.of(CampaignActivityKafkaProducerDto.builder()
                        .campaignActivityType(CampaignActivityType.COUPON)
                        .userId(1L)
                        .actionReferenceId(999L)
                        .executionId(42L)
                        .dispatchId(11L)
                        .build()));

        verify(executionService, never()).markSucceeded(any());
        verify(kafkaTemplate).send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT), any());
    }

    @Test
    void invalidUserDoesNotBecomeSucceeded() {
        Coupon coupon = mock(Coupon.class);
        when(coupon.getId()).thenReturn(10L);
        when(couponRepository.findAllById(List.of(10L))).thenReturn(List.of(coupon));
        when(kafkaTemplate.send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        new CouponStrategy(userCouponRepository, couponRepository, executionService, kafkaTemplate)
                .processBatch(List.of(CampaignActivityKafkaProducerDto.builder()
                        .campaignActivityType(CampaignActivityType.COUPON)
                        .actionReferenceId(10L)
                        .executionId(42L)
                        .dispatchId(11L)
                        .build()));

        verify(executionService, never()).markSucceeded(any());
        verify(kafkaTemplate).send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT), any());
    }

    @Test
    void dltConsumerFailureEscapesListenerBoundary() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db unavailable"))
                .when(executionService).markDltFinal(11L, "failure");
        WebhookFailedDelivery envelope = WebhookFailedDelivery.builder()
                .dispatchId(11L)
                .failureReason("failure")
                .build();

        assertThatThrownBy(() -> new MarketingActionExecutionDltConsumer(executionService)
                .consumeWebhookDlt(List.of(envelope)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db unavailable");
    }

    private CampaignActivityKafkaProducerDto webhookMessage() {
        return CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(CampaignActivityType.WEBHOOK)
                .marketingRuleId(10L)
                .marketingActionId(5L)
                .actionReferenceId(99L)
                .userId(1L)
                .productId(100L)
                .executionId(42L)
                .dispatchId(11L)
                .timestamp(1234L)
                .build();
    }
}
