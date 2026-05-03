#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

print_usage() {
  cat <<'USAGE' >&2
Usage: scripts/refresh-verification-metadata.sh [default|sca]

Modes:
  default  Refresh metadata for lightweight Gradle task graph (help).
  sca      Heavy metadata refresh for SCA path (scaPreflight + dependencyCheckAggregate graph).

Notes:
  - This script updates Gradle dependency verification metadata only.
  - Mode "sca" traverses aggregate SCA task graph and preflight contract; it is not a lightweight toolchain-only action.
  - For real SCA gate run: ./gradlew --no-configuration-cache scaCheck --console=plain
USAGE
}

mode="${1:-default}"

case "$mode" in
  default)
    TASK="help"
    ;;
  sca)
    TASK="dependencyCheckAggregate"
    ;;
  *)
    print_usage
    exit 2
    ;;
esac

./gradlew --write-verification-metadata sha256 "$TASK" --console=plain

echo "Updated gradle/verification-metadata.xml for task: $TASK (mode=$mode)"
