package com.axon.core_service.commandprocessing;

import com.axon.core_service.event.CampaignActivityApprovedEvent;
import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.core_service.service.UserSummaryService;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.messaging.topic.KafkaTopics;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FcfsCommandOrchestrator {

    private final FcfsLedgerPersistenceService ledgerPersistenceService;
    private final UserSummaryService userSummaryService;
    private final UserSummaryProjectionFailurePublisher projectionFailurePublisher;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final CorePipelineMetrics pipelineMetrics;

    public void process(List<CampaignActivityKafkaProducerDto> messages) {
        List<CampaignActivityKafkaProducerDto> deduped = deduplicate(messages);
        List<FcfsLedgerPersistenceService.LedgerResult> successes;
        try {
            successes = persistLedger(deduped);
        } catch (Exception batchFailure) {
            log.warn("FCFS ledger batch failed; retrying each message", batchFailure);
            pipelineMetrics.recordPurchaseIndividualRetry(deduped.size());
            successes = deduped.stream().map(this::persistOneOrDlt).flatMap(java.util.Optional::stream).toList();
        }
        updateProjection(successes);
        publishPurchaseEvents(successes);
    }

    private java.util.Optional<FcfsLedgerPersistenceService.LedgerResult> persistOneOrDlt(
            CampaignActivityKafkaProducerDto message) {
        try {
            return java.util.Optional.of(persistLedger(List.of(message)).getFirst());
        } catch (Exception failure) {
            try {
                kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT, message).join();
            } catch (RuntimeException dltFailure) {
                throw new OffsetCommitBlockedException("command DLT publish failed", dltFailure);
            }
            pipelineMetrics.recordDltRouted("campaign-command", 1);
            return java.util.Optional.empty();
        }
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

    private List<CampaignActivityKafkaProducerDto> deduplicate(List<CampaignActivityKafkaProducerDto> messages) {
        Map<com.axon.core_service.service.ActivityUserKey, CampaignActivityKafkaProducerDto> deduped = new LinkedHashMap<>();
        for (CampaignActivityKafkaProducerDto message : messages) {
            com.axon.core_service.service.ActivityUserKey key = new com.axon.core_service.service.ActivityUserKey(
                    message.getCampaignActivityId(), message.getUserId());
            CampaignActivityKafkaProducerDto previous = deduped.get(key);
            if (previous == null || newer(message, previous)) {
                deduped.put(key, message);
            }
        }
        return List.copyOf(deduped.values());
    }

    private boolean newer(CampaignActivityKafkaProducerDto candidate, CampaignActivityKafkaProducerDto current) {
        return candidate.getTimestamp() != null
                && (current.getTimestamp() == null || candidate.getTimestamp() > current.getTimestamp());
    }
}
