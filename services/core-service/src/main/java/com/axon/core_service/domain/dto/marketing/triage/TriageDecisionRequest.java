package com.axon.core_service.domain.dto.marketing.triage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TriageDecisionRequest(
        @NotBlank String decision,
        @NotBlank String decidedBy,
        @Size(max = 1000) String reason
) {
}
