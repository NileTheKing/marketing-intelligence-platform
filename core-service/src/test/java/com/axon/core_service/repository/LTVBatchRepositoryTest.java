package com.axon.core_service.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.dashboard.LTVBatch;
import com.axon.core_service.domain.dto.campaignactivity.CampaignActivityStatus;
import com.axon.messaging.CampaignActivityType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
class LTVBatchRepositoryTest {

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private CampaignActivityRepository activityRepository;

    @Autowired
    private LTVBatchRepository ltvBatchRepository;

    @Test
    void activityCannotHaveTwoStatsForTheSameMonthOffset() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 1, 0, 0);
        Campaign campaign = campaignRepository.save(Campaign.builder().name("campaign").build());
        CampaignActivity activity = activityRepository.save(CampaignActivity.builder()
                .campaign(campaign)
                .name("activity")
                .limitCount(100)
                .status(CampaignActivityStatus.ACTIVE)
                .startDate(now.minusMonths(1))
                .endDate(now.plusMonths(1))
                .activityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .price(BigDecimal.TEN)
                .quantity(1)
                .build());

        ltvBatchRepository.saveAndFlush(stat(activity, 0, now));

        assertThatThrownBy(() -> ltvBatchRepository.saveAndFlush(stat(activity, 0, now.plusHours(1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private LTVBatch stat(CampaignActivity activity, int monthOffset, LocalDateTime collectedAt) {
        return LTVBatch.builder()
                .campaignActivity(activity)
                .monthOffset(monthOffset)
                .collectedAt(collectedAt)
                .cohortStartDate(activity.getStartDate())
                .cohortSize(10)
                .avgCac(BigDecimal.ONE)
                .ltvCumulative(BigDecimal.TEN)
                .ltvCacRatio(BigDecimal.TEN)
                .cumulativeProfit(BigDecimal.TEN)
                .isBreakEven(true)
                .monthlyRevenue(BigDecimal.TEN)
                .monthlyOrders(1)
                .activeUsers(1)
                .repeatPurchaseRate(BigDecimal.ZERO)
                .avgPurchaseFrequency(BigDecimal.ONE)
                .avgOrderValue(BigDecimal.TEN)
                .build();
    }
}
