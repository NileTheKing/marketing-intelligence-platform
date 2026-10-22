package com.axon.core_service.service;

import com.axon.core_service.campaign.domain.CampaignActivityRun;
import com.axon.core_service.campaign.domain.CampaignActivityRunStatus;
import com.axon.core_service.campaign.domain.CampaignActivityRunTargetGroup;
import com.axon.core_service.domain.dto.dashboard.CampaignRunGroupPerformance;
import com.axon.core_service.domain.dto.dashboard.CampaignRunIncrementalityResponse;
import com.axon.core_service.exception.BusinessConflictException;
import com.axon.core_service.exception.ResourceNotFoundException;
import com.axon.core_service.campaign.CampaignActivityRunRepository;
import com.axon.core_service.campaign.CampaignActivityRunTargetRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CampaignRunIncrementalityService {

    private final CampaignActivityRunRepository runRepository;
    private final CampaignActivityRunTargetRepository targetRepository;

    @Transactional(readOnly = true)
    public CampaignRunIncrementalityResponse analyze(Long runId, int windowDays) {
        if (windowDays != 7 && windowDays != 30) {
            throw new BusinessConflictException("Campaign performance window must be 7 or 30 days");
        }

        CampaignActivityRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("campaign activity run", runId));
        if (run.getStatus() != CampaignActivityRunStatus.DISPATCHED || run.getDispatchedAt() == null) {
            throw new BusinessConflictException("Campaign performance is available after dispatch");
        }
        if (run.getHoldoutCount() == 0) {
            throw new BusinessConflictException("Campaign run has no holdout group");
        }

        LocalDateTime observedFrom = run.getDispatchedAt();
        LocalDateTime observedUntil = observedFrom.plusDays(windowDays);
        LocalDateTime now = LocalDateTime.now();
        if (observedUntil.isAfter(now)) {
            observedUntil = now;
        }

        List<CampaignRunGroupPerformance> rows = targetRepository.aggregatePerformanceByRun(
                runId, observedFrom, observedUntil);
        Map<CampaignActivityRunTargetGroup, CampaignRunIncrementalityResponse.GroupMetrics> overall =
                aggregateByGroup(rows);
        CampaignRunIncrementalityResponse.GroupMetrics treatment = overall.getOrDefault(
                CampaignActivityRunTargetGroup.TREATMENT, zeroMetrics());
        CampaignRunIncrementalityResponse.GroupMetrics holdout = overall.getOrDefault(
                CampaignActivityRunTargetGroup.HOLDOUT, zeroMetrics());

        BigDecimal incrementalRevenue = incrementalRevenue(treatment, holdout);
        BigDecimal predictedFixedCouponCost = fixedCouponCost(run, treatment.targetCount());
        Map<String, CampaignRunIncrementalityResponse.SegmentComparison> bySegment = aggregateBySegment(rows);

        return new CampaignRunIncrementalityResponse(
                runId,
                windowDays,
                Math.toIntExact(Duration.between(observedFrom, observedUntil).toDays()),
                observedFrom,
                observedUntil,
                treatment,
                holdout,
                incrementalRevenue,
                predictedFixedCouponCost,
                incrementalRevenue.subtract(predictedFixedCouponCost),
                bySegment);
    }

    private Map<CampaignActivityRunTargetGroup, CampaignRunIncrementalityResponse.GroupMetrics> aggregateByGroup(
            List<CampaignRunGroupPerformance> rows) {
        Map<CampaignActivityRunTargetGroup, Aggregate> aggregates = new EnumMap<>(CampaignActivityRunTargetGroup.class);
        for (CampaignRunGroupPerformance row : rows) {
            CampaignActivityRunTargetGroup group = CampaignActivityRunTargetGroup.valueOf(row.getAssignmentGroup());
            aggregates.computeIfAbsent(group, ignored -> new Aggregate()).add(row);
        }
        Map<CampaignActivityRunTargetGroup, CampaignRunIncrementalityResponse.GroupMetrics> result =
                new EnumMap<>(CampaignActivityRunTargetGroup.class);
        aggregates.forEach((group, aggregate) -> result.put(group, aggregate.toMetrics()));
        return result;
    }

    private Map<String, CampaignRunIncrementalityResponse.SegmentComparison> aggregateBySegment(
            List<CampaignRunGroupPerformance> rows) {
        Map<String, Map<CampaignActivityRunTargetGroup, Aggregate>> segments = new LinkedHashMap<>();
        for (CampaignRunGroupPerformance row : rows) {
            String segment = row.getBaselineRfmSegment() == null ? "UNKNOWN" : row.getBaselineRfmSegment();
            CampaignActivityRunTargetGroup group = CampaignActivityRunTargetGroup.valueOf(row.getAssignmentGroup());
            segments.computeIfAbsent(segment, ignored -> new EnumMap<>(CampaignActivityRunTargetGroup.class))
                    .computeIfAbsent(group, ignored -> new Aggregate())
                    .add(row);
        }
        Map<String, CampaignRunIncrementalityResponse.SegmentComparison> result = new LinkedHashMap<>();
        segments.forEach((segment, groups) -> {
            CampaignRunIncrementalityResponse.GroupMetrics treatment = groups
                    .getOrDefault(CampaignActivityRunTargetGroup.TREATMENT, new Aggregate()).toMetrics();
            CampaignRunIncrementalityResponse.GroupMetrics holdout = groups
                    .getOrDefault(CampaignActivityRunTargetGroup.HOLDOUT, new Aggregate()).toMetrics();
            result.put(segment, new CampaignRunIncrementalityResponse.SegmentComparison(
                    treatment, holdout, incrementalRevenue(treatment, holdout)));
        });
        return result;
    }

    private BigDecimal fixedCouponCost(CampaignActivityRun run, long treatmentCount) {
        Object discount = run.getFactSnapshot() == null ? null : run.getFactSnapshot().get("couponDiscountAmount");
        if (discount == null || String.valueOf(discount).isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(discount)).multiply(BigDecimal.valueOf(treatmentCount));
    }

    private BigDecimal incrementalRevenue(CampaignRunIncrementalityResponse.GroupMetrics treatment,
                                          CampaignRunIncrementalityResponse.GroupMetrics holdout) {
        if (holdout.targetCount() == 0) {
            return BigDecimal.ZERO;
        }
        return treatment.revenuePerUser()
                .subtract(holdout.revenuePerUser())
                .multiply(BigDecimal.valueOf(treatment.targetCount()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private CampaignRunIncrementalityResponse.GroupMetrics zeroMetrics() {
        return new CampaignRunIncrementalityResponse.GroupMetrics(
                0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0, 0, 0, BigDecimal.ZERO);
    }

    private static final class Aggregate {
        private long targetCount;
        private long purchasingUserCount;
        private long orderCount;
        private BigDecimal revenue = BigDecimal.ZERO;
        private long issuedCouponCount;
        private long usedCouponCount;
        private long couponAttributedOrderCount;
        private BigDecimal couponAttributedRevenue = BigDecimal.ZERO;

        private void add(CampaignRunGroupPerformance row) {
            targetCount += row.getTargetCount();
            purchasingUserCount += row.getPurchasingUserCount();
            orderCount += row.getOrderCount();
            if (row.getRevenue() != null) {
                revenue = revenue.add(row.getRevenue());
            }
            issuedCouponCount += row.getIssuedCouponCount();
            usedCouponCount += row.getUsedCouponCount();
            couponAttributedOrderCount += row.getCouponAttributedOrderCount();
            if (row.getCouponAttributedRevenue() != null) {
                couponAttributedRevenue = couponAttributedRevenue.add(row.getCouponAttributedRevenue());
            }
        }

        private CampaignRunIncrementalityResponse.GroupMetrics toMetrics() {
            if (targetCount == 0) {
                return new CampaignRunIncrementalityResponse.GroupMetrics(
                        0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        0, 0, 0, BigDecimal.ZERO);
            }
            BigDecimal audience = BigDecimal.valueOf(targetCount);
            return new CampaignRunIncrementalityResponse.GroupMetrics(
                    targetCount,
                    purchasingUserCount,
                    orderCount,
                    revenue,
                    revenue.divide(audience, 2, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(purchasingUserCount)
                            .multiply(BigDecimal.valueOf(100))
                            .divide(audience, 2, RoundingMode.HALF_UP),
                    issuedCouponCount,
                    usedCouponCount,
                    couponAttributedOrderCount,
                    couponAttributedRevenue);
        }
    }
}
