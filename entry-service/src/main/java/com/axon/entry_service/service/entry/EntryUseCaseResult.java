package com.axon.entry_service.service.entry;

import com.axon.entry_service.dto.payment.PaymentConfirmationResponse;

public record EntryUseCaseResult(
        EntryUseCaseStatus status,
        PaymentConfirmationResponse body
) {

    public static EntryUseCaseResult noBody(EntryUseCaseStatus status) {
        return new EntryUseCaseResult(status, null);
    }

    public static EntryUseCaseResult ok(PaymentConfirmationResponse body) {
        return new EntryUseCaseResult(EntryUseCaseStatus.OK, body);
    }

    public static EntryUseCaseResult badRequest(PaymentConfirmationResponse body) {
        return new EntryUseCaseResult(EntryUseCaseStatus.BAD_REQUEST, body);
    }
}
