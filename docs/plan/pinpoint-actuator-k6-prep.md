# Pinpoint + Actuator + k6 Pre-VM Prep

## Goal

Prepare the Axon codebase so that, once the Oracle VM is available, spike-traffic bottleneck analysis can start immediately.

This prep phase is intentionally minimal:

- request tracing: Pinpoint
- application metrics: Spring Actuator
- load generation: k6

Prometheus and Grafana are not required for the first bottleneck analysis pass.

## Why This Stack

### Pinpoint

Use Pinpoint to answer:

- which API becomes slow first
- where latency is spent inside one request
- whether the hot path is controller, service, DB, Redis, or Kafka-related

### Actuator

Use Actuator to confirm:

- app health
- JVM metrics
- Hikari connection pool metrics
- HTTP server metrics

This is enough for an initial analysis without introducing a separate metrics storage layer.

### k6

Use k6 to reproduce the same FCFS spike scenario and connect:

- load pattern
- response latency
- error rate
- domain outcomes such as success, sold out, and conflict

## Current Repo Status

### Already Available

- `core-service` exposes `health`, `info`, `metrics`, `prometheus`
- `entry-service` exposes `health`, `info`, `metrics`, `prometheus`
- FCFS load test script already exists:
  - `scripts/load-test/k6-fcfs-load-test.js`
- spike analysis plan already exists:
  - `docs/plan/spike-traffic-observability-plan.md`

### Not Ready Yet

- no Pinpoint runtime configuration is wired into app startup
- no VM-oriented runbook for attaching Pinpoint agent
- no narrowed “analysis mode” checklist for the purchase path only

## Pre-VM Preparation Scope

### 1. Keep Actuator Reachable

Required endpoints:

- `GET /actuator/health`
- `GET /actuator/metrics`

Optional:

- `GET /actuator/prometheus`

Reason:

- Prometheus scrape is optional, but the endpoint can stay exposed because it is already configured.

### 2. Prepare Pinpoint Agent Injection Points

Both service Dockerfiles currently start with:

```dockerfile
ENTRYPOINT ["java", "-jar", "app.jar"]
```

When the VM is ready, these entrypoints must support a Pinpoint javaagent form like:

```text
java -javaagent:/pinpoint-agent/pinpoint-bootstrap.jar \
  -Dpinpoint.agentId=<unique-agent-id> \
  -Dpinpoint.applicationName=<service-name> \
  -Dpinpoint.collector.ip=<collector-host> \
  -jar app.jar
```

Pre-VM decision:

- keep Dockerfiles unchanged for now
- inject agent options through runtime env or compose override on the VM

Reason:

- Pinpoint collector/web/container layout is environment-sensitive
- avoiding premature hardcoding keeps the repo cleaner

### 3. Narrow the First Analysis Scenario

Use the first VM run for the FCFS hot path only:

- entry reservation API
- Redis Lua reservation
- Kafka publish
- core consumer
- DB persistence
- purchase batch flush

Do not include in the first pass unless needed:

- Elasticsearch
- Kafka Connect
- Kibana
- LLM queries
- non-essential dashboard flows

Reason:

- fewer moving parts
- easier trace reading
- easier attribution of latency

### 4. Define First Success Criteria

The first VM run is successful if all of the following are true:

- k6 can reproduce the FCFS spike scenario
- Pinpoint shows at least one full request trace for the entry path
- Actuator metrics are reachable from both services
- one bottleneck can be stated with evidence, not guesswork

Examples:

- DB connection wait dominates tail latency
- Redis is fast but Kafka consumer flush becomes slow
- entry API is stable while core persistence backs up

## First VM Checklist

### Runtime

- start MySQL
- start Redis
- start Kafka
- start core-service
- start entry-service
- attach Pinpoint collector/web if using containerized deployment
- attach Pinpoint javaagent to both app services

### Verification

- `curl http://<entry-host>/actuator/health`
- `curl http://<core-host>/actuator/health`
- `curl http://<entry-host>/actuator/metrics`
- `curl http://<core-host>/actuator/metrics`
- open Pinpoint UI and verify both services are visible

### Load

- run `scripts/load-test/k6-fcfs-load-test.js`
- keep scenario fixed while tracing
- save:
  - k6 summary
  - Pinpoint trace screenshots
  - notable actuator metrics

## Out of Scope for the First Pass

- Prometheus time-series retention
- Grafana dashboards
- Kafka HA validation
- Redis Sentinel or Cluster
- Kubernetes HPA verification
- multi-broker failover

These can be added later only if the first trace-based analysis shows a gap.
