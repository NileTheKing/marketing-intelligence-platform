package com.axon.core_service.client;

import com.axon.core_service.client.dto.WebhookRequest;

public interface WebhookClient {

    void send(WebhookRequest request);
}
