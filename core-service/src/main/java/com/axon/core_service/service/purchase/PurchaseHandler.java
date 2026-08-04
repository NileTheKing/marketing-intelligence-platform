package com.axon.core_service.service.purchase;
import com.axon.core_service.domain.dto.purchase.PurchaseInfoDto;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.service.ProductService;
import com.axon.core_service.service.UserSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;


@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseHandler {
    private final ProductService productService;
    private final UserSummaryService userSummaryService;
    private final PurchaseService purchaseService;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handle(PurchaseInfoDto info) {
        if (info.purchaseType() == PurchaseType.SHOP) {
            log.debug("[Purchase] Processing SHOP purchase immediately: userId={}, productId={}", info.userId(), info.productId());
            processImmediate(info);
        }
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
}
