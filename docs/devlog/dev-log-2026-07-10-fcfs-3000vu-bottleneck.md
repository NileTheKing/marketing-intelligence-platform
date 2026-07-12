# Dev Log 2026-07-10 — FCFS 3000-VU Bottleneck Investigation

Status: reference (point-in-time investigation record)

## Scenario

Reproduce the FCFS event-open spike on the single Oracle A1 Flex VM (4 OCPU / 24 GB, Chuncheon region, Docker Compose) and locate the bottleneck.

- Flow: `payment` (reservation → prepare → confirm)
- Shape: `waiting_burst`, `NUM_USERS=3000`, `MAX_VUS=3000`, `FCFS_LIMIT_COUNT=800`
- Resource profile for the runs below: **entry 1.5 CPU, core 1.2 CPU** (VM `.env`; see reproduce block — the repo default is different)
- Public request path: `Cloudflare → host nginx (:443, systemd) → 127.0.0.1:28080 → axon-nginx container → entry-service`. Note there are **two** nginx layers; the container (`worker_connections 2048`) and the host nginx (`/etc/nginx`, tuned to `worker_connections 4096` / `worker_rlimit_nofile 16384`).

The key axis is `MAX_VUS`. Earlier runs held `MAX_VUS=600` and only changed FCFS volume; those stayed clean. The collapse only appears at true event-open concurrency (`MAX_VUS=3000`).

## Two separate axes: real infra capacity defect vs measurement contamination

An earlier reviewer pass corrected an over-broad framing in this log. Failures on this path split into **two distinct causes** that must not be lumped together:

### Axis A — real public-path capacity defect (host nginx)

The public route runs through a host nginx (nginx 1.18, systemd), separate from the `axon-nginx` container. Its config now shows non-default `worker_connections 4096` / `worker_rlimit_nofile 16384`, and `nginx.conf` was last tuned **2026-07-10 05:07 UTC**. The repo already recorded this axis before this session:

- `docs/plan/spike-traffic-observability-plan.md:60` lists `-> VM host Nginx` in the path.
- `docs/plan/oracle-compose-baseline-runbook.md:318` records `3000/600` failure runs with **nginx `500`** and **DB convergence ~588–590** (not full 800/600).
- `oracle-compose-baseline-runbook.md:321` notes results **changed after connection tuning**.

This is a genuine capacity fault of the public route (host nginx connection saturation → 5xx and non-convergence), fixed by raising `worker_connections`/`worker_rlimit_nofile`. It is **not** a measurement artifact.

Scope note: the host-nginx tuning (05:07 UTC) predates **all** runs in the matrix below (08:11 UTC onward), so the collapses in this matrix were not caused by host nginx. The host-nginx saturation belongs to the earlier `3000/600` failure history. The clean external results below therefore *depend on* that pre-existing host-nginx fix as a precondition.

### Axis B — measurement contamination (four confounds)

With host nginx already tuned, the remaining apparent "collapses" traced to measurement confounds, not a server wall. Once controlled, the single VM serves 3000-VU / 800 at 800/800, 0 error, DB 800/800, 0 orphan.

| # | Confound | Symptom | Control |
|---|---|---|---|
| 1 | Same-VM k6 | Load generator steals CPU from the app on the shared 4 cores | Run k6 externally (`run-external-compose-baseline.sh`) |
| 2 | Cold `campaign:*:meta` cache | First burst stampedes Core for metadata → reservation server latency ~doubles | `PRELOAD_CAMPAIGN_META=true` before the measured burst |
| 3 | Cold JIT after container recreate | Changing a CPU limit recreates the container → fresh JVM → first burst interpreted → collapse (523/800) | Warm-up burst after every recreate; "Ready ≠ warmed" |
| 4 | Client-side E2E latency | Client k6 p95 (7–9 s) mixes Cloudflare + host nginx + container nginx + app wait + network + Mac TLS; even an unloaded request over study-café WiFi is ~630 ms | Use VM-side Prometheus histogram; treat client p95 as E2E only |

### Result matrix (payment, 3000-VU / 800; host nginx already tuned)

| Run | k6 | meta | JIT | entry CPU | success | reservation p95 (server) | reservation p95 (client, E2E) |
|---|---|---|---|---|---|---|---|
| same-VM warm | VM | warm | warm | 1.5 | 669 | 11,035 ms | 10 s (clipped) |
| external cold | Mac | cold | warm | 1.5 | 607 | 9,432 ms | 10 s (clipped) |
| external warm | Mac | warm | warm | 1.5 | 800 | 4,197 ms | 7,661 ms |
| external warm (fresh recreate) | Mac | warm | cold | 2.0 | 523 | 10 s (clipped) | 10 s (clipped) |
| external warm | Mac | warm | warm | 2.0 | 800 | ~5,100 ms | 6,068–8,019 ms |
| external warm | Mac | warm | warm | 2.5 | 800 | **1,781 ms** | 9,267 ms |

Each config is a **single run** (N=1); variance under identical conditions was not characterized. Client `reservation_duration` p95 is capped by k6 `timeout: '10s'`; "10 s (clipped)" means the true latency was worse and requests were aborted (→ nginx 499).

## What CPU does here: carrier count (`ceil` of quota) — stated by confidence tier

Entry runs on virtual threads (`spring.threads.virtual.enabled: true`). The carrier (platform) thread count the scheduler uses is `Runtime.availableProcessors()`, which the JVM derives in a cgroup as `ceil(cpu.max)` and Micrometer exposes as `system_cpu_count`.

Server-side reservation p95 observed (each a **single, warmup-inconsistent run** — do not read as a clean curve):

| entry | `ceil` = carriers | reservation server p95 |
|---|---|---|
| 0.6 | 1 | (prior work: timeout-class errors, see Tier 2) |
| 1.5 | 2 | 4,197 ms |
| 2.0 | 2 | ~5,100 ms |
| 2.1 | 3 | 5,173 ms **and** 2,017 ms (two runs) |
| 2.5 | 3 | 1,781 ms |

Stated by confidence, not as a settled "saturation curve":

- **Tier 1 — fact:** carrier count = `ceil(cpu.max)`, confirmed via `system_cpu_count` (0.6→1, 1.5→2, 2.5→3). Documented JVM behaviour; no repeats needed.
- **Tier 2 — well supported (partly via prior docs):** 0.6 is far worse than 1.5. Basis: the earlier `otel-jaeger-apm-plan` result recorded *timeout-class errors eliminated and throughput ~2×* (a categorical effect, not a small p95 shift, so it resists run noise), plus the mechanism — `ceil(0.6)=1` serialises all virtual threads through a single carrier. Confident on direction; the exact "2×" magnitude was not re-verified this session.
- **Tier 3 — NOT established (was over-claimed):** the effect of going above 1.5 (2→3 carriers). Our 1.5–2.5 runs are N=1 and confounded by the warm-up ramp (below); the same config (2.1) produced 5,173 ms and 2,017 ms. So we **failed to measure** a reliable difference — this is *inconclusive*, not evidence that more carriers do not help. Settling it needs identical-protocol repeats (recreate → multi-burst warm-up → N≥3).

Whatever the server-side truth, it is invisible in client E2E p95 because Axis-B confound #4 swamps it — which is why the server-side histogram had to be enabled.

### Reservation is robust; the payment path is the fragile part; warm-up is a multi-burst ramp

Two findings from a `FLOW=reservation` isolation run (external, entry 1.5):

- **Reservation-only never collapsed**: 3× runs each 800/800, error 0. All the collapses, 499s, and orphan reservations were a **payment-flow** phenomenon (the `entry→core` prepare/confirm hops), not the FCFS admission path. Redis-Lua admission itself is solid.
- **`entry↔core` contention costs ~1,200 ms**: reservation server p95 was 2,966 ms with core idle (CPU 0.27) in reservation-only, versus 4,197 ms with core busy (CPU 0.64) under payment. Payment makes entry and core compete for the shared 4 cores at the same instant.
- **Warm-up is a ramp, not a one-shot**: successive reservation-only runs gave client p95 8,316 → 6,044 → 3,545 ms (monotonic). One warm-up burst is not "warmed". This is a large part of why the single-run CPU numbers above are noisy, and it upgrades the warm-up gate from "Ready ≠ warmed" to "one burst ≠ warmed".

### Rejected causes (measured innocent)

- **Core / DB persistence**: Hikari active 0–1, pending 0; DB convergence 0 s at 800.
- **Kafka**: `records_lag_max = 0` at all sampled points.
- **Backend event executor** (2 threads): active 0, queue remaining 1000 throughout.
- **Reconciliation**: mismatch 0.
- **Virtual-thread pinning**: no *explicit* `synchronized` in the entry application hot path (grep). This does **not** rule out pinning from library/native code — that requires JFR `jdk.VirtualThreadPinned` (or `-Djdk.tracePinnedThreads`), which was not run.

### The orphan-reservation mechanic

Under contamination the signature is consistent: Redis Lua admits exactly `FCFS_LIMIT_COUNT` (e.g., 800), but only N < 800 persist to DB. Cause: reservation succeeds server-side (Redis counter incremented), but the client hits its 10 s timeout and closes the connection (nginx 499) before issuing `prepare`/`confirm`, so Core never persists those winners. The funnel shows the drop between reservation-200 and prepare-200 (e.g., 811 → 616). These admitted-but-abandoned reservations lock stock that is never paid for.

## Changes applied

1. **Observability** (`chore/entry-burst-observability`, merged to main)
   - entry: `management.metrics.distribution.percentiles-histogram.http.server.requests=true` (server-side p95 per endpoint) and `server.tomcat.mbeanregistry.enabled=true`.
   - Prometheus scrapes `entry-service:8081` (was core-only).
   - `run-external-compose-baseline.sh` forwards `PRELOAD_CAMPAIGN_META` (default true) to the VM prepare step.
2. **Config drift fix**: VM `.env` pins `ENTRY_CPUS=1.5`, `CORE_CPUS=1.2`; and the repo default in `compose.resources.yml` was corrected from the stale `0.6` to the validated `1.5` (see git history). Previously 1.5 lived only in an ephemeral shell env, so a plain `docker compose up` would silently revert entry to `0.6`.

Entry was returned to 1.5 (2 carriers) as the validated steady-state baseline: it reliably serves 800/800, keeps headroom for core (the fragile payment path, which is also not owned by this workstream), and the big CPU win (0.6→1.5) is already banked there. Raising entry (2.1/2.5 → 3 carriers) is *not* an established improvement (Tier 3 above) and, on a shared 4-core box, a larger entry limit mainly lets entry win more contention against whatever else runs concurrently. A defensible use of a higher value is a **dedicated event window** (pause batch/scheduler, bump entry via `.env`, revert after) — i.e. part of pre-event tuning, not the steady-state default.

## Remaining limits and conclusion

The vertical ceiling on this box is bounded: a 4th carrier needs `ceil ≥ 4` (cpu ≥ 3.01), and `entry 3.0 + core 1.2` already exceeds 4 cores, starving Core. **Horizontal scaling is not available** (this VM is the entire deployment), so replica-based scale-out cannot add CPU.

Fixed-hardware answer:

1. **Admission control** — bound concurrency to what the box serves within SLA; reject the excess fast (`429`/`503` + `Retry-After`) or hold it in a waiting room. Turns the 10 s-hang → 499 → orphan-reservation collapse into graceful degradation and removes stock-locking orphans. The measured single-box ceiling is the admission threshold.
2. **Warm-up gate** — guarantee warm state (campaign-meta preload + JIT warm-up) before the event opens. The fresh-recreate 523 collapse proved a `Ready`/healthy pod is not a warmed hot path.

### Method takeaway

Separate the **two axes**: a real public-path capacity defect (host nginx connection limits — a genuine infra fix) versus measurement contamination (load-generator/cache/JIT/E2E). Removing each contamination layer flipped the apparent conclusion, so client load-test numbers were not trusted at face value; the server's true bottleneck and headroom were isolated via VM-side server metrics.

## Reproduce

```bash
# external, warmed. Set the CPU profile explicitly — the repo default alone is not the validated profile.
cd /Users/yangnail/dev/projects/skusw/axon
# on the VM, ensure the resource profile:  ENTRY_CPUS=1.5 CORE_CPUS=1.2  (in ~/apps/axon/.env)
SCENARIO=waiting_burst FLOW=payment PRELOAD_CAMPAIGN_META=true \
  NUM_USERS=3000 MAX_VUS=3000 FCFS_LIMIT_COUNT=800 \
  ./scripts/load-test/run-external-compose-baseline.sh 3000 1
# after any entry recreate: run once to warm JIT, then measure the second run
# read server truth from Prometheus histogram, not client k6 latency:
#   1000*histogram_quantile(0.95, sum by(le,uri)(rate(http_server_requests_seconds_bucket{application="entry-service"}[5m])))
#   system_cpu_count{application="entry-service"}   # = carrier count = ceil(cpu.max)
```
