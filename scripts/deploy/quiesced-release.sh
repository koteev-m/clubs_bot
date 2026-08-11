#!/usr/bin/env bash
set -euo pipefail

required_variables=(
  APP_ENV
  IMAGE_REPOSITORY
  IMAGE_TAG
  EXPECTED_REVISION
  SSH_USER
  SSH_HOST
  SSH_PORT
  COMPOSE_PATH
  REGISTRY_USERNAME
  REGISTRY_READ_TOKEN
  GITHUB_RUN_ID
  GITHUB_RUN_ATTEMPT
)
for variable_name in "${required_variables[@]}"; do
  if [ -z "${!variable_name:-}" ]; then
    echo "quiesced-release: required variable is empty: $variable_name" >&2
    exit 2
  fi
done

case "$APP_ENV" in
  stage|prod) ;;
  *) echo "quiesced-release: APP_ENV must be stage or prod" >&2; exit 2 ;;
esac
if [[ ! "$IMAGE_REPOSITORY" =~ ^ghcr\.io/[a-zA-Z0-9._/-]+$ ]]; then
  echo "quiesced-release: invalid IMAGE_REPOSITORY" >&2
  exit 2
fi
IMAGE_REPOSITORY="${IMAGE_REPOSITORY,,}"
if [[ ! "$IMAGE_TAG" =~ ^[a-zA-Z0-9._-]+$ ]]; then
  echo "quiesced-release: invalid IMAGE_TAG" >&2
  exit 2
fi
if [[ ! "$EXPECTED_REVISION" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "quiesced-release: EXPECTED_REVISION must be a full Git SHA" >&2
  exit 2
fi
if [[ ! "$SSH_USER" =~ ^[a-zA-Z0-9._-]+$ ]] ||
  [[ ! "$SSH_HOST" =~ ^[a-zA-Z0-9._:-]+$ ]] ||
  [[ ! "$SSH_PORT" =~ ^[0-9]{1,5}$ ]]; then
  echo "quiesced-release: invalid SSH target" >&2
  exit 2
fi
if [[ ! "$COMPOSE_PATH" =~ ^/[a-zA-Z0-9._/-]+$ ]]; then
  echo "quiesced-release: COMPOSE_PATH must be a simple absolute path" >&2
  exit 2
fi
if [[ ! "$REGISTRY_USERNAME" =~ ^[a-zA-Z0-9._-]+(\[bot\])?$ ]] ||
  [[ ! "$GITHUB_RUN_ID" =~ ^[0-9]+$ ]] ||
  [[ ! "$GITHUB_RUN_ATTEMPT" =~ ^[0-9]+$ ]]; then
  echo "quiesced-release: invalid release identity" >&2
  exit 2
fi

# Drop the exported workflow token before scp/ssh can inherit it. The lowercase
# copy is a shell-only value and is consumed solely by the preflight stdin pipe.
registry_read_token="$REGISTRY_READ_TOKEN"
unset REGISTRY_READ_TOKEN
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
remote_owner="${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
remote_script="/tmp/clubs-bot-release-${remote_owner}.sh"
ssh_target="${SSH_USER}@${SSH_HOST}"
maintenance_attempted=0
maintenance_acquired=0
verified_digest=""

remote_command() {
  local mode="$1"
  shift
  local command_parts=("$remote_script" "$mode" "$remote_owner" "$APP_ENV" "$@")
  local quoted_command
  printf -v quoted_command '%q ' "${command_parts[@]}"
  ssh -p "$SSH_PORT" "$ssh_target" "$quoted_command"
}

cleanup() {
  local exit_status=$?
  if [ "$maintenance_acquired" = "1" ]; then
    echo "quiesced-release: failure is fail-closed; maintenance lock owner=$remote_owner is retained" >&2
    echo "quiesced-release: inspect remote app state and Flyway history before manual recovery" >&2
  elif [ "$maintenance_attempted" = "1" ]; then
    echo "quiesced-release: quiesce did not complete; remote maintenance state is unknown" >&2
    echo "quiesced-release: inspect owner=$remote_owner, app state and Flyway history before recovery" >&2
  else
    ssh -p "$SSH_PORT" "$ssh_target" "rm -f '$remote_script'" >/dev/null 2>&1 || true
  fi
  trap - EXIT
  exit "$exit_status"
}
trap cleanup EXIT

scp -P "$SSH_PORT" \
  "$repository_root/scripts/deploy/remote-compose-release.sh" \
  "$ssh_target:$remote_script"
ssh -p "$SSH_PORT" "$ssh_target" "chmod 700 '$remote_script'"

preflight_remote_release() {
  local digest_prefix digest_hash
  verified_digest="$(
    printf '%s\n' "$registry_read_token" |
      remote_command \
        preflight \
        "$COMPOSE_PATH" \
        "$IMAGE_REPOSITORY" \
        "$IMAGE_TAG" \
        "$EXPECTED_REVISION" \
      "$REGISTRY_USERNAME"
  )"
  unset registry_read_token
  digest_prefix="${IMAGE_REPOSITORY}@sha256:"
  digest_hash="${verified_digest#"$digest_prefix"}"
  if [ "$digest_hash" = "$verified_digest" ] || [[ ! "$digest_hash" =~ ^[0-9a-f]{64}$ ]]; then
    echo "quiesced-release: remote preflight returned an invalid digest" >&2
    exit 1
  fi
}

quiesce_remote_release() {
  maintenance_attempted=1
  remote_command \
      quiesce \
      "$COMPOSE_PATH" \
      "$verified_digest" \
      "$EXPECTED_REVISION"
  maintenance_acquired=1
}

assert_remote_app_absent() {
  remote_command assert-absent
}

run_database_migration() {
  remote_command migrate
}

start_remote_release() {
  remote_command start
}

finish_remote_release() {
  remote_command cleanup
  maintenance_attempted=0
  maintenance_acquired=0
  ssh -p "$SSH_PORT" "$ssh_target" "rm -f '$remote_script'"
}

run_release() {
  preflight_remote_release
  quiesce_remote_release
  assert_remote_app_absent
  run_database_migration
  assert_remote_app_absent
  start_remote_release
  finish_remote_release
}

run_release
trap - EXIT

echo "quiesced-release: verified digest is healthy in $APP_ENV"
