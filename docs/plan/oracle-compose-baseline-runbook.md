# Oracle Compose Baseline Runbook

Status: active

## Purpose

Run a reproducible FCFS purchase-path baseline on the Oracle VM Docker Compose environment.

This runbook is separate from the older k8s-era load-test scripts and previous KT Cloud K2P results. Do not compare Oracle VM numbers directly with K2P numbers.

## Source Boundary

Use these scripts for the current Oracle VM Compose baseline:

- `scripts/load-test/prepare-load-test-compose.sh`
- `scripts/load-test/run-external-compose-baseline.sh`
- `scripts/load-test/run-baseline-compose.sh`
- `scripts/load-test/check-results-compose.sh`

Keep these existing scripts as historical/k8s reproduction references:

- `scripts/load-test/prepare-load-test.sh`
- `scripts/load-test/check-results.sh`
- `scripts/load-test/monitor-load-test.sh`

## CI/CD Execution Policy

Status: active

- `.github/workflows/ci.yml` runs automatically on pushes to `main` and pull requests. It verifies tests, required Testcontainers suites, Docker Compose configuration, and image builds; it does not change the VM.
- `.github/workflows/deploy-compose.yml` is the current Oracle VM deployment workflow. It originally ran automatically after a successful CI workflow, but that `workflow_run` trigger was disabled on 2026-07-01 to avoid unintended rebuilds during repeated load tests and frequent code changes.
- Current Oracle deployment is manual. Run `Deploy to Oracle VM Compose` through GitHub Actions (`workflow_dispatch`) or deploy directly on the VM when an explicit deployment is intended.
- The Actions deployment connects to the VM over SSH, pulls `origin/main`, runs `docker compose up -d --build`, restarts `axon-nginx`, and checks Core, Entry, and nginx health.
- CI failure is a visible verification failure, but it does not technically prevent the manual deployment workflow or a direct VM deployment. Check CI before triggering either manual path.
- `.github/workflows/deploy.yml` is the legacy KT Cloud K2P workflow. It is manual-only and is not the current Oracle deployment path.

Do not re-enable automatic CI-success deployment while load-test artifacts or the running VM state must be preserved across frequent commits. Re-enable it only after automatic deployment is again the desired release policy.

## Baseline Assumptions

- Axon services run on the Oracle VM Docker Compose environment.
- Official baseline/final load is generated from an external client machine, not from the same VM.
- Core health is `UP`: `http://127.0.0.1:8080/actuator/health`
- Entry health is `UP`: `http://127.0.0.1:8081/actuator/health`
- MySQL is exposed on `127.0.0.1:3306`.
- Redis container name is `axon-redis`.
- k6 is installed on the external client machine for official measurement.
- Official before/after baseline runs use `compose.app.yml` plus `compose.resources.yml`.

If k6 runs on the same VM, its CPU, network, and Docker overhead become part of the measured environment. Use same-VM k6 only for fast isolation/debugging. Do not use same-VM k6 numbers as the headline baseline.

## Resource Profile

Use the same resource profile for baseline and final measurement.

Default `compose.resources.yml` limits:

| Service | CPU | Memory | JVM heap |
|---|---:|---:|---|
| core-service | 1.2 | 3GiB | `-Xms512m -Xmx2g` |
| entry-service | 0.6 | 2GiB | `-Xms256m -Xmx1536m` |
| mysql | 0.75 | 4GiB | n/a |
| kafka broker | 0.6 | 4GiB | image default |
| kafka controller | 0.25 | 1GiB | image default |
| redis | 0.25 | 1GiB | n/a |
| axon-nginx | 0.1 | 256MiB | n/a |

This 3.75 vCPU profile is intended for a 4 vCPU Oracle VM. It leaves CPU headroom for the host OS, Docker, host nginx, SSH, and short debug commands. Do not compare runs that use different resource profiles as a single before/after result.

## First Baseline Command

Preferred official path: run k6 from the Mac against the public route through the external baseline wrapper.

Default end-to-end purchase path:

```bash
cd /Users/yangnail/dev/projects/skusw/axon
FLOW=payment MAX_VUS=100 ./scripts/load-test/run-external-compose-baseline.sh 1000 1
```

Reservation-only hot path:

```bash
cd /Users/yangnail/dev/projects/skusw/axon
FLOW=reservation MAX_VUS=100 ./scripts/load-test/run-external-compose-baseline.sh 1000 1
```

The wrapper performs the previous manual sequence:

1. Reset seed data and regenerate JWT tokens on the VM.
2. Verify token count on the VM.
3. Copy the fresh token file to the Mac.
4. Verify token count locally.
5. Run external k6 against `https://axon.opicnic.xyz`.
6. Verify Redis and MySQL counts on the VM.

Optional manual equivalent:

```bash
ssh -i ~/.ssh/oci_arm_key ubuntu@134.185.100.15 'cd ~/apps/axon && ./scripts/load-test/prepare-load-test-compose.sh 1000 1'
```

```bash
scp -i ~/.ssh/oci_arm_key ubuntu@134.185.100.15:/home/ubuntu/apps/axon/scripts/load-test/jwt-tokens.json /Users/yangnail/dev/projects/skusw/axon/scripts/load-test/jwt-tokens.json
```

```bash
cd /Users/yangnail/dev/projects/skusw/axon && FLOW=payment SCENARIO=spike MAX_VUS=100 USE_PRODUCTION_API=true USE_TOKEN_FILE=true TOKEN_FILE_PATH=/Users/yangnail/dev/projects/skusw/axon/scripts/load-test/jwt-tokens.json ENTRY_SERVICE_URL=https://axon.opicnic.xyz CORE_SERVICE_URL=https://axon.opicnic.xyz ACTIVITY_ID=1 PRODUCT_ID=1 FCFS_LIMIT_COUNT=200 USER_ID_START=1000 USER_ID_END=1999 k6 run scripts/load-test/k6-fcfs-load-test.js
```

GitHub Actions path: useful for remote execution and artifact collection, but not the preferred official baseline while k6 runs inside the VM.

- Actions workflow: `Run VM Compose Baseline`
- Execution mode: GitHub Actions connects to the VM through SSH, but k6 runs inside the VM.
- Result retrieval: the workflow downloads `latest-compose-baseline.tar.gz` from the VM and uploads it as a GitHub Actions artifact named `vm-compose-baseline`.
- `use_resource_profile=true`: deploy with `compose.app.yml + compose.resources.yml`.
- `use_resource_profile=false`: deploy with `compose.app.yml` only. Use this only for A/B checks against the older unlimited Compose shape.
- `redeploy=true`: pull `main`, rebuild/restart Compose, then run k6.
- `redeploy=false`: skip rebuild and run k6 against the currently running containers.
- `flow=behavior|reservation|payment|full`: choose which k6 path to execute.

Use `flow=full` only after the smaller flows are stable.

Manual VM fallback:

```bash
cd ~/apps/axon
docker compose -f compose.app.yml -f compose.resources.yml up -d --build
docker restart axon-nginx
./scripts/load-test/run-baseline-compose.sh 1000 1
```

## Mandatory Nginx Restart After App Recreate

Status: active operational rule

When `axon-entry` or `axon-core` is recreated, restart `axon-nginx` before any load test that goes through `127.0.0.1:28080`, `https://axon.opicnic.xyz`, or the nginx container path.

Reason:

- Docker Compose can recreate `axon-entry` or `axon-core` with a new container IP.
- Nginx can keep using the stale upstream IP it resolved earlier.
- The app can be healthy on the direct published port while nginx still sends traffic to the old container IP.

Symptom:

```text
direct 127.0.0.1:8081 responds
nginx  127.0.0.1:28080 returns 502
nginx log: connect() failed (113: Host is unreachable) while connecting to upstream
```

Required post-recreate check:

```bash
cd ~/apps/axon
docker restart axon-nginx

curl -fsS http://127.0.0.1:8081/actuator/health
curl -sS -o /tmp/nginx-entry.out -w "nginx_entry_http=%{http_code} time=%{time_total}\n" \
  -X POST http://127.0.0.1:28080/api/v1/entries \
  -H "Content-Type: application/json" \
  -d "{}"
```

Expected unauthenticated probe result:

```text
nginx_entry_http=401
```

`401` means nginx reached Entry and Spring Security rejected the unauthenticated request. That is a valid path check. `502`, `499`, or `connect() failed ... upstream` means do not run the load test yet.

Warm-run before/after loop. Use this when comparing code or config changes and the first post-deploy cold run would hide the real signal:

> `MEASURED_NUM_USERS=3000` and `MEASURED_MAX_VUS=600` is a VU-capped shared-iteration throughput run. It processes 3,000 unique users through at most 600 active k6 VUs; it is not a 3,000-person simultaneous waiting-room flash crowd. Use `MAX_VUS=3000` when the test question is event-open concurrency.

```bash
cd ~/apps/axon

SCENARIO=waiting_burst \
FLOW=reservation \
WARMUP_NUM_USERS=50 \
WARMUP_MAX_VUS=5 \
WARMUP_FCFS_LIMIT_COUNT=50 \
MEASURED_NUM_USERS=3000 \
MEASURED_MAX_VUS=600 \
MEASURED_FCFS_LIMIT_COUNT=600 \
MEASURED_RUNS=2 \
K6_ENTRY_SERVICE_URL=http://127.0.0.1:28080 \
  ./scripts/load-test/run-warm-baseline-compose.sh 1
```

This wrapper intentionally runs a small real reservation-path warm-up, then runs the measured baseline after `run-baseline-compose.sh` resets the test data again. Treat the warm-up run as setup, not as a measured result.

Known-good validation:

```text
commit: be6975a
artifact: /home/ubuntu/apps/axon/artifacts/load-test/20260708-074127-warm-baseline
measured-1: status 0, success 600, error 0, reservation p95 127.10ms
measured-2: status 0, success 600, error 0, reservation p95 115.05ms
```

For `FLOW=reservation`, DB entries and purchases are not expected. The reservation-only domain target is Redis counter/users matching `FCFS_LIMIT_COUNT`.

Payment flow warm-run validation:

```bash
cd ~/apps/axon

SCENARIO=waiting_burst \
FLOW=payment \
WARMUP_NUM_USERS=50 \
WARMUP_MAX_VUS=5 \
WARMUP_FCFS_LIMIT_COUNT=50 \
MEASURED_NUM_USERS=3000 \
MEASURED_MAX_VUS=600 \
MEASURED_FCFS_LIMIT_COUNT=600 \
MEASURED_RUNS=1 \
K6_ENTRY_SERVICE_URL=http://127.0.0.1:28080 \
  ./scripts/load-test/run-warm-baseline-compose.sh 1
```

Known-good validation:

```text
commit: 93a29a5
artifact: /home/ubuntu/apps/axon/artifacts/load-test/20260708-payment-main-600
measured-1: status 0, success 600, error 0, reservation p95 1194.20ms, http p95 1621.06ms
domain: Redis 600/600, DB entries/purchases 600/600, convergence 0s/0s

artifact: /home/ubuntu/apps/axon/artifacts/load-test/20260709-payment-main-600-repeat
measured-1: status 0, success 600, error 0, reservation p95 613.05ms, http p95 799.01ms
domain: Redis 600/600, DB entries/purchases 600/600, convergence 0s/0s
```

For `FLOW=payment`, `check-results-compose.sh` prints DB entries/purchases convergence seconds. Use this as the first split between payment-path failure and Core async persistence delay.

Fast debug loop on the VM. Use this only to isolate obvious server-side failures before running the external Mac baseline:

```bash
cd ~/apps/axon

FLOW=behavior MAX_VUS=100 FCFS_LIMIT_COUNT=20 \
  ./scripts/load-test/run-baseline-compose.sh 100 1

FLOW=reservation MAX_VUS=100 FCFS_LIMIT_COUNT=20 \
  ./scripts/load-test/run-baseline-compose.sh 100 1

FLOW=payment MAX_VUS=100 FCFS_LIMIT_COUNT=20 \
  ./scripts/load-test/run-baseline-compose.sh 100 1

FLOW=full MAX_VUS=100 FCFS_LIMIT_COUNT=20 \
  ./scripts/load-test/run-baseline-compose.sh 100 1
```

Waiting-burst diagnostic loop on the VM. Use this to reproduce the FCFS event-open burst against the VM nginx path:

```bash
cd ~/apps/axon

SCENARIO=waiting_burst FLOW=payment NUM_USERS=3000 FCFS_LIMIT_COUNT=400 MAX_VUS=3000 K6_ENTRY_SERVICE_URL=http://127.0.0.1:28080 \
  ./scripts/load-test/run-baseline-compose.sh 3000 1

SCENARIO=waiting_burst FLOW=payment NUM_USERS=3000 FCFS_LIMIT_COUNT=500 MAX_VUS=3000 K6_ENTRY_SERVICE_URL=http://127.0.0.1:28080 \
  ./scripts/load-test/run-baseline-compose.sh 3000 1

SCENARIO=waiting_burst FLOW=payment NUM_USERS=3000 FCFS_LIMIT_COUNT=600 MAX_VUS=3000 K6_ENTRY_SERVICE_URL=http://127.0.0.1:28080 \
  ./scripts/load-test/run-baseline-compose.sh 3000 1
```

The runner also supports selecting the Docker network used by the k6 container:

- `K6_DOCKER_NETWORK=host`: k6 uses the VM host network.
- `K6_DOCKER_NETWORK=axon_axon-network`: k6 joins the Compose bridge network and can call service DNS names such as `entry-service`.

Use these only for diagnosis. Do not mix their numbers as one before/after result.

Nginx path, reservation-only:

```bash
cd ~/apps/axon

K6_DOCKER_NETWORK=host \
SCENARIO=waiting_burst \
FLOW=reservation \
NUM_USERS=3000 \
FCFS_LIMIT_COUNT=600 \
MAX_VUS=3000 \
K6_ENTRY_SERVICE_URL=http://127.0.0.1:28080 \
  ./scripts/load-test/run-baseline-compose.sh 3000 1
```

Host published-port path, payment flow:

```bash
cd ~/apps/axon

K6_DOCKER_NETWORK=host \
SCENARIO=waiting_burst \
FLOW=payment \
NUM_USERS=3000 \
FCFS_LIMIT_COUNT=500 \
MAX_VUS=3000 \
K6_ENTRY_SERVICE_URL=http://127.0.0.1:8081 \
K6_CORE_SERVICE_URL=http://127.0.0.1:8080 \
  ./scripts/load-test/run-baseline-compose.sh 3000 1
```

Docker bridge direct path, payment flow:

```bash
cd ~/apps/axon

K6_DOCKER_NETWORK=axon_axon-network \
SCENARIO=waiting_burst \
FLOW=payment \
NUM_USERS=3000 \
FCFS_LIMIT_COUNT=500 \
MAX_VUS=3000 \
K6_ENTRY_SERVICE_URL=http://entry-service:8081 \
K6_CORE_SERVICE_URL=http://core-service:8080 \
  ./scripts/load-test/run-baseline-compose.sh 3000 1
```

Interpretation boundary for the 2026-07-01 and 2026-07-02 VM diagnostic runs:

- `3000/400`: stable success baseline in latest repeats.
- `3000/500`: latency boundary candidate; domain consistency can succeed while `reservation_duration` p95 approaches or crosses 5s.
- `3000/600`: stress/failure reproduction candidate; repeated runs showed large variance, including `EOF`, nginx `500`, Redis success count `600`, and DB convergence around `588~590` in failure runs.
- `3000/500` through Docker bridge direct reached DB `500/500` in repeated payment-flow runs, but p95 still remained high.
- `3000/500` through the host published port showed high variance: one run could persist only a small subset, while later repeats reached `498~500/500`.
- `3000/600` reservation-only through nginx changed after connection tuning, but nginx `499` and high p95 still appeared around the burst boundary.

Do not treat one run as the capacity number. The useful signal is the boundary shape:

```text
400: stable success candidate
500: latency boundary candidate
600: unstable stress/failure reproduction candidate
```

`499` in the nginx access log means the client closed the connection before nginx returned a response. In this test, it usually means k6 timed out or disconnected while nginx was still waiting for the upstream path to finish.

Do not conclude from this evidence alone that nginx, Docker networking, or Entry application code is the sole root cause. Use OpenTelemetry/Jaeger and metrics to split:

- k6 connection wait
- nginx request time and upstream response time
- Docker host/bridge path
- Entry controller/service time
- Redis Lua latency
- reservation token handling
- Kafka publish latency
- Core consumer and DB persistence

When the 600 run fails, collect logs and resource snapshots before changing code:

```bash
docker logs --since=2m axon-nginx 2>&1 | tail -120
docker logs --since=2m axon-entry 2>&1 | tail -160
docker logs --since=2m axon-core 2>&1 | tail -160
docker stats --no-stream axon-nginx axon-entry axon-core axon-mysql axon-redis broker_1 kafka-controller
ss -s
```

If `entry-service:8081` direct traffic succeeds but `axon-nginx:80` traffic fails, verify nginx first:

```bash
docker exec axon-nginx nginx -T | grep -E 'worker_connections|multi_accept|upstream|keepalive|proxy_http_version|proxy_pass' -n
docker logs --since=2m axon-nginx 2>&1 | grep -Ei 'worker_connections|upstream|connect|timeout|reset|refused|failed|500'
```

After changing nginx connection settings, retest nginx-only reservation flow before returning to payment flow:

```bash
SCENARIO=waiting_burst FLOW=reservation NUM_USERS=3000 FCFS_LIMIT_COUNT=600 MAX_VUS=3000 K6_ENTRY_SERVICE_URL=http://127.0.0.1:28080 \
  ./scripts/load-test/run-baseline-compose.sh 3000 1
```

Flow boundary:

- `behavior`: sends `PAGE_VIEW` and `CLICK` behavior events only.
- `reservation`: executes FCFS reservation only and does not confirm payment.
- `payment`: executes reservation and payment prepare/confirm, without behavior events.
- `full`: executes behavior events, reservation, and payment.

Optional overrides:

```bash
MAX_VUS=3000 FCFS_LIMIT_COUNT=200 PRODUCT_ID=1 \
RESOURCE_PROFILE=compose.resources.yml \
  ./scripts/load-test/run-baseline-compose.sh 3000 1
```

## What The Script Captures

The run creates:

- `artifacts/load-test/<run-id>-compose-baseline/k6-summary.json`
- `artifacts/load-test/<run-id>-compose-baseline/k6-console.log`
- `artifacts/load-test/<run-id>-compose-baseline/domain-check.log`
- `artifacts/load-test/<run-id>-compose-baseline/docker-stats.txt`
- `artifacts/load-test/<run-id>-compose-baseline/docker-stats-timeseries.txt`
- `artifacts/load-test/<run-id>-compose-baseline/docker-ps.txt`
- `artifacts/load-test/<run-id>-compose-baseline/ss-before.txt`
- `artifacts/load-test/<run-id>-compose-baseline/ss-after.txt`
- `artifacts/load-test/<run-id>-compose-baseline/<container>.log`
- `artifacts/load-test/<run-id>-compose-baseline/summary.md`
- `artifacts/load-test/<run-id>-compose-baseline/run-meta.txt`
- `artifacts/load-test/<run-id>-compose-baseline.tar.gz`
- `artifacts/load-test/latest-compose-baseline.tar.gz`
- `artifacts/load-test/latest-compose-baseline.txt`

`summary.md` is an automatically generated first-pass summary. It is not a replacement for engineering analysis; use it to decide which raw files to inspect first.

`run-meta.txt` records the effective route configuration, including `k6_docker_network`, `k6_entry_service_url`, and `k6_core_service_url`. Always check it before comparing two runs.

## Success Criteria

- k6 completes the spike scenario.
- `fcfs_error_count` is `0`.
- `fcfs_success_count` matches `FCFS_LIMIT_COUNT`.
- `interrupted_iterations` is `0`.
- Redis counter/set and DB entry count are checked after the run.
- For `FLOW=payment` and `FLOW=full`, DB entries and purchases match `FCFS_LIMIT_COUNT`.
- For `FLOW=reservation`, DB entries and purchases can remain `0` because payment confirmation and Core purchase persistence are intentionally skipped.
- The result directory contains enough evidence to compare against the next run on the same VM.

Notes:

- `http_req_failed` may be high when `410 Sold Out` is counted as an HTTP failure by k6. Treat the domain check `reservation valid business outcome (200/409/410)` as the main business-success indicator for the reservation-only flow.
- Reusing the same campaign activity after a successful run contaminates results because Redis/DB state already contains winners, retry tokens, and sold-out state. Reset seed data and regenerate tokens before every official run.

## Next Step After Baseline

After one clean baseline, attach OpenTelemetry/Jaeger for diagnosis mode. Do not compare OTel-attached latency directly against the baseline because the agent adds tracing overhead.

Measurement flow:

1. Run baseline with `compose.app.yml + compose.resources.yml`, without OTel.
2. Attach OpenTelemetry/Jaeger only for diagnosis and trace inspection.
3. Apply one bottleneck fix.
4. Run final measurement again with `compose.app.yml + compose.resources.yml`, without OTel.

OTel/Jaeger evidence can explain where time was spent. The headline performance number should come from the OTel-off baseline/final pair.

## 2026-07-15 Follow-up: Stable-Network Final Measurement (Pending)

**Status: pending.** This section is the next execution checklist after the
2026-07-15 external generator network investigation. It is intentionally
separate from the historical 600-VU records above.

### Final Scenario

```text
flow:          payment
shape:         waiting_burst
users:         3000
max VUs:       3000
FCFS limit:    800
VM profile:    Entry/Core/Axon nginx = 1.5 / 1.2 / 0.5 CPU
path:          Mac external direct origin -> host nginx -> axon-nginx -> Entry/Core
protocol:      3 full warm-ups, then 3 measured runs
```

The 2026-07-13 `3000 users / 3000 VUs / FCFS 800` successes are a provisional
historical reference, not the final current-code result. The current-code run
must be repeated from a stable home wired/Wi-Fi connection before T27 uses a
final p95 or RPS.

### Before Running

1. Record the generator network's unloaded and loaded latency/jitter using the
   same method for the entire measurement session. Do not use a café/shared
   Wi-Fi session with multi-second loaded latency as final evidence.
2. On the VM, record the source revision and resource profile, then rebuild and
   recreate the services once. Do not change code, CPU quotas, nginx settings,
   or network between the warm-up and measured runs.

```bash
cd ~/apps/axon
git rev-parse HEAD
grep -E '^(ENTRY_CPUS|CORE_CPUS|NGINX_CPUS)=' .env
docker compose -f compose.app.yml -f compose.resources.yml \
  build entry-service core-service
docker compose -f compose.app.yml -f compose.resources.yml \
  up -d --force-recreate entry-service core-service axon-nginx
```

3. Confirm Entry/Core health and retain the output with the result directory.

### Final Command

Run from the stable Mac network. `K6_EVENT_OUTPUT=true` is required once so the
artifact includes reservation ingress/completion peak data; do not print or
inspect per-request console logs unless the run fails.

```bash
cd /Users/yangnail/dev/projects/skusw/axon

RUN_ID="$(date '+%Y%m%d-%H%M%S')-home-final-payment-3000vu-800"
RESULT_ROOT="$PWD/artifacts/load-test/$RUN_ID"
mkdir -p "$RESULT_ROOT"

# Save the observed unloaded/loaded network values in this directory.
# Use the same network-quality tool/session for all three measured runs.

K6_EVENT_OUTPUT=true \
RUN_ID="$RUN_ID" \
RESULT_ROOT="$RESULT_ROOT" \
./scripts/load-test/run-external-warmed-payment.sh 1
```

### Acceptance Gate

Accept a final measurement only if **all three measured runs** meet every
condition below. Warm-up results are setup data and are never used as headline
numbers.

- `fcfs_success_count = 800`, `fcfs_error_count = 0`
- Redis counter/users = `800/800`
- DB entries/purchases = `800/800`; convergence seconds retained
- Host nginx reservation records = `3000`, status `200=800`, `410=2200`
- Axon nginx reservation completion records = `3000`, status `200=800`,
  `410=2200`
- No host nginx `worker_connections`, upstream timeout/connect/reset signal
- Same Git SHA, VM CPU profile, host nginx configuration, and generator
  network session across the three measured runs

Use the median of the three measured runs for each final p95/RPS value. Keep
the metrics separate:

- client E2E `reservation_duration` p95
- k6 `http_req_duration` p95
- host nginx request/upstream timing p95
- Axon nginx reservation completion peak RPS
- reservation ingress peak RPS (from the k6 event artifact)

### Home Measurement Checkpoints (Pending)

Default runtime conditions:

- VM: Entry `1.5 CPU`, Core `1.2 CPU`, Axon nginx `0.5 CPU`
- Axon nginx container: `worker_connections 2048`, `multi_accept` off
- External path only, host nginx: `worker_connections 4096`,
  `worker_rlimit_nofile 16384`; upstream keepalive is off (the `256` trial was
  reverted after Tomcat `400` responses)

For each row: fix the Git SHA and every condition other than the listed
comparison variable; run 3 warm-ups and 3 measured runs.

Historical change order: Entry CPU/cache/publish diagnosis and executor work
(2026-07-06 to 08) -> host nginx connection-limit fix (2026-07-10 05:07 UTC)
-> Axon nginx CPU follow-up (2026-07-13). The host nginx decision below is a
completed prerequisite, not a new A/B row.

| Checkpoint | Compare | Scenario / path |
|---|---|---|
| Entry CPU | `ENTRY_CPUS=0.6` vs `1.5` | reservation, 3000 users / 600 VUs / FCFS 600, Axon nginx path |
| Campaign meta preload | `PRELOAD_CAMPAIGN_META=false` vs `true` | reservation, 3000 users / 600 VUs / FCFS 600, Axon nginx path |
| Backend event executor | pre-executor Git checkpoint vs current | reservation, 3000 users / 600 VUs / FCFS 600, Axon nginx path |
| Axon nginx CPU | `NGINX_CPUS=0.1` vs `0.5` | payment, 2000 users / 2000 VUs / FCFS 500, Axon nginx path |
| Final event-open result | current code/config | payment, 3000 users / 3000 VUs / FCFS 800, Mac external direct origin |

Completed nginx decisions, retained as diagnosis history rather than new A/B tests:

| Layer | Decision | Evidence / disposition |
|---|---|---|
| Host nginx | `worker_connections 768 -> 4096`, `worker_rlimit_nofile 16384` | Error log recorded `768 worker_connections are not enough while connecting to upstream`. Keep the fixed limit; do not deliberately recreate the failing live configuration. |
| Axon nginx | `worker_connections 8192 + multi_accept` trial | Rejected: it increased burst pressure on Entry. Current tracked configuration remains `worker_connections 2048`. |
| Axon nginx | CPU quota `0.1 -> 0.5` | Reproducible A/B checkpoint above. |
| Host nginx -> Axon nginx | upstream `keepalive 256` | Rejected: this environment produced Tomcat `400 Bad Request` responses. Keep upstream keepalive off. |

### How to Use These Results

Do not present rows with different VU counts, FCFS limits, or request paths as
one before/after result. Use two separate claims:

1. **Cumulative improvement benchmark:** reproduce one fixed internal scenario
   (`reservation`, 3000 users / 600 VUs / FCFS 600) at a pre-improvement
   checkpoint and at the current checkpoint. This is the only result allowed
   to state cumulative p95, error-rate, or throughput improvement.
2. **Event-open capacity validation:** current code under `payment`, 3000
   users / 3000 VUs / FCFS 800. Report it as a final all-success capacity
   result, not as a percentage comparison with the first benchmark.

The individual rows above explain *why* the cumulative benchmark changed.
They are per-change evidence only and must retain their own scenario labels.

For the T27 diagnosis narrative, do not remeasure every historical experiment.
Use a number only when that intervention has a same-condition repeated A/B
result. Otherwise retain the direct evidence (for example, the host nginx
connection-saturation error), the decision, and its current configuration.
Remeasure an individual intervention only when it will be a portfolio headline.

After a passing final run, update T27 with the three-run median and link the
result directory. Move this section to a dated completed record or mark it
implemented; retain the 2026-07-15 boundary devlog as history.

## 2026-07-30 Core Kafka-Native Backlog A/B

Status: completed

This comparison answers a Core durability-boundary question. It is not a
headline ingress performance benchmark.

Fixed conditions:

- External Mac payment path
- 1,000 users / 1,000 VUs / FCFS 800
- Entry/Core/Axon nginx CPU: `1.5/1.2/0.5`
- Host nginx: `worker_connections 4096`,
  `worker_rlimit_nofile 16384`, no upstream `keepalive 256`, no host listen
  backlog override
- Axon nginx container: `worker_connections 2048`,
  `listen 80 backlog=4096`; this uncommitted VM setting was held constant and
  was not independently validated by this A/B

| Result | Before `49ca09f` | After `d5f396b` |
|---|---:|---:|
| Measured FCFS success/errors | `800/0`, `800/0` | `800/0`, `800/0` |
| DB Entry/Purchase | `800/800`, `800/800` | `800/800`, `800/800` |
| DB convergence | `0s`, `0s` | `0s`, `0s` |
| Hikari active scrape peak | `3` | `2` |
| Hikari pending/timeout | `0/0` | `0/0` |
| Reservation p95 | `3129ms`, `2723ms` | `4125ms`, `2547ms` |
| HTTP p95 | `1637ms`, `1701ms` | `1884ms`, `1904ms` |

The latency samples overlap and do not support a speedup claim. The accepted
result is that the same 800-result workload converged without application
queues or DB-pool pressure.

Crash injection:

- Core stopped: Kafka group lag `800`, DB `0/0`.
- Abrupt kill after partial work: DB `360/360`, committed offset progress
  `340`; 20 durable records remained uncommitted.
- Restart: Entry/Purchase `800/800`, distinct users `800/800`, group lag `0`.

Evidence:

- `artifacts/load-test/20260730-152209-queue-before-main49ca-controlled`
- `artifacts/load-test/20260730-153943-queue-after-d5f396b-controlled`
- `artifacts/load-test/20260730-queue-crash-injection-d5f396b/result.md`

## 2026-08-03 FCFS Ledger Transaction Regression

Status: completed

Purpose:

- Validate the Entry–Purchase single-transaction boundary after removing the
  former batch transaction-event/`REQUIRES_NEW` chain.
- Confirm that UserSummary projection failure history and Kafka offset
  boundaries do not cause loss or duplicate ledger rows.
- This is a correctness/capacity regression, not a latency improvement A/B.

Runtime:

- Code: `bb34774`
- Oracle VM Docker Compose
- Entry/Core/Axon nginx CPU: `1.5/1.2/0.5`
- External payment `waiting_burst`

### Repeated results

| Shape | Runs | FCFS success/errors | Entry/Purchase | DB convergence | reservation p95 | host request p95 | Axon completion peak |
|---|---:|---|---|---|---|---|---|
| 1,000 users / 1,000 VUs / FCFS 800 | 3 | all `800/0` | all `800/800` | all `0s` | `2.253s / 6.046s / 1.907s` | `1.664s / 5.240s / 1.306s` | `239 / 284 / 268 req/s` |
| 3,000 users / 3,000 VUs / FCFS 800 | 3 | all `800/0` | all `800/800` | all `0s` | `5.120s / 5.704s / 4.573s` | `1.777s / 2.510s / 1.311s` | `817 / 898 / 1,067 req/s` |

Final state after the repeated runs:

- broker committed lag: `0`
- command DLT end offset: `0`
- UserSummary projection-failure topic end offset: `0`
- Entry/Purchase pairs: `800`
- UserSummary timestamps matching durable Purchase timestamps: `800`
- Hikari active/pending/timeout at final snapshot: `0/0/0`

Latency boundary:

- Current host-to-Axon and Axon-to-Entry TCP connect p95 stayed in the
  single-digit millisecond range.
- Most server-side reservation time was upstream header/response wait inside
  the Entry path.
- The generator was on a non-final external network and the 1,000-VU second run
  was a large latency outlier. Do not use these p95 values as a portfolio
  speedup claim.
- The accepted result is three consecutive loss-free 3,000-VU/800
  convergences on the current code.

### Crash injection

- Core stopped: command lag `800`, DB Entry/Purchase `0/0`.
- Core started and was terminated with `SIGKILL` after the first ledger batch
  committed: Entry/Purchase `20/20`, committed offset progress `0`.
- Restart redelivered those 20 durable-but-uncommitted messages.
- Final Entry/Purchase, distinct users, ledger pairs, and matching UserSummary
  timestamps were all `800`; committed lag was `0`.
- Command DLT and projection-failure offsets remained `0`.

Evidence:

- `artifacts/load-test/20260803-fcfs-ledger-regression-1000vu-800`
- `artifacts/load-test/20260803-fcfs-ledger-regression-1000vu-800-r2`
- `artifacts/load-test/20260803-fcfs-ledger-regression-1000vu-800-r3`
- `artifacts/load-test/20260803-fcfs-ledger-3000vu-800-r1`
- `artifacts/load-test/20260803-fcfs-ledger-3000vu-800-r2`
- `artifacts/load-test/20260803-fcfs-ledger-3000vu-800-r3`
- `artifacts/load-test/20260803-fcfs-ledger-crash-recovery/result.md`
