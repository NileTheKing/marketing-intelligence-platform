package com.axon.core_service.domain.dto.customer;

import com.axon.core_service.domain.user.RfmSegment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One customer's summary, purchases, coupons and recent behavior for the admin profile screen.
 * Behavior covers logged-in events only; anonymous session history is not merged.
 */
public record CustomerProfileResponse(
        Long userId,
        String name,
        String email,
        String grade,
        RfmSegment rfmSegment,
        LocalDateTime lastPurchaseAt,
        LocalDateTime lastLoginAt,
        long confirmedPurchaseCount,
        BigDecimal confirmedRevenue,
        long issuedCouponCount,
        long usedCouponCount,
        List<PurchaseItem> recentPurchases,
        List<CustomerBehaviorEvent> recentEvents
) {
    public record PurchaseItem(
            Long purchaseId,
            String productName,
            BigDecimal amount,
            String status,
            String couponName,
            LocalDateTime purchasedAt
    ) {
    }
}
