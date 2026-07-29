#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="${KTLINT_REPO_DIR:-$ROOT_DIR}"
cd "$REPO_DIR"

main() {
  local changed_files
  changed_files="$("$ROOT_DIR/scripts/changed-kotlin-files.sh")"
  if [ -z "$changed_files" ]; then
    echo "No changed Kotlin files — ktlint gate skipped"
    return 0
  fi

  local gradlew_bin="${GRADLEW_BIN:-$REPO_DIR/gradlew}"
  if [ ! -x "$gradlew_bin" ]; then
    echo "Gradle wrapper is not executable: $gradlew_bin" >&2
    return 2
  fi

  echo "Kotlin changes detected — running baseline-aware ktlintCheck"
  "$gradlew_bin" ktlintCheck --console=plain
}

main "$@"
