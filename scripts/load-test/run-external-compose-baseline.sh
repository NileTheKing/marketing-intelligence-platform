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

RUN_ID="${RUN_ID:-$(date '+%Y%m%d-%H%M%S')-external-compose-baseline}"
RESULT_DIR="${RESULT_DIR:-$PROJECT_ROOT/artifacts/load-test/$RUN_ID}"
TOKEN_FILE="$SCRIPT_DIR/jwt-tokens.json"
REMOTE_TOKEN_FILE="$REMOTE_DIR/scripts/load-test/jwt-tokens.json"
SUMMARY_FILE="$RESULT_DIR/k6-summary.json"
CONSOLE_LOG="$RESULT_DIR/k6-console.log"
DOMAIN_CHECK_LOG="$RESULT_DIR/domain-check.log"
REMOTE_CHECK_SCRIPT="$RESULT_DIR/domain-check-remote.sh"
REMOTE_CHECK_PATH="/tmp/axon-domain-check-$RUN_ID.sh"

mkdir -p "$RESULT_DIR"

ssh_vm() {
  ssh -i "$SSH_KEY" "$VM_HOST" "$@"
}

count_tokens() {
  python3 - "$1" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)

print(len(data))
PY
}

echo "External compose baseline: $RUN_ID"
echo "VM:        $VM_HOST"
echo "Remote:    $REMOTE_DIR"
echo "Public URL: $PUBLIC_URL"
echo "Flow:      $FLOW"
echo "Users:     $NUM_USERS"
echo "Max VUs:   $MAX_VUS"
echo "Results:   $RESULT_DIR"

cat > "$RESULT_DIR/run-meta.txt" <<EOF
run_id=$RUN_ID
vm_host=$VM_HOST
remote_dir=$REMOTE_DIR
public_url=$PUBLIC_URL
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
ssh_vm "cd '$REMOTE_DIR' && PRELOAD_CAMPAIGN_META='$PRELOAD_CAMPAIGN_META' FCFS_LIMIT_COUNT='$FCFS_LIMIT_COUNT' PRODUCT_ID='$PRODUCT_ID' ./scripts/load-test/prepare-load-test-compose.sh '$NUM_USERS' '$ACTIVITY_ID'"

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
set +e
FLOW="$FLOW" \
SCENARIO="$SCENARIO" \
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
  k6 run --summary-export "$SUMMARY_FILE" "$SCRIPT_DIR/k6-fcfs-load-test.js" \
  2>&1 | tee "$CONSOLE_LOG"
K6_STATUS=${PIPESTATUS[0]}
set -e

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
  docker exec -i -e MYSQL_PWD="$MYSQL_PASSWORD" axon-mysql \
    mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" -N -s -e "$1"
}

DB_ENTRIES="$(mysql_count "SELECT COUNT(*) FROM campaign_activity_entries WHERE campaign_activity_id = ${ACTIVITY_ID};")"
DB_PURCHASES="$(mysql_count "SELECT COUNT(*) FROM purchases WHERE campaign_activity_id = ${ACTIVITY_ID};")"

if [ "$FLOW" != "reservation" ]; then
  for _ in $(seq 1 30); do
    if [ "$DB_ENTRIES" -ge "$FCFS_LIMIT_COUNT" ] && [ "$DB_PURCHASES" -ge "$FCFS_LIMIT_COUNT" ]; then
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

cat > "$RESULT_DIR/summary.md" <<EOF
# External Compose Baseline Summary

- Run ID: \`$RUN_ID\`
- Flow: \`$FLOW\`
- Scenario: \`$SCENARIO\`
- Public URL: \`$PUBLIC_URL\`
- Users: \`$NUM_USERS\`
- Max VUs: \`$MAX_VUS\`
- Arrival pre-allocated VUs: \`$ARRIVAL_PRE_ALLOCATED_VUS\`
- Arrival rate multiplier: \`$ARRIVAL_RATE_MULTIPLIER\`
- Activity ID: \`$ACTIVITY_ID\`
- FCFS limit count: \`$FCFS_LIMIT_COUNT\`
- k6 status: \`$K6_STATUS\`
- domain check status: \`$DOMAIN_STATUS\`

## Domain Check

\`\`\`text
$(cat "$DOMAIN_CHECK_LOG")
\`\`\`

## k6 Console Tail

\`\`\`text
$(tail -80 "$CONSOLE_LOG")
\`\`\`
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

if [ "$K6_STATUS" -ne 0 ] || [ "$DOMAIN_STATUS" -ne 0 ]; then
  exit 1
fi
