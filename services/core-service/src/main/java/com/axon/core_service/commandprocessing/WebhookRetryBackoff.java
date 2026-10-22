package com.axon.core_service.commandprocessing;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class WebhookRetryBackoff {

    private static final long BASE_DELAY_MILLIS = 200;
    private static final long MAX_DELAY_MILLIS = 1_000;

    public void pauseAfterFailure(int failedAttempt) {
        long exponentialDelay = Math.min(
                MAX_DELAY_MILLIS,
                BASE_DELAY_MILLIS * (1L << Math.max(0, failedAttempt - 1)));
        long jitteredDelay = ThreadLocalRandom.current()
                .nextLong(exponentialDelay / 2, exponentialDelay + exponentialDelay / 2 + 1);

        try {
            Thread.sleep(jitteredDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Webhook retry backoff interrupted", e);
        }
    }
}
