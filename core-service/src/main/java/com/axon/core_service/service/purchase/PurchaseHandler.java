package com.axon.core_service.service.purchase;
import com.axon.core_service.domain.dto.purchase.PurchaseInfoDto;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.event.CampaignActivityApprovedEvent;
import com.axon.core_service.event.PurchaseBatchRequestedEvent;
import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.core_service.service.ProductService;
import com.axon.core_service.service.UserSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseHandler {
    private final ProductService productService;
    private final UserSummaryService userSummaryService;
    private final PurchaseService purchaseService;
    private final ApplicationEventPublisher eventPublisher;
    private final DeadLetterHandler<PurchaseInfoDto> deadLetterHandler;
    private final CorePipelineMetrics pipelineMetrics;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handle(PurchaseInfoDto info) {
        if (info.purchaseType() == PurchaseType.SHOP) {
            log.debug("[Purchase] Processing SHOP purchase immediately: userId={}, productId={}", info.userId(), info.productId());
            processImmediate(info);
        } else {
            pipelineMetrics.recordPurchaseFlush(1, () -> processBatch(List.of(info)));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handleBatch(PurchaseBatchRequestedEvent event) {
        if (event.purchases().isEmpty()) {
            return;
        }
        pipelineMetrics.recordPurchaseFlush(event.purchases().size(), () -> processBatch(event.purchases()));
    }

    private void processImmediate(PurchaseInfoDto info) {
        // 1. 실시간 재고 감소 (단건 처리)
        productService.decreaseStock(info.productId(), info.quantity());

        // 2. 실시간 유저 요약 업데이트 (단건 처리)
        userSummaryService.recordPurchase(info.userId(), info.occurredAt());

        // 3. Purchase 기록 저장 (단건 처리)
        purchaseService.createPurchase(info);

        log.info("Successfully processed immediate purchase for user {}", info.userId());
    }

    private void processBatch(List<PurchaseInfoDto> purchases) {
        List<PurchaseInfoDto> persistedPurchases = persistPurchases(purchases);
        if (persistedPurchases.isEmpty()) {
            return;
        }

        updateUserSummaries(persistedPurchases);
        publishApprovedEvents(persistedPurchases);
    }

    private List<PurchaseInfoDto> persistPurchases(List<PurchaseInfoDto> purchases) {
        try {
            purchaseService.createPurchaseBatch(purchases);
            log.info("Created {} purchase records", purchases.size());
            return purchases;
        } catch (org.springframework.dao.DataIntegrityViolationException | org.springframework.transaction.UnexpectedRollbackException e) {
            log.warn("Batch failed due to transaction rollback (likely duplicate). Marking for individual retry... Error: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Error processing purchase batch. Falling back to individual retry for {} purchases", purchases.size(), e);
        }

        return retryIndividually(purchases);
    }

    private void updateUserSummaries(List<PurchaseInfoDto> purchases) {
        Map<Long, PurchaseSummary> userSummaries = purchases.stream()
                .collect(Collectors.groupingBy(
                        PurchaseInfoDto::userId,
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                list -> new PurchaseSummary(
                                        list.size(),
                                        list.stream()
                                                .map(p -> p.price().multiply(BigDecimal.valueOf(p.quantity())))
                                                .reduce(BigDecimal.ZERO, BigDecimal::add),
                                        list.getFirst().occurredAt()
                                )
                        )
                ));

        try {
            userSummaryService.recordPurchaseBatch(userSummaries);
        } catch (Exception e) {
            // Purchase is the durable fact; do not replay it when its projection update fails.
            log.error("Purchase records persisted but UserSummary projection update failed for {} users", userSummaries.size(), e);
        }
    }

    private void publishApprovedEvents(List<PurchaseInfoDto> purchases) {
        List<CampaignActivityApprovedEvent> events = purchases.stream()
                .filter(p -> p.campaignActivityId() != null)
                .map(p -> new CampaignActivityApprovedEvent(
                        p.campaignId(),
                        p.campaignActivityId(),
                        p.userId(),
                        p.productId(),
                        p.occurredAt()
                ))
                .toList();

        events.forEach(eventPublisher::publishEvent);
        log.info("Published {} campaign approval events", events.size());
    }

    private List<PurchaseInfoDto> retryIndividually(List<PurchaseInfoDto> purchases) {
        pipelineMetrics.recordPurchaseIndividualRetry(purchases.size());
        List<PurchaseInfoDto> persistedPurchases = new ArrayList<>();
        for (PurchaseInfoDto purchase : purchases) {
            try {
                purchaseService.createPurchaseBatch(List.of(purchase));
                persistedPurchases.add(purchase);
            } catch (Exception e) {
                deadLetterHandler.handle(purchase, e);
            }
        }
        return persistedPurchases;
    }

    /**
     * 유저 구매 요약 (내부 사용)
     */
    public record PurchaseSummary(
            int purchaseCount,
            BigDecimal totalAmount,
            Instant lastPurchaseTime
    ) {}
}
