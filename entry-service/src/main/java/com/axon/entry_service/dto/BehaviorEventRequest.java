package com.axon.entry_service.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@JsonIgnoreProperties("userId")
public class BehaviorEventRequest {

    private Long eventId;

    @Size(max = 128)
    private String eventName;

    @NotBlank
    private String triggerType;

    private Instant occurredAt;

    @Size(max = 128)
    private String sessionId;

    @Size(max = 2048)
    private String pageUrl;

    @Size(max = 2048)
    private String referrer;

    @Size(max = 20)
    private Map<String, Object> properties;
}
