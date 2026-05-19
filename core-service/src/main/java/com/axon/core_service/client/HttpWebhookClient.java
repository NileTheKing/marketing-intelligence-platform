package com.axon.core_service.client;

import com.axon.core_service.client.dto.WebhookRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class HttpWebhookClient implements WebhookClient {

    private final RestClient webhookRestClient;

    @Value("${axon.webhook.endpoint-url:}")
    private String endpointUrl;

    @Override
    public void send(WebhookRequest request) {
        if (!StringUtils.hasText(endpointUrl)) {
            throw new IllegalStateException("axon.webhook.endpoint-url is not configured");
        }

        webhookRestClient.post()
                .uri(endpointUrl)
                .header("Idempotency-Key", request.getIdempotencyKey())
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }
}
