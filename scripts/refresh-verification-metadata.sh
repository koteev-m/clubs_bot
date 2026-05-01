#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

mode="${1:-default}"

case "$mode" in
  default)
    TASK="help"
    ;;
  sca)
    TASK="dependencyCheckAggregate"
    ;;
  *)
    echo "Usage: scripts/refresh-verification-metadata.sh [default|sca]" >&2
    exit 2
    ;;
esac

./gradlew --write-verification-metadata sha256 "$TASK" --console=plain

echo "Updated gradle/verification-metadata.xml for task: $TASK (mode=$mode)"
