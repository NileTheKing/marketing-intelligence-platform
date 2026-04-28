package com.axon.core_service.service.llm;

import com.axon.core_service.domain.dashboard.DashboardPeriod;
import com.axon.core_service.service.DashboardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

@DisplayName("Gemini LLM 에이전트 날짜 해석 단위 테스트 (순수 로직 검증)")
class GeminiLLMQueryServiceUnitTest {

    @InjectMocks
    private GeminiLLMQueryService geminiLLMQueryService;

    @Mock
    private DashboardService dashboardService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("Gemini가 보낸 날짜 문자열(YYYY-MM-DD)이 백엔드의 LocalDateTime 범위로 정확히 변환되어야 한다")
    void testExecuteToolWithDateRangeLogic() {
        // given
        var args = objectMapper.createObjectNode();
        args.put("campaignId", 1L);
        args.put("startDate", "2025-06-01");
        args.put("endDate", "2025-08-31");

        // when
        ReflectionTestUtils.invokeMethod(geminiLLMQueryService, "executeTool", "get_campaign_dashboard", args, 1L);

        // then
        // 시작일은 00:00:00, 종료일은 23:59:59로 변환되었는지 검증
        LocalDateTime expectedStart = LocalDateTime.of(2025, 6, 1, 0, 0, 0);
        LocalDateTime expectedEnd = LocalDateTime.of(2025, 8, 31, 23, 59, 59);

        verify(dashboardService).getDashboardByCampaign(
                eq(1L),
                eq(DashboardPeriod.CUSTOM),
                argThat(actualStart -> actualStart != null && actualStart.isEqual(expectedStart)),
                argThat(actualEnd -> actualEnd != null && actualEnd.isEqual(expectedEnd))
        );
    }
}
