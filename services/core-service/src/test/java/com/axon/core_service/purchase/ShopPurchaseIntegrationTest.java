package com.axon.core_service.purchase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class ShopPurchaseIntegrationTest extends AbstractIntegrationTest {
    @Autowired ShopPurchaseApplicationService service;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;
    @MockitoSpyBean ShopPurchaseRequestRepository requests;
    @Autowired ProductRepository products;
    @Autowired CouponRepository coupons;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired UserCouponRepository userCoupons;
    @MockitoSpyBean PurchaseRepository purchases;
    private Product product;
    private UserCoupon userCoupon;

    @BeforeEach
    void setUp() {
        reset(purchases, requests);
        requests.deleteAll(); purchases.deleteAll(); userCoupons.deleteAll(); coupons.deleteAll(); products.deleteAll();
        product = products.save(new Product("shop", 10L, new BigDecimal("100.01"), "TECH", null, 15));
        var coupon = coupons.save(Coupon.builder().name("sale").discountRate(10).minOrderAmount(new BigDecimal("100"))
                .targetCategory("TECH").startDate(LocalDateTime.now().minusDays(1))
                .endDate(LocalDateTime.now().plusDays(1)).build());
        userCoupon = userCoupons.save(UserCoupon.builder().userId(1L).coupon(coupon).build());
    }

    @Test
    void sameKeyConcurrentRequestsConvergeAndDifferentPayloadConflicts() throws Exception {
        var request = new ShopPurchaseRequest(UUID.randomUUID(), product.getId(), userCoupon.getId());
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { gate.await(); return service.confirm(1L, request); });
            var second = executor.submit(() -> { gate.await(); return service.confirm(1L, request); });
            gate.countDown();
            var a = first.get(20, TimeUnit.SECONDS); var b = second.get(20, TimeUnit.SECONDS);
            assertThat(a.purchase()).isEqualTo(b.purchase());
            assertThat(a.created()).isNotEqualTo(b.created());
            assertThat(a.purchase().paidAmount()).isEqualByComparingTo("75.01");
        }
        assertThat(purchases.count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
        assertThat(userCoupons.findById(userCoupon.getId()).orElseThrow().getStatus()).isEqualTo(CouponStatus.USED);
        assertThatThrownBy(() -> service.confirm(1L, new ShopPurchaseRequest(request.requestId(), product.getId(), null)))
                .isInstanceOf(ShopPurchaseException.class).hasMessage("REQUEST_ID_REUSED");
        assertThatThrownBy(() -> service.getPurchase(2L, purchases.findAll().getFirst().getId()))
                .isInstanceOf(ShopPurchaseException.class).hasMessage("PURCHASE_NOT_FOUND");
    }

    @Test
    void committedRetryReturnsStoredPriceWithoutRevalidatingCouponOrProduct() {
        var request = new ShopPurchaseRequest(UUID.randomUUID(), product.getId(), userCoupon.getId());
        var original = service.confirm(1L, request);
        jdbc.update("UPDATE products SET price = ? WHERE id = ?", new BigDecimal("500.00"), product.getId());
        jdbc.update("UPDATE coupons SET end_date = ? WHERE id = ?", LocalDateTime.now().minusDays(1),
                userCoupon.getCoupon().getId());
        var retry = service.confirm(1L, request);
        assertThat(retry.created()).isFalse();
        assertThat(retry.purchase()).isEqualTo(original.purchase());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shop_purchase_requests WHERE purchase_id IS NULL", Long.class))
                .isZero();
        assertThat(purchases.count()).isEqualTo(1);
    }

    @Test
    void purchaseFailureRollsBackRequestAndCouponAndSameKeyCanRetry() {
        var request = new ShopPurchaseRequest(UUID.randomUUID(), product.getId(), userCoupon.getId());
        doThrow(new IllegalStateException("injected failure")).when(purchases).saveAndFlush(any());
        assertThatThrownBy(() -> service.confirm(1L, request)).hasMessage("injected failure");
        assertThat(requests.count()).isZero();
        assertThat(purchases.count()).isZero();
        assertThat(userCoupons.findById(userCoupon.getId()).orElseThrow().getStatus()).isEqualTo(CouponStatus.ISSUED);
        reset(purchases);
        assertThat(service.confirm(1L, request).created()).isTrue();
        assertThat(requests.count()).isEqualTo(1);
    }

    @Test
    void failureAfterRequestInsertRollsBackAndAllowsRetry() {
        var request = new ShopPurchaseRequest(UUID.randomUUID(), product.getId(), userCoupon.getId());
        doAnswer(call -> { entityManager.persist(call.getArgument(0)); entityManager.flush(); throw new IllegalStateException("after insert"); })
                .when(requests).saveAndFlush(any());
        assertThatThrownBy(() -> service.confirm(1L, request)).hasMessage("after insert");
        reset(requests);
        assertThat(requests.count()).isZero();
        assertThat(purchases.count()).isZero();
        assertThat(userCoupons.findById(userCoupon.getId()).orElseThrow().getStatus()).isEqualTo(CouponStatus.ISSUED);
        assertThat(service.confirm(1L, request).created()).isTrue();
    }

    @Test
    void failureAfterPurchaseInsertRollsBackAllThreeTables() {
        var request = new ShopPurchaseRequest(UUID.randomUUID(), product.getId(), userCoupon.getId());
        doAnswer(call -> { entityManager.persist(call.getArgument(0)); entityManager.flush(); throw new IllegalStateException("after purchase"); })
                .when(purchases).saveAndFlush(any());
        assertThatThrownBy(() -> service.confirm(1L, request)).hasMessage("after purchase");
        reset(purchases);
        assertThat(requests.count()).isZero();
        assertThat(purchases.count()).isZero();
        assertThat(userCoupons.findById(userCoupon.getId()).orElseThrow().getStatus()).isEqualTo(CouponStatus.ISSUED);
        assertThat(service.confirm(1L, request).created()).isTrue();
    }

    @Test
    void differentKeysCompeteForOneCoupon() throws Exception {
        var first = new ShopPurchaseRequest(UUID.randomUUID(), product.getId(), userCoupon.getId());
        var second = new ShopPurchaseRequest(UUID.randomUUID(), product.getId(), userCoupon.getId());
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = java.util.List.of(executor.submit(() -> attempt(gate, first)),
                    executor.submit(() -> attempt(gate, second)));
            gate.countDown();
            var outcomes = java.util.List.of(results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("OK", "COUPON_ALREADY_USED");
        }
        assertThat(purchases.count()).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
    }

    private String attempt(CountDownLatch gate, ShopPurchaseRequest request) throws InterruptedException {
        gate.await();
        try { service.confirm(1L, request); return "OK"; }
        catch (ShopPurchaseException failure) { return failure.code(); }
    }
}
