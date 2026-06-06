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

If k6 runs on the same VM, its CPU and network usage are part of the measured environment. For cleaner server-capacity numbers, run k6 from a separate client machine and keep the same target URL.

## First Baseline Command

```bash
cd ~/apps/axon
./scripts/load-test/run-baseline-compose.sh 1000 1
```

Optional overrides:

```bash
MAX_VUS=3000 FCFS_LIMIT_COUNT=200 PRODUCT_ID=1 \
  ./scripts/load-test/run-baseline-compose.sh 3000 1
```

## What The Script Captures

The run creates:

- `artifacts/load-test/<run-id>-compose-baseline/k6-summary.json`
- `artifacts/load-test/<run-id>-compose-baseline/k6-console.log`
- `artifacts/load-test/<run-id>-compose-baseline/domain-check.log`
- `artifacts/load-test/<run-id>-compose-baseline/docker-stats.txt`
- `artifacts/load-test/<run-id>-compose-baseline/docker-ps.txt`

## Success Criteria

- k6 completes the spike scenario.
- `fcfs_error_count` is `0`.
- `fcfs_success_count` matches `FCFS_LIMIT_COUNT`.
- Redis counter/set and DB entry count are checked after the run.
- The result directory contains enough evidence to compare against the next run on the same VM.

## Next Step After Baseline

After one clean baseline, attach Pinpoint for diagnosis mode. Do not compare Pinpoint-attached latency directly against the baseline because the agent adds tracing overhead.
