# Behavior Event Schema V1

> Status: active (implemented code, clean ES cutover required)

## Goal

Keep the existing `JS SDK -> Entry -> Kafka -> Elasticsearch` topology while preventing arbitrary SDK keys or inconsistent types from expanding the Elasticsearch mapping.

## Contract

- Queryable `properties`: `activityId`, `campaignId`, `productId`, `depth`, `durationSec`, `source`, `order`.
- Entry normalizes numeric values to `long`; malformed values for known fields return `400`.
- Unknown SDK metadata remains accepted under `attributes` and is mapped as Elasticsearch `flattened`.
- `MarketingRule.propertyConditions` can only use numeric `depth` and `durationSec`, which are the fields currently supported by the range-query implementation.

## Runtime Flow

```text
JS SDK -> Entry normalize -> Kafka axon.event.behavior -> Kafka Connect -> ES
                 |                         |
                 +-> properties            +-> properties: strict mapping
                 +-> attributes            +-> attributes: flattened
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
