package com.axon.core_service.domain.dto.customer;

import java.time.Instant;
import java.util.Map;

public record CustomerBehaviorEvent(
        Instant occurredAt,
        String eventName,
        String triggerType,
        String pageUrl,
        Map<String, Object> properties
) {
}
