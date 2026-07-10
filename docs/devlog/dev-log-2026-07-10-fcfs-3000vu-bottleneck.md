# Dev Log 2026-07-10 — FCFS 3000-VU Bottleneck Investigation

Status: reference (point-in-time investigation record)

## Scenario

Reproduce the FCFS event-open spike on the single Oracle A1 Flex VM (4 OCPU / 24 GB, Chuncheon region, Docker Compose) and locate the bottleneck.

- Flow: `payment` (reservation → prepare → confirm)
- Shape: `waiting_burst`, `NUM_USERS=3000`, `MAX_VUS=3000`, `FCFS_LIMIT_COUNT=800`
- Entry/Core resource profile: entry 1.5 CPU, core 1.2 CPU (see drift note below)
- Load generated externally from a Mac against `https://axon.opicnic.xyz` (nginx public route)

The key axis is `MAX_VUS`. Earlier runs held `MAX_VUS=600` and only changed FCFS volume; those stayed clean. The collapse only appears at true event-open concurrency (`MAX_VUS=3000`).

## Headline Result: the "collapse" was mostly measurement contamination

The single VM **does** serve 3000-VU / 800 successfully (800/800 success, 0 error, DB 800/800, 0 orphan) once the measurement environment is clean. Every apparent "collapse" traced back to a measurement confound, not a server wall.

### Four measurement confounds (each one flipped the conclusion)

| # | Confound | Symptom | Control |
|---|---|---|---|
| 1 | Same-VM k6 | Load generator steals CPU from the app on the shared 4 cores | Run k6 externally (`run-external-compose-baseline.sh`) |
| 2 | Cold `campaign:*:meta` cache | First burst stampedes Core for metadata → reservation server latency ~doubles | `PRELOAD_CAMPAIGN_META=true` before the measured burst |
| 3 | Cold JIT after container recreate | Changing a CPU limit recreates the container → fresh JVM → first burst interpreted → collapse (523/800) | Warm-up burst after every recreate; "Ready ≠ warmed" |
| 4 | Client-side network masking | Client k6 p95 (7–9 s) is dominated by RTT + the Mac doing 3000 TLS handshakes; even an unloaded request over study-café WiFi is ~630 ms | Trust VM-side Prometheus histogram, not client k6 latency |

### Result matrix (payment, 3000-VU / 800)

| Run | k6 | meta | JIT | entry CPU | success | reservation p95 (server) | reservation p95 (client) |
|---|---|---|---|---|---|---|---|
| same-VM warm | VM | warm | warm | 1.5 | 669 | 11,035 ms | 10 s (clipped) |
| external cold | Mac | cold | warm | 1.5 | 607 | 9,432 ms | 10 s (clipped) |
| external warm | Mac | warm | warm | 1.5 | 800 | 4,197 ms | 7,661 ms |
| external warm (fresh recreate) | Mac | warm | cold | 2.0 | 523 | 10 s (clipped) | 10 s (clipped) |
| external warm | Mac | warm | warm | 2.0 | 800 | ~5,100 ms | 6,068–8,019 ms |
| external warm | Mac | warm | warm | 2.5 | 800 | **1,781 ms** | 9,267 ms |

Client p95 for `reservation_duration` is capped by the k6 `timeout: '10s'`; "10 s (clipped)" means the true latency was worse and requests were aborted (→ nginx 499).

## Confirmed bottleneck: virtual-thread carrier count, not raw CPU quota

Entry runs on virtual threads (`spring.threads.virtual.enabled: true`). Its hot path has no `synchronized`, so there is no carrier pinning. The real lever is the **carrier count**, which the JVM derives as `ceil(cpu.max)` and exposes as `system_cpu_count`:

- entry 1.5 → `ceil(1.5) = 2` carriers → reservation server p95 **4,197 ms**
- entry 2.0 → `ceil(2.0) = 2` carriers (same count!) → **no improvement**
- entry 2.5 → `ceil(2.5) = 3` carriers → reservation server p95 **1,781 ms** (−58 %)

Raising the CPU quota without changing `ceil(cpu.max)` adds CPU-time budget but not a parallel lane, so it does nothing for tail latency. Adding a carrier lane (2 → 3) nearly halves the server-side reservation latency. The gain is invisible in the client k6 numbers because confound #4 (network/load-generator latency) swamps it — this is exactly why the server-side histogram had to be enabled.

### Rejected causes (measured innocent)

- **Core / DB persistence**: Hikari active 0–1, pending 0; DB convergence 0 s at 800. Not the bottleneck.
- **Kafka**: `records_lag_max = 0` at all sampled points.
- **Backend event executor** (2 threads): active 0, queue remaining 1000 throughout.
- **Reconciliation**: mismatch 0.
- **Virtual-thread pinning**: no `synchronized` in the entry hot path.

### The orphan-reservation mechanic

When the environment is contaminated, the failure signature is consistent: Redis Lua admits exactly `FCFS_LIMIT_COUNT` (e.g., 800), but only N < 800 persist to DB. Cause: reservation succeeds server-side (Redis counter incremented), but the client hits its 10 s timeout and closes the connection (nginx 499) before issuing `prepare`/`confirm`, so Core never persists those winners. The funnel shows the drop between reservation-200 and prepare-200 (e.g., 811 → 616). These admitted-but-abandoned reservations lock stock that is never paid for.

## Changes applied

1. **Observability (committed, branch `chore/entry-burst-observability`)**
   - entry: `management.metrics.distribution.percentiles-histogram.http.server.requests=true` (server-side p95 per endpoint) and `server.tomcat.mbeanregistry.enabled=true`.
   - Prometheus scrapes `entry-service:8081` (was core-only).
   - `run-external-compose-baseline.sh` forwards `PRELOAD_CAMPAIGN_META` (default true) to the VM prepare step.
2. **Config drift fix (VM `.env`)**: pinned `ENTRY_CPUS=1.5`, `CORE_CPUS=1.2`. Previously 1.5 was set via an ephemeral shell env at deploy time and not persisted, so a plain `docker compose up` would silently revert entry to the 0.6 default.

Entry was returned to 1.5 (2 carriers) as the validated baseline. Entry 2.5 (3 carriers) is a measured server-side improvement and a candidate for adoption; it fits the box (`2.5 + core 1.2 + datastores ≈ 3.7 < 4`).

## Remaining limits and conclusion

The vertical ceiling on this box is bounded: to add a 4th carrier entry would need `ceil ≥ 4` (cpu ≥ 3.01), and `entry 3.0 + core 1.2` already exceeds 4 cores, starving Core. **Horizontal scaling is not available** (this VM is the entire deployment), so replica-based scale-out cannot add CPU.

Therefore the fixed-hardware answer is:

1. **Admission control** — bound concurrency to what the box serves within SLA; reject the excess fast (`429`/`503` + `Retry-After`) or hold it in a waiting room. This turns the 10 s-hang → 499 → orphan-reservation collapse into graceful degradation and removes the stock-locking orphans. The measured single-box ceiling is the admission threshold.
2. **Warm-up gate** — guarantee warm state (campaign-meta preload + JIT warm-up) before the event opens. The fresh-recreate 523 collapse proved a `Ready`/healthy pod is not a warmed hot path.

### Method takeaway

Every layer of measurement contamination removed (same-VM k6 → cold cache → cold JIT → client-network masking) flipped the apparent conclusion. Load-test client numbers were not trusted at face value; the server's true bottleneck and true headroom were isolated only via VM-side server metrics.

## Reproduce

```bash
# external, warmed
cd /Users/yangnail/dev/projects/skusw/axon
SCENARIO=waiting_burst FLOW=payment PRELOAD_CAMPAIGN_META=true \
  NUM_USERS=3000 MAX_VUS=3000 FCFS_LIMIT_COUNT=800 \
  ./scripts/load-test/run-external-compose-baseline.sh 3000 1
# after any entry recreate: run once to warm JIT, then measure the second run
# read server truth from Prometheus histogram, not client k6 latency:
#   1000*histogram_quantile(0.95, sum by(le,uri)(rate(http_server_requests_seconds_bucket{application="entry-service"}[5m])))
#   system_cpu_count{application="entry-service"}   # = carrier count = ceil(cpu.max)
```
