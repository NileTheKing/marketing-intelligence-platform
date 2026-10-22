package com.axon.core_service.service;

import com.axon.core_service.domain.dto.coupon.CouponRequest;
import com.axon.core_service.domain.coupon.CouponStatus;
import com.axon.core_service.domain.coupon.UserCoupon;
import com.axon.core_service.repository.CouponRepository;
import com.axon.core_service.repository.UserCouponRepository;
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
        UserCoupon userCoupon = mock(UserCoupon.class);
        when(userCouponRepository.findByIdForUpdate(10L)).thenReturn(java.util.Optional.of(userCoupon));
        when(userCoupon.getUserId()).thenReturn(1L);
        when(userCoupon.getStatus()).thenReturn(CouponStatus.ISSUED);

        couponService.useCoupon(10L, 1L);

        verify(userCouponRepository).findByIdForUpdate(10L);
        verify(userCoupon).use();
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
