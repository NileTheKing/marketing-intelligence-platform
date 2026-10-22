package com.axon.core_service.domain.dto.marketing.triage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TriageAnalysisFailureRequest(
        @NotBlank String claimToken,
        @NotBlank @Size(max = 1000) String reason
) {
}
