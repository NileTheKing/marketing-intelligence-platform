# 2026 H2 Portfolio Hardening Roadmap

## Purpose

This roadmap defines the next Axon improvements for backend portfolio depth.

The goal is not to add more features. The goal is to turn the existing event-driven commerce platform into a stronger operational backend story:

- reproduce a spike scenario
- observe where the system slows down
- store operational history as data
- make recovery or scaling decisions explainable
- keep AI as a bounded assistant, not an autonomous controller

## Target Companies

Primary target groups:

- large SI / enterprise IT companies
- commerce and platform companies with spike traffic
- CRM / marketing / customer-data teams such as Kia Global CRM and Hyundai AutoEver CRM

## Core Positioning

Axon should be positioned as:

```text
An event-driven commerce and CRM backend platform that handles spike traffic, asynchronous processing, data consistency, operational diagnostics, and controlled automation.
```

Do not position it as:

```text
An autonomous AI operations platform.
```

## Writing Rule

Portfolio sections should not be tool-adoption sections.

Weak section titles:

- Pinpoint adoption
- Actuator monitoring
- AI automation server
- k3s deployment

Strong section titles:

- Core DB flush bottleneck analysis under spike load
- API-Batch failure triage and retry approval flow
- Pre-scaling advisor for predictable event traffic
- MarketingRule execution result tracking

Observability, AI, and k3s are tools used inside a problem-solving story.

## Architecture Direction

### 0. Pre-Hardening Code Cleanup

Before adding larger operational features, fix small code-quality and safety issues that can distract from the main backend story.

Immediate cleanup scope:

1. Actuator exposure boundary
   - Keep `/actuator/health` public.
   - Restrict `/actuator/metrics` and `/actuator/prometheus` by authentication, profile, or internal network.
   - Reason: observability endpoints are useful for VM/APM testing, but broad public exposure is not a production-safe default.

2. Test/debug runtime separation
   - Move test-only controllers/endpoints behind a local/test profile.
   - Remove or isolate scheduler parameters that are explicitly marked as test-only.
   - Reason: test helpers are useful, but they should not look like production runtime behavior.

3. StoreController responsibility split
   - Move repository access, purchase persistence, user coupon lookup, and view DTO assembly out of `StoreController` into a service.
   - Reason: the controller should not mix HTTP/view concerns with persistence and domain updates.

4. Coupon duplicate check improvement
   - Replace per-message duplicate `exists` checks with batch prefetch for the current batch.
   - Keep or add a DB unique constraint for `(user_id, coupon_id)` as the final idempotency guard.
   - Reason: prefetch reduces query count, while the unique constraint handles concurrent duplicate issuance.

These are not portfolio headline features. They are code-review hygiene tasks that make the later hardening work easier to defend.

Implementation status:

- `/actuator/health` remains public; core-service no longer permits all `/actuator/**`.
- Test/debug endpoints are profile-scoped to `dev`/`test`; the explicitly test-parameterized `UserPurchaseScheduler` is also profile-scoped.
- `StoreController` now delegates repository access, purchase persistence, user coupon lookup, and view DTO assembly to `StoreViewService`.
- `CouponStrategy` replaced per-message duplicate checks with batch prefetch and `UserCoupon` now has a `(user_id, coupon_id)` unique constraint.

### 1. Docker Compose for Bottleneck Analysis

Use Docker Compose first for repeatable performance analysis.

Scope:

- Entry-service
- Core-service
- MySQL
- Redis
- Kafka
- Nginx
- Pinpoint
- Actuator
- k6

Reason:

- fewer variables than Kubernetes
- easier local/Oracle VM reproduction
- better for isolating application bottlenecks

Success criteria:

- k6 reproduces the FCFS spike scenario
- Pinpoint shows request traces across Entry/Core where possible
- Actuator exposes health and metrics
- at least one bottleneck is stated with evidence

### 2. k3s for Controlled Operations Automation

Use k3s later when Kubernetes API control is part of the feature.

Scope:

- deployment replica patching
- approval-based scale execution
- service-level operational automation

Reason:

- Docker Compose is enough for analysis
- k3s becomes useful when the system needs to call Kubernetes APIs safely
- the portfolio story becomes stronger when scaling is tied to a controlled operational workflow

## Main Upgrade 1: Event History Based Scale Advisor

### Problem

Predictable events such as FCFS campaigns create traffic before autoscaling can react. HPA is reactive, so the first spike can still hit the system before new pods are ready.

### Backend Idea

Store past event scale and performance data, combine it with upcoming event demand signals, calculate recommended replicas with a deterministic formula, and ask for human approval before changing k3s Deployment replicas.

### Inputs

Current event demand signals:

- notification subscriber count
- stock quantity
- campaign weight
- expected open time
- expected concentration window

Past event history:

- actual peak-window requests
- peak RPS / TPS
- p95 / p99 latency
- error rate
- CPU peak
- memory peak
- active replicas
- Kafka lag peak
- DB connection peak

### Formula Boundary

AI must not calculate replicas freely.

Replica recommendation must be deterministic:

```text
expectedPeakRps =
notificationSubscribers
* historicalArrivalRate
* campaignWeight
/ concentrationWindowSeconds

rpsPerReplicaAtTargetCpu =
(peakRps / replicas)
* (targetCpuPercent / observedCpuPercent)

requiredReplicas =
ceil(expectedPeakRps / rpsPerReplicaAtTargetCpu * safetyFactor)
```

AI role:

- summarize the calculation
- explain assumptions and risks
- generate approval message
- summarize the result after application

AI must not:

- invent metrics
- choose replicas without formula
- apply scaling without approval
- modify arbitrary Kubernetes resources

### Minimal Tables

```text
campaign_capacity_signal
- campaign_id
- notification_subscribers
- stock_quantity
- campaign_weight
- expected_open_at
- concentration_window_seconds

traffic_event_history
- campaign_id
- actual_requests_in_peak_window
- peak_rps
- peak_tps
- p95_latency_ms
- error_rate
- cpu_peak_percent
- memory_peak_mb
- replicas
- kafka_lag_peak
- db_connection_peak
- measured_at

scale_proposal
- campaign_id
- expected_peak_rps
- rps_per_replica
- safety_factor
- current_replicas
- recommended_replicas
- formula_version
- ai_summary
- status
- approved_by
- created_at
- applied_at
```

### Minimal API

```http
POST /admin/scaling/proposals
GET /admin/scaling/proposals/{id}
POST /admin/scaling/proposals/{id}/approve
POST /admin/scaling/proposals/{id}/reject
```

### Portfolio Message

Use this angle:

```text
For predictable spike traffic, I connected business demand signals such as notification subscribers with past performance history, calculated a pre-scale recommendation with a fixed formula, and applied k3s scaling only after human approval.
```

Avoid:

```text
AI autonomously optimized infrastructure cost and scaling.
```

## Main Upgrade 2: DLQ Failure Triage Agent

### Problem

DLQ isolates failed messages, but operations still need to know why the message failed and whether retry is safe.

### Backend Idea

Persist failed events in a queryable table, classify failure type, generate an AI summary, and allow retry only through an explicit approval or admin action.

### Minimal Tables

```text
failed_event
- id
- source_topic
- event_type
- aggregate_id
- payload
- failure_stage
- failure_reason
- retry_count
- status
- ai_summary
- created_at
- resolved_at
```

### AI Boundary

AI can:

- classify likely cause
- summarize payload and stack trace
- suggest whether retry looks safe
- generate Slack/admin message

AI cannot:

- directly update business tables
- retry messages by itself
- override deterministic retry rules

### Portfolio Message

```text
I did not stop at sending poison messages to DLQ. I stored failed events as operational data, classified the failure stage, and used an AI harness to summarize retry risk for the operator.
```

Implementation note:

- Current DLT handling isolates failed messages, but it does not yet provide operator-facing audit state.
- The failure table should be the source for status, retry count, failure stage, and AI summary.
- Kafka DLT remains the transport-level isolation path. The DB audit row is the operational recovery view.

## Main Upgrade 3: MarketingRule Execution History

### Problem

When an automated CRM rule appears not to work, the operator needs to know whether:

- there were no target users
- users were skipped by Redis deduplication
- Elasticsearch query failed
- Kafka publish failed
- rule configuration was invalid

### Backend Idea

Store each rule execution as operational history.

### Minimal Table

```text
marketing_rule_execution
- id
- rule_id
- status
- target_count
- skipped_duplicate_count
- published_count
- failure_stage
- failure_reason
- started_at
- finished_at
```

### Portfolio Message

```text
I made CRM automation observable by storing target count, duplicate-skip count, publish count, and failure stage per rule execution.
```

Multi-pod note:

- Kafka consumer flush workers are local queue processors. Kafka consumer groups distribute partition ownership, so the same message is not normally processed by multiple pods in the same group.
- Global scheduled jobs are different. `BehaviorTriggerScheduler`, stock sync, cohort batch, and segmentation batch read shared DB/ES state directly, so multi-pod deployment can run the same job more than once unless a scheduler lock or single-runner constraint is introduced.
- If multi-pod operation becomes part of the claim, add ShedLock or an equivalent DB/Redis scheduler lock for state-mutating jobs.

## Main Upgrade 3-B: Webhook Delivery Isolation

### Problem

Webhook delivery is external HTTP I/O. If it runs inside the campaign command consumer flush path, a slow external endpoint can delay local queue flushing for unrelated campaign commands.

Current code boundary:

```text
Kafka listener
-> in-memory command buffer
-> scheduled synchronized flush
-> strategy dispatch
-> FCFS / COUPON / WEBHOOK strategy
```

This means Kafka message reception is already separated from strategy execution, but the strategy execution path is still shared. A slow `WEBHOOK` strategy can keep the flush task open and delay the next drain/dispatch cycle for other command types.

### Backend Idea

Do not call external webhook endpoints directly from the campaign command flush path.

Use an outbox-style delivery boundary:

```text
Campaign command consumer
-> WebhookStrategy creates webhook_outbox PENDING row
-> flush returns quickly

WebhookDeliveryWorker
-> reads PENDING rows
-> sends HTTP request with Idempotency-Key
-> marks SENT / FAILED
-> increments retry count
```

### Threading Boundary

- The goal is not to replace every worker with virtual threads.
- Kafka listener and flush workers should remain lightweight and focused on receiving/flushing internal commands.
- Webhook delivery may use a dedicated scheduler or executor later.
- Virtual threads are a candidate for webhook HTTP delivery, but only with explicit concurrency/rate limits.

Near-term implementation option:

- Split the single command buffer into type-aware buffers or type-aware flush lanes.
- Keep internal DB-oriented strategies conservative until their idempotency and duplicate handling are verified.
- Move webhook HTTP delivery to a dedicated bounded executor first if the goal is to reduce flush-path blocking without introducing a durable outbox yet.
- Treat virtual threads as an implementation option for the webhook delivery executor, not as a substitute for separating the webhook responsibility from command flushing.
- If delivery state, operator retry, or process-crash recovery becomes part of the claim, promote the design to an outbox + delivery worker.

### Baseline Before Refactor

Do not refactor this path only because it looks cleaner. First create a load scenario that makes the coupling visible.

Suggested scenario:

```text
Input:
- mixed CAMPAIGN_ACTIVITY_COMMAND messages
- COUPON commands for internal DB writes
- WEBHOOK commands pointing to a mock endpoint with 2-3s delay
- optional FCFS/PURCHASE follow-up commands if the scenario needs richer traffic

Observe:
- command buffer size over time
- type별 처리 완료 시간
- webhook delay / retry / DLT count
- coupon issue completion time
- consumer lag if available
- core-service thread/CPU snapshot
```

Expected failure mode to confirm:

```text
WEBHOOK delay
-> shared flush task remains open
-> next drain/dispatch is delayed
-> COUPON/FCFS command processing completion time increases
```

Only after this baseline is captured, compare with:

```text
After:
- type-aware queue/flush lanes
- webhook delivery executor
- same command mix
- same webhook mock delay
- same VM/container resource boundary
```

Portfolio-safe claim after measurement:

```text
I found that Kafka reception was separated from business execution, but the post-Kafka in-memory flush path still shared different command types. Under delayed webhook delivery, internal coupon/FCFS commands could wait behind external HTTP work. I measured the coupling with a mixed-command load scenario, then separated type-specific flush lanes and webhook delivery execution to reduce delay propagation.
```

### Portfolio Message

```text
I separated external webhook delivery from the Kafka command flush path so slow partner endpoints could not delay internal campaign command processing. The command path only records delivery intent, while a dedicated worker handles retry, idempotency, and failure status.
```

Avoid:

```text
Virtual threads solved webhook latency.
```

## Main Upgrade 4: APM-Based Bottleneck Report

### Problem

k6 summaries alone show latency and error rate, but they do not show where the backend spends time.

### Backend Idea

Use Pinpoint and Actuator while running the same k6 scenario. Identify whether the bottleneck is:

- Entry API
- Redis Lua
- Kafka publish
- Core consumer
- DB batch flush
- Hikari connection wait
- SQL execution

### Output Artifact

Create a short report after the VM run:

```text
scenario
baseline metrics
trace evidence
candidate causes
rejected causes
confirmed bottleneck
change applied
after metrics
remaining limits
```

### Portfolio Message

```text
I used APM traces and runtime metrics to separate application, Redis, Kafka, and DB latency instead of guessing from aggregate k6 results.
```

## Recommended Execution Order

1. Finish Docker Compose + Pinpoint + Actuator + k6 analysis mode.
2. Create one bottleneck report from a fixed FCFS scenario.
3. Add `traffic_event_history` and store k6/APM summary data.
4. Implement deterministic Scale Advisor calculation.
5. Add k3s deployment patching behind approval.
6. Add DLQ Failure Triage Agent.
7. Add MarketingRule ExecutionHistory if CRM-specific applications are next.

## Company Mapping

| Target | Strongest angle |
|---|---|
| CJ Olive Young / commerce | predictable spike traffic, pre-scaling, FCFS consistency |
| Kia Global CRM | API-Batch failure isolation, execution history, data pipeline reliability |
| Hyundai AutoEver CRM | customer behavior data, CRM automation traceability, dashboard/LLM operations |
| large SI | operational standardization, failure triage, approval-based automation |
| IT platform companies | observability, deterministic automation, bounded AI harness |

## Non-Goals

- Fully autonomous AI operations
- Multi-node Kafka failover
- Redis Sentinel/Cluster failover
- FinOps optimization claims
- Production-grade SRE platform
- Real customer production operation claims

## Portfolio Risk Controls

- Use "advisor", "proposal", "approval", "operator" language.
- Avoid "autonomous", "self-healing", "AIOps complete" language.
- State that formula-based calculation is deterministic.
- State that AI summarizes and explains, but does not make unchecked infrastructure changes.
- Keep previous KT Cloud K2P Kubernetes results separate from Oracle VM/k3s reproduction results.
