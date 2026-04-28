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

    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent";

    @PostConstruct
    public void init() {
        log.info("Gemini API Key loaded: {}", (apiKey != null && !apiKey.isBlank()) ? "YES (" + apiKey.substring(0, Math.min(apiKey.length(), 4)) + "***)" : "NO");
    }

    @Override
    public DashboardQueryResponse processQuery(Long campaignId, String query) {
        log.info("Gemini LLM processing query for campaign: {}", campaignId);

        CampaignDashboardResponse dashboardData = dashboardService.getDashboardByCampaign(campaignId, DashboardPeriod.SEVEN_DAYS, null, null);
        Object cohortData = null;
        if (shouldFetchCohort(query) && !dashboardData.activities().isEmpty()) {
            Long representativeActivityId = dashboardData.activities().get(0).activityId();
            log.info("Fetching cohort data for representative activity: {}", representativeActivityId);
            // Campaign level aggregation is complex, using representative activity's real-time analysis for now

            cohortData = cohort_month_data(representativeActivityId);
        }

        // Context Pinning System Instruction
        String systemInstruction = String.format(
            "당신은 Axon CRM 전문가입니다. 현재 보고계신 [캠페인 ID: %d] 데이터는 이미 지식(RAG)으로 주어졌습니다. " +
            "만약 이 외에 다른 상품이나 캠페인 정보가 필요하다면, 직접 물어보지 말고 제공된 도구를 사용하십시오.", 
            campaignId);

        String geminiResponse = callGeminiApiWithTools(query, getGlobalTools(), campaignId, dashboardData);
        return new DashboardQueryResponse(geminiResponse, dashboardData.overview(), "GEMINI_HYBRID_CAMPAIGN");
    }

    private Object cohort_month_data(Long campaignActivityId) {
        Object CD = null;
        Optional<LTVBatch> batchData = ltvBatchRepository.findTopByCampaignActivityIdOrderByMonthOffsetDesc(campaignActivityId);

        if (batchData.isPresent()) {
            log.info("Using cached LTVBatch data for activity: {}", campaignActivityId);
            CD = convertBatchToMap(batchData.get());
        } else {
            // 2. If no batch data, fetch real-time data
            log.info("Fetching realtime cohort data for activity: {}", campaignActivityId);
            CD = cohortAnalysisService.analyzeCohortByActivity(campaignActivityId, null, null);
        }
        return CD;
    }

    @Override
    public DashboardQueryResponse processQueryByActivity(Long activityId, String query) {
        log.info("Gemini LLM processing query for activity: {}", activityId);

        DashboardResponse dashboardData = dashboardService.getDashboardByActivity(activityId, DashboardPeriod.SEVEN_DAYS, null, null);
        Object cohortData = null;
        if (shouldFetchCohort(query)) {
            // 1. Try to fetch latest batch data first
            cohortData = cohort_month_data(activityId);
        }

        // Context Pinning System Instruction
        String systemInstruction = String.format(
            "당신은 Axon CRM 전문가입니다. 현재 보고계신 [활동 ID: %d] 데이터는 이미 지식(RAG)으로 주어졌습니다. " +
            "이 외의 정보가 필요하다면 제공된 도구를 사용하십시오.", 
            activityId);

        String geminiResponse = callGeminiApiWithTools(query, getGlobalTools(), activityId, dashboardData);
        return new DashboardQueryResponse(geminiResponse, dashboardData.overview(), "GEMINI_HYBRID_ACTIVITY");
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
        String geminiResponse = callGeminiApiWithTools(query, getGlobalTools(), 0L, dashboardData);
        
        return new DashboardQueryResponse(geminiResponse, dashboardData.overview(), "GEMINI_HYBRID_GLOBAL");
    }

    private List<Map<String, Object>> getGlobalTools() {
        return List.of(
            Map.of("function_declarations", List.of(
                Map.of(
                    "name", "get_global_dashboard",
                    "description", "전체 캠페인의 매출 순위, 방문자 순위, ROAS 효율성 지표를 가져옵니다. 특정 기간 조회가 필요하면 startDate와 endDate를 사용하십시오.",
                    "parameters", Map.of(
                        "type", "OBJECT",
                        "properties", Map.of(
                            "startDate", Map.of("type", "STRING", "description", "조회 시작일 (YYYY-MM-DD)"),
                            "endDate", Map.of("type", "STRING", "description", "조회 종료일 (YYYY-MM-DD)")
                        )
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
            if (endDateStr != null) end = java.time.LocalDate.parse(endDateStr).atTime(23, 59, 59);
        } catch (Exception e) {
            log.warn("Failed to parse date from Gemini: {} / {}", startDateStr, endDateStr);
        }

        if ("get_campaign_dashboard".equals(name)) {
            Long campaignId = args.has("campaignId") ? args.get("campaignId").asLong() : defaultId;
            return dashboardService.getDashboardByCampaign(campaignId, DashboardPeriod.CUSTOM, start, end);
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

    private String callGeminiApiWithTools(String userQuery, List<Map<String, Object>> tools, Long defaultId, Object initialContext) {
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
                    "- 사용자가 다른 데이터를 원하거나 비교를 요청하면 도구를 체인으로 호출하십시오. " +
                    "- 답변 시에는 Markdown 형식을 적극 사용하고 시각적으로 깔끔하게(테이블 등) 출력하십시오."))
            ));

            JsonNode firstResponse = restClientBuilder.build()
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

                JsonNode secondResponse = restClientBuilder.build()
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(secondRequestBody)
                    .retrieve()
                    .body(JsonNode.class);

                if (secondResponse == null || secondResponse.path("candidates").isEmpty()) {
                    log.error("Gemini Tool Response is empty or invalid: {}", secondResponse);
                    return "분석 결과 생성에 실패했습니다 (API 응답 오류).";
                }

                JsonNode parts = secondResponse.path("candidates").get(0).path("content").path("parts");
                if (parts.isEmpty()) {
                    log.error("Gemini Tool Response parts are empty: {}", secondResponse);
                    return "분석 결과 생성 중 응답 형식이 올바르지 않습니다.";
                }

                return parts.get(0).path("text").asText();
            }

            return firstPart.path("text").asText();

        } catch (Exception e) {
            log.error("Tool-Calling API failed", e);
            return "분석 중 오류가 발생했습니다: " + e.getMessage();
        }
    }

    private DashboardQueryResponse processQueryInternal(String query, Object dashboardData, Object cohortData, OverviewData overview, String systemInstruction) {
        Map<String, Object> contextMap = new HashMap<>();
        contextMap.put("dashboard", dashboardData);
        if (cohortData != null) {
            contextMap.put("cohortAnalysis", cohortData);
        }

        String contextJson = serializeData(contextMap);

        // Log context size for debugging
        log.info("Context JSON size: {} characters, Has cohort data: {}",
                 contextJson.length(), cohortData != null);
        if (contextJson.length() < 200) {
            log.warn("Context JSON is very small ({}chars), might be missing data: {}",
                     contextJson.length(), contextJson);
        }

        String prompt = buildPrompt(query, contextJson);
        log.info("Prompt size: {} characters, Query type detected", prompt.length());

        String geminiResponse = callGeminiApi(prompt, systemInstruction);

        return new DashboardQueryResponse(geminiResponse, overview, "GEMINI_RAG_DETAIL");
    }

    private boolean shouldFetchCohort(String query) {
        String lower = query.toLowerCase();
        return lower.contains("ltv") || lower.contains("cohort") || lower.contains("retention")
            || lower.contains("재구매") || lower.contains("코호트") || lower.contains("생애")
            || lower.contains("평균주문금액") || lower.contains("구매 횟수") || lower.contains("누적이익")
            || lower.contains("cac") || lower.contains("누적매");
    }

    /**
     * 질문 타입 분석
     */
    private enum QueryType {
        STATS_ONLY,     // 통계만 원함
        INSIGHT_ONLY,   // 인사이트/분석만 원함
        HYBRID          // 통계 + 인사이트 둘 다
    }

    private QueryType analyzeQueryType(String query) {
        String lower = query.toLowerCase();

        // 인사이트 키워드
        boolean hasInsightKeywords =
            lower.contains("인사이트") || lower.contains("분석") ||
            lower.contains("평가") || lower.contains("어떻게") ||
            lower.contains("방법") || lower.contains("전략") ||
            lower.contains("개선") || lower.contains("향상") ||
            lower.contains("insight") || lower.contains("analyze") ||
            lower.contains("improve") || lower.contains("strategy");

        // 통계 키워드
        boolean hasStatsKeywords =
            lower.contains("얼마") || lower.contains("몇") ||
            lower.contains("평균") || lower.contains("총") ||
            lower.contains("현재") || lower.contains("what") ||
            lower.contains("how many") || lower.contains("how much");

        if (hasInsightKeywords && hasStatsKeywords) {
            return QueryType.HYBRID;
        } else if (hasInsightKeywords) {
            return QueryType.INSIGHT_ONLY;
        } else {
            return QueryType.STATS_ONLY;
        }
    }

    private String buildPrompt(String userQuery, String dataContext) {
        QueryType queryType = analyzeQueryType(userQuery);

        return switch (queryType) {
            case STATS_ONLY -> buildStatsPrompt(userQuery, dataContext);
            case INSIGHT_ONLY -> buildInsightPrompt(userQuery, dataContext);
            case HYBRID -> buildHybridPrompt(userQuery, dataContext);
        };
    }

    /**
     * 통계 전용 프롬프트
     */
    private String buildStatsPrompt(String userQuery, String dataContext) {
        return """
            You are an expert Data Analyst for an E-commerce platform.
            Answer the user's question based ONLY on the provided JSON data.

            Context Data:
            %s

            User Question: "%s"

            Instructions:
            - Provide direct, concise statistical answers
            - If the data is not available, say "해당 정보는 현재 데이터에 없습니다."
            - Format numbers with thousand separators (e.g., 1,000,000 → 1,000,000원)
            - Keep it brief (1-3 sentences)
            - Conflict Resolution: If data exists in both 'dashboard' and 'cohortAnalysis', prioritize 'cohortAnalysis' data as it is more accurate.
            IMPORTANT: You MUST answer in Korean.

            Example:
            Q: "이번 캠페인 참여자가 몇 명이야?"
            A: "이번 캠페인 참여자는 총 1,247명입니다. 목표 대비 124%% 달성했습니다."
            """.formatted(dataContext, userQuery);
    }

    /**
     * 인사이트 전용 프롬프트 (예시 기반 Few-shot learning)
     */
    private String buildInsightPrompt(String userQuery, String dataContext) {
        return """
            You are an expert E-commerce Marketing Strategist and Data Analyst.

            [Provided Data]
            %s

            [User Question]
            "%s"

            [Response Guidelines]

            1. Data-Driven Analysis (with specific numbers)
               - Cohort analysis: repeat purchase rate, LTV, CAC, etc.
               - Campaign performance: participation rate, conversion rate, fill rate, etc.
               - Pattern discovery: which customer segments/time periods/products show notable trends

            2. Root Cause Analysis
               - Infer "why" these results occurred from the data
               - Identify drop-off points and reasons for underperforming metrics

            3. Actionable Recommendations (Priority 1, 2, 3)
               Each action must include:
               - Specific execution method (channel, message, incentive details)
               - Target audience (who)
               - Timing (when)
               - Expected impact (quantitative, e.g., "2.5x LTV increase expected", "30%% conversion improvement")

            [Response Format Examples - ANSWER IN KOREAN LIKE THESE EXAMPLES]

            Example 1: "재구매율이 낮은데 어떻게 개선하죠?"

            - 최근 유입 코호트 분석
              지난달 'XX 캠페인'으로 유입된 신규 고객들의 첫 구매 후 30일 내 재구매율이 12%%에 불과합니다. (업계 평균 25%%). LTV 분석 결과, 첫 구매 금액이 3만 원 미만인 고객군은 재구매 확률이 현저히 떨어지는 패턴을 보입니다.

            - 충성도 증대 액션 아이템
              1. 첫 구매 경험 강화: 첫 구매 후 3일 이내에 "재구매 시 무료배송 + 5천 원 할인 쿠폰"을 문자로 발송하여 2차 구매를 유도하세요. (재구매 시 LTV 2.5배 상승 예측)
              2. 객단가(AOV) 상승 유도: 장바구니 단계에서 "1만 원 더 담으면 VIP 등급 혜택" 메시지를 노출하여 첫 결제 금액을 높이세요.
              3. 맞춤 상품 추천: 이탈 위험이 높은 고객군이 가장 많이 검색한 키워드 기반으로 개인화된 추천 상품 메일을 발송하세요.

            Example 2: "작년 크리스마스 캠페인이 저조했는데 올해는 어떤 전략을 써야 할까?"

            - 작년 크리스마스 캠페인 실패 요인 분석
              작년 데이터 분석 결과, '상세 페이지 조회'에서 '구매 시도'로 넘어가는 단계의 이탈률이 85%%로 매우 높았습니다. 특히 특정 고객층의 이탈이 두드러졌는데, 이는 당시 경쟁사 대비 할인율이 낮았거나 매력적인 혜택 부재가 원인으로 추정됩니다.

            - 올해 크리스마스 추천 전략
              1. 퍼널 개선: 상세 페이지에서 이탈을 막기 위해 "선착순 한정 15%% 추가 쿠폰" 팝업을 노출하여 구매 결심을 유도하세요.
              2. 타겟팅 강화: 작년 이탈했던 고객층을 타겟으로 매력적인 패키지 구성(인스타그래머블 패키지, 1+1 기프트 세트 등)을 제안합니다.
              3. 골든 타임 공략: 구매 데이터상 특정 시간대(예: 오후 8-10시)에 결제가 집중되었습니다. 이 시간에 맞춰 타임 세일 푸시 알림을 발송하세요.

            [Writing Principles]
            - Cite specific numbers from provided data
            - For unavailable data (search keywords, hourly patterns, etc.), note "Further analysis required" or "Data collection recommended"
            - Be specific enough for immediate practical application
            - Provide quantitative expected impact for each action
            - Clearly prioritize (Priority 1 has highest impact)
            - Keep concise for chat interface while including essential information
            
            [Tone & Style]
            - Professional and trustworthy
            - Action-oriented: use "~하세요", "~을 권장합니다"
            - Focus on core message without unnecessary decoration
            - Conflict Resolution: If data exists in both 'dashboard' and 'cohortAnalysis', prioritize 'cohortAnalysis' data as it is more accurate
            IMPORTANT: You MUST answer in Korean, following the exact format and style of the examples above.
            """.formatted(dataContext, userQuery);
    }

    /**
     * 혼합형 프롬프트 (통계 + 인사이트)
     */
    private String buildHybridPrompt(String userQuery, String dataContext) {
        return """
            You are an expert E-commerce Marketing Strategist and Data Analyst.

            [Provided Data]
            %s

            [User Question]
            "%s"

            [Response Structure]

            The user wants BOTH statistics AND actionable insights. Provide your answer in two parts:

            Part 1: Statistical Answer
            - Directly answer the statistical question with specific numbers
            - Be concise (1-2 sentences)

            Part 2: Strategic Insights & Recommendations
            - Analyze why the current numbers are what they are
            - Provide 2-3 prioritized actionable recommendations
            - Each recommendation must include:
              * Specific execution method
              * Target audience and timing
              * Expected quantitative impact

            [Example - FOLLOW THIS FORMAT AND ANSWER IN KOREAN]

            Question: "평균 구매액이 얼마야? 이걸 올릴 방법 없나?"

            Part 1: 현재 통계
            현재 평균 구매액은 28,000원입니다. 지난달 대비 5%% 감소했으며, 목표 금액 35,000원에는 7,000원 부족합니다.

            Part 2: 개선 전략
            - 객단가 하락 원인 분석
              최근 유입 고객의 70%%가 저가 상품(2만원 이하) 위주로 구매하고 있습니다. 고가 상품 카테고리의 방문율은 높으나(45%%) 전환율이 낮습니다(8%%).

            - 객단가 상승 전략
              1. 번들링 제안: 장바구니에서 "추천 세트로 담으면 15%% 할인" 표시하여 연관 상품 함께 구매 유도 (평균 객단가 +12,000원 예상)
              2. 무료배송 문턱 조정: 현재 3만원에서 3.5만원으로 상향하여 추가 상품 담기 유도
              3. VIP 등급 혜택: 3.5만원 이상 구매 시 다음 구매 시 사용 가능한 5,000원 쿠폰 제공

            [Instructions]
            - Part 1: 간결하고 직접적인 통계 답변
            - Part 2: 상세 분석과 실행 가능한 권장사항
            - 제공된 데이터의 구체적 수치 인용 (1,000,000)
            - 정량적 예상 효과 제시
            - Conflict Resolution: If data exists in both 'dashboard' and 'cohortAnalysis', prioritize 'cohortAnalysis' data as it is more accurate
            IMPORTANT: You MUST answer in Korean, following the exact format of the example above.
            """.formatted(dataContext, userQuery);
    }

    private String callGeminiApi(String prompt) {
        return callGeminiApi(prompt, null);
    }

    private String callGeminiApi(String prompt, String systemInstruction) {
        try {
            String url = GEMINI_URL + "?key=" + apiKey;
            
            Map<String, Object> requestBody = new HashMap<>();
            
            // Add System Instruction if provided
            if (systemInstruction != null) {
                requestBody.put("system_instruction", Map.of(
                    "parts", List.of(Map.of("text", systemInstruction))
                ));
            }

            requestBody.put("contents", List.of(
                Map.of("parts", List.of(
                    Map.of("text", prompt)
                ))
            ));

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
