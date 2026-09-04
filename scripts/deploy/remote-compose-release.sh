#!/usr/bin/env bash
set -euo pipefail

mode="${1:-}"
owner="${2:-}"
app_env="${3:-}"

case "$app_env" in
  stage|prod)
    state_root=""
    ;;
  test)
    if [ "${REMOTE_RELEASE_TESTING:-}" != "enabled" ] ||
      [[ "${REMOTE_RELEASE_TEST_ROOT:-}" != /tmp/* && "${REMOTE_RELEASE_TEST_ROOT:-}" != /private/tmp/* && "${REMOTE_RELEASE_TEST_ROOT:-}" != /private/var/* ]] ||
      [ ! -d "${REMOTE_RELEASE_TEST_ROOT:-}" ] || [ -L "${REMOTE_RELEASE_TEST_ROOT:-}" ]; then
      echo "remote-release: invalid test isolation" >&2
      exit 2
    fi
    state_root="$REMOTE_RELEASE_TEST_ROOT"
    ;;
  *)
    echo "remote-release: invalid environment" >&2
    exit 2
    ;;
esac
if [[ ! "$owner" =~ ^[0-9]+-[0-9]+$ ]]; then
  echo "remote-release: invalid owner" >&2
  exit 2
fi

# Child stderr is never part of the public protocol. Keep one private descriptor
# solely for the bounded migration events emitted below and discard every raw
# child diagnostic, including filesystem and Docker implementation details.
exec 3>&2
exec 2>/dev/null

umask 077
release_user_id="$(id -u)"
state_parent=""
application_lock_file=""
application_binding_file=""
active_anchor_file=""
lock_dir=""
finalizing_dir=""
result_dir=""
ledger_dir=""
operation_lock_file=""
disposal_dir=""
volatile_root="/tmp"
helper_root="/tmp"
managed_override_marker="# clubs-bot-managed-quiesced-release"
terminal_retention_days=30
terminal_retention_count=32
mount_fingerprint_version=2
state_dir=""
initializing_dir=""
operation_active=0
operation_completed=0
operation_signal=0
operation_name=""
operation_expected_revision=""
operation_digest=""
operation_checkpoint_before="none"
operation_compose_path=""
failure_category="unexpected"
migration_log_temporary=""
migration_invocation_fingerprint=""
durable_backup_temporary=""
application_project=""
application_service="app"
approved_filesystem_type=""
approved_mount_fingerprint=""
detected_filesystem_type=""
detected_mount_fingerprint=""

path_mode() {
  stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1" 2>/dev/null
}

sha256_file() {
  sha256sum "$1" | awk '{print $1}'
}

sha256_text() {
  printf '%s' "$1" | sha256sum | awk '{print $1}'
}

validate_compose_path() {
  local compose_path="$1"
  local canonical_path
  [[ "$compose_path" =~ ^/[a-zA-Z0-9._/-]+$ ]] &&
    [[ "$compose_path" != *//* ]] && [[ "$compose_path" != */./* ]] &&
    [[ "$compose_path" != */../* ]] &&
    [ -d "$compose_path" ] && [ ! -L "$compose_path" ] || return 1
  canonical_path="$(cd "$compose_path" 2>/dev/null && pwd -P)" || return 1
  [ "$canonical_path" = "$compose_path" ] &&
    path_chain_has_no_symlink "$compose_path" &&
    [ -f "$compose_path/docker-compose.yml" ] && [ ! -L "$compose_path/docker-compose.yml" ]
}

path_chain_has_no_symlink() {
  local path="$1" component current=""
  local components=()
  [[ "$path" == /* ]] || return 1
  IFS='/' read -r -a components <<<"${path#/}"
  for component in "${components[@]}"; do
    [ -n "$component" ] || return 1
    current="$current/$component"
    [ ! -L "$current" ] || return 1
  done
}

path_owner_id() {
  stat -c '%u' "$1" 2>/dev/null || stat -f '%u' "$1" 2>/dev/null
}

path_link_count() {
  stat -c '%h' "$1" 2>/dev/null || stat -f '%l' "$1" 2>/dev/null
}

path_security_metadata() {
  stat -c '%a:%u:%h' "$1" 2>/dev/null || stat -f '%Lp:%u:%l' "$1" 2>/dev/null
}

trusted_directory() {
  local path="$1"
  local expected_mode="$2"
  [ -d "$path" ] && [ ! -L "$path" ] && path_chain_has_no_symlink "$path" &&
    [[ "$(path_security_metadata "$path")" == "$expected_mode:$release_user_id:"* ]] &&
    authoritative_backing_matches "$path"
}

trusted_authoritative_file() {
  local path="$1"
  [ -f "$path" ] && [ ! -L "$path" ] && path_chain_has_no_symlink "$path" &&
    [ "$(path_security_metadata "$path")" = "600:$release_user_id:1" ] &&
    authoritative_backing_matches "$path"
}

validate_findmnt_pair_value() {
  local value="$1" prefix remainder
  [ -n "$value" ] && [ "${#value}" -le 1024 ] && [[ "$value" != *[[:space:]]* ]] || return 1
  remainder="$value"
  while [[ "$remainder" == *\\* ]]; do
    prefix="${remainder%%\\*}"
    remainder="${remainder:${#prefix}}"
    case "$remainder" in
      \\x[0-9a-fA-F][0-9a-fA-F]*) remainder="${remainder:4}" ;;
      *) return 1 ;;
    esac
  done
}

decode_findmnt_pair_value() {
  local encoded="$1" prefix remainder hex byte decoded=""
  remainder="$encoded"
  while [[ "$remainder" == *\\* ]]; do
    prefix="${remainder%%\\*}"
    decoded+="$prefix"
    remainder="${remainder:${#prefix}}"
    [[ "$remainder" == \\x[0-9a-fA-F][0-9a-fA-F]* ]] || return 1
    hex="${remainder:2:2}"
    [ "$hex" != "00" ] || return 1
    LC_ALL=C printf -v byte '%b' "\\x$hex" || return 1
    decoded+="$byte"
    remainder="${remainder:4}"
  done
  decoded+="$remainder"
  [ -n "$decoded" ] || return 1
  decoded_findmnt_value="$decoded"
}

parse_findmnt_pair_record() {
  local record="$1" field prefix value
  local remaining="$record"
  local fields=(FSTYPE SOURCE FSROOT TARGET)
  parsed_mount_fstype=""
  parsed_mount_source=""
  parsed_mount_fsroot=""
  parsed_mount_target=""
  for field in "${fields[@]}"; do
    prefix="${field}=\""
    [[ "$remaining" == "$prefix"* ]] || return 1
    remaining="${remaining#"$prefix"}"
    [[ "$remaining" == *\"* ]] || return 1
    value="${remaining%%\"*}"
    remaining="${remaining#"$value\""}"
    validate_findmnt_pair_value "$value" || return 1
    decode_findmnt_pair_value "$value" || return 1
    value="$decoded_findmnt_value"
    case "$field" in
      FSTYPE) parsed_mount_fstype="$value" ;;
      SOURCE) parsed_mount_source="$value" ;;
      FSROOT) parsed_mount_fsroot="$value" ;;
      TARGET) parsed_mount_target="$value" ;;
    esac
    if [ "$field" = "TARGET" ]; then
      [ -z "$remaining" ] || return 1
    else
      [[ "$remaining" == " "* ]] || return 1
      remaining="${remaining# }"
    fi
  done
  [ -n "$parsed_mount_fstype" ] && [ -n "$parsed_mount_source" ] &&
    [ -n "$parsed_mount_fsroot" ] && [ -n "$parsed_mount_target" ]
}

compute_mount_fingerprint() {
  local filesystem_type="$1" source="$2" filesystem_root="$3" mount_target="$4"
  local filesystem_type_hash source_hash filesystem_root_hash mount_target_hash canonical field_hash
  filesystem_type_hash="$(sha256_text "$filesystem_type")" || return 1
  source_hash="$(sha256_text "$source")" || return 1
  filesystem_root_hash="$(sha256_text "$filesystem_root")" || return 1
  mount_target_hash="$(sha256_text "$mount_target")" || return 1
  for field_hash in "$filesystem_type_hash" "$source_hash" "$filesystem_root_hash" "$mount_target_hash"; do
    [[ "$field_hash" =~ ^[0-9a-f]{64}$ ]] || return 1
  done
  canonical="clubs-bot-mount-fingerprint-version=$mount_fingerprint_version
FSTYPE_SHA256=$filesystem_type_hash
SOURCE_SHA256=$source_hash
FSROOT_SHA256=$filesystem_root_hash
TARGET_SHA256=$mount_target_hash"
  detected_mount_fingerprint="mount-v${mount_fingerprint_version}:$(sha256_text "$canonical")"
  [[ "$detected_mount_fingerprint" =~ ^mount-v2:[0-9a-f]{64}$ ]]
}

read_mount_identity() {
  local path="$1" record
  command -v findmnt >/dev/null 2>&1 || return 1
  [ -e "$path" ] && [ ! -L "$path" ] && path_chain_has_no_symlink "$path" || return 1
  record="$(LC_ALL=C findmnt --noheadings --pairs --output FSTYPE,SOURCE,FSROOT,TARGET --target "$path" 2>/dev/null)" || return 1
  [ -n "$record" ] && [ "${#record}" -le 4096 ] && [[ "$record" != *$'\n'* ]] || return 1
  parse_findmnt_pair_record "$record" || return 1
  [[ "$parsed_mount_fstype" =~ ^[a-z0-9][a-z0-9._+-]{0,31}$ ]] &&
    [[ "$parsed_mount_fsroot" == /* ]] && [[ "$parsed_mount_target" == /* ]] || return 1
  detected_filesystem_type="$parsed_mount_fstype"
  compute_mount_fingerprint \
    "$parsed_mount_fstype" "$parsed_mount_source" "$parsed_mount_fsroot" "$parsed_mount_target"
}

validate_persistent_filesystem() {
  local compose_path="$1"
  case "$compose_path" in
    /tmp|/tmp/*|/private/tmp|/private/tmp/*|/var/tmp|/var/tmp/*|/private/var/tmp|/private/var/tmp/*|/run|/run/*|/private/run|/private/run/*|/var/run|/var/run/*|/private/var/run|/private/var/run/*|/dev/shm|/dev/shm/*|/private/dev/shm|/private/dev/shm/*) return 1 ;;
  esac
  read_mount_identity "$compose_path" || return 1
  case "$detected_filesystem_type" in
    ext2|ext3|ext4|xfs|btrfs|zfs|f2fs) ;;
    *) return 1 ;;
  esac
  approved_filesystem_type="$detected_filesystem_type"
  approved_mount_fingerprint="$detected_mount_fingerprint"
}

authoritative_backing_matches() {
  local path="$1"
  if [ "$app_env" = "test" ]; then
    return 0
  fi
  [[ "$approved_mount_fingerprint" =~ ^mount-v2:[0-9a-f]{64}$ ]] &&
    [ -n "$approved_filesystem_type" ] || return 1
  read_mount_identity "$path" || return 1
  [ "$detected_filesystem_type" = "$approved_filesystem_type" ] &&
    [ "$detected_mount_fingerprint" = "$approved_mount_fingerprint" ]
}

configure_protocol_paths() {
  local compose_path="$1"
  validate_compose_path "$compose_path" || return 1
  if [ "$app_env" = "test" ]; then
    state_parent="$state_root"
    volatile_root="${REMOTE_RELEASE_VOLATILE_ROOT:-$state_root}"
    helper_root="$volatile_root"
    [ -d "$volatile_root" ] && [ ! -L "$volatile_root" ] || return 1
    approved_filesystem_type="test"
    compute_mount_fingerprint "test" "$(sha256_text "$state_root")" "/" "$state_root" || return 1
    approved_mount_fingerprint="$detected_mount_fingerprint"
  else
    validate_persistent_filesystem "$compose_path" || return 1
    state_parent="$compose_path/.clubs-bot-release-state"
    state_root="$state_parent/$app_env"
    volatile_root="/tmp"
    helper_root="/tmp"
  fi
  application_lock_file="$state_parent/application.lock"
  application_binding_file="$state_parent/application.binding"
  active_anchor_file="$state_parent/active-candidate.anchor"
  lock_dir="$state_root/clubs-bot-schema-${app_env}.lock"
  finalizing_dir="$state_root/clubs-bot-schema-${app_env}.finalizing"
  result_dir="$state_root/clubs-bot-schema-${app_env}.results"
  ledger_dir="$state_root/clubs-bot-schema-${app_env}.migration-ledgers"
  operation_lock_file="$result_dir/operation.lock"
  disposal_dir="$state_root/.clubs-bot-schema-${app_env}.disposed.${owner}"
}

validate_revision() {
  [[ "$1" =~ ^[0-9a-fA-F]{40}$ ]]
}

validate_digest() {
  [[ "$1" =~ ^ghcr\.io/[a-z0-9._/-]+@sha256:[0-9a-f]{64}$ ]]
}

validate_checkpoint() {
  case "$1" in
    none|maintenance_prepared|prior_state_captured|candidate_override_published|app_stop_intent|app_quiesced|migration_started|migration_completed|candidate_start_begun|candidate_healthy|cleanup_started|cleanup_completed|abort_started|abort_completed|unavailable) return 0 ;;
    *) return 1 ;;
  esac
}

validate_operation() {
  case "$1" in
    preflight|prepare|publish|quiesce|migrate|start|cleanup|abort|retention|helper-cleanup|resume-quiesce|resume-migrate|resume-start|resume-cleanup) return 0 ;;
    *) return 1 ;;
  esac
}

validate_failure_category() {
  case "$1" in
    operation_in_progress|success|already_clean|already_released|state_conflict|identity_mismatch|prior_state_invalid|override_invalid|app_identity_mismatch|app_lifecycle_failed|child_exit_255|migration_failed|migration_evidence_invalid|migration_outcome_requires_incident_reconciliation|candidate_start_failed|readiness_failed|health_failed|interrupted|cleanup_failed|durability_failure|retention_failed|credential_failure|image_verification_failed|unexpected) return 0 ;;
    *) return 1 ;;
  esac
}

fsync_file_content() {
  local path="$1"
  [ -f "$path" ] && [ ! -L "$path" ] || return 1
  sync "$path"
}

fsync_parent_directory() {
  local directory="$1"
  [ -d "$directory" ] && [ ! -L "$directory" ] || return 1
  sync "$directory"
}

ensure_durable_directory() {
  local directory="$1"
  local parent
  parent="$(dirname "$directory")"
  if [ -e "$directory" ] || [ -L "$directory" ]; then
    trusted_directory "$directory" 700
    return
  fi
  [ -d "$parent" ] && [ ! -L "$parent" ] && path_chain_has_no_symlink "$parent" &&
    authoritative_backing_matches "$parent" || return 1
  mkdir "$directory" || return 1
  chmod 700 "$directory" && trusted_directory "$directory" 700 || {
    rmdir "$directory" 2>/dev/null || true
    fsync_parent_directory "$parent" 2>/dev/null || true
    return 1
  }
  if ! fsync_parent_directory "$directory" || ! fsync_parent_directory "$parent"; then
    rmdir "$directory" 2>/dev/null || true
    fsync_parent_directory "$parent" 2>/dev/null || true
    return 1
  fi
}

validate_existing_authoritative_backing() {
  local path directory entry
  local paths=(
    "$state_parent"
    "$application_lock_file"
    "$application_binding_file"
    "$state_root"
    "$active_anchor_file"
    "$lock_dir"
    "$finalizing_dir"
    "$result_dir"
    "$ledger_dir"
    "$operation_lock_file"
    "$disposal_dir"
  )
  if [ -n "$initializing_dir" ]; then
    paths+=("$initializing_dir")
  fi
  for path in "${paths[@]}"; do
    if [ -e "$path" ] || [ -L "$path" ]; then
      [ ! -L "$path" ] && path_chain_has_no_symlink "$path" &&
        authoritative_backing_matches "$path" || return 1
    fi
  done
  for directory in "$lock_dir" "$finalizing_dir" "$result_dir" "$ledger_dir" "$disposal_dir"; do
    if [ -d "$directory" ] && [ ! -L "$directory" ]; then
      for entry in "$directory"/*; do
        [ -e "$entry" ] || [ -L "$entry" ] || continue
        [ ! -L "$entry" ] && path_chain_has_no_symlink "$entry" &&
          authoritative_backing_matches "$entry" || return 1
      done
    fi
  done
}

ensure_protocol_root() {
  local compose_path="$1"
  command -v sync >/dev/null 2>&1 || {
    failure_category="durability_failure"
    return 1
  }
  if [ "$app_env" = "test" ]; then
    trusted_directory "$state_parent" 700 || return 1
  else
    ensure_durable_directory "$state_parent" || {
      failure_category="durability_failure"
      return 1
    }
  fi
  validate_existing_authoritative_backing || {
    failure_category="durability_failure"
    return 1
  }
  acquire_application_lock || return 1
  validate_existing_authoritative_backing || {
    failure_category="durability_failure"
    return 1
  }
  ensure_application_binding "$compose_path" || return 1
  if [ "$app_env" != "test" ]; then
    ensure_durable_directory "$state_root" || {
      failure_category="durability_failure"
      return 1
    }
  fi
  trusted_directory "$state_root" 700 || {
    failure_category="durability_failure"
    return 1
  }
  validate_existing_authoritative_backing || {
    failure_category="durability_failure"
    return 1
  }
}

validate_authoritative_target() {
  local target="$1"
  case "$target" in
    "$application_binding_file"|"$active_anchor_file") return 0 ;;
    "$lock_dir"/*|"$finalizing_dir"/*|"$result_dir"/*|"$ledger_dir"/*) return 0 ;;
    *)
      if [ -n "$initializing_dir" ] && [[ "$target" == "$initializing_dir"/* ]]; then
        return 0
      fi
      if [ -n "$operation_compose_path" ] && [ "$target" = "$operation_compose_path/docker-compose.override.yml" ]; then
        return 0
      fi
      return 1
      ;;
  esac
}

rollback_uncommitted_rename() {
  local target="$1"
  local backup="$2"
  local had_previous="$3"
  local parent restore_temporary=""
  parent="$(dirname "$target")"
  if [ "$had_previous" = "yes" ]; then
    restore_temporary="$(mktemp "${target}.rollback.XXXXXX")" || return 1
    cp -- "$backup" "$restore_temporary" && chmod 600 "$restore_temporary" &&
      fsync_file_content "$restore_temporary" && mv -f -- "$restore_temporary" "$target" || {
      rm -f -- "$restore_temporary" 2>/dev/null || true
      return 1
    }
  else
    rm -f -- "$target" 2>/dev/null || return 1
  fi
  fsync_parent_directory "$parent" 2>/dev/null || return 1
}

durable_commit_temporary() {
  local temporary="$1"
  local target="$2"
  local backup="" had_previous=no parent
  validate_authoritative_target "$target" || return 1
  [ -f "$temporary" ] && [ ! -L "$temporary" ] && [ ! -L "$target" ] || return 1
  parent="$(dirname "$target")"
  [ -d "$parent" ] && [ ! -L "$parent" ] &&
    authoritative_backing_matches "$parent" &&
    authoritative_backing_matches "$temporary" || return 1
  if ! fsync_file_content "$temporary"; then
    rm -f -- "$temporary"
    failure_category="durability_failure"
    return 1
  fi
  if [ -e "$target" ]; then
    [ -f "$target" ] && [ ! -L "$target" ] || {
      rm -f -- "$temporary"
      return 1
    }
    backup="$(mktemp "$volatile_root/.clubs-release-previous.${owner}.XXXXXX")" || {
      rm -f -- "$temporary"
      return 1
    }
    durable_backup_temporary="$backup"
    cp -- "$target" "$backup" && chmod 600 "$backup" || {
      rm -f -- "$temporary" "$backup"
      durable_backup_temporary=""
      failure_category="durability_failure"
      return 1
    }
    had_previous=yes
  fi
  if ! mv -f -- "$temporary" "$target"; then
    rm -f -- "$temporary"
    [ -z "$backup" ] || rm -f -- "$backup"
    durable_backup_temporary=""
    failure_category="durability_failure"
    return 1
  fi
  if ! trusted_authoritative_file "$target"; then
    rollback_uncommitted_rename "$target" "$backup" "$had_previous" 2>/dev/null || true
    [ -z "$backup" ] || rm -f -- "$backup" 2>/dev/null || true
    durable_backup_temporary=""
    failure_category="durability_failure"
    return 1
  fi
  if ! fsync_parent_directory "$parent"; then
    rollback_uncommitted_rename "$target" "$backup" "$had_previous" 2>/dev/null || true
    [ -z "$backup" ] || rm -f -- "$backup" 2>/dev/null || true
    durable_backup_temporary=""
    failure_category="durability_failure"
    return 1
  fi
  if [ -n "$backup" ]; then
    rm -f -- "$backup" || {
      failure_category="durability_failure"
      return 1
    }
    durable_backup_temporary=""
  fi
  trusted_authoritative_file "$target"
}

durable_atomic_replace() {
  local source="$1"
  local target="$2"
  local temporary
  validate_authoritative_target "$target" || return 1
  [ -f "$source" ] && [ ! -L "$source" ] && [ ! -L "$target" ] || return 1
  temporary="$(mktemp "${target}.tmp.XXXXXX")" || return 1
  cp -- "$source" "$temporary" || {
    rm -f -- "$temporary"
    return 1
  }
  chmod 600 "$temporary" || {
    rm -f -- "$temporary"
    return 1
  }
  durable_commit_temporary "$temporary" "$target"
}

atomic_write_value() {
  local target="$1"
  local value="$2"
  local temporary
  validate_authoritative_target "$target" || return 1
  temporary="$(mktemp "${target}.tmp.XXXXXX")" || return 1
  chmod 600 "$temporary" || {
    rm -f -- "$temporary"
    return 1
  }
  printf '%s' "$value" >"$temporary" || {
    rm -f -- "$temporary"
    return 1
  }
  durable_commit_temporary "$temporary" "$target"
}

atomic_copy_file() {
  local source="$1"
  local target="$2"
  local temporary
  [ -f "$source" ] && [ ! -L "$source" ] || return 1
  validate_authoritative_target "$target" || return 1
  durable_atomic_replace "$source" "$target"
}

current_application_identity() {
  local compose_path="$1" container_id project service
  container_id="$(current_compose_container_id "$compose_path" 2>/dev/null)" || return 1
  [ -n "$container_id" ] || return 2
  project="$(inspect_container_value '{{ index .Config.Labels "com.docker.compose.project" }}' "$container_id" 2>/dev/null)" || return 1
  service="$(inspect_container_value '{{ index .Config.Labels "com.docker.compose.service" }}' "$container_id" 2>/dev/null)" || return 1
  [[ "$project" =~ ^[a-zA-Z0-9_.-]{1,128}$ ]] && [ "$service" = "app" ] || return 1
  application_project="$project"
  application_service="$service"
}

application_binding_valid() {
  local compose_path="$1" binding="$application_binding_file"
  local binding_environment path_hash project service mount_fingerprint binding_mount_version
  trusted_authoritative_file "$binding" || return 1
  [ "$(awk 'END { print NR }' "$binding" 2>/dev/null || true)" = "7" ] || return 1
  [ "$(result_field "$binding" binding_version 2>/dev/null || true)" = "3" ] || return 1
  binding_environment="$(result_field "$binding" environment 2>/dev/null || true)"
  path_hash="$(result_field "$binding" compose_path_hash 2>/dev/null || true)"
  binding_mount_version="$(result_field "$binding" mount_fingerprint_version 2>/dev/null || true)"
  mount_fingerprint="$(result_field "$binding" mount_fingerprint 2>/dev/null || true)"
  project="$(result_field "$binding" compose_project 2>/dev/null || true)"
  service="$(result_field "$binding" compose_service 2>/dev/null || true)"
  [ "$binding_environment" = "$app_env" ] &&
    [ "$path_hash" = "$(sha256_text "$compose_path")" ] &&
    [ "$binding_mount_version" = "$mount_fingerprint_version" ] &&
    [[ "$mount_fingerprint" =~ ^mount-v2:[0-9a-f]{64}$ ]] &&
    [ "$mount_fingerprint" = "$approved_mount_fingerprint" ] &&
    [[ "$project" =~ ^[a-zA-Z0-9_.-]{1,128}$ ]] && [ "$service" = "app" ] || return 1
  application_project="$project"
  application_service="$service"
}

write_application_binding() {
  local compose_path="$1" record
  authoritative_backing_matches "$state_parent" &&
    current_application_identity "$compose_path" || return 1
  record="binding_version=3
environment=$app_env
compose_path_hash=$(sha256_text "$compose_path")
mount_fingerprint_version=$mount_fingerprint_version
mount_fingerprint=$approved_mount_fingerprint
compose_project=$application_project
compose_service=$application_service"
  atomic_write_value "$application_binding_file" "$record"
}

ensure_application_binding() {
  local compose_path="$1"
  if [ -e "$application_binding_file" ] || [ -L "$application_binding_file" ]; then
    application_binding_valid "$compose_path" || {
      failure_category="state_conflict"
      return 1
    }
    return 0
  fi
  write_application_binding "$compose_path" || {
    [ "$failure_category" = "durability_failure" ] || failure_category="state_conflict"
    return 1
  }
  application_binding_valid "$compose_path"
}

acquire_application_lock() {
  local created=0
  command -v flock >/dev/null 2>&1 || {
    failure_category="state_conflict"
    return 1
  }
  trusted_directory "$state_parent" 700 || return 1
  [ ! -L "$application_lock_file" ] || return 1
  if [ ! -e "$application_lock_file" ]; then
    if (set -o noclobber; : >"$application_lock_file") 2>/dev/null; then
      created=1
    fi
  fi
  if [ "$created" = "1" ]; then
    chmod 600 "$application_lock_file" &&
      fsync_file_content "$application_lock_file" &&
      fsync_parent_directory "$state_parent" || {
      rm -f -- "$application_lock_file" 2>/dev/null || true
      fsync_parent_directory "$state_parent" 2>/dev/null || true
      failure_category="durability_failure"
      return 1
    }
  fi
  trusted_authoritative_file "$application_lock_file" || {
    failure_category="state_conflict"
    return 1
  }
  exec 7>>"$application_lock_file"
  flock -n 7 || {
    failure_category="state_conflict"
    return 1
  }
}

read_restricted_value() {
  local path="$1"
  local value
  trusted_authoritative_file "$path" || return 1
  [ "$(wc -l <"$path" | tr -d ' ')" = "0" ] || return 1
  [ "$(wc -c <"$path" | tr -d ' ')" -le 4096 ] || return 1
  value="$(cat "$path")"
  [ -n "$value" ] || return 1
  printf '%s' "$value"
}

resolve_state_dir() {
  if [ -d "$lock_dir" ] && [ ! -L "$lock_dir" ]; then
    state_dir="$lock_dir"
  elif [ -d "$finalizing_dir" ] && [ ! -L "$finalizing_dir" ]; then
    state_dir="$finalizing_dir"
  else
    state_dir=""
    return 1
  fi
  trusted_directory "$state_dir" 700
}

state_value() {
  local name="$1"
  case "$name" in
    owner|expected_revision|image_digest|compose_path_hash|checkpoint|prior_override_exists|prior_override_sha256|old_app_digest|old_app_revision|old_container_hash|old_image_id_hash|old_started_at_hash|old_restart_count|compose_project|compose_service|candidate_override_sha256|migration_image_digest|migration_image_id) ;;
    *) return 1 ;;
  esac
  [ -n "$state_dir" ] || resolve_state_dir
  read_restricted_value "$state_dir/$name"
}

current_checkpoint() {
  local checkpoint
  if ! resolve_state_dir 2>/dev/null; then
    if completed_record_valid 2>/dev/null; then
      checkpoint="$(result_field "$result_dir/$owner.completed" checkpoint 2>/dev/null || true)"
      if validate_checkpoint "$checkpoint"; then
        printf '%s' "$checkpoint"
        return 0
      fi
    fi
    printf '%s' "none"
    return 0
  fi
  checkpoint="$(state_value checkpoint 2>/dev/null || true)"
  if [ "$checkpoint" = "migration_started" ] && current_completed_ledger_matches_state 2>/dev/null; then
    checkpoint="migration_completed"
  fi
  if validate_checkpoint "$checkpoint"; then
    printf '%s' "$checkpoint"
  else
    printf '%s' "unavailable"
  fi
}

require_identity() {
  local compose_path="$1"
  local expected_revision="$2"
  local digest="$3"
  resolve_state_dir || {
    failure_category="state_conflict"
    return 1
  }
  if [ "$(state_value owner 2>/dev/null || true)" != "$owner" ] ||
    [ "$(state_value expected_revision 2>/dev/null || true)" != "$expected_revision" ] ||
    [ "$(state_value image_digest 2>/dev/null || true)" != "$digest" ] ||
    [ "$(state_value compose_path_hash 2>/dev/null || true)" != "$(sha256_text "$compose_path")" ]; then
    failure_category="identity_mismatch"
    return 1
  fi
  validate_revision "$expected_revision" && validate_digest "$digest"
}

require_checkpoint() {
  local expected_checkpoint="$1"
  local actual_checkpoint
  actual_checkpoint="$(state_value checkpoint 2>/dev/null || true)"
  if [ "$actual_checkpoint" != "$expected_checkpoint" ]; then
    failure_category="state_conflict"
    return 1
  fi
}

checkpoint_transition_allowed() {
  local before="$1"
  local after="$2"
  case "$before:$after" in
    maintenance_prepared:prior_state_captured|prior_state_captured:candidate_override_published|candidate_override_published:app_stop_intent|app_stop_intent:app_quiesced|app_quiesced:migration_started|migration_started:migration_completed|migration_completed:candidate_start_begun|candidate_start_begun:candidate_healthy|candidate_healthy:cleanup_started|maintenance_prepared:abort_started|prior_state_captured:abort_started|candidate_override_published:abort_started|app_stop_intent:abort_started|abort_started:abort_started|cleanup_started:cleanup_started) return 0 ;;
    *) return 1 ;;
  esac
}

write_checkpoint() {
  local expected_before="$1"
  local after="$2"
  local compose_path="$3"
  local expected_revision="$4"
  local digest="$5"
  require_identity "$compose_path" "$expected_revision" "$digest" || return 1
  require_checkpoint "$expected_before" || return 1
  checkpoint_transition_allowed "$expected_before" "$after" || {
    failure_category="state_conflict"
    return 1
  }
  atomic_write_value "$state_dir/checkpoint" "$after" || return 1
}

ensure_result_dir() {
  if [ -e "$result_dir" ] || [ -L "$result_dir" ]; then
    trusted_directory "$result_dir" 700 || return 1
    return 0
  fi
  ensure_durable_directory "$result_dir" || {
    failure_category="durability_failure"
    return 1
  }
}

ensure_ledger_dir() {
  if [ -e "$ledger_dir" ] || [ -L "$ledger_dir" ]; then
    trusted_directory "$ledger_dir" 700
    return
  fi
  ensure_durable_directory "$ledger_dir" || {
    failure_category="durability_failure"
    return 1
  }
}

durable_remove_file() {
  local path="$1"
  local parent
  trusted_authoritative_file "$path" || return 1
  parent="$(dirname "$path")"
  rm -f -- "$path" || return 1
  fsync_parent_directory "$parent" || {
    failure_category="durability_failure"
    return 1
  }
}

durable_remove_directory() {
  local directory="$1"
  local parent
  trusted_directory "$directory" 700 || return 1
  parent="$(dirname "$directory")"
  rmdir "$directory" || return 1
  fsync_parent_directory "$parent" || {
    failure_category="durability_failure"
    return 1
  }
}

durable_rename_directory() {
  local source="$1"
  local target="$2"
  local parent
  trusted_directory "$source" 700 && [ ! -e "$target" ] && [ ! -L "$target" ] || return 1
  [ "$(dirname "$source")" = "$(dirname "$target")" ] || return 1
  parent="$(dirname "$source")"
  mv -- "$source" "$target" || return 1
  if ! trusted_directory "$target" 700; then
    mv -- "$target" "$source" 2>/dev/null || true
    fsync_parent_directory "$parent" 2>/dev/null || true
    failure_category="durability_failure"
    return 1
  fi
  if ! fsync_parent_directory "$parent"; then
    mv -- "$target" "$source" 2>/dev/null || true
    fsync_parent_directory "$parent" 2>/dev/null || true
    failure_category="durability_failure"
    return 1
  fi
}

result_directory_has_entries() {
  local entry
  for entry in "$result_dir"/* "$result_dir"/.[!.]* "$result_dir"/..?*; do
    if [ -e "$entry" ] || [ -L "$entry" ]; then
      return 0
    fi
  done
  return 1
}

write_operation_result() {
  local requested_operation="$1"
  local checkpoint_before="$2"
  local checkpoint_after="$3"
  local result="$4"
  local category="$5"
  local expected_revision="$6"
  local digest="$7"
  local compose_path_hash="$8"
  local record
  validate_operation "$requested_operation" || return 1
  validate_checkpoint "$checkpoint_before" || return 1
  validate_checkpoint "$checkpoint_after" || return 1
  case "$result" in success|remote_failure|incomplete_unknown) ;;
    *) return 1 ;;
  esac
  validate_failure_category "$category" || return 1
  validate_revision "$expected_revision" || return 1
  if [ "$digest" != "pending" ]; then
    validate_digest "$digest" || return 1
  fi
  [[ "$compose_path_hash" =~ ^[0-9a-f]{64}$ ]] || return 1
  ensure_result_dir || return 1
  record="result_version=1
owner=$owner
requested_operation=$requested_operation
checkpoint_before=$checkpoint_before
checkpoint_after=$checkpoint_after
result=$result
failure_category=$category
expected_revision=$expected_revision
image_digest=$digest
compose_path_hash=$compose_path_hash"
  atomic_write_value "$result_dir/$owner.result" "$record"
}

result_field() {
  local record="$1"
  local field="$2"
  local count value
  trusted_authoritative_file "$record" || return 1
  [ "$(wc -c <"$record" | tr -d ' ')" -le 2048 ] || return 1
  count="$(grep -c "^${field}=" "$record" 2>/dev/null || true)"
  [ "$count" = "1" ] || return 1
  value="$(sed -n "s/^${field}=//p" "$record")"
  [ -n "$value" ] || return 1
  printf '%s' "$value"
}

migration_record_field() {
  result_field "$1" "$2"
}

migration_invocation_id() {
  local compose_path="$1"
  local expected_revision="$2"
  local digest="$3"
  migration_invocation_id_for "$owner" "$compose_path" "$expected_revision" "$digest"
}

migration_invocation_id_for() {
  local release_owner="$1"
  local compose_path="$2"
  local expected_revision="$3"
  local digest="$4"
  sha256_text "v1|$app_env|$release_owner|$expected_revision|$digest|$(sha256_text "$compose_path")"
}

migration_ledger_valid() {
  local record="$1"
  local basename record_owner environment revision digest path_hash operation state fingerprint result completion
  local created_epoch completed_epoch
  trusted_authoritative_file "$record" || return 1
  [ "$(wc -c <"$record" | tr -d ' ')" -le 2048 ] || return 1
  [ "$(awk 'END { print NR }' "$record" 2>/dev/null || true)" = "13" ] || return 1
  basename="${record##*/}"
  [[ "$basename" =~ ^([0-9]+-[0-9]+)\.ledger$ ]] || return 1
  record_owner="$(migration_record_field "$record" owner 2>/dev/null || true)"
  environment="$(migration_record_field "$record" environment 2>/dev/null || true)"
  revision="$(migration_record_field "$record" expected_revision 2>/dev/null || true)"
  digest="$(migration_record_field "$record" image_digest 2>/dev/null || true)"
  path_hash="$(migration_record_field "$record" compose_path_hash 2>/dev/null || true)"
  operation="$(migration_record_field "$record" operation 2>/dev/null || true)"
  state="$(migration_record_field "$record" state 2>/dev/null || true)"
  fingerprint="$(migration_record_field "$record" invocation_fingerprint 2>/dev/null || true)"
  result="$(migration_record_field "$record" result 2>/dev/null || true)"
  completion="$(migration_record_field "$record" completion_checkpoint 2>/dev/null || true)"
  created_epoch="$(migration_record_field "$record" created_epoch 2>/dev/null || true)"
  completed_epoch="$(migration_record_field "$record" completed_epoch 2>/dev/null || true)"
  [ "$(migration_record_field "$record" ledger_version 2>/dev/null || true)" = "1" ] &&
    [ "$record_owner" = "${BASH_REMATCH[1]}" ] && [ "$environment" = "$app_env" ] &&
    validate_revision "$revision" && validate_digest "$digest" &&
    [[ "$path_hash" =~ ^[0-9a-f]{64}$ ]] && [ "$operation" = "migration" ] &&
    [[ "$fingerprint" =~ ^[0-9a-f]{64}$ ]] && [[ "$created_epoch" =~ ^[0-9]{1,12}$ ]] &&
    [[ "$completed_epoch" =~ ^[0-9]{1,12}$ ]] || return 1
  case "$state:$result:$completion:$completed_epoch" in
    started:pending:none:0|completed:completed:migration_completed:[0-9]*) return 0 ;;
    *) return 1 ;;
  esac
}

migration_outcome_valid() {
  local record="$1"
  local basename record_owner environment revision digest path_hash operation state fingerprint bounded_result completion recorded_epoch
  trusted_authoritative_file "$record" || return 1
  [ "$(wc -c <"$record" | tr -d ' ')" -le 2048 ] || return 1
  [ "$(awk 'END { print NR }' "$record" 2>/dev/null || true)" = "12" ] || return 1
  basename="${record##*/}"
  [[ "$basename" =~ ^([0-9]+-[0-9]+)\.outcome$ ]] || return 1
  record_owner="$(migration_record_field "$record" owner 2>/dev/null || true)"
  environment="$(migration_record_field "$record" environment 2>/dev/null || true)"
  revision="$(migration_record_field "$record" expected_revision 2>/dev/null || true)"
  digest="$(migration_record_field "$record" image_digest 2>/dev/null || true)"
  path_hash="$(migration_record_field "$record" compose_path_hash 2>/dev/null || true)"
  operation="$(migration_record_field "$record" operation 2>/dev/null || true)"
  state="$(migration_record_field "$record" state 2>/dev/null || true)"
  fingerprint="$(migration_record_field "$record" invocation_fingerprint 2>/dev/null || true)"
  bounded_result="$(migration_record_field "$record" bounded_result 2>/dev/null || true)"
  completion="$(migration_record_field "$record" completion_checkpoint 2>/dev/null || true)"
  recorded_epoch="$(migration_record_field "$record" recorded_epoch 2>/dev/null || true)"
  [ "$(migration_record_field "$record" outcome_version 2>/dev/null || true)" = "1" ] &&
    [ "$record_owner" = "${BASH_REMATCH[1]}" ] && [ "$environment" = "$app_env" ] &&
    validate_revision "$revision" && validate_digest "$digest" &&
    [[ "$path_hash" =~ ^[0-9a-f]{64}$ ]] && [ "$operation" = "migration" ] &&
    [ "$state" = "succeeded" ] && [[ "$fingerprint" =~ ^[0-9a-f]{64}$ ]] &&
    [ "$bounded_result" = "migration_succeeded" ] &&
    [ "$completion" = "migration_process_succeeded" ] && [[ "$recorded_epoch" =~ ^[0-9]{1,12}$ ]]
}

migration_records_correlate() {
  local ledger="$1"
  local outcome="$2"
  local field
  migration_ledger_valid "$ledger" && migration_outcome_valid "$outcome" || return 1
  for field in owner environment expected_revision image_digest compose_path_hash operation invocation_fingerprint; do
    [ "$(migration_record_field "$ledger" "$field")" = "$(migration_record_field "$outcome" "$field")" ] || return 1
  done
}

scan_migration_authority() {
  local entry state revision digest path_hash entry_owner
  migration_authority_exact_state="none"
  migration_authority_exact_owner=""
  migration_authority_unresolved="no"
  [ -e "$ledger_dir" ] || return 0
  [ -d "$ledger_dir" ] && [ ! -L "$ledger_dir" ] && [ "$(path_mode "$ledger_dir")" = "700" ] || return 1
  for entry in "$ledger_dir"/*; do
    [ -e "$entry" ] || [ -L "$entry" ] || continue
    case "${entry##*/}" in
      *.ledger)
        migration_ledger_valid "$entry" || return 1
        state="$(migration_record_field "$entry" state)"
        revision="$(migration_record_field "$entry" expected_revision)"
        digest="$(migration_record_field "$entry" image_digest)"
        path_hash="$(migration_record_field "$entry" compose_path_hash)"
        entry_owner="$(migration_record_field "$entry" owner)"
        if [ "$state" = "started" ]; then
          migration_authority_unresolved="yes"
        else
          migration_records_correlate "$entry" "$ledger_dir/$entry_owner.outcome" || return 1
        fi
        if [ "$revision" = "$1" ] && [ "$digest" = "$2" ] && [ "$path_hash" = "$(sha256_text "$3")" ]; then
          [ "$(migration_record_field "$entry" invocation_fingerprint)" = "$(migration_invocation_id_for "$entry_owner" "$3" "$revision" "$digest")" ] || return 1
          [ "$migration_authority_exact_state" = "none" ] || return 1
          migration_authority_exact_state="$state"
          migration_authority_exact_owner="$entry_owner"
        fi
        ;;
      *.outcome)
        migration_outcome_valid "$entry" || return 1
        entry_owner="$(migration_record_field "$entry" owner)"
        [ -f "$ledger_dir/$entry_owner.ledger" ] && [ ! -L "$ledger_dir/$entry_owner.ledger" ] || return 1
        ;;
      *) return 1 ;;
    esac
  done
}

guard_new_release_against_migration_authority() {
  local expected_revision="$1"
  local digest="$2"
  local compose_path="$3"
  if [ -e "$active_anchor_file" ] || [ -L "$active_anchor_file" ]; then
    active_anchor_valid "$compose_path" yes || {
      failure_category="migration_evidence_invalid"
      return 1
    }
    if [ "$(active_anchor_field expected_revision)" = "$expected_revision" ] &&
      [ "$(active_anchor_field image_digest)" = "$digest" ]; then
      failure_category="already_released"
      return 1
    fi
  fi
  scan_migration_authority "$expected_revision" "$digest" "$compose_path" || {
    failure_category="migration_evidence_invalid"
    return 1
  }
  if [ "$migration_authority_unresolved" = "yes" ]; then
    failure_category="migration_outcome_requires_incident_reconciliation"
    return 1
  fi
  if [ "$migration_authority_exact_state" = "completed" ]; then
    failure_category="already_released"
    return 1
  fi
}

write_migration_started_ledger() {
  local compose_path="$1"
  local expected_revision="$2"
  local digest="$3"
  local created_epoch record
  ensure_ledger_dir || return 1
  [ ! -e "$ledger_dir/$owner.ledger" ] && [ ! -L "$ledger_dir/$owner.ledger" ] &&
    [ ! -e "$ledger_dir/$owner.outcome" ] && [ ! -L "$ledger_dir/$owner.outcome" ] || {
    failure_category="migration_outcome_requires_incident_reconciliation"
    return 1
  }
  created_epoch="$(date +%s)"
  [[ "$created_epoch" =~ ^[0-9]{1,12}$ ]] || return 1
  migration_invocation_fingerprint="$(migration_invocation_id "$compose_path" "$expected_revision" "$digest")"
  record="ledger_version=1
environment=$app_env
owner=$owner
expected_revision=$expected_revision
image_digest=$digest
compose_path_hash=$(sha256_text "$compose_path")
operation=migration
state=started
invocation_fingerprint=$migration_invocation_fingerprint
result=pending
completion_checkpoint=none
created_epoch=$created_epoch
completed_epoch=0"
  atomic_write_value "$ledger_dir/$owner.ledger" "$record"
}

write_migration_success_outcome() {
  local compose_path="$1"
  local expected_revision="$2"
  local digest="$3"
  local recorded_epoch record
  [ ! -e "$ledger_dir/$owner.outcome" ] && [ ! -L "$ledger_dir/$owner.outcome" ] || return 1
  recorded_epoch="$(date +%s)"
  record="outcome_version=1
environment=$app_env
owner=$owner
expected_revision=$expected_revision
image_digest=$digest
compose_path_hash=$(sha256_text "$compose_path")
operation=migration
state=succeeded
invocation_fingerprint=$migration_invocation_fingerprint
bounded_result=migration_succeeded
completion_checkpoint=migration_process_succeeded
recorded_epoch=$recorded_epoch"
  atomic_write_value "$ledger_dir/$owner.outcome" "$record"
}

write_migration_completed_ledger() {
  local compose_path="$1"
  local expected_revision="$2"
  local digest="$3"
  local ledger="$ledger_dir/$owner.ledger" outcome="$ledger_dir/$owner.outcome"
  local created_epoch completed_epoch record
  migration_ledger_valid "$ledger" && [ "$(migration_record_field "$ledger" state)" = "started" ] || return 1
  migration_outcome_valid "$outcome" || return 1
  migration_invocation_fingerprint="$(migration_record_field "$ledger" invocation_fingerprint)"
  [ "$migration_invocation_fingerprint" = "$(migration_record_field "$outcome" invocation_fingerprint)" ] || return 1
  created_epoch="$(migration_record_field "$ledger" created_epoch)"
  completed_epoch="$(date +%s)"
  record="ledger_version=1
environment=$app_env
owner=$owner
expected_revision=$expected_revision
image_digest=$digest
compose_path_hash=$(sha256_text "$compose_path")
operation=migration
state=completed
invocation_fingerprint=$migration_invocation_fingerprint
result=completed
completion_checkpoint=migration_completed
created_epoch=$created_epoch
completed_epoch=$completed_epoch"
  atomic_write_value "$ledger" "$record"
}

current_migration_ledger_state() {
  local compose_path="$1"
  local expected_revision="$2"
  local digest="$3"
  local ledger="$ledger_dir/$owner.ledger"
  [ -f "$ledger" ] && [ ! -L "$ledger" ] || {
    printf '%s' none
    return 0
  }
  migration_ledger_valid "$ledger" || return 1
  [ "$(migration_record_field "$ledger" owner)" = "$owner" ] &&
    [ "$(migration_record_field "$ledger" expected_revision)" = "$expected_revision" ] &&
    [ "$(migration_record_field "$ledger" image_digest)" = "$digest" ] &&
    [ "$(migration_record_field "$ledger" compose_path_hash)" = "$(sha256_text "$compose_path")" ] &&
    [ "$(migration_record_field "$ledger" invocation_fingerprint)" = "$(migration_invocation_id "$compose_path" "$expected_revision" "$digest")" ] || return 1
  if [ "$(migration_record_field "$ledger" state)" = "completed" ]; then
    migration_records_correlate "$ledger" "$ledger_dir/$owner.outcome" || return 1
  fi
  migration_record_field "$ledger" state
}

current_completed_ledger_matches_state() {
  local ledger="$ledger_dir/$owner.ledger"
  local outcome="$ledger_dir/$owner.outcome"
  resolve_state_dir 2>/dev/null || return 1
  migration_records_correlate "$ledger" "$outcome" || return 1
  [ "$(migration_record_field "$ledger" state)" = "completed" ] &&
    [ "$(migration_record_field "$ledger" owner)" = "$(state_value owner)" ] &&
    [ "$(migration_record_field "$ledger" expected_revision)" = "$(state_value expected_revision)" ] &&
    [ "$(migration_record_field "$ledger" image_digest)" = "$(state_value image_digest)" ] &&
    [ "$(migration_record_field "$ledger" compose_path_hash)" = "$(state_value compose_path_hash)" ]
}

acquire_operation_lock() {
  local created=0
  command -v flock >/dev/null 2>&1 || {
    failure_category="state_conflict"
    return 1
  }
  ensure_result_dir || return 1
  [ ! -L "$operation_lock_file" ] || return 1
  if [ ! -e "$operation_lock_file" ]; then
    if [ -e "$lock_dir" ] || [ -L "$lock_dir" ] ||
      [ -e "$finalizing_dir" ] || [ -L "$finalizing_dir" ] ||
      result_directory_has_entries; then
      failure_category="state_conflict"
      return 1
    fi
    if (set -o noclobber; : >"$operation_lock_file") 2>/dev/null; then
      created=1
    fi
  fi
  trusted_authoritative_file "$operation_lock_file" || return 1
  if [ "$created" = "1" ]; then
    chmod 600 "$operation_lock_file" &&
      fsync_file_content "$operation_lock_file" &&
      fsync_parent_directory "$result_dir" || {
      rm -f -- "$operation_lock_file" 2>/dev/null || true
      fsync_parent_directory "$result_dir" 2>/dev/null || true
      failure_category="durability_failure"
      return 1
    }
  fi
  trusted_authoritative_file "$operation_lock_file" || return 1
  exec 9>>"$operation_lock_file"
  flock -n 9 || {
    failure_category="state_conflict"
    return 1
  }
}

existing_result_identity_matches() {
  local compose_path="$1"
  local expected_revision="$2"
  local digest="$3"
  local record="$result_dir/$owner.result"
  [ "$(awk 'END { print NR }' "$record" 2>/dev/null || true)" = "10" ] &&
    [ "$(result_field "$record" owner 2>/dev/null || true)" = "$owner" ] &&
    [ "$(result_field "$record" expected_revision 2>/dev/null || true)" = "$expected_revision" ] &&
    [ "$(result_field "$record" image_digest 2>/dev/null || true)" = "$digest" ] &&
    [ "$(result_field "$record" compose_path_hash 2>/dev/null || true)" = "$(sha256_text "$compose_path")" ]
}

guard_operation_identity() {
  local requested_operation="$1"
  local expected_revision="$2"
  local digest="$3"
  local compose_path="$4"
  if resolve_state_dir 2>/dev/null; then
    [ "$digest" != "pending" ] && require_identity "$compose_path" "$expected_revision" "$digest" || {
      failure_category="identity_mismatch"
      return 1
    }
  elif completed_record_valid 2>/dev/null; then
    [ "$digest" != "pending" ] && completed_identity_matches "$compose_path" "$expected_revision" "$digest" || {
      failure_category="identity_mismatch"
      return 1
    }
  elif [ -f "$result_dir/$owner.result" ] && [ ! -L "$result_dir/$owner.result" ]; then
    [ "$requested_operation" != "preflight" ] && existing_result_identity_matches "$compose_path" "$expected_revision" "$digest" || {
      failure_category="identity_mismatch"
      return 1
    }
  elif [ "$requested_operation" = "prepare" ]; then
    guard_new_release_against_migration_authority "$expected_revision" "$digest" "$compose_path"
  elif [ "$requested_operation" != "preflight" ]; then
    failure_category="state_conflict"
    return 1
  fi
}

begin_operation() {
  local requested_operation="$1"
  local expected_revision="$2"
  local digest="$3"
  local compose_path="$4"
  guard_operation_identity "$requested_operation" "$expected_revision" "$digest" "$compose_path"
  operation_name="$requested_operation"
  operation_expected_revision="$expected_revision"
  operation_digest="$digest"
  operation_compose_path="$compose_path"
  operation_checkpoint_before="$(current_checkpoint)"
  validate_checkpoint "$operation_checkpoint_before" || operation_checkpoint_before="unavailable"
  operation_active=1
  write_operation_result \
    "$operation_name" \
    "$operation_checkpoint_before" \
    "unavailable" \
    "incomplete_unknown" \
    "operation_in_progress" \
    "$operation_expected_revision" \
    "$operation_digest" \
    "$(sha256_text "$compose_path")"
}

mark_operation_success() {
  local compose_path="$1"
  local category="${2:-success}"
  local checkpoint_after
  checkpoint_after="$(current_checkpoint)"
  validate_checkpoint "$checkpoint_after" || checkpoint_after="unavailable"
  write_operation_result \
    "$operation_name" \
    "$operation_checkpoint_before" \
    "$checkpoint_after" \
    "success" \
    "$category" \
    "$operation_expected_revision" \
    "$operation_digest" \
    "$(sha256_text "$compose_path")"
  operation_completed=1
  operation_active=0
}

handle_operation_signal() {
  operation_signal=1
  failure_category="interrupted"
  exit 1
}

cleanup_initializing_state() {
  local path name
  if [ -n "$initializing_dir" ]; then
    case "$initializing_dir" in
      "$state_root/.clubs-bot-schema-${app_env}.initializing.${owner}."*)
        if [ -e "$initializing_dir" ] || [ -L "$initializing_dir" ]; then
          [ -d "$initializing_dir" ] && [ ! -L "$initializing_dir" ] || return 1
          for path in "$initializing_dir"/* "$initializing_dir"/.[!.]* "$initializing_dir"/..?*; do
            [ -e "$path" ] || [ -L "$path" ] || continue
            name="${path##*/}"
            case "$name" in
              docker-compose.release.yml|prior-override|owner|expected_revision|image_digest|compose_path_hash|checkpoint|prior_override_exists|prior_override_sha256|old_app_digest|old_app_revision|old_container_hash|old_image_id_hash|old_started_at_hash|old_restart_count|compose_project|compose_service|candidate_override_sha256|migration_image_digest|migration_image_id) ;;
              *) return 1 ;;
            esac
            [ -f "$path" ] && [ ! -L "$path" ] || return 1
            durable_remove_file "$path" || return 1
          done
          durable_remove_directory "$initializing_dir" || return 1
        fi
        ;;
      *) return 1 ;;
    esac
    initializing_dir=""
  fi
}

cleanup_migration_log_temporary() {
  if [ -n "$migration_log_temporary" ]; then
    case "$migration_log_temporary" in
      "$volatile_root/.clubs-bot-migration-log.${owner}."*) rm -f -- "$migration_log_temporary" || return 1 ;;
      *) return 1 ;;
    esac
    migration_log_temporary=""
  fi
}

cleanup_durable_backup_temporary() {
  if [ -n "$durable_backup_temporary" ]; then
    case "$durable_backup_temporary" in
      "$volatile_root/.clubs-release-previous.${owner}."*) rm -f -- "$durable_backup_temporary" || return 1 ;;
      *) return 1 ;;
    esac
    durable_backup_temporary=""
  fi
}

finalize_operation_result() {
  local exit_status=$?
  local checkpoint_after result category compose_path_hash
  trap - EXIT HUP INT TERM
  if [ "$operation_active" = "1" ] && [ "$operation_completed" = "0" ]; then
    checkpoint_after="$(current_checkpoint 2>/dev/null || printf '%s' unavailable)"
    validate_checkpoint "$checkpoint_after" || checkpoint_after="unavailable"
    result="remote_failure"
    category="$failure_category"
    if [ "$operation_signal" = "1" ]; then
      result="incomplete_unknown"
      category="interrupted"
    elif [ "$exit_status" = "255" ]; then
      category="child_exit_255"
    elif ! validate_failure_category "$category"; then
      category="unexpected"
    fi
    compose_path_hash="$(result_field "$result_dir/$owner.result" compose_path_hash 2>/dev/null || printf '%s' 0000000000000000000000000000000000000000000000000000000000000000)"
    write_operation_result \
      "$operation_name" \
      "$operation_checkpoint_before" \
      "$checkpoint_after" \
      "$result" \
      "$category" \
      "$operation_expected_revision" \
      "$operation_digest" \
      "$compose_path_hash" 2>/dev/null || true
  fi
  cleanup_migration_log_temporary 2>/dev/null || exit_status=1
  cleanup_durable_backup_temporary 2>/dev/null || exit_status=1
  cleanup_initializing_state 2>/dev/null || exit_status=1
  exit "$exit_status"
}

trap handle_operation_signal HUP INT TERM
trap finalize_operation_result EXIT

compose_base_command() {
  local compose_path="$1"
  shift
  cd "$compose_path" || return 1
  docker compose "$@"
}

compose_command() {
  local compose_path
  if [[ "${1:-}" == /* ]]; then
    compose_path="$1"
    shift
  else
    compose_path="$operation_compose_path"
  fi
  validate_compose_path "$compose_path" || return 1
  cd "$compose_path" || return 1
  docker compose -f docker-compose.yml -f "$state_dir/docker-compose.release.yml" "$@"
}

validate_compose_contract() {
  local compose_path="$1"
  local services
  validate_compose_path "$compose_path" || return 1
  [ -f "$compose_path/docker-compose.yml" ] && [ ! -L "$compose_path/docker-compose.yml" ] || return 1
  services="$(compose_base_command "$compose_path" -f docker-compose.yml config --services 2>/dev/null)" || return 1
  [ "$(printf '%s\n' "$services" | grep -Fxc app)" = "1" ]
}

image_revision() {
  docker image inspect --format='{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$1" 2>/dev/null
}

image_digest() {
  docker image inspect --format='{{ index .RepoDigests 0 }}' "$1" 2>/dev/null
}

current_compose_container_id() {
  local compose_path="$1"
  local ids container_id oneoff_label
  local -a container_ids=()
  local ordinary_container_id=""
  local ordinary_container_count=0
  ids="$(compose_base_command "$compose_path" ps -aq app 2>/dev/null)" || return 1
  while IFS= read -r container_id; do
    [ -n "$container_id" ] || continue
    [[ "$container_id" =~ ^[0-9a-f]{64}$ ]] || return 1
    container_ids+=("$container_id")
  done <<<"$ids"
  for container_id in "${container_ids[@]}"; do
    oneoff_label="$(
      inspect_container_value \
        '{{ index .Config.Labels "com.docker.compose.oneoff" }}' \
        "$container_id"
    )" || return 1
    case "$oneoff_label" in
      True) ;;
      False)
        ordinary_container_count=$((ordinary_container_count + 1))
        ordinary_container_id="$container_id"
        ;;
      *) return 1 ;;
    esac
  done
  [ "$ordinary_container_count" -le 1 ] || return 1
  if [ "$ordinary_container_count" = "1" ]; then
    printf '%s' "$ordinary_container_id"
  fi
}

inspect_container_value() {
  local format="$1"
  local container_id="$2"
  docker inspect --format="$format" "$container_id" 2>/dev/null
}

collect_old_app_evidence() {
  local compose_path="$1"
  local container_id running image_id digest revision project service started_at restart_count
  container_id="$(current_compose_container_id "$compose_path")" || return 1
  [ -n "$container_id" ] || return 1
  running="$(inspect_container_value '{{.State.Running}}' "$container_id")" || return 1
  [ "$running" = "true" ] || return 1
  image_id="$(inspect_container_value '{{.Image}}' "$container_id")" || return 1
  [[ "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || return 1
  digest="$(image_digest "$image_id")" || return 1
  revision="$(image_revision "$image_id")" || return 1
  validate_digest "$digest" && validate_revision "$revision" || return 1
  project="$(inspect_container_value '{{ index .Config.Labels "com.docker.compose.project" }}' "$container_id")" || return 1
  service="$(inspect_container_value '{{ index .Config.Labels "com.docker.compose.service" }}' "$container_id")" || return 1
  started_at="$(inspect_container_value '{{.State.StartedAt}}' "$container_id")" || return 1
  restart_count="$(inspect_container_value '{{.RestartCount}}' "$container_id")" || return 1
  [[ "$project" =~ ^[a-zA-Z0-9_.-]+$ ]] && [ "$service" = "app" ] || return 1
  [[ "$started_at" =~ ^[0-9TZ:._+-]+$ ]] || return 1
  [[ "$restart_count" =~ ^[0-9]+$ ]] || return 1
  old_container_id="$container_id"
  old_image_id="$image_id"
  old_app_digest="$digest"
  old_app_revision="$revision"
  old_compose_project="$project"
  old_compose_service="$service"
  old_started_at="$started_at"
  old_restart_count="$restart_count"
}

canonical_override_matches() {
  local path="$1"
  local digest="$2"
  local revision="$3"
  trusted_authoritative_file "$path" || return 1
  cmp -s "$path" <(printf '%s\n' \
    "$managed_override_marker" \
    "# revision: $revision" \
    "services:" \
    "  app:" \
    "    image: $digest")
}

legacy_override_matches() {
  local path="$1"
  local digest="$2"
  trusted_authoritative_file "$path" || return 1
  cmp -s "$path" <(printf '%s\n' \
    "$managed_override_marker" \
    "services:" \
    "  app:" \
    "    image: $digest")
}

write_canonical_override() {
  local target="$1"
  local digest="$2"
  local revision="$3"
  local temporary
  [ ! -L "$target" ] || return 1
  temporary="$(mktemp "${target}.tmp.XXXXXX")" || return 1
  chmod 600 "$temporary" || {
    rm -f -- "$temporary"
    return 1
  }
  printf '%s\n' \
    "$managed_override_marker" \
    "# revision: $revision" \
    "services:" \
    "  app:" \
    "    image: $digest" >"$temporary" || {
    rm -f -- "$temporary"
    return 1
  }
  durable_commit_temporary "$temporary" "$target" || return 1
  canonical_override_matches "$target" "$digest" "$revision"
}

capture_prior_override() {
  local compose_path="$1"
  local persistent_override="$compose_path/docker-compose.override.yml"
  if [ -L "$persistent_override" ]; then
    return 1
  fi
  if [ ! -e "$persistent_override" ]; then
    prior_override_exists="no"
    prior_override_source=""
    return 0
  fi
  [ -f "$persistent_override" ] || return 1
  [ "$(wc -c <"$persistent_override" | tr -d ' ')" -le 1024 ] || return 1
  if ! canonical_override_matches "$persistent_override" "$old_app_digest" "$old_app_revision" &&
    ! legacy_override_matches "$persistent_override" "$old_app_digest"; then
    return 1
  fi
  prior_override_exists="yes"
  prior_override_source="$persistent_override"
}

create_maintenance_state() {
  local compose_path="$1"
  local digest="$2"
  local expected_revision="$3"
  local release_override
  validate_compose_contract "$compose_path" || {
    failure_category="prior_state_invalid"
    return 1
  }
  guard_new_release_against_migration_authority "$expected_revision" "$digest" "$compose_path" || return 1
  [ ! -e "$lock_dir" ] && [ ! -e "$finalizing_dir" ] || {
    failure_category="state_conflict"
    return 1
  }
  [ "$(image_revision "$digest" 2>/dev/null || true)" = "$expected_revision" ] || {
    failure_category="image_verification_failed"
    return 1
  }
  collect_old_app_evidence "$compose_path" || {
    failure_category="prior_state_invalid"
    return 1
  }
  [ "$old_compose_project" = "$(result_field "$application_binding_file" compose_project 2>/dev/null || true)" ] &&
    [ "$old_compose_service" = "$(result_field "$application_binding_file" compose_service 2>/dev/null || true)" ] || {
    failure_category="state_conflict"
    return 1
  }
  capture_prior_override "$compose_path" || {
    failure_category="prior_state_invalid"
    return 1
  }
  initializing_dir="$(mktemp -d "$state_root/.clubs-bot-schema-${app_env}.initializing.${owner}.XXXXXX")" || return 1
  if ! chmod 700 "$initializing_dir" ||
    ! trusted_directory "$initializing_dir" 700 ||
    ! fsync_parent_directory "$initializing_dir" ||
    ! fsync_parent_directory "$state_root"; then
    rmdir "$initializing_dir" 2>/dev/null || true
    fsync_parent_directory "$state_root" 2>/dev/null || true
    initializing_dir=""
    failure_category="durability_failure"
    return 1
  fi
  state_dir="$initializing_dir"
  release_override="$state_dir/docker-compose.release.yml"
  write_canonical_override "$release_override" "$digest" "$expected_revision" || return 1
  atomic_write_value "$state_dir/owner" "$owner" || return 1
  atomic_write_value "$state_dir/expected_revision" "$expected_revision" || return 1
  atomic_write_value "$state_dir/image_digest" "$digest" || return 1
  atomic_write_value "$state_dir/compose_path_hash" "$(sha256_text "$compose_path")" || return 1
  atomic_write_value "$state_dir/prior_override_exists" "$prior_override_exists" || return 1
  if [ "$prior_override_exists" = "yes" ]; then
    atomic_copy_file "$prior_override_source" "$state_dir/prior-override" || return 1
  else
    atomic_write_value "$state_dir/prior-override" "absent" || return 1
  fi
  atomic_write_value "$state_dir/prior_override_sha256" "$(sha256_file "$state_dir/prior-override")" || return 1
  atomic_write_value "$state_dir/old_app_digest" "$old_app_digest" || return 1
  atomic_write_value "$state_dir/old_app_revision" "$old_app_revision" || return 1
  atomic_write_value "$state_dir/old_container_hash" "$(sha256_text "$old_container_id")" || return 1
  atomic_write_value "$state_dir/old_image_id_hash" "$(sha256_text "$old_image_id")" || return 1
  atomic_write_value "$state_dir/old_started_at_hash" "$(sha256_text "$old_started_at")" || return 1
  atomic_write_value "$state_dir/old_restart_count" "$old_restart_count" || return 1
  atomic_write_value "$state_dir/compose_project" "$old_compose_project" || return 1
  atomic_write_value "$state_dir/compose_service" "$old_compose_service" || return 1
  atomic_write_value "$state_dir/checkpoint" "maintenance_prepared" || return 1
  [ ! -e "$lock_dir" ] && [ ! -L "$lock_dir" ] || return 1
  durable_rename_directory "$initializing_dir" "$lock_dir" || return 1
  initializing_dir=""
  state_dir="$lock_dir"
  require_identity "$compose_path" "$expected_revision" "$digest" || return 1
  write_checkpoint "maintenance_prepared" "prior_state_captured" "$compose_path" "$expected_revision" "$digest" || return 1
}

verify_old_app_unchanged() {
  local compose_path="$1"
  local expected_container_hash expected_image_hash expected_digest expected_revision
  local expected_started_at_hash expected_restart_count expected_project expected_service
  collect_old_app_evidence "$compose_path" || return 1
  expected_container_hash="$(state_value old_container_hash)"
  expected_image_hash="$(state_value old_image_id_hash)"
  expected_digest="$(state_value old_app_digest)"
  expected_revision="$(state_value old_app_revision)"
  expected_started_at_hash="$(state_value old_started_at_hash)"
  expected_restart_count="$(state_value old_restart_count)"
  expected_project="$(state_value compose_project)"
  expected_service="$(state_value compose_service)"
  [ "$(sha256_text "$old_container_id")" = "$expected_container_hash" ] &&
    [ "$(sha256_text "$old_image_id")" = "$expected_image_hash" ] &&
    [ "$old_app_digest" = "$expected_digest" ] &&
    [ "$old_app_revision" = "$expected_revision" ] &&
    [ "$(sha256_text "$old_started_at")" = "$expected_started_at_hash" ] &&
    [ "$old_restart_count" = "$expected_restart_count" ] &&
    [ "$old_compose_project" = "$expected_project" ] &&
    [ "$old_compose_service" = "$expected_service" ]
}

verify_prior_override_current() {
  local compose_path="$1"
  local path="$compose_path/docker-compose.override.yml"
  local exists expected_hash
  verify_stored_prior_override || return 1
  exists="$(state_value prior_override_exists)"
  expected_hash="$(state_value prior_override_sha256)"
  case "$exists" in
    no)
      [ ! -e "$path" ] && [ ! -L "$path" ]
      ;;
    yes)
      [ -f "$path" ] && [ ! -L "$path" ] &&
        [ "$(sha256_file "$path")" = "$expected_hash" ] &&
        cmp -s "$path" "$state_dir/prior-override"
      ;;
    *) return 1 ;;
  esac
}

verify_stored_prior_override() {
  local stored="$state_dir/prior-override"
  local exists expected_hash expected_digest expected_revision
  exists="$(state_value prior_override_exists)"
  expected_hash="$(state_value prior_override_sha256)"
  [[ "$expected_hash" =~ ^[0-9a-f]{64}$ ]] || return 1
  trusted_authoritative_file "$stored" || return 1
  [ "$(wc -c <"$stored" | tr -d ' ')" -le 1024 ] || return 1
  [ "$(sha256_file "$stored")" = "$expected_hash" ] || return 1
  case "$exists" in
    no)
      [ "$(read_restricted_value "$stored")" = "absent" ]
      ;;
    yes)
      expected_digest="$(state_value old_app_digest)"
      expected_revision="$(state_value old_app_revision)"
      validate_digest "$expected_digest" && validate_revision "$expected_revision" || return 1
      canonical_override_matches "$stored" "$expected_digest" "$expected_revision" ||
        legacy_override_matches "$stored" "$expected_digest"
      ;;
    *) return 1 ;;
  esac
}

verify_candidate_override() {
  local compose_path="$1"
  local digest="$2"
  local expected_revision="$3"
  local path="$compose_path/docker-compose.override.yml"
  local expected_hash
  canonical_override_matches "$path" "$digest" "$expected_revision" || return 1
  expected_hash="$(state_value candidate_override_sha256 2>/dev/null || true)"
  [ -z "$expected_hash" ] || [ "$(sha256_file "$path")" = "$expected_hash" ]
}

verify_effective_candidate_override() {
  local compose_path="$1"
  local digest="$2"
  local expected_revision="$3"
  local configured_digest_count
  verify_candidate_override "$compose_path" "$digest" "$expected_revision" || return 1
  configured_digest_count="$(compose_base_command "$compose_path" config --images 2>/dev/null | grep -Fxc "$digest" || true)"
  [ "$configured_digest_count" = "1" ]
}

promote_persistent_override() {
  local compose_path="$1"
  local digest="$2"
  local expected_revision="$3"
  local checkpoint persistent_override
  require_identity "$compose_path" "$expected_revision" "$digest"
  checkpoint="$(state_value checkpoint)"
  case "$checkpoint" in
    candidate_override_published|app_stop_intent|app_quiesced|migration_started|migration_completed|candidate_start_begun|candidate_healthy|cleanup_started)
      verify_effective_candidate_override "$compose_path" "$digest" "$expected_revision" || {
        failure_category="override_invalid"
        return 1
      }
      return 0
      ;;
    prior_state_captured) ;;
    *) failure_category="state_conflict"; return 1 ;;
  esac
  if canonical_override_matches "$compose_path/docker-compose.override.yml" "$digest" "$expected_revision" &&
    verify_old_app_unchanged "$compose_path" &&
    verify_effective_candidate_override "$compose_path" "$digest" "$expected_revision"; then
    atomic_write_value "$state_dir/candidate_override_sha256" "$(sha256_file "$compose_path/docker-compose.override.yml")"
    write_checkpoint "prior_state_captured" "candidate_override_published" "$compose_path" "$expected_revision" "$digest"
    return 0
  fi
  verify_old_app_unchanged "$compose_path" && verify_prior_override_current "$compose_path" || {
    failure_category="prior_state_invalid"
    return 1
  }
  persistent_override="$compose_path/docker-compose.override.yml"
  write_canonical_override "$persistent_override" "$digest" "$expected_revision" || {
    failure_category="override_invalid"
    return 1
  }
  verify_effective_candidate_override "$compose_path" "$digest" "$expected_revision" || {
    failure_category="override_invalid"
    return 1
  }
  atomic_write_value "$state_dir/candidate_override_sha256" "$(sha256_file "$persistent_override")"
  write_checkpoint "prior_state_captured" "candidate_override_published" "$compose_path" "$expected_revision" "$digest"
}

classify_app_state() {
  local compose_path="$1"
  local candidate_digest="$2"
  local expected_revision="$3"
  local container_id running image_id reference revision project service recorded_image_id=""
  container_id="$(current_compose_container_id "$compose_path" 2>/dev/null || printf '%s' invalid)"
  if [ -z "$container_id" ]; then
    printf '%s' "absent"
    return 0
  fi
  if [ "$container_id" = "invalid" ]; then
    printf '%s' "ambiguous"
    return 0
  fi
  running="$(inspect_container_value '{{.State.Running}}' "$container_id" 2>/dev/null || true)"
  image_id="$(inspect_container_value '{{.Image}}' "$container_id" 2>/dev/null || true)"
  reference="$(inspect_container_value '{{.Config.Image}}' "$container_id" 2>/dev/null || true)"
  revision="$(image_revision "$image_id" 2>/dev/null || true)"
  project="$(inspect_container_value '{{ index .Config.Labels "com.docker.compose.project" }}' "$container_id" 2>/dev/null || true)"
  service="$(inspect_container_value '{{ index .Config.Labels "com.docker.compose.service" }}' "$container_id" 2>/dev/null || true)"
  if [ -e "$state_dir/migration_image_id" ] || [ -L "$state_dir/migration_image_id" ]; then
    recorded_image_id="$(state_value migration_image_id 2>/dev/null || true)"
  fi
  if [ "$running" != "true" ] || [[ ! "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    printf '%s' "ambiguous"
  elif verify_old_app_unchanged "$compose_path" 2>/dev/null; then
    printf '%s' "old_running"
  elif [ "$reference" = "$candidate_digest" ] && [ "$revision" = "$expected_revision" ] &&
    [ "$project" = "$(state_value compose_project 2>/dev/null || true)" ] &&
    [ "$service" = "app" ] &&
    { [ -z "$recorded_image_id" ] || [ "$image_id" = "$recorded_image_id" ]; }; then
    printf '%s' "candidate_running"
  else
    printf '%s' "replaced"
  fi
}

assert_app_absent() {
  local compose_path="$1"
  local digest="$2"
  local expected_revision="$3"
  [ "$(classify_app_state "$compose_path" "$digest" "$expected_revision")" = "absent" ] || {
    failure_category="app_identity_mismatch"
    return 1
  }
}

migration_evidence_category() {
  local migration_name="clubs-bot-migrate-${owner}"
  local evidence_path
  if [ -f "$ledger_dir/$owner.ledger" ] && [ ! -L "$ledger_dir/$owner.ledger" ]; then
    if ! migration_ledger_valid "$ledger_dir/$owner.ledger"; then
      printf '%s' "unknown"
      return 0
    fi
    if [ "$(migration_record_field "$ledger_dir/$owner.ledger" state)" = "started" ]; then
      printf '%s' "migration_outcome_requires_incident_reconciliation"
      return 0
    fi
    if migration_records_correlate "$ledger_dir/$owner.ledger" "$ledger_dir/$owner.outcome"; then
      printf '%s' "present"
      return 0
    fi
    printf '%s' "unknown"
    return 0
  fi
  for evidence_path in \
    "$state_dir/migration_image_digest" \
    "$state_dir/migration_image_id" \
    "$state_dir/migration-container.log"; do
    if [ -e "$evidence_path" ] || [ -L "$evidence_path" ]; then
      printf '%s' "present"
      return 0
    fi
  done
  if docker inspect "$migration_name" >/dev/null 2>&1; then
    printf '%s' "present"
  elif docker info >/dev/null 2>&1; then
    printf '%s' "absent"
  else
    printf '%s' "unknown"
  fi
}

run_compose_lifecycle() {
  local compose_path="$1"
  shift
  local exit_status
  set +e
  compose_command "$compose_path" "$@" >/dev/null 2>&1
  exit_status=$?
  set -e
  if [ "$exit_status" != "0" ]; then
    failure_category="app_lifecycle_failed"
    return "$exit_status"
  fi
}

stop_and_remove_app() {
  local compose_path="$1"
  local digest="$2"
  local expected_revision="$3"
  run_compose_lifecycle "$compose_path" stop --timeout 60 app
  run_compose_lifecycle "$compose_path" rm -f app
  assert_app_absent "$compose_path" "$digest" "$expected_revision"
}

continue_quiesce() {
  local compose_path="$1"
  local digest="$2"
  local expected_revision="$3"
  local checkpoint app_state
  require_identity "$compose_path" "$expected_revision" "$digest"
  verify_stored_prior_override || {
    failure_category="prior_state_invalid"
    return 1
  }
  checkpoint="$(state_value checkpoint)"
  [ "$(migration_evidence_category)" = "absent" ] || {
    failure_category="migration_evidence_invalid"
    return 1
  }
  case "$checkpoint" in
    app_quiesced)
      verify_effective_candidate_override "$compose_path" "$digest" "$expected_revision" || {
        failure_category="override_invalid"
        return 1
      }
      assert_app_absent "$compose_path" "$digest" "$expected_revision"
      return 0
      ;;
    prior_state_captured)
      verify_effective_candidate_override "$compose_path" "$digest" "$expected_revision" || {
        failure_category="override_invalid"
        return 1
      }
      verify_old_app_unchanged "$compose_path" || {
        failure_category="app_identity_mismatch"
        return 1
      }
      atomic_write_value "$state_dir/candidate_override_sha256" "$(sha256_file "$compose_path/docker-compose.override.yml")" || return 1
      write_checkpoint "prior_state_captured" "candidate_override_published" "$compose_path" "$expected_revision" "$digest" || return 1
      write_checkpoint "candidate_override_published" "app_stop_intent" "$compose_path" "$expected_revision" "$digest" || return 1
      ;;
    candidate_override_published)
      verify_effective_candidate_override "$compose_path" "$digest" "$expected_revision" || {
        failure_category="override_invalid"
        return 1
      }
      app_state="$(classify_app_state "$compose_path" "$digest" "$expected_revision")"
      case "$app_state" in old_running|absent) ;;
        *) failure_category="app_identity_mismatch"; return 1 ;;
      esac
      write_checkpoint "candidate_override_published" "app_stop_intent" "$compose_path" "$expected_revision" "$digest" || return 1
      ;;
    app_stop_intent)
      verify_effective_candidate_override "$compose_path" "$digest" "$expected_revision" || {
        failure_category="override_invalid"
        return 1
      }
      ;;
    *) failure_category="state_conflict"; return 1 ;;
  esac
  app_state="$(classify_app_state "$compose_path" "$digest" "$expected_revision")"
  case "$app_state" in
    old_running)
      stop_and_remove_app "$compose_path" "$digest" "$expected_revision"
      ;;
    absent) ;;
    *) failure_category="app_identity_mismatch"; return 1 ;;
  esac
  write_checkpoint "app_stop_intent" "app_quiesced" "$compose_path" "$expected_revision" "$digest"
}

emit_safe_migration_diagnostics() {
  local migration_log_file="$1"
  local migration_exit_code="$2"
  local line completed_applied="" failed_phase="" failed_category=""
  local state="initial" parse_failed=0
  local LC_ALL=C

  if [[ ! "$migration_exit_code" =~ ^(0|[1-9][0-9]*)$ ]]; then
    echo "remote-release: migration diagnostic protocol rejected invalid exit status; raw output suppressed" >&3
    return 1
  fi
  if ! od -An -tu1 -v "$migration_log_file" | awk '
    BEGIN { seen = 0; valid = 1; last = -1 }
    {
      for (field = 1; field <= NF; field++) {
        byte = $field + 0
        seen = 1
        last = byte
        if (byte != 10 && (byte < 32 || byte > 126)) {
          valid = 0
        }
      }
    }
    END { exit !(seen && valid && last == 10) }
  '; then
    echo "remote-release: migration diagnostic protocol rejected non-canonical bytes; raw output suppressed" >&3
    return 1
  fi

  while IFS= read -r line; do
    case "$state" in
      initial)
        if [ "$line" = "migration-safe:v=1 event=started" ]; then
          state="started"
        else
          parse_failed=1
        fi
        ;;
      started)
        if [ "$migration_exit_code" = "0" ] &&
          [[ "$line" =~ ^migration-safe:v=1\ event=completed\ applied=(0|[1-9][0-9]{0,9})$ ]]; then
          completed_applied="${BASH_REMATCH[1]}"
          if [ "${#completed_applied}" -eq 10 ] && [[ "$completed_applied" > "2147483647" ]]; then
            parse_failed=1
          else
            state="completed"
          fi
        elif [ "$migration_exit_code" != "0" ] &&
          [[ "$line" =~ ^migration-safe:v=1\ event=failed\ phase=(bootstrap|configuration|migration|validation|pending-check)\ category=(configuration|connection|authentication|migration|validation|cancelled|unexpected)$ ]]; then
          failed_phase="${BASH_REMATCH[1]}"
          failed_category="${BASH_REMATCH[2]}"
          state="failed"
        else
          parse_failed=1
        fi
        ;;
      completed|failed)
        parse_failed=1
        ;;
      *)
        parse_failed=1
        ;;
    esac
    if [ "$parse_failed" = "1" ]; then
      break
    fi
  done <"$migration_log_file"

  if [ "$parse_failed" = "1" ] ||
    { [ "$migration_exit_code" = "0" ] && [ "$state" != "completed" ]; } ||
    { [ "$migration_exit_code" != "0" ] && [ "$state" != "failed" ]; }; then
    echo "remote-release: migration diagnostic protocol rejected non-canonical output; raw output suppressed" >&3
    return 1
  fi

  printf '%s\n' "migration-safe:v=1 event=started" >&3
  if [ "$migration_exit_code" = "0" ]; then
    printf 'migration-safe:v=1 event=completed applied=%s\n' "$completed_applied" >&3
    return 0
  fi
  printf 'migration-safe:v=1 event=failed phase=%s category=%s\n' "$failed_phase" "$failed_category" >&3
}

capture_and_forward_safe_migration_diagnostics() {
  local migration_container_id="$1"
  local migration_exit_code="$2"
  local parse_status=0
  migration_log_temporary="$(mktemp "$volatile_root/.clubs-bot-migration-log.${owner}.XXXXXX")" || return 1
  chmod 600 "$migration_log_temporary" || {
    cleanup_migration_log_temporary 2>/dev/null || true
    return 1
  }
  if ! docker logs "$migration_container_id" >"$migration_log_temporary" 2>&1; then
    failure_category="migration_evidence_invalid"
    cleanup_migration_log_temporary 2>/dev/null || true
    return 1
  fi
  emit_safe_migration_diagnostics "$migration_log_temporary" "$migration_exit_code" || parse_status=$?
  cleanup_migration_log_temporary || return 1
  [ "$parse_status" = "0" ]
}

migrate_verified_image() {
  local compose_path="$1"
  local digest="$2"
  local expected_revision="$3"
  local checkpoint revision expected_image_id migration_container_id recorded_image_id
  local migration_reference migration_image_id migration_exit_code configured_digest_count exit_status
  require_identity "$compose_path" "$expected_revision" "$digest"
  checkpoint="$(state_value checkpoint)"
  if [ "$checkpoint" = "migration_completed" ]; then
    [ "$(current_migration_ledger_state "$compose_path" "$expected_revision" "$digest" 2>/dev/null || true)" = "completed" ] || {
      failure_category="migration_evidence_invalid"
      return 1
    }
    recorded_image_id="$(state_value migration_image_id 2>/dev/null || true)"
    [ "$(state_value migration_image_digest)" = "$digest" ] &&
      [[ "$recorded_image_id" =~ ^sha256:[0-9a-f]{64}$ ]] &&
      [ "$(docker image inspect --format='{{.Id}}' "$digest" 2>/dev/null || true)" = "$recorded_image_id" ] || {
      failure_category="migration_evidence_invalid"
      return 1
    }
    verify_candidate_override "$compose_path" "$digest" "$expected_revision" || {
      failure_category="override_invalid"
      return 1
    }
    assert_app_absent "$compose_path" "$digest" "$expected_revision"
    return 0
  fi
  if [ "$checkpoint" = "migration_started" ]; then
    failure_category="migration_outcome_requires_incident_reconciliation"
    return 1
  fi
  [ "$checkpoint" = "app_quiesced" ] || {
    failure_category="state_conflict"
    return 1
  }
  [ "$(migration_evidence_category)" = "absent" ] || {
    failure_category="migration_evidence_invalid"
    return 1
  }
  assert_app_absent "$compose_path" "$digest" "$expected_revision"
  verify_candidate_override "$compose_path" "$digest" "$expected_revision" || {
    failure_category="override_invalid"
    return 1
  }
  revision="$(image_revision "$digest" 2>/dev/null || true)"
  [ "$revision" = "$expected_revision" ] || {
    failure_category="image_verification_failed"
    return 1
  }
  expected_image_id="$(docker image inspect --format='{{.Id}}' "$digest" 2>/dev/null)" || {
    failure_category="image_verification_failed"
    return 1
  }
  [[ "$expected_image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || return 1
  configured_digest_count="$(compose_command "$compose_path" config --images 2>/dev/null | grep -Fxc "$digest" || true)"
  [ "$configured_digest_count" = "1" ] || {
    failure_category="override_invalid"
    return 1
  }
  migration_container_name="clubs-bot-migrate-${owner}"
  if docker inspect "$migration_container_name" >/dev/null 2>&1; then
    failure_category="migration_evidence_invalid"
    return 1
  fi
  write_migration_started_ledger "$compose_path" "$expected_revision" "$digest" || return 1
  write_checkpoint "app_quiesced" "migration_started" "$compose_path" "$expected_revision" "$digest" || return 1
  set +e
  migration_container_id="$(
    compose_command "$compose_path" run \
      --detach \
      --no-deps \
      --pull never \
      --name "$migration_container_name" \
      --entrypoint /opt/app/bin/app-bot-migrate \
      -e APP_ENV="$app_env" \
      -e FLYWAY_MODE=migrate-and-validate \
      -e QUIESCED_RELEASE_MIGRATION=required \
      app 2>/dev/null
  )"
  exit_status=$?
  set -e
  if [ "$exit_status" != "0" ]; then
    failure_category="migration_failed"
    return "$exit_status"
  fi
  if [[ ! "$migration_container_id" =~ ^[0-9a-f]{64}$ ]]; then
    failure_category="migration_evidence_invalid"
    return 1
  fi
  migration_reference="$(docker inspect --format='{{.Config.Image}}' "$migration_container_id" 2>/dev/null)" || return 1
  migration_image_id="$(docker inspect --format='{{.Image}}' "$migration_container_id" 2>/dev/null)" || return 1
  if [ "$migration_reference" != "$digest" ] || [ "$migration_image_id" != "$expected_image_id" ]; then
    failure_category="migration_evidence_invalid"
    return 1
  fi
  migration_exit_code="$(docker wait "$migration_container_id" 2>/dev/null)" || {
    failure_category="migration_failed"
    return 1
  }
  [[ "$migration_exit_code" =~ ^[0-9]+$ ]] || {
    failure_category="migration_evidence_invalid"
    return 1
  }
  capture_and_forward_safe_migration_diagnostics "$migration_container_id" "$migration_exit_code" || {
    failure_category="migration_evidence_invalid"
    return 1
  }
  if [ "$migration_exit_code" != "0" ]; then
    failure_category="migration_failed"
    return 1
  fi
  if [ "$(docker inspect --format='{{.Config.Image}}' "$migration_container_id" 2>/dev/null || true)" != "$digest" ] ||
    [ "$(docker inspect --format='{{.Image}}' "$migration_container_id" 2>/dev/null || true)" != "$expected_image_id" ]; then
    failure_category="migration_evidence_invalid"
    return 1
  fi
  atomic_write_value "$state_dir/migration_image_digest" "$digest" || return 1
  atomic_write_value "$state_dir/migration_image_id" "$expected_image_id" || return 1
  write_migration_success_outcome "$compose_path" "$expected_revision" "$digest" || return 1
  write_migration_completed_ledger "$compose_path" "$expected_revision" "$digest" || return 1
  write_checkpoint "migration_started" "migration_completed" "$compose_path" "$expected_revision" "$digest" || return 1
}

remove_completed_migration_container() {
  local digest="$1"
  local expected_image_id migration_name reference image_id running exit_code
  expected_image_id="$(state_value migration_image_id 2>/dev/null || true)"
  [ "$(state_value migration_image_digest 2>/dev/null || true)" = "$digest" ] &&
    [[ "$expected_image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || return 1
  migration_name="clubs-bot-migrate-${owner}"
  if ! docker inspect "$migration_name" >/dev/null 2>&1; then
    docker info >/dev/null 2>&1 || return 1
    return 0
  fi
  reference="$(docker inspect --format='{{.Config.Image}}' "$migration_name" 2>/dev/null)" || return 1
  image_id="$(docker inspect --format='{{.Image}}' "$migration_name" 2>/dev/null)" || return 1
  running="$(docker inspect --format='{{.State.Running}}' "$migration_name" 2>/dev/null)" || return 1
  exit_code="$(docker inspect --format='{{.State.ExitCode}}' "$migration_name" 2>/dev/null)" || return 1
  [ "$reference" = "$digest" ] && [ "$image_id" = "$expected_image_id" ] &&
    [ "$running" = "false" ] && [ "$exit_code" = "0" ] || return 1
  docker rm "$migration_name" >/dev/null 2>&1 || return 1
}

probe_candidate() {
  local compose_path="$1"
  local digest="$2"
  local expected_revision="$3"
  local migration_image_id container_id running running_reference running_image_id project service
  migration_image_id="$(state_value migration_image_id)"
  container_id="$(current_compose_container_id "$compose_path")" || return 1
  [ -n "$container_id" ] || return 1
  running="$(inspect_container_value '{{.State.Running}}' "$container_id")" || return 1
  running_reference="$(inspect_container_value '{{.Config.Image}}' "$container_id")" || return 1
  running_image_id="$(inspect_container_value '{{.Image}}' "$container_id")" || return 1
  project="$(inspect_container_value '{{ index .Config.Labels "com.docker.compose.project" }}' "$container_id")" || return 1
  service="$(inspect_container_value '{{ index .Config.Labels "com.docker.compose.service" }}' "$container_id")" || return 1
  [ "$running" = "true" ] && [ "$running_reference" = "$digest" ] &&
    [ "$running_image_id" = "$migration_image_id" ] &&
    [ "$project" = "$(state_value compose_project)" ] && [ "$service" = "app" ] || return 1
  [ "$(image_revision "$running_image_id" 2>/dev/null || true)" = "$expected_revision" ] || return 1
}

probe_readiness_and_health() {
  local compose_path="$1"
  local ready=0 health=0
  local _
  for _ in $(seq 1 60); do
    if compose_command "$compose_path" exec -T app curl -fsS http://127.0.0.1:8080/ready >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 1
  done
  [ "$ready" = "1" ] || {
    failure_category="readiness_failed"
    return 1
  }
  for _ in $(seq 1 60); do
    if compose_command "$compose_path" exec -T app curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1; then
      health=1
      break
    fi
    sleep 1
  done
  [ "$health" = "1" ] || {
    failure_category="health_failed"
    return 1
  }
}

start_and_probe_app() {
  local compose_path="$1"
  local digest="$2"
  local expected_revision="$3"
  local permit_lifecycle="$4"
  local checkpoint migration_digest just_recorded=0 exit_status
  require_identity "$compose_path" "$expected_revision" "$digest"
  verify_candidate_override "$compose_path" "$digest" "$expected_revision" || {
    failure_category="override_invalid"
    return 1
  }
  checkpoint="$(state_value checkpoint)"
  if [ "$checkpoint" = "migration_started" ]; then
    if current_completed_ledger_matches_state && migration_completion_evidence_valid "$digest"; then
      write_checkpoint "migration_started" "migration_completed" "$compose_path" "$expected_revision" "$digest" || return 1
      checkpoint="migration_completed"
    else
      failure_category="migration_outcome_requires_incident_reconciliation"
      return 1
    fi
  fi
  if [ "$checkpoint" = "candidate_healthy" ]; then
    probe_candidate "$compose_path" "$digest" "$expected_revision" || {
      failure_category="app_identity_mismatch"
      return 1
    }
    probe_readiness_and_health "$compose_path" || return 1
    return 0
  fi
  if [ "$checkpoint" = "migration_completed" ]; then
    current_completed_ledger_matches_state || {
      failure_category="migration_evidence_invalid"
      return 1
    }
    assert_app_absent "$compose_path" "$digest" "$expected_revision"
    migration_digest="$(state_value migration_image_digest)"
    [ "$migration_digest" = "$digest" ] || {
      failure_category="migration_evidence_invalid"
      return 1
    }
    [ "$permit_lifecycle" = "yes" ] || {
      failure_category="state_conflict"
      return 1
    }
    write_checkpoint "migration_completed" "candidate_start_begun" "$compose_path" "$expected_revision" "$digest"
    just_recorded=1
  elif [ "$checkpoint" != "candidate_start_begun" ]; then
    failure_category="state_conflict"
    return 1
  fi
  if [ "$just_recorded" = "1" ]; then
    set +e
    compose_command up -d --no-deps --pull never app >/dev/null 2>&1
    exit_status=$?
    set -e
    if [ "$exit_status" != "0" ]; then
      failure_category="candidate_start_failed"
      return "$exit_status"
    fi
  fi
  probe_candidate "$compose_path" "$digest" "$expected_revision" || {
    failure_category="app_identity_mismatch"
    return 1
  }
  probe_readiness_and_health "$compose_path"
  write_checkpoint "candidate_start_begun" "candidate_healthy" "$compose_path" "$expected_revision" "$digest"
}

completed_field() {
  result_field "$result_dir/$owner.completed" "$1"
}

completed_record_valid() {
  local record="$result_dir/$owner.completed"
  local version record_owner revision digest path_hash checkpoint disposition migration_evidence app_state
  trusted_authoritative_file "$record" || return 1
  [ "$(awk 'END { print NR }' "$record" 2>/dev/null || true)" = "9" ] || return 1
  version="$(completed_field completed_version 2>/dev/null || true)"
  record_owner="$(completed_field owner 2>/dev/null || true)"
  revision="$(completed_field expected_revision 2>/dev/null || true)"
  digest="$(completed_field image_digest 2>/dev/null || true)"
  path_hash="$(completed_field compose_path_hash 2>/dev/null || true)"
  checkpoint="$(completed_field checkpoint 2>/dev/null || true)"
  disposition="$(completed_field disposition 2>/dev/null || true)"
  migration_evidence="$(completed_field migration_evidence 2>/dev/null || true)"
  app_state="$(completed_field app_state 2>/dev/null || true)"
  [ "$version" = "1" ] && [ "$record_owner" = "$owner" ] && [[ "$record_owner" =~ ^[0-9]+-[0-9]+$ ]] &&
    validate_revision "$revision" && validate_digest "$digest" &&
    [[ "$path_hash" =~ ^[0-9a-f]{64}$ ]] || return 1
  case "$checkpoint:$disposition:$migration_evidence:$app_state" in
    cleanup_completed:release:present:candidate_running|abort_completed:abort:absent:old_running) return 0 ;;
    *) return 1 ;;
  esac
}

write_completed_record() {
  local checkpoint="$1"
  local disposition="$2"
  local expected_revision="$3"
  local digest="$4"
  local compose_path="$5"
  local migration_evidence="$6"
  local app_state="$7"
  local record
  record="completed_version=1
owner=$owner
expected_revision=$expected_revision
image_digest=$digest
compose_path_hash=$(sha256_text "$compose_path")
checkpoint=$checkpoint
disposition=$disposition
migration_evidence=$migration_evidence
app_state=$app_state"
  ensure_result_dir || return 1
  atomic_write_value "$result_dir/$owner.completed" "$record" || return 1
}

completed_identity_matches() {
  local compose_path="$1"
  local expected_revision="$2"
  local digest="$3"
  completed_record_valid || return 1
  [ "$(completed_field owner 2>/dev/null || true)" = "$owner" ] &&
    [ "$(completed_field expected_revision 2>/dev/null || true)" = "$expected_revision" ] &&
    [ "$(completed_field image_digest 2>/dev/null || true)" = "$digest" ] &&
    [ "$(completed_field compose_path_hash 2>/dev/null || true)" = "$(sha256_text "$compose_path")" ]
}

terminal_completed_record_valid() {
  local record="$1"
  local expected_owner="$2"
  local version record_owner revision digest path_hash checkpoint disposition migration_evidence app_state
  trusted_authoritative_file "$record" || return 1
  [ "$(awk 'END { print NR }' "$record" 2>/dev/null || true)" = "9" ] || return 1
  version="$(result_field "$record" completed_version 2>/dev/null || true)"
  record_owner="$(result_field "$record" owner 2>/dev/null || true)"
  revision="$(result_field "$record" expected_revision 2>/dev/null || true)"
  digest="$(result_field "$record" image_digest 2>/dev/null || true)"
  path_hash="$(result_field "$record" compose_path_hash 2>/dev/null || true)"
  checkpoint="$(result_field "$record" checkpoint 2>/dev/null || true)"
  disposition="$(result_field "$record" disposition 2>/dev/null || true)"
  migration_evidence="$(result_field "$record" migration_evidence 2>/dev/null || true)"
  app_state="$(result_field "$record" app_state 2>/dev/null || true)"
  [ "$version" = "1" ] && [ "$record_owner" = "$expected_owner" ] &&
    validate_revision "$revision" && validate_digest "$digest" && [[ "$path_hash" =~ ^[0-9a-f]{64}$ ]] || return 1
  case "$checkpoint:$disposition:$migration_evidence:$app_state" in
    cleanup_completed:release:present:candidate_running|abort_completed:abort:absent:old_running) return 0 ;;
    *) return 1 ;;
  esac
}

active_anchor_field() {
  result_field "$active_anchor_file" "$1"
}

running_candidate_matches_anchor() {
  local compose_path="$1" revision="$2" digest="$3"
  local container_id running reference image_id project service
  container_id="$(current_compose_container_id "$compose_path" 2>/dev/null)" || return 1
  [ -n "$container_id" ] || return 2
  running="$(inspect_container_value '{{.State.Running}}' "$container_id" 2>/dev/null)" || return 1
  reference="$(inspect_container_value '{{.Config.Image}}' "$container_id" 2>/dev/null)" || return 1
  image_id="$(inspect_container_value '{{.Image}}' "$container_id" 2>/dev/null)" || return 1
  project="$(inspect_container_value '{{ index .Config.Labels "com.docker.compose.project" }}' "$container_id" 2>/dev/null)" || return 1
  service="$(inspect_container_value '{{ index .Config.Labels "com.docker.compose.service" }}' "$container_id" 2>/dev/null)" || return 1
  [ "$running" = "true" ] && [ "$reference" = "$digest" ] &&
    [ "$project" = "$(result_field "$application_binding_file" compose_project)" ] &&
    [ "$service" = "app" ] && [ "$(image_revision "$image_id" 2>/dev/null || true)" = "$revision" ]
}

active_anchor_valid() {
  local compose_path="$1" verify_runtime="${2:-no}"
  local revision digest ledger_key ledger_fingerprint receipt_key artifact_owner runtime_status=0
  trusted_authoritative_file "$active_anchor_file" || return 1
  [ "$(awk 'END { print NR }' "$active_anchor_file" 2>/dev/null || true)" = "8" ] || return 1
  [ "$(active_anchor_field anchor_version 2>/dev/null || true)" = "1" ] &&
    [ "$(active_anchor_field environment 2>/dev/null || true)" = "$app_env" ] &&
    [ "$(active_anchor_field binding_fingerprint 2>/dev/null || true)" = "$(sha256_file "$application_binding_file")" ] || return 1
  revision="$(active_anchor_field expected_revision 2>/dev/null || true)"
  digest="$(active_anchor_field image_digest 2>/dev/null || true)"
  ledger_key="$(active_anchor_field migration_ledger_key 2>/dev/null || true)"
  ledger_fingerprint="$(active_anchor_field migration_ledger_fingerprint 2>/dev/null || true)"
  receipt_key="$(active_anchor_field terminal_receipt_key 2>/dev/null || true)"
  validate_revision "$revision" && validate_digest "$digest" &&
    [[ "$ledger_key" =~ ^([0-9]+-[0-9]+)\.ledger$ ]] || return 1
  artifact_owner="${BASH_REMATCH[1]}"
  [[ "$ledger_fingerprint" =~ ^[0-9a-f]{64}$ ]] || return 1
  [ "$receipt_key" = "$artifact_owner.completed" ] || return 1
  migration_records_correlate "$ledger_dir/$ledger_key" "$ledger_dir/$artifact_owner.outcome" &&
    [ "$(migration_record_field "$ledger_dir/$ledger_key" state)" = "completed" ] &&
    [ "$(migration_record_field "$ledger_dir/$ledger_key" expected_revision)" = "$revision" ] &&
    [ "$(migration_record_field "$ledger_dir/$ledger_key" image_digest)" = "$digest" ] &&
    [ "$(migration_record_field "$ledger_dir/$ledger_key" compose_path_hash)" = "$(sha256_text "$compose_path")" ] &&
    [ "$(migration_record_field "$ledger_dir/$ledger_key" invocation_fingerprint)" = "$ledger_fingerprint" ] || return 1
  terminal_completed_record_valid "$result_dir/$receipt_key" "$artifact_owner" &&
    [ "$(result_field "$result_dir/$receipt_key" disposition)" = "release" ] &&
    [ "$(result_field "$result_dir/$receipt_key" expected_revision)" = "$revision" ] &&
    [ "$(result_field "$result_dir/$receipt_key" image_digest)" = "$digest" ] &&
    [ "$(result_field "$result_dir/$receipt_key" compose_path_hash)" = "$(sha256_text "$compose_path")" ] || return 1
  if [ "$verify_runtime" = "yes" ]; then
    canonical_override_matches "$compose_path/docker-compose.override.yml" "$digest" "$revision" || return 1
    running_candidate_matches_anchor "$compose_path" "$revision" "$digest" || runtime_status=$?
    [ "$runtime_status" = "0" ] || [ "$runtime_status" = "2" ] || return 1
  fi
}

active_anchor_owner() {
  local ledger_key
  ledger_key="$(active_anchor_field migration_ledger_key 2>/dev/null)" || return 1
  [[ "$ledger_key" =~ ^([0-9]+-[0-9]+)\.ledger$ ]] || return 1
  printf '%s' "${BASH_REMATCH[1]}"
}

write_active_candidate_anchor() {
  local compose_path="$1" expected_revision="$2" digest="$3"
  local fingerprint record
  application_binding_valid "$compose_path" &&
    migration_records_correlate "$ledger_dir/$owner.ledger" "$ledger_dir/$owner.outcome" &&
    [ "$(migration_record_field "$ledger_dir/$owner.ledger" state)" = "completed" ] &&
    completed_identity_matches "$compose_path" "$expected_revision" "$digest" &&
    [ "$(completed_field disposition)" = "release" ] || return 1
  fingerprint="$(migration_record_field "$ledger_dir/$owner.ledger" invocation_fingerprint)"
  record="anchor_version=1
environment=$app_env
binding_fingerprint=$(sha256_file "$application_binding_file")
expected_revision=$expected_revision
image_digest=$digest
migration_ledger_key=$owner.ledger
migration_ledger_fingerprint=$fingerprint
terminal_receipt_key=$owner.completed"
  atomic_write_value "$active_anchor_file" "$record" || return 1
  active_anchor_valid "$compose_path" yes
}

result_record_structurally_valid() {
  local record="$1"
  local expected_owner="$2"
  local record_owner requested_operation checkpoint_before checkpoint_after result category revision digest path_hash
  trusted_authoritative_file "$record" || return 1
  [ "$(awk 'END { print NR }' "$record" 2>/dev/null || true)" = "10" ] || return 1
  record_owner="$(result_field "$record" owner 2>/dev/null || true)"
  requested_operation="$(result_field "$record" requested_operation 2>/dev/null || true)"
  checkpoint_before="$(result_field "$record" checkpoint_before 2>/dev/null || true)"
  checkpoint_after="$(result_field "$record" checkpoint_after 2>/dev/null || true)"
  result="$(result_field "$record" result 2>/dev/null || true)"
  category="$(result_field "$record" failure_category 2>/dev/null || true)"
  revision="$(result_field "$record" expected_revision 2>/dev/null || true)"
  digest="$(result_field "$record" image_digest 2>/dev/null || true)"
  path_hash="$(result_field "$record" compose_path_hash 2>/dev/null || true)"
  [ "$(result_field "$record" result_version 2>/dev/null || true)" = "1" ] &&
    [ "$record_owner" = "$expected_owner" ] && validate_operation "$requested_operation" &&
    validate_checkpoint "$checkpoint_before" && validate_checkpoint "$checkpoint_after" &&
    validate_failure_category "$category" && validate_revision "$revision" &&
    [[ "$path_hash" =~ ^[0-9a-f]{64}$ ]] || return 1
  case "$result" in success|remote_failure|incomplete_unknown) ;;
    *) return 1 ;;
  esac
  [ "$digest" = "pending" ] || validate_digest "$digest"
}

artifact_mtime_epoch() {
  stat -c '%Y' "$1" 2>/dev/null || stat -f '%m' "$1" 2>/dev/null
}

terminal_prune_marker_valid() {
  local marker="$1"
  local expected_owner="$2"
  trusted_authoritative_file "$marker" || return 1
  [ "$(awk 'END { print NR }' "$marker" 2>/dev/null || true)" = "4" ] || return 1
  [ "$(result_field "$marker" prune_version 2>/dev/null || true)" = "1" ] &&
    [ "$(result_field "$marker" owner 2>/dev/null || true)" = "$expected_owner" ] &&
    [ "$(result_field "$marker" disposition 2>/dev/null || true)" = "terminal_retention" ] &&
    [[ "$(result_field "$marker" created_epoch 2>/dev/null || true)" =~ ^[0-9]{1,12}$ ]]
}

write_terminal_prune_marker() {
  local artifact_owner="$1"
  local now record
  now="$(date +%s)"
  record="prune_version=1
owner=$artifact_owner
disposition=terminal_retention
created_epoch=$now"
  atomic_write_value "$result_dir/$artifact_owner.prune" "$record"
}

resume_one_terminal_prune() {
  local artifact_owner="$1"
  local active_owner="" anchored_owner="" path
  [[ "$artifact_owner" =~ ^[0-9]+-[0-9]+$ ]] || return 1
  terminal_prune_marker_valid "$result_dir/$artifact_owner.prune" "$artifact_owner" || return 1
  if resolve_state_dir 2>/dev/null; then
    active_owner="$(state_value owner 2>/dev/null || true)"
  fi
  if [ -e "$active_anchor_file" ] || [ -L "$active_anchor_file" ]; then
    active_anchor_valid "$operation_compose_path" yes || return 1
    anchored_owner="$(active_anchor_owner)" || return 1
  fi
  [ "$artifact_owner" != "$owner" ] && [ "$artifact_owner" != "$active_owner" ] &&
    [ "$artifact_owner" != "$anchored_owner" ] || return 1
  if [ -f "$ledger_dir/$artifact_owner.ledger" ] &&
    [ "$(migration_record_field "$ledger_dir/$artifact_owner.ledger" state 2>/dev/null || true)" != "completed" ]; then
    return 1
  fi
  path="$helper_root/clubs-bot-release-$artifact_owner.sh"
  if [ -e "$path" ] || [ -L "$path" ]; then
    [ -f "$path" ] && [ ! -L "$path" ] || return 1
    rm -f -- "$path" || return 1
  fi
  for path in \
    "$result_dir/$artifact_owner.result" \
    "$result_dir/$artifact_owner.completed" \
    "$ledger_dir/$artifact_owner.outcome" \
    "$ledger_dir/$artifact_owner.ledger"; do
    if [ -e "$path" ] || [ -L "$path" ]; then
      [ -f "$path" ] && [ ! -L "$path" ] || return 1
      durable_remove_file "$path" || return 1
    fi
  done
  durable_remove_file "$result_dir/$artifact_owner.prune"
}

resume_terminal_prunes() {
  local marker basename artifact_owner
  [ -d "$result_dir" ] && [ ! -L "$result_dir" ] || return 0
  for marker in "$result_dir"/*.prune; do
    [ -e "$marker" ] || [ -L "$marker" ] || continue
    basename="${marker##*/}"
    [[ "$basename" =~ ^([0-9]+-[0-9]+)\.prune$ ]] || return 1
    artifact_owner="${BASH_REMATCH[1]}"
    resume_one_terminal_prune "$artifact_owner" || return 1
  done
}

validate_retention_layout() {
  local entry basename record_owner
  if [ -e "$result_dir" ] || [ -L "$result_dir" ]; then
    trusted_directory "$result_dir" 700 || return 1
    for entry in "$result_dir"/*; do
      [ -e "$entry" ] || [ -L "$entry" ] || continue
      basename="${entry##*/}"
      case "$basename" in
        operation.lock)
          trusted_authoritative_file "$entry" || return 1
          ;;
        *.completed)
          [[ "$basename" =~ ^([0-9]+-[0-9]+)\.completed$ ]] || return 1
          terminal_completed_record_valid "$entry" "${BASH_REMATCH[1]}" || return 1
          ;;
        *.result)
          [[ "$basename" =~ ^([0-9]+-[0-9]+)\.result$ ]] || return 1
          record_owner="${BASH_REMATCH[1]}"
          result_record_structurally_valid "$entry" "$record_owner" || return 1
          ;;
        *.prune)
          [[ "$basename" =~ ^([0-9]+-[0-9]+)\.prune$ ]] || return 1
          terminal_prune_marker_valid "$entry" "${BASH_REMATCH[1]}" || return 1
          ;;
        *) return 1 ;;
      esac
    done
  fi
  if [ -e "$ledger_dir" ] || [ -L "$ledger_dir" ]; then
    trusted_directory "$ledger_dir" 700 || return 1
    for entry in "$ledger_dir"/*; do
      [ -e "$entry" ] || [ -L "$entry" ] || continue
      case "${entry##*/}" in
        *.ledger) migration_ledger_valid "$entry" || return 1 ;;
        *.outcome) migration_outcome_valid "$entry" || return 1 ;;
        *) return 1 ;;
      esac
    done
  fi
}

prune_terminal_artifacts() {
  local now cutoff active_owner="" entry basename artifact_owner mtime key index=0
  local anchored_owner="" release_receipt_seen=no result_value
  local terminal_keys=() sorted_keys=()
  validate_retention_layout || {
    failure_category="retention_failed"
    return 1
  }
  scan_migration_authority "$operation_expected_revision" "$operation_digest" "$operation_compose_path" || {
    failure_category="retention_failed"
    return 1
  }
  if [ -d "$result_dir" ] && [ ! -L "$result_dir" ]; then
    for entry in "$result_dir"/*.completed; do
      [ -e "$entry" ] || continue
      artifact_owner="$(result_field "$entry" owner)"
      if [ -f "$result_dir/$artifact_owner.result" ] && [ ! -L "$result_dir/$artifact_owner.result" ]; then
        for result_value in expected_revision image_digest compose_path_hash; do
          [ "$(result_field "$entry" "$result_value")" = "$(result_field "$result_dir/$artifact_owner.result" "$result_value")" ] || {
            failure_category="retention_failed"
            return 1
          }
        done
      fi
      if [ "$(result_field "$entry" disposition)" = "release" ]; then
        release_receipt_seen=yes
        migration_records_correlate "$ledger_dir/$artifact_owner.ledger" "$ledger_dir/$artifact_owner.outcome" &&
          [ "$(migration_record_field "$ledger_dir/$artifact_owner.ledger" state)" = "completed" ] &&
          [ "$(migration_record_field "$ledger_dir/$artifact_owner.ledger" expected_revision)" = "$(result_field "$entry" expected_revision)" ] &&
          [ "$(migration_record_field "$ledger_dir/$artifact_owner.ledger" image_digest)" = "$(result_field "$entry" image_digest)" ] &&
          [ "$(migration_record_field "$ledger_dir/$artifact_owner.ledger" compose_path_hash)" = "$(result_field "$entry" compose_path_hash)" ] || {
          failure_category="retention_failed"
          return 1
        }
      fi
    done
  fi
  if [ -e "$active_anchor_file" ] || [ -L "$active_anchor_file" ]; then
    active_anchor_valid "$operation_compose_path" yes || {
      failure_category="retention_failed"
      return 1
    }
    anchored_owner="$(active_anchor_owner)" || {
      failure_category="retention_failed"
      return 1
    }
  elif [ "$release_receipt_seen" = "yes" ]; then
    failure_category="retention_failed"
    return 1
  fi
  resume_terminal_prunes || {
    failure_category="retention_failed"
    return 1
  }
  now="$(date +%s)"
  [[ "$now" =~ ^[0-9]{1,12}$ ]] || return 1
  cutoff=$((now - terminal_retention_days * 86400))
  if resolve_state_dir 2>/dev/null; then
    active_owner="$(state_value owner 2>/dev/null || true)"
  fi
  if [ -d "$result_dir" ] && [ ! -L "$result_dir" ]; then
    for entry in "$result_dir"/*.completed; do
      [ -e "$entry" ] || continue
      basename="${entry##*/}"
      artifact_owner="${basename%.completed}"
      mtime="$(artifact_mtime_epoch "$entry")" || {
        failure_category="retention_failed"
        return 1
      }
      [[ "$mtime" =~ ^[0-9]{1,12}$ ]] || return 1
      terminal_keys+=("$mtime|$artifact_owner")
    done
  fi
  if [ "${#terminal_keys[@]}" -gt 0 ]; then
    while IFS= read -r key; do
      [ -n "$key" ] && sorted_keys+=("$key")
    done < <(printf '%s\n' "${terminal_keys[@]}" | sort -t '|' -k1,1nr -k2,2r)
    for key in "${sorted_keys[@]}"; do
      mtime="${key%%|*}"
      artifact_owner="${key#*|}"
      if [ "$index" -ge "$terminal_retention_count" ] && [ "$mtime" -lt "$cutoff" ] &&
        [ "$artifact_owner" != "$owner" ] && [ "$artifact_owner" != "$active_owner" ] &&
        [ "$artifact_owner" != "$anchored_owner" ]; then
        result_value=""
        if [ -f "$result_dir/$artifact_owner.result" ] && [ ! -L "$result_dir/$artifact_owner.result" ]; then
          result_value="$(result_field "$result_dir/$artifact_owner.result" result 2>/dev/null || true)"
        fi
        if [ "$result_value" = "incomplete_unknown" ]; then
          index=$((index + 1))
          continue
        fi
        if [ -e "$helper_root/clubs-bot-release-$artifact_owner.sh" ] || [ -L "$helper_root/clubs-bot-release-$artifact_owner.sh" ]; then
          [ -f "$helper_root/clubs-bot-release-$artifact_owner.sh" ] &&
            [ ! -L "$helper_root/clubs-bot-release-$artifact_owner.sh" ] || {
            failure_category="retention_failed"
            return 1
          }
        fi
        if [ -f "$ledger_dir/$artifact_owner.ledger" ]; then
          [ "$(migration_record_field "$ledger_dir/$artifact_owner.ledger" state 2>/dev/null || true)" = "completed" ] || {
            failure_category="retention_failed"
            return 1
          }
        fi
        write_terminal_prune_marker "$artifact_owner" || return 1
        resume_one_terminal_prune "$artifact_owner" || return 1
      fi
      index=$((index + 1))
    done
  fi
}

remove_current_remote_helper() {
  local expected="$helper_root/clubs-bot-release-${owner}.sh"
  [ "$0" = "$expected" ] || return 1
  [ -f "$expected" ] && [ ! -L "$expected" ] || return 1
  rm -f -- "$expected"
}

move_to_finalizing() {
  [ "$state_dir" = "$lock_dir" ] || return 0
  [ ! -e "$finalizing_dir" ] || return 1
  durable_rename_directory "$lock_dir" "$finalizing_dir" || return 1
  state_dir="$finalizing_dir"
  [ "$(path_mode "$state_dir")" = "700" ]
}

validate_state_cleanup_allowlist() {
  local path name restore_dotglob=0 restore_nullglob=0
  local entries=()
  trusted_directory "$state_dir" 700 || return 1
  shopt -q dotglob && restore_dotglob=1
  shopt -q nullglob && restore_nullglob=1
  shopt -s dotglob nullglob
  entries=("$state_dir"/*)
  [ "$restore_dotglob" = "1" ] || shopt -u dotglob
  [ "$restore_nullglob" = "1" ] || shopt -u nullglob
  for path in "${entries[@]}"; do
    name="${path##*/}"
    case "$name" in
      docker-compose.release.yml|prior-override|owner|expected_revision|image_digest|compose_path_hash|checkpoint|prior_override_exists|prior_override_sha256|old_app_digest|old_app_revision|old_container_hash|old_image_id_hash|old_started_at_hash|old_restart_count|compose_project|compose_service|candidate_override_sha256|migration_image_digest|migration_image_id) ;;
      *) return 1 ;;
    esac
    trusted_authoritative_file "$path" || return 1
  done
}

remove_allowlisted_state() {
  local path
  validate_state_cleanup_allowlist || {
    failure_category="cleanup_failed"
    return 1
  }
  if [ "$state_dir" != "$disposal_dir" ]; then
    [ ! -e "$disposal_dir" ] && [ ! -L "$disposal_dir" ] || {
      failure_category="cleanup_failed"
      return 1
    }
    durable_rename_directory "$state_dir" "$disposal_dir" || {
      failure_category="cleanup_failed"
      return 1
    }
    state_dir="$disposal_dir"
    validate_state_cleanup_allowlist || {
      failure_category="cleanup_failed"
      return 1
    }
  fi
  for path in \
    "$state_dir/docker-compose.release.yml" \
    "$state_dir/prior-override" \
    "$state_dir/owner" \
    "$state_dir/expected_revision" \
    "$state_dir/image_digest" \
    "$state_dir/compose_path_hash" \
    "$state_dir/checkpoint" \
    "$state_dir/prior_override_exists" \
    "$state_dir/prior_override_sha256" \
    "$state_dir/old_app_digest" \
    "$state_dir/old_app_revision" \
    "$state_dir/old_container_hash" \
    "$state_dir/old_image_id_hash" \
    "$state_dir/old_started_at_hash" \
    "$state_dir/old_restart_count" \
    "$state_dir/compose_project" \
    "$state_dir/compose_service" \
    "$state_dir/candidate_override_sha256" \
    "$state_dir/migration_image_digest" \
    "$state_dir/migration_image_id"; do
    if [ -L "$path" ]; then
      failure_category="cleanup_failed"
      return 1
    fi
    if [ -e "$path" ]; then
      durable_remove_file "$path" || {
        failure_category="cleanup_failed"
        return 1
      }
    fi
  done
  durable_remove_directory "$state_dir" || {
    failure_category="cleanup_failed"
    return 1
  }
  state_dir=""
}

cleanup_disposal_state() {
  if [ ! -e "$disposal_dir" ] && [ ! -L "$disposal_dir" ]; then
    return 0
  fi
  [ -d "$disposal_dir" ] && [ ! -L "$disposal_dir" ] && [ "$(path_mode "$disposal_dir")" = "700" ] || return 1
  state_dir="$disposal_dir"
  remove_allowlisted_state || return 1
}

cleanup_successful_release() {
  local compose_path="$1"
  local digest="$2"
  local expected_revision="$3"
  local checkpoint
  if ! resolve_state_dir 2>/dev/null; then
    if completed_identity_matches "$compose_path" "$expected_revision" "$digest" &&
      [ "$(completed_field checkpoint 2>/dev/null || true)" = "cleanup_completed" ]; then
      if ! active_anchor_valid "$compose_path" yes 2>/dev/null ||
        [ "$(active_anchor_field expected_revision 2>/dev/null || true)" != "$expected_revision" ] ||
        [ "$(active_anchor_field image_digest 2>/dev/null || true)" != "$digest" ] ||
        [ "$(active_anchor_field terminal_receipt_key 2>/dev/null || true)" != "$owner.completed" ]; then
        write_active_candidate_anchor "$compose_path" "$expected_revision" "$digest" || {
          failure_category="cleanup_failed"
          return 1
        }
      fi
      cleanup_disposal_state || {
        failure_category="cleanup_failed"
        return 1
      }
      return 2
    fi
    failure_category="state_conflict"
    return 1
  fi
  require_identity "$compose_path" "$expected_revision" "$digest" || return 1
  checkpoint="$(state_value checkpoint)" || return 1
  validate_state_cleanup_allowlist || {
    failure_category="cleanup_failed"
    return 1
  }
  case "$checkpoint" in
    candidate_healthy)
      write_checkpoint "candidate_healthy" "cleanup_started" "$compose_path" "$expected_revision" "$digest" || return 1
      ;;
    cleanup_started) ;;
    *) failure_category="state_conflict"; return 1 ;;
  esac
  verify_candidate_override "$compose_path" "$digest" "$expected_revision" || {
    failure_category="override_invalid"
    return 1
  }
  probe_candidate "$compose_path" "$digest" "$expected_revision" || {
    failure_category="app_identity_mismatch"
    return 1
  }
  probe_readiness_and_health "$compose_path" || return 1
  validate_state_cleanup_allowlist || {
    failure_category="cleanup_failed"
    return 1
  }
  remove_completed_migration_container "$digest" || {
    failure_category="migration_evidence_invalid"
    return 1
  }
  move_to_finalizing || {
    failure_category="cleanup_failed"
    return 1
  }
  write_completed_record "cleanup_completed" "release" "$expected_revision" "$digest" "$compose_path" "present" "candidate_running" || return 1
  write_active_candidate_anchor "$compose_path" "$expected_revision" "$digest" || {
    failure_category="cleanup_failed"
    return 1
  }
  remove_allowlisted_state || return 1
}

restore_prior_override() {
  local compose_path="$1"
  local digest="$2"
  local expected_revision="$3"
  local persistent_override="$compose_path/docker-compose.override.yml"
  local prior_exists current_is_prior=0 current_is_candidate=0 temporary
  verify_stored_prior_override || {
    failure_category="prior_state_invalid"
    return 1
  }
  prior_exists="$(state_value prior_override_exists)" || return 1
  if verify_prior_override_current "$compose_path" 2>/dev/null; then
    current_is_prior=1
  fi
  if verify_candidate_override "$compose_path" "$digest" "$expected_revision" 2>/dev/null; then
    current_is_candidate=1
  fi
  if [ "$current_is_prior" != "1" ] && [ "$current_is_candidate" != "1" ]; then
    failure_category="override_invalid"
    return 1
  fi
  if [ "$current_is_prior" = "1" ]; then
    return 0
  fi
  case "$prior_exists" in
    yes)
      durable_atomic_replace "$state_dir/prior-override" "$persistent_override" || return 1
      ;;
    no)
      [ ! -L "$persistent_override" ] || return 1
      if [ -e "$persistent_override" ]; then
        durable_remove_file "$persistent_override" || return 1
      fi
      ;;
    *) return 1 ;;
  esac
  verify_prior_override_current "$compose_path" || return 1
}

abort_pre_quiesce() {
  local compose_path="$1"
  local digest="$2"
  local expected_revision="$3"
  local checkpoint
  if ! resolve_state_dir 2>/dev/null; then
    if completed_identity_matches "$compose_path" "$expected_revision" "$digest"; then
      checkpoint="$(completed_field checkpoint 2>/dev/null || true)"
      case "$checkpoint" in
        abort_completed)
          cleanup_disposal_state || {
            failure_category="cleanup_failed"
            return 1
          }
          return 2
          ;;
      esac
    fi
    failure_category="state_conflict"
    return 1
  fi
  require_identity "$compose_path" "$expected_revision" "$digest" || return 1
  checkpoint="$(state_value checkpoint)" || return 1
  [ "$(migration_evidence_category)" = "absent" ] || {
    failure_category="migration_evidence_invalid"
    return 1
  }
  validate_state_cleanup_allowlist || {
    failure_category="cleanup_failed"
    return 1
  }
  verify_stored_prior_override || {
    failure_category="prior_state_invalid"
    return 1
  }
  if ! verify_prior_override_current "$compose_path" 2>/dev/null &&
    ! verify_candidate_override "$compose_path" "$digest" "$expected_revision" 2>/dev/null; then
    failure_category="override_invalid"
    return 1
  fi
  case "$checkpoint" in
    maintenance_prepared|prior_state_captured|candidate_override_published|app_stop_intent)
      verify_old_app_unchanged "$compose_path" || {
        failure_category="app_identity_mismatch"
        return 1
      }
      write_checkpoint "$checkpoint" "abort_started" "$compose_path" "$expected_revision" "$digest" || return 1
      ;;
    abort_started)
      verify_old_app_unchanged "$compose_path" || {
        failure_category="app_identity_mismatch"
        return 1
      }
      ;;
    *) failure_category="state_conflict"; return 1 ;;
  esac
  restore_prior_override "$compose_path" "$digest" "$expected_revision" || return 1
  verify_old_app_unchanged "$compose_path" || {
    failure_category="app_identity_mismatch"
    return 1
  }
  move_to_finalizing || return 1
  write_completed_record "abort_completed" "abort" "$expected_revision" "$digest" "$compose_path" "absent" "old_running" || return 1
  remove_allowlisted_state || return 1
}

read_result_category() {
  local requested_operation="$1"
  local expected_revision="$2"
  local digest="$3"
  local compose_path="$4"
  local record="$result_dir/$owner.result"
  local version record_owner record_operation before after result category record_revision record_digest record_path_hash
  local classified_result
  if [ "$(awk 'END { print NR }' "$record" 2>/dev/null || true)" != "10" ]; then
    printf '%s' "malformed"
    return 0
  fi
  version="$(result_field "$record" result_version 2>/dev/null || true)"
  record_owner="$(result_field "$record" owner 2>/dev/null || true)"
  record_operation="$(result_field "$record" requested_operation 2>/dev/null || true)"
  before="$(result_field "$record" checkpoint_before 2>/dev/null || true)"
  after="$(result_field "$record" checkpoint_after 2>/dev/null || true)"
  result="$(result_field "$record" result 2>/dev/null || true)"
  category="$(result_field "$record" failure_category 2>/dev/null || true)"
  record_revision="$(result_field "$record" expected_revision 2>/dev/null || true)"
  record_digest="$(result_field "$record" image_digest 2>/dev/null || true)"
  record_path_hash="$(result_field "$record" compose_path_hash 2>/dev/null || true)"
  [ "$version" = "1" ] && [ "$record_owner" = "$owner" ] &&
    validate_operation "$record_operation" &&
    validate_checkpoint "$before" && validate_checkpoint "$after" &&
    validate_failure_category "$category" && [ "$record_revision" = "$expected_revision" ] &&
    [ "$record_path_hash" = "$(sha256_text "$compose_path")" ] || {
      printf '%s' "malformed"
      return 0
    }
  if [ "$digest" != "unknown" ] && [ "$record_digest" != "$digest" ]; then
    printf '%s' "malformed"
    return 0
  fi
  if [ "$digest" = "unknown" ] && [ "$record_digest" != "pending" ] && ! validate_digest "$record_digest"; then
    printf '%s' "malformed"
    return 0
  fi
  case "$result:$category" in
    success:success|success:already_clean) classified_result=success ;;
    remote_failure:operation_in_progress|remote_failure:success|remote_failure:already_clean) classified_result=malformed ;;
    remote_failure:*) classified_result=remote_failure ;;
    incomplete_unknown:operation_in_progress|incomplete_unknown:interrupted) classified_result=incomplete_unknown ;;
    *) classified_result=malformed ;;
  esac
  if [ "$classified_result" = "malformed" ]; then
    printf '%s' "malformed"
  elif [ "$record_operation" != "$requested_operation" ]; then
    printf '%s' "unavailable"
  else
    printf '%s' "$classified_result"
  fi
}

migration_completion_evidence_valid() {
  local digest="$1"
  local recorded_image_id
  current_completed_ledger_matches_state || return 1
  [ "$(state_value migration_image_digest 2>/dev/null || true)" = "$digest" ] || return 1
  recorded_image_id="$(state_value migration_image_id 2>/dev/null || true)"
  [[ "$recorded_image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || return 1
  [ "$(docker image inspect --format='{{.Id}}' "$digest" 2>/dev/null || true)" = "$recorded_image_id" ]
}

emit_untrusted_status() {
  printf '%s\n' 'release-status:v=1 status_available=no owner_match=no revision_match=no digest_match=no checkpoint=unavailable operation_result=unavailable migration_evidence=unknown app_state=unknown abort_permitted=no resume_permitted=no failure_category=untrusted_state_root'
}

validate_protocol_root_readonly() {
  local compose_path="$1" directory bound_project
  validate_compose_path "$compose_path" || return 1
  if [ "$app_env" != "test" ]; then
    validate_persistent_filesystem "$compose_path" || return 1
  fi
  trusted_directory "$state_parent" 700 && trusted_authoritative_file "$application_lock_file" || return 1
  validate_existing_authoritative_backing || return 1
  exec 6<"$application_lock_file"
  flock -s -n 6 2>/dev/null || return 1
  application_binding_valid "$compose_path" || return 1
  trusted_directory "$state_root" 700 || return 1
  validate_existing_authoritative_backing || return 1
  for directory in "$result_dir" "$ledger_dir" "$lock_dir" "$finalizing_dir" "$disposal_dir"; do
    if [ -e "$directory" ] || [ -L "$directory" ]; then
      trusted_directory "$directory" 700 || return 1
    fi
  done
  trusted_directory "$result_dir" 700 || return 1
  bound_project="$(result_field "$application_binding_file" compose_project)" || return 1
  if resolve_state_dir 2>/dev/null; then
    [ "$(state_value compose_project 2>/dev/null || true)" = "$bound_project" ] &&
      [ "$(state_value compose_service 2>/dev/null || true)" = "app" ] || return 1
  fi
  if [ -e "$active_anchor_file" ] || [ -L "$active_anchor_file" ]; then
    active_anchor_valid "$compose_path" no || return 1
  fi
}

status_operation() {
  local compose_path="$1"
  local expected_revision="$2"
  local digest="$3"
  local requested_operation="$4"
  local owner_match=no revision_match=no digest_match=no checkpoint=unavailable
  local operation_result=unavailable migration_evidence=unknown app_state=unknown
  local abort_permitted=no resume_permitted=no stable=yes stored_revision stored_digest
  local stored_prior_ok=no prior_ok=no candidate_file_ok=no candidate_ok=no layout_ok=no
  validate_compose_path "$compose_path" && validate_revision "$expected_revision" && validate_operation "$requested_operation" || {
    emit_untrusted_status
    return 0
  }
  if [ "$digest" != "unknown" ]; then
    validate_digest "$digest" || {
      emit_untrusted_status
      return 0
    }
  fi
  if ! validate_protocol_root_readonly "$compose_path"; then
    emit_untrusted_status
    return 0
  fi
  if [ -e "$result_dir" ] || [ -L "$result_dir" ]; then
    if [ ! -d "$result_dir" ] || [ -L "$result_dir" ] || [ "$(path_mode "$result_dir")" != "700" ]; then
      stable=no
    elif [ -e "$operation_lock_file" ] || [ -L "$operation_lock_file" ]; then
      if ! trusted_authoritative_file "$operation_lock_file"; then
        stable=no
      else
        exec 8<"$operation_lock_file"
        if ! flock -s -n 8 2>/dev/null; then
          stable=no
        fi
      fi
    elif [ -e "$lock_dir" ] || [ -L "$lock_dir" ] ||
      [ -e "$finalizing_dir" ] || [ -L "$finalizing_dir" ] ||
      result_directory_has_entries; then
      stable=no
    fi
  elif [ -e "$lock_dir" ] || [ -L "$lock_dir" ] ||
    [ -e "$finalizing_dir" ] || [ -L "$finalizing_dir" ] ||
    [ -e "$disposal_dir" ] || [ -L "$disposal_dir" ]; then
    stable=no
  fi
  if [ "$stable" = "yes" ] && [ -f "$result_dir/$owner.result" ] && [ ! -L "$result_dir/$owner.result" ]; then
    operation_result="$(read_result_category "$requested_operation" "$expected_revision" "$digest" "$compose_path")"
  fi
  if resolve_state_dir 2>/dev/null; then
    if [ "$(state_value owner 2>/dev/null || true)" = "$owner" ]; then
      owner_match=yes
      stored_revision="$(state_value expected_revision 2>/dev/null || true)"
      stored_digest="$(state_value image_digest 2>/dev/null || true)"
      [ "$stored_revision" = "$expected_revision" ] && revision_match=yes
      if [ "$digest" = "unknown" ]; then
        validate_digest "$stored_digest" && digest_match=yes
      elif [ "$stored_digest" = "$digest" ]; then
        digest_match=yes
      fi
      checkpoint="$(current_checkpoint)"
      validate_state_cleanup_allowlist 2>/dev/null && layout_ok=yes
      case "$checkpoint" in
        migration_started) migration_evidence="$(migration_evidence_category)" ;;
        migration_completed|candidate_start_begun|candidate_healthy|cleanup_started) migration_evidence=present ;;
        *)
          if [ "$stable" = "yes" ]; then
            migration_evidence="$(migration_evidence_category)"
          else
            migration_evidence=unknown
          fi
          ;;
      esac
      if [ "$stable" = "yes" ] && [ "$revision_match" = "yes" ] && [ "$digest_match" = "yes" ] &&
        [ "$layout_ok" = "yes" ] &&
        [ "$(state_value compose_path_hash 2>/dev/null || true)" = "$(sha256_text "$compose_path")" ]; then
        app_state="$(classify_app_state "$compose_path" "$stored_digest" "$stored_revision")"
        verify_stored_prior_override 2>/dev/null && stored_prior_ok=yes
        verify_prior_override_current "$compose_path" 2>/dev/null && prior_ok=yes
        verify_candidate_override "$compose_path" "$stored_digest" "$stored_revision" 2>/dev/null && candidate_file_ok=yes
        verify_effective_candidate_override "$compose_path" "$stored_digest" "$stored_revision" 2>/dev/null && candidate_ok=yes
        case "$checkpoint" in
          maintenance_prepared)
            if [ "$migration_evidence" = "absent" ] && [ "$app_state" = "old_running" ] && [ "$prior_ok" = "yes" ]; then abort_permitted=yes; fi
            ;;
          prior_state_captured)
            if [ "$migration_evidence" = "absent" ] && [ "$app_state" = "old_running" ] &&
              [ "$stored_prior_ok" = "yes" ] && { [ "$prior_ok" = "yes" ] || [ "$candidate_file_ok" = "yes" ]; }; then abort_permitted=yes; fi
            if [ "$migration_evidence" = "absent" ] && [ "$app_state" = "old_running" ] &&
              [ "$stored_prior_ok" = "yes" ] && [ "$candidate_ok" = "yes" ]; then resume_permitted=yes; fi
            ;;
          candidate_override_published|app_stop_intent)
            if [ "$migration_evidence" = "absent" ] && [ "$app_state" = "old_running" ] &&
              [ "$stored_prior_ok" = "yes" ] && [ "$candidate_file_ok" = "yes" ]; then abort_permitted=yes; fi
            if [ "$migration_evidence" = "absent" ] &&
              { [ "$app_state" = "old_running" ] || [ "$app_state" = "absent" ]; } &&
              [ "$stored_prior_ok" = "yes" ] && [ "$candidate_ok" = "yes" ]; then resume_permitted=yes; fi
            ;;
          abort_started)
            if [ "$migration_evidence" = "absent" ] && [ "$app_state" = "old_running" ] &&
              [ "$stored_prior_ok" = "yes" ] && { [ "$prior_ok" = "yes" ] || [ "$candidate_file_ok" = "yes" ]; }; then abort_permitted=yes; fi
            ;;
          app_quiesced)
            [ "$migration_evidence" = "absent" ] && [ "$app_state" = "absent" ] && [ "$candidate_ok" = "yes" ] && resume_permitted=yes
            ;;
          migration_completed)
            if [ "$app_state" = "absent" ] && [ "$candidate_ok" = "yes" ] &&
              migration_completion_evidence_valid "$stored_digest"; then resume_permitted=yes; fi
            ;;
          candidate_start_begun)
            [ "$app_state" = "candidate_running" ] && [ "$candidate_ok" = "yes" ] && resume_permitted=yes
            ;;
          candidate_healthy|cleanup_started)
            [ "$app_state" = "candidate_running" ] && [ "$candidate_ok" = "yes" ] && resume_permitted=yes
            ;;
        esac
      fi
    fi
  elif completed_record_valid 2>/dev/null; then
    if [ "$(completed_field owner 2>/dev/null || true)" = "$owner" ]; then
      owner_match=yes
      [ "$(completed_field expected_revision 2>/dev/null || true)" = "$expected_revision" ] && revision_match=yes
      stored_digest="$(completed_field image_digest 2>/dev/null || true)"
      if [ "$digest" = "unknown" ]; then
        validate_digest "$stored_digest" && digest_match=yes
      elif [ "$stored_digest" = "$digest" ]; then
        digest_match=yes
      fi
      checkpoint="$(completed_field checkpoint 2>/dev/null || printf '%s' unavailable)"
      migration_evidence="$(completed_field migration_evidence 2>/dev/null || printf '%s' unknown)"
      app_state=unknown
    fi
  elif [ "$operation_result" != "malformed" ] &&
    [ -f "$result_dir/$owner.result" ] && [ ! -L "$result_dir/$owner.result" ]; then
    if [ "$(result_field "$result_dir/$owner.result" owner 2>/dev/null || true)" = "$owner" ]; then
      owner_match=yes
      stored_revision="$(result_field "$result_dir/$owner.result" expected_revision 2>/dev/null || true)"
      stored_digest="$(result_field "$result_dir/$owner.result" image_digest 2>/dev/null || true)"
      [ "$stored_revision" = "$expected_revision" ] && revision_match=yes
      if [ "$digest" = "unknown" ]; then
        if [ "$stored_digest" = "pending" ] || validate_digest "$stored_digest"; then digest_match=yes; fi
      elif [ "$stored_digest" = "$digest" ]; then
        digest_match=yes
      fi
      checkpoint="$(result_field "$result_dir/$owner.result" checkpoint_after 2>/dev/null || printf '%s' unavailable)"
      migration_evidence=unknown
      app_state=unknown
    fi
  fi
  if [ "$stable" != "yes" ]; then
    operation_result=unavailable
    app_state=unknown
    abort_permitted=no
    resume_permitted=no
  fi
  if [ "$operation_result" = "malformed" ]; then
    abort_permitted=no
    resume_permitted=no
  fi
  printf 'release-status:v=1 status_available=yes owner_match=%s revision_match=%s digest_match=%s checkpoint=%s operation_result=%s migration_evidence=%s app_state=%s abort_permitted=%s resume_permitted=%s failure_category=none\n' \
    "$owner_match" "$revision_match" "$digest_match" "$checkpoint" "$operation_result" \
    "$migration_evidence" "$app_state" "$abort_permitted" "$resume_permitted"
}

preflight_image() (
  local compose_path="$1"
  local image_repository="$2"
  local image_tag="$3"
  local expected_revision="$4"
  local registry_username="$5"
  local registry_read_token tag_reference revision digest digest_prefix digest_hash
  # Keep this variable in the preflight subshell scope rather than function-local
  # scope: Bash unwinds function locals before running an EXIT trap.
  registry_config_dir=""

  cleanup_registry_config() {
    local exit_status=$?
    if [ -n "$registry_config_dir" ]; then
      case "$registry_config_dir" in
        "/tmp/clubs-bot-docker-config.${owner}."*)
          rm -rf -- "$registry_config_dir" || exit_status=1
          ;;
        *)
          exit_status=1
          ;;
      esac
    fi
    trap - EXIT
    exit "$exit_status"
  }
  trap cleanup_registry_config EXIT

  validate_compose_contract "$compose_path" || exit 1
  IFS= read -r registry_read_token || [ -n "$registry_read_token" ]
  if [ -z "$registry_read_token" ]; then
    exit 2
  fi
  registry_config_dir="$(mktemp -d "/tmp/clubs-bot-docker-config.${owner}.XXXXXX")" || exit 1
  chmod 700 "$registry_config_dir" || exit 1
  printf '%s\n' "$registry_read_token" |
    docker --config "$registry_config_dir" login \
      ghcr.io \
      -u "$registry_username" \
      --password-stdin >/dev/null 2>&1 || exit 1
  unset registry_read_token
  tag_reference="${image_repository}:${image_tag}"
  revision=""
  for _ in $(seq 1 60); do
    if docker --config "$registry_config_dir" pull "$tag_reference" >/dev/null 2>&1; then
      revision="$(docker image inspect --format='{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$tag_reference" 2>/dev/null)"
      if [ "$revision" = "$expected_revision" ]; then
        break
      fi
    fi
    sleep 5
  done
  [ "$revision" = "$expected_revision" ] || exit 1
  digest="$(docker image inspect --format='{{ index .RepoDigests 0 }}' "$tag_reference" 2>/dev/null)" || exit 1
  digest_prefix="${image_repository}@sha256:"
  digest_hash="${digest#"$digest_prefix"}"
  if [ "$digest_hash" = "$digest" ] || [[ ! "$digest_hash" =~ ^[0-9a-f]{64}$ ]]; then
    exit 1
  fi
  docker --config "$registry_config_dir" pull "$digest" >/dev/null 2>&1 || exit 1
  printf '%s' "$digest"
)

case "$mode" in
  resume) protocol_compose_path="${5:-}" ;;
  status|preflight|prepare|publish|quiesce|migrate|start|cleanup|abort|retention|helper-cleanup) protocol_compose_path="${4:-}" ;;
  *) protocol_compose_path="" ;;
esac
if [ -n "$protocol_compose_path" ]; then
  if ! configure_protocol_paths "$protocol_compose_path"; then
    if [ "$mode" = "status" ]; then
      emit_untrusted_status
      operation_completed=1
      operation_active=0
      trap - EXIT HUP INT TERM
      exit 0
    fi
    exit 2
  fi
  if [ "$mode" != "status" ]; then
    ensure_protocol_root "$protocol_compose_path" || exit 1
  fi
fi

case "$mode" in
  status)
    compose_path="${4:-}"
    expected_revision="${5:-}"
    digest="${6:-}"
    requested_operation="${7:-}"
    status_operation "$compose_path" "$expected_revision" "$digest" "$requested_operation"
    operation_completed=1
    operation_active=0
    ;;
  preflight)
    compose_path="${4:-}"
    image_repository="${5:-}"
    image_tag="${6:-}"
    expected_revision="${7:-}"
    registry_username="${8:-}"
    if ! validate_compose_path "$compose_path" ||
      [[ ! "$image_repository" =~ ^ghcr\.io/[a-z0-9._/-]+$ ]] ||
      [[ ! "$image_tag" =~ ^[a-zA-Z0-9._-]+$ ]] ||
      ! validate_revision "$expected_revision" ||
      [[ ! "$registry_username" =~ ^[a-zA-Z0-9._-]+(\[bot\])?$ ]]; then
      exit 2
    fi
    acquire_operation_lock
    begin_operation "preflight" "$expected_revision" "pending" "$compose_path"
    failure_category="credential_failure"
    digest="$(preflight_image "$compose_path" "$image_repository" "$image_tag" "$expected_revision" "$registry_username")" || {
      failure_category="image_verification_failed"
      exit 1
    }
    operation_digest="$digest"
    mark_operation_success "$compose_path"
    printf 'release-operation:v=1 result=success digest=%s\n' "$digest"
    ;;
  prepare)
    compose_path="${4:-}"
    digest="${5:-}"
    expected_revision="${6:-}"
    validate_compose_path "$compose_path" && validate_digest "$digest" && validate_revision "$expected_revision" || exit 2
    acquire_operation_lock
    begin_operation "prepare" "$expected_revision" "$digest" "$compose_path"
    prune_terminal_artifacts
    create_maintenance_state "$compose_path" "$digest" "$expected_revision"
    mark_operation_success "$compose_path"
    printf '%s\n' 'release-operation:v=1 result=success'
    ;;
  publish)
    compose_path="${4:-}"
    digest="${5:-}"
    expected_revision="${6:-}"
    validate_compose_path "$compose_path" && validate_digest "$digest" && validate_revision "$expected_revision" || exit 2
    acquire_operation_lock
    begin_operation "publish" "$expected_revision" "$digest" "$compose_path"
    promote_persistent_override "$compose_path" "$digest" "$expected_revision"
    mark_operation_success "$compose_path"
    printf '%s\n' 'release-operation:v=1 result=success'
    ;;
  quiesce)
    compose_path="${4:-}"
    digest="${5:-}"
    expected_revision="${6:-}"
    validate_compose_path "$compose_path" && validate_digest "$digest" && validate_revision "$expected_revision" || exit 2
    acquire_operation_lock
    begin_operation "quiesce" "$expected_revision" "$digest" "$compose_path"
    continue_quiesce "$compose_path" "$digest" "$expected_revision"
    mark_operation_success "$compose_path"
    printf '%s\n' 'release-operation:v=1 result=success'
    ;;
  migrate)
    compose_path="${4:-}"
    digest="${5:-}"
    expected_revision="${6:-}"
    validate_compose_path "$compose_path" && validate_digest "$digest" && validate_revision "$expected_revision" || exit 2
    acquire_operation_lock
    begin_operation "migrate" "$expected_revision" "$digest" "$compose_path"
    migrate_verified_image "$compose_path" "$digest" "$expected_revision"
    mark_operation_success "$compose_path"
    printf '%s\n' 'release-operation:v=1 result=success'
    ;;
  start)
    compose_path="${4:-}"
    digest="${5:-}"
    expected_revision="${6:-}"
    validate_compose_path "$compose_path" && validate_digest "$digest" && validate_revision "$expected_revision" || exit 2
    acquire_operation_lock
    begin_operation "start" "$expected_revision" "$digest" "$compose_path"
    start_and_probe_app "$compose_path" "$digest" "$expected_revision" "yes"
    mark_operation_success "$compose_path"
    printf '%s\n' 'release-operation:v=1 result=success'
    ;;
  cleanup)
    compose_path="${4:-}"
    digest="${5:-}"
    expected_revision="${6:-}"
    validate_compose_path "$compose_path" && validate_digest "$digest" && validate_revision "$expected_revision" || exit 2
    acquire_operation_lock
    begin_operation "cleanup" "$expected_revision" "$digest" "$compose_path"
    cleanup_status=0
    cleanup_successful_release "$compose_path" "$digest" "$expected_revision" || cleanup_status=$?
    if [ "$cleanup_status" = "2" ]; then
      mark_operation_success "$compose_path" "already_clean"
      printf '%s\n' 'release-operation:v=1 result=already_clean'
    elif [ "$cleanup_status" != "0" ]; then
      exit "$cleanup_status"
    else
      mark_operation_success "$compose_path"
      printf '%s\n' 'release-operation:v=1 result=success'
    fi
    ;;
  abort)
    compose_path="${4:-}"
    digest="${5:-}"
    expected_revision="${6:-}"
    validate_compose_path "$compose_path" && validate_digest "$digest" && validate_revision "$expected_revision" || exit 2
    acquire_operation_lock
    begin_operation "abort" "$expected_revision" "$digest" "$compose_path"
    abort_status=0
    abort_pre_quiesce "$compose_path" "$digest" "$expected_revision" || abort_status=$?
    if [ "$abort_status" = "2" ]; then
      mark_operation_success "$compose_path" "already_clean"
      printf '%s\n' 'release-operation:v=1 result=already_clean'
    elif [ "$abort_status" != "0" ]; then
      exit "$abort_status"
    else
      mark_operation_success "$compose_path"
      printf '%s\n' 'release-operation:v=1 result=success'
    fi
    ;;
  retention)
    compose_path="${4:-}"
    digest="${5:-}"
    expected_revision="${6:-}"
    validate_compose_path "$compose_path" && validate_digest "$digest" && validate_revision "$expected_revision" || exit 2
    acquire_operation_lock
    begin_operation "retention" "$expected_revision" "$digest" "$compose_path"
    prune_terminal_artifacts
    mark_operation_success "$compose_path"
    printf '%s\n' 'release-operation:v=1 result=success'
    ;;
  helper-cleanup)
    compose_path="${4:-}"
    digest="${5:-}"
    expected_revision="${6:-}"
    validate_compose_path "$compose_path" && validate_digest "$digest" && validate_revision "$expected_revision" || exit 2
    acquire_operation_lock
    begin_operation "helper-cleanup" "$expected_revision" "$digest" "$compose_path"
    completed_identity_matches "$compose_path" "$expected_revision" "$digest" &&
      [ "$(completed_field checkpoint 2>/dev/null || true)" = "cleanup_completed" ] || {
      failure_category="state_conflict"
      exit 1
    }
    remove_current_remote_helper || {
      failure_category="retention_failed"
      exit 1
    }
    mark_operation_success "$compose_path"
    printf '%s\n' 'release-operation:v=1 result=success'
    ;;
  resume)
    resume_target="${4:-}"
    compose_path="${5:-}"
    digest="${6:-}"
    expected_revision="${7:-}"
    case "$resume_target" in quiesce|migrate|start|cleanup) ;;
      *) exit 2 ;;
    esac
    validate_compose_path "$compose_path" && validate_digest "$digest" && validate_revision "$expected_revision" || exit 2
    acquire_operation_lock
    begin_operation "resume-$resume_target" "$expected_revision" "$digest" "$compose_path"
    case "$resume_target" in
      quiesce) continue_quiesce "$compose_path" "$digest" "$expected_revision" ;;
      migrate) migrate_verified_image "$compose_path" "$digest" "$expected_revision" ;;
      start) start_and_probe_app "$compose_path" "$digest" "$expected_revision" "yes" ;;
      cleanup)
        cleanup_status=0
        cleanup_successful_release "$compose_path" "$digest" "$expected_revision" || cleanup_status=$?
        [ "$cleanup_status" = "0" ] || [ "$cleanup_status" = "2" ] || exit "$cleanup_status"
        ;;
    esac
    mark_operation_success "$compose_path"
    printf '%s\n' 'release-operation:v=1 result=success'
    ;;
  *)
    echo "remote-release: expected preflight, prepare, publish, quiesce, migrate, start, cleanup, status, abort, retention, helper-cleanup or resume" >&3
    exit 2
    ;;
esac

trap - EXIT HUP INT TERM
