package com.axon.core_service.domain.dto.user;

import java.math.BigDecimal;

public record UserRfmMetricsDto(Long userId, long purchaseCount, BigDecimal totalRevenue) {
}
