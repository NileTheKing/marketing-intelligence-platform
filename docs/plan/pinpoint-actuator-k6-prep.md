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
- Pinpoint agent injection override exists:
  - `compose.trace.yml`
- Example Pinpoint agent environment exists:
  - `observability/pinpoint-agent.env.example`
- Official Pinpoint Docker network override exists:
  - `observability/pinpoint/compose.axon-network.yml`
- Pinpoint agent copy helper exists:
  - `scripts/observability/install-pinpoint-agent.sh`

### Not Ready Yet

- Pinpoint Collector/Web is not managed inside the Axon Compose stack.
- The Pinpoint agent directory must be supplied separately as `PINPOINT_AGENT_DIR`.
- The collector host must be reachable from `core-service` and `entry-service` containers.

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

Current decision:

- keep Dockerfiles unchanged
- inject agent options through `JAVA_TOOL_OPTIONS` in `compose.trace.yml`
- keep heap settings in the same value so `compose.trace.yml` does not wipe the resource profile

Reason:

- Pinpoint collector/web/container layout is environment-sensitive
- avoiding collector hardcoding keeps the Axon stack focused on the application under diagnosis

Current trace override:

```bash
docker compose \
  -f compose.app.yml \
  -f compose.resources.yml \
  -f compose.trace.yml \
  up -d --build core-service entry-service axon-nginx
```

Before running it, provide the agent options in the shell or `.env`.

Example:

```bash
cp observability/pinpoint-agent.env.example .env.pinpoint
set -a
. ./.env.pinpoint
set +a
```

The default example assumes the agent bootstrap jar is available at:

```text
./pinpoint-agent/pinpoint-bootstrap.jar
```

If the downloaded agent uses a versioned file name such as `pinpoint-bootstrap-2.5.3.jar`, either create a local symlink or override the `PINPOINT_*_AGENT_OPTIONS` values.

The collector host in `PINPOINT_COLLECTOR_HOST` must be reachable from the app containers. If the official `pinpoint-docker` stack is used separately, connect it to the Axon Docker network or set a host/IP that the containers can reach.

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

### Pinpoint Server Stack

Run the official Pinpoint Docker stack outside the Axon repository.

Recommended VM layout:

```text
~/apps/axon
~/apps/pinpoint-docker
```

Clone and start Pinpoint:

```bash
cd ~/apps
git clone https://github.com/pinpoint-apm/pinpoint-docker.git
cd ~/apps/pinpoint-docker

# Avoid conflict with Axon core-service on host port 8080.
sed -i 's/^WEB_SERVER_PORT=.*/WEB_SERVER_PORT=18080/' .env

docker compose \
  -f docker-compose.yml \
  -f /home/ubuntu/apps/axon/observability/pinpoint/compose.axon-network.yml \
  up -d
```

The override connects only `pinpoint-collector` to the existing Axon network:

```text
axon-entry/core
  -> axon_axon-network
  -> pinpoint-collector
```

`pinpoint-web`, HBase, MySQL, Redis, Zookeeper, and other Pinpoint internal services stay in the Pinpoint stack network. The Axon override removes host port publishing for Pinpoint internal services so they do not conflict with Axon MySQL/Redis/Kafka support services. Only Pinpoint Web should be exposed on host port `18080`.

Verify the collector is on the Axon network:

```bash
docker inspect pinpoint-collector --format '{{json .NetworkSettings.Networks}}' | grep axon_axon-network
```

### Pinpoint Agent Directory

The official `pinpoint-docker` stack includes a `pinpoint-agent` container that exposes the agent files. Copy them into the Axon repository after the Pinpoint stack is running:

```bash
cd ~/apps/axon
./scripts/observability/install-pinpoint-agent.sh
```

This creates:

```text
~/apps/axon/pinpoint-agent/pinpoint-bootstrap.jar
```

The directory is intentionally ignored by git because it contains downloaded agent binaries.

### Axon Trace Startup

Prepare the Axon trace environment:

```bash
cd ~/apps/axon
cp observability/pinpoint-agent.env.example .env.pinpoint

set -a
. ./.env.pinpoint
set +a
```

Expected collector setting:

```bash
PINPOINT_COLLECTOR_HOST=pinpoint-collector
```

Start only the app-facing services with the trace override:

```bash
docker compose \
  -f compose.app.yml \
  -f compose.resources.yml \
  -f compose.trace.yml \
  up -d --build core-service entry-service axon-nginx
```

### Verification

- `curl http://<entry-host>/actuator/health`
- `curl http://<core-host>/actuator/health`
- `curl http://<entry-host>/actuator/metrics`
- `curl http://<core-host>/actuator/metrics`
- open Pinpoint UI and verify both services are visible
- verify the JVM picked up the agent options:

```bash
docker logs --since=2m axon-entry 2>&1 | grep -E 'Picked up JAVA_TOOL_OPTIONS|Pinpoint'
docker logs --since=2m axon-core 2>&1 | grep -E 'Picked up JAVA_TOOL_OPTIONS|Pinpoint'
```

Verify container DNS from Axon to Pinpoint Collector:

```bash
docker exec axon-entry getent hosts pinpoint-collector
docker exec axon-core getent hosts pinpoint-collector
```

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
