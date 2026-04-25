#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

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
  run_cmd ./gradlew --no-daemon clean detektGate test coverageGate scaCheck
  run_cmd ./gradlew --no-daemon test -PrunIT=true
}

sha_exists() {
  local sha="${1:-}"
  [ -n "$sha" ] && git cat-file -e "${sha}^{commit}" >/dev/null 2>&1
}

is_zero_sha() {
  [ "${1:-}" = "0000000000000000000000000000000000000000" ]
}

resolve_diff_range() {
  local from="${VERIFY_FROM_SHA:-}"
  local to="${VERIFY_TO_SHA:-HEAD}"

  if [ -n "$from" ] && ! is_zero_sha "$from" && sha_exists "$from"; then
    printf '%s...%s\n' "$from" "$to"
    return
  fi

  local parent
  parent="$(git rev-parse "${to}^" 2>/dev/null || true)"
  if sha_exists "$parent"; then
    printf '%s...%s\n' "$parent" "$to"
  else
    printf '%s\n' "$to"
  fi
}

run_lint() {
  run_cmd ./gradlew --no-daemon detektGate

  local range
  range="$(resolve_diff_range)"

  local files=()
  mapfile -t files < <(
    git diff --name-only --diff-filter=ACMR "$range" -- '*.kt' '*.kts' | sed '/^$/d'
  )

  if [ ${#files[@]} -eq 0 ]; then
    echo "No changed Kotlin files — ktlint gate skipped"
    return 0
  fi

  local ktlint_bin="$ROOT_DIR/.tools/ktlint"
  local ktlint_version="1.3.1"
  local ktlint_sha256="a9f923be58fbd32670a17f0b729b1df804af882fa57402165741cb26e5440ca1"

  if [ ! -x "$ktlint_bin" ]; then
    mkdir -p "$(dirname "$ktlint_bin")"
    curl -fsSL -o "$ktlint_bin" "https://github.com/pinterest/ktlint/releases/download/${ktlint_version}/ktlint"
    chmod +x "$ktlint_bin"
  fi
  echo "${ktlint_sha256}  ${ktlint_bin}" | sha256sum -c -

  run_cmd "$ktlint_bin" "${files[@]}"
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
  *)
    printf 'Usage: scripts/verify.sh [full|ci|lint]\n' >&2
    exit 2
    ;;
esac

printf '\nverify.sh completed in %s mode.\n' "$mode"
