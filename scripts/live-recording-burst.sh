#!/bin/bash

# Configuration (Based on seed-power-demo.sh success logic)
ENTRY_SERVICE_URL="${ENTRY_SERVICE_URL:-http://localhost:8081}"

ACTIVITY_ID=${1:-11}
EVENT_ENDPOINT="$ENTRY_SERVICE_URL/entry/api/v1/behavior/events"
RESERVE_ENDPOINT="$ENTRY_SERVICE_URL/api/v1/test/reserve"

echo "Streaming traffic burst for Activity: $ACTIVITY_ID"
echo "Targeting Entry Service: $ENTRY_SERVICE_URL"

while true; do
  for i in {1..7}; do
    BURST_UID=$((RANDOM%100000 + 50000))
    OCC_AT=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
    
    # 1. Behavior Event (PAGE_VIEW -> This updates VISIT count)
    curl -s -X POST "$EVENT_ENDPOINT" \
      -H "Content-Type: application/json" \
      -d "{\"eventName\":\"PAGE_VIEW\",\"triggerType\":\"PAGE_VIEW\",\"occurredAt\":\"$OCC_AT\",\"userId\":$BURST_UID,\"sessionId\":\"sim-$(date +%s)\",\"pageUrl\":\"/campaign-activity/$ACTIVITY_ID/view\",\"properties\":{\"activityId\":$ACTIVITY_ID}}" > /dev/null &

    # 2. Behavior Event (CLICK)
    curl -s -X POST "$EVENT_ENDPOINT" \
      -H "Content-Type: application/json" \
      -d "{\"eventName\":\"CLICK\",\"triggerType\":\"CLICK\",\"occurredAt\":\"$OCC_AT\",\"userId\":$BURST_UID,\"sessionId\":\"sim-$(date +%s)\",\"pageUrl\":\"/campaign-activity/$ACTIVITY_ID/view\",\"properties\":{\"activityId\":$ACTIVITY_ID}}" > /dev/null &
    
    # 3. Purchase Simulation (RESERVE) - Randomly 30% of users
    if [ $((RANDOM % 10)) -lt 3 ]; then
      curl -s -X POST "$RESERVE_ENDPOINT/$BURST_UID" \
        -H "Content-Type: application/json" \
        -d "{\"campaignActivityId\":$ACTIVITY_ID, \"productId\":1}" > /dev/null &
    fi
  done
  
  sleep 1
done
