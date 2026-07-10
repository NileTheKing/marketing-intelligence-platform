package com.axon.core_service.service.strategy;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    @DisplayName("Webhook 전송 성공 시 idempotency key를 포함해 외부 호출해야 한다")
    void processBatch_WhenWebhookSucceeds_SendsRequest() {
        WebhookStrategy strategy = new WebhookStrategy(webhookClient, kafkaTemplate, pipelineMetrics);
        CampaignActivityKafkaProducerDto message = message();

        strategy.processBatch(List.of(message));

        ArgumentCaptor<WebhookRequest> captor = ArgumentCaptor.forClass(WebhookRequest.class);
        verify(webhookClient).send(captor.capture());
        verify(kafkaTemplate, never()).send(anyString(), any());

        WebhookRequest request = captor.getValue();
        assertThat(request.getIdempotencyKey()).isEqualTo("webhook:10:99:1:100");
        assertThat(request.getRuleId()).isEqualTo(10L);
        assertThat(request.getTemplateId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("Webhook 전송이 계속 실패하면 3회 재시도 후 DLT로 격리해야 한다")
    void processBatch_WhenWebhookKeepsFailing_SendsToDlt() {
        WebhookStrategy strategy = new WebhookStrategy(webhookClient, kafkaTemplate, pipelineMetrics);
        doThrow(new RuntimeException("timeout"))
                .when(webhookClient).send(any(WebhookRequest.class));

        strategy.processBatch(List.of(message()));

        verify(webhookClient, times(3)).send(any(WebhookRequest.class));
        verify(kafkaTemplate).send(eq(KafkaTopics.WEBHOOK_FAILED_DLT), any(WebhookRequest.class));
        verify(pipelineMetrics).recordDltRouted("webhook", 1);
    }

    private CampaignActivityKafkaProducerDto message() {
        return CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(CampaignActivityType.WEBHOOK)
                .campaignActivityId(10L)
                .userId(1L)
                .productId(100L)
                .couponId(99L)
                .timestamp(1234L)
                .build();
    }
}
