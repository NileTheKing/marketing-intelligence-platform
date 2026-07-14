# Axon Documentation Map

## Purpose

This file separates current working documents from old backlog and portfolio source notes.

Do not delete old documents just because they are outdated. Mark their current role instead.

## Start Here (entry point)

| Document | Status | Use |
|---|---|---|
| `docs/architecture-map.md` | active | **새 투입자/에이전트 진입점.** 정체성 2축, 서비스, 이벤트 emit/consume 흐름, Kafka 토픽, 스코프 경계. 반 페이지. 상세는 flow 문서로 분기. |

## Current Planning Documents

| Document | Status | Use |
|---|---|---|
| `docs/plan/2026-h2-portfolio-hardening-roadmap.md` | active | 2026 H2 upgrade direction: observability, Scale Advisor, DLQ triage, execution history |
| `docs/plan/critical-refactoring-decision-log.md` | active | current code-quality refactoring decisions and ABLY interview prep follow-up backlog: transaction boundaries, DTO/OSIV, idempotency, unbounded queue/backpressure, Purchase/UserSummary split, scheduler safety |
| `docs/plan/domain-refactoring-map.md` | active | DDD-style structure map, ubiquitous language draft, bounded context candidates, and small Fowler-style refactoring units |
| `docs/plan/spike-traffic-observability-plan.md` | active | Oracle VM Docker Compose based spike bottleneck analysis |
| `docs/plan/otel-jaeger-apm-plan.md` | active | current OpenTelemetry/Jaeger APM setup and FCFS trace diagnosis plan |
| `docs/plan/oracle-compose-baseline-runbook.md` | active | current Oracle VM Compose FCFS baseline execution steps and artifact boundary |
| `docs/plan/rest-api-route-clean-cutover-plan.md` | active (implemented) | current SSR/API route contract and 2026-07-14 clean-cutover record |
| `docs/plan/marketing-rule-multi-action-handoff.md` | active (implemented) | implemented MarketingRule 1:N action expansion; records the bounded scope, acceptance tests, and required VM schema cutover |

## Active / Current Implementation References

These are still useful, but code must win if there is a conflict.

| Document | Status | Use |
|---|---|---|
| `docs/flow/payment-resilience-flow.md` | active/reference | payment resilience flow details |
| `docs/flow/reservation-payment-flow.md` | active/reference | reservation to payment flow |
| `docs/flow/token-based-payment-flow.md` | active/reference | token/payment flow reference |
| `docs/design/llm-query-architecture.md` | active/reference | LLM query architecture background |
| `docs/network-architecture.md` | active/reference | final KT Cloud K2P network topology used in the previous project deployment; keep as historical deployment reference, not as current Oracle VM/k3s source of truth |

## Current Portfolio Source Notes

| Document | Status | Use |
|---|---|---|
| `/Users/yangnail/Documents/obsidian-career/projects/이벤트기반커머스플랫폼/프로젝트_전체그림.md` | active | neutral project overview and portfolio interpretation boundary |
| `/Users/yangnail/Documents/obsidian-career/projects/이벤트기반커머스플랫폼/T1_*.md` | active | FCFS Redis Lua consistency story |
| `/Users/yangnail/Documents/obsidian-career/projects/이벤트기반커머스플랫폼/T2_*.md` | active | Kafka transaction pipeline and batch contamination story |
| `/Users/yangnail/Documents/obsidian-career/projects/이벤트기반커머스플랫폼/T3_*.md` | active | JS SDK, Kafka, Elasticsearch behavior pipeline |
| `/Users/yangnail/Documents/obsidian-career/projects/이벤트기반커머스플랫폼/T6_*.md` | active | MarketingRule behavior trigger campaign |
| `/Users/yangnail/Documents/obsidian-career/projects/이벤트기반커머스플랫폼/T8_*.md` | active | bounded LLM Function Calling and tool metadata |
| `/Users/yangnail/Documents/obsidian-career/projects/이벤트기반커머스플랫폼/T9_*.md` | active | SQL offloading and index optimization |
| `/Users/yangnail/Documents/obsidian-career/projects/이벤트기반커머스플랫폼/T12_*.md` | active | MySQL FOR UPDATE lock contention diagnosis |
| `/Users/yangnail/Documents/obsidian-career/projects/이벤트기반커머스플랫폼/T24_*.md` | active | Testcontainers and concurrency tests |
| `/Users/yangnail/Documents/obsidian-career/projects/이벤트기반커머스플랫폼/T25_*.md` | active | webhook failure isolation |
| `/Users/yangnail/Documents/obsidian-career/projects/이벤트기반커머스플랫폼/T26_*.md` | active | common funnel modeling and unsupported-type boundary |
| `/Users/yangnail/Documents/obsidian-career/projects/이벤트기반커머스플랫폼/T27_*.md` | active | OTel/Jaeger/Actuator based FCFS/nginx bottleneck diagnosis and Docker Compose payment load-test results |

## Reference Documents

| Document | Status | Use |
|---|---|---|
| `docs/PORTFOLIO_MASTER.md` | reference | old/current portfolio source; verify facts before reuse |
| `docs/payment-resilience-architecture.md` | reference | payment resilience architecture; contains Transactional Outbox wording that must be code-verified before reuse |
| `docs/micro-batch-implementation-plan.md` | reference | historical micro-batch implementation plan |
| `docs/FUNNEL_STANDARD_PLAN.md` | reference | funnel modeling background; T26 is newer for portfolio facts |
| `docs/funnel-expansion-plan.md` | reference | expansion ideas; do not claim unfinished campaign types as complete |
| `docs/cohort-analysis-optimization-plan.md` | reference | cohort optimization background; T9 is newer for portfolio facts |
| `docs/behavior-tracker.md` | reference | behavior tracking background; contains Fluentd transition wording, so T3/code is newer for portfolio facts |
| `docs/code-improvements.md` | reference | code cleanup notes |
| `docs/Filter_System_Architecture.md` | reference | filter system design context; verify current usage before reuse |
| `docs/devlog/dev-log-2025-11-18-dashboard-architecture.md` | reference | dashboard architecture development log |
| `docs/llm-insights-feature-plan.md` | reference | LLM feature planning context; T8 is newer for portfolio facts |
| `docs/plan/js-sdk-enhancement-plan.md` | reference | JS SDK extension ideas; T3 is newer for implemented facts |
| `docs/plan/redis-fast-validation-plan.md` | reference | Redis fast validation plan; verify against current entry-service implementation |
| `docs/flow/campaign-activity-limit-flow.md` | reference | FCFS architecture evolution record; T1/T2 are newer for portfolio facts |
| `docs/flow/purchase-event-flow.md` | reference | purchase event and instrumentation notes; contains Fluentd/TODO wording, so recheck before reuse |

## Legacy / Do Not Use As Current Source Without Recheck

| Document | Status | Reason |
|---|---|---|
| `docs/plan/remaining-tasks-and-improvements.md` | legacy backlog | contains old plans, completed items, and outdated claims such as Redisson-based load-test wording |
| `docs/plan/pinpoint-actuator-k6-prep.md` | legacy/reference | superseded by OTel/Jaeger plan after Pinpoint Docker HBase path failed on ARM Oracle VM |
| `docs/FCFS_Refactor.md` | legacy/reference | verify against current Redis Lua implementation before reuse |
| `docs/FLUENT_BIT_DEPLOYMENT.md` | legacy/reference | useful for old K8s log collection context, not current Oracle VM plan |
| `docs/MONITORING_DEPLOYMENT.md` | legacy/reference | older monitoring stack; first-pass plan now prefers OTel/Jaeger + Actuator for tracing diagnosis |
| `docs/infrastructure-status-report.md` | legacy/reference | old infrastructure state; do not mix with Oracle VM/k3s plan without rechecking |
| `docs/flow/behavior-event-fluentd-plan.md` | legacy/reference | Fluentd/Fluent Bit behavior-event plan; current behavior pipeline facts should come from code/T3 |
| `docs/project-tasks.md` | legacy backlog | old 4-week project plan; not a current implementation source |
| `docs/purchase-domain-refactoring-plan.md` | legacy/draft | planned purchase-domain refactor; do not claim as implemented without code check |
| `docs/plan/marketing-dashboard-development-plan.md` | legacy/reference | broad team plan mixing completed, planned, and future dashboard/LLM items |
| `docs/private/*` | private/reference | company-specific or old output materials; not project source of truth |

## Non-Source Assets / Ignore For Facts

| Path | Status | Use |
|---|---|---|
| `docs/assets/recordings/*` | asset | screenshots/gifs only; useful as evidence if matched to current claim |
| `docs/.DS_Store`, `docs/assets/.DS_Store`, `docs/private/.DS_Store` | ignore | macOS metadata |
| `docs/.Rhistory` | ignore | local R history, not project documentation |

## Needs Recheck Before Use

These documents are not classified as wrong, but they may contain old assumptions, future plans, or partially completed ideas.

| Document | Why recheck |
|---|---|
| `docs/PORTFOLIO_MASTER.md` | contains old Redisson wording and TODO sections; latest company portfolios/T-files may be newer |
| `docs/micro-batch-implementation-plan.md` | old implementation plan with Prometheus/Grafana monitoring assumptions |
| `docs/purchase-domain-refactoring-plan.md` | explicitly marked implementation pending in content |
| `docs/infrastructure-status-report.md` | old K8s/Prometheus/Grafana deployment state; separate from Oracle VM plan |
| `docs/payment-resilience-architecture.md` | claims Transactional Outbox based resilience that must be checked against current code and T2 wording before reuse |
| `docs/flow/purchase-event-flow.md` | contains Fluentd/TODO instrumentation notes that may no longer match current behavior pipeline |

## Current Upgrade Themes

Use these names for new work:

1. APM-based spike bottleneck analysis
2. Event history based Scale Advisor
3. DLQ Failure Triage Agent
4. MarketingRule ExecutionHistory
5. Reconciliation issue history if consistency operations are expanded
6. Pre-hardening cleanup: actuator boundary, test/debug profile separation, controller responsibility split, coupon duplicate prefetch, Core idempotency/backpressure, Purchase/UserSummary projection split, global scheduler safety
7. Webhook delivery isolation through outbox/worker when external integration becomes a stronger story

Avoid creating new documents with broad names such as:

- final plan
- real final plan
- portfolio upgrade
- monitoring new

Prefer specific names:

- `scale-advisor-design.md`
- `dlq-failure-triage-design.md`
- `marketing-rule-execution-history.md`
- `apm-bottleneck-report-YYYY-MM-DD.md`

## Fact Boundary

- Docker Compose is the first analysis environment.
- k3s is justified only when Kubernetes API based scaling is implemented.
- Previous KT Cloud K2P Kubernetes results and new Oracle VM/k3s results must be kept separate.
- AI may summarize, classify, and explain. AI must not make unchecked DB or infrastructure changes.
- Scale calculation must be formula-based and auditable.
- Kafka consumer local flush workers and global scheduled jobs must be discussed separately. Consumer groups handle Kafka partition ownership; global schedulers still need a lock or single-runner constraint under multi-pod deployment.
- Virtual threads are not a substitute for responsibility separation. Use them selectively for blocking external I/O workers, not as a blanket fix for Kafka listener or scheduler design.
