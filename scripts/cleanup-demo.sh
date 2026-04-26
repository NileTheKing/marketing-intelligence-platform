#!/bin/bash

##############################################################################
# 🧹 Axon Demo Data Cleanup Script
# 
# 이 스크립트는 모든 데모 데이터를 삭제하고 초기 상태로 되돌립니다.
##############################################################################

set -e

# Configuration (Matches application.yml defaults)
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_USER="${DB_USER:-axon_user}"
DB_PASS="${DB_PASS:-axon1234}"
DB_NAME="${DB_NAME:-axon_db}"

MYSQL_CMD="mysql -h$DB_HOST -u$DB_USER -p$DB_PASS $DB_NAME"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🧹 Axon Environment Cleanup Starting..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# 1. MySQL Data Truncate
echo "📦 Step 1: Truncating MySQL Tables..."
$MYSQL_CMD -e "SET FOREIGN_KEY_CHECKS = 0; TRUNCATE TABLE purchases; TRUNCATE TABLE campaign_activity_entries; TRUNCATE TABLE campaign_activities; TRUNCATE TABLE campaigns; SET FOREIGN_KEY_CHECKS = 1;"
echo " ✅ MySQL Cleaned."

# 2. Elasticsearch Index Cleanup
echo "📊 Step 2: Deleting Elasticsearch Indices..."
curl -s -X DELETE "http://localhost:9200/axon.event.behavior" > /dev/null || true
curl -s -X DELETE "http://localhost:9200/axon.event.commerce" > /dev/null || true
echo " ✅ ES Cleaned."

# 3. Redis Cleanup
echo "🔑 Step 3: Flushing Redis Counters..."
redis-cli -h localhost -p 6379 FLUSHALL > /dev/null || true
echo " ✅ Redis Cleaned."

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🎉 Cleanup Completed! Your environment is now FRESH."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
