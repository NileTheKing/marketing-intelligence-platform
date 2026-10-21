#!/bin/bash

# Run the Oracle VM Docker Compose baseline from an external client machine.
# This script orchestrates:
# 1. prepare seed/JWT on the VM
# 2. copy JWT tokens to this machine
# 3. run k6 from this machine against the public URL
# 4. verify Redis/MySQL results on the VM

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

NUM_USERS="${NUM_USERS:-${1:-1000}}"
ACTIVITY_ID="${ACTIVITY_ID:-${2:-1}}"
MAX_VUS="${MAX_VUS:-100}"
SCENARIO="${SCENARIO:-spike}"
PRELOAD_CAMPAIGN_META="${PRELOAD_CAMPAIGN_META:-true}" # warm campaign meta cache on the VM before the measured burst
FLOW="${FLOW:-payment}"
FCFS_LIMIT_COUNT="${FCFS_LIMIT_COUNT:-200}"
ARRIVAL_PRE_ALLOCATED_VUS="${ARRIVAL_PRE_ALLOCATED_VUS:-200}"
ARRIVAL_RATE_MULTIPLIER="${ARRIVAL_RATE_MULTIPLIER:-1}"
PRODUCT_ID="${PRODUCT_ID:-1}"
USER_ID_START="${USER_ID_START:-1000}"
USER_ID_END="${USER_ID_END:-$((USER_ID_START + NUM_USERS - 1))}"

VM_HOST="${VM_HOST:-ubuntu@134.185.100.15}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/oci_arm_key}"
REMOTE_DIR="${REMOTE_DIR:-/home/ubuntu/apps/axon}"
PUBLIC_URL="${PUBLIC_URL:-https://axon.opicnic.xyz}"
VIRTUAL_HOST="${VIRTUAL_HOST:-}"
REACTION_MODE="${REACTION_MODE:-random}"
K6_INSECURE_SKIP_TLS_VERIFY="${K6_INSECURE_SKIP_TLS_VERIFY:-false}"
K6_LOG_OUTPUT="${K6_LOG_OUTPUT:-none}"
K6_EVENT_OUTPUT="${K6_EVENT_OUTPUT:-false}"
PREPARE_IDLE_TIMEOUT_SECONDS="${PREPARE_IDLE_TIMEOUT_SECONDS:-90}"
PREPARE_IDLE_STABLE_SAMPLES="${PREPARE_IDLE_STABLE_SAMPLES:-5}"

RUN_ID="${RUN_ID:-$(date '+%Y%m%d-%H%M%S')-external-compose-baseline}"
if [[ ! "$RUN_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]]; then
  echo "RUN_ID may contain only letters, digits, dots, underscores, and hyphens." >&2
  exit 2
fi
RESULT_DIR="${RESULT_DIR:-$PROJECT_ROOT/artifacts/load-test/$RUN_ID}"
TOKEN_FILE="$SCRIPT_DIR/jwt-tokens.json"
REMOTE_TOKEN_FILE="$REMOTE_DIR/scripts/load-test/jwt-tokens.json"
SUMMARY_FILE="$RESULT_DIR/k6-summary.json"
CONSOLE_LOG="$RESULT_DIR/k6-console.log"
EVENT_LOG="$RESULT_DIR/k6-events.json"
PEAK_SUMMARY="$RESULT_DIR/reservation-request-peak.md"
NGINX_ACCESS_LOG="$RESULT_DIR/axon-nginx-access.log"
NGINX_PEAK_SUMMARY="$RESULT_DIR/axon-nginx-reservation-peak.md"
HOST_NGINX_ACCESS_LOG="$RESULT_DIR/host-nginx-access.log"
HOST_NGINX_ERROR_LOG="$RESULT_DIR/host-nginx-error.log"
HOST_NGINX_SUMMARY="$RESULT_DIR/host-nginx-reservation-summary.md"
DOMAIN_CHECK_LOG="$RESULT_DIR/domain-check.log"
REMOTE_CHECK_SCRIPT="$RESULT_DIR/domain-check-remote.sh"
REMOTE_CHECK_PATH="/tmp/axon-domain-check-$RUN_ID.sh"
VM_RUNTIME_SAMPLES="$RESULT_DIR/vm-runtime-samples.csv"
ENTRY_ACTUATOR_SAMPLES="$RESULT_DIR/entry-actuator-samples.prom"
CORE_ACTUATOR_SAMPLES="$RESULT_DIR/core-actuator-samples.prom"
HOST_PROCESS_SAMPLES="$RESULT_DIR/host-process-samples.txt"
ALL_CONTAINER_SAMPLES="$RESULT_DIR/all-container-samples.txt"
CGROUP_CPU_BEFORE="$RESULT_DIR/container-cpu-before-k6.csv"
CGROUP_CPU_AFTER="$RESULT_DIR/container-cpu-after-idle.csv"
CGROUP_CPU_SUMMARY="$RESULT_DIR/container-cpu-throttling.md"
TCP_INGRESS_BEFORE="$RESULT_DIR/tcp-ingress-before-k6.txt"
TCP_INGRESS_AFTER="$RESULT_DIR/tcp-ingress-after-idle.txt"
PAYMENT_FUNNEL_SUMMARY="$RESULT_DIR/payment-funnel.md"
POST_K6_IDLE_LOG="$RESULT_DIR/core-post-k6-idle.log"
LOCAL_SAMPLER_SCRIPT=""
REMOTE_SAMPLER_DIR=""
REMOTE_SAMPLER_PID=""
VM_SAMPLER_STARTED=false

mkdir -p "$RESULT_DIR"

ssh_vm() {
  ssh -i "$SSH_KEY" "$VM_HOST" "$@"
}

wait_for_core_idle() {
  local stable_samples=0
  local attempt=0
  local snapshot status summary
  local max_attempts=$((PREPARE_IDLE_TIMEOUT_SECONDS / 2))

  echo "== Preflight: wait for Core Kafka consumers to become idle =="
  echo "Requires zero lag and zero pending Hikari threads for $PREPARE_IDLE_STABLE_SAMPLES consecutive samples."

  while [ "$attempt" -lt "$max_attempts" ]; do
    attempt=$((attempt + 1))
    snapshot="$(ssh_vm "curl -fsS http://127.0.0.1:8080/actuator/prometheus" 2>/dev/null || true)"

    set +e
    summary="$(printf '%s\n' "$snapshot" | awk '
      /^kafka_consumer_fetch_manager_records_lag\{/ {
        lag_series++
        if ($NF != "0" && $NF != "0.0") nonzero_lag++
      }
      /^hikaricp_connections_pending\{/ {
        pending_series++
        if ($NF != "0" && $NF != "0.0") nonzero_pending++
      }
      END {
        printf "lag_series=%d nonzero_lag=%d pending_series=%d nonzero_pending=%d", lag_series, nonzero_lag, pending_series, nonzero_pending
        if (lag_series == 0 || pending_series == 0 || nonzero_lag > 0 || nonzero_pending > 0) exit 1
      }
    ')"
    status=$?
    set -e

    if [ "$status" -eq 0 ]; then
      stable_samples=$((stable_samples + 1))
      echo "preflight sample $attempt/$max_attempts: idle ($stable_samples/$PREPARE_IDLE_STABLE_SAMPLES), $summary"
      if [ "$stable_samples" -ge "$PREPARE_IDLE_STABLE_SAMPLES" ]; then
        return 0
      fi
    else
      stable_samples=0
      echo "preflight sample $attempt/$max_attempts: not idle, $summary"
    fi

    sleep 2
  done

  echo "Timed out waiting for Core Kafka consumers to drain. Refusing to reset load-test data." >&2
  return 1
}

capture_actuator_metrics() {
  local phase="$1"

  if ! ssh_vm "curl -fsS http://127.0.0.1:8081/actuator/prometheus" \
    > "$RESULT_DIR/entry-${phase}.prom"; then
    echo "Warning: failed to capture Entry metrics for ${phase}" >&2
  fi

  if ! ssh_vm "curl -fsS http://127.0.0.1:8080/actuator/prometheus" \
    > "$RESULT_DIR/core-${phase}.prom"; then
    echo "Warning: failed to capture Core metrics for ${phase}" >&2
  fi
}

capture_cgroup_cpu_stats() {
  local output="$1"
  local containers=(
    axon-entry axon-core axon-nginx broker_1 kafka-controller axon-mysql
    axon-redis axon-alloy axon-loki axon-prometheus axon-jaeger
  )

  printf 'container,source,usage,nr_periods,nr_throttled,throttled\n' > "$output"
  for container in "${containers[@]}"; do
    local stats source usage periods throttled throttled_time
    stats="$(ssh_vm "docker exec '$container' sh -c 'cat /sys/fs/cgroup/cpu.stat 2>/dev/null || cat /sys/fs/cgroup/cpu/cpu.stat 2>/dev/null || true'" 2>/dev/null || true)"

    if printf '%s\n' "$stats" | grep -q '^usage_usec '; then
      source="cgroup-v2-usec"
      usage="$(printf '%s\n' "$stats" | awk '$1 == "usage_usec" { print $2 }')"
      periods="$(printf '%s\n' "$stats" | awk '$1 == "nr_periods" { print $2 }')"
      throttled="$(printf '%s\n' "$stats" | awk '$1 == "nr_throttled" { print $2 }')"
      throttled_time="$(printf '%s\n' "$stats" | awk '$1 == "throttled_usec" { print $2 }')"
    elif printf '%s\n' "$stats" | grep -q '^nr_periods '; then
      source="cgroup-v1-nsec"
      usage="n/a"
      periods="$(printf '%s\n' "$stats" | awk '$1 == "nr_periods" { print $2 }')"
      throttled="$(printf '%s\n' "$stats" | awk '$1 == "nr_throttled" { print $2 }')"
      throttled_time="$(printf '%s\n' "$stats" | awk '$1 == "throttled_time" { print $2 }')"
    else
      source="unavailable"
      usage="n/a"
      periods="n/a"
      throttled="n/a"
      throttled_time="n/a"
    fi

    printf '%s,%s,%s,%s,%s,%s\n' "$container" "$source" "${usage:-n/a}" \
      "${periods:-n/a}" "${throttled:-n/a}" "${throttled_time:-n/a}" >> "$output"
  done
}

capture_tcp_ingress_stats() {
  local output="$1"

  # Keep raw kernel counters so a run can prove whether the public listener
  # dropped connections, rather than inferring it from cumulative host state.
  ssh_vm "printf 'captured_at='; date -u +%Y-%m-%dT%H:%M:%SZ; \
    sysctl net.core.somaxconn net.ipv4.tcp_max_syn_backlog; \
    ss -ltn 'sport = :443'; \
    awk '/^TcpExt:/ { print; getline; print; exit }' /proc/net/netstat" > "$output" || {
      echo "Warning: failed to capture TCP ingress counters" >&2
    }
}

create_vm_sampler_script() {
  LOCAL_SAMPLER_SCRIPT="$(mktemp "${TMPDIR:-/tmp}/axon-vm-sampler.XXXXXX")"
  cat > "$LOCAL_SAMPLER_SCRIPT" <<'REMOTE_SAMPLER'
#!/usr/bin/env bash
set -u

output="$1"
entry_output="$2"
core_output="$3"
host_process_output="$4"
all_container_output="$5"
containers=(
  axon-entry axon-core axon-nginx broker_1 kafka-controller axon-mysql
  axon-redis axon-alloy axon-loki axon-prometheus axon-jaeger
)
stop_requested=false
trap 'stop_requested=true' TERM INT HUP

printf 'utc_timestamp,cpu_pressure_some_raw,cpu_pressure_full_raw' > "$output"
for container in "${containers[@]}"; do
  printf ',%s_cpu_percent,%s_memory_usage_limit' "$container" "$container" >> "$output"
done
printf '\n' >> "$output"

# Self-expire if the controller loses SSH before it can stop this sampler.
deadline=$((SECONDS + 900))
while [ "$stop_requested" != true ] && [ "$SECONDS" -lt "$deadline" ]; do
  timestamp="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  pressure_some="$(awk '$1 == "some" { sub(/^some /, ""); print; found=1 } END { if (!found) print "missing" }' /proc/pressure/cpu 2>/dev/null)"
  pressure_full="$(awk '$1 == "full" { sub(/^full /, ""); print; found=1 } END { if (!found) print "missing" }' /proc/pressure/cpu 2>/dev/null)"
  stats="$(docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}' 2>/dev/null || true)"
  docker_values="$(printf '%s\n' "$stats" | awk -F'|' -v names='axon-entry axon-core axon-nginx broker_1 kafka-controller axon-mysql axon-redis axon-alloy axon-loki axon-prometheus axon-jaeger' '
    BEGIN {
      count = split(names, order, " ")
      for (i = 1; i <= count; i++) {
        cpu[order[i]] = "missing"
        memory[order[i]] = "missing"
      }
    }
    NF >= 3 && ($1 in cpu) {
      cpu[$1] = $2
      memory[$1] = $3
    }
    END {
      for (i = 1; i <= count; i++) printf ",%s,%s", cpu[order[i]], memory[order[i]]
    }
  ')"
  printf '%s,%s,%s%s\n' "$timestamp" "$pressure_some" "$pressure_full" "$docker_values" >> "$output"

  {
    printf '# timestamp=%s\n' "$timestamp"
    printf '%s\n' "$stats" | sort -t '|' -k2 -r
  } >> "$all_container_output"

  {
    printf '# timestamp=%s\n' "$timestamp"
    awk '/^cpu / { print "cpu " $0 }' /proc/stat
    ps -eo pid,ppid,comm,%cpu,%mem --sort=-%cpu | head -n 16
  } >> "$host_process_output"

  {
    printf '# timestamp=%s\n' "$timestamp"
    curl -fsS http://127.0.0.1:8081/actuator/prometheus 2>/dev/null | grep -E \
      '^(process_cpu_usage|jvm_gc_pause_seconds_(count|sum)|executor_(active_threads|queued_tasks)|spring_kafka_template_seconds_(count|sum|max)|axon_entry_diagnostic_stage_seconds_(count|sum))' || true
  } >> "$entry_output"

  {
    printf '# timestamp=%s\n' "$timestamp"
    curl -fsS http://127.0.0.1:8080/actuator/prometheus 2>/dev/null | grep -E \
      '^(process_cpu_usage|jvm_gc_pause_seconds_(count|sum)|executor_(active_threads|queued_tasks)|hikaricp_connections_(active|idle|pending)|kafka_consumer_fetch_manager_records_lag\{|spring_kafka_listener_seconds_(count|sum|max))' || true
  } >> "$core_output"
  sleep 1
done
REMOTE_SAMPLER
}

start_vm_sampler() {
  create_vm_sampler_script
  REMOTE_SAMPLER_DIR="$(ssh_vm "mktemp -d /tmp/axon-vm-runtime.XXXXXX")"
  if [[ ! "$REMOTE_SAMPLER_DIR" =~ ^/tmp/axon-vm-runtime\.[A-Za-z0-9]+$ ]]; then
    echo "Unexpected remote sampler directory; refusing to use it." >&2
    return 1
  fi
  VM_SAMPLER_STARTED=true

  scp -i "$SSH_KEY" "$LOCAL_SAMPLER_SCRIPT" "$VM_HOST:$REMOTE_SAMPLER_DIR/sampler.sh" >/dev/null
  REMOTE_SAMPLER_PID="$(ssh_vm "nohup bash '$REMOTE_SAMPLER_DIR/sampler.sh' '$REMOTE_SAMPLER_DIR/samples.csv' '$REMOTE_SAMPLER_DIR/entry.prom' '$REMOTE_SAMPLER_DIR/core.prom' '$REMOTE_SAMPLER_DIR/host-processes.txt' '$REMOTE_SAMPLER_DIR/all-containers.txt' >'$REMOTE_SAMPLER_DIR/sampler.log' 2>&1 </dev/null & sampler_pid=\$!; printf '%s\\n' \"\$sampler_pid\" >'$REMOTE_SAMPLER_DIR/sampler.pid'; printf '%s\\n' \"\$sampler_pid\"")"
  if [[ ! "$REMOTE_SAMPLER_PID" =~ ^[0-9]+$ ]]; then
    echo "Remote VM sampler did not return a valid process ID." >&2
    return 1
  fi
}

finalize_vm_sampler() {
  [ "$VM_SAMPLER_STARTED" = true ] || return 0

  local remote_csv="$REMOTE_SAMPLER_DIR/samples.csv"
  local remote_entry="$REMOTE_SAMPLER_DIR/entry.prom"
  local remote_core="$REMOTE_SAMPLER_DIR/core.prom"
  local remote_host_processes="$REMOTE_SAMPLER_DIR/host-processes.txt"
  local remote_all_containers="$REMOTE_SAMPLER_DIR/all-containers.txt"
  local remote_pid_file="$REMOTE_SAMPLER_DIR/sampler.pid"

  set +e
  ssh_vm "if [ -f '$remote_pid_file' ]; then sampler_pid=\$(cat '$remote_pid_file'); case \$sampler_pid in ''|*[!0-9]*) ;; *) if [ -r /proc/\$sampler_pid/cmdline ] && tr '\\000' ' ' </proc/\$sampler_pid/cmdline | grep -Fq -- '$REMOTE_SAMPLER_DIR/sampler.sh'; then kill -TERM \$sampler_pid 2>/dev/null || true; for attempt in 1 2 3 4 5; do if [ ! -r /proc/\$sampler_pid/cmdline ] || ! tr '\\000' ' ' </proc/\$sampler_pid/cmdline | grep -Fq -- '$REMOTE_SAMPLER_DIR/sampler.sh'; then break; fi; sleep 1; done; if [ -r /proc/\$sampler_pid/cmdline ] && tr '\\000' ' ' </proc/\$sampler_pid/cmdline | grep -Fq -- '$REMOTE_SAMPLER_DIR/sampler.sh'; then kill -KILL \$sampler_pid 2>/dev/null || true; fi; fi ;; esac; fi" >/dev/null 2>&1
  if ! scp -i "$SSH_KEY" "$VM_HOST:$remote_csv" "$VM_RUNTIME_SAMPLES" >/dev/null 2>&1; then
    echo "Warning: could not copy VM runtime samples to $VM_RUNTIME_SAMPLES" >&2
  fi
  if ! scp -i "$SSH_KEY" "$VM_HOST:$remote_entry" "$ENTRY_ACTUATOR_SAMPLES" >/dev/null 2>&1; then
    echo "Warning: could not copy Entry actuator samples to $ENTRY_ACTUATOR_SAMPLES" >&2
  fi
  if ! scp -i "$SSH_KEY" "$VM_HOST:$remote_core" "$CORE_ACTUATOR_SAMPLES" >/dev/null 2>&1; then
    echo "Warning: could not copy Core actuator samples to $CORE_ACTUATOR_SAMPLES" >&2
  fi
  if ! scp -i "$SSH_KEY" "$VM_HOST:$remote_host_processes" "$HOST_PROCESS_SAMPLES" >/dev/null 2>&1; then
    echo "Warning: could not copy host process samples to $HOST_PROCESS_SAMPLES" >&2
  fi
  if ! scp -i "$SSH_KEY" "$VM_HOST:$remote_all_containers" "$ALL_CONTAINER_SAMPLES" >/dev/null 2>&1; then
    echo "Warning: could not copy all container samples to $ALL_CONTAINER_SAMPLES" >&2
  fi
  ssh_vm "rm -f '$REMOTE_SAMPLER_DIR/sampler.sh' '$remote_csv' '$remote_entry' '$remote_core' '$remote_host_processes' '$remote_all_containers' '$remote_pid_file' '$REMOTE_SAMPLER_DIR/sampler.log'; rmdir '$REMOTE_SAMPLER_DIR' 2>/dev/null || true" >/dev/null 2>&1
  VM_SAMPLER_STARTED=false
  set -e
}

runner_exit() {
  local status="$1"
  trap - EXIT INT TERM HUP
  finalize_vm_sampler
  if [ -n "$LOCAL_SAMPLER_SCRIPT" ]; then
    rm -f "$LOCAL_SAMPLER_SCRIPT"
  fi
  exit "$status"
}

trap 'runner_exit $?' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

count_tokens() {
  python3 - "$1" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)

print(len(data))
PY
}

capture_runtime_metadata() {
  local remote_git_sha remote_git_dirty_count
  local entry_image_id core_image_id nginx_image_id
  local entry_cpus core_cpus nginx_cpus

  remote_git_sha="$(ssh_vm "cd '$REMOTE_DIR' && git rev-parse HEAD" 2>/dev/null || echo unavailable)"
  remote_git_dirty_count="$(ssh_vm "cd '$REMOTE_DIR' && git status --porcelain | wc -l" 2>/dev/null | tr -d ' ' || echo unavailable)"
  entry_image_id="$(ssh_vm "docker inspect --format='{{.Image}}' axon-entry" 2>/dev/null || echo unavailable)"
  core_image_id="$(ssh_vm "docker inspect --format='{{.Image}}' axon-core" 2>/dev/null || echo unavailable)"
  nginx_image_id="$(ssh_vm "docker inspect --format='{{.Image}}' axon-nginx" 2>/dev/null || echo unavailable)"
  entry_cpus="$(ssh_vm "cd '$REMOTE_DIR' && grep '^ENTRY_CPUS=' .env | tail -n 1 | cut -d= -f2-" 2>/dev/null || echo unavailable)"
  core_cpus="$(ssh_vm "cd '$REMOTE_DIR' && grep '^CORE_CPUS=' .env | tail -n 1 | cut -d= -f2-" 2>/dev/null || echo unavailable)"
  nginx_cpus="$(ssh_vm "cd '$REMOTE_DIR' && grep '^NGINX_CPUS=' .env | tail -n 1 | cut -d= -f2-" 2>/dev/null || echo unavailable)"

  cat >> "$RESULT_DIR/run-meta.txt" <<EOF
remote_git_sha=$remote_git_sha
remote_git_dirty_count=$remote_git_dirty_count
entry_image_id=$entry_image_id
core_image_id=$core_image_id
nginx_image_id=$nginx_image_id
entry_cpus=$entry_cpus
core_cpus=$core_cpus
nginx_cpus=$nginx_cpus
EOF
}

echo "External compose baseline: $RUN_ID"
echo "VM:        $VM_HOST"
echo "Remote:    $REMOTE_DIR"
echo "Public URL: $PUBLIC_URL"
if [ -n "$VIRTUAL_HOST" ]; then
  echo "Virtual Host: $VIRTUAL_HOST"
fi
echo "Flow:      $FLOW"
echo "Users:     $NUM_USERS"
echo "Max VUs:   $MAX_VUS"
echo "Results:   $RESULT_DIR"

cat > "$RESULT_DIR/run-meta.txt" <<EOF
run_id=$RUN_ID
vm_host=$VM_HOST
remote_dir=$REMOTE_DIR
public_url=$PUBLIC_URL
virtual_host=$VIRTUAL_HOST
reaction_mode=$REACTION_MODE
k6_insecure_skip_tls_verify=$K6_INSECURE_SKIP_TLS_VERIFY
k6_log_output=$K6_LOG_OUTPUT
k6_event_output=$K6_EVENT_OUTPUT
num_users=$NUM_USERS
activity_id=$ACTIVITY_ID
max_vus=$MAX_VUS
flow=$FLOW
scenario=$SCENARIO
fcfs_limit_count=$FCFS_LIMIT_COUNT
arrival_pre_allocated_vus=$ARRIVAL_PRE_ALLOCATED_VUS
arrival_rate_multiplier=$ARRIVAL_RATE_MULTIPLIER
product_id=$PRODUCT_ID
user_id_start=$USER_ID_START
user_id_end=$USER_ID_END
started_at=$(date -Iseconds)
local_host=$(hostname)
EOF

echo ""
echo "== Step 1/4: prepare VM seed and JWT tokens =="
wait_for_core_idle | tee "$RESULT_DIR/core-preflight.log"
ssh_vm "cd '$REMOTE_DIR' && PRELOAD_CAMPAIGN_META='$PRELOAD_CAMPAIGN_META' FCFS_LIMIT_COUNT='$FCFS_LIMIT_COUNT' PRODUCT_ID='$PRODUCT_ID' ./scripts/load-test/prepare-load-test-compose.sh '$NUM_USERS' '$ACTIVITY_ID'"
capture_runtime_metadata

REMOTE_TOKEN_COUNT="$(ssh_vm "cd '$REMOTE_DIR' && python3 -c 'import json; j=json.load(open(\"scripts/load-test/jwt-tokens.json\")); print(len(j))'")"
echo "Remote JWT tokens: $REMOTE_TOKEN_COUNT / $NUM_USERS"
if [ "$REMOTE_TOKEN_COUNT" -ne "$NUM_USERS" ]; then
  echo "JWT token generation failed on VM"
  exit 1
fi

echo ""
echo "== Step 2/4: copy JWT token file to local =="
scp -i "$SSH_KEY" "$VM_HOST:$REMOTE_TOKEN_FILE" "$TOKEN_FILE"

LOCAL_TOKEN_COUNT="$(count_tokens "$TOKEN_FILE")"
echo "Local JWT tokens: $LOCAL_TOKEN_COUNT / $NUM_USERS"
if [ "$LOCAL_TOKEN_COUNT" -ne "$NUM_USERS" ]; then
  echo "JWT token copy or local parse failed"
  exit 1
fi

echo ""
echo "== Step 3/4: run external k6 =="
capture_actuator_metrics "before-k6"
capture_cgroup_cpu_stats "$CGROUP_CPU_BEFORE" || \
  echo "Warning: could not capture pre-run cgroup CPU stats." >&2
capture_tcp_ingress_stats "$TCP_INGRESS_BEFORE" || \
  echo "Warning: could not capture pre-run TCP ingress stats." >&2
echo "Starting one-second VM runtime sampler..."
if ! start_vm_sampler; then
  # The load test remains useful without the optional high-frequency sampler.
  # Do not lose the k6 and before/after snapshots when sampler setup fails.
  echo "Warning: VM runtime sampler unavailable; continuing without one-second samples." >&2
  VM_SAMPLER_STARTED=false
fi
K6_STARTED_AT="$(ssh_vm "date -u +%Y-%m-%dT%H:%M:%SZ")"
set +e
if [ "$K6_INSECURE_SKIP_TLS_VERIFY" = "true" ]; then
  K6_TLS_FLAG="--insecure-skip-tls-verify"
else
  K6_TLS_FLAG=""
fi
run_k6() {
  FLOW="$FLOW" \
  SCENARIO="$SCENARIO" \
  REACTION_MODE="$REACTION_MODE" \
  VIRTUAL_HOST="$VIRTUAL_HOST" \
  MAX_VUS="$MAX_VUS" \
  ARRIVAL_PRE_ALLOCATED_VUS="$ARRIVAL_PRE_ALLOCATED_VUS" \
  ARRIVAL_RATE_MULTIPLIER="$ARRIVAL_RATE_MULTIPLIER" \
  USE_PRODUCTION_API=true \
  USE_TOKEN_FILE=true \
  TOKEN_FILE_PATH="$TOKEN_FILE" \
  ENTRY_SERVICE_URL="$PUBLIC_URL" \
  CORE_SERVICE_URL="$PUBLIC_URL" \
  ACTIVITY_ID="$ACTIVITY_ID" \
  PRODUCT_ID="$PRODUCT_ID" \
  FCFS_LIMIT_COUNT="$FCFS_LIMIT_COUNT" \
  USER_ID_START="$USER_ID_START" \
  USER_ID_END="$USER_ID_END" \
    k6 run --log-output "$K6_LOG_OUTPUT" $K6_TLS_FLAG "$@" --summary-export "$SUMMARY_FILE" "$SCRIPT_DIR/k6-fcfs-load-test.js"
}

if [ "$K6_EVENT_OUTPUT" = "true" ]; then
  run_k6 --out "json=$EVENT_LOG" 2>&1 | tee "$CONSOLE_LOG"
else
  run_k6 2>&1 | tee "$CONSOLE_LOG"
fi
K6_STATUS=${PIPESTATUS[0]}
# A failed k6 threshold is a measurement result, not a harness failure. Keep
# collecting the post-run snapshots so overload runs remain diagnosable.
set +e
capture_actuator_metrics "after-k6"
wait_for_core_idle | tee "$POST_K6_IDLE_LOG"
POST_K6_IDLE_STATUS=${PIPESTATUS[0]}
capture_cgroup_cpu_stats "$CGROUP_CPU_AFTER" || \
  echo "Warning: could not capture post-run cgroup CPU stats." >&2
capture_tcp_ingress_stats "$TCP_INGRESS_AFTER" || \
  echo "Warning: could not capture post-run TCP ingress stats." >&2
python3 "$SCRIPT_DIR/summarize-cgroup-cpu-stat.py" \
  "$CGROUP_CPU_BEFORE" "$CGROUP_CPU_AFTER" "$CGROUP_CPU_SUMMARY"

if [ "$K6_EVENT_OUTPUT" = "true" ] && [ -f "$EVENT_LOG" ]; then
  python3 "$SCRIPT_DIR/summarize-k6-request-peaks.py" "$EVENT_LOG" "$PEAK_SUMMARY"
fi
K6_FINISHED_AT="$(ssh_vm "date -u +%Y-%m-%dT%H:%M:%SZ")"
ssh_vm "docker logs --since '$K6_STARTED_AT' --until '$K6_FINISHED_AT' axon-nginx 2>&1" > "$NGINX_ACCESS_LOG" || true
python3 "$SCRIPT_DIR/summarize-nginx-reservation-peak.py" "$NGINX_ACCESS_LOG" "$NGINX_PEAK_SUMMARY"

# Host nginx is outside the Compose stack. Persist only this run's interval so
# its ingress status and upstream timings can be compared with axon-nginx.
HOST_LOG_START="${K6_STARTED_AT%Z}"
HOST_LOG_END="${K6_FINISHED_AT%Z}"
ssh_vm "sudo awk -v start='$HOST_LOG_START' -v end='$HOST_LOG_END' 'substr(\$1, 1, 19) >= start && substr(\$1, 1, 19) <= end { print }' /var/log/nginx/axon_timing.log" \
  > "$HOST_NGINX_ACCESS_LOG" || true
HOST_ERROR_START="$(ssh_vm "date -u -d '$K6_STARTED_AT' '+%Y/%m/%d %H:%M:%S'")"
HOST_ERROR_END="$(ssh_vm "date -u -d '$K6_FINISHED_AT' '+%Y/%m/%d %H:%M:%S'")"
ssh_vm "sudo awk -v start='$HOST_ERROR_START' -v end='$HOST_ERROR_END' 'substr(\$0, 1, 19) >= start && substr(\$0, 1, 19) <= end { print }' /var/log/nginx/error.log" \
  > "$HOST_NGINX_ERROR_LOG" || true
python3 "$SCRIPT_DIR/summarize-host-nginx-reservation.py" \
  "$HOST_NGINX_ACCESS_LOG" "$HOST_NGINX_ERROR_LOG" "$HOST_NGINX_SUMMARY"

echo ""
echo "== Step 4/4: verify VM Redis/MySQL results =="
cat > "$REMOTE_CHECK_SCRIPT" <<'REMOTE_CHECK'
#!/bin/bash
set -euo pipefail

REDIS_PASSWORD="$(grep '^REDIS_PASSWORD=' .env | cut -d= -f2-)"
REDIS_COUNTER="$(docker exec axon-redis redis-cli -a "$REDIS_PASSWORD" GET "campaign:${ACTIVITY_ID}:counter" 2>/dev/null | tr -d '\r\n')"
REDIS_USERS="$(docker exec axon-redis redis-cli -a "$REDIS_PASSWORD" SCARD "campaign:${ACTIVITY_ID}:users" 2>/dev/null | tr -d '\r\n')"

MYSQL_USER="$(docker exec axon-mysql printenv MYSQL_USER)"
MYSQL_PASSWORD="$(docker exec axon-mysql printenv MYSQL_PASSWORD)"
MYSQL_DATABASE="$(docker exec axon-mysql printenv MYSQL_DATABASE)"

mysql_count() {
  docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" axon-mysql \
    mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" -N -s -e "$1"
}

DB_ENTRIES="$(mysql_count "SELECT COUNT(*) FROM campaign_activity_entries WHERE campaign_activity_id = ${ACTIVITY_ID};")"
DB_PURCHASES="$(mysql_count "SELECT COUNT(*) FROM purchases WHERE campaign_activity_id = ${ACTIVITY_ID};")"
ENTRY_CONVERGENCE_SECONDS="n/a"
PURCHASE_CONVERGENCE_SECONDS="n/a"

if [ "$FLOW" != "reservation" ]; then
  for second in $(seq 0 30); do
    if [ "$ENTRY_CONVERGENCE_SECONDS" = "n/a" ] && [ "$DB_ENTRIES" -ge "$FCFS_LIMIT_COUNT" ]; then
      ENTRY_CONVERGENCE_SECONDS="$second"
    fi
    if [ "$PURCHASE_CONVERGENCE_SECONDS" = "n/a" ] && [ "$DB_PURCHASES" -ge "$FCFS_LIMIT_COUNT" ]; then
      PURCHASE_CONVERGENCE_SECONDS="$second"
    fi
    if [ "$ENTRY_CONVERGENCE_SECONDS" != "n/a" ] && [ "$PURCHASE_CONVERGENCE_SECONDS" != "n/a" ]; then
      break
    fi
    if [ "$second" -eq 30 ]; then
      break
    fi
    sleep 1
    DB_ENTRIES="$(mysql_count "SELECT COUNT(*) FROM campaign_activity_entries WHERE campaign_activity_id = ${ACTIVITY_ID};")"
    DB_PURCHASES="$(mysql_count "SELECT COUNT(*) FROM purchases WHERE campaign_activity_id = ${ACTIVITY_ID};")"
  done
fi

echo "Redis counter: $REDIS_COUNTER"
echo "Redis users:   $REDIS_USERS"
echo "DB entries:    $DB_ENTRIES"
echo "DB purchases:  $DB_PURCHASES"
echo "Entries convergence seconds:   $ENTRY_CONVERGENCE_SECONDS"
echo "Purchases convergence seconds: $PURCHASE_CONVERGENCE_SECONDS"
echo "Flow:          $FLOW"

if [ "$REDIS_COUNTER" != "$FCFS_LIMIT_COUNT" ] || [ "$REDIS_USERS" != "$FCFS_LIMIT_COUNT" ]; then
  echo "Domain check failed: Redis count mismatch"
  exit 1
fi

if [ "$FLOW" != "reservation" ]; then
  if [ "$DB_ENTRIES" != "$FCFS_LIMIT_COUNT" ] || [ "$DB_PURCHASES" != "$FCFS_LIMIT_COUNT" ]; then
    echo "Domain check failed: DB count mismatch"
    exit 1
  fi
fi
REMOTE_CHECK

scp -i "$SSH_KEY" "$REMOTE_CHECK_SCRIPT" "$VM_HOST:$REMOTE_CHECK_PATH" >/dev/null
set +e
ssh_vm "cd '$REMOTE_DIR' && ACTIVITY_ID='$ACTIVITY_ID' FCFS_LIMIT_COUNT='$FCFS_LIMIT_COUNT' FLOW='$FLOW' bash '$REMOTE_CHECK_PATH'" | tee "$DOMAIN_CHECK_LOG"
DOMAIN_STATUS=${PIPESTATUS[0]}
set -e
ssh_vm "rm -f '$REMOTE_CHECK_PATH'" >/dev/null 2>&1 || true
capture_actuator_metrics "after-domain-check"
finalize_vm_sampler
if [ "$FLOW" = "payment" ]; then
  python3 "$SCRIPT_DIR/summarize-payment-funnel.py" \
    "$SUMMARY_FILE" "$DOMAIN_CHECK_LOG" "$PAYMENT_FUNNEL_SUMMARY" "$POST_K6_IDLE_STATUS"
fi

cat > "$RESULT_DIR/summary.md" <<EOF
# External Compose Baseline Summary

- Run ID: \`$RUN_ID\`
- Flow: \`$FLOW\`
- Scenario: \`$SCENARIO\`
- Public URL: \`$PUBLIC_URL\`
- Virtual Host: \`${VIRTUAL_HOST:-none}\`
- Reaction mode: \`$REACTION_MODE\`
- Users: \`$NUM_USERS\`
- Max VUs: \`$MAX_VUS\`
- Arrival pre-allocated VUs: \`$ARRIVAL_PRE_ALLOCATED_VUS\`
- Arrival rate multiplier: \`$ARRIVAL_RATE_MULTIPLIER\`
- Activity ID: \`$ACTIVITY_ID\`
- FCFS limit count: \`$FCFS_LIMIT_COUNT\`
- k6 status: \`$K6_STATUS\`
- Core idle after k6: \`$POST_K6_IDLE_STATUS\`
- domain check status: \`$DOMAIN_STATUS\`
- Entry/Core Prometheus snapshots: \`before-k6\`, \`after-domain-check\`
- VM runtime samples: \`$( [ -f "$VM_RUNTIME_SAMPLES" ] && echo "vm-runtime-samples.csv" || echo "not captured" )\`
- Entry/Core one-second samples: \`$( [ -f "$ENTRY_ACTUATOR_SAMPLES" ] && echo "captured" || echo "not captured" )\`
- Host and all-container CPU samples: \`$( [ -f "$HOST_PROCESS_SAMPLES" ] && echo "captured" || echo "not captured" )\`
- Container CPU throttling: \`$( [ -f "$CGROUP_CPU_SUMMARY" ] && echo "container-cpu-throttling.md" || echo "not captured" )\`
- TCP ingress counters: \`$( [ -f "$TCP_INGRESS_BEFORE" ] && [ -f "$TCP_INGRESS_AFTER" ] && echo "captured" || echo "not captured" )\`
- Payment funnel: \`$( [ -f "$PAYMENT_FUNNEL_SUMMARY" ] && echo "payment-funnel.md" || echo "not applicable" )\`
- Reservation request peak: \`$( [ -f "$PEAK_SUMMARY" ] && echo "reservation-request-peak.md" || echo "not captured" )\`
- axon-nginx reservation completion peak: \`axon-nginx-reservation-peak.md\`
- host nginx reservation ingress: \`host-nginx-reservation-summary.md\`

## Domain Check

\`\`\`text
$(cat "$DOMAIN_CHECK_LOG")
\`\`\`

## k6 Console Tail

\`\`\`text
$(tail -80 "$CONSOLE_LOG")
\`\`\`
EOF

if [ -f "$PEAK_SUMMARY" ]; then
  cat >> "$RESULT_DIR/summary.md" <<EOF

## Reservation Request Peak

$(cat "$PEAK_SUMMARY")
EOF
fi

cat >> "$RESULT_DIR/summary.md" <<EOF

## axon-nginx Reservation Completion Peak

$(cat "$NGINX_PEAK_SUMMARY")
EOF

cat >> "$RESULT_DIR/summary.md" <<EOF

## Host nginx Reservation Ingress

$(cat "$HOST_NGINX_SUMMARY")
EOF

if [ -f "$SUMMARY_FILE" ]; then
  python3 - "$SUMMARY_FILE" >> "$RESULT_DIR/summary.md" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)

metrics = data.get("metrics", {})

def metric(name, key):
    item = metrics.get(name, {})
    value = item.get(key)
    return "n/a" if value is None else value

print()
print("## k6 Key Metrics")
print()
for name, key in [
    ("http_req_duration", "avg"),
    ("http_req_duration", "p(95)"),
    ("http_req_failed", "rate"),
    ("http_reqs", "rate"),
    ("iterations", "rate"),
    ("iteration_duration", "p(95)"),
    ("reservation_duration", "p(95)"),
    ("fcfs_success_count", "count"),
    ("fcfs_error_count", "count"),
    ("fcfs_sold_out_count", "count"),
]:
    print(f"- `{name}.{key}`: `{metric(name, key)}`")
PY
fi

echo ""
echo "Artifacts saved to: $RESULT_DIR"

if [ "$K6_STATUS" -ne 0 ] || [ "$DOMAIN_STATUS" -ne 0 ] || [ "$POST_K6_IDLE_STATUS" -ne 0 ]; then
  exit 1
fi
