package com.axon.core_service.repository;

import com.axon.core_service.domain.campaignactivity.CampaignActivityRunTarget;
import com.axon.core_service.domain.campaignactivity.CampaignActivityRunTargetGroup;
import com.axon.core_service.domain.dto.dashboard.CampaignRunGroupPerformance;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignActivityRunTargetRepository extends JpaRepository<CampaignActivityRunTarget, Long> {

    List<CampaignActivityRunTarget> findAllByRunIdAndAssignmentGroupAndExecutionIdIsNullOrderByUserIdAsc(
            Long runId, CampaignActivityRunTargetGroup assignmentGroup);

    long countByRunId(Long runId);

    @org.springframework.data.jpa.repository.Query(value = """
            SELECT target.assignment_group AS assignmentGroup,
                   target.baseline_rfm_segment AS baselineRfmSegment,
                   COUNT(DISTINCT target.user_id) AS targetCount,
                   COUNT(DISTINCT CASE WHEN purchase.id IS NOT NULL THEN target.user_id END) AS purchasingUserCount,
                   COUNT(purchase.id) AS orderCount,
                   COALESCE(SUM(purchase.price * purchase.quantity), 0) AS revenue,
                   COUNT(DISTINCT issued_coupon.id) AS issuedCouponCount,
                   COUNT(DISTINCT CASE WHEN issued_coupon.status = 'USED' THEN issued_coupon.id END) AS usedCouponCount,
                   COALESCE(SUM(CASE WHEN purchase.user_coupon_id = issued_coupon.id
                                     THEN 1 ELSE 0 END), 0) AS couponAttributedOrderCount,
                   COALESCE(SUM(CASE WHEN purchase.user_coupon_id = issued_coupon.id
                                     THEN purchase.price * purchase.quantity ELSE 0 END), 0) AS couponAttributedRevenue
            FROM campaign_activity_run_targets target
            LEFT JOIN user_coupons issued_coupon
              ON issued_coupon.marketing_action_execution_id = target.execution_id
            LEFT JOIN purchases purchase
              ON purchase.user_id = target.user_id
             AND purchase.status = 'CONFIRMED'
             AND purchase.purchase_at >= :startAt
             AND purchase.purchase_at < :endAt
            WHERE target.run_id = :runId
            GROUP BY target.assignment_group, target.baseline_rfm_segment
            """, nativeQuery = true)
    List<CampaignRunGroupPerformance> aggregatePerformanceByRun(
            @org.springframework.data.repository.query.Param("runId") Long runId,
            @org.springframework.data.repository.query.Param("startAt") LocalDateTime startAt,
            @org.springframework.data.repository.query.Param("endAt") LocalDateTime endAt);
}
