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
  -X POST http://127.0.0.1:28080/entry/api/v1/entries \
  -H "Content-Type: application/json" \
  -d "{}"
```

Expected unauthenticated probe result:

```text
nginx_entry_http=403
```

`403` means nginx reached Entry and Spring Security rejected the unauthenticated request. That is a valid path check. `502`, `499`, or `connect() failed ... upstream` means do not run the load test yet.

Warm-run before/after loop. Use this when comparing code or config changes and the first post-deploy cold run would hide the real signal:

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
