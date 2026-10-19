package com.axon.core_service.commandprocessing;

import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.MarketingActionFailureCategory;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CampaignActivityCommandDispatcher {

    private final Map<CampaignActivityType, CampaignStrategy> strategies;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final CorePipelineMetrics pipelineMetrics;

    public CampaignActivityCommandDispatcher(
            List<CampaignStrategy> strategyList,
            KafkaTemplate<String, Object> kafkaTemplate,
            CorePipelineMetrics pipelineMetrics) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toUnmodifiableMap(CampaignStrategy::getType, Function.identity()));
        this.kafkaTemplate = kafkaTemplate;
        this.pipelineMetrics = pipelineMetrics;
    }

    public void dispatch(List<CampaignActivityKafkaProducerDto> messages) {
        if (messages.isEmpty()) {
            return;
        }

        int start = 0;
        while (start < messages.size()) {
            CampaignActivityKafkaProducerDto first = messages.get(start);
            CampaignActivityType type = first.getCampaignActivityType();
            if (type == null) {
                log.warn("Campaign activity type is missing");
                routeToDlt(List.of(first), "Campaign activity type is missing", start);
                start++;
                continue;
            }

            int end = start + 1;
            while (end < messages.size() && messages.get(end).getCampaignActivityType() == type) {
                end++;
            }
            dispatchBatch(type, messages.subList(start, end), start);
            start = end;
        }
    }

    private void dispatchBatch(CampaignActivityType type, List<CampaignActivityKafkaProducerDto> batch, int startIndex) {
        if (type == CampaignActivityType.WEBHOOK) {
            log.info("Forwarding legacy webhook commands to isolated topic: count={}", batch.size());
            for (int i = 0; i < batch.size(); i++) {
                try {
                    kafkaTemplate.send(KafkaTopics.WEBHOOK_COMMAND, batch.get(i)).join();
                } catch (RuntimeException failure) {
                    throw new BatchListenerFailedException("Webhook command forwarding failed", failure,
                            startIndex + i);
                }
            }
            return;
        }

        CampaignStrategy strategy = strategies.get(type);

        if (strategy == null) {
            log.warn("Unsupported campaign activity type: type={}, count={}", type, batch.size());
            routeToDlt(batch, "Unsupported campaign activity type: " + type, startIndex);
            return;
        }

        if (strategy instanceof BatchStrategy batchStrategy) {
            try {
                batchStrategy.processBatch(batch);
            } catch (BatchListenerFailedException failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                throw new BatchListenerFailedException(failure.getMessage(), cause,
                        startIndex + failure.getIndex());
            }
            log.info("Batch processed: type={}, count={}", type, batch.size());
            return;
        }

        for (int i = 0; i < batch.size(); i++) {
            try {
                strategy.process(batch.get(i));
                log.info("Consumed message: {}", batch.get(i));
            } catch (Exception failure) {
                Throwable cause = failure instanceof BatchListenerFailedException batchFailure
                        && batchFailure.getCause() != null
                        ? batchFailure.getCause()
                        : failure;
                throw new BatchListenerFailedException(
                        "Campaign command processing failed", cause, startIndex + i);
            }
        }
    }

    private void routeToDlt(List<CampaignActivityKafkaProducerDto> batch, String failureReason, int startIndex) {
        log.warn("Sending {} command messages to DLT: {}",
                batch.size(), KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT);
        for (int i = 0; i < batch.size(); i++) {
            CampaignActivityKafkaProducerDto message = batch.get(i);
            message.setFailureReason(failureReason);
            if (message.getFailureCategory() == null) {
                message.setFailureCategory(MarketingActionFailureCategory.UNKNOWN);
            }
            try {
                kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT, message).join();
            } catch (RuntimeException failure) {
                throw new BatchListenerFailedException("Command DLT publish failed", failure, startIndex + i);
            }
        }
        pipelineMetrics.recordDltRouted("campaign-command", batch.size());
    }
}
