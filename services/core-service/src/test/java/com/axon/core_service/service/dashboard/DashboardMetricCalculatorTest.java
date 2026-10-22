package com.axon.core_service.service.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DashboardMetricCalculatorTest {

    private final DashboardMetricCalculator calculator = new DashboardMetricCalculator();

    @Test
    void percentageReturnsZeroWhenDenominatorIsZero() {
        assertThat(calculator.percentage(10, 0)).isZero();
    }

    @Test
    void percentageCalculatesPercentValue() {
        assertThat(calculator.percentage(25, 100)).isEqualTo(25.0);
    }

    @Test
    void averageOrderValueReturnsZeroWhenPurchaseCountIsZero() {
        assertThat(calculator.averageOrderValue(BigDecimal.valueOf(10000), 0))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void roasReturnsZeroWhenBudgetIsZero() {
        assertThat(calculator.roas(BigDecimal.valueOf(10000), BigDecimal.ZERO)).isZero();
    }
}
