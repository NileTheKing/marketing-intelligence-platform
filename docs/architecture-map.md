# Axon 시스템 지도

Status: active

서비스 역할과 데이터 흐름을 파악하는 진입점이다. 상세 구현은 아래 정본을 따른다.
개정: 2026-10-27, 일반 구매 확정 경계 반영. VM 배포 또는 재검증을 뜻하지 않는다.

## 목적과 서비스

Axon은 선착순 트래픽을 수용하고, 유입된 고객의 행동 로그를 마케팅과 분석에 활용한다.

| 위치 | 책임 |
|---|---|
| `services/entry-service` (`:8081`) | 요청 수신, Redis 선착순 판정, 결제 준비와 처리 명령 발행 |
| `services/core-service` (`:8080`) | 구매 저장, 집계와 대사, 쿠폰 발급, 관리자 화면 |
| `services/ai-triage-service` | 실패 분석과 운영자 승인 지원 |
| `libs/common-messaging` | 서비스 간 Kafka 토픽과 메시지 계약 |
| `infrastructure/` | nginx, Docker Compose, 관측 설정 |

## 핵심 흐름

```text
선착순 요청 → nginx → Entry → Redis Lua로 중복과 정원 판정 → 응답
일반 구매   → Core → 요청 기록 + 쿠폰 사용 + 구매 저장 (단일 DB 트랜잭션)
선착순 결제 명령 → Entry → Kafka → Core
                              → 구매와 참여 기록을 같은 DB 트랜잭션에 저장
                              → 별도 트랜잭션으로 집계 갱신 → 구매 이벤트 발행
행동 수집   → JS SDK / 서버 이벤트 → Entry → Kafka
                                            ├─ Connect → Elasticsearch → 분석
                                            └─ Core → 장바구니 쿠폰 후보 저장
마케팅     → 대상 조회 / 스케줄러 → Kafka 명령 → 쿠폰 발급 또는 전용 Webhook 처리
실패 분석  → Core → AI 서비스 → Slack 운영자 승인 → 재처리
```

## 변경할 때 지킬 경계

- Redis 참여 성공과 DB 구매 저장은 서로 다른 단계다. Entry는 Core의 구매 기록을 직접 저장하지 않는다.
- Core는 구매와 참여를 원자적으로 저장한다. 집계 실패는 이미 커밋된 구매를 롤백하지 않으며, 구매 기록을 기준으로 대사한다.
- Consumer는 필수 DB 처리 또는 실패 기록 이후 offset을 진행한다. 재전달은 DB unique key로 방어한다.
- 외부 Webhook 호출은 선착순 소비와 토픽을 분리한다.
- Entry의 활동 메타 캐시는 Core 변경 시 무효화한다. 경쟁 조건과 single-flight 범위는 코드 탐색 문서의 상세 절을 확인한다.
- 다중 인스턴스의 Kafka 소비는 파티션과 consumer group이 조율한다. 전역 스케줄러의 중복 실행 방지는 별도로 판단한다.
- 결제 상태 영속화와 Outbox는 개발 로드맵의 계획이다. 현재 보장으로 해석하지 않는다.

## 실행과 관측

배포 기준은 Oracle Cloud VM과 Docker Compose다. 인스턴스 수와 CPU 할당은 배포 또는
실험 조건이며 구조적 상한이 아니다. 기능 구성에 Elasticsearch가 포함돼 있어도
실제 VM 가동 여부는 날짜가 있는 운영 기록과 환경을 따로 확인한다.

| 데이터 | 경로 |
|---|---|
| 운영 로그 | Entry / Core / AI / nginx → Alloy → Loki → Grafana |
| 메트릭 | Prometheus → Grafana |
| 트레이스 | OTel → Jaeger → Grafana |
| 행동 분석 | Kafka → Kafka Connect → Elasticsearch |

## 필요한 상세 문서

- 코드 위치, 캐시와 컴포넌트 경계: [코드 탐색](architecture/domain-refactoring-map.md)
- 구매 저장, 배치 실패와 집계 경계: [구매 후처리](architecture/fcfs-entry-purchase-transaction-boundary-handoff.md#현재-구조)
- HTTP 경로와 관리자 데모 권한: [API 계약](architecture/rest-api-route-clean-cutover-plan.md)
- 행동 속성과 사용자 식별: [행동 이벤트 계약](architecture/behavior-event-schema-v1.md)
- 쿠폰 실행과 전달 이력: [마케팅 실행](architecture/marketing-action-execution-dispatch-history-plan.md)
- 자동 쿠폰 조건과 실험 근거: [장바구니 이탈](experiments/abandoned-cart-coupon-candidate-query-v1-plan.md), [휴면 고객](experiments/dormant-customer-reengagement-query-v1-plan.md)
- 실패 분석과 승인: [AI 처리 흐름](architecture/dlq-failure-triage-agent-v1-plan.md)
- 실제 운영과 관측 설정: [운영 절차](operations/oracle-compose-baseline-runbook.md), [관측 구성](operations/observability-correlation-v1-plan.md)
- 다른 주제의 정본과 과거 기록: [전체 문서 목록](catalog.md)

Kafka 토픽 이름과 메시지 정의는 `libs/common-messaging`의 `KafkaTopics`와 DTO에서 확인한다.
