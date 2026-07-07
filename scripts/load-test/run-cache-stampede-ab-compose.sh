#!/bin/bash

# Run a focused cache-stampede A/B test for the FCFS entry path.
# The only intended variable is PRELOAD_CAMPAIGN_META=false/true.
#
# Assumption: execute on the Compose VM where Axon containers and k6 are available.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

NUM_USERS="${NUM_USERS:-3000}"
ACTIVITY_ID="${ACTIVITY_ID:-1}"
FCFS_LIMIT_COUNT="${FCFS_LIMIT_COUNT:-600}"
MAX_VUS="${MAX_VUS:-600}"
REPEATS="${REPEATS:-3}"
FLOW="${FLOW:-reservation}"
SCENARIO="${SCENARIO:-waiting_burst}"
K6_ENTRY_SERVICE_URL="${K6_ENTRY_SERVICE_URL:-http://127.0.0.1:28080}"
K6_DOCKER_NETWORK="${K6_DOCKER_NETWORK:-host}"
RESOURCE_PROFILE="${RESOURCE_PROFILE:-cache-stampede-ab}"

AB_RUN_ID="$(date '+%Y%m%d-%H%M%S')"
AB_DIR="${AB_DIR:-$PROJECT_ROOT/artifacts/load-test/$AB_RUN_ID-cache-stampede-ab}"
mkdir -p "$AB_DIR"

echo "Cache stampede A/B run: $AB_RUN_ID"
echo "Users:       $NUM_USERS"
echo "Activity ID: $ACTIVITY_ID"
echo "FCFS limit:  $FCFS_LIMIT_COUNT"
echo "Max VUs:     $MAX_VUS"
echo "Repeats:     $REPEATS"
echo "Result dir:  $AB_DIR"

cat > "$AB_DIR/run-meta.txt" <<EOF
ab_run_id=$AB_RUN_ID
num_users=$NUM_USERS
activity_id=$ACTIVITY_ID
fcfs_limit_count=$FCFS_LIMIT_COUNT
max_vus=$MAX_VUS
repeats=$REPEATS
flow=$FLOW
scenario=$SCENARIO
k6_entry_service_url=$K6_ENTRY_SERVICE_URL
k6_docker_network=$K6_DOCKER_NETWORK
resource_profile=$RESOURCE_PROFILE
started_at=$(date -Iseconds)
EOF

run_one() {
  local preload="$1"
  local index="$2"
  local label
  if [ "$preload" = "true" ]; then
    label="preload-true"
  else
    label="preload-false"
  fi

  local run_name="$AB_RUN_ID-${index}-${label}"
  local result_dir="$PROJECT_ROOT/artifacts/load-test/$run_name"
  local run_log="$AB_DIR/${index}-${label}.log"
  echo
  echo "== [$index] PRELOAD_CAMPAIGN_META=$preload =="

  set +e
  PRELOAD_CAMPAIGN_META="$preload" \
  SCENARIO="$SCENARIO" \
  FLOW="$FLOW" \
  NUM_USERS="$NUM_USERS" \
  FCFS_LIMIT_COUNT="$FCFS_LIMIT_COUNT" \
  MAX_VUS="$MAX_VUS" \
  K6_ENTRY_SERVICE_URL="$K6_ENTRY_SERVICE_URL" \
  K6_DOCKER_NETWORK="$K6_DOCKER_NETWORK" \
  RESOURCE_PROFILE="$RESOURCE_PROFILE $label" \
  RESULT_DIR="$result_dir" \
    "$SCRIPT_DIR/run-baseline-compose.sh" "$NUM_USERS" "$ACTIVITY_ID" > "$run_log" 2>&1
  local status=$?
  set -e

  echo "preload_campaign_meta=$preload" >> "$result_dir/ab-meta.txt"
  echo "ab_index=$index" >> "$result_dir/ab-meta.txt"
  echo "ab_status=$status" >> "$result_dir/ab-meta.txt"
  echo "log=$run_log" >> "$result_dir/ab-meta.txt"
  ln -sfn "$result_dir" "$AB_DIR/${index}-${label}"
  return 0
}

index=1
while [ "$index" -le "$REPEATS" ]; do
  run_one false "$index"
  run_one true "$index"
  index=$((index + 1))
done

python3 - "$AB_DIR" > "$AB_DIR/summary.md" <<'PY'
import json
import re
import statistics
import sys
from pathlib import Path

root = Path(sys.argv[1])

def metric(metrics, name, key):
    value = metrics.get(name, {}).get(key)
    return None if value is None else float(value)

def domain_value(text, label):
    match = re.search(rf"{re.escape(label)}:\s+([0-9]+)", text)
    return None if not match else int(match.group(1))

rows = []
for run_dir in sorted(p for p in root.iterdir() if p.is_dir()):
    meta_path = run_dir / "ab-meta.txt"
    summary_path = run_dir / "k6-summary.json"
    domain_path = run_dir / "domain-check.log"
    run_meta_path = run_dir / "run-meta.txt"
    if not meta_path.exists() or not summary_path.exists():
        continue

    meta = dict(
        line.split("=", 1)
        for line in meta_path.read_text(encoding="utf-8").splitlines()
        if "=" in line
    )
    run_meta = dict(
        line.split("=", 1)
        for line in run_meta_path.read_text(encoding="utf-8").splitlines()
        if "=" in line
    ) if run_meta_path.exists() else {}

    with summary_path.open(encoding="utf-8") as f:
        data = json.load(f)
    metrics = data.get("metrics", {})
    domain = domain_path.read_text(encoding="utf-8") if domain_path.exists() else ""

    rows.append({
        "dir": run_dir.name,
        "preload": meta.get("preload_campaign_meta", "unknown"),
        "status": meta.get("ab_status", "unknown"),
        "k6_status": run_meta.get("k6_status", "unknown"),
        "http_p95_ms": metric(metrics, "http_req_duration", "p(95)"),
        "reservation_p95_ms": metric(metrics, "reservation_duration", "p(95)"),
        "http_reqs": metric(metrics, "http_reqs", "count"),
        "iterations": metric(metrics, "iterations", "count"),
        "success": metric(metrics, "fcfs_success_count", "count"),
        "errors": metric(metrics, "fcfs_error_count", "count"),
        "sold_out": metric(metrics, "fcfs_sold_out_count", "count"),
        "redis_counter": domain_value(domain, "Redis counter"),
        "redis_users": domain_value(domain, "Redis unique users"),
        "db_entries": domain_value(domain, "DB entries"),
        "db_purchases": domain_value(domain, "DB purchases"),
    })

def fmt(value):
    if value is None:
        return "n/a"
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    if isinstance(value, float):
        return f"{value:.2f}"
    return str(value)

print("# Cache Stampede A/B Summary")
print()
print("Status: active diagnostic artifact")
print()
print("This experiment keeps the FCFS scenario fixed and changes only `PRELOAD_CAMPAIGN_META`.")
print("Use this as diagnostic evidence, not as a final headline unless the VM resource profile is also fixed and recorded.")
print()
print("## Runs")
print()
print("| Run | PRELOAD | script status | k6 status | reservation p95 ms | http p95 ms | success | errors | sold out | Redis | DB entries |")
print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
for row in rows:
    redis = "n/a"
    if row["redis_counter"] is not None and row["redis_users"] is not None:
        redis = f"{row['redis_counter']}/{row['redis_users']}"
    print(
        f"| `{row['dir']}` | `{row['preload']}` | `{row['status']}` | `{row['k6_status']}` | "
        f"`{fmt(row['reservation_p95_ms'])}` | `{fmt(row['http_p95_ms'])}` | "
        f"`{fmt(row['success'])}` | `{fmt(row['errors'])}` | `{fmt(row['sold_out'])}` | "
        f"`{redis}` | `{fmt(row['db_entries'])}` |"
    )

print()
print("## Aggregates")
print()
for preload in ("false", "true"):
    group = [row for row in rows if row["preload"] == preload]
    if not group:
        continue
    print(f"### PRELOAD_CAMPAIGN_META={preload}")
    print()
    for label, key in [
        ("reservation p95 ms", "reservation_p95_ms"),
        ("http p95 ms", "http_p95_ms"),
        ("success count", "success"),
        ("error count", "errors"),
    ]:
        values = [row[key] for row in group if row[key] is not None]
        if not values:
            continue
        print(f"- {label}: avg `{statistics.mean(values):.2f}`, min `{min(values):.2f}`, max `{max(values):.2f}`")
    print()

print("## Interpretation Guardrail")
print()
print("- Strong claim: whether metadata preload removes repeated Core metadata lookup from the entry burst path.")
print("- Strong claim: whether timeout/error tendency improves under the same resource profile.")
print("- Weak claim unless separately verified: exact percentage improvement caused only by cache stampede removal.")
PY

echo
echo "A/B summary saved to: $AB_DIR/summary.md"
