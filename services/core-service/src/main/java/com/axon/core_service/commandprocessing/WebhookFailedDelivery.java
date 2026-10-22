package com.axon.core_service.commandprocessing;

import com.axon.core_service.client.dto.WebhookRequest;
import com.axon.messaging.MarketingActionFailureCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookFailedDelivery {
    private String channel;
    private Long dispatchId;
    private WebhookRequest request;
    private int attemptCount;
    private String failureReason;
    private MarketingActionFailureCategory failureCategory;
}
