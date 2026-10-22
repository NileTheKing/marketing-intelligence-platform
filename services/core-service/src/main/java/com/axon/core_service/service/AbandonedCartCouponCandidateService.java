package com.axon.core_service.service;

import com.axon.core_service.domain.marketing.AbandonedCartCouponCampaign;
import com.axon.core_service.domain.marketing.AbandonedCartCouponCandidate;
import com.axon.core_service.domain.marketing.AbandonedCartCouponCandidateStatus;
import com.axon.core_service.domain.marketing.MarketingAction;
import com.axon.core_service.domain.marketing.MarketingActionDispatch;
import com.axon.core_service.campaign.domain.CouponDeliveryMode;
import com.axon.core_service.campaign.dto.CampaignActivityStatus;
import com.axon.core_service.domain.purchase.PurchaseStatus;
import com.axon.core_service.repository.AbandonedCartCouponCampaignRepository;
import com.axon.core_service.repository.AbandonedCartCouponCandidateRepository;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.messaging.CampaignActivityType;
import com.axon.messaging.dto.CampaignActivityKafkaProducerDto;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AbandonedCartCouponCandidateService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final AbandonedCartCouponCampaignRepository campaignRepository;
    private final AbandonedCartCouponCandidateRepository candidateRepository;
    private final PurchaseRepository purchaseRepository;
    private final MarketingActionExecutionService executionService;

    @Transactional
    public void scheduleForCartItem(Long userId, Long productId, Instant occurredAt) {
        if (userId == null || productId == null) {
            return;
        }

        LocalDateTime cartActivityAt = toBusinessTime(occurredAt);
        for (AbandonedCartCouponCampaign campaign : campaignRepository.findExecutableCampaigns(
                CampaignActivityStatus.ACTIVE, CouponDeliveryMode.ABANDONED_CART, cartActivityAt)) {
            LocalDateTime dueAt = cartActivityAt.plusMinutes(campaign.getDelayMinutes());
            candidateRepository.findByCampaign_IdAndUserIdAndProductId(campaign.getId(), userId, productId)
                    .ifPresentOrElse(
                            candidate -> candidate.reschedule(cartActivityAt, dueAt),
                            () -> candidateRepository.save(AbandonedCartCouponCandidate.builder()
                                    .campaign(campaign)
                                    .userId(userId)
                                    .productId(productId)
                                    .lastCartActivityAt(cartActivityAt)
                                    .dueAt(dueAt)
                                    .build()));
        }
    }

    @Transactional
    public void cancelScheduledForPurchaseOrRemoval(Long userId, Long productId) {
        if (userId == null || productId == null) {
            return;
        }
        candidateRepository.cancelScheduledByUserIdAndProductId(userId, productId,
                AbandonedCartCouponCandidateStatus.SCHEDULED,
                AbandonedCartCouponCandidateStatus.PROCESSING,
                AbandonedCartCouponCandidateStatus.CANCELLED);
    }

    /**
     * Claims one due candidate and creates the existing marketing dispatch before Kafka publication.
     * Returning empty means another runner claimed it or a purchase made it ineligible.
     */
    @Transactional
    public PreparedDispatch claimAndPrepare(Long candidateId, LocalDateTime now) {
        boolean claimed = candidateRepository.claim(candidateId,
                AbandonedCartCouponCandidateStatus.SCHEDULED,
                AbandonedCartCouponCandidateStatus.PROCESSING, now, now) == 1;
        if (!claimed) {
            return null;
        }

        AbandonedCartCouponCandidate candidate = candidateRepository.findByIdWithCampaign(candidateId).orElseThrow();
        MarketingAction action = candidate.getCampaign().getMarketingAction();
        if (!isExecutable(candidate.getCampaign(), now)) {
            candidateRepository.releaseClaim(candidateId,
                    AbandonedCartCouponCandidateStatus.PROCESSING,
                    AbandonedCartCouponCandidateStatus.SCHEDULED);
            return null;
        }
        if (!action.isActive() || action.getActionType() != com.axon.core_service.domain.marketing.RewardType.COUPON) {
            candidateRepository.cancelProcessing(candidateId,
                    AbandonedCartCouponCandidateStatus.PROCESSING,
                    AbandonedCartCouponCandidateStatus.CANCELLED);
            return null;
        }
        boolean purchasedAfterCartActivity = purchaseRepository
                .existsByUserIdAndProductIdAndStatusAndPurchaseAtGreaterThanEqual(
                        candidate.getUserId(), candidate.getProductId(), PurchaseStatus.CONFIRMED,
                        candidate.getLastCartActivityAt());
        if (purchasedAfterCartActivity) {
            candidateRepository.cancelProcessing(candidateId,
                    AbandonedCartCouponCandidateStatus.PROCESSING,
                    AbandonedCartCouponCandidateStatus.CANCELLED);
            return null;
        }

        MarketingActionDispatch dispatch = executionService.createPending(
                action.getId(), null, action.getReferenceId(), candidate.getUserId(), candidate.getProductId(),
                action.getActionType());
        if (!executionService.markDispatching(dispatch.getId())) {
            candidateRepository.releaseClaim(candidateId,
                    AbandonedCartCouponCandidateStatus.PROCESSING,
                    AbandonedCartCouponCandidateStatus.SCHEDULED);
            return null;
        }

        CampaignActivityKafkaProducerDto command = CampaignActivityKafkaProducerDto.builder()
                .campaignActivityType(CampaignActivityType.COUPON)
                .userId(candidate.getUserId())
                .productId(candidate.getProductId())
                .marketingActionId(action.getId())
                .actionReferenceId(action.getReferenceId())
                .executionId(dispatch.getExecution().getId())
                .dispatchId(dispatch.getId())
                .timestamp(System.currentTimeMillis())
                .build();
        return new PreparedDispatch(candidateId, dispatch.getExecution().getId(), dispatch.getId(), command);
    }

    @Transactional
    public void markPublished(PreparedDispatch prepared) {
        candidateRepository.markDispatched(prepared.candidateId(), prepared.executionId(),
                AbandonedCartCouponCandidateStatus.PROCESSING,
                AbandonedCartCouponCandidateStatus.DISPATCHED);
        executionService.markDispatched(prepared.dispatchId());
    }

    @Transactional
    public void releaseAfterPublishFailure(PreparedDispatch prepared, String reason) {
        candidateRepository.releaseClaim(prepared.candidateId(),
                AbandonedCartCouponCandidateStatus.PROCESSING,
                AbandonedCartCouponCandidateStatus.SCHEDULED);
        executionService.markDispatchFailed(prepared.dispatchId(), reason);
    }

    public List<AbandonedCartCouponCandidate> findDueCandidates(LocalDateTime now, int batchSize) {
        return candidateRepository.findExecutableDueCandidates(
                AbandonedCartCouponCandidateStatus.SCHEDULED, now, CampaignActivityStatus.ACTIVE,
                CouponDeliveryMode.ABANDONED_CART,
                org.springframework.data.domain.PageRequest.of(0, batchSize));
    }

    private boolean isExecutable(AbandonedCartCouponCampaign config, LocalDateTime now) {
        var activity = config.getCampaignActivity();
        if (activity == null || activity.getActivityType() != CampaignActivityType.COUPON
                || activity.getCouponDeliveryMode() != CouponDeliveryMode.ABANDONED_CART
                || activity.getStatus() != CampaignActivityStatus.ACTIVE
                || now.isBefore(activity.getStartDate()) || now.isAfter(activity.getEndDate())) {
            return false;
        }
        var campaign = activity.getCampaign();
        return campaign != null
                && (campaign.getStartAt() == null || !now.isBefore(campaign.getStartAt()))
                && (campaign.getEndAt() == null || !now.isAfter(campaign.getEndAt()));
    }

    private LocalDateTime toBusinessTime(Instant occurredAt) {
        return LocalDateTime.ofInstant(occurredAt != null ? occurredAt : Instant.now(), BUSINESS_ZONE);
    }

    public record PreparedDispatch(Long candidateId, Long executionId, Long dispatchId,
                                   CampaignActivityKafkaProducerDto command) {
    }
}
