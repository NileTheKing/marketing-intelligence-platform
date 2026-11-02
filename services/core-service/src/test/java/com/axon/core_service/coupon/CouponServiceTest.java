package com.axon.core_service.coupon;

import com.axon.core_service.coupon.dto.CouponRequest;
import com.axon.core_service.coupon.domain.CouponStatus;
import com.axon.core_service.coupon.domain.Coupon;
import static org.assertj.core.api.Assertions.assertThat;
import com.axon.core_service.coupon.domain.UserCoupon;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponServiceTest {

    private final CouponRepository couponRepository = mock(CouponRepository.class);
    private final UserCouponRepository userCouponRepository = mock(UserCouponRepository.class);
    private final CouponService couponService = new CouponService(couponRepository, userCouponRepository);

    @Test
    void createCouponRejectsNonPositiveDiscountAmount() {
        CouponRequest request = validRequest();
        request.setDiscountAmount(BigDecimal.ZERO);

        assertThatThrownBy(() -> couponService.createCoupon(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("할인 금액");
    }

    @Test
    void createCouponRejectsDiscountRateOutsideOneToOneHundred() {
        CouponRequest request = validRequest();
        request.setDiscountAmount(null);
        request.setDiscountRate(101);

        assertThatThrownBy(() -> couponService.createCoupon(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("할인율");
    }

    @Test
    void createCouponRejectsNonPositiveValidityWindow() {
        CouponRequest request = validRequest();
        request.setEndDate(request.getStartDate());

        assertThatThrownBy(() -> couponService.createCoupon(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("종료 날짜");
    }

    @Test
    void useCouponLocksTheRowBeforeCheckingOneTimeStatus() {
        UserCoupon userCoupon = issuedCoupon(LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1), null, null);
        when(userCouponRepository.findByIdForUpdate(10L)).thenReturn(java.util.Optional.of(userCoupon));

        couponService.useCoupon(10L, 1L, BigDecimal.valueOf(10_000), "TECH");

        verify(userCouponRepository).findByIdForUpdate(10L);
        assertThat(userCoupon.getStatus()).isEqualTo(CouponStatus.USED);
    }

    @Test
    void rejectsExpiredAndFutureCouponsWithoutChangingStatus() {
        var now = LocalDateTime.now();
        assertRejected(issuedCoupon(now.minusDays(2), now.minusDays(1), null, null), 1L, "TECH", 10_000);
        assertRejected(issuedCoupon(now.plusDays(1), now.plusDays(2), null, null), 1L, "TECH", 10_000);
    }

    @Test
    void checksMinimumAmountAndCategoryAtUseAsWellAsLookup() {
        var now = LocalDateTime.now();
        var coupon = issuedCoupon(now.minusDays(1), now.plusDays(1), BigDecimal.valueOf(10_000), "TECH");
        assertRejected(coupon, 1L, "TECH", 9_999);
        assertRejected(coupon, 1L, "FOOD", 10_000);
        when(userCouponRepository.findAllByUserId(1L)).thenReturn(java.util.List.of(coupon));
        assertThat(couponService.getApplicableCoupons(1L, BigDecimal.valueOf(9_999), "TECH")).isEmpty();
        assertThat(couponService.getApplicableCoupons(1L, BigDecimal.valueOf(10_000), "tech")).hasSize(1);
        couponService.useCoupon(10L, 1L, BigDecimal.valueOf(10_000), "tech");
        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.USED);
    }

    @Test
    void rejectsAnotherUsersCouponAndRepeatedUse() {
        var now = LocalDateTime.now();
        var coupon = issuedCoupon(now.minusDays(1), now.plusDays(1), null, null);
        assertRejected(coupon, 2L, "TECH", 10_000);
        couponService.useCoupon(10L, 1L, BigDecimal.valueOf(10_000), "TECH");
        assertThatThrownBy(() -> couponService.useCoupon(10L, 1L, BigDecimal.valueOf(10_000), "TECH"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.USED);
    }

    @Test
    void couponPreviewCapsDiscountAtSellingPriceWhileUsingNormalPriceAsItsBasis() {
        var now = LocalDateTime.now();
        var definition = Coupon.builder().name("percentage").discountRate(20)
                .minOrderAmount(BigDecimal.valueOf(10_000))
                .startDate(now.minusDays(1)).endDate(now.plusDays(1)).build();
        var coupon = UserCoupon.builder().userId(1L).coupon(definition).build();
        when(userCouponRepository.findAllByUserId(1L)).thenReturn(java.util.List.of(coupon));

        var applicable = couponService.getApplicableCoupons(1L, BigDecimal.valueOf(10_000),
                "TECH", BigDecimal.valueOf(1_000));

        assertThat(applicable).hasSize(1);
        assertThat(applicable.getFirst().getCalculatedDiscount()).isEqualByComparingTo("1000");
        when(userCouponRepository.findByIdForUpdate(10L)).thenReturn(java.util.Optional.of(coupon));
        assertThat(couponService.useCoupon(10L, 1L, BigDecimal.valueOf(10_000), "TECH"))
                .isEqualByComparingTo("2000");
    }

    private void assertRejected(UserCoupon coupon, Long userId, String category, long price) {
        when(userCouponRepository.findByIdForUpdate(10L)).thenReturn(java.util.Optional.of(coupon));
        assertThatThrownBy(() -> couponService.useCoupon(10L, userId, BigDecimal.valueOf(price), category))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.ISSUED);
        assertThat(coupon.getUsedAt()).isNull();
    }

    private UserCoupon issuedCoupon(LocalDateTime start, LocalDateTime end, BigDecimal minimum, String category) {
        return UserCoupon.builder().userId(1L).coupon(Coupon.builder().name("checkout")
                .discountAmount(BigDecimal.valueOf(1_000)).minOrderAmount(minimum).targetCategory(category)
                .startDate(start).endDate(end).build()).build();
    }

    private CouponRequest validRequest() {
        CouponRequest request = new CouponRequest();
        request.setCouponName("welcome");
        request.setDiscountAmount(BigDecimal.valueOf(1000));
        request.setStartDate(LocalDateTime.of(2026, 8, 1, 0, 0));
        request.setEndDate(LocalDateTime.of(2026, 9, 1, 0, 0));
        return request;
    }
}
