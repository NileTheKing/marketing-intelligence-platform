#!/bin/bash

##############################################################################
# 🚀 Axon Power Demo Data Seeder
#
# 이 스크립트는 포트폴리오 시연을 위해 최적화된 대량의 데이터를 생성합니다.
# 3개의 캠페인과 각 캠페인별 다양한 성공/실패 시나리오를 시뮬레이션합니다.
##############################################################################

set -e

# Configuration
ENTRY_SERVICE_URL="${ENTRY_SERVICE_URL:-http://localhost:8081}"
CORE_SERVICE_URL="${CORE_SERVICE_URL:-http://localhost:8080}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_USER="${DB_USER:-axon_user}"
DB_PASS="${DB_PASS:-axon1234}"
DB_NAME="${DB_NAME:-axon_db}"

# MySQL Connection (Check if local mysql works, else use docker)
if command -v mysql >/dev/null 2>&1 && mysql -h$DB_HOST -u$DB_USER -p$DB_PASS -e "status" >/dev/null 2>&1; then
    MYSQL_CMD="mysql -h$DB_HOST -u$DB_USER -p$DB_PASS $DB_NAME"
    echo " 🔌 Using Local MySQL Client..."
else
    MYSQL_CMD="docker exec -i axon-mysql mysql -u$DB_USER -p$DB_PASS $DB_NAME"
    echo " 🐳 Using Docker MySQL (axon-mysql)..."
fi

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
# 1. 초기화 (기존 데이터 삭제)
echo "🧹 Step 1: Cleaning up existing data..."

# MySQL Truncate (Foreign Key 무시)
$MYSQL_CMD -e "SET FOREIGN_KEY_CHECKS = 0; TRUNCATE TABLE purchases; TRUNCATE TABLE campaign_activity_entries; TRUNCATE TABLE campaign_activities; TRUNCATE TABLE campaigns; SET FOREIGN_KEY_CHECKS = 1;"

# Elasticsearch Index 초기화 (Delete and Recreate via Sink)
curl -s -X DELETE "http://localhost:9200/axon.event.behavior" > /dev/null || true
curl -s -X DELETE "http://localhost:9200/axon.event.commerce" > /dev/null || true

# Redis Counter 초기화
# Redis Counter 초기화 (Password: axon1234)
redis-cli -h localhost -p 6379 -a axon1234 FLUSHALL 2>/dev/null || \
docker exec axon-redis redis-cli -a axon1234 FLUSHALL 2>/dev/null || \
echo " ⚠️  Warning: Redis could not be cleared (Check if redis-cli is installed or Docker container is running)"

echo " ✅ DB, ES, Redis Cleaned."

# 2. 캠페인 & 활동 생성
echo ""
echo "🏗️ Step 2: Seeding 5 Campaigns & 15 Activities..."

# Campaign 1: Premium Tech (Tech/IT)
$MYSQL_CMD -e "REPLACE INTO campaigns (id, name, start_at, end_at, budget) VALUES (1, 'Premium Tech Festival', NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 50000000);"
$MYSQL_CMD -e "REPLACE INTO campaign_activities (id, campaign_id, name, activity_type, status, limit_count, price, quantity, budget, start_date, end_date) VALUES 
(11, 1, 'SmartWatch Ultra FCFS', 'FIRST_COME_FIRST_SERVE', 'ACTIVE', 500, 500000, 500, 10000000, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY)),
(12, 1, 'Tech Accessory Coupon', 'FIRST_COME_FIRST_SERVE', 'ACTIVE', 1000, 20000, 1000, 5000000, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY)),
(13, 1, 'New Member Welcome Deal', 'FIRST_COME_FIRST_SERVE', 'ACTIVE', 2000, 15000, 2000, 3000000, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY));"

# Campaign 2: Modern Living (Home/Furniture)
$MYSQL_CMD -e "REPLACE INTO campaigns (id, name, start_at, end_at, budget) VALUES (2, 'Modern Living Fair', NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 30000000);"
$MYSQL_CMD -e "REPLACE INTO campaign_activities (id, campaign_id, name, activity_type, status, limit_count, price, quantity, budget, start_date, end_date) VALUES 
(21, 2, 'Design Sofa Limited', 'FIRST_COME_FIRST_SERVE', 'ACTIVE', 50, 1200000, 50, 10000000, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY)),
(22, 2, 'Kitchen Collection Sale', 'FIRST_COME_FIRST_SERVE', 'ACTIVE', 300, 45000, 300, 5000000, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY)),
(23, 2, 'Interior Lookbook Event', 'FIRST_COME_FIRST_SERVE', 'ACTIVE', 500, 10000, 500, 1000000, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY));"

# Campaign 3: K-Beauty & Health (Beauty)
$MYSQL_CMD -e "REPLACE INTO campaigns (id, name, start_at, end_at, budget) VALUES (3, 'K-Beauty Spring Festa', NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 20000000);"
$MYSQL_CMD -e "REPLACE INTO campaign_activities (id, campaign_id, name, activity_type, status, limit_count, price, quantity, budget, start_date, end_date) VALUES 
(31, 3, 'Premium Ample VIP Set', 'FIRST_COME_FIRST_SERVE', 'ACTIVE', 100, 150000, 100, 2000000, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY)),
(32, 3, 'Daily Vitamin Special', 'FIRST_COME_FIRST_SERVE', 'ACTIVE', 500, 9900, 500, 1000000, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY)),
(33, 3, 'Sheet Mask 1+1 Deal', 'FIRST_COME_FIRST_SERVE', 'ACTIVE', 1000, 2000, 1000, 500000, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY));"

# Campaign 4: Trend Fashion (Fashion)
$MYSQL_CMD -e "REPLACE INTO campaigns (id, name, start_at, end_at, budget) VALUES (4, 'Global Sneaker Week', NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 15000000);"
$MYSQL_CMD -e "REPLACE INTO campaign_activities (id, campaign_id, name, activity_type, status, limit_count, price, quantity, budget, start_date, end_date) VALUES 
(41, 4, 'Limited Edition Sneaker', 'FIRST_COME_FIRST_SERVE', 'ACTIVE', 100, 250000, 100, 5000000, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY)),
(42, 4, 'Season Off Cardigan', 'FIRST_COME_FIRST_SERVE', 'ACTIVE', 400, 39000, 400, 2000000, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY)),
(43, 4, 'Stock Clearance Sale', 'FIRST_COME_FIRST_SERVE', 'ACTIVE', 500, 19000, 500, 1000000, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY));"

# Campaign 5: Outdoor Life (Outdoor)
$MYSQL_CMD -e "REPLACE INTO campaigns (id, name, start_at, end_at, budget) VALUES (5, 'Camping & Trekking Expo', NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 10000000);"
$MYSQL_CMD -e "REPLACE INTO campaign_activities (id, campaign_id, name, activity_type, status, limit_count, price, quantity, budget, start_date, end_date) VALUES 
(51, 5, 'Premium Tent FCFS', 'FIRST_COME_FIRST_SERVE', 'ACTIVE', 30, 850000, 30, 2000000, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY)),
(52, 5, 'Camping Gear Guide', 'FIRST_COME_FIRST_SERVE', 'ACTIVE', 200, 49000, 200, 1000000, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY)),
(53, 5, 'Outdoor Food Kit', 'FIRST_COME_FIRST_SERVE', 'ACTIVE', 300, 15000, 300, 500000, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY));"

echo " ✅ 5 Campaigns & 15 Activities Seeded."

# 3. 퍼널 데이터 생성 함수
generate_funnel() {
    local aid=$1
    local visits=$2
    local click_rate_pct=$3  
    local conv_rate_pct=$4   
    
    echo "📊 Generating funnel for Activity $aid: $visits visits..."
    
    for i in $(seq 1 $visits); do
        uid=$((aid * 1000 + i))
        OCCURRED_AT=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
        SESSION_ID="session-$uid-$(date +%s)"
        
        # 1. PAGE_VIEW
        curl -s -X POST "${ENTRY_SERVICE_URL}/entry/api/v1/behavior/events" \
            -H "Content-Type: application/json" \
            -d "{\"eventName\":\"PAGE_VIEW\",\"triggerType\":\"PAGE_VIEW\",\"occurredAt\":\"$OCCURRED_AT\",\"userId\":$uid,\"sessionId\":\"$SESSION_ID\",\"pageUrl\":\"/campaign-activity/$aid/view\",\"properties\":{\"activityId\":$aid}}" > /dev/null

        # 2. CLICK
        if [ $((i * 100 / visits)) -lt $click_rate_pct ]; then
            curl -s -X POST "${ENTRY_SERVICE_URL}/entry/api/v1/behavior/events" \
                -H "Content-Type: application/json" \
                -d "{\"eventName\":\"CLICK\",\"triggerType\":\"CLICK\",\"occurredAt\":\"$OCCURRED_AT\",\"userId\":$uid,\"sessionId\":\"$SESSION_ID\",\"pageUrl\":\"/campaign-activity/$aid/view\",\"properties\":{\"activityId\":$aid}}" > /dev/null

            # 3. QUALIFY & PURCHASE (Via Test Endpoint - 15% Churn applied in Backend)
            if [ $((i * 100 / visits)) -lt $conv_rate_pct ]; then
                curl -s -X POST "${ENTRY_SERVICE_URL}/api/v1/test/reserve/$uid" \
                    -H "Content-Type: application/json" \
                    -d "{\"campaignActivityId\":$aid, \"productId\":1}" > /dev/null
            fi
        fi
        
        if [ $((i % 20)) -eq 0 ]; then echo -n "."; fi
    done
    echo " Done."
}

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# 🚛 Step 3: Historical Data for Cohort Analysis (Activity 11)
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
echo "🕒 Step 3: Generating 400-day History for Cohort Analysis (Activity 11)..."
generate_funnel 11 100 80 50 
echo "  ⏳ Waiting for Kafka processing (5s)..."
sleep 5
export DB_PASS=$DB_PASS
# 400일 전으로 이동시켜 365일 LTV가 오늘 기준 과거 데이터가 되도록 함
bash core-service/scripts/time-travel-activity.sh 11 400 > /dev/null
bash core-service/scripts/generate-ltv-simulation.sh 11 > /dev/null

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# 🚀 Step 4: Hybrid Performance Seeding (Heroes & Backgrounds)
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
echo ""
echo "🔥 Step 4: Seeding Performance for 15 Activities..."

# Campaign 1: Tech (High Traffic)
generate_funnel 11 250 85 45
generate_funnel 12 100 40 10 # Background
generate_funnel 13 80 30 5   # Background

# Campaign 2: Living (Mid Traffic)
generate_funnel 21 150 70 30
generate_funnel 22 80 25 8   # Background
generate_funnel 23 50 20 4   # Background

# Campaign 3: Beauty (High Efficiency)
generate_funnel 31 100 90 75
generate_funnel 32 80 40 20  # Background
generate_funnel 33 60 30 15  # Background

# Campaign 4: Fashion (Viral/High Click)
generate_funnel 41 200 95 60
generate_funnel 42 100 35 12 # Background
generate_funnel 43 80 20 6   # Background

# Campaign 5: Outdoor (Niche/Specific)
generate_funnel 51 50 80 60
generate_funnel 52 40 30 10  # Background
generate_funnel 53 30 20 5   # Background

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🎉 GLOBAL BRAND PLATFORM SEEDING COMPLETE!"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📽️  Snapshot Strategy:"
echo "  1. Global Dashboard: 5 Diverse Industry Campaigns"
2. Campaign Detail: 1 Hero FCFS + 2 Supporting Activities
3. Realistic Funnel: ~15% Payment dropout simulated"
echo "  4. Cohort: 30-day Retention verified on Activity 11"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
