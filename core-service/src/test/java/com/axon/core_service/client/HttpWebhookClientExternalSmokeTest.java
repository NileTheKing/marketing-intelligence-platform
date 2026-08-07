package com.axon.core_service.client;

import com.axon.core_service.client.dto.WebhookRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

class HttpWebhookClientExternalSmokeTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "AXON_WEBHOOK_SMOKE_URL", matches = "https://.+")
    void sendsWebhookToRealExternalEndpoint() {
        HttpWebhookClient client = new HttpWebhookClient(RestClient.create());
        ReflectionTestUtils.setField(client, "endpointUrl", System.getenv("AXON_WEBHOOK_SMOKE_URL"));

        client.send(WebhookRequest.builder()
                .idempotencyKey("webhook:smoke:20260807")
                .ruleId(10L)
                .userId(1L)
                .productId(100L)
                .templateId(99L)
                .eventType("EXTERNAL_WEBHOOK_SMOKE_TEST")
                .timestamp(System.currentTimeMillis())
                .build());
    }
}
