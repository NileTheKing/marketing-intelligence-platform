package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import com.axon.core_service.coupon.domain.Coupon;
import com.axon.core_service.coupon.domain.UserCoupon;
import com.axon.core_service.domain.dto.customer.CustomerBehaviorEvent;
import com.axon.core_service.domain.dto.customer.CustomerProfileResponse;
import com.axon.core_service.domain.product.Product;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.domain.purchase.PurchaseType;
import com.axon.core_service.domain.user.User;
import com.axon.core_service.exception.ResourceNotFoundException;
import com.axon.core_service.repository.ProductRepository;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.coupon.UserCouponRepository;
import com.axon.core_service.repository.UserRepository;
import com.axon.core_service.repository.UserSummaryRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CustomerProfileServiceTest {

    private static final Long USER_ID = 42L;

    private UserRepository userRepository;
    private UserSummaryRepository userSummaryRepository;
    private PurchaseRepository purchaseRepository;
    private UserCouponRepository userCouponRepository;
    private ProductRepository productRepository;
    private BehaviorEventService behaviorEventService;
    private CustomerProfileService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userSummaryRepository = mock(UserSummaryRepository.class);
        purchaseRepository = mock(PurchaseRepository.class);
        userCouponRepository = mock(UserCouponRepository.class);
        productRepository = mock(ProductRepository.class);
        behaviorEventService = mock(BehaviorEventService.class);
        service = new CustomerProfileService(userRepository, userSummaryRepository, purchaseRepository,
                userCouponRepository, productRepository, behaviorEventService);

        User user = User.builder().name("김고객").email("customer@example.com").build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userSummaryRepository.findById(USER_ID)).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("확정 구매만 매출에 합산하고 최근 구매에 상품명과 사용 쿠폰을 붙인다")
    void aggregatesConfirmedPurchasesAndCoupons() throws IOException {
        Coupon coupon = Coupon.builder().name("장바구니 10% 쿠폰").build();
        UserCoupon used = UserCoupon.builder().userId(USER_ID).coupon(coupon).build();
        ReflectionTestUtils.setField(used, "id", 7L);
        used.use();
        UserCoupon unused = UserCoupon.builder().userId(USER_ID).coupon(coupon).build();
        ReflectionTestUtils.setField(unused, "id", 8L);
        when(userCouponRepository.findAllByUserId(USER_ID)).thenReturn(List.of(used, unused));

        Purchase older = purchase(10L, "10000", 2, Instant.parse("2026-11-20T01:00:00Z"), null);
        Purchase newer = purchase(11L, "30000", 1, Instant.parse("2026-11-28T11:05:00Z"), 7L);
        Purchase cancelled = purchase(12L, "50000", 1, Instant.parse("2026-11-25T01:00:00Z"), null);
        cancelled.cancel("고객 요청", Instant.parse("2026-11-26T01:00:00Z"));
        when(purchaseRepository.findByUserId(USER_ID)).thenReturn(List.of(older, newer, cancelled));

        Product watch = new Product("스마트워치 울트라", 10L, new BigDecimal("30000"), "ELECTRONICS");
        ReflectionTestUtils.setField(watch, "id", 100L);
        when(productRepository.findAllById(anyList())).thenReturn(List.of(watch));
        when(behaviorEventService.getRecentEventsByUser(eq(USER_ID), anyInt())).thenReturn(List.of());

        CustomerProfileResponse profile = service.getProfile(USER_ID);

        assertThat(profile.confirmedPurchaseCount()).isEqualTo(2);
        assertThat(profile.confirmedRevenue()).isEqualByComparingTo("50000");
        assertThat(profile.issuedCouponCount()).isEqualTo(2);
        assertThat(profile.usedCouponCount()).isEqualTo(1);
        assertThat(profile.recentPurchases()).extracting(CustomerProfileResponse.PurchaseItem::purchaseId)
                .containsExactly(11L, 12L, 10L);
        CustomerProfileResponse.PurchaseItem latest = profile.recentPurchases().get(0);
        assertThat(latest.productName()).isEqualTo("스마트워치 울트라");
        assertThat(latest.couponName()).isEqualTo("장바구니 10% 쿠폰");
    }

    @Test
    @DisplayName("Elasticsearch 조회가 실패해도 프로필은 빈 타임라인으로 응답한다")
    void elasticsearchFailureLeavesTimelineEmpty() throws IOException {
        when(purchaseRepository.findByUserId(USER_ID)).thenReturn(List.of());
        when(userCouponRepository.findAllByUserId(USER_ID)).thenReturn(List.of());
        when(productRepository.findAllById(anyList())).thenReturn(List.of());
        when(behaviorEventService.getRecentEventsByUser(eq(USER_ID), anyInt()))
                .thenThrow(new IOException("es down"));

        CustomerProfileResponse profile = service.getProfile(USER_ID);

        assertThat(profile.name()).isEqualTo("김고객");
        assertThat(profile.recentEvents()).isEmpty();
    }

    @Test
    @DisplayName("Elasticsearch 서버 측 오류도 프로필은 빈 타임라인으로 응답한다")
    void elasticsearchServerErrorLeavesTimelineEmpty() throws IOException {
        when(purchaseRepository.findByUserId(USER_ID)).thenReturn(List.of());
        when(userCouponRepository.findAllByUserId(USER_ID)).thenReturn(List.of());
        when(productRepository.findAllById(anyList())).thenReturn(List.of());
        when(behaviorEventService.getRecentEventsByUser(eq(USER_ID), anyInt()))
                .thenThrow(new ElasticsearchException("es/search",
                        ErrorResponse.of(r -> r.status(400).error(e -> e.type("search_phase_execution_exception")))));

        assertThat(service.getProfile(USER_ID).recentEvents()).isEmpty();
    }

    @Test
    @DisplayName("없는 사용자는 ResourceNotFoundException")
    void missingUserIsNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProfile(99L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("저장된 occurredAt은 epoch 초 숫자와 ISO 문자열을 모두 읽는다")
    void storedEventParsesBothOccurredAtFormats() {
        CustomerBehaviorEvent fromSeconds = new BehaviorEventService.StoredBehaviorEvent(
                "PAGE_VIEW", "PAGE_VIEW", 1_795_000_000.5, "/products/1", null).toCustomerEvent();
        CustomerBehaviorEvent fromText = new BehaviorEventService.StoredBehaviorEvent(
                "CLICK", "CLICK", "2026-11-28T11:00:00Z", "/products/1", Map.of("productId", 1)).toCustomerEvent();

        assertThat(fromSeconds.occurredAt()).isEqualTo(Instant.ofEpochMilli(1_795_000_000_500L));
        assertThat(fromSeconds.properties()).isEmpty();
        assertThat(fromText.occurredAt()).isEqualTo(Instant.parse("2026-11-28T11:00:00Z"));
    }

    private static Purchase purchase(Long id, String price, int quantity, Instant at, Long userCouponId) {
        Purchase purchase = Purchase.builder()
                .userId(USER_ID)
                .productId(100L)
                .campaignActivityId(1L)
                .purchaseType(PurchaseType.CAMPAIGNACTIVITY)
                .price(new BigDecimal(price))
                .quantity(quantity)
                .purchasedAt(at)
                .userCouponId(userCouponId)
                .build();
        ReflectionTestUtils.setField(purchase, "id", id);
        return purchase;
    }
}
