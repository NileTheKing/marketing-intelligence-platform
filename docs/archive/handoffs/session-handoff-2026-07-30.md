# Kafka backlog 전환과 강제 종료 복구 실험

Status: reference (completed, 2026-07-30)

코드: `d5f396b`. 아래는 당시 구현과 실험 결과다. 현재 트랜잭션 경계는
[구매 후처리](../../architecture/fcfs-entry-purchase-transaction-boundary-handoff.md)를 따른다.

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
