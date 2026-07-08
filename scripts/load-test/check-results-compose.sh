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

REDIS_PASSWORD_FROM_FILE=""
if [ -f "$PROJECT_ROOT/.env" ]; then
  REDIS_PASSWORD_FROM_FILE="$(grep '^REDIS_PASSWORD=' "$PROJECT_ROOT/.env" | tail -n 1 | cut -d= -f2- | tr -d '\r')"
fi
if [ -n "$REDIS_PASSWORD_FROM_FILE" ]; then
  export REDIS_PASSWORD="$REDIS_PASSWORD_FROM_FILE"
fi

ACTIVITY_ID="${1:-1}"
FLOW="${FLOW:-full}"

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-${DB_USERNAME:-axon_user}}"
DB_PASS="${DB_PASS:-${DB_PASSWORD:-axon1234}}"
DB_NAME="${DB_NAME:-${MYSQL_DATABASE:-axon_db}}"

if docker ps --format '{{.Names}}' | grep -qx 'axon-mysql'; then
  DB_USER="$(docker exec axon-mysql printenv MYSQL_USER 2>/dev/null || echo "$DB_USER")"
  DB_PASS="$(docker exec axon-mysql printenv MYSQL_PASSWORD 2>/dev/null || echo "$DB_PASS")"
  DB_NAME="$(docker exec axon-mysql printenv MYSQL_DATABASE 2>/dev/null || echo "$DB_NAME")"
fi

REDIS_PASSWORD="${REDIS_PASSWORD:-axon1234}"

redis_get() {
  docker exec axon-redis redis-cli --no-auth-warning -a "$REDIS_PASSWORD" "$@" 2>/dev/null | tr -d '\r\n'
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

EXPECTED_PURCHASES="${FCFS_LIMIT_COUNT:-0}"
ENTRY_CONVERGENCE_SECONDS="n/a"
PURCHASE_CONVERGENCE_SECONDS="n/a"
if [ "$FLOW" != "reservation" ] && [[ "$EXPECTED_PURCHASES" =~ ^[0-9]+$ ]] && [ "$EXPECTED_PURCHASES" -gt 0 ]; then
  for second in $(seq 0 30); do
    if [ "$ENTRY_CONVERGENCE_SECONDS" = "n/a" ] \
      && [[ "$DB_ENTRY_COUNT" =~ ^[0-9]+$ ]] \
      && [ "$DB_ENTRY_COUNT" -ge "$EXPECTED_PURCHASES" ]; then
      ENTRY_CONVERGENCE_SECONDS="$second"
    fi

    if [ "$PURCHASE_CONVERGENCE_SECONDS" = "n/a" ] \
      && [[ "$DB_PURCHASE_COUNT" =~ ^[0-9]+$ ]] \
      && [ "$DB_PURCHASE_COUNT" -ge "$EXPECTED_PURCHASES" ]; then
      PURCHASE_CONVERGENCE_SECONDS="$second"
    fi

    if [ "$ENTRY_CONVERGENCE_SECONDS" != "n/a" ] && [ "$PURCHASE_CONVERGENCE_SECONDS" != "n/a" ]; then
      break
    fi

    if [ "$second" -eq 30 ]; then
      break
    fi

    sleep 1
    DB_ENTRY_COUNT="$(mysql_query "SELECT COUNT(*) FROM campaign_activity_entries WHERE campaign_activity_id = ${ACTIVITY_ID};")"
    DB_PURCHASE_COUNT="$(mysql_query "SELECT COUNT(*) FROM purchases WHERE campaign_activity_id = ${ACTIVITY_ID};")"
  done
fi

REDIS_COUNT="${REDIS_COUNT:-0}"
REDIS_SET_SIZE="${REDIS_SET_SIZE:-0}"

echo "Redis counter:       $REDIS_COUNT"
echo "Redis unique users:  $REDIS_SET_SIZE"
echo "DB entries:          $DB_ENTRY_COUNT"
echo "DB purchases:        $DB_PURCHASE_COUNT"
echo "Flow:                $FLOW"
echo "Entries convergence seconds:   $ENTRY_CONVERGENCE_SECONDS"
echo "Purchases convergence seconds: $PURCHASE_CONVERGENCE_SECONDS"
echo ""

if [ "$FLOW" = "reservation" ]; then
  echo "Reservation-only flow: Redis reservation count is the domain check target; DB persistence is not expected."
fi

if [[ "$REDIS_COUNT" =~ ^[0-9]+$ ]] && [[ "$DB_ENTRY_COUNT" =~ ^[0-9]+$ ]]; then
  ENTRY_DIFF=$((REDIS_COUNT - DB_ENTRY_COUNT))
  echo "Redis counter - DB entries: $ENTRY_DIFF"
fi

if [[ "$REDIS_SET_SIZE" =~ ^[0-9]+$ ]] && [[ "$DB_ENTRY_COUNT" =~ ^[0-9]+$ ]]; then
  SET_DIFF=$((REDIS_SET_SIZE - DB_ENTRY_COUNT))
  echo "Redis set - DB entries:     $SET_DIFF"
fi

echo "=========================================="
