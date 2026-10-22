package com.axon.entry_service.service.payment;

import com.axon.entry_service.dto.payment.PaymentApprovalPayload;
import com.axon.entry_service.service.CampaignActivityProducerService;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
    private static final long BROKER_ACK_TIMEOUT_SECONDS = 5;
    private static final long RETRY_BACKOFF_MILLIS = 1000;

    private final CampaignActivityProducerService campaignActivityProducerService;

    public boolean sendToKafkaWithRetry(PaymentApprovalPayload payload, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                CampaignActivityKafkaProducerDto message = CampaignActivityKafkaProducerDto.builder()
                        .userId(payload.getUserId())
                        .campaignActivityId(payload.getCampaignActivityId())
                        .productId(payload.getProductId())
                        .campaignActivityType(payload.getCampaignActivityType())
                        .quantity(payload.getQuantity() != null ? payload.getQuantity().longValue() : 1L)
                        .timestamp(Instant.now().toEpochMilli())
                        .build();

                campaignActivityProducerService.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, message)
                        .get(BROKER_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                log.info("Kafka 전송 성공 (attempt {}): userId={}, campaignActivityId={}",
                        attempt, payload.getUserId(), payload.getCampaignActivityId());
                return true;

            } catch (Exception e) {
                log.error("Kafka 전송 실패 (attempt {}/{}): userId={}, error={}",
                        attempt, maxRetries, payload.getUserId(), e.getMessage());

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(RETRY_BACKOFF_MILLIS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }

        log.error("Kafka 전송 최종 실패: userId={}, campaignActivityId={}", payload.getUserId(), payload.getCampaignActivityId());
        return false;
    }
}
