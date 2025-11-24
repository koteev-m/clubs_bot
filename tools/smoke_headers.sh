#!/usr/bin/env bash
set -euo pipefail
URL="${1:-http://localhost:8080}"

print_headers_with_status() {
  local target="$1"
  local pattern="$2"

  local response
  response=$(curl -sSI -w $'\n%{http_code}' "$target")
  local status
  status=$(echo "$response" | tail -n1)
  local headers
  headers=$(echo "$response" | sed '$d')

  echo "$headers" | grep -Ei "$pattern" || true
  echo "HTTP $status"
}

echo "== GET $URL =="
print_headers_with_status "$URL" 'strict-transport-security|referrer-policy|x-content-type-options|permissions-policy|content-security-policy'
echo

echo "== GET $URL/webapp/entry/app.js =="
entry_response=$(curl -sSI -w $'\n%{http_code}' "$URL/webapp/entry/app.js")
entry_status=$(echo "$entry_response" | tail -n1)
entry_headers=$(echo "$entry_response" | sed '$d')
echo "$entry_headers" | grep -i 'cache-control' || true
echo "HTTP $entry_status"

entry_etag=$(echo "$entry_headers" | awk 'BEGIN{IGNORECASE=1} /^etag:/ {sub(/^etag:[ ]*/i, "", $0); print; exit}')
if [[ -n "${entry_etag:-}" ]]; then
  revalidate_response=$(curl -sSI -H "If-None-Match: $entry_etag" -w $'\n%{http_code}' "$URL/webapp/entry/app.js")
  revalidate_status=$(echo "$revalidate_response" | tail -n1)
  echo "If-None-Match: $entry_etag → HTTP $revalidate_status"
fi

echo
echo "Tip: to check CSP for WebApp explicitly:"
echo "curl -sSI \"$URL/webapp/entry\" | grep -Ei 'content-security-policy'"
echo
echo "Done."
