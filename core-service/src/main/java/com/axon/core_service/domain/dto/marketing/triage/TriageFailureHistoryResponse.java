package com.axon.core_service.domain.dto.marketing.triage;

import java.util.List;

public record TriageFailureHistoryResponse(
        Long actionId,
        int days,
        long totalFailures,
        List<FailureCategoryCountResponse> byCategory
) {
    public record FailureCategoryCountResponse(String category, long count) {
    }
}
