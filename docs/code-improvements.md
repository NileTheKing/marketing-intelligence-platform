# 코드 개선 메모

포트폴리오/면접 대비 관점에서 발견한 개선 포인트 목록.

---

## BehaviorEventService.java

**파일**: `core-service/src/main/java/com/axon/core_service/service/BehaviorEventService.java`

- `java.util.Map`, `java.util.List`, `java.util.Collections` 등 FQCN 인라인 사용 → import로 정리
- `getHourlyTraffic(Long campaignId, ...)` deprecated placeholder 제거 또는 구현
- `getCampaignStats()` / `getAllCampaignStats()` 로직 중복 → 공통 private 메서드 추출
- `buildMultiActivityIdFilter()`에 `minimumShouldMatch("1")` 누락 → `buildMultiTriggerTypeFilter()`와 동일하게 추가 (잠재적 버그)

---

## GeminiLLMQueryService.java — Function 고도화

**파일**: `core-service/src/main/java/com/axon/core_service/service/llm/GeminiLLMQueryService.java`

- 현재 Function 3개: `get_global_dashboard`, `get_campaign_dashboard`, `get_cohort_analysis`
- `get_activity_dashboard` 추가 고려 — 캠페인 활동(CampaignActivity) 단위 지표 조회 Function 부재
  - campaign(캠페인 기획)과 activity(실제 이벤트 실행)는 별도 엔티티인데 LLM이 activity 레벨 질의 시 적절한 Function 없음
  - 추가 시 RAG 컨텍스트가 activity 단위 대시보드인 경우 FC 정확도 향상 기대
