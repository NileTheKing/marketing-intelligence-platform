# Spike Traffic Observability Plan

## Purpose

This plan defines a reproducible spike-traffic test environment for Axon.

The goal is not to compare absolute numbers with the previous KT Cloud K2P Kubernetes test. The goal is to observe where latency is spent in the backend request path and to improve the system using measured evidence.

Primary questions:

- Which API becomes slow first during a spike?
- Is the latency from application code, DB connection acquisition, SQL execution, Redis command latency, Kafka buffering, or batch flush delay?
- Which changes improve the same scenario on the same Oracle VM environment?

## Scope

Target environment:

- Oracle Free Tier VM
- Docker Compose based deployment
- Nginx front proxy
- Entry-service and Core-service with multiple containers where needed
- Kafka single broker
- Redis single instance
- MySQL single instance
- Elasticsearch and Kafka Connect when full-stack behavior-log flow is needed
- Pinpoint for request tracing
- k6 for load generation

This environment is for backend bottleneck analysis. It is separate from the previous KT Cloud K2P Kubernetes environment, which was used to validate Kubernetes deployment structure, Ingress routing, Service discovery, resource limits, and HPA configuration.

## Non-Goals

This plan does not verify:

- Kafka broker high availability
- Kafka `replication-factor=3` failover
- Kafka `min.insync.replicas=2` behavior under broker failure
- Redis Sentinel failover
- Redis Cluster sharding
- Kubernetes HPA behavior
- Kubernetes rolling update behavior
- Multi-node scheduling behavior

These are valid future hardening topics, but they are not required for the current backend bottleneck analysis.

## Environment Strategy

### Measurement Baseline Mode

Measurement baseline mode runs the core purchase path with fixed resource limits and without tracing agents.

Official measurement traffic should be generated from an external client machine.

Current official route:

```text
Mac k6
  -> Cloudflare
  -> VM host Nginx
  -> axon-nginx container
  -> entry-service/core-service
```

Same-VM k6 is allowed only for debugging because it shares CPU, network, and Docker resources with the system under test.

Components:

- Nginx
- Entry-service containers
- Core-service containers
- Kafka
- Redis
- MySQL

Purpose:

- Produce the official before/after performance number.
- Keep measurement overhead stable and low.
- Verify FCFS reservation, Kafka command flow, Core consumer processing, DB persistence, Redis counter/set, and domain consistency.

Compose files:

```bash
docker compose -f compose.app.yml -f compose.resources.yml up -d --build
```

Do not attach Pinpoint to the official baseline/final comparison run.

### Diagnostic Mode

Diagnostic mode attaches request tracing after a clean baseline exists.

Components:

- Nginx
- Entry-service containers
- Core-service containers
- Kafka
- Redis
- MySQL
- Pinpoint

Optional exclusions:

- Elasticsearch
- Kafka Connect
- Kibana
- AI/RAG related services
- Dashboard batch jobs not required for the purchase path

Purpose:

- Focus on FCFS entry, Redis Lua reservation, Kafka command flow, Core consumer processing, CampaignActivityEntry persistence, Purchase buffering, batch flush, DLQ, and reconciliation-related risks.
- Identify which API or internal method is slow.
- Separate controller/service/repository time, DB connection wait, SQL execution, Redis calls, Kafka publish/consume, and scheduled flush delay.
- Guide one concrete code or configuration change.

Pinpoint-attached latency may be used as diagnostic evidence, but it is not the headline before/after performance number because the agent adds tracing overhead.

### Final Measurement Mode

Final measurement mode repeats the measurement baseline after the fix.

Rules:

- Use the same Oracle VM.
- Use the same Compose files: `compose.app.yml + compose.resources.yml`.
- Use the same k6 scenario, users, `MAX_VUS`, `FCFS_LIMIT_COUNT`, and seed assumptions.
- Keep Pinpoint detached.
- Compare only against the matching measurement baseline.

### Full Pipeline / k3s Mode

Full pipeline or k3s mode is a separate experiment.

Use it for:

- Elasticsearch and Kafka Connect behavior-log flow
- dashboard freshness and analytics-path cost
- Kubernetes/k3s rolling update behavior
- controlled scaling automation
- Kafka/Redis/container failure scenarios

Do not compare Docker Compose measurement numbers directly with k3s or full-pipeline numbers.

## Service Topology

Docker Compose service discovery is acceptable for this experiment.

Example topology:

```text
Client / k6
  -> Nginx
      /entry -> entry-service containers
      /      -> core-service containers

entry-service
  -> Redis
  -> Kafka
  -> core-service when synchronous validation is needed

core-service
  -> MySQL
  -> Redis
  -> Kafka
  -> Elasticsearch when behavior-log flow is enabled
```

Nginx can provide path routing and upstream load balancing in Docker Compose. Kubernetes Service and CoreDNS are not required for this experiment because the current goal is not orchestration behavior.

## Kafka Configuration

Recommended test configuration:

```text
brokers = 1
partitions = 3
replication-factor = 1
min.insync.replicas = 1
acks = all
enable.idempotence = true
```

This verifies:

- Entry to Kafka to Core message flow
- Partition-based consumer processing
- Consumer group behavior
- DLQ routing
- At-least-once duplicate defense at the application/database layer

This does not verify Kafka HA.

Production-oriented design can still be documented separately:

```text
brokers = 3
replication-factor = 3
min.insync.replicas = 2
acks = all
```

The portfolio wording must distinguish between "designed for multi-broker operation" and "tested on a single-broker Oracle VM reproduction environment."

## Redis Configuration

Recommended test configuration:

```text
redis instances = 1
persistence = off for latency-focused tests
maxmemory = 512MiB to 1GiB initially
```

Rationale:

- The current test focuses on Redis Lua atomic reservation, duplicate participation prevention, trigger deduplication, cache latency, and cache stampede behavior.
- Redis Sentinel and Redis Cluster add failover/sharding concerns that are outside the current scope.
- Redis Cluster would require hash-tag-aware key design for multi-key Lua scripts. Current keys such as `campaign:%d:users` and `campaign:%d:counter` are safe on a single Redis instance, but Redis Cluster would require keys like `campaign:{%d}:users` and `campaign:{%d}:counter` to keep them in the same hash slot.

If testing operational durability later:

- Evaluate AOF with `appendfsync everysec`.
- Evaluate Redis Sentinel failover separately.
- Evaluate Redis Cluster only after key hash-tag migration is planned.

## Observability Targets

Pinpoint should answer:

- Which API is slow under spike load?
- How much time is spent in controller/service/repository layers?
- How much time is spent waiting for DB connections?
- How much time is spent executing SQL?
- How long do Redis commands take?
- Are Kafka consumers or scheduled batch flushes delayed?

Additional metrics to collect if available:

- k6 p50/p90/p95/p99 latency
- k6 error rate
- Hikari active/idle/pending connections
- JVM heap usage
- GC pauses
- Redis command latency
- Kafka consumer lag
- MySQL slow query log

## Test Scenarios

### Scenario 1. FCFS Spike

Purpose:

- Validate the entry path under sudden traffic.
- Observe Redis Lua latency and Kafka handoff behavior.

Flow:

```text
k6 users
  -> Entry reservation API
  -> Redis Lua SADD/INCR/rollback
  -> Kafka command publish
  -> Core consumer
  -> CampaignActivityEntry/Purchase processing
```

Key checks:

- Reservation success/sold-out/duplicate ratios
- Redis command latency
- Entry API latency
- Kafka producer latency if visible
- Core consumer processing delay
- Purchase batch flush behavior

### Scenario 2. Purchase Pipeline Isolation

Purpose:

- Validate that Kafka receiving and DB persistence are separated.
- Observe whether Purchase batch flush or DB connection acquisition becomes the bottleneck.

Key checks:

- Core consumer trace
- `CampaignActivityConsumerService` processing time
- `PurchaseHandler` buffer and flush timing
- Hikari pending connection count
- SQL execution time
- DLQ behavior under injected duplicate/failure input

### Scenario 3. Cache Stampede Risk

Purpose:

- Reproduce a cache TTL boundary under spike traffic.
- Verify whether many requests hit DB at the same time after cache expiration.

Candidate targets:

- Campaign activity metadata cache
- User validation/cache data
- Behavior trigger deduplication keys if applicable

Candidate mitigations:

- TTL jitter
- Cache warm-up before event start
- Refresh when TTL is below a threshold
- Cache miss lock so only one request repopulates the value

### Scenario 4. Behavior Log Full-Stack Flow

Purpose:

- Verify the full behavior event pipeline when Elasticsearch and Kafka Connect are enabled.

Flow:

```text
JS/behavior event
  -> Kafka behavior topic
  -> Kafka Connect
  -> Elasticsearch
```

Key checks:

- Kafka Connect lag
- Elasticsearch indexing latency
- Whether ES/Kafka Connect affects the core purchase path on the same VM

## Experiment Method

Each test should follow the same loop:

1. Start from a known compose profile and resource profile.
2. Reset or seed the same dataset.
3. Run the same k6 scenario.
4. Capture k6 summary, domain check, and container stats.
5. Attach Pinpoint in a separate diagnostic run if the baseline exposes a bottleneck.
6. Identify one bottleneck only.
7. Apply one change.
8. Run the same measurement scenario again without Pinpoint.
9. Compare only results from the same Oracle VM environment and same resource profile.

Do not compare Oracle results directly against the previous K2P results. K2P and Oracle differ in CPU model, memory, disk I/O, network path, Kubernetes overhead, and deployed component shape.

## 2026-06-12 Baseline Debug Notes

Status: diagnostic notes, not final portfolio numbers.

Observed facts:

- The old KT Cloud K2P Kubernetes result and the new Oracle Docker Compose result have different deployment shape, log pipeline, resource profile, and load-generator path.
- High-frequency request-path logs were observed during FCFS load tests.
- The JWT authentication success log and reservation-token Redis-miss log were normal-path logs but emitted at `INFO`/`WARN` frequency.
- These logs can create Docker stdout / Logback appender lock contention under spike traffic.
- The normal-path logs were lowered to `DEBUG`; invalid or tampered reservation tokens still remain `WARN`.
- Same-VM diagnostic k6 after log reduction produced a clean reservation path result at low concurrency: `fcfs_success_count=200`, `fcfs_error_count=0`.
- External Mac k6 against the public route still showed intermittent timeout in one run: `fcfs_success_count=199`, `fcfs_error_count=6`.

Interpretation boundary:

- Do not claim "Virtual Thread pinning was fixed" from this evidence alone.
- Safe claim: high-frequency synchronous application logging was identified as a bottleneck candidate and reduced on the hot request path.
- The remaining timeout must be diagnosed separately before claiming a clean Oracle Compose baseline.

Clean baseline requirement:

- `fcfs_success_count == FCFS_LIMIT_COUNT`
- `fcfs_error_count == 0`
- no interrupted iterations
- fresh seed data and fresh token file for every official run
- external Mac k6 route for headline before/after numbers

## 2026-07-01 Waiting Burst Baseline Notes

Status: active diagnostic baseline, not final portfolio numbers.

Scenario model:

- `SCENARIO=waiting_burst`
- 3,000 users are assumed to be waiting before event open.
- Each user attempts once within a short reaction-time window.
- The test is intended to stress the event-open burst path, not steady arrival traffic.
- Same-VM k6 is used only for fast diagnosis because it shares CPU, network stack, and Docker resources with the system under test.

Current VM diagnostic route:

```text
k6 container on Oracle VM
  -> host network
  -> http://127.0.0.1:28080
  -> axon-nginx container
  -> entry-service / core-service
  -> Redis / Kafka / MySQL
```

Current command shape:

```bash
cd ~/apps/axon
SCENARIO=waiting_burst FLOW=payment NUM_USERS=3000 FCFS_LIMIT_COUNT=600 MAX_VUS=3000 K6_ENTRY_SERVICE_URL=http://127.0.0.1:28080 ./scripts/load-test/run-baseline-compose.sh 3000 1
```

Observed pattern on the same VM:

| Waiting users | FCFS limit | Result | Domain consistency | Notable signal |
|---:|---:|---|---|---|
| 3,000 | 400 | stable success in latest repeat | Redis/DB `400/400` | `reservation_duration` p95 stayed below the 5s threshold in the latest run |
| 3,000 | 500 | consistency generally succeeds, latency reaches the threshold boundary | Redis/DB matched in the observed run | `reservation_duration` p95 was around the 5s threshold |
| 3,000 | 600 | unstable boundary; repeated success/failure variance observed | failure runs showed Redis `600/600` but DB around `588~590/600` | `EOF`, nginx `500`, and high `http_req_blocked` / `http_req_connecting` p95 |

Interpretation:

- Redis Lua reservation still accepts exactly the configured limit.
- The unstable area is not the Redis counter itself.
- Failure runs show that some Redis-success users do not complete the payment / Kafka / Core persistence path.
- High `http_req_blocked` and `http_req_connecting` p95 indicate connection acquisition or TCP connection establishment pressure before focusing only on application method latency.
- Same-VM k6 may amplify variance because k6, nginx, Entry, Core, MySQL, Redis, Kafka, and Docker share the same 4 vCPU host.

Current working baseline classification:

- `3000/400`: stable success baseline.
- `3000/500`: latency boundary candidate.
- `3000/600`: stress/failure reproduction candidate.

Next diagnostic evidence to collect before claiming a root cause:

- Nginx error log during the failing window.
- Entry-service logs during the failing window.
- `docker stats` during the burst, not only after completion.
- `ss -s` before and after the burst.
- Pinpoint / Actuator metrics for Entry and Core in a separate diagnostic run.
- Hikari active/idle/pending connections and DB connection wait if available.

2026-07-02 diagnostic update:

Status: active diagnostic notes, not final portfolio numbers.

The same waiting-burst model was repeated across three request paths to avoid over-attributing the failure to one layer.

| Path | Flow | Scenario | Observed result | Interpretation boundary |
|---|---|---|---|---|
| VM k6 -> nginx `28080` -> Entry | reservation | `3000/600` | After nginx connection changes, Redis reached `600/600` and one best run had `fcfs_error_count=6`; p95 was still high. | This suggests the front path affects the symptom, but does not prove nginx is the root cause. |
| VM k6 -> host published port `8081` | payment | `3000/500` | Large variance: one run persisted only `21/500`, later repeats reached `498/500` and `500/500`. | The host/Docker published-port path showed variance under burst load; one run is not enough evidence. |
| VM k6 -> Docker bridge `entry-service:8081` | payment | `3000/500` | Three repeats reached DB `500/500`; p95 still ranged roughly from 5s to 8s. | The application can complete the 500 payment path on this VM, but latency still needs breakdown. |

Current nginx findings:

- Nginx `499` means the client closed the request before nginx returned a response. In these runs, that usually means k6 timed out or disconnected while nginx was still waiting on the upstream path.
- Earlier `8192 + multi_accept` admitted too much burst traffic at once and made the downstream path worse.
- The current nginx setting is intentionally more conservative: `worker_connections=2048`, no `multi_accept`, and timing fields in access logs.
- Upstream keepalive was tried and reverted because it produced Tomcat `400 Bad Request` responses in this environment.

Current interpretation:

- Redis Lua still reaches the configured reservation limit when the request is accepted.
- In the observed runs, DB persistence roughly followed the number of successful payment confirmations. This does not prove Core is fault-free; it only means many failed runs did not even reach successful confirmation.
- The unstable area is before or around Entry response completion: k6 connection wait, nginx forwarding, Docker networking, Entry request handling, Redis Lua, token issuance, and Kafka publish still need to be separated.
- High run-to-run variance means the current evidence is not strong enough to claim one exact root cause.

APM transition:

- Move to Pinpoint/metrics diagnostic mode before making further code-level performance claims.
- Trace Entry reservation, Redis Lua call, reservation token handling, Kafka publish, payment prepare/confirm, Core consumer processing, and DB persistence.
- Continue collecting nginx timing logs, `ss -s`, and short `docker stats` snapshots around the burst window.
- Keep same-VM k6 results as diagnostic evidence only. Official before/after numbers should still come from a stable, repeated scenario with the same route and resource profile.

Portfolio boundary:

- Safe claim: a waiting-burst reproduction showed a stable success range and an unstable boundary where connection wait, nginx errors, and asynchronous DB convergence issues appear.
- Avoid claiming a single exact capacity number from one run.
- Avoid claiming Redis Lua is the bottleneck without Redis command latency evidence.
- Avoid using same-VM k6 numbers as the headline external before/after result.

## Candidate Improvements

Backend changes:

- Tune Hikari pool size and connection timeout based on measured pending connections.
- Add cache warm-up for event-start metadata.
- Add TTL jitter to reduce simultaneous cache expiration.
- Add cache miss lock for high-traffic metadata keys.
- Review SQL execution plans for slow queries found by Pinpoint or MySQL slow query log.
- Consider covering indexes only after confirming table lookup cost is relevant.

Runtime/config changes:

- Set explicit JVM heap sizes for Entry/Core.
- Set Redis maxmemory intentionally rather than inheriting large K2P values.
- Reduce Elasticsearch heap for single-node test use.
- Keep Kafka single broker for bottleneck analysis unless testing Kafka HA separately.
- Keep Redis single instance unless testing Sentinel/Cluster separately.
- Keep high-frequency normal-path logs at `DEBUG` during load tests; reserve `WARN` for abnormal states that require operator attention.

## Portfolio Wording

Safe wording:

> The earlier KT Cloud K2P test validated the Kubernetes deployment shape, including Ingress routing, Service discovery, resource limits, and HPA configuration. The later Oracle VM test used Docker Compose to reduce orchestration variables and focus on backend bottleneck analysis with Pinpoint and k6.

Safe wording:

> In the Oracle reproduction environment, Kafka and Redis were intentionally configured as single-node components. The test focused on message flow, Redis Lua atomic operations, DLQ routing, DB idempotency, and request-level latency breakdown rather than HA failover.

Avoid:

> Oracle results improved over K2P, so the system became faster.

Avoid:

> Single-broker Kafka verified RF3/MISR2 HA behavior.

Avoid:

> Single Redis verified Sentinel or Cluster failover.

Avoid:

> HPA handled the 30-second spike.

## Future Hardening

These are separate experiments after the baseline backend bottleneck analysis:

- Kafka 3-broker failure injection with RF3 and `min.insync.replicas=2`
- Redis Sentinel master failover test
- Redis Cluster key hash-tag migration for multi-key Lua
- Nginx upstream health check and container failure redistribution
- Cache stampede automated refresh job
- Kubernetes/k3s deployment reproduction after Docker Compose analysis stabilizes
