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

### Baseline Mode

Baseline mode runs the full project shape with observability attached.

Components:

- Nginx
- Entry-service containers
- Core-service containers
- Kafka
- Redis
- MySQL
- Elasticsearch
- Kafka Connect
- Pinpoint

Purpose:

- Confirm the integrated application flow runs under spike load.
- Observe API latency, Redis calls, DB calls, Kafka consumer processing, and behavior-log pipeline impact.
- Identify noisy or heavy components before optimizing.

### Analysis Mode

Analysis mode reduces variables while keeping the core purchase path.

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
- Reduce noise when reading Pinpoint traces.

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

1. Start from a known compose profile.
2. Reset or seed the same dataset.
3. Run the same k6 scenario.
4. Capture Pinpoint traces and k6 summary.
5. Identify one bottleneck only.
6. Apply one change.
7. Run the same scenario again.
8. Compare only results from the same Oracle VM environment.

Do not compare Oracle results directly against the previous K2P results. K2P and Oracle differ in CPU model, memory, disk I/O, network path, Kubernetes overhead, and deployed component shape.

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

