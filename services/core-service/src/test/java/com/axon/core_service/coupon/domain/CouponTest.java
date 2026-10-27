package com.axon.core_service.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axon.core_service.exception.InvalidRequestException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CouponTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime END = START.plusDays(1);

    @Test
    void validityIncludesStartAndEnd() {
        Coupon coupon = coupon().build();

        assertThat(coupon.isApplicable(BigDecimal.TEN, "TECH", START.minusNanos(1))).isFalse();
        assertThat(coupon.isApplicable(BigDecimal.TEN, "TECH", START)).isTrue();
        assertThat(coupon.isApplicable(BigDecimal.TEN, "TECH", END)).isTrue();
        assertThat(coupon.isApplicable(BigDecimal.TEN, "TECH", END.plusNanos(1))).isFalse();
    }

    @Test
    void minimumAmountIncludesExactThreshold() {
        Coupon coupon = coupon().minOrderAmount(new BigDecimal("10000")).build();

        assertThat(coupon.isApplicable(new BigDecimal("9999"), "TECH", START)).isFalse();
        assertThat(coupon.isApplicable(new BigDecimal("10000"), "TECH", START)).isTrue();
    }

    @Test
    void categoryRestrictionIgnoresCaseAndRejectsMissingCategory() {
        Coupon coupon = coupon().targetCategory("TECH").build();

        assertThat(coupon.isApplicable(BigDecimal.TEN, "tech", START)).isTrue();
        assertThat(coupon.isApplicable(BigDecimal.TEN, "FOOD", START)).isFalse();
        assertThat(coupon.isApplicable(BigDecimal.TEN, null, START)).isFalse();
    }

    @Test
    void absentCategoryRestrictionAcceptsAnyCategory() {
        assertThat(coupon().build().isApplicable(BigDecimal.TEN, null, START)).isTrue();
        assertThat(coupon().targetCategory("").build()
                .isApplicable(BigDecimal.TEN, "FOOD", START)).isTrue();
    }

    @Test
    void fixedDiscountCannotExceedProductPrice() {
        Coupon coupon = coupon().discountRate(null).discountAmount(new BigDecimal("1000")).build();

        assertThat(coupon.calculateDiscount(new BigDecimal("10000"))).isEqualByComparingTo("1000");
        assertThat(coupon.calculateDiscount(new BigDecimal("500"))).isEqualByComparingTo("500");
    }

    @Test
    void percentageDiscountRoundsDownToWholeWon() {
        Coupon coupon = coupon().discountRate(15).build();

        assertThat(coupon.calculateDiscount(new BigDecimal("9999"))).isEqualByComparingTo("1499");
    }

    @ParameterizedTest
    @MethodSource("invalidDiscounts")
    void rejectsInvalidDiscountWhenConstructedDirectly(BigDecimal amount, Integer rate) {
        assertThatThrownBy(() -> coupon().discountAmount(amount).discountRate(rate).build())
                .isInstanceOf(InvalidRequestException.class);
    }

    private static Stream<Arguments> invalidDiscounts() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of(BigDecimal.ONE, 10),
                Arguments.of(BigDecimal.ZERO, null),
                Arguments.of(BigDecimal.ONE.negate(), null),
                Arguments.of(null, 0),
                Arguments.of(null, 101));
    }

    @Test
    void rejectsMissingNameNegativeMinimumAndInvalidValidity() {
        assertThatThrownBy(() -> coupon().name(" ").build()).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> coupon().minOrderAmount(BigDecimal.ONE.negate()).build())
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> coupon().startDate(null).build()).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> coupon().endDate(START).build()).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> coupon().endDate(START.minusDays(1)).build())
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectedUpdateLeavesAllOriginalValuesIntact() {
        Coupon coupon = coupon().minOrderAmount(BigDecimal.TEN).targetCategory("TECH").build();

        assertThatThrownBy(() -> coupon.update("changed", BigDecimal.ONE, null,
                BigDecimal.ZERO, "FOOD", END, START)).isInstanceOf(InvalidRequestException.class);

        assertThat(coupon.getCouponName()).isEqualTo("checkout");
        assertThat(coupon.getDiscountAmount()).isNull();
        assertThat(coupon.getDiscountRate()).isEqualTo(10);
        assertThat(coupon.getMinOrderAmount()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(coupon.getTargetCategory()).isEqualTo("TECH");
        assertThat(coupon.getStartDate()).isEqualTo(START);
        assertThat(coupon.getEndDate()).isEqualTo(END);
    }

    @Test
    void acceptsValidUpdateToFixedDiscount() {
        Coupon coupon = coupon().build();

        coupon.update("fixed", BigDecimal.ONE, null, BigDecimal.ZERO, "TECH", START, END);

        assertThat(coupon.getCouponName()).isEqualTo("fixed");
        assertThat(coupon.getDiscountRate()).isNull();
        assertThat(coupon.calculateDiscount(BigDecimal.TEN)).isEqualByComparingTo(BigDecimal.ONE);
    }

    private Coupon.CouponBuilder coupon() {
        return Coupon.builder().name("checkout").discountRate(10).startDate(START).endDate(END);
    }
}
