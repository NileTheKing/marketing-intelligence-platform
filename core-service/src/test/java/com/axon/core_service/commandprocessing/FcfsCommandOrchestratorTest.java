package com.axon.core_service.commandprocessing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.core_service.event.CampaignActivityApprovedEvent;
import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.core_service.service.UserSummaryService;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;

class FcfsCommandOrchestratorTest {

    private final FcfsLedgerPersistenceService ledger = Mockito.mock(FcfsLedgerPersistenceService.class);
    private final UserSummaryService summaries = Mockito.mock(UserSummaryService.class);
    private final UserSummaryProjectionFailurePublisher projectionFailures = Mockito.mock(UserSummaryProjectionFailurePublisher.class);
    private final KafkaTemplate<String, Object> kafka = Mockito.mock(KafkaTemplate.class);
    private final ApplicationEventPublisher events = Mockito.mock(ApplicationEventPublisher.class);
    private final CorePipelineMetrics metrics = Mockito.mock(CorePipelineMetrics.class);
    private final FcfsCommandOrchestrator orchestrator = new FcfsCommandOrchestrator(
            ledger, summaries, projectionFailures, kafka, events, metrics);

    @BeforeEach
    void executeMeasuredLedgerFlush() {
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(metrics).recordPurchaseFlush(org.mockito.ArgumentMatchers.anyInt(), any(Runnable.class));
    }

    @Test
    void isolatesOnlyPoisonMessageAfterBatchFailure() {
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
        when(kafka.send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        orchestrator.process(messages);

        ArgumentCaptor<java.util.Map<Long, Instant>> summaryCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(summaries).recordLatestPurchaseBatch(summaryCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(summaryCaptor.getValue()).hasSize(19);
        verify(kafka).send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT, messages.get(19));
        verify(events, org.mockito.Mockito.times(19)).publishEvent(any(CampaignActivityApprovedEvent.class));
    }

    @Test
    void commandDltFailurePropagates() {
        CampaignActivityKafkaProducerDto message = message(1);
        when(ledger.persistBatch(any())).thenThrow(new IllegalArgumentException("poison"));
        when(kafka.send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT), eq(message)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("unavailable")));

        assertThatThrownBy(() -> orchestrator.process(List.of(message)))
                .isInstanceOf(OffsetCommitBlockedException.class);
    }

    @Test
    void projectionFailureIsRecordedWithoutCommandDlt() {
        CampaignActivityKafkaProducerDto message = message(1);
        when(ledger.persistBatch(any())).thenReturn(List.of(result(message, true)));
        doThrow(new IllegalStateException("summary unavailable"))
                .when(summaries).recordLatestPurchaseBatch(any());

        orchestrator.process(List.of(message));

        verify(projectionFailures).publish(any(), any());
        verify(kafka, never()).send(eq(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT), any());
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

    @Test
    void existingPurchaseRedeliveryDoesNotPublishBehaviorEvent() {
        CampaignActivityKafkaProducerDto message = message(1);
        when(ledger.persistBatch(any())).thenReturn(List.of(result(message, false)));

        orchestrator.process(List.of(message));

        verify(events, never()).publishEvent(any(CampaignActivityApprovedEvent.class));
    }

    @Test
    void newPurchaseBehaviorEventIsPublishedOnlyAfterLedgerMethodReturns() {
        CampaignActivityKafkaProducerDto message = message(1);
        when(ledger.persistBatch(any())).thenReturn(List.of(result(message, true)));

        orchestrator.process(List.of(message));

        org.mockito.InOrder inOrder = Mockito.inOrder(ledger, events);
        inOrder.verify(ledger).persistBatch(List.of(message));
        inOrder.verify(events).publishEvent(any(CampaignActivityApprovedEvent.class));
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
