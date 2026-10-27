package com.axon.core_service.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.axon.core_service.AbstractIntegrationTest;
import com.axon.core_service.catalog.ProductRepository;
import com.axon.core_service.catalog.domain.Product;
import com.axon.core_service.coupon.CouponRepository;
import com.axon.core_service.coupon.UserCouponRepository;
import com.axon.core_service.coupon.domain.Coupon;
import com.axon.core_service.coupon.domain.CouponStatus;
import com.axon.core_service.coupon.domain.UserCoupon;
import com.axon.core_service.repository.PurchaseRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class ShopPurchaseIntegrationTest extends AbstractIntegrationTest {
    @Autowired private ShopPurchaseService shopPurchaseService;
    @Autowired private ProductRepository products;
    @Autowired private CouponRepository coupons;
    @Autowired private UserCouponRepository userCoupons;
    @MockitoSpyBean private PurchaseRepository purchases;

    private Product product;
    private Coupon coupon;
    private UserCoupon userCoupon;

    @BeforeEach
    void setUp() {
        reset(purchases);
        product = products.save(new Product("coupon checkout", 10L, BigDecimal.valueOf(10_000), "TECH"));
        coupon = coupons.save(Coupon.builder().name("checkout discount")
                .discountAmount(BigDecimal.valueOf(1_000)).minOrderAmount(BigDecimal.valueOf(10_000))
                .targetCategory("TECH").startDate(LocalDateTime.now().minusDays(1))
                .endDate(LocalDateTime.now().plusDays(1)).build());
        userCoupon = userCoupons.save(UserCoupon.builder().userId(1L).coupon(coupon).build());
    }

    @AfterEach
    void cleanUp() {
        reset(purchases);
        purchases.deleteAll(purchases.findByUserId(1L).stream()
                .filter(p -> product.getId().equals(p.getProductId())).toList());
        userCoupons.deleteById(userCoupon.getId());
        coupons.deleteById(coupon.getId());
        products.deleteById(product.getId());
    }

    @Test
    void purchaseFailureRollsBackCouponUseAndAllowsRetry() {
        doThrow(new IllegalStateException("purchase write failed")).when(purchases).save(any());
        assertThatThrownBy(this::checkout).isInstanceOf(IllegalStateException.class);
        assertThat(userCoupons.findById(userCoupon.getId()).orElseThrow().getStatus())
                .isEqualTo(CouponStatus.ISSUED);
        reset(purchases);
        checkout();
        assertThat(userCoupons.findById(userCoupon.getId()).orElseThrow().getStatus())
                .isEqualTo(CouponStatus.USED);
        assertThat(purchases.findByUserId(1L).stream()
                .filter(p -> userCoupon.getId().equals(p.getUserCouponId()))).hasSize(1);
    }

    @Test
    void concurrentCheckoutWithSameCouponCommitsOnlyOnePurchase() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> attemptCheckout(start));
            var second = executor.submit(() -> attemptCheckout(start));
            start.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(userCoupons.findById(userCoupon.getId()).orElseThrow().getStatus())
                .isEqualTo(CouponStatus.USED);
        assertThat(purchases.findByUserId(1L).stream()
                .filter(p -> userCoupon.getId().equals(p.getUserCouponId()))).hasSize(1);
    }

    private boolean attemptCheckout(CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            checkout();
            return true;
        } catch (IllegalStateException alreadyUsed) {
            assertThat(alreadyUsed).hasMessageContaining("Coupon already used");
            return false;
        }
    }

    private void checkout() {
        shopPurchaseService.recordPaymentSuccess(1L, product.getId(), userCoupon.getId(), BigDecimal.valueOf(9_000));
    }
}
