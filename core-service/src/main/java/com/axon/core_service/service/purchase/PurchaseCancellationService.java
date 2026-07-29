package com.axon.core_service.service.purchase;

import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.service.UserSummaryService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal command invoked only after the payment provider has completed its
 * cancellation or refund. Entry admission state is intentionally not reopened.
 */
@Service
@RequiredArgsConstructor
public class PurchaseCancellationService {

    private final PurchaseRepository purchaseRepository;
    private final UserSummaryService userSummaryService;

    @Transactional
    public boolean cancelConfirmedPurchase(Long purchaseId, String reason, Instant occurredAt) {
        Purchase purchase = purchaseRepository.findById(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Purchase not found: " + purchaseId));
        boolean changed = purchase.cancel(reason, occurredAt);
        if (changed) {
            userSummaryService.rebuildPurchaseSummary(purchase.getUserId());
        }
        return changed;
    }

    @Transactional
    public boolean refundConfirmedPurchase(Long purchaseId, String reason, Instant occurredAt) {
        Purchase purchase = purchaseRepository.findById(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Purchase not found: " + purchaseId));
        boolean changed = purchase.refund(reason, occurredAt);
        if (changed) {
            userSummaryService.rebuildPurchaseSummary(purchase.getUserId());
        }
        return changed;
    }
}
