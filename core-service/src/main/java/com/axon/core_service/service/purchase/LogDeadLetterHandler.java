package com.axon.core_service.service.purchase;

import com.axon.core_service.domain.dto.purchase.PurchaseInfoDto;
import com.axon.messaging.topic.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * DeadLetterHandler의 기본 구현체.
 * 최종 실패 건에 대해 상세 로그를 남겨 운영 가시성을 확보함.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogDeadLetterHandler implements DeadLetterHandler<PurchaseInfoDto> {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    public void handle(PurchaseInfoDto data, Throwable reason) {
        log.error("[DeadLetter] Purchase processing permanently failed. " +
                        "UserId: {}, ActivityId: {}, Reason: {}",
                data.userId(), data.campaignActivityId(), reason.getMessage());
        
        // [PORTFOLIO POINT] Persistent DLQ for Auditability
        try {
            kafkaTemplate.send(KafkaTopics.PURCHASE_FAILED_DLT, data);
            log.info("📢 [DLQ] Failed purchase sent to {}: userId={}", KafkaTopics.PURCHASE_FAILED_DLT, data.userId());
        } catch (Exception e) {
            log.error("❌ [DLQ] Failed to send message to DLT: {}", e.getMessage());
        }
    }
}
