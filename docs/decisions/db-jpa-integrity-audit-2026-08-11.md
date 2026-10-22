# DB/JPA 정합성 감사 — 2026-08-11

상태: active

## 결론

현재 OCI 데이터에서 중복·고아·음수 값은 발견되지 않았다. 다만 정상 데이터가 유지된 이유 일부가 DB 제약이 아니라 단일 실행 순서와 애플리케이션 코드에 의존하고 있었다. 이번 작업은 재현 가능한 정합성 위험을 수정했고, 2026-08-19 후속 작업에서 캠페인 수나 Activity 수에 따라 증가하던 Purchase·Entry 조회, SSE 중복 집계, RFM offset pagination을 정리했다. 운영 경로가 월 배치 결과 조회로 확정된 코호트의 과거 실시간 JVM 집계도 제거했다. 스키마 마이그레이션과 결제 정책처럼 별도 합의가 필요한 작업은 분리했다.

## 확인 범위

- Core의 17개 JPA 엔티티와 Repository
- FCFS 원장 이후 UserSummary projection
- 쿠폰 사용, 재고 동기화, 월별 LTV 배치
- OCI MySQL `SHOW CREATE TABLE` 및 정합성 위반 건수 조회
- 서비스·스케줄러의 트랜잭션 호출 경로

OCI 조회는 스키마와 건수만 읽었고 사용자 데이터 값은 출력하지 않았다.

## 2026-10-20 FCFS 벌크 저장 적용

- FCFS 배치의 신규 Entry·Purchase 저장을 JDBC 다중 행 INSERT로, UserSummary 최신 구매 시각 반영을 CASE UPDATE로 변경했다. 실험용 선택 설정은 제거하고 기본 경로로 사용한다. 소비자 동시성 1과 최대 poll 20건은 유지한다.
- Entry·Purchase는 기존 서비스 트랜잭션에 함께 참여한다. UserSummary는 기존처럼 별도 트랜잭션에서 동기 호출한다. 배치 실패 후 개별 재처리와 DLT 정책은 유지한다.
- 관련 단위·MySQL 통합 테스트 20개 통과. 실제 INSERT 제약 위반 시 두 테이블 롤백, 같은 메시지 재처리 및 중복 방지, 최신 시각 유지와 누락된 Summary 처리까지 확인했다. 전체 Core 테스트 실행 결과를 의미하지는 않는다.
- VM Core에 `cf36d63`의 JAR를 기존 런타임 이미지에 반영해 배포했다. 1.2 CPU·3 GiB에서 신규 메시지 20건의 Entry·Purchase·UserSummary 반영과 동일 메시지 재전송 시 중복 미생성을 확인했다. 최종 lag 0, DLT와 projection 실패 토픽의 추가 메시지 0건이다. 이 점검은 처리량 재측정이 아니다.
- 이미지 ID·JAR 해시·소스 커밋·점검 결과와 이전 이미지 백업은 VM의 `/home/ubuntu/apps/axon-releases/core-bulk-cf36d63/manifest.json` 및 `axon-core-service:pre-bulk-cf36d63`에 보관한다. 자동 승인 검토에서 전체 소스 아카이브 전송을 거절해 JAR만 전송했다. VM Git checkout은 기존 `c8634b3a`를 유지하므로 이후 소스 재빌드 배포 시 `cf36d63`을 먼저 반영해야 한다.

## 실제 OCI 상태

| 확인 항목 | 결과 |
| --- | --- |
| `CampaignActivityEntry(activity, user)` 유니크 키 | 존재 |
| `Purchase(activity, user)` 유니크 키와 주요 조회 인덱스 | 존재 |
| `UserCoupon(user, coupon)` 유니크 키 | 존재 |
| User–UserSummary 공유 PK/FK | 존재 |
| LTV `(activity, monthOffset)` 유니크 키 | 변경 전 없음 |
| Entry/Purchase/UserSummary 고아 데이터 | 0건 |
| 중복 LTV 키 | 0건 |
| 음수 가격·재고, 0 이하 구매 수량 | 0건 |
| 액티비티 기간·타입 참조 불일치 | 0건 |

확인 시점 데이터 규모는 User 3,000건, Entry/Purchase 각 800건이다. 인덱스 성능을 일반화하기에는 작은 규모이므로, 실행계획만으로 성능 개선 수치를 만들지 않았다.

## 변경 전 → 위험 → 변경 후

### 1. UserSummary 최신 구매 시각

- **변경 전:** User와 LAZY UserSummary를 읽고 객체 안에서 최댓값을 비교했다.
- **위험:** 서로 다른 트랜잭션이 같은 과거 스냅샷을 읽으면 늦게 commit한 오래된 이벤트가 최신 시각을 덮을 수 있었다. FCFS 배치마다 UserSummary 추가 조회도 발생했다.
- **변경 후:** DB 조건부 UPDATE가 `기존 값 < 후보 시각`일 때만 변경한다. 취소·환불 재구성은 UserSummary 행을 잠근 뒤 수행하며, RFM과 로그인처럼 다른 필드의 동시 변경은 `@DynamicUpdate`로 불필요하게 덮지 않는다.

### 2. 재고 동기화 실패 범위

- **변경 전:** 모든 ACTIVE 액티비티를 하나의 트랜잭션에서 처리하고 액티비티별 예외를 내부에서 잡았다.
- **위험:** 하위 트랜잭션 서비스가 rollback-only로 표시하면 앞에서 성공한 액티비티까지 마지막에 함께 롤백될 수 있었다.
- **변경 후:** 액티비티 ID만 먼저 조회하고 각 액티비티를 독립된 `TransactionTemplate` 경계에서 다시 읽어 처리한다. 한 건의 실패가 다음 액티비티 실행을 막지 않는다.

### 3. 일회성 쿠폰 사용

- **변경 전:** 쿠폰 상태를 읽은 뒤 `ISSUED`인지 확인하고 `USED`로 변경했다.
- **위험:** 동시 요청 둘이 같은 `ISSUED` 상태를 읽으면 모두 사용 처리까지 통과할 수 있었다.
- **변경 후:** 사용 시 UserCoupon 행을 비관적 쓰기 잠금으로 조회한 뒤 소유자와 상태를 검사한다.

### 4. ACTIVE FCFS 상품 단일 연결

- **변경 전:** 같은 상품을 쓰는 ACTIVE FCFS가 있는지 조회한 뒤 새 액티비티를 저장했다.
- **위험:** 동시 생성 요청은 둘 다 없음으로 판단할 수 있었다.
- **변경 후:** campaign-only 상품 행을 비관적 쓰기 잠금으로 잡은 상태에서 기존 ACTIVE FCFS를 확인한다.

### 5. DB 직전 불변식

- **변경 전:** 음수 수량으로 `decreaseStock`을 호출하면 재고가 증가했고, Purchase는 음수 가격과 0 이하 수량을 허용했다.
- **변경 후:** Product와 Purchase 생성 경계에서 잘못된 값을 거부한다. 외부 DTO 검증을 우회한 내부 호출·메시지도 동일한 규칙을 적용받는다.

### 6. 월별 LTV 중복

- **변경 전:** 분산락과 `마지막 offset + 1` 계산만으로 중복을 피했고 DB에는 `(activity, monthOffset)` 제약이 없었다.
- **변경 후:** 엔티티 스키마에 복합 유니크 제약을 추가하고 중복 저장 실패 테스트를 추가했다. OCI에는 아직 배포하지 않았으므로 실제 제약 반영은 다음 배포 검증 대상이다.

### 7. Global Dashboard Purchase 집계

- **변경 전:** 전체 Campaign을 읽은 뒤 Campaign마다 LAZY Activity 목록을 조회하고, 각 Campaign의 Activity ID로 confirmed Purchase 건수와 GMV를 다시 집계했다. Campaign이 `N`개이면 MySQL 조회가 구조적으로 `1 + N + N`까지 증가했다.
- **위험:** 행동 통계의 Elasticsearch N+1은 제거되어 있었지만 MySQL Purchase 집계는 Campaign 수에 비례해 반복되어, Global Dashboard와 LLM 전역 조회에서 같은 종류의 확장 문제가 남아 있었다.
- **변경 후:** Campaign과 Activity를 fetch join으로 한 번에 읽고, 모든 Activity의 Purchase 집계를 한 번의 `GROUP BY campaign_activity_id` 쿼리로 가져온 뒤 메모리에서 Campaign별로 합산한다. Campaign 수와 무관하게 이 범위의 MySQL 조회는 2회로 고정된다.

### 8. Activity 목록 참여자 수

- **변경 전:** Activity 목록을 읽은 뒤 각 Activity마다 `CampaignActivityEntry COUNT`를 호출했고, 응답 변환 중 Product/Coupon LAZY 조회도 발생할 수 있었다.
- **위험:** Activity가 `N`개이면 참여자 수 조회만으로 N개의 추가 쿼리가 발생했다. 현재 OCI에는 Campaign/Activity가 각 1건이라 지연은 없었지만, 목록 조회 구조 자체에는 N+1이 남아 있었다.
- **변경 후:** Activity와 Product/Coupon을 한 번에 조회하고, 대상 Activity 전체의 참여자 수를 `GROUP BY campaign_activity_id` 한 번으로 가져온다. Entry가 없는 Activity는 응답에서 0명으로 유지한다.
- **인덱스 판단:** 참여자 집계는 기존 Entry 유니크 키 `(campaign_activity_id, user_id)`의 선두 컬럼을 활용할 수 있어 새 인덱스를 추가하지 않았다.

### 9. Activity SSE Purchase 중복 집계

- **변경 전:** Activity Dashboard 한 번의 계산에서 현재 기간 confirmed Purchase 집계를 overview와 funnel이 각각 호출했고, 이전 기간 집계까지 합쳐 Purchase 집계가 3회 발생했다. Activity SSE는 같은 계산을 2초마다 반복한다.
- **위험:** 현재 기간의 동일 조건 집계가 SSE 연결 수와 갱신 횟수만큼 중복 실행됐다.
- **변경 후:** 현재 기간과 이전 기간 Purchase 집계를 각각 한 번만 수행하고, 현재 기간 결과를 overview와 funnel이 공유한다. Activity 메타데이터도 한 번 읽어 overview, funnel, realtime 계산에 전달한다.

### 10. RFM UserSummary 순회

- **변경 전:** `Page<UserSummary>`와 page number 기반 offset pagination으로 100명씩 순회했다. 뒤 페이지로 갈수록 offset scan이 커지고, `Page` 생성을 위한 전체 COUNT가 반복될 수 있었다.
- **위험:** 사용자 수가 증가하면 Purchase 집계 자체와 무관한 offset·COUNT 비용이 배치 페이지 수에 따라 증가한다.
- **변경 후:** UserSummary PK인 `user_id`를 cursor로 사용해 `WHERE user_id > :lastSeenUserId ORDER BY user_id LIMIT 100` 형태로 순회한다. Purchase RFM 집계, 산식, 100명 단위 처리와 기존 분산 실행 잠금은 유지한다.

### 11. 사용하지 않는 실시간 코호트 계산

- **변경 전:** 운영 API는 `cohort_ltv_monthly_stats`에 저장된 월 배치 결과만 반환하고 결과가 없으면 202를 반환하지만, 서비스에는 코호트 사용자의 Purchase 전체 엔티티를 읽어 JVM에서 LTV와 재구매율을 계산하는 과거 public 메서드가 남아 있었다.
- **위험:** 실제 운영 경로와 호출되지 않는 과거 경로가 함께 보여 SQL 오프로딩의 현재 경계가 불명확했고, 사용하지 않는 Purchase Repository 쿼리도 유지됐다.
- **변경 후:** 실시간 JVM 집계 메서드와 전용 helper 및 미사용 기간/재구매 조회 쿼리를 제거했다. 월 배치가 사용하는 첫 구매 코호트 조회와 SQL 집계, 저장된 배치 결과 응답은 보존했다.

## 검증

- 2026-08-19 Core 전체: 61 suites, 190 tests, failures/errors 0, skipped 25
- UserSummary 조건부 갱신 JPA 테스트
- 재고 동기화 액티비티별 실패 격리 테스트
- 쿠폰 잠금 경로 테스트
- Product/Purchase 불변식 테스트
- LTV 중복 키 DB 제약 테스트
- Campaign 수가 늘어도 Global Purchase 집계 Repository 호출이 1회인지 확인하는 서비스 테스트
- Activity 수가 늘어도 Entry 참여자 집계 Repository 호출이 1회인지 확인하는 서비스 테스트
- Campaign–Activity fetch join과 Entry `GROUP BY` projection JPA 테스트
- Activity Dashboard의 동일 현재 기간 Purchase 집계 호출이 1회인지 확인하는 서비스 테스트
- UserSummary PK cursor 조회 JPA 테스트와 RFM scheduler 회귀 테스트
- Cohort API 응답이 저장된 월 배치 결과만 사용하는 단위 테스트
- `git diff --check` 통과

2026-08-19 로컬 전체 테스트와 새 H2 기반 JPA projection 테스트가 통과했다. 2026-08-12 GitHub Actions에서는 MySQL·Kafka·Redis 컨테이너를 사용하는 Core 전체 suite와 필수 통합 테스트의 실제 실행까지 통과했지만, 이번 조회 최적화 diff의 GitHub Actions 검증은 push 이후 대상이다. LTV 유니크 제약을 기존 OCI 스키마에 반영하는 운영 마이그레이션도 아직 수행하지 않았으므로 다음 OCI 배포 검증 대상으로 유지한다.

## 후속 측정 계획: RFM keyset pagination

상태: planned. 현재 변경은 query shape 개선이며, 아직 응답시간 또는 배치 시간 개선 수치를 주장하지 않는다.

- **목적:** offset pagination과 PK cursor(keyset)의 비용 차이, 그리고 전체 RFM 배치 시간을 같은 조건에서 분리해 확인한다.
- **환경:** 로컬 MySQL에 `UserSummary` 100,000건과 confirmed `Purchase` 약 500,000건을 고정 seed한다. page size는 현재 구현과 같은 100건으로 둔다.
- **비교:** 이전 offset query와 현재 keyset query를 같은 데이터에서 비교한다. offset은 0, 50,000, 90,000 지점, keyset은 동일 위치의 cursor를 사용한다.
- **측정:** `EXPLAIN ANALYZE`의 실제 scan/examined rows와 SQL 실행시간, 전체 scheduler 실행시간 및 처리 건수를 기록한다. 전체 시간에는 Purchase 집계와 UserSummary 갱신 비용도 포함됨을 함께 명시한다.
- **표현 원칙:** 반복 측정으로 같은 경향이 확인되기 전에는 포트폴리오 성능 수치로 사용하지 않는다. 수치가 없으면 대량 배치의 offset/COUNT 비용을 제거한 예방적 query-shape 개선으로만 기록한다.

### 코호트 SQL 오프로딩 재현 harness

과거 JVM 집계와 현재 SQL 오프로딩의 포트폴리오 근거는 별도 테스트 harness로 재측정한다. `core-service/src/test/java/com/axon/core_service/benchmark/CohortSqlOffloadingBenchmarkIT.java`는 과거의 Purchase 엔티티 적재 + Java 집계와 현재 SQL 집계만 비교하며, 전체 scheduler/HTTP 성능을 주장하지 않는다. 같은 Testcontainers MySQL 데이터에서 결과 일치, 반복 실행시간, `EXPLAIN ANALYZE`, 마지막 실행의 JFR allocation/GC 기록을 남긴다.

```bash
./scripts/benchmark/run-cohort-sql-offloading-benchmark.sh smoke
./scripts/benchmark/run-cohort-sql-offloading-benchmark.sh index-selectivity
./scripts/benchmark/run-cohort-sql-offloading-benchmark.sh hydration-jfr
./scripts/benchmark/run-cohort-sql-offloading-benchmark.sh index-write-cost
```

`smoke`는 harness 검증용이고 포트폴리오 수치에 사용하지 않는다. `index-selectivity`는 FCFS 당첨자 800명과 user당 구매 이력 10건을 기준으로 8,000 cohort purchases(전체 1,000,000건의 0.8%)를 생성해 실행계획을 확인한다. `hydration-jfr`는 10,000 cohort users, cohort user당 50 purchases, unrelated 500,000 purchases로 cohort 비중 50%를 만들며, JPA entity hydration의 JVM allocation/GC 관찰용이다. 두 profile의 목적을 섞어 성능 수치나 인덱스 효과를 주장하지 않는다. 실행 당시의 Java/MySQL/Docker 환경과 artifact를 함께 저장한 뒤에만 수치를 갱신한다.

2026-09-04 로컬 Testcontainers MySQL에서 `hydration-jfr` 조건(기존 `portfolio` alias)을 세 번 실행했다. 5회 반복 중앙값은 이전 entity hydration + Java 집계가 1,866ms/1,876ms/1,837ms, 현재 SQL 집계가 1,615ms/1,647ms/1,868ms였다. 두 번은 SQL이 약 12~13% 빨랐지만, 메타데이터를 남긴 세 번째 실행에서는 SQL이 약 2% 느렸다. 따라서 이 환경의 실행시간 퍼센트는 포트폴리오 성과로 쓰지 않는다.

반면 마지막 JFR 기록에서는 entity hydration 쪽이 allocation event 2,137개와 GC 5회를 남겼고, SQL 쪽은 27개와 GC 0회였다. JFR event의 `allocationSize` 합계도 약 41MiB 대 약 300KiB였다. 이는 전체 할당량이 아니라 JFR이 기록한 이벤트 합계지만, 대량 엔티티 hydration을 없앤 구조적 차이를 뒷받침한다. 이 결과는 query-shape 비교의 로컬 증거이며 전체 scheduler/운영 성능 수치는 아니다.

`hydration-jfr`의 `EXPLAIN ANALYZE`에서는 코호트 대상이 전체 1,000,000건 중 500,000건으로 넓어 두 방식 모두 table scan을 선택했다. 따라서 이 조건에서 `(user_id, purchase_at)` 인덱스가 scanned rows를 `456 -> 1`로 줄였다고 주장할 수 없다.

별도 `index-selectivity` 조건에서는 FCFS 당첨자 800명 x 구매 이력 10건, 즉 8,000건(전체 1,000,000건의 0.8%)을 생성했다. 2026-09-04 로컬 Testcontainers MySQL에서 독립 실행을 세 번 반복했고, 각 실행의 5회 반복 중앙값은 legacy 149ms/122ms/81ms, SQL 23ms/30ms/37ms였다. 실행 단위 중앙값의 중앙값은 legacy 122ms, SQL 30ms다. 이는 과거 entity hydration + Java 집계 대비 SQL 집계가 이 microbenchmark에서 약 75% 낮은 실행시간을 보였다는 로컬 측정값이다. 전체 scheduler/HTTP/운영 처리량 성과로 일반화하지 않는다. `EXPLAIN ANALYZE`에서는 legacy lookup이 8,000 rows, SQL monthly aggregate가 620 rows를 읽으며 두 쿼리 모두 `idx_purchase_user_history` index range scan을 선택했다. 낮은 선택도에서 복합 인덱스가 날짜 조건까지 활용될 수 있다는 직접 근거로 사용한다.

`idx_purchase_user_history`의 읽기 이득만 보고 쓰기 우세 Purchase 원장에 인덱스를 정당화하지 않기 위해, 별도 `index-write-cost` harness를 추가했다. 1,000,000건의 background Purchase가 있는 임시 MySQL에서 현재 Core와 같은 20건 트랜잭션 단위로 800건을 JPA `IDENTITY` insert하고, 해당 복합 인덱스만 drop/add해 비교한다. 2026-09-04 독립 실행 세 번의 실행 단위 중앙값은 인덱스 없음 325ms, 인덱스 있음 320ms였고, 20건 batch p95의 중앙값은 12ms, 11ms였다. 이 차이는 측정 잡음 범위이므로 인덱스가 쓰기를 빠르게 하거나 쓰기 비용이 없다고 주장하지 않는다. 다만 이 프로젝트의 FCFS 목표 쓰기 형태에서는 측정 가능한 insert 성능 저하가 관측되지 않았다는 근거로만 사용한다. Kafka·Entry·UserSummary·HTTP는 의도적으로 제외한 microbenchmark이며, E2E payment 부하의 DB 수렴 결과와 혼동하지 않는다.

## 별도 결정이 필요한 남은 작업

| 우선순위 | 작업 | 지금 합치지 않은 이유 |
| --- | --- | --- |
| 높음 | Flyway/Liquibase 기준 스키마 도입 후 운영 `ddl-auto: validate` 전환 | 기존 OCI 스키마 baseline과 무중단 cutover가 필요한 별도 배포 작업 |
| 높음 | SHOP 결제 기록 경계 재설계 | GET 성공 콜백, 클라이언트 가격, Purchase·Coupon 원자성은 다른 담당자의 결제 계약과 함께 정해야 함 |
| 중간 | 재고 부족 시 동기화 정책 결정 | 현재는 보유 재고까지만 차감한다. 실패·대사 이력·활성화 차단 중 제품 정책 선택이 필요 |
| 중간 | Product/Purchase/CampaignActivity 금액 precision 통일 | 실제 DB가 Product `decimal(38,2)`, Purchase·Activity `decimal(10,2)`로 달라 기존 값 범위 확인이 필요 |
| 완료 (2026-09-17) | DTO 변환 후 OSIV 비활성화 | `StoreViewService`와 `DashboardPageService`가 SSR DTO/view-model을 read-only 트랜잭션 안에서 조립하도록 옮긴 뒤 Core에 `spring.jpa.open-in-view=false` 적용 |

## 의도적으로 하지 않은 것

- 모든 엔티티에 범용 `@Version`을 붙이지 않았다.
- 성능 근거 없이 인덱스를 대량 추가하지 않았다.
- OCI 데이터 규모가 작으므로 이번 쿼리 수 개선을 응답시간 단축 수치로 표현하지 않았다.
- FK가 없는 scalar ID를 일괄 연관관계로 바꾸지 않았다. 이벤트 원장과 삭제 정책을 먼저 정해야 한다.
- 현재 실패 사례가 없는 범용 Repository·도메인 이벤트·Inbox/Outbox 계층을 추가하지 않았다.

## SQL 성능 사례 후보 검토 (2026-10-15, 측정 전)

상태: 코드와 기존 실험 아티팩트 검토 완료. 이번 검토에서는 실험 실행, 애플리케이션 코드·인덱스 변경을 하지 않았다. 인덱스 성과를 만들기 위해 업무 요구나 지연 목표를 새로 가정하지 않는다.

### 판단 기준과 기존 실험의 경계

- 필요한 업무 -> 지연의 영향 -> 실제 DB 병목 -> 구조 변경과 SQL 개선 비교 순서로 검증한다. 느린 SQL이나 전체 행 수만으로 비즈니스 필요성을 만들지 않는다.
- 장바구니 후보는 완료/취소 이력을 작업 조회에서 분리하거나 정리하는 대안을 먼저 검토한다. 같은 테이블에 5M 이력을 유지한 기존 인덱스/발급 실험은 유효한 조건부 측정이지만, 그 저장 구조가 최선이라는 증거는 아니다. 분리 후의 조회·발급 개선도 아직 미측정이다.
- 캠페인 성과 조회는 7/30일 분석이다. 1.5초 -> 0.5초 자체에 긴급한 사업상 이유나 SLA는 확인되지 않았다. 기존 인덱스 효과를 부정하지 않되 대표적인 업무 병목 해결로 강화하지 않는다.
- 성과 조회 benchmark는 전체 대상 100,000명(TREATMENT 90,000 / HOLDOUT 10,000)이다. `targetPurchases = min(totalPurchases / 5, TARGET_USERS * 2)` 때문에 1M/5M 모두 대상자의 구매가 200,000건, 사용자당 2건이다. 늘어나는 것은 비대상 구매이며 기존 고객의 긴 구매 이력은 검증하지 않았다.
- 해당 실행계획은 `(user_id, purchase_at)` 인덱스를 user_id lookup으로 사용한 뒤 기간을 필터링했다. 비교군에 user_id 단일 인덱스는 없으므로 날짜 컬럼 추가의 독립적 효과까지 입증한 것은 아니다. 5회 read 표본의 p95는 최댓값이며, 5M overlap의 read 표본은 인덱스 없음/있음 각각 1/3회다.
- benchmark는 assignment group만 집계하고 모든 대상은 LOYAL이지만 현재 서비스는 group과 baseline RFM segment를 함께 집계한다. 한 Run, 간소화 스키마, warm-up 후 반복, 하나의 reader/writer 조건으로 한정한다.
- 코호트 benchmark는 entity hydration과 SQL 집계의 비교다. `index-selectivity`와 `hydration-jfr`의 목적을 구분한 기존 기록은 유지하며 전체 배치나 인덱스 단독 성과로 바꾸지 않는다.

기존 근거: `artifacts/benchmark/20261014-170325-campaign-incrementality-1m/`, `artifacts/benchmark/20261014-170500-campaign-incrementality-5m/`, `artifacts/benchmark/20260904-145007-cohort-sql-offloading-index-selectivity/`의 summary/실행계획 및 두 benchmark 소스. 미커밋 소스가 포함되므로 artifact와 현재 코드의 경계도 함께 확인한다.

### 1순위: 캠페인 실행 준비의 대상 확정

- 실제 경로: `POST /api/v1/campaign-activities/{activityId}/runs` -> `CampaignActivityRunService.prepare` -> `UserSummaryRepository.countCouponCampaignTargets` / `findCouponCampaignTargetUserIds`.
- 업무: 지정 RFM 세그먼트에서 해당 쿠폰을 이미 받은 고객을 제외하고 현재 대상 수와 ID를 확정한다. 대상 스냅샷이 준비되어야 분석/검토/발송 절차로 진행한다.
- 지연의 영향: 운영자 API 응답과 같은 활동의 실행 준비가 기다린다. `prepare`는 활동에 비관적 잠금을 잡은 트랜잭션 안에서 count, ID 조회, 대상 스냅샷 저장을 수행한다. 이 잠금이 일반 구매를 막는다고 주장하지 않는다. deadline/timeout 위반은 아직 관측되지 않았다.
- 현재 인덱스: UserSummary 엔티티에는 PK와 `(last_purchase_at,user_id)`가 선언되어 있고 RFM 선두 인덱스는 확인되지 않았다. 쿠폰 발급 여부 확인은 기존 UNIQUE `(user_id,coupon_id)`를 사용할 수 있는 형태다. 실제 DB 인덱스와 실행계획은 아직 확인하지 않았다.
- 다음 최소 검증: 실제 인덱스 확인 후 count와 ID 조회를 분리해 EXPLAIN/반복 시간 및 결과 동등성 확인. RFM 분포, 기존 발급 비율, 대상 수를 업무 가정으로 명시한다. `(rfm_segment,user_id)`는 검토안이며 채택하지 않았다. count에는 정렬이 없으므로 user_id 단일/PK 포함 특성까지 확인하고 복합 키 필요성을 과장하지 않는다.
- 중단 기준: 자연스러운 규모에서 기존 조회가 충분하면 더 늘리지 않는다. 전체 prepare가 느려도 스냅샷 INSERT/JPA 시간이 주원인이면 SQL 조회 개선을 전체 원인 해결로 쓰지 않는다.
- 검증 비용: 중간. 현재 SQL Lab 구매 fixture에는 UserSummary/UserCoupon 전체 도메인이 없으므로 그대로 실행할 수 없다. fixture 확장은 아직 하지 않는다.

### 2순위: 이미 있는 휴면 고객 조회 사례의 활용 가능성

- 실제 경로: `DormantCustomerCampaignScheduler` -> `findDormantUserIdsWithoutCampaignExecution`. UserSummary의 마지막 구매 시각과 캠페인 실행 이력 anti join으로 500명씩 선정한다.
- 기존 근거: `docs/experiments/dormant-customer-reengagement-query-v1-plan.md`에 1M 고객, 휴면 50k, 기발송 10k에서 183ms -> 7ms가 기록되어 있다. 이미 도입한 `(last_purchase_at,user_id)` 및 UNIQUE `(reengagement_campaign_id,user_id)` 사례이며 새 발견/새 성과로 다루지 않는다.
- 구조 차이: UserSummary는 고객당 현재 상태이며 완료 작업 이력을 같은 행으로 계속 append하는 후보 테이블과 다르다. 고객과 발송 이력은 계속 필요한 데이터다.
- 제한: 하루 10시 배치에 완료 시간 목표는 확인되지 않았다. 새벽/정시 발송의 긴급성이나 전체 발급량 개선을 추가하지 않는다. user_id는 InnoDB PK이므로 `(last_purchase_at)`와 명시적 `(last_purchase_at,user_id)` 차이를 확인하기 전 두 컬럼이 모두 필수라고 주장하지 않는다.
- 다음 최소 검증: 기존 아티팩트 존재/조건과 결과 동등성 검사를 우선 리뷰한다. 현재 harness는 반환 건수 500만 검사하고 ID/순서 동일성은 검사하지 않는다. 발송 진행으로 이미 처리한 고객 비율이 늘어날 때 anti join 비용이 어떻게 바뀌는지도 초기 500건과 구분한다.
- 검증 비용: 낮음(기존 근거 검토), 추가 실행은 별도 결정. 기본 SQL 역량 사례로 검토하며 강한 사업상 장애 해결로 포장하지 않는다.

### 3순위: 전일 Purchase–Entry 불일치 탐지

- 실제 경로: 매일 03:00 `ReconciliationScheduler.detectGhostPurchases` -> `PurchaseRepository.findGhostPurchases`.
- 업무: 전일 CONFIRMED 캠페인 구매 중 참여 원장이 없는 건을 찾아 운영 이슈로 남긴다. 구매 원장을 보존하면서 날짜 범위를 조회하므로 완료 작업 이력 청소와는 다른 문제다.
- 현재 인덱스: Purchase의 활동 선두 인덱스들과 `(user_id,purchase_at)`은 있지만, 이 쿼리는 특정 활동 ID 동등 조건 없이 purchase_type/status/날짜로 시작한다. Entry의 `(campaign_activity_id,user_id)` UNIQUE는 NOT EXISTS 확인에 대응한다. 느리다는 측정은 없다.
- 다음 최소 검증: 실제 DB 인덱스와 EXPLAIN 확인 후에만 필요성 판단. 보관 기간, 하루 구매량, 캠페인 구매 비중을 분리한다. batch 소요 시간이 실제 탐지/복구에 문제가 되는지 먼저 확인하며 임의 SLA를 만들지 않는다.
- 검증 비용: 중간. Purchase/Entry fixture 및 정상/불일치 결과 검증 필요. 업무 빈도는 하루 1회라 1순위보다 우선도가 낮다.

### 우선 제외/보류

- `PurchaseRepository.findByUserId/findByProductId/findByPurchaseType/findByCampaignActivityId`와 `MarketingActionDispatchRepository.findFailureHistoryByActionSince`: 선언만으로 후보를 만들지 않는다. 현재 main 소스에서 호출 경로를 찾지 못했다.
- `CouponService.getApplicableCoupons`: 2026-10-16 재확인에서 실제 호출 경로 `/checkout` -> `StoreViewService.getCheckoutViewData` -> `CouponService.getApplicableCoupons`가 확인됐다. 앞선 미호출 판단을 정정한다. 주문서 렌더링이 쿠폰 조회를 기다리지만 기존 user_id 인덱스와 Coupon EntityGraph가 있고, 보통의 인당 쿠폰 수에서 느리다는 측정은 없다. 인당 쿠폰을 억지로 수천 건 쌓아 병목을 만들지 않는다. `/mypage`의 유효 쿠폰 조회도 별도로 존재한다.
- 최종 발송 실패 목록/triage 기간별 실패 집계: 실제 API 경로는 있지만 `(status,dlt_at)`와 `(execution_id,dispatch_sequence)` 인덱스가 이미 있고, 정상 운영의 실패 비율/운영 빈도가 확인되지 않았다. 장애를 인위적으로 대량 생성해 성과 근거로 삼지 않는다.
- 취소/환불 후 최신 CONFIRMED 구매 복구: 현재 `(user_id,purchase_at)`로 최신순 접근이 가능한 형태이며 고객별 이력/취소 분포에 크게 좌우된다. 결제 내부 책임 경계와 미확인 호출 빈도를 고려해 보류한다.
- 전역 대시보드 N+1, SSE 중복 집계, RFM keyset은 기존 개선이다. 신규 인덱스 후보로 다시 포장하지 않는다.

다음 행동은 1순위의 짧은 검증 범위를 정하는 것이다. 아직 새 fixture 생성, benchmark 실행, 인덱스 추가나 서비스 변경을 승인된 다음 작업으로 확장하지 않았다. 무거운 실험/구현은 사용자 지시대로 Luna에 위임하며, 한도/권한 문제면 부모가 조용히 대신 실행하지 않는다.

### 관리자 구매 목록 필터/정렬 SQL 후보 (2026-10-16, SQL-only)

아직 API/화면으로 구현되지 않은 구매 목록 패턴의 인덱스 설계만 별도 persistent SQL Lab에서 검토했다. 재사용한 `bench_purchases.purchases`는 1,000,000행이며 CONFIRMED 950,400건(2025-10-01 01:00–2026-10-07 23:00), CANCELLED 49,600건(2025-10-01–2026-09-30 04:00)이었다. fixture 최대 시각 다음 날 자정인 `2026-10-08 00:00:00`을 고정 종료로 두고 `[2026-09-08 00:00:00, 2026-10-08 00:00:00)` 30일을 비교했다. 기간 내 상태별 전체 건수는 CONFIRMED 67,850, CANCELLED 3,078이다. 데이터는 합성 분포이며 운영 데이터의 상태·날짜 비율을 대표한다고 보장하지 않는다.

같은 전체 Purchase 필드 SELECT, `status` equality, 반열린 날짜 범위, `ORDER BY purchase_at DESC, id DESC LIMIT 50`을 사용해 A(원래 인덱스만), B(임시 `(status)`), C(임시 `(status,purchase_at,id)`), D(임시 `(status,purchase_at)`)를 비교했다. 상태/설계마다 warm-up 1회와 서버 elapsed 5회, 전체 결과 50행과 정렬 순서 SHA-256 동등성을 확인했다. 각 임시 인덱스는 한 번에 하나만 만들었고, 생성·제거 뒤 원래 인덱스 집합과 fixture를 검증했다.

| 상태 | A 기존 인덱스 | B status | C status,date,id | D status,date |
| --- | ---: | ---: | ---: | ---: |
| CONFIRMED median ms | 371.059 | 2684.622 | 0.817 | 1.169 |
| CANCELLED median ms | 272.477 | 95.849 | 0.764 | 0.572 |

각 조건의 다섯 원시 표본, max, 인덱스 생성 시간, 전체 행 hash 및 plain/ANALYZE 실행계획은 artifact `build/benchmark/admin-purchase-list-20261016T021433Z/`에 있다. A는 1M 행 full scan 후 filesort였다. B는 상태 인덱스 조회 후 날짜 필터 및 filesort였고, 상태별 950,400/49,600건을 읽어 기간 내 67,850/3,078건으로 줄였다. C와 D는 두 상태 모두 선택한 복합 인덱스의 역방향 범위 scan을 사용했으며 plain EXPLAIN Extra는 `Using index condition; Backward index scan`이고 filesort는 표시되지 않았다. D의 `(status,purchase_at)` 선언만으로 `id DESC` 정렬이 포함된 같은 결과 순서가 나왔고, 명시적 `id`를 더한 C가 두 상태에서 일관된 시간 이득을 보이지 않았다. 이는 InnoDB 보조 인덱스의 PK suffix가 이 실행계획에서 활용된 것과 부합한다.

### 관리자 구매목록 인덱스 쓰기비용 확인 (2026-10-16, SQL-only)

사용자가 승인한 1M행 append 쓰기 비교에서 A(기존 Purchase 인덱스)와 B(동일 인덱스 + `(status,purchase_at)`)는 각각 원본 1,000,000행의 비공개 복사본에서 시작했다. 복사와 B 인덱스 빌드는 측정에서 제외했다(A 복사 21.9s, B 50.7s, B의 빈 전용 테이블에서 인덱스 빌드 161.8ms; 이는 1M행 인덱스 빌드 시간 아님). 각 라운드는 동일한 synthetic `CONFIRMED/SHOP` 20행 `INSERT SELECT` 트랜잭션 100개이며 COMMIT까지 서버시각으로 측정했다. 각 라운드 뒤 A/B에는 동일하게 누적 2,000행이 추가됐다.

| 라운드 | 순서 | A tx p50/p95 ms | B tx p50/p95 ms | A/B 전체 서버 경과 ms |
| --- | --- | ---: | ---: | ---: |
| 1 | A→B | 4.251 / 9.894 | 3.032 / 8.096 | 474.964 / 380.502 |
| 2 | B→A | 1.650 / 2.344 | 1.826 / 2.542 | 167.888 / 193.152 |
| 3 | A→B | 1.982 / 3.063 | 2.005 / 2.901 | 213.375 / 211.817 |
| 4 | B→A | 1.643 / 2.293 | 2.047 / 4.439 | 171.209 / 233.189 |

첫 라운드는 이후보다 양쪽 모두 크게 느렸고, 라운드별 우열도 뒤집혔다. 캐시 상태나 순서 영향 가능성은 있지만 원인을 입증하지 않았다. 실행 중 harness의 잘못된 20% A/B 차이 규칙이 세 번째와 네 번째 라운드까지 자동 실행했다. 이 편차는 숨기지 않고 전체 raw 표본과 함께 보존했으며, 추가 측정은 하지 않았다. 따라서 이 결과는 추가 복합 인덱스의 쓰기비용을 신뢰성 있게 정량화하지 못하며, 작거나 없는 비용으로 해석해서는 안 된다. 이전 빈 테이블 smoke(`admin-purchase-write-20261016T023131Z`)도 1M행 유지비용 근거가 아닌 역사적 smoke로만 취급한다.

완료 artifact `build/benchmark/admin-purchase-write-1m-20261016T025733Z/`에는 100개 tx/arm/라운드의 raw elapsed, 전체 라운드 통계, 복사·빌드 시간, 인덱스 서명 및 행 서명이 있다. 이 SQL `INSERT SELECT` append 패턴은 Spring/JPA·HTTP 쓰기 성능이나 다른 갱신/삽입 분포를 대표하지 않는다. 원본 구매 1M행 및 기존 인덱스 서명은 전후 일치했고, 후보 fixture 5M행/ready였다. 임시 A/B 테이블은 제거됐다.

### 일반 상품 목록 API 및 Store 조회 범위

`GET /api/v1/products`는 일반 상품만 반환하며 `category`(대소문자 무시), `page`(기본 0), `size`(기본 20, 1–100)를 받는다. 결과는 `id ASC`로 안정 정렬하고 `items/page/size/hasNext`만 제공한다. DTO에는 카드용 식별자·이름·브랜드·카테고리·이미지·정상가·판매가·할인율을 담고, 금액은 BigDecimal JSON 숫자이며 재고와 내부 campaignOnly 값은 노출하지 않는다. 잘못된 페이지/크기는 공통 `INVALID_REQUEST` 400 응답이다. 목록 GET만 공개했고 기존 `/api/v1/products/search` ADMIN 권한은 그대로다.

목록과 `/shop` 카테고리 화면은 repository `Slice` 조회에서 `campaignOnly=false` 및 선택 카테고리를 DB 조건으로 적용하고 `id ASC` 페이지 범위를 읽는다. category 일치는 trim 후 대소문자를 무시한다. 홈페이지는 TECH/FOOD/HOME/FASHION별 첫 4개를 각각 조회하며 지정 랭킹 ID만 조회해 기존 순서를 복원한다. 카테고리 SSR 페이지의 이전/다음 링크는 category와 page를 유지한다. 기존 화면 가격 포맷과 이미지 경로 변환은 유지한다.

대상 테스트: `ProductCatalogRepositoryTest`는 카테고리·campaign 전용 제외·페이지 경계/hasNext를, `StoreViewServiceTest`는 API 페이지 정렬·금액 DTO·입력 경계와 홈 카테고리 제한/랭킹 순서를, `SecurityConfigTest`는 익명 목록 공개와 search 관리자 권한을 확인한다. Core `compileJava`와 위 세 클래스 대상 13 tests가 통과했다. 전체 Core 테스트 suite는 실행하지 않았다. 목록 API는 새 기능이며 운영 사용량·인덱스 필요성은 측정하지 않았다. 구현은 서비스 조회 구조를 제한할 뿐, 성능 개선 측정이나 운영 장애 해결 근거가 아니다.

측정은 grouped warm-cache SQL 실행이며 다섯 표본의 median/max/raw만 사용했다(p95는 계산하지 않음). 인덱스 생성은 쿼리 시간에서 제외했고 개별 빌드는 약 2.6–4.4초였다. `Using filesort`만으로 디스크 spill이나 CPU 포화를 주장하지 않는다. 이 결과는 1M synthetic fixture의 SQL 설계 후보 비교일 뿐, 새 관리자 기능의 채택 결정·운영 병목·HTTP/E2E·쓰기 비용·SLA 증거가 아니다. 애플리케이션 엔티티, 마이그레이션, 서비스는 변경하지 않았다. 비교 뒤 purchases 행 수와 fixture metadata는 그대로였고 기존 인덱스 signature가 복원됐으며 `bench_admin_*` 인덱스는 남지 않았다. 후보 DB는 실행 전후 read-only로 ready/5M 행수를 확인했고 변경하지 않았다.

#### 관리자 구매 목록 인덱스 쓰기비용 smoke (2026-10-16)

읽기 후보 A(기존 인덱스 전부)와 B(추가 `(status,purchase_at)`)를 빈 private table에서 비교했다. `CREATE TABLE LIKE purchases`로 schema/index만 복제하고 1M fixture를 복사하지 않았다. warm-up 후 두 전용 테이블을 TRUNCATE해 같은 빈 시작 상태를 만들고, 고정 synthetic 20행 `INSERT SELECT`와 `COMMIT` 시간을 한 MySQL 세션에서 재었다. 초기 AB/BA 두 pair의 순위가 뒤집혀 두 pair를 더 추가했지만 네 표본에서도 순위가 다시 뒤집혔다. A 원시 ms `[4.195,3.038,4.080,3.912]`, median `3.996`; B `[3.112,5.203,2.949,5.050]`, median `4.081`; B 인덱스 build는 `214.494ms`로 query sample에서 제외했다. 이 차이는 표본 변동 내라 추가 인덱스의 쓰기 비용을 결론내릴 수 없다. 표본이 네 건/arm이므로 p95를 계산하지 않았다.

아티팩트 `build/benchmark/admin-purchase-write-20261016T023131Z/`에는 raw tx 기록, 동일 synthetic row hash, SQL, harness, 설정 및 identity가 있다. SQL INSERT SELECT는 Spring/JPA `IDENTITY` 저장과 다르며 빈 테이블 smoke는 1M 행 테이블의 저장 비용을 재현하지 않는다. 운영 쓰기 성능으로 일반화하지 않는다. 각 tx는 20행 commit을 확인했고 A/B row hash가 같았다. 실행 후 A/B 임시 테이블만 제거했으며 원본 purchases 1M 행/기존 인덱스와 candidates 5M fixture ready 상태가 유지됐다. 이 검증은 B 인덱스 채택·제거 결정을 단독 정당화하지 않는다.


### 실제 호출 목적 기준 조회 소재 재검토 (2026-10-16)

관리자 구매 목록 SQL 실험은 아직 API/화면이 없고 호출 업무도 확정되지 않았다. 실험 수치에 맞추어 신규 사용처를 만들어 이력서 성과로 쓰지 않는다. 다음은 현재 코드에서 확인한 호출 경로이며 새 부하 실험은 하지 않았다.

| 조회 경로 | 사용자와 지연 영향 | 현재 판단 |
| --- | --- | --- |
| 주문서 `/checkout` -> 적용 가능 쿠폰 | 주문서 화면이 상품/쿠폰 로딩을 기다림 | 실제 고객 경로. 사용자 인덱스와 EntityGraph가 이미 있어 병목 근거 없음. |
| Entry 참여 -> Core `/api/v1/validation` -> 최근 구매 조건 | HEAVY 조건이 있는 신규 참여에서 자격 응답 후 Redis 선착순 판정 진행. 재시도 토큰 반환은 이 경로를 우회함 | 실제 서비스 간 동기 조회. 현재 UserSummary PK 단건 조회라 신규 복합 인덱스 후보로 부적합. 실제 호출량은 미측정. |
| 활동 대시보드 -> `/api/v1/dashboard/activity/{id}` | 로드된 dashboard.js가 5초마다 반복 호출해 매출/전환/참여 현황 갱신. 캠페인 화면은 SSE로 5초마다 갱신 | 반복 수요가 코드에 존재. Purchase 집계는 이미 activity/status/period 인덱스가 있고 ES/Redis 작업도 포함하므로 SQL과 API 시간을 구분해야 함. |
| 캠페인 실행 준비 POST -> 대상 수/ID 확정 -> 스냅샷 저장 | 준비 API 응답과 이후 검토/발송이 대상 확정을 기다림 | 실제 업무 의존성은 있으나 자주 호출되는 고객 서빙 조회는 아님. 조회보다 대량 저장이 병목일 수 있으며 미측정. |

추천은 새 관리자 목록 API를 만드는 대신 기존 대시보드의 실제 API 비용을 먼저 보는 것이다. 기존 Global Dashboard의 캠페인별 반복 집계를 일괄 집계로 바꾼 개선과 Activity SSE의 중복 집계 제거는 위 7–9절에 이미 기록되어 있다. 이는 신규 발견/신규 인덱스 성과가 아니다. 전후 비교를 한다면 기존 변경의 의미를 확인하는 작업이며, 구매 지연이나 DB CPU 감소를 사전에 주장하지 않는다. 조건에 맞는 기존 조회는 이미 인덱스를 갖추고 있을 수 있고, 이 경우 신규 인덱스 소재를 억지로 만들지 않는다.


### 장바구니 후보 취소 조건 SQL 인덱스 확인 (2026-10-16, SQL-only)

구매/상품 제거 이벤트가 호출하는 `user_id + product_id + status IN (SCHEDULED, PROCESSING)` 취소 UPDATE만 검토했다. 현재 entity/migration의 PK, UNIQUE `(campaign_id,user_id,product_id)`, `(campaign_id,status,due_at,id)`를 유지한 A와, 같은 구성에 `(user_id,product_id)`를 더한 B를 비교했다. 100,000행 private synthetic fixture는 20개 campaign, 25,000명, 사용자당 서로 다른 상품 4개, SCHEDULED 99,500/PROCESSING 500이다. fixture는 완료/취소 이력이 쌓여 생긴 병목이 아니다. 각 round/arm 25개 매칭과 25개 fixture에 없는 user-id를 UPDATE+COMMIT으로 측정했고, warm-up/setup은 제외했다.

| Round/order | A match p50/p95 ms | A absent-user p50/p95 ms | B match p50/p95 ms | B absent-user p50/p95 ms |
| --- | ---: | ---: | ---: | ---: |
| 1 A→B | 32.499 / 36.459 | 27.924 / 32.721 | 3.149 / 4.890 | 0.066 / 0.120 |
| 2 B→A | 32.040 / 32.927 | 27.752 / 28.833 | 3.412 / 4.814 | 0.062 / 0.103 |

A UPDATE EXPLAIN chose PRIMARY full index scan (estimated 100,000 rows); B chose the new composite range index (estimated 1 row). These are optimizer estimates; actual rows examined were not collected. Separately, one short B→C comparison tested composite `(user_id,product_id)` against user-only `(user_id)`: matched p50/p95 2.795/4.238 vs 3.530/7.388 ms, and absent-user no-match 0.072/0.124 vs 0.111/0.188 ms; EXPLAIN estimates 1 vs 4 rows. This single, non-counterbalanced B-then-C check is order/cache-sensitive and does not establish that the composite key is required. A and B primary rounds used AB/BA order, two rounds only.

Artifacts and per-transaction raws: `build/benchmark/abandoned-cart-cancel-100k-20261016T052824Z/`; C comparison: its `user-only-vs-composite/` subdirectory. EXPLAIN plans and harness sources are included. Three earlier attempts failed before measurement and are labeled failed in their own artifacts. Source candidate fixture stayed 5,000,000 rows/ready with original index signature; all private tables were removed. This result is SQL-only: it does not measure Spring/JPA/Kafka consumer behavior, throughput, row-lock contention, HTTP or purchase persistence latency, and does not prove an operational incident or mandate an application index.

### 상품 후기 API 및 상품별 최신 목록 (2026-10-16)

`ProductReview`를 추가해 확정 구매 1건당 텍스트 후기 하나를 등록하고 상품별 후기를 조회한다. `POST /api/v1/products/{productId}/reviews`는 `CustomOAuth2User.userId`만 작성자로 사용하고 구매 PK 조회 후 구매자/상품/CONFIRMED 상태를 확인한다. 요청은 rating 1–5, trim 후 content 1–1000자이며, 누락 상품/구매는 404, 검증·구매 불일치는 400, 같은 구매의 기존 후기 또는 DB 유니크 경쟁은 409다. 응답은 201 body이며 개별 조회가 없으므로 Location은 보내지 않는다. `GET`은 익명 허용, 기본 page=0,size=20(1–100), `createdAt DESC,id DESC`의 `Slice`와 최소 필드(id/rating/content/createdAt)를 돌려준다. 전체 COUNT는 수행하지 않는다. 사진, 평점 집계, 편집·삭제, 추가 정렬과 UI는 포함하지 않았다.

Entity와 `scripts/migrations/2026-10-16-create-product-reviews.sql`에 `purchase_id` UNIQUE와 목록 인덱스를 정의하고, 상품 FK와 구매 FK는 migration에 명시한다. 목록 인덱스는 `(product_id,created_at)`이며 단일 product 인덱스를 중복 유지하지 않는다. InnoDB PK suffix 때문에 id를 인덱스 선언에 넣어야 한다고 주장하지 않는다. 결제/Purchase 내부 구현과 기존 Product/Purchase 엔티티는 수정하지 않았다.

검증은 Core focused test 20개 성공: service 7, repository 2, controller 2, 기존 `SecurityConfigTest` 9; `compileJava` 통과. 확인 범위는 소유/상품/상태, 잘못된 content·rating, 404/409, DB unique 위반, 실제 Slice 동률 ID/경계/타 상품 격리, principal 기반 작성자, GET 공개·POST 인증 및 기존 관리자 상품 경로 보존이다. 전체 suite는 실행하지 않았다.

#### 인덱스 비교 (SQL-only, 초기 실행 기록; 최종 결과는 아래 영속 fixture 절)

Artifact: `build/benchmark/product-review-index-20261016T074710Z/`. 전용 persistent MySQL Lab identity `axon-local-sql-lab`의 MySQL 8.0.46, loopback `127.0.0.1:13306`을 확인했다. 원본 candidate 5M / purchase 1M rows, metadata, 기존 인덱스 서명은 실행 전후 일치했다. 후기 1M건은 1,000개 synthetic 상품에 1×10,000 + 9×5,000 + 940×1,000 + 50×100으로 분산했다. 이는 실험 가정이며 무신사 전체 후기 분포의 추정이 아니다. 본문은 100–300자, 상품/시각은 결정적 seed, 같은 시각 동률 포함, product group을 PK 순서에 몰지 않는 결정적 ID permutation이었다.

A는 PK + UNIQUE purchase + `product_id` 단일 인덱스, B는 같은 UNIQUE를 유지하고 `(product_id,created_at)` 복합 인덱스로 단일 인덱스를 대체했다. 같은 SQL `WHERE product_id=? ORDER BY created_at DESC,id DESC LIMIT 21`의 두 AB/BA 측정 라운드, MySQL 서버 안 elapsed(ms):

| 상품 내 후기 수 | A 단일 인덱스 median | B 복합 인덱스 median | A raw | B raw |
|---:|---:|---:|---:|---:|
| 100 | 1.304 | 0.661 | 1.345, 1.263 | 0.711, 0.610 |
| 1,000 | 7.797 | 1.535 | 7.497, 8.096 | 1.874, 1.196 |
| 10,000 | 79.963 | 1.390 | 87.390, 72.536 | 1.954, 0.826 |

전체 ordered rows SHA-256와 페이지 21건 hash가 각 상품에서 A/B 및 모든 표본 간 일치했다. EXPLAIN에서 A는 단일 상품 인덱스 후 filesort, B는 `(product_id,created_at)` reverse index scan으로 정렬 작업이 없었다. ID를 인덱스 컬럼으로 명시하지 않은 계획에서 관찰된 결과이며 다른 DB/버전 일반화는 하지 않는다.

실험은 SQL-only다. synthetic purchase/user ID를 사용했고 후기 테이블에는 구매 FK가 없어 구매 연결·HTTP/API/JPA 응답 시간은 검증하지 않았다. 이 실행의 private schema는 entity보다 좁은 `rating TINYINT`, `content VARCHAR(300)`을 사용했다(entity/migration은 INT/VARCHAR(1000)); 생성 본문 자체는 모두 100–300자라 조회 payload 길이는 가정 범위 안이지만 물리 스키마가 일치하지 않는 한계다. grouped warm sample 두 번뿐이다. 특히 10k B가 1.954/0.826ms로 2.37배 변해, 새 spread-only 기준이라면 세 번째 표본을 추가할 조건이었다. 세 번째는 수집되지 않았다.

실행한 harness의 기존 `finally`가 후속 보존 지시 전에 자기 private schema `bench_reviews_20261016_074710`를 삭제했다. 원본 5M/1M fixture와 인덱스에는 변경이 없으며, 이 private fixture는 재생성하지 않았다. raw result, 실행 harness snapshot, A/B DDL·seed SQL, EXPLAIN/EXPLAIN ANALYZE는 위 artifact에 보존했다. 당시 후속 reusable harness `scripts/benchmark/product-review-index-benchmark.py`는 source fixture identity/count/index 서명과 fixture 재사용 guard를 반영하도록 수정되었고, 이후 영속 fixture 재사용 실행을 아래 절에 기록했다. 수정본은 순차 PK 적재, entity와 같은 INT/VARCHAR(1000), page hash assertion, A/B 효과 크기와 독립된 라운드 변동 규칙을 반영했다.

#### 영속 fixture 재사용 및 최종 후기 인덱스 비교 (2026-10-16)

위 첫 실험 뒤 사용자 지적에 따라 setup을 고쳤다. persistent schema `bench_reviews`를 한 번 생성해 A/B 각 1M행으로 보존하고, metadata owner/version/seed/ready, application 타입, 행수, 인덱스 및 상품별 분포를 검사한 뒤 재사용한다. runner에는 자동 DROP/reseed 경로가 없다. 초기 적재는 결정적 ID permutation을 유지하면서 PK 오름차순 INSERT, B는 A의 PK 순서 복사, secondary index는 적재 후 생성했다. 실행 중 약 45초 시점에 B 복합 인덱스 생성 단계였고 한 분 내 seed 완료를 확인했지만, 검증 오류로 create 함수가 반환되지 않아 단계별 정확한 setup ms는 보존되지 않았다. 따라서 빠른 setup은 순차 PK 적재와 인덱스 사후 생성이 결합된 효과이며 각각의 효과로 분리해 주장하지 않는다. 복구 과정에서는 기존 테이블을 변경·재생성하지 않고, 마지막 빈 `information_schema` 필드 구분자가 SQL helper에서 제거되는 서명 검증 버그만 고친 뒤 read-only 검증을 통과하고 소유 metadata의 `ready`만 1로 설정했다. 수정된 `status` 실행으로 fixture 재사용을 확인했다.

최종 run artifact `build/benchmark/product-review-index-reuse-20261016T081151Z/`의 `raw-and-summary.json`에는 raw 표본, 환경, hash, 원본 전후 signature, 계획 및 실행 harness/SQL이 있다. A는 PK + UNIQUE purchase + 단일 product 인덱스, B는 PK + 같은 UNIQUE + `(product_id,created_at)` 인덱스다. 동일한 조회는 `WHERE product_id=? ORDER BY created_at DESC,id DESC LIMIT 21`; server elapsed(ms)는 MySQL 안에서 측정해 Docker/mysql client 시작 시간을 제외했다. warm-up 뒤 두 AB/BA 라운드를 재고, 같은 arm의 두 표본 간 편차가 25%를 넘은 medium/popular에만 세 번째 pair를 추가했다. 세 번째 후에도 변동 기준을 넘었지만 네 번째는 실행하지 않았다.

| 상품 내 후기 수 | A 단일 인덱스 median (ms) | B 복합 인덱스 median (ms) | A raw (ms) | B raw (ms) | 표본 |
|---:|---:|---:|---:|---:|---:|
| 100 | 1.160 | 0.652 | 1.196, 1.123 | 0.708, 0.595 | 2 |
| 1,000 | 6.803 | 0.734 | 6.803, 6.415, 7.409 | 0.734, 0.555, 0.743 | 3 |
| 10,000 | 69.516 | 0.594 | 88.190, 63.455, 69.516 | 0.709, 0.449, 0.594 | 3 |

모든 warm-up/측정 sample의 첫 페이지 hash와 전체 ordered rows hash가 A/B에서 일치했고, 전체 hash는 보존한 이전 논리 fixture hash와도 일치했다. EXPLAIN은 A에서 단일 product 인덱스 뒤 filesort, B에서 복합 인덱스의 backward scan을 선택했다. 이 실행에서 B의 차이는 뚜렷했지만 세 표본의 범위는 흔들리므로 수치는 이 SQL fixture/서버의 관측값으로만 해석한다. optimizer 선택은 자연 상태로 기록했고 강제 인덱스를 사용하지 않았다.

이 최종 fixture는 MySQL 8.0.46의 SQL-only synthetic 자료다. 구매/user id는 synthetic이며 review에 purchase FK를 두지 않아 관계 무결성, HTTP/API/JPA 지연이나 운영 성능을 측정하지 않는다. 1,000개 상품의 1×10k + 9×5k + 940×1k + 50×100 분포는 실험 가정이고 실제 상점 분포 추정이 아니다. 본문은 100–300자, 물리 컬럼은 app과 같이 INT/VARCHAR(1000), timestamp tie를 포함한다. A/B 각 1M행이므로 전체 테이블 크기 증대의 영향을 평가한 실험이 아니며, 작은/중간/인기 개별 상품에서 선택한 동일 조회의 계획과 응답만 비교했다. 원본 purchases 1M 및 candidates 5M의 metadata/count/index signature는 전후 동일하고 최종 `status` 검사에서도 확인했다. `bench_reviews`의 ready fixture는 재사용을 위해 남겨뒀다. 직전 fail-closed artifact `build/benchmark/product-review-index-reuse-20261016T080845Z/`는 서명 오검출을 포함한 실패 기록으로 보존한다.

#### 상품별 낮은 별점 후기 최신순 SQL 비교 (2026-10-16)

별점 필터가 있는 조회를 추가 비교했다. query는 `SELECT id,rating,content,created_at FROM reviews_{a|b} WHERE product_id=? AND rating=1 ORDER BY created_at DESC,id DESC LIMIT 21`이다. A는 기존 PK+purchase UNIQUE+`product_id`, B는 기존 PK+purchase UNIQUE+`(product_id,created_at)`, C는 B 테이블에 `(product_id,rating,created_at)`를 임시 추가한 상태다. C가 보이는 동안에는 C query만 측정하고, C index를 제거한 뒤 B를 측정했다. 따라서 B arm은 optimizer가 C를 선택할 수 없는 원래 index signature에서 측정했다. C index create/drop 서버 시간은 별도 기록하고 query elapsed에서 제외했다. round 순서는 A→B→C 및 C→B→A로 반대 배치했으며 강제 인덱스는 사용하지 않았다.

rating 분포는 전체 1M 행에서 각 rating 200,000건으로 실제 집계됐다(이 seed에서 rating=1 20%). 대상 상품도 100건 중 20건, 1,000건 중 200건, 10,000건 중 2,000건이었다. 이는 결정적 synthetic seed의 관측이지 실제 후기 평점 분포 가정이 아니다. 작은 상품은 rating=1 결과가 20행이라 LIMIT 21 쿼리가 20행만 반환했고, 중간/인기 상품은 첫 페이지 21행을 반환했다.

| 상품 후기 수 | rating=1 행수 | A `product_id` median ms | B `(product_id,created_at)` median ms | C `(product_id,rating,created_at)` median ms | 측정 수 A/B/C |
|---:|---:|---:|---:|---:|---:|
| 100 | 20 | 0.848 | 1.144 | 0.470 | 2 / 2 / 2 |
| 1,000 | 200 | 4.128 | 1.398 | 0.441 | 2 / 2 / 2 |
| 10,000 | 2,000 | 74.069 | 1.552 | 0.512 | 3 / 3 / 3 |

인기 상품의 첫 두 표본에서 same-arm spread가 25%를 넘어 인기 상품만 세 번째 A/B/C sample을 추가했다. 세 번째 후에도 A/B 변동이 25%를 넘었으며 네 번째는 실행하지 않았다. raw 값은 artifact `build/benchmark/product-review-rating-filter-20261016T083902Z/raw-and-summary.json`에 저장했다. 전체 ordered rows와 page hash가 모든 arm에서 같았다: full row counts 20/200/2,000이며 EXPLAIN ANALYZE의 반환 행도 각각 20 또는 21이었다. SQL server elapsed는 Docker/mysql client 시작을 제외한다.

EXPLAIN ANALYZE에서 A는 단일 product index로 대상 상품 전체 100/1,000/10,000건을 읽고 rating filter 후 filesort했다. B는 `(product_id,created_at)`의 reverse scan으로 filesort를 피하고 rating 잔여 필터를 적용했다. 중간·인기 상품에서 첫 페이지 21행을 반환하기 위해 각각 131/133행을 읽었다. C는 새 composite index의 reverse range scan으로 중간·인기 대상에서 21행, 작은 대상에서 20행만 읽었다. 따라서 기존 정렬 인덱스 B도 이 synthetic 20% rating=1 조건에서 쿼리 median 1.14–1.55ms였고, C의 추가 개선은 약 0.68–1.04ms 절대 차이였다. C는 읽는 행 수를 줄였지만 추가 index의 저장/쓰기 비용은 측정하지 않았고, rating filter API도 아직 없어 이 실험만으로 제품 index 채택이나 API 확장을 결정하지 않는다.

실행 harness `scripts/benchmark/product-review-rating-filter-index-benchmark.py`, base fixture verifier snapshot, query, plan, rating 집계, EXPLAIN/EXPLAIN ANALYZE 및 C index 생성/삭제 시간은 같은 artifact에 보존했다. C index는 자기 harness의 `finally` 정리로 제거됐고, 후속 `status`와 restore 검사가 ready metadata, 각 1M행, A/B index signatures, 원본 purchases/candidates count 및 signatures의 전후 동일성을 확인했다. A/B 후기 데이터는 재적재·복사하지 않았고 source 및 기존 fixture는 보존했다.

#### 체형 snapshot 및 도움순 동적 필터 SQL 비교 (2026-10-16)

고정 필터 `상품 + 구매 당시 L 사이즈 + 키 175–180cm + 몸무게 70–80kg`와 `도움수 DESC, review_id DESC LIMIT 21` 조회를 비교했다. 세 arm은 동일한 SQL 본문 join을 사용한다. 선택은 `review_attributes`에서 수행하고 결과 본문·평점·작성일은 기존 `reviews_b`에 review PK로 join한다. 이 side table의 product/size/height/weight/helpful 값은 실험용 review-time snapshot이다. 애플리케이션에 이런 entity/API나 저장 구조가 구현됐다는 뜻은 아니다. 후기 본문은 복사하지 않았다.

기존 1M review rows에서 PK 순서로 `review_attributes` 1M행을 한 번 적재했다. 첫 setup은 CREATE TABLE 31ms, ordered seed 6,165ms, A index 생성 2,383ms, ANALYZE 10ms였다. 별도 seed를 반복하지 않고 완성된 table을 persistent ready fixture로 재사용한다. 합성 size는 체중 구간에 따라 선택 확률을 바꾸되 독립 roll을 함께 써서 특정 체형에 고정되지 않게 했고, 키·몸무게도 서로 양의 상관을 둔 결정적 합성값이다. 이 생성 범위는 키 150–195cm, 몸무게 42–85kg이었다. 도움수는 독립 roll로 70% 0, 20% 1–4, 8% 5–50, 2% 51–1,000의 편중을 만들었다. 이 값은 실제 이용자·후기 분포가 아니다.

| 합성 크기 분포 | 행수 | 합성 키/몸무게 | 도움수 0 |
|---|---:|---|---:|
| XS / S / M / L / XL | 123,291 / 206,272 / 286,776 / 250,988 / 132,673 | 키 150–195cm, 평균 172.49cm; 몸무게 42–85kg, 평균 63.45kg | 700,000 / 1,000,000 (70%) |

조회 대상의 실제 일치 행은 상품 951의 100개 후기 중 1개, 상품 11의 1,000개 중 8개, 상품 1의 10,000개 중 110개였다. 이 때문에 첫 두 상품은 LIMIT 21보다 적은 1개와 8개만 반환했다. 측정 데이터는 일치 행을 인위적으로 21개까지 늘리지 않았다.

arm은 한 번에 하나의 보조 인덱스만 존재하도록 전환했다. A는 `product_id`, B는 `(product_id,purchase_size,height_cm,weight_kg,helpful_count)`, C는 `(product_id,purchase_size,helpful_count)`이다. 같은 필터/정렬/join SQL에서 A→B→C와 C→B→A 두 회차를 비교하고, 같은 arm 변동이 25%를 넘은 세 상품 모두에 세 번째 표본을 추가했다. 세 번째 후에도 일부 변동이 컸지만 네 번째 회차는 실행하지 않았다. DDL 시간은 별도 기록했고 query elapsed에서는 제외했다.

| 상품 후기 수 | 일치 행 | A 상품 인덱스 median ms | B 필터 우선 median ms | C 도움순 우선 median ms | 표본 A/B/C |
|---:|---:|---:|---:|---:|---:|
| 100 | 1 | 1.273 | 0.300 | 0.467 | 3 / 3 / 3 |
| 1,000 | 8 | 10.298 | 0.357 | 1.419 | 3 / 3 / 3 |
| 10,000 | 110 | 21.855 | 0.523 | 2.428 | 3 / 3 / 3 |

원시 표본, query, harness, 실제 분포, 환경, 각 arm EXPLAIN 및 EXPLAIN ANALYZE는 `build/benchmark/product-review-dynamic-filter-20261016T091345Z/`에 있다. 세 arm의 full ordered 결과와 page hash는 같았다. 실제 계획에서 A는 상품 인덱스로 100/1,000/10,000개 attribute 행을 읽고 일치 행을 정렬했다. B는 복합 range scan으로 각각 5/28/288개 attribute 행을 확인한 뒤 1/8/110개를 정렬했다. C는 sort를 피하고 도움순 reverse scan했지만 키·몸무게 조건이 잔여 필터라 각각 27/249/632개 attribute 행을 읽어 1/8/21개를 반환했다. 본문 PK lookup은 join 순서상 page 반환 행에만 발생해 각각 1/8/21회였고, 후보 전체 본문을 미리 읽지 않았다. 작은/중간에서는 B가 C보다 빨랐고 인기 상품에서도 B median 0.523ms, C 2.428ms였다. 따라서 이 합성 분포에서는 두 range 조건을 먼저 좁힌 뒤 소량을 정렬하는 B가, 도움순으로 더 많이 훑는 C보다 나았다. 도움순 index가 정렬을 없앤다는 사실만으로 선택하지 않는다.

세션 중 실수로 같은 runner를 한 번 더 시작한 두 번째 실행 artifact `build/benchmark/product-review-dynamic-filter-20261016T091602Z/`도 raw와 함께 별도 보존했다. 이는 원 계획의 표본 수에 합치지 않았고 결론의 median에도 포함하지 않았다. 해당 재실행은 attribute table을 재사용했으며 완료 후 A index/source/review signatures가 복원됐음을 artifact와 읽기 전용 status로 확인했다. 재발 방지를 위해 runner는 `run`/`status` CLI를 구분한다. `status`는 fixture/source/index를 읽기만 하고 seed, DDL, query sample을 시작하지 않는다. 미지정 명령은 `run`, 알 수 없는 인수는 argparse 오류다.

인덱스를 반복 생성·삭제하면서 InnoDB 통계와 buffer/cache 상태가 arm 순서에 영향을 줄 수 있고, 실제 표본에서도 특히 A 시간이 흔들렸다. 이는 안정된 운영 지연이나 효과 배수의 근거가 아니다. 비교는 MySQL server-side SQL time이며 Docker/client 시작 시간은 제외했지만, API·HTTP/JPA, concurrent traffic, index write/storage 비용은 측정하지 않았다. 기존 1M `reviews_a/b`와 source purchases 1M/candidates 5M은 변경하지 않았다. 새 `review_attributes` 1M snapshot은 reuse용으로 남겼고 마지막 canonical index는 A(`product_id`)로 복원했다. rating filter, snapshot entity, API, migration 변경은 하지 않았다.

### 고객별 캠페인 참여 이력 조회: 사용자 키 인덱스 비교 (2026-10-17)

`CampaignActivityEntry`에는 `(campaign_activity_id,user_id)` 유니크 키가 있으나 고객별 이력 조건 `WHERE user_id=? ORDER BY requested_at DESC,id DESC LIMIT 21`은 그 키의 선두 컬럼을 사용하지 못한다. 동일한 스칼라 projection으로 PK-only(A0, 진단용), 앱의 PK+UNIQUE(activity,user)(A1, 기본), A1+`user_id`(B), A1+`(user_id,requested_at)`(C)를 비교했다. A0는 업무 유니크 제약을 제거하자는 설계가 아니다. 기본 후보는 A1이며 B/C는 고객 이력 접근 경로의 추가 인덱스 비교다.

실험은 SQL Lab MySQL 8.0.46의 persistent `bench_entries.campaign_activity_entries` 1M행을 사용했다. 합성 분포는 고객 100,000명×각 10건, 활동 100개×각 10,000건이다. 요청 시각은 결정적 permutation에서 10행 단위 초로 만들어 동률을 포함했고, 상태·상품·처리시각·추가정보도 엔티티 타입에 맞춘 합성값이다. 활동/사용자 ID는 synthetic이고 FK는 두지 않았다. 이는 앱/운영 분포 추정이 아니다. 표본 고객 1,001·50,001·99,999는 모두 10건이라 LIMIT 21에서 10행만 반환하며 다음 페이지가 없었다. status 조건이나 Activity 상세 JOIN은 사용하지 않았다.

| arm | 조회 인덱스 | user별 조회의 EXPLAIN ANALYZE 관측 | 중앙 SQL 시간(ms): 1,001 / 50,001 / 99,999 |
|---|---|---|---:|
| A0 | PK만 | 테이블 1,000,000행 scan 후 filesort, 10행 반환 | 245.2005 / 223.7715 / 215.874 |
| A1 | PK + UNIQUE(activity,user) | A0와 같이 전체 scan 후 filesort, 10행 반환 | 237.4305 / 233.356 / 261.953 |
| B | A1 + `user_id` | 사용자 인덱스로 10행 lookup 후 10행 sort | 0.4365 / 0.392 / 0.3615 |
| C | A1 + `(user_id,requested_at)` | 복합 인덱스 reverse scan, 10행 반환, sort 없음 | 0.927 / 0.562 / 0.4295 |

각 arm에 warm-up 1회 후 두 라운드를 A0→A1→B→C 및 C→B→A1→A0 순서로 측정했다. 같은 arm 표본 편차가 25% 초과한 C의 고객 1,001·50,001 및 A1의 고객 99,999에만 세 번째 표본을 추가했다. 모든 arm에서 반환 행수와 ordered full-row hash가 일치했다. 기본 A1은 사용자 선두 인덱스가 없어 1M행을 읽었고, B/C는 각 사용자 10행만 읽었다. 이 fixture에서는 B가 이미 0.36–0.44ms였고 C는 0.43–0.93ms였다. C는 sort를 없앴지만 B보다 일관된 응답 이득을 보이지 않았다. 이 결과만으로 실제 호출 빈도나 운영 인덱스 채택을 단정하지 않는다.

성공 측정 raw·median·plain/ANALYZE 계획·SQL·실행 harness는 `build/benchmark/campaign-entry-history/20261017-013820/`에 보존했다. `bench_entries` metadata owner/version/seed/ready, 1M exact count, 컬럼 타입, 인덱스 서명을 검증했고 최종 A1을 복원했다. 원본 candidates 5M, purchases 1M 및 기존 review/attributes fixture의 전후 count/index signature도 일치했다. 첫 ordered PK seed는 약 10초 내 완료된 것이 관찰됐으나 직후 schema verifier 오류로 정확한 seed timer가 보존되지 않았고 인덱스별 DDL elapsed도 기록하지 못했다. 이를 정확한 setup 수치로 제시하지 않는다. 중간 harness 오류 artifact `013629`, `013705`, `013734`, `013805`는 각각 DDL 구두점, 적재 뒤 검증 기대 문자열, 인덱스 이름 부분검색, 변수 선언 순서 문제를 남긴 실행 기록으로 보존했고, 이 오류를 고친 뒤의 성공 표본만 표에 사용했다. 1M 재적재는 하지 않았다.

결과는 SQL-only 합성 고정 데이터의 MySQL 실행이며 HTTP/JPA 응답, 동시 트래픽, 사용자당 이력 증가, 추가 인덱스 쓰기·저장 비용은 측정하지 않았다. arm 전환 DDL과 ANALYZE가 cache/statistics에 영향을 줄 수 있다. 따라서 이 자료는 고객별 이력 조회가 별도 접근 경로가 될 때의 계획 차이를 확인하는 근거이지 API/운영 처리량의 성능 주장이나 업무 UNIQUE 제약 변경 제안이 아니다.

#### 3M 고객별 캠페인 참여 이력 확대 확인 (2026-10-17)

같은 조회와 필드를 3M행에서 확인하기 위해 별도 persistent table `bench_entries.campaign_activity_entries_3m` 및 owner metadata를 만들었다. 기존 1M `campaign_activity_entries`는 재사용·변경하지 않았다. 분포는 synthetic 고객 300,000명×10건, Activity 300개×10,000건이다. 3M requested_at 순서는 `MOD(n*7919,3,000,000)/30`초로 만들었다. 표본 고객 1,001·50,001·99,999는 각 10행을 반환했다. Coupon/UserCoupon 발급 이력은 이 Activity Entry projection에 포함하지 않았다.

| 고객 | A0 PK-only raw ms | A1 PK+UNIQUE raw ms | B +user_id raw ms | C +(user_id,requested_at) raw ms |
|---:|---:|---:|---:|---:|
| 1,001 | 905.577, 789.742 | 836.998, 877.152 | 0.507, 0.946, 0.676 | 0.531, 0.334, 0.530 |
| 50,001 | 783.270, 759.598 | 776.518, 781.935 | 0.565, 0.423, 0.560 | 3.344, 1.140, 0.473 |
| 99,999 | 775.693, 760.801 | 777.708, 764.791 | 0.506, 0.478 | 0.903, 0.333, 0.491 |

Median은 각각 A0 847.660/771.434/768.247ms, A1 857.075/779.227/771.250ms, B 0.676/0.560/0.492ms, C 0.530/1.140/0.491ms였다. 1M과 마찬가지로 A0/A1은 전체 3M table scan 후 sort, B는 사용자별 10행을 읽고 sort, C는 10행 reverse index lookup으로 sort 없이 반환했다. B와 C 모두 훨씬 적은 행을 읽었고 이 조회에서는 C의 추가 이득이 일관되지 않았다. A0는 진단군이며 A1 업무 유니크 키는 최종 보존했다.

성공 raw/계획/harness/DDL/seed는 `build/benchmark/campaign-entry-history-3m/20261017-102350/`에 있다. `setup-events.json`은 각 DDL·seed 명령의 client wall 시간을 실행 중 즉시 기록했다. 순차 PK seed는 42.789초, table 생성 0.107초, 첫 UNIQUE/B/C 인덱스 생성은 각각 7.858/8.471/9.226초였다. 이 wall 값은 Docker/mysql client 시작을 포함하며 server 내부 seed SQL 시간으로 해석하지 않는다. 조회 시간은 MySQL server elapsed다. 동일 arm 두 표본 편차가 25% 초과한 B/C 대상에만 세 번째 sample을 추가했다.

모든 arm/page의 10개 ordered row hash가 일치했고 3M metadata ready=1·exact count·최종 A1 index를 확인했다. 실행 전후 기존 1M entry metadata/count/index, candidates 5M·purchases 1M의 metadata/count/index, 기존 후기/attributes fixture의 count/index가 동일했다. 합성 ID는 FK가 없는 SQL fixture이고 상품/쿠폰 통합 고객 이력, HTTP/JPA, 실제 고객 분포나 운영 처리량은 측정하지 않았다. DDL 전환과 ANALYZE는 cache/statistics를 바꿀 수 있다. `run --scale 1m|3m`으로 명시 실행하며 `status --scale ...`은 read-only 검증이다. 새 계획 문서는 만들지 않았다.

#### 고객 캠페인 참여 이력 API (2026-10-19)

`GET /api/v1/me/campaign-entries?page=0&size=20`는 인증 principal의 `CustomOAuth2User.userId`만 사용한다. `page >= 0`, `1 <= size <= 100`을 공통 400 예외로 검증하고 `requestedAt DESC,id DESC`로 정렬한 `Slice`를 반환하므로 전체 count 쿼리는 없다. 응답 필드는 `items/page/size/hasNext`; 각 항목에는 id, 행사 ID/이름/유형, 상태, 요청·처리 시각, productId만 포함하며 userId와 additional_data는 노출하지 않는다. JPQL DTO projection에서 행사 세 scalar를 한 번에 조인 조회한다. 쿠폰 발급/미발급 이력은 포함하지 않으며 기존 관리자 Activity entries API는 변경하지 않았다.

`CampaignActivityEntry`의 기존 `(campaign_activity_id,user_id)` 업무 UNIQUE를 유지하면서 `user_id` 단일 인덱스를 추가하고 `scripts/migrations/2026-10-19-add-campaign-entry-user-index.sql`에 수동 migration을 기록했다. DB migration은 실행하지 않았다. 검증은 `CampaignQueryOptimizationRepositoryTest`, `CampaignEntryHistoryServiceTest`, `SecurityConfigTest`의 focused 실행으로 컴파일 포함 성공했다. JPA 테스트는 다른 고객 행 격리, 동시간 요청의 id 내림차순, Slice 경계를 확인하고, 보안 테스트는 익명 GET 거절을 확인한다.

승인된 HTTP A/B는 보존된 `bench_entries.campaign_activity_entries_3m`을 대상으로 수행했다. 실행 전 lab identity, source owner/version/seed/ready, 3,000,000행 count와 정확한 A1 인덱스 signature를 검사했다. 임시 `bench_entry_api` schema의 owner-guarded activity table 300행과 MERGE view를 통해 기존 source를 읽었고, 마지막에 해당 임시 schema를 삭제했다. Source table은 재적재하지 않았다. 실행 후 `status --scale 3m`에서 metadata/행수/컬럼과 A1 인덱스 signature를 다시 확인했다.

세 synthetic 고객(1,001, 50,001, 99,999)은 각각 10건을 반환해 기본 LIMIT 20에서 `hasNext=false`였다. 익명 호출 401, 잘못된 page/size 400, 임의 `userId` query parameter 무시, 고객별 결과 분리와 A/B ordered JSON 동일성을 확인했다. 각 arm은 고객별 warm-up 1회와 두 측정값을 사용했으며, 같은 arm 값 차가 25%를 넘은 고객에게만 세 번째 측정을 추가했다. HTTP client wall(ms, request/serialization 포함)은 다음과 같다.

| 고객 | A1 (기존 UNIQUE만) | B (+user_id 단일 인덱스) |
|---:|---:|---:|
| 1,001 | 11, 11 | 7, 7 |
| 50,001 | 9, 7, 8 | 7, 5, 5 |
| 99,999 | 8, 8 | 5, 4 |

A1 계획은 300개 activity scan 후 `(campaign_activity_id,user_id)` unique lookup을 300회 수행해 10행을 반환했다. B는 `user_id` index에서 10행을 읽고 sort한 뒤 activity PK를 10회 조회했다. 이 DTO join으로 A1도 이미 빠르게 실행되어 세 고객의 HTTP client-wall median 차이는 3–4ms였다. B 인덱스 DDL의 local client wall은 9,481ms였으며 요청 시간에는 포함하지 않았다. A1→B의 순차 DDL/cache transition이 결과에 영향을 줄 수 있다.

HTTP 측정은 테스트 전용 `X-Benchmark-UserId` filter로 실제 `CustomOAuth2User` principal을 만들었다. production JWT/`SecurityConfig`, coupon/UserCoupon 이력, 실제 고객별 이력 분포, 운영 부하를 측정하지 않았다. 이전 SQL-only 3M 실험은 activity metadata JOIN이 없었으며 A1은 약 0.8초(800ms), user index arm은 약 0.5ms였으므로 해당 값을 API baseline으로 삼지 않았다. artifact와 실행용 harness는 `core-service/build/benchmark/campaign-entry-history-http/20261019-101304/` 및 `core-service/src/test/java/com/axon/core_service/benchmark/CampaignEntryHistoryHttpBenchmarkIT.java`에 보존했다. 테스트 XML은 `core-service/build/test-results/test/TEST-com.axon.core_service.benchmark.CampaignEntryHistoryHttpBenchmarkIT.xml`이다.
