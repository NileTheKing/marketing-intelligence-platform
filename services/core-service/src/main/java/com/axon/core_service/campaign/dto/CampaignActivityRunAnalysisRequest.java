package com.axon.core_service.campaign.dto;

import jakarta.validation.constraints.NotBlank;

public record CampaignActivityRunAnalysisRequest(
        @NotBlank String claimToken,
        @NotBlank String recommendation,
        @NotBlank String summary
) {
}
