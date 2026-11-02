package com.axon.entry_service.reservation.v2;

import com.axon.payment.PaymentTransactions;
import com.axon.payment.PaymentTransactions.Reservation;
import com.axon.payment.PaymentTransactions.Status;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicitly invoked lifecycle operations; this class starts no workers or schedulers. */
public final class ReservationLifecycle {
    private final RedisReservationHolds holds;
    private final PaymentTransactions payments;

    public ReservationLifecycle(RedisReservationHolds holds, PaymentTransactions payments) {
        this.holds = holds;
        this.payments = payments;
    }

    public record Admission(Status status, boolean newlyAdmitted, Long order) { }

    /** A DB or Redis failure leaves the hold for recovery; neither implies a rollback. */
    public Status admitAndRegister(Reservation proposed, int limit) {
        Admission admission = admitWithResult(proposed, limit);
        return admission == null ? null : admission.status();
    }

    public Admission admitWithResult(Reservation proposed, int limit) {
        requireNoTransaction();
        var admission = holds.admitWithResult(proposed, limit);
        if (admission == null) {
            return null;
        }
        Reservation admitted = admission.reservation();
        Status status = payments.register(admitted);
        holds.markRegistered(admitted.id());
        return new Admission(status, admission.newlyAdmitted(), admission.order());
    }

    public void recoverOrphans(int limit) {
        requireNoTransaction();
        Set<String> ids = holds.dueUnregistered(payments.currentTime(), limit);
        if (ids == null) {
            throw new IllegalStateException("Redis returned no orphan index response");
        }
        for (String id : ids) {
            Reservation hold = holds.find(UUID.fromString(id));
            if (hold == null) {
                if (holds.isUnregistered(UUID.fromString(id))) {
                    throw new IllegalStateException("orphan index references a missing hold: " + id);
                }
                continue;
            }
            Status status = payments.expireOrFence(hold);
            holds.markRegistered(hold.id());
            releaseIfPending(status);
        }
    }

    public void expireDue(int limit) {
        requireNoTransaction();
        for (UUID id : payments.dueReservations(limit)) {
            Status status = payments.status(id);
            if (status != null) {
                releaseIfPending(payments.expireOrFence(status.reservation()));
            }
        }
    }

    public void retryReleases(int limit) {
        requireNoTransaction();
        for (UUID id : payments.pendingReleases(limit)) {
            Status status = payments.status(id);
            if (status != null) {
                releaseIfPending(status);
            }
        }
    }

    private void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("reservation lifecycle cannot run inside an outer transaction");
        }
    }

    private void releaseIfPending(Status status) {
        if (status.releasePending()) {
            holds.release(status.reservation());
            payments.acknowledgeRelease(status.reservation().id());
        }
    }
}
