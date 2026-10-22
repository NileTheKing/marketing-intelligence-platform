# Dormant Customer Reengagement Query v1

**Status:** active

## Goal

Send an existing coupon or Webhook action to customers whose most recent confirmed purchase is older than a configured threshold. This is a scheduled CRM campaign, not a behavior-event trigger.

## Query Boundary

```
user_summary where last_purchase_at < cutoff
  and no execution exists for this reengagement campaign
  -> existing Kafka coupon or Webhook delivery pipeline
```

- `DormantCustomerCampaign` owns the business configuration: action, dormant-day threshold, active flag.
  Activity registration and lifecycle integration is tracked in the Activity Settings Integration section of
  `docs/architecture/campaign-activity-run-review-v1-plan.md`; independent configuration alone is not the intended runtime activation boundary.
- `MarketingActionExecution` stores `reengagement_campaign_id` only for this scheduled campaign type.
- The unique key `(reengagement_campaign_id, user_id)` is the durable duplicate-delivery boundary. Normal behavior executions keep the campaign ID null.
- The scheduler lock remains an optimization for one runner. The unique key is the final correctness boundary.

## Index Decision

- Add `user_summary(last_purchase_at, user_id)` because the job begins from a last-purchase cutoff.
- Existing `marketing_action_executions(marketing_action_id, user_id, product_id)` is not used for this campaign. Add `(reengagement_campaign_id, user_id)` through the unique key so the anti join can probe delivery history.
- Measure both read and write behavior before retaining the new `user_summary` index. The write test must update `last_purchase_at`, the same field the purchase projection advances.

## Verification

1. Repository test: only users before the cutoff and without an execution are selected.
2. Scheduler test: creates one execution/dispatch per target and skips an already-created execution.
3. Database constraint test: duplicate campaign/user execution is rejected.
4. Testcontainers MySQL benchmark: 1,000,000 summaries, a selective dormant cohort, repeated `EXPLAIN ANALYZE` and query timings, plus repeated `last_purchase_at` update timings with and without the index.

## Local MySQL Measurement (2026-09-27)

- Dataset: 1,000,000 `user_summary` rows, 50,000 dormant candidates, and 10,000 already-executed campaign targets. Each read returns the oldest 500 eligible users.
- Read lookup, five runs: median `183ms -> 7ms` with `user_summary(last_purchase_at, user_id)`.
- `EXPLAIN ANALYZE`: without the index, MySQL scans 1,000,000 summaries, filters 50,000 rows, then sorts before applying the 500-row limit. With the index, it uses a covering `last_purchase_at` range scan and preserves the required order. The execution-history anti join uses the campaign/user unique key in both plans.
- Purchase projection write proxy, five runs of 1,000 single-row `last_purchase_at` updates: median `3,820ms -> 3,789ms`. The difference is measurement noise, so this does not prove the index makes writes faster or costs nothing.
- The benchmark is a local query-shape measurement. It excludes Kafka, delivery consumers, and HTTP latency.

## Out Of Scope

- A generic audience DSL, automatic retry of failed delivery, UI, and payment changes.
- Treating the local microbenchmark as end-to-end capacity evidence.
