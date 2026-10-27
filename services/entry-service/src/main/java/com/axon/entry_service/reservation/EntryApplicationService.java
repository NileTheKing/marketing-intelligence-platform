package com.axon.entry_service.reservation;

import com.axon.entry_service.reservation.domain.CampaignActivityMeta;
import com.axon.entry_service.reservation.domain.CampaignActivityStatus;
import com.axon.entry_service.reservation.domain.ReservationResult;
import com.axon.entry_service.reservation.domain.ReservationStatus;
import com.axon.entry_service.reservation.dto.EntryRequestDto;
import com.axon.entry_service.payment.dto.PaymentConfirmationResponse;
import com.axon.entry_service.payment.CheckoutTokenService;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.payment.ReservationTokenPayload;
import com.axon.messaging.dto.validation.ValidationResponse;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EntryApplicationService {

    private final EntryReservationService reservationService;
    private final CampaignActivityMetaService campaignActivityMetaService;
    private final CoreValidationService coreValidationService;
    private final FastValidationService fastValidationService;
    private final CheckoutTokenService reservationTokenService;

    public EntryUseCaseResult createEntry(EntryRequestDto requestDto, String token, long userId) {
        long campaignActivityId = requestDto.getCampaignActivityId();
        Instant now = Instant.now();

        CampaignActivityMeta meta = campaignActivityMetaService.getMeta(campaignActivityId);
        if (meta == null) {
            return EntryUseCaseResult.noBody(EntryUseCaseStatus.NOT_FOUND);
        }

        if (meta.productId() != null && !meta.productId().equals(requestDto.getProductId())) {
            log.warn("요청 중에 상품 정보가 일치하지 않는 요청이 있습니다. Meta {} || Request {}",
                    meta.productId(), requestDto.getProductId());
            return EntryUseCaseResult.badRequest(
                    PaymentConfirmationResponse.failure(ReservationResult.error(), "상품 정보가 일치하지 않습니다."));
        }

        CampaignActivityType requestedType = requestDto.getCampaignActivityType() != null
                ? requestDto.getCampaignActivityType()
                : CampaignActivityType.FIRST_COME_FIRST_SERVE;
        if (meta.campaignActivityType() != null && !meta.campaignActivityType().equals(requestedType)) {
            log.warn("요청 정보 중에 캠페인 타입이 다른 요청이 있습니다. Meta {} || Request {}",
                    meta.campaignActivityType(), requestedType);
            return EntryUseCaseResult.badRequest(
                    PaymentConfirmationResponse.failure(ReservationResult.error(), "캠페인 타입이 일치하지 않습니다."));
        }

        String deterministicToken = reservationTokenService.generateDeterministicToken(userId, campaignActivityId);
        Optional<ReservationTokenPayload> existingToken = reservationTokenService.getPayloadFromToken(deterministicToken);

        if (existingToken.isPresent()) {
            log.info("재결제 시나리오: 기존 1차 토큰 재사용, userId={}, campaignActivityId={}",
                    userId, campaignActivityId);
            return EntryUseCaseResult.ok(PaymentConfirmationResponse.successWithRetry(deterministicToken));
        }

        if (meta.hasFastValidation()) {
            try {
                fastValidationService.fastValidation(userId, meta);
            } catch (FastValidationException e) {
                log.info("{}번 사용자가 [빠른검증]: {} 조건에서 실패!", userId, e.getType());
                return EntryUseCaseResult.badRequest(
                        PaymentConfirmationResponse.failure(ReservationResult.error(), e.getMessage()));
            }
        }

        if (meta.hasHeavyValidation()) {
            ValidationResponse response = coreValidationService.isEligible(token, requestDto.getCampaignActivityId());
            if (!response.isEligible()) {
                log.info("{} 사용자의 요청이 {}번 응모요청의 자격미달로 통과하지 못했습니다.",
                        userId, requestDto.getCampaignActivityId());
                return EntryUseCaseResult.badRequest(
                        PaymentConfirmationResponse.failure(ReservationResult.error(), response.getErrorMessage()));
            }
        }

        ReservationResult result = reservationService.reserve(campaignActivityId, userId, meta, now);

        if (result.status() == ReservationStatus.DUPLICATED) {
            return EntryUseCaseResult.noBody(EntryUseCaseStatus.CONFLICT);
        }
        if (result.status() == ReservationStatus.SOLD_OUT) {
            return EntryUseCaseResult.noBody(EntryUseCaseStatus.GONE);
        }
        if (result.status() == ReservationStatus.CLOSED) {
            return EntryUseCaseResult.noBody(EntryUseCaseStatus.BAD_REQUEST);
        }
        if (!result.isSuccess()) {
            return EntryUseCaseResult.noBody(EntryUseCaseStatus.INTERNAL_SERVER_ERROR);
        }

        ReservationTokenPayload tokenPayload = ReservationTokenPayload.builder()
                .userId(userId)
                .campaignActivityId(meta.id())
                .productId(meta.productId())
                .campaignActivityType(meta.campaignActivityType())
                .quantity(requestDto.getQuantity())
                .build();
        String reservationToken = reservationTokenService.issueToken(tokenPayload);
        return EntryUseCaseResult.ok(PaymentConfirmationResponse.success(reservationToken));
    }
}
