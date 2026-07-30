package com.axon.core_service.event;

import com.axon.core_service.domain.dto.purchase.PurchaseInfoDto;
import java.util.List;

public record PurchaseBatchRequestedEvent(List<PurchaseInfoDto> purchases) {

    public PurchaseBatchRequestedEvent {
        purchases = List.copyOf(purchases);
    }
}
