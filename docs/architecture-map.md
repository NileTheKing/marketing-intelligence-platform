# Axon 시스템 지도 (진입점)

> **목적**: 새로 투입된 사람/에이전트가 30초에 "무엇이 무엇에게 무엇을 보내는지" 잡는 반 페이지 지도.
> **원칙**: 짧게 유지. 상세는 각 flow 문서로. **충돌 시 코드가 이긴다.**
> 개정: 2026-10-21 (문서 구조·관측 구성 정리; VM 재검증 아님).

## 정체성 (왜 존재하나)
1. **선착순(FCFS) 스파이크 트래픽을 안정적으로 수용·판정** (한정 상품 응모, 2초에 수천 명)
2. **거기서 나오는 행동 로그를 수집→마케팅 통계/대시보드로 활용**

이 둘이 프로젝트의 두 축. 모든 컴포넌트는 결국 이 중 하나를 위해 존재.

## 서비스 (MSA, 수신부/처리부 분리)
- **entry-service** (`:8081`, virtual threads on) — 요청 수신부. FCFS 판정·인증·결제 준비. **빠르게 받고 즉시 응답**이 사명.
- **core-service** (`:8080`) — 비즈니스 처리부. Kafka로 온 후속 이벤트 처리, MySQL/Elasticsearch 적재, 대시보드, 마케팅 룰.
- **infra**: Redis(원자 연산), Kafka(KRaft), MySQL(정합성), Elasticsearch(행동 분석), nginx **2겹**(host nginx systemd + axon-nginx 컨테이너 `infrastructure/nginx/`).

현재 배포는 Oracle Cloud VM의 Docker Compose다. 과거 K8s 배포 기록은
`docs/archive/k8s/`에 보관하며 현재 스택에 포함하지 않는다. 단일 VM 배포는
현재 자원 제약에 따른 선택이며, 서비스 인스턴스나 Kafka 소비 병렬성을
구조적으로 1개로 제한한다는 뜻은 아니다. 다중 인스턴스 운영 시에는 아래
문서 목록의 consumer group 및 전역 scheduler 경계 원칙을 따른다.

## 운영 관측과 행동 로그

- 운영 로그: Entry/Core/AI 및 nginx → Alloy → Loki → Grafana.
- 메트릭: Prometheus → Grafana. 요청 지연, 오류, 자원 사용량과 Kafka lag 등을 확인한다.
- 트레이스: OTel → Jaeger → Grafana. 일반 실행은 표본 수집이며, 공식 성능 비교에서는 OTel을 비활성화한다.
- 행동 로그: Kafka → Kafka Connect → Elasticsearch. 마케팅 분석용이며 운영 로그 저장소와 구분한다.

현재 구성에 Fluent Bit/Fluentd는 필요하지 않다. 설정과 마지막 VM 배포 확인은
`docs/operations/observability-correlation-v1-plan.md`를 기준으로 하며, 문서의 확인
날짜를 현재 VM의 실시간 가동 확인으로 해석하지 않는다.

## HTTP 계약 원칙
- SSR 화면은 `/shop`, `/products/{id}`, `/campaign-activities/{id}`, `/admin/**`처럼 API와 분리한다.
- CRUD/조회와 참여·이벤트 기록은 `/api/v1`의 명사 collection으로 노출한다 (`campaigns`, `campaign-activities`, `entries`, `behavior-events`).
- 상태 전이 command는 억지로 resource화하지 않는다. 결제 `prepare`/`confirm`은 RPC-style로 유지한다.
- 익명 호출은 진행 중 Event 정의 조회처럼 명시한 경로만 허용한다. 사용자 검증은 인증이 필요하고, 캠페인·대시보드·대사 등 운영 API와 `/admin/**`는 `ROLE_ADMIN`만 허용한다.
- Entry가 Core의 액티비티 메타를 읽을 때는 `ROLE_SYSTEM` 토큰을 사용한다. 관리자 이메일은 `AXON_ADMIN_EMAILS`에 쉼표로 지정하며 로그인 시 기존 USER를 ADMIN으로 승격한다.

## 핵심 흐름 (누가 뭘 emit/consume)

**활동 등록과 자동 쿠폰 설정**
```
Campaign -> CampaignActivity (기간/상태)
  COUPON + couponDeliveryMode
    DIRECT -> 기존 Run 대상 확정/검토/일괄 발송
    ABANDONED_CART -> 1:1 장바구니 설정 -> 행동 이벤트/후보 스케줄러
    DORMANT_CUSTOMER -> 1:1 휴면 설정 -> 미구매 고객 스케줄러
  -> 모두 기존 Execution/Dispatch/Kafka 쿠폰 발급 경로 재사용
```
등록 화면/API가 활동과 전용 설정을 함께 저장한다. 자동 쿠폰은 활동의 ACTIVE 상태와
활동/상위 캠페인의 기간을 확인하며, 중지된 장바구니 후보는 재개를 위해 보존한다.
2026-10-19 집중 Java 테스트는 통과했으며, 실제 배포 전에는 migration과 MySQL/Kafka 통합 확인이 필요하다.

**A. FCFS 예약**
```
클라 → nginx → entry `POST /api/v1/entries` → EntryController#createEntry
  → EntryReservationService (Redis Lua: SADD+INCR+한도)
      성공(200+토큰) / 매진(410) / 중복(409)   ← 매진판정은 Lua가 원자적, 이미 견고
  → 성공 시 ReservationApprovedEvent (앱 내부 이벤트)
      → BackendEventPublisher(@Async @EventListener)
      → Kafka BEHAVIOR_EVENT  (축2로 합류)
```

**B. 결제**
```
당첨자 → entry PaymentController prepare/confirm (토큰 검증 및 Core 처리 명령 전달)
  → PaymentService → Kafka CAMPAIGN_ACTIVITY_COMMAND
  → core batch listener (poll당 최대 20건) → FCFS orchestration
      → Entry + Purchase 단일 원장 트랜잭션
          배치 실패: 전체 rollback → 메시지별 새 트랜잭션 재시도
          실패 위치를 예외로 반환 -> Kafka error handler가 재시도/최종 실패 메시지의 command DLT 전송 담당
      → UserSummary 별도 projection 트랜잭션
          실패: USER_SUMMARY_PROJECTION_FAILED에 기록
      → 매일 03:00 DB 대사: Purchase 원장과 UserSummary 비교 후 불일치 자동 rebuild
      → 새 Purchase의 행동 로그 발행
  → 필수 DB 처리/실패 기록 완료 후 listener 반환 → batch offset commit
```

**C. 행동 로그 (축2)**
```
프론트 JS SDK → entry `POST /api/v1/behavior-events` → BehaviorEventController
  → 로그인 userId는 JWT에서만 결정, 익명 요청은 브라우저 sessionId 사용
  → 공식 `properties` 타입 정규화 + 미등록 `attributes` 분리 → Kafka BEHAVIOR_EVENT
ReservationApprovedEvent → entry BehaviorEventAdapter → CDP 포맷 Kafka 메시지
  → Kafka BEHAVIOR_EVENT
BEHAVIOR_EVENT → Kafka Connect Elasticsearch sink → Elasticsearch (행동 분석 파이프라인)
BEHAVIOR_EVENT → Core candidate consumer (`CART_ITEM_ADDED`/`CART_ITEM_REMOVED`/`PURCHASE`만 처리)
  → MySQL 장바구니 이탈 쿠폰 후보 상태 저장
  ※ 백엔드는 성공(reservation_approved)만 발행. 매진/거절 신호는 프론트 SDK 몫.
```

**D. 행동 기반 후속 액션**
```
BehaviorTriggerScheduler
  → COUPON: CAMPAIGN_ACTIVITY_COMMAND → 기존 command consumer → UserCoupon
  → WEBHOOK: WEBHOOK_COMMAND → 전용 consumer
      → timeout/네트워크·5xx·429만 최대 3회 backoff+jitter 재시도
      → 일반 4xx 또는 최종 실패는 WEBHOOK_FAILED_DLT 기록
      → DLT 발행 실패 시 listener 예외 전파로 offset commit 차단

AbandonedCartCouponScheduler
  → 30분이 지난 장바구니 이탈 후보를 MySQL에서 500건씩 조회
  → 구매 재확인 뒤 기존 COUPON command consumer로 발행
  → Kafka 수락을 확인한 뒤 다음 500건을 이어서 처리
```

## Kafka 토픽 (`common-messaging` KafkaTopics)
| 토픽 | 용도 |
|---|---|
| `axon.event.behavior` | 행동 이벤트 (축2, → Elasticsearch) |
| `axon.event.commerce` | 커머스 이벤트 |
| `axon.campaign-activity.command` (+`.dlt`) | 결제/캠페인 명령 (entry→core), 실패 시 DLT |
| `axon.webhook.command` | 외부 Webhook 전달 전용 명령; FCFS·쿠폰 소비 경로와 분리 |
| `axon.projection.user-summary.failed` | 원장 커밋 후 UserSummary projection 실패 기록 |
| `axon.payment.retry` | 결제 재시도 (deprecated, 신규 사용 금지) |
| `axon.webhook.failed.dlt` | Webhook 최종 실패 격리 |
| `axon.event.raw` / `axon.user.login` | 원시 이벤트 / 로그인 (deprecated, 신규 사용 금지) |

## 스코프 경계 (누가 뭘 담당)
- 이 레포 = **개발/고도화**. 채용 산출물은 별도 관리한다.
- 결제 API·토큰 내부 로직은 현재 이 문서의 고도화 범위 밖이다.
- Kafka 이후 Core 후속처리, 관측, DB 수렴은 현재 고도화 범위다.

## 더 깊이
- 문서 목록/권위 → `docs/README.md`
- 흐름 상세 → `docs/architecture/` (예약·결제, 구매 후처리, 행동 이벤트 계약 등 — `docs/README.md`에서 상태 확인)
- 발견/결정(왜) → session memory. 사용 불가하면 `docs/experiments/dev-log-2026-07-10-fcfs-3000vu-bottleneck.md`
- 부하/병목 → `docs/experiments/dev-log-2026-07-10-fcfs-3000vu-bottleneck.md`
