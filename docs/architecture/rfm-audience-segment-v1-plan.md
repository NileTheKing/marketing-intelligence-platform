# RFM Audience Segment V1 Plan

Status: `active (implemented)`

## Goal

Repair RFM so it produces real user segments from purchase data, then let an active
`MarketingRule` optionally limit its behavior-event candidates to one RFM audience.

```text
Behavior candidate from Elasticsearch
  -> optional AudienceSegment (RFM only) filter
  -> existing MarketingAction coupon/webhook command
```

This is a narrow marketing-targeting vertical slice. It is not a generic CDP audience
builder or FCFS eligibility feature.

## Current Defect

`RfmSegmentationScheduler` reads `PURCHASE_COUNT` and `TOTAL_REVENUE`, while the only
current `UserMetric` batch writes `USER_PURCHASE_COUNT`; no `TOTAL_REVENUE` writer was
found. The scheduler can therefore classify users with zero frequency/monetary values.

## Scope

### 1. Make RFM use purchase facts directly

- Add one bulk aggregate query for a page of user IDs:
  `userId`, `COUNT(purchase)`, `SUM(price * quantity)`.
- Keep Recency from `UserSummary.lastPurchaseAt`, which is updated by the purchase path.
- Change `RfmSegmentationScheduler` to issue one aggregate query per `UserSummary` page,
  not two `UserMetric` queries per user.
- Use `BigDecimal` for Monetary in the RFM service; purchase prices are decimal amounts.
- Keep the existing policy unless explicitly changed:
  - `VIP`: recency <= 30 days, frequency >= 3, monetary >= 100,000
  - `LOYAL`: recency <= 60 days, frequency >= 2
  - `AT_RISK`: recency > 60 days, frequency >= 1
  - `DORMANT`: all remaining users
- Do not delete `UserMetric` or its Spring Batch job in this change. It is simply no
  longer an RFM input until a separate metric-contract decision is made.

### 2. Add a constrained AudienceSegment model

Create `AudienceSegment` in the marketing domain:

```text
id, name (unique), targetRfmSegment (required), isActive, createdAt, updatedAt
```

- No JSON condition DSL, AND/OR nesting, region/gender/consent fields, or membership
  table in V1.
- Add an optional `MarketingRule -> AudienceSegment` relation.
- A rule without a segment keeps current behavior.
- A rule referencing an inactive segment produces no actions. This must fail closed;
  disabling a segment must never broaden a campaign audience.
- Add an additive migration for the new table and nullable foreign key. Do not alter
  existing MarketingAction or Redis dedup schemas.

### 3. Filter behavior candidates once per rule

In `BehaviorTriggerScheduler.processRule()`:

1. Query Elasticsearch as today to obtain behavior-qualified user IDs.
2. If the rule has no audience segment, keep all candidates.
3. If it has an active audience segment, bulk-query `UserSummary` for the candidate IDs
   whose `rfmSegment` equals `targetRfmSegment`.
4. Only the filtered IDs enter the existing action loop and Redis/Kafka dedup flow.

The filter is evaluated when the hourly MarketingRule scheduler runs. It must not be
called from the FCFS HTTP hot path.

## Explicit Non-Goals

- No `AudienceSegmentMembership` pre-computation.
- No user profile schema for region, gender, age, or marketing consent.
- No generic audience criteria engine or arbitrary JSON predicates.
- No Campaign/FCFS entry eligibility restriction.
- No new marketing admin UI or CRUD API; current MarketingRule itself has no management
  API, so segment/rule data remains managed by the existing data setup path for V1.
- No change to MarketingAction, coupon execution, webhook retry/DLT, or scheduler lock.

## Tests

1. RFM service boundary tests use decimal monetary values and preserve the four policy
   boundaries above.
2. Scheduler integration/service test proves a purchase aggregate produces each expected
   RFM segment and persists it to `UserSummary`.
3. Rule without `AudienceSegment` still publishes actions for every ES candidate.
4. Rule with `AT_RISK` segment publishes only for matching candidates; the user-summary
   lookup is bulk, not per user.
5. Inactive segment publishes no actions.
6. Existing action-level Redis dedup and Kafka send-failure cleanup tests remain green.

## Documentation After Implementation

- Update `T5_RFM_세그멘테이션.md` with the actual policy and state that RFM is a
  persisted audience input, not autonomous campaign execution.
- Update `T6_행동기반트리거캠페인.md` to show optional RFM audience filtering before
  MarketingAction execution.
- Update the relevant portfolio behavior-data section only after tests prove the
  end-to-end filtered command path.

## Completion Criteria

For one active rule with `AudienceSegment(AT_RISK)`, behavior candidates containing both
`AT_RISK` and non-`AT_RISK` users result in Kafka commands only for the `AT_RISK` users.
The same setup without an audience segment preserves the existing behavior.
