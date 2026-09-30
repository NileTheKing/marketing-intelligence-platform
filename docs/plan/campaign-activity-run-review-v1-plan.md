# Campaign Activity Run Review v1

> Status: `active` (implementation plan)

## Goal

대상 조건과 쿠폰을 설정한 캠페인 활동을 실제 발송하기 전, 현재 대상자 수와 설정을 고정해 확인한다. 코드가 위험 신호를 찾거나 AI가 설정과 운영 의도의 어긋남을 표시한 경우에만 운영자의 승인을 받는다.

AI는 코드가 확인할 수 없는 의도와 설정의 어긋남만 표시한다. AI가 발송을 실행하거나 코드가 잡은 위험을 해제하지 않는다.

## Scope

v1은 `COUPON` 활동만 다룬다.

```text
Campaign
  -> CampaignActivity (쿠폰, 기간, 발급 한도, 예산, 대상 세그먼트)
    -> CampaignActivityRun (이번 대량 발송 한 번)
      -> CampaignActivityRunTarget (이번 실행에서 고정한 대상 사용자)
        -> MarketingActionExecution
          -> MarketingActionDispatch
            -> Kafka -> CouponStrategy -> UserCoupon
```

- `CampaignActivityRunTarget`은 대상 스냅샷과 연결된 `executionId`만 가진다. 발송 상태를 별도로 만들지 않는다.
- 최종 발송 상태, 재시도, DLT, 개별 실패 분석은 기존 `MarketingActionExecution`과 `MarketingActionDispatch`가 유일한 정본이다.
- 별도 Kafka producer, 별도 재시도 worker, 별도 DLT는 만들지 않는다.

## Registration

캠페인 활동 등록과 수정에서는 결정 가능한 설정만 막는다.

- 쿠폰 활동은 coupon이 반드시 있어야 한다.
- 시작 시각은 종료 시각보다 앞서야 한다.
- 발급 한도와 최대 대상 수는 양수여야 한다.
- 예산은 음수일 수 없다.

등록 시점에는 대상자를 조회하거나 AI를 호출하지 않는다. 대상과 쿠폰 상태는 시간이 지나면 바뀌므로, 실행 직전의 사실을 사용해야 한다.

`CampaignActivity`에 다음 실행 정책을 추가한다.

- `expectedRecipientCount`: 마케터가 기대한 대상자 수. 실제 대상 수가 이 값을 넘으면 코드가 `FLAG`를 남긴다. 임의 비율은 쓰지 않는다.
- `maxRecipientCount`: 이 수를 넘으면 코드가 실행을 차단한다.
- `purpose`: 운영자가 고르는 캠페인 목적. 예를 들어 VIP 보상, 이탈 고객 재유입, 일반 프로모션이다.
- `operatorMemo`: 대상과 할인 설정의 의도를 적는 짧은 운영 메모다.

새 쿠폰 활동 등록은 목적과 운영 메모가 없으면 거부한다. 이미 저장된 활동은 과거 데이터 호환을 위해 수정하지 않고, Run 준비 때 값이 없으면 `FLAG`로 검토한다.
- 기존 `limitCount`: 최대 쿠폰 발급 수.
- 기존 `budget`: 정액 할인 쿠폰일 때 `대상 수 x 할인 금액`과 비교한다.

## Run lifecycle

관리자는 `POST /api/v1/campaign-activities/{activityId}/runs`으로 실행 준비를 요청한다.

1. Core는 활동, 쿠폰, 캠페인 대상 세그먼트를 읽는다.
2. 현재 `UserSummary.rfmSegment`로 대상 사용자 ID를 조회해 `CampaignActivityRunTarget`에 저장한다. `(run_id, user_id)`는 유일하다.
3. 실제 대상 수, 쿠폰 ID와 할인 정보, 활동 기간, 한도와 예산을 `CampaignActivityRun`의 사실 스냅샷으로 저장한다.
4. 아래 하드 규칙에 걸리면 `BLOCKED`로 끝낸다. Kafka 메시지는 0건이다.
5. 통과하면 `PENDING_ANALYSIS`로 두고 AI review를 요청한다. 코드 위험 신호와 AI `FLAG` 중 하나라도 있으면 Slack 검토로 보낸다.
6. 코드 위험 신호가 없고 AI 결과가 `NO_FLAG`이면 Core가 같은 정책을 다시 확인한 뒤 기존 Kafka 발송 경로로 넘긴다.

### Hard rules

- 활동이 `ACTIVE`가 아니거나 현재 시각이 활동 기간 밖이다.
- 쿠폰이 없거나 현재 발급 가능 기간 밖이다.
- 실제 대상 수가 `maxRecipientCount`보다 크다.
- 기존에 준비되었거나 발송 중인 Run의 대상 수와 이번 대상 수 합계가 활동의 `limitCount`보다 크다.
- 정액 할인 쿠폰에서 기존 Run 예약분과 이번 대상의 합계 비용이 `budget`보다 크다.

### Code flags

하드 규칙은 명백한 위반이라 즉시 `BLOCKED`로 끝낸다. 아래는 설정상 유효하지만 운영자가 다시 봐야 하는 신호라 `codeFlags`에 남긴다.

- `expectedRecipientCount`가 없거나 실제 대상 수보다 작다.
- 목적이 VIP 보상인데 VIP 세그먼트가 아니거나, 이탈 고객 재유입인데 `AT_RISK` 또는 `DORMANT` 세그먼트가 아니다.
- 목적 또는 운영 메모가 비어 있다.

코드 위험 신호는 AI가 해제할 수 없다.

Run 준비와 승인 직전에는 `CampaignActivity` 행을 잠가, 동시에 두 Run이 같은 남은 발급 한도를 각각 예약하지 못하게 한다. `CLOSED`와 `BLOCKED` Run은 한도를 점유하지 않는다. 발송을 시작한 Run의 개별 실패분은 자동으로 보충하지 않으므로, v1은 안전하게 한도를 예약한 상태로 유지한다.

정률 할인 쿠폰은 주문 금액이 없어서 발급 전 정확한 비용을 계산할 수 없다. v1에서는 금액 예측 차단 대상에서 제외하고, AI 요약에 이 한계를 표시한다.

## AI review and Slack

기존 `ai-triage-service`의 FastAPI, LangGraph, MySQL checkpointer, Slack 서명 검증, 사람 피드백 재분석 흐름을 재사용한다. DLT triage case를 Run에 억지로 연결하지 않는다.

Core는 Run 전용 내부 API를 제공한다.

- 승인 대기 Run claim
- Run의 설정과 대상 스냅샷 조회
- 같은 활동의 최근 Run 요약 조회
- AI 분석 저장
- 운영자 승인 또는 종료

AI는 아래 정형 사실만 읽는다.

- 기대 대상 수와 실제 대상 수
- 발급 한도, 최대 대상 수, 예산과 정액 할인 예상 비용
- 쿠폰과 활동의 현재 기간
- 같은 활동의 최근 Run 결과
- 코드 위험 신호, 목적 선택값, 운영 메모

AI는 `NO_FLAG`, `FLAG` 중 하나와 근거 요약을 만든다. 숫자 계산이나 정책 위반 판단은 새로 하지 않고, 운영 메모의 의도와 대상 세그먼트, 쿠폰 설정이 어긋나는지만 본다. AI 호출 실패나 응답 형식 오류는 안전하게 `FLAG`로 처리한다.

Slack에서는 운영자가 다음 중 하나를 고른다.

- 승인: `FLAG` Run만 Core가 마지막 상태 확인 뒤 발송한다.
- 추가 확인 요청 또는 확인 결과 입력: 같은 LangGraph thread에서 사실을 다시 읽고 요약을 갱신한다.
- 종료: Run을 종료하고 Kafka 메시지를 만들지 않는다.

AI와 FastAPI는 Core MySQL 업무 테이블, Kafka, Redis에 직접 접근하지 않는다. 자동 발송을 포함한 Kafka 발행은 Core만 한다.

## Approval and dispatch

사람 승인 또는 `NO_FLAG` 자동 발송 직전에 Core는 다시 다음을 확인한다.

- 활동과 쿠폰이 여전히 활성 기간 안에 있다.
- 활동의 쿠폰, 한도, 예산, 대상 세그먼트 정책이 Run 스냅샷과 달라지지 않았다.
- 발급 한도와 예산 하드 규칙을 여전히 만족한다.

통과하면 대상마다 기존 `MarketingActionExecution -> MarketingActionDispatch`를 생성하고 기존 command topic으로 보낸다.

- 실행은 `CampaignActivity`에 연결된 `MarketingAction`을 사용한다. `MarketingAction`은 이 활동의 쿠폰과 1:1로 연결된다.
- `(campaign_activity_run_id, user_id)` 단위 unique constraint로 중복 승인이나 재시작이 같은 사용자를 두 번 발송하지 못하게 한다.
- Kafka callback, consumer retry, 최종 DLT는 기존 Dispatch 상태 전이를 그대로 쓴다.

승인 이후 개별 발송이 최종 실패하면 기존 DLT triage가 해당 Dispatch를 다룬다. 이 v1에서는 Run 전체 자동 중단이나 대량 실패율 경보를 넣지 않는다.

## Data model

### CampaignActivity additions

- `expected_recipient_count` nullable
- `max_recipient_count` nullable
- `purpose` nullable
- `operator_memo` nullable
- `marketing_action_id` nullable unique FK

기존 FCFS 활동은 이 필드를 사용하지 않는다. 대량 쿠폰 발송을 위해 준비된 활동만 `marketing_action_id`를 가진다.

### CampaignActivityRun

- `campaign_activity_id`
- `status`: `BLOCKED`, `PENDING_ANALYSIS`, `ANALYZING`, `AWAITING_APPROVAL`, `ANALYSIS_FAILED`, `DISPATCHING`, `DISPATCHED`, `CLOSED`
- 대상 수와 설정 사실 스냅샷 JSON
- AI 권고, 분석 요약, 운영자 피드백, Slack message timestamp
- 분석 claim token과 만료 시각
- 승인자와 시각

### CampaignActivityRunTarget

- `run_id`, `user_id` unique
- `execution_id` nullable unique

## Acceptance criteria

1. 대상 수가 한도보다 큰 `100장 / 120명` Run은 `BLOCKED`이며 Kafka 메시지가 없다.
2. 비활성 또는 만료 쿠폰 Run은 `BLOCKED`이며 Kafka 메시지가 없다.
3. 코드 위험 신호나 AI `FLAG` Run은 Slack 승인 전에는 Kafka 메시지가 없다.
4. 코드 위험 신호가 없고 AI `NO_FLAG` Run은 Core 재검증 뒤 기존 Kafka 메시지를 만든다.
5. 승인 전 활동 또는 쿠폰 설정이 바뀌면 발송하지 않고 Slack 검토로 되돌린다.
6. 중복 승인 요청은 사용자별 Execution, Dispatch, Kafka publish를 각각 한 번만 만든다.
7. 승인 뒤 개별 쿠폰 실패는 기존 command DLT와 Dispatch triage로 이동한다.
8. AI는 Run 사실 조회 API 외의 DB, Kafka, Redis 명령을 실행하지 않는다.

## Explicit exclusions

- 선착순 FCFS 판정 변경
- 자동 재발송
- 정률 쿠폰의 예상 총비용 계산
- Run 대량 실패율 기반 자동 pause
- RAG, vector DB, 임의 SQL 생성
