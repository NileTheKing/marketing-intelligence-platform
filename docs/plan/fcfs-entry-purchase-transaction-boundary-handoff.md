# FCFS Entry–Purchase 트랜잭션 경계 개선 Handoff

Status: active (implemented and OCI-validated)

Last verified: 2026-08-03
Verified code baseline: `bb34774` (`codex/kafka-native-backlog`)

## 이 문서의 역할

다음 구현자는 코드베이스 전체를 탐색하지 말고 이 문서와 아래의 지정 파일만
읽고 작업한다.

이 문서는 FCFS Kafka 수신 이후의 트랜잭션 경계, 실패 격리와 UserSummary
projection 실패 기록 작업의 현재 정본이다.

- 구현자는 이 문서의 `최소 열람 순서`에 지정된 코드만 먼저 확인한다.
- 다른 계획 문서와 포트폴리오 문서는 읽지 않는다.
- 구현 중 지정 파일 밖의 호출 관계를 확인해야 할 때만 `rg`로 해당
  심볼의 직접 사용처를 추가 확인한다.
- 구현 완료 범위는 로컬 통합 테스트와 `core-service` 전체 테스트까지다.
  Oracle VM 접속과 부하 테스트는 수행하지 않는다.
- 큐 제거 실험과 수치는 `docs/plan/session-handoff-2026-07-30.md`가 정본이다.
- 전체 구조는 `docs/architecture-map.md`가 정본이다.
- 두 문서와 이 문서가 충돌하면 현재 코드를 다시 확인하고 충돌을 보고한다.

## 1. 작업 목표

### 작업 A — FCFS 원장과 projection의 경계 정리

1. FCFS의 `CampaignActivityEntry`와 `Purchase`를 하나의 물리 트랜잭션으로
   저장한다.
2. 배치 저장 실패 시 메시지별 트랜잭션으로 재시도하여 정상 메시지는
   보존하고, 최종 실패 메시지만 command DLT로 보낸다.
3. `UserSummary`는 원장 트랜잭션 밖에서 갱신한다.
4. UserSummary 갱신 실패는 전용 Kafka 실패 토픽에 동기적으로 기록한다.
5. 구매 행동 로그는 Entry–Purchase 커밋이 끝난 뒤 발행한다.

## 2. 최소 열람 순서

### 먼저 읽을 파일

1. `core-service/src/main/java/com/axon/core_service/commandprocessing/CampaignActivityConsumerService.java`
2. `core-service/src/main/java/com/axon/core_service/commandprocessing/CampaignActivityCommandDispatcher.java`
3. `core-service/src/main/java/com/axon/core_service/commandprocessing/FirstComeFirstServeStrategy.java`
4. `core-service/src/main/java/com/axon/core_service/service/CampaignActivityEntryService.java`
5. `core-service/src/main/java/com/axon/core_service/service/purchase/PurchaseHandler.java`
6. `core-service/src/main/java/com/axon/core_service/service/purchase/PurchaseService.java`
7. `core-service/src/main/java/com/axon/core_service/service/UserSummaryService.java`
8. `core-service/src/main/java/com/axon/core_service/domain/campaignactivityentry/CampaignActivityEntry.java`
9. `core-service/src/main/java/com/axon/core_service/domain/purchase/Purchase.java`
10. `core-service/src/main/java/com/axon/core_service/repository/CampaignActivityEntryRepository.java`
11. `core-service/src/main/java/com/axon/core_service/repository/PurchaseRepository.java`
12. `common-messaging/src/main/java/com/axon/messaging/topic/KafkaTopics.java`

### 해당 작업을 할 때만 읽을 파일

- 행동 로그:
  `core-service/src/main/java/com/axon/core_service/service/behavior/BackendEventPublisher.java`
- 기존 테스트:
  `PurchaseFlowIntegrationTest.java`, `CampaignActivityEntryRetryTest.java`,
  `PurchaseHandlerTest.java`, `CampaignActivityCommandDispatcherTest.java`,
  `CampaignActivityConsumerServiceTest.java`

그 밖의 문서는 구현 도중 실제 충돌이 발견될 때만 연다.

## 3. 현재 코드에서 확인된 문제

현재 FCFS 배치 흐름은 다음과 같다.

```text
Kafka batch listener
  -> CampaignActivityCommandDispatcher
  -> FirstComeFirstServeStrategy
  -> CampaignActivityEntryService.upsertBatch          (바깥 Tx)
      -> Entry saveBatch REQUIRES_NEW                  (별도 Tx)
      -> PurchaseBatchRequestedEvent
          -> PurchaseHandler BEFORE_COMMIT
              -> Purchase createPurchaseBatch REQUIRES_NEW
              -> UserSummary recordPurchaseBatch       (바깥 Tx 참여)
              -> CampaignActivityApprovedEvent
```

이 구조에는 다음 문제가 있다.

1. 하나의 FCFS 성공 사실인 Entry와 Purchase가 서로 다른 물리
   트랜잭션에 저장된다.
2. UserSummary는 projection인데 바깥 Entry 트랜잭션에 참여한다.
   실제 Spring 프록시 환경에서는 예외가 잡혀도 트랜잭션이
   rollback-only가 되어 커밋 시 `UnexpectedRollbackException`이 발생할 수
   있다.
3. `PurchaseHandlerTest`는 mock 호출 흐름만 검증한다. 실제 프록시,
   rollback-only, 커밋 시점은 증명하지 않는다.
4. 기존 배치 로직은 새 Entry에 대해서만 Purchase 이벤트를 만든다.
   따라서 과거 실패로 `Entry 있음 / Purchase 없음` 상태가 남아 있으면
   재전달만으로 복구되지 않는다.
5. Dispatcher는 전략 예외를 잡아 타입 배치 전체를 command DLT로 보낸다.
   일부 메시지가 이미 개별 트랜잭션으로 성공한 뒤라면 정상 메시지까지
   DLT로 섞일 수 있다.
6. 존재하지 않는 activity 메시지는 현재 FCFS 전략에서 로그만 남기고
   건너뛴다. 오프셋은 진행하므로 실패 사실이 남지 않는다.

## 4. 확정한 목표 구조

```text
Kafka batch listener
  -> 비트랜잭션 orchestration
      -> Entry + Purchase 배치 트랜잭션
          성공: 커밋
          실패: 전체 롤백
              -> 메시지별 Entry + Purchase 트랜잭션 재시도
              -> 최종 실패 메시지만 command DLT
      -> 성공 메시지의 UserSummary를 별도 트랜잭션으로 갱신
          실패: 전용 projection-failure 토픽에 동기 기록
      -> 새로 생성된 Purchase의 행동 로그 발행
  -> 모든 필수 처리/실패 기록이 끝난 뒤 listener 반환
  -> Kafka batch offset commit
```

### 트랜잭션 원칙

- orchestration 계층에는 `@Transactional`을 붙이지 않는다.
- Entry와 Purchase를 저장하는 Spring bean의 public 메서드가 트랜잭션
  경계다.
- 배치 메서드와 단건 메서드는 모두 Spring 프록시를 통해 호출한다.
  같은 클래스 내부 호출로 새 트랜잭션을 기대하지 않는다.
- 저장 오류는 트랜잭션 내부에서 삼키지 않는다. `flush`까지 수행하여
  제약 위반을 트랜잭션 메서드가 반환하기 전에 드러낸다.
- UserSummary 갱신은 Entry–Purchase 메서드가 정상 반환하여 커밋된 뒤
  호출한다.

## 5. Entry–Purchase 멱등 처리

두 테이블 모두 `(campaign_activity_id, user_id)` unique constraint가 현재
코드에 선언되어 있다.

- Entry:
  `uk_campaign_activity_entry_activity_user`
- Purchase:
  `uk_campaign_purchase_user`

한 메시지를 처리할 때 두 테이블을 각각 조회하고 다음 네 상태를 모두
수렴시킨다.

| 기존 상태 | 처리 결과 |
|---|---|
| Entry 없음 / Purchase 없음 | 둘 다 생성 |
| Entry 있음 / Purchase 있음 | 둘 다 유지, 성공 처리 |
| Entry 있음 / Purchase 없음 | Purchase만 생성 |
| Entry 없음 / Purchase 있음 | Entry만 생성 |

주의:

- “Entry가 새로 생겼는가”를 Purchase 생성 조건으로 사용하지 않는다.
- DB unique constraint는 마지막 방어선으로 유지한다.
- 동시 삽입 충돌로 배치가 실패하면 바깥 orchestration이 단건 경로로
  재조회·재시도하여 기존 레코드를 정상 상태로 받아들인다.
- projection 갱신 대상은 이번 메시지에서 Purchase가 새로 생성되었는지와
  무관하게, 원장 처리에 성공한 사용자다.
- 행동 로그는 중복 발행을 줄이기 위해 이번 처리에서 Purchase가 새로
  생성된 경우에만 발행한다.

## 6. 배치 실패와 DLT 규칙

1. 먼저 전체 FCFS 배치를 하나의 Entry–Purchase 트랜잭션으로 처리한다.
2. 실패하면 그 배치는 전부 롤백한다.
3. 원본 메시지를 하나씩 별도 트랜잭션으로 재시도한다.
4. 단건 처리까지 실패한 메시지만
   `KafkaTopics.CAMPAIGN_ACTIVITY_COMMAND_DLT`로 보낸다.
5. DLT 전송은 반드시 `.join()` 등으로 성공을 확인한다.
6. DLT 전송 성공 후에는 해당 메시지를 격리된 것으로 보고 listener가
   진행할 수 있다.
7. DLT 전송 자체가 실패하면 예외를 listener까지 전파하여 offset이
   진행하지 않게 한다.

Dispatcher가 이 예외를 다시 잡아 이미 성공한 메시지까지 배치 전체 DLT로
보내면 안 된다. 전용 예외를 두고 Dispatcher가 그대로 재던지거나, 같은
효과를 내는 명시적 계약을 둔다.

존재하지 않는 activity도 조용히 건너뛰지 않는다. 해당 메시지를 단건
실패로 분류하여 command DLT에 남긴다.

## 7. UserSummary projection 실패 처리

UserSummary는 구매 원장이 아니라 재생성 가능한 projection이다.

### 정상 경로

- Entry–Purchase 커밋 후 `UserSummaryService.recordPurchaseBatch`를
  호출한다.
- 이 메서드는 별도 물리 트랜잭션에서 실행된다.
- 실패해도 이미 커밋된 Entry와 Purchase는 롤백하지 않는다.

### 실패 경로

전용 토픽과 메시지를 추가한다.

- 권장 토픽 상수:
  `USER_SUMMARY_PROJECTION_FAILED =
  "axon.projection.user-summary.failed"`
- 실패 메시지에 필요한 최소 정보:
  - schema version
  - 실패한 user ID 목록
  - 관련 campaign activity ID 목록 또는 `(activityId, userId)` 키 목록
  - 실패 시각
  - 예외 타입과 짧은 원인

실패 메시지에는 stack trace 전체를 넣지 않는다. UserSummary는 Purchase를
기준으로 `rebuildPurchaseSummary(userId)` 할 수 있으므로 재구축 대상을
식별할 키가 핵심이다.

- 실패 토픽 전송은 동기적으로 성공을 확인한다.
- 전송 성공: 원장과 실패 이력이 모두 보존됐으므로 listener 진행 가능.
- 전송 실패: 예외를 listener까지 전파하여 offset 진행 금지.
- 이 작업에서는 실패 토픽 consumer나 자동 복구 worker를 만들지 않는다.

재전달 시 Entry–Purchase가 이미 존재하더라도 UserSummary 갱신은 다시
시도해야 한다. 그렇지 않으면 “원장은 복구됐지만 projection은 영원히
누락된 상태”가 된다.

## 8. 행동 로그 발행 시점

`CampaignActivityApprovedEvent`를 통한 commerce 행동 로그는
Entry–Purchase 트랜잭션이 커밋된 뒤 발행한다.

- 현재의 `BackendEventPublisher` 비동기·best-effort 정책은 유지한다.
- 로그 발행 실패로 Entry, Purchase, UserSummary를 롤백하지 않는다.
- 이벤트 이름 변경이나 Outbox 도입은 이번 작업 범위가 아니다.
- 로그 전달 보장이 요구사항으로 올라갈 때만 Outbox를 별도 검토한다.

## 9. 구현 시 정리할 기존 구성

FCFS 배치 경로에서 아래 연결을 제거한다.

- `PurchaseBatchRequestedEvent`
- FCFS 배치의 `@TransactionalEventListener(BEFORE_COMMIT)` 의존
- Entry 전용 `REQUIRES_NEW`와 Purchase 전용 `REQUIRES_NEW`를 조합한
  분리 저장

단, `PurchaseHandler`의 SHOP 단건 경로 등 FCFS 밖의 호출자는 먼저
`rg`로 확인하고 동작을 보존한다. 이번 작업으로 사용되지 않게 된 클래스,
메서드, 테스트만 제거한다. 인접 도메인까지 리팩터링하지 않는다.

권장 역할 분리는 다음과 같다. 실제 이름은 기존 패키지 스타일에 맞춰도
된다.

- FCFS orchestration bean: 배치 시도, 단건 fallback, DLT, projection,
  행동 로그 순서를 담당
- Entry–Purchase persistence bean: 배치/단건 원장 트랜잭션만 담당
- projection failure publisher: 전용 Kafka 실패 기록과 전송 실패 전파

## 10. 필수 테스트

mock 테스트만 추가하고 끝내지 않는다. 실제 Spring 트랜잭션 프록시와 DB를
사용하는 통합 테스트가 핵심이다.

### 원장 트랜잭션

- Entry와 Purchase가 함께 커밋된다.
- Entry 저장 실패 시 Purchase도 남지 않는다.
- Purchase 저장 실패 시 Entry도 남지 않는다.
- 20개 중 1개가 실패하면 정상 19개의 Entry–Purchase pair는 커밋되고
  실패 1개만 command DLT로 간다.
- 네 가지 기존 상태가 모두 목표 상태로 수렴한다.
- 동일 메시지 재전달 후 Entry와 Purchase가 각각 1개다.
- 존재하지 않는 activity 메시지가 조용히 유실되지 않고 DLT로 간다.
- command DLT 전송 실패가 listener 호출 경계 밖으로 전파된다.

### projection과 로그

- UserSummary 실패 후에도 Entry와 Purchase는 커밋 상태다.
- UserSummary 실패 시 command DLT가 아니라 projection-failure 토픽만
  사용한다.
- projection-failure 전송 실패는 listener 경계까지 전파된다.
- 재전달 시 기존 Purchase여도 UserSummary 갱신을 다시 시도한다.
- 행동 로그 이벤트는 원장 커밋 이후에만 발행된다.
- 기존 Purchase의 단순 재전달에서는 행동 로그를 다시 발행하지 않는다.

### 회귀

- `core-service` 전체 테스트 성공
- 기존 SHOP purchase 테스트 성공
- 기존 Kafka batch listener 및 command dispatcher 테스트 성공

## 11. OCI 회귀 검증

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

아래는 이 검증을 수행하기 전에 확정한 판정 기준으로 기록을 보존한다.

이 절은 구현 에이전트의 작업 범위와 완료 조건이 아니다. 로컬 구현과 테스트가
끝난 뒤, VM 접근 권한과 안정적인 부하 발생 환경을 가진 별도 작업에서 수행한다.

기존과 같은 FCFS 800 시나리오로 회귀 확인한다.

- Redis 성공 수: 800
- distinct Entry: 800
- distinct Purchase: 800
- 최종 consumer group lag: 0
- Hikari pending/timeout 없음

강제 종료 복구도 반복한다.

- 커밋된 Entry와 Purchase 수가 항상 같다.
- 재시작 후 최종 distinct Entry/Purchase가 800/800이다.
- 최종 broker committed lag가 0이다.

이 검증은 정합성과 회귀 확인이다. 이전 A/B 결과만으로 처리량 향상이나
속도 개선을 주장하지 않는다.

## 12. 이번 작업의 명시적 비범위

- bounded in-memory queue, pause/resume backpressure
- Inbox
- Outbox
- 자동 UserSummary 복구 worker
- 역방향 자동 대사/수정
- Webhook 전달 구조 변경
- unsupported campaign type 전반의 정책 변경
- k3s 전환
- 외부 네트워크가 불안정한 3,000 VU 결과의 재해석
- 포트폴리오·블로그 문구 수정
- Cohort/RFM 등 전역 스케줄러 락

Inbox가 없는 이유는 Kafka가 이미 durable backlog이고, listener 반환 전에
필수 DB 처리 또는 실패 기록을 끝내기 때문이다. DB 커밋 전 장애는 Kafka
재전달로, DB 커밋 후 offset 커밋 전 장애는 DB unique key 기반 멱등 처리로
수렴시킨다.

## 13. 완료 보고 형식

구현자는 완료 시 아래 순서로 보고한다.

1. 변경한 트랜잭션 경계: before → after
2. 생성·수정·삭제한 파일 목록
3. 위 필수 테스트별 결과
4. `core-service` 전체 테스트 결과
5. OCI에서 추가로 검증해야 할 항목
6. 남은 실패 가능성과 비범위
7. 코드와 기존 문서 사이에서 발견한 충돌

검증 전에는 `docs/architecture-map.md`, 포트폴리오 T 파일, 블로그의 현재
구조 설명을 구현 완료 상태로 고쳐 쓰지 않는다.
