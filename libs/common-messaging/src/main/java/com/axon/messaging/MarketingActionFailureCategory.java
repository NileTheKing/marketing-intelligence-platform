package com.axon.messaging;

public enum MarketingActionFailureCategory {
    INVALID_TARGET,
    TRANSIENT_DELIVERY,
    RATE_LIMITED,
    AUTHORIZATION,
    UNKNOWN
}
