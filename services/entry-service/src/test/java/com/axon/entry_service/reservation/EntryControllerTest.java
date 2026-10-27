package com.axon.entry_service.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.axon.entry_service.reservation.domain.CampaignActivityMeta;
import com.axon.entry_service.reservation.domain.CampaignActivityStatus;
import com.axon.entry_service.reservation.domain.ReservationResult;
import com.axon.entry_service.reservation.domain.ReservationStatus;
import com.axon.entry_service.reservation.dto.EntryRequestDto;
import com.axon.entry_service.payment.CheckoutTokenService;
import com.axon.messaging.dto.payment.ReservationTokenPayload;
import com.axon.messaging.topic.KafkaTopics;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;

@org.junit.jupiter.api.extension.ExtendWith(MockitoExtension.class)
class EntryControllerTest {

    @Mock
    private EntryReservationService reservationService;

    @Mock
    private CampaignActivityMetaService campaignActivityMetaService;

    @Mock
    private CoreValidationService coreValidationService;

    @Mock
    private FastValidationService fastValidationService;

    @Mock
    private CheckoutTokenService reservationTokenService;

    @Mock
    private CouponEntryService couponEntryService;

    @InjectMocks
    private EntryApplicationService entryApplicationService;

    private EntryController entryController;

    private EntryRequestDto requestDto;
    private User userDetails;
    private CampaignActivityMeta activeMeta;

    @BeforeEach
    void setUp() {
        entryController = new EntryController(entryApplicationService);

        requestDto = new EntryRequestDto();
        requestDto.setCampaignActivityId(1L);
        requestDto.setProductId(10L);

        userDetails = (User) User.withUsername("42")
                .password("dummy")
                .roles("USER")
                .build();

        activeMeta = new CampaignActivityMeta(1L, 1L, 100, CampaignActivityStatus.ACTIVE, null, null, java.util.Collections.emptyList(), false, false, 10L, null, com.axon.messaging.CampaignActivityType.FIRST_COME_FIRST_SERVE, null);
    }

    @Test
    void issueCouponAllowsOnlyServerDeclaredDirectCoupon() {
        var meta = couponMeta(com.axon.messaging.CampaignActivityType.COUPON, "DIRECT");
        when(campaignActivityMetaService.getMeta(1L)).thenReturn(meta);
        when(couponEntryService.publishCouponIssue(any())).thenReturn(true);
        requestDto.setCampaignActivityType(com.axon.messaging.CampaignActivityType.FIRST_COME_FIRST_SERVE);

        assertThat(entryController.issueCoupon(requestDto, "FAKETOKEN", userDetails).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        org.mockito.Mockito.verify(couponEntryService).publishCouponIssue(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ABANDONED_CART", "DORMANT_CUSTOMER", "UNKNOWN"})
    void issueCouponRejectsNonDirectOrUnknownMode(String mode) {
        when(campaignActivityMetaService.getMeta(1L))
                .thenReturn(couponMeta(com.axon.messaging.CampaignActivityType.COUPON, mode));
        assertThat(entryController.issueCoupon(requestDto, "FAKETOKEN", userDetails).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        org.mockito.Mockito.verifyNoInteractions(couponEntryService);
    }

    @Test
    void issueCouponRejectsNonCouponEvenIfModeIsDirect() {
        when(campaignActivityMetaService.getMeta(1L))
                .thenReturn(couponMeta(com.axon.messaging.CampaignActivityType.FIRST_COME_FIRST_SERVE, "DIRECT"));
        requestDto.setCampaignActivityType(com.axon.messaging.CampaignActivityType.COUPON);
        assertThat(entryController.issueCoupon(requestDto, "FAKETOKEN", userDetails).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        org.mockito.Mockito.verifyNoInteractions(couponEntryService);
    }

    private CampaignActivityMeta couponMeta(com.axon.messaging.CampaignActivityType type, String mode) {
        return new CampaignActivityMeta(1L, 1L, 100, CampaignActivityStatus.ACTIVE, null, null,
                java.util.List.of(), false, false, null, 10L, type, mode);
    }

    @Test
    void createEntry_successReturnsAcceptedAndPublishesEvent() {
        when(campaignActivityMetaService.getMeta(1L)).thenReturn(activeMeta);
        when(reservationTokenService.generateDeterministicToken(any(Long.class), any(Long.class))).thenReturn("DETERMINISTIC_TOKEN");
        when(reservationTokenService.getPayloadFromToken(anyString())).thenReturn(java.util.Optional.empty());
        when(reservationTokenService.issueToken(any(ReservationTokenPayload.class))).thenReturn("TOKEN");
        when(reservationService.reserve(eq(1L), eq(42L), eq(activeMeta), any(Instant.class)))
                .thenReturn(ReservationResult.success(1L));

        ResponseEntity<?> response = entryController.createEntry(requestDto,"FAKETOKEN",userDetails);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void createEntry_duplicateReturnsConflict() {
        when(campaignActivityMetaService.getMeta(1L)).thenReturn(activeMeta);
        when(reservationService.reserve(eq(1L), eq(42L), eq(activeMeta), any(Instant.class)))
                .thenReturn(ReservationResult.duplicated());

        ResponseEntity<?> response = entryController.createEntry(requestDto, "FAKETOKEN",userDetails);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createEntry_soldOutReturnsGone() {
        when(campaignActivityMetaService.getMeta(1L)).thenReturn(activeMeta);
        when(reservationService.reserve(eq(1L), eq(42L), eq(activeMeta), any(Instant.class)))
                .thenReturn(ReservationResult.soldOut());

        ResponseEntity<?> response = entryController.createEntry(requestDto, "FAKETOKEN",userDetails);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void createEntry_closedReturnsBadRequest() {
        when(campaignActivityMetaService.getMeta(1L)).thenReturn(activeMeta);
        when(reservationService.reserve(eq(1L), eq(42L), eq(activeMeta), any(Instant.class)))
                .thenReturn(ReservationResult.closed());

        ResponseEntity<?> response = entryController.createEntry(requestDto, "FAKETOKEN", userDetails);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createEntry_metaNotFoundReturnsNotFound() {
        when(campaignActivityMetaService.getMeta(1L)).thenReturn(null);

        ResponseEntity<?> response = entryController.createEntry(requestDto, "FAKETOKEN", userDetails);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createEntry_errorReturnsInternalServerError() {
        when(campaignActivityMetaService.getMeta(1L)).thenReturn(activeMeta);
        when(reservationService.reserve(eq(1L), eq(42L), eq(activeMeta), any(Instant.class)))
                .thenReturn(ReservationResult.error());

        ResponseEntity<?> response = entryController.createEntry(requestDto, "FAKETOKEN", userDetails);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
