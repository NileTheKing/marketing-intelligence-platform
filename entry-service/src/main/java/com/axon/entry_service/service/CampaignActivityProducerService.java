package com.axon.entry_service.service;

import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

@Service
public class CampaignActivityProducerService {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public CampaignActivityProducerService(@Qualifier("transactionalKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publishes the given campaign activity message to the specified Kafka topic.
     *
     * @param topic the Kafka topic name to which the message will be sent
     * @param msg the campaign activity payload to publish
     */
    public CompletableFuture<SendResult<String, Object>> send(String topic, CampaignActivityKafkaProducerDto msg){
        return kafkaTemplate.send(topic, msg);
    }
}
