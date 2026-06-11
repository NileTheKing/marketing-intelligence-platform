#!/bin/bash

# Oracle VM Docker Compose baseline wrapper.
# Keeps the original k8s-era prepare-load-test.sh unchanged for portfolio
# reproduction, while supplying the current Compose defaults.

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
CAMPAIGN_ID="${CAMPAIGN_ID:-1}"
PRODUCT_ID="${PRODUCT_ID:-1}"
FCFS_LIMIT_COUNT="${FCFS_LIMIT_COUNT:-200}"

export CORE_SERVICE_URL="${CORE_SERVICE_URL:-http://127.0.0.1:8080}"
export ENTRY_SERVICE_URL="${ENTRY_SERVICE_URL:-http://127.0.0.1:8081}"

export DB_HOST="${DB_HOST:-127.0.0.1}"
export DB_PORT="${DB_PORT:-3306}"
export DB_USER="${DB_USER:-${DB_USERNAME:-axon_user}}"
export DB_PASS="${DB_PASS:-${DB_PASSWORD:-axon1234}}"
export DB_NAME="${DB_NAME:-${MYSQL_DATABASE:-axon_db}}"

export REDIS_MODE="${REDIS_MODE:-docker}"
export REDIS_PASSWORD="${REDIS_PASSWORD:-axon1234}"

MYSQL_WRAPPER_DIR="$PROJECT_ROOT/artifacts/load-test/bin"
mkdir -p "$MYSQL_WRAPPER_DIR"
cat > "$MYSQL_WRAPPER_DIR/mysql" <<'EOF'
#!/bin/bash
set -euo pipefail
docker exec -i -e MYSQL_PWD="${MYSQL_PWD:-}" axon-mysql mysql "$@"
EOF
chmod +x "$MYSQL_WRAPPER_DIR/mysql"
export PATH="$MYSQL_WRAPPER_DIR:$PATH"

echo "Ensuring Compose baseline seed data..."
MYSQL_PWD="$DB_PASS" mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" "$DB_NAME" <<SQL
INSERT INTO products (id, product_name, price, stock, category, brand, discount_rate, image_url)
VALUES ($PRODUCT_ID, 'Axon Baseline Product', 1290000.00, GREATEST($FCFS_LIMIT_COUNT * 3, 1000), 'BASELINE', 'AXON', 0, NULL)
ON DUPLICATE KEY UPDATE
  product_name = VALUES(product_name),
  price = VALUES(price),
  stock = GREATEST(stock, VALUES(stock)),
  category = VALUES(category),
  brand = VALUES(brand),
  discount_rate = VALUES(discount_rate);

INSERT INTO campaigns (id, name, start_at, end_at, created_at, updated_at, budget)
VALUES ($CAMPAIGN_ID, 'Axon Compose Baseline Campaign', NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 7 DAY, NOW(), NOW(), 10000000)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  start_at = VALUES(start_at),
  end_at = VALUES(end_at),
  updated_at = NOW(),
  budget = VALUES(budget);

INSERT INTO campaign_activities (
  id, campaign_id, product_id, name, activity_type, status,
  start_date, end_date, price, quantity, limit_count, budget, synced_count, created_at, updated_at
)
VALUES (
  $ACTIVITY_ID, $CAMPAIGN_ID, $PRODUCT_ID, 'Axon Compose FCFS Baseline', 'FIRST_COME_FIRST_SERVE', 'ACTIVE',
  NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 7 DAY, 1290000.00, $FCFS_LIMIT_COUNT, $FCFS_LIMIT_COUNT, 5000000.00, 0, NOW(), NOW()
)
ON DUPLICATE KEY UPDATE
  campaign_id = VALUES(campaign_id),
  product_id = VALUES(product_id),
  name = VALUES(name),
  activity_type = VALUES(activity_type),
  status = VALUES(status),
  start_date = VALUES(start_date),
  end_date = VALUES(end_date),
  price = VALUES(price),
  quantity = VALUES(quantity),
  limit_count = VALUES(limit_count),
  budget = VALUES(budget),
  updated_at = NOW();
SQL

exec "$SCRIPT_DIR/prepare-load-test.sh" "$NUM_USERS" "$ACTIVITY_ID"
