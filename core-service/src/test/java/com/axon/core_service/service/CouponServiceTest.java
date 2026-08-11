package com.axon.core_service.service;

import com.axon.core_service.domain.dto.coupon.CouponRequest;
import com.axon.core_service.repository.CouponRepository;
import com.axon.core_service.repository.UserCouponRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CouponServiceTest {

    private final CouponService couponService = new CouponService(
            mock(CouponRepository.class), mock(UserCouponRepository.class));

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

    private CouponRequest validRequest() {
        CouponRequest request = new CouponRequest();
        request.setCouponName("welcome");
        request.setDiscountAmount(BigDecimal.valueOf(1000));
        request.setStartDate(LocalDateTime.of(2026, 8, 1, 0, 0));
        request.setEndDate(LocalDateTime.of(2026, 9, 1, 0, 0));
        return request;
    }
}
