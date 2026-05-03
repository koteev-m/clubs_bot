#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d)"
SCA_CACHE_DIR="$TMP_DIR/sca-cache"
SCA_MARKER="$SCA_CACHE_DIR/cache-warm.marker"
SCA_MANIFEST="$SCA_CACHE_DIR/cache-warm.manifest"
SCA_VALID_LOG="$TMP_DIR/sca-valid.log"
SCA_MARKER_ONLY_LOG="$TMP_DIR/sca-marker-only.log"
SCA_JUNK_LOG="$TMP_DIR/sca-junk.log"
SCA_STALE_LOG="$TMP_DIR/sca-stale.log"
SCA_SAME_SIZE_LOG="$TMP_DIR/sca-same-size.log"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

fail() {
  echo "selfcheck: $1" >&2
  exit 1
}

epoch_millis() {
  if ts="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
  )" && [ -n "$ts" ]; then
    printf '%s\n' "$ts"
    return 0
  fi

  if date +%s%3N >/dev/null 2>&1; then
    date +%s%3N
    return 0
  fi

  printf '%s000\n' "$(date +%s)"
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

assert_contains() {
  local haystack="$1"
  local needle="$2"
  if [[ "$haystack" != *"$needle"* ]]; then
    fail "expected output to contain '$needle', got: $haystack"
  fi
}

mkdir -p "$SCA_CACHE_DIR"
printf 'warmedAt=%s\nmaxAgeHours=168\n' "$(epoch_millis)" > "$SCA_MARKER"
if NVD_API_KEY= ./gradlew --no-configuration-cache -PdependencyCheckDataDir="$SCA_CACHE_DIR" scaPreflight --console=plain >"$SCA_MARKER_ONLY_LOG" 2>&1; then
  fail "expected scaPreflight to fail on marker-only cache"
fi
assert_contains "$(cat "$SCA_MARKER_ONLY_LOG")" "Warm marker/manifest not found"

printf 'warmedAt=%s\nmaxAgeHours=168\n' "$(epoch_millis)" > "$SCA_MARKER"
mkdir -p "$SCA_CACHE_DIR/data/cache"
printf 'payload' > "$SCA_CACHE_DIR/data/cache/nvd.json"
payload_size="$(wc -c < "$SCA_CACHE_DIR/data/cache/nvd.json" | tr -d ' ')"
payload_sha="$(sha256sum "$SCA_CACHE_DIR/data/cache/nvd.json" | awk '{print $1}')"
payload_digest="$(printf 'data/cache/nvd.json:%s:%s' "$payload_size" "$payload_sha" | sha256sum | awk '{print $1}')"
printf 'payloadFileCount=1\npayloadTotalBytes=%s\npayloadDigest=%s\nfile=data/cache/nvd.json|%s|%s\n' \
  "$payload_size" "$payload_digest" "$payload_size" "$payload_sha" > "$SCA_MANIFEST"
if ! NVD_API_KEY= ./gradlew --no-configuration-cache -PdependencyCheckDataDir="$SCA_CACHE_DIR" scaPreflight --console=plain >"$SCA_VALID_LOG" 2>&1; then
  fail "expected scaPreflight to pass with valid cache manifest"
fi

printf 'payloadFileCount=1\npayloadTotalBytes=9999\npayloadDigest=junk\nfile=data/cache/nvd.json|%s|junk\n' "$payload_size" > "$SCA_MANIFEST"
if NVD_API_KEY= ./gradlew --no-configuration-cache -PdependencyCheckDataDir="$SCA_CACHE_DIR" scaPreflight --console=plain >"$SCA_JUNK_LOG" 2>&1; then
  fail "expected scaPreflight to fail on junk payload manifest"
fi
assert_contains "$(cat "$SCA_JUNK_LOG")" "warm manifest does not match cache payload"

printf 'payloadFileCount=1\npayloadTotalBytes=%s\npayloadDigest=%s\nfile=data/cache/nvd.json|%s|%s\n' \
  "$payload_size" "$payload_digest" "$payload_size" "$payload_sha" > "$SCA_MANIFEST"
printf 'warmedAt=1\nmaxAgeHours=168\n' > "$SCA_MARKER"
if NVD_API_KEY= ./gradlew --no-configuration-cache -PdependencyCheckDataDir="$SCA_CACHE_DIR" scaPreflight --console=plain >"$SCA_STALE_LOG" 2>&1; then
  fail "expected scaPreflight to fail on stale cache marker"
fi
assert_contains "$(cat "$SCA_STALE_LOG")" "local cache is stale"

printf 'warmedAt=%s\nmaxAgeHours=168\n' "$(epoch_millis)" > "$SCA_MARKER"
printf 'abc1234' > "$SCA_CACHE_DIR/data/cache/nvd.json"
if NVD_API_KEY= ./gradlew --no-configuration-cache -PdependencyCheckDataDir="$SCA_CACHE_DIR" scaPreflight --console=plain >"$SCA_SAME_SIZE_LOG" 2>&1; then
  fail "expected scaPreflight to fail on same-size-different-content payload"
fi
assert_contains "$(cat "$SCA_SAME_SIZE_LOG")" "warm manifest does not match cache payload"

(
  cd "$TMP_DIR"
  git init -q
  git config user.email "selfcheck@example.com"
  git config user.name "selfcheck"

  mkdir -p src
  cat > src/App.kt <<'KOT'
fun main() = println("ok")
KOT
  git add src/App.kt
  git commit -q -m "root"

  root_out="$($ROOT_DIR/scripts/changed-kotlin-files.sh)"
  assert_eq "$root_out" "src/App.kt"

  empty_out="$(VERIFY_FROM_SHA=HEAD VERIFY_TO_SHA=HEAD "$ROOT_DIR/scripts/changed-kotlin-files.sh")"
  assert_empty "$empty_out"

  ktlint_skip_out="$(VERIFY_FROM_SHA=HEAD VERIFY_TO_SHA=HEAD KTLINT_BIN=__missing_ktlint__ "$ROOT_DIR/scripts/ktlint-changed.sh")"
  assert_contains "$ktlint_skip_out" "No changed Kotlin files"

  echo "note" > README.md
  git add README.md
  git commit -q -m "non-kotlin"

  no_kotlin_out="$($ROOT_DIR/scripts/changed-kotlin-files.sh)"
  assert_empty "$no_kotlin_out"

  source_branch="$(git branch --show-current)"

  git checkout -q -b feature/merge-base
  cat > src/Feature.kt <<'KOT'
fun feature() = 1
KOT
  git add src/Feature.kt
  git commit -q -m "feature adds kotlin"
  feature_head="$(git rev-parse HEAD)"

  git checkout -q "$source_branch"
  cat > src/Mainline.kt <<'KOT'
fun mainline() = 2
KOT
  git add src/Mainline.kt
  git commit -q -m "mainline kotlin"

  merge_base_out="$(VERIFY_FROM_SHA="$feature_head" VERIFY_TO_SHA=HEAD "$ROOT_DIR/scripts/changed-kotlin-files.sh")"
  assert_eq "$merge_base_out" "src/Mainline.kt"

  rm src/Mainline.kt
  git add -A
  git commit -q -m "delete kotlin"
  deleted_out="$(VERIFY_FROM_SHA=HEAD~1 VERIFY_TO_SHA=HEAD "$ROOT_DIR/scripts/changed-kotlin-files.sh")"
  assert_empty "$deleted_out"
)

if DOCKER_BIN=__missing_docker__ "$ROOT_DIR/scripts/verify.sh" secret-scan; then
  fail "expected secret-scan to fail without docker"
else
  status=$?
  assert_eq "$status" "2"
fi

usage_out="$("$ROOT_DIR/scripts/refresh-verification-metadata.sh" unknown 2>&1 || true)"
assert_contains "$usage_out" "Usage: scripts/refresh-verification-metadata.sh [default|sca]"

verify_usage_out="$("$ROOT_DIR/scripts/verify.sh" unknown 2>&1 || true)"
assert_contains "$verify_usage_out" "Usage: scripts/verify.sh [full|ci|lint|secret-scan|sca-warm-cache]"

echo "selfcheck: OK"
