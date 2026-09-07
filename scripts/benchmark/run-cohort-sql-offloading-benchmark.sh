#!/usr/bin/env bash
set -euo pipefail

profile="${1:-smoke}"
timestamp="$(date +%Y%m%d-%H%M%S)"
artifact_dir="${COHORT_BENCHMARK_ARTIFACT_DIR:-artifacts/benchmark/${timestamp}-cohort-sql-offloading-${profile}}"

case "$profile" in
  smoke)
    : "${COHORT_BENCHMARK_COHORT_USERS:=200}"
    : "${COHORT_BENCHMARK_PURCHASES_PER_COHORT_USER:=10}"
    : "${COHORT_BENCHMARK_UNRELATED_PURCHASES:=50000}"
    : "${COHORT_BENCHMARK_ITERATIONS:=5}"
    ;;
  index-selectivity)
    : "${COHORT_BENCHMARK_COHORT_USERS:=800}"
    : "${COHORT_BENCHMARK_PURCHASES_PER_COHORT_USER:=10}"
    : "${COHORT_BENCHMARK_UNRELATED_PURCHASES:=992000}"
    : "${COHORT_BENCHMARK_ITERATIONS:=5}"
    ;;
  hydration-jfr|portfolio)
    : "${COHORT_BENCHMARK_COHORT_USERS:=10000}"
    : "${COHORT_BENCHMARK_PURCHASES_PER_COHORT_USER:=50}"
    : "${COHORT_BENCHMARK_UNRELATED_PURCHASES:=500000}"
    : "${COHORT_BENCHMARK_ITERATIONS:=5}"
    ;;
  index-write-cost)
    : "${PURCHASE_INDEX_WRITE_BACKGROUND_ROWS:=1000000}"
    : "${PURCHASE_INDEX_WRITE_BACKGROUND_USERS:=100000}"
    : "${PURCHASE_INDEX_WRITE_ROWS:=800}"
    : "${PURCHASE_INDEX_WRITE_TRANSACTION_BATCH_SIZE:=20}"
    : "${PURCHASE_INDEX_WRITE_ITERATIONS:=5}"
    ;;
  *)
    echo "Usage: $0 [smoke|index-selectivity|hydration-jfr|index-write-cost]" >&2
    exit 2
    ;;
esac

docker info >/dev/null
mkdir -p "$artifact_dir"

{
  echo "timestamp=$(date --iso-8601=seconds 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z')"
  echo "profile=$profile"
  echo "git_sha=$(git rev-parse HEAD)"
  echo "git_dirty_files=$(git status --short | wc -l | tr -d ' ')"
  echo "os=$(uname -srm)"
  echo "mysql_image_id=$(docker image inspect mysql:8.0 --format '{{.Id}}')"
  echo "--- java ---"
  java -version 2>&1
  echo "--- docker ---"
  docker version --format 'client={{.Client.Version}} server={{.Server.Version}}'
} > "$artifact_dir/execution-metadata.txt"

test_class="com.axon.core_service.benchmark.CohortSqlOffloadingBenchmarkIT"
benchmark_args=()

if [[ "$profile" == "index-write-cost" ]]; then
  test_class="com.axon.core_service.benchmark.PurchaseIndexWriteCostBenchmarkIT"
  benchmark_args=(
    -Dpurchase.index.write.benchmark=true
    -Dpurchase.index.write.background-rows="$PURCHASE_INDEX_WRITE_BACKGROUND_ROWS"
    -Dpurchase.index.write.background-users="$PURCHASE_INDEX_WRITE_BACKGROUND_USERS"
    -Dpurchase.index.write.rows="$PURCHASE_INDEX_WRITE_ROWS"
    -Dpurchase.index.write.transaction-batch-size="$PURCHASE_INDEX_WRITE_TRANSACTION_BATCH_SIZE"
    -Dpurchase.index.write.iterations="$PURCHASE_INDEX_WRITE_ITERATIONS"
  )
else
  benchmark_args=(
    -Dcohort.sql.benchmark=true
    -Dcohort.benchmark.profile="$profile"
    -Dcohort.benchmark.cohort-users="$COHORT_BENCHMARK_COHORT_USERS"
    -Dcohort.benchmark.purchases-per-cohort-user="$COHORT_BENCHMARK_PURCHASES_PER_COHORT_USER"
    -Dcohort.benchmark.unrelated-purchases="$COHORT_BENCHMARK_UNRELATED_PURCHASES"
    -Dcohort.benchmark.iterations="$COHORT_BENCHMARK_ITERATIONS"
  )
fi

(
  cd core-service
  ./gradlew test \
    --rerun-tasks \
    -Dcohort.benchmark.docker-api-version=1.40 \
    -Dcohort.benchmark.max-heap=2g \
    -Dcohort.benchmark.artifact-dir="../$artifact_dir" \
    -Dpurchase.index.write.artifact-dir="../$artifact_dir" \
    "${benchmark_args[@]}" \
    --tests "$test_class"
)

echo "Artifacts: $artifact_dir"
