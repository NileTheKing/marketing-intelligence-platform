package com.axon.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit construction; callers obtain price snapshots from a trusted server source. */
public final class PaymentTransactions {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PaymentTransactions(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public record Reservation(UUID id, long userId, long activityId, long productId, int quantity,
                              Instant deadline, BigDecimal unitPrice, BigDecimal totalAmount, String currency) {
        public Reservation {
            Objects.requireNonNull(id);
            deadline = Objects.requireNonNull(deadline).truncatedTo(ChronoUnit.MICROS);
            unitPrice = Objects.requireNonNull(unitPrice).setScale(2, RoundingMode.UNNECESSARY);
            totalAmount = Objects.requireNonNull(totalAmount).setScale(2, RoundingMode.UNNECESSARY);
            if (quantity <= 0 || unitPrice.signum() < 0
                    || !unitPrice.multiply(BigDecimal.valueOf(quantity)).equals(totalAmount)
                    || currency == null || !currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("invalid price snapshot");
            }
        }
    }

    public record Claim(UUID paymentId, UUID ownerToken) { }
    public record Payment(UUID paymentId, String state, String approvalId, UUID eventId, String payload,
                          Instant createdAt) { }
    public record Status(Reservation reservation, String reservationState, boolean releasePending, Payment payment) { }
    public enum Completion { COMPLETED, ALREADY_COMPLETED, NOT_OWNER }

    // Deadline decisions lock the row first, then read the database clock in a separate statement.
    private Instant dbNow() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class).toInstant();
    }

    private void insert(Reservation r, String state, boolean pending) {
        jdbc.update("INSERT INTO payment_reservations "
                        + "(reservation_id, user_id, activity_id, product_id, quantity, deadline, "
                        + "unit_price, total_amount, currency, state, release_pending) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.id().toString(), r.userId(), r.activityId(), r.productId(), r.quantity(),
                Timestamp.from(r.deadline()), r.unitPrice(), r.totalAmount(), r.currency(), state, pending);
    }

    private void lockAndValidate(Reservation r) {
        jdbc.queryForObject("SELECT user_id, activity_id, product_id, quantity, deadline, unit_price, "
                        + "total_amount, currency FROM payment_reservations WHERE reservation_id = ? FOR UPDATE",
                (rs, row) -> {
                    if (rs.getLong(1) != r.userId() || rs.getLong(2) != r.activityId()
                            || rs.getLong(3) != r.productId() || rs.getInt(4) != r.quantity()
                            || !rs.getTimestamp(5).toInstant().equals(r.deadline())
                            || rs.getBigDecimal(6).compareTo(r.unitPrice()) != 0
                            || rs.getBigDecimal(7).compareTo(r.totalAmount()) != 0
                            || !rs.getString(8).equals(r.currency())) {
                        throw new IllegalStateException("reservation identity reused with different hold");
                    }
                    return true;
                }, r.id().toString());
    }

    private void expireDue(UUID id, Instant now) {
        jdbc.update("UPDATE payment_reservations SET state = 'EXPIRED', release_pending = TRUE "
                        + "WHERE reservation_id = ? AND state = 'RESERVED' AND deadline <= ?",
                id.toString(), Timestamp.from(now));
    }

    public Status register(Reservation r) {
        return tx.execute(status -> {
            try {
                insert(r, "RESERVED", false);
            } catch (DuplicateKeyException ignored) {
                // A prior registration or tombstone is validated under the same row lock.
            }
            lockAndValidate(r);
            expireDue(r.id(), dbNow());
            return status(r.id());
        });
    }

    public Status expireOrFence(Reservation r) {
        return tx.execute(status -> {
            try {
                insert(r, "EXPIRED", true);
            } catch (DuplicateKeyException ignored) {
                // A concurrent registration or tombstone won the unique key.
            }
            lockAndValidate(r);
            Instant now = dbNow();
            if (now.isBefore(r.deadline())) {
                throw new IllegalStateException("hold is not due");
            }
            expireDue(r.id(), now);
            return status(r.id());
        });
    }

    public Status status(UUID reservationId) {
        return jdbc.query("SELECT r.state, r.release_pending, p.payment_id, p.state, p.approval_id, "
                        + "o.event_id, o.payload, r.user_id, r.activity_id, r.product_id, r.quantity, r.deadline, "
                        + "r.unit_price, r.total_amount, r.currency, p.created_at FROM payment_reservations r "
                        + "LEFT JOIN payments p ON p.reservation_id = r.reservation_id "
                        + "LEFT JOIN payment_outbox o ON o.payment_id = p.payment_id WHERE r.reservation_id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    String paymentId = rs.getString(3);
                    String eventId = rs.getString(6);
                    Payment payment = paymentId == null ? null : new Payment(UUID.fromString(paymentId),
                            rs.getString(4), rs.getString(5), eventId == null ? null : UUID.fromString(eventId),
                            rs.getString(7), rs.getTimestamp(16).toInstant());
                    Reservation reservation = new Reservation(reservationId, rs.getLong(8), rs.getLong(9),
                            rs.getLong(10), rs.getInt(11), rs.getTimestamp(12).toInstant(),
                            rs.getBigDecimal(13), rs.getBigDecimal(14), rs.getString(15));
                    return new Status(reservation, rs.getString(1), rs.getBoolean(2), payment);
                }, reservationId.toString());
    }

    public Claim claim(UUID reservationId) {
        return tx.execute(status -> {
            var rows = jdbc.query("SELECT state FROM payment_reservations WHERE reservation_id = ? FOR UPDATE",
                    (rs, row) -> rs.getString(1), reservationId.toString());
            if (rows.isEmpty() || !rows.getFirst().equals("RESERVED")) {
                return null;
            }
            Instant now = dbNow();
            int updated = jdbc.update("UPDATE payment_reservations SET state = 'PAYMENT_PROCESSING' "
                            + "WHERE reservation_id = ? AND state = 'RESERVED' AND deadline > ?",
                    reservationId.toString(), Timestamp.from(now));
            if (updated == 0) {
                expireDue(reservationId, now);
                return null;
            }
            Claim claim = new Claim(UUID.randomUUID(), UUID.randomUUID());
            jdbc.update("INSERT INTO payments (payment_id, reservation_id, state, owner_token, claimed_at, created_at) "
                            + "VALUES (?, ?, 'PROCESSING', ?, ?, ?)",
                    claim.paymentId().toString(), reservationId.toString(), claim.ownerToken().toString(),
                    Timestamp.from(now), Timestamp.from(now));
            return claim;
        });
    }

    public Claim takeover(UUID paymentId, Duration lease) {
        if (lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        return tx.execute(status -> {
            var rows = jdbc.query("SELECT state FROM payments WHERE payment_id = ? FOR UPDATE",
                    (rs, row) -> rs.getString(1), paymentId.toString());
            if (rows.isEmpty() || !rows.getFirst().equals("PROCESSING")) {
                return null;
            }
            UUID owner = UUID.randomUUID();
            Instant now = dbNow();
            int changed = jdbc.update("UPDATE payments SET owner_token = ?, claimed_at = ? "
                            + "WHERE payment_id = ? AND state = 'PROCESSING' AND claimed_at <= ?",
                    owner.toString(), Timestamp.from(now), paymentId.toString(),
                    Timestamp.from(now.minus(lease).truncatedTo(ChronoUnit.MICROS)));
            return changed == 1 ? new Claim(paymentId, owner) : null;
        });
    }

    public Completion complete(Claim claim, UUID eventId, String approvalId, String payload) {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(approvalId);
        Objects.requireNonNull(payload);
        return tx.execute(status -> {
            var rows = jdbc.query("SELECT state, owner_token, approval_id FROM payments WHERE payment_id = ? FOR UPDATE",
                    (rs, row) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)},
                    claim.paymentId().toString());
            if (rows.isEmpty()) {
                return Completion.NOT_OWNER;
            }
            var row = rows.getFirst();
            if (row[0].equals("SUCCEEDED")) {
                var stored = jdbc.queryForObject("SELECT event_id, payload FROM payment_outbox WHERE payment_id = ?",
                        (rs, index) -> new String[]{rs.getString(1), rs.getString(2)}, claim.paymentId().toString());
                if (!Objects.equals(row[2], approvalId) || !stored[0].equals(eventId.toString())
                        || !stored[1].equals(payload)) {
                    throw new IllegalStateException("completion replay differs from stored result");
                }
                return Completion.ALREADY_COMPLETED;
            }
            if (!row[0].equals("PROCESSING") || !row[1].equals(claim.ownerToken().toString())) {
                return Completion.NOT_OWNER;
            }
            jdbc.update("UPDATE payments SET state = 'SUCCEEDED', approval_id = ? WHERE payment_id = ?",
                    approvalId, claim.paymentId().toString());
            int updated = jdbc.update("UPDATE payment_reservations SET state = 'COMPLETED' WHERE reservation_id = "
                            + "(SELECT reservation_id FROM payments WHERE payment_id = ?) AND state = 'PAYMENT_PROCESSING'",
                    claim.paymentId().toString());
            if (updated != 1) {
                throw new IllegalStateException("payment reservation is not processing");
            }
            jdbc.update("INSERT INTO payment_outbox (event_id, payment_id, payload, state) VALUES (?, ?, ?, 'PENDING')",
                    eventId.toString(), claim.paymentId().toString(), payload);
            return Completion.COMPLETED;
        });
    }

    public boolean fail(Claim claim) {
        return tx.execute(status -> {
            int changed = jdbc.update("UPDATE payments SET state = 'FAILED' "
                            + "WHERE payment_id = ? AND owner_token = ? AND state = 'PROCESSING'",
                    claim.paymentId().toString(), claim.ownerToken().toString());
            if (changed == 0) {
                return false;
            }
            int updated = jdbc.update("UPDATE payment_reservations SET state = 'FAILED', release_pending = TRUE "
                            + "WHERE reservation_id = (SELECT reservation_id FROM payments WHERE payment_id = ?) "
                            + "AND state = 'PAYMENT_PROCESSING'", claim.paymentId().toString());
            if (updated != 1) {
                throw new IllegalStateException("payment reservation is not processing");
            }
            return true;
        });
    }

    public List<UUID> stalePayments(Duration lease, int limit) {
        if (lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        requirePositiveLimit(limit);
        Instant threshold = dbNow().minus(lease).truncatedTo(ChronoUnit.MICROS);
        return jdbc.query("SELECT payment_id FROM payments WHERE state = 'PROCESSING' AND claimed_at <= ? "
                        + "ORDER BY claimed_at, payment_id LIMIT ?",
                (rs, row) -> UUID.fromString(rs.getString(1)), Timestamp.from(threshold), limit);
    }

    public Status statusForPayment(UUID paymentId) {
        var ids = jdbc.query("SELECT reservation_id FROM payments WHERE payment_id = ?",
                (rs, row) -> UUID.fromString(rs.getString(1)), paymentId.toString());
        return ids.isEmpty() ? null : status(ids.getFirst());
    }

    /** Database time is the sole authority for lifecycle deadlines. */
    public Instant currentTime() {
        return dbNow();
    }

    public List<UUID> dueReservations(int limit) {
        requirePositiveLimit(limit);
        return jdbc.query("SELECT reservation_id FROM payment_reservations "
                        + "WHERE state = 'RESERVED' AND deadline <= CURRENT_TIMESTAMP(6) "
                        + "ORDER BY deadline, reservation_id LIMIT ?",
                (rs, row) -> UUID.fromString(rs.getString(1)), limit);
    }

    public List<UUID> pendingReleases(int limit) {
        requirePositiveLimit(limit);
        return jdbc.query("SELECT reservation_id FROM payment_reservations WHERE release_pending = TRUE "
                        + "ORDER BY reservation_id LIMIT ?",
                (rs, row) -> UUID.fromString(rs.getString(1)), limit);
    }

    private void requirePositiveLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    /** Only after owner-comparing Redis release returns successfully. */
    public void acknowledgeRelease(UUID reservationId) {
        jdbc.update("UPDATE payment_reservations SET release_pending = FALSE "
                + "WHERE reservation_id = ? AND release_pending = TRUE", reservationId.toString());
    }
}
