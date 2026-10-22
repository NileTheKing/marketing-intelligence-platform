package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.axon.core_service.campaign.domain.CampaignActivityRun;
import com.axon.core_service.campaign.domain.CampaignActivityRunTargetGroup;
import com.axon.core_service.domain.dto.dashboard.CampaignRunGroupPerformance;
import com.axon.core_service.campaign.CampaignActivityRunRepository;
import com.axon.core_service.campaign.CampaignActivityRunTargetRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CampaignRunIncrementalityServiceTest {

    @Mock private CampaignActivityRunRepository runRepository;
    @Mock private CampaignActivityRunTargetRepository targetRepository;

    @Test
    void comparesTreatmentAndHoldoutRevenueWithoutCountingCouponCostAsRevenue() {
        CampaignActivityRun run = CampaignActivityRun.pendingAnalysis(
                null, 100, 90, 10, Map.of("couponDiscountAmount", "50"));
        run.markDispatching("operator");
        run.markDispatched();
        when(runRepository.findById(7L)).thenReturn(Optional.of(run));
        when(targetRepository.aggregatePerformanceByRun(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        performance(CampaignActivityRunTargetGroup.TREATMENT,
                                "LOYAL", 90, 18, 24, "18000", 86, 31, 31, "12000"),
                        performance(CampaignActivityRunTargetGroup.HOLDOUT,
                                "LOYAL", 10, 1, 1, "1000", 0, 0, 0, "0")));

        var response = service().analyze(7L, 7);

        assertThat(response.treatment().revenuePerUser()).isEqualByComparingTo("200.00");
        assertThat(response.holdout().revenuePerUser()).isEqualByComparingTo("100.00");
        assertThat(response.incrementalRevenue()).isEqualByComparingTo("9000.00");
        assertThat(response.predictedFixedCouponCost()).isEqualByComparingTo("4500");
        assertThat(response.incrementalRevenueAfterCouponCost()).isEqualByComparingTo("4500.00");
        assertThat(response.treatment().issuedCouponCount()).isEqualTo(86);
        assertThat(response.treatment().usedCouponCount()).isEqualTo(31);
        assertThat(response.treatment().couponAttributedRevenue()).isEqualByComparingTo("12000");
        assertThat(response.byBaselineSegment().get("LOYAL").incrementalRevenue())
                .isEqualByComparingTo("9000.00");
    }

    private CampaignRunIncrementalityService service() {
        return new CampaignRunIncrementalityService(runRepository, targetRepository);
    }

    private CampaignRunGroupPerformance performance(CampaignActivityRunTargetGroup group,
                                                     String segment,
                                                     long targets,
                                                     long purchasers,
                                                     long orders,
                                                     String revenue,
                                                     long issuedCoupons,
                                                     long usedCoupons,
                                                     long couponOrders,
                                                     String couponRevenue) {
        return new CampaignRunGroupPerformance() {
            @Override
            public String getAssignmentGroup() {
                return group.name();
            }

            @Override
            public String getBaselineRfmSegment() {
                return segment;
            }

            @Override
            public long getTargetCount() {
                return targets;
            }

            @Override
            public long getPurchasingUserCount() {
                return purchasers;
            }

            @Override
            public long getOrderCount() {
                return orders;
            }

            @Override
            public BigDecimal getRevenue() {
                return new BigDecimal(revenue);
            }

            @Override
            public long getIssuedCouponCount() {
                return issuedCoupons;
            }

            @Override
            public long getUsedCouponCount() {
                return usedCoupons;
            }

            @Override
            public long getCouponAttributedOrderCount() {
                return couponOrders;
            }

            @Override
            public BigDecimal getCouponAttributedRevenue() {
                return new BigDecimal(couponRevenue);
            }
        };
    }
}
