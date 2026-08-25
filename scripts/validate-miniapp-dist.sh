#!/usr/bin/env bash

set -euo pipefail

fail() {
  echo "miniapp-dist: $1" >&2
  exit 1
}

if [ "$#" -gt 1 ]; then
  fail "usage: validate-miniapp-dist.sh [dist-directory]"
fi

dist_dir="${1:-miniapp/dist}"
index_file="$dist_dir/index.html"
assets_dir="$dist_dir/assets"
public_asset_prefix="/app/react/assets/"

[ -f "$index_file" ] && [ ! -L "$index_file" ] && [ -s "$index_file" ] ||
  fail "index.html must be a nonempty regular file"
[ -d "$assets_dir" ] && [ ! -L "$assets_dir" ] ||
  fail "assets must be a regular directory"

if ! reference_output="$(
  awk -v public_prefix="$public_asset_prefix" '
    function emit_error(code) {
      print "error|" code
      exit 0
    }

    function reset_attributes() {
      attribute_structure_error = 0
      src_seen = src_malformed = src_duplicate = 0
      href_seen = href_malformed = href_duplicate = 0
      rel_seen = rel_malformed = rel_duplicate = 0
      as_seen = as_malformed = as_duplicate = 0
      src_value = href_value = rel_value = as_value = ""
    }

    function remember_attribute(name, value, malformed) {
      if (name == "src") {
        if (src_seen) src_duplicate = 1
        src_seen = 1
        src_value = value
        if (malformed) src_malformed = 1
      } else if (name == "href") {
        if (href_seen) href_duplicate = 1
        href_seen = 1
        href_value = value
        if (malformed) href_malformed = 1
      } else if (name == "rel") {
        if (rel_seen) rel_duplicate = 1
        rel_seen = 1
        rel_value = value
        if (malformed) rel_malformed = 1
      } else if (name == "as") {
        if (as_seen) as_duplicate = 1
        as_seen = 1
        as_value = value
        if (malformed) as_malformed = 1
      }
    }

    function parse_attributes(tag,    body, length_body, position, character, start, name, quote, value, malformed) {
      reset_attributes()
      body = tag
      sub(/^<[[:space:]]*[[:alpha:]][[:alnum:]_:-]*/, "", body)
      sub(/[[:space:]]*\/?>[[:space:]]*$/, "", body)
      length_body = length(body)
      position = 1

      while (position <= length_body) {
        while (position <= length_body && substr(body, position, 1) ~ /[[:space:]]/) position++
        if (position > length_body) break
        character = substr(body, position, 1)
        if (character == "/") {
          position++
          continue
        }

        start = position
        while (position <= length_body && substr(body, position, 1) !~ /[[:space:]=\/>]/) position++
        if (position == start) {
          attribute_structure_error = 1
          return
        }
        name = tolower(substr(body, start, position - start))

        while (position <= length_body && substr(body, position, 1) ~ /[[:space:]]/) position++
        if (position > length_body || substr(body, position, 1) != "=") {
          malformed = name == "src" || name == "href" || name == "rel" || name == "as"
          remember_attribute(name, "", malformed)
          continue
        }

        position++
        while (position <= length_body && substr(body, position, 1) ~ /[[:space:]]/) position++
        if (position > length_body) {
          remember_attribute(name, "", 1)
          return
        }

        quote = substr(body, position, 1)
        if (quote != "\"" && quote != "\047") {
          start = position
          while (position <= length_body && substr(body, position, 1) !~ /[[:space:]>]/) position++
          remember_attribute(name, substr(body, start, position - start), 1)
          continue
        }

        position++
        start = position
        while (position <= length_body && substr(body, position, 1) != quote) position++
        if (position > length_body) {
          attribute_structure_error = 1
          return
        }
        value = substr(body, start, position - start)
        position++
        remember_attribute(name, value, 0)
      }
    }

    function rel_has_token(value, expected,    tokens, count, token_index) {
      count = split(tolower(value), tokens, /[[:space:]]+/)
      for (token_index = 1; token_index <= count; token_index++) {
        if (tokens[token_index] == expected) return 1
      }
      return 0
    }

    function has_only_plain_tokens(value,    tokens, count, token_index, nonempty_count) {
      count = split(value, tokens, /[[:space:]]+/)
      nonempty_count = 0
      for (token_index = 1; token_index <= count; token_index++) {
        if (tokens[token_index] == "") continue
        if (tokens[token_index] !~ /^[A-Za-z0-9._:-]+$/) return 0
        nonempty_count++
      }
      return nonempty_count > 0
    }

    function is_safe_reference(reference, extension) {
      return reference ~ ("^" public_prefix "[A-Za-z0-9][A-Za-z0-9._-]*\\." extension "$")
    }

    function process_tag(tag,    lower_tag, relative_name) {
      lower_tag = tolower(tag)
      if (lower_tag ~ /^<[[:space:]]*\/[[:space:]]*script([[:space:]]|>|$)/) return
      if (lower_tag ~ /^<[[:space:]]*script([[:space:]]|\/|>|$)/) {
        parse_attributes(tag)
        if (attribute_structure_error || src_malformed || src_duplicate) {
          emit_error("malformed-script-src")
        }
        if (!src_seen) emit_error("missing-script-src")
        if (!is_safe_reference(src_value, "js")) emit_error("invalid-script-src")
        relative_name = substr(src_value, length(public_prefix) + 1)
        print "js|" relative_name
        return
      }

      if (lower_tag ~ /^<[[:space:]]*link([[:space:]]|\/|>|$)/) {
        parse_attributes(tag)
        if (attribute_structure_error || rel_malformed || rel_duplicate) {
          emit_error("malformed-link-resource")
        }
        if (!rel_seen) return
        if (!has_only_plain_tokens(rel_value)) emit_error("malformed-link-resource")
        if (rel_has_token(rel_value, "modulepreload")) emit_error("unsupported-modulepreload")
        if (rel_has_token(rel_value, "preload") || rel_has_token(rel_value, "prefetch")) {
          emit_error("unsupported-executable-style-link")
        }
        if (!rel_has_token(rel_value, "stylesheet")) return
        if (href_malformed || href_duplicate) emit_error("malformed-stylesheet-href")
        if (!href_seen) emit_error("missing-stylesheet-href")
        if (!is_safe_reference(href_value, "css")) emit_error("invalid-stylesheet-href")
        relative_name = substr(href_value, length(public_prefix) + 1)
        print "css|" relative_name
      }
    }

    { html = html $0 " " }

    END {
      # Generated production index.html has no comment requirement. Rejecting
      # comment syntax avoids browser/parser differentials such as <!-->.
      if (index(html, "<!--") || index(html, "-->")) emit_error("malformed-resource-tag")

      while (match(html, /<[^>]*>/)) {
        tag = substr(html, RSTART, RLENGTH)
        html = substr(html, RSTART + RLENGTH)
        process_tag(tag)
      }

      lower_remainder = tolower(html)
      if (lower_remainder ~ /<[[:space:]]*\/?[[:space:]]*(script|link)([[:space:]]|\/|>|$)/) {
        emit_error("malformed-resource-tag")
      }
    }
  ' "$index_file" 2>/dev/null
)"; then
  fail "index.html executable/style resource parsing failed"
fi

javascript_count=0
stylesheet_count=0
referenced_assets=("")

while IFS='|' read -r record_kind relative_path; do
  [ -n "$record_kind" ] || continue
  if [ "$record_kind" = "error" ]; then
    case "$relative_path" in
      malformed-script-src|malformed-link-resource|malformed-stylesheet-href|malformed-resource-tag)
        fail "index.html contains a malformed executable/style resource attribute"
        ;;
      missing-script-src) fail "every script tag must contain exactly one quoted src" ;;
      invalid-script-src) fail "script src must reference an exact local flat JavaScript asset" ;;
      missing-stylesheet-href) fail "every stylesheet link must contain exactly one quoted href" ;;
      invalid-stylesheet-href) fail "stylesheet href must reference an exact local flat CSS asset" ;;
      unsupported-modulepreload) fail "modulepreload resources are unsupported" ;;
      unsupported-executable-style-link) fail "preload and prefetch script/style resources are unsupported" ;;
      *) fail "index.html executable/style resource parsing failed" ;;
    esac
  fi

  case "$record_kind" in
    js) asset_kind="js" ;;
    css) asset_kind="css" ;;
    *) fail "index.html executable/style resource parsing failed" ;;
  esac

  for referenced_name in "${referenced_assets[@]}"; do
    [ -n "$referenced_name" ] || continue
    [ "$referenced_name" != "$relative_path" ] || fail "referenced asset names must be unique"
  done

  referenced_file="$assets_dir/$relative_path"
  [ -f "$referenced_file" ] && [ ! -L "$referenced_file" ] && [ -s "$referenced_file" ] ||
    fail "referenced asset must be a nonempty regular file"

  referenced_assets+=("$relative_path")
  if [ "$asset_kind" = "js" ]; then
    javascript_count=$((javascript_count + 1))
  else
    stylesheet_count=$((stylesheet_count + 1))
  fi
done <<<"$reference_output"

[ "$javascript_count" -gt 0 ] || fail "index.html must reference at least one JavaScript asset"
[ "$stylesheet_count" -gt 0 ] || fail "index.html must reference at least one stylesheet asset"

inventory_file="$(mktemp "${TMPDIR:-/tmp}/clubs-bot-miniapp-dist.XXXXXX" 2>/dev/null)" ||
  fail "could not create asset inventory"
trap 'rm -f -- "$inventory_file" 2>/dev/null || true' EXIT

if ! find "$assets_dir" -mindepth 1 -type l -print0 >"$inventory_file" 2>/dev/null; then
  fail "could not inspect asset symlinks"
fi
[ ! -s "$inventory_file" ] || fail "assets tree must not contain symbolic links"

if ! find "$assets_dir" -mindepth 1 \( -name 'index-*.js' -o -name 'index-*.css' \) -print0 >"$inventory_file" 2>/dev/null; then
  fail "could not inspect hashed asset inventory"
fi

actual_index_assets=("")
while IFS= read -r -d '' candidate_path; do
  relative_candidate="${candidate_path#"$assets_dir"/}"
  case "$relative_candidate" in
    */*) fail "hashed index assets must be direct children of the assets directory" ;;
  esac

  candidate_is_referenced=false
  for referenced_name in "${referenced_assets[@]}"; do
    [ -n "$referenced_name" ] || continue
    if [ "$relative_candidate" = "$referenced_name" ]; then
      candidate_is_referenced=true
      break
    fi
  done
  [ "$candidate_is_referenced" = true ] || fail "unreferenced stale hashed asset"
  actual_index_assets+=("$relative_candidate")
done <"$inventory_file"

for referenced_name in "${referenced_assets[@]}"; do
  [ -n "$referenced_name" ] || continue
  case "$referenced_name" in
    index-*.js|index-*.css)
      referenced_is_actual=false
      for actual_name in "${actual_index_assets[@]}"; do
        [ -n "$actual_name" ] || continue
        if [ "$referenced_name" = "$actual_name" ]; then
          referenced_is_actual=true
          break
        fi
      done
      [ "$referenced_is_actual" = true ] || fail "referenced hashed asset is absent from inventory"
      ;;
  esac
done

echo "miniapp-dist: OK"
