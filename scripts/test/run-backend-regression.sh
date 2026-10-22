#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RESULT_DIR="$ROOT/artifacts/test-regression/$(date -u '+%Y%m%dT%H%M%SZ')-backend"
mkdir -p "$RESULT_DIR"

if ! docker info >/dev/null 2>&1; then
  printf 'Docker is required; integration tests must not be silently skipped.\n' >&2
  exit 2
fi

# Testcontainers does not automatically follow the active OrbStack/Colima context.
if [[ -z "${DOCKER_HOST:-}" ]]; then
  context_host="$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null || true)"
  if [[ "$context_host" == unix://* ]]; then
    export DOCKER_HOST="$context_host"
  fi
fi
case "${DOCKER_HOST:-}" in
  *orbstack*|*colima*)
    export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="${TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE:-/var/run/docker.sock}"
    ;;
esac

# Compatibility for this repository's Testcontainers 1.20 Docker client.
if [[ "${JAVA_TOOL_OPTIONS:-}" != *-Dapi.version=* ]]; then
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dapi.version=${DOCKER_API_VERSION:-1.44}"
fi

snapshot_sources() {
  git -C "$ROOT" diff HEAD --binary | shasum -a 256
  shasum -a 256 \
    "$ROOT/services/core-service/src/test/java/com/axon/core_service/service/CampaignRunWorkflowIntegrationTest.java" \
    "$ROOT/scripts/test/run-backend-regression.sh"
}

head_before="$(git -C "$ROOT" rev-parse HEAD)"
git -C "$ROOT" status --porcelain=v1 > "$RESULT_DIR/worktree-before.txt"
snapshot_sources > "$RESULT_DIR/sources-before.sha256"
printf 'Running backend integration regression; log: %s/run.log\n' "$RESULT_DIR"
if ! (
  cd "$ROOT/services/core-service"
  ./gradlew test --rerun-tasks \
    --tests '*PurchaseFlowIntegrationTest' \
    --tests '*CampaignRunWorkflowIntegrationTest' \
    --console=plain
) > "$RESULT_DIR/run.log" 2>&1; then
  printf 'FAIL: Gradle tests failed. Inspect %s/run.log\n' "$RESULT_DIR" >&2
  exit 1
fi

for suite in \
  com.axon.core_service.service.PurchaseFlowIntegrationTest \
  com.axon.core_service.service.CampaignRunWorkflowIntegrationTest; do
  report="$ROOT/services/core-service/build/test-results/test/TEST-$suite.xml"
  bash "$ROOT/scripts/ci/assert-test-suite-ran.sh" "$report"
  cp "$report" "$RESULT_DIR/"
done

head_after="$(git -C "$ROOT" rev-parse HEAD)"
git -C "$ROOT" status --porcelain=v1 > "$RESULT_DIR/worktree-after.txt"
snapshot_sources > "$RESULT_DIR/sources-after.sha256"
if [[ "$head_before" != "$head_after" ]] \
  || ! cmp -s "$RESULT_DIR/worktree-before.txt" "$RESULT_DIR/worktree-after.txt" \
  || ! cmp -s "$RESULT_DIR/sources-before.sha256" "$RESULT_DIR/sources-after.sha256"; then
  printf 'INCONCLUSIVE: Git state changed during execution; rerun after coordinating shared sessions.\n' >&2
  exit 3
fi

cat > "$RESULT_DIR/summary.txt" <<EOF
PASS: required suites executed without skipped tests.
HEAD: $head_after
Boundary: Core backend integration with disposable MySQL, Redis and Kafka.
External AI: controlled result at Core review APIs, not actual LangGraph/LLM/Slack.
Entry HTTP flow, browser UI, live external integrations and performance are excluded.
Working tree status snapshots and JUnit XML are included beside run.log.
EOF
printf 'PASS: backend integration regression. Results: %s\n' "$RESULT_DIR"
