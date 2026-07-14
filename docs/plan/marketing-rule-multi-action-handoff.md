# MarketingRule Multi-Action Implementation Handoff

Status: `active (implemented)` | Owner: implementation agent | Reviewer: Codex plan/review agent

> Implemented on 2026-07-14. The acceptance tests in this handoff pass, including
> synchronous and asynchronous Kafka-send failure cleanup. The VM schema still requires
> the one-time migration at `scripts/migrations/2026-07-12-drop-marketing-rule-reward-columns.sql`.

## Read Order

1. `CLAUDE.md`
2. `docs/architecture-map.md`
3. This file
4. Only for ambiguity: roadmap Main Upgrade 3-A/3-B

Code wins if documents conflict. Stop and report the conflict rather than choosing silently.

## Goal

One active behavior `MarketingRule` has independent actions:

```text
coupon only  -> COUPON command
webhook only -> WEBHOOK command
both         -> both commands, independently deduplicated
```

## Non-Goals

- No PUSH/EMAIL, admin UI, execution history, outbox, webhook worker, new topic, or scheduler lock.
- Do not move webhook HTTP I/O out of the current shared command flush path.
- Do not alter FCFS, payment-token, Purchase, UserSummary, or load-test behavior.
- Existing MarketingRule data and Redis-key compatibility are not required.

## Required Changes

1. Add `MarketingAction` child entity.

```text
MarketingAction: id, marketingRule(FK), actionType(COUPON|WEBHOOK),
                 referenceId, isActive, createdAt, updatedAt
UNIQUE(marketing_rule_id, action_type, reference_id)
```

Do not add execution order or failure policy: Kafka dispatch does not guarantee order and strategies own
their retry/DLT behavior.

2. Use a clean cutover.

- Remove `MarketingRule.rewardType/rewardReferenceId` from code.
- Add a one-time SQL script that drops the corresponding old DB columns.
- Scheduler reads active `MarketingAction` rows only; a rule without an active action is skipped.
- Do not add backfill, fallback, or old Redis-key handling.

3. Extend `CampaignActivityKafkaProducerDto` with optional fields:

```text
marketingRuleId, marketingActionId, actionReferenceId
```

New commands:

```text
COUPON:  type=COUPON,  actionReferenceId=couponId
WEBHOOK: type=WEBHOOK, actionReferenceId=webhookTemplateId
```

Keep `couponId` behavior for non-marketing coupon producers. Coupon strategy resolves:

```text
CouponStrategy:  actionReferenceId -> couponId -> productId
```

Webhook idempotency keys include `marketingActionId`.

4. Scheduler must fetch rules with actions without per-rule action N+1. For each matching
`(rule, user, product)`, process each active action separately.

```text
Redis: marketing:action-trigger:{actionId}:{userId}:{productId}
TTL:   rule.dedupTtlDays
```

Acquire with `SET NX EX` before Kafka publish. If the Kafka send future fails, delete only that action key.

Final idempotency remains:

```text
COUPON  -> UserCoupon(user_id, coupon_id) unique
WEBHOOK -> receiver Idempotency-Key
```

This is not an outbox guarantee. Do not add durable delivery/replay state.

## Required Tests

1. Coupon + webhook actions publish two commands with distinct action/reference IDs.
2. Re-run inside TTL publishes neither action.
3. Inactive action is skipped; another active action still publishes.
4. Rule without an active action is skipped.
5. Kafka send failure removes only the failed action key.
6. Coupon strategy accepts legacy and new commands.
7. Same webhook template with different action IDs yields different idempotency keys.

External HTTP tests with WireMock/MockWebServer are a separate task.

## Original Completion Report Format

Return: changed-file list with reason, migration assumptions, exact test commands/results, intentional
deviations, `git diff --stat`, and `git diff --check`. Do not commit/push or touch unrelated dirty files.
