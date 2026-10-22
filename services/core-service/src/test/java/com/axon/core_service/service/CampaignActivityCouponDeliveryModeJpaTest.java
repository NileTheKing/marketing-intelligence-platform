package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.campaignactivity.CampaignActivityPurpose;
import com.axon.core_service.domain.campaignactivity.CouponDeliveryMode;
import com.axon.core_service.domain.coupon.Coupon;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityRequest;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.core_service.domain.marketing.AbandonedCartCouponCampaign;
import com.axon.core_service.domain.marketing.AbandonedCartCouponCandidate;
import com.axon.core_service.domain.marketing.AbandonedCartCouponCandidateStatus;
import com.axon.core_service.repository.AbandonedCartCouponCampaignRepository;
import com.axon.core_service.repository.AbandonedCartCouponCandidateRepository;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.CampaignRepository;
import com.axon.core_service.repository.CouponRepository;
import com.axon.messaging.CampaignActivityType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DataJpaTest
@ActiveProfiles("test")
@Import(CampaignActivityService.class)
class CampaignActivityCouponDeliveryModeJpaTest {

    @Autowired private CampaignActivityService campaignActivityService;
    @Autowired private CampaignRepository campaignRepository;
    @Autowired private CampaignActivityRepository activityRepository;
    @Autowired private CouponRepository couponRepository;
    @Autowired private AbandonedCartCouponCampaignRepository abandonedCartRepository;
    @Autowired private AbandonedCartCouponCandidateRepository candidateRepository;
    @MockitoBean private StringRedisTemplate redisTemplate;
    @PersistenceContext private EntityManager entityManager;

    @Test
    void registrationPersistsLinkedConfigAndLifecycleAndParentWindowGateCandidates() {
        LocalDateTime now = LocalDateTime.now();
        Campaign parent = campaignRepository.saveAndFlush(Campaign.builder().name("parent").build());
        Coupon coupon = couponRepository.saveAndFlush(Coupon.builder().name("coupon")
                .discountAmount(BigDecimal.ONE).startDate(now.minusDays(1)).endDate(now.plusDays(10)).build());

        var response = campaignActivityService.createCampaignActivity(parent.getId(),
                CampaignActivityRequest.builder().name("abandoned cart").limitCount(10)
                        .status(CampaignActivityStatus.ACTIVE).startDate(now.minusDays(1)).endDate(now.plusDays(1))
                        .activityType(CampaignActivityType.COUPON).couponId(coupon.getId())
                        .couponDeliveryMode(CouponDeliveryMode.ABANDONED_CART).delayMinutes(30)
                        .purpose(CampaignActivityPurpose.REENGAGEMENT).operatorMemo("cart follow-up").build());

        assertThat(response.getCouponDeliveryMode()).isEqualTo(CouponDeliveryMode.ABANDONED_CART);
        assertThat(response.getDelayMinutes()).isEqualTo(30);
        entityManager.flush();
        entityManager.clear();
        CampaignActivity activity = activityRepository.findById(response.getId()).orElseThrow();
        AbandonedCartCouponCampaign config = abandonedCartRepository.findAll().get(0);
        assertThat(config.getCampaignActivity().getId()).isEqualTo(activity.getId());
        assertThat(config.getMarketingAction().getId()).isEqualTo(activity.getMarketingAction().getId());
        assertThat(config.getDelayMinutes()).isEqualTo(30);

        AbandonedCartCouponCandidate candidate = candidateRepository.saveAndFlush(
                AbandonedCartCouponCandidate.builder().campaign(config).userId(7L).productId(8L)
                        .lastCartActivityAt(now.minusMinutes(31)).dueAt(now.minusMinutes(1)).build());
        assertDue(now, candidate.getId());

        campaignActivityService.changeCampaignActivityStatus(activity.getId(), CampaignActivityStatus.PAUSED);
        assertThat(dueCandidates(now)).isEmpty();
        campaignActivityService.changeCampaignActivityStatus(activity.getId(), CampaignActivityStatus.ACTIVE);
        assertDue(now, candidate.getId());

        entityManager.flush();
        entityManager.clear();
        Campaign scheduledParent = campaignRepository.findById(parent.getId()).orElseThrow();
        scheduledParent.updateSchedule(now.plusHours(1), now.plusDays(2));
        campaignRepository.saveAndFlush(scheduledParent);
        entityManager.clear();
        assertThat(dueCandidates(now)).isEmpty();
        Campaign resumedParent = campaignRepository.findById(parent.getId()).orElseThrow();
        resumedParent.updateSchedule(null, null);
        campaignRepository.saveAndFlush(resumedParent);
        entityManager.clear();
        assertDue(now, candidate.getId());
    }

    private java.util.List<AbandonedCartCouponCandidate> dueCandidates(LocalDateTime now) {
        return candidateRepository.findExecutableDueCandidates(AbandonedCartCouponCandidateStatus.SCHEDULED,
                now, CampaignActivityStatus.ACTIVE, CouponDeliveryMode.ABANDONED_CART, PageRequest.of(0, 10));
    }

    private void assertDue(LocalDateTime now, Long candidateId) {
        assertThat(dueCandidates(now)).extracting(AbandonedCartCouponCandidate::getId).containsExactly(candidateId);
    }
}
