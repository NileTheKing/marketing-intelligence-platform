package com.axon.core_service.service.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class DashboardMetricCalculator {

    public double percentage(long numerator, long denominator) {
        if (denominator == 0) {
            return 0.0;
        }
        return (numerator * 100.0) / denominator;
    }

    public BigDecimal gmv(BigDecimal price, long purchaseCount) {
        BigDecimal safePrice = price != null ? price : BigDecimal.ZERO;
        return safePrice.multiply(BigDecimal.valueOf(purchaseCount));
    }

    public BigDecimal averageOrderValue(BigDecimal gmv, long purchaseCount) {
        if (purchaseCount == 0) {
            return BigDecimal.ZERO;
        }
        return gmv.divide(BigDecimal.valueOf(purchaseCount), 2, RoundingMode.HALF_UP);
    }

    public double roas(BigDecimal gmv, BigDecimal budget) {
        if (budget == null || budget.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }
        return gmv.divide(budget, 2, RoundingMode.HALF_UP).doubleValue() * 100;
    }
}
