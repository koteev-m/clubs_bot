#!/usr/bin/env bash
set -euo pipefail

max_attempts="${RETRY_MAX_ATTEMPTS:-3}"
delay_seconds="${RETRY_DELAY_SECONDS:-10}"

if [[ ${1:-} != --maven-429 ]]; then
  echo "Usage: scripts/retry-command.sh --maven-429 GRADLE_INVOCATION" >&2
  exit 2
fi
shift

case "$max_attempts" in
  1|2|3) ;;
  *) echo "The Maven retry allows one to three attempts." >&2; exit 2 ;;
esac
case "$delay_seconds" in
  0|[1-9]|[1-5][0-9]|60) ;;
  *) echo "RETRY_DELAY_SECONDS must be 0 to 60." >&2; exit 2 ;;
esac

# Gradle reports resolution failures as an indented tree. Every terminal cause
# must be a Central 429. A second failure, task execution or unfamiliar format
# keeps the original failure, including on mixed verification/transport errors.
only_configuration_rate_limits() {
  awk '
    /^> Task / { invalid = 1 }
    /^FAILURE:/ { if ($0 != "FAILURE: Build failed with an exception.") invalid = 1; failures++ }
    /^\* What went wrong:$/ { sections++; in_reason = 1; next }
    /^\* Try:$/ { if (in_reason) closed++; in_reason = 0; next }
    /^$/ { next }
    closed && /^BUILD FAILED in [0-9]/ { completed++; next }
    closed && (/^> (Run with |Get more help at )/ || /^Configuration cache entry (stored|discarded)\.$/) { next }
    closed { invalid = 1; next }
    !in_reason { next }
    /^A problem occurred configuring (root )?project .*\.$/ { roots++; next }
    /^ +Required by:$/ || /^ +(buildscript of )?(root project|project) [^>]/ { next }
    /^ *> / {
      depth = index($0, ">")
      if (previous && depth <= previous && !terminal) invalid = 1
      previous = depth
      sub(/^ *> /, "")
      terminal = /^Could not (GET|HEAD) \047https:\/\/repo\.maven\.apache\.org\/maven2\/[^\047]+\047\. Received status code 429 from server: Too Many Requests$/
      if (terminal) rate_limits++
      else if (/^There (is|are) [1-9][0-9]* more failure(s)? with (an )?identical cause(s)?\.$/ && rate_limits) terminal = 1
      else if (!/^Could not (resolve |get resource |parse POM )/) invalid = 1
      next
    }
    { invalid = 1 }
    END { exit !(failures == 1 && sections == 1 && roots == 1 && closed == 1 && completed == 1 && rate_limits && terminal && !invalid) }
  ' "$1"
}

matches_gradle_invocation() {
  local argument
  local -a expected
  if [[ ${2:-} == help ]]; then
    expected=(./gradlew help --dependency-verification=strict --no-configuration-cache --no-build-cache --console=plain)
  else
    expected=(./gradlew --no-daemon :app-bot:installDist -x test --dependency-verification=strict --no-configuration-cache --console=plain)
  fi
  [[ $# -eq ${#expected[@]} ]] || return 1
  for argument in "${expected[@]}"; do
    [[ $1 == "$argument" ]] || return 1
    shift
  done
}

if ! matches_gradle_invocation "$@"; then
  echo "Unsupported Gradle invocation for --maven-429." >&2
  exit 2
fi
log=$(mktemp)
trap 'rm -f "$log"' EXIT
for ((attempt=1; attempt<=max_attempts; attempt++)); do
  if "$@" 2>&1 | tee "$log"; then
    exit 0
  else
    statuses=("${PIPESTATUS[@]}")
    if [ "${statuses[1]}" -ne 0 ]; then exit "${statuses[1]}"; fi
    status="${statuses[0]}"
  fi
  if [ "$attempt" -eq "$max_attempts" ] || ! only_configuration_rate_limits "$log"; then
    exit "$status"
  fi
  echo "Maven Central rate limited Gradle configuration; retrying after $((delay_seconds * attempt))s." >&2
  sleep "$((delay_seconds * attempt))"
done
