#!/usr/bin/env bash

# Run a small hot-path warm-up, reset test data, then run measured baselines.
# Use this when comparing code/config changes where cold-start variance would
# hide the actual before/after signal.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

ACTIVITY_ID="${1:-1}"
RUN_ID="$(date '+%Y%m%d-%H%M%S')-warm-baseline"
RESULT_ROOT="${RESULT_ROOT:-$PROJECT_ROOT/artifacts/load-test/$RUN_ID}"

WARMUP_NUM_USERS="${WARMUP_NUM_USERS:-50}"
WARMUP_MAX_VUS="${WARMUP_MAX_VUS:-5}"
WARMUP_FCFS_LIMIT_COUNT="${WARMUP_FCFS_LIMIT_COUNT:-50}"
MEASURED_NUM_USERS="${MEASURED_NUM_USERS:-3000}"
MEASURED_MAX_VUS="${MEASURED_MAX_VUS:-600}"
MEASURED_FCFS_LIMIT_COUNT="${MEASURED_FCFS_LIMIT_COUNT:-600}"
MEASURED_RUNS="${MEASURED_RUNS:-2}"

SCENARIO="${SCENARIO:-waiting_burst}"
FLOW="${FLOW:-reservation}"
PRELOAD_CAMPAIGN_META="${PRELOAD_CAMPAIGN_META:-true}"
K6_ENTRY_SERVICE_URL="${K6_ENTRY_SERVICE_URL:-http://127.0.0.1:28080}"
K6_CORE_SERVICE_URL="${K6_CORE_SERVICE_URL:-http://127.0.0.1:8080}"
NGINX_ENTRY_URL="${NGINX_ENTRY_URL:-http://127.0.0.1:28080/entry/api/v1/entries}"

mkdir -p "$RESULT_ROOT"

probe_nginx_entry() {
  docker restart axon-nginx >/dev/null

  local code="000"
  for attempt in $(seq 1 20); do
    code="$(curl -sS -o "$RESULT_ROOT/nginx-entry-probe.out" -w "%{http_code}" \
      -X POST "$NGINX_ENTRY_URL" \
      -H "Content-Type: application/json" \
      -d "{}" || true)"
    echo "attempt=$attempt nginx_entry_probe_http=$code" | tee -a "$RESULT_ROOT/nginx-entry-probe.log"
    if [ "$code" = "403" ]; then
      return 0
    fi
    sleep 1
  done

  echo "nginx probe failed. Expected 403 from Spring Security before running load test." >&2
  return 1
}

run_baseline() {
  local phase="$1"
  local num_users="$2"
  local max_vus="$3"
  local fcfs_limit="$4"
  local result_dir="$RESULT_ROOT/$phase"

  mkdir -p "$result_dir"
  echo "== $phase: users=$num_users max_vus=$max_vus fcfs_limit=$fcfs_limit =="

  set +e
  SCENARIO="$SCENARIO" \
  FLOW="$FLOW" \
  NUM_USERS="$num_users" \
  MAX_VUS="$max_vus" \
  FCFS_LIMIT_COUNT="$fcfs_limit" \
  PRELOAD_CAMPAIGN_META="$PRELOAD_CAMPAIGN_META" \
  K6_ENTRY_SERVICE_URL="$K6_ENTRY_SERVICE_URL" \
  K6_CORE_SERVICE_URL="$K6_CORE_SERVICE_URL" \
  RESULT_DIR="$result_dir" \
    "$SCRIPT_DIR/run-baseline-compose.sh" "$num_users" "$ACTIVITY_ID" > "$result_dir/wrapper.log" 2>&1
  local status=$?
  set -e

  echo "$status" > "$result_dir/status.txt"
  summarize_run "$phase" "$status" "$result_dir"
}

json_metric() {
  local file="$1"
  local name="$2"
  local key="$3"
  python3 - "$file" "$name" "$key" <<'PY'
import json
import sys

path, name, key = sys.argv[1:]
try:
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    print(data.get("metrics", {}).get(name, {}).get(key, "n/a"))
except Exception:
    print("n/a")
PY
}

summarize_run() {
  local phase="$1"
  local status="$2"
  local result_dir="$3"
  local summary="$result_dir/k6-summary.json"

  local success error reservation_p95 http_p95 http_reqs
  success="$(json_metric "$summary" fcfs_success_count count)"
  error="$(json_metric "$summary" fcfs_error_count count)"
  reservation_p95="$(json_metric "$summary" reservation_duration "p(95)")"
  http_p95="$(json_metric "$summary" http_req_duration "p(95)")"
  http_reqs="$(json_metric "$summary" http_reqs rate)"

  local line
  line="|$phase|$status|$success|$error|$reservation_p95|$http_p95|$http_reqs|$result_dir|"
  echo "$line" | tee -a "$RESULT_ROOT/result-table.md"
}

{
  echo "# Warm Baseline Result"
  echo
  echo "|phase|status|success|error|reservation_p95_ms|http_p95_ms|http_reqs_s|artifact|"
  echo "|---|---:|---:|---:|---:|---:|---:|---|"
} > "$RESULT_ROOT/result-table.md"

cat > "$RESULT_ROOT/run-meta.txt" <<EOF
run_id=$RUN_ID
activity_id=$ACTIVITY_ID
scenario=$SCENARIO
flow=$FLOW
preload_campaign_meta=$PRELOAD_CAMPAIGN_META
warmup_num_users=$WARMUP_NUM_USERS
warmup_max_vus=$WARMUP_MAX_VUS
warmup_fcfs_limit_count=$WARMUP_FCFS_LIMIT_COUNT
measured_num_users=$MEASURED_NUM_USERS
measured_max_vus=$MEASURED_MAX_VUS
measured_fcfs_limit_count=$MEASURED_FCFS_LIMIT_COUNT
measured_runs=$MEASURED_RUNS
k6_entry_service_url=$K6_ENTRY_SERVICE_URL
k6_core_service_url=$K6_CORE_SERVICE_URL
EOF

echo "Warm baseline run: $RUN_ID"
echo "Result root: $RESULT_ROOT"

probe_nginx_entry

run_baseline "warmup" "$WARMUP_NUM_USERS" "$WARMUP_MAX_VUS" "$WARMUP_FCFS_LIMIT_COUNT" || true

for run_number in $(seq 1 "$MEASURED_RUNS"); do
  run_baseline "measured-$run_number" "$MEASURED_NUM_USERS" "$MEASURED_MAX_VUS" "$MEASURED_FCFS_LIMIT_COUNT" || true
done

echo
cat "$RESULT_ROOT/result-table.md"
echo
echo "Warm baseline artifacts saved to: $RESULT_ROOT"
