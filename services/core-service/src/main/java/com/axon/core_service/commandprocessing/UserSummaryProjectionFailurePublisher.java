package com.axon.core_service.commandprocessing;

import com.axon.messaging.topic.KafkaTopics;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserSummaryProjectionFailurePublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(List<UserSummaryProjectionFailedEvent.LedgerKey> targets, Exception exception) {
        String reason = exception.getMessage() == null ? "" : exception.getMessage();
        kafkaTemplate.send(KafkaTopics.USER_SUMMARY_PROJECTION_FAILED,
                        new UserSummaryProjectionFailedEvent(
                                1, targets, Instant.now(), exception.getClass().getName(),
                                reason.length() > 500 ? reason.substring(0, 500) : reason))
                .join();
    }
}
