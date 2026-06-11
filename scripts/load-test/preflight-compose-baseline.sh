#!/bin/bash

# Fast environment check before running the Oracle VM Compose baseline.
# This script does not run k6 or mutate test data.

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

DB_USER="${DB_USER:-${DB_USERNAME:-axon_user}}"
DB_PASS="${DB_PASS:-${DB_PASSWORD:-axon1234}}"
DB_NAME="${DB_NAME:-${MYSQL_DATABASE:-axon_db}}"
REDIS_PASSWORD="${REDIS_PASSWORD:-axon1234}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:0.53.0}"

if docker ps --format '{{.Names}}' | grep -qx 'axon-mysql'; then
  DB_USER="$(docker exec axon-mysql printenv MYSQL_USER 2>/dev/null || echo "$DB_USER")"
  DB_PASS="$(docker exec axon-mysql printenv MYSQL_PASSWORD 2>/dev/null || echo "$DB_PASS")"
  DB_NAME="$(docker exec axon-mysql printenv MYSQL_DATABASE 2>/dev/null || echo "$DB_NAME")"
fi

check_url() {
  local name="$1"
  local url="$2"
  if curl -fsS "$url" >/dev/null; then
    echo "OK   $name"
  else
    echo "FAIL $name: $url"
    return 1
  fi
}

echo "=========================================="
echo "Axon Compose Baseline Preflight"
echo "=========================================="
echo "Commit: $(git -C "$PROJECT_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
echo "k6 image: $K6_IMAGE"
echo ""

echo "[1/8] Docker availability"
docker version --format 'Docker client {{.Client.Version}}, server {{.Server.Version}}'

echo ""
echo "[2/8] Compose config"
cd "$PROJECT_ROOT"
docker compose -f compose.app.yml config --quiet
echo "OK   compose.app.yml"

echo ""
echo "[3/8] Required containers"
for name in axon-core axon-entry axon-nginx axon-mysql axon-redis broker_1 kafka-controller; do
  if docker ps --format '{{.Names}}' | grep -qx "$name"; then
    echo "OK   $name"
  else
    echo "FAIL missing container: $name"
    exit 1
  fi
done

echo ""
echo "[4/8] Service health"
check_url "core-service" "http://127.0.0.1:8080/actuator/health"
check_url "entry-service" "http://127.0.0.1:8081/actuator/health"
if curl -fsSI "http://127.0.0.1:28080/" >/dev/null; then
  echo "OK   axon-nginx"
else
  echo "FAIL axon-nginx: http://127.0.0.1:28080/"
  exit 1
fi

echo ""
echo "[5/8] MySQL access"
docker exec -i -e MYSQL_PWD="$DB_PASS" axon-mysql mysql -u"$DB_USER" "$DB_NAME" -e "SELECT 1;" >/dev/null
echo "OK   mysql user=$DB_USER database=$DB_NAME"

echo ""
echo "[6/8] Redis access"
docker exec axon-redis redis-cli -a "$REDIS_PASSWORD" PING 2>/dev/null | grep -qx "PONG"
echo "OK   redis ping"

echo ""
echo "[7/8] k6 Docker image"
docker image inspect "$K6_IMAGE" >/dev/null 2>&1 || docker pull "$K6_IMAGE" >/dev/null
echo "OK   $K6_IMAGE"

echo ""
echo "[8/8] Artifact directory"
mkdir -p "$PROJECT_ROOT/artifacts/load-test"
test -w "$PROJECT_ROOT/artifacts/load-test"
echo "OK   $PROJECT_ROOT/artifacts/load-test"

echo ""
echo "Preflight passed."
