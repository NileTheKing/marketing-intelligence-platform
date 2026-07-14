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

## Writing Rule

Portfolio sections should not be tool-adoption sections.

Weak section titles:

- OpenTelemetry/Jaeger adoption
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

5. Core event idempotency and backpressure cleanup
   - Add DB-level uniqueness for `CampaignActivityEntry(campaign_activity_id, user_id)` if it remains the participation idempotency boundary.
   - Add internal command queue depth and flush duration metrics before changing queue behavior.
   - Move from unbounded queue semantics toward bounded queue plus Kafka pause/resume when sustained overload is reproduced.
   - Reason: current listener/flush split is useful, but production-grade backpressure and idempotency need explicit storage and metrics boundaries.

6. Purchase/UserSummary hot-path cleanup
   - Treat `Purchase` as the source-of-truth append path.
   - Move `UserSummary` toward projection semantics with separate retry/rebuild.
   - Evaluate JDBC batch or multi-row insert only if SQL logs and spike tests show JPA `saveAll` insert count as a measured bottleneck.
   - Reason: resume/portfolio claims should be backed by real persistence behavior, not by assuming JPA `saveAll` is DB bulk insert.

7. Global scheduler safety
   - Add single-runner protection for behavior trigger and stock-sync style schedulers under multi-pod deployment.
   - Add execution history for trigger/sync runs before adding AI summaries or operator recommendations.
   - Reason: Kafka consumer groups solve partition ownership, but they do not protect global scheduled jobs from running on every pod.

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
- OpenTelemetry/Jaeger
- Actuator
- k6

Reason:

- fewer variables than Kubernetes
- easier local/Oracle VM reproduction
- better for isolating application bottlenecks

Success criteria:

- k6 reproduces the FCFS spike scenario
- OpenTelemetry/Jaeger shows request traces across Entry/Core where possible
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

This should run as a pre-event automation, not as a manually entered event form.

Flow:

```text
scheduled pre-event check
-> find upcoming FCFS campaign/activity from DB
-> calculate scale proposal from event signals and history
-> ask operator for approval
-> Harness/k3s scale-out
-> wait for rollout/readiness
-> run synthetic warm-up against the hot path
-> validate warm-up metrics
-> mark event infrastructure as ready
```

The operator approves the proposed action, but the target event information should come from already scheduled campaign/activity records.

Important boundary:

- `Ready` pods are not enough for a predictable FCFS spike.
- The pipeline should verify that the actual Entry hot path is warm before the campaign opens.
- Warm-up must not pollute real business or analytics data.
- Human-in-the-loop is mandatory before infrastructure mutation, and conditional after failed warm-up validation.

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

Human-in-the-loop boundary:

- Required before `scale_proposal` is applied to k3s/Harness.
- The operator approves the recommended replica change, not arbitrary AI-generated infrastructure actions.
- Not required for successful rollout/readiness/warm-up checks once approval has been granted.
- Required again when warm-up or metric gate fails and the system needs a retry/abort/manual-intervention decision.

### Minimal Tables

```text
campaign_capacity_signal
- campaign_id
- campaign_activity_id
- notification_subscribers
- stock_quantity
- campaign_weight
- expected_open_at
- concentration_window_seconds
- warmup_enabled
- warmup_user_count

traffic_event_history
- campaign_id
- campaign_activity_id
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
- campaign_activity_id
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
- warmup_status
- warmup_p95_latency_ms
- warmup_error_rate
```

### Minimal API

```http
POST /admin/scaling/proposals/run-due
GET /admin/scaling/proposals/{id}
POST /admin/scaling/proposals/{id}/approve
POST /admin/scaling/proposals/{id}/reject
POST /admin/scaling/proposals/{id}/warmup
```

`run-due` is triggered by scheduler/Harness before event open time. It queries upcoming campaign/activity records and creates proposals for events that are inside the pre-scale window.

### Post-Approval Warm-Up Gate

After approval, scaling is not complete until warm-up passes.

Recommended Harness stages:

```text
1. Patch Entry/Core Deployment replicas.
2. Wait for rollout and readiness.
3. Execute synthetic FCFS warm-up.
4. Validate p95/error/CPU/restart/Kafka producer error metrics.
5. Mark scale proposal as READY or NEEDS_OPERATOR_ATTENTION.
```

Normal path:

```text
formula/AI explanation
-> human approval
-> automatic scale-out
-> automatic warm-up
-> automatic READY when metric gate passes
```

Exception path:

```text
warm-up or metric gate failure
-> mark NEEDS_OPERATOR_ATTENTION
-> operator chooses retry, abort, or manual intervention
```

Warm-up strategy:

- Use a dedicated warm-up campaign/activity or a warm-up flag.
- Exercise the same Entry reservation path that the real event uses.
- Include JWT validation, Redis Lua reservation path, token issue path, and backend Kafka producer path.
- Tag downstream messages with `warmup=true` or route them to a separate sink.
- Exclude warm-up events from dashboard, cohort, analytics, coupon, settlement, and marketing-trigger decisions.

Avoid:

- using only `/actuator/health` as readiness evidence
- warming a different endpoint that does not hit Redis/Kafka/JWT paths
- inserting warm-up behavior events into real marketing analytics without an exclusion flag

### Portfolio Message

Use this angle:

```text
For predictable spike traffic, I connected scheduled campaign signals with past performance history, calculated a pre-scale recommendation with a fixed formula, and applied k3s scaling only after human approval. The pipeline should then run a synthetic warm-up gate before the event opens, because a Ready pod is not necessarily a warmed hot path.
```

Human-in-the-loop angle:

```text
AI summarizes the proposal and risk, but infrastructure mutation happens only after operator approval. After that, rollout and warm-up checks are automated; if the warm-up gate fails, the system escalates back to the operator instead of silently opening the event.
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

## Main Upgrade 3-A: MarketingRule Multi-Action Model

Status: `active (implemented 2026-07-14)`

### Problem

Current `MarketingRule` mixes condition and one reward action:

```text
MarketingRule
-> behavior condition
-> rewardType
-> rewardReferenceId
```

This is enough for an MVP where a matching rule issues either a coupon or a webhook command. It is not enough for real CRM automation where one condition can trigger several actions together.

Example:

```text
Condition:
- user viewed product 100 at least 3 times in the last 7 days

Actions:
- issue coupon 10
- send CRM webhook template 99
- send push template 23
- send email template 77
```

### Backend Idea

Separate "when to execute" from "what to execute":

```text
MarketingRule
-> condition evaluation

MarketingAction
-> ruleId
-> actionType: COUPON | WEBHOOK
-> referenceId: couponId/templateId
-> isActive
```

Execution flow:

```text
BehaviorTriggerScheduler
-> find users/products matching MarketingRule
-> load active MarketingActions for the rule
-> action-level dedup
-> publish one command per active action

COUPON action -> CouponStrategy -> UserCoupon save
WEBHOOK action -> WebhookStrategy -> external CRM endpoint
```

`PUSH` and `EMAIL` are future types, not part of this implementation.

### Dedup Boundary

Use an action-level trigger boundary:

```text
marketing:action-trigger:{actionId}:{userId}:{productId}
```

Final idempotency remains in `UserCoupon(user_id, coupon_id)` and the webhook receiver's
idempotency key. A durable execution table is a later recovery/operation upgrade.

### Portfolio Message

```text
The first version modeled a marketing rule as one condition with one reward type. While reviewing CRM use cases, I found that a single user behavior often needs multiple downstream actions such as coupon issue, CRM webhook, push, and email. I split MarketingRule and MarketingAction so condition evaluation stays stable while operators can attach multiple actions and manage action-level retry/idempotency independently.
```

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

## Main Upgrade 3-A Implementation Specification

#### Scope

This change expands one matching behavior condition into multiple commands. It does **not** add
push/email providers, an operator UI, delivery history, or a webhook outbox in the same change.

Supported actions in this pass:

```text
COUPON
WEBHOOK
```

`PUSH` and `EMAIL` remain future action types. They must not be represented as working integrations
until an actual provider contract exists.

#### Domain Model

Split condition evaluation from executable work.

```text
MarketingRule
- id
- behavior condition fields
- dedupTtlDays
- isActive
- actions: 1:N MarketingAction

MarketingAction
- id
- marketingRuleId
- actionType: COUPON | WEBHOOK
- referenceId: couponId or webhookTemplateId
- isActive
- createdAt / updatedAt
```

Database constraints:

```text
marketing_actions
- FK marketing_rule_id -> marketing_rules.id
- UNIQUE(marketing_rule_id, action_type, reference_id)
```

Do not add `executionOrder` or `failurePolicy` in this pass. Kafka commands are asynchronous, so an
ordering field would suggest a guarantee this implementation does not provide. Coupon duplicate
protection and webhook retry/DLT remain the responsibility of their existing strategies.

#### Schema Cutover

This personal project does not require existing rule or Redis-key compatibility.

1. Remove `MarketingRule.rewardType/rewardReferenceId` from the application model.
2. Add `marketing_actions` through the current `ddl-auto=update` configuration.
3. Run a one-time SQL script to drop the old `reward_type` and `reward_reference_id` columns from the
   existing VM DB; `ddl-auto=update` does not remove them.
4. Scheduler reads active action rows only and skips a rule without one.

No backfill, legacy fallback, or old Redis-key handling is needed.

#### Command Contract

`CampaignActivityKafkaProducerDto` remains the transport envelope for this pass, but gains optional
marketing fields instead of overloading `campaignActivityId` and `couponId`:

```text
marketingRuleId
marketingActionId
actionReferenceId
```

Command mapping:

```text
COUPON action
-> campaignActivityType=COUPON
-> marketingRuleId, marketingActionId, actionReferenceId=couponId

WEBHOOK action
-> campaignActivityType=WEBHOOK
-> marketingRuleId, marketingActionId, actionReferenceId=webhookTemplateId
```

`CouponStrategy` reads `actionReferenceId` as the coupon ID. `WebhookStrategy` uses both rule ID and
action ID in its idempotency key, so two distinct webhook actions that share a template do not collapse
into one request.

#### Dedup and Failure Boundary

Dedup is action-level, not one shared rule-level key:

```text
marketing:action-trigger:{actionId}:{userId}:{productId}
```

This lets a matching behavior issue a coupon and a webhook independently. A successful coupon must not
prevent a failed or not-yet-published webhook action from being attempted on the next scheduler run.

The scheduler obtains the action key with `SET NX EX` before publishing. If the Kafka broker send future
fails, it removes that action key so the next schedule can retry publication. Final idempotency remains:

```text
COUPON  -> UserCoupon(user_id, coupon_id) unique constraint
WEBHOOK -> action-aware Idempotency-Key at the receiver boundary
```

This is at-least-once command publication, not an outbox guarantee. A durable delivery state and replay
workflow belong to the separate execution-history/outbox upgrade.

#### Scheduler Flow

```text
BehaviorTriggerScheduler
-> load active rules with active actions in one query
-> query matching user/product pairs once per rule
-> for each active action
   -> acquire action dedup key
   -> publish one typed Kafka command
   -> release dedup key if broker send fails

COUPON command  -> CouponStrategy -> UserCoupon
WEBHOOK command -> WebhookStrategy -> HTTP retry x3 -> WEBHOOK_FAILED_DLT
```

The rule/action repository query must fetch actions with the rule to avoid an N+1 query per active rule.

#### Verification

Unit/integration acceptance tests:

1. One rule with one coupon action and one webhook action publishes exactly two commands with distinct
   action IDs and reference IDs.
2. A second scheduler run inside the action TTL publishes neither action.
3. An inactive action is skipped without suppressing another active action on the same rule.
4. A rule with no active action rows is skipped.
5. Kafka send failure removes only that action's Redis dedup key.
6. Coupon duplicate command still leaves one `UserCoupon`; webhook idempotency key includes action ID.

External HTTP verification is a separate follow-up gate: run `WebhookStrategy` against WireMock or
MockWebServer for 2xx, timeout, retry, and DLT. Do not claim external delivery resilience before that
test exists.

#### Completion Criteria

- One behavior condition can issue coupon and webhook commands together.
- Re-running the scheduler does not duplicate either action during its TTL.
- Old single-reward columns are removed from the VM schema before creating new rules.
- A failed Kafka publish does not leave a permanently blocking Redis dedup key.
- No claim is made that webhook delivery is isolated from the shared command flush path; that is Main
  Upgrade 3-B and needs its own delayed-webhook baseline.

## Main Upgrade 4: APM-Based Bottleneck Report

### Problem

k6 summaries alone show latency and error rate, but they do not show where the backend spends time.

### Backend Idea

Use OpenTelemetry/Jaeger and Actuator while running the same k6 scenario. Identify whether the bottleneck is:

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

0. Add minimum pipeline observability: consumer lag, internal queue depth, flush duration, DLQ count, and reconciliation mismatch count.
1. Run stronger `FLOW=payment` tests and diagnose the Core consumption/persistence boundary before changing queue or batch behavior.
2. Apply and re-measure the smallest evidence-backed Core improvement, then record DB convergence and consistency results.
3. Build pre-event scale-out and hot-path warm-up only after the Core capacity boundary is measured: proposal -> operator approval -> rollout/readiness -> warm-up gate -> READY.
4. Operationalize DLQ and reconciliation only when DLT recurrence or a mismatch is reproduced: durable failure/issue history, operator alert, and an approval-based recovery path. Promote this step ahead of pre-event scale-out if a real failure requires it.
5. Add AI/Harness recovery assistance only after step 4 has accumulated real failure history and deterministic recovery criteria. AI summarizes risk; a person approves a bounded runbook.
6. Add MarketingRule execution history or multi-action automation only when the target application specifically needs CRM-operation depth.

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
