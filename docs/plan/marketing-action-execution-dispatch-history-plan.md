# Marketing Action Execution / Dispatch History Plan

Status: active implementation plan

## Goal

Preserve the delivery history of behavior-triggered coupon issuance and Webhook delivery.

The current `MarketingActionExecution` row mixes two different concepts:

```text
Execution: a qualified user must receive a configured marketing action.
Dispatch: one Kafka delivery generation for that execution.
```

The current row-reuse model overwrites the previous DLT reason and timestamps when an administrator retries a final failure. `dispatchVersion` exists only because one row represents multiple dispatch generations. Replace that compression with a parent `Execution` and child `Dispatch` model.

## Domain Model

```text
MarketingRule
  -> MarketingAction
    -> MarketingActionExecution       one qualified user/action business obligation
      -> MarketingActionDispatch      one initial or administrator-approved Kafka delivery
```

### `MarketingActionExecution`

One row is created when the scheduler's Redis dedup accepts an action target.

Keep only stable business facts:

- `id`
- `marketingActionId`, `marketingRuleId`, `actionReferenceId`
- `userId`, nullable `productId`, `channel`
- `createdAt`, `updatedAt`

Remove delivery-lifecycle fields from this table:

- `status`, `attemptCount`, `dispatchVersion`
- `lastFailureReason`, `dispatchedAt`, `completedAt`, `dltAt`

### `MarketingActionDispatch`

Create a new row for every Kafka delivery generation.

Required fields:

- `id`
- `execution_id` foreign key to `marketing_action_executions`
- `sequence` starting at 1 for the initial dispatch and increasing for each admin retry
- `initiatedBy`: `SYSTEM` for initial dispatch, `ADMIN` for approved retry
- existing lifecycle `status` values: `PENDING`, `DISPATCHING`, `DISPATCHED`, `PROCESSING`, `RETRYING`, `SUCCEEDED`, `FAILED_FINAL`
- `attemptCount`: consumer-side execution attempts within this dispatch only
- `lastFailureReason`, `dispatchedAt`, `completedAt`, `dltAt`
- `createdAt`, `updatedAt`

Constraints and indexes:

- foreign key `execution_id -> marketing_action_executions(id)`
- unique `(execution_id, sequence)`
- index `(status, dlt_at)` for final-failure operations
- index `(execution_id, sequence)` for execution history

Do not add a third table for each HTTP retry. The existing maximum three Webhook retries remain summarized in one Dispatch's `attemptCount`.

## Message Contract

Replace `executionDispatchVersion` with `dispatchId` in the command DTO and Webhook DLT envelope.

```text
CampaignActivityKafkaProducerDto
  executionId
  dispatchId

WebhookFailedDelivery
  dispatchId
```

Every consumer-side lifecycle update and DLT finalization must target `dispatchId`, never the parent Execution state.

An old DLT message therefore only updates its own old Dispatch row. It cannot alter a newer admin retry Dispatch row, so `dispatchVersion` is removed.

## Write Flows

### Initial scheduler dispatch

1. Redis dedup succeeds.
2. In one DB transaction, create `MarketingActionExecution` and `MarketingActionDispatch(sequence=1, initiatedBy=SYSTEM, status=PENDING)`.
3. Mark that Dispatch `DISPATCHING` and publish a command with `executionId` and `dispatchId`.
4. Producer callback changes only that Dispatch to `DISPATCHED` or `FAILED_FINAL`.

### Consumer and DLT

1. Coupon/Webhook consumer records attempts and success against `dispatchId`.
2. Final command/Webhook failure is published to its existing DLT topic with `dispatchId`.
3. The DLT consumer marks only that Dispatch `FAILED_FINAL`.
4. A duplicated old DLT message either repeats a terminal update harmlessly or becomes a no-op; it never touches a later Dispatch.

### Administrator-approved retry

1. `POST /core/api/v1/marketing-action-executions/{executionId}/retry` targets the latest Dispatch for that Execution.
2. Retry is allowed only when the latest Dispatch is `FAILED_FINAL`.
3. Create a new `MarketingActionDispatch` with the next sequence and `initiatedBy=ADMIN`.
4. Publish a new original command containing the same `executionId` and the new `dispatchId`.
5. Do not replay the DLT message directly.

Prevent double-click/concurrent approval from creating two retries. Lock the parent Execution row while reading the latest Dispatch and inserting the new one, or use an equally explicit atomic strategy. A read-then-insert without a concurrency guard is not acceptable.

## Read API

Keep the existing paths to avoid unrelated frontend cutover:

```text
GET  /core/api/v1/marketing-action-executions/failed
POST /core/api/v1/marketing-action-executions/{executionId}/retry
```

`GET /failed` returns only Executions whose **latest** Dispatch is `FAILED_FINAL`, ordered by that Dispatch's `dltAt` descending. The response must include the current/latest Dispatch summary (`dispatchId`, sequence, initiatedBy, status, attemptCount, failure reason, timestamps) plus the existing target facts.

Do not add an admin UI or AI behavior in this change.

## Migration

Add an explicit forward migration. Do not rely on `ddl-auto=update` as the production cutover.

1. Create `marketing_action_dispatches`.
2. Backfill one `SYSTEM`, `sequence=1` Dispatch for each existing Execution, copying its current lifecycle fields.
3. Drop the old delivery-lifecycle columns from `marketing_action_executions` only after backfill.
4. Keep the existing execution ID so existing command/DLT records can be identified during cutover; code after this change must require `dispatchId` for new lifecycle updates.

The implementation may use a clean local schema for tests, but the migration must be safe for an already-populated table.

## Tests and Acceptance Criteria

Update existing lifecycle tests and add these cases:

1. A qualified action target creates one Execution and initial Dispatch sequence 1.
2. Coupon and Webhook success update only their Dispatch and preserve the parent Execution.
3. Webhook's three automatic attempts increment one Dispatch's `attemptCount`; no per-retry table is created.
4. Coupon invalid input and Webhook final failure reach their existing DLT paths and mark the correct Dispatch `FAILED_FINAL`.
5. Admin retry of a final failure creates Dispatch sequence 2 with `initiatedBy=ADMIN`; Dispatch sequence 1 keeps its DLT time and failure reason.
6. A duplicate DLT for Dispatch 1 cannot alter Dispatch 2.
7. Two concurrent retry approvals yield exactly one Dispatch sequence 2 and one Kafka command.
8. Existing focused scheduler, coupon, Webhook, DLT, repository, and controller tests remain green.

Run:

```bash
cd core-service
./gradlew test
```

Also run `git diff --check`. Do not commit or push; leave the diff for review.

## Out of Scope

- individual HTTP-attempt history table
- automatic DLT redrive
- AI analysis or approval
- real payment/PG/outbox work
- Entry-service changes
