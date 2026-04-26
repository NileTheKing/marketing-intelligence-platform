# [Design] CRM Funnel Terminology Standardization Plan

## 🎯 개요 (Objective)
본 문서는 Axon 시스템의 고도화 단계에서 데이터 분석의 정합성을 높이고, 다양한 캠페인(선착순, 응모, 쿠폰 등)으로 확장할 때 일관된 퍼널 분석이 가능하도록 이벤트 트리거 명칭을 표준화하는 계획을 담고 있습니다.

## 📉 현재 상태 (Current State)
현재 시스템은 기술적 로직 중심의 트리거 명칭을 혼용하고 있어, 대시보드 집계 시 별도의 매핑 레이어에 크게 의존하고 있습니다.
- **VISIT**: `PAGE_VIEW` (프론트엔드 일반 용어)
- **ENGAGE**: `CLICK` (프론트엔드 일반 용어)
- **QUALIFY**: `APPROVED` (FCFS 로직 중심 용어)
- **PURCHASE**: `PURCHASE` (커머스 표준 용어)

## 🚀 로드맵: 트리거 타입 표준화 (UPPER_SNAKE_CASE)

### 1단계: 비즈니스 중심의 명칭 통일
시스템 로직(Approved)보다는 고객 여정(Qualified) 관점에서 용어를 재정의합니다.

| 현재 (System-Centric) | 변경안 (Customer-Centric) | 비고 |
| :--- | :--- | :--- |
| `PAGE_VIEW` | **`VISIT`** | 사이트 진입 및 랜딩 페이지 조회 |
| `CLICK` | **`ENGAGE`** | 참여 버튼 클릭 등 활성 참여 의지 |
| `APPROVED` | **`QUALIFIED`** | FCFS 선점, 응모 당첨, 쿠폰 발급 성공 |
| `PURCHASE` | **`PURCHASE`** | 최종 결제/확정 완료 |

### 2단계: 구성 요소별 수정 사항

#### [Entry-Service]
- `BehaviorEventAdapter` 내 트리거 상수 변경: `APPROVED` ➔ `QUALIFIED`
- FCFS 통과 시 발행되는 이벤트명 정비.

#### [Core-Service]
- `BehaviorEventService`: 하위 호환성을 위해 `getQualifyTriggerTypes()` 리스트에 `QUALIFIED`와 `APPROVED`를 모두 포함한 후 점진적으로 `APPROVED` 제거.
- `DashboardService`: 하드코딩된 스트링을 `TriggerType` Enum 상수로 교체.

## 🏗️ 엔지니어링 기대 효과 (Engineering Rationale)

1. **도메인 주도 데이터 설계 (Domain-Driven Data)**: 기술적 상태(Database State)가 아닌 비즈니스 상태(Lifecycle Stage)로 데이터를 관리하여 마케팅/기획팀과의 커뮤니케이션 비용을 절감합니다.
2. **확장성 (Scalability)**: 향후 '럭키드로우(WON)', '타임세일(CLAIM)' 등 새로운 캠페인 유형이 추가되어도 동일한 `QUALIFIED` 퍼널 내에서 즉시 분석이 가능합니다.
3. **분석 정합성 (Data Integrity)**: `triggerType`이 곧 `FunnelStep`과 일치하게 되어, 분석 쿼리의 복잡도가 낮아지고 대시보드 로딩 성능이 향상됩니다.

---

> [!TIP]
> **면접 시 포인트**: "데이터를 단순히 쌓는 것을 넘어, 전사적 공통 언어(Ubiquitous Language)를 데이터 스키마에 투영하여 데이터 분석 서비스의 확장성을 확보하려 노력했습니다."라는 논리로 활용하십시오.
