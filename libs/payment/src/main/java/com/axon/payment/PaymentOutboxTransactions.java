package com.axon.payment;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Short DB claims; publishing occurs only after the claim transaction has committed. */
public final class PaymentOutboxTransactions {
    public record Delivery(UUID eventId, UUID paymentId, String payload, UUID ownerToken) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PaymentOutboxTransactions(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public List<Delivery> claim(int limit, Duration lease) {
        if (limit <= 0 || lease == null || lease.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("positive limit and lease of at least one millisecond required");
        }
        return tx.execute(status -> {
            var rows = jdbc.query("SELECT event_id, payment_id, payload FROM payment_outbox "
                            + "WHERE state = 'PENDING' OR (state = 'CLAIMED' AND claimed_until <= CURRENT_TIMESTAMP(6)) "
                            + "ORDER BY created_at, event_id LIMIT ? FOR UPDATE SKIP LOCKED",
                    (rs, row) -> new Delivery(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                            rs.getString(3), UUID.randomUUID()), limit);
            Instant now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toInstant();
            for (Delivery delivery : rows) {
                jdbc.update("UPDATE payment_outbox SET state = 'CLAIMED', owner_token = ?, claimed_until = ?, "
                                + "attempts = attempts + 1 WHERE event_id = ?",
                        delivery.ownerToken().toString(), Timestamp.from(now.plus(lease)), delivery.eventId().toString());
            }
            return rows;
        });
    }

    /** An expired or replaced owner cannot acknowledge an event. */
    public boolean delivered(Delivery delivery) {
        return tx.execute(status -> jdbc.update("UPDATE payment_outbox SET state = 'DELIVERED', "
                        + "owner_token = NULL, claimed_until = NULL WHERE event_id = ? AND state = 'CLAIMED' "
                        + "AND owner_token = ? AND claimed_until > CURRENT_TIMESTAMP(6)",
                delivery.eventId().toString(), delivery.ownerToken().toString()) == 1);
    }
}
