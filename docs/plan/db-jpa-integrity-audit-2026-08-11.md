# DB/JPA 정합성 감사 — 2026-08-11

상태: active

## 결론

현재 OCI 데이터에서 중복·고아·음수 값은 발견되지 않았다. 다만 정상 데이터가 유지된 이유 일부가 DB 제약이 아니라 단일 실행 순서와 애플리케이션 코드에 의존하고 있었다. 이번 작업은 재현 가능한 정합성 위험만 수정했으며, 스키마 마이그레이션과 결제 정책처럼 별도 합의가 필요한 작업은 분리했다.

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

## 검증

- Core 전체: 56 suites, 167 tests, failures/errors 0, skipped 21
- UserSummary 조건부 갱신 JPA 테스트
- 재고 동기화 액티비티별 실패 격리 테스트
- 쿠폰 잠금 경로 테스트
- Product/Purchase 불변식 테스트
- LTV 중복 키 DB 제약 테스트
- `git diff --check` 통과

로컬 Docker 부재로 Testcontainers 대상은 skip됐다. H2 기반 JPA 테스트와 단위 테스트는 통과했고, 2026-08-12 GitHub Actions에서는 MySQL·Kafka·Redis 컨테이너를 사용하는 Core 전체 suite와 필수 통합 테스트의 실제 실행까지 통과했다. 다만 LTV 유니크 제약을 기존 OCI 스키마에 반영하는 운영 마이그레이션은 아직 수행하지 않았으므로 다음 OCI 배포 검증 대상으로 유지한다.

## 별도 결정이 필요한 남은 작업

| 우선순위 | 작업 | 지금 합치지 않은 이유 |
| --- | --- | --- |
| 높음 | Flyway/Liquibase 기준 스키마 도입 후 운영 `ddl-auto: validate` 전환 | 기존 OCI 스키마 baseline과 무중단 cutover가 필요한 별도 배포 작업 |
| 높음 | SHOP 결제 기록 경계 재설계 | GET 성공 콜백, 클라이언트 가격, Purchase·Coupon 원자성은 다른 담당자의 결제 계약과 함께 정해야 함 |
| 중간 | 재고 부족 시 동기화 정책 결정 | 현재는 보유 재고까지만 차감한다. 실패·대사 이력·활성화 차단 중 제품 정책 선택이 필요 |
| 중간 | Product/Purchase/CampaignActivity 금액 precision 통일 | 실제 DB가 Product `decimal(38,2)`, Purchase·Activity `decimal(10,2)`로 달라 기존 값 범위 확인이 필요 |
| 낮음 | 관리자 Campaign 목록의 participant count N+1 제거 | 현재 OCI Campaign/Activity가 각 1건이고 hot path가 아니므로 측정 없이 구조를 늘리지 않음 |
| 후속 | DTO 변환 완료 후 OSIV 비활성화 | 아직 일부 SSR 조회가 LAZY 연관관계에 의존하므로 선행 정리가 필요 |

## 의도적으로 하지 않은 것

- 모든 엔티티에 범용 `@Version`을 붙이지 않았다.
- 성능 근거 없이 인덱스를 대량 추가하지 않았다.
- FK가 없는 scalar ID를 일괄 연관관계로 바꾸지 않았다. 이벤트 원장과 삭제 정책을 먼저 정해야 한다.
- 현재 실패 사례가 없는 범용 Repository·도메인 이벤트·Inbox/Outbox 계층을 추가하지 않았다.
