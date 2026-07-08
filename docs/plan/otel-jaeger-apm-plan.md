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
- `compose.diagnostic.yml`: enables Entry diagnostic profile and stage timing.
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

## Diagnostic Entry Stage Metrics

OTel auto-instrumentation did not split the successful Entry `200` path enough. Long root spans still had gaps between child Redis/Kafka spans.

Decision:

- Do not add inline timers to `EntryApplicationService`.
- Keep production default behavior unchanged.
- Add diagnostic-only AOP timing behind the `diagnostic` Spring profile.

Implementation:

- `entry-service/src/main/java/com/axon/entry_service/config/diagnostic/EntryDiagnosticTimingAspect.java`
- Active only when `SPRING_PROFILES_ACTIVE` includes `diagnostic`.
- Records Micrometer timer `axon.entry.diagnostic.stage`.
- Emits structured slow-stage logs only when a stage exceeds `axon.diagnostic.entry.slow-threshold-ms` (default `100ms`).

Stages:

- `entry_total`
- `meta_lookup`
- `token_generate`
- `token_lookup`
- `token_issue`
- `fast_validation`
- `heavy_validation`
- `redis_reserve`
- `backend_event_publish_async`

Suggested diagnostic run settings:

```bash
ENTRY_SPRING_PROFILES_ACTIVE=diagnostic \
AXON_DIAGNOSTIC_ENTRY_SLOW_THRESHOLD_MS=100 \
ENTRY_CPUS=1.5 \
docker compose \
  -f compose.app.yml \
  -f compose.resources.yml \
  -f compose.diagnostic.yml \
  up -d --build entry-service axon-nginx
```

Use Prometheus or actuator metrics to inspect:

```text
axon_entry_diagnostic_stage_seconds_count
axon_entry_diagnostic_stage_seconds_sum
axon_entry_diagnostic_stage_seconds_max
```

Guardrails:

- Treat this as a diagnostic profile, not the operating profile.
- Do not use diagnostic-profile latency as portfolio headline latency.
- After identifying the bottleneck, rerun headline measurements with `diagnostic` and OTel disabled.

### 2026-07-07 Diagnostic Pool A/B

Diagnostic-only run shape:

```text
Scenario: waiting_burst
Flow: reservation
Users: 3000
FCFS limit: 600
MAX_VUS: 600
Entry CPU: 1.5
PRELOAD_CAMPAIGN_META: true
Entry URL: http://127.0.0.1:28080
```

Important operational note:

- After recreating `axon-entry`, restart `axon-nginx`.
- Otherwise nginx can keep a stale Docker DNS resolution and send traffic to the old Entry container IP.
- Symptom: direct `127.0.0.1:8081` responds, but nginx returns `502` with `connect() failed (113: Host is unreachable) while connecting to upstream`.

Baseline pool 8 artifact:

```text
/home/ubuntu/apps/axon/artifacts/load-test/20260707-081646-diagnostic-pool8-valid
fcfs_success_count: 600
fcfs_error_count: 0
reservation p95: 5335.25ms
http_req_duration p95: 5.33s
Redis counter/users: 600/600
```

Diagnostic stage totals from Prometheus:

```text
entry_total OK: 600 calls, sum 1844.83s, max 6.91s
entry_total GONE: 2400 calls, sum 1482.53s, max 3.59s
meta_lookup: 3000 calls, sum 866.22s, max 1.32s
token_lookup: 3000 calls, sum 973.39s, max 2.29s
redis_reserve: 3000 calls, sum 457.50s, max 1.33s
token_issue: 600 calls, sum 438.33s, max 2.29s
backend_event_publish_async: 600 calls, sum 388.04s, max 1.99s
token_generate: 3000 calls, sum 0.57s, max 0.06s
```

Temporary Redis pool 64 artifact:

```text
/home/ubuntu/apps/axon/artifacts/load-test/20260707-081857-diagnostic-pool64-valid
fcfs_success_count: 600
fcfs_error_count: 0
reservation p95: 6792.50ms
http_req_duration p95: 6.72s
Redis counter/users: 600/600
```

Diagnostic stage totals from Prometheus:

```text
entry_total OK: 600 calls, sum 2060.99s, max 8.30s
entry_total GONE: 2400 calls, sum 1321.15s, max 6.01s
meta_lookup: 3000 calls, sum 819.51s, max 2.01s
token_lookup: 3000 calls, sum 1161.15s, max 2.72s
redis_reserve: 3000 calls, sum 359.00s, max 2.00s
token_issue: 600 calls, sum 573.88s, max 2.77s
backend_event_publish_async: 600 calls, sum 693.68s, max 2.29s
token_generate: 3000 calls, sum 0.44s, max 0.06s
```

Interpretation:

- Increasing Lettuce pool from `8` to `64` did not improve the end-to-end p95 in this shape.
- `redis_reserve` improved, but `token_lookup`, `token_issue`, and `backend_event_publish_async` worsened.
- Current evidence does not support "Redis connection pool size is the dominant bottleneck."
- The next stronger candidate is successful-path side work competing for Entry resources, especially backend approved-event Kafka publish running asynchronously during the burst.

Next focused test:

- Add a diagnostic-only way to disable backend `ReservationApprovedEvent` publishing, or isolate it with a bounded/tuned async executor.
- Compare the same shape with and without backend behavior event publish.
- If it improves, the fix should be designed as async executor isolation/backpressure or outbox/staging, not as blind Redis pool tuning.

### Backend Event Publish Toggle

For the next focused A/B, `BackendEventPublisher` supports a diagnostic-only toggle:

```text
axon.diagnostic.backend-event-publish-enabled=true
```

`compose.diagnostic.yml` exposes it as:

```text
AXON_DIAGNOSTIC_BACKEND_EVENT_PUBLISH_ENABLED
```

Use this only to isolate whether successful-path backend behavior-event publishing competes with Entry resources during the burst.

Example off run:

```bash
ENTRY_SPRING_PROFILES_ACTIVE=diagnostic \
AXON_DIAGNOSTIC_ENTRY_SLOW_THRESHOLD_MS=100 \
AXON_DIAGNOSTIC_BACKEND_EVENT_PUBLISH_ENABLED=false \
ENTRY_CPUS=1.5 \
docker compose \
  -f compose.app.yml \
  -f compose.resources.yml \
  -f compose.diagnostic.yml \
  up -d --build entry-service axon-nginx

docker restart axon-nginx
```

Interpretation guardrail:

- If the off run improves, do not remove analytics publishing as the final fix.
- Treat it as evidence to isolate or backpressure the async publisher, tune its executor, or move it behind a more durable staging/outbox path.
- Final performance measurements must run with the production behavior restored.

### 2026-07-07 Backend Event Publish Off Run

Artifact:

```text
/home/ubuntu/apps/axon/artifacts/load-test/20260707-083033-diagnostic-backend-publish-off
/home/ubuntu/apps/axon/artifacts/load-test/20260707-083638-backend-publish-ab
/home/ubuntu/apps/axon/artifacts/load-test/20260707-085112-diagnostic-backend-publish-off2
```

Run shape:

```text
Scenario: waiting_burst
Flow: reservation
Users: 3000
FCFS limit: 600
MAX_VUS: 600
Entry CPU: 1.5
PRELOAD_CAMPAIGN_META: true
AXON_DIAGNOSTIC_BACKEND_EVENT_PUBLISH_ENABLED=false
```

Result:

```text
fcfs_success_count: 600
fcfs_error_count: 0
reservation p95: 4433.05ms
http_req_duration p95: 4.43s
http_reqs: 162.08/s
Redis counter/users: 600/600
```

Diagnostic stage totals from Prometheus:

```text
entry_total OK: 600 calls, sum 1725.71s, max 4.38s
entry_total GONE: 2400 calls, sum 872.72s, max 2.53s
meta_lookup: 3000 calls, sum 729.87s, max 1.31s
token_lookup: 3000 calls, sum 748.85s, max 1.32s
redis_reserve: 3000 calls, sum 584.93s, max 1.31s
token_issue: 600 calls, sum 388.22s, max 1.31s
backend_event_publish_async: 600 calls, sum 0.01s, max 0.002s
token_generate: 3000 calls, sum 0.38s, max 0.06s
```

Comparison to pool 8 baseline:

```text
pool 8 baseline:
reservation p95 5335.25ms, http p95 5.33s, http_reqs 148.86/s
backend publish off:
reservation p95 4433.05ms, http p95 4.43s, http_reqs 162.08/s
```

Repeated A/B summary:

```text
on1:
success 600, error 0, reservation p95 5963.2ms, http p95 5901.877ms, http_reqs 158.623/s
backend_event_publish_async sum 573.361s

off1:
success 600, error 0, reservation p95 3291.05ms, http p95 3289.556ms, http_reqs 160.606/s
backend_event_publish_async sum 0.008s

on2:
success 51, error 2949, reservation p95 10002ms, http p95 10000ms, http_reqs 45.307/s
Redis counter/users 229/229

off2:
success 600, error 0, reservation p95 3963.05ms, http p95 3.96s, http_reqs 156.321/s
backend_event_publish_async sum 0.008s
```

Interpretation:

- Disabling backend approved-event publish improved repeated diagnostic runs.
- `on2` also reproduced a severe timeout/error run while the corresponding `off` runs remained stable.
- This supports the hypothesis that successful-path side work, especially async backend behavior-event publish, competes with Entry resources during the burst.
- It does not justify removing analytics publishing.
- The next production-oriented fix should isolate this async work from the hot path, e.g. a dedicated bounded executor, backpressure policy, or durable staging/outbox-like path.
- After these off runs, the VM was restored to `AXON_DIAGNOSTIC_BACKEND_EVENT_PUBLISH_ENABLED=true`.

### Backend Event Executor Isolation

Production-candidate change:

- `BackendEventPublisher` now uses `@Async("backendEventTaskExecutor")`.
- `backendEventTaskExecutor` is a dedicated `ThreadPoolTaskExecutor`.
- Default thread prefix: `backend-event-`.
- Default pool: `core=2`, `max=2`, `queue=1000`.

Configuration:

```text
AXON_BACKEND_EVENT_EXECUTOR_CORE_SIZE=2
AXON_BACKEND_EVENT_EXECUTOR_MAX_SIZE=2
AXON_BACKEND_EVENT_EXECUTOR_QUEUE_CAPACITY=1000
AXON_BACKEND_EVENT_EXECUTOR_THREAD_NAME_PREFIX=backend-event-
```

Why:

- The previous `@Async` path used the default async executor, making backend analytics publish hard to isolate in dumps and metrics.
- A dedicated executor makes thread dumps identifiable by `backend-event-*`.
- Spring Boot Actuator/Micrometer can expose executor metrics tagged by the bean name `backendEventTaskExecutor`.

Next validation:

- Re-run the same `3000/600/MAX_VUS=600` publish-on scenario.
- Compare against the prior publish-on baseline and publish-off runs.
- During a bad run, collect thread dumps and inspect whether `backend-event-*` threads are active, queued, or blocked around Kafka send/callback work.

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

Focused A/B script:

```bash
# PRELOAD_CAMPAIGN_META=false/true is controlled by the wrapper below.
REPEATS=3 \
NUM_USERS=3000 \
FCFS_LIMIT_COUNT=600 \
MAX_VUS=600 \
SCENARIO=waiting_burst \
FLOW=reservation \
K6_ENTRY_SERVICE_URL=http://127.0.0.1:28080 \
./scripts/load-test/run-cache-stampede-ab-compose.sh
```

Use this only when cache stampede needs an independent evidence table. The script keeps the FCFS scenario fixed and alternates `PRELOAD_CAMPAIGN_META=false` and `true`, then writes a summary under `artifacts/load-test/*-cache-stampede-ab/summary.md`.

Portfolio wording guardrail:

- Strong: Jaeger showed repeated Core metadata lookup on cache miss, and metadata preload removes that lookup from the burst path.
- Strong if the focused A/B confirms it: timeout/error tendency and p95 improved under the same resource profile.
- Weak unless re-run as focused A/B: exact percentage improvement caused only by cache stampede removal.

Focused A/B result from `20260707-040148-cache-stampede-ab`:

```text
Scenario: waiting_burst
Flow: reservation
Users: 3000
FCFS limit: 600
MAX_VUS: 600
Entry CPU: 1.5

PRELOAD_CAMPAIGN_META=false
run1: Core activity markers 70, success 600, error 0, reservation p95 170.05ms
run2: Core activity markers 60, success 600, error 0, reservation p95 297ms
avg reservation p95: 233.52ms

PRELOAD_CAMPAIGN_META=true
run1: Core activity markers 0, success 600, error 0, reservation p95 118ms
run2: Core activity markers 0, success 600, error 0, reservation p95 115ms
avg reservation p95: 116.50ms
```

Interpretation:

- At the current `600` VU diagnostic shape, cache stampede did not cause failures.
- It still produced `60~70` unnecessary Core metadata lookups during the cache-miss window.
- Metadata preload removed those lookups and reduced reservation p95 by roughly half in this run.
- This should be positioned as proactive hot-path risk removal for larger bursts, not as the main outage-level bottleneck fix.

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

follow-up ENTRY_CPUS=2.0 with PRELOAD_CAMPAIGN_META=true
run1: error 0, success 600, p95 4200ms, rps 185.7
```

Clean measurement was re-run after stopping non-Axon containers (`opicnic_*`) and Jaeger:

```text
clean baseline ENTRY_CPUS=0.6
run1: error 706,  success 71,  p95 10000ms, rps 79.6
run2: error 1078, success 70,  p95 10000ms, rps 77.6

clean improved ENTRY_CPUS=1.5
run1: error 0, success 600, p95 4390ms, rps 161.9
run2: error 0, success 600, p95 6480ms, rps 156.8
```

The clean runs keep the same conclusion:

- `ENTRY_CPUS=0.6` cannot reliably return responses before the k6 timeout even though Redis eventually reaches `600`.
- `ENTRY_CPUS=1.5` removes timeout-class errors and returns all `600` successful reservation responses.
- p95 still has run-to-run variance, so it should not be overclaimed as fully solved.
- The defensible headline is stability/error elimination and roughly doubled throughput, not perfect tail latency.

Prometheus samples supported the CPU diagnosis:

- Entry CPU hit `1.0` during tests.
- JVM live threads stayed low, around the 20s.
- This points to CPU quota / CPU contention rather than Tomcat thread exhaustion.
- With `ENTRY_CPUS=2.0`, throughput improved again, but p95 stayed around the `4s` range. This suggests `0.6 -> 1.5` fixed the dominant CPU quota bottleneck, while the remaining tail latency is likely in the successful `200` reservation path or same-VM load-generator contention.

### Current Conclusion

The main FCFS spike bottleneck after cache warm-up is not Redis Lua, Kafka publish, or Core metadata lookup. The remaining dominant bottleneck is Entry service CPU quota under burst load.

The result is strong enough for an engineering note:

> APM and nginx/k6 logs were compared to narrow the p95 delay. Cache stampede on campaign metadata was removed with warm-up, and repeated tests showed that increasing Entry CPU quota from `0.6` to `1.5` eliminated timeout-class errors and roughly doubled observed throughput.

### Remaining Work

- Re-run the `ENTRY_CPUS=1.5` comparison with `PRELOAD_CAMPAIGN_META=true` if a clean `success=600` table is needed.
- Inspect successful `200` traces with `{"http.response.status_code":"200"}` to reduce the remaining p95.
- Compare an external k6 run against the VM-local k6 run to separate application latency from same-VM load-generator contention.
- For headline portfolio numbers, keep OTel disabled and collect only k6/nginx/Prometheus summaries.

## Next Session Handoff

Status at compact time:

- Cache stampede A/B script exists at `scripts/load-test/run-cache-stampede-ab-compose.sh`.
- The VM also has the latest quiet version of that script copied manually.
- Java production code should not contain manual stage timers. A direct `EntryStageMetrics` attempt was reverted.
- `entry-service/gradlew compileJava` passed after reverting that manual instrumentation.

Important evidence:

```text
Cache stampede focused A/B:
artifact: /home/ubuntu/apps/axon/artifacts/load-test/20260707-040148-cache-stampede-ab
shape: 3000 users / FCFS 600 / MAX_VUS 600 / reservation / Entry CPU 1.5

PRELOAD=false:
Core activity markers: 70, 60
reservation p95 avg: 233.52ms
success: 600
error: 0

PRELOAD=true:
Core activity markers: 0, 0
reservation p95 avg: 116.50ms
success: 600
error: 0
```

Interpretation:

- Cache stampede was not the outage-level bottleneck at this scale.
- It is still valid as proactive hot-path risk removal because burst-time Core metadata lookup fell from `60~70` to `0`.
- Portfolio wording should say `hot-path external lookup removal`, not `system failure fixed`.

OTel run caution:

```text
OTel diagnostic run:
artifact: /home/ubuntu/apps/axon/artifacts/load-test/20260707-043055-otel-entry15-preload-200path
shape: Entry CPU 1.5 / PRELOAD=true / 3000 users / FCFS 600 / MAX_VUS 600
result: success 600, error 0, sold out 2400
http p95: 7885ms
```

- Do not use OTel-attached latency as performance evidence.
- The OTel run is useful only for trace shape.
- Jaeger showed successful `200` traces with these child spans:
  - `GET campaign:1:meta`
  - `GET RESERVATION_TOKEN:*`
  - `EVALSHA`
  - `SET RESERVATION_TOKEN:*`
  - `axon.event.behavior publish`
- In long `200` traces, child Redis/Kafka spans were small; the root span had large gaps between child spans.
- This does not prove the remaining bottleneck is any specific method. It means automatic instrumentation is insufficient to split the Entry internal path.

Rules for next session:

- Do not scp uncommitted Java production code to the VM for performance claims.
- If code changes are needed, use git commit/push/pull or an explicitly named diagnostic branch.
- Do not permanently pollute `EntryApplicationService` with inline timing wrappers.
- If stage-level timing is needed, prefer one of:
  - a diagnostic-only Spring profile,
  - an AOP/aspect around `EntryApplicationService.createEntry`,
  - Micrometer timers isolated behind a small instrumentation component and clearly documented as diagnostic,
  - or non-code evidence from existing Jaeger/Prometheus/nginx logs.
- Before claiming OTel overhead, run a controlled `OTel off -> OTel on -> OTel off` comparison under the same `ENTRY_CPUS`, `PRELOAD_CAMPAIGN_META`, and traffic shape.

Recommended next action:

1. Keep `ENTRY_CPUS=1.5` as the diagnostic stability profile, not as final operating resource.
2. Decide whether to build diagnostic-only stage metrics or stop at the current trace evidence.
3. If building stage metrics, implement it in a way that does not make `EntryApplicationService` harder to read.
4. After diagnosis, rerun headline measurements with OTel off.
