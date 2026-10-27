# 코드 탐색과 도메인 경계

Status: active

## 읽기 순서

서비스·컴포넌트 표에서 기능 진입점과 변경 경계를 찾고, 해당 실행 흐름과 테스트를 확인한다.

## 1. Code Structure Map

### Module Boundary

| Module | Current role | Main evidence |
|---|---|---|
| `entry-service` | Handles public entry/payment APIs, Redis reservation, token issuance, and Kafka command publishing. | `EntryController`, `PaymentController`, `EntryReservationService`, `PaymentService`, `CampaignActivityProducerService` |
| `core-service` | Handles campaign/activity persistence, Kafka command consumption, purchase/entry persistence, dashboard aggregation, marketing rules, validation, and LLM query. | `CampaignActivityConsumerService`, `FcfsCommandOrchestrator`, `FcfsLedgerPersistenceService`, `DashboardService`, `BehaviorTriggerScheduler`, `GeminiLLMQueryService` |
| `common-messaging` | Shares Kafka topic names and message DTOs between services. | `KafkaTopics`, `CampaignActivityKafkaProducerDto`, `ReservationTokenPayload`, `UserBehaviorEventMessage` |

### Current Component Navigation Map

> **Current-code navigation, not a target package design.** Read this table before changing a feature. Follow the listed boundary and focused tests before expanding the change surface. Class names are search anchors, not a claim that one class owns every detail.

| Capability | Entry point | Main path and output | Focused tests | Change boundary |
|---|---|---|---|---|
| FCFS reservation | `EntryController#createEntry` | `EntryApplicationService` → `EntryReservationService` → Redis Lua; success publishes a behavior event | `EntryReservationServiceRedisIntegrationTest` | Entry owns fast slot admission and reservation tokens. It must not persist Core ledger records directly. |
| Payment command | `PaymentController` `prepare` / `confirm` | `PaymentService` / `CampaignActivityProducerService` → `CAMPAIGN_ACTIVITY_COMMAND` | `PaymentControllerTest` | Payment token internals are a separate owner. Do not change them while modifying Core purchase persistence unless explicitly requested. |
| FCFS ledger consumption | `CampaignActivityConsumerService` Kafka listener | `FcfsCommandOrchestrator` → `FcfsLedgerPersistenceService` → `CampaignActivityEntry` + `Purchase`; final command failure goes to DLT | `CampaignActivityConsumerServiceTest`, `FcfsCommandOrchestratorTest` | Core owns durable ledger persistence and Kafka completion. Keep Entry reservation and post-ledger projections outside this transaction. |
| Purchase projection and repair | Post-ledger projection / scheduled reconciliation | `UserSummary` projection; failures recorded separately and reconciliation repairs from `Purchase` ledger | `ReconciliationSchedulerTest`, `ReconciliationIssueServiceIntegrationTest` | `Purchase` is the source of truth. `UserSummary` is rebuildable derived data, so do not make a projection failure roll back a committed ledger. |
| Behavior collection | `BehaviorEventController` and reservation-approved application event | Event normalization → `BEHAVIOR_EVENT` → Elasticsearch sink | `BehaviorEventApiContractTest`, `BehaviorEventPropertyTest` | Entry accepts and normalizes behavior data; Core consumes analysis results. Keep official property schema separate from free-form attributes. |
| Marketing action delivery | `BehaviorTriggerScheduler` and command consumers | Rule/action evaluation → coupon command or dedicated webhook topic; Webhook retries then DLT | `BehaviorTriggerSchedulerTest`, `WebhookCommandConsumerServiceTest`, `WebhookStrategyTest` | Rule evaluation decides an action. Command processing delivers it. Do not let slow Webhook I/O share the FCFS command topic/group. |
| Dashboard and SSR reads | Dashboard controllers / `StoreController` | `DashboardService`, `DashboardPageService`, `StoreViewService` assemble DTO/view models from Core read data | `DashboardServiceTest`, `DashboardPageServiceTest`, `StoreViewServiceTest` | Controllers pass DTO/view data only. Keep persistence access and lazy association resolution inside read-only service transactions. |
| Cohort and RFM batch analysis | Scheduled batch jobs | SQL-based cohort aggregation and keyset-paged RFM segmentation over purchase-derived data | `CohortLtvSqlOffloadingTest`, `RfmSegmentationServiceTest` | These are read/projection workloads, not the FCFS hot path. Do not add them to Kafka listener transactions. |

### Cross-Component Rules

1. **HTTP admission, durable ledger, and derived projections are separate stages.** A successful Redis reservation is not a persisted Entry or Purchase; a committed Purchase is not the same thing as a successful UserSummary projection.
2. **Kafka topic/group is a failure-isolation boundary.** A consumer may own only the topic whose completion semantics it can safely control. External Webhook latency must not delay the FCFS command consumer.
3. **`common-messaging` is a contract module, not a business layer.** Message DTO or topic changes require checking both Entry producers and Core consumers plus their tests.
4. **Schedulers are global work, unlike Kafka consumers.** Kafka consumer groups coordinate partition ownership; scheduled jobs require their own single-runner or lock decision before multi-pod deployment.

### Current Package Shape

The project currently mixes two package styles.

2026-10-22: 쿠폰 정의·발급 내역·사용 처리 코드를 `com.axon.core_service.coupon`에
모았다. 기능별 패키지 전환의 첫 범위이며, 나머지 계층형 패키지는 아직 유지한다.

```text
coupon/
  CouponController.java
  CouponService.java
  CouponRepository.java
  UserCouponRepository.java
  domain/   Coupon, CouponStatus, UserCoupon
  dto/      쿠폰 요청·응답과 적용 가능 쿠폰 정보
```

`CouponServiceTest`도 같은 기능 패키지로 옮겼다. HTTP 계약, 엔티티·테이블 이름,
트랜잭션과 쿼리는 바꾸지 않는다. Kafka의 `CouponStrategy`는 메시지 처리 경계를
담당하므로 `commandprocessing`에 유지하고, 구매 매출 귀속 조회인
`CouponAttributionRepository`도 이번 이동에 포함하지 않는다.

2026-10-22: Entry도 기능별로 묶었다. HTTP 경로, Redis 키·TTL, Kafka 메시지와
처리 순서는 유지한다. 각 기능의 테스트도 같은 패키지로 옮겼다.

```text
entry_service/
  reservation/        참여 요청, 검증, Redis 선착순 판정과 예약 만료 처리
    domain/           예약 결과와 활동 메타데이터
    dto/              참여 요청과 활동 조회 응답
    event/            예약 승인 이벤트
  payment/            결제 준비·확정과 CheckoutTokenService
    dto/              결제 요청·응답
  behavior/           행동 수집, 정규화와 Kafka 발행
    domain/           행동 이벤트 모델
    dto/              행동 수집 요청
  messaging/          참여·결제가 함께 사용하는 Kafka 명령 발행
  config/             인증, 클라이언트 설정과 진단용 AOP
  observability/      요청 상관관계 필터
  dto/payment/        기존 Redis 직렬화 계약인 PaymentApprovalPayload
```

`CheckoutTokenService`는 기존 `ReservationTokenService`의 이름만 바꿨으며,
예약 토큰·승인 토큰·확정 중복 실행 방지 책임은 나누지 않았다. 기존 Spring bean
이름도 유지한다. `PaymentApprovalPayload`는 Redis JSON에 클래스명이 포함되므로
패키지 이동에서 제외했고, 공통 모듈의 `ReservationTokenPayload`도 변경하지 않았다.
진단 AOP와 CI의 필수 Redis 통합 테스트 경로는 새 패키지를 따르도록 수정했다.

2026-10-22: 상품과 상품 후기도 기능별로 묶었다.

```text
catalog/
  ProductController, ProductService, ProductRepository
  domain/   Product
  dto/      상품 목록 응답
review/
  ProductReviewController, ProductReviewService, ProductReviewRepository
  domain/   ProductReview
  dto/      후기 작성 요청과 조회 응답
```

관련 테스트도 같은 기능 패키지로 옮겼다. 상품 재고 동기화는 `ProductService`에,
구매 여부 확인과 후기 중복 방지는 `ProductReviewService`에 그대로 유지한다.
쇼핑 화면 조합은 `StoreViewService`에 남기며, 캠페인 실행 승인인
`CampaignActivityRunReviewController`는 상품 후기와 무관하므로 이동하지 않는다.
HTTP 계약, JPA 매핑, 쿼리와 트랜잭션 경계는 변경하지 않았다.

2026-10-22: 캠페인 설정과 참여 기록을 기능별 패키지로 묶었다.

```text
campaign/
  캠페인·활동 API, 설정 서비스와 Repository
  발송 실행·승인 API, 실행 서비스와 Repository
  domain/   Campaign, CampaignActivity, 발송 실행·대상 모델
  dto/      설정·실행 요청과 응답, 참여 조건 설정
entry/
  참여 이력 조회 API, 조회 서비스와 Repository
  ValidationController
  domain/   CampaignActivityEntry와 상태
  dto/      참여 건수·목록·이력 응답
  validation/   참여 자격 검사와 조건별 전략
```

캠페인이 참여 조건을 정의하고 `entry.validation`이 이를 평가한다. 설정과 참여
이력은 패키지를 구분했지만 기존 서비스·Repository 의존 관계는 유지한다.
발송 실행은 캠페인 운영에 속하므로 `campaign`에 함께 두었다. 구매 저장을
조율하는 `commandprocessing`, 구매 집계·대사와 재고 동기화는 이번 범위에서
옮기지 않았다. 관련 테스트와 CI의 필수 통합 테스트 경로도 함께 변경했다.
HTTP 경로, JPA 엔티티·테이블 이름, 스케줄과 트랜잭션 경계는 유지한다.

2026-10-22: `StoreViewService`의 일반 구매 저장을 `purchase/ShopPurchaseService`로
분리했다. `StoreController`는 조회를 `StoreViewService`에, 구매 기록 저장을
`ShopPurchaseService`에 맡긴다. 쿠폰 사용과 구매 저장은 같은 트랜잭션을 유지한다.
이번 변경은 책임 분리이며 기존 결제 시뮬레이션, 가격 입력과 HTTP 계약을 바꾸지 않는다.
캠페인 메타 캐시의 상세 경계는 아래 절을 따른다.

| Style | Example | Reading impact |
|---|---|---|
| Layer package | `controller`, `service`, `repository`, `domain/dto` | Easy to find Spring stereotypes, but business boundary is not visible from the package tree. |
| Feature package | `coupon`, `catalog`, `review`, `campaign`, `entry`, `commandprocessing` | Related code can be followed within the feature or processing boundary. |
| Partial domain package | `domain/campaignactivity`, `service/purchase` | Some business concepts are grouped, but services still cross several concepts. |

This is a layered Spring application with partial feature packaging, not a full DDD or hexagonal package layout. The coupon package is an incremental grouping of related code, not an enforced bounded context. The meaningful architecture is service/module ownership and dependency direction. Packages are useful when they make those boundaries easier to read.

### 캠페인 메타 캐시

Entry는 활동 메타데이터를 Redis `campaign:{id}:meta`에 1시간 저장한다.
같은 활동의 캐시 미스는 Entry 인스턴스 내부 single-flight로 합쳐 Core 조회를
공유한다. 여러 Entry 인스턴스 사이의 조회까지 합치는 분산 락은 사용하지 않는다.
조회 실패 또는 404 응답은 캐싱하지 않으며 진행 중 요청도 정리한다.

Core의 활동 변경·삭제는 DB 커밋 후 Redis 버전 증가와 캐시 삭제를 한 번에 수행한다.
Entry는 조회 전 버전을 읽고 저장 시 Lua로 비교해, 무효화 도중 진행되던 조회가
이전 값을 캐시에 다시 저장하지 못하게 한다. 버전 키 `campaign:{id}:meta:version`은
진행 중인 조회와 충돌하지 않도록 만료시키지 않는다.
이미 진행 중인 요청의 응답까지 최신으로 보장하지는 않는다. DB와 Redis의 원자적
커밋은 아니므로 커밋 직후 프로세스 종료·Redis 장애 시 무효화가 누락될 가능성은
남는다. 이 경우 기존 캐시는 TTL까지 유지될 수 있으며, 영속 재시도는 현재 범위 밖이다.
두 키를 사용하는 Lua는 현재 단일 Redis 구성 기준이다.

직접 쿠폰 발급은 Core 메타의 활동 유형이 `COUPON`이고 `couponDeliveryMode`가
`DIRECT`인 경우만 허용한다. 클라이언트가 보낸 유형은 이 판단에 사용하지 않는다.
발급 방식이 없는 이전 캐시는 Core에서 다시 조회하며, 조회 후에도 값이 없거나
알 수 없으면 직접 발급을 거절한다. 자동 발급 스케줄러는 기존 내부 경로를 사용한다.

### High-Traffic Flow

1. `EntryController.createEntry()` maps HTTP input to `EntryApplicationService`; the application service validates request/user/meta and calls Redis reservation.
2. `EntryReservationService.reserve()` executes Lua script and publishes `ReservationApprovedEvent`.
3. Payment flow uses `PaymentController.preparePayment()` and `PaymentController.confirmPayment()`.
4. `PaymentService.sendToKafkaWithRetry()` sends `CampaignActivityKafkaProducerDto` to Kafka and waits for broker ACK.
5. `CampaignActivityConsumerService.consume()` receives up to 20 records from one Kafka poll.
6. The listener dispatches by `CampaignActivityType` and returns after Entry/Purchase durable processing.
7. `FirstComeFirstServeStrategy.processBatch()` delegates the FCFS batch to `FcfsCommandOrchestrator`.
8. `FcfsLedgerPersistenceService` flushes Entry and Purchase in one transaction; a failed batch is retried per message and final command failures are sent to the command DLT.
9. After ledger commit, UserSummary is projected in a separate transaction and a new Purchase emits its behavior log.

### Analytics / Dashboard Flow

1. Behavior events are stored and queried through `BehaviorEventService`.
2. Activity dashboard uses `DashboardService.getDashboardByActivity()`.
3. Activity funnel uses `FunnelStep` and `CampaignFunnelDefinition`.
4. Campaign/global dashboards are still mostly assembled inside `DashboardService`.

### Marketing Automation Flow

1. `BehaviorTriggerScheduler` loads active `MarketingRule`.
2. It queries behavior data through `BehaviorEventService`.
3. It creates `CampaignActivityKafkaProducerDto` reward commands.
4. Existing Kafka command processing handles `COUPON` / `WEBHOOK` through strategy dispatch.
5. `MarketingRule` is the condition and has active `MarketingAction` children. `BehaviorTriggerScheduler` evaluates each active action independently, so one rule can issue a coupon, invoke a webhook, or do both. Action-level Redis dedup and Kafka send-failure cleanup are handled per action.
