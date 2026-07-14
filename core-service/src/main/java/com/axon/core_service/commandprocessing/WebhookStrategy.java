package com.axon.core_service.commandprocessing;

import com.axon.core_service.client.WebhookClient;
import com.axon.core_service.client.dto.WebhookRequest;
import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookStrategy implements BatchStrategy {

    private static final int MAX_ATTEMPTS = 3;

    private final WebhookClient webhookClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final CorePipelineMetrics pipelineMetrics;

    @Override
    public CampaignActivityType getType() {
        return CampaignActivityType.WEBHOOK;
    }

    @Override
    public void process(CampaignActivityKafkaProducerDto message) {
        processBatch(List.of(message));
    }

    @Override
    public void processBatch(List<CampaignActivityKafkaProducerDto> messages) {
        messages.stream()
                .map(this::toRequest)
                .forEach(this::sendWithRetry);
    }

    private WebhookRequest toRequest(CampaignActivityKafkaProducerDto message) {
        Long ruleId = message.getMarketingRuleId();
        Long actionId = message.getMarketingActionId();
        Long templateId = message.getActionReferenceId();
        Long userId = message.getUserId();
        Long productId = message.getProductId();

        return WebhookRequest.builder()
                .idempotencyKey("webhook:%d:%d:%d:%d:%d".formatted(ruleId, actionId, templateId, userId, productId))
                .ruleId(ruleId)
                .userId(userId)
                .productId(productId)
                .templateId(templateId)
                .eventType("MARKETING_RULE_MATCHED")
                .timestamp(message.getTimestamp() != null ? message.getTimestamp() : System.currentTimeMillis())
                .build();
    }

    private void sendWithRetry(WebhookRequest request) {
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                webhookClient.send(request);
                log.info("Webhook sent: idempotencyKey={}, attempt={}", request.getIdempotencyKey(), attempt);
                return;
            } catch (Exception e) {
                lastFailure = e;
                log.warn("Webhook send failed: idempotencyKey={}, attempt={}, error={}",
                        request.getIdempotencyKey(), attempt, e.getMessage());
            }
        }

        log.error("Webhook permanently failed. Sending to DLT: idempotencyKey={}",
                request.getIdempotencyKey(), lastFailure);
        kafkaTemplate.send(KafkaTopics.WEBHOOK_FAILED_DLT, request);
        pipelineMetrics.recordDltRouted("webhook", 1);
    }
}
