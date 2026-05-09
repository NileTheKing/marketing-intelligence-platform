package com.axon.entry_service.service.Payment;

import com.axon.entry_service.dto.Payment.PaymentApprovalPayload;
import com.axon.entry_service.service.CampaignActivityProducerService;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private CampaignActivityProducerService campaignActivityProducerService;

    @InjectMocks
    private PaymentService paymentService;

    @Test
    @DisplayName("Kafka ack를 받으면 결제 이벤트 전송을 성공으로 처리해야 한다")
    void sendToKafkaWithRetry_WhenAckArrives_ReturnsTrue() {
        PaymentApprovalPayload payload = PaymentApprovalPayload.builder()
                .userId(1L)
                .campaignActivityId(2L)
                .productId(3L)
                .campaignActivityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .quantity(1)
                .reservationToken("token")
                .build();

        when(campaignActivityProducerService.send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), any(CampaignActivityKafkaProducerDto.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        boolean result = paymentService.sendToKafkaWithRetry(payload, 1);

        assertThat(result).isTrue();
        verify(campaignActivityProducerService).send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), any(CampaignActivityKafkaProducerDto.class));
    }

    @Test
    @DisplayName("Kafka future가 실패하면 결제 이벤트 전송을 실패로 처리해야 한다")
    void sendToKafkaWithRetry_WhenAckFails_ReturnsFalse() {
        PaymentApprovalPayload payload = PaymentApprovalPayload.builder()
                .userId(1L)
                .campaignActivityId(2L)
                .productId(3L)
                .campaignActivityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .quantity(1)
                .reservationToken("token")
                .build();

        CompletableFuture<SendResult<String, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("broker down"));
        when(campaignActivityProducerService.send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND), any(CampaignActivityKafkaProducerDto.class)))
                .thenReturn(failedFuture);

        boolean result = paymentService.sendToKafkaWithRetry(payload, 1);

        assertThat(result).isFalse();
    }
}
