# Session Handoff - 2026-07-30

Status: reference (completed)

Current implementation replacement:
`docs/plan/fcfs-entry-purchase-transaction-boundary-handoff.md`.
This file preserves the queue-removal A/B and first crash-recovery evidence;
its Purchase event/transaction description is not current.

## Current Code State

- Local branch: `codex/kafka-native-backlog`
- Code commit: `d5f396b`
- Oracle VM branch/SHA: `codex/kafka-native-backlog` / `d5f396b`
- Core full test suite: `BUILD SUCCESSFUL`
- The local `README.md` has an unrelated unstaged user change. Do not revert or
  stage it with this work.

## Implemented Boundary

- Removed `CampaignActivityCommandBuffer` and the Purchase in-memory queue.
- Spring Kafka now uses a batch listener with `max.poll.records=20`,
  `enable.auto.commit=false`, and `AckMode.BATCH`.
- Entry and Purchase durable work completes before listener return.
- Entry batch publication preserves Purchase batching through
  `PurchaseBatchRequestedEvent`.
- Command and Purchase DLT sends are awaited. A DLT send failure propagates
  instead of allowing offset progress.

## Validation

Controlled A/B:

- Scenario: external payment, 1,000 users / 1,000 VUs / FCFS 800.
- Before: `49ca09f`.
- After: `d5f396b`.
- Both measured runs on both branches reached Redis, Entry, and Purchase
  `800/800`, with `0s` DB convergence and no Hikari pending/timeout.
- Latency varied across the two-run samples; do not claim a speedup.

Crash injection:

- Core stopped with Kafka group lag `800` and DB `0/0`.
- Abrupt kill left DB `360/360` but committed offset progress of `340`.
- The 20 durable-but-uncommitted records were redelivered after restart.
- Final Entry/Purchase and distinct-user counts were `800/800`; group lag was
  `0`.

Evidence:

- `artifacts/load-test/20260730-152209-queue-before-main49ca-controlled`
- `artifacts/load-test/20260730-153943-queue-after-d5f396b-controlled`
- `artifacts/load-test/20260730-queue-crash-injection-d5f396b/result.md`

## Oracle VM State

- DB backup:
  `/home/ubuntu/backups/axon-pre-queue-ab-20260730.sql`
- Applied migrations:
  - `2026-07-16-add-rfm-audience-segments.sql`
  - `2026-07-28-add-purchase-status-and-campaign-only-product.sql`
  - `2026-07-28-add-reconciliation-issues.sql`
- Host nginx:
  - keep `worker_connections 4096`
  - keep `worker_rlimit_nofile 16384`
  - keep systemd/master open-file limit `16384`
  - upstream `keepalive 256` removed
  - host `listen ... backlog=4096` removed
- Axon nginx container was not changed. Its current VM config still has
  `worker_connections 2048` and `listen 80 backlog=4096`.

## Remaining Decision

- Path B satisfies the current 800-result gate and is the preferred design.
- Do not implement bounded queue + pause/resume unless a larger controlled Core
  workload shows that listener-owned persistence cannot meet throughput or
  DB-pool constraints.
- `kafka_consumer_fetch_manager_records_lag_max` can remain stale after the
  broker group has converged. Use `kafka-consumer-groups --describe` as the
  committed-lag authority for final convergence.
- The 3,000-user external run was not a valid Core A/B baseline: ingress
  timeouts left DB at `606`, `666`, and `589` despite Redis reaching 800.
  Preserve those artifacts as a rejected scenario, not queue-loss evidence.
