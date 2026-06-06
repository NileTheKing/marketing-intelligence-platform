#!/bin/bash

# Run the Oracle VM Docker Compose FCFS baseline.
# Assumption: execute on the VM where axon containers and k6 are available.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

NUM_USERS="${1:-1000}"
ACTIVITY_ID="${2:-1}"
MAX_VUS="${MAX_VUS:-$NUM_USERS}"
FCFS_LIMIT_COUNT="${FCFS_LIMIT_COUNT:-200}"
PRODUCT_ID="${PRODUCT_ID:-1}"

RUN_ID="$(date '+%Y%m%d-%H%M%S')"
RESULT_DIR="${RESULT_DIR:-$PROJECT_ROOT/artifacts/load-test/$RUN_ID-compose-baseline}"
mkdir -p "$RESULT_DIR"

TOKEN_FILE="$SCRIPT_DIR/jwt-tokens.json"

echo "Baseline run: $RUN_ID"
echo "Users:        $NUM_USERS"
echo "Activity ID:  $ACTIVITY_ID"
echo "Max VUs:      $MAX_VUS"
echo "Result dir:   $RESULT_DIR"

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

echo "Baseline artifacts saved to: $RESULT_DIR"
