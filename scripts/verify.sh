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
  run_cmd ./gradlew --no-daemon clean coverageGate scaCheck
  run_cmd ./gradlew --no-daemon test -PrunIT=true
  run_secret_scan
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
    printf '\n'
  fi
}

collect_changed_kotlin_files() {
  local to="${VERIFY_TO_SHA:-HEAD}"
  local range
  range="$(resolve_diff_range)"

  local files=()
  if [ -n "$range" ]; then
    mapfile -t files < <(
      git diff --name-only --diff-filter=ACMR "$range" -- '*.kt' '*.kts' | sed '/^$/d'
    )
  else
    mapfile -t files < <(
      git diff-tree --no-commit-id --name-only -r --diff-filter=ACMR "$to" -- '*.kt' '*.kts' | sed '/^$/d'
    )
  fi

  local existing=()
  local file
  for file in "${files[@]}"; do
    [ -f "$file" ] && existing+=("$file")
  done
  printf '%s\n' "${existing[@]}"
}

install_ktlint_cli() {
  local ktlint_bin="$ROOT_DIR/.tools/ktlint"
  local ktlint_version="${KTLINT_VERSION:-1.3.1}"
  local ktlint_sha256="${KTLINT_SHA256:-a9f923be58fbd32670a17f0b729b1df804af882fa57402165741cb26e5440ca1}"

  if [ ! -x "$ktlint_bin" ]; then
    mkdir -p "$(dirname "$ktlint_bin")"
    curl -fsSL -o "$ktlint_bin" "https://github.com/pinterest/ktlint/releases/download/${ktlint_version}/ktlint"
    chmod +x "$ktlint_bin"
  fi
  echo "${ktlint_sha256}  ${ktlint_bin}" | sha256sum -c - >/dev/null
  printf '%s\n' "$ktlint_bin"
}

run_lint() {
  run_cmd ./gradlew --no-daemon detektGate

  local files=()
  mapfile -t files < <(collect_changed_kotlin_files)
  if [ ${#files[@]} -eq 0 ]; then
    echo "No changed Kotlin files — ktlint gate skipped"
    return 0
  fi

  local ktlint_bin
  ktlint_bin="$(install_ktlint_cli)"
  run_cmd "$ktlint_bin" "${files[@]}"
}

run_secret_scan() {
  local gitleaks_image="${GITLEAKS_IMAGE:-ghcr.io/gitleaks/gitleaks@sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854}"
  if ! command -v docker >/dev/null 2>&1; then
    echo "Secret scan requires Docker. Install Docker or run this gate in GitHub Actions."
    return 2
  fi

  run_cmd docker run --rm -v "$PWD:/repo" -w /repo "$gitleaks_image" \
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
  *)
    printf 'Usage: scripts/verify.sh [full|ci|lint|secret-scan]\n' >&2
    exit 2
    ;;
esac

printf '\nverify.sh completed in %s mode.\n' "$mode"
