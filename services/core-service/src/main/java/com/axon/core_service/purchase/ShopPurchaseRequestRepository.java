package com.axon.core_service.purchase;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopPurchaseRequestRepository extends JpaRepository<ShopPurchaseRequestRecord, Long> {
    Optional<ShopPurchaseRequestRecord> findByUserIdAndRequestId(Long userId, String requestId);
}
