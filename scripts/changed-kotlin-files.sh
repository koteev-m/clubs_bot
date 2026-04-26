#!/usr/bin/env bash
set -euo pipefail

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

  if [ ${#existing[@]} -gt 0 ]; then
    printf '%s\n' "${existing[@]}"
  fi
}

collect_changed_kotlin_files
