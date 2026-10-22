package com.axon.core_service.domain.dto.reconciliation;

import com.axon.core_service.domain.reconciliation.ReconciliationIssue;
import com.axon.core_service.domain.reconciliation.ReconciliationIssueStatus;
import com.axon.core_service.domain.reconciliation.ReconciliationIssueType;

import java.time.LocalDateTime;

public record ReconciliationIssueResponse(
        Long id,
        ReconciliationIssueType issueType,
        ReconciliationIssueStatus status,
        Long campaignActivityId,
        Long purchaseId,
        Long userId,
        Long expectedValue,
        Long observedValue,
        String evidence,
        LocalDateTime firstDetectedAt,
        LocalDateTime lastDetectedAt,
        LocalDateTime acknowledgedAt,
        LocalDateTime resolvedAt,
        String resolutionNote
) {
    public static ReconciliationIssueResponse from(ReconciliationIssue issue) {
        return new ReconciliationIssueResponse(
                issue.getId(), issue.getIssueType(), issue.getStatus(), issue.getCampaignActivityId(),
                issue.getPurchaseId(), issue.getUserId(), issue.getExpectedValue(), issue.getObservedValue(),
                issue.getEvidence(), issue.getFirstDetectedAt(), issue.getLastDetectedAt(), issue.getAcknowledgedAt(),
                issue.getResolvedAt(), issue.getResolutionNote());
    }
}
