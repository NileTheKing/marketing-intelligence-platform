package com.axon.payment;

import java.util.UUID;

/** External gateways may return unknown after a timeout and must be queried before release. */
public interface PaymentGateway {
    enum Outcome { APPROVED, DECLINED, UNKNOWN }

    record Result(Outcome outcome, String approvalId) {
        public Result {
            if (outcome == null || (outcome == Outcome.APPROVED && (approvalId == null || approvalId.isBlank()))
                    || (outcome != Outcome.APPROVED && approvalId != null)) {
                throw new IllegalArgumentException("invalid gateway result");
            }
        }
    }

    Result approve(UUID paymentId, PaymentTransactions.Reservation snapshot);

    Result lookup(UUID paymentId, PaymentTransactions.Reservation snapshot);
}
