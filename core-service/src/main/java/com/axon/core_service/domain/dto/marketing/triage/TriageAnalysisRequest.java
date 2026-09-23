package com.axon.core_service.domain.dto.marketing.triage;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record TriageAnalysisRequest(
        @NotBlank String claimToken,
        @NotNull Map<String, Object> factSnapshot,
        @NotBlank String recommendation,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double confidence,
        @NotBlank @Size(max = 2000) String summary,
        @NotNull @Size(min = 1, max = 4) List<@NotBlank String> evidenceRefs,
        @NotBlank @Size(max = 1000) String operatorNextStep,
        @Size(max = 100) String llmModel
) {
}
