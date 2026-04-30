package com.axon.entry_service.controller;

import com.axon.entry_service.domain.ReservationResult;
import com.axon.entry_service.dto.Payment.PaymentApprovalPayload;
import com.axon.entry_service.dto.Payment.PaymentConfirmationRequest;
import com.axon.entry_service.dto.Payment.PaymentConfirmationResponse;
import com.axon.entry_service.dto.Payment.PaymentPrepareRequest;
import com.axon.entry_service.dto.Payment.PaymentPrepareResponse;
import com.axon.entry_service.service.Payment.PaymentService;
import com.axon.entry_service.service.Payment.ReservationTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final ReservationTokenService reservationTokenService;
    private final PaymentService paymentService;

    @PostMapping("/prepare")
    public ResponseEntity<PaymentPrepareResponse> preparePayment(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody PaymentPrepareRequest request) {

        long userId = Long.parseLong(userDetails.getUsername());
        String reservationToken = request.getReservationToken();

        return reservationTokenService.getPayloadFromToken(reservationToken)
                .filter(payload -> payload.getUserId() == userId)
                .map(payload -> {
                    PaymentApprovalPayload approvalPayload = PaymentApprovalPayload.builder()
                            .userId(userId)
                            .campaignActivityId(payload.getCampaignActivityId())
                            .productId(payload.getProductId())
                            .campaignActivityType(payload.getCampaignActivityType())
                            .quantity(payload.getQuantity() != null ? payload.getQuantity().intValue() : 1)
                            .reservationToken(reservationToken)
                            .build();

                    String result = reservationTokenService.CreateApprovalToken(approvalPayload);

                    if (result != null) {
                        return ResponseEntity.ok(PaymentPrepareResponse.success("결제를 진행해주세요.", result));
                    } else {
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(PaymentPrepareResponse.failure("시스템 오류가 발생했습니다."));
                    }
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(PaymentPrepareResponse.failure("결제 시간이 만료되었습니다. 처음부터 다시 응모해주세요.")));
    }

    @PostMapping("/confirm")
    public ResponseEntity<PaymentConfirmationResponse> confirmPayment(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody PaymentConfirmationRequest request) {

        long currentUserId = Long.parseLong(userDetails.getUsername());
        String token = request.getReservationToken();

        Optional<PaymentApprovalPayload> payloadOpt = reservationTokenService.getApprovalPayload(token);

        if (payloadOpt.isEmpty()) {
            log.warn("2차 토큰 만료 또는 없음: token={}", token);
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(PaymentConfirmationResponse.failure(ReservationResult.error(), "결제 시간이 만료되었습니다. 관리자에게 문의해주세요."));
        }

        PaymentApprovalPayload payload = payloadOpt.get();
        if (payload.getUserId() != currentUserId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(PaymentConfirmationResponse.failure(ReservationResult.error(), "응모자와 요청자가 다릅니다."));
        }

        boolean success = paymentService.sendToKafkaWithRetry(payload, 3);

        if (success) {
            reservationTokenService.cleanup(payload);
            return ResponseEntity.ok(PaymentConfirmationResponse.success(null));
        } else {
            log.warn("Kafka 전송 최종 실패: userId={}, campaignActivityId={}", currentUserId, payload.getCampaignActivityId());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(PaymentConfirmationResponse.failure(ReservationResult.error(), "일시적인 오류로 결제가 취소되었습니다. 처음부터 다시 응모해주세요."));
        }
    }
}
