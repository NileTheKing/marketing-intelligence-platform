package com.axon.core_service.commandprocessing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.core_service.service.UserSummaryService;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.listener.BatchListenerFailedException;

class FcfsCommandOrchestratorTest {

    private final FcfsLedgerPersistenceService ledger = Mockito.mock(FcfsLedgerPersistenceService.class);
    private final UserSummaryService summaries = Mockito.mock(UserSummaryService.class);
    private final UserSummaryProjectionFailurePublisher projectionFailures = Mockito.mock(UserSummaryProjectionFailurePublisher.class);
    private final ApplicationEventPublisher eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
    private final CorePipelineMetrics metrics = Mockito.mock(CorePipelineMetrics.class);
    private final FcfsCommandOrchestrator orchestrator = new FcfsCommandOrchestrator(
            ledger, summaries, projectionFailures, eventPublisher, metrics);

    @BeforeEach
    void executeMeasuredLedgerFlush() {
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(metrics).recordPurchaseFlush(org.mockito.ArgumentMatchers.anyInt(), any(Runnable.class));
    }

    @Test
    void reportsPoisonMessageIndexAfterBatchFailure() {
        List<CampaignActivityKafkaProducerDto> messages = java.util.stream.IntStream.range(0, 20)
                .mapToObj(this::message).toList();
        when(ledger.persistBatch(any())).thenAnswer(invocation -> {
            List<CampaignActivityKafkaProducerDto> batch = invocation.getArgument(0, List.class);
            if (batch.size() == 20) {
                throw new IllegalStateException("batch failure");
            }
            CampaignActivityKafkaProducerDto message = batch.getFirst();
            if (message.getUserId().equals(19L)) {
                throw new IllegalArgumentException("poison");
            }
            return List.of(result(message, true));
        });
        assertThatThrownBy(() -> orchestrator.process(messages))
                .isInstanceOf(BatchListenerFailedException.class)
                .satisfies(failure -> org.assertj.core.api.Assertions.assertThat(
                        ((BatchListenerFailedException) failure).getIndex()).isEqualTo(19));

        ArgumentCaptor<java.util.Map<Long, Instant>> summaryCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(summaries).recordLatestPurchaseBatch(summaryCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(summaryCaptor.getValue()).hasSize(19);
    }

    @Test
    void reportsTransientFailureForSpringKafkaRetry() {
        CampaignActivityKafkaProducerDto first = message(1);
        CampaignActivityKafkaProducerDto second = message(2);
        when(ledger.persistBatch(any())).thenAnswer(invocation -> {
            throw new TransientDataAccessResourceException("database temporarily unavailable");
        });

        assertThatThrownBy(() -> orchestrator.process(List.of(first, second)))
                .isInstanceOf(BatchListenerFailedException.class)
                .satisfies(failure -> org.assertj.core.api.Assertions.assertThat(
                        ((BatchListenerFailedException) failure).getIndex()).isZero());

        verify(ledger, org.mockito.Mockito.times(1)).persistBatch(any());
    }

    @Test
    void singletonFailureIsReportedWithoutAnImmediateSecondAttempt() {
        CampaignActivityKafkaProducerDto message = message(1);
        when(ledger.persistBatch(any())).thenThrow(new IllegalArgumentException("poison"));

        assertThatThrownBy(() -> orchestrator.process(List.of(message)))
                .isInstanceOf(BatchListenerFailedException.class)
                .satisfies(failure -> org.assertj.core.api.Assertions.assertThat(
                        ((BatchListenerFailedException) failure).getIndex()).isZero());

        verify(ledger, org.mockito.Mockito.times(1)).persistBatch(any());
    }

    @Test
    void retriesDuplicatesInInputOrderAndReportsFirstUnprocessedIndex() {
        CampaignActivityKafkaProducerDto older = message(1);
        CampaignActivityKafkaProducerDto middle = message(2);
        CampaignActivityKafkaProducerDto newer = message(1);
        older.setTimestamp(100L);
        newer.setTimestamp(200L);
        when(ledger.persistBatch(any())).thenAnswer(invocation -> {
            List<CampaignActivityKafkaProducerDto> batch = invocation.getArgument(0);
            if (batch.size() > 1) throw new IllegalStateException("batch failure");
            if (batch.getFirst() == middle) throw new IllegalArgumentException("poison");
            return List.of(result(batch.getFirst(), true));
        });

        assertThatThrownBy(() -> orchestrator.process(List.of(older, middle, newer)))
                .isInstanceOf(BatchListenerFailedException.class)
                .satisfies(failure -> org.assertj.core.api.Assertions.assertThat(
                        ((BatchListenerFailedException) failure).getIndex()).isEqualTo(1));

        var calls = org.mockito.Mockito.inOrder(ledger);
        calls.verify(ledger).persistBatch(List.of(older, middle, newer));
        calls.verify(ledger).persistBatch(List.of(older));
        calls.verify(ledger).persistBatch(List.of(middle));
        calls.verifyNoMoreInteractions();
        verify(eventPublisher).publishEvent(any(com.axon.core_service.event.CampaignActivityApprovedEvent.class));
    }

    @Test
    void transientFailureDuringIndividualRetryReportsItsOriginalIndex() {
        var first = message(1);
        var middle = message(2);
        var duplicate = message(1);
        first.setTimestamp(100L);
        duplicate.setTimestamp(200L);
        when(ledger.persistBatch(any())).thenAnswer(invocation -> {
            List<CampaignActivityKafkaProducerDto> batch = invocation.getArgument(0);
            if (batch.size() > 1) throw new IllegalStateException("batch failure");
            if (batch.getFirst() == middle) throw new TransientDataAccessResourceException("db unavailable");
            return List.of(result(batch.getFirst(), true));
        });
        assertThatThrownBy(() -> orchestrator.process(List.of(first, middle, duplicate)))
                .isInstanceOf(BatchListenerFailedException.class)
                .satisfies(failure -> org.assertj.core.api.Assertions.assertThat(
                        ((BatchListenerFailedException) failure).getIndex()).isEqualTo(1));
        verify(ledger, org.mockito.Mockito.never()).persistBatch(List.of(duplicate));
    }

    @Test
    void projectionFailureIsRecordedWithoutCommandDlt() {
        CampaignActivityKafkaProducerDto message = message(1);
        when(ledger.persistBatch(any())).thenReturn(List.of(result(message, true)));
        doThrow(new IllegalStateException("summary unavailable"))
                .when(summaries).recordLatestPurchaseBatch(any());

        orchestrator.process(List.of(message));

        verify(projectionFailures).publish(any(), any());
    }

    @Test
    void projectionFailurePublishFailurePropagates() {
        CampaignActivityKafkaProducerDto message = message(1);
        when(ledger.persistBatch(any())).thenReturn(List.of(result(message, true)));
        doThrow(new IllegalStateException("summary unavailable"))
                .when(summaries).recordLatestPurchaseBatch(any());
        doThrow(new IllegalStateException("topic unavailable"))
                .when(projectionFailures).publish(any(), any());

        assertThatThrownBy(() -> orchestrator.process(List.of(message)))
                .isInstanceOf(OffsetCommitBlockedException.class);
    }

    private CampaignActivityKafkaProducerDto message(int index) {
        return CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .campaignActivityId(1L).userId((long) index).productId(1L)
                .timestamp(Instant.now().toEpochMilli()).build();
    }

    private FcfsLedgerPersistenceService.LedgerResult result(
            CampaignActivityKafkaProducerDto message, boolean purchaseCreated) {
        return new FcfsLedgerPersistenceService.LedgerResult(message, 1L, purchaseCreated, Instant.now());
    }
}
