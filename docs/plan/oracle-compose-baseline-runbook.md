# Oracle Compose Baseline Runbook

Status: active

## Purpose

Run a reproducible FCFS purchase-path baseline on the Oracle VM Docker Compose environment.

This runbook is separate from the older k8s-era load-test scripts and previous KT Cloud K2P results. Do not compare Oracle VM numbers directly with K2P numbers.

## Source Boundary

Use these scripts for the current Oracle VM Compose baseline:

- `scripts/load-test/prepare-load-test-compose.sh`
- `scripts/load-test/run-baseline-compose.sh`
- `scripts/load-test/check-results-compose.sh`

Keep these existing scripts as historical/k8s reproduction references:

- `scripts/load-test/prepare-load-test.sh`
- `scripts/load-test/check-results.sh`
- `scripts/load-test/monitor-load-test.sh`

## Baseline Assumptions

- Run on the VM where Axon Compose containers are running.
- Core health is `UP`: `http://127.0.0.1:8080/actuator/health`
- Entry health is `UP`: `http://127.0.0.1:8081/actuator/health`
- MySQL is exposed on `127.0.0.1:3306`.
- Redis container name is `axon-redis`.
- k6 is installed on the machine running the baseline.
- Official before/after baseline runs use `compose.app.yml` plus `compose.resources.yml`.

If k6 runs on the same VM, its CPU and network usage are part of the measured environment. For cleaner server-capacity numbers, run k6 from a separate client machine and keep the same target URL.

## Resource Profile

Use the same resource profile for baseline and final measurement.

Default `compose.resources.yml` limits:

| Service | CPU | Memory | JVM heap |
|---|---:|---:|---|
| core-service | 1.5 | 3GiB | `-Xms512m -Xmx2g` |
| entry-service | 1.0 | 2GiB | `-Xms256m -Xmx1536m` |
| mysql | 1.0 | 4GiB | n/a |
| kafka broker | 1.0 | 4GiB | image default |
| kafka controller | 0.5 | 1GiB | image default |
| redis | 0.5 | 1GiB | n/a |
| axon-nginx | 0.25 | 256MiB | n/a |

This profile leaves host headroom for OS cache, Docker overhead, SSH/GitHub Actions, and k6 when k6 runs on the same VM. Do not compare runs that use different resource profiles as a single before/after result.

## First Baseline Command

Preferred path: use GitHub Actions manual execution.

- Actions workflow: `Run VM Compose Baseline`
- Execution mode: GitHub Actions connects to the VM through SSH, but k6 runs inside the VM.
- Result retrieval: the workflow downloads `latest-compose-baseline.tar.gz` from the VM and uploads it as a GitHub Actions artifact named `vm-compose-baseline`.
- `use_resource_profile=true`: deploy with `compose.app.yml + compose.resources.yml`.
- `use_resource_profile=false`: deploy with `compose.app.yml` only. Use this only for A/B checks against the older unlimited Compose shape.
- `redeploy=true`: pull `main`, rebuild/restart Compose, then run k6.
- `redeploy=false`: skip rebuild and run k6 against the currently running containers.

Manual VM fallback:

```bash
cd ~/apps/axon
docker compose -f compose.app.yml -f compose.resources.yml up -d --build
./scripts/load-test/run-baseline-compose.sh 1000 1
```

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
- Redis counter/set and DB entry count are checked after the run.
- The result directory contains enough evidence to compare against the next run on the same VM.

## Next Step After Baseline

After one clean baseline, attach Pinpoint for diagnosis mode. Do not compare Pinpoint-attached latency directly against the baseline because the agent adds tracing overhead.

Measurement flow:

1. Run baseline with `compose.app.yml + compose.resources.yml`, without Pinpoint.
2. Attach Pinpoint only for diagnosis and trace inspection.
3. Apply one bottleneck fix.
4. Run final measurement again with `compose.app.yml + compose.resources.yml`, without Pinpoint.

Pinpoint evidence can explain where time was spent. The headline performance number should come from the Pinpoint-off baseline/final pair.
