package com.axon.core_service.service.llm;

import com.axon.core_service.domain.dashboard.DashboardPeriod;
import com.axon.core_service.domain.dto.dashboard.CampaignDashboardResponse;
import com.axon.core_service.domain.dashboard.LTVBatch;
import com.axon.core_service.repository.LTVBatchRepository;
import com.axon.core_service.service.DashboardService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import com.axon.core_service.domain.dto.llm.DashboardQueryResponse;
import com.axon.core_service.domain.dto.dashboard.DashboardResponse;
import com.axon.core_service.domain.dto.dashboard.GlobalDashboardResponse;


import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@Primary
@Profile("gemini | prod")
public class GeminiLLMQueryService implements LLMQueryService {

    private final DashboardService dashboardService;
    private final LTVBatchRepository ltvBatchRepository;
    private final RestClient geminiRestClient;
    private final ObjectMapper objectMapper;

    public GeminiLLMQueryService(
            DashboardService dashboardService,
            LTVBatchRepository ltvBatchRepository,
            @Qualifier("geminiRestClient") RestClient geminiRestClient,
            ObjectMapper objectMapper) {
        this.dashboardService = dashboardService;
        this.ltvBatchRepository = ltvBatchRepository;
        this.geminiRestClient = geminiRestClient;
        this.objectMapper = objectMapper;
    }

    @Value("${gemini.api.key}")
    private String apiKey;

    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent";

    @PostConstruct
    public void init() {
        log.info("Gemini API key configured: {}", apiKey != null && !apiKey.isBlank());
    }

    @Override
    public DashboardQueryResponse processQuery(Long campaignId, String query) {
        log.info("Gemini LLM processing query for campaign: {}", campaignId);

        CampaignDashboardResponse dashboardData = dashboardService.getDashboardByCampaign(campaignId, DashboardPeriod.SEVEN_DAYS, null, null);

        GeminiToolResponse geminiResponse = callGeminiApiWithTools(query, getGlobalTools(), campaignId, dashboardData);
        return new DashboardQueryResponse(geminiResponse.answer(), dashboardData.overview(), "GEMINI_HYBRID_CAMPAIGN",
                geminiResponse.metadata());
    }

    @Override
    public DashboardQueryResponse processQueryByActivity(Long activityId, String query) {
        log.info("Gemini LLM processing query for activity: {}", activityId);

        DashboardResponse dashboardData = dashboardService.getDashboardByActivity(activityId, DashboardPeriod.SEVEN_DAYS, null, null);

        GeminiToolResponse geminiResponse = callGeminiApiWithTools(query, getGlobalTools(), activityId, dashboardData);
        return new DashboardQueryResponse(geminiResponse.answer(), dashboardData.overview(), "GEMINI_HYBRID_ACTIVITY",
                geminiResponse.metadata());
    }

    private Map<String, Object> convertBatchToMap(LTVBatch batch) {
        Map<String, Object> map = new HashMap<>();
        map.put("analysisDate", batch.getCollectedAt().toString());
        map.put("monthOffset", batch.getMonthOffset());
        
        // Match keys with CohortAnalysisResponse where possible
        map.put("ltvCurrent", batch.getLtvCumulative());
        map.put("avgCAC", batch.getAvgCac());
        map.put("ratioCurrent", batch.getLtvCacRatio());
        map.put("repeatPurchaseRate", batch.getRepeatPurchaseRate());
        map.put("avgPurchaseFrequency", batch.getAvgPurchaseFrequency());
        map.put("avgOrderValue", batch.getAvgOrderValue());
        
        // Additional batch-specific info
        map.put("monthlyRevenue", batch.getMonthlyRevenue());
        map.put("activeUsers", batch.getActiveUsers());
        map.put("isBreakEven", batch.getIsBreakEven());
        map.put("cohortSize", batch.getCohortSize());
        
        return map;
    }

    @Override
    public DashboardQueryResponse processGlobalQuery(String query) {
        log.info("Gemini LLM processing global query (Mode: Global Hybrid Agent)");

        // 1. 전역 대시보드 데이터 확보 (RAG 전용)
        GlobalDashboardResponse dashboardData = dashboardService.getGlobalDashboard();

        // 2. 도구 상자와 함께 전역 데이터를 지식(initialContext)으로 전달
        GeminiToolResponse geminiResponse = callGeminiApiWithTools(query, getGlobalTools(), 0L, dashboardData);
        
        return new DashboardQueryResponse(geminiResponse.answer(), dashboardData.overview(), "GEMINI_HYBRID_GLOBAL",
                geminiResponse.metadata());
    }

    private List<Map<String, Object>> getGlobalTools() {
        return List.of(
            Map.of("function_declarations", List.of(
                Map.of(
                    "name", "get_global_dashboard",
                    "description", "최근 30일 기준 전체 캠페인의 매출 순위, 방문자 순위, ROAS 효율성 지표를 가져옵니다.",
                    "parameters", Map.of(
                        "type", "OBJECT",
                        "properties", Map.of()
                    )
                ),
                Map.of(
                    "name", "get_campaign_dashboard",
                    "description", "특정 캠페인의 상세 성과 지표를 가져옵니다. 특정 기간(예: 작년 여름) 조회가 필요하면 startDate와 endDate를 명시하십시오.",
                    "parameters", Map.of(
                        "type", "OBJECT",
                        "properties", Map.of(
                            "campaignId", Map.of("type", "NUMBER", "description", "캠페인 ID"),
                            "startDate", Map.of("type", "STRING", "description", "조회 시작일 (YYYY-MM-DD)"),
                            "endDate", Map.of("type", "STRING", "description", "조회 종료일 (YYYY-MM-DD)")
                        ),
                        "required", List.of("campaignId")
                    )
                ),
                Map.of(
                    "name", "get_activity_dashboard",
                    "description", "특정 활동(Activity)의 상세 퍼널 및 성과 지표를 가져옵니다. 특정 기간 조회가 필요하면 startDate와 endDate를 명시하십시오.",
                    "parameters", Map.of(
                        "type", "OBJECT",
                        "properties", Map.of(
                            "activityId", Map.of("type", "NUMBER", "description", "활동 ID"),
                            "startDate", Map.of("type", "STRING", "description", "조회 시작일 (YYYY-MM-DD)"),
                            "endDate", Map.of("type", "STRING", "description", "조회 종료일 (YYYY-MM-DD)")
                        ),
                        "required", List.of("activityId")
                    )
                ),
                Map.of(
                    "name", "get_cohort_analysis",
                    "description", "특정 활동(Activity)의 코호트 분석(LTV, CAC, 재구매율) 데이터를 가져옵니다.",
                    "parameters", Map.of(
                        "type", "OBJECT",
                        "properties", Map.of(
                            "activityId", Map.of("type", "NUMBER", "description", "활동 ID")
                        ),
                        "required", List.of("activityId")
                    )
                )
            ))
        );
    }

    private Object executeTool(String name, com.fasterxml.jackson.databind.node.ObjectNode args, Long defaultId) {
        String startDateStr = args.has("startDate") ? args.get("startDate").asText() : null;
        String endDateStr = args.has("endDate") ? args.get("endDate").asText() : null;
        
        java.time.LocalDateTime start = null;
        java.time.LocalDateTime end = null;
        
        try {
            if (startDateStr != null) start = java.time.LocalDate.parse(startDateStr).atStartOfDay();
            if (endDateStr != null) end = java.time.LocalDate.parse(endDateStr).plusDays(1).atStartOfDay();
        } catch (Exception e) {
            log.warn("Failed to parse date from Gemini: {} / {}", startDateStr, endDateStr);
        }

        if ("get_campaign_dashboard".equals(name)) {
            Long campaignId = args.has("campaignId") ? args.get("campaignId").asLong() : defaultId;
            DashboardPeriod period = start != null ? DashboardPeriod.CUSTOM : DashboardPeriod.SEVEN_DAYS;
            return dashboardService.getDashboardByCampaign(campaignId, period, start, end);
        } else if ("get_activity_dashboard".equals(name)) {
            Long activityId = args.has("activityId") ? args.get("activityId").asLong() : defaultId;
            DashboardPeriod period = start != null ? DashboardPeriod.CUSTOM : DashboardPeriod.SEVEN_DAYS;
            return dashboardService.getDashboardByActivity(activityId, period, start, end);
        } else if ("get_global_dashboard".equals(name)) {
            // Note: GlobalDashboard currently has fixed 30 days, we can extend it later if needed
            return dashboardService.getGlobalDashboard();
        } else if ("get_cohort_analysis".equals(name)) {
            Long activityId = args.has("activityId") ? args.get("activityId").asLong() : defaultId;
            if (ltvBatchRepository.existsByCampaignActivityId(activityId)) {
                return ltvBatchRepository.findByCampaignActivityIdOrderByMonthOffsetAsc(activityId)
                    .stream().map(this::convertBatchToMap).toList();
            }
            return Map.of("message", "데이터 집계 중입니다.");
        }
        return Map.of("error", "Unknown function");
    }

    private GeminiToolResponse callGeminiApiWithTools(String userQuery, List<Map<String, Object>> tools, Long defaultId, Object initialContext) {
        try {
            String url = GEMINI_URL + "?key=" + apiKey;
            String contextJson = initialContext != null ? objectMapper.writeValueAsString(initialContext) : "";

            // 1. First Request: Ask Gemini if it needs to call a function
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("contents", List.of(
                Map.of("role", "user", "parts", List.of(Map.of("text", 
                    (initialContext != null ? "[Current Context]:\n" + contextJson + "\n\n" : "") + userQuery)))
            ));
            requestBody.put("tools", tools);
            requestBody.put("system_instruction", Map.of(
                "parts", List.of(Map.of("text", 
                    "당신은 Axon CRM 마케팅 전문가입니다. 제공된 도구를 적극적으로 활용하십시오. " +
                    "- 현재 페이지의 데이터가 [Current Context]로 이미 주어져 있다면 먼저 이를 참고하십시오. " +
                    "- 사용자가 다른 데이터를 원하거나 비교를 요청하면 적합한 도구를 호출하십시오. " +
                    "- 답변 시에는 Markdown 형식을 적극 사용하고 시각적으로 깔끔하게(테이블 등) 출력하십시오."))
            ));

            JsonNode firstResponse = geminiRestClient
                .post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(JsonNode.class);

            JsonNode firstPart = firstResponse.path("candidates").get(0).path("content").path("parts").get(0);

            if (firstPart.has("functionCall")) {
                String funcName = firstPart.get("functionCall").get("name").asText();
                com.fasterxml.jackson.databind.node.ObjectNode args = (com.fasterxml.jackson.databind.node.ObjectNode) firstPart.get("functionCall").get("args");
                
                log.info("Gemini requested tool call: {} with args: {}", funcName, args);

                // Execute Tool
                Object toolResult = executeTool(funcName, args, defaultId);

                // 2. Second Request: Send Tool Result back to Gemini
                Map<String, Object> secondRequestBody = new HashMap<>();
                secondRequestBody.put("system_instruction", requestBody.get("system_instruction"));
                secondRequestBody.put("contents", List.of(
                    Map.of("role", "user", "parts", List.of(Map.of("text", userQuery))),
                    Map.of("role", "model", "parts", List.of(Map.of("functionCall", firstPart.get("functionCall")))),
                    Map.of("role", "function", "parts", List.of(
                        Map.of("functionResponse", Map.of(
                            "name", funcName,
                            "response", Map.of("content", toolResult)
                        ))
                    ))
                ));

                JsonNode secondResponse = geminiRestClient
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(secondRequestBody)
                    .retrieve()
                    .body(JsonNode.class);

                if (secondResponse == null || secondResponse.path("candidates").isEmpty()) {
                    log.error("Gemini Tool Response is empty or invalid: {}", secondResponse);
                    return GeminiToolResponse.error("분석 결과 생성에 실패했습니다 (API 응답 오류).");
                }

                JsonNode parts = secondResponse.path("candidates").get(0).path("content").path("parts");
                if (parts.isEmpty()) {
                    log.error("Gemini Tool Response parts are empty: {}", secondResponse);
                    return GeminiToolResponse.error("분석 결과 생성 중 응답 형식이 올바르지 않습니다.");
                }

                return new GeminiToolResponse(parts.get(0).path("text").asText(), toolMetadata(funcName, args, defaultId));
            }

            return new GeminiToolResponse(firstPart.path("text").asText(), Map.of(
                    "toolUsed", false,
                    "defaultId", defaultId
            ));

        } catch (Exception e) {
            log.error("Tool-Calling API failed", e);
            return GeminiToolResponse.error("분석 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    private Map<String, Object> toolMetadata(String functionName,
                                             com.fasterxml.jackson.databind.node.ObjectNode args,
                                             Long defaultId) {
        return Map.of(
                "toolUsed", true,
                "toolName", functionName,
                "arguments", objectMapper.convertValue(args, Map.class),
                "defaultId", defaultId
        );
    }

    private record GeminiToolResponse(String answer, Map<String, Object> metadata) {
        private static GeminiToolResponse error(String answer) {
            return new GeminiToolResponse(answer, Map.of("toolUsed", false, "error", true));
        }
    }
}
