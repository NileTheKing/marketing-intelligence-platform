package com.axon.core_service.commandprocessing;

import java.time.Instant;
import java.util.List;

public record UserSummaryProjectionFailedEvent(
        int schemaVersion,
        List<LedgerKey> targets,
        Instant failedAt,
        String exceptionType,
        String reason
) {
    public record LedgerKey(Long campaignActivityId, Long userId) {
    }
}
