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
./scripts/load-test/run-baseline-compose.sh 1000 1
```

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
- `artifacts/load-test/<run-id>-compose-baseline/docker-ps.txt`
- `artifacts/load-test/<run-id>-compose-baseline/summary.md`
- `artifacts/load-test/<run-id>-compose-baseline/run-meta.txt`
- `artifacts/load-test/<run-id>-compose-baseline.tar.gz`
- `artifacts/load-test/latest-compose-baseline.tar.gz`
- `artifacts/load-test/latest-compose-baseline.txt`

`summary.md` is an automatically generated first-pass summary. It is not a replacement for engineering analysis; use it to decide which raw files to inspect first.

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

After one clean baseline, attach Pinpoint for diagnosis mode. Do not compare Pinpoint-attached latency directly against the baseline because the agent adds tracing overhead.

Measurement flow:

1. Run baseline with `compose.app.yml + compose.resources.yml`, without Pinpoint.
2. Attach Pinpoint only for diagnosis and trace inspection.
3. Apply one bottleneck fix.
4. Run final measurement again with `compose.app.yml + compose.resources.yml`, without Pinpoint.

Pinpoint evidence can explain where time was spent. The headline performance number should come from the Pinpoint-off baseline/final pair.
