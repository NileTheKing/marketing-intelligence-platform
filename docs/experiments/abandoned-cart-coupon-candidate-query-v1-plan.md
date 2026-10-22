# Abandoned Cart Coupon Candidate Query V1

> Status: active

## Goal

장바구니에 상품을 담은 뒤 30분 안에 같은 상품을 구매하지 않은 사용자에게 쿠폰을 발급한다.
행동 로그는 분석용 Elasticsearch로 계속 보내되, 시간 경과 뒤 실행할 쿠폰 후보는 Core MySQL에 별도로 유지한다.

## Why a Candidate Table

"30분 동안 구매하지 않음"은 이벤트가 아니라 시간 조건이다. 장바구니 추가 시점에 후보를 남기고,
구매 또는 장바구니 제거 이벤트가 오면 후보를 취소한다. 워커는 시각이 지난 후보만 찾아 기존 쿠폰
명령 처리 경로로 보낸다.

후보 식별 단위는 `캠페인, 사용자, 상품`이다. 현재 장바구니 화면은 영속 장바구니를 제공하지 않으며,
쿠폰도 사용자에게 지급되므로 별도 cart aggregate는 V1 범위에 넣지 않는다.

캠페인은 기존의 활성 `MarketingAction(COUPON)`에 연결한다. Activity 등록/상태와의 통합은
`docs/architecture/campaign-activity-run-review-v1-plan.md`의 Activity Settings Integration을 따른다.
이 마이그레이션은
`marketing_actions`와 delivery history 마이그레이션을 적용한 뒤 실행한다.

## Flow

```text
CART_ITEM_ADDED
  -> 후보를 SCHEDULED로 저장 또는 dueAt 갱신
CART_ITEM_REMOVED / PURCHASE
  -> 아직 SCHEDULED인 후보를 CANCELLED로 변경

scheduled worker
  -> SCHEDULED and dueAt <= now 후보 500건 조회
  -> PROCESSING으로 조건부 claim
  -> Purchase 재확인
  -> 기존 MarketingActionExecution / Dispatch / Kafka coupon command 재사용
  -> Kafka 수락을 확인한 뒤 남은 due 후보가 없을 때까지 다음 500건 반복
```

`CART_ITEM_ADDED`와 `CART_ITEM_REMOVED`는 로그인 사용자와 기존 공식 속성 `productId`가 있어야 한다.
익명 행동과 다른 일반 분석 이벤트는 Elasticsearch로만 전달되고 이 후보 상태를 바꾸지 않는다.

구매 재확인은 Kafka 이벤트의 도착 순서가 뒤바뀌어 구매 취소 이벤트보다 후보 claim이 먼저 일어나는
경우를 막는다.

## Query Shape

```sql
SELECT candidate.*, campaign.*, action.*
FROM abandoned_cart_coupon_candidates candidate
JOIN abandoned_cart_coupon_campaigns campaign ON campaign.id = candidate.campaign_id
JOIN marketing_actions action ON action.id = campaign.marketing_action_id
JOIN campaign_activities activity ON activity.id = campaign.campaign_activity_id
JOIN campaigns parent ON parent.id = activity.campaign_id
WHERE candidate.status = 'SCHEDULED'
  AND candidate.due_at <= ?
  AND activity.status = 'ACTIVE'
  AND activity.coupon_delivery_mode = 'ABANDONED_CART'
  AND activity.start_date <= ? AND activity.end_date >= ?
  AND (parent.start_at IS NULL OR parent.start_at <= ?)
  AND (parent.end_at IS NULL OR parent.end_at >= ?)
ORDER BY candidate.due_at, candidate.id
LIMIT 500;
```

현재 repository는 `@EntityGraph`로 캠페인과 쿠폰 액션도 함께 읽는다. 위 SQL은 그 조회 형태를
요약한 것이다. ID만 읽는 SQL의 측정값을 현재 repository 성능으로 사용하지 않는다.

2026-10-19에는 Activity 연관관계와 상태/기간 조건을 추가했다. 아래 2026-10-15의 SQL과
발급량 실험은 이 변경 전의 쿼리를 대상으로 한 역사적 결과다. 새 조건을 포함한 성능은
다시 측정하지 않았으며, 기존 2.17배 수치를 현재 코드의 재측정 결과로 취급하지 않는다.

인덱스는 `idx_abandoned_cart_candidate_campaign_due(campaign_id, status, due_at, id)`이다.
현재 MySQL 실행계획은 campaign에서 candidate로 조인하므로, 조인 키와 상태를 먼저 좁히고
실행 시각을 인덱스 조건으로 거른다. 여러 캠페인 사이의 전체 정렬은 남는다.
OFFSET 페이지네이션은 상태가 계속 바뀌는 작업 큐에서
누락 또는 중복을 만들 수 있어 사용하지 않는다.

## Benchmark Boundary

MySQL Testcontainers에서 인덱스 없음/있음이 같은 후보 결과를 반환하는지 비교한다.

- `EXPLAIN ANALYZE`로 scan 및 sort 여부 확인
- 후보 조회 p50/p95와 overdue 후보 수 기록
- 후보 생성 및 상태 전이 쓰기 비용도 함께 기록

벤치마크가 정한 지연 목표를 실제로 넘기지 않으면, 이 기능을 성능 장애 해결로 주장하지 않는다.
워크로드 확대나 워커 증설은 이 V1 범위 밖이다.

## Historical ID-Only Microbenchmark (2026-10-14)

아래 수치는 `SELECT id`만 실행한 별도 측정이다. 현재 repository의 조인 조회 성능이나
워커 처리 시간을 뜻하지 않는다.

- Dataset: 1,000,000 candidate rows. Of these, 10,000 were due and `SCHEDULED`; each read returns the oldest 500.
- Read lookup, five runs: median `149ms -> 1ms` with `idx_abandoned_cart_candidate_due(status, due_at, id)`.
- `EXPLAIN ANALYZE`: without the index, MySQL scanned 1,000,000 rows and sorted 10,000 matches. With the index, it used a covering range scan and read 500 rows for the limit.
- Write tradeoff, five 10,000-row batches: candidate inserts had no stable material difference, while `SCHEDULED -> CANCELLED` state updates had a higher median (`41ms -> 75ms`).
- This is a local MySQL query-shape measurement. It excludes Kafka publication, coupon processing, and HTTP latency.

## Historical ID-Only 5M Microbenchmark (2026-10-15)

아래 수치도 ID 조회에만 해당한다. 초기 seed는 `row % 100`으로 상태를, `row % 20`으로
캠페인을 정해 due 후보 50,000건이 한 캠페인에 몰렸다. 여러 캠페인에 due 후보가 분산된
상황의 증거로 사용하지 않는다.

- Dataset: 5,000,000 candidate rows across 20 campaigns. Of these, 50,000 were due and `SCHEDULED`; each read returns the oldest 500.
- Read lookup, five runs: median `799ms -> 1ms` with `idx_abandoned_cart_candidate_due(status, due_at, id)`.
- `EXPLAIN ANALYZE`: without the index, MySQL scanned 5,000,000 rows, filtered 50,000 matches, and sorted them before the limit. With the index, it used a covering range scan and read 500 rows for the limit.
- Write tradeoff, five 10,000-row batches: candidate insert median was `1,229ms -> 1,352ms`; `SCHEDULED -> CANCELLED` was `41ms -> 76ms`.
- This verifies the due-candidate selection boundary only. Kafka publication and coupon issuance throughput require a separate end-to-end measurement.

## Joined Query Retest (2026-10-15, completed)

- 첫 조인 재측정: 인덱스 없음 중앙값 6,616ms, 있음 6,450ms. 두 실행계획 모두 캠페인부터
  시작해 `uk_candidate(campaign_id, user_id, product_id)`로 전체 후보를 읽고 정렬했다.
- 이때 campaign 테이블의 예상 행 수는 1, 실제는 20이었다. 따라서 이 결과만으로
  `(status, due_at, id)`의 컬럼 순서가 잘못됐다고 결론 내리지 않는다.
- 재측정 준비: 캠페인 배정을 `(row / 100) % 20`으로 바꿔 상태 비율과 분리했다.
  세 조인 테이블 모두 `ANALYZE TABLE` 성공을 확인하고, 데이터 분포와 인덱스 cardinality를
  `dataset-and-statistics.txt`에 저장한다.
- 100,000건 확인: 같은 전체 필드 조인 조회에서 중앙값 24ms -> 4ms. 인덱스를 추가하면
  candidate range scan으로 시작하고 500건만 읽은 뒤 campaign/action을 PK로 조인했다.
- 같은 준비 조건의 첫 5,000,000건 조인 재측정도 중앙값 13,139ms -> 12,680ms로 개선이
  거의 없었다. 기존 인덱스는 선택되지 않았다. 통계 갱신만으로 해결되지 않았다.
- 모든 조회 측정은 JDBC 결과 수신까지이며 JPA 객체 생성, Kafka 발행, 쿠폰 발급은 제외한다.
  쓰기 측정은 별도의 빈 테이블에서 10,000건씩 누적하는 테스트로, 500만 건 테이블의
  쓰기 성능을 직접 측정한 값은 아니다.

## Same-Query Index Order Comparison (2026-10-15)

- MySQL 8.0.46, 후보 5,000,000건, 캠페인 20개. 캠페인별 전체 250,000건과 due 후보
  2,500건으로 분산했다. 전체 due 후보 50,000건 중 오래된 500건을 조회한다.
- 전체 필드와 조인을 포함한 SQL은 세 비교군에서 동일하다. 각 비교군에서 buffer warm-up
  1회 뒤 5회 측정했다. 반환된 후보 ID와 순서가 같은지도 assertion으로 확인했다.
- 세 조인 테이블의 통계를 갱신했다. 실행계획을 강제하는 힌트는 사용하지 않았다.
- 데이터 입력 시간을 줄이기 위해 이 실행부터 모든 비교군에 JDBC
  `rewriteBatchedStatements=true`를 적용했다. 이전 실행의 쓰기 시간과 직접 비교하지 않는다.

| 측정 | 조회용 인덱스 없음 | 기존 `(status, due_at, id)` | 변경 `(campaign_id, status, due_at, id)` |
| --- | --- | --- | --- |
| 조인 조회 5회, ms | 12386, 13221, 14348, 14635, 12697 | 13231, 13402, 12329, 13869, 13391 | 442, 407, 419, 382, 432 |
| 조인 조회 중앙값 | 13,221ms | 13,391ms | 419ms |
| 10,000건 INSERT 중앙값 | 135ms | 183ms | 167ms |
| 10,000건 상태 변경 중앙값 | 52ms | 115ms | 130ms |

현재 코드의 기존 인덱스 대비 조회 시간은 약 96.9% 줄었다. 실행계획의 차이는 다음과 같다.

- 기존: `uk_candidate(campaign_id, user_id, product_id)`에서 캠페인당 250,000건씩 총
  5,000,000건을 읽고, 상태와 시각으로 50,000건을 남긴 뒤 정렬했다.
- 변경: `idx_abandoned_cart_candidate_campaign_due`에서 캠페인과 상태로 조회하고 시각을
  index condition으로 적용했다. candidate iterator 출력은 캠페인당 2,500건씩 총
  50,000건이었다. 정렬은 여전히 수행한다. 인덱스 leaf entry 50,000개만 방문했다는 뜻은 아니다.
- 조인과 전체 필드 조회를 유지하므로 covering index 측정이 아니다. JPA 객체 생성과
  실제 쿠폰 처리 지연까지 줄었다고 주장하지 않는다.
- 쓰기 측정은 별도 테이블에 10,000건씩 5회 누적한 결과다. 상태 변경 비용 증가는 관측됐지만,
  대규모 이력 테이블에 동시 쓰기를 가한 처리량은 아직 측정하지 않았다.

결과 경로: `build/benchmark/abandoned-cart-joined-index-order-5m/`

- `campaign-leading-index-comparison.md`: 세 비교군의 원시 측정값
- `abandoned-cart-candidate-explain-*.txt`: 각 비교군의 EXPLAIN ANALYZE
- `dataset-and-statistics.txt`: 실제 데이터 분포와 통계

새 DB는 갱신된 `scripts/migrations/2026-10-14-add-abandoned-cart-coupon-candidates.sql`을 사용한다.
기존 상태 우선 인덱스가 있는 DB만 `scripts/migrations/2026-10-15-replace-abandoned-cart-candidate-due-index.sql`로 교체한다.
운영 DB에는 이번 작업에서 적용하지 않았다.

## Issuance Deadline Experiment (completed, 2026-10-15)

가정: 처리 가능한 장바구니 후보 50,000건이 쌓였을 때, 단일 스케줄러가 500건씩 반복해서
기존 Kafka 쿠폰 처리 경로로 넘기고 600초 안에 사용자 쿠폰 저장까지 완료해야 한다.
이는 적체 해소 목표이며, 실제 고객의 장바구니 추가 시점부터 40분 이내 도착했다는 뜻은 아니다.

- A: PK와 업무 중복 방지 UNIQUE는 유지하고, 후보 조회용 추가 인덱스는 제거한다.
- B: 동일한 데이터와 SQL에 `(campaign_id, status, due_at, id)`를 추가한다.
- 후보 이력 5,000,000건, 캠페인 20개, 처리 대상 50,000건. 다른 후보가 실험 중 새로 due가
  되지 않게 시각을 설정한다. 처리 대상에는 유효한 쿠폰과 중복되지 않는 사용자를 준비한다.
- 같은 사전 준비와 별도 대상의 warm-up 뒤 시작한다. 데이터 입력 시간은 제외한다.
  A/B 사이 비동기 작업과 발급 기록이 섞이지 않게 격리한다.
- 측정 범위는 이미 저장된 due 후보 -> 기존 스케줄러 -> Kafka -> 쿠폰 consumer ->
  MySQL `UserCoupon` 저장이다. 브라우저와 장바구니 이벤트 입력은 포함하지 않는다.
- 성공 기준은 600초 내 대상 50,000건의 실제 사용자 쿠폰 저장, 중복 발급 없음,
  실행/발송 이력 보존이다. Kafka 발행 성공이나 후보 DISPATCHED만으로 발급 완료로 세지 않는다.
- 완료한 경우 완료 시간, 미완료한 경우 600초의 발급 수와 미처리 수를 남긴다.
  후보 상태, Dispatch 상태, Kafka lag와 DLT도 함께 남긴다.
- 목표 충족 여부는 아래 실측 결과로 판단한다. SQL 시간의 개선율을 전체 발급 완료 시간의
  개선율로 사용하지 않는다.
- 기존 SQL 단위 실험의 MySQL 데이터는 테스트 컨테이너 종료로 삭제됐다. 이번 측정은
  묶음 입력으로 새 fixture를 준비한다.

### Measured Result

- 사전 통합 테스트: A/B 모두 20건 실제 발급, 중복 0건.
- 본 실험은 로컬 MySQL, Kafka, Redis 테스트 컨테이너와 실제 Spring 서비스/consumer 경로로
  수행했다. 두 비교군은 별도 테스트 프로세스와 컨테이너로 격리했다. 각 비교군 본 측정은 1회다.

| 600초 시점 | A: 조회용 인덱스 없음 | B: 캠페인 우선 인덱스 |
| --- | --- | --- |
| 실제 사용자 쿠폰 저장 | 16,000건 | 34,798건 |
| 대상 50,000건 중 미발급 | 34,000건 | 15,202건 |
| 중복 사용자/쿠폰 조합 | 0건 | 0건 |
| 10분 내 전체 발급 목표 | 미달 | 미달 |

같은 600초 동안의 실제 발급량은 약 2.17배 늘었다. 미발급 수는 약 55.3% 줄었다.
그러나 인덱스 개선만으로 10분 내 전체 발급까지 완료했다는 가설은 확인되지 않았다.
개별 후보의 claim, 구매 재확인, 실행/발송 이력 저장, Kafka 발행과 쿠폰 저장 시간이 모두
포함된 결과이며, 각 단계의 남은 시간을 이번 실험에서 따로 분해하지는 않았다.

- 본 측정 전 실제 repository 조회를 1회 별도로 잰 값은 A 14,892ms, B 596ms였다.
  위의 5회 중앙값 SQL 실험과 측정 범위가 달라 같은 수치로 취급하지 않는다.
- 원시 snapshot의 `outstandingCandidates=234000`은 미래 실행 예정 200,000건까지 포함한
  전체 SCHEDULED 수다. 대상 50,000건의 미발급 수와 혼동하지 않는다.
- 상태별 집계는 순차적인 별도 DB 조회다. 발급 수를 읽은 뒤의 실행 이력 수가 더 많을 수 있으므로
  같은 시점의 원자적 대사 결과로 사용하지 않는다.
- 종료 후 정리 시점의 발급 수는 A 16,094건(607초), B 35,000건으로 추가 증가했다.
  이 값들을 600초 발급량에 포함하지 않는다.
- DLT 적재는 양쪽 0건이었다. Kafka lag는 consumer를 중지한 뒤 수집한 값으로 A 406,
  B 0이었다. 정상 실행 중 또는 600초 시점의 lag로 해석하지 않는다.
- 결과 위치: `build/benchmark/abandoned-cart-issuance-e2e/summary.md`, `summary.json`,
  각 `arm-A/`, `arm-B/`의 `cutoff.json`, `post-cutoff-drain.json`, `kafka-topics.txt`.
- 재실행: `bash scripts/benchmark/run-abandoned-cart-issuance-e2e.sh smoke` 또는 `full`.
  각 실행은 timestamp와 충돌 방지 suffix가 붙은 새 아티팩트 디렉터리를 사용하며, 긴 실행 로그도 그 안에 보관한다.

## Single-Page Design Comparison (completed, 2026-10-15)

이후 조회 설계 비교는 600초 실험을 반복하지 않고 500건 처리 표본을 사용한다.

- 현재 설계: 캠페인 우선 인덱스로 기존 전체 필드 조인 조회를 수행한다.
- 비교 설계: 상태 우선 인덱스로 가장 오래된 후보 ID 500개를 먼저 정한 뒤,
  그 500개의 후보, 캠페인, 쿠폰 설정을 실제 JPA 조회로 가져온다.
- 인덱스만의 A/B가 아니라 조회 구조와 인덱스 조합의 비교다. 상세 정보 조회를 생략한
  과거 ID-only microbenchmark와 구분한다.
- 후보 이력 5,000,000건과 대기 대상 50,000건을 유지한다. 각 표본 사이 처리한 후보와
  발급/실행 이력을 복구하고 비동기 작업이 남지 않았는지 확인한다.
- 설계별 동일한 준비 후 500건 실제 쿠폰 발급을 3회 측정한다. 최초 조회 시간과
  Kafka를 거친 사용자 쿠폰 500건 저장 완료 시간을 분리한다.
- 50,000건의 소요 시간을 `500건 중앙값 * 100`으로 계산한다면 단순 추정으로만 표시한다.
  같은 500건을 반복하는 표본은 전체 적체 해소의 캐시 변화와 상태 변화를 재현하지 않는다.
- 기존 600초 실측 결과는 그대로 보존한다. 새 표본으로 10분 목표 충족을 확정하지 않는다.

### Result

MySQL 8.0.46, 하나의 Spring 컨텍스트와 MySQL/Kafka/Redis fixture에서 수행했다.
A 워밍업 1회와 3회 측정 후, 인덱스를 한 번 교체하고 B 워밍업 1회와 3회 측정했다.
500만 건 입력은 한 번만 수행했다. 각 회차 전에 같은 500건의 상태와 실행 시각을 복구하고
새 실행 이력을 생성했다. 나머지 4,999,500건의 fingerprint도 변경되지 않았다.

| 측정, ms | A: 기존 조인과 캠페인 우선 인덱스 | B: 후보 ID 선조회와 상태 우선 인덱스 |
| --- | --- | --- |
| 상세 정보 포함 조회 3회 | 441.54, 459.71, 459.61 | 15.80, 14.89, 8.29 |
| 조회 중앙값 | 459.61 | 14.89 |
| 실제 쿠폰 500건 완료 3회 | 7461.96, 7534.65, 5940.27 | 4956.04, 5200.31, 7554.47 |
| 500건 완료 중앙값 | 7461.96 | 5200.31 |

- B는 ID 조회만 잰 값이 아니다. 두 쿼리, 실제 JPA 전체 필드 로딩과 순서 복구를 포함한다.
  첫 조회는 covering range scan에서 500건을 반환하고, 상세 조회는 후보 PK 500건과
  campaign/action PK를 조회한다. A처럼 due 후보 전체를 모아 전역 정렬하지 않는다.
- 8회(워밍업 포함) 모두 사용자 쿠폰 500건, 후보 DISPATCHED 500건, 새 실행 이력 500건,
  SUCCEEDED Dispatch 500건, 명령 500건 발행, 실제 consumer quiescence 후 lag 0,
  DLT 0, 중복 0을 확인했다.
- 조회 중앙값은 약 96.8% 감소했다. 전체 500건 처리 중앙값은 약 30.3% 감소했지만,
  표본이 설계별 3개뿐이고 범위가 겹친다. 전체 처리 개선율을 안정적인 값으로 확정하지 않는다.
- 같은 페이지를 사전 조회한 뒤 반복하는 warm-buffer 표본이며, A 다음 B 순서라 시간과
  캐시 편향이 있을 수 있다. 완료 관측에는 100ms polling과 DB/AdminClient 조회 시간이 들어간다.
- 50,000건 단순 추정은 A 746.2초, B 520.0초다. 전체 적체 해소나 600초 준수의 실측이 아니다.
- 새 조회 설계는 테스트 하네스에만 구현했다. 실제 서비스의 repository와 인덱스는 이번 비교에서
  변경하지 않았다.
- 결과: `build/benchmark/abandoned-cart-single-page/summary.md`, `summary.json`,
  `sample-*.json`, `identity.json`, `explain-A.txt`, `explain-B.txt`.
- 재실행: `bash scripts/benchmark/run-abandoned-cart-issuance-e2e.sh single-page`.
  작은 fixture 확인은 `single-page-smoke`이며, 두 모드 모두 600초 전체 실험을 실행하지 않는다.

## Persistent Local SQL Lab

반복적인 SQL 실험은 `infrastructure/compose/compose.benchmark.yml`의 전용 MySQL 8.0.46을 사용한다.
프로젝트 이름은 `axon-benchmark`, 포트는 `127.0.0.1:13306`이며 운영 Compose와 분리한다.
데이터는 `axon-benchmark_mysql-data` 볼륨에 남는다. 컨테이너를 재생성해도 볼륨을 지우지
않으면 다시 입력하지 않는다. 일반 테스트와 서비스는 이 DB에 자동 연결하지 않는다.

```sh
python3 scripts/benchmark/mysql-lab.py up
python3 scripts/benchmark/mysql-lab.py seed candidates
python3 scripts/benchmark/mysql-lab.py seed purchases
python3 scripts/benchmark/mysql-lab.py status
python3 scripts/benchmark/mysql-lab.py compare-candidates --repeat 3
python3 scripts/benchmark/mysql-lab.py sql purchases --file /path/to/query.sql
python3 scripts/benchmark/mysql-lab.py stop
```

- `bench_candidates`: 후보 이력 5,000,000건, due 후보 50,000건, 캠페인 20개.
  논리 기준 시각은 `2026-10-14 12:00:00`이다. 실시간 `NOW()` 대신 이 시각을 기준으로
  조회해야 기존 due 비율을 유지한다. candidate, campaign, marketing action 테이블만 둔다.
- `bench_purchases`: Purchase 필드와 현재 인덱스를 갖춘 합성 구매 이력 1,000,000건.
  이 중 사용자 1~800의 구매가 8,000건이다. 첫 800건은 캠페인 1의 참여 구매,
  나머지는 SHOP 구매다. 다른 도메인의 테이블과 API는 포함하지 않는다.
- 같은 `seed` 명령은 버전과 행 수가 맞으면 기존 데이터를 재사용한다. 의도적으로 다시 만들 때만
  `seed candidates --reset` 또는 `seed purchases --reset`을 사용한다. 해당 DB만 교체한다.
- 초기 입력은 MySQL 내부 `INSERT ... SELECT`로 수행하고, Java/JDBC에서 수백만 행을
  한 행씩 생성해 전송하지 않는다. 도구에는 외부 DB URL을 받는 옵션이 없다.
- `compare-candidates`는 같은 500건의 전체 필드와 순서까지 비교하는 SQL 전용 도구다.
  A는 기존 조인, B는 임시 ID 500건을 먼저 정한 뒤 상세를 조인한다. JPA와 Kafka,
  실제 쿠폰 발급은 제외한다. 서버 SQL 경과 시간만 저장하므로 앞선 JPA/발급 수치와 섞지 않는다.
  1회 사전 조회 후 지정한 횟수를 재고, 원시 시간과 결과 해시, 실행계획을
  `build/benchmark/mysql-lab/<timestamp>/`에 저장한다. 실행 후에는 B의 상태 우선 인덱스가 남는다.
- 기존 Testcontainers/JPA 통합 테스트는 스키마 생성/삭제와 비동기 처리가 있으므로 계속
  일회성 DB를 사용한다. SQL Lab을 연결하는 것만으로 기존 통합 테스트의 seed가 생략되지는 않는다.
- 인덱스 생성이나 상태 변경 실험을 했다면 다음 실험의 시작 상태도 직접 확인한다. 이 DB의
  숫자를 기존 일회성 DB 측정과 동일 조건이라고 자동으로 간주하지 않는다.

### Persistent SQL Lab Retest (2026-10-15)

전용 `axon-benchmark` MySQL 8.0.46의 기존 후보 5,000,000건(기준 시각 기준 due 50,000건)과 구매 1,000,000건을 seed 재실행으로 재사용했다. MySQL 컨테이너만 재생성한 뒤 두 fixture의 버전·행 수·ready 상태가 유지됨을 확인했다.

A(캠페인 우선 조인)와 B(상태 우선 인덱스로 ID 500건 선조회 후 전체 필드 조인)를 각각 1회 워밍업하고 3회 측정했다. SQL 서버 경과 시간은 A `[346.903, 733.343, 295.786]ms`(중앙값 `346.903ms`), B `[6.384, 5.729, 6.044]ms`(중앙값 `6.044ms`)였다. 세 번 모두 전체 조회 결과 500행의 필드와 순서가 같았고 SHA-256은 `15cb72d09208db4ed0829b0d7b63a7820224fdf0899fc7f17cda7a1365f694a3`였다.

A 계획은 20개 캠페인에서 후보 50,000건을 읽고 정렬했다. B의 별도 ID 선조회 계획은 상태 우선 covering range scan에서 500건을 읽고, 상세 조회 계획은 그 ID로 후보·campaign·action을 PK 조회했다. 측정은 SQL 전용으로 JPA 객체화, Kafka, 쿠폰 발급을 포함하지 않는다. 따라서 과거 JPA/발급 결과와 직접 비교하거나 전체 발급 개선으로 해석하지 않는다. 이 로컬 3회 측정은 warm buffer 순서 비교이며 안정적인 운영 지연 보장을 뜻하지 않는다.

아티팩트: `build/benchmark/mysql-lab/20261015-163820/` (`summary.json`, `explain-join.txt`, `explain-ids-first-selection.txt`, `explain-ids-first.txt`).

### Purchase Persistence Overlap Retest (2026-10-15)

별도 일회성 Spring/MySQL/Kafka/Redis Testcontainers context에서 후보 이력 1,000,000건과 due 후보 50,000건(20개 campaign에 각 2,500건)을 한 번 생성하고, 같은 context에서 fixture를 복구하며 6개 구간을 측정했다. 분포는 SCHEDULED due 5%, SCHEDULED future 4%, CANCELLED 10%, DISPATCHED 81%였다. 각 구간은 10초 warm-up 뒤 고정 60초 동안 합성 offered load 10개의 20-message `FcfsLedgerPersistenceService.persistBatch` 트랜잭션/초(초당 200 Purchase row)였다. 실제 Entry-confirm HTTP 요청 경로는 측정하지 않았다. 순서는 baseline-A-1, A-1, B-1, baseline-B-2, B-2, A-2이며, baseline은 worker 없이 구매 저장만 했다. A는 상세 조인+campaign-first composite index, B는 due ID 선조회+status-first composite index로, query와 index 설계를 함께 바꾼 비교다.

| 구간 | 구매 E2E p95 / transaction p95 (ms) | MySQL CPU seconds / 측정 seconds (평균 core) | 후보 조회 p95 (ms) / 호출 | 발급 / 남은 due (drain 후) |
| --- | ---: | ---: | ---: | ---: |
| baseline-A-1 | 70.16 / 61.86 | 11.61 / 60.39 (0.192) | — | 0 / 50,000 |
| A-1 | 51.51 / 39.87 | 40.34 / 60.83 (0.663) | 494.86 / 17 | 8,500 / 41,500 |
| B-1 | 72.28 / 45.40 | 38.09 / 60.52 (0.629) | 212.89 / 19 | 9,500 / 40,500 |
| baseline-B-2 | 66.27 / 62.10 | 12.25 / 60.28 (0.203) | — | 0 / 50,000 |
| B-2 | 70.03 / 50.22 | 36.74 / 60.39 (0.608) | 133.90 / 21 | 10,500 / 39,500 |
| A-2 | 50.19 / 41.42 | 42.63 / 61.13 (0.697) | 307.16 / 18 | 9,000 / 41,000 |

모든 구간에서 예정된 600개 구매 트랜잭션이 모두 commit됐고 각 구간의 Entry/Purchase 저장 행은 각각 12,000건이었다. 실패, dropped/missing offer, duplicate, DLT, Kafka lag는 모두 0이었다. 구매 p95는 worker 구간에서 baseline보다 일관되게 악화되지 않아, 이 부하에서 구매 저장 간섭 또는 B의 A 대비 구매 지연 완화를 확인하지 못했다. 오히려 A의 p95가 두 worker 비교 구간에서 B보다 낮았다. worker 구간의 MySQL 평균 CPU는 0.608–0.697 core, baseline은 0.192–0.203 core였으므로 공유 MySQL CPU 사용량 증가는 관측됐다. CPU 증가만으로 구매 지연 영향이 입증되지는 않는다. B가 A보다 더 많은 쿠폰을 발급해 후속 write 수도 달라지는 전체 worker 비교라 순수 SQL 비용 효과로 귀속할 수 없다. InnoDB I/O 지표도 구간별로 혼재해 일관된 I/O 개선을 주장하지 않는다. 이 workload는 MySQL 처리 용량을 포화시킨 측정이 아니며 50,000건의 발급 완료 시간 또는 10분 목표를 검증하지 않았다.

발급 수와 남은 due 수는 구간 cutoff 시각이 아니라 Kafka quiescence/drain 이후 값이다. worker는 고정 측정 종료 뒤 진행 중인 batch를 마무리했으며 실제 active 시간은 62.2–64.4초였다. due 50,000건은 어떤 worker 구간에서도 소진되지 않았다. 행/조회는 서로 다른 테이블과 논리 행을 사용하므로 row-lock 경합으로 해석하지 않는다. 이 결과는 합성 제공률, 단일 fixture 및 해당 로컬 자원 조건에 한정되며 운영 유입률이나 SLA를 나타내지 않는다.

일회성 DB에서 Testcontainers 기본값의 buffer pool 16MiB, table_open_cache 4, sort buffer 64KiB를 실험 모드에서 각각 128MiB, 4000, 256KiB로 정규화했다. redo capacity는 100MiB, `innodb_log_file_size`는 기본 5MiB였고 CPU/메모리 quota는 추가하지 않았다. 따라서 프로젝트의 다른 실행 환경과 동일하다고 보장하지 않는다. 실험 전후 `FcfsLedgerPersistenceService`, `CouponStrategy`, scheduler/service/repository 및 benchmark test의 SHA-256과 mtime이 동일했다. opt-in 단일 benchmark JUnit은 1 passed, 0 skipped로 종료했다. 전체 suite는 실행하지 않았다. 최초 전체 test 컴파일 시 별도 `FcfsCommandOrchestratorTest` 생성자 불일치가 드러나, 이번 실험은 필요한 두 test source만 컴파일하는 opt-in 경로로 검증했다. 해당 테스트/프로덕션 코드는 변경하지 않았다.

아티팩트: `/Users/yangnail/dev/projects/skusw/axon/build/benchmark/abandoned-cart-purchase-overlap-20261015T182311/` (`summary.md`, `summary.json`, 여섯 `window-*.json`, raw 구매 표본, 실행계획/인덱스, `source-fingerprint-before.json`, `source-fingerprint-after.json`, `test-completed.json`).
