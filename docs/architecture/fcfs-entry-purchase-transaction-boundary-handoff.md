# 구매 후처리 트랜잭션 경계

Status: active (implemented and OCI-validated)

Historical VM transaction-boundary verification: 2026-08-03, `bb34774`.
Current code structure checked: 2026-10-21 (documentation review; no new tests or VM run).

## 현재 구조

선착순 결제 명령을 Kafka에서 받은 뒤 구매·참여 기록을 함께 저장한다.
집계 갱신은 별도 트랜잭션으로 처리해 구매 기록의 커밋과 분리한다.

```text
Kafka batch listener
  → FcfsCommandOrchestrator
    → FcfsLedgerPersistenceService: 참여 + 구매를 같은 트랜잭션에 JDBC 벌크 저장
    → UserSummaryService: 별도 트랜잭션으로 집계 갱신 (동기 호출)
    → 새 구매 이벤트 발행
  → 필수 처리 또는 실패 기록 완료 후 listener 반환 → offset commit
```

- 저장 실패 시 일시적인 DB 장애는 Kafka 재시도로 넘기고, 그 외 배치 실패는
  메시지별 트랜잭션으로 처리한다. 최종 재시도·DLT는 Kafka error handler가 담당한다.
- 집계 실패는 전용 실패 토픽에 기록하며, 그 기록도 실패하면 offset 진행을 막는다.
- DB 커밋 후 offset 커밋 전 재전달은 DB unique key로 중복을 방어한다.
- 2026-10-20 JDBC 벌크 적용·배포 확인은 `docs/decisions/db-jpa-integrity-audit-2026-08-11.md`의
  해당 절을 참고한다. 아래 8월 측정값은 당시 코드·환경의 기록이다.
- 이 문서는 Core 후처리 경계를 다룬다. Entry 결제 상태 영속화와 Outbox는 미구현이다.

## 재전달과 복구 경계

Kafka가 durable backlog를 보존한다. DB 커밋 전 장애는 재전달로, DB 커밋 후
offset 커밋 전 장애는 DB unique key 기반 멱등 처리로 수렴시킨다.
행동 로그는 필수 구매 저장과 별개의 부가 경로다.

## 2026-08-03 OCI 회귀 결과

Status: completed on 2026-08-03

검증 환경:

- Oracle VM Docker Compose
- Entry/Core/Axon nginx CPU: `1.5/1.2/0.5`
- 코드: `bb34774`
- 외부 payment `waiting_burst`

정합성 회귀:

- `1,000 users / 1,000 VUs / FCFS 800`: 3회 모두 성공 `800`,
  오류 `0`, Entry/Purchase `800/800`, DB 수렴 `0s`
- `3,000 users / 3,000 VUs / FCFS 800`: 3회 모두 성공 `800`,
  오류 `0`, Entry/Purchase `800/800`, DB 수렴 `0s`
- 최종 broker committed lag `0`
- command DLT와 UserSummary projection-failure 토픽 end offset `0`
- 최종 Hikari pending/timeout `0/0`
- 3,000-VU 세 실행의 reservation p95는
  `5.120s / 5.704s / 4.573s`였다. 외부망 상태와 Entry 응답 지연이
  섞인 회귀 실행이므로 성능 향상 수치로 사용하지 않는다.

강제 종료 복구:

- Core 정지 상태에서 command lag `800`, DB Entry/Purchase `0/0`
- Core가 첫 원장 배치 `20/20`을 커밋한 뒤 `SIGKILL`
- kill 직후 committed offset은 전진하지 않아 20건 모두 재전달 대상
- 재시작 후 Entry/Purchase, distinct user, 원장 pair, UserSummary가 모두
  `800`
- 최종 broker committed lag `0`, command DLT/projection failure `0`

증거:

- `artifacts/load-test/20260803-fcfs-ledger-regression-1000vu-800`
- `artifacts/load-test/20260803-fcfs-ledger-regression-1000vu-800-r2`
- `artifacts/load-test/20260803-fcfs-ledger-regression-1000vu-800-r3`
- `artifacts/load-test/20260803-fcfs-ledger-3000vu-800-r1`
- `artifacts/load-test/20260803-fcfs-ledger-3000vu-800-r2`
- `artifacts/load-test/20260803-fcfs-ledger-3000vu-800-r3`
- `artifacts/load-test/20260803-fcfs-ledger-crash-recovery/result.md`
