package com.axon.core_service.purchase;

import com.axon.core_service.catalog.ProductRepository;
import com.axon.core_service.coupon.CouponService;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.repository.PurchaseRepository;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShopPurchaseService {
    private final ProductRepository productRepository;
    private final CouponService couponService;
    private final PurchaseRepository purchaseRepository;

    @Transactional
    public void recordPaymentSuccess(Long userId, Long productId, Long userCouponId, BigDecimal finalPrice) {
        if (userId != null && productId != null && finalPrice != null) {
            var product = productRepository.findByIdAndCampaignOnlyFalse(productId)
                    .orElseThrow(() -> new IllegalArgumentException("Campaign-only products cannot use shop checkout"));
            if (userCouponId != null) {
                couponService.useCoupon(userCouponId, userId, product.getPrice(), product.getCategory());
                log.info("User coupon {} successfully used by user {} during payment", userCouponId, userId);
            }
            Purchase purchase = Purchase.builder()
                    .userId(userId)
                    .productId(productId)
                    .campaignActivityId(null)
                    .purchaseType(PurchaseType.SHOP)
                    .price(finalPrice)
                    .quantity(1)
                    .purchasedAt(Instant.now())
                    .userCouponId(userCouponId)
                    .build();
            purchaseRepository.save(purchase);
            log.info("Purchase saved: userId={}, productId={}, price={}", userId, productId, finalPrice);
        }
    }

}
