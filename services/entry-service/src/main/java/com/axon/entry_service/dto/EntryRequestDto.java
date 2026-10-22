package com.axon.entry_service.dto;

import com.axon.messaging.CampaignActivityType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class EntryRequestDto {
    private CampaignActivityType campaignActivityType;

    @NotNull(message = "Campaign Activity ID is required")
    private Long campaignActivityId;

    @NotNull(message = "Product ID is required")
    private Long productId;

    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity = 1;
}
