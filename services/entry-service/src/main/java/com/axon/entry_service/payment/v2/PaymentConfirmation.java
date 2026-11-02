package com.axon.entry_service.payment.v2;

import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import com.axon.payment.PaymentGateway;
import com.axon.payment.PaymentGateway.Result;
import com.axon.payment.PaymentTransactions;
import com.axon.payment.PaymentTransactions.Claim;
import com.axon.payment.PaymentTransactions.Status;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicitly invoked confirmation and recovery; gateway calls run outside DB transactions. */
public final class PaymentConfirmation {
    public enum Outcome { NOT_FOUND, FORBIDDEN, PROCESSING, COMPLETED, EXPIRED, FAILED }
    public record Response(Outcome outcome, PaymentTransactions.Payment payment) { }

    private final PaymentTransactions payments;
    private final PaymentGateway gateway;
    private final ObjectMapper mapper;

    public PaymentConfirmation(PaymentTransactions payments, PaymentGateway gateway, ObjectMapper mapper) {
        this.payments = payments;
        this.gateway = gateway;
        this.mapper = mapper;
    }

    public Response confirm(UUID reservationId, long userId) {
        requireNoTransaction();
        Status status = payments.status(reservationId);
        if (status == null) {
            return new Response(Outcome.NOT_FOUND, null);
        }
        if (status.reservation().userId() != userId) {
            return new Response(Outcome.FORBIDDEN, null);
        }
        if (!status.reservationState().equals("RESERVED")) {
            return response(status);
        }
        Claim claim = payments.claim(reservationId);
        if (claim == null) {
            return response(payments.status(reservationId));
        }
        Status claimed = payments.status(reservationId);
        Result result;
        try {
            result = gateway.approve(claim.paymentId(), claimed.reservation());
        } catch (RuntimeException ambiguous) {
            return response(payments.status(reservationId));
        }
        return finish(claim, claimed, result);
    }

    public void recover(Duration lease, int limit) {
        requireNoTransaction();
        for (UUID paymentId : payments.stalePayments(lease, limit)) {
            Claim claim = payments.takeover(paymentId, lease);
            if (claim == null) {
                continue;
            }
            Status status = payments.statusForPayment(paymentId);
            Result result;
            try {
                result = gateway.lookup(paymentId, status.reservation());
            } catch (RuntimeException ambiguous) {
                continue;
            }
            finish(claim, status, result);
        }
    }

    private Response finish(Claim claim, Status status, Result result) {
        switch (result.outcome()) {
            case APPROVED -> {
                UUID eventId = UUID.nameUUIDFromBytes(("payment-completed:" + claim.paymentId())
                        .getBytes(StandardCharsets.UTF_8));
                payments.complete(claim, eventId, result.approvalId(), payload(status, eventId));
            }
            case DECLINED -> payments.fail(claim);
            case UNKNOWN -> { }
        }
        return response(payments.status(status.reservation().id()));
    }

    private String payload(Status status, UUID eventId) {
        var reservation = status.reservation();
        var payment = status.payment();
        var message = CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .campaignActivityId(reservation.activityId())
                .userId(reservation.userId())
                .productId(reservation.productId())
                .quantity((long) reservation.quantity())
                .price(reservation.unitPrice())
                .totalAmount(reservation.totalAmount())
                .currency(reservation.currency())
                .paymentId(payment.paymentId().toString())
                .eventId(eventId.toString())
                // This is the DB processing-claim time, not a verified PG approval time.
                .timestamp(payment.createdAt().toEpochMilli())
                .build();
        try {
            return mapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize payment completion", e);
        }
    }

    private Response response(Status status) {
        if (status == null) {
            return new Response(Outcome.NOT_FOUND, null);
        }
        Outcome outcome = switch (status.reservationState()) {
            case "PAYMENT_PROCESSING" -> Outcome.PROCESSING;
            case "COMPLETED" -> Outcome.COMPLETED;
            case "EXPIRED" -> Outcome.EXPIRED;
            case "FAILED" -> Outcome.FAILED;
            default -> throw new IllegalStateException("unexpected reservation state: " + status.reservationState());
        };
        return new Response(outcome, status.payment());
    }

    static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("external operation cannot run inside a DB transaction");
        }
    }
}
