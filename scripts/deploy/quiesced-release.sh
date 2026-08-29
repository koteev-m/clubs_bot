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
IMAGE_REPOSITORY="$(printf '%s' "$IMAGE_REPOSITORY" | tr '[:upper:]' '[:lower:]')"
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
if [[ ! "$COMPOSE_PATH" =~ ^/[a-zA-Z0-9._/-]+$ ]] ||
  [[ "$COMPOSE_PATH" == *//* ]] || [[ "$COMPOSE_PATH" == */./* ]] ||
  [[ "$COMPOSE_PATH" == */../* ]]; then
  echo "quiesced-release: COMPOSE_PATH must be a canonical simple absolute path" >&2
  exit 2
fi
case "$COMPOSE_PATH" in
  /tmp|/tmp/*|/var/tmp|/var/tmp/*|/run|/run/*|/var/run|/var/run/*|/dev/shm|/dev/shm/*|/private/tmp|/private/tmp/*|/private/var/tmp|/private/var/tmp/*)
    echo "quiesced-release: COMPOSE_PATH is not an approved persistent root" >&2
    exit 2
    ;;
esac
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
verified_digest=""
operation_stdout=""
operation_stderr=""
status_stdout=""
status_stderr=""
status_checkpoint="unavailable"
status_operation_result="unavailable"
status_available="no"
status_abort_permitted="no"
status_resume_permitted="no"

umask 077

remote_command() {
  local remote_mode="$1"
  shift
  local command_parts=(bash "$remote_script" "$remote_mode" "$remote_owner" "$APP_ENV" "$@")
  local quoted_command
  printf -v quoted_command '%q ' "${command_parts[@]}"
  ssh -p "$SSH_PORT" "$ssh_target" "$quoted_command"
}

cleanup_local_files() {
  for local_file in "$operation_stdout" "$operation_stderr" "$status_stdout" "$status_stderr"; do
    if [ -n "$local_file" ]; then
      rm -f -- "$local_file"
    fi
  done
  operation_stdout=""
  operation_stderr=""
  status_stdout=""
  status_stderr=""
}

cleanup_on_exit() {
  local exit_status=$?
  trap - EXIT
  cleanup_local_files
  exit "$exit_status"
}
trap cleanup_on_exit EXIT

new_capture_files() {
  cleanup_local_files
  operation_stdout="$(mktemp "${TMPDIR:-/tmp}/clubs-release-output.XXXXXX")"
  operation_stderr="$(mktemp "${TMPDIR:-/tmp}/clubs-release-error.XXXXXX")"
  chmod 600 "$operation_stdout" "$operation_stderr"
}

emit_safe_migration_events() {
  local line
  [ -f "$operation_stderr" ] || return 0
  while IFS= read -r line; do
    case "$line" in
      'migration-safe:v=1 event=started') printf '%s\n' "$line" >&2 ;;
      migration-safe:v=1\ event=completed\ applied=*)
        if [[ "$line" =~ ^migration-safe:v=1\ event=completed\ applied=(0|[1-9][0-9]{0,9})$ ]]; then
          printf '%s\n' "$line" >&2
        fi
        ;;
      migration-safe:v=1\ event=failed\ phase=*)
        if [[ "$line" =~ ^migration-safe:v=1\ event=failed\ phase=(bootstrap|configuration|migration|validation|pending-check)\ category=(configuration|connection|authentication|migration|validation|cancelled|unexpected)$ ]]; then
          printf '%s\n' "$line" >&2
        fi
        ;;
    esac
  done <"$operation_stderr"
}

parse_status() {
  local line="$1"
  if [[ "$line" =~ ^release-status:v=1\ status_available=(yes|no)\ owner_match=(yes|no)\ revision_match=(yes|no)\ digest_match=(yes|no)\ checkpoint=(none|maintenance_prepared|prior_state_captured|candidate_override_published|app_stop_intent|app_quiesced|migration_started|migration_completed|candidate_start_begun|candidate_healthy|cleanup_started|cleanup_completed|abort_started|abort_completed|unavailable)\ operation_result=(success|remote_failure|incomplete_unknown|unavailable|malformed)\ migration_evidence=(present|absent|unknown|migration_outcome_requires_incident_reconciliation)\ app_state=(old_running|absent|candidate_running|replaced|ambiguous|unknown)\ abort_permitted=(yes|no)\ resume_permitted=(yes|no)\ failure_category=(none|untrusted_state_root)$ ]]; then
    status_available="${BASH_REMATCH[1]}"
    status_owner_match="${BASH_REMATCH[2]}"
    status_revision_match="${BASH_REMATCH[3]}"
    status_digest_match="${BASH_REMATCH[4]}"
    status_checkpoint="${BASH_REMATCH[5]}"
    status_operation_result="${BASH_REMATCH[6]}"
    status_abort_permitted="${BASH_REMATCH[9]}"
    status_resume_permitted="${BASH_REMATCH[10]}"
    return 0
  fi
  return 1
}

query_status_once() {
  local requested_operation="$1"
  local digest="$2"
  local status_exit
  status_stdout="$(mktemp "${TMPDIR:-/tmp}/clubs-release-status.XXXXXX")"
  status_stderr="$(mktemp "${TMPDIR:-/tmp}/clubs-release-status-error.XXXXXX")"
  chmod 600 "$status_stdout" "$status_stderr"
  set +e
  remote_command \
    status \
    "$COMPOSE_PATH" \
    "$EXPECTED_REVISION" \
    "$digest" \
    "$requested_operation" >"$status_stdout" 2>"$status_stderr"
  status_exit=$?
  set -e
  [ "$status_exit" = "0" ] || return 1
  [ "$(wc -l <"$status_stdout" | tr -d ' ')" = "1" ] || return 1
  parse_status "$(cat "$status_stdout")"
}

recovery_for_status() {
  if [ "$status_abort_permitted" = "yes" ]; then
    printf '%s' "explicit-abort"
    return
  fi
  if [ "$status_resume_permitted" = "yes" ]; then
    case "$status_checkpoint" in
      candidate_override_published|app_stop_intent) printf '%s' "explicit-resume-quiesce" ;;
      app_quiesced) printf '%s' "explicit-resume-migrate" ;;
      migration_completed|candidate_start_begun) printf '%s' "explicit-resume-start" ;;
      candidate_healthy|cleanup_started) printf '%s' "explicit-resume-cleanup" ;;
      *) printf '%s' "manual-investigation" ;;
    esac
    return
  fi
  printf '%s' "manual-investigation"
}

stop_with_outcome() {
  local outcome="$1"
  local operation="$2"
  local recovery="$3"
  echo "quiesced-release: outcome=$outcome operation=$operation checkpoint=$status_checkpoint" >&2
  echo "quiesced-release: recovery=$recovery" >&2
  exit 1
}

classify_ambiguous_operation() {
  local requested_operation="$1"
  local digest="$2"
  if ! query_status_once "$requested_operation" "$digest"; then
    stop_with_outcome "status_unavailable" "$requested_operation" "manual-investigation"
  fi
  if [ "$status_available" != "yes" ]; then
    stop_with_outcome "status_unavailable" "$requested_operation" "manual-investigation"
  fi
  if [ "$status_owner_match" != "yes" ] || [ "$status_revision_match" != "yes" ] ||
    [ "$status_digest_match" != "yes" ]; then
    stop_with_outcome "status_unavailable" "$requested_operation" "manual-investigation"
  fi
  case "$status_operation_result" in
    success)
      stop_with_outcome "completed_but_acknowledgement_lost" "$requested_operation" "$(recovery_for_status)"
      ;;
    remote_failure)
      stop_with_outcome "confirmed_remote_failure" "$requested_operation" "$(recovery_for_status)"
      ;;
    incomplete_unknown|unavailable)
      case "$status_checkpoint" in
        none|unavailable)
          stop_with_outcome "status_unavailable" "$requested_operation" "manual-investigation"
          ;;
        *)
          stop_with_outcome "transport_loss_with_durable_checkpoint" "$requested_operation" "$(recovery_for_status)"
          ;;
      esac
      ;;
    malformed)
      stop_with_outcome "status_unavailable" "$requested_operation" "manual-investigation"
      ;;
  esac
}

preflight_remote_release() {
  local ssh_exit_code digest_prefix digest_hash acknowledgement
  new_capture_files
  set +e
  printf '%s\n' "$registry_read_token" |
    remote_command \
      preflight \
      "$COMPOSE_PATH" \
      "$IMAGE_REPOSITORY" \
      "$IMAGE_TAG" \
      "$EXPECTED_REVISION" \
      "$REGISTRY_USERNAME" >"$operation_stdout" 2>"$operation_stderr"
  ssh_exit_code=$?
  set -e
  unset registry_read_token
  if [ "$ssh_exit_code" != "0" ] && [ "$ssh_exit_code" != "255" ]; then
    stop_with_outcome "confirmed_remote_failure" "preflight" "run-explicit-read-only-status"
  fi
  if [ "$ssh_exit_code" = "255" ]; then
    classify_ambiguous_operation "preflight" "unknown"
  fi
  [ "$(wc -l <"$operation_stdout" | tr -d ' ')" = "1" ] ||
    classify_ambiguous_operation "preflight" "unknown"
  acknowledgement="$(cat "$operation_stdout")"
  if [[ ! "$acknowledgement" =~ ^release-operation:v=1\ result=success\ digest=(ghcr\.io/[a-z0-9._/-]+@sha256:[0-9a-f]{64})$ ]]; then
    classify_ambiguous_operation "preflight" "unknown"
  fi
  verified_digest="${BASH_REMATCH[1]}"
  digest_prefix="${IMAGE_REPOSITORY}@sha256:"
  digest_hash="${verified_digest#"$digest_prefix"}"
  if [ "$digest_hash" = "$verified_digest" ] || [[ ! "$digest_hash" =~ ^[0-9a-f]{64}$ ]]; then
    classify_ambiguous_operation "preflight" "$verified_digest"
  fi
}

execute_release_operation() {
  local requested_operation="$1"
  shift
  local ssh_exit_code acknowledgement
  new_capture_files
  set +e
  remote_command "$requested_operation" "$@" >"$operation_stdout" 2>"$operation_stderr"
  ssh_exit_code=$?
  set -e
  if [ "$requested_operation" = "migrate" ]; then
    emit_safe_migration_events
  fi
  if [ "$ssh_exit_code" != "0" ] && [ "$ssh_exit_code" != "255" ]; then
    stop_with_outcome "confirmed_remote_failure" "$requested_operation" "run-explicit-read-only-status"
  fi
  if [ "$ssh_exit_code" = "255" ]; then
    classify_ambiguous_operation "$requested_operation" "$verified_digest"
  fi
  [ "$(wc -l <"$operation_stdout" | tr -d ' ')" = "1" ] ||
    classify_ambiguous_operation "$requested_operation" "$verified_digest"
  acknowledgement="$(cat "$operation_stdout")"
  if [ "$acknowledgement" != "release-operation:v=1 result=success" ]; then
    classify_ambiguous_operation "$requested_operation" "$verified_digest"
  fi
}

upload_remote_helper() {
  local scp_stdout scp_stderr scp_exit
  scp_stdout="$(mktemp "${TMPDIR:-/tmp}/clubs-release-scp.XXXXXX")"
  scp_stderr="$(mktemp "${TMPDIR:-/tmp}/clubs-release-scp-error.XXXXXX")"
  chmod 600 "$scp_stdout" "$scp_stderr"
  set +e
scp -P "$SSH_PORT" \
    "$repository_root/scripts/deploy/remote-compose-release.sh" \
    "$ssh_target:$remote_script" >"$scp_stdout" 2>"$scp_stderr"
  scp_exit=$?
  set -e
  rm -f -- "$scp_stdout" "$scp_stderr"
  if [ "$scp_exit" != "0" ]; then
    status_checkpoint="unavailable"
    stop_with_outcome "status_unavailable" "upload" "manual-investigation"
  fi
}

run_release() {
  upload_remote_helper
  preflight_remote_release
  execute_release_operation prepare "$COMPOSE_PATH" "$verified_digest" "$EXPECTED_REVISION"
  execute_release_operation publish "$COMPOSE_PATH" "$verified_digest" "$EXPECTED_REVISION"
  execute_release_operation quiesce "$COMPOSE_PATH" "$verified_digest" "$EXPECTED_REVISION"
  execute_release_operation migrate "$COMPOSE_PATH" "$verified_digest" "$EXPECTED_REVISION"
  execute_release_operation start "$COMPOSE_PATH" "$verified_digest" "$EXPECTED_REVISION"
  execute_release_operation cleanup "$COMPOSE_PATH" "$verified_digest" "$EXPECTED_REVISION"
  execute_release_operation helper-cleanup "$COMPOSE_PATH" "$verified_digest" "$EXPECTED_REVISION"
}

run_release
trap - EXIT
cleanup_local_files

echo "quiesced-release: verified digest is healthy in $APP_ENV"
