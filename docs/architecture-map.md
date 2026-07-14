# Axon 시스템 지도 (진입점)

> **목적**: 새로 투입된 사람/에이전트가 30초에 "무엇이 무엇에게 무엇을 보내는지" 잡는 반 페이지 지도.
> **원칙**: 짧게 유지. 상세는 각 flow 문서로. **충돌 시 코드가 이긴다.**
> 개정: 2026-07-12.

## 정체성 (왜 존재하나)
1. **선착순(FCFS) 스파이크 트래픽을 안정적으로 수용·판정** (한정 상품 응모, 2초에 수천 명)
2. **거기서 나오는 행동 로그를 수집→마케팅 통계/대시보드로 활용**

이 둘이 프로젝트의 두 축. 모든 컴포넌트는 결국 이 중 하나를 위해 존재.

## 서비스 (MSA, 수신부/처리부 분리)
- **entry-service** (`:8081`, virtual threads on) — 요청 수신부. FCFS 판정·인증·결제 준비. **빠르게 받고 즉시 응답**이 사명.
- **core-service** (`:8080`) — 비즈니스 처리부. Kafka로 온 후속 이벤트 처리, MySQL/Elasticsearch 적재, 대시보드, 마케팅 룰.
- **infra**: Redis(원자 연산), Kafka(KRaft), MySQL(정합성), Elasticsearch(행동 분석), nginx **2겹**(host nginx systemd + axon-nginx 컨테이너 `infrastructure/nginx/`).

## HTTP 계약 원칙
- SSR 화면은 `/shop`, `/products/{id}`, `/campaign-activities/{id}`, `/admin/**`처럼 API와 분리한다.
- CRUD/조회와 참여·이벤트 기록은 `/api/v1`의 명사 collection으로 노출한다 (`campaigns`, `campaign-activities`, `entries`, `behavior-events`).
- 상태 전이 command는 억지로 resource화하지 않는다. 결제 `prepare`/`confirm`은 RPC-style로 유지한다.

## 핵심 흐름 (누가 뭘 emit/consume)

**A. FCFS 예약**
```
클라 → nginx → entry `POST /api/v1/entries` → EntryController#createEntry
  → EntryReservationService (Redis Lua: SADD+INCR+한도)
      성공(202+토큰) / 매진(410) / 중복(409)   ← 매진판정은 Lua가 원자적, 이미 견고
  → 성공 시 ReservationApprovedEvent (앱 내부 이벤트)
      → BackendEventPublisher(@Async @EventListener)
      → Kafka BEHAVIOR_EVENT  (축2로 합류)
```

**B. 결제**
```
당첨자 → entry PaymentController prepare/confirm (토큰 검증, entry↔core 경합 취약 구간)
  → PaymentService → Kafka CAMPAIGN_ACTIVITY_COMMAND
  → core listener → command buffer → scheduled flush → strategy → Entry upsert
  → PurchaseHandler → Purchase/UserSummary (MySQL)
     (명령 배치 실패: command DLT, Purchase 실패: purchase DLT)
```

**C. 행동 로그 (축2)**
```
프론트 JS SDK → entry `POST /api/v1/behavior-events` → BehaviorEventController
  → Kafka BEHAVIOR_EVENT
ReservationApprovedEvent → entry BehaviorEventAdapter → CDP 포맷 Kafka 메시지
  → Kafka BEHAVIOR_EVENT
BEHAVIOR_EVENT → Kafka Connect Elasticsearch sink → Elasticsearch (행동 분석 파이프라인)
  ※ 백엔드는 성공(reservation_approved)만 발행. 매진/거절 신호는 프론트 SDK 몫.
```

## Kafka 토픽 (`common-messaging` KafkaTopics)
| 토픽 | 용도 |
|---|---|
| `axon.event.behavior` | 행동 이벤트 (축2, → Elasticsearch) |
| `axon.event.commerce` | 커머스 이벤트 |
| `axon.campaign-activity.command` (+`.dlt`) | 결제/캠페인 명령 (entry→core), 실패 시 DLT |
| `axon.payment.retry` | 결제 재시도 (deprecated, 신규 사용 금지) |
| `axon.purchase.failed.dlt` / `axon.webhook.failed.dlt` | 실패 격리 |
| `axon.event.raw` / `axon.user.login` | 원시 이벤트 / 로그인 (deprecated, 신규 사용 금지) |

## 스코프 경계 (누가 뭘 담당)
- 이 레포 = **개발/고도화**. 자소서·포폴 산출물은 별도(obsidian)에서.
- **결제 API·토큰 내부 로직 = 다른 개발자 담당.** 별도 요청 없이 해당 세부 구현은 변경하지 않는다.
- Kafka 이후 Core 후속처리, 관측, DB 수렴은 현재 고도화 범위다.

## 더 깊이
- 문서 목록/권위 → `docs/plan/document-map.md`
- 흐름 상세 → `docs/flow/*` (reservation-payment, token-based-payment, payment-resilience 등 — document-map에서 정본 확인)
- 발견/결정(왜) → session memory. 사용 불가하면 `docs/devlog/dev-log-2026-07-10-fcfs-3000vu-bottleneck.md`
- 부하/병목 → `docs/devlog/dev-log-2026-07-10-fcfs-3000vu-bottleneck.md`
