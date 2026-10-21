#!/usr/bin/env bash
set -euo pipefail

rows="${1:-1000000}"
artifact_dir="${2:-core-service/build/benchmark/abandoned-cart-candidate}"

# Testcontainers does not read the active OrbStack context automatically.
if [[ -z "${DOCKER_HOST:-}" ]] && docker context inspect orbstack >/dev/null 2>&1; then
  export DOCKER_HOST
  DOCKER_HOST="$(docker context inspect orbstack --format '{{.Endpoints.docker.Host}}')"
fi

cd "$(dirname "$0")/../../core-service"
./gradlew test --tests '*AbandonedCartCouponCandidateIndexBenchmarkTest' \
  -Dabandoned.cart.candidate.benchmark=true \
  -Dabandoned.cart.candidate.benchmark.rows="$rows" \
  -Dabandoned.cart.candidate.benchmark.artifact-dir="../$artifact_dir"
