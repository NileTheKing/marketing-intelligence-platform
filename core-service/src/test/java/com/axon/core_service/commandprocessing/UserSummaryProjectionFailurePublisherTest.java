package com.axon.core_service.commandprocessing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.messaging.topic.KafkaTopics;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

class UserSummaryProjectionFailurePublisherTest {

    @Test
    void publishesProjectionFailureSynchronously() {
        KafkaTemplate<String, Object> kafka = Mockito.mock(KafkaTemplate.class);
        when(kafka.send(eq(KafkaTopics.USER_SUMMARY_PROJECTION_FAILED), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        UserSummaryProjectionFailurePublisher publisher = new UserSummaryProjectionFailurePublisher(kafka);

        publisher.publish(List.of(new UserSummaryProjectionFailedEvent.LedgerKey(1L, 2L)),
                new IllegalStateException("summary failed"));

        verify(kafka).send(eq(KafkaTopics.USER_SUMMARY_PROJECTION_FAILED), any(UserSummaryProjectionFailedEvent.class));
    }
}
