package com.axon.core_service.service;

import com.axon.core_service.domain.campaign.Campaign;
import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.dashboard.DashboardPeriod;
import com.axon.core_service.domain.dashboard.FunnelStep;
import com.axon.core_service.domain.dto.dashboard.DashboardResponse;
import com.axon.core_service.domain.dto.dashboard.GlobalDashboardResponse;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.CampaignRepository;
import com.axon.core_service.repository.PurchaseRepository;
import com.axon.core_service.domain.dto.dashboard.PurchaseAggregate;
import com.axon.core_service.domain.dto.dashboard.PurchaseAggregateByActivity;
import com.axon.core_service.service.dashboard.DashboardMetricCalculator;
import com.axon.messaging.CampaignActivityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Test
    @DisplayName("Global Dashboard는 캠페인이 여러 개여도 Purchase 집계를 한 번만 조회한다")
    void getGlobalDashboardAggregatesPurchasesOnce() throws Exception {
        RealtimeMetricsService realtimeMetricsService = mock(RealtimeMetricsService.class);
        BehaviorEventService behaviorEventService = mock(BehaviorEventService.class);
        CampaignRepository campaignRepository = mock(CampaignRepository.class);
        CampaignActivityRepository campaignActivityRepository = mock(CampaignActivityRepository.class);
        PurchaseRepository purchaseRepository = mock(PurchaseRepository.class);
        DashboardService dashboardService = new DashboardService(
                realtimeMetricsService,
                behaviorEventService,
                campaignRepository,
                campaignActivityRepository,
                purchaseRepository,
                new DashboardMetricCalculator());

        Campaign firstCampaign = campaign(1L, "first", BigDecimal.TEN);
        Campaign secondCampaign = campaign(2L, "second", BigDecimal.valueOf(20));
        firstCampaign.getCampaignActivities().add(activity(11L, firstCampaign));
        secondCampaign.getCampaignActivities().add(activity(21L, secondCampaign));

        when(campaignRepository.findAllWithActivities()).thenReturn(List.of(firstCampaign, secondCampaign));
        when(behaviorEventService.getAllCampaignStats(any(), any())).thenReturn(Map.of());
        when(behaviorEventService.getHourlyTraffic(any(), any(), any())).thenReturn(Map.of());
        when(purchaseRepository.findConfirmedAggregatesByActivityIdsAndPeriod(any(), any(), any()))
                .thenReturn(List.of(
                        new PurchaseAggregateByActivity(11L, 2L, BigDecimal.valueOf(200)),
                        new PurchaseAggregateByActivity(21L, 3L, BigDecimal.valueOf(300))));

        GlobalDashboardResponse response = dashboardService.getGlobalDashboard();

        assertThat(response.overview().purchaseCount()).isEqualTo(5L);
        assertThat(response.overview().gmv()).isEqualByComparingTo("500");
        verify(campaignRepository).findAllWithActivities();
        verify(campaignRepository, never()).findAll();
        verify(purchaseRepository, times(1))
                .findConfirmedAggregatesByActivityIdsAndPeriod(any(), any(), any());
    }

    @Test
    @DisplayName("CUSTOM Activity Dashboard는 지정한 종료 시각과 같은 길이의 이전 구간을 사용한다")
    void getDashboardByActivityUsesExactCustomWindow() throws Exception {
        RealtimeMetricsService realtimeMetricsService = mock(RealtimeMetricsService.class);
        BehaviorEventService behaviorEventService = mock(BehaviorEventService.class);
        CampaignRepository campaignRepository = mock(CampaignRepository.class);
        CampaignActivityRepository campaignActivityRepository = mock(CampaignActivityRepository.class);
        PurchaseRepository purchaseRepository = mock(PurchaseRepository.class);
        DashboardService dashboardService = new DashboardService(
                realtimeMetricsService,
                behaviorEventService,
                campaignRepository,
                campaignActivityRepository,
                purchaseRepository,
                new DashboardMetricCalculator());

        Long activityId = 9L;
        LocalDateTime start = LocalDateTime.of(2026, 8, 1, 10, 0);
        LocalDateTime end = LocalDateTime.of(2026, 8, 3, 16, 0);
        LocalDateTime previousStart = LocalDateTime.of(2026, 7, 30, 4, 0);
        CampaignActivity activity = CampaignActivity.builder()
                .name("custom activity")
                .activityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .budget(BigDecimal.ZERO)
                .limitCount(10)
                .build();

        when(campaignActivityRepository.findById(activityId)).thenReturn(Optional.of(activity));
        when(realtimeMetricsService.getParticipantCount(activityId)).thenReturn(0L);
        when(realtimeMetricsService.getRemainingStock(0L, 10L)).thenReturn(10L);

        dashboardService.getDashboardByActivity(activityId, DashboardPeriod.CUSTOM, start, end);

        verify(behaviorEventService).getHourlyTraffic(java.util.List.of(activityId), start, end);
        verify(purchaseRepository, times(1))
                .findConfirmedAggregateByActivityIdAndPeriod(activityId, start, end);
        verify(purchaseRepository)
                .findConfirmedAggregateByActivityIdAndPeriod(activityId, previousStart, start);
    }

    @Test
    @DisplayName("FCFS Activity Dashboard는 공통 FunnelStep 순서와 기존 count 의미를 유지한다")
    void getDashboardByActivityUsesFcfsFunnelMapping() throws Exception {
        RealtimeMetricsService realtimeMetricsService = mock(RealtimeMetricsService.class);
        BehaviorEventService behaviorEventService = mock(BehaviorEventService.class);
        CampaignRepository campaignRepository = mock(CampaignRepository.class);
        CampaignActivityRepository campaignActivityRepository = mock(CampaignActivityRepository.class);
        PurchaseRepository purchaseRepository = mock(PurchaseRepository.class);
        DashboardService dashboardService = new DashboardService(
                realtimeMetricsService,
                behaviorEventService,
                campaignRepository,
                campaignActivityRepository,
                purchaseRepository,
                new DashboardMetricCalculator());

        Long activityId = 1L;
        CampaignActivity activity = CampaignActivity.builder()
                .name("FCFS activity")
                .activityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .price(BigDecimal.valueOf(10000))
                .budget(BigDecimal.valueOf(100000))
                .limitCount(200)
                .build();

        when(campaignActivityRepository.findById(activityId)).thenReturn(Optional.of(activity));
        when(realtimeMetricsService.getParticipantCount(activityId)).thenReturn(12L);
        when(realtimeMetricsService.getRemainingStock(12L, 200L)).thenReturn(188L);
        when(behaviorEventService.getFunnelStepCount(eq(activityId), eq(CampaignActivityType.FIRST_COME_FIRST_SERVE),
                eq(FunnelStep.VISIT), any(), any())).thenReturn(100L);
        when(behaviorEventService.getFunnelStepCount(eq(activityId), eq(CampaignActivityType.FIRST_COME_FIRST_SERVE),
                eq(FunnelStep.ENGAGE), any(), any())).thenReturn(40L);
        when(behaviorEventService.getFunnelStepCount(eq(activityId), eq(CampaignActivityType.FIRST_COME_FIRST_SERVE),
                eq(FunnelStep.QUALIFY), any(), any())).thenReturn(12L);
        when(purchaseRepository.findConfirmedAggregateByActivityIdAndPeriod(eq(activityId), any(), any()))
                .thenReturn(new PurchaseAggregate(10L, BigDecimal.valueOf(100_000)));

        DashboardResponse response = dashboardService.getDashboardByActivity(
                activityId,
                DashboardPeriod.SEVEN_DAYS,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now());

        assertThat(response.funnel())
                .extracting("step")
                .containsExactly(FunnelStep.VISIT, FunnelStep.ENGAGE, FunnelStep.QUALIFY, FunnelStep.PURCHASE);
        assertThat(response.funnel())
                .extracting("count")
                .containsExactly(100L, 40L, 12L, 10L);
        verify(behaviorEventService, org.mockito.Mockito.never()).getFunnelStepCount(eq(activityId),
                eq(CampaignActivityType.FIRST_COME_FIRST_SERVE), eq(FunnelStep.PURCHASE), any(), any());
    }

    @Test
    @DisplayName("미구현 타입 Activity Dashboard는 FCFS count getter로 fallback하지 않는다")
    void getDashboardByActivityDoesNotFallbackToFcfsForUnsupportedType() throws Exception {
        RealtimeMetricsService realtimeMetricsService = mock(RealtimeMetricsService.class);
        BehaviorEventService behaviorEventService = mock(BehaviorEventService.class);
        CampaignRepository campaignRepository = mock(CampaignRepository.class);
        CampaignActivityRepository campaignActivityRepository = mock(CampaignActivityRepository.class);
        PurchaseRepository purchaseRepository = mock(PurchaseRepository.class);
        DashboardService dashboardService = new DashboardService(
                realtimeMetricsService,
                behaviorEventService,
                campaignRepository,
                campaignActivityRepository,
                purchaseRepository,
                new DashboardMetricCalculator());

        Long activityId = 2L;
        CampaignActivity activity = CampaignActivity.builder()
                .name("Coupon activity")
                .activityType(CampaignActivityType.COUPON)
                .price(BigDecimal.ZERO)
                .budget(BigDecimal.ZERO)
                .limitCount(100)
                .build();

        when(campaignActivityRepository.findById(activityId)).thenReturn(Optional.of(activity));
        when(realtimeMetricsService.getParticipantCount(activityId)).thenReturn(0L);
        when(realtimeMetricsService.getRemainingStock(0L, 100L)).thenReturn(100L);
        for (FunnelStep step : FunnelStep.values()) {
            if (step == FunnelStep.PURCHASE) {
                continue;
            }
            when(behaviorEventService.getFunnelStepCount(eq(activityId), eq(CampaignActivityType.COUPON),
                    eq(step), any(), any())).thenReturn(0L);
        }

        DashboardResponse response = dashboardService.getDashboardByActivity(
                activityId,
                DashboardPeriod.SEVEN_DAYS,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now());

        assertThat(response.funnel())
                .extracting("count")
                .containsExactly(0L, 0L, 0L, 0L);
        verify(behaviorEventService, org.mockito.Mockito.never()).getFunnelStepCount(
                eq(activityId), eq(CampaignActivityType.FIRST_COME_FIRST_SERVE), any(), any(), any());
    }

    private Campaign campaign(Long id, String name, BigDecimal budget) {
        Campaign campaign = Campaign.builder().name(name).budget(budget).build();
        ReflectionTestUtils.setField(campaign, "id", id);
        return campaign;
    }

    private CampaignActivity activity(Long id, Campaign campaign) {
        CampaignActivity activity = CampaignActivity.builder()
                .campaign(campaign)
                .name("activity-" + id)
                .activityType(CampaignActivityType.FIRST_COME_FIRST_SERVE)
                .build();
        ReflectionTestUtils.setField(activity, "id", id);
        return activity;
    }
}
