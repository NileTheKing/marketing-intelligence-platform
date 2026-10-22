package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.axon.core_service.domain.marketing.AbandonedCartCouponCampaign;
import com.axon.core_service.domain.marketing.AbandonedCartCouponCandidate;
import com.axon.core_service.domain.marketing.AbandonedCartCouponCandidateStatus;
import com.axon.core_service.domain.marketing.MarketingAction;
import com.axon.core_service.domain.marketing.MarketingActionDispatch;
import com.axon.core_service.domain.marketing.MarketingActionExecution;
import com.axon.core_service.domain.marketing.RewardType;
import com.axon.core_service.campaign.domain.Campaign;
import com.axon.core_service.campaign.domain.CampaignActivity;
import com.axon.core_service.campaign.domain.CouponDeliveryMode;
import com.axon.core_service.campaign.dto.CampaignActivityStatus;
import com.axon.messaging.CampaignActivityType;
import com.axon.core_service.repository.AbandonedCartCouponCampaignRepository;
import com.axon.core_service.repository.AbandonedCartCouponCandidateRepository;
import com.axon.core_service.repository.PurchaseRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AbandonedCartCouponCandidateServiceTest {

    @Mock private AbandonedCartCouponCampaignRepository campaignRepository;
    @Mock private AbandonedCartCouponCandidateRepository candidateRepository;
    @Mock private PurchaseRepository purchaseRepository;
    @Mock private MarketingActionExecutionService executionService;

    @Test
    void cartActivityCreatesCandidateWithConfiguredDelay() {
        AbandonedCartCouponCampaign campaign = campaign(10L, 30);
        when(campaignRepository.findExecutableCampaigns(any(), any(), any())).thenReturn(List.of(campaign));
        when(candidateRepository.findByCampaign_IdAndUserIdAndProductId(10L, 7L, 9L))
                .thenReturn(Optional.empty());
        Instant occurredAt = Instant.parse("2026-10-14T00:00:00Z");

        service().scheduleForCartItem(7L, 9L, occurredAt);

        ArgumentCaptor<AbandonedCartCouponCandidate> captor =
                ArgumentCaptor.forClass(AbandonedCartCouponCandidate.class);
        verify(candidateRepository).save(captor.capture());
        LocalDateTime expected = LocalDateTime.ofInstant(occurredAt, ZoneId.of("Asia/Seoul"));
        assertThat(captor.getValue().getLastCartActivityAt()).isEqualTo(expected);
        assertThat(captor.getValue().getDueAt()).isEqualTo(expected.plusMinutes(30));
        assertThat(captor.getValue().getStatus()).isEqualTo(AbandonedCartCouponCandidateStatus.SCHEDULED);
    }

    @Test
    void purchaseRecheckCancelsClaimedCandidateBeforeCommandCreation() {
        AbandonedCartCouponCandidate candidate = candidate(20L, 7L, 9L);
        when(candidateRepository.claim(eq(20L), any(), any(), any(), any())).thenReturn(1);
        when(candidateRepository.findByIdWithCampaign(20L)).thenReturn(Optional.of(candidate));
        when(purchaseRepository.existsByUserIdAndProductIdAndStatusAndPurchaseAtGreaterThanEqual(
                eq(7L), eq(9L), any(), eq(candidate.getLastCartActivityAt()))).thenReturn(true);

        var prepared = service().claimAndPrepare(20L, LocalDateTime.now());

        assertThat(prepared).isNull();
        verify(candidateRepository).cancelProcessing(20L,
                AbandonedCartCouponCandidateStatus.PROCESSING,
                AbandonedCartCouponCandidateStatus.CANCELLED);
    }

    @Test
    void pausedActivityReleasesDueCandidateWithoutCancellingIt() {
        AbandonedCartCouponCandidate candidate = candidate(20L, 7L, 9L, CampaignActivityStatus.PAUSED,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));
        when(candidateRepository.claim(eq(20L), any(), any(), any(), any())).thenReturn(1);
        when(candidateRepository.findByIdWithCampaign(20L)).thenReturn(Optional.of(candidate));

        assertThat(service().claimAndPrepare(20L, LocalDateTime.now())).isNull();

        verify(candidateRepository).releaseClaim(20L, AbandonedCartCouponCandidateStatus.PROCESSING,
                AbandonedCartCouponCandidateStatus.SCHEDULED);
        verify(candidateRepository, never()).cancelProcessing(any(), any(), any());
        verify(executionService, never()).createPending(any(), any(), any(), any(), any(), any());
    }

    @Test
    void futureAndEndedActivityWindowsReleaseDueCandidates() {
        for (int i = 0; i < 2; i++) {
            Long id = 21L + i;
            LocalDateTime start = i == 0 ? LocalDateTime.now().plusDays(1) : LocalDateTime.now().minusDays(3);
            LocalDateTime end = i == 0 ? LocalDateTime.now().plusDays(3) : LocalDateTime.now().minusDays(1);
            AbandonedCartCouponCandidate candidate = candidate(id, 7L, 9L,
                    CampaignActivityStatus.ACTIVE, start, end);
            when(candidateRepository.claim(eq(id), any(), any(), any(), any())).thenReturn(1);
            when(candidateRepository.findByIdWithCampaign(id)).thenReturn(Optional.of(candidate));

            assertThat(service().claimAndPrepare(id, LocalDateTime.now())).isNull();
            verify(candidateRepository).releaseClaim(id, AbandonedCartCouponCandidateStatus.PROCESSING,
                    AbandonedCartCouponCandidateStatus.SCHEDULED);
        }
    }

    @Test
    void preparesExistingCouponCommandAndMarksCandidateAfterKafkaAcceptance() {
        AbandonedCartCouponCandidate candidate = candidate(20L, 7L, 9L);
        MarketingActionDispatch dispatch = org.mockito.Mockito.mock(MarketingActionDispatch.class);
        MarketingActionExecution execution = MarketingActionExecution.builder()
                .actionId(5L).actionReferenceId(30L).userId(7L).productId(9L).channel(RewardType.COUPON).build();
        ReflectionTestUtils.setField(execution, "id", 40L);
        when(dispatch.getId()).thenReturn(50L);
        when(dispatch.getExecution()).thenReturn(execution);
        when(candidateRepository.claim(eq(20L), any(), any(), any(), any())).thenReturn(1);
        when(candidateRepository.findByIdWithCampaign(20L)).thenReturn(Optional.of(candidate));
        when(purchaseRepository.existsByUserIdAndProductIdAndStatusAndPurchaseAtGreaterThanEqual(
                eq(7L), eq(9L), any(), any())).thenReturn(false);
        when(executionService.createPending(5L, null, 30L, 7L, 9L, RewardType.COUPON)).thenReturn(dispatch);
        when(executionService.markDispatching(50L)).thenReturn(true);

        var prepared = service().claimAndPrepare(20L, LocalDateTime.now());
        service().markPublished(prepared);

        assertThat(prepared.command().getActionReferenceId()).isEqualTo(30L);
        assertThat(prepared.command().getExecutionId()).isEqualTo(40L);
        verify(candidateRepository).markDispatched(20L, 40L,
                AbandonedCartCouponCandidateStatus.PROCESSING,
                AbandonedCartCouponCandidateStatus.DISPATCHED);
        verify(executionService).markDispatched(50L);
    }

    private AbandonedCartCouponCandidateService service() {
        return new AbandonedCartCouponCandidateService(campaignRepository, candidateRepository,
                purchaseRepository, executionService);
    }

    private AbandonedCartCouponCampaign campaign(Long id, int delayMinutes) {
        return campaign(id, delayMinutes, CampaignActivityStatus.ACTIVE,
                LocalDateTime.of(2020, 1, 1, 0, 0), LocalDateTime.of(2035, 1, 1, 0, 0));
    }

    private AbandonedCartCouponCampaign campaign(Long id, int delayMinutes, CampaignActivityStatus status,
                                                   LocalDateTime start, LocalDateTime end) {
        MarketingAction action = MarketingAction.builder().marketingRule(null)
                .actionType(RewardType.COUPON).referenceId(30L).isActive(true).build();
        ReflectionTestUtils.setField(action, "id", 5L);
        AbandonedCartCouponCampaign campaign = AbandonedCartCouponCampaign.builder()
                .marketingAction(action).delayMinutes(delayMinutes).isActive(true)
                .campaignActivity(activity(status, start, end)).build();
        ReflectionTestUtils.setField(campaign, "id", id);
        return campaign;
    }

    private AbandonedCartCouponCandidate candidate(Long id, Long userId, Long productId) {
        return candidate(id, userId, productId, CampaignActivityStatus.ACTIVE,
                LocalDateTime.of(2020, 1, 1, 0, 0), LocalDateTime.of(2035, 1, 1, 0, 0));
    }

    private AbandonedCartCouponCandidate candidate(Long id, Long userId, Long productId,
                                                     CampaignActivityStatus status,
                                                     LocalDateTime start, LocalDateTime end) {
        AbandonedCartCouponCandidate candidate = AbandonedCartCouponCandidate.builder()
                .campaign(campaign(10L, 30, status, start, end)).userId(userId).productId(productId)
                .lastCartActivityAt(LocalDateTime.now().minusMinutes(31))
                .dueAt(LocalDateTime.now().minusMinutes(1)).build();
        ReflectionTestUtils.setField(candidate, "id", id);
        return candidate;
    }

    private CampaignActivity activity(CampaignActivityStatus status, LocalDateTime start, LocalDateTime end) {
        return CampaignActivity.builder()
                .campaign(Campaign.builder().name("parent").build())
                .name("cart coupon")
                .status(status)
                .startDate(start)
                .endDate(end)
                .activityType(CampaignActivityType.COUPON)
                .couponDeliveryMode(CouponDeliveryMode.ABANDONED_CART)
                .build();
    }
}
