# Reconciliation Issue V1 Plan

Status: `active (implemented)`

## Goal

Turn FCFS consistency mismatches from transient logs into reviewable operational
records, while automatically repairing the derived UserSummary projection from
the Purchase ledger during the daily reconciliation run.

```text
Redis FCFS admission count
          vs
persisted Purchase count
          -> ReconciliationIssue
          -> OPEN / ACKNOWLEDGED / RESOLVED

Ghost Purchase (missing Entry)
          -> ReconciliationIssue

Purchase latest CONFIRMED timestamp
          vs
UserSummary.lastPurchaseAt
          -> rebuild UserSummary
          -> ReconciliationIssue only if repair fails

confirmed Purchase count
          -> existing Product stock sync (outside V1 issue scope)
```

## Current State

- `CampaignStockSyncService` logs Redis counter and persisted campaign Purchase
  count mismatches.
- A completed-payment cancellation/refund intentionally keeps the FCFS Redis
  slot, so Redis admission count and confirmed-commercial count are different
  concepts.
- `ReconciliationScheduler` can detect ghost purchases, but its output is log
  and metric oriented rather than an operator-visible issue history.
- `UserSummary` is a derived projection. Its projection-failure Kafka topic is
  failure evidence and observation; the daily DB comparison is the final
  automatic repair safety net.

## V1 Scope

### 1. Durable issue record

Add `ReconciliationIssue` with:

- `id`, `issueType`, `status`
- `campaignActivityId`, optional `purchaseId`, optional `userId`
- expected/observed values and a compact evidence JSON or text field
- first detected, last detected, acknowledged, resolved timestamps
- resolution note

Issue types initially cover:

- `REDIS_PURCHASE_COUNT_MISMATCH`
- `GHOST_PURCHASE`
- `USER_SUMMARY_MISMATCH` (repair failure or an unrepaired mismatch)

`STOCK_SYNC_MISMATCH` is intentionally excluded from V1. The current stock
sync job immediately applies its confirmed-Purchase delta to Product stock, so
it is not an observation-only mismatch path yet. Adding the type before a
separate detector exists would create an empty operational category.

### 2. Idempotent detection

- Generate a stable fingerprint from issue type and business scope, such as
  `issueType + campaignActivityId + purchaseId`.
- Repeated scans update `lastDetectedAt` and observed evidence on the same
  unresolved issue (OPEN or ACKNOWLEDGED) rather than inserting one row per
  scheduler run.
- A resolved issue may be reopened only when the mismatch is observed again.

### 3. Operator workflow and bounded automatic repair

- Add an internal/admin query for OPEN issues and a command to acknowledge or
  resolve one with a note.
- Retain existing scheduler detection, but replace bare mismatch logs with
  issue upsert plus structured logs and metrics.
- The daily scheduler compares database-level summary facts, rebuilds a
  mismatched UserSummary from confirmed Purchase rows, and resolves an existing
  matching issue after successful repair.
- A failed rebuild creates or refreshes `USER_SUMMARY_MISMATCH`; no UI, DLQ, or
  Kafka recovery consumer is introduced.
- Redis, Purchase, and Product are never mutated by this repair.

### 4. Observability

Expose counters/gauges for:

- open issue count by type
- newly detected/reopened issue count
- observed issue age
- reconciliation scan duration

## Non-Goals

- No Slack/PagerDuty integration in V1.
- No AI diagnosis or generic automatic repair outside the bounded UserSummary
  rebuild described above.
- No generic reconciliation framework for every table.
- No FCFS slot reopening.

## Tests

1. The same mismatch across two scans creates one OPEN issue and refreshes it.
2. A resolved issue reopens only after the mismatch recurs.
3. A cancellation-created difference between Redis admission count and
   confirmed Purchase count is not classified as a mismatch; Redis is compared
   with all persisted Purchase rows for admission reconciliation.
4. Ghost Purchase detection creates an issue with the affected purchase scope.
5. Acknowledge/resolve commands preserve the original evidence and audit times.
6. A UserSummary mismatch is repaired from the confirmed Purchase ledger.
7. A failed UserSummary repair creates or refreshes one user-scoped issue.

## Completion Criteria

- Operators can see one durable record per unresolved consistency problem.
- Scheduler repetition does not create duplicate records.
- The only automatic correction is idempotent UserSummary rebuild from the
  Purchase ledger; Redis, Purchase, Product, and Purchase rows are untouched.

## Implementation Record

- `ReconciliationIssue` persists one fingerprinted record for a Redis-Purchase
  count mismatch, an affected Ghost Purchase, or a failed UserSummary repair.
- Detection refreshes unresolved records, and a resolved record reopens only
  when the same fingerprint recurs.
- `GET /core/api/v1/reconciliation-issues` returns OPEN issues. Authenticated
  operators can acknowledge or resolve one through the corresponding PATCH
  endpoints with a mandatory note.
- Both the daily reconciliation scan and the five-minute campaign stock sync
  job use `SchedulerExecutionLock`, preventing duplicate runners across Core
  instances.
- The daily scan keeps the historical Ghost Purchase check and additionally
  compares `UserSummary.lastPurchaseAt` with the latest `CONFIRMED Purchase`.
  Successful mismatches are rebuilt and do not leave an open issue; failed
  rebuilds are recorded as `USER_SUMMARY_MISMATCH`.
- Apply `scripts/migrations/2026-07-28-add-reconciliation-issues.sql` before
  deploying to an existing MySQL database.
