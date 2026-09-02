package com.axon.entry_service.controller;

import com.axon.entry_service.dto.payment.PaymentApprovalPayload;
import com.axon.entry_service.dto.payment.PaymentConfirmationRequest;
import com.axon.entry_service.dto.payment.PaymentConfirmationResponse;
import com.axon.entry_service.service.payment.PaymentService;
import com.axon.entry_service.service.payment.ReservationTokenService;
import com.axon.entry_service.service.payment.ReservationTokenService.ConfirmationLeaseResult;
import com.axon.messaging.CampaignActivityType;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    private static final String APPROVAL_TOKEN = "1:2";
    private static final String PRIMARY_TOKEN = "primary-token";

    @Mock
    private ReservationTokenService reservationTokenService;

    @Mock
    private PaymentService paymentService;

    @Mock
    private UserDetails userDetails;

    @InjectMocks
    private PaymentController paymentController;

    @Test
    @DisplayName("유효한 1차 및 2차 토큰이면 구매 명령을 전송한다")
    void confirmPayment_WithValidPrimaryAndApprovalTokens_SendsPurchaseCommand() {
        PaymentApprovalPayload payload = approvalPayload();
        when(userDetails.getUsername()).thenReturn("1");
        when(reservationTokenService.getApprovalPayload(APPROVAL_TOKEN)).thenReturn(Optional.of(payload));
        when(reservationTokenService.tryAcquireConfirmationLease(PRIMARY_TOKEN))
                .thenReturn(ConfirmationLeaseResult.ACQUIRED);
        when(paymentService.sendToKafkaWithRetry(payload, 3)).thenReturn(true);

        ResponseEntity<?> response = paymentController.confirmPayment(
                userDetails, new PaymentConfirmationRequest(APPROVAL_TOKEN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(paymentService).sendToKafkaWithRetry(payload, 3);
        verify(reservationTokenService).cleanup(payload);
    }

    @Test
    @DisplayName("2차 토큰이 남아 있어도 1차 토큰이 없으면 구매 명령을 전송하지 않는다")
    void confirmPayment_WithMissingPrimaryToken_RejectsWithoutSendingPurchaseCommand() {
        PaymentApprovalPayload payload = approvalPayload();
        when(userDetails.getUsername()).thenReturn("1");
        when(reservationTokenService.getApprovalPayload(APPROVAL_TOKEN)).thenReturn(Optional.of(payload));
        when(reservationTokenService.tryAcquireConfirmationLease(PRIMARY_TOKEN))
                .thenReturn(ConfirmationLeaseResult.MISSING);

        ResponseEntity<?> response = paymentController.confirmPayment(
                userDetails, new PaymentConfirmationRequest(APPROVAL_TOKEN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        verify(paymentService, never()).sendToKafkaWithRetry(any(), eq(3));
        verify(reservationTokenService, never()).cleanup(any());
    }

    @Test
    @DisplayName("결제 확인 lease가 이미 있으면 Kafka를 다시 전송하지 않는다")
    void confirmPayment_WhenLeaseIsAlreadyHeld_ReturnsConflictWithoutSendingPurchaseCommand() {
        PaymentApprovalPayload payload = approvalPayload();
        when(userDetails.getUsername()).thenReturn("1");
        when(reservationTokenService.getApprovalPayload(APPROVAL_TOKEN)).thenReturn(Optional.of(payload));
        when(reservationTokenService.tryAcquireConfirmationLease(PRIMARY_TOKEN))
                .thenReturn(ConfirmationLeaseResult.ALREADY_PROCESSING);

        ResponseEntity<?> response = paymentController.confirmPayment(
                userDetails, new PaymentConfirmationRequest(APPROVAL_TOKEN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(paymentService, never()).sendToKafkaWithRetry(any(), eq(3));
    }

    @Test
    @DisplayName("Kafka 전송이 최종 실패하면 예약 및 승인 토큰은 남기고 lease만 해제한다")
    void confirmPayment_WhenKafkaSendFails_ReleasesOnlyConfirmationLease() {
        PaymentApprovalPayload payload = approvalPayload();
        when(userDetails.getUsername()).thenReturn("1");
        when(reservationTokenService.getApprovalPayload(APPROVAL_TOKEN)).thenReturn(Optional.of(payload));
        when(reservationTokenService.tryAcquireConfirmationLease(PRIMARY_TOKEN))
                .thenReturn(ConfirmationLeaseResult.ACQUIRED);
        when(paymentService.sendToKafkaWithRetry(payload, 3)).thenReturn(false);

        ResponseEntity<?> response = paymentController.confirmPayment(
                userDetails, new PaymentConfirmationRequest(APPROVAL_TOKEN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(((PaymentConfirmationResponse) response.getBody()).getReason())
                .isEqualTo("일시적인 오류가 발생했습니다. 잠시 후 같은 결제 요청을 다시 시도해주세요.");
        verify(reservationTokenService).releaseConfirmationLease(PRIMARY_TOKEN);
        verify(reservationTokenService, never()).cleanup(any());
        verify(reservationTokenService, never()).removeToken(any());
        verify(reservationTokenService, never()).removeApprovalToken(any());
    }

    private PaymentApprovalPayload approvalPayload() {
        return PaymentApprovalPayload.builder()
                .userId(1L)
                .campaignActivityId(2L)
                .productId(3L)
                .campaignActivityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .quantity(1)
                .reservationToken(PRIMARY_TOKEN)
                .build();
    }
}
