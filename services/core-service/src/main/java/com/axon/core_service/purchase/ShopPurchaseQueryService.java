package com.axon.core_service.purchase;

import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.repository.PurchaseRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShopPurchaseQueryService {
    private final ShopPurchaseRequestRepository requests;
    private final PurchaseRepository purchases;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<ShopPurchaseResponse> findCompleted(Long userId, ShopPurchaseRequest request) {
        return requests.findByUserIdAndRequestId(userId, request.requestId().toString()).map(record -> {
            if (!record.matches(request)) throw new ShopPurchaseException(HttpStatus.CONFLICT, "REQUEST_ID_REUSED");
            if (record.purchaseId() == null) throw new IllegalStateException("Incomplete shop purchase request");
            return getPurchase(userId, record.purchaseId());
        });
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public ShopPurchaseResponse getPurchase(Long userId, Long purchaseId) {
        var purchase = purchases.findById(purchaseId)
                .filter(p -> p.getUserId().equals(userId) && p.getPurchaseType() == PurchaseType.SHOP)
                .orElseThrow(() -> new ShopPurchaseException(HttpStatus.NOT_FOUND, "PURCHASE_NOT_FOUND"));
        return ShopPurchaseResponse.from(purchase);
    }
}
