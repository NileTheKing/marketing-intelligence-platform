package com.axon.core_service.domain.dto.dashboard;

import java.math.BigDecimal;

public interface CampaignRunGroupPerformance {
    String getAssignmentGroup();

    String getBaselineRfmSegment();

    long getTargetCount();

    long getPurchasingUserCount();

    long getOrderCount();

    BigDecimal getRevenue();

    long getIssuedCouponCount();

    long getUsedCouponCount();

    long getCouponAttributedOrderCount();

    BigDecimal getCouponAttributedRevenue();
}
