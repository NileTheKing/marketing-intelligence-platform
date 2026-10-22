package com.axon.core_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class WebhookClientConfig {

    @Bean
    public RestClient webhookRestClient(
            RestClient.Builder builder,
            @Value("${axon.webhook.connect-timeout:2s}") Duration connectTimeout,
            @Value("${axon.webhook.read-timeout:3s}") Duration readTimeout
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return builder
                .requestFactory(requestFactory)
                .build();
    }
}
