package com.axon.entry_service.payment.v2;

import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;

/** Uses the existing DTO serializer and topic so legacy Core consumers retain their contract. */
public final class KafkaPaymentSender implements PaymentOutboxPublisher.Sender {
    private final KafkaTemplate<String, Object> kafka;
    private final ObjectMapper mapper;
    private final Duration timeout;

    public KafkaPaymentSender(KafkaTemplate<String, Object> kafka, ObjectMapper mapper, Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.kafka = kafka;
        this.mapper = mapper;
        this.timeout = timeout;
    }

    @Override
    public void sendAndAwaitAck(UUID eventId, String payload) {
        PaymentConfirmation.requireNoTransaction();
        try {
            CampaignActivityKafkaProducerDto message = mapper.readValue(payload, CampaignActivityKafkaProducerDto.class);
            if (!eventId.toString().equals(message.getEventId())) {
                throw new IllegalStateException("outbox event ID does not match payload");
            }
            kafka.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, eventId.toString(), message)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka acknowledgement interrupted", e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid payment event payload", e);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka acknowledgement failed or timed out", e);
        }
    }
}
