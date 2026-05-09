package com.axon.core_service.service.batch;

import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.dashboard.LTVBatch;
import com.axon.core_service.domain.purchase.Purchase;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.LTVBatchRepository;
import com.axon.core_service.repository.PurchaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StopWatch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CohortLtvBatchService {
    private final CampaignActivityRepository campaignActivityRepository;
    private final PurchaseRepository purchaseRepository;
    private final LTVBatchRepository ltvBatchRepository;
    private final NamedParameterJdbcTemplate namedJdbc;

    private record MonthlyAggResult(BigDecimal monthlyRevenue, int monthlyOrders, int activeUsers) {}
    private record RepeatAggResult(BigDecimal repeatRate, BigDecimal avgFrequency, BigDecimal avgOrderValue) {}

    private MonthlyAggResult queryMonthlyStats(List<Long> userIds, LocalDateTime start, LocalDateTime end) {
        String sql = """
                SELECT
                  COALESCE(SUM(price * quantity), 0) AS monthly_revenue,
                  COUNT(*)                            AS monthly_orders,
                  COUNT(DISTINCT user_id)             AS active_users
                FROM purchases
                WHERE user_id IN (:userIds)
                  AND purchase_at >= :start
                  AND purchase_at < :end
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userIds", userIds)
                .addValue("start", start)
                .addValue("end", end);
        return namedJdbc.queryForObject(sql, params, (rs, rowNum) -> new MonthlyAggResult(
                rs.getBigDecimal("monthly_revenue"),
                rs.getInt("monthly_orders"),
                rs.getInt("active_users")
        ));
    }

    private RepeatAggResult queryRepeatStats(List<Long> userIds, LocalDateTime until) {
        String sql = """
                SELECT
                  (COUNT(DISTINCT CASE WHEN purchase_count > 1 THEN user_id END) * 100.0 / NULLIF(COUNT(DISTINCT user_id), 0)) AS repeat_rate,
                  (SUM(purchase_count) * 1.0 / NULLIF(COUNT(DISTINCT user_id), 0)) AS avg_frequency,
                  (SUM(total_revenue) / NULLIF(SUM(purchase_count), 0)) AS avg_order_value
                FROM (
                    SELECT user_id, COUNT(*) as purchase_count, SUM(price * quantity) as total_revenue
                    FROM purchases
                    WHERE user_id IN (:userIds)
                      AND purchase_at <= :until
                    GROUP BY user_id
                    ) AS user_agg
                    """;
                    MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("userIds", userIds)
                    .addValue("until", until);
                    return namedJdbc.queryForObject(sql, params, (rs, rowNum) -> new RepeatAggResult(
                    rs.getBigDecimal("repeat_rate"),
                    rs.getBigDecimal("avg_frequency"),
                    rs.getBigDecimal("avg_order_value")
                    ));
                    }

                    private BigDecimal queryCumulativeLtv(List<Long> userIds, LocalDateTime until) {
                    String sql = """
                    SELECT COALESCE(SUM(price * quantity), 0) AS ltv_cumulative
                    FROM purchases
                    WHERE user_id IN (:userIds)
                      AND purchase_at <= :until
                    """;        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userIds", userIds)
                .addValue("until", until);
        return namedJdbc.queryForObject(sql, params, BigDecimal.class);
    }

    public void processMonthlyCohortStats() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime twelveMonthsAgo = now.minusMonths(12);

        log.info("Starting monthly cohort LTV batch job: {}", now);

        List<Long> activityIds = ltvBatchRepository.findActivitiesForBatchProcessing(twelveMonthsAgo, now);

        for (Long activityId : activityIds) {
            try {
                processActivityCohortInTransaction(activityId, now);
            } catch (Exception e) {
                log.error("Failed to process activity {}: {}", activityId, e.getMessage());
            }
        }
    }

    @Transactional
    public void processActivityCohortInTransaction(Long activityId, LocalDateTime collectedAt) {
        LTVBatch stat = processActivityCohort(activityId, collectedAt);
        if (stat != null) {
            ltvBatchRepository.save(stat);
        }
    }

    private LTVBatch processActivityCohort(Long activityId, LocalDateTime collectedAt) {
        StopWatch sw = new StopWatch();
        sw.start();

        CampaignActivity activity = campaignActivityRepository.findById(activityId)
                .orElseThrow(() -> new IllegalArgumentException("Activity not found: " + activityId));

        LocalDateTime cohortStartDate = activity.getStartDate();
        List<LTVBatch> existingStats = ltvBatchRepository.findByCampaignActivityIdOrderByMonthOffsetAsc(activityId);

        int lastOffset = existingStats.stream()
                .mapToInt(LTVBatch::getMonthOffset)
                .max()
                .orElse(-1);

        int newOffset = lastOffset + 1;
        if (newOffset >= 12) return null;

        LocalDateTime newMonthEnd = cohortStartDate.plusMonths(newOffset + 1);
        if (newMonthEnd.isAfter(collectedAt)) return null;

        List<Purchase> firstPurchases = purchaseRepository.findFirstPurchasesByActivityAndPeriod(
                activityId, cohortStartDate, LocalDateTime.now());

        if (firstPurchases.isEmpty()) return null;

        List<Long> cohortUserIds = firstPurchases.stream()
                .map(Purchase::getUserId)
                .distinct()
                .toList();

        int cohortSize = cohortUserIds.size();
        BigDecimal avgCac = calculateAvgCac(activity.getBudget(), cohortSize);

        LTVBatch newStat;
        if (newOffset == 0) {
            newStat = calculateFullStats(activity, newOffset, collectedAt, cohortStartDate, cohortSize, avgCac, cohortUserIds);
        } else {
            LTVBatch prevStat = existingStats.get(existingStats.size() - 1);
            newStat = calculateIncrementalStats(activity, newOffset, collectedAt, cohortStartDate, cohortSize, avgCac, cohortUserIds, prevStat);
        }

        sw.stop();
        log.info("Activity {} month {} processed ({}ms)", activityId, newOffset, sw.getTotalTimeMillis());
        return newStat;
    }

    private LTVBatch calculateFullStats(CampaignActivity activity, int monthOffset, LocalDateTime collectedAt, LocalDateTime cohortStartDate, int cohortSize, BigDecimal avgCac, List<Long> cohortUserIds) {
        LocalDateTime monthStart = cohortStartDate.plusMonths(monthOffset);
        LocalDateTime monthEnd = cohortStartDate.plusMonths(monthOffset + 1);

        MonthlyAggResult monthlyStats = queryMonthlyStats(cohortUserIds, monthStart, monthEnd);
        BigDecimal ltvCumulative = queryCumulativeLtv(cohortUserIds, monthEnd);
        RepeatAggResult repeatStats = queryRepeatStats(cohortUserIds, monthEnd);

        return buildLTVBatch(activity, monthOffset, collectedAt, cohortStartDate, cohortSize, avgCac, ltvCumulative, monthlyStats, repeatStats);
    }

    private LTVBatch calculateIncrementalStats(CampaignActivity activity, int monthOffset, LocalDateTime collectedAt, LocalDateTime cohortStartDate, int cohortSize, BigDecimal avgCac, List<Long> cohortUserIds, LTVBatch prevStat) {
        LocalDateTime monthStart = cohortStartDate.plusMonths(monthOffset);
        LocalDateTime monthEnd = cohortStartDate.plusMonths(monthOffset + 1);

        MonthlyAggResult monthlyStats = queryMonthlyStats(cohortUserIds, monthStart, monthEnd);
        RepeatAggResult repeatStats = queryRepeatStats(cohortUserIds, monthEnd);
        BigDecimal ltvCumulative = prevStat.getLtvCumulative().add(monthlyStats.monthlyRevenue());

        return buildLTVBatch(activity, monthOffset, collectedAt, cohortStartDate, cohortSize, avgCac, ltvCumulative, monthlyStats, repeatStats);
    }

    private LTVBatch buildLTVBatch(CampaignActivity activity, int monthOffset, LocalDateTime collectedAt, LocalDateTime cohortStartDate, int cohortSize, BigDecimal avgCac, BigDecimal ltvCumulative, MonthlyAggResult monthlyStats, RepeatAggResult repeatStats) {
        BigDecimal ltvCacRatio = avgCac.compareTo(BigDecimal.ZERO) > 0
                ? ltvCumulative.divide(avgCac.multiply(BigDecimal.valueOf(cohortSize)), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal budget = activity.getBudget() != null ? activity.getBudget() : BigDecimal.ZERO;
        BigDecimal profit = ltvCumulative.subtract(budget);

        return LTVBatch.builder()
                .campaignActivity(activity)
                .monthOffset(monthOffset)
                .collectedAt(collectedAt)
                .cohortStartDate(cohortStartDate)
                .cohortSize(cohortSize)
                .avgCac(avgCac)
                .ltvCumulative(ltvCumulative)
                .ltvCacRatio(ltvCacRatio)
                .cumulativeProfit(profit)
                .isBreakEven(profit.compareTo(BigDecimal.ZERO) >= 0)
                .monthlyRevenue(monthlyStats.monthlyRevenue())
                .monthlyOrders(monthlyStats.monthlyOrders())
                .activeUsers(monthlyStats.activeUsers())
                .repeatPurchaseRate(repeatStats.repeatRate() != null ? repeatStats.repeatRate() : BigDecimal.ZERO)
                .avgPurchaseFrequency(repeatStats.avgFrequency() != null ? repeatStats.avgFrequency() : BigDecimal.ZERO)
                .avgOrderValue(repeatStats.avgOrderValue() != null ? repeatStats.avgOrderValue() : BigDecimal.ZERO)
                .build();
    }

    private BigDecimal calculateAvgCac(BigDecimal budget, int cohortSize) {
        if (budget == null || cohortSize == 0) return BigDecimal.ZERO;
        return budget.divide(BigDecimal.valueOf(cohortSize), 2, RoundingMode.HALF_UP);
    }
}
