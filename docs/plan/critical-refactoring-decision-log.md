# Critical Refactoring Decision Log

status: active

## Purpose

This document records the code-review decisions made after scanning the Axon codebase for JPA, transaction, thread, and architecture anti-patterns.

The goal is not to add features. The goal is to improve code quality, transaction correctness, query boundaries, and operational safety without confusing future agents about what was intentionally deferred.

## Current Refactoring Scope

### 1. Fix `REQUIRES_NEW` self-invocation

Status: done in current pass

Current issue:

- `CampaignActivityEntryService.retryEntriesIndividually()` calls `saveSingleEntryInNewTransaction()` inside the same class.
- Because Spring transaction AOP is proxy-based, calling a `@Transactional(propagation = REQUIRES_NEW)` method through `this` does not apply the new transaction boundary.

Target direction:

- Move the single-entry retry save method into a separate Spring bean, for example `CampaignActivityEntryRetryService`.
- Keep `CampaignActivityEntryService` responsible for batch orchestration.
- Keep the retry service responsible for independent transaction boundaries.

Implemented:

- Added `CampaignActivityEntryRetryService`.
- `CampaignActivityEntryService.retryEntriesIndividually()` now calls the retry bean instead of self-invoking the `REQUIRES_NEW` method.

Reason:

- This is not just style. It directly affects the correctness of the "batch failure isolated into single retries" claim.

## 2. Remove Entity access from Controller/View paths

Status: partially done in current pass

Current issue:

- Some controller/view paths fetch JPA entities and access lazy associations directly.
- Examples identified during review:
  - `DashboardViewController` accesses `CampaignActivity.campaign`.
  - `StoreController` accesses `CampaignActivity.product`, `CampaignActivity.coupon`, and `UserCoupon.coupon`.
  - `DashboardController` builds response data from `LTVBatch` and accesses `LTVBatch.campaignActivity`.

Target direction:

- Introduce view/query services that assemble DTOs inside transactional service boundaries.
- Controllers should add DTOs to `Model` or return response DTOs only.
- Views/Jackson should not trigger JPA lazy loading.

Implemented:

- Moved cached cohort batch response assembly from `DashboardController` into `CohortAnalysisService`.
- Added explicit `@EntityGraph` repository methods for view paths that still need simple lazy associations.

Remaining:

- `DashboardViewController` and `StoreController` still assemble some view models directly.
- A later pass should move these remaining view-model assembly paths into dedicated query/view services before considering OSIV off.

Reason:

- DTO conversion solves the layer-boundary and OSIV problem.
- It prevents accidental DB access during Thymeleaf rendering or JSON serialization.

## 3. Use `EntityGraph`, fetch join, or DTO projection only where needed

Status: partially done in current pass

Clarification:

- DTO conversion and fetch optimization solve different problems.
- DTO conversion prevents entity leakage outside the service boundary.
- `EntityGraph` or fetch join controls how many SQL round trips are needed while building DTOs.

Selection rule:

- Use `@EntityGraph` for simple entity loading where a few lazy associations are needed.
- Use fetch join when query conditions, joins, or ordering need explicit JPQL control.
- Use DTO projection when only selected columns are needed and the entity itself is not needed.

Current preference:

- Start with DTO-oriented query/view services.
- Use `@EntityGraph` as the default surgical fetch optimization for simple lazy association loading.
- Do not convert every repository method to fetch join.

Implemented:

- Added `CampaignActivityRepository.findWithCampaignById(...)`.
- Added `CampaignActivityRepository.findWithProductAndCouponById(...)`.
- Added `@EntityGraph` to active activity listing, `UserCoupon.findAllByUserId(...)`, and LTV batch lookup.

## 4. Consider turning OSIV off after DTO conversion

Status: defer until DTO paths are stable

Clarification:

- OSIV keeps the JPA `EntityManager` open until the HTTP response is rendered.
- It is convenient for SSR/admin CRUD views because lazy associations can still be loaded in the view.
- The downside is that DB queries can happen outside the service layer, hiding N+1 problems.

Decision:

- Do not turn OSIV off first.
- First move controller/view entity access to DTOs.
- After tests pass and view paths are stable, consider `spring.jpa.open-in-view=false`.

Reason:

- OSIV off is useful as a boundary-enforcing safety net, but it should follow DTO cleanup rather than precede it.

## 5. Hide internal implementation classes where practical

Status: partially done in current pass

Current issue:

- Some internal handler/strategy implementation classes are public even though they are not intended as external entry points.
- Examples discussed:
  - `PurchaseHandler`
  - `LogDeadLetterHandler`
  - concrete `CampaignStrategy` implementations

Target direction:

- Keep public interfaces public when other packages depend on them.
- Make internal implementations package-private where Spring registration and tests still work cleanly.

Reason:

- This is mostly architecture hygiene, not an immediate runtime bug.
- It reduces accidental coupling to internal processing parts.

Implemented:

- Made `LogDeadLetterHandler` package-private.

Deferred:

- `PurchaseHandler` remains public because `UserSummaryService` currently depends on `PurchaseHandler.PurchaseSummary`; changing that safely requires extracting the summary type first.

## 6. Replace meaningless entity setter naming

Status: done in current pass

Current issue:

- `CampaignActivity.setFilters(...)` is a public setter-style method on an entity.

Target direction:

- Rename to an intention-revealing method such as `replaceFilters(...)` or `updateTargetingFilters(...)`.
- Add validation if a domain rule exists.

Implemented:

- Renamed `CampaignActivity.setFilters(...)` to `replaceFilters(...)`.

Reason:

- Public setter naming weakens the entity's mutation boundary.
- Domain methods should express why a field changes, not just that it can be assigned.

## 7. Add explicit Webhook timeout

Status: already implemented before this pass

Current issue:

- `WebhookStrategy` calls an external webhook synchronously through `HttpWebhookClient`.
- The structure already has retry and DLT, but timeout policy must be explicit.

Target direction:

- Configure connect timeout and read/response timeout for the webhook `RestClient`.
- Keep the current retry + DLT structure for now.

Current code:

- `WebhookClientConfig` configures `axon.webhook.connect-timeout` and `axon.webhook.read-timeout`.

Reason:

- External I/O failure is a normal case.
- Without timeout, retry and DLT do not sufficiently protect processing threads from long blocking.

## 8. Keep PaymentService Kafka ack wait for now, but limit it clearly

Status: light cleanup done in current pass, outbox deferred

Current flow:

- `PaymentController.confirmPayment()` validates the second payment token.
- `PaymentService.sendToKafkaWithRetry()` sends a `CAMPAIGN_ACTIVITY_COMMAND` Kafka message.
- It waits for Kafka broker ack using `.get(timeout)`.
- It does not wait for `core-service` to consume and persist the message.

Current issue:

- The HTTP request thread waits for Kafka broker ack.
- On failure it retries and sleeps in the request thread.

Short-term direction:

- Keep the policy that payment confirm waits until Kafka accepts the command.
- Make timeout and retry behavior short, explicit, and documented.
- Avoid widening scope into entry-service outbox in this refactoring pass.

Implemented:

- Renamed the hard-coded timeout constant to `BROKER_ACK_TIMEOUT_SECONDS`.
- Kept retry behavior functionally unchanged and explicit.

Long-term direction:

- If stronger payment reliability is needed, introduce an entry-service owned DB/outbox:
  - persist payment confirmation
  - persist outbox event in the same local transaction
  - publish Kafka asynchronously after commit

Reason for deferral:

- entry-service currently does not have a real JPA persistence model even though local datasource config exists.
- Adding entry DB/outbox is a larger architectural change.
- Core-service has more immediate transaction/query quality work.

## Deferred / Not Doing Now

### Product search indexing

Decision: defer

Reason:

- `ProductRepository.findByProductNameContaining(...)` likely becomes `LIKE '%keyword%'`.
- This is a potential search scalability issue, but product search is not a core bottleneck path in this project.
- There is currently no `product_name` index being "invalidated"; the table has no explicit product-name index.

Do not claim:

- Do not describe this as "an existing index being neutralized."

### Elasticsearch or MySQL FULLTEXT for product search

Decision: not now

Reason:

- Search is not the core domain of the current Axon backend story.
- Elasticsearch/FULLTEXT would be over-scoped for this refactoring pass.

### Webhook outbox or separate worker

Decision: defer

Reason:

- Timeout + retry + DLT is the right near-term improvement.
- Webhook outbox/worker is valid but larger than the current code-quality pass.

### Entry-service DB/outbox

Decision: defer

Reason:

- Architecturally valid for payment-confirm reliability.
- Too large for the current pass because entry-service would need a proper owned persistence model.
- If implemented later, entry-service must own its own DB/schema. It should not write directly to core-service tables.

### Core-service outbox

Decision: long-term candidate

Reason:

- Core-service already owns persistence-heavy flows.
- If outbox is introduced first, core-service is the more natural place to evaluate it.

### Turning OSIV off immediately

Decision: defer

Reason:

- DTO conversion should happen first.
- Then OSIV can be disabled to catch accidental lazy loading outside the service boundary.

## Summary

Immediate refactoring target:

1. Fix transaction self-invocation.
2. Move controller/view entity access behind DTO-producing services.
3. Optimize DTO assembly queries only where needed.
4. Hide internal implementation classes where practical.
5. Replace entity setter-style mutation with intention-revealing methods.
6. Add explicit webhook timeout.
7. Clean up PaymentService timeout/retry policy without introducing outbox yet.

Main boundary:

- This pass improves code quality and reliability boundaries.
- It does not introduce product search infrastructure, entry-service outbox, or broad repository rewrites.

## Execution Plan

This plan follows the project rule: every changed line must trace to the refactoring goal, and every step must have a verification path.

### Phase 0. Baseline check

Goal:

- Capture current test state before changing code.

Actions:

- Run focused tests for the affected areas first:
  - `core-service` tests around campaign activity entry batch/retry.
  - `core-service` dashboard/controller tests if present.
  - `entry-service` payment/controller tests.
- If a test already fails before refactoring, record it instead of mixing it with the refactor.

Verify:

- Baseline command output is known.
- Existing failures, if any, are separated from refactor-caused failures.

### Phase 1. Fix transaction self-invocation

Goal:

- Ensure individual retry really runs through a Spring proxy with `REQUIRES_NEW`.

Actions:

- Add a separate bean such as `CampaignActivityEntryRetryService`.
- Move `saveSingleEntryInNewTransaction(CampaignActivityEntry entry)` into that bean.
- Inject the retry service into `CampaignActivityEntryService`.
- Replace the internal self-call in `retryEntriesIndividually(...)` with the injected retry service call.
- Keep batch orchestration logic in `CampaignActivityEntryService`.

Do not:

- Do not redesign the whole entry lifecycle.
- Do not change Kafka message contracts.

Verify:

- Existing `CampaignActivityEntry` retry tests pass.
- Add or update a focused test if needed so the retry service is called during batch fallback.
- Confirm there is no same-class call to a `REQUIRES_NEW` method left in `CampaignActivityEntryService`.

### Phase 2. Move controller/view entity access to DTO-producing services

Goal:

- Prevent controllers and views from depending on JPA lazy loading.

Target paths:

- `DashboardViewController`
- `StoreController`
- `DashboardController` cohort batch response path

Actions:

- Create small query/view services only for the paths being cleaned up.
- Return DTOs or view models from those services.
- Move entity traversal into service methods with transactional boundaries.
- Controllers should only:
  - parse request parameters
  - call the service
  - place DTOs in `Model`
  - return view names or response DTOs

Do not:

- Do not refactor every controller in the application.
- Do not change endpoint URLs or response shapes unless required.
- Do not introduce broad generic DTO frameworks.

Verify:

- Existing controller/service tests pass.
- Add focused tests for DTO-producing service methods if no coverage exists.
- Search controllers for direct lazy association access patterns after the change:
  - `getCampaign()`
  - `getProduct()`
  - `getCoupon()`
  - `getCampaignActivity()`

### Phase 3. Optimize DTO assembly queries only where needed

Goal:

- Prevent moving N+1 from controller/view into service DTO assembly.

Actions:

- Add repository methods with `@EntityGraph` for simple association loading:
  - `CampaignActivity` with `campaign` for dashboard view.
  - `CampaignActivity` with `product` and `coupon` for store/event views.
  - `LTVBatch` with `campaignActivity` for cohort batch response.
- Use fetch join only if `@EntityGraph` is insufficient or the query requires explicit join conditions.
- Use DTO projection only if the path needs selected columns and no entity behavior.

Do not:

- Do not convert all repository methods.
- Do not replace lazy mappings with eager mappings.
- Do not add fetch joins to paginated collection queries without checking JPA pitfalls.

Verify:

- Tests pass.
- Optional but preferred: enable SQL logging locally for the changed paths and confirm query count is bounded.
- No repository method is broadened beyond the specific use case.

### Phase 4. Clean up entity mutation boundary

Goal:

- Remove setter-style mutation from entity API.

Actions:

- Rename `CampaignActivity.setFilters(...)` to an intention-revealing method such as `replaceFilters(...)` or `updateTargetingFilters(...)`.
- Update only direct call sites.
- Add validation only if there is an existing domain rule to enforce.

Do not:

- Do not create speculative filter validation rules.
- Do not refactor unrelated entity methods.

Verify:

- Compile succeeds.
- Search confirms there is no `setFilters(` call on `CampaignActivity`.

### Phase 5. Hide internal implementation classes where safe

Goal:

- Reduce accidental coupling to internal processing components.

Actions:

- Check direct imports/usages before changing visibility.
- Make implementation classes package-private only when:
  - Spring can still instantiate them.
  - tests can be adjusted without weakening coverage.
  - public interfaces remain available where needed.

Candidates:

- `PurchaseHandler`
- `LogDeadLetterHandler`
- concrete `CampaignStrategy` implementations

Do not:

- Do not make public interfaces package-private.
- Do not break test readability just to hide a class.
- Do not treat this as a runtime-critical fix.

Verify:

- Compile and affected tests pass.
- Search confirms no external package imports package-private implementations.

### Phase 6. Add explicit webhook timeout

Goal:

- Bound external webhook blocking time.

Actions:

- Configure timeout on the webhook `RestClient`.
- Prefer property-backed timeout values with conservative defaults.
- Keep existing retry and DLT behavior.

Do not:

- Do not add webhook outbox or separate worker in this pass.
- Do not change webhook payload contract.

Verify:

- `WebhookStrategyTest` passes.
- Add/update a configuration test if practical.
- Manual code check confirms connect and read/response timeout are configured.

### Phase 7. Clarify PaymentService timeout/retry policy

Goal:

- Keep Kafka broker ack wait for now, but make request-thread blocking bounded and explicit.

Actions:

- Keep the current policy: payment confirm waits for Kafka broker ack, not core-service consumption.
- Reduce or clearly configure timeout and retry values.
- Avoid long `Thread.sleep(...)` in the request path if a simpler bounded retry is enough.
- Document that entry-service outbox is deferred.

Do not:

- Do not introduce entry-service DB/outbox in this pass.
- Do not call core-service API to store outbox on behalf of entry-service.
- Do not claim core-service processing is complete when only Kafka ack is confirmed.

Verify:

- `PaymentServiceTest` and `PaymentController` tests pass.
- Failure response behavior remains explicit when Kafka send fails.
- Code comments or docs make the success boundary clear: broker ack only.

### Phase 8. OSIV follow-up

Goal:

- Use OSIV off as a later boundary check, not as the first change.

Actions:

- After DTO conversion and query optimization are stable, test with:
  - `spring.jpa.open-in-view=false`
- Fix any remaining lazy loading failures by moving data access into service/query methods.

Do not:

- Do not disable OSIV before DTO cleanup.
- Do not hide failures by adding broad eager loading.

Verify:

- Controller/view tests pass with OSIV disabled.
- No view rendering path depends on lazy loading outside service transactions.

## Success Criteria

The refactoring is considered complete when:

- `CampaignActivityEntryService` no longer self-invokes a `REQUIRES_NEW` method.
- Controllers in the targeted paths no longer traverse lazy JPA associations directly.
- DTO assembly uses explicit query plans only where needed.
- `CampaignActivity` no longer exposes `setFilters(...)`.
- Webhook calls have explicit timeout configuration.
- Payment confirm code documents and enforces a bounded Kafka broker ack wait.
- Deferred items remain deferred and are not half-implemented.
- Focused tests for affected modules pass, or any pre-existing failures are documented separately.

## Portfolio Recovery Notes

Use this section when turning the refactoring into a portfolio or interview story. Keep the wording tied to code facts, not generic cleanup claims.

### Strongest usable points

1. Transaction boundary correction in batch fallback

- Code evidence:
  - `core-service/src/main/java/com/axon/core_service/service/CampaignActivityEntryService.java`
  - `core-service/src/main/java/com/axon/core_service/service/CampaignActivityEntryRetryService.java`
- Before:
  - Batch fallback intended to retry each failed entry with `REQUIRES_NEW`, but the method was called inside the same class.
  - In Spring proxy-based transaction AOP, same-class self-invocation can bypass the new transaction boundary.
- After:
  - Single-entry retry save moved to `CampaignActivityEntryRetryService`.
  - `CampaignActivityEntryService` now orchestrates batch retry and calls the retry bean.
- Portfolio-safe message:
  - "배치 실패 복구 로직에서 Spring AOP self-invocation으로 트랜잭션 격리가 깨질 수 있는 지점을 분리하고, 단건 재시도가 실제 별도 트랜잭션 경계를 타도록 수정했다."
- Do not overclaim:
  - Do not say this alone proves data loss 0.
  - Data loss/consistency claims still need load-test, DB state, or reconciliation evidence.

2. View/Controller lazy loading risk reduction

- Code evidence:
  - `core-service/src/main/java/com/axon/core_service/repository/CampaignActivityRepository.java`
  - `core-service/src/main/java/com/axon/core_service/repository/UserCouponRepository.java`
  - `core-service/src/main/java/com/axon/core_service/repository/LTVBatchRepository.java`
  - `core-service/src/main/java/com/axon/core_service/service/CohortAnalysisService.java`
  - `core-service/src/main/java/com/axon/core_service/controller/DashboardController.java`
  - `core-service/src/test/java/com/axon/core_service/performance/JpaAssociationQueryCountTest.java`
- Before:
  - Some controller/view paths accessed lazy associations such as campaign, product, coupon, and campaignActivity.
  - This can hide query execution behind OSIV and make N+1 or lazy loading behavior harder to reason about.
- After:
  - Cohort batch response assembly moved from controller to service.
  - Simple association loading paths now use explicit `@EntityGraph` methods where needed.
- Measured query-count evidence:
  - Active activity list with product/coupon access: 21 prepared statements to 1.
  - User coupon list with coupon access: 11 prepared statements to 1.
  - LTV batch list with campaign activity access: 2 prepared statements to 1.
  - Command: `./gradlew test --tests com.axon.core_service.performance.JpaAssociationQueryCountTest --rerun-tasks --info`
- Portfolio-safe message:
  - "컨트롤러에서 엔티티 연관관계를 직접 타던 조회 경로를 점검하고, 서비스 책임으로 옮기거나 필요한 조회 경로에 `EntityGraph`를 명시해 view 렌더링 중 예측 못한 lazy loading을 줄였다."
- Do not overclaim:
  - Do not say all controller entity access is removed yet.
  - `DashboardViewController` and `StoreController` still have remaining view-model assembly work.
  - Do not describe these numbers as production latency improvements. They are local JPA query-count regression measurements.

3. Entity mutation boundary cleanup

- Code evidence:
  - `core-service/src/main/java/com/axon/core_service/domain/campaignactivity/CampaignActivity.java`
  - `core-service/src/main/java/com/axon/core_service/service/CampaignActivityService.java`
- Before:
  - Entity exposed `setFilters(...)`, which reads like arbitrary field assignment.
- After:
  - Renamed to `replaceFilters(...)` to express an intentional domain mutation.
- Portfolio-safe message:
  - "엔티티의 public setter 스타일 메서드를 의도 기반 메서드로 바꿔, 외부 계층이 필드를 임의 변경하는 구조가 아니라 도메인 행위를 호출하도록 정리했다."
- Do not overclaim:
  - This is code quality and maintainability evidence, not performance evidence.

4. Payment confirm success boundary clarification

- Code evidence:
  - `entry-service/src/main/java/com/axon/entry_service/service/Payment/PaymentService.java`
- Current behavior:
  - Payment confirm waits for Kafka broker ack with bounded timeout.
  - It does not wait for core-service consumption or DB persistence.
- Change:
  - Timeout constant renamed to `BROKER_ACK_TIMEOUT_SECONDS`.
  - Retry behavior kept functionally unchanged.
- Portfolio-safe message:
  - "결제 확인 흐름에서 성공 기준을 core 처리 완료가 아니라 Kafka broker ack까지로 명확히 분리하고, 후속 영속화는 비동기 파이프라인 책임으로 두었다."
- Do not overclaim:
  - Do not say payment persistence is guaranteed at HTTP response time.
  - If stronger guarantee is needed, entry-service owned outbox is a future design candidate.

5. Internal implementation exposure reduction

- Code evidence:
  - `core-service/src/main/java/com/axon/core_service/service/purchase/LogDeadLetterHandler.java`
- Change:
  - `LogDeadLetterHandler` made package-private.
- Portfolio-safe message:
  - "외부에서 직접 사용할 필요가 없는 내부 구현체의 public 노출을 줄여 패키지 경계를 명확히 했다."
- Do not overclaim:
  - This is architecture hygiene. It is not a runtime failure fix.

### Verification record

- `core-service`: `./gradlew compileJava compileTestJava` passed.
- `entry-service`: `./gradlew compileJava compileTestJava` passed.
- `PaymentServiceTest` passed.
- `CohortAnalysisServiceTest` passed.
- `JpaAssociationQueryCountTest` passed and recorded query-count reductions:
  - active activities: 21 to 1
  - user coupons: 11 to 1
  - LTV batch: 2 to 1
- `CampaignActivityEntryRetryTest` compile path passed, but runtime execution failed because Testcontainers could not find Docker in the current environment.

### Recommended portfolio framing

- Best theme:
  - "동작하는 기능을 넘어서, Spring 트랜잭션 프록시 경계와 JPA 조회 경계를 코드 기준으로 재점검하고 수정했다."
- Best connection to existing T2 story:
  - This strengthens the batch failure isolation story because `REQUIRES_NEW` was corrected from intended design to actual proxy-applied transaction boundary.
- Best connection to backend quality:
  - Transaction correctness, query boundary control, entity mutation boundary, and async success-boundary clarification.

### Remaining follow-up if polishing further

- Move remaining `DashboardViewController` and `StoreController` view-model assembly into dedicated query/view services.
- Then test with `spring.jpa.open-in-view=false`.
- Extract `PurchaseHandler.PurchaseSummary` out of `PurchaseHandler` before making `PurchaseHandler` package-private.
- Add an integration test that verifies individual retry continues after one failed entry when Docker/Testcontainers is available.

## Additional Design Review Backlog

This section is the next review queue after the initial five anti-pattern checks. The goal is to find portfolio deduction risks and upgrade candidates that are not covered by public setters, controller entity exposure, transactional external I/O, public internal classes, and index-neutralizing queries.

### 1. Transaction boundary and event timing

Review targets:

- `@Transactional` methods that publish Spring events, Kafka messages, or external effects.
- `BEFORE_COMMIT` / `AFTER_COMMIT` usage.
- Places where docs say Outbox was considered or deferred.

Questions:

- Does the code clearly separate DB commit success from downstream processing success?
- Does documentation distinguish Kafka broker ack, Kafka consumption, DB persistence, and reconciliation?
- Are there same-class transaction self-invocations left outside the already fixed `CampaignActivityEntryService` path?

Portfolio value:

- Strong if it shows concrete tradeoffs: throughput vs consistency, before/after commit timing, outbox deferral reason, reconciliation safety net.

### 2. Async scheduler and multi-pod safety

Review targets:

- `@Scheduled` jobs.
- In-memory buffers and queue drain loops.
- `@PreDestroy` graceful shutdown.
- Any scheduler that updates stock, reconciliation, behavior triggers, or batch metrics.

Questions:

- If two core-service pods run, can the same scheduler process the same work twice?
- Is there a DB/Redis lock, idempotency key, status transition, or unique constraint preventing duplicate effects?
- Can scheduled jobs overlap with themselves if one execution takes longer than the interval?
- What happens to unbounded queues under downstream DB/Kafka slowdown?

Portfolio value:

- High for operation-readiness. This turns "batch exists" into "batch is safe under distributed deployment assumptions."

### 3. Exception handling, DLQ, and recovery traceability

Review targets:

- `catch (Exception e)` blocks.
- DLQ send paths.
- retry loops and backoff.
- failure logs for Kafka, webhook, purchase, scheduler, reconciliation.

Questions:

- Does the caller incorrectly see success after a swallowed failure?
- Does each failure log include enough recovery keys such as `userId`, `activityId`, `eventId`, `idempotencyKey`, topic, or payload type?
- Does DLQ contain enough information to retry or diagnose?
- Is DLQ send failure itself handled and visible?

Portfolio value:

- High. "DLQ exists" is weaker than "failed data can be identified, isolated, and recovered."

### 4. Test evidence quality

Review targets:

- Testcontainers tests.
- Redis/Kafka/MySQL integration tests.
- concurrency tests using `CountDownLatch` or equivalent.
- tests for retry/DLQ/reconciliation.
- query-count tests such as `JpaAssociationQueryCountTest`.

Questions:

- Are claims backed by real infrastructure tests or only mocks?
- Do concurrency tests actually start at the same time?
- Are Testcontainers-backed claims documented as such?
- Are local/H2 query-count measurements clearly separated from production latency claims?

Portfolio value:

- High for interview defense. It prevents overclaiming and identifies which claims are truly test-backed.

### 5. Domain and package boundaries

Review targets:

- Controllers directly injecting repositories.
- Services depending on implementation details or nested types from other services.
- Handler/strategy classes exposed as public without being stable APIs.
- DTO assembly mixed into controllers.

Questions:

- Does a controller know too much about persistence shape?
- Does one service import an internal type from another component, such as `PurchaseHandler.PurchaseSummary`?
- Can internal implementations be package-private after extracting shared value types?

Portfolio value:

- Medium to high. It reduces "beginner code smell" and improves code-review impression.

### 6. Configuration and operational defaults

Review targets:

- Kafka producer/consumer ack, retry, timeout, group-id, transaction-id-prefix.
- HikariCP pool settings.
- Redis timeout and connection settings.
- Webhook timeout.
- local/test/prod config separation.

Questions:

- Are failure/timeouts explicit for every external dependency?
- Are local and production assumptions mixed?
- Are broker count, replication factor, and min.insync replicas claims separated from the actual single-broker local/VM environment?

Portfolio value:

- Medium. Useful for operational maturity, especially if paired with concrete failure-mode tests.

### 7. Observability and diagnosis readiness

Review targets:

- Logs around Kafka consumer, batch flush, DLQ, reconciliation, webhook, and scheduler jobs.
- Metrics/Actuator/Pinpoint readiness.
- Batch result counts and mismatch counts.

Questions:

- If a failure happens, can we locate it by user/activity/event key without reading every log?
- Are batch processed/succeeded/failed counts logged consistently?
- Are slow paths and retry paths visible?

Portfolio value:

- High if later connected to Pinpoint/APM bottleneck analysis or incident-style troubleshooting notes.

### Recommended next scan order

1. `@Scheduled` / multi-pod safety.
2. Exception handling, DLQ, and recovery traceability.
3. Controller/repository and domain boundary cleanup.
4. Test evidence quality.
5. Config and observability pass.

Do not turn every finding into code immediately. First classify findings as:

- `fact error`: docs claim something code does not do.
- `real bug`: can cause wrong data or failed recovery.
- `code smell`: maintainability or beginner-impression issue.
- `portfolio upgrade`: not a bug, but creates stronger evidence if improved.
- `defer`: valid concern but too large or outside current portfolio direction.

## Reporting Requirements

When implementing this plan, report:

- Files changed by phase.
- Tests run and results.
- Any remaining controller/view entity access.
- Any repository query path that still risks N+1.
- Any deferred item that was intentionally not implemented.

## Additional Scan Results - 2026-05-30

Status: active follow-up backlog.

Scope:

- This section records code-level findings from the broader design/anti-pattern scan beyond the initial five rules.
- Do not treat every item as an immediate implementation task.
- Use the classification field to decide whether to fix, document, or defer.

### 1. Multi-pod scheduler duplication risk

Classification: `portfolio upgrade` / possible `real bug` under multi-instance deployment.

Evidence:

- `core-service/src/main/java/com/axon/core_service/scheduler/BehaviorTriggerScheduler.java:39` runs hourly with `@Scheduled`.
- `core-service/src/main/java/com/axon/core_service/service/scheduler/CampaignStockSyncScheduler.java:28` runs every five minutes with `@Scheduled`.
- `core-service/src/main/java/com/axon/core_service/scheduler/CohortLtvBatchScheduler.java:23` runs monthly with `@Scheduled`.
- `core-service/src/main/java/com/axon/core_service/scheduler/RfmSegmentationScheduler.java:30` runs daily with `@Scheduled`.
- `core-service/src/main/java/com/axon/core_service/scheduler/UserPurchaseScheduler.java:28` runs with `fixedRate`.
- There is no `ShedLock`, leader election, DB job lock, or distributed scheduler guard found in these scheduler entry points.

Why it matters:

- If `core-service` is scaled to multiple pods/containers, each instance can run the same scheduled job.
- `BehaviorTriggerScheduler` has Redis `setIfAbsent` per rule/user/product, so reward duplication is partly absorbed, but all instances still scan ES/DB and attempt the job.
- `CampaignStockSyncScheduler` updates `syncedCount` and product stock from DB counts. Running the same sync concurrently can race unless guarded by DB row lock/versioning or a scheduler lock.

Recommended direction:

- For portfolio-quality backend code, introduce one explicit scheduler ownership mechanism for jobs that mutate state.
- Lowest-risk option: use ShedLock backed by MySQL for scheduled jobs.
- Alternative: keep scheduling single-instance only and document that constraint in deployment notes.

Do not overclaim:

- Current code has reconciliation and some idempotent keys, but it is not a general multi-pod-safe scheduler architecture.

### 2. In-memory unbounded buffers

Classification: `code smell` / `portfolio upgrade`.

Evidence:

- `core-service/src/main/java/com/axon/core_service/service/CampaignActivityConsumerService.java:32` uses `ConcurrentLinkedQueue` for Kafka command buffering.
- `core-service/src/main/java/com/axon/core_service/service/purchase/PurchaseHandler.java:38` uses `ConcurrentLinkedQueue` for purchase buffering.
- Both queues are unbounded.
- `CampaignActivityConsumerService.consume()` only does `buffer.offer(...)` at line 54.
- `PurchaseHandler.handle()` only does `purchaseBuffer.offer(...)` at line 46.

Why it matters:

- The current design intentionally separates Kafka listener/event listener threads from DB flush work.
- Under sustained overload, an unbounded queue hides backpressure and can turn DB slowness into heap growth.
- For the current 200-result FCFS scenario and 20-size flush batches, this is acceptable as a pragmatic project design, but it is not a production-grade backpressure mechanism.

Recommended direction:

- Do not blindly replace it before measuring.
- If hardening this path, use a bounded queue plus an explicit overflow policy:
- reject and route to DLT,
- block briefly with timeout,
- or record overload and fail fast.

Do not overclaim:

- Current queue design is "listener/flush responsibility separation", not full backpressure control.

### 3. DLT send is isolated but not operationally traceable enough

Classification: `portfolio upgrade`.

Evidence:

- `core-service/src/main/java/com/axon/core_service/service/CampaignActivityConsumerService.java:120-124` catches batch processing failure and sends failed messages to `CAMPAIGN_ACTIVITY_COMMAND_DLT`.
- `core-service/src/main/java/com/axon/core_service/service/purchase/LogDeadLetterHandler.java:28` sends permanently failed purchase data to `PURCHASE_FAILED_DLT`.
- `core-service/src/main/java/com/axon/core_service/service/strategy/WebhookStrategy.java:75-77` sends permanently failed webhook requests to `WEBHOOK_FAILED_DLT`.
- DLT messages are sent, but there is no durable failure audit table, retry state, operator status, or DLT consumer found in the current code.

Why it matters:

- "DLT로 격리" is supported.
- "운영자가 실패 원인을 분류하고 재처리한다" is not yet supported unless a separate operational workflow is implemented.
- This is a good candidate for the previously discussed DLQ failure triage/audit feature.

Recommended direction:

- Add a small `failure_event`/`dlq_audit` table only if the portfolio direction needs operational recovery evidence.
- Store topic, payload key fields, failure stage, exception summary, status, createdAt, and lastRetriedAt.
- Keep Kafka DLT as transport-level isolation; use DB audit as operator-facing recovery state.

Do not overclaim:

- Current code does not provide an end-to-end DLQ replay console or automatic repair.

### 4. Actuator exposure is too broad for production

Classification: `real bug` if deployed publicly as-is; otherwise `code smell`.

Evidence:

- `core-service/src/main/java/com/axon/core_service/config/auth/SecurityConfig.java:81` permits `/actuator/**`.
- `core-service/src/main/resources/application.yml:62-66` exposes `health,info,prometheus,metrics`.
- `entry-service/src/main/resources/application.yml` also exposes `health,info,prometheus,metrics`.

Why it matters:

- Public `/actuator/metrics` and `/actuator/prometheus` can expose service internals.
- For local/VM performance testing this is convenient.
- For an internet-facing deployment it should be restricted by network, profile, or authentication.

Recommended direction:

- Keep `health` public.
- Restrict `metrics`/`prometheus` to an internal network, monitoring profile, or admin-only security rule.
- If using Pinpoint only, keep actuator minimal unless Prometheus is intentionally enabled.

Implemented:

- `core-service` now permits only `/actuator/health` explicitly; other actuator endpoints fall through to authenticated access.

Do not overclaim:

- Current observability setup is useful for test environments, not yet hardened production security.

### 5. Controller still owns too much persistence/view assembly

Classification: `code smell`.

Evidence:

- `core-service/src/main/java/com/axon/core_service/controller/StoreController.java:37-42` injects repositories directly.
- `StoreController.java:49` calls `productRepository.findAll()`.
- `StoreController.java:196-198` calls `campaignActivityRepository.findAllByStatus(...)`.
- `StoreController.java:255` calls `campaignActivityRepository.findWithProductAndCouponById(...)`.
- `StoreController.java:319` saves a `Purchase` directly through `purchaseRepository`.
- `StoreController.java:383-422` converts `CampaignActivity` to view DTO inside the controller.
- `DashboardViewController` also still injects repositories for view-model assembly.

Why it matters:

- This is not necessarily a runtime bug, but it reads like early-stage MVC code.
- It mixes HTTP/view concerns, persistence access, authentication parsing, and DTO assembly.
- It can make OSIV/lazy-loading mistakes easier to reintroduce.

Recommended direction:

- Move store-page query and DTO assembly into a `StoreViewService`.
- Keep controller methods thin: parse request, call service, put response into model.
- Keep repository access out of controllers unless it is a deliberate prototype/admin shortcut.

Implemented:

- `StoreController` now delegates store-page query, campaign activity view data, user coupon lookup, and shop purchase persistence to `StoreViewService`.

Do not overclaim:

- The recent EntityGraph work reduced query-count risk, but it did not fully clean the controller-service boundary.

### 6. Webhook retry protects caller timeout but still runs synchronously inside strategy batch

Classification: `portfolio upgrade`.

Evidence:

- `core-service/src/main/java/com/axon/core_service/service/strategy/WebhookStrategy.java:37-40` maps each message and sends sequentially.
- `WebhookStrategy.java:63-77` retries three times and sends to DLT after final failure.
- `core-service/src/main/java/com/axon/core_service/config/WebhookClientConfig.java` configures connect/read timeouts, so indefinite blocking was addressed.

Why it matters:

- Timeout is now explicit, so the worst network wait is bounded.
- However, the strategy still spends the consumer flush worker's time on outbound HTTP retry.
- If webhook traffic grows, this can slow the campaign command processing batch.

Recommended direction:

- Keep current version if webhook is a small portfolio extension.
- If hardening, split webhook delivery into a separate topic/worker or audit table so campaign reward detection is not coupled to external HTTP delivery latency.

Do not overclaim:

- Current implementation demonstrates timeout/retry/DLT isolation, not a fully decoupled external-integration delivery platform.

### 7. Test/debug endpoints and comments need profile discipline

Classification: `code smell` / possible security issue if deployed publicly.

Evidence:

- `core-service/src/main/java/com/axon/core_service/config/auth/SecurityConfig.java:64-67` permits `/test/**`.
- `entry-service/src/main/java/com/axon/entry_service/config/auth/SecurityConfig.java` also permits `/actuator/health`; test endpoint exposure should be checked similarly.
- `entry-service/src/main/java/com/axon/entry_service/controller/TestReservationController.java` is explicitly test-only and uses mock metadata.
- `core-service/src/main/java/com/axon/core_service/scheduler/UserPurchaseScheduler.java:32-40` contains test-oriented batch parameters and comments.

Why it matters:

- Test helpers are useful in portfolio projects, but production profile separation should be visible.
- Otherwise reviewers may read it as debug code left in runtime paths.

Recommended direction:

- Put test controllers behind `@Profile("local")` or remove them from production component scanning.
- Move test-only scheduler parameterization to a local profile or explicit manual test runner.

Implemented:

- `entry-service` test reservation controller is now active only for `dev`/`test`.
- The explicitly test-parameterized `UserPurchaseScheduler` is now active only for `dev`/`test`.

Do not overclaim:

- Current code contains test support; it should not be presented as production admin tooling.

### 8. Coupon batch duplicate check is correct but not optimal for larger batches

Classification: `portfolio upgrade` / defer unless coupon issuance becomes a main story.

Evidence:

- `core-service/src/main/java/com/axon/core_service/service/strategy/CouponStrategy.java` bulk-loads coupon entities.
- It still checks duplicates with `userCouponRepository.existsByUserIdAndCouponId(userId, couponId)` per message before `saveAll`.
- The source comment already notes that `INSERT IGNORE` or a composite unique key would be faster for large batches.

Why it matters:

- Functionally clear and acceptable for small batches.
- For high-throughput coupon issuing, per-row existence checks can become N+1-like write-path overhead.

Recommended direction:

- Confirm a unique constraint exists on `(user_id, coupon_id)`.
- If coupon issuing becomes a main performance path, switch to DB-level duplicate absorption with bulk insert semantics.

Implemented:

- `UserCoupon` now declares a `(user_id, coupon_id)` unique constraint.
- `CouponStrategy` now bulk-prefetches already issued user-coupon pairs for the current batch and removes duplicates before `saveAll`.

Do not overclaim:

- Current coupon path is not the strongest high-throughput evidence compared with FCFS Redis Lua and Kafka batch processing.
