<h1 align="center">Axon: 실시간 대규모 트래픽 처리 및 마케팅 분석 플랫폼</h1>
<p align="center">
  <b>선착순 이벤트 제어와 고객 생애 가치(LTV) 분석을 결합한 통합 CRM 솔루션</b><br>
  대규모 트래픽 환경에서의 데이터 정합성 보장 및 비동기 파이프라인 구축
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=java" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen?style=flat-square&logo=springboot" />
  <img src="https://img.shields.io/badge/Apache%20Kafka-KRaft-black?style=flat-square&logo=apachekafka" />
  <img src="https://img.shields.io/badge/Elasticsearch-8.x-005571?style=flat-square&logo=elasticsearch" />
  <img src="https://img.shields.io/badge/Redis-Lua-DC382D?style=flat-square&logo=redis" />
</p>

---

## 프로젝트 개요
Axon은 대규모 프로모션 시 발생하는 **급격한 접속자 유입 상황에서도 시스템 안정성을 유지**하고, 유입된 고객 데이터를 실시간으로 분석하여 **마케팅 의사결정을 돕는 지능형 플랫폼**입니다. 요청 수집과 비즈니스 로직 처리를 물리적으로 분리한 **부하 충격 완화 구조**를 통해 대규모 스파이크 트래픽을 안정적으로 수용합니다.

---

## 비즈니스 시나리오 및 성과
> "3,000명의 접속자가 2초 만에 200개의 한정 상품에 응모하며 대량의 행동 로그를 생성하는 극한 상황"을 가정하여 검증을 수행했습니다.

| 측정 항목 | 결과 수치 | 비고 |
| :--- | :---: | :--- |
| **최대 가용량** | **2,900 RPS / 3,000 VU** | 스파이크 구간 피크 처리량 실측 |
| **응답 품질** | **Avg 1.2s / p95 3.99s** | 극한 부하 상황의 지연 시간 관리 |
| **통합 로그 처리량** | **20,000+ EPS** | 인프라/미들웨어/애플리케이션 로그 통합 적재 |
| **선착순 정합성** | **오버부킹 0건** | 10,655건 응모 중 정확히 200건만 당첨 |
| **데이터 무결성** | **Loss 0%** | 21,310건의 행동 로그 전량 유실 없이 적재 완료 |
| **시스템 안정성** | **에러율 0.00%** | 비즈니스 응답(410, 409) 제외 서버 에러 0건 |

<details>
<summary><b>부하 테스트 데이터 상세 해석</b></summary>

- **꼬리 지연 시간(Tail Latency) 방어**: 3,000명의 동시 접속자가 쏟아지는 상황에서도 p95 지연 시간을 3.99s 이내로 관리하여, 시스템 응답 불능 없이 모든 요청을 완주했습니다.
- **복합 워크로드 수용**: 전체 트래픽의 93%를 차지하는 행동 로그 수집(62%)과 선착순 응모(31%)가 혼재된 상황에서, 나머지 7%의 결제 및 인증 트래픽까지 서버 붕괴 없이 안정적으로 처리했습니다.
- **의도된 비즈니스 응답**: k6 결과상의 `http_req_failed(30.4%)`는 시스템 오류가 아닌, 품절(410) 및 중복 참여 차단(409)이라는 설계된 비즈니스 로직의 정상 작동 결과입니다.
</details>

---

## 시스템 아키텍처

### 서비스 논리 구조
요청 수집(Entry)과 비즈니스 처리(Core) 서비스를 분리하여 부하 충격을 완화하고, Kafka를 통해 데이터 처리 속도를 조절하는 **배압 조절(Backpressure)** 구조를 채택했습니다.

```mermaid
graph TB
    subgraph Client["브라우저"]
        Browser["UI (Thymeleaf/JS)"]
        JSTracker["자체 행동 수집 SDK"]
    end

    subgraph Entry["Entry Service (유입 제어)"]
        EntryController["Entry Controller"]
        FastValidation["조건 검증 (Redis)"]
        FCFSLogic["선착순 제어 (Redis Lua)"]
        EntryKafka["Kafka Producer"]
    end

    subgraph MQ["메시지 브로커"]
        Kafka[("Apache Kafka")]
    end

    subgraph Core["Core Service (로직/분석)"]
        KafkaConsumer["Kafka Consumer (Batch)"]
        CampaignLogic["비즈니스 로직"]
        LLMController["AI 에이전트 (Gemini)"]
        DashboardLogic["분석/대시보드"]
        MySQL[("MySQL")]
    end

    subgraph Pipeline["데이터 파이프라인"]
        KafkaConnect["Kafka Connect"]
        ES[("Elasticsearch")]
    end

    %% Flow
    Browser --> EntryController
    JSTracker --> EntryController
    EntryController --> FastValidation
    EntryController --> FCFSLogic
    FCFSLogic --> EntryKafka
    EntryKafka --> Kafka
    Kafka --> KafkaConsumer
    KafkaConsumer --> CampaignLogic
    CampaignLogic --> MySQL
    Kafka --> KafkaConnect
    KafkaConnect --> ES
    LLMController --> MySQL
    DashboardLogic --> ES
    DashboardLogic --> MySQL
```

### 인프라 및 클라우드 구성
<p align="center">
  <img src="./docs/assets/recordings/archi.png" width="850" />
</p>

- **Cloud Platform**: KT Cloud K2P (Kubernetes to Production) 환경 기반.
- **Network & Security**: Public IP를 특정 워커 노드에 1:1 매핑(Static NAT)하고, 방화벽 설정을 통해 특정 포트만 허용하는 구조.
- **배포 자동화 (CI/CD)**: GitHub Actions를 통해 메인 브랜치 푸시 시 Docker 이미지 빌드 및 K2P 클러스터 자동 배포 수행.

---

## 핵심 엔지니어링 사례
> 상세한 기술적 의사결정 과정은 [Architecture Deep-Dive 포트폴리오](./docs/PORTFOLIO_DIAGRAMS.md)에서 확인하실 수 있습니다.

### 1. 비동기 환경의 순서 정합성 해결을 위한 로직 전진 배치
초기 설계 시 선착순 판단을 Core 서비스에 두었으나, Kafka 비동기 소비 특성상 요청-처리 순서 불일치 현상이 발견되었습니다. 이를 해결하기 위해 검증 로직을 시스템 최전방인 **Entry 서비스로 전진 배치**하여 유입 시점에 즉각 당첨을 확정하는 구조로 개선했습니다. 더불어 Redis Lua 스크립트를 도입하여 중복 체크와 수량 차감을 단일 연산으로 처리함으로써 오버부킹 0건을 달성했습니다.

### 2. 트랜잭션 격리 및 장애 파급 차단을 통한 데이터 신뢰성 확보
대량 저장 중 단 1건의 오류가 전체 배치를 롤백시키는 '배치 오염'을 방지하기 위해 `REQUIRES_NEW` 속성을 적용하여 개별 트랜잭션을 물리적으로 분리했습니다. 실패 건만 Dead Letter Queue(DLQ)로 격리하고 나머지 데이터는 보존하는 폴백 전략을 구축하여, 비정상 데이터 유입 시에도 파이프라인 중단 없이 데이터 유실 0%를 실증했습니다.

### 3. 쓰기 병목 해소를 위한 지연 동기화 설계
구매 확정 시 상품 재고와 유저 요약 정보를 실시간 업데이트할 때 발생하는 DB Row Lock 경합을 해결하기 위해 **결과적 일관성(Eventual Consistency)** 모델을 채택했습니다. 메인 트랜잭션에서는 구매 로그만 남기고, 재고 차감 등 무거운 쓰기 작업은 스케줄러가 사후 정산하게 하여 커넥션 풀 안정화 및 처리량 극대화에 성공했습니다.

### 4. 수집 시점 역정규화를 통한 조회 성능 개선
수백만 건의 로그를 대시보드에서 조인 조회할 때 발생하는 N+1 문제를 해결하기 위해, 데이터 수집(SDK) 단계에서 메타데이터를 결합하여 전송하는 **의도적 역정규화(Denormalization)** 설계를 적용했습니다. 이를 통해 Elasticsearch 단일 인덱스 쿼리만으로 통계를 산출하여 대시보드 조회 성능을 **440% 향상**시켰습니다.

### 5. 데이터 무결성 최후의 보루: 사후 대사(Reconciliation) 아키텍처
극단적인 1ns 응답 속도 확보를 위해 이벤트 전송과 영속성을 비동기로 분리(BEFORE_COMMIT)하면서 생겨날 수 있는 트랜잭션 롤백 시차 등의 'Ghost Data(고아 데이터)' 리스크를 방어하기 위해, 유휴 시간대(새벽 3시)에 작동하는 비동기 대사 스케줄러를 구축했습니다. 1차 방어막(REQUIRES_NEW)과 2차 백그라운드 정산 로직을 통해 시스템 가용성과 무결성의 타협점을 완벽히 검증했습니다.

---

## 주요 기능

### 1. 마케팅 인텔리전스 (Analytics & AI)

#### 계층형 성과 대시보드
<p align="center">
  <img src="./docs/assets/recordings/dashboard_overview.png" width="850" />
  <br><em>Level 1: 전역 성과 - 전체 캠페인 통합 매출 및 효율 지표</em>
</p>

<p align="center">
  <img src="./docs/assets/recordings/campaign_admin.png" width="850" />
  <br><em>Level 2: 캠페인 성과 - 개별 캠페인 내 활동들의 성과 기여도 비교</em>
</p>

<p align="center">
  <img src="./docs/assets/recordings/dashboard_11.png" width="850" />
  <br><em>Level 3: 활동 심층 분석 - 실시간 참여 지표 및 유입 트렌드 모니터링</em>
</p>

<p align="center">
  <img src="./docs/assets/recordings/dashboard_cohort.png" width="850" />
  <br><em>코호트 및 LTV 분석 - 유입 고객의 재구매율 및 장기 가치 추적</em>
</p>

- **코호트 및 LTV 분석**: 마케팅 유입 시점(Cohort)을 기준으로 생애 가치(LTV)와 획득 비용(CAC)을 장기 추적하는 의사결정 지표 제공.
- **RFM 세그먼테이션 스케줄러**: 최근성(Recency), 구매 빈도(Frequency), 누적 금액(Monetary) 데이터를 기반으로 매일 유저 등급(VIP, 이탈 우려 등)을 재분류하는 자동화 파이프라인.

#### 하이브리드 AI 전략 에이전트 (Gemini 2.5 Flash-lite)
<p align="center">
  <img src="./docs/assets/recordings/dashboard_llm.gif" width="800" />
</p>

- **데이터 기반 리포팅**: 실시간 지표(RAG)와 분석 도구(Tool Calling)를 결합하여 "LTV/CAC 기반 예산 재분배 전략" 등 구체적인 리포트 생성.
- **최적화 성과**: 필요한 데이터만 선택 호출하는 구조를 통해 데이터 전수 주입 방식 대비 **토큰 소모량 80% 절감** 및 DB 조회 부하 경감.

#### 실시간 지표 스트리밍
<p align="center">
  <img src="./docs/assets/recordings/dashboard_sse.gif" width="850" />
</p>

- **지연 없는 가시성**: SSE 프로토콜을 활용하여 이벤트 발생부터 대시보드 반영까지의 파이프라인 정합성 실시간 유지.

### 2. 운영 및 시스템 검증 (Operation & Verification)

#### CRM 운영 관리 및 행동 기반 동적 쿠폰 트리거
<p align="center">
  <img src="./docs/assets/recordings/event_admin.png" width="850" />
</p>

- **코드 수정 없는 추적**: 자체 개발한 JS SDK를 통해 관리자 화면에서 클릭, 페이지 뷰 등 수집 조건을 동적으로 등록 및 제어.
- **실시간 반응형 쿠폰 (Behavior Trigger)**: "특정 상품 5회 이상 열람" 등 유저의 고관여 행동 패턴을 ES로 실시간 집계하여, 조건 달성 시 **Kafka를 통해 무지연(Zero-latency)으로 할인 쿠폰을 발급**하는 마케팅 통합 루프 구현.
- **캠페인 생명주기 관리**: 마케팅 활동의 상태, 한정 수량, 예산 등을 실시간으로 관리하는 통합 운영 콘솔.

#### 대규모 트래픽 수용성 검증
<p align="center">
  <img src="./docs/assets/recordings/k6_spike.gif" width="850" />
</p>

- **극한 환경 가용성 실증**: 3,000 VU(Peak 2,900 RPS) 부하 상황에서도 5XX 에러 0건을 유지하며 시스템 안정성 검증 완료.

<table>
  <tr>
    <td><img src="./docs/assets/recordings/k6_result_stat.png" width="450" /></td>
    <td><img src="./docs/assets/recordings/k6_result_db.png" width="450" /></td>
  </tr>
  <tr align="center">
    <td><b>[k6 최종 결과] 3,000 VU 완주</b></td>
    <td><b>[정합성 검증] 유입 대비 처리량 일치</b></td>
  </tr>
</table>

---

## 기술 스택
- **Application**: Java 21, Spring Boot 3.x, Virtual Threads
- **Messaging**: Apache Kafka (KRaft), Redis
- **Storage**: MySQL 8 (Master-Slave), Elasticsearch 8
- **Infrastructure**: Kubernetes (K2P), Nginx Ingress Controller
- **Monitoring**: Prometheus, Grafana, Fluent Bit, Kibana

---

## 빠른 시작 (Getting Started)
본 프로젝트는 클라우드 환경(K2P)을 기반으로 설계되었으나, 로컬 검증을 위해 Docker Compose를 통한 원클릭 인프라 구축을 지원합니다.

1. **인프라 환경 구성**
   ```bash
   docker-compose up -d
   ```
2. **서비스 실행**
   ```bash
   ./gradlew :entry-service:bootRun
   ./gradlew :core-service:bootRun
   ```
3. **대시보드 접속**
   - 브라우저에서 `http://localhost:8080/admin/dashboard/1` 접속 시 실시간 지표 및 AI 분석 기능을 확인할 수 있습니다.
