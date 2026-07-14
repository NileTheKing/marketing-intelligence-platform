# Domain Refactoring Map

Status: active

## Purpose

This document is the current working map for DDD-style boundary cleanup and Martin Fowler style small refactoring.

It is not a rewrite plan. The goal is to make the existing Axon backend easier to read, safer to change, and easier for humans or AI agents to navigate without changing business behavior.

## 1. Code Structure Map

### Module Boundary

| Module | Current role | Main evidence |
|---|---|---|
| `entry-service` | Handles public entry/payment APIs, Redis reservation, token issuance, and Kafka command publishing. | `EntryController`, `PaymentController`, `EntryReservationService`, `PaymentService`, `CampaignActivityProducerService` |
| `core-service` | Handles campaign/activity persistence, Kafka command consumption, purchase/entry persistence, dashboard aggregation, marketing rules, validation, and LLM query. | `CampaignActivityConsumerService`, `CampaignActivityEntryService`, `PurchaseHandler`, `DashboardService`, `BehaviorTriggerScheduler`, `GeminiLLMQueryService` |
| `common-messaging` | Shares Kafka topic names and message DTOs between services. | `KafkaTopics`, `CampaignActivityKafkaProducerDto`, `ReservationTokenPayload`, `UserBehaviorEventMessage` |

### Current Package Shape

The project currently mixes two package styles.

| Style | Example | Reading impact |
|---|---|---|
| Layer package | `controller`, `service`, `repository`, `domain/dto` | Easy to find Spring stereotypes, but business boundary is not visible from the package tree. |
| Partial domain package | `domain/campaignactivity`, `domain/coupon`, `service/strategy`, `service/purchase` | Some business concepts are grouped, but services still cross several concepts. |

This is a layered Spring application with partial feature packaging, not a full DDD or hexagonal package layout. The directory tree is not the architecture itself: the meaningful architecture is service/module ownership and dependency direction. Packages are useful when they make those boundaries easier to read.

### Core Package Direction

The final cleanup may cover the whole Core service, but do not make it one unreviewable package-move commit. Split the mechanical move by feature boundary so compile failures and incorrect ownership decisions remain local.

Target orientation:

```text
core-service
├── campaign/
│   ├── api/               # CampaignController, request/response DTO
│   ├── application/       # CampaignService
│   ├── domain/            # Campaign entity and domain policy
│   └── persistence/       # CampaignRepository
├── entry/                 # durable CampaignActivityEntry persistence/retry/query
├── commandprocessing/     # Kafka listener, buffer, dispatcher, strategies
├── marketing/             # rule evaluation + action definition/emission
├── coupon/                # coupon definition and user coupon state
├── purchase/              # purchase and user-summary projection
├── behavior/              # behavior collection/query/funnel mapping
├── dashboard/             # dashboard API/query/assembly
├── store/                 # Thymeleaf store views
├── catalog/               # Product API/domain/persistence
├── eventdefinition/       # operator-defined event configuration
└── config, observability, support
```

This is a vertical feature-first navigation goal, not a partial move of only `service` and `repository`. A feature owns its API/controller, application service, domain model, and persistence adapter together, so a reader can follow one capability without jumping through global layer folders. DDD does not require this fixed template; it is a navigation choice that makes the chosen domain boundaries visible. Existing stable code stays where it is unless a concrete change already touches that boundary.

### Core Feature Package Migration Map

This map is the agreed target for a behavior-preserving package refactor. It is based on current collaboration, not a rule that every package must have `api/application/domain/persistence` subfolders. Keep a feature flat until it has enough files to justify another level.

| Target feature | Move together | Keep outside the feature | Reason |
|---|---|---|---|
| `campaign` | `CampaignController`, `CampaignActivityController`, `CampaignService`, `CampaignActivityService`, `Campaign`, `CampaignActivity`, campaign/activity request-response-filter DTOs, `CampaignRepository`, `CampaignActivityRepository` | `CampaignActivityEntry*` | Campaign and activity definition/administration change together; a durable participation record has a different lifecycle. |
| `entry` | `CampaignActivityEntryQueryController`, `CampaignActivityEntry*` services, entity/status/page DTOs, repository, `ReconciliationScheduler`, eligibility validation API/services | Redis reservation and payment token logic in entry-service | Core owns durable participation persistence, retry, query, and reconciliation. |
| `commandprocessing` | `CampaignActivityConsumerService`, command buffer, dispatcher, `CampaignStrategy`, `BatchStrategy`, FCFS/coupon/webhook strategies, command DLT handling, command-pipeline metrics | `MarketingRule` evaluation | This owns Kafka command intake, buffering, dispatch, and failure isolation. It executes a command; it does not decide whether a marketing action should exist. |
| `marketing` | `MarketingRule`, `MarketingAction`, their repositories, `BehaviorTriggerScheduler`, rule/action evaluation helpers | command consumer and strategy dispatch | This owns condition evaluation and action selection. It emits commands to the processing boundary. |
| `coupon` | `CouponController`, `CouponService`, `Coupon`, `UserCoupon`, coupon DTOs, coupon repositories | `CouponStrategy` | Coupon definition and user coupon state are a business capability. The strategy is a Kafka command handler, so it remains in command processing. |
| `purchase` | `Purchase`, `PurchaseService`, `PurchaseHandler`, dead-letter handler, purchase repository/DTOs, `UserSummary`, user-summary service/repository, purchase scheduler, cohort/LTV/RFM services and schedulers | OAuth user authentication | These are durable commerce facts and projections derived from purchase flow. |
| `behavior` | `BehaviorEventService`, core behavior publisher/adapter, funnel definition/step, behavior-event collection/query controller if it remains in Core | dashboard assembly | This owns behavior-event interpretation; dashboard consumes its query result. |
| `dashboard` | dashboard REST/SSR/query controllers, `DashboardService`, metric/realtime services, calculator, dashboard DTOs/domain values, LLM query services | raw behavior storage/query | This owns dashboard response assembly and controlled insight querying. |
| `store` | `StoreController`, `StoreViewService`, store-only view routes | admin/dashboard views | This owns Thymeleaf shopping experience composition. |
| `catalog` | `ProductController`, `ProductService`, `Product`, `ProductRepository` | store view composition | Product is a catalog capability reused by campaign and store. |
| `eventdefinition` | `EventController`, `EventService`, `Event`, `EventDefinitionAudit`, related repository/DTO/converter | behavior events | Existing `Event` means operator-defined event configuration, not a collected `BehaviorEvent`; keep the distinction visible. |

Cross-cutting code stays at the root: `config`, `config/auth`, `aop`, `observability`, `support`, generic exception handling, application bootstrap, and test fixtures. `MonitoringController` stays with `observability`; `FileController` and fake-data/test controllers remain support/test-only adapters until a concrete feature owns them.

### Explicit Ownership Decisions

1. `CampaignActivity` belongs to `campaign`, while `CampaignActivityEntry` belongs to `entry`. The former defines an event; the latter records a user's durable participation.
2. `MarketingAction` belongs to `marketing`, while `CouponStrategy` and `WebhookStrategy` belong to `commandprocessing`. Marketing decides and emits; command processing executes and isolates failure.
3. `DashboardService` remains separate from `behavior`. Dashboard assembles read models across behavior, campaigns, and purchases; it should not own raw behavior collection semantics.
4. Scheduled jobs move with their business owner where clear: behavior-trigger → `marketing`, reconciliation → `entry`, purchase projection/cohort/RFM → `purchase`. The scheduler annotation is an adapter detail, not a reason to collect all jobs in one global package.

### Migration Rules

- This is a package/import refactor only. Do not alter HTTP contracts, Kafka topics, Spring bean names, database mappings, scheduling policy, or business logic in the same change.
- Move main code and its focused tests together. Test packages mirror the new production feature package where possible.
- Use one commit per feature boundary. Start with `commandprocessing`, then `marketing`, then `campaign` + `entry`; leave dashboard/purchase/store for separate commits because their file count and dependency surface are larger.
- Keep package names lowercase and concise. Prefer `entry`, not `campaignactivityentry`; the class name already carries the detailed noun.
- A source file used by two features stays with the feature that owns its state or decision. The other feature depends on its public application/domain contract; do not duplicate entities or repositories to avoid an import.

### Migration Progress

- 2026-07-14: `commandprocessing` moved without behavior changes. This includes the Kafka consumer, command buffer, dispatcher, batch/typed strategies, command DLT routing, pipeline-metrics buffer import, and their focused tests. `CohortLtvBatchService` intentionally remains under Purchase ownership despite its historical `batch` package name.

### High-Traffic Flow

1. `EntryController.createEntry()` maps HTTP input to `EntryApplicationService`; the application service validates request/user/meta and calls Redis reservation.
2. `EntryReservationService.reserve()` executes Lua script and publishes `ReservationApprovedEvent`.
3. Payment flow uses `PaymentController.preparePayment()` and `PaymentController.confirmPayment()`.
4. `PaymentService.sendToKafkaWithRetry()` sends `CampaignActivityKafkaProducerDto` to Kafka and waits for broker ACK.
5. `CampaignActivityConsumerService.consume()` buffers Kafka command messages.
6. `CampaignActivityConsumerService.scheduledFlush()` drains up to 20 messages and dispatches by `CampaignActivityType`.
7. `FirstComeFirstServeStrategy.processBatch()` bulk-loads `CampaignActivity` and delegates entry upsert.
8. `CampaignActivityEntryService.upsertBatch()` saves entries, isolates batch failure through individual retry, and publishes purchase events.
9. Purchase persistence is handled downstream by purchase handlers/listeners.

### Analytics / Dashboard Flow

1. Behavior events are stored and queried through `BehaviorEventService`.
2. Activity dashboard uses `DashboardService.getDashboardByActivity()`.
3. Activity funnel uses `FunnelStep` and `CampaignFunnelDefinition`.
4. Campaign/global dashboards are still mostly assembled inside `DashboardService`.

### Marketing Automation Flow

1. `BehaviorTriggerScheduler` loads active `MarketingRule`.
2. It queries behavior data through `BehaviorEventService`.
3. It creates `CampaignActivityKafkaProducerDto` reward commands.
4. Existing Kafka command processing handles `COUPON` / `WEBHOOK` through strategy dispatch.
5. `MarketingRule` is the condition and has active `MarketingAction` children. `BehaviorTriggerScheduler` evaluates each active action independently, so one rule can issue a coupon, invoke a webhook, or do both. Action-level Redis dedup and Kafka send-failure cleanup are handled per action.

## 2. Ubiquitous Language Draft

### Terms To Keep

| Term | Meaning in this project | Preferred use |
|---|---|---|
| `Campaign` | A marketing campaign container. | Top-level marketing plan / dashboard grouping. |
| `CampaignActivity` | A concrete executable activity inside a campaign. | User-facing event unit such as FCFS, coupon issue, giveaway, webhook trigger. |
| `Entry` | A user's participation record for a campaign activity. | Core persistence result after an activity command is accepted. |
| `Reservation` | Fast Redis-side slot claim before durable persistence. | Entry-service only; should not be confused with persisted `Entry`. |
| `Payment` | User confirmation step before purchase-related activity is finalized. | Entry payment API and token flow. |
| `Purchase` | Durable commerce event / purchase history used by analytics. | Core persistence and LTV/cohort domain. |
| `BehaviorEvent` | User behavior log used for funnel, rule triggering, and dashboard. | Event collection and analytics query. |
| `FunnelStep` | Dashboard-facing common step. | `VISIT`, `ENGAGE`, `QUALIFY`, `PURCHASE`. |
| `TriggerType` | Raw behavior event type. | `PAGE_VIEW`, `CLICK`, `PURCHASE`, etc. |
| `MarketingRule` | Operator-defined condition for behavior-triggered reward. | Scheduler/evaluation domain. |
| `MarketingAction` | Executable action attached to a rule. | Current action types are coupon issue and webhook; future push/email use the same rule-to-action boundary. |

### Terms That Currently Collide

| Collision | Current symptom | Suggested direction |
|---|---|---|
| `Activity` vs `CampaignActivity` | Dashboard DTOs and comments use `activity`, while persistence and Kafka use `CampaignActivity`. | Treat `CampaignActivity` as domain name; use `Activity` only in UI/DTO labels where brevity is needed. |
| `Entry` vs `Reservation` | Entry API creates a Redis reservation first, but the persisted entity is `CampaignActivityEntry`. | Keep `Reservation` for Redis pre-confirmation and `Entry` for durable record. |
| `Payment` vs `Purchase` | Payment API sends Kafka command; core later creates purchase event/history. | Payment is user confirmation/command; Purchase is durable commerce fact. |
| `Event` vs `BehaviorEvent` vs `CampaignActivity command` | Raw user behavior, backend command, and old event definition all use event-like names. | Use `BehaviorEvent` for user logs and `CampaignActivityCommand` for Kafka command messages. |
| `CampaignActivityType` | Used both as activity kind and strategy dispatch key. | Acceptable now, but strategy/command names should clarify when it is used for processing. |

## 3. Bounded Context Candidates

These are candidates, not mandatory package moves.

| Context | Owns | Should not own | Current files touching it |
|---|---|---|---|
| Entry / Reservation | Redis slot claim, duplicate/sold-out/closed result, reservation token issuing. | Durable purchase persistence, dashboard aggregation. | `EntryController`, `EntryReservationService`, `ReservationTokenService`, `FastValidationService` |
| Payment Command | Payment prepare/confirm, approval token, Kafka command send. | Purchase history calculation, campaign dashboard. | `PaymentController`, `PaymentService`, `CampaignActivityProducerService` |
| Campaign Operations | Campaign and campaign activity creation/update/status/filter/budget. | Kafka buffer management, Redis reservation internals. | `CampaignService`, `CampaignActivityService`, `CampaignActivity`, `Campaign` |
| Activity Command Processing | Kafka command buffering, batch dispatch, strategy execution, DLT on command failure. | HTTP controller validation, dashboard response formatting. | `CampaignActivityConsumerService`, `CampaignStrategy`, `BatchStrategy`, `FirstComeFirstServeStrategy`, `CouponStrategy`, `WebhookStrategy` |
| Entry Persistence | Durable `CampaignActivityEntry` upsert, batch retry, purchase event publication after approved entry. | Kafka listener mechanics, Redis reservation. | `CampaignActivityEntryService`, `CampaignActivityEntryRetryService`, `CampaignActivityEntryRepository` |
| Purchase / Commerce | Durable purchase records, LTV/cohort, user summary purchase metrics. | Payment API token validation. | `PurchaseService`, `PurchaseHandler`, `CohortAnalysisService`, `CohortLtvBatchService`, `UserSummaryService` |
| Behavior Analytics | Behavior event collection/query, funnel mapping, dashboard stats. | Campaign mutation and payment command execution. | `BehaviorEventService`, `DashboardService`, `CampaignFunnelDefinition`, `FunnelStep`, `TriggerType` |
| Marketing Automation | Rule evaluation, action-level dedup, reward command emission. | Coupon persistence internals and webhook HTTP delivery internals. | `BehaviorTriggerScheduler`, `MarketingRule`, `MarketingAction`, `CouponStrategy`, `WebhookStrategy` |
| LLM Query | Controlled dashboard query/tooling, answer formatting, metadata boundary. | Direct DB mutation or uncontrolled operations. | `GeminiLLMQueryService`, `MockLLMQueryService`, `LLMQueryService` |

## 4. Martin Fowler Style Code Smells

### 4.1 Large Class / Mixed Responsibilities

| File | Evidence | Risk | Small refactoring direction |
|---|---|---|---|
| `core-service/src/main/java/com/axon/core_service/service/DashboardService.java` | One service assembles activity, campaign, global dashboard, heatmap, GMV, ROAS, ranking, and ES error fallback. | Hard to test dashboard calculations independently; future metric changes can touch a large class. | Extract calculation helpers such as `DashboardMetricCalculator`; later extract query assemblers by dashboard level. |
| `entry-service/src/main/java/com/axon/entry_service/service/entry/EntryApplicationService.java` | FCFS and coupon-entry use cases are intentionally orchestrated here after the controller extraction. | It is a central application-flow class, so unrelated activity flows must not accumulate here. | Keep controllers HTTP-only; split only if coupon and FCFS validation rules diverge materially. |
| `core-service/src/main/java/com/axon/core_service/service/CampaignActivityConsumerService.java` | Now owns Kafka listener, scheduled flush, and shutdown flush only. Buffering and dispatch/DLT moved to named collaborators. | The flush policy remains a separate future backpressure concern, but responsibility is no longer hidden in one class. | Keep `CampaignActivityCommandBuffer` and `CampaignActivityCommandDispatcher` separate. |

### 4.2 Long Method

| File/method | Evidence | Small refactoring direction |
|---|---|---|
| `EntryApplicationService.createEntry()` | Validation, retry token handling, reservation, and token issuance are one FCFS application use case. | Keep its flow coherent; extract only a repeated validation policy with a clear owner. |
| `DashboardService.buildOverviewDataByCampaign()` | Fetch campaign, read stats, loop activities, calculate totals/rates/table in one method. | Extract `buildActivityComparison()`, `accumulateOverview()`, and pure metric calculator. |
| `CampaignActivityEntryService.upsertBatch()` | Existing lookup, entity mutation, purchase event decision, saveAll fallback retry, event publishing in one method. | Extract key creation and purchase event creation first. Keep transaction boundary unchanged. |

### 4.3 Primitive Obsession / Data Clumps

| Repeated cluster | Evidence | Direction |
|---|---|---|
| `campaignActivityId`, `userId`, `productId`, `quantity`, `campaignActivityType` | Appears across entry request, reservation token payload, payment approval payload, Kafka command DTO, entry upsert. | Introduce a small internal value object only if it reduces duplication in one flow. Avoid broad shared abstraction first. |
| `activityId + ':' + userId` string key | Used for dedupe and map lookup in strategies/services. | Extract local key record such as `ActivityUserKey` when touching FCFS/entry persistence code. |
| raw trigger type strings | Dashboard uses string stats maps; `CampaignFunnelDefinition` maps strings. | Long term: align `TriggerType` enum and ES string boundary. Short term: keep strings at ES boundary only. |

### 4.4 Inappropriate Intimacy / Boundary Leakage

| File | Evidence | Risk | Direction |
|---|---|---|---|
| `CampaignActivityEntryService` | Entry persistence publishes `PurchaseInfoDto` when approved and purchase-related. | Entry persistence knows purchase event shape. This is acceptable as current event boundary, but it is a coupling point. | If this grows, introduce domain event record dedicated to approved entry, then purchase listener maps it. |
| `DashboardService` | Reads `CampaignActivity.getProduct().getCategory()` while building campaign comparison. | Dashboard aggregation depends on entity graph and lazy associations. | Keep repository fetch graph explicit. Later project query DTO for dashboard. |
| `EntryApplicationService` | Reads `CampaignActivityMeta` and applies request/meta tamper validation. | This is now correctly in the application layer; it can still grow if every activity type adds special cases. | Keep result mapping in `EntryUseCaseResult`; extract type-specific validation only when duplication appears. |

### 4.5 Switch / Type Code Branching

| File | Evidence | Status | Direction |
|---|---|---|---|
| `CampaignActivityConsumerService` | Groups by `CampaignActivityType` and dispatches to `CampaignStrategy`. | Mostly good: strategy map already exists. | Rename around command processing to clarify the type is a strategy dispatch key. |
| `CampaignFunnelDefinition` | Type-specific funnel mapping exists. | Good recent improvement. | Keep unsupported types empty; do not fallback to FCFS. |
| `EntryApplicationService` | Coupon and FCFS entry flows are separate use-case methods and share some validation dependencies. | Duplication risk if their rules continue to diverge. | Extract a narrow shared validator only after repeated policy is visible; do not create a generic activity framework. |

### 4.6 Comments Explaining Instead Of Structure

| File | Evidence | Direction |
|---|---|---|
| `CampaignActivityConsumerService` | Listener/flush coordination remains, while buffering and dispatch are now named classes. | The class is readable enough for the current flow. | Preserve the split; revisit only with a concrete queue/backpressure change. |
| `DashboardService` | Section banners split dashboard levels but class remains broad. | Section banners are a signal that extract-class may help. |

### 4.7 Naming Smells

| Current name | Issue | Candidate |
|---|---|---|
| `CampaignActivityConsumerService` | Sounds like a generic service, but it is Kafka command listener + buffer + dispatcher. | `CampaignActivityCommandConsumer` after extracting buffer/dispatcher. |
| `CampaignActivityKafkaProducerDto` | Shared command message but named from producer perspective. | `CampaignActivityCommandMessage` long term. |
| `PaymentService.sendToKafkaWithRetry()` | Method name exposes transport; payment use case is command confirmation. | `sendPaymentCommandWithRetry()` or `publishConfirmedPayment()` later. |
| `CampaignActivityStatus` duplicated in core DTO package and entry domain package | Same name in two modules can confuse AI/humans. | Keep if values differ; otherwise document or consolidate through shared contract later. |
| Package `dto/Payment` and `service/Payment` | Java package is uppercase. | Rename to lowercase `payment` only if done as separate mechanical refactor. |

## 5. Small Refactoring Units

Do these one at a time with tests. Do not combine them.

### Unit 1: Entry Controller Use-Case Extraction

Status: implemented

Goal: move orchestration out of `EntryController` without changing API response.

Scope:
- Add `EntryApplicationService` for `createEntry`.
- Add `CouponIssueApplicationService` or a method in the same application service for `/coupon`.
- Controller keeps HTTP status mapping.

Verification:
- Existing entry/payment tests pass.
- Add or update controller/use-case test only if existing coverage is missing.

Risk: low to medium. Behavior is sensitive but extraction can be mechanical.

### Unit 2: CampaignActivity Command Buffer / Dispatcher Split

Status: implemented

Goal: make Kafka listener, buffer, batch dispatch, and DLT responsibility visible.

Scope:
- Extract buffer drain logic into `CampaignActivityCommandBuffer`.
- Extract strategy dispatch and DLT send into `CampaignActivityCommandDispatcher`.
- Keep batch size 20 and flush interval unchanged.

Verification:
- `CampaignActivityConsumerServiceTest` or equivalent still passes.
- Add unit test for unsupported type and batch failure DLT behavior if missing.

Risk: medium. Threading and shutdown flush must be preserved.

### Unit 3: Dashboard Pure Metric Calculator

Status: implemented

Goal: make dashboard arithmetic testable without ES/JPA.

Scope:
- Extract pure calculations: conversion rate, engagement rate, qualification rate, purchase rate, AOV, ROAS, GMV.
- Keep data fetching inside `DashboardService`.

Verification:
- Add unit tests for zero division and normal values.
- Existing dashboard tests pass.

Risk: low. This is the safest first refactor.

### Unit 4: Activity/User Key Value Object

Status: implemented

Goal: remove repeated string-concatenated dedupe keys.

Scope:
- Add package-private record near command processing or entry persistence.
- Replace `"activityId:userId"` key creation in one flow only.

Verification:
- FCFS batch test passes.

Risk: low.

### Unit 5: Package Case Cleanup

Status: implemented

Goal: normalize uppercase Java package names.

Scope:
- Rename `entry_service/dto/Payment` to `entry_service/dto/payment`.
- Rename `entry_service/service/Payment` to `entry_service/service/payment`.

Verification:
- Compile and entry-service tests pass.

Risk: low mechanically, but broad import churn. Do separately.

## What Not To Do Yet

| Do not do | Reason |
|---|---|
| Full DDD package rewrite | Too much churn; can break portfolio evidence and delay performance work. |
| Rename `CampaignActivity` entity now | Central concept across DB/API/docs; high blast radius. |
| Introduce generic activity framework | Current types are few; likely over-engineering. |
| Move all DTOs into domain packages | DTO boundary is mixed today, but wholesale move gives little runtime value. |
| Add Outbox just for architectural purity | Current decision is timeout/retry and future outbox where it has a concrete failure story. |

## Suggested Order

Status: completed for items 1-5 on 2026-06-06.

1. Dashboard pure metric calculator.
2. Activity/user key value object.
3. Entry controller use-case extraction.
4. CampaignActivity command buffer/dispatcher split.
5. Package case cleanup.

The first two are low-risk warmups. The third and fourth improve the actual boundary readability. The fifth is cosmetic but helps Java convention and AI navigation.

## Next Candidates

These are not part of the completed 1-5 refactoring set. Re-check code before implementing because later performance or deployment work may change priorities.

### Candidate A: ReservationTokenService Cleanup

Current issue:
- `ReservationTokenService` still mixes token generation, Redis storage, signature verification, approval-token refresh, and cleanup.
- Method name `CreateApprovalToken` violates Java method naming convention.

Small next step:
- Rename `CreateApprovalToken` to `createApprovalToken`.
- Keep old behavior and update callers/tests only.
- Do not redesign token flow in the same change.

Why later:
- Low risk, but not as important as bottleneck testing.

### Candidate B: CampaignActivity Entity Behavior Naming

Current issue:
- `CampaignActivity` has field-operation style methods such as `updateStatus`, `updateSyncedCount`, `updateProductInfo`, `updateCouponInfo`.
- Some call sites could read better if the method expressed the domain event/reason.

Small next step:
- Start with one narrow case, for example stock sync:
  - replace separate status/count changes with `markEndedAfterStockSync(int soldCount)` only where the code currently performs that exact transition.
- Do not rename the `CampaignActivity` entity itself.
- Do not convert every setter-like method at once.

Why later:
- Useful for ubiquitous language, but central entity changes have higher blast radius.

### Candidate C: DashboardService Further Split

Current issue:
- `DashboardMetricCalculator` now owns pure calculations, but `DashboardService` still assembles activity, campaign, global dashboard, rankings, heatmap, and fallback handling.

Small next step:
- Extract one assembler at a time:
  - `ActivityDashboardAssembler` or `CampaignDashboardAssembler`.
- Keep repository/ES calls in service until there is a clear reason to split query ports.

Why later:
- Current service is improved enough for now. Further split should follow a real dashboard change or test pain.

### Candidate D: CampaignActivityEntryService Batch Upsert Split

Current issue:
- `upsertBatch()` still does existing-entry lookup, entity mutation, purchase event decision, batch save, individual retry fallback, and event publishing.

Small next step:
- Extract pure/key logic first:
  - `ActivityUserKey` is already introduced.
  - next split can be purchase-event creation or retry fallback handling.
- Preserve transaction boundary and batch failure policy.

Why later:
- This flow is correctness-sensitive, so split only with targeted tests around duplicate handling and purchase event publication.

### Candidate E: Payment Retry Policy Naming/Configuration

Current issue:
- `PaymentService` has retry count passed from controller and timeout/backoff values as internal constants.
- This is acceptable now, but the policy is not named as an operational decision.

Small next step:
- Rename method to a domain/command-oriented name such as `publishConfirmedPaymentWithRetry`.
- Consider config properties only if the value will actually vary by environment.

Why later:
- Current timeout/retry behavior is already explicit enough. Avoid speculative configurability.

## Recommended Next Session Entry Point

If the next session is about refactoring, start here:

1. Decide whether to do a tiny convention cleanup (`CreateApprovalToken` -> `createApprovalToken`) or move to performance baseline work.
2. If refactoring continues, prefer Candidate A or D.
3. If portfolio competitiveness is the priority, pause refactoring and run the Oracle Compose baseline from `docs/plan/oracle-compose-baseline-runbook.md`.
