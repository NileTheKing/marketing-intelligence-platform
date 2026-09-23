package com.axon.core_service.domain.dto.marketing.triage;

import com.axon.core_service.domain.marketing.MarketingActionDispatch;

import java.time.LocalDateTime;

public record TriageDispatchResponse(
        Long dispatchId,
        Long executionId,
        long sequence,
        String initiatedBy,
        String status,
        int attemptCount,
        String failureReason,
        LocalDateTime dispatchedAt,
        LocalDateTime completedAt,
        LocalDateTime dltAt
) {
    public static TriageDispatchResponse from(MarketingActionDispatch dispatch) {
        return new TriageDispatchResponse(
                dispatch.getId(), dispatch.getExecution().getId(), dispatch.getSequence(),
                dispatch.getInitiatedBy().name(), dispatch.getStatus().name(), dispatch.getAttemptCount(),
                dispatch.getLastFailureReason(), dispatch.getDispatchedAt(), dispatch.getCompletedAt(), dispatch.getDltAt());
    }
}
