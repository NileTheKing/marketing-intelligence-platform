package com.axon.core_service.commandprocessing;

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
public class WebhookCommandConsumerService {

    private final WebhookStrategy webhookStrategy;

    @KafkaListener(topics = KafkaTopics.WEBHOOK_COMMAND, groupId = "axon-webhook-group")
    public void consume(List<CampaignActivityKafkaProducerDto> messages) {
        if (messages.isEmpty()) {
            return;
        }

        log.info("Consumed isolated webhook batch: count={}", messages.size());
        webhookStrategy.processBatch(messages);
    }
}
