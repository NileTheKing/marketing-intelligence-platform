#!/usr/bin/env bash
set -euo pipefail

profile="${1:-1m}"
timestamp="$(date +%Y%m%d-%H%M%S)"
artifact_dir="${CAMPAIGN_INCREMENTALITY_BENCHMARK_ARTIFACT_DIR:-artifacts/benchmark/${timestamp}-campaign-incrementality-${profile}}"

case "$profile" in
  1m) purchases=1000000 ;;
  5m) purchases=5000000 ;;
  10m) purchases=10000000 ;;
  *)
    echo "Usage: $0 [1m|5m|10m]" >&2
    exit 2
    ;;
esac

docker info >/dev/null
mkdir -p "$artifact_dir"

iterations="${CAMPAIGN_INCREMENTALITY_BENCHMARK_ITERATIONS:-5}"
write_rows="${CAMPAIGN_INCREMENTALITY_BENCHMARK_WRITE_ROWS:-2000}"

{
  echo "timestamp=$(date '+%Y-%m-%dT%H:%M:%S%z')"
  echo "profile=$profile"
  echo "git_sha=$(git rev-parse HEAD)"
  echo "git_dirty_files=$(git status --short | wc -l | tr -d ' ')"
  echo "purchase_rows=$purchases"
  echo "target_users=100000"
  echo "treatment_users=90000"
  echo "holdout_users=10000"
  echo "iterations=$iterations"
  echo "write_rows=$write_rows"
  echo "mysql_image_id=$(docker image inspect mysql:8.0 --format '{{.Id}}')"
  java -version 2>&1
} > "$artifact_dir/execution-metadata.txt"

(
  cd services/core-service
  ./gradlew test \
    --rerun-tasks \
    -Dcampaign.incrementality.benchmark=true \
    -Dcampaign.incrementality.benchmark.purchases="$purchases" \
    -Dcampaign.incrementality.benchmark.iterations="$iterations" \
    -Dcampaign.incrementality.benchmark.write-rows="$write_rows" \
    -Dcampaign.incrementality.benchmark.artifact-dir="../../$artifact_dir" \
    --tests com.axon.core_service.benchmark.CampaignRunIncrementalityIndexBenchmarkTest
)

echo "Artifacts: $artifact_dir"
