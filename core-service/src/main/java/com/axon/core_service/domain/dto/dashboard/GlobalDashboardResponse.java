package com.axon.core_service.domain.dto.dashboard;

import java.time.LocalDateTime;
import java.util.List;

public record GlobalDashboardResponse(
        OverviewData overview,
        List<CampaignRankData> topGmvCampaigns,
        List<CampaignRankData> topVisitCampaigns,
        List<CampaignEfficiencyData> campaignEfficiency,
        HeatmapData hourlyTraffic,
        // Coupon purchases not tied to a campaign run (behavior rules, abandoned cart, re-engagement);
        // reported separately and not included in overview or campaign totals.
        PurchaseAggregate unattributedCouponPurchases,
        LocalDateTime calculatedAt
) {}
