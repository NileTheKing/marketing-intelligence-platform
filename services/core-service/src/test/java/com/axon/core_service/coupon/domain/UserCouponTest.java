package com.axon.core_service.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class UserCouponTest {

    @Test
    void issuedCouponCanBeUsed() {
        UserCoupon coupon = UserCoupon.builder().userId(1L).build();

        coupon.use();

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.USED);
        assertThat(coupon.getUsedAt()).isNotNull();
    }

    @Test
    void repeatedUsePreservesOriginalUsageTime() {
        UserCoupon coupon = UserCoupon.builder().userId(1L).build();
        coupon.use();
        var originalUsedAt = coupon.getUsedAt();

        assertThatThrownBy(coupon::use).isInstanceOf(IllegalStateException.class);

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.USED);
        assertThat(coupon.getUsedAt()).isEqualTo(originalUsedAt);
    }

    @Test
    void expiredCouponCannotBeUsed() {
        UserCoupon coupon = UserCoupon.builder().userId(1L).build();
        ReflectionTestUtils.setField(coupon, "status", CouponStatus.EXPIRED);

        assertThatThrownBy(coupon::use).isInstanceOf(IllegalStateException.class);

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.EXPIRED);
        assertThat(coupon.getUsedAt()).isNull();
    }
}
