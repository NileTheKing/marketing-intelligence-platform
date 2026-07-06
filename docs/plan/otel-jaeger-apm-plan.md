# OTel + Jaeger APM Plan

Status: active

## Purpose

Use OpenTelemetry Java Agent and Jaeger to diagnose the Axon FCFS spike path.

The goal is not to adopt a specific APM product. The goal is to split request latency with evidence before changing code or infrastructure.

## Why Switch From Pinpoint

Pinpoint was tested first because it gives Java/Spring friendly call-tree views.

The Oracle VM currently used for Axon is ARM64. The official Pinpoint Docker stack pulled several `linux/amd64` images, and `pinpoint-hbase` restarted with HBase/ZooKeeper initialization failures. Pinpoint Web then failed with:

```text
KeeperErrorCode = NoNode for /hbase/hbaseid
```

This made the task drift from APM diagnosis into Pinpoint/HBase platform porting.

Decision:

- Stop the Pinpoint path on the ARM VM.
- Keep Pinpoint files as legacy/reference only.
- Use OpenTelemetry Java Agent + Jaeger for the current APM diagnosis.

## Architecture

```text
k6
  -> nginx / entry-service
      -> OTel Java Agent spans
      -> Jaeger OTLP endpoint
      -> Jaeger UI

core-service
  -> OTel Java Agent spans
  -> Jaeger OTLP endpoint
  -> Jaeger UI
```

Jaeger runs in the same Docker Compose network as Axon services.

Axon services export traces to:

```text
http://jaeger:4318
```

Jaeger UI is exposed on:

```text
http://127.0.0.1:16686
```

Override `JAEGER_UI_PORT` if the host port is already in use.

## Files

- `compose.otel.yml`: starts Jaeger and injects OTel Java Agent options into Entry/Core.
- `observability/otel-agent.env.example`: example environment values for trace runs.
- `scripts/observability/install-otel-agent.sh`: downloads `opentelemetry-javaagent.jar`.

## VM Setup

Install the OTel Java agent:

```bash
cd ~/apps/axon
./scripts/observability/install-otel-agent.sh
```

Prepare trace environment:

```bash
cp observability/otel-agent.env.example .env.otel

set -a
. ./.env.otel
set +a
```

Start Jaeger and traced Axon services:

```bash
docker compose \
  -f compose.app.yml \
  -f compose.resources.yml \
  -f compose.otel.yml \
  up -d --build jaeger core-service entry-service axon-nginx
```

Verify agent options:

```bash
docker logs --since=2m axon-entry 2>&1 | grep -E 'Picked up JAVA_TOOL_OPTIONS|opentelemetry|OpenTelemetry'
docker logs --since=2m axon-core 2>&1 | grep -E 'Picked up JAVA_TOOL_OPTIONS|opentelemetry|OpenTelemetry'
```

Verify Jaeger DNS from app containers:

```bash
docker exec axon-entry getent hosts jaeger
docker exec axon-core getent hosts jaeger
```

Verify Jaeger UI:

```bash
curl -I http://127.0.0.1:${JAEGER_UI_PORT:-16686}
```

## First Trace Test

Do not start with the 3,000 VU diagnostic burst.

Use a small run first:

```bash
SCENARIO=waiting_burst \
FLOW=reservation \
NUM_USERS=100 \
FCFS_LIMIT_COUNT=20 \
MAX_VUS=100 \
K6_ENTRY_SERVICE_URL=http://127.0.0.1:28080 \
./scripts/load-test/run-baseline-compose.sh 100 1
```

Expected:

- Jaeger UI shows `axon-entry`.
- If payment flow is used, Jaeger UI also shows `axon-core`.
- Trace contains HTTP server spans and downstream Redis/Kafka/HTTP/JDBC spans where the OTel agent can auto-instrument them.

## Diagnosis Boundary

OTel/Jaeger runs are diagnostic runs.

Do not compare OTel-attached latency directly against the headline baseline because the Java agent adds overhead.

Use OTel/Jaeger to decide what to fix. Then run the final before/after measurement without `compose.otel.yml`.

## Questions To Answer

- Is p95 dominated by k6 connection wait, nginx forwarding, Entry request handling, Redis Lua, token issuance, or Kafka publish?
- In payment flow, does latency move from Entry to Core consume/DB persistence?
- Are failed payment-path runs failing before successful confirmation, during Kafka publish, or during Core persistence?
- Is Redis actually slow under burst load, or only near a timeout boundary caused elsewhere?

## 2026-07-06 FCFS Entry CPU Diagnosis

### Test Shape

- Scenario: `waiting_burst`
- Flow: `reservation`
- Users: `3000`
- FCFS limit: `600`
- Entry URL: `http://127.0.0.1:28080`
- Warm-up: campaign meta cache preloaded before k6
- Primary metrics: k6 p95/error/RPS, Redis counter/users, Entry Prometheus CPU/thread/http metrics, Jaeger traces

The first warm-up attempt used `POST /entry/api/v1/entries`, which consumed one FCFS slot. This was useful for diagnosis but not clean enough for repeatable portfolio measurements.

Follow-up fix:

- `scripts/load-test/prepare-load-test-compose.sh` now supports `PRELOAD_CAMPAIGN_META=true`.
- This preloads `campaign:{activityId}:meta` directly from MySQL into Redis after prepare clears test keys.
- It avoids consuming a reservation slot during warm-up.

### Findings

Cache stampede was a real contributor:

- Without a prepared meta cache, Entry traces showed Core `GET /api/v1/campaign/activities/{id}` during the burst.
- After meta warm-up, Core GET disappeared from recent Entry traces.
- k6 p95 improved from roughly `8.5s` to the `3s` range in comparable diagnostic runs.

Redis Lua and Kafka were not the dominant bottlenecks:

- Sold-out `410` traces mostly contained `GET`, `GET`, and `EVALSHA`.
- Successful `200` traces contained `GET`, `GET`, `EVALSHA`, `SET`, and `axon.event.behavior publish`.
- Jaeger showed successful handler spans around `1.4s` in sampled traces, while k6/nginx still observed higher end-to-end latency.

Entry CPU quota was the dominant remaining bottleneck:

```text
baseline ENTRY_CPUS=0.6
run1: error 567, success 32,  p95 10000ms, rps 79.8
run2: error 494, success 105, p95 10000ms, rps 84.5

improved ENTRY_CPUS=1.5
run1: error 0, success 599, p95 4325ms, rps 162.2
run2: error 0, success 599, p95 4311ms, rps 167.8
```

Prometheus samples supported the CPU diagnosis:

- Entry CPU hit `1.0` during tests.
- JVM live threads stayed low, around the 20s.
- This points to CPU quota / CPU contention rather than Tomcat thread exhaustion.

### Current Conclusion

The main FCFS spike bottleneck after cache warm-up is not Redis Lua, Kafka publish, or Core metadata lookup. The remaining dominant bottleneck is Entry service CPU quota under burst load.

The result is strong enough for an engineering note:

> APM and nginx/k6 logs were compared to narrow the p95 delay. Cache stampede on campaign metadata was removed with warm-up, and repeated tests showed that increasing Entry CPU quota from `0.6` to `1.5` eliminated timeout-class errors and roughly doubled observed throughput.

### Remaining Work

- Re-run the CPU comparison with `PRELOAD_CAMPAIGN_META=true` so `fcfs_success_count` reaches `600` instead of `599`.
- Consider a follow-up `ENTRY_CPUS=2.0` run to see whether p95 drops below the `4s` range.
- For headline portfolio numbers, keep OTel disabled and collect only k6/nginx/Prometheus summaries.
