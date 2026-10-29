#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

QUALITY_GATES_ENV="$ROOT_DIR/scripts/quality-gates.env"
if [ -f "$QUALITY_GATES_ENV" ]; then
  # shellcheck disable=SC1090
  source "$QUALITY_GATES_ENV"
fi

mode="${1:-full}"

run_cmd() {
  printf '\n==> '
  printf '%q ' "$@"
  printf '\n'
  "$@"
}

run_full() {
  run_cmd ./gradlew --no-daemon formatAll
  run_cmd ./gradlew --no-daemon staticCheck
  run_cmd ./gradlew --no-daemon test
  run_cmd ./gradlew --no-daemon test -PrunIT=true
}

run_ci() {
  run_lint
  run_cmd ./gradlew --no-daemon clean coverageGate
  run_cmd ./gradlew --no-daemon --no-configuration-cache \
    verifyResolvedProductionDependencyGraph :app-bot:installDist \
    --dependency-verification=strict --console=plain
  run_cmd ./gradlew --no-daemon test -PrunIT=true
  run_secret_scan
}

run_lint() {
  run_cmd ./gradlew --no-daemon detektGate

  run_cmd ./scripts/ktlint-changed.sh
}

run_secret_scan() {
  local gitleaks_image="${GITLEAKS_IMAGE:-ghcr.io/gitleaks/gitleaks@sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854}"
  local args=(--image "$gitleaks_image")
  if [ -n "${SECRET_SCAN_OUTPUT_DIR:-}" ]; then
    args+=(--output "$SECRET_SCAN_OUTPUT_DIR")
  fi
  run_cmd python3 -I -S -B scripts/secret-scan.py "${args[@]}"
}

case "$mode" in
  full)
    run_full
    ;;
  ci)
    run_ci
    ;;
  lint)
    run_lint
    ;;
  secret-scan)
    run_secret_scan
    ;;
  *)
    printf 'Usage: scripts/verify.sh [full|ci|lint|secret-scan]\n' >&2
    exit 2
    ;;
esac

printf '\nverify.sh completed in %s mode.\n' "$mode"
