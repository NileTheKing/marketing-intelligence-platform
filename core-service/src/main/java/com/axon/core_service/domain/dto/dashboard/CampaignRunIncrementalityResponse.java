package com.axon.core_service.domain.dto.dashboard;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record CampaignRunIncrementalityResponse(
        Long runId,
        int windowDays,
        int observedDays,
        LocalDateTime observedFrom,
        LocalDateTime observedUntil,
        GroupMetrics treatment,
        GroupMetrics holdout,
        BigDecimal incrementalRevenue,
        BigDecimal predictedFixedCouponCost,
        BigDecimal incrementalRevenueAfterCouponCost,
        Map<String, SegmentComparison> byBaselineSegment
) {
    public record GroupMetrics(
            long targetCount,
            long purchasingUserCount,
            long orderCount,
            BigDecimal revenue,
            BigDecimal revenuePerUser,
            BigDecimal purchaseConversionRate,
            long issuedCouponCount,
            long usedCouponCount,
            long couponAttributedOrderCount,
            BigDecimal couponAttributedRevenue
    ) {
    }

    public record SegmentComparison(
            GroupMetrics treatment,
            GroupMetrics holdout,
            BigDecimal incrementalRevenue
    ) {
    }
}
