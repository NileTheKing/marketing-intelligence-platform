package com.axon.core_service.domain.dto.campaignactivity;

import jakarta.validation.constraints.NotBlank;

public record CampaignActivityRunAnalysisFailureRequest(
        @NotBlank String claimToken,
        @NotBlank String reason
) {
}
