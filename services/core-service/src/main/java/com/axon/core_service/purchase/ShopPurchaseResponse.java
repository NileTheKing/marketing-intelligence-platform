package com.axon.core_service.purchase;

import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseStatus;
import java.math.BigDecimal;

public record ShopPurchaseResponse(Long purchaseId, Long productId, Integer quantity,
                                   BigDecimal paidAmount, PurchaseStatus status) {
    static ShopPurchaseResponse from(Purchase purchase) {
        return new ShopPurchaseResponse(purchase.getId(), purchase.getProductId(), purchase.getQuantity(),
                purchase.getPrice(), purchase.getStatus());
    }
}
