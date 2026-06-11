#!/bin/bash

# Oracle VM Docker Compose result checker.
# This is separate from check-results.sh, which remains the k8s-era checker.

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

ACTIVITY_ID="${1:-1}"

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-${DB_USERNAME:-axon_user}}"
DB_PASS="${DB_PASS:-${DB_PASSWORD:-axon1234}}"
DB_NAME="${DB_NAME:-${MYSQL_DATABASE:-axon_db}}"
REDIS_PASSWORD="${REDIS_PASSWORD:-axon1234}"

redis_get() {
  docker exec axon-redis redis-cli -a "$REDIS_PASSWORD" "$@" 2>/dev/null | tr -d '\r\n'
}

mysql_query() {
  docker exec -i -e MYSQL_PWD="$DB_PASS" axon-mysql mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" "$DB_NAME" -N -s -e "$1"
}

echo ""
echo "=========================================="
echo "Axon Compose Load Test Results"
echo "=========================================="
echo "Activity ID: $ACTIVITY_ID"
echo "Checked at: $(date '+%Y-%m-%d %H:%M:%S')"
echo ""

REDIS_COUNT="$(redis_get GET "campaign:${ACTIVITY_ID}:counter")"
REDIS_SET_SIZE="$(redis_get SCARD "campaign:${ACTIVITY_ID}:users")"
DB_ENTRY_COUNT="$(mysql_query "SELECT COUNT(*) FROM campaign_activity_entries WHERE campaign_activity_id = ${ACTIVITY_ID};")"
DB_PURCHASE_COUNT="$(mysql_query "SELECT COUNT(*) FROM purchases WHERE campaign_activity_id = ${ACTIVITY_ID};")"

REDIS_COUNT="${REDIS_COUNT:-0}"
REDIS_SET_SIZE="${REDIS_SET_SIZE:-0}"

echo "Redis counter:       $REDIS_COUNT"
echo "Redis unique users:  $REDIS_SET_SIZE"
echo "DB entries:          $DB_ENTRY_COUNT"
echo "DB purchases:        $DB_PURCHASE_COUNT"
echo ""

if [[ "$REDIS_COUNT" =~ ^[0-9]+$ ]] && [[ "$DB_ENTRY_COUNT" =~ ^[0-9]+$ ]]; then
  ENTRY_DIFF=$((REDIS_COUNT - DB_ENTRY_COUNT))
  echo "Redis counter - DB entries: $ENTRY_DIFF"
fi

if [[ "$REDIS_SET_SIZE" =~ ^[0-9]+$ ]] && [[ "$DB_ENTRY_COUNT" =~ ^[0-9]+$ ]]; then
  SET_DIFF=$((REDIS_SET_SIZE - DB_ENTRY_COUNT))
  echo "Redis set - DB entries:     $SET_DIFF"
fi

echo "=========================================="
