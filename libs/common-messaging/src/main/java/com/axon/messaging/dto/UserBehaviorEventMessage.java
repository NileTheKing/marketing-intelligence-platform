package com.axon.messaging.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class UserBehaviorEventMessage {

    private Long eventId;
    private String eventName;
    private String triggerType;
    // ISO-8601 text: the Elasticsearch sink cannot parse Jackson's default decimal epoch seconds as a date.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant occurredAt;
    private Long userId;
    private String sessionId;
    private String pageUrl;
    private String referrer;
    private String userAgent;
    private Map<String, Object> properties;
    private Map<String, Object> attributes;
}
