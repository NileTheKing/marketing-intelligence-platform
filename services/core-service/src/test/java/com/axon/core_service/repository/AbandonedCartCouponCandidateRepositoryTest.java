package com.axon.core_service.repository;

import com.axon.core_service.campaign.CampaignRepository;

import com.axon.core_service.campaign.CampaignActivityRepository;

import static org.assertj.core.api.Assertions.assertThat;

import com.axon.core_service.domain.marketing.AbandonedCartCouponCampaign;
import com.axon.core_service.domain.marketing.AbandonedCartCouponCandidate;
import com.axon.core_service.domain.marketing.AbandonedCartCouponCandidateStatus;
import com.axon.core_service.domain.marketing.MarketingAction;
import com.axon.core_service.domain.marketing.RewardType;
import com.axon.core_service.campaign.domain.Campaign;
import com.axon.core_service.campaign.domain.CampaignActivity;
import com.axon.core_service.campaign.domain.CouponDeliveryMode;
import com.axon.core_service.campaign.dto.CampaignActivityStatus;
import com.axon.messaging.CampaignActivityType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
@ActiveProfiles("test")
class AbandonedCartCouponCandidateRepositoryTest {

    @Autowired private AbandonedCartCouponCandidateRepository candidateRepository;
    @Autowired private AbandonedCartCouponCampaignRepository campaignRepository;
    @Autowired private MarketingActionRepository actionRepository;
    @Autowired private CampaignRepository parentCampaignRepository;
    @Autowired private CampaignActivityRepository activityRepository;

    @Test
    void fetchesOnlyDueScheduledCandidatesInDueTimeOrderAndClaimsOnce() {
        LocalDateTime now = LocalDateTime.now();
        AbandonedCartCouponCampaign campaign = campaign(CampaignActivityStatus.PAUSED,
                now.minusDays(1), now.plusDays(1));
        AbandonedCartCouponCandidate earliest = saveCandidate(campaign, 1L, now.minusMinutes(3));
        saveCandidate(campaign, 2L, now.plusMinutes(1));
        AbandonedCartCouponCandidate later = saveCandidate(campaign, 3L, now.minusMinutes(1));
        AbandonedCartCouponCandidate cancelled = saveCandidate(campaign, 4L, now.minusMinutes(2));
        ReflectionTestUtils.setField(cancelled, "status", AbandonedCartCouponCandidateStatus.CANCELLED);
        candidateRepository.saveAndFlush(cancelled);

        assertThat(candidateRepository.findExecutableDueCandidates(AbandonedCartCouponCandidateStatus.SCHEDULED,
                now, CampaignActivityStatus.ACTIVE, CouponDeliveryMode.ABANDONED_CART,
                PageRequest.of(0, 500))).isEmpty();

        campaign.getCampaignActivity().changeStatus(CampaignActivityStatus.ACTIVE);
        activityRepository.saveAndFlush(campaign.getCampaignActivity());
        List<AbandonedCartCouponCandidate> due = candidateRepository.findExecutableDueCandidates(
                AbandonedCartCouponCandidateStatus.SCHEDULED, now, CampaignActivityStatus.ACTIVE,
                CouponDeliveryMode.ABANDONED_CART, PageRequest.of(0, 500));

        assertThat(due).extracting(AbandonedCartCouponCandidate::getId)
                .containsExactly(earliest.getId(), later.getId());
        assertThat(candidateRepository.claim(earliest.getId(), AbandonedCartCouponCandidateStatus.SCHEDULED,
                AbandonedCartCouponCandidateStatus.PROCESSING, now, now)).isOne();
        assertThat(candidateRepository.claim(earliest.getId(), AbandonedCartCouponCandidateStatus.SCHEDULED,
                AbandonedCartCouponCandidateStatus.PROCESSING, now, now)).isZero();
    }

    private AbandonedCartCouponCampaign campaign(CampaignActivityStatus status,
                                                  LocalDateTime start, LocalDateTime end) {
        MarketingAction action = actionRepository.save(MarketingAction.builder().marketingRule(null)
                .actionType(RewardType.COUPON).referenceId(100L).isActive(true).build());
        Campaign parent = parentCampaignRepository.save(Campaign.builder().name("parent").build());
        CampaignActivity activity = activityRepository.save(CampaignActivity.builder()
                .campaign(parent).name("cart").status(status).startDate(start).endDate(end)
                .activityType(CampaignActivityType.COUPON)
                .couponDeliveryMode(CouponDeliveryMode.ABANDONED_CART)
                .price(java.math.BigDecimal.ZERO).quantity(0).build());
        return campaignRepository.save(AbandonedCartCouponCampaign.builder()
                .campaignActivity(activity).marketingAction(action).delayMinutes(30).isActive(true).build());
    }

    private AbandonedCartCouponCandidate saveCandidate(AbandonedCartCouponCampaign campaign, Long userId,
                                                        LocalDateTime dueAt) {
        return candidateRepository.saveAndFlush(AbandonedCartCouponCandidate.builder()
                .campaign(campaign).userId(userId).productId(userId)
                .lastCartActivityAt(dueAt.minusMinutes(30)).dueAt(dueAt).build());
    }
}
