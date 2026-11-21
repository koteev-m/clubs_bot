#!/usr/bin/env bash
set -euo pipefail

max_attempts="${RETRY_MAX_ATTEMPTS:-3}"
delay_seconds="${RETRY_DELAY_SECONDS:-10}"

if [ "$#" -eq 0 ]; then
  echo "Usage: scripts/retry-command.sh COMMAND [ARG ...]" >&2
  exit 2
fi

case "$max_attempts" in
  ''|*[!0-9]*|0)
    echo "RETRY_MAX_ATTEMPTS must be a positive integer" >&2
    exit 2
    ;;
esac

case "$delay_seconds" in
  ''|*[!0-9]*)
    echo "RETRY_DELAY_SECONDS must be a non-negative integer" >&2
    exit 2
    ;;
esac

if [ "$1" = "--maven-429" ]; then
  shift
  if [ "${1:-}" != "./gradlew" ] || [ "${2:-}" != "help" ]; then
    echo "The Maven retry applies only to Gradle help." >&2
    exit 2
  fi
  log=$(mktemp)
  trap 'rm -f "$log"' EXIT
  for ((attempt=1; attempt<=max_attempts; attempt++)); do
    if "$@" >"$log" 2>&1; then
      cat "$log"
      exit 0
    else
      status=$?
    fi
    cat "$log"
    if [ "$attempt" -eq "$max_attempts" ] ||
       ! grep -Eq "Could not (GET|HEAD) 'https://repo\.maven\.apache\.org/maven2/[^']+'\. Received status code 429 " "$log"; then
      exit "$status"
    fi
    echo "Maven Central rate limited Gradle help; retrying after $((delay_seconds * attempt))s." >&2
    sleep "$((delay_seconds * attempt))"
  done
fi

attempt=1
while true; do
  if "$@"; then
    exit 0
  else
    status=$?
  fi

  if [ "$attempt" -ge "$max_attempts" ]; then
    echo "Command failed after $attempt attempts (exit $status)" >&2
    exit "$status"
  fi

  echo "Command failed (attempt $attempt/$max_attempts); retrying in ${delay_seconds}s..." >&2
  if [ "$delay_seconds" -gt 0 ]; then
    sleep "$delay_seconds"
  fi
  attempt=$((attempt + 1))
done
