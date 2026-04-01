# Technical Portfolio: 고성능 마케팅 및 실시간 데이터 처리 시스템 설계
> **3,000 VU 환경의 선착순 트래픽 제어와 비동기 분석 파이프라인 최적화 기록**

본 포트폴리오는 Axon 플랫폼의 핵심 엔지니어링 사례를 다루는 기술 리포트입니다. Axon은 이커머스 프로모션 시 발생하는 **대규모 선착순(FCFS) 트래픽을 안정적으로 제어**하고, 유입된 고객의 행동 로그를 실시간으로 분석하여 **마케팅 수익성(LTV) 인사이트를 도출**하는 시스템입니다.

단순한 기능 구현을 넘어, 3,000 VU(Peak 2,900 RPS)라는 극한의 상황에서 발생하는 병목을 어떻게 진단하고 기술적 트레이드오프를 거쳐 최적화했는지에 대한 해결 논리를 서술합니다.

**시스템 구조**: 트래픽 유입과 선착순 판정을 담당하는 **Entry-service**와 캠페인·구매 도메인 로직 및 영속성을 처리하는 **Core-service**, 두 서비스가 **Kafka**를 통해 비동기로 연결된 구조다. 이하 섹션들은 이 아키텍처 위에서 발생한 문제와 해결 과정을 다룹니다.

- 프로젝트 개요 깃허브링크: https://github.com/NileTheKing/marketing-intelligence-platform

---

## 1. 아키텍처 설계: 부하 완충을 위한 구조적 격리

```mermaid
flowchart TD
    subgraph BEFORE["BEFORE: 단일 동기 트랜잭션"]
        direction LR
        U1(("트래픽")) --> SVC["단일 서비스<br/>(선착순 확정 → 재고 차감 → 이력 업데이트 → 참여 기록)"]
        SVC --> DB1[("MySQL")]
    end

    subgraph AFTER["AFTER: 계층 분리 + 비동기"]
        direction LR
        U2(("스파이크 트래픽")) --> Entry["Entry-Service<br/>(유입 제어)"]
        Entry -->|"비동기 전송"| Kafka{{"Kafka<br/>(부하 완충)"}}
        Kafka -->|"순차 소비"| Core["Core-Service<br/>(비즈니스 처리)"]
        Core --> DB2[("MySQL")]
    end
```

**문제**
- 선착순 구매 이벤트 오픈 시점에 집중되는 스파이크 트래픽이 비즈니스 로직과 DB에 직접적인 충격을 주어 시스템 전체가 마비될 위험 확인.
- 기존 단일 요청 흐름에서는 FCFS 선착순 확정, 재고 차감, 유저 구매 이력 업데이트, 이벤트 참여 기록이 하나의 동기 트랜잭션으로 연결되어 있어 DB 지연이 즉각적으로 사용자 응답 지연으로 전파되는 구조적 한계 노출.
- 대량 유입 시 DB 커넥션 풀 고갈을 방지하기 위해 트래픽을 일시적으로 수용할 메시지 대기열 계층 부재.

**해결**
- API Gateway 수준의 단순 Throttling 대신, 요청을 대기열에 수용하면서 시스템이 가용한 수준만큼 순차 처리하는 **Kafka 기반 Backpressure** 설계.
- 시스템 복잡도는 증가하지만, DB 커넥션 풀을 보호하고 스파이크 트래픽을 평탄화(Traffic Smoothing)하여 가용성을 확보하는 것이 비즈니스 연속성 측면에서 적합하다고 판단.

**결과**
- Entry-service만 독립적으로 수평 확장(Scale-out) 가능한 구조 확보 — 트래픽 유입부(Entry)와 비즈니스 처리부(Core)의 배포 단위 분리.
- Kafka 버퍼링을 통해 피크 타임에도 DB 커넥션 풀의 급격한 변동 없이 안정적인 메시지 소비 흐름 유지.

---

## 2. 비즈니스 확장성: 객체지향 설계를 통한 유연성 확보

```mermaid
flowchart TD
    Consumer["Campaign Consumer"] -->|"Strategy Lookup"| Map["Strategy Map"]
    Map --> FCFS["First-Come-First-Serve"]
    Map --> Coupon["Coupon Issuance"]
    Map --> General["General Activity"]
```

**문제**
- 선착순, 쿠폰 발행 등 캠페인 유형이 추가될 때마다 늘어나는 `if-else` 분기문으로 인해 코드 가독성과 유지보수성 저하.
- 특정 유형의 로직 수정이 전체 Consumer에 영향을 줄 수 있는 강한 결합도로 인해 신규 정책 도입 시 사이드 이펙트 리스크 존재.

**해결**
- 캠페인 유형별 특화 로직을 `CampaignStrategy` 인터페이스로 추상화하고 독립된 구현체로 캡슐화하는 **전략 패턴(Strategy Pattern)** 도입.
- 신규 정책 도입 시 기존 코드 수정 없이 구현체만 추가하는 **OCP(Open-Closed Principle)** 준수.
- 스프링의 `ApplicationContext`가 전략 빈(Bean)을 자동 수집 → Consumer 생성 시 `Map<CampaignActivityType, CampaignStrategy>`(유형 식별자: FCFS, 쿠폰 발행 등)로 빌드하여 런타임 시점의 전략 디스패치 구현.

**결과**
- 신규 캠페인 유형 추가 시 기존 전략 코드 무수정으로 배포 가능한 구조 확립.
- 정책별 로직이 물리적으로 분리되어 각 전략에 대한 독립적인 단위 테스트 가능.

---

## 3. 정합성 최적화: 비동기 환경의 한계 극복 (1)

```mermaid
flowchart TD
    subgraph Problem ["BEFORE: 로직이 Core에 위치"]
        U1(("User1")) --"1번 요청"--> K1{{"Kafka"}}
        U2(("User2")) --"2번 요청"--> K1
        K1 --"2번 먼저 소비"--> C1["Core 정합성 검증"]
    end
    
    subgraph Solution ["AFTER: 로직을 Entry로 전진"]
        U3(("User1")) --"1번 요청"--> E1["Entry: 원자적 검증"]
        U4(("User2")) --"2번 요청"--> E1
        E1 --"1번 당첨 확정"--> K2{{"Kafka"}}
    end
```

**문제**
- 선착순 판단 로직이 비동기 구간(Core) 뒤에 위치할 경우, Kafka의 파티션 할당이나 네트워크 지연에 따라 실제 유입 순서와 처리 순서가 뒤바뀌는 현상 발생.
- 먼저 응모한 유저가 낙첨되고 나중에 응모한 유저가 당첨되는 정합성 오류를 부하 테스트 중 실측하여 확인.
- 마감 이후의 요청까지 Kafka를 거쳐 Core DB에 도달하는 불필요한 리소스 낭비.

**해결**
- Kafka 파티션 키를 통한 순서 보장은 리밸런싱이나 장애 상황에서 완벽하지 않으므로, 판정 시점 자체를 유입 시점과 일치시키는 **전진 배치(Forward Placement)** 단행.
- Entry에서 즉각적인 원자적 판정을 내리고 성공한 건만 Kafka로 발행 — 이후 비동기 소비 순서와 무관하게 당첨 권한 보장.
- 선착순 마감 이후 요청은 Entry에서 즉각 Fail-fast 응답 반환하여 배후 서비스 보호.

**결과**
- 다중 Consumer 환경에서 유입 순서 역전 0건 (k6 검증).
- k6 기준 10,000건 요청 중 200건(2%)만 Core에 도달, **98%를 Entry에서 Fail-fast 처리** — DB와 Core 서비스 부하를 구조적으로 차단.
- DB 접근 없이 메모리 기반 검증으로 즉각 응답하여 피크 타임 응답 속도 개선.

---

## 4. 정합성 최적화: 비동기 환경의 한계 극복 (2)

```mermaid
sequenceDiagram
    participant App as Entry-Service
    participant Redis as Redis (Single Thread)
    App->>Redis: EVAL reservation.lua (UserId)
    Note right of Redis: 1. SADD (중복 체크)<br/>2. INCR (수량 차감)<br/>3. 한도 확인
    Redis-->>App: OK (Atomic Response)
    App-)Kafka: 성공 이벤트 발행
```

**문제**
- Section 3의 Forward Placement로 선착순 순서 역전 문제를 해결한 뒤, Entry에서 사용하는 FCFS 카운터 연산 방식에서 별도의 정합성 문제 발견.
- 기존 구현: `INCR(카운터 증가) → 오버부킹 여부 확인 → 오버부킹 시 DECR(복구)`의 3-step 연산.
- Redis는 싱글스레드 기반이므로 이 시퀀스에서 동시성으로 인한 오버부킹 자체는 발생하지 않음.
- 단, INCR 성공 후 네트워크 단절이나 프로세스 크래시 발생 시 DECR 복구가 실행되지 않아 **Ghost Reservation(유령 선점)** 발생 — 카운터는 점유됐지만 실제 참여자는 없는 상태. 이 오차가 누적되면 실제 참여 가능 재고가 카운터보다 높아지는 재고 정합성 오류 유발.

**해결**
- 복구 로직의 실패 가능성 자체를 없애기 위해 `SADD(중복 체크) + INCR(수량 차감) + 한도 확인`을 **단일 Lua 스크립트**로 원자화.
- Redis는 Lua 스크립트를 단일 명령으로 처리 — 스크립트 실행 도중 다른 커맨드가 끼어들 수 없으므로 중간 상태 자체가 발생하지 않아, DECR 복구 로직이 불필요해짐.
- 네트워크 재시도 시 발생하는 중복 참여를 막기 위한 **멱등성(Idempotency)** 검증(`SADD`)을 동일 스크립트 내에 결합.

**결과**
- Ghost Reservation 0건 — 원자 연산으로 중간 실패 상태 없으므로 카운터와 실제 참여자 수가 항상 일치.
- k6 10,000+ 동시 요청에서 오버부킹 0건.
- Redis 호출 3회 → Lua 스크립트 1회로 감소.

---

## 5. 적재 신뢰성: 장애 파급 차단 및 트랜잭션 연쇄 롤백 전략

```mermaid
flowchart TD
    Consumer["Kafka Consumer (20개 배치)"] --> Loop["개별 메시지 처리 루프"]
    subgraph ParentTX ["참여 기록 트랜잭션"]
        Loop --> Entry["1. 참여 기록 저장<br/>(CampaignActivityEntry)"]
        Entry --> Event["구매 이벤트 발행"]
        subgraph ChildTX ["구매 트랜잭션 (REQUIRES_NEW)"]
            Event --> Purchase["2. 구매 처리<br/>(Purchase 저장 + UserSummary 갱신)"]
        end
    end
    ChildTX --"예외 발생"--> Rollback["참여 기록까지 연쇄 롤백"]
    ChildTX --"성공"--> Commit["최종 커밋"]
    Rollback --> DLT["데드 레터 토픽(DLT) 격리"]
```

**문제**
- Kafka 메시지 소비 시 **'참여 기록 저장'**과 **'구매 처리(구매 내역 저장 및 유저 요약 갱신)'**가 연쇄적으로 수행되는 구조임.
- 20개 단위 배치 처리 중 단 1건의 구매 처리 예외가 전체 배치를 롤백시켜 정상적인 타 유저의 적재까지 중단시키는 '배치 오염' 발생.
- 초기 `AFTER_COMMIT` 검토 시 구매 실패에도 참여 기록만 남는 데이터 불일치 위험 확인 및 정합성 사수를 위한 연쇄 롤백 구조의 필요성 진단.

**해결**
- **정합성 사수**: `BEFORE_COMMIT` 이벤트를 활용해 구매 실패 시 해당 참여 기록까지 함께 롤백되도록 설계하여 "구매 없는 참여" 발생 원천 차단.
- **트랜잭션 격리**: `PurchaseService`에 `REQUIRES_NEW`를 적용해 배치 내 각 메시지를 독립 트랜잭션으로 격리하고 단건 장애의 배치 전체 파급 방지.
- **장애 복구**: 최종 실패 건은 데드 레터 토픽(DLT)으로 분리하여 데이터 유실을 방지하고, 정상 데이터는 즉시 커밋하여 파이프라인 가용성 확보.

**결과**
- 일부 데이터 결함에도 전체 적재 프로세스가 중단되지 않음을 확인하여 데이터 유실률 0% 달성.
- 배치 처리의 성능(Throughput)을 유지하면서도 개별 유저 단위의 정합성을 단일 DB 수준으로 보장함.
- DLT 보존 체계를 통해 장애 원인 분석 및 재처리 기반을 마련하여 운영 안정성 확보.

---

## 6. 정합성 최종 수비: 분산 시스템의 데이터 무결성 설계

```mermaid
flowchart TD
    Kafka --> Consumer
    Consumer --> Lock{"Redisson Lock<br/>(EventId)"}
    Lock -->|성공| DB{"멱등성 체크<br/>(Unique Key)"}
    DB -->|신규| Process["데이터 적재 및 커밋"]
    DB -->|중복| Skip["중복 처리 스킵"]
```

**문제**
- Kafka의 'At-least-once' 전달 특성으로 인해 네트워크 재시도 시 동일한 성공 메시지가 중복 소비될 가능성 상존.
- 다중 Consumer 환경에서 동일 이벤트 ID에 동시 접근 시 레이스 컨디션으로 이중 결제나 이중 적재가 발생할 정합성 리스크 확인.
- DB Unique 제약 조건만으로는 선행 작업(재고 차감 등)의 중복 실행을 막을 수 없어 상위 레벨의 동시성 제어 필요.

**해결**
- **Redisson 분산 락**을 도입하여 동일한 이벤트 ID에 대해 한 번에 하나의 Consumer만 접근하도록 인프라 레벨 동시성 제어 구현.
- DB 레벨에 **멱등키(Idempotency Key)** 제약 조건을 추가하여 이미 처리된 건의 중복 저장을 이중으로 차단.
- '하나의 이벤트는 오직 한 번만 처리되어야 한다'는 분산 시스템의 기본 정합성 원칙을 최우선으로 설계.

**결과**
- k6 부하 테스트 및 Consumer 재기동 시나리오에서 중복 적재 0건.
- 인프라 레벨의 공격적인 재시도 전략을 안전하게 수립할 수 있는 기반 확보.

---

## 7. 쓰기 병목 해소: 지연 동기화를 통한 Throughput 극대화

```mermaid
flowchart LR
    P[("구매 로그 테이블")] --"Single Source of Truth"--> S["스케줄러 / 이벤트"]
    S --"비동기 배치 정산"--> Stock[("재고 테이블")]
```

**문제**
- 구매 확정 시 상품 재고와 유저 요약을 실시간 업데이트할 때 발생하는 빈번한 **DB Row-Lock 경합**이 응답 지연의 주요 원인임을 확인.
- 인기 상품(Hot-Spot)의 경우 특정 행에 트랜잭션이 집중되어 커넥션 풀이 고갈되는 성능 임계점 노출.
- Strong Consistency를 유지하는 방식으로는 초당 수천 건의 쓰기 요청을 감당할 수 없음을 부하 테스트를 통해 진단.

**해결**
- 실시간 강한 정합성 대신 구매 로그를 근거로 사후 정산하는 **Eventual Consistency** 모델 채택.
- 메인 결제 트랜잭션 내 인라인 업데이트를 의도적으로 제거하여 DB 쓰기 부하와 Row-Lock 경합을 줄이고, 정합성의 근거(Single Source of Truth)가 되는 구매 로그 적재에만 집중.
- **`@TransactionalEventListener`**로 기록 시점과 정산 시점을 물리적으로 분리하여 재고·유저 요약(UserSummary — 마지막 구매일, 누적 구매액 등 집계 테이블) 업데이트를 비동기 후처리로 이관.

**결과**
- 재고 테이블 Row-Lock 경합 제거로 피크 타임 응답 속도 개선 및 DB 커넥션 풀 가동률 안정화.
- 장애 발생 시에도 구매 로그 기반의 재정산이 가능한 복구 구조 확보.

---

## 8. 조회 성능 최적화: 수집 시점 역정규화 설계

```mermaid
flowchart LR
    subgraph BEFORE["BEFORE: N+1 집계"]
        A1["Activity 1 조회"] --> AGG["백엔드 합산"]
        A2["Activity 2 조회"] --> AGG
        AN["Activity N 조회"] --> AGG
    end

    subgraph AFTER["AFTER: 역정규화 + 단일 집계"]
        SDK["JS SDK (캠페인 메타 포함)"] --> Entry2 --> Kafka2 --> ES[("ES 인덱스")]
        ES --> Q["단일 버킷 집계"]
    end
```

**문제**
- Campaign은 여러 CampaignActivity의 집합으로 구성. 캠페인 레벨 대시보드 조회 시 해당 Campaign에 속한 모든 Activity를 각각 쿼리한 뒤 백엔드에서 합산하는 방식이었음.
- Activity 수가 늘어날수록 쿼리 수가 선형으로 증가하는 N+1성 연산으로 조회 지연과 타임아웃 발생.
- 분석 워크로드와 OLTP 워크로드가 동일 DB 자원을 경합하며 서로의 성능을 저하시키는 리소스 간섭 현상 실측.

**해결**
- 개별 Activity 조회 후 합산하는 방식 대신, 데이터 수집 단계(JS SDK)에서 `campaign_id`, `activity_id` 등 메타데이터를 이벤트 페이로드에 미리 포함하여 전송하는 **의도적 역정규화(Denormalization)** 단행.
- Elasticsearch에서 단일 인덱스 쿼리와 버킷 집계만으로 캠페인 레벨 통계 산출 가능 — 백엔드 N+1 집계 연산 제거.
- 분석용 저장소(ES)와 처리용 저장소(MySQL)의 책임 분리로 OLAP/OLTP 리소스 간섭 제거.

**결과**
- 대시보드 주요 KPI 조회 성능 **440% 향상 (실측)**.
- 피크 타임에도 데이터 발생부터 대시보드 반영까지의 지연 시간 최소화.

---

## 9. AI 에이전트 최적화: RAG와 Function Calling의 결합

```mermaid
flowchart TD
    User --> Server["AI 에이전트"]
    Server -->|"1. 의도 분석"| LLM["Gemini"]
    LLM -->|"2. 도구 선택 및 호출"| Tool["get_global_dashboard<br/>get_cohort_analysis 등"]
    Tool -->|"3. Safety Guard"| Cache["LTV 배치 캐시"]
    Cache --> LLM --> User
```

**문제**
- Campaign/Activity 단위 질문("이 캠페인의 전환율은?")은 현재 대시보드 데이터를 RAG 컨텍스트로 주입하여 LLM이 바로 답변 가능.
- 하지만 "이번 달 전체 캠페인 중 LTV/CAC 비율이 가장 높은 곳은?" 같은 Overview 수준의 질문은 컨텍스트에 없는 데이터를 요구. 모든 캠페인 데이터를 프롬프트에 Full Injection하면 캠페인당 Activity 수 × KPI 수 × 행동 로그 집계 데이터가 모두 포함되어 컨텍스트 크기가 선형으로 증가 — 컨텍스트 윈도우 제한 및 토큰 비용 급증.
- LLM이 원천 데이터 없이 수치를 추론할 경우 Hallucination 위험, 실시간 DB 직접 접근은 OLTP 부하 가중 우려.

**해결**
- 정적 지식 검색(RAG)과 **Native Function Calling**을 결합한 하이브리드 추론 엔진 설계.
- Overview 수준 질문 시 LLM이 스스로 필요한 도구(`get_global_dashboard`, `get_campaign_dashboard`, `get_cohort_analysis` 중 선택)를 호출 — 질문의 의도에 맞는 데이터만 가져오는 구조.
- **Safety Guard Layer**: LLM 도구 호출 시 실시간 DB 조회를 차단하고 사전 계산된 LTV Batch 캐시 테이블만 참조하도록 강제 — 분석 쿼리의 OLTP 부하를 구조적으로 차단.

**결과**
- Full Context 주입 대비 토큰 소모 절감 (추정: 약 80% — Activity KPI·행동 로그 집계를 포함한 전체 컨텍스트 대비, Function Calling 시 특정 질문에 필요한 집계 데이터만 조회하는 구조적 차이에서 기인).
- 실시간 집계 쿼리 대신 LTV Batch 캐시 우선 참조로 메인 DB 부하 경감 (추정: 약 70% — 월별 사전 계산된 배치 테이블이 실시간 Purchase 테이블 집계를 대체).
- 구조화된 API 응답 기반 추론으로 수치 관련 Hallucination 방어.

---

## 10. 비즈니스 가치 창출: 코호트 기반 LTV 분석 파이프라인

```mermaid
flowchart LR
    Cohort["유입 코호트 정의"] --> Track["재구매 이력 추적"]
    Track --> Calc["LTV / CAC 산출"]
    Calc --> Batch["배치 캐시 적재"]
    Batch --> Insight["AI 전략 리포트 연동"]
```

**문제**
- 선착순 이벤트로 유입된 고객의 단기 전환 성과를 넘어, 마케팅 투자가 장기 수익성(LTV)으로 이어지는지 판단할 수 있는 정량적 지표 부재.
- 유입 고객별 재구매 이력을 대시보드 조회 시마다 실시간으로 집계하는 방식은 쿼리 비용과 서비스 가용성 모두 문제.
- 30일/90일/365일 단위의 생애 가치 성장을 시계열로 가공하는 프로세스 부재.

**해결**
- 실시간 집계는 서비스 DB에 부하를 주므로 Read Replica 분리를 검토했으나, LTV 코호트 집계 자체가 전체 구매 이력을 스캔하는 무거운 쿼리라 분리된 읽기 전용 인스턴스에서도 서비스에 영향을 줄 수 있다고 판단 → 서비스 트래픽이 없는 새벽 시간대에 실행하는 **일 단위 배치** 방식으로 결정.
- 유입 시점(Cohort)을 기준으로 고객군을 그룹화하고, 기간별 누적 매출 및 획득 비용(CAC)을 자동 추적하는 **코호트 분석 엔진** 설계.
- 배치 결과를 대시보드 및 AI 에이전트(Section 9)와 연동하여 캠페인 ROI를 정량 수치 기반으로 즉각 판단할 수 있는 의사결정 보조 시스템 구성.

**결과**
- 캠페인 효율성(LTV/CAC Ratio)을 정량화하여 수익성 기반의 마케팅 예산 재분배 의사결정 지원.
- 배치 캐시 활용으로 코호트 분석 조회 시 DB 직접 집계 없이 안정적인 지표 제공 구조 확보.
