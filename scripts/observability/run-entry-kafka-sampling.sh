#!/usr/bin/env bash

# Run a Compose baseline while sampling Entry actuator metrics.
# Intended for VM diagnostics, not for headline portfolio measurements.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

NUM_USERS="${1:-3000}"
ACTIVITY_ID="${2:-1}"
SAMPLE_SECONDS="${SAMPLE_SECONDS:-35}"
SAMPLE_INTERVAL_SECONDS="${SAMPLE_INTERVAL_SECONDS:-1}"
ENTRY_ACTUATOR_URL="${ENTRY_ACTUATOR_URL:-http://127.0.0.1:8081/actuator/prometheus}"
NGINX_ENTRY_URL="${NGINX_ENTRY_URL:-http://127.0.0.1:28080/api/v1/entries}"

RUN_ID="$(date '+%Y%m%d-%H%M%S')-entry-kafka-sampling"
RESULT_ROOT="${RESULT_ROOT:-$PROJECT_ROOT/artifacts/load-test/$RUN_ID-compose-baseline}"
BASELINE_RESULT_DIR="$RESULT_ROOT"

mkdir -p "$RESULT_ROOT"

echo "Entry Kafka sampling run: $RUN_ID"
echo "Result root: $RESULT_ROOT"

docker restart axon-nginx >/dev/null

PROBE_CODE="000"
for attempt in $(seq 1 10); do
  PROBE_CODE="$(curl -sS -o "$RESULT_ROOT/nginx-entry-probe.out" -w "%{http_code}" \
    -X POST "$NGINX_ENTRY_URL" \
    -H "Content-Type: application/json" \
    -d "{}" || true)"
  echo "attempt=$attempt nginx_entry_probe_http=$PROBE_CODE" | tee -a "$RESULT_ROOT/nginx-entry-probe.log"
  if [ "$PROBE_CODE" = "403" ]; then
    break
  fi
  sleep 1
done

if [ "$PROBE_CODE" != "403" ]; then
  echo "nginx probe failed. Expected 403 from Spring Security before running load test." >&2
  exit 2
fi

sample_metrics() {
  local sample_count="$((SAMPLE_SECONDS / SAMPLE_INTERVAL_SECONDS))"
  for i in $(seq 1 "$sample_count"); do
    echo "===== sample=$i time=$(date -Iseconds) ====="
    curl -fsS "$ENTRY_ACTUATOR_URL" | grep -Ei \
      "spring_kafka_template_seconds|executor_|axon_entry_diagnostic_stage_seconds_(count|sum)|process_cpu_usage|jvm_threads_states_threads|tomcat_threads|http_server_requests_seconds_(count|sum|max)" \
      || true
    sleep "$SAMPLE_INTERVAL_SECONDS"
  done
}

sample_metrics > "$RESULT_ROOT/entry-actuator-timeseries.txt" 2>&1 &
SAMPLER_PID=$!

set +e
RESULT_DIR="$BASELINE_RESULT_DIR" "$PROJECT_ROOT/scripts/load-test/run-baseline-compose.sh" "$NUM_USERS" "$ACTIVITY_ID" \
  > "$RESULT_ROOT/baseline-wrapper.log" 2>&1
BASELINE_STATUS=$?
set -e

kill "$SAMPLER_PID" 2>/dev/null || true
wait "$SAMPLER_PID" 2>/dev/null || true

{
  echo "run_id=$RUN_ID"
  echo "baseline_status=$BASELINE_STATUS"
  echo "result_root=$RESULT_ROOT"
  echo "baseline_result_dir=$BASELINE_RESULT_DIR"
  echo "sample_seconds=$SAMPLE_SECONDS"
  echo "sample_interval_seconds=$SAMPLE_INTERVAL_SECONDS"
  echo "entry_actuator_url=$ENTRY_ACTUATOR_URL"
  echo "nginx_entry_url=$NGINX_ENTRY_URL"
} > "$RESULT_ROOT/sampling-meta.txt"

echo
echo "== baseline tail =="
tail -80 "$RESULT_ROOT/baseline-wrapper.log"

echo
echo "== selected actuator metric tail =="
grep -E "spring_kafka_template_seconds_(count|sum)|executor_active_threads|executor_queued_tasks|executor_queue_remaining_tasks|process_cpu_usage" \
  "$RESULT_ROOT/entry-actuator-timeseries.txt" | tail -80 || true

echo
echo "Sampling artifacts saved to: $RESULT_ROOT"
exit "$BASELINE_STATUS"
