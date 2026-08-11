# Behavior Event Schema V1

> Status: active (implemented code, clean ES cutover required)

## Goal

Keep the existing `JS SDK -> Entry -> Kafka -> Elasticsearch` topology while preventing arbitrary SDK keys or inconsistent types from expanding the Elasticsearch mapping.

## Contract

- Request body의 `userId`는 받지 않는다. 로그인 사용자의 `userId`는 검증된 JWT principal에서만 결정한다.
- 익명 행동은 숫자 사용자로 승격하지 않고 `userId=null`로 발행한다. 대신 탭 단위로 생성한 `sessionId`가 필수다.
- Queryable `properties`: `activityId`, `campaignId`, `productId`, `depth`, `durationSec`, `source`, `order`.
- Entry normalizes numeric values to `long`; malformed values for known fields return `400`.
- Unknown SDK metadata remains accepted under `attributes` and is mapped as Elasticsearch `flattened`.
- `MarketingRule.propertyConditions` can only use numeric `depth` and `durationSec`, which are the fields currently supported by the range-query implementation.

## Runtime Flow

```text
JS SDK -> Entry identity/normalize -> Kafka axon.event.behavior -> Kafka Connect -> ES
                 |                              |
                 +-> JWT user or anon session   +-> userId/sessionId
                 +-> properties                 +-> properties: strict mapping
                 +-> attributes                 +-> attributes: flattened
```

The schema does not add a new consumer, topic, or synchronous ES/DB call to the Entry request path.

## ES Cutover

`compose.analytics.yml` starts `elasticsearch-init`, which installs the `axon.event.behavior` index template before Kafka Connect starts. Elasticsearch templates only affect newly created indices.

For a clean development cutover, stop Kafka Connect, delete the existing `axon.event.behavior` index, start the analytics compose stack, then resume Kafka Connect. This intentionally does not replay old behavior history. Production reindex/replay is out of scope for V1.

## Verification

1. Send `depth: 75` and an unknown SDK key; verify `properties.depth` is numeric and the unknown key is under `attributes`.
2. Send `depth: "not-a-number"`; verify Entry returns `400`.
3. Verify `GET /axon.event.behavior/_mapping` shows `properties.dynamic: strict` and `attributes: flattened`.
4. Persist a MarketingRule with `depth` or `durationSec`; reject an unknown or nonnumeric property condition.
5. Send an anonymous event containing another user's body `userId`; verify the stored event has `userId=null` and keeps only `sessionId`.
6. Send the same event with a valid JWT; verify `userId` comes from the JWT regardless of the request body.
