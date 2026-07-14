#!/usr/bin/env bash

# Fixed-protocol external payment measurement.
# Three full-size warm-up runs are never used as results. The following three
# runs are the measurement set; each run resets test data but keeps JVM state.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

ACTIVITY_ID="${1:-1}"
NUM_USERS="${NUM_USERS:-3000}"
FCFS_LIMIT_COUNT="${FCFS_LIMIT_COUNT:-800}"
MAX_VUS="${MAX_VUS:-3000}"
WARMUP_RUNS="${WARMUP_RUNS:-3}"
MEASURED_RUNS="${MEASURED_RUNS:-3}"

PUBLIC_URL="${PUBLIC_URL:-https://134.185.100.15}"
VIRTUAL_HOST="${VIRTUAL_HOST:-axon.opicnic.xyz}"
K6_INSECURE_SKIP_TLS_VERIFY="${K6_INSECURE_SKIP_TLS_VERIFY:-true}"
REACTION_MODE="${REACTION_MODE:-deterministic}"
EXPECTED_ENTRY_CPUS="${EXPECTED_ENTRY_CPUS:-1.5}"
EXPECTED_CORE_CPUS="${EXPECTED_CORE_CPUS:-1.2}"
EXPECTED_NGINX_CPUS="${EXPECTED_NGINX_CPUS:-0.1}"
VM_HOST="${VM_HOST:-ubuntu@134.185.100.15}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/oci_arm_key}"
REMOTE_DIR="${REMOTE_DIR:-/home/ubuntu/apps/axon}"

RUN_ID="${RUN_ID:-$(date '+%Y%m%d-%H%M%S')-external-warmed-payment}"
RESULT_ROOT="${RESULT_ROOT:-$PROJECT_ROOT/artifacts/load-test/$RUN_ID}"
TABLE_FILE="$RESULT_ROOT/result-table.md"

mkdir -p "$RESULT_ROOT"

if ! [[ "$WARMUP_RUNS" =~ ^[1-9][0-9]*$ ]] || ! [[ "$MEASURED_RUNS" =~ ^[1-9][0-9]*$ ]]; then
  echo "WARMUP_RUNS and MEASURED_RUNS must both be positive integers." >&2
  exit 2
fi

remote_env_value() {
  local key="$1"
  ssh -i "$SSH_KEY" "$VM_HOST" "cd '$REMOTE_DIR' && grep '^${key}=' .env | tail -n 1 | cut -d= -f2-"
}

require_profile() {
  local entry core nginx
  entry="$(remote_env_value ENTRY_CPUS)"
  core="$(remote_env_value CORE_CPUS)"
  nginx="$(remote_env_value NGINX_CPUS)"

  if [ "$entry" != "$EXPECTED_ENTRY_CPUS" ] \
    || [ "$core" != "$EXPECTED_CORE_CPUS" ] \
    || [ "$nginx" != "$EXPECTED_NGINX_CPUS" ]; then
    echo "Refusing mixed resource profile." >&2
    echo "Expected entry/core/nginx: $EXPECTED_ENTRY_CPUS/$EXPECTED_CORE_CPUS/$EXPECTED_NGINX_CPUS" >&2
    echo "Actual   entry/core/nginx: ${entry:-missing}/${core:-missing}/${nginx:-missing}" >&2
    exit 2
  fi
}

metric() {
  local summary="$1"
  local name="$2"
  local key="$3"
  python3 - "$summary" "$name" "$key" <<'PY'
import json
import sys

path, name, key = sys.argv[1:]
try:
    with open(path, encoding="utf-8") as source:
        print(json.load(source).get("metrics", {}).get(name, {}).get(key, "n/a"))
except Exception:
    print("n/a")
PY
}

domain_status() {
  if [ ! -f "$1/summary.md" ]; then
    echo "n/a"
    return
  fi
  sed -n 's/^- domain check status: `\([0-9]*\)`/\1/p' "$1/summary.md" | tail -n 1
}

append_result() {
  local phase="$1"
  local status="$2"
  local run_dir="$3"
  local summary="$run_dir/k6-summary.json"
  local domain="$run_dir/domain-check.log"

  local success errors reservation_p95 http_p95 entries purchases
  success="$(metric "$summary" fcfs_success_count count)"
  errors="$(metric "$summary" fcfs_error_count count)"
  reservation_p95="$(metric "$summary" reservation_duration 'p(95)')"
  http_p95="$(metric "$summary" http_req_duration 'p(95)')"
  if [ -f "$domain" ]; then
    entries="$(sed -n 's/^DB entries: *//p' "$domain" | tail -n 1)"
    purchases="$(sed -n 's/^DB purchases: *//p' "$domain" | tail -n 1)"
  else
    entries="n/a"
    purchases="n/a"
  fi

  printf '|%s|%s|%s|%s|%s|%s|%s|%s|\n' \
    "$phase" "$status" "$(domain_status "$run_dir")" "$success" "$errors" \
    "$reservation_p95" "$http_p95" "${entries:-n/a}/${purchases:-n/a}" >> "$TABLE_FILE"
}

run_phase() {
  local phase="$1"
  local run_dir="$RESULT_ROOT/$phase"
  local status

  mkdir -p "$run_dir"
  echo "== $phase =="
  set +e
  RUN_ID="$RUN_ID-$phase" \
  RESULT_DIR="$run_dir" \
  NUM_USERS="$NUM_USERS" \
  FCFS_LIMIT_COUNT="$FCFS_LIMIT_COUNT" \
  MAX_VUS="$MAX_VUS" \
  SCENARIO=waiting_burst \
  FLOW=payment \
  PRELOAD_CAMPAIGN_META=true \
  REACTION_MODE="$REACTION_MODE" \
  PUBLIC_URL="$PUBLIC_URL" \
  VIRTUAL_HOST="$VIRTUAL_HOST" \
  K6_INSECURE_SKIP_TLS_VERIFY="$K6_INSECURE_SKIP_TLS_VERIFY" \
  VM_HOST="$VM_HOST" \
  SSH_KEY="$SSH_KEY" \
  REMOTE_DIR="$REMOTE_DIR" \
    "$SCRIPT_DIR/run-external-compose-baseline.sh" "$NUM_USERS" "$ACTIVITY_ID" \
    > "$run_dir/wrapper.log" 2>&1
  status=$?
  set -e

  printf '%s\n' "$status" > "$run_dir/status.txt"
  append_result "$phase" "$status" "$run_dir"
  return "$status"
}

cat > "$TABLE_FILE" <<EOF
# External Warmed Payment Result

- Shape: payment / waiting_burst / $NUM_USERS users / $MAX_VUS VUs / FCFS $FCFS_LIMIT_COUNT
- Path: $PUBLIC_URL (Host: $VIRTUAL_HOST)
- Reaction mode: $REACTION_MODE
- Resource profile: entry/core/nginx = $EXPECTED_ENTRY_CPUS/$EXPECTED_CORE_CPUS/$EXPECTED_NGINX_CPUS
- Protocol: $WARMUP_RUNS full warm-ups, then $MEASURED_RUNS measured runs

| phase | wrapper status | domain status | FCFS success | FCFS errors | reservation p95 ms | HTTP p95 ms | entries/purchases |
|---|---:|---:|---:|---:|---:|---:|---|
EOF

require_profile

for index in $(seq 1 "$WARMUP_RUNS"); do
  run_phase "warmup-$index" || true
done

if [ "$(domain_status "$RESULT_ROOT/warmup-$WARMUP_RUNS")" != "0" ]; then
  echo "Final warm-up did not converge. Measurement runs were not started." >&2
  cat "$TABLE_FILE"
  exit 1
fi

overall_status=0
for index in $(seq 1 "$MEASURED_RUNS"); do
  run_phase "measured-$index" || overall_status=1
done

echo
cat "$TABLE_FILE"
echo
echo "Artifacts saved to: $RESULT_ROOT"

exit "$overall_status"
