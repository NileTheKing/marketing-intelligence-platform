package com.axon.core_service.scheduler;

import com.axon.core_service.domain.marketing.AbandonedCartCouponCandidate;
import com.axon.core_service.service.AbandonedCartCouponCandidateService;
import com.axon.messaging.topic.KafkaTopics;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AbandonedCartCouponScheduler {

    private static final int TARGET_BATCH_SIZE = 500;

    private final AbandonedCartCouponCandidateService candidateService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final SchedulerExecutionLock schedulerExecutionLock;

    @Scheduled(fixedDelayString = "${axon.marketing.abandoned-cart.fixed-delay-ms:60000}")
    public void dispatchDueCandidates() {
        schedulerExecutionLock.runIfAcquired("abandoned-cart-coupon", this::dispatchDueCandidatesLocked);
    }

    void dispatchDueCandidatesLocked() {
        List<AbandonedCartCouponCandidate> candidates;
        do {
            candidates = candidateService.findDueCandidates(LocalDateTime.now(), TARGET_BATCH_SIZE);
            List<CompletableFuture<Boolean>> publications = new ArrayList<>();
            for (AbandonedCartCouponCandidate candidate : candidates) {
                AbandonedCartCouponCandidateService.PreparedDispatch prepared = candidateService.claimAndPrepare(
                        candidate.getId(), LocalDateTime.now());
                if (prepared != null) {
                    publications.add(publish(prepared));
                }
            }

            // Keep at most one page of Kafka sends in flight before claiming the next page.
            CompletableFuture.allOf(publications.toArray(CompletableFuture[]::new)).join();
            if (publications.stream().anyMatch(publication -> !publication.join())) {
                return;
            }
        } while (!candidates.isEmpty());
    }

    private CompletableFuture<Boolean> publish(AbandonedCartCouponCandidateService.PreparedDispatch prepared) {
        try {
            return kafkaTemplate.send(KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND, prepared.command())
                    .handle((result, failure) -> {
                        if (failure == null) {
                            candidateService.markPublished(prepared);
                        } else {
                            log.warn("Could not publish abandoned-cart coupon command: candidateId={}",
                                    prepared.candidateId(), failure);
                            candidateService.releaseAfterPublishFailure(prepared, failure.getMessage());
                        }
                        return failure == null;
                    });
        } catch (RuntimeException failure) {
            log.warn("Could not start abandoned-cart coupon command publication: candidateId={}",
                    prepared.candidateId(), failure);
            candidateService.releaseAfterPublishFailure(prepared, failure.getMessage());
            return CompletableFuture.completedFuture(false);
        }
    }
}
