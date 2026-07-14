package com.axon.core_service.service;

import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.core_service.service.batch.BatchStrategy;
import com.axon.core_service.service.strategy.CampaignStrategy;
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

        Map<CampaignActivityType, List<CampaignActivityKafkaProducerDto>> groupedByType =
                messages.stream()
                        .collect(Collectors.groupingBy(CampaignActivityKafkaProducerDto::getCampaignActivityType));

        groupedByType.forEach(this::dispatchBatch);
    }

    private void dispatchBatch(CampaignActivityType type, List<CampaignActivityKafkaProducerDto> batch) {
        CampaignStrategy strategy = strategies.get(type);

        if (strategy == null) {
            log.warn("지원하지 않는 캠페인 활동 타입입니다: {}", type);
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
        } catch (Exception e) {
            log.error("Error processing batch for type {}: {}", type, e.getMessage(), e);
            log.warn("🚨 [DLQ] Sending {} failed messages to DLT: {}", batch.size(), KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT);
            batch.forEach(msg -> kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT, msg));
            pipelineMetrics.recordDltRouted("campaign-command", batch.size());
        }
    }
}
