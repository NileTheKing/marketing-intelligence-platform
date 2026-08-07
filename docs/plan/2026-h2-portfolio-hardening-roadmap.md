# 2026 H2 Portfolio Hardening Roadmap

Status: active

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
   - Status: Path B implemented and validated on 2026-07-30 (`d5f396b`).
   - The internal command/Purchase queues were removed. Kafka supplies the
     backlog, and Entry/Purchase durable processing completes before the batch
     listener returns.
   - Controlled 800-result A/B retained `800/800` DB convergence with no pool
     pending/timeout. Crash injection verified redelivery of 20
     durable-but-uncommitted records and final `800/800` distinct rows.
   - Keep Path A (bounded queue + pause/resume + durable offset handoff) only as
     a future fallback if a larger measured workload fails the current gate.

6. Purchase/UserSummary hot-path cleanup
   - Treat `Purchase` as the source-of-truth append path.
   - Move `UserSummary` toward projection semantics with separate retry/rebuild.
   - Evaluate JDBC batch or multi-row insert only if SQL logs and spike tests show JPA `saveAll` insert count as a measured bottleneck.
   - Reason: resume/portfolio claims should be backed by real persistence behavior, not by assuming JPA `saveAll` is DB bulk insert.

7. Global scheduler safety
   - `BehaviorTriggerScheduler` has single-runner protection via a Redisson wrapper: one owner runs and other pods skip normally.
   - Apply the same policy to stock-sync style schedulers after their lock scope and failure semantics are defined.
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

Status: deferred as a standalone generic feature (decision updated 2026-08-06)

Decision boundary:

- Do not build an LLM/DLT console only because a DLT topic exists.
- The current FCFS ledger path is idempotent and has no ambiguous external side effect. After the
  underlying cause is fixed, replay is comparatively safe and does not justify mandatory human
  approval for every record.
- First create a real operational need through external delivery history, especially webhook
  outcomes where a timeout cannot prove whether the receiver applied the action.
- Add approval-based replay only after a reproducible ambiguous outcome exists. AI may summarize
  evidence later; it is not the retry decision maker.

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
- For Axon, this generic model is a later extraction from a concrete webhook delivery/recovery
  workflow, not the next implementation target.

## Main Upgrade 3: MarketingRule Execution History and Holdout Measurement

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

### Campaign Effect Measurement Extension

Execution history alone answers "did the automation run?" It does not answer whether the
action was associated with a later purchase. For CRM-oriented use cases, extend the action
execution record with a small, deterministic experiment boundary:

```text
Behavior condition / AudienceSegment
-> eligible users
-> deterministic assignment by hash(userId + experimentId)
   -> treatment: dispatch MarketingAction
   -> control: record eligibility only, do not dispatch
-> Purchase source of truth
-> group-level conversion / GMV comparison
```

Minimal action-level history:

```text
marketing_action_execution
- id
- rule_id
- action_id
- experiment_id (nullable when no experiment is configured)
- user_id
- product_id
- group: TREATMENT | CONTROL
- status: ELIGIBLE | DISPATCHED | SUCCEEDED | FAILED
- dispatched_at
- completed_at
- failure_reason
```

Boundary:

- This is a measurement-ready structure, not a claim that a coupon caused a conversion.
- Purchase remains the MySQL source of truth; Elasticsearch remains the behavior targeting/read model.
- Redis dedup continues to prevent duplicate action dispatch. The execution row is the durable audit and result-join boundary.
- The first UI is a small admin result card/table. The LLM may later call the same read-only aggregation service; it must not calculate a separate result.
- Statistical significance, generic experiment management, and multi-tenant isolation are out of scope for the first version.

Portfolio message:

```text
I connected behavior-based targeting, coupon/webhook execution, and purchase-led outcome aggregation.
I preserved a deterministic control group so campaign effectiveness can be compared without treating
delivery attempts as business outcomes.
```

### Portfolio Message

```text
I made CRM automation observable by storing target count, duplicate-skip count, publish count, and failure stage per rule execution.
```

Multi-pod note:

- Kafka consumer flush workers are local queue processors. Kafka consumer groups distribute partition ownership, so the same message is not normally processed by multiple pods in the same group.
- Global scheduled jobs are different. `BehaviorTriggerScheduler`, stock sync, cohort batch, and segmentation batch read shared DB/ES state directly, so multi-pod deployment can run the same job more than once unless a scheduler lock or single-runner constraint is introduced.
- `BehaviorTriggerScheduler`, stock sync, reconciliation, cohort LTV, and RFM segmentation are protected by `SchedulerExecutionLock` (Redisson).
- `UserPurchaseScheduler` remains a `dev`/`test`-profile batch launcher and is not part of the production single-runner claim.

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

COUPON action -> Campaign command consumer -> CouponStrategy -> UserCoupon save
WEBHOOK action -> Webhook command consumer -> WebhookStrategy -> external CRM endpoint
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

Status: `active (implemented 2026-08-06, public external endpoint smoke completed 2026-08-07)`

### Problem

Webhook delivery is external HTTP I/O. If it runs inside the campaign command
batch listener, a slow external endpoint can delay offset progress for
unrelated campaign commands in the same poll.

Previous code boundary:

```text
Kafka listener
-> poll batch (max 20)
-> strategy dispatch
-> FCFS / COUPON / WEBHOOK strategy
```

The strategy execution path is shared within a poll batch. A slow `WEBHOOK`
strategy can keep the listener open and delay the next poll/offset progress for
other command types.

Implemented boundary:

```text
BehaviorTriggerScheduler
-> COUPON: axon.campaign-activity.command -> axon-group
-> WEBHOOK: axon.webhook.command -> axon-webhook-group
   -> HTTP delivery
   -> retryable: timeout/network, 5xx, 429
   -> non-retryable: other 4xx
   -> final failure: axon.webhook.failed.dlt
```

- The existing command dispatcher forwards legacy `WEBHOOK` commands to the isolated topic and does
  not perform external HTTP in the shared command listener.
- Automatic retry uses exponential backoff with jitter and at most three total attempts.
- The webhook DLT send awaits broker acknowledgment. If the DLT publish fails, the listener throws
  `OffsetCommitBlockedException`, preventing a successful offset boundary.
- `HttpWebhookClientIntegrationTest` opens a real local HTTP socket through WireMock and verifies
  `2xx`, `400`, request headers, and read timeout behavior.
- Strategy tests verify `5xx` recovery, `429` retry, ordinary `4xx` immediate DLT, retry exhaustion,
  and DLT-publish failure propagation.
- `HttpWebhookClientExternalSmokeTest` is disabled unless `AXON_WEBHOOK_SMOKE_URL` is supplied, so
  the repository does not store a temporary or provider URL.
- A 2026-08-07 run sent one synthetic request to a public Webhook.site endpoint and confirmed the
  JSON body and `Idempotency-Key` header. This verifies public-network delivery, not a CRM/provider
  adapter.
- KakaoTalk is not a drop-in generic webhook receiver. It still needs a provider adapter for OAuth,
  Kakao payloads, token refresh, and provider-specific delivery semantics.

### Chosen Scope

The first isolation step uses a dedicated Kafka topic and consumer group. It does not add an outbox,
delivery table, replay UI, or provider-specific adapter.

An outbox/delivery worker becomes justified only if the project needs durable delivery history,
operator replay, or a stronger process-crash recovery claim.

### Threading Boundary

- The goal is not to replace every worker with virtual threads.
- The dedicated consumer may block only its own partitions while waiting for external HTTP.
- Keep internal DB-oriented strategies on the existing command consumer.
- Treat virtual threads as a later implementation option, not as a substitute for the Kafka
  responsibility boundary.
- If delivery state, operator retry, or process-crash recovery becomes part of the claim, promote
  this design to an outbox + delivery worker.

### Evidence Boundary

Completed:

- Code inspection confirmed that the previous shared dispatcher called synchronous external HTTP
  before the Kafka listener returned.
- WireMock failure injection reproduces the actual HTTP success, client-error, and read-timeout
  boundaries without mocking `WebhookClient`.
- Dispatcher and listener tests verify that the shared command path now performs only a Kafka
  forward for legacy webhook messages and that the dedicated listener owns HTTP delivery.
- A three-run embedded-Kafka fault-injection A/B used the current `max.poll.records=20` boundary and
  a deterministic 2-second Webhook delay:
  - shared topic: FCFS completed in `1,920 / 2,013 / 2,015 ms`; the shared committed offset did not
    advance during the Webhook wait
  - isolated topics/groups: FCFS completed in `5 / 4 / 4 ms` and its group committed one record while
    the Webhook group was still blocked
  - evidence: `docs/devlog/dev-log-2026-08-06-webhook-topic-isolation-ab.md`
- A separate public-endpoint smoke test sent one synthetic Java 21 `HttpWebhookClient` request to
  Webhook.site and confirmed the JSON body and idempotency header at the receiver.

Still required before claiming an OCI or production latency improvement:

```text
Before input (previous revision):
- mixed CAMPAIGN_ACTIVITY_COMMAND messages, including WEBHOOK
- COUPON commands for internal DB writes
- WEBHOOK commands pointing to a mock endpoint with 2-3s delay
- optional FCFS/PURCHASE follow-up commands if the scenario needs richer traffic

After input:
- same command mix and rate
- WEBHOOK is published to WEBHOOK_COMMAND

Observe:
- consumer-group lag over time
- type별 처리 완료 시간
- webhook delay / retry / DLT count
- coupon issue completion time
- core-service thread/CPU snapshot
```

- Run the same mixed command input before/after under a separately controlled receiver.
- Record consumer lag and coupon/FCFS completion time by consumer group.
- Add a provider-specific smoke test only when a real CRM, push, email, or Kakao adapter is selected.

Until that experiment exists, the safe claim is structural isolation and failure-policy verification,
not a numeric latency reduction.

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

COUPON command  -> CAMPAIGN_ACTIVITY_COMMAND -> CouponStrategy -> UserCoupon
WEBHOOK command -> WEBHOOK_COMMAND -> dedicated consumer
                -> HTTP retry x3 -> WEBHOOK_FAILED_DLT
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

External HTTP verification covers WireMock `2xx`, `400`, timeout, retry classification, and DLT
failure propagation. The public Webhook.site smoke verifies real internet delivery; a
provider-specific smoke remains optional until an actual CRM/channel adapter is selected.

#### Completion Criteria

- One behavior condition can issue coupon and webhook commands together.
- Re-running the scheduler does not duplicate either action during its TTL.
- Old single-reward columns are removed from the VM schema before creating new rules.
- A failed Kafka publish does not leave a permanently blocking Redis dedup key.
- Webhook HTTP delivery uses a dedicated topic and consumer group; the local fault-injection A/B
  verifies causal isolation, while any OCI capacity or production latency claim still requires the
  mixed-load baseline described in Main Upgrade 3-B.

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

Completed foundation:

0. Minimum FCFS/Core observability, Kafka-native backlog, Entry/Purchase ledger boundary, failure
   isolation, crash recovery, and 3,000-VU regression evidence are complete.
1. MarketingRule multi-action and RFM AudienceSegment are implemented.

Current next sequence:

2. Select a provider-specific adapter only if a real CRM/channel contract becomes part of scope;
   WireMock failure injection, dedicated topic/consumer isolation, and a public endpoint smoke are
   complete.
3. If a numeric portfolio claim is needed, run a mixed-command before/after experiment and record
   group lag plus coupon/FCFS completion time.
4. Add durable webhook delivery/execution history only if operator recovery or process-crash
   recovery becomes a real requirement.
5. Add operator-approved replay only for ambiguous or terminal delivery outcomes. Deterministic
   safe retries stay automatic; AI summarization remains optional and comes last.
6. Build pre-event scale-out and hot-path warm-up after the external delivery story, unless a
   target application values infrastructure automation more than CRM-operation depth.

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
