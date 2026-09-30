package com.axon.core_service.domain.campaignactivity;

public enum CampaignActivityRunStatus {
    BLOCKED,
    PENDING_ANALYSIS,
    ANALYZING,
    AWAITING_APPROVAL,
    DISPATCHING,
    DISPATCHED,
    CLOSED,
    ANALYSIS_FAILED
}
