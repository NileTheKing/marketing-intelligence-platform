package com.axon.entry_service.payment.v2;

import com.axon.entry_service.payment.dto.PaymentConfirmationResponse;
import com.axon.entry_service.payment.dto.PaymentPrepareResponse;
import com.axon.entry_service.reservation.EntryUseCaseResult;
import com.axon.entry_service.reservation.EntryUseCaseStatus;
import com.axon.entry_service.reservation.domain.CampaignActivityMeta;
import com.axon.entry_service.reservation.domain.ReservationResult;
import com.axon.entry_service.reservation.event.ReservationApprovedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskRejectedException;
import com.axon.entry_service.reservation.v2.ReservationLifecycle;
import com.axon.messaging.CampaignActivityType;
import com.axon.payment.PaymentTransactions;
import com.axon.payment.PaymentTransactions.Reservation;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public final class PaymentV2Facade {
    private final ReservationLifecycle lifecycle;
    private final PaymentTransactions payments;
    private final PaymentCredentials credentials;
    private final PaymentConfirmation confirmation;
    private final Set<Long> activities;
    private final ApplicationEventPublisher events;

    public PaymentV2Facade(ReservationLifecycle lifecycle, PaymentTransactions payments,
            PaymentCredentials credentials, PaymentConfirmation confirmation, String activityIds,
            ApplicationEventPublisher events) {
        this.lifecycle = lifecycle;
        this.payments = payments;
        this.credentials = credentials;
        this.confirmation = confirmation;
        this.events = events;
        this.activities = Arrays.stream(activityIds.split(",")).map(String::trim).filter(id -> !id.isEmpty())
                .map(Long::valueOf).collect(Collectors.toUnmodifiableSet());
    }

    public boolean handles(long activityId) {
        return activities.contains(activityId);
    }

    public EntryUseCaseResult admit(CampaignActivityMeta meta, long userId, Integer requestedQuantity) {
        // Admission creates a five-minute deadline using the host clock; DB time decides validity.
        Instant now = Instant.now();
        if (!meta.isParticipatable(now) || meta.campaignActivityType() != CampaignActivityType.FIRST_COME_FIRST_SERVE
                || meta.productId() == null || meta.paymentUnitPrice() == null || !"KRW".equals(meta.paymentCurrency())) {
            return EntryUseCaseResult.noBody(EntryUseCaseStatus.BAD_REQUEST);
        }
        int quantity = requestedQuantity == null ? 1 : requestedQuantity;
        BigDecimal unitPrice = meta.paymentUnitPrice();
        Reservation proposed = new Reservation(UUID.randomUUID(), userId, meta.id(), meta.productId(), quantity,
                now.plus(Duration.ofMinutes(5)), unitPrice, unitPrice.multiply(BigDecimal.valueOf(quantity)), "KRW");
        var admission = lifecycle.admitWithResult(proposed, meta.limitCount() == null ? 0 : meta.limitCount());
        if (admission == null) {
            return EntryUseCaseResult.noBody(EntryUseCaseStatus.GONE);
        }
        var status = admission.status();
        if (!"RESERVED".equals(status.reservationState())) {
            return EntryUseCaseResult.noBody(EntryUseCaseStatus.CONFLICT);
        }
        Duration remaining = Duration.between(payments.currentTime(), status.reservation().deadline());
        if (remaining.isNegative() || remaining.isZero()) {
            return EntryUseCaseResult.noBody(EntryUseCaseStatus.GONE);
        }
        if (admission.newlyAdmitted()) {
            try {
                events.publishEvent(new ReservationApprovedEvent(meta.id(), userId, admission.order(), now,
                        meta.productId(), meta.campaignActivityType()));
            } catch (TaskRejectedException ignored) {
                // Behavior delivery is best effort, as in the existing admission path.
            }
        }
        String token = credentials.issue(status.reservation().id(), PaymentCredentials.PRIMARY, remaining);
        return EntryUseCaseResult.ok(PaymentConfirmationResponse.success(token));
    }

    public ResponseEntity<PaymentPrepareResponse> prepare(String token, long userId) {
        UUID id = credentials.resolve(token, PaymentCredentials.PRIMARY);
        if (id == null) {
            return ResponseEntity.status(HttpStatus.GONE).body(PaymentPrepareResponse.failure("결제 시간이 만료되었습니다."));
        }
        var status = payments.status(id);
        if (status == null || status.reservation().userId() != userId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(PaymentPrepareResponse.failure("응모자와 요청자가 다릅니다."));
        }
        if ("RESERVED".equals(status.reservationState())
                && !payments.currentTime().isBefore(status.reservation().deadline())) {
            status = payments.expireOrFence(status.reservation());
        }
        if (!"RESERVED".equals(status.reservationState())
                && !"PAYMENT_PROCESSING".equals(status.reservationState())
                && !"COMPLETED".equals(status.reservationState())) {
            return ResponseEntity.status(HttpStatus.GONE).body(PaymentPrepareResponse.failure("결제 시간이 만료되었습니다."));
        }
        String approval = credentials.issue(id, PaymentCredentials.APPROVAL, Duration.ofMinutes(30));
        return ResponseEntity.ok(PaymentPrepareResponse.success("결제를 진행해주세요.", approval));
    }

    public ResponseEntity<PaymentConfirmationResponse> confirm(String token, long userId) {
        UUID id = credentials.resolve(token, PaymentCredentials.APPROVAL);
        if (id == null) {
            return failure(HttpStatus.GONE, "결제 시간이 만료되었습니다.");
        }
        var response = confirmation.confirm(id, userId);
        return switch (response.outcome()) {
            case COMPLETED -> ResponseEntity.ok(PaymentConfirmationResponse.success(null));
            case PROCESSING -> failure(HttpStatus.CONFLICT, "결제 확인 처리 중입니다. 잠시 후 다시 시도해주세요.");
            case FORBIDDEN -> failure(HttpStatus.FORBIDDEN, "응모자와 요청자가 다릅니다.");
            case EXPIRED, FAILED, NOT_FOUND -> failure(HttpStatus.GONE, "결제가 만료되었거나 실패했습니다.");
        };
    }

    private ResponseEntity<PaymentConfirmationResponse> failure(HttpStatus status, String reason) {
        return ResponseEntity.status(status).body(PaymentConfirmationResponse.failure(ReservationResult.error(), reason));
    }
}
