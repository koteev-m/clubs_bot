#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

TASK="${1:-dependencyCheckAggregate}"

./gradlew --write-verification-metadata sha256 "$TASK" --console=plain

echo "Updated gradle/verification-metadata.xml for task: $TASK"
