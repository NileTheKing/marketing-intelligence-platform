package com.axon.core_service.purchase;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ShopPurchaseApplicationService {
    private final ShopPurchaseQueryService queries;
    private final ShopPurchaseConfirmationService confirmations;

    public ShopPurchaseResult confirm(Long userId, ShopPurchaseRequest request) {
        var existing = queries.findCompleted(userId, request);
        if (existing.isPresent()) return new ShopPurchaseResult(false, existing.get());
        try {
            return new ShopPurchaseResult(true, confirmations.confirm(userId, request));
        } catch (ShopPurchaseConfirmationService.RequestInsertFailure failure) {
            var completed = queries.findCompleted(userId, request);
            if (completed.isPresent()) return new ShopPurchaseResult(false, completed.get());
            throw (org.springframework.dao.DataIntegrityViolationException) failure.getCause();
        }
    }

    public ShopPurchaseResponse getPurchase(Long userId, Long purchaseId) {
        return queries.getPurchase(userId, purchaseId);
    }
}
