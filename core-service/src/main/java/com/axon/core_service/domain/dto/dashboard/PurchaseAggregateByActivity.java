package com.axon.core_service.domain.dto.dashboard;

import java.math.BigDecimal;

public record PurchaseAggregateByActivity(Long activityId, long purchaseCount, BigDecimal gmv) {
}
