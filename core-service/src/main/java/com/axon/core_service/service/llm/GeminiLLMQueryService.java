package com.axon.core_service.service.llm;

import com.axon.core_service.domain.dashboard.DashboardPeriod;
import com.axon.core_service.domain.dto.dashboard.CampaignDashboardResponse;
import com.axon.core_service.domain.dashboard.LTVBatch;
import com.axon.core_service.repository.LTVBatchRepository;
import com.axon.core_service.service.CohortAnalysisService;
import com.axon.core_service.service.DashboardService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import com.axon.core_service.domain.dto.llm.DashboardQueryResponse;
import com.axon.core_service.domain.dto.dashboard.DashboardResponse;
import com.axon.core_service.domain.dto.dashboard.GlobalDashboardResponse;
import com.axon.core_service.domain.dto.dashboard.OverviewData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@Primary
@Profile("gemini | prod")
@RequiredArgsConstructor
public class GeminiLLMQueryService implements LLMQueryService {

    private final DashboardService dashboardService;
    private final CohortAnalysisService cohortAnalysisService;
    private final LTVBatchRepository ltvBatchRepository;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api.key}")
    private String apiKey;

    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    @PostConstruct
    public void init() {
        log.info("Gemini API Key loaded: {}",
                (apiKey != null && !apiKey.isBlank())
                        ? "YES (" + apiKey.substring(0, Math.min(apiKey.length(), 4)) + "***)"
                        : "NO");
    }

    @Override
    public DashboardQueryResponse processQuery(Long campaignId, String query) {
        log.info("Gemini LLM processing query for campaign: {} (Mode: Hybrid/Tool-Calling)", campaignId);

        // 1. Define Tools (Functions) for Gemini
        List<Map<String, Object>> tools = List.of(
            Map.of("function_declarations", List.of(
                Map.of(
                    "name", "get_campaign_dashboard",
                    "description", "캠페인의 방문자, 구매자, 수익(GMV) 등 주요 성과 지표와 활동별 비교 데이터를 가져옵니다.",
                    "parameters", Map.of(
                        "type", "OBJECT",
                        "properties", Map.of(
                            "campaignId", Map.of("type", "NUMBER", "description", "캠페인 ID"),
                            "period", Map.of("type", "STRING", "description", "조회 기간 (1d, 7d, 30d)")
                        ),
                        "required", List.of("campaignId")
                    )
                ),
                Map.of(
                    "name", "get_cohort_analysis",
                    "description", "특정 활동의 리텐션, LTV(고객 생애 가치), CAC(고객 획득 비용) 등 심층 분석 데이터를 가져옵니다.",
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

        // 2. Initial Call to Gemini with Tools
        String geminiResponse = callGeminiApiWithTools(query, tools, campaignId);
        
        // 3. Simple Mock of Dashboard Overview for the response DTO
        OverviewData overview = dashboardService.getDashboardByCampaign(campaignId, DashboardPeriod.SEVEN_DAYS, null, null).overview();
        
        return new DashboardQueryResponse(geminiResponse, overview, "GEMINI_TOOL_CALLING");
    }

    private String callGeminiApiWithTools(String userQuery, List<Map<String, Object>> tools, Long contextId) {
        try {
            String url = GEMINI_URL + "?key=" + apiKey;
            
            // First Request: Send Query + Tools
            var requestBody = new HashMap<String, Object>();
            requestBody.put("contents", List.of(
                Map.of("role", "user", "parts", List.of(Map.of("text", userQuery)))
            ));
            requestBody.put("tools", tools);

            JsonNode response = restClientBuilder.build()
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode firstPart = response.path("candidates").get(0).path("content").path("parts").get(0);
            
            // Check if Tool Call was requested
            if (firstPart.has("functionCall")) {
                String funcName = firstPart.get("functionCall").get("name").asText();
                JsonNode args = firstPart.get("functionCall").get("args");
                log.info("🚀 Gemini Requested Tool Call: {} with args: {}", funcName, args);

                Object toolResult = executeTool(funcName, args, contextId);
                String resultJson = serializeData(toolResult);

                // Second Request: Send Tool Result back to Gemini for final interpretation
                var secondRequestBody = new HashMap<String, Object>();
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
                // tools must be present in every turn
                secondRequestBody.put("tools", tools);

                JsonNode finalResponse = restClientBuilder.build()
                        .post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(secondRequestBody)
                        .retrieve()
                        .body(JsonNode.class);

                return finalResponse.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
            }

            return firstPart.path("text").asText();

        } catch (Exception e) {
            log.error("Gemini Tool Call failed", e);
            return "AI 분석 중 오류가 발생했습니다: " + e.getMessage();
        }
    }

    private Object executeTool(String name, JsonNode args, Long defaultId) {
        if ("get_campaign_dashboard".equals(name)) {
            Long id = args.has("campaignId") ? args.get("campaignId").asLong() : defaultId;
            return dashboardService.getDashboardByCampaign(id, DashboardPeriod.SEVEN_DAYS, null, null);
        } else if ("get_cohort_analysis".equals(name)) {
            Long id = args.has("activityId") ? args.get("activityId").asLong() : defaultId;
            return cohort_month_data(id);
        }
        return Map.of("error", "Unknown function: " + name);
    }

    @Override
    public DashboardQueryResponse processQueryByActivity(Long activityId, String query) {
        // Simple redirect to campaign-style tool calling for demonstration consistency
        return processQuery(activityId, query);
    }

    @Override
    public DashboardQueryResponse processGlobalQuery(String query) {
        GlobalDashboardResponse dashboardData = dashboardService.getGlobalDashboard();
        // Fallback to simple RAG for global for now to keep code concise
        String contextJson = serializeData(Map.of("global_dashboard", dashboardData));
        String prompt = buildPrompt(query, contextJson);
        String geminiResponse = callGeminiApi(prompt);
        return new DashboardQueryResponse(geminiResponse, dashboardData.overview(), "GEMINI_RAG_FALLBACK");
    }

    private Object cohort_month_data(Long campaignActivityId) {
        Optional<LTVBatch> batchData = ltvBatchRepository.findTopByCampaignActivityIdOrderByMonthOffsetDesc(campaignActivityId);
        if (batchData.isPresent()) {
            return convertBatchToMap(batchData.get());
        }
        return cohortAnalysisService.analyzeCohortByActivity(campaignActivityId, null, null);
    }

    private Map<String, Object> convertBatchToMap(LTVBatch batch) {
        Map<String, Object> map = new HashMap<>();
        map.put("analysisDate", batch.getCollectedAt().toString());
        map.put("ltvCurrent", batch.getLtvCumulative());
        map.put("avgCAC", batch.getAvgCac());
        map.put("ratioCurrent", batch.getLtvCacRatio());
        map.put("repeatPurchaseRate", batch.getRepeatPurchaseRate());
        map.put("isBreakEven", batch.getIsBreakEven());
        return map;
    }

    private String buildPrompt(String userQuery, String dataContext) {
        return """
                You are an expert CRM Data Analyst. Answer the user's question based on the provided data.
                
                Context Data:
                %s
                
                User Question: "%s"
                
                IMPORTANT: You MUST answer in Korean.
                """.formatted(dataContext, userQuery);
    }

    private String callGeminiApi(String prompt) {
        try {
            String url = GEMINI_URL + "?key=" + apiKey;
            log.info("Calling Gemini API. URL: {} (key masked), Prompt length: {}",
                    GEMINI_URL, prompt.length());

            var requestBody = Map.of(
                    "contents", List.of(
                            Map.of("parts", List.of(
                                    Map.of("text", prompt)))));

            JsonNode response = restClientBuilder.build()
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);

            log.info("Gemini API response received successfully.");

            return response.path("candidates").get(0)
                    .path("content").path("parts").get(0)
                    .path("text").asText();

        } catch (Exception e) {
            log.error("Gemini API call failed details. URL: {}", GEMINI_URL, e);
            return "죄송합니다. AI 분석 서버와 연결할 수 없습니다. (Error: " + e.getClass().getName() + " - " + e.getMessage() + ")";
        }
    }

    private String serializeData(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            return "{}";
        }
    }
}
