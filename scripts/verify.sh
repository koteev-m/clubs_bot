#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

mode="${1:-full}"

run_cmd() {
  echo "\n==> $*"
  "$@"
}

run_full() {
  run_cmd ./gradlew --no-daemon formatAll
  run_cmd ./gradlew --no-daemon staticCheck
  run_cmd ./gradlew --no-daemon test
  run_cmd ./gradlew --no-daemon test -PrunIT=true
}

run_ci() {
  run_cmd ./gradlew --no-daemon clean staticCheck test -PrunIT=true
}

case "$mode" in
  full)
    run_full
    ;;
  ci)
    run_ci
    ;;
  *)
    echo "Usage: scripts/verify.sh [full|ci]" >&2
    exit 2
    ;;
esac

echo "\nverify.sh completed in '$mode' mode."
