package com.axon.payment;

import java.util.UUID;

/** Stateless synthetic approval: lookup returns the same simulated outcome even after a process restart. */
public final class MockPaymentGateway implements PaymentGateway {
    @Override
    public Result approve(UUID paymentId, PaymentTransactions.Reservation snapshot) {
        return approved(paymentId);
    }

    @Override
    public Result lookup(UUID paymentId, PaymentTransactions.Reservation snapshot) {
        return approved(paymentId);
    }

    private Result approved(UUID paymentId) {
        return new Result(Outcome.APPROVED, "mock-" + paymentId);
    }
}
