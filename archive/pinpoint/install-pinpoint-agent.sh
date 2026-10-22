#!/usr/bin/env bash
set -euo pipefail

PINPOINT_AGENT_CONTAINER="${PINPOINT_AGENT_CONTAINER:-pinpoint-agent}"
PINPOINT_AGENT_DIR="${PINPOINT_AGENT_DIR:-./pinpoint-agent}"

if ! docker ps --format '{{.Names}}' | grep -qx "$PINPOINT_AGENT_CONTAINER"; then
  echo "Pinpoint agent container is not running: $PINPOINT_AGENT_CONTAINER" >&2
  echo "Start the official pinpoint-docker stack first." >&2
  exit 1
fi

rm -rf "$PINPOINT_AGENT_DIR"
mkdir -p "$PINPOINT_AGENT_DIR"

docker cp "$PINPOINT_AGENT_CONTAINER:/pinpoint-agent/." "$PINPOINT_AGENT_DIR/"

BOOTSTRAP="$(find "$PINPOINT_AGENT_DIR" -maxdepth 1 -name 'pinpoint-bootstrap*.jar' | head -1)"
if [ -z "$BOOTSTRAP" ]; then
  echo "Pinpoint bootstrap jar was not found under $PINPOINT_AGENT_DIR" >&2
  exit 1
fi

ln -sf "$(basename "$BOOTSTRAP")" "$PINPOINT_AGENT_DIR/pinpoint-bootstrap.jar"

echo "Installed Pinpoint agent into $PINPOINT_AGENT_DIR"
echo "Bootstrap: $PINPOINT_AGENT_DIR/pinpoint-bootstrap.jar -> $(basename "$BOOTSTRAP")"
