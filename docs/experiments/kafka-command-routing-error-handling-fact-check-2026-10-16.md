# Kafka Command Routing / Error Handling Fact Check

Status: reference (code snapshot checked 2026-10-16)

## Scope

This document records code-observed facts and items that still require checking. It does not select a target design or prescribe a refactor.

## Current listener and topic map

| Topic | Listener / group | Payload and observed work |
|---|---|---|
| `axon.campaign-activity.command` | `CampaignActivityConsumerService`, group `axon-group` | Batch of `CampaignActivityKafkaProducerDto`; `CampaignActivityCommandDispatcher` routes by `campaignActivityType` |
| `axon.webhook.command` | `WebhookCommandConsumerService`, group `axon-webhook-group` | Batch of `CampaignActivityKafkaProducerDto`; `WebhookStrategy` calls the external HTTP endpoint |
| `axon.campaign-activity.command.dlt` | `MarketingActionExecutionDltConsumer.consumeCampaignCommandDlt`, group `axon-marketing-action-campaign-dlt-group` | Batch of command DTOs; updates marketing dispatch final-failure state |
| `axon.webhook.failed.dlt` | `MarketingActionExecutionDltConsumer.consumeWebhookDlt`, group `axon-marketing-action-webhook-dlt-group` | Batch of `WebhookFailedDelivery`; updates marketing dispatch final-failure state |

`core-service` has other Kafka listeners as well; the table is limited to the FCFS/Coupon/Webhook command paths and their DLT consumers.

## Producer routing observed in source

### `axon.campaign-activity.command`

Source call sites found:

- `entry-service/.../PaymentService.java`
- `entry-service/.../CouponEntryService.java`
- `entry-service/.../TestReservationController.java`
- `core-service/.../CampaignActivityRunService.java`
- `core-service/.../AbandonedCartCouponScheduler.java`
- `core-service/.../BehaviorTriggerScheduler.java` when the action is not `WEBHOOK`
- `core-service/.../MarketingActionExecutionService.java` when the channel is not `WEBHOOK`
- `core-service/.../MarketingActionTriageService.java` when the retried channel is not `WEBHOOK`
- `core-service/.../DormantCustomerCampaignScheduler.java` when the action is not `WEBHOOK`

### `axon.webhook.command`

Direct producer routing by action/channel was found in:

- `BehaviorTriggerScheduler`
- `DormantCustomerCampaignScheduler`
- `MarketingActionExecutionService`
- `MarketingActionTriageService`

There is also a second code path: `CampaignActivityCommandDispatcher` forwards a message whose `campaignActivityType` is `WEBHOOK` from `axon.campaign-activity.command` to `axon.webhook.command`; the send waits on `.join()`.

The source search establishes that both direct publication and command-topic forwarding exist in code. It does not establish whether the forwarding branch receives messages in current runtime traffic.

## `axon.campaign-activity.command` processing

Application configuration sets `max.poll.records: 20`, `listener.type: batch`, and `ack-mode: batch`. `CampaignActivityConsumerService` explicitly uses `campaignCommandKafkaListenerContainerFactory`.

The Dispatcher processes contiguous runs of the same `campaignActivityType` in the listener batch:

- `FIRST_COME_FIRST_SERVE` → `FirstComeFirstServeStrategy` → `FcfsCommandOrchestrator` → `FcfsLedgerPersistenceService.persistBatch()`.
- `COUPON` → `CouponStrategy.processBatch()`.
- `WEBHOOK` → synchronous forwarding to `axon.webhook.command`.
- Missing or unsupported type → direct send to `axon.campaign-activity.command.dlt`.

### FCFS exception path

- `FcfsLedgerPersistenceService.persistBatch()` is annotated `@Transactional`.
- The orchestrator first attempts persistence for its deduplicated batch.
- A batch failure whose cause chain matches the configured transient persistence types (`TransientDataAccessException`, `RecoverableDataAccessException`, `DataAccessResourceFailureException`, `SQLTransientException`, `SQLRecoverableException`) is reported as `BatchListenerFailedException` at the first retained message index. The individual-message isolation loop is skipped for this branch.
- A one-message batch failure is reported with that message's index.
- Other failures from a batch containing multiple retained messages trigger sequential one-message persistence attempts. On the first failure, the failure index is reported to Spring Kafka after projection updates and purchase-event publication for the preceding successful results.
- The orchestrator does not publish command DLT records directly.

### Coupon exception path

- `CouponStrategy.processBatch()` is annotated `@Transactional` and performs bulk reads, validates commands, saves valid user coupons, then marks valid dispatches succeeded.
- Missing `userId`, unresolved coupon ID, or a coupon ID not found in the database is sent directly to `axon.campaign-activity.command.dlt` with category `INVALID_TARGET`.
- If that direct DLT send fails, the strategy throws `OffsetCommitBlockedException`.
- Other batch exceptions propagate without a `BatchListenerFailedException` index.

## Campaign command error-handler configuration

`CampaignCommandKafkaErrorHandlerConfig` creates a named listener factory and attaches its `DefaultErrorHandler` only to that factory. `CampaignActivityConsumerService` names that factory explicitly. The handler is built inside the factory configuration method; it is not exposed as a `CommonErrorHandler` bean.

Observed settings:

- `FixedBackOff(1_000 ms, 2 retries)`; this is up to three attempts including the initial delivery.
- Non-retryable exception classes added: `IllegalArgumentException`, `DataIntegrityViolationException`, `InvalidDataAccessApiUsageException`, and `BadSqlGrammarException`.
- The recoverer sends a `CampaignActivityKafkaProducerDto` to `axon.campaign-activity.command.dlt` and records the DLT metric.
- If the failure cause chain contains `OffsetCommitBlockedException`, the recoverer throws instead of publishing to command DLT.
- If command-DLT publication fails, the send exception propagates from the recoverer.

For a batch listener, Spring Kafka's documented fallback for an exception other than `BatchListenerFailedException` retries the batch and, after retry exhaustion, invokes the recoverer for each record. For `BatchListenerFailedException`, the reported index determines the failed record and offset handling. See [Spring Kafka 3.3 exception handling](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html).

## Webhook delivery path

`WebhookCommandConsumerService` listens to `axon.webhook.command` with group `axon-webhook-group`. It does not name the campaign-command-specific container factory.

`WebhookStrategy.processBatch()` processes messages sequentially. For each message:

- It makes at most three HTTP attempts.
- It retries `ResourceAccessException`, `HttpServerErrorException`, and HTTP 429.
- Other HTTP 4xx failures are not retried by `isRetryable()`.
- After final failure, it sends a `WebhookFailedDelivery` to `axon.webhook.failed.dlt` and records the DLT metric.
- If publishing to `axon.webhook.failed.dlt` fails, it throws `OffsetCommitBlockedException`.

The project-specific `1-second × 2` campaign-command handler is not attached to this listener factory. This fact does not describe any framework default behavior beyond the explicit project configuration.

## Current source/document alignment

- `docs/architecture-map.md` and `docs/experiments/dev-log-2026-08-06-webhook-topic-isolation-ab.md` describe direct Webhook-topic publication and the separate Webhook consumer.
- The isolation devlog also records the legacy Dispatcher-forwarding path.
- Current source contains both direct topic routing and the Dispatcher-forwarding branch.
- Static source inspection did not establish whether any live producer still publishes `campaignActivityType=WEBHOOK` to the campaign-command topic.

## Items to verify before changing topic/listener boundaries

- Which runtime callers, if any, publish `campaignActivityType=WEBHOOK` to `axon.campaign-activity.command` and therefore use the Dispatcher-forwarding branch.
- The producer key, partition count, and ordering assumptions for each command topic.
- Whether FCFS and Coupon commands require ordering relative to each other or only within their own business key.
- The actual listener concurrency and consumer-instance counts for `axon-group` and `axon-webhook-group` in the run configuration being used.
- The intended behavior after Coupon batch-level database failure, including the number and payloads of records recovered to command DLT.
- The effective framework error behavior for `WebhookCommandConsumerService` when the webhook DLT send fails; this listener has no project-specific handler attached in the inspected source.
- Kafka integration-test coverage for committed offsets, redelivery order, DLT publication failure, and repeated delivery. The focused tests run for this code check were unit tests, not broker-backed offset tests.

## Documents updated by this change

- New: `docs/experiments/kafka-command-routing-error-handling-fact-check-2026-10-16.md`.
- Updated: `docs/README.md` to register this reference document.
- No existing document was marked legacy or superseded.
