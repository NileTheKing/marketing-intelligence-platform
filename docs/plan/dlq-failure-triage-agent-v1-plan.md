# DLQ Failure Triage Agent v1

> Status: `active` (implementation plan)
>
> Scope: Core의 마케팅 액션 최종 실패를 운영자가 빠르게 판단·승인할 수 있게 한다. AI는 분석과 요약만 하고, 재실행은 사람의 승인 뒤 Core가 수행한다.

## 1. 문제와 목표

쿠폰 발급 또는 Webhook 전달이 자동 재시도 후에도 실패하면 Kafka DLT에 격리된다. 현재는 DLT를 Core의 실행 이력으로 남기고 관리자 재실행 API를 호출할 수 있지만, 운영자는 다음을 직접 조합해야 한다.

- 어떤 사용자·어떤 액션·어떤 발송 시도가 실패했는가
- 일시 장애라 재실행할 만한가, 잘못된 쿠폰/대상처럼 재실행해도 소용없는가
- 같은 액션에서 최근에 반복되는 장애인가

v1의 목표는 이 판단 재료와 권고를 Slack으로 전달하고, 사람이 재실행 승인·추가 확인 요청·확인 결과 입력·종료를 선택하도록 만드는 것이다.

```text
DLT 최종 실패
  -> Core: 실패 이력 + triage case 저장
  -> AI triage service: Core의 읽기 도구로 사실 수집
  -> LangGraph: 정해진 JSON 권고 생성
  -> Core: 분석 결과 저장
  -> Slack: 운영자에게 요약과 승인/재분석/종료 버튼 전송
  -> 사람 승인
  -> Core: 새 Dispatch 생성 후 Kafka 재발행
```

## 2. 결정과 경계

| 항목 | v1 결정 | 이유 |
|---|---|---|
| 상태·권한 소유 | Core + MySQL | FastAPI와 Slack은 운영 보조 도구다. 실행 상태를 소유하거나 Kafka를 발행하지 않는다. |
| 사실 조회 | Core의 고정된 내부 API를 function tool로 호출 | LLM이 SQL, Elasticsearch DSL, Redis 명령을 만들지 않는다. |
| AI 역할 | 제한된 근거 참조값 선택, 실패 요약, 재실행 권고 | AI가 상태 변경·자동 재실행·인프라 변경을 하지 않는다. Core가 근거 문장을 렌더링한다. |
| 최종 실행 | 사람 승인 후 Core | 기존 `retry` 도메인 규칙과 Dispatch 동시성 제어를 재사용한다. |
| 알림 | Slack | 운영자가 보는 단일 채널로 시작한다. Discord, n8n은 추가하지 않는다. |
| 문서 지식 | Core가 선택한 짧은 운영 가이드만 전달 | 현재 문서 규모에서 RAG/벡터 DB는 비용 대비 이득이 없다. |
| LangGraph 저장소 | MySQL checkpointer | Slack의 사람 피드백 뒤 같은 분석 흐름을 재개한다. Core 업무 테이블과 checkpointer 테이블은 별도 권한으로 분리한다. |

`MarketingActionExecution`과 `MarketingActionDispatch` 분리 계획이 먼저 적용되어야 한다. triage case는 **Dispatch 하나당 하나**를 가진다. 관리자 재실행은 새 Dispatch를 만들므로, 재시도도 다시 최종 실패하면 별도 case가 생긴다.

## 3. 데이터 모델

Core MySQL에 `marketing_action_triage_cases`를 추가한다.

```text
MarketingActionDispatch 1 --- 0..1 MarketingActionTriageCase

MarketingActionTriageCase
  id
  dispatch_id                 unique, FK
  status                      PENDING | ANALYZING | AWAITING_APPROVAL |
                              APPROVED | CLOSED | ANALYSIS_FAILED
  failure_category            INVALID_TARGET | TRANSIENT_DELIVERY |
                              RATE_LIMITED | AUTHORIZATION | UNKNOWN
  analysis_claim_token        FastAPI 분석 실행 소유권
  analysis_claim_expires_at   분석 프로세스 장애 시 재수거 기준
  analysis_attempt_count
  fact_snapshot_json          AI가 실제로 본 정형 사실의 스냅샷
  recommendation              RETRY_RECOMMENDED | MANUAL_INVESTIGATION |
                              NO_RETRY
  confidence                  0.0~1.0, 판단 보조 값일 뿐 자동 실행 기준이 아님
  analysis_summary            Slack에 보낼 짧은 한국어 요약
  operator_guidance           Core가 선택한 고정 운영 가이드
  llm_model                   재현·감사 용도
  failure_reason              분석 실패 시 원인
  slack_message_ts            중복 알림 방지용 식별자
  decided_by / decided_at
  rejected_reason
  created_at / updated_at
```

- `dispatch_id UNIQUE`로 동일 DLT가 Kafka에서 재전달되어도 case를 중복 생성하지 않는다.
- `failure_category`는 raw 예외 메시지의 정규식 추론이 아니라, Coupon/Webhook 처리부가 DLT를 만들 때 함께 넣는 코드값이다.
- `fact_snapshot_json`은 LLM이 확인한 Core 사실과, 재분석 때 관리자가 입력한 확인 결과를 출처와 함께 저장한다. 생성 문장만 남기지 않아 운영자가 근거를 재확인할 수 있다.
- 사용자 개인정보·전체 Kafka payload·인증 토큰은 case와 Slack에 저장하지 않는다. 필요한 식별자는 내부 ID와 마스킹된 대상 정보로 제한한다.

`analysis_claim_expires_at`은 AI worker가 case를 가져간 뒤 죽었을 때의 안전장치다. 예를 들어 10분 안에 분석 결과를 저장하지 못하면 다른 worker가 그 case를 다시 가져갈 수 있다. 이것은 **작업 점유 시간**이고, LangGraph가 어디까지 판단했는지는 저장하지 않는다.

LangGraph의 중간 상태는 별도 MySQL checkpointer 테이블에 저장한다. `thread_id`는 `triageCaseId`로 고정한다. 이 저장소는 "사람의 반려 사유를 받아 같은 그래프를 이어서 실행하기 위한 작업 노트"이며, Dispatch·승인·재실행 상태의 정본은 아니다.

## 4. Core 변경

### 4.1 DLT 최종화

1. Coupon/Webhook이 최종 실패할 때 `dispatchId`, channel, `failureCategory`, 사람이 읽을 수 있는 짧은 원인을 DLT envelope에 넣는다.
2. 기존 DLT consumer는 Dispatch를 `FAILED_FINAL`로 갱신한다.
3. 같은 Core 트랜잭션에서 해당 Dispatch의 triage case를 `PENDING`으로 `insert-if-absent` 한다.
4. DLT consumer가 DB 갱신에 실패하면 listener 예외를 전파해 offset commit을 막는 현재 원칙을 유지한다.

`INVALID_TARGET`(없는 사용자·쿠폰 등)은 재실행해도 성공할 수 없는 결정적 실패다. `TRANSIENT_DELIVERY`, `RATE_LIMITED`, `AUTHORIZATION`, `UNKNOWN`은 사람이 판단할 실패다. 정확한 분류가 불가능하면 `UNKNOWN`으로 저장한다.

### 4.2 내부 triage API

FastAPI 전용이며 `ROLE_SYSTEM` 또는 별도 service token만 허용한다. 외부 브라우저 API로 노출하지 않는다.

| API | 역할 |
|---|---|
| `POST /internal/v1/marketing-triage/cases/claim` | `PENDING` 또는 분석 점유 시간이 만료된 `ANALYZING` case 하나를 원자적으로 claim하고 최소 컨텍스트 반환 |
| `GET /internal/v1/marketing-triage/dispatches/{dispatchId}/context` | 대상, 액션 설정, 현재 Dispatch, 실패 category/reason, 이전 Dispatch 요약 반환 |
| `GET /internal/v1/marketing-triage/actions/{actionId}/failure-history` | 제한된 기간의 같은 액션 실패 건수·category 집계 반환 |
| `GET /internal/v1/marketing-triage/executions/{executionId}/dispatch-history` | 해당 대상의 Dispatch 이력 반환 |
| `POST /internal/v1/marketing-triage/cases/{caseId}/analysis` | 유효한 claim token일 때만 분석 결과를 저장하고 `AWAITING_APPROVAL`로 전이 |
| `POST /internal/v1/marketing-triage/cases/{caseId}/decision` | `APPROVE`/`CLOSE`를 원자적으로 반영 |

- `claim`은 `analysisClaimToken`과 짧은 분석 점유 만료 시각을 발급한다. FastAPI 장애 뒤에는 만료된 case를 다시 분석할 수 있다. 자동 worker는 `PENDING`과 점유 만료 case만 가져가며, `ANALYSIS_FAILED`는 Slack에서 운영자가 추가 확인 또는 확인 결과를 제출할 때만 다시 claim한다.
- 모든 조회 API는 페이징/기간 상한을 둔다. 동적 SQL, 임의 테이블 조회, 전체 payload 조회는 제공하지 않는다.
- `decision=APPROVE`는 parent Execution을 잠그고 최신 Dispatch가 여전히 `FAILED_FINAL`인지 확인한 뒤, 기존 관리자 재실행 규칙으로 새 Dispatch를 생성한다. Kafka 발행은 그 DB 트랜잭션 commit 뒤에 한다.
- `decision=CLOSE`는 triage case만 종료한다. 기존 Dispatch와 DLT 이력은 유지한다.
- 중복 Slack 클릭이나 이미 종료된 case는 새 Dispatch를 만들지 않고 현재 상태를 돌려준다.

### 4.3 운영 가이드

Core는 `failureCategory + channel`에 따라 짧은 `operatorGuidance`를 선택해 컨텍스트에 포함한다.

예시:

- `INVALID_TARGET`: 대상/쿠폰 설정을 수정한 뒤 필요하면 새 액션을 생성한다. 같은 Dispatch 재실행을 권하지 않는다.
- `RATE_LIMITED`: 외부 수신자 제한과 재시도 시점을 확인한다.
- `TRANSIENT_DELIVERY`: 최근 같은 Webhook endpoint 실패율과 정상화 여부를 확인한 뒤 재실행을 검토한다.

이는 검색용 문서가 아니라 코드로 선택되는 소형 runbook이다. v1에는 embedding, vector DB, RAG를 넣지 않는다.

## 5. AI triage service

새 Python `ai-triage-service`를 FastAPI + LangGraph로 추가한다. 별도 Docker Compose `ai` profile에서만 실행한다.

### 5.1 책임

- 주기적으로 `claim` API를 호출해 case를 하나씩 가져온다.
- Core의 읽기 전용 function tool만 호출한다.
- 정해진 출력 schema로 권고를 만든 뒤 Core에 저장한다.
- 저장 성공 case만 Slack으로 알린다.
- Slack 재실행 승인/추가 확인 요청/확인 결과 입력/종료 요청의 서명을 검증하고, 그래프 재개 또는 Core decision API 호출을 수행한다.

FastAPI는 Core의 MySQL 업무 테이블·Kafka·Redis에 직접 접속하지 않는다. 단, LangGraph checkpointer 전용 MySQL schema에는 별도 최소 권한 계정으로 접근한다. 재실행 Kafka command를 만들거나 발행하지 않는다.

### 5.2 LangGraph 흐름

```text
claim case
  -> checkpointer에 thread_id=triageCaseId로 그래프 시작 상태 저장
  -> load dispatch context
  -> deterministic route
      INVALID_TARGET -> NO_RETRY 요약 생성
      그 외 -> LLM agent
  -> agent가 필요할 때만 read-only tools 호출
      failure history / dispatch history / action context
  -> schema validation -> Core에 analysis 저장 -> Slack 알림
  -> interrupt: 사람 판단 대기
      승인 -> 같은 thread 재개 -> Core APPROVE -> 새 Dispatch 발행
      추가 확인 요청 또는 확인 결과 입력 -> 같은 thread 재개 -> 사실 재확인/요약 갱신 -> Slack 갱신
      종료 -> Core CLOSE -> END
```

LLM tool은 아래 세 개로 제한한다.

```text
get_dispatch_context(dispatchId)
get_action_failure_history(actionId, days <= 30)
get_execution_dispatch_history(executionId)
```

출력은 Pydantic schema로 강제한다.

```json
{
  "recommendation": "RETRY_RECOMMENDED | MANUAL_INVESTIGATION | NO_RETRY",
  "confidence": 0.0,
  "summary": "운영자가 읽는 2~4문장 요약",
  "evidence_refs": ["CURRENT_DELIVERY_FAILURE"],
  "operator_next_step": "승인 전 확인할 한 가지 또는 두 가지"
}
```

- `evidence_refs`는 `CURRENT_DELIVERY_FAILURE`, `RECENT_ACTION_FAILURES`, `EXECUTION_DISPATCH_HISTORY`, `OPERATOR_CONFIRMED_RECOVERY` 중에서만 선택한다. Core는 스냅샷에 해당 사실이 있을 때만 한국어 문장으로 렌더링·저장한다. AI가 만든 근거 문장은 Slack에 표시하지 않는다.
- JSON 검증 실패, 허용되지 않은 근거 참조값, 허용되지 않은 권고는 `ANALYSIS_FAILED`로 기록하고 Slack 재실행 버튼을 제공하지 않는다.
- `confidence`는 모델의 자기평가다. 값이 높아도 자동 재실행하지 않는다.
- Jev 같은 별도 분류 모델은 넣지 않는다. v1의 결정적 실패 라우팅은 코드 조건으로 충분하고, triage 건수도 별도 모델 도입을 정당화할 규모가 아니다.

분석 결과를 Core에 저장하고 Slack을 보낸 뒤에는 Core case를 `AWAITING_APPROVAL`로 둔다. 이때 분석 점유는 해제한다. LangGraph checkpointer만 해당 case의 `interrupt` 지점과 사람이 보기 직전의 사실·초안을 저장한다. FastAPI가 재시작되어도 Slack action의 `triageCaseId`로 같은 graph thread를 재개할 수 있다.

출력 schema가 바뀐 뒤 이전 `evidence` 필드를 가진 checkpoint를 재개해야 하면, FastAPI는 그 checkpoint만 삭제하고 동일 `triageCaseId`로 최신 Core 사실과 사람 피드백을 다시 분석한다. Core의 case·Dispatch·결정 이력은 삭제하지 않는다.

### 5.3 Slack

Slack 메시지는 한국어 운영 문장으로 다음만 전달한다. Core JSON field name, 내부 상태값, 마케팅 룰의 행동 조건은 그대로 노출하지 않는다.

- action/channel, Dispatch ID, 실패 category, 시도 횟수
- Core가 렌더링한 `확인된 사실`과 AI의 `판단 요약`
- 승인 전 확인할 항목
- `추가 확인 요청`, `확인 결과 입력`, `종료` 버튼
- 권고가 `RETRY_RECOMMENDED`일 때만 `재실행 승인` 버튼

`추가 확인 요청`에는 AI가 다시 검토할 가설이나 질문을 입력한다. `확인 결과 입력`에는 운영자가 외부 endpoint, 수신자 제한처럼 Core에서 직접 볼 수 없는 사실을 입력한다. FastAPI는 둘 다 출처를 표시해 LangGraph의 같은 `thread_id`에 전달하고, 새 Core 사실과 함께 다시 판단하게 한다. `재실행 승인`과 `종료`는 Core decision API로 전달한다. FastAPI가 승인 여부를 자체 DB에 저장하거나 Kafka를 직접 발행하지 않는다.

Slack 사용자 ID allowlist와 Core의 service-to-service 인증을 모두 적용한다. Slack 알림 실패는 분석 결과를 지우지 않고 `slack_message_ts`/전송 상태를 기준으로 별도 재전송할 수 있게 한다.

## 6. 배포·설정·관측

### 설정 키

```text
Core
  TRIAGE_SERVICE_TOKEN
  TRIAGE_ANALYSIS_CLAIM_TTL_SECONDS

AI triage service
  CORE_INTERNAL_BASE_URL
  CORE_TRIAGE_SERVICE_TOKEN
  TRIAGE_POLL_SECONDS
  TRIAGE_ANALYSIS_CLAIM_TTL_SECONDS
  LANGGRAPH_CHECKPOINT_MYSQL_URL
  GROQ_API_KEY
  GROQ_BASE_URL
  GROQ_MODEL
  SLACK_WEBHOOK_URL
  SLACK_SIGNING_SECRET
  SLACK_ALLOWED_USER_IDS
```

- 실제 값은 `.env`/secret store로만 주입한다. Git에 넣지 않는다.
- Docker Compose에서는 `ai` profile로 두어 기존 FCFS 부하 테스트와 기본 개발 기동에 영향을 주지 않는다.

필수 지표와 로그:

- `marketing_triage_cases{status,category}` gauge 또는 집계
- claim/analysis/slack/approval 성공·실패 counter
- 분석 처리 시간 histogram
- 모든 로그에 `triageCaseId`, `executionId`, `dispatchId`, LangGraph run ID

LLM prompt, raw Kafka payload, 인증 정보, 사용자 개인정보는 로그/metric label에 넣지 않는다.

## 7. 테스트와 수락 기준

### Core

1. Coupon/Webhook 최종 DLT 하나당 triage case가 정확히 하나 생성된다.
2. 중복 DLT 소비는 같은 Dispatch의 case를 중복 생성하지 않는다.
3. 두 claim 요청 중 하나만 동일 case를 얻는다. 분석 점유 시간이 만료된 뒤에는 재claim할 수 있다.
4. analysis claim token이 틀리거나 만료되면 analysis 저장이 거절된다.
5. `INVALID_TARGET`와 전송 장애가 구조화된 category로 저장된다.
6. 승인 시 최신 Dispatch가 final failure일 때만 Dispatch sequence 하나와 Kafka publish 하나가 생성된다.
7. 승인 버튼 중복 클릭과 승인·종료 경합은 하나의 최종 decision만 남긴다.
8. 종료는 Kafka 재발행을 하지 않고 기존 이력을 보존한다.

### AI triage service

1. 결정적 `INVALID_TARGET`은 LLM 호출 없이 `NO_RETRY` 분석을 만든다.
2. agent는 선언된 read-only tool 외 호출할 수 없다.
3. 유효하지 않은 LLM JSON은 Core에 승인 가능 상태로 저장되지 않는다.
4. Slack 서명이 없거나 allowlist 밖 사용자의 action은 Core로 전달되지 않는다.
5. 같은 case의 Slack 알림은 중복 발송하지 않는다.
6. FastAPI 재시작 뒤에도 MySQL checkpointer의 같은 `triageCaseId` thread를 재개해 재분석 요청 사유를 반영한다.
7. mocked Core/LLM/Slack 통합 테스트로 claim -> analysis -> Slack -> approve/reanalyze/close 흐름을 검증한다.

## 8. 구현 순서

1. `MarketingActionExecution`/`MarketingActionDispatch` 분리와 migration을 먼저 완료한다.
2. Core에 `MarketingActionTriageCase`, DLT category, 내부 API, Core 단위·통합 테스트를 추가한다.
3. `ai-triage-service`의 claim, 고정 schema, Core read-only tool, Core 결과 저장을 구현한다.
4. Slack outbound 알림과 signing 검증 callback을 붙인다.
5. 승인/재분석/종료가 Core의 기존 retry 정책과 경합 없이 연결되는 테스트를 추가한다.
6. 마지막에 Compose `ai` profile, metric, runbook을 추가한다.

## 9. 명시적 제외

- 자동 재실행, 자동 쿠폰 재발급, 자동 Webhook 재전송
- LLM이 생성한 SQL/Elasticsearch query 실행
- FastAPI의 Core MySQL 업무 테이블·Kafka·Redis 직접 접근
- n8n, Discord, Jev, vector DB, RAG
- 인프라 증설/변경을 AI가 수행하는 Scale Advisor
- payment/PG/outbox 변경

이들은 v1의 운영 판단 흐름이 안정적으로 증명된 뒤 별도 계획으로 분리한다.
