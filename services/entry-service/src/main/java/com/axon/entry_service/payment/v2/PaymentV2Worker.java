package com.axon.entry_service.payment.v2;

import com.axon.entry_service.reservation.v2.ReservationLifecycle;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class PaymentV2Worker {
    private static final Logger log = LoggerFactory.getLogger(PaymentV2Worker.class);
    private final ReservationLifecycle lifecycle;
    private final PaymentConfirmation confirmation;
    private final PaymentOutboxPublisher publisher;

    public PaymentV2Worker(ReservationLifecycle lifecycle, PaymentConfirmation confirmation,
            PaymentOutboxPublisher publisher) {
        this.lifecycle = lifecycle;
        this.confirmation = confirmation;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${axon.payment-v2.worker-interval-ms:5000}")
    public void run() {
        attempt("orphans", () -> lifecycle.recoverOrphans(50));
        attempt("expiry", () -> lifecycle.expireDue(50));
        attempt("release", () -> lifecycle.retryReleases(50));
        attempt("payment recovery", () -> confirmation.recover(Duration.ofSeconds(30), 50));
    }

    @Scheduled(fixedDelayString = "${axon.payment-v2.outbox-interval-ms:100}")
    public void publish() {
        attempt("outbox", () -> publisher.publish(50, Duration.ofSeconds(30)));
    }

    private void attempt(String operation, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("Payment v2 {} failed; will retry", operation, e);
        }
    }
}
