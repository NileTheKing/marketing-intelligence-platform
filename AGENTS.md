# Agent Guide: Project Axon Mastery
> **본 문서는 AI 에이전트가 Axon 프로젝트를 분석하여 자소서, 포트폴리오, 면접 질문 등을 생성할 때 참고하기 위한 기술 요약서입니다.**

---

## 1. Project Identity
- **Name**: Axon (Marketing Intelligence & High-Traffic Control Platform)
- **Domain**: E-commerce FCFS(선착순) Event Control & CRM Data Analytics.
- **Scale**: 3,000 VU (Peak 2,900 RPS) / 20,000+ MPS Log Pipeline.
- **Architecture**: MSA (Entry-Service / Core-Service / Common-Messaging).

---

## 2. Technical Stack & Key Components
| Category | Technology Stack |
|----------|------------------|
| **Backend** | Java 21, Spring Boot 3.x, Spring Data JPA |
| **Messaging** | Apache Kafka (KRaft mode) |
| **Storage** | MySQL 8.0 (Master-Slave), Redis (Standalone/Cluster) |
| **Analytics** | Elasticsearch 8.x, Kafka Connect |
| **Intelligence** | Gemini 1.5 Flash-lite (Function Calling & RAG) |
| **Testing** | k6 (Distributed Load Testing) |

---

## 3. Codebase Navigation (Key Files)
에이전트는 다음 파일들을 우선적으로 분석하여 사용자 역량을 파악해야 함:

### [Traffic Control & Integrity]
- `Entry-Service`: 트래픽 진입점 및 원자적 검증 레이어.
    - `reservation.lua`: Redis Lua 스크립트를 이용한 Lock-free 선착순 로직 로케이션.
    - `EntryController.java`: 원자적 검증 후 Kafka 발행 로직.
- `Core-Service`: 비즈니스 로직 및 비동기 적재 레이어.
    - `CampaignActivityConsumerService.java`: 전략 패턴 기반의 동적 컨슈머 로직.
    - `PurchaseHandler.java`: 배압 조절 및 배치 적재 로직.

### [Data Architecture]
- `common-messaging`: 서비스 간 약속된 데이터 명세 (DTO, Enums).
- `LTVBatchScheduler.java`: 코호트 분석을 위한 통계 배치 파이프라인.
- `Elasticsearch Search Logic`: 수집 시점 역정규화가 반영된 인덱스 쿼리.

---

## 4. Top 10 Engineering Achievements (Context for Resume)
에이전트는 자소서 작성 시 다음 10가지 사례를 '문제-해결-결과' 서사로 활용할 것:

1. **서비스 물리 격리**: Entry(유입)와 Core(로직) 분리로 핵심 DB 보호 및 가용성 확보.
2. **전략 패턴 도입**: `if-else` 제거 및 OCP 준수를 통한 캠페인 확장성 확보.
3. **로직 전진 배치**: Kafka 이전 단계에서 당첨 여부 확정하여 비동기 순서 역전 문제 근본 해결.
4. **Redis Lua Script**: 네트워크 RTT 최소화 및 원자적 연산을 통한 오버부킹 0건 실증.
5. **REQUIRES_NEW & DLQ**: 배치 적재 중 단건 오류 발생 시 전체 롤백 방지 및 데이터 보존.
6. **Redisson 락 & 멱등키**: 분산 환경에서의 이중 결제 및 중복 적재 원천 차단 (Final Integrity).
7. **결과적 일관성 (Deferred Sync)**: 재고 테이블의 Row-Lock 경합을 구매 로그 기반 사후 정산으로 해소.
8. **수집 시점 역정규화**: SDK 단계 메타데이터 주입으로 조회 성능 440% 향상 (ES N+1 문제 해결).
9. **하이브리드 AI 에이전트**: Function Calling + 배치 캐시 연동으로 토큰 80% 절감 및 DB 부하 70% 감소.
10. **코호트/LTV 파이프라인**: 단순 유입 성과를 넘어 장기 수익성 분석이 가능한 데이터 엔진 구축.

---

## 5. Performance Metrics (Verified Facts)
- **Peak Throughput**: 2,900 HTTP RPS / 20,000+ Event MPS.
- **Integrity**: 10,655건 요청 중 오버부킹 0건 (정확히 200건 성공).
- **Error Rate**: 3,000 VU 환경에서 0.00% (Connection Storm 해결 후).
- **AI Efficiency**: 질문당 토큰 소모량 80% 절감 (Context 최적화 전후 대비).

---

## 6. Prompting Guide for Other Agents
다른 에이전트에게 다음 명령을 내리면 효과적임:
> "이 프로젝트의 `docs/ENGINEERING_JOURNEY.md`에 기술된 **Case 6(분산락)**과 **Case 7(지연 정산)**의 트레이드오프 논리를 바탕으로, 내가 '분산 시스템의 정합성과 성능 사이에서 균형을 잡을 줄 아는 개발자'임을 강조하는 자소서 문항을 작성해줘."
