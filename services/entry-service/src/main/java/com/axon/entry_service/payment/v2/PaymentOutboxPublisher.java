package com.axon.entry_service.payment.v2;

import com.axon.payment.PaymentOutboxTransactions;
import java.time.Duration;
import java.util.UUID;

/** Claims and sends one event at a time; expired leases can cause duplicate delivery. */
public final class PaymentOutboxPublisher {
    @FunctionalInterface
    public interface Sender {
        void sendAndAwaitAck(UUID eventId, String payload);
    }

    private final PaymentOutboxTransactions outbox;
    private final Sender sender;

    public PaymentOutboxPublisher(PaymentOutboxTransactions outbox, Sender sender) {
        this.outbox = outbox;
        this.sender = sender;
    }

    public void publish(int limit, Duration lease) {
        PaymentConfirmation.requireNoTransaction();
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        for (int i = 0; i < limit; i++) {
            var claimed = outbox.claim(1, lease);
            if (claimed.isEmpty()) {
                return;
            }
            var delivery = claimed.getFirst();
            sender.sendAndAwaitAck(delivery.eventId(), delivery.payload());
            outbox.delivered(delivery);
        }
    }
}
