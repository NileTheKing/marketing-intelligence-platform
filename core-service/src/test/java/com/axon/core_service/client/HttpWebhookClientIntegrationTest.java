package com.axon.core_service.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axon.core_service.client.dto.WebhookRequest;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class HttpWebhookClientIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    private HttpWebhookClient client;

    @BeforeEach
    void setUp() {
        client = clientWithReadTimeout(Duration.ofSeconds(1));
    }

    private HttpWebhookClient clientWithReadTimeout(Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(100));
        requestFactory.setReadTimeout(readTimeout);
        HttpWebhookClient webhookClient =
                new HttpWebhookClient(RestClient.builder().requestFactory(requestFactory).build());
        ReflectionTestUtils.setField(webhookClient, "endpointUrl", wireMock.baseUrl() + "/crm/events");
        return webhookClient;
    }

    @Test
    void sendsJsonAndIdempotencyKeyOverRealHttp() {
        wireMock.stubFor(post("/crm/events").willReturn(aResponse().withStatus(204)));

        client.send(request());

        wireMock.verify(postRequestedFor(urlEqualTo("/crm/events"))
                .withHeader("Idempotency-Key", equalTo("webhook:10:5:99:1:100")));
    }

    @Test
    void exposesClientErrorForRetryClassification() {
        wireMock.stubFor(post("/crm/events").willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(() -> client.send(request()))
                .isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    void exposesReadTimeoutForRetryClassification() {
        wireMock.stubFor(post("/crm/events")
                .willReturn(aResponse().withStatus(204).withFixedDelay(300)));
        client = clientWithReadTimeout(Duration.ofMillis(100));

        assertThatThrownBy(() -> client.send(request()))
                .isInstanceOf(ResourceAccessException.class);
    }

    private WebhookRequest request() {
        return WebhookRequest.builder()
                .idempotencyKey("webhook:10:5:99:1:100")
                .ruleId(10L)
                .userId(1L)
                .productId(100L)
                .templateId(99L)
                .eventType("MARKETING_RULE_MATCHED")
                .timestamp(1234L)
                .build();
    }
}
