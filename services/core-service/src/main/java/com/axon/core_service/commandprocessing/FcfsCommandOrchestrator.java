package com.axon.core_service.commandprocessing;

import com.axon.core_service.event.CampaignActivityApprovedEvent;
import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.core_service.service.UserSummaryService;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FcfsCommandOrchestrator {

    private final FcfsLedgerPersistenceService ledgerPersistenceService;
    private final UserSummaryService userSummaryService;
    private final UserSummaryProjectionFailurePublisher projectionFailurePublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final CorePipelineMetrics pipelineMetrics;

    public void process(List<CampaignActivityKafkaProducerDto> messages) {
        List<FcfsLedgerPersistenceService.LedgerResult> successes;
        try {
            successes = persistLedger(messages);
        } catch (Exception batchFailure) {
            if (isTransientPersistenceFailure(batchFailure)) {
                throw new BatchListenerFailedException("Transient FCFS ledger batch failure", batchFailure,
                        0);
            }
            if (messages.size() == 1) {
                throw new BatchListenerFailedException("FCFS ledger message failed", batchFailure,
                        0);
            }
            log.warn("FCFS ledger batch failed; retrying each message", batchFailure);
            pipelineMetrics.recordPurchaseIndividualRetry(messages.size());
            successes = new ArrayList<>();
            for (int i = 0; i < messages.size(); i++) {
                CampaignActivityKafkaProducerDto message = messages.get(i);
                try {
                    successes.add(persistLedger(List.of(message)).getFirst());
                } catch (Exception failure) {
                    message.setFailureReason(failure.getMessage());
                    if (message.getFailureCategory() == null) {
                        message.setFailureCategory(com.axon.messaging.MarketingActionFailureCategory.UNKNOWN);
                    }
                    updateProjection(successes);
                    publishPurchaseEvents(successes);
                    throw new BatchListenerFailedException(
                            "FCFS ledger message failed", failure, i);
                }
            }
        }
        updateProjection(successes);
        publishPurchaseEvents(successes);
    }

    private boolean isTransientPersistenceFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException
                    || cause instanceof RecoverableDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof SQLTransientException
                    || cause instanceof SQLRecoverableException) {
                return true;
            }
        }
        return false;
    }

    private void updateProjection(List<FcfsLedgerPersistenceService.LedgerResult> successes) {
        if (successes.isEmpty()) {
            return;
        }
        Map<Long, Instant> latestPurchaseTimes = successes.stream().collect(Collectors.groupingBy(
                result -> result.message().getUserId(),
                Collectors.collectingAndThen(Collectors.toList(), this::latestPurchaseTime)));
        try {
            userSummaryService.recordLatestPurchaseBatch(latestPurchaseTimes);
        } catch (Exception failure) {
            List<UserSummaryProjectionFailedEvent.LedgerKey> targets = successes.stream()
                    .map(result -> new UserSummaryProjectionFailedEvent.LedgerKey(
                            result.message().getCampaignActivityId(), result.message().getUserId()))
                    .toList();
            try {
                projectionFailurePublisher.publish(targets, failure);
            } catch (RuntimeException publishFailure) {
                throw new OffsetCommitBlockedException("projection failure publish failed", publishFailure);
            }
        }
    }

    private Instant latestPurchaseTime(List<FcfsLedgerPersistenceService.LedgerResult> results) {
        return results.stream()
                .map(FcfsLedgerPersistenceService.LedgerResult::purchasedAt)
                .max(Instant::compareTo).orElseGet(Instant::now);
    }

    private void publishPurchaseEvents(List<FcfsLedgerPersistenceService.LedgerResult> successes) {
        successes.stream().filter(FcfsLedgerPersistenceService.LedgerResult::purchaseCreated)
                .forEach(result -> {
                    CampaignActivityKafkaProducerDto message = result.message();
                    eventPublisher.publishEvent(new CampaignActivityApprovedEvent(
                            result.campaignId(), message.getCampaignActivityId(), message.getUserId(),
                            message.getProductId(), result.purchasedAt()));
                });
    }

    private List<FcfsLedgerPersistenceService.LedgerResult> persistLedger(
            List<CampaignActivityKafkaProducerDto> messages) {
        List<FcfsLedgerPersistenceService.LedgerResult> results = new ArrayList<>(messages.size());
        pipelineMetrics.recordPurchaseFlush(messages.size(), () -> results.addAll(ledgerPersistenceService.persistBatch(messages)));
        return results;
    }
}
