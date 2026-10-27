package com.axon.core_service.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axon.core_service.domain.marketing.AbandonedCartCouponCandidate;
import com.axon.core_service.service.AbandonedCartCouponCandidateService;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AbandonedCartCouponSchedulerTest {

    @Mock private AbandonedCartCouponCandidateService candidateService;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private SchedulerExecutionLock schedulerExecutionLock;

    @Test
    void publishesPreparedCandidatesThroughExistingCouponCommandTopic() {
        AbandonedCartCouponCandidate candidate = org.mockito.Mockito.mock(AbandonedCartCouponCandidate.class);
        when(candidate.getId()).thenReturn(11L);
        CampaignActivityKafkaProducerDto command = CampaignActivityKafkaProducerDto.builder().userId(7L).build();
        var prepared = new AbandonedCartCouponCandidateService.PreparedDispatch(11L, 21L, 31L, command);
        when(candidateService.findDueCandidates(any(LocalDateTime.class), eq(500)))
                .thenReturn(List.of(candidate), List.of());
        when(candidateService.claimAndPrepare(eq(11L), any(LocalDateTime.class))).thenReturn(prepared);
        when(kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, command))
                .thenReturn(CompletableFuture.completedFuture(null));

        scheduler().dispatchDueCandidatesLocked();

        verify(candidateService).markPublished(prepared);
    }

    @Test
    void releasesCandidateWhenKafkaDoesNotAcceptTheCommand() {
        AbandonedCartCouponCandidate candidate = org.mockito.Mockito.mock(AbandonedCartCouponCandidate.class);
        when(candidate.getId()).thenReturn(12L);
        CampaignActivityKafkaProducerDto command = CampaignActivityKafkaProducerDto.builder().userId(8L).build();
        var prepared = new AbandonedCartCouponCandidateService.PreparedDispatch(12L, 22L, 32L, command);
        when(candidateService.findDueCandidates(any(LocalDateTime.class), eq(500)))
                .thenReturn(List.of(candidate), List.of());
        when(candidateService.claimAndPrepare(eq(12L), any(LocalDateTime.class))).thenReturn(prepared);
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, command)).thenReturn(failed);

        scheduler().dispatchDueCandidatesLocked();

        verify(candidateService).releaseAfterPublishFailure(eq(prepared), eq("broker unavailable"));
    }

    @Test
    void drainsFollowingPagesWithoutWaitingForTheNextScheduledRun() {
        AbandonedCartCouponCandidate first = candidate(11L);
        AbandonedCartCouponCandidate second = candidate(12L);
        var firstPrepared = prepared(11L, 21L, 31L, 7L);
        var secondPrepared = prepared(12L, 22L, 32L, 8L);
        when(candidateService.findDueCandidates(any(LocalDateTime.class), eq(500)))
                .thenReturn(List.of(first), List.of(second), List.of());
        when(candidateService.claimAndPrepare(eq(11L), any(LocalDateTime.class))).thenReturn(firstPrepared);
        when(candidateService.claimAndPrepare(eq(12L), any(LocalDateTime.class))).thenReturn(secondPrepared);
        when(kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, firstPrepared.command()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, secondPrepared.command()))
                .thenReturn(CompletableFuture.completedFuture(null));

        scheduler().dispatchDueCandidatesLocked();

        verify(candidateService).markPublished(firstPrepared);
        verify(candidateService).markPublished(secondPrepared);
    }

    @Test
    void releasesCandidateWhenKafkaSendThrowsBeforeReturningAFuture() {
        AbandonedCartCouponCandidate candidate = candidate(13L);
        var prepared = prepared(13L, 23L, 33L, 9L);
        when(candidateService.findDueCandidates(any(LocalDateTime.class), eq(500)))
                .thenReturn(List.of(candidate), List.of());
        when(candidateService.claimAndPrepare(eq(13L), any(LocalDateTime.class))).thenReturn(prepared);
        when(kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, prepared.command()))
                .thenThrow(new IllegalStateException("producer unavailable"));

        scheduler().dispatchDueCandidatesLocked();

        verify(candidateService).releaseAfterPublishFailure(eq(prepared), eq("producer unavailable"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void failedPageDrainsOtherSendsAndRetriesOnNextRun(boolean immediateFailure) throws Exception {
        var failed = candidate(41L);
        var successful = candidate(42L);
        var failedPrepared = prepared(41L, 51L, 61L, 71L);
        var successfulPrepared = prepared(42L, 52L, 62L, 72L);
        when(candidateService.findDueCandidates(any(LocalDateTime.class), eq(500)))
                .thenReturn(List.of(failed, successful), List.of(failed), List.of());
        when(candidateService.claimAndPrepare(eq(41L), any(LocalDateTime.class))).thenReturn(failedPrepared);
        when(candidateService.claimAndPrepare(eq(42L), any(LocalDateTime.class))).thenReturn(successfulPrepared);
        if (immediateFailure) {
            when(kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, failedPrepared.command()))
                    .thenThrow(new IllegalStateException("broker unavailable"))
                    .thenReturn(CompletableFuture.completedFuture(null));
        } else {
            when(kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, failedPrepared.command()))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")),
                            CompletableFuture.completedFuture(null));
        }
        var pendingSuccess = new CompletableFuture<SendResult<String, Object>>();
        var sent = new java.util.concurrent.CountDownLatch(1);
        when(kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, successfulPrepared.command()))
                .thenAnswer(invocation -> { sent.countDown(); return pendingSuccess; });
        var scheduler = scheduler();
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var run = executor.submit(scheduler::dispatchDueCandidatesLocked);
            org.assertj.core.api.Assertions.assertThat(sent.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            org.assertj.core.api.Assertions.assertThat(run.isDone()).isFalse();
            pendingSuccess.complete(null);
            run.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        verify(candidateService).releaseAfterPublishFailure(failedPrepared, "broker unavailable");
        verify(candidateService).markPublished(successfulPrepared);
        verify(candidateService, org.mockito.Mockito.times(1)).findDueCandidates(any(LocalDateTime.class), eq(500));

        scheduler.dispatchDueCandidatesLocked();

        verify(candidateService).markPublished(failedPrepared);
        verify(candidateService, org.mockito.Mockito.times(3)).findDueCandidates(any(LocalDateTime.class), eq(500));
    }

    private AbandonedCartCouponCandidate candidate(Long id) {
        AbandonedCartCouponCandidate candidate = org.mockito.Mockito.mock(AbandonedCartCouponCandidate.class);
        when(candidate.getId()).thenReturn(id);
        return candidate;
    }

    private AbandonedCartCouponCandidateService.PreparedDispatch prepared(
            Long candidateId, Long executionId, Long dispatchId, Long userId) {
        CampaignActivityKafkaProducerDto command = CampaignActivityKafkaProducerDto.builder().userId(userId).build();
        return new AbandonedCartCouponCandidateService.PreparedDispatch(candidateId, executionId, dispatchId, command);
    }

    private AbandonedCartCouponScheduler scheduler() {
        return new AbandonedCartCouponScheduler(candidateService, kafkaTemplate, schedulerExecutionLock);
    }
}
