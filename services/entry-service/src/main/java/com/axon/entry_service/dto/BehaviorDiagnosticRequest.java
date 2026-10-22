package com.axon.entry_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class BehaviorDiagnosticRequest {

    @NotBlank
    @Size(max = 128)
    private String reason;

    private Instant occurredAt;

    @Size(max = 2048)
    private String pageUrl;

    @Size(max = 128)
    private String sessionId;

    private Long eventId;

    private String triggerType;

    private Map<String, Object> details;
}
