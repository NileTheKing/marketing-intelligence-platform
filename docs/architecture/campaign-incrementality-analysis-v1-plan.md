# Campaign Incrementality Analysis v1

> Status: `active` (implementation plan)

## Goal

대량 쿠폰 캠페인에서 단순 발급 건수나 참여자 매출이 아니라, 쿠폰을 받은 집단과 받지 않은 대조 집단의 이후 구매를 같은 기간으로 비교한다. 마케터가 할인 비용을 감안해 캠페인이 추가 매출을 만들었는지 판단할 수 있게 한다.

참여 의지가 높은 고객이 쿠폰을 먼저 받았다는 사실만으로는 캠페인 효과를 증명할 수 없다. 따라서 대상자를 확정할 때 발송 대상과 holdout 대조군을 함께 저장한다.

## Scope

`COUPON` CampaignActivity의 CampaignActivityRun만 대상이다.

```text
AudienceSegment 대상 사용자
  -> CampaignActivityRunTarget snapshot
       -> TREATMENT: 기존 Execution -> Dispatch -> Kafka -> UserCoupon
       -> HOLDOUT: 발송하지 않음
  -> Run dispatch time 이후 Purchase SQL aggregate
       -> 7일 / 30일 증분 매출, 구매 고객 수, 주문 수
```

기존 `LTVBatch` 월별 코호트 보고서는 변경하지 않는다. 이 기능은 특정 발송 Run의 현재 성과를 조회하는 읽기 모델이다.

## Decisions

### Assignment

- `CampaignActivityRunTarget`은 `TREATMENT` 또는 `HOLDOUT`을 가진다.
- 각 대상자의 배정은 Run 생성 시 결정하고 변경하지 않는다.
- v1의 holdout 비율은 활동 설정값으로 받고, 유효 범위는 1~50%로 제한한다.
- 대조군은 쿠폰 메시지, Execution, Dispatch를 만들지 않는다.
- 기존 RunTarget은 migration에서 `TREATMENT`로 backfill한다.

### Coupon attribution

- `UserCoupon.marketingActionExecutionId`는 실제 발급 쿠폰의 발송 실행을 가리킨다.
- `Purchase.userCouponId`는 결제에서 사용된 발급 쿠폰을 가리킨다.
- 성과 조회는 대조군 비교를 위한 전체 이후 구매와, 쿠폰을 직접 사용한 구매를 구분해 반환한다.
- 발송군 배정 수와 실제 발급·사용 수가 다를 수 있으므로, 대조군 비교는 배정 기준으로 유지하고 쿠폰 사용 퍼널은 운영 지표로 별도 확인한다.

### Capacity and delivery

- `CampaignActivityRun.targetCount`는 전체 분석 대상 수를 뜻한다.
- 새 `treatmentCount`만 쿠폰 한도와 예산 예약에 사용한다.
- `controlCount`는 분석용 대조군 수다.
- 발송 시각을 `dispatchedAt`에 기록하고, 성과 집계의 시작 시점으로 쓴다.

### Metrics

Run 조회 API는 발송 후 선택한 관찰 기간(7일 또는 30일)에 대해 다음을 반환한다.

- 참여군과 대조군의 대상 수, 구매 고객 수, 주문 수, 총 매출
- 인당 매출, 구매 전환율
- 실제 쿠폰 발급 수, 사용 수, 쿠폰 사용 구매 건수와 매출
- `incrementalRevenue = (treatmentRevenuePerUser - holdoutRevenuePerUser) * treatmentCount`
- 정액 쿠폰의 발급 예상 비용과 `incrementalRevenue - predictedCouponCost`
- Run 생성 시 스냅샷한 RFM 세그먼트별 동일 지표

이는 무작위 대조군이 있다는 v1 가정 아래의 추정치다. 수수료, 상품 원가, 광고비는 현재 데이터 모델에 없으므로 이익이라고 부르지 않고 증분 매출과 쿠폰 비용까지만 반환한다.

### Query boundary and indexes

성과 API는 한 Run만 조회하며, `campaign_activity_run_targets`를 기준으로 `purchases`를 사용자와 발송 이후 기간으로 left join해 집계한다.

- Run target lookup: `(run_id, assignment_group, baseline_rfm_segment, user_id)`
- Purchase history: existing `(user_id, purchase_at)`

`Purchase.status = CONFIRMED`는 사용자별 구매 이력 lookup 뒤의 결과 필터다. `(user_id, purchase_at)` 복합 인덱스가 없으면 MySQL은 기간 내 `purchases` 전체를 스캔해 target과 hash join한다. 인덱스가 있으면 target 사용자의 기간별 구매 이력만 lookup한다.

100,000명 target의 local MySQL microbenchmark에서 100만 Purchase p95는 `757ms -> 539ms`, 500만 Purchase p95는 `1,708ms -> 605ms`였다. 단일 foreground write와 read를 겹친 측정에서는 write p95 악화가 관측되지 않았다. 여러 동시 구매 요청 간섭은 별도 측정이 필요하다. Set-based 변형과 커버링 인덱스는 이 기능에 적용하지 않는다.

## API

`GET /api/v1/dashboard/campaign-runs/{runId}/incrementality?windowDays=7`

- `ROLE_ADMIN` 전용
- `windowDays`는 7 또는 30
- Run이 아직 `DISPATCHED`가 아니면 409
- 반환값은 저장하지 않는다. 구매가 계속 들어오므로 조회 시점의 값이다.

## Acceptance criteria

1. Run 대상자는 참여군과 대조군 중 정확히 하나에만 배정된다.
2. 대조군에는 Execution, Dispatch, Kafka 메시지가 생성되지 않는다.
3. 쿠폰 한도와 예산은 참여군 수만큼만 예약한다.
4. 분석은 발송 시각 이전 구매를 포함하지 않는다.
5. 참여군, 대조군, 증분 매출 및 쿠폰 비용이 일관되게 계산된다.
6. 동일 Run의 7일, 30일 조회는 서로 다른 종료 시점으로 계산된다.
7. 100만, 500만 Purchase profile에서 인덱스 없는 실행계획과 인덱스 적용 후 실행계획, 반복 시간, 단일 foreground write overlap 결과를 artifact로 남긴다.

## Explicit exclusions

- 선착순 FCFS 구매 자체에 holdout을 적용하지 않는다.
- 자동으로 다음 캠페인을 집행하지 않는다.
- 광고비, 상품 원가, 수수료를 임의로 추정하지 않는다.
- 다변량 실험, 통계적 유의성 판정, tenant 분리는 v1 범위 밖이다.
