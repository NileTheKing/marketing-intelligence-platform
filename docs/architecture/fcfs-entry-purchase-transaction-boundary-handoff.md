# 구매 후처리 트랜잭션 경계

Status: active (implemented and OCI-validated)

Historical VM transaction-boundary verification: 2026-08-03, `bb34774`.
Current code structure checked: 2026-10-27 (local unit and MySQL/Kafka integration tests; no VM run).

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
- Orchestrator는 입력 순서를 유지하고 사전 중복 제거로 메시지 순서를 바꾸지 않는다.
  저장 단계의 기존 구매 조회와 Map으로 배치 내부 중복을 합치며, 개별 복구 실패 시
  원본 배치의 실패 위치를 Kafka error handler에 전달한다.
- 2026-10-20 JDBC 벌크 적용·배포 확인은 `docs/decisions/db-jpa-integrity-audit-2026-08-11.md`의
  해당 절을 참고한다. 아래 8월 측정값은 당시 코드·환경의 기록이다.
- Entry의 선택적 결제 v2는 아래 경계로 연결한다. 기본 비활성화이며 VM 배포 기록은 아니다.

## 결제 v2의 실행 경계

2026-10-27 로컬 구현. 실제 PG, 일반 SHOP 구매의 결제 이관과 VM 활성화는 포함하지 않는다.

```text
Entry 입장 → Redis Lua: UUID 예약, 소유자, 복구 인덱스 저장
          → DB 예약 등록 커밋 → 1차 토큰 응답
prepare   → 소유자 확인 → 같은 예약의 2차 토큰
confirm   → DB 예약 행 잠금, DB 시각으로 결제 처리권 확보
          → DB 트랜잭션 밖 Mock PG 호출
          → 결제 성공 + 예약 완료 + Outbox를 함께 커밋 → 성공 응답
Entry 발행 작업 → Outbox 임대 → DB 트랜잭션 밖 Kafka ACK 대기 → 발행 완료
Core      → 기존 구매, 참여 트랜잭션 → 별도 집계와 이벤트 처리
```

- 공통 모듈은 `libs/payment`다. 모듈을 의존한다고 작업자가 자동 시작되지 않는다.
  Entry의 명시적 설정만 DB 연결과 작업자를 등록한다. 구매와 참여 기록의 소유자는 Core다.
- 새 경로는 `AXON_PAYMENT_V2_ENABLED`와 활동 ID 목록으로 선택한다. v1 Redis 상태가
  존재하는 활동에는 v2 입장을 허용하지 않는다. v1과 v2의 토큰과 Redis 키는 별개다.
- 토큰은 단계별 서명과 Redis TTL을 갖는 자격 증명이다. 사용자 소유권을 DB에서 확인하며,
  토큰 만료가 재고 반환을 결정하지 않는다. 만료와 confirm은 같은 예약 행과 DB 시각으로 경쟁한다.
- Redis 입장 뒤 DB 응답이 불명확하면 자리를 즉시 반환하지 않는다. 만료 후 같은 UUID의
  DB 종료 기록으로 늦은 등록을 막고 반환한다. DB 만료가 확정돼도 Redis 반환 응답을
  확인할 때까지 `release_pending`을 유지한다. 반환 Lua는 소유자가 일치할 때만 차감한다.
- PG 결과가 불명확한 결제는 처리 중으로 남긴다. 복구 작업은 임대 소유권을 교체한 뒤
  결과를 조회한다. 완료와 명확한 실패만 확정하며 오래된 소유자는 상태를 덮어쓸 수 없다.
- 입장 행동 이벤트는 기존과 같이 best effort다. 예약 저장 응답이 유실되고 중복 요청으로
  복구된 경우 행동 이벤트가 누락될 수 있다. 결제와 구매 기록의 보장과 분리한다.
- 기본 Mock PG는 payment ID로 결정적 합성 승인을 반환한다. 재기동 후 lookup도 합성
  성공을 반환하는 시뮬레이션이며 실제 외부 승인 조회를 구현한 것은 아니다.
- Outbox는 한 건씩 임대하고 ACK를 기다린다. 발행 후 응답 유실은 같은 event ID와 payload로
  재발행한다. Core는 기존 업무 unique key로 중복을 막고, v2 메시지는 저장된 가격과
  수량, 통화, 합계를 확인해 구매 가격에 반영한다. legacy 메시지의 가격 정책은 유지한다.
- 메시지 timestamp는 최초 DB 결제 처리권 확보 시각이다. PG 승인 시각이 아니다.
  제안 예약 기한은 Entry 호스트 시각 기준 5분이며 호스트와 DB 시각 동기화가 필요하다.
- 자동 작업은 예약 복구와 만료, 반환, 결제 복구를 5초 간격으로 제한된 건수만 처리한다.
  Outbox는 별도 100ms 간격에 최대 50건을 처리한다. 두 스케줄은 명시적 스레드 풀로 분리한다.
  이는 처리량 측정값이 아니며, 신규 DB 비용을 포함한 admission과 confirm 부하 측정은 별도다.

로컬 확인(2026-10-27): Core 전체 362개 중 353개 통과, 선택 실행 벤치마크 8개와
외부 Webhook smoke 1개는 skip했다. Entry 전체 84개는 모두 통과했다. 두 서비스
실행 JAR 빌드와 Compose 설정 검사를 통과했다. 격리 MySQL, Redis, Kafka에서 예약
등록 응답 유실, 만료 경쟁, 이전 소유자 차단, 반환 응답 유실과 Outbox 중복 발행을
확인했다. MockMvc로 기존 HTTP 계약을, 실제 Boot 문맥으로 기능 off/on 기동을 확인했다.
VM 배포와 신규 경로 부하 측정은 하지 않았다.

활성화, 중단과 보존 조건은 [운영 절차](../operations/oracle-compose-baseline-runbook.md#결제-v2-활성화-조건-미배포)를 따른다.

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
