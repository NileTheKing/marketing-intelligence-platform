package com.axon.core_service.domain.dto.dashboard;

import java.math.BigDecimal;

public record PurchaseAggregateByCampaign(Long campaignId, long purchaseCount, BigDecimal gmv) {
}
