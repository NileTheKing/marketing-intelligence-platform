package com.axon.core_service.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookRequest {
    private Long userId;
    private Long templateId; // the reference id representing the message template
    private String eventType;
    private long timestamp;
}
