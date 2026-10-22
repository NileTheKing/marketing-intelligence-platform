package com.axon.core_service.repository;

import com.axon.core_service.domain.dto.dashboard.PurchaseAggregate;
import com.axon.core_service.domain.dto.dashboard.PurchaseAggregateByCampaign;
import com.axon.core_service.domain.purchase.Purchase;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Attributes shop purchases made with a coupon back to the campaign that dispatched the coupon.
 *
 * Shop purchases carry only {@code userCouponId} (no campaign activity), so the path is
 * Purchase -> UserCoupon -> MarketingActionExecution -> CampaignActivityRun -> Campaign.
 * Coupons whose execution has no campaign run (behavior rules, abandoned cart, re-engagement,
 * or no execution at all) are reported as one unattributed bucket instead.
 */
public interface CouponAttributionRepository extends Repository<Purchase, Long> {

    @Query("SELECT new com.axon.core_service.domain.dto.dashboard.PurchaseAggregateByCampaign(" +
            "a.campaign.id, COUNT(p), COALESCE(SUM(p.price * p.quantity), 0)) " +
            "FROM Purchase p, UserCoupon uc, MarketingActionExecution e, CampaignActivityRun r " +
            "JOIN r.campaignActivity a " +
            "WHERE uc.id = p.userCouponId " +
            "AND e.id = uc.marketingActionExecutionId " +
            "AND r.id = e.campaignActivityRunId " +
            "AND p.campaignActivityId IS NULL " +
            "AND p.status = 'CONFIRMED' " +
            "AND p.purchaseAt >= :startDate AND p.purchaseAt < :endDate " +
            "GROUP BY a.campaign.id")
    List<PurchaseAggregateByCampaign> findCouponPurchasesByCampaign(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    @Query("SELECT new com.axon.core_service.domain.dto.dashboard.PurchaseAggregate(" +
            "COUNT(p), COALESCE(SUM(p.price * p.quantity), 0)) " +
            "FROM Purchase p " +
            "WHERE p.userCouponId IS NOT NULL " +
            "AND p.campaignActivityId IS NULL " +
            "AND p.status = 'CONFIRMED' " +
            "AND p.purchaseAt >= :startDate AND p.purchaseAt < :endDate " +
            "AND NOT EXISTS (SELECT 1 FROM UserCoupon uc, MarketingActionExecution e " +
            "    WHERE uc.id = p.userCouponId " +
            "    AND e.id = uc.marketingActionExecutionId " +
            "    AND e.campaignActivityRunId IS NOT NULL)")
    PurchaseAggregate findUnattributedCouponPurchases(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);
}
