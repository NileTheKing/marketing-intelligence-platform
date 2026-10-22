package com.axon.core_service.campaign.dto;

import jakarta.validation.constraints.NotBlank;

public record CampaignActivityRunAnalysisFailureRequest(
        @NotBlank String claimToken,
        @NotBlank String reason
) {
}
