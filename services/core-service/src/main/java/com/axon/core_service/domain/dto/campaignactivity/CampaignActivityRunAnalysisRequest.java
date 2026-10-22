package com.axon.core_service.domain.dto.campaignactivity;

import jakarta.validation.constraints.NotBlank;

public record CampaignActivityRunAnalysisRequest(
        @NotBlank String claimToken,
        @NotBlank String recommendation,
        @NotBlank String summary
) {
}
