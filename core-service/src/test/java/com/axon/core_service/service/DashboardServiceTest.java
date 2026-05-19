package com.axon.core_service.service;

import com.axon.core_service.domain.campaignactivity.CampaignActivity;
import com.axon.core_service.domain.dashboard.DashboardPeriod;
import com.axon.core_service.domain.dashboard.FunnelStep;
import com.axon.core_service.domain.dto.dashboard.DashboardResponse;
import com.axon.core_service.repository.CampaignActivityRepository;
import com.axon.core_service.repository.CampaignRepository;
import com.axon.messaging.CampaignActivityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Test
    @DisplayName("FCFS Activity Dashboard는 공통 FunnelStep 순서와 기존 count 의미를 유지한다")
    void getDashboardByActivityUsesFcfsFunnelMapping() throws Exception {
        RealtimeMetricsService realtimeMetricsService = mock(RealtimeMetricsService.class);
        BehaviorEventService behaviorEventService = mock(BehaviorEventService.class);
        CampaignRepository campaignRepository = mock(CampaignRepository.class);
        CampaignActivityRepository campaignActivityRepository = mock(CampaignActivityRepository.class);
        DashboardService dashboardService = new DashboardService(
                realtimeMetricsService,
                behaviorEventService,
                campaignRepository,
                campaignActivityRepository);

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
        when(behaviorEventService.getFunnelStepCount(eq(activityId), eq(CampaignActivityType.FIRST_COME_FIRST_SERVE),
                eq(FunnelStep.PURCHASE), any(), any())).thenReturn(10L);

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
    }

    @Test
    @DisplayName("미구현 타입 Activity Dashboard는 FCFS count getter로 fallback하지 않는다")
    void getDashboardByActivityDoesNotFallbackToFcfsForUnsupportedType() throws Exception {
        RealtimeMetricsService realtimeMetricsService = mock(RealtimeMetricsService.class);
        BehaviorEventService behaviorEventService = mock(BehaviorEventService.class);
        CampaignRepository campaignRepository = mock(CampaignRepository.class);
        CampaignActivityRepository campaignActivityRepository = mock(CampaignActivityRepository.class);
        DashboardService dashboardService = new DashboardService(
                realtimeMetricsService,
                behaviorEventService,
                campaignRepository,
                campaignActivityRepository);

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
        verify(behaviorEventService, never()).getVisitCount(eq(activityId), any(), any());
        verify(behaviorEventService, never()).getEngageCount(eq(activityId), any(), any());
        verify(behaviorEventService, never()).getQualifyCount(eq(activityId), any(), any());
        verify(behaviorEventService, never()).getPurchaseCount(eq(activityId), any(), any());
    }
}
