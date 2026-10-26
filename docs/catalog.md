# 전체 문서 목록

현재 배포는 **Oracle Cloud VM + Docker Compose**다. Entry는 선착순 판정과 요청 수신,
Core는 구매 후처리와 마케팅 기능을 담당한다.

[개발 문서 안내로 돌아가기](README.md)

## 상태와 근거

`active`는 현재 참조 문서라는 뜻이며, 그 안의 모든 계획이 구현됐다는 뜻은 아니다.
구현·배포·실험 여부는 각 절의 상태, 날짜와 코드로 구분한다.
`reference`는 대조가 필요한 참고 자료, `legacy`는 과거 구조,
`superseded`는 후속 문서로 대체된 기록, `draft`는 미확정 내용이다.

현재 구현은 코드와 테스트, 배포 상태는 날짜가 있는 배포 기록과 실제 환경,
과거 성능은 당시 커밋과 실험 증거로 확인한다. 세션 메모리는 보조 자료이며,
개발에 필요한 결정은 해당 주제의 문서에서 찾을 수 있어야 한다.

문서와 코드가 충돌하면 코드를 확인하고 충돌을 정정한다. 과거 실험의 환경·수치는
현재 결과로 바꾸지 않는다. 내부 파일명과 세션 지시는 해당 시점의 작업 맥락이다.

## 문서 목록

폴더는 문서의 주된 역할로 분류했다. 구현이 끝난 기능의 설계 문서는
`architecture/`, DB 정합성 개선은 `decisions/`, 측정 근거는 `experiments/`,
실행 절차는 `operations/`에서 찾는다. `archive/`는 과거 구조와 실험 근거를 보존한다.

### 현재 구조와 기능 설계

| 문서 | 상태 | 내용 |
|---|---|---|
| [관리자 콘솔 디자인](architecture/admin-console-design.md) | active | 공통 디자인과 화면 확인 기록 |
| [실패 분석 AI 에이전트](architecture/dlq-failure-triage-agent-v1-plan.md) | active | Core·AI·Slack의 구현된 분석·재시도 승인 흐름 |
| [행동 이벤트 스키마](architecture/behavior-event-schema-v1.md) | active | 속성 타입과 인증 사용자 식별. ES 전환은 문서상 대기 상태 |
| [선착순 설계 변천](architecture/campaign-activity-limit-flow.md) | reference | 초기 대안과 예약 설계 참고 |
| [쿠폰 발급 실행과 승인](architecture/campaign-activity-run-review-v1-plan.md) | active | 대상 확정과 승인 흐름. migration·배포 상태는 본문 참조 |
| [쿠폰 캠페인 효과 분석](architecture/campaign-incrementality-analysis-v1-plan.md) | active | 실험군·대조군 배정과 매출 효과 계산 |
| [코드 구조와 도메인 경계](architecture/domain-refactoring-map.md) | active | 서비스·패키지·컴포넌트 탐색 |
| [구매 후처리 트랜잭션](architecture/fcfs-entry-purchase-transaction-boundary-handoff.md) | active | 현재 벌크 저장, 실패 경계와 8월 회귀 근거 |
| [LLM 대시보드 질의 설계](architecture/llm-query-architecture.md) | reference | 설계 당시 참고 구조. 코드 대조 필요 |
| [마케팅 실행과 전달 이력](architecture/marketing-action-execution-dispatch-history-plan.md) | active | 업무 실행과 개별 전송 시도 구분 |
| [구매 취소와 캠페인 전용 상품](architecture/purchase-cancellation-and-campaign-product-v1-plan.md) | active | 취소 정책과 집계 반영 경계 |
| [구매·집계 대사](architecture/reconciliation-issue-v1-plan.md) | active | 불일치 기록, 재계산과 운영 확인 |
| [예약·결제 흐름 개요](architecture/reservation-payment-flow.md) | reference | 토큰 설계 참고. 실제 PG 연동은 미구현 |
| [HTTP API 경로 계약](architecture/rest-api-route-clean-cutover-plan.md) | active | SSR 화면과 REST API 분리 및 변경 기록 |
| [RFM 고객 세그먼트](architecture/rfm-audience-segment-v1-plan.md) | active | 구매 기반 계산과 마케팅 대상 필터 |
| [토큰 기반 결제 상세](architecture/token-based-payment-flow.md) | reference | 2단계 토큰 설계와 예외 시나리오 참고 |

### DB 정합성 개선

| 문서 | 상태 | 내용 |
|---|---|---|
| [DB·JPA 정합성 감사](decisions/db-jpa-integrity-audit-2026-08-11.md) | active | 쿼리·제약·트랜잭션 및 10월 벌크 배포 기록 |

### 실험과 진단 기록

| 문서 | 상태 | 내용 |
|---|---|---|
| [Redis 선착순 판정 비교](experiments/dev-log-2026-10-21-redis-reservation-comparison.md) | active evidence | 개별 호출·WATCH·Lua 비교. Redis 구간과 두 HTTP API 비교(개별 호출, WATCH) 구분 |
| [장바구니 이탈 후보 조회](experiments/abandoned-cart-coupon-candidate-query-v1-plan.md) | active | 후보 상태, SQL·인덱스 비교와 실제 쿠폰 발급 측정 |
| [인프라·애플리케이션 병목 진단](experiments/dev-log-2026-07-10-fcfs-3000vu-bottleneck.md) | reference | 선착순 부하에서 nginx·CPU·측정 조건을 진단한 기록 |
| [외부 부하 발생기와 측정 경계](experiments/dev-log-2026-07-15-external-load-generator-boundary.md) | reference | 네트워크 영향과 유효한 비교 조건 |
| [Webhook 토픽 격리 실험](experiments/dev-log-2026-08-06-webhook-topic-isolation-ab.md) | active | 외부 호출 지연이 선착순 소비를 막는지 비교 |
| [휴면 고객 조회 실험](experiments/dormant-customer-reengagement-query-v1-plan.md) | active | 미구매 조건과 중복 발송 제외 인덱스 |
| [선착순 유입 속도 비교](experiments/fcfs-ingress-operating-window-ab-plan.md) | active | 고정 사용자·정원에서 응답 지연 측정 설계 |
| [Kafka 라우팅·오류 처리 확인](experiments/kafka-command-routing-error-handling-fact-check-2026-10-16.md) | reference (code snapshot checked 2026-10-16) | 2026-10-16 코드 기준 토픽과 실패 경로 |
| [스파이크 부하 진단](experiments/spike-traffic-observability-plan.md) | active | Compose 환경과 날짜별 진단 기록 |

### 운영과 재현 절차

추가 Compose 설정은 [파일별 용도와 실행 예시](../infrastructure/compose/README.md)를 참고한다.
개발·운영 도구는 [스크립트 안내](../scripts/README.md)에서 찾을 수 있다.
과거 배포 설정은 [보관 자료](../archive/README.md)에서 찾을 수 있다.

| 문서 | 상태 | 내용 |
|---|---|---|
| [로그·메트릭·트레이스 운영](operations/observability-correlation-v1-plan.md) | active | Alloy/Loki, Prometheus, Jaeger와 AI 관측 연결 |
| [Oracle Compose 실행 절차](operations/oracle-compose-baseline-runbook.md) | active | 배포·부하 실행 명령과 측정 근거 |
| [APM 진단 절차와 기록](operations/otel-jaeger-apm-plan.md) | active | OTel/Jaeger 설정과 과거 진단. 현행 운영은 관측 문서 참조 |

### 과거 기록

| 문서 | 상태 | 내용 |
|---|---|---|
| [2025년 개발 일지](archive/development-log-2025-11.md) | legacy | 초기 대시보드 개발과 당시 운영 준비 기록 |
| [초기 성능 개선 계획](archive/performance-improvement-plan.md) | legacy | 2025년 추정·목표 수치와 설계 제안 |
| [과거 코호트 배치 설명](archive/cohort-batch-flow-explained.md) | legacy | 당시 테스트 스케줄과 계산 설명. 현재 스케줄·성과와 구분 |
| [과거 대시보드 스크립트 안내](archive/dashboard-scripts-readme.md) | legacy | 초기 도구 안내와 당시 성능 주장 보존 |
| [대시보드 개발 기록 (2025-11-18)](archive/dev-log-2025-11-18-dashboard-architecture.md) | reference | 당시 구현과 의사결정. 현재 상태와 구분 |
| [Fluentd 행동 로그 전환 검토](archive/behavior-event-fluentd-plan.md) | legacy | 현재는 Kafka Connect→ES 사용 |
| [과거 행동 SDK 안내](archive/behavior-tracker.md) | superseded | 이전 API·토픽·초기화 예시. 현재 계약 아님 |
| [7월 30일 queue·offset 실험](archive/handoffs/session-handoff-2026-07-30.md) | reference (완료 기록) | 완료된 비교·강제 종료 실험. 당시 근거 보존 |
| [과거 Fluent Bit 배포안](archive/k8s/FLUENT_BIT_DEPLOYMENT.md) | legacy | K8s 로그 수집 계획 |
| [과거 K8s 모니터링 안내](archive/k8s/MONITORING_DEPLOYMENT.md) | legacy | 당시 Prometheus/Grafana 구성 |
| [과거 인프라 상태](archive/k8s/infrastructure-status-report.md) | legacy | KT Cloud K2P 당시 구성 |
| [과거 네트워크 구조](archive/k8s/network-architecture.md) | legacy | KT Cloud K2P·NodePort 경로 |
| [과거 결제 복구 구조](archive/payment-resilience-architecture.md) | legacy | 실패 로그를 Outbox로 설명한 오류 정정 |
| [과거 결제 복구 시나리오](archive/payment-resilience-flow.md) | legacy | 제거된 Core 결제·자동 복구 경로 |
| [과거 구매 이벤트 흐름](archive/purchase-event-flow.md) | superseded | 대체된 이벤트 핸들러 구조와 instrumentation 제안 |

## 기록 작성과 변경

- [실험 증거 확인 절차](experiment-evidence-workflow.md)
- [AGENTS.md](../AGENTS.md): 에이전트 작업 지침

같은 주제의 정본을 먼저 갱신한다. 새 문서는 이 목록에 등록하고, 대체된 문서는
후속 문서를 표시한다. 완료됐다는 이유만으로 유효한 설계나 실험 근거를 보관함으로
옮기지 않는다. 실험 리소스와 운영 인스턴스 수를 구조적 제한으로 일반화하지 않는다.
Kafka consumer 병렬성과 여러 인스턴스 운영은 파티션·consumer group 조건을,
전역 scheduler는 별도의 중복 실행 방지 조건을 확인한다.

## 문서 탐색 유지

`AGENTS.md`는 작업별 진입점, `architecture-map.md`는 서비스 경계,
이 목록은 주제별 정본을 안내한다. 상세 구현과 실행 이력을 세 곳에 반복하지 않는다.
긴 문서는 상단에 현재 설명, 제안, 과거 기록의 읽기 경로를 둔다.

진입 문서의 상대 Markdown 링크와 `AGENTS.md`/`CLAUDE.md` 일치는
`python3 scripts/ci/check-doc-navigation.py`로 검사하며 CI에서도 실행한다.
검사 대상은 스크립트의 `ENTRY_DOCS`에 명시한다. 외부 URL, 절 앵커, 코드 경로와
문서 내용의 사실성은 검사하지 않으므로 관련 변경 시 별도로 확인한다.

이 구성은 [OpenAI의 저장소 지식 관리 사례](https://openai.com/index/harness-engineering/)와
[Kiro의 주제별 지침 관리](https://kiro.dev/docs/steering/)를 참고했다.
도구별 파일 구조를 복제하지 않고 기존 폴더에서 필요한 문서를 찾아 읽는 방식으로 적용한다.
