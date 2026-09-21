package com.axon.core_service.commandprocessing;

import com.axon.core_service.client.WebhookClient;
import com.axon.core_service.client.dto.WebhookRequest;
import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.core_service.service.MarketingActionExecutionService;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.CompletionException;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
@Component
public class WebhookStrategy implements BatchStrategy {

    private static final int MAX_ATTEMPTS = 3;

    private final WebhookClient webhookClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final CorePipelineMetrics pipelineMetrics;
    private final WebhookRetryBackoff retryBackoff;
    private final MarketingActionExecutionService executionService;

    @Autowired
    public WebhookStrategy(WebhookClient webhookClient,
                           KafkaTemplate<String, Object> kafkaTemplate,
                           CorePipelineMetrics pipelineMetrics,
                           WebhookRetryBackoff retryBackoff,
                           MarketingActionExecutionService executionService) {
        this.webhookClient = webhookClient;
        this.kafkaTemplate = kafkaTemplate;
        this.pipelineMetrics = pipelineMetrics;
        this.retryBackoff = retryBackoff;
        this.executionService = executionService;
    }

    /** Backward-compatible constructor for focused strategy tests. */
    public WebhookStrategy(WebhookClient webhookClient,
                           KafkaTemplate<String, Object> kafkaTemplate,
                           CorePipelineMetrics pipelineMetrics,
                           WebhookRetryBackoff retryBackoff) {
        this(webhookClient, kafkaTemplate, pipelineMetrics, retryBackoff, null);
    }

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

    private void sendWithRetry(CampaignActivityKafkaProducerDto message) {
        WebhookRequest request = toRequest(message);
        Exception lastFailure = null;
        int attemptsMade = 0;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            attemptsMade = attempt;
            try {
                if (executionService != null) {
                    executionService.recordAttempt(message.getExecutionId(), message.getExecutionDispatchVersion());
                }
                webhookClient.send(request);
                log.info("Webhook sent: idempotencyKey={}, attempt={}", request.getIdempotencyKey(), attempt);
                if (executionService != null) {
                    executionService.markSucceeded(message.getExecutionId(), message.getExecutionDispatchVersion());
                }
                return;
            } catch (Exception e) {
                lastFailure = e;
                log.warn("Webhook send failed: idempotencyKey={}, attempt={}, error={}",
                        request.getIdempotencyKey(), attempt, e.getMessage());
                if (!isRetryable(e) || attempt == MAX_ATTEMPTS) {
                    break;
                }
                retryBackoff.pauseAfterFailure(attempt);
            }
        }

        log.error("Webhook permanently failed. Sending to DLT: idempotencyKey={}",
                request.getIdempotencyKey(), lastFailure);
        try {
            kafkaTemplate.send(KafkaTopics.WEBHOOK_FAILED_DLT, WebhookFailedDelivery.builder()
                    .executionId(message.getExecutionId())
                    .dispatchVersion(message.getExecutionDispatchVersion())
                    .request(request)
                    .attemptCount(attemptsMade)
                    .failureReason(lastFailure == null ? "Webhook delivery failed" : lastFailure.getMessage())
                    .build()).join();
            pipelineMetrics.recordDltRouted("webhook", 1);
        } catch (CompletionException e) {
            throw new OffsetCommitBlockedException("Webhook DLT publish failed", e);
        }
    }

    private boolean isRetryable(Exception failure) {
        if (failure instanceof ResourceAccessException || failure instanceof HttpServerErrorException) {
            return true;
        }
        return failure instanceof HttpClientErrorException clientError
                && clientError.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS;
    }

}
