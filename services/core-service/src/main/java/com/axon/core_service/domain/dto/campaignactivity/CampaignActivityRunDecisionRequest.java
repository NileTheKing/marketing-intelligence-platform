package com.axon.core_service.domain.dto.campaignactivity;

import jakarta.validation.constraints.NotBlank;

public record CampaignActivityRunDecisionRequest(
        @NotBlank String decision,
        @NotBlank String decidedBy,
        String reason
) {
}
