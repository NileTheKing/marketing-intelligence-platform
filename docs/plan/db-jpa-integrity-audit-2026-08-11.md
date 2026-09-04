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

## 별도 결정이 필요한 남은 작업

| 우선순위 | 작업 | 지금 합치지 않은 이유 |
| --- | --- | --- |
| 높음 | Flyway/Liquibase 기준 스키마 도입 후 운영 `ddl-auto: validate` 전환 | 기존 OCI 스키마 baseline과 무중단 cutover가 필요한 별도 배포 작업 |
| 높음 | SHOP 결제 기록 경계 재설계 | GET 성공 콜백, 클라이언트 가격, Purchase·Coupon 원자성은 다른 담당자의 결제 계약과 함께 정해야 함 |
| 중간 | 재고 부족 시 동기화 정책 결정 | 현재는 보유 재고까지만 차감한다. 실패·대사 이력·활성화 차단 중 제품 정책 선택이 필요 |
| 중간 | Product/Purchase/CampaignActivity 금액 precision 통일 | 실제 DB가 Product `decimal(38,2)`, Purchase·Activity `decimal(10,2)`로 달라 기존 값 범위 확인이 필요 |
| 후속 | DTO 변환 완료 후 OSIV 비활성화 | 아직 일부 SSR 조회가 LAZY 연관관계에 의존하므로 선행 정리가 필요 |

## 의도적으로 하지 않은 것

- 모든 엔티티에 범용 `@Version`을 붙이지 않았다.
- 성능 근거 없이 인덱스를 대량 추가하지 않았다.
- OCI 데이터 규모가 작으므로 이번 쿼리 수 개선을 응답시간 단축 수치로 표현하지 않았다.
- FK가 없는 scalar ID를 일괄 연관관계로 바꾸지 않았다. 이벤트 원장과 삭제 정책을 먼저 정해야 한다.
- 현재 실패 사례가 없는 범용 Repository·도메인 이벤트·Inbox/Outbox 계층을 추가하지 않았다.
