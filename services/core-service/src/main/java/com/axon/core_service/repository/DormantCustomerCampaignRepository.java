package com.axon.core_service.repository;

import com.axon.core_service.domain.marketing.DormantCustomerCampaign;
import java.util.List;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DormantCustomerCampaignRepository extends JpaRepository<DormantCustomerCampaign, Long> {

    @EntityGraph(attributePaths = {"marketingAction", "campaignActivity", "campaignActivity.campaign"})
    @Query("select c from DormantCustomerCampaign c where c.marketingAction.isActive = true "
            + "and c.marketingAction.actionType = com.axon.core_service.domain.marketing.RewardType.COUPON "
            + "and c.campaignActivity.status = :active "
            + "and c.campaignActivity.couponDeliveryMode = :mode "
            + "and c.campaignActivity.activityType = com.axon.messaging.CampaignActivityType.COUPON "
            + "and c.campaignActivity.startDate <= :now and c.campaignActivity.endDate >= :now "
            + "and (c.campaignActivity.campaign.startAt is null or c.campaignActivity.campaign.startAt <= :now) "
            + "and (c.campaignActivity.campaign.endAt is null or c.campaignActivity.campaign.endAt >= :now)")
    List<DormantCustomerCampaign> findExecutableCampaigns(
            @Param("active") com.axon.core_service.campaign.dto.CampaignActivityStatus active,
            @Param("mode") com.axon.core_service.campaign.domain.CouponDeliveryMode mode,
            @Param("now") LocalDateTime now);

    @Query("select case when count(c) > 0 then true else false end from DormantCustomerCampaign c "
            + "where c.id = :id and c.marketingAction.isActive = true and c.marketingAction.actionType = "
            + "com.axon.core_service.domain.marketing.RewardType.COUPON "
            + "and c.campaignActivity.status = :active and c.campaignActivity.couponDeliveryMode = :mode "
            + "and c.campaignActivity.activityType = com.axon.messaging.CampaignActivityType.COUPON "
            + "and c.campaignActivity.startDate <= :now and c.campaignActivity.endDate >= :now "
            + "and (c.campaignActivity.campaign.startAt is null or c.campaignActivity.campaign.startAt <= :now) "
            + "and (c.campaignActivity.campaign.endAt is null or c.campaignActivity.campaign.endAt >= :now)")
    boolean existsExecutableById(@Param("id") Long id,
            @Param("active") com.axon.core_service.campaign.dto.CampaignActivityStatus active,
            @Param("mode") com.axon.core_service.campaign.domain.CouponDeliveryMode mode,
            @Param("now") LocalDateTime now);
}
