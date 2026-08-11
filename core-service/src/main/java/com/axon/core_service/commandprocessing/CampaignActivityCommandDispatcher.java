package com.axon.core_service.commandprocessing;

import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
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

        List<CampaignActivityKafkaProducerDto> missingType = messages.stream()
                .filter(message -> message.getCampaignActivityType() == null)
                .toList();
        if (!missingType.isEmpty()) {
            log.warn("Campaign activity type is missing: count={}", missingType.size());
            routeToDlt(missingType);
        }

        Map<CampaignActivityType, List<CampaignActivityKafkaProducerDto>> groupedByType =
                messages.stream()
                        .filter(message -> message.getCampaignActivityType() != null)
                        .collect(Collectors.groupingBy(CampaignActivityKafkaProducerDto::getCampaignActivityType));

        groupedByType.forEach(this::dispatchBatch);
    }

    private void dispatchBatch(CampaignActivityType type, List<CampaignActivityKafkaProducerDto> batch) {
        if (type == CampaignActivityType.WEBHOOK) {
            log.info("Forwarding legacy webhook commands to isolated topic: count={}", batch.size());
            batch.forEach(message -> kafkaTemplate.send(KafkaTopics.WEBHOOK_COMMAND, message).join());
            return;
        }

        CampaignStrategy strategy = strategies.get(type);

        if (strategy == null) {
            log.warn("Unsupported campaign activity type: type={}, count={}", type, batch.size());
            routeToDlt(batch);
            return;
        }

        try {
            if (strategy instanceof BatchStrategy batchStrategy) {
                batchStrategy.processBatch(batch);
                log.info("Batch processed: type={}, count={}", type, batch.size());
            } else {
                batch.forEach(msg -> {
                    strategy.process(msg);
                    log.info("Consumed message: {}", msg);
                });
            }
        } catch (OffsetCommitBlockedException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error processing batch for type {}: {}", type, e.getMessage(), e);
            routeToDlt(batch);
        }
    }

    private void routeToDlt(List<CampaignActivityKafkaProducerDto> batch) {
        log.warn("Sending {} command messages to DLT: {}",
                batch.size(), KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT);
        batch.forEach(message -> kafkaTemplate
                .send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT, message)
                .join());
        pipelineMetrics.recordDltRouted("campaign-command", batch.size());
    }
}
