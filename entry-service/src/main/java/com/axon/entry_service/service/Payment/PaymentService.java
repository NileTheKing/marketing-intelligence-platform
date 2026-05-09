package com.axon.entry_service.service.Payment;

import com.axon.entry_service.dto.Payment.PaymentApprovalPayload;
import com.axon.entry_service.service.CampaignActivityProducerService;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
    private static final long SEND_TIMEOUT_SECONDS = 5;

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
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                log.info("Kafka 전송 성공 (attempt {}): userId={}, campaignActivityId={}",
                        attempt, payload.getUserId(), payload.getCampaignActivityId());
                return true;

            } catch (Exception e) {
                log.error("Kafka 전송 실패 (attempt {}/{}): userId={}, error={}",
                        attempt, maxRetries, payload.getUserId(), e.getMessage());

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(1000L * attempt); // 지수 백오프: 1s, 2s, 3s
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
