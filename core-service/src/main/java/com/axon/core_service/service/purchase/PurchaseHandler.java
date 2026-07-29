package com.axon.core_service.service.purchase;
import com.axon.core_service.domain.dto.purchase.PurchaseInfoDto;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.event.CampaignActivityApprovedEvent;
import com.axon.core_service.observability.CorePipelineMetrics;
import com.axon.core_service.service.ProductService;
import com.axon.core_service.service.UserSummaryService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseHandler {
    private static final int batchSize = 20;
    private final ProductService productService;
    private final UserSummaryService userSummaryService;
    private final PurchaseService purchaseService;
    private final ApplicationEventPublisher eventPublisher;
    private final DeadLetterHandler<PurchaseInfoDto> deadLetterHandler;
    private final CorePipelineMetrics pipelineMetrics;

    // Purchase 이벤트 버퍼
    private final ConcurrentLinkedQueue<PurchaseInfoDto> purchaseBuffer = new ConcurrentLinkedQueue<>();

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handle(PurchaseInfoDto info) {
        if (info.purchaseType() == PurchaseType.SHOP) {
            log.debug("[Purchase] Processing SHOP purchase immediately: userId={}, productId={}", info.userId(), info.productId());
            processImmediate(info);
        } else {
            purchaseBuffer.offer(info);
            log.debug("[Purchase] Event buffered. Buffer size: {}", purchaseBuffer.size());

            // 수신 스레드는 메모리에 넣고 즉시 반환 - DB 처리는 스케줄러가 전담
        }
    }

    /**
     * 일반 쇼핑몰 구매(SHOP) 등 즉시 처리가 필요한 경우 호출
     */
    private void processImmediate(PurchaseInfoDto info) {
        // 1. 실시간 재고 감소 (단건 처리)
        productService.decreaseStock(info.productId(), info.quantity());

        // 2. 실시간 유저 요약 업데이트 (단건 처리)
        userSummaryService.recordPurchase(info.userId(), info.occurredAt());

        // 3. Purchase 기록 저장 (단건 처리)
        purchaseService.createPurchase(info);

        log.info("Successfully processed immediate purchase for user {}", info.userId());
    }

    /**
     * 100ms마다 자동으로 버퍼 플러시
     */
    @Scheduled(fixedDelay = 100)
    public void scheduledFlush() {

        // 단일 처리가 아닌, 큐가 빌 때까지 50개씩 계속 퍼나르도록 while 루프 적용 (책임의 완벽한 분리)
        while (!purchaseBuffer.isEmpty()) {
            flushBatch();
        }
    }

    /** 버퍼의 Purchase 이벤트를 배치 처리한다. */
    public synchronized void flushBatch() {
        if (purchaseBuffer.isEmpty()) {
            return;
        }

        // 1. 버퍼에서 Purchase 추출 (최대 20개)
        List<PurchaseInfoDto> purchases = drainBuffer();

        if (purchases.isEmpty()) {
            return;
        }

        log.info("Processing Purchase batch: {} purchases", purchases.size());
        pipelineMetrics.recordPurchaseFlush(purchases.size(), () -> processBatch(purchases));
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
     * 버퍼에서 Purchase 추출
     */
    private List<PurchaseInfoDto> drainBuffer() {
        List<PurchaseInfoDto> drained = new ArrayList<>(batchSize);

        for (int i = 0; i < batchSize; i++) {
            PurchaseInfoDto purchase = purchaseBuffer.poll();
            if (purchase == null) {
                break;
            }
            drained.add(purchase);
        }

        return drained;
    }

    public int bufferedPurchaseCount() {
        return purchaseBuffer.size();
    }

    /**
     * 서비스 종료 시 남은 Purchase 처리
     */
    @PreDestroy
    public void onShutdown() {
        log.info("Shutting down PurchaseHandler, flushing remaining purchases...");
        flushBatch();
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
