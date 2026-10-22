package com.axon.core_service.domain.dto.dashboard;

import java.math.BigDecimal;

/** MySQL-confirmed commercial aggregate for one dashboard scope. */
public record PurchaseAggregate(long purchaseCount, BigDecimal gmv) {

    public static PurchaseAggregate empty() {
        return new PurchaseAggregate(0, BigDecimal.ZERO);
    }
}
