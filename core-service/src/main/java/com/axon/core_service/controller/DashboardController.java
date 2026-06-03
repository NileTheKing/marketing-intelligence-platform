package com.axon.core_service.controller;

import com.axon.core_service.domain.dashboard.DashboardPeriod;
import com.axon.core_service.domain.dto.dashboard.CampaignDashboardResponse;
import com.axon.core_service.domain.dto.dashboard.CohortAnalysisResponse;
import com.axon.core_service.domain.dto.dashboard.DashboardResponse;
import com.axon.core_service.repository.LTVBatchRepository;
import com.axon.core_service.service.CohortAnalysisService;
import com.axon.core_service.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.axon.core_service.domain.dto.dashboard.GlobalDashboardResponse;

@Slf4j
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final CohortAnalysisService cohortAnalysisService;
    private final LTVBatchRepository ltvBatchRepository;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // View Controllers - Moved to DashboardViewController
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // REST Endpoints (one-time fetch)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @GetMapping("/overview")
    public ResponseEntity<GlobalDashboardResponse> getGlobalDashboard() {
        return ResponseEntity.ok(dashboardService.getGlobalDashboard());
    }

    @GetMapping("/activity/{activityId}")
    public ResponseEntity<DashboardResponse> getDashboardByActivity(
            @PathVariable Long activityId,
            @RequestParam(defaultValue = "7d") String period,
            @RequestParam(required = false) LocalDateTime startDate,
            @RequestParam(required = false) LocalDateTime endDate) {
        DashboardPeriod dashboardPeriod = DashboardPeriod.fromCode(period);
        DashboardResponse response = dashboardService.getDashboardByActivity(
                activityId,
                dashboardPeriod,
                startDate,
                endDate);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/campaign/{campaignId}")
    public ResponseEntity<CampaignDashboardResponse> getDashboardByCampaign(
            @PathVariable Long campaignId,
            @RequestParam(defaultValue = "7d") String period,
            @RequestParam(required = false) LocalDateTime startDate,
            @RequestParam(required = false) LocalDateTime endDate) {

        DashboardPeriod dashboardPeriod = DashboardPeriod.fromCode(period);
        CampaignDashboardResponse response = dashboardService.getDashboardByCampaign(
                campaignId,
                dashboardPeriod,
                startDate,
                endDate);

        return ResponseEntity.ok(response);
    }

    /**
     * Get Cohort Analysis for an Activity
     *
     * 특정 Activity에서 획득한 고객들의 LTV, CAC, 재구매율 분석
     *
     * 배치 데이터 우선 조회 방식:
     * 1. 배치 테이블에 데이터가 있으면 즉시 반환 (0.1초)
     * 2. 없으면 실시간 계산 (Fallback, 느림)
     *
     * @param activityId Activity ID
     * @param startDate  Cohort 시작일 (optional, 기본값: Activity 시작일)
     * @param endDate    Cohort 종료일 (optional, 기본값: 현재)
     * @return Cohort 분석 결과
     */
    @GetMapping("/cohort/activity/{activityId}")
    public ResponseEntity<CohortAnalysisResponse> getCohortAnalysisByActivity(
            @PathVariable Long activityId,
            @RequestParam(required = false) LocalDateTime startDate,
            @RequestParam(required = false) LocalDateTime endDate) {

        log.info("Cohort analysis request - activityId: {}, startDate: {}, endDate: {}",
                activityId, startDate, endDate);

        // 1. 배치 데이터가 있는지 확인
        if (ltvBatchRepository.existsByCampaignActivityId(activityId)) {
            log.info("Returning cached batch data for activity {}", activityId);
            CohortAnalysisResponse response = cohortAnalysisService.buildResponseFromBatchData(activityId);
            return ResponseEntity.ok(response);
        }

        // 2. 배치 데이터 없으면 실시간 계산 (Fallback)
        log.warn("No batch data found for activity {}, calculating in real-time (slow)", activityId);
        CohortAnalysisResponse response = cohortAnalysisService.analyzeCohortByActivity(
                activityId,
                startDate,
                endDate);
        return ResponseEntity.ok(response);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // SSE Endpoints (real-time streaming)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Stream real-time dashboard updates for a specific campaign.
     */
    @GetMapping(value = "/stream/campaign/{campaignId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamCampaignDashboard(@PathVariable Long campaignId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        AtomicReference<ScheduledFuture<?>> scheduledTask = new AtomicReference<>();

        log.info("SSE connection opened for campaign: {}", campaignId);

        scheduledTask.set(scheduler.scheduleAtFixedRate(() -> {
            try {
                CampaignDashboardResponse data = dashboardService.getDashboardByCampaign(
                        campaignId, DashboardPeriod.SEVEN_DAYS, null, null);
                emitter.send(SseEmitter.event().name("dashboard-update").data(data));
            } catch (IOException e) {
                log.debug("SSE client disconnected for campaign: {}", campaignId);
                if (scheduledTask.get() != null) scheduledTask.get().cancel(true);
            } catch (Exception e) {
                log.error("Error streaming dashboard for campaign: {}", campaignId, e);
                emitter.completeWithError(e);
            }
        }, 0, 5, TimeUnit.SECONDS));

        emitter.onCompletion(() -> { if (scheduledTask.get() != null) scheduledTask.get().cancel(true); });
        emitter.onTimeout(() -> { if (scheduledTask.get() != null) scheduledTask.get().cancel(true); });
        emitter.onError((ex) -> { if (scheduledTask.get() != null) scheduledTask.get().cancel(true); });

        return emitter;
    }

    /**
     * Stream real-time dashboard updates for a specific campaign activity.
     *
     * Sends dashboard data every 5 seconds via Server-Sent Events (SSE).
     * Marketers can monitor FCFS campaigns in real-time without manual refresh.
     *
     * @param activityId the campaign activity ID to monitor
     * @param period     the time period for analytics (default: 7d)
     * @return SseEmitter that streams dashboard updates
     */
    @GetMapping(value = "/stream/activity/{activityId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamActivityDashboard(
            @PathVariable Long activityId,
            @RequestParam(defaultValue = "7d") String period) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        DashboardPeriod dashboardPeriod = DashboardPeriod.fromCode(period);
        AtomicReference<ScheduledFuture<?>> scheduledTask = new AtomicReference<>();

        log.info("SSE connection opened for activity: {}, period: {}", activityId, period);

        scheduledTask.set(scheduler.scheduleAtFixedRate(() -> {
            try {
                DashboardResponse data = dashboardService.getDashboardByActivity(
                        activityId, dashboardPeriod, null, null);
                emitter.send(SseEmitter.event().name("dashboard-update").data(data));
                log.debug("Sent dashboard update for activity: {}", activityId);
            } catch (IOException e) {
                log.debug("SSE client disconnected for activity: {}", activityId);
                if (scheduledTask.get() != null) scheduledTask.get().cancel(true);
            } catch (Exception e) {
                log.error("Error streaming dashboard for activity: {}", activityId, e);
                emitter.completeWithError(e);
            }
        }, 0, 2, TimeUnit.SECONDS));

        emitter.onCompletion(() -> {
            if (scheduledTask.get() != null) scheduledTask.get().cancel(true);
            log.info("SSE completed for activity: {}", activityId);
        });

        emitter.onTimeout(() -> {
            if (scheduledTask.get() != null) scheduledTask.get().cancel(true);
            log.warn("SSE timeout for activity: {}", activityId);
        });

        emitter.onError((ex) -> {
            if (scheduledTask.get() != null) scheduledTask.get().cancel(true);
            log.error("SSE error for activity: {}", activityId, ex);
        });

        return emitter;
    }
}
