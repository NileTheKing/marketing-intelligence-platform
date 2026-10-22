package com.axon.core_service.campaign.dto;

import jakarta.validation.constraints.NotBlank;

public record CampaignActivityRunDecisionRequest(
        @NotBlank String decision,
        @NotBlank String decidedBy,
        String reason
) {
}
