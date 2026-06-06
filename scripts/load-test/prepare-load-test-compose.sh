#!/bin/bash

# Oracle VM Docker Compose baseline wrapper.
# Keeps the original k8s-era prepare-load-test.sh unchanged for portfolio
# reproduction, while supplying the current Compose defaults.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export CORE_SERVICE_URL="${CORE_SERVICE_URL:-http://127.0.0.1:8080}"
export ENTRY_SERVICE_URL="${ENTRY_SERVICE_URL:-http://127.0.0.1:8081}"

export DB_HOST="${DB_HOST:-127.0.0.1}"
export DB_PORT="${DB_PORT:-3306}"
export DB_USER="${DB_USER:-axon_user}"
export DB_PASS="${DB_PASS:-axon1234}"
export DB_NAME="${DB_NAME:-axon_db}"

export REDIS_MODE="${REDIS_MODE:-docker}"
export REDIS_PASSWORD="${REDIS_PASSWORD:-axon1234}"

exec "$SCRIPT_DIR/prepare-load-test.sh" "$@"
