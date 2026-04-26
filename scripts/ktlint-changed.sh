#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

QUALITY_GATES_ENV="$ROOT_DIR/scripts/quality-gates.env"
if [ -f "$QUALITY_GATES_ENV" ]; then
  # shellcheck disable=SC1090
  source "$QUALITY_GATES_ENV"
fi

install_ktlint_cli() {
  local ktlint_bin="${KTLINT_BIN:-$ROOT_DIR/.tools/ktlint}"
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

main() {
  local files=()
  mapfile -t files < <("$ROOT_DIR/scripts/changed-kotlin-files.sh")

  if [ ${#files[@]} -eq 0 ]; then
    echo "No changed Kotlin files — ktlint gate skipped"
    return 0
  fi

  local ktlint_bin
  ktlint_bin="$(install_ktlint_cli)"

  printf 'Running ktlint for %s files\n' "${#files[@]}"
  "$ktlint_bin" "${files[@]}"
}

main "$@"
