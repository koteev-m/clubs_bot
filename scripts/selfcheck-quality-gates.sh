#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

fail() {
  echo "selfcheck: $1" >&2
  exit 1
}

assert_empty() {
  local value="${1:-}"
  if [ -n "$value" ]; then
    fail "expected empty output, got: $value"
  fi
}

assert_eq() {
  local left="$1"
  local right="$2"
  if [ "$left" != "$right" ]; then
    fail "expected '$right', got '$left'"
  fi
}

(
  cd "$TMP_DIR"
  git init -q
  git config user.email "selfcheck@example.com"
  git config user.name "selfcheck"

  echo "a" > README.md
  git add README.md
  git commit -q -m "init"

  empty_out="$(VERIFY_FROM_SHA=HEAD VERIFY_TO_SHA=HEAD "$ROOT_DIR/scripts/changed-kotlin-files.sh")"
  assert_empty "$empty_out"

  mkdir -p src
  cat > src/App.kt <<'KOT'
fun main() = println("ok")
KOT
  git add src/App.kt
  git commit -q -m "kotlin"

  single_commit_out="$($ROOT_DIR/scripts/changed-kotlin-files.sh)"
  assert_eq "$single_commit_out" "src/App.kt"
)

if DOCKER_BIN=__missing_docker__ "$ROOT_DIR/scripts/verify.sh" secret-scan; then
  fail "expected secret-scan to fail without docker"
else
  status=$?
  assert_eq "$status" "2"
fi

echo "selfcheck: OK"
