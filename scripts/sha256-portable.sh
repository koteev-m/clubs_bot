#!/usr/bin/env bash
set -euo pipefail

sha256_file() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
    return 0
  fi
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
    return 0
  fi
  echo "Neither sha256sum nor shasum -a 256 is available" >&2
  return 1
}

sha256_stdin() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{print $1}'
    return 0
  fi
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 | awk '{print $1}'
    return 0
  fi
  echo "Neither sha256sum nor shasum -a 256 is available" >&2
  return 1
}

print_checksum_mismatch() {
  local file="$1"
  local expected="$2"
  local actual="$3"
  echo "Checksum mismatch for $file" >&2
  echo "Expected: $expected" >&2
  echo "Actual:   $actual" >&2
}

verify_sha256() {
  local file="$1"
  local expected="$2"
  local actual
  actual="$(sha256_file "$file")"
  if [ "$actual" != "$expected" ]; then
    print_checksum_mismatch "$file" "$expected" "$actual"
    return 1
  fi
}
