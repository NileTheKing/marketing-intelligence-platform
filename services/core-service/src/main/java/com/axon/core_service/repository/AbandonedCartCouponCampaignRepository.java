package com.axon.core_service.repository;

import com.axon.core_service.domain.marketing.AbandonedCartCouponCampaign;
import java.util.List;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AbandonedCartCouponCampaignRepository extends JpaRepository<AbandonedCartCouponCampaign, Long> {

    @EntityGraph(attributePaths = {"marketingAction", "campaignActivity", "campaignActivity.campaign"})
    @Query("select c from AbandonedCartCouponCampaign c where c.marketingAction.isActive = true "
            + "and c.marketingAction.actionType = com.axon.core_service.domain.marketing.RewardType.COUPON "
            + "and c.campaignActivity.status = :active "
            + "and c.campaignActivity.couponDeliveryMode = :mode "
            + "and c.campaignActivity.activityType = com.axon.messaging.CampaignActivityType.COUPON "
            + "and c.campaignActivity.startDate <= :now and c.campaignActivity.endDate >= :now "
            + "and (c.campaignActivity.campaign.startAt is null or c.campaignActivity.campaign.startAt <= :now) "
            + "and (c.campaignActivity.campaign.endAt is null or c.campaignActivity.campaign.endAt >= :now)")
    List<AbandonedCartCouponCampaign> findExecutableCampaigns(
            @Param("active") com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus active,
            @Param("mode") com.axon.core_service.domain.campaignactivity.CouponDeliveryMode mode,
            @Param("now") LocalDateTime now);
}
