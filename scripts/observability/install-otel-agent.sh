#!/usr/bin/env bash
set -euo pipefail

OTEL_AGENT_DIR="${OTEL_AGENT_DIR:-./otel-agent}"
OTEL_AGENT_VERSION="${OTEL_AGENT_VERSION:-latest}"

mkdir -p "$OTEL_AGENT_DIR"

if [ "$OTEL_AGENT_VERSION" = "latest" ]; then
  OTEL_AGENT_URL="https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar"
else
  OTEL_AGENT_URL="https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar"
fi

curl -fL "$OTEL_AGENT_URL" -o "$OTEL_AGENT_DIR/opentelemetry-javaagent.jar"

echo "Installed OpenTelemetry Java agent:"
ls -lh "$OTEL_AGENT_DIR/opentelemetry-javaagent.jar"
