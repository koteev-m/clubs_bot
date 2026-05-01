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
  run_cmd ./gradlew --no-daemon --no-configuration-cache scaCheck
  run_cmd ./gradlew --no-daemon test -PrunIT=true
  run_secret_scan
}

run_sca_warm_cache() {
  run_cmd ./gradlew --no-daemon --no-configuration-cache dependencyCheckUpdate scaWarmCacheMark
}


run_lint() {
  run_cmd ./gradlew --no-daemon detektGate

  run_cmd ./scripts/ktlint-changed.sh
}

run_secret_scan() {
  local gitleaks_image="${GITLEAKS_IMAGE:-ghcr.io/gitleaks/gitleaks@sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854}"
  local docker_bin="${DOCKER_BIN:-docker}"
  if ! command -v "$docker_bin" >/dev/null 2>&1; then
    echo "Secret scan requires Docker. Install Docker or run this gate in GitHub Actions."
    return 2
  fi

  run_cmd "$docker_bin" run --rm -v "$PWD:/repo" -w /repo "$gitleaks_image" \
    detect --source . --report-format sarif --report-path gitleaks.sarif --redact --verbose
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
  sca-warm-cache)
    run_sca_warm_cache
    ;;
  *)
    printf 'Usage: scripts/verify.sh [full|ci|lint|secret-scan|sca-warm-cache]\n' >&2
    exit 2
    ;;
esac

printf '\nverify.sh completed in %s mode.\n' "$mode"
