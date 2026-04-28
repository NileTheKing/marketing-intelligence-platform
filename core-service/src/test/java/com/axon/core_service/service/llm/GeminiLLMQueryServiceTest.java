package com.axon.core_service.service.llm;

import com.axon.core_service.AbstractIntegrationTest;
import com.axon.core_service.domain.dashboard.DashboardPeriod;
import com.axon.core_service.service.DashboardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

@DisplayName("Gemini LLM 에이전트 도구 호출 및 날짜 해석 테스트")
class GeminiLLMQueryServiceTest extends AbstractIntegrationTest {

    @Autowired
    private GeminiLLMQueryService geminiLLMQueryService;

    @MockBean
    private DashboardService dashboardService;

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
        LocalDateTime expectedEnd = LocalDateTime.of(2025, 8, 31, 23, 59, 59);

        verify(dashboardService).getDashboardByCampaign(
                eq(1L),
                eq(DashboardPeriod.CUSTOM),
                argThat(actualStart -> actualStart.isEqual(expectedStart)),
                argThat(actualEnd -> actualEnd.isEqual(expectedEnd))
        );
    }
}
