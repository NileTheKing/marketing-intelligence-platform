package com.axon.core_service.commandprocessing;

import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignActivityConsumerService {

    private final CampaignActivityCommandDispatcher dispatcher;
    private final CorePipelineMetrics pipelineMetrics;

    @KafkaListener(topics = KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, groupId = "axon-group")
    public void consume(List<CampaignActivityKafkaProducerDto> messages) {
        if (messages.isEmpty()) {
            return;
        }

        log.info("📥 [Kafka] Consumed batch: count={}", messages.size());
        pipelineMetrics.recordCommandFlush(messages.size(), () -> dispatcher.dispatch(messages));
    }

}
