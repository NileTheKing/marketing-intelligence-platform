package com.axon.core_service.commandprocessing;

import com.axon.core_service.client.WebhookClient;
import com.axon.core_service.client.dto.WebhookRequest;
import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.CompletableFuture;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookStrategyTest {

    @Mock
    private WebhookClient webhookClient;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private CorePipelineMetrics pipelineMetrics;

    @Mock
    private WebhookRetryBackoff retryBackoff;

    @Test
    @DisplayName("Webhook 전송 성공 시 idempotency key를 포함해 외부 호출해야 한다")
    void processBatch_WhenWebhookSucceeds_SendsRequest() {
        WebhookStrategy strategy = strategy();
        CampaignActivityKafkaProducerDto message = message();

        strategy.processBatch(List.of(message));

        ArgumentCaptor<WebhookRequest> captor = ArgumentCaptor.forClass(WebhookRequest.class);
        verify(webhookClient).send(captor.capture());
        verify(kafkaTemplate, never()).send(anyString(), any());

        WebhookRequest request = captor.getValue();
        assertThat(request.getIdempotencyKey()).isEqualTo("webhook:10:5:99:1:100");
        assertThat(request.getRuleId()).isEqualTo(10L);
        assertThat(request.getTemplateId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("동일 웹훅 템플릿이라도 actionId가 다르면 idempotency key가 달라야 한다")
    void processBatch_SameTemplateDifferentActionId_YieldsDifferentIdempotencyKey() {
        WebhookStrategy strategy = strategy();
        CampaignActivityKafkaProducerDto messageA = message();
        CampaignActivityKafkaProducerDto messageB = CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(CampaignActivityType.WEBHOOK)
                .marketingRuleId(10L)
                .marketingActionId(6L)
                .userId(1L)
                .productId(100L)
                .actionReferenceId(99L)
                .timestamp(1234L)
                .build();

        strategy.processBatch(List.of(messageA, messageB));

        ArgumentCaptor<WebhookRequest> captor = ArgumentCaptor.forClass(WebhookRequest.class);
        verify(webhookClient, times(2)).send(captor.capture());

        List<String> keys = captor.getAllValues().stream().map(WebhookRequest::getIdempotencyKey).toList();
        assertThat(keys).containsExactlyInAnyOrder("webhook:10:5:99:1:100", "webhook:10:6:99:1:100");
    }

    @Test
    @DisplayName("Webhook 전송이 계속 실패하면 3회 재시도 후 DLT로 격리해야 한다")
    void processBatch_WhenWebhookKeepsFailing_SendsToDlt() {
        WebhookStrategy strategy = strategy();
        when(kafkaTemplate.send(eq(KafkaTopics.WEBHOOK_FAILED_DLT), any(WebhookRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        doThrow(new ResourceAccessException("timeout"))
                .when(webhookClient).send(any(WebhookRequest.class));

        strategy.processBatch(List.of(message()));

        verify(webhookClient, times(3)).send(any(WebhookRequest.class));
        verify(retryBackoff, times(2)).pauseAfterFailure(anyInt());
        verify(kafkaTemplate).send(eq(KafkaTopics.WEBHOOK_FAILED_DLT), any(WebhookRequest.class));
        verify(pipelineMetrics).recordDltRouted("webhook", 1);
    }

    @Test
    @DisplayName("재시도해도 성공할 수 없는 일반 4xx는 즉시 DLT로 격리해야 한다")
    void processBatch_WhenWebhookReturnsBadRequest_DoesNotRetry() {
        WebhookStrategy strategy = strategy();
        when(kafkaTemplate.send(eq(KafkaTopics.WEBHOOK_FAILED_DLT), any(WebhookRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        doThrow(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "bad request", null, null, null))
                .when(webhookClient).send(any(WebhookRequest.class));

        strategy.processBatch(List.of(message()));

        verify(webhookClient).send(any(WebhookRequest.class));
        verifyNoInteractions(retryBackoff);
    }

    @Test
    @DisplayName("429 응답은 일시 장애로 보고 재시도해야 한다")
    void processBatch_WhenWebhookReturnsTooManyRequests_Retries() {
        WebhookStrategy strategy = strategy();
        when(kafkaTemplate.send(eq(KafkaTopics.WEBHOOK_FAILED_DLT), any(WebhookRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        doThrow(HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "too many requests", null, null, null))
                .when(webhookClient).send(any(WebhookRequest.class));

        strategy.processBatch(List.of(message()));

        verify(webhookClient, times(3)).send(any(WebhookRequest.class));
        verify(retryBackoff, times(2)).pauseAfterFailure(anyInt());
    }

    @Test
    @DisplayName("5xx 응답 뒤 성공하면 백오프 후 재시도하고 DLT를 남기지 않아야 한다")
    void processBatch_WhenServerRecovers_RetriesAndSucceeds() {
        WebhookStrategy strategy = strategy();
        doThrow(HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "unavailable", null, null, null))
                .doNothing()
                .when(webhookClient).send(any(WebhookRequest.class));

        strategy.processBatch(List.of(message()));

        verify(webhookClient, times(2)).send(any(WebhookRequest.class));
        verify(retryBackoff).pauseAfterFailure(1);
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    @Test
    @DisplayName("Webhook DLT 발행 실패는 offset commit을 막아야 한다")
    void processBatch_WhenDltPublishFails_BlocksOffsetCommit() {
        WebhookStrategy strategy = strategy();
        when(kafkaTemplate.send(eq(KafkaTopics.WEBHOOK_FAILED_DLT), any(WebhookRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        doThrow(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "bad request", null, null, null))
                .when(webhookClient).send(any(WebhookRequest.class));

        assertThatThrownBy(() -> strategy.processBatch(List.of(message())))
                .isInstanceOf(OffsetCommitBlockedException.class);
    }

    private WebhookStrategy strategy() {
        return new WebhookStrategy(webhookClient, kafkaTemplate, pipelineMetrics, retryBackoff);
    }

    private CampaignActivityKafkaProducerDto message() {
        return CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(CampaignActivityType.WEBHOOK)
                .marketingRuleId(10L)
                .marketingActionId(5L)
                .userId(1L)
                .productId(100L)
                .actionReferenceId(99L)
                .timestamp(1234L)
                .build();
    }
}
