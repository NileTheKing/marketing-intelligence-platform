package com.axon.core_service.service.llm;

import com.axon.core_service.AbstractIntegrationTest;
import com.axon.core_service.domain.dashboard.DashboardPeriod;
import com.axon.core_service.domain.dashboard.LTVBatch;
import com.axon.core_service.repository.LTVBatchRepository;
import com.axon.core_service.service.DashboardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Gemini LLM 에이전트 도구 호출 및 날짜 해석 테스트")
class GeminiLLMQueryServiceTest extends AbstractIntegrationTest {

    @Autowired
    private GeminiLLMQueryService geminiLLMQueryService;

    @MockitoBean
    private DashboardService dashboardService;

    @MockitoBean
    private LTVBatchRepository ltvBatchRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Gemini가 '작년 여름' 기간을 인자로 주었을 때, DashboardService에 정확한 날짜 범위가 전달되어야 한다")
    void testExecuteToolWithDateRange() {
        // given
        // Gemini가 보낸 것으로 가정하는 JSON 인자 (작년 여름: 2025-06-01 ~ 2025-08-31)
        var args = objectMapper.createObjectNode();
        args.put("campaignId", 1L);
        args.put("startDate", "2025-06-01");
        args.put("endDate", "2025-08-31");

        // when
        // private 메서드인 executeTool을 Reflection으로 호출하여 내부 로직 검증
        ReflectionTestUtils.invokeMethod(geminiLLMQueryService, "executeTool", "get_campaign_dashboard", args, 1L);

        // then
        // DashboardService가 CUSTOM 모드와 정확한 시작/종료 시간으로 호출되었는지 검증
        LocalDateTime expectedStart = LocalDateTime.of(2025, 6, 1, 0, 0, 0);
        LocalDateTime expectedEnd = LocalDateTime.of(2025, 9, 1, 0, 0, 0);

        verify(dashboardService).getDashboardByCampaign(
                eq(1L),
                eq(DashboardPeriod.CUSTOM),
                argThat(actualStart -> actualStart.isEqual(expectedStart)),
                argThat(actualEnd -> actualEnd.isEqual(expectedEnd))
        );
    }

    @Test
    @DisplayName("Gemini가 activity 기간 조회를 요청했을 때, Activity Dashboard에 정확한 날짜 범위가 전달되어야 한다")
    void testExecuteActivityToolWithDateRange() {
        // given
        var args = objectMapper.createObjectNode();
        args.put("activityId", 77L);
        args.put("startDate", "2025-09-01");
        args.put("endDate", "2025-09-30");

        // when
        ReflectionTestUtils.invokeMethod(geminiLLMQueryService, "executeTool", "get_activity_dashboard", args, 77L);

        // then
        LocalDateTime expectedStart = LocalDateTime.of(2025, 9, 1, 0, 0, 0);
        LocalDateTime expectedEnd = LocalDateTime.of(2025, 10, 1, 0, 0, 0);

        verify(dashboardService).getDashboardByActivity(
                eq(77L),
                eq(DashboardPeriod.CUSTOM),
                argThat(actualStart -> actualStart.isEqual(expectedStart)),
                argThat(actualEnd -> actualEnd.isEqual(expectedEnd))
        );
    }

    @Test
    @DisplayName("코호트 배치 데이터가 없으면 실시간 집계 대신 집계 대기 상태를 반환한다")
    void getCohortAnalysisWithoutBatchReturnsPending() {
        // given
        var args = objectMapper.createObjectNode();
        args.put("activityId", 77L);
        when(ltvBatchRepository.existsByCampaignActivityId(77L)).thenReturn(false);

        // when
        Object result = ReflectionTestUtils.invokeMethod(
                geminiLLMQueryService, "executeTool", "get_cohort_analysis", args, 77L);

        // then
        assertEquals(Map.of("message", "데이터 집계 중입니다."), result);
    }

    @Test
    @DisplayName("코호트 배치 데이터가 있으면 실시간 집계 없이 저장된 월별 결과를 반환한다")
    void getCohortAnalysisWithBatchReturnsStoredMonthlyResults() {
        // given
        var args = objectMapper.createObjectNode();
        args.put("activityId", 77L);
        LocalDateTime collectedAt = LocalDateTime.of(2026, 7, 1, 3, 0);
        LTVBatch batch = mock(LTVBatch.class);
        when(batch.getCollectedAt()).thenReturn(collectedAt);
        when(batch.getMonthOffset()).thenReturn(1);
        when(batch.getLtvCumulative()).thenReturn(BigDecimal.valueOf(120_000));
        when(batch.getAvgCac()).thenReturn(BigDecimal.valueOf(10_000));
        when(batch.getLtvCacRatio()).thenReturn(BigDecimal.valueOf(12));
        when(batch.getRepeatPurchaseRate()).thenReturn(BigDecimal.valueOf(25));
        when(batch.getAvgPurchaseFrequency()).thenReturn(BigDecimal.valueOf(2));
        when(batch.getAvgOrderValue()).thenReturn(BigDecimal.valueOf(60_000));
        when(batch.getMonthlyRevenue()).thenReturn(BigDecimal.valueOf(70_000));
        when(batch.getActiveUsers()).thenReturn(2);
        when(batch.getIsBreakEven()).thenReturn(true);
        when(batch.getCohortSize()).thenReturn(10);
        when(ltvBatchRepository.existsByCampaignActivityId(77L)).thenReturn(true);
        when(ltvBatchRepository.findByCampaignActivityIdOrderByMonthOffsetAsc(77L))
                .thenReturn(List.of(batch));

        // when
        Object result = ReflectionTestUtils.invokeMethod(
                geminiLLMQueryService, "executeTool", "get_cohort_analysis", args, 77L);

        // then
        List<?> rows = assertInstanceOf(List.class, result);
        Map<?, ?> first = assertInstanceOf(Map.class, rows.getFirst());
        assertEquals(collectedAt.toString(), first.get("analysisDate"));
        assertEquals(BigDecimal.valueOf(120_000), first.get("ltvCurrent"));
        assertEquals(true, first.get("isBreakEven"));
    }
}
