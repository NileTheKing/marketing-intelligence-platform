#!/bin/bash

# Run the Oracle VM Docker Compose FCFS baseline.
# Assumption: execute on the VM where axon containers and k6 are available.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [ -f "$PROJECT_ROOT/.env" ]; then
  set -a
  . "$PROJECT_ROOT/.env"
  set +a
fi

NUM_USERS="${1:-1000}"
ACTIVITY_ID="${2:-1}"
MAX_VUS="${MAX_VUS:-$NUM_USERS}"
FCFS_LIMIT_COUNT="${FCFS_LIMIT_COUNT:-200}"
PRODUCT_ID="${PRODUCT_ID:-1}"

RUN_ID="$(date '+%Y%m%d-%H%M%S')"
RESULT_DIR="${RESULT_DIR:-$PROJECT_ROOT/artifacts/load-test/$RUN_ID-compose-baseline}"
mkdir -p "$RESULT_DIR"
RESULT_ARCHIVE="$PROJECT_ROOT/artifacts/load-test/$RUN_ID-compose-baseline.tar.gz"
LATEST_ARCHIVE="$PROJECT_ROOT/artifacts/load-test/latest-compose-baseline.tar.gz"
LATEST_META="$PROJECT_ROOT/artifacts/load-test/latest-compose-baseline.txt"

TOKEN_FILE="$SCRIPT_DIR/jwt-tokens.json"
COMMIT_SHA="$(git -C "$PROJECT_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"

echo "Baseline run: $RUN_ID"
echo "Users:        $NUM_USERS"
echo "Activity ID:  $ACTIVITY_ID"
echo "Max VUs:      $MAX_VUS"
echo "Result dir:   $RESULT_DIR"

cat > "$RESULT_DIR/run-meta.txt" <<EOF
run_id=$RUN_ID
commit_sha=$COMMIT_SHA
num_users=$NUM_USERS
activity_id=$ACTIVITY_ID
max_vus=$MAX_VUS
fcfs_limit_count=$FCFS_LIMIT_COUNT
product_id=$PRODUCT_ID
started_at=$(date -Iseconds)
host=$(hostname)
EOF

"$SCRIPT_DIR/prepare-load-test-compose.sh" "$NUM_USERS" "$ACTIVITY_ID"

SCENARIO=spike \
MAX_VUS="$MAX_VUS" \
ACTIVITY_ID="$ACTIVITY_ID" \
PRODUCT_ID="$PRODUCT_ID" \
FCFS_LIMIT_COUNT="$FCFS_LIMIT_COUNT" \
USE_PRODUCTION_API=true \
TOKEN_FILE_PATH="$TOKEN_FILE" \
k6 run \
  --summary-export "$RESULT_DIR/k6-summary.json" \
  "$SCRIPT_DIR/k6-fcfs-load-test.js" \
  2>&1 | tee "$RESULT_DIR/k6-console.log"

"$SCRIPT_DIR/check-results-compose.sh" "$ACTIVITY_ID" | tee "$RESULT_DIR/domain-check.log"

docker stats --no-stream > "$RESULT_DIR/docker-stats.txt"
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" > "$RESULT_DIR/docker-ps.txt"

cat > "$RESULT_DIR/summary.md" <<EOF
# Compose Baseline Summary

- Run ID: \`$RUN_ID\`
- Commit: \`$COMMIT_SHA\`
- Users: \`$NUM_USERS\`
- Max VUs: \`$MAX_VUS\`
- Activity ID: \`$ACTIVITY_ID\`
- Product ID: \`$PRODUCT_ID\`
- FCFS limit count: \`$FCFS_LIMIT_COUNT\`

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

if command -v python3 >/dev/null 2>&1; then
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
