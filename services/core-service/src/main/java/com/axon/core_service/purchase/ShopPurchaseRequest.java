package com.axon.core_service.purchase;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record ShopPurchaseRequest(@NotNull UUID requestId, @NotNull @Positive Long productId,
                                  @Positive Long userCouponId) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unsupported purchase field: " + name);
    }
}
