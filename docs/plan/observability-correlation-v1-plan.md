# Observability Correlation v1

Status: active (local implementation verified, VM rollout pending)

## Goal

Operate the Oracle VM Compose deployment with four Golden Signals, centralized
container logs, and sampled request traces. A single incident must be searchable
by both technical trace identity and business workflow identity without sending
behavior analytics events or sensitive values to the log backend.

## Scope

```text
Entry/Core/AI/container nginx/host nginx logs -> Alloy -> Loki -> Grafana
Entry/Core/AI metrics                         -> Prometheus -> Grafana
Entry/Core/AI sampled traces                  -> Jaeger -> Grafana
```

- `Elasticsearch` remains the behavior-analytics store. It is not an
  application-log backend.
- Prometheus and error logs are always collected.
- OpenTelemetry uses 5% head sampling for normal runtime trace topology.
  It is not evidence that every failed request has a saved trace.
- Official load-test before/after measurements keep OTel disabled so agent
  overhead does not affect headline figures.
- Tail sampling through an OTel Collector, always-on error traces, alerting,
  and Kubernetes deployment are out of v1.

## Correlation Contract

All application logs are JSON. Low-cardinality Loki labels are limited to:

```text
service, environment, level
```

The following are JSON fields, never Loki labels:

```text
trace_id, span_id, request_id, triage_case_id, dispatch_id
```

Do not log tokens, authorization headers, request/response bodies, user IDs,
or LLM prompts/operator feedback.

- `trace_id` joins one sampled technical request to Jaeger.
- `triage_case_id` joins initial analysis, re-analysis, approval, and retry
  across separate traces.
- `dispatch_id` joins a specific delivery attempt to its execution history.

## Minimal Runtime Components

1. `loki`: single-binary local storage with bounded retention for this
   single-VM deployment.
2. `alloy`: discovers Docker containers and tails Docker JSON logs. It also
   tails mounted host-nginx logs. Docker discovery retains only the `axon`
   Compose project because the VM also runs another project. It runs as root
   solely to read the Docker socket and host log files, which are mounted
   read-only.
3. `prometheus` and `grafana`: existing metrics stack, with Loki and Jaeger
   provisioned as datasources.
4. `jaeger`: existing trace backend promoted from manual diagnosis overlay to
   sampled operational tracing.

## Rollout Boundary

The tracked Compose changes are implemented locally. The Oracle VM host nginx
source files are tracked under `infrastructure/host-nginx/`. Rollout is not
complete until that vhost forwards `X-Request-Id` to axon-nginx and writes the
observability JSON log with the same `request_id` field. Its legacy timing log
remains unchanged because FCFS load-test artifact scripts consume it.

Use the normal runtime overlay only after the Java agent JAR is present:

```bash
./scripts/observability/install-otel-agent.sh
docker compose \
  -f compose.app.yml \
  -f compose.resources.yml \
  -f compose.metrics.yml \
  -f compose.otel.yml \
  --profile ai up -d --build
```

Formal k6 performance runs keep `compose.otel.yml` out of the command. Loki,
Prometheus, and Grafana can remain enabled in both modes.

## Service Instrumentation

### Entry/Core

- Keep Micrometer/Actuator metrics and existing diagnostic profile separate.
- OTel Java agent exports sampled traces to Jaeger.
- Logback JSON output includes OTel MDC `trace_id`/`span_id` when a trace is
  sampled.

### AI triage

- FastAPI and `httpx` instrumentation emits HTTP spans.
- Custom spans cover graph nodes, Core tool calls, Groq calls, and Slack calls.
- Prometheus metrics cover case processing, tool/LLM latency, failures, and
  Slack/decision outcomes.
- Structured logs use the same correlation contract.

## Grafana

The default dashboard is Golden Signals first:

| Signal | Measures |
|---|---|
| Traffic | HTTP request rate, Kafka consumption, AI case processing |
| Latency | Entry p95, Core processing, AI analysis duration |
| Errors | HTTP 5xx/timeouts, DLT, AI analysis/Slack failures |
| Saturation | JVM/container CPU, Hikari pending, Kafka lag, executor queue |

Detailed FCFS and AI panels are drill-down views, not a dense landing page.
Grafana derives a Jaeger link from the Loki JSON `trace_id` field.

## Acceptance Checks

1. `docker compose ... config` validates the observability overlays.
2. Prometheus scrapes Entry, Core, and AI `/metrics`.
3. A structured AI log with `triage_case_id` is searchable in Loki.
4. A sampled AI request shows spans for the graph and Core/LLM/Slack boundary
   in Jaeger.
5. A Loki log containing `trace_id` links to the corresponding Jaeger trace.
6. Existing Webhook 500 -> DLT -> triage -> operator approval -> retry success
   scenario is repeatable with the observability signals visible.
