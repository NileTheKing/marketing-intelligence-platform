package com.axon.core_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.dashboard.LTVBatch;
import com.axon.core_service.repository.LTVBatchRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CohortAnalysisServiceTest {

    @Test
    void buildsCohortResponseOnlyFromStoredBatchStats() {
        LTVBatchRepository repository = mock(LTVBatchRepository.class);
        CohortAnalysisService service = new CohortAnalysisService(repository);
        CampaignActivity activity = CampaignActivity.builder()
                .name("activity")
                .budget(BigDecimal.valueOf(1_000))
                .build();
        LocalDateTime cohortStart = LocalDateTime.of(2026, 1, 1, 0, 0);
        LTVBatch stat = LTVBatch.builder()
                .campaignActivity(activity)
                .monthOffset(0)
                .collectedAt(LocalDateTime.of(2026, 2, 1, 3, 0))
                .cohortStartDate(cohortStart)
                .cohortSize(10)
                .avgCac(BigDecimal.valueOf(100))
                .ltvCumulative(BigDecimal.valueOf(500))
                .ltvCacRatio(BigDecimal.valueOf(0.5))
                .cumulativeProfit(BigDecimal.valueOf(-500))
                .isBreakEven(false)
                .monthlyRevenue(BigDecimal.valueOf(500))
                .monthlyOrders(5)
                .activeUsers(4)
                .repeatPurchaseRate(BigDecimal.TEN)
                .avgPurchaseFrequency(BigDecimal.valueOf(1.2))
                .avgOrderValue(BigDecimal.valueOf(100))
                .build();
        when(repository.findByCampaignActivityIdOrderByMonthOffsetAsc(1L)).thenReturn(List.of(stat));

        var response = service.buildResponseFromBatchData(1L);

        assertThat(response.totalCustomers()).isEqualTo(10L);
        assertThat(response.ltvCurrent()).isEqualByComparingTo("500");
        assertThat(response.monthlyDetails()).hasSize(1);
    }
}
