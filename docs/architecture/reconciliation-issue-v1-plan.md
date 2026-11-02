# Reconciliation Issue V1 Plan

Status: `active (implemented)`

구현 기록은 아래 V1 기준이다. 2026-11-02에 합의한 후속 복구 계획은
문서 끝의 별도 절에 있다. 로컬 코드와 일부 통합 검증을 반영했으며 배포는 별도다.

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

## 후속 계획: 실패 토픽 즉시 복구와 일일 대사 (2026-11-02)

Status: `local implementation` — V1에서 복구 consumer를 두지 않기로 한
범위를 확장한 로컬 코드다. 배포는 아직 하지 않았다.
기존 실패 토픽의 메시지는 신규 consumer group에서 earliest로 읽는다. 복구를
3회 시도 후 사용자별로 최종 확인하고, 실패가 계속되면 이슈를 저장한다. 저장 실패 시 offset을
진행하지 않는다. 형식이 잘못된 메시지는 전용 DLT로 발행한 후 진행한다.
2026-11-02 Testcontainers MySQL·Kafka에서 Summary 행 누락 탐지와 실패 메시지
소비를 통한 복구를 검증했다. 복구 전용 메트릭, 메시지 재시도·최종 실패 및
구매·취소 경합의 통합 검증은 아직 완료하지 않았다.

### 목표와 경계

- 원장 저장 성공 후 UserSummary 갱신에 실패하면 기존 실패 토픽을 소비해
  빠르게 재계산한다. 매일 대사는 메시지 누락과 다른 원인의 불일치를 찾는
  안전망으로 유지한다.
- 메시지의 시간이나 값을 재적용하지 않고 `userId`별 현재 CONFIRMED Purchase
  원장에서 마지막 구매 시각을 계산한다. 중복·오래된 메시지에도 같은 원장
  상태로 수렴해야 한다. 구매 취소와 동시 구매도 기존 잠금 경계를 고려한다.
- Summary 행이 없고 회원이 존재하면 행을 생성하고 구매 시각을 복구한다.
  로그인 시각과 RFM 값은 구매 원장에서 복구할 수 없으며 기본값으로 남는다.
  회원 자체가 없거나 메시지가 잘못된 경우 임의 생성하지 않고 실패를 기록한다.

### 구현 순서

1. `UserSummaryService.rebuildPurchaseSummary`를 확장한다. 동일 회원의
   consumer, 대사, 구매·취소가 동시에 실행될 수 있으므로 행 생성 경쟁과
   재계산 중 원장 변경을 직렬화하는 방식을 정하고 기존 경로에도 일관되게
   적용한다. 다른 Summary 필드는 덮어쓰지 않는다.
2. 실패 토픽 전용 consumer group을 만들고 신규 group의 시작 위치를
   `earliest`로 명시한다. 기존 보존 기간 안의 과거 메시지부터 처리하며,
   이미 삭제된 메시지는 복구할 수 없다. 재배포 시에는 기존 committed
   offset부터 이어간다. 메시지의 대상 userId를 중복 제거해 개별 복구한다.
3. 일시 장애는 제한된 횟수와 간격으로 재시도한다. 계속 실패한 사용자와
   오류는 durable한 기존 `USER_SUMMARY_MISMATCH` 이슈에 기록한 뒤에만
   해당 메시지의 offset을 진행한다. 기록 자체가 실패하면 offset을 진행하지
   않고 재전달한다. 읽을 수 없는 메시지도 유실 없이 별도 durable 실패 기록
   또는 DLT로 격리하고, 격리 발행 실패 시 offset을 막는다. 배치 메시지 내
   일부 성공 후 재전달도 안전해야 한다.
4. 일일 대사 조회가 기존 Summary 불일치뿐 아니라 Summary가 없는 구매 회원도
   발견하도록 확장한다. 사용자당 재계산 및 실패 이슈 기록은 같은 경로를
   공유한다. 기존 이슈는 복구 성공 후에만 해소한다.
5. 실패 토픽 소비·복구 성공·실패·최종 격리와 lag를 관찰 가능하게 하고,
   재시도 횟수와 간격은 기존 Kafka 오류 처리 관례를 확인해 정한다.

### 검증

- Kafka/MySQL 통합 테스트에서 원장 커밋 후 집계 실패, 실패 토픽 소비,
  원장 기준 복구와 offset 진행을 확인한다. 이미 보존된 과거 메시지,
  중복 전달, 여러 사용자 중 일부 실패, 재시도와 재시작을 포함한다.
- 복구와 이슈 저장/격리 발행이 각각 실패할 때 메시지를 잃지 않는지,
  이후 복구 성공 시 기존 이슈가 해소되는지 확인한다.
- Summary 행 누락, 동시 생성, 최신 구매와 취소가 복구와 경합하는 경우,
  기존 로그인·RFM 필드 보존 및 일일 대사의 누락 행 탐지를 확인한다.
- 별도 테스트 환경에서 실행한다. 실제 토픽의 보존 기간·기존 group offset과
  배포 순서를 확인하기 전에는 운영 데이터 재처리를 수행하지 않는다.
