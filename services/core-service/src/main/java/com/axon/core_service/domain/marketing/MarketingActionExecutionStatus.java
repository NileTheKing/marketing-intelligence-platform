package com.axon.core_service.domain.marketing;

public enum MarketingActionExecutionStatus {
    PENDING,
    DISPATCHING,
    DISPATCHED,
    PROCESSING,
    RETRYING,
    SUCCEEDED,
    FAILED_FINAL
}
