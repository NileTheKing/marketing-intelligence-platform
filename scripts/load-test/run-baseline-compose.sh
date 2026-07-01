#!/bin/bash

# Run the Oracle VM Docker Compose FCFS baseline.
# Assumption: execute on the VM where axon containers and k6 are available.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

load_env_file() {
  local env_file="$1"
  while IFS='=' read -r key value; do
    if [[ -z "$key" || "$key" =~ ^[[:space:]]*# ]]; then
      continue
    fi
    key="$(echo "$key" | xargs)"
    export "$key=$value"
  done < "$env_file"
}

if [ -f "$PROJECT_ROOT/.env" ]; then
  load_env_file "$PROJECT_ROOT/.env"
fi

NUM_USERS="${1:-1000}"
ACTIVITY_ID="${2:-1}"
USER_ID_START="${USER_ID_START:-1000}"
USER_ID_END="${USER_ID_END:-$((USER_ID_START + NUM_USERS - 1))}"
MAX_VUS="${MAX_VUS:-$NUM_USERS}"
SCENARIO="${SCENARIO:-spike}"
FCFS_LIMIT_COUNT="${FCFS_LIMIT_COUNT:-200}"
PRODUCT_ID="${PRODUCT_ID:-1}"
RESOURCE_PROFILE="${RESOURCE_PROFILE:-unlimited-compose-app}"
FLOW="${FLOW:-full}"
PREPARE_CORE_SERVICE_URL="${PREPARE_CORE_SERVICE_URL:-http://127.0.0.1:8080}"
PREPARE_ENTRY_SERVICE_URL="${PREPARE_ENTRY_SERVICE_URL:-http://127.0.0.1:8081}"
K6_ENTRY_SERVICE_URL="${K6_ENTRY_SERVICE_URL:-${ENTRY_SERVICE_URL:-http://127.0.0.1:8081}}"
K6_CORE_SERVICE_URL="${K6_CORE_SERVICE_URL:-${CORE_SERVICE_URL:-http://127.0.0.1:8080}}"

RUN_ID="$(date '+%Y%m%d-%H%M%S')"
RESULT_DIR="${RESULT_DIR:-$PROJECT_ROOT/artifacts/load-test/$RUN_ID-compose-baseline}"
mkdir -p "$RESULT_DIR"
RESULT_ARCHIVE="$PROJECT_ROOT/artifacts/load-test/$RUN_ID-compose-baseline.tar.gz"
LATEST_ARCHIVE="$PROJECT_ROOT/artifacts/load-test/latest-compose-baseline.tar.gz"
LATEST_META="$PROJECT_ROOT/artifacts/load-test/latest-compose-baseline.txt"

TOKEN_FILE="$SCRIPT_DIR/jwt-tokens.json"
COMMIT_SHA="$(git -C "$PROJECT_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
K6_IMAGE="${K6_IMAGE:-grafana/k6:0.53.0}"

echo "Baseline run: $RUN_ID"
echo "Users:        $NUM_USERS"
echo "Activity ID:  $ACTIVITY_ID"
echo "Max VUs:      $MAX_VUS"
echo "Result dir:   $RESULT_DIR"

cat > "$RESULT_DIR/run-meta.txt" <<EOF
run_id=$RUN_ID
commit_sha=$COMMIT_SHA
num_users=$NUM_USERS
user_id_start=$USER_ID_START
user_id_end=$USER_ID_END
activity_id=$ACTIVITY_ID
max_vus=$MAX_VUS
scenario=$SCENARIO
fcfs_limit_count=$FCFS_LIMIT_COUNT
product_id=$PRODUCT_ID
resource_profile=$RESOURCE_PROFILE
flow=$FLOW
prepare_core_service_url=$PREPARE_CORE_SERVICE_URL
prepare_entry_service_url=$PREPARE_ENTRY_SERVICE_URL
k6_entry_service_url=$K6_ENTRY_SERVICE_URL
k6_core_service_url=$K6_CORE_SERVICE_URL
started_at=$(date -Iseconds)
host=$(hostname)
EOF

CORE_SERVICE_URL="$PREPARE_CORE_SERVICE_URL" \
ENTRY_SERVICE_URL="$PREPARE_ENTRY_SERVICE_URL" \
FCFS_LIMIT_COUNT="$FCFS_LIMIT_COUNT" \
PRODUCT_ID="$PRODUCT_ID" \
  "$SCRIPT_DIR/prepare-load-test-compose.sh" "$NUM_USERS" "$ACTIVITY_ID"

set +e
docker run --rm \
  --network host \
  --user "$(id -u):$(id -g)" \
  -e SCENARIO="$SCENARIO" \
  -e ENTRY_SERVICE_URL="$K6_ENTRY_SERVICE_URL" \
  -e CORE_SERVICE_URL="$K6_CORE_SERVICE_URL" \
  -e MAX_VUS="$MAX_VUS" \
  -e ACTIVITY_ID="$ACTIVITY_ID" \
  -e PRODUCT_ID="$PRODUCT_ID" \
  -e FCFS_LIMIT_COUNT="$FCFS_LIMIT_COUNT" \
  -e USER_ID_START="$USER_ID_START" \
  -e USER_ID_END="$USER_ID_END" \
  -e FLOW="$FLOW" \
  -e USE_PRODUCTION_API=true \
  -e TOKEN_FILE_PATH=/scripts/jwt-tokens.json \
  -v "$SCRIPT_DIR:/scripts:ro" \
  -v "$RESULT_DIR:/results" \
  "$K6_IMAGE" run \
  --summary-export /results/k6-summary.json \
  /scripts/k6-fcfs-load-test.js \
  2>&1 | tee "$RESULT_DIR/k6-console.log"
K6_STATUS=${PIPESTATUS[0]}
set -e

FCFS_LIMIT_COUNT="$FCFS_LIMIT_COUNT" "$SCRIPT_DIR/check-results-compose.sh" "$ACTIVITY_ID" | tee "$RESULT_DIR/domain-check.log"

docker stats --no-stream > "$RESULT_DIR/docker-stats.txt"
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" > "$RESULT_DIR/docker-ps.txt"

cat > "$RESULT_DIR/summary.md" <<EOF
# Compose Baseline Summary

- Run ID: \`$RUN_ID\`
- Commit: \`$COMMIT_SHA\`
- Users: \`$NUM_USERS\`
- User ID range: \`$USER_ID_START-$USER_ID_END\`
- Max VUs: \`$MAX_VUS\`
- Scenario: \`$SCENARIO\`
- Activity ID: \`$ACTIVITY_ID\`
- Product ID: \`$PRODUCT_ID\`
- FCFS limit count: \`$FCFS_LIMIT_COUNT\`
- Prepare core URL: \`$PREPARE_CORE_SERVICE_URL\`
- k6 entry URL: \`$K6_ENTRY_SERVICE_URL\`
- k6 core URL: \`$K6_CORE_SERVICE_URL\`
- k6 status: \`$K6_STATUS\`

## Domain Check

\`\`\`text
$(cat "$RESULT_DIR/domain-check.log")
\`\`\`

## k6 Console Tail

\`\`\`text
$(tail -80 "$RESULT_DIR/k6-console.log")
\`\`\`

## Docker Stats

\`\`\`text
$(cat "$RESULT_DIR/docker-stats.txt")
\`\`\`
EOF

if command -v python3 >/dev/null 2>&1 && [ -f "$RESULT_DIR/k6-summary.json" ]; then
  python3 - "$RESULT_DIR/k6-summary.json" >> "$RESULT_DIR/summary.md" <<'PY'
import json
import sys

path = sys.argv[1]
with open(path, "r", encoding="utf-8") as f:
    data = json.load(f)

metrics = data.get("metrics", {})

def metric(name, key="value"):
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
    ("http_reqs", "count"),
    ("iterations", "count"),
    ("fcfs_success_count", "count"),
    ("fcfs_error_count", "count"),
    ("fcfs_sold_out_count", "count"),
    ("fcfs_conflict_count", "count"),
]:
    print(f"- `{name}.{key}`: `{metric(name, key)}`")
PY
fi

tar -C "$PROJECT_ROOT/artifacts/load-test" -czf "$RESULT_ARCHIVE" "$(basename "$RESULT_DIR")"
cp "$RESULT_ARCHIVE" "$LATEST_ARCHIVE"
cat > "$LATEST_META" <<EOF
result_dir=$RESULT_DIR
result_archive=$RESULT_ARCHIVE
latest_archive=$LATEST_ARCHIVE
run_id=$RUN_ID
commit_sha=$COMMIT_SHA
EOF

echo "Baseline artifacts saved to: $RESULT_DIR"
echo "Baseline archive saved to: $RESULT_ARCHIVE"

if [ "$K6_STATUS" -ne 0 ]; then
  exit "$K6_STATUS"
fi
