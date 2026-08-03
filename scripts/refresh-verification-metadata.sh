#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

print_usage() {
  cat <<'USAGE' >&2
Usage: scripts/refresh-verification-metadata.sh [default]

Modes:
  default  Refresh metadata for lightweight Gradle task graph (help).

Notes:
  - This script updates Gradle dependency verification metadata only.
  - Dependency graph submission runs only from trusted main; dependency vulnerability
    checks remain the responsibility of the existing fail-closed Trivy scans.
USAGE
}

mode="${1:-default}"

case "$mode" in
  default)
    TASK="help"
    ;;
  *)
    print_usage
    exit 2
    ;;
esac

./gradlew --write-verification-metadata sha256 "$TASK" --console=plain

echo "Updated gradle/verification-metadata.xml for task: $TASK (mode=$mode)"
