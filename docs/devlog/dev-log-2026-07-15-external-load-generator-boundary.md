# Dev Log 2026-07-15 - External Load Generator Boundary

Status: reference (point-in-time investigation record)

## Purpose

Record why the `payment / waiting_burst / 3000 users / 3000 VUs / FCFS 800`
external measurements run on 2026-07-15 are **not** portfolio-final performance
numbers. The point is to preserve the boundary between confirmed server facts
and load-generator/network contamination.

## Confirmed Facts

- Test path used direct origin with `https://134.185.100.15` plus the Axon
  virtual host. It bypassed Cloudflare.
- Each run prepared fresh FCFS data and tokens. Redis admitted all 800 winners,
  but the payment path sometimes converged below 800 in MySQL because clients
  did not reach the later prepare/confirm steps.
- For every measured run, `3000 - host nginx reservation access-log records`
  matched k6 `fcfs_error_count` (within the interval boundary). Requests counted
  as FCFS errors therefore did not reach host nginx user-space access logging.
- Requests that did reach host nginx and `axon-nginx` completed as business
  responses (`200` or `410`); no host nginx `worker_connections`, upstream
  timeout, upstream connect failure, or upstream reset signal was recorded in
  the captured interval.
- Host nginx to Axon nginx upstream-connect p95 varied widely across otherwise
  similar runs (`0.000s` to `1.493s`). This is a latency-variance signal, not a
  proven root cause for pre-host request loss.
- The external Mac network measured approximately `38ms` unloaded latency and
  `1.2s` latency under a speed-test load. Throughput was about `46Mbps` down /
  `39Mbps` up. This is strong evidence of client-network queueing/bufferbloat,
  but not proof that Wi-Fi is the sole cause.

## Why the APM Work Was Necessary

The earlier diagnostic work was not a detour to be discarded. It narrowed the
application-side candidates before the host boundary was observable:

1. **OTel Java agent + Jaeger** traced Entry and Core handler shape. Successful
   reservation traces could complete in the application while client E2E time
   remained much larger, proving that client p95 cannot be read as Entry-only
   latency.
2. **Micrometer/Actuator** exposed Entry HTTP histograms, cgroup CPU/carrier
   count, Core Kafka lag, executor state, queue/flush/retry/reconciliation
   metrics, and DB convergence. At the target observation points, Core lag and
   persistence were not the source of the missing reservation requests.
3. The diagnostic stage timer showed backend behavior-event publish as a hot
   path candidate. It led to a dedicated executor experiment, but repeated
   warm-up variance prevented claiming that executor isolation alone produced a
   final p95 improvement. The executor remains a maintainability/isolation
   change, not the explanation for this external failure.
4. Once APM showed the Entry/Core side was not producing the missing-request
   signature, host nginx interval logs were added. They established that the
   missing requests were absent even before host nginx access logging.

Therefore the current conclusion is a **boundary confirmation**, not a final
single-cause claim: Entry/Core are not the direct source of these external k6
errors; the unresolved area is before host nginx user-space handling. Detailed
trace, metric, and executor evidence remains in
`docs/plan/otel-jaeger-apm-plan.md`.

## Observability Added

`scripts/load-test/run-external-compose-baseline.sh` now captures, per k6
interval:

- `host-nginx-access.log`: only the matching host nginx timing-log interval
- `host-nginx-error.log`: only the matching host nginx error-log interval
- `host-nginx-reservation-summary.md`: reservation record count, host status,
  host request-time p95, host-to-Axon upstream connect/response p95, and key
  nginx error counts

This complements the existing `axon-nginx` completion summary. It makes the
following boundary explicit:

```text
Mac k6 -> network/TCP -> host nginx -> axon-nginx -> Entry
                         ^ host summary     ^ container summary
```

## Historical Host nginx Keep-alive Trial

The VM host nginx Axon vhost temporarily tested the following rejected change:

- Added `upstream axon_nginx { server 127.0.0.1:28080; keepalive 256; }`
- Switched the general and SSE Axon proxy locations to HTTP/1.1 upstream
  keep-alive (`Connection ""`)
- Backed up the vhost, passed `nginx -t`, and reloaded nginx successfully.

The first post-change runs still had high variance, and the trial later produced
Tomcat `400 Bad Request` responses in this environment. It was reverted. The
current host-nginx configuration keeps upstream keep-alive off; do not present
this trial as an active configuration or a latency improvement.

## Invalid / Non-final Artifacts

- `20260715-120927-external-warmed-payment`: measured errors `158/120/248`;
  DB convergence `798/800`, `800/800`, `798/800`.
- `20260715-123059-external-warmed-payment`: measured errors `214/305/74`;
  DB convergence `792/800`, `800/800`, `797/800`.
- `20260715-130457-external-warmed-payment`: post keep-alive diagnostic;
  measured errors `337/204/177`; DB convergence `797/800`, `800/800`,
  `790/800`.

None may be used as the final FCFS 3000/800 portfolio result.

## Next Reproducible Measurement

Run only on a stable home wired/Wi-Fi connection or an independent load
generator. Before accepting a final run:

1. Record unloaded and loaded latency/jitter on the generator network.
2. Keep VM resource profile fixed: Entry/Core/Axon nginx = `1.5/1.2/0.5`.
3. Use the same `3 warm-up + 3 measured` payment protocol.
4. Require every measured run to have FCFS `800`, error `0`, Redis `800`, DB
   entries/purchases `800`, and inspect the host/Axon nginx summaries.
5. Use only the measured-run median from a passing protocol. Keep client E2E
   latency and host/Axon server timings as separate metrics.

## Portfolio Boundary

T27 and portfolio section 12 may describe the layered diagnosis and the
confirmed host-nginx connection-limit remediation. They must not present a
final `3000/800` latency/RPS value until the stable-network protocol above
passes.
