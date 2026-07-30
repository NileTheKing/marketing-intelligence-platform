package com.axon.core_service.service.purchase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.core_service.domain.dto.purchase.PurchaseInfoDto;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.messaging.topic.KafkaTopics;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class LogDeadLetterHandlerTest {

    @Test
    void recordsDltMetricOnlyAfterKafkaSendCompletes() {
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        CorePipelineMetrics pipelineMetrics = mock(CorePipelineMetrics.class);
        PurchaseInfoDto purchase = purchase();
        when(kafkaTemplate.send(KafkaTopics.PURCHASE_FAILED_DLT, purchase))
                .thenReturn(CompletableFuture.completedFuture(null));
        LogDeadLetterHandler handler = new LogDeadLetterHandler(kafkaTemplate, pipelineMetrics);

        handler.handle(purchase, new IllegalStateException("purchase failed"));

        verify(pipelineMetrics).recordDltRouted("purchase", 1);
    }

    @Test
    void propagatesDltFailureSoListenerCannotCommitOffset() {
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        CorePipelineMetrics pipelineMetrics = mock(CorePipelineMetrics.class);
        PurchaseInfoDto purchase = purchase();
        when(kafkaTemplate.send(KafkaTopics.PURCHASE_FAILED_DLT, purchase))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("dlt unavailable")));
        LogDeadLetterHandler handler = new LogDeadLetterHandler(kafkaTemplate, pipelineMetrics);

        assertThatThrownBy(() -> handler.handle(
                purchase, new IllegalStateException("purchase failed")))
                .hasRootCauseMessage("dlt unavailable");
    }

    private static PurchaseInfoDto purchase() {
        return new PurchaseInfoDto(
                1L,
                1L,
                1L,
                1L,
                Instant.now(),
                PurchaseType.CAMPAIGNACTIVITY,
                BigDecimal.TEN,
                1,
                Instant.now());
    }
}
