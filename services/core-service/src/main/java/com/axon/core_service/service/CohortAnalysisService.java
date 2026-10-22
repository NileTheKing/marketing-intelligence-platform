package com.axon.core_service.service;

import com.axon.core_service.domain.dashboard.LTVBatch;
import com.axon.core_service.domain.dto.dashboard.CohortAnalysisResponse;
import com.axon.core_service.repository.LTVBatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Cohort 분석 서비스
 * LTV (Lifetime Value), CAC (Customer Acquisition Cost), 재구매율 등 계산
 */
@Service
@RequiredArgsConstructor
public class CohortAnalysisService {

    private final LTVBatchRepository ltvBatchRepository;

    /**
     * 배치 데이터로부터 CohortAnalysisResponse 생성.
     */
    public CohortAnalysisResponse buildResponseFromBatchData(Long activityId) {
        List<LTVBatch> stats = ltvBatchRepository
                .findByCampaignActivityIdOrderByMonthOffsetAsc(activityId);

        if (stats.isEmpty()) {
            throw new IllegalStateException("No batch stats found for activity " + activityId);
        }

        LTVBatch latestStat = stats.getLast();

        BigDecimal ltv30d = findLtvByMonthOffset(stats, 1);
        BigDecimal ltv90d = findLtvByMonthOffset(stats, 3);
        BigDecimal ltv365d = findLtvByMonthOffset(stats, 12);

        List<CohortAnalysisResponse.MonthlyDetail> monthlyDetails = stats.stream()
                .map(stat -> new CohortAnalysisResponse.MonthlyDetail(
                        stat.getMonthOffset(),
                        formatMonthLabel(stat.getCohortStartDate(), stat.getMonthOffset()),
                        stat.getCohortStartDate().plusMonths(stat.getMonthOffset()).toLocalDate().toString(),
                        stat.getLtvCumulative(),
                        stat.getMonthlyRevenue(),
                        stat.getCumulativeProfit(),
                        stat.getLtvCacRatio().doubleValue(),
                        stat.getIsBreakEven(),
                        stat.getActiveUsers()))
                .toList();

        return new CohortAnalysisResponse(
                "activity-" + activityId,
                latestStat.getCampaignActivity().getName(),
                latestStat.getCohortStartDate(),
                LocalDateTime.now(),
                (long) latestStat.getCohortSize(),
                latestStat.getCampaignActivity().getBudget(),
                latestStat.getAvgCac(),
                ltv30d,
                ltv90d,
                ltv365d,
                latestStat.getLtvCumulative(),
                calculateRatio(ltv30d, latestStat.getAvgCac()),
                calculateRatio(ltv90d, latestStat.getAvgCac()),
                calculateRatio(ltv365d, latestStat.getAvgCac()),
                latestStat.getLtvCacRatio().doubleValue(),
                latestStat.getRepeatPurchaseRate().doubleValue(),
                latestStat.getAvgPurchaseFrequency().doubleValue(),
                latestStat.getAvgOrderValue(),
                latestStat.getCollectedAt(),
                monthlyDetails);
    }

    /**
     * LTV/CAC 비율 계산 헬퍼
     */
    private Double calculateRatio(BigDecimal ltv, BigDecimal cac) {
        if (cac == null || cac.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }
        return ltv.divide(cac, 2, RoundingMode.HALF_UP).doubleValue();
    }

    private String formatMonthLabel(LocalDateTime cohortStartDate, int monthOffset) {
        LocalDateTime targetMonth = cohortStartDate.plusMonths(monthOffset);
        return targetMonth.getYear() + "년 " + targetMonth.getMonthValue() + "월";
    }

    private BigDecimal findLtvByMonthOffset(List<LTVBatch> stats, int targetMonth) {
        return stats.stream()
                .filter(s -> s.getMonthOffset() == targetMonth - 1)
                .findFirst()
                .map(LTVBatch::getLtvCumulative)
                .orElse(BigDecimal.ZERO);
    }

}
