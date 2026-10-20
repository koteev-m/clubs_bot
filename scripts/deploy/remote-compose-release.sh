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

# Status keeps its existing read-only contract. Every mutating entrypoint,
# including explicit recovery, requires kernel evidence of the inherited host
# lock before any project lock/root/intent. No flag or pathname override.
if [ "$mode" != "status" ] && {
  [ "$mode" != "corrected-start" ] || { [ "${7:-}" != "inspect" ] && [ "${7:-}" != "reconcile" ]; }
}; then
  python3 -I -S -B -c "$(cat <<'CLB_HOST_LOCK'
import os, stat, sys
from pathlib import Path
LOCK_PATH = '/run/lock/shared-staging-docker.lock'
LOCK_FD = 19
LOCK_OWNER_UID = 0
class Refused(Exception): pass
def require(condition):
    if not condition:
        raise Refused('untrusted_lock_or_protocol')


def lock_identity(fd, path=LOCK_PATH):
    require(sys.platform == 'linux')
    # Root-owned sticky /run/lock is supported. Operators must never replace the
    # file while participants are active. Deploy users cannot unlink this inode.
    for parent in Path(path).parents:
        info = parent.lstat()
        require(stat.S_ISDIR(info.st_mode) and info.st_uid in (0, LOCK_OWNER_UID))
        require(not info.st_mode & 0o022 or info.st_mode & stat.S_ISVTX)
    info, named = os.fstat(fd), os.lstat(path)
    require(stat.S_ISREG(info.st_mode) and stat.S_ISREG(named.st_mode))
    require(info.st_uid == LOCK_OWNER_UID and info.st_nlink == 1)
    require(stat.S_IMODE(info.st_mode) == 0o660)
    require((info.st_dev, info.st_ino) == (named.st_dev, named.st_ino))
    require(not os.listxattr(fd))  # no hidden ACL granting broader access
    return info.st_dev, info.st_ino


def verify_held(fd=LOCK_FD, path=LOCK_PATH):
    lock_identity(fd, path)
    # Kernel evidence on this open file description, not a PID/existence test
    # and not a second flock acquisition (which could deadlock or mint proof).
    lines = Path(f'/proc/self/fdinfo/{fd}').read_text().splitlines()
    require(any(line.startswith('lock:') and ' FLOCK  ADVISORY  WRITE ' in line
                and line.endswith(' 0 EOF') for line in lines))

verify_held()

CLB_HOST_LOCK
)" 2>/dev/null || {
    echo "remote-release: shared host lock required; read-only reconciliation only" >&2
    exit 75
  }
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

state_file_exists() {
  [ -e "$state_dir/$1" ] || [ -L "$state_dir/$1" ]
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
  if state_file_exists migration_image_id; then
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

# Additive corrected-start path: helper-owned context and locks. FD 4 is data
# only; no caller root/lock descriptor or lock-held bypass is accepted.
bound_corrected_start() {
  local bound_python
  IFS= read -r -d '' bound_python <<'CLB82_BOUND_PYTHON' || true
# Embedded implementation bytes: no imports from checkout, cwd or user site.
import base64
import contextlib
import fcntl
import hashlib
import json
import os
import re
import selectors
import shlex
import signal
import socket
import stat
import struct
import subprocess
import sys
import time
import tempfile


# Diagnostic data only. No guard state survives a successful scope or a caught
# RPC failure; only the exception causing the terminal block carries a tag.
INSPECT_GUARDS = frozenset(('control', 'input', 'principal', 'compose_chain', 'compose_owner',
    'protocol_layout', 'protocol_device', 'lock_files', 'lock_shared', 'context_edges',
    'application_binding', 'mount_query', 'mount_identity', 'configuration_capture',
    'compose_file', 'compose_file_type', 'compose_file_owner', 'compose_file_nlink',
    'compose_file_mode', 'compose_file_device', 'compose_file_size', 'compose_subset',
    'override', 'dotenv', 'compose_command', 'compose_model',
    'runtime', 'semantic_equivalence', 'binding_candidate', 'retained_layout', 'retained_identity', 'retained_checkpoint',
    'prior_override', 'migration_records', 'result_record', 'worker_protocol', 'worker_capture',
    'status_classification', 'status_read', 'inspect_output', 'finalize', 'interrupted', 'internal'))
INSPECT_FAILURES = frozenset(('invalid', 'mismatch', 'missing', 'permission', 'busy',
    'command', 'protocol', 'io', 'interrupted', 'internal'))
INSPECT_MODE = len(sys.argv) == 7 and sys.argv[6] == 'inspect'


class InspectRejected(RuntimeError):
    pass


class InspectFailure(Exception):
    def __init__(self, guard, failure):
        self.guard, self.failure = guard, failure


@contextlib.contextmanager
def inspect_guard(guard, rejected='invalid'):
    if not INSPECT_MODE:
        yield
        return
    try:
        yield
    except InspectFailure:
        raise
    except BaseException as error:
        failure = 'internal'
        if isinstance(error, (InterruptedError, KeyboardInterrupt)):
            guard, failure = 'interrupted', 'interrupted'
        elif isinstance(error, InspectRejected):
            failure = rejected
        elif isinstance(error, FileNotFoundError):
            failure = 'missing'
        elif isinstance(error, PermissionError):
            failure = 'permission'
        elif isinstance(error, BlockingIOError):
            failure = 'busy'
        elif isinstance(error, (subprocess.CalledProcessError, subprocess.TimeoutExpired)):
            failure = 'command'
        elif isinstance(error, OSError):
            failure = 'io'
        elif isinstance(error, (UnicodeError, json.JSONDecodeError)):
            failure = 'invalid'
        raise InspectFailure(guard, failure) from None


def inspect_guarded(guard, rejected='invalid'):
    def decorate(function):
        def invoke(*args, **kwargs):
            with inspect_guard(guard, rejected):
                return function(*args, **kwargs)
        return invoke
    return decorate


def inspect_diagnostic(error):
    guard, failure = 'internal', 'internal'
    if isinstance(error, InspectFailure):
        guard, failure = error.guard, error.failure
    elif isinstance(error, (InterruptedError, KeyboardInterrupt)):
        guard, failure = 'interrupted', 'interrupted'
    if type(guard) is not str or type(failure) is not str or guard not in INSPECT_GUARDS or failure not in INSPECT_FAILURES:
        guard, failure = 'internal', 'internal'
    return f'corrected-inspect:v=1 guard={guard} failure={failure}\n'


def check(ok):
    if not ok:
        raise (InspectRejected if INSPECT_MODE else RuntimeError)('bound context rejected')


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def sha(value):
    return hashlib.sha256(value).hexdigest()


def unique(pairs):
    result = {}
    for key, value in pairs:
        check(key not in result)
        result[key] = value
    return result


def write_all(fd, data):
    while data:
        count = os.write(fd, data)
        check(count > 0)
        data = data[count:]


def read_all(fd, limit):
    data = bytearray()
    while True:
        chunk = os.read(fd, min(65536, limit + 1 - len(data)))
        if not chunk:
            return bytes(data)
        data.extend(chunk)
        check(len(data) <= limit)


def identity(fd):
    value = os.fstat(fd)
    return [value.st_dev, value.st_ino]


def fdpath(fd):
    path = f'/proc/{os.getpid()}/fd/{fd}'
    if not os.path.exists(path):
        path = f'/dev/fd/{fd}'
    check(os.path.exists(path))
    return path


# BEGIN CLB91 FIXED EXECUTOR SOURCES
import types
CLB91_PLAN = {}
exec(compile('import json, math, re\nLINUX_RUBY_LOAD = "$LOAD_PATH.replace([\'/usr/lib/ruby/3.2.0\', \'/usr/lib/x86_64-linux-gnu/ruby/3.2.0\']);\\n"\nFILE_LIMIT = 65536\nMODEL_LIMIT = 262144\nNAME = re.compile(\'[A-Za-z_][A-Za-z0-9_]*\\\\Z\')\nREASONS = frozenset((\'input\', \'unsupported\', \'version\', \'parser\', \'model\', \'different\', \'io\', \'cleanup\', \'interrupted\', \'bounds\'))\nCONTROL_KEYS = frozenset((\'HOME\', \'PATH\', \'TMPDIR\', \'TMP\', \'TEMP\', \'ENV\', \'BASH_ENV\', \'SHELLOPTS\', \'BASHOPTS\', \'CDPATH\', \'RUBYOPT\', \'RUBYLIB\', \'GEM_HOME\', \'GEM_PATH\', \'LD_PRELOAD\', \'LD_LIBRARY_PATH\', \'PWD\', \'OLDPWD\', \'SHLVL\', \'_\', \'IFS\', \'OPTIND\', \'OPTARG\', \'PPID\', \'UID\', \'EUID\', \'RANDOM\', \'SECONDS\', \'LINENO\'))\nOUTLINE = \'\\nrequire \\\'psych\\\'; require \\\'json\\\'\\nbegin\\n  raise unless ARGV.empty? || ARGV == [\\\'helper-one-dotenv\\\']\\n  strict_helper = ARGV == [\\\'helper-one-dotenv\\\']\\n  raw = STDIN.read(65537)\\n  raise unless raw.bytesize <= 65536 && raw.valid_encoding?\\n  tree = Psych.parse_stream(raw)\\n  raise unless tree.children.size == 1\\n  count = 0\\n  references = []\\n  walk = lambda do |n, depth, scope|\\n    count += 1\\n    raise if count > 4096 || depth > 32\\n    raise unless [Psych::Nodes::Stream, Psych::Nodes::Document, Psych::Nodes::Mapping,\\n                  Psych::Nodes::Sequence, Psych::Nodes::Scalar].include?(n.class)\\n    raise if (n.respond_to?(:anchor) && n.anchor) || (n.respond_to?(:tag) && n.tag)\\n    if n.is_a?(Psych::Nodes::Scalar)\\n      # YAML escapes can synthesize \\\'$\\\' or variable-name letters. Apply the\\n      # Python control-name policy to decoded references in EVERY scalar scope.\\n      references.concat(n.value.scan(/\\\\$\\\\{?([A-Za-z_][A-Za-z0-9_]*)/).flatten)\\n    end\\n    if n.is_a?(Psych::Nodes::Mapping)\\n      raise unless n.style == Psych::Nodes::Mapping::BLOCK\\n      seen = {}\\n      n.children.each_slice(2) do |k,v|\\n        raise unless k.is_a?(Psych::Nodes::Scalar) && k.plain &&\\n                     k.value.match?(/\\\\A[A-Za-z0-9_.-]+\\\\z/) && !seen[k.value]\\n        if strict_helper\\n          raise if %w[build include extends secrets configs external].include?(k.value)\\n          raise if k.value == \\\'env_file\\\' && scope != %w[services app]\\n        end\\n        seen[k.value] = true\\n        walk.call(k, depth + 1, scope + [k.value])\\n        walk.call(v, depth + 1, scope + [k.value])\\n      end\\n    else\\n      (n.children || []).each { |child| walk.call(child, depth + 1, scope) }\\n    end\\n  end\\n  walk.call(tree, 0, [])\\n  mapping = lambda do |n|\\n    raise unless n.is_a?(Psych::Nodes::Mapping)\\n    n.children.each_slice(2).to_h { |k,v| [k.value, [k,v]] }\\n  end\\n  root = mapping.call(tree.children[0].root)\\n  raise unless (root.keys - %w[services volumes networks version name]).empty?\\n  services = mapping.call(root.fetch(\\\'services\\\')[1])\\n  allowed = %w[image container_name depends_on environment healthcheck volumes ports restart\\n               expose command entrypoint networks working_dir user read_only init labels\\n               stop_grace_period env_file]\\n  environment_names = []\\n  services.each do |name, pair|\\n    fields = mapping.call(pair[1])\\n    raise unless (fields.keys - allowed).empty?\\n    raise if name != \\\'app\\\' && fields.key?(\\\'env_file\\\')\\n    if fields.key?(\\\'environment\\\')\\n      entries = mapping.call(fields[\\\'environment\\\'][1])\\n      raise if entries.empty?\\n      entries.each do |variable, entry|\\n        raise unless variable.match?(/\\\\A[A-Za-z_][A-Za-z0-9_]*\\\\z/) &&\\n                     entry[1].is_a?(Psych::Nodes::Scalar) && entry[1].start_line == entry[1].end_line\\n      end\\n      environment_names.concat(entries.keys)\\n    end\\n    if fields.key?(\\\'volumes\\\')\\n      mounts = fields[\\\'volumes\\\'][1]\\n      raise unless mounts.is_a?(Psych::Nodes::Sequence)\\n      mounts.children.each do |mount|\\n        # A tilde bind depends on parser HOME/user lookup, outside the supplied\\n        # non-control interpolation context. Restrict this check to sources;\\n        # ordinary environment strings containing \\\'~\\\' retain their semantics.\\n        raise unless mount.is_a?(Psych::Nodes::Scalar) && mount.start_line == mount.end_line\\n        # An interpolated source could become \\\'~...\\\' only after expansion;\\n        # decline dynamic sources instead of adding another interpolation parser.\\n        source = mount.value.split(\\\':\\\', 2)[0]\\n        raise if source.start_with?(\\\'~\\\') || source.include?(\\\'$\\\')\\n      end\\n    end\\n  end\\n  app = mapping.call(services.fetch(\\\'app\\\')[1])\\n  key, refs = app.fetch(\\\'env_file\\\')\\n  raise unless refs.is_a?(Psych::Nodes::Sequence) && refs.children.size.between?(1,16)\\n  raise if strict_helper && refs.children.size != 1\\n  refs.children.each do |item|\\n    raise unless item.is_a?(Psych::Nodes::Scalar) && %w[.env ./.env].include?(item.value) &&\\n                 item.start_line == item.end_line\\n    if strict_helper\\n      token = raw.lines[item.start_line].each_char.to_a[item.start_column...item.end_column].join\\n      raise unless item.value == \\\'.env\\\' && [\\\'.env\\\', "\\\'.env\\\'", \\\'".env"\\\'].include?(token)\\n    end\\n  end\\n  last_line = refs.style == Psych::Nodes::Sequence::FLOW ? refs.end_line : refs.children[-1].end_line\\n  raise if refs.style == Psych::Nodes::Sequence::FLOW && refs.start_line != refs.end_line\\n  env = app[\\\'environment\\\']\\n  names = []; insert = key.start_line; indent = key.start_column + 2\\n  if env\\n    env_fields = mapping.call(env[1])\\n    names = env_fields.keys\\n    insert = env_fields.values[-1][1].end_line + 1\\n    indent = env_fields.values[0][0].start_column\\n  end\\n  puts JSON.generate({remove: [key.start_line,last_line+1], insert: insert,\\n                      indent: indent, has_environment: !!env, names: names,\\n                      environment_names: environment_names, references: references.uniq,\\n                      dotenv_spans: refs.children.map { |n| [n.start_line,n.start_column,n.end_column] }})\\nrescue Exception\\n  exit 1\\nend\\n\'\nHELPER_OUTLINE = "ARGV.replace([\'helper-one-dotenv\']);\\n" + OUTLINE\n\nclass Refused(Exception):\n\n    def __init__(self, reason):\n        self.reason = reason if reason in REASONS else \'input\'\n        super().__init__(self.reason)\n\ndef need(ok, reason=\'input\'):\n    if not ok:\n        raise Refused(reason)\n\nclass Plan:\n    __slots__ = (\'candidate\', \'strategy\')\n\n    def __init__(self, candidate, strategy):\n        self.candidate, self.strategy = (candidate, strategy)\n\n    def __repr__(self):\n        return \'<private CLB-91 plan; revalidation required>\'\n\n    def public(self):\n        return \'compose-env-file-plan:v=1 result=equivalent strategy=\' + self.strategy + \' scope=snapshot future=requires_recheck application=not_authorized\'\n\nclass HelperPlan(Plan):\n    __slots__ = (\'model\',)\n\n    def __init__(self, candidate, strategy, model):\n        super().__init__(candidate, strategy)\n        self.model = model\n\ndef controlled(name):\n    return name in CONTROL_KEYS or name.startswith((\'COMPOSE_\', \'DOCKER_\', \'PYTHON\', \'BASH\', \'DYLD_\', \'LD_\', \'RUBY\', \'GEM_\', \'GO\', \'OTEL_\'))\n\ndef unique(pairs):\n    result = {}\n    for key, value in pairs:\n        need(key not in result, \'model\')\n        result[key] = value\n    return result\n\ndef same_json(left, right):\n\n    def identity(value):\n        kind = type(value)\n        if kind is type(None):\n            return (\'null\',)\n        if kind is bool:\n            return (\'boolean\', value)\n        if kind is int:\n            return (\'integer\', value)\n        if kind is float:\n            need(math.isfinite(value), \'model\')\n            return (\'float\', value.hex())\n        if kind is str:\n            return (\'string\', value)\n        if kind is list:\n            return (\'array\', tuple((identity(item) for item in value)))\n        if kind is dict:\n            need(all((type(key) is str for key in value)), \'model\')\n            return (\'object\', tuple(((key, identity(value[key])) for key in sorted(value))))\n        raise Refused(\'model\')\n    try:\n        return identity(left) == identity(right)\n    except RecursionError:\n        raise Refused(\'model\') from None\n\ndef transform(base, outline, additions=(), *, candidate_check=None, allow_controls=False):\n    lines = base.splitlines(keepends=True)\n    start, end = outline[\'remove\']\n    inserted = []\n    if additions:\n        if not outline[\'has_environment\']:\n            inserted.append(b\' \' * (outline[\'indent\'] - 2) + b\'environment:\\n\')\n        for name in additions:\n            need(NAME.fullmatch(name) and (allow_controls or not controlled(name)), \'unsupported\')\n            inserted.append((\' \' * outline[\'indent\'] + name + \': ${\' + name + \'?CLB91 required contribution}\\n\').encode())\n    result = []\n    for index in range(len(lines) + 1):\n        if index == outline[\'insert\']:\n            if result and (not result[-1].endswith(b\'\\n\')):\n                result[-1] += b\'\\n\'\n            result.extend(inserted)\n        if index < len(lines) and (not start <= index < end):\n            result.append(lines[index])\n    candidate = b\'\'.join(result)\n    need(len(candidate) <= FILE_LIMIT, \'bounds\')\n    if candidate_check is None:\n        need(not DIAGNOSTIC[\'scan\'](candidate)[\'violations\'], \'unsupported\')\n    else:\n        need(candidate_check(candidate) is True, \'unsupported\')\n    return candidate\n\ndef environment(model):\n    app = model.get(\'services\', {}).get(\'app\')\n    need(type(app) is dict and \'env_file\' not in app, \'model\')\n    values = app.get(\'environment\', {})\n    need(type(values) is dict and all((type(k) is str and NAME.fullmatch(k) and (v is None or type(v) is str) for k, v in values.items())), \'model\')\n    return values\n\ndef project_dotenv(base, outline, reference):\n    need(re.fullmatch(\'/proc/[1-9][0-9]*/fd/[0-9]+\', reference), \'unsupported\')\n    lines = base.decode(\'utf-8\').splitlines(keepends=True)\n    spans = outline[\'dotenv_spans\']\n    need(1 <= len(spans) <= 16 and len({tuple(s) for s in spans}) == len(spans), \'unsupported\')\n    for line, start, end in sorted(spans, reverse=True):\n        need(0 <= line < len(lines) and 0 <= start < end <= len(lines[line]), \'unsupported\')\n        lines[line] = lines[line][:start] + json.dumps(reference) + lines[line][end:]\n    projected = \'\'.join(lines).encode()\n    need(len(projected) <= FILE_LIMIT, \'bounds\')\n    return projected\n\ndef frozen_model(model):\n    need(type(model) is dict, \'model\')\n    same_json(model, model)\n    environment(model)\n    raw = json.dumps(model, sort_keys=True, separators=(\',\', \':\'), allow_nan=False).encode()\n    need(len(raw) <= MODEL_LIMIT, \'bounds\')\n    return json.loads(raw, object_pairs_hook=unique)\n\ndef decode_model(raw):\n    try:\n        need(type(raw) is bytes and 0 < len(raw) <= MODEL_LIMIT, \'bounds\')\n        model = json.loads(raw, object_pairs_hook=unique, parse_constant=lambda _: (_ for _ in ()).throw(Refused(\'model\')))\n        return frozen_model(model)\n    except Refused:\n        raise\n    except (KeyboardInterrupt, InterruptedError):\n        raise Refused(\'interrupted\') from None\n    except BaseException:\n        raise Refused(\'model\') from None\n\ndef prove_semantics(base, outline, before_input, normalize, *, candidate_check=None, allow_controls=False):\n\n    def model(data, with_override=True):\n        return frozen_model(normalize(data, with_override))\n    before = model(before_input)\n    candidate = transform(base, outline, candidate_check=candidate_check, allow_controls=allow_controls)\n    after = model(candidate)\n    strategy = \'remove\'\n    if not same_json(before, after):\n        old, new = (environment(before), environment(after))\n        additions = sorted(old.keys() - new.keys())\n        need(0 < len(additions) <= 256 and all((old[k] is not None for k in additions)), \'different\')\n        need(not set(additions).intersection(outline[\'names\']), \'different\')\n        candidate = transform(base, outline, additions, candidate_check=candidate_check, allow_controls=allow_controls)\n        after = model(candidate)\n        strategy = \'explicit\'\n    need(same_json(before, after), \'different\')\n    encoded = lambda value: json.dumps(value, sort_keys=True, separators=(\',\', \':\')).encode()\n    need(same_json(model(encoded(before), False), before), \'model\')\n    need(same_json(model(encoded(after), False), after), \'model\')\n    return (Plan(candidate, strategy), before, after)\n\ndef helper_prepare(base, *, dotenv_reference, effective_environment, outline_parser, normalize, candidate_check):\n    try:\n        need(type(base) is bytes and 0 < len(base) <= FILE_LIMIT, \'bounds\')\n        need(b\'\\x00\' not in base and b\'\\r\' not in base, \'unsupported\')\n        need(type(effective_environment) is dict and len(effective_environment) <= 4096)\n        need(all((type(k) is str and k and (\'=\' not in k) and (\'\\x00\' not in k) and (type(v) is str) and (\'\\x00\' not in v) for k, v in effective_environment.items())))\n        need(sum((len(k.encode()) + len(v.encode()) for k, v in effective_environment.items())) <= MODEL_LIMIT, \'bounds\')\n        snapshot = dict(effective_environment)\n        raw = outline_parser(HELPER_OUTLINE, base)\n        need(type(raw) is bytes and 0 < len(raw) <= FILE_LIMIT, \'parser\')\n        outline = json.loads(raw, object_pairs_hook=unique)\n        need(type(outline) is dict and set(outline) == {\'remove\', \'insert\', \'indent\', \'has_environment\', \'names\', \'environment_names\', \'references\', \'dotenv_spans\'}, \'parser\')\n        need(type(outline[\'dotenv_spans\']) is list and len(outline[\'dotenv_spans\']) == 1, \'unsupported\')\n        transform(base, outline, candidate_check=candidate_check, allow_controls=True)\n        projected = project_dotenv(base, outline, dotenv_reference)\n\n        def actual_normalize(data, with_override):\n            need(effective_environment == snapshot, \'input\')\n            result = normalize(data, with_override, effective_environment)\n            need(effective_environment == snapshot, \'input\')\n            return result\n        plan, _, after = prove_semantics(base, outline, projected, actual_normalize, candidate_check=candidate_check, allow_controls=True)\n        need(effective_environment == snapshot, \'input\')\n        return HelperPlan(plan.candidate, plan.strategy, after)\n    except Refused:\n        raise\n    except (KeyboardInterrupt, InterruptedError):\n        raise Refused(\'interrupted\') from None\n    except BaseException:\n        raise Refused(\'input\') from None\n', '<clb91-planner>', 'exec'), CLB91_PLAN)
CLB91_CAPTURE = {}
exec(compile("import os, selectors, signal, subprocess, time\ndef identity(value):\n    return (value.st_dev, value.st_ino, value.st_uid, value.st_gid, value.st_mode, value.st_nlink, value.st_size, value.st_mtime_ns, value.st_ctime_ns)\n\nclass CaptureResult:\n    __slots__ = ('code', 'output', 'failure')\n\n    def __init__(self, code, output, failure=None):\n        self.code, self.output, self.failure = (code, output, failure)\n\nclass CaptureInterrupted(BaseException):\n    pass\n\nclass CaptureCleanupError(OSError):\n    pass\n_capture_cancellation = None\n\ndef interrupted(_signum, _frame):\n    if _capture_cancellation is not None:\n        _capture_cancellation[0] = True\n        return\n    for watched in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):\n        signal.signal(watched, signal.SIG_IGN)\n    raise CaptureInterrupted\n\ndef capture(argv, payload=b'', *, timeout=30, limit=32768, env=None, pass_fds=(), spawn_failed=None, cwd_fd=None):\n    result = capture_result(argv, payload, timeout=timeout, limit=limit, env=env, pass_fds=pass_fds, spawn_failed=spawn_failed, cwd_fd=cwd_fd)\n    return (result.code, result.output)\n\ndef capture_result(argv, payload=b'', *, timeout=30, limit=32768, env=None, pass_fds=(), spawn_failed=None, cwd_fd=None):\n    global _capture_cancellation\n    cancellation = [False]\n    previous = _capture_cancellation\n    result, failure = (None, None)\n    _capture_cancellation = cancellation\n    try:\n        result = _capture_owned(argv, payload, timeout=timeout, limit=limit, env=env, pass_fds=pass_fds, spawn_failed=spawn_failed, cancellation=cancellation, cwd_fd=cwd_fd)\n    except BaseException as error:\n        failure = error\n    finally:\n        _capture_cancellation = previous\n    if isinstance(failure, CaptureCleanupError):\n        raise failure\n    if cancellation[0]:\n        if previous is not None:\n            previous[0] = True\n        return CaptureResult(124, result.output if result is not None else b'', 'capture_interrupted')\n    if failure is not None:\n        raise failure\n    return result\n\ndef _capture_owned(argv, payload, *, timeout, limit, env, pass_fds, spawn_failed, cancellation, cwd_fd):\n    output = bytearray()\n    offset = 0\n    deadline = time.monotonic() + timeout\n\n    def held_cwd():\n        os.fchdir(cwd_fd)\n    try:\n        child = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=env, pass_fds=pass_fds, start_new_session=True, preexec_fn=held_cwd if cwd_fd is not None else None)\n    except OSError:\n        if spawn_failed is not None:\n            spawn_failed()\n        raise\n    group = child.pid\n    try:\n        with selectors.DefaultSelector() as poll:\n            os.set_blocking(child.stdin.fileno(), False)\n            os.set_blocking(child.stdout.fileno(), False)\n            poll.register(child.stdout, selectors.EVENT_READ)\n            if payload:\n                poll.register(child.stdin, selectors.EVENT_WRITE)\n            else:\n                child.stdin.close()\n            while poll.get_map():\n                if cancellation[0]:\n                    return CaptureResult(124, bytes(output), 'capture_interrupted')\n                remaining = deadline - time.monotonic()\n                if remaining <= 0:\n                    return CaptureResult(124, bytes(output), 'capture_timeout')\n                for key, _ in poll.select(min(remaining, 0.2)):\n                    if key.fileobj is child.stdin:\n                        try:\n                            offset += os.write(child.stdin.fileno(), payload[offset:offset + 4096])\n                        except BrokenPipeError:\n                            offset = len(payload)\n                        if offset == len(payload):\n                            poll.unregister(child.stdin)\n                            child.stdin.close()\n                    else:\n                        chunk = os.read(child.stdout.fileno(), 4096)\n                        if not chunk:\n                            poll.unregister(child.stdout)\n                        else:\n                            output.extend(chunk)\n                            if len(output) > limit:\n                                return CaptureResult(125, b'', 'capture_output_limit')\n            while True:\n                if cancellation[0]:\n                    return CaptureResult(124, bytes(output), 'capture_interrupted')\n                exited = os.waitid(os.P_PID, group, os.WEXITED | os.WNOHANG | os.WNOWAIT)\n                if exited is not None:\n                    code = exited.si_status if exited.si_code == os.CLD_EXITED else -exited.si_status\n                    return CaptureResult(code, bytes(output))\n                remaining = deadline - time.monotonic()\n                if remaining <= 0:\n                    return CaptureResult(124, bytes(output), 'capture_timeout')\n                time.sleep(min(remaining, 0.01))\n    except subprocess.TimeoutExpired:\n        return CaptureResult(124, bytes(output), 'capture_timeout')\n    except (InterruptedError, CaptureInterrupted):\n        return CaptureResult(124, bytes(output), 'capture_interrupted')\n    finally:\n        cleanup_failed = False\n        permission_denied = False\n        try:\n            os.killpg(group, signal.SIGKILL)\n        except ProcessLookupError:\n            pass\n        except PermissionError:\n            permission_denied = True\n        except OSError:\n            cleanup_failed = True\n        try:\n            child.wait(timeout=2)\n        except (OSError, subprocess.TimeoutExpired):\n            cleanup_failed = True\n        finally:\n            for stream in (child.stdin, child.stdout):\n                try:\n                    if not stream.closed:\n                        stream.close()\n                except OSError:\n                    cleanup_failed = True\n        if permission_denied:\n            try:\n                os.killpg(group, 0)\n            except ProcessLookupError:\n                pass\n            except OSError:\n                cleanup_failed = True\n            else:\n                cleanup_failed = True\n        if cleanup_failed:\n            raise CaptureCleanupError() from None\n", '<clb91-capture>', 'exec'), CLB91_CAPTURE)
CLB91_SEMANTIC = {}
exec(compile('import hashlib, json, os, platform, re, stat, sys\nfrom pathlib import Path\nPREREQUISITES = (\'platform\', \'manifest\', \'availability\', \'path_safety\', \'integrity\', \'interpreter\', \'modules\', \'aliases\', \'maps\', \'descriptors\', \'private_root\', \'ruby\')\nPHASES = (\'initial\', \'pre_capture\', \'post_open\', \'capture\', \'prepare\', \'post_prepare\', \'finalize\')\nSTATUSES = (\'pass\', \'fail\', \'not_evaluated\')\n\nclass Evidence:\n\n    def __init__(self):\n        self.phase, self.capture = (\'initial\', \'not_started\')\n        self.first = None\n        self.observations = {key: [] for key in PREREQUISITES}\n\n    def record(self, guard, status):\n        self.observations[guard].append(status)\n        if status == \'fail\' and self.first is None:\n            self.first = (self.phase, guard)\n\nclass RuntimeMismatch(Exception):\n    pass\n\nclass Runtime:\n    FILE_BYTES = 33554432\n    TOTAL_BYTES = 134217728\n\n    def __init__(self, cancelled, evidence=None):\n        self.cancelled, self.held = (cancelled, [])\n        self.evidence = evidence or Evidence()\n        self.total = 0\n\n    def check(self, ok):\n        if self.cancelled():\n            raise P.Refused(\'interrupted\')\n        if not ok:\n            raise RuntimeMismatch()\n\n    def observe(self, guard, check):\n        self.check(True)\n        try:\n            check()\n        except P.Refused:\n            raise\n        except (KeyboardInterrupt, InterruptedError):\n            raise\n        except Exception:\n            self.evidence.record(guard, \'fail\')\n            return False\n        self.evidence.record(guard, \'pass\')\n        return True\n\n    def require_compatible(self):\n        self.check(True)\n        if self.evidence.first is not None:\n            raise RuntimeMismatch()\n\n    def manifest(self):\n        self.check(type(MANIFEST) is dict and MANIFEST.get(\'format\') == 1)\n        files = MANIFEST[\'files\']\n        self.check(type(files) is dict and 1 <= len(files) <= 256 and (COMPOSE in files))\n        self.check(all((type(path) is str and type(digest) is str and re.fullmatch(\'[0-9a-f]{64}\', digest) for path, digest in files.items())))\n        aliases = MANIFEST[\'aliases\']\n        self.check(type(aliases) is dict and len(aliases) <= 256 and all((type(k) is str and type(v) is str for k, v in aliases.items())))\n\n    def open(self):\n        self.observe(\'platform\', lambda: self.check(sys.platform == \'linux\' and platform.machine() == \'x86_64\' and sys.flags.isolated and sys.flags.no_site))\n        if not self.observe(\'manifest\', self.manifest):\n            self.require_compatible()\n        files = MANIFEST[\'files\']\n        for path, digest in files.items():\n\n            def safe_path():\n                self.check(path == os.path.realpath(path) and os.path.isabs(path))\n                for parent in Path(path).parents:\n                    value = parent.stat()\n                    self.check(value.st_uid == 0 and stat.S_ISDIR(value.st_mode) and (not value.st_mode & 18))\n            available = self.observe(\'availability\', lambda: os.stat(path, follow_symlinks=False))\n            safe = self.observe(\'path_safety\', safe_path)\n            if not (available and safe):\n                self.evidence.record(\'path_safety\', \'not_evaluated\')\n                self.evidence.record(\'integrity\', \'not_evaluated\')\n                continue\n            fd = None\n\n            def acquire():\n                nonlocal fd\n                fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)\n                self.held.append((path, fd, None))\n                value = os.fstat(fd)\n                self.held[-1] = (path, fd, D.identity(value))\n            if not self.observe(\'availability\', acquire):\n                self.evidence.record(\'path_safety\', \'not_evaluated\')\n                self.evidence.record(\'integrity\', \'not_evaluated\')\n                continue\n\n            def metadata():\n                value = os.fstat(fd)\n                self.check(stat.S_ISREG(value.st_mode) and value.st_uid == 0 and (not value.st_mode & 18) and (value.st_size <= self.FILE_BYTES))\n            if not self.observe(\'path_safety\', metadata):\n                self.evidence.record(\'integrity\', \'not_evaluated\')\n                continue\n\n            def hash_file():\n                actual = hashlib.sha256()\n                while True:\n                    self.check(True)\n                    self.check(self.total <= self.TOTAL_BYTES)\n                    data = os.read(fd, min(65536, self.TOTAL_BYTES + 1 - self.total))\n                    self.total += len(data)\n                    self.check(self.total <= self.TOTAL_BYTES)\n                    if not data:\n                        break\n                    actual.update(data)\n                self.check(actual.hexdigest() == digest)\n            self.observe(\'integrity\', hash_file)\n        self.observe(\'interpreter\', lambda: self.check(os.path.realpath(\'/proc/self/exe\') in files))\n        self.observe(\'modules\', self.modules)\n        self.recheck()\n\n    def modules(self):\n        for module in tuple(sys.modules.values()):\n            for attribute in (\'__file__\', \'__cached__\'):\n                path = getattr(module, attribute, None)\n                if path:\n                    if attribute == \'__file__\':\n                        self.check(Path(path).is_file())\n                    if Path(path).is_file():\n                        self.check(os.path.realpath(path) in MANIFEST[\'files\'])\n\n    def mapped_files(self):\n        with open(\'/proc/self/maps\', \'rb\') as stream:\n            raw = stream.read(65537)\n        self.check(len(raw) <= 65536)\n        for line in raw.decode(\'utf-8\').splitlines():\n            fields = line.split(None, 5)\n            if len(fields) == 6 and fields[-1].startswith(\'/\'):\n                self.check(fields[-1] in MANIFEST[\'files\'])\n\n    def ruby_probe(self, temporary_root):\n        self.require_compatible()\n        script = P.LINUX_RUBY_LOAD + "require \'psych\'; require \'json\'\\nmaps=File.read(\'/proc/self/maps\',65537)\\nraise unless maps.bytesize<=65536\\npaths=maps.lines.filter_map { |l| p=l.split(nil,6)[5]&.strip; p if p&.start_with?(\'/\') }\\nputs JSON.generate({ruby:RUBY_VERSION,psych:Psych::VERSION,files:($LOADED_FEATURES.grep(%r{^/})+paths).uniq})\\n"\n\n        def probe():\n            try:\n                result = D.capture_result([\'/usr/bin/ruby\', \'--disable-gems\', \'-e\', script], env=dict(PATH=\'/usr/bin:/bin\', HOME=temporary_root, LC_ALL=\'C\'), timeout=5, limit=32768)\n            except D.CaptureCleanupError:\n                raise P.Refused(\'cleanup\') from None\n            if result.failure == \'capture_interrupted\':\n                raise P.Refused(\'interrupted\')\n            self.check(result.failure is None and result.code == 0)\n            data = json.loads(result.output)\n            self.check(set(data) == {\'ruby\', \'psych\', \'files\'} and data[\'ruby\'] == MANIFEST[\'ruby\'] and (data[\'psych\'] == MANIFEST[\'psych\']) and (type(data[\'files\']) is list) and (len(data[\'files\']) <= 256))\n            self.check(all((type(path) is str and os.path.realpath(path) in MANIFEST[\'files\'] for path in data[\'files\'])))\n        self.observe(\'ruby\', probe)\n        self.recheck()\n\n    def recheck(self):\n        self.observe(\'maps\', self.mapped_files)\n\n        def aliases():\n            for alias, expected in MANIFEST[\'aliases\'].items():\n                self.check(os.path.realpath(alias) == expected)\n        self.observe(\'aliases\', aliases)\n\n        def descriptors():\n            for path, fd, value in self.held:\n                self.check(value is not None and D.identity(os.fstat(fd)) == value and (D.identity(os.stat(path, follow_symlinks=False)) == value))\n        self.observe(\'descriptors\', descriptors)\n        self.require_compatible()\n\n    def close(self):\n        failed = False\n        for _, fd, _ in reversed(self.held):\n            try:\n                os.close(fd)\n            except BaseException:\n                failed = True\n        self.held.clear()\n        if failed:\n            raise P.Refused(\'cleanup\')\n', '<clb91-runtime>', 'exec'), CLB91_SEMANTIC)
CLB91_EXECUTOR = {}
exec(compile("import os\nimport re\nimport resource\nimport stat\nD = E = P = S = PROFILE = None\nCLI = '/usr/bin/docker'\nPLUGIN = '/usr/libexec/docker/cli-plugins/docker-compose'\nCONTRACT = 'clb91-cli29.3.1-compose5.1.1-config-v1'\nCLI_SHA256 = '18c2a16d6aa0331bbd78b93e0a57f344c62bda0be52c054d4ee80e11176b0c30'\nPLUGIN_SHA256 = '2ac954c9d506b912a12477d72f01601dc72ec918c429c7bae48fd707bdf0f3e5'\nSYSTEM_PLUGIN_DIRS = ('/usr/local/lib/docker/cli-plugins', '/usr/local/libexec/docker/cli-plugins', '/usr/lib/docker/cli-plugins', '/usr/libexec/docker/cli-plugins')\nREMOVED_PREFIXES = ('COMPOSE_', 'DOCKER_', 'PYTHON', 'BASH_FUNC_')\nREMOVED_NAMES = frozenset(('BASH_ENV', 'ENV', 'SHELLOPTS', 'BASHOPTS', 'CDPATH'))\nHOOK_PREFIXES = ('LD_', 'DYLD_', 'RUBY', 'GEM_', 'BUNDLE_', 'OTEL_', 'OTLP_', 'GRPC_', 'GO')\nHOOK_NAMES = frozenset(('SSL_CERT_FILE', 'SSL_CERT_DIR', 'REQUESTS_CA_BUNDLE', 'CURL_CA_BUNDLE', 'ALL_PROXY', 'all_proxy', 'HTTP_PROXY', 'http_proxy', 'HTTPS_PROXY', 'https_proxy', 'NO_PROXY', 'no_proxy', 'XDG_CONFIG_HOME', 'XDG_DATA_HOME', 'XDG_CACHE_HOME', 'XDG_RUNTIME_DIR', 'TZ', 'TMPDIR', 'TMP', 'TEMP', 'IRBRC', 'LOCPATH', 'NLSPATH', 'TERMINFO', 'TERMINFO_DIRS'))\n\nclass GateUnavailable(Exception):\n    pass\n\ndef docker_environment(environ):\n    return {k: v for k, v in environ.items() if not k.startswith(REMOVED_PREFIXES) and k not in REMOVED_NAMES}\n\nclass PipelineGate:\n\n    def __init__(self, cancelled):\n        self.cancelled = cancelled\n        self.runtime = None\n        self.held, self.negative, self.directories = ([], [], {})\n        self.alias_leases = {}\n        self.environment = None\n        self.config_fd = None\n        self.principal_name = None\n        self.initialized, self.bound, self.closed = (False, False, False)\n\n    def require(self, condition):\n        if self.cancelled():\n            raise P.Refused('interrupted') from None\n        if not condition:\n            raise GateUnavailable() from None\n\n    def profile(self):\n        self.require(type(PROFILE) is dict and set(PROFILE) == {'format', 'contract', 'runtime', 'cli', 'compose'})\n        self.require(type(PROFILE['format']) is int and PROFILE['format'] == 1 and (PROFILE['contract'] == CONTRACT))\n        for name, path, version, digest, size in (('cli', CLI, '29.3.1', CLI_SHA256, 44320674), ('compose', PLUGIN, '5.1.1', PLUGIN_SHA256, 31557130)):\n            value = PROFILE[name]\n            self.require(type(value) is dict and set(value) == {'path', 'version', 'sha256', 'size'})\n            self.require(value == dict(path=path, version=version, sha256=digest, size=size) and type(value['size']) is int)\n        manifest = PROFILE['runtime']\n        self.require(type(manifest) is dict and type(manifest.get('format')) is int and (manifest['format'] == 1) and (type(manifest.get('files')) is dict))\n        self.require(manifest['files'].get(CLI) == CLI_SHA256 and manifest['files'].get(PLUGIN) == PLUGIN_SHA256)\n        namespace = E.Runtime.manifest.__globals__\n        self.require(namespace.get('MANIFEST') is manifest and namespace.get('COMPOSE') == PLUGIN and (namespace.get('D') is D) and (namespace.get('P') is P) and (namespace.get('S') is S))\n        self.require(E.Runtime.FILE_BYTES == 33554432 and E.Runtime.TOTAL_BYTES == 134217728)\n\n    def aliases(self):\n        for alias, expected in PROFILE['runtime']['aliases'].items():\n            self.require(type(alias) is str and alias.startswith('/') and (os.path.realpath(alias) == expected))\n            current = ''\n            for part in alias.split('/')[1:]:\n                self.require(part not in ('', '.', '..'))\n                current += '/' + part\n                info = os.stat(current, follow_symlinks=False)\n                self.require(info.st_uid == 0 and (stat.S_ISLNK(info.st_mode) or not info.st_mode & 18))\n                value = self.identity(info)\n                self.require(current not in self.alias_leases or self.alias_leases[current] == value)\n                self.alias_leases[current] = value\n\n    def core_policy(self, install=False):\n        if install:\n            resource.setrlimit(resource.RLIMIT_CORE, (0, 0))\n        self.require(resource.getrlimit(resource.RLIMIT_CORE) == (0, 0))\n        with open('/proc/sys/kernel/core_pattern', 'rb') as stream:\n            pattern = stream.read(4097)\n        self.require(0 < len(pattern) <= 4096 and b'\\x00' not in pattern and (not pattern.startswith(b'|')))\n\n    def initial(self):\n        self.require(not self.closed and (not self.initialized))\n        try:\n            self.profile()\n\n            class FixedRuntime(E.Runtime):\n                FILE_BYTES, TOTAL_BYTES = (67108864, 201326592)\n            self.runtime = FixedRuntime(self.cancelled)\n            self.runtime.open()\n            self.runtime.require_compatible()\n            self.require(all((os.fstat(fd).st_nlink == 1 for _, fd, _ in self.runtime.held)))\n            self.aliases()\n            self.core_policy(install=True)\n            self.runtime.ruby_probe('/')\n            self.runtime.recheck()\n            self.initialized = True\n        except BaseException:\n            self.close()\n            raise\n\n    def keep(self, fd, path):\n        entry = [path, fd, None]\n        self.held.append(entry)\n        self.require(len(self.held) <= 48)\n        info = os.fstat(fd)\n        entry[2] = self.identity(info)\n        return (fd, info)\n\n    @staticmethod\n    def identity(info):\n        value = D.identity(info)\n        return value[:6] if stat.S_ISDIR(info.st_mode) else value\n\n    def directory(self, path, temporary=False):\n        self.require(type(path) is str and path.startswith('/') and all((part not in ('', '.', '..') for part in path.split('/')[1:])))\n        if path in self.directories:\n            return self.directories[path]\n        if '/' not in self.directories:\n            fd, info = self.keep(os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW), '/')\n            self.require(stat.S_ISDIR(info.st_mode) and info.st_uid == 0 and (not info.st_mode & 18))\n            self.directories['/'] = fd\n        parent, current = (self.directories['/'], '')\n        for part in path.split('/')[1:]:\n            current += '/' + part\n            if current in self.directories:\n                parent = self.directories[current]\n                if parent is None:\n                    return None\n                continue\n            try:\n                child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)\n            except FileNotFoundError:\n                self.negative.append((parent, part))\n                self.directories[current] = None\n                return None\n            fd, info = self.keep(child, current)\n            self.require(stat.S_ISDIR(info.st_mode) and info.st_uid == 0)\n            self.require(not info.st_mode & 18 or (temporary and current == '/tmp' and info.st_mode & stat.S_ISVTX))\n            self.require(len(self.held) <= 48 and len(self.negative) <= 48)\n            self.directories[current] = fd\n            parent = fd\n        return parent\n\n    def absent(self, parent, name):\n        try:\n            os.stat(name, dir_fd=parent, follow_symlinks=False)\n        except FileNotFoundError:\n            self.negative.append((parent, name))\n            self.require(len(self.negative) <= 48)\n            return\n        raise GateUnavailable() from None\n\n    def executable(self, path):\n        values = [entry for entry in self.runtime.held if entry[0] == path]\n        self.require(len(values) == 1)\n        _, fd, identity = values[0]\n        info = os.fstat(fd)\n        self.require(D.identity(info) == identity and stat.S_ISREG(info.st_mode) and (info.st_uid == 0) and (not info.st_mode & 18) and info.st_mode & 73 and (info.st_nlink == 1) and (info.st_size == PROFILE['cli' if path == CLI else 'compose']['size']))\n\n    def public_account_file(self, name):\n        parent = self.directory('/etc')\n        self.require(parent is not None and name in ('passwd', 'nsswitch.conf'))\n        fd, info = self.keep(os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent), '/etc/' + name)\n        self.require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and (stat.S_IMODE(info.st_mode) in (384, 420)) and (info.st_nlink == 1) and (0 < info.st_size <= 65536))\n        output = bytearray()\n        while True:\n            self.require(len(output) <= 65536)\n            chunk = os.read(fd, min(4096, 65537 - len(output)))\n            if not chunk:\n                break\n            output.extend(chunk)\n        self.require(len(output) == info.st_size and D.identity(os.fstat(fd)) == D.identity(info))\n        self.require(b'\\x00' not in output and b'\\r' not in output)\n        return bytes(output).decode('utf-8')\n\n    def principal(self, expected):\n        self.require(self.initialized and (not self.closed) and (self.principal_name is None))\n        self.require(type(expected) is str and re.fullmatch('[a-z_][a-z0-9_-]{0,31}', expected) and (expected not in ('root', 'hookah-staging')))\n        self.require(os.getuid() == os.geteuid() != 0 and os.getgid() == os.getegid())\n        nss = self.public_account_file('nsswitch.conf')\n        passwd_rules = []\n        for line in nss.splitlines():\n            line = line.split('#', 1)[0].strip()\n            if line and line.split(':', 1)[0].strip() == 'passwd':\n                passwd_rules.append(line)\n        self.require(len(passwd_rules) == 1 and re.fullmatch('passwd\\\\s*:\\\\s*files\\\\s*', passwd_rules[0]))\n        passwd = self.public_account_file('passwd')\n        matches = []\n        for line in passwd.splitlines():\n            if not line or line.startswith('#'):\n                continue\n            fields = line.split(':')\n            self.require(len(fields) == 7 and re.fullmatch('[0-9]+', fields[2]) and re.fullmatch('[0-9]+', fields[3]))\n            if fields[0] == expected or int(fields[2]) == os.geteuid():\n                matches.append(fields)\n        self.require(len(matches) == 1 and matches[0][0] == expected and (int(matches[0][2]) == os.geteuid()) and (int(matches[0][3]) == os.getegid()))\n        self.principal_name = expected\n        self.recheck()\n        return expected\n\n    def command_environment(self, value):\n        self.require(self.bound and self.principal_name is not None and (type(value) is dict) and (value == self.environment))\n        self.recheck()\n        return value\n\n    def path_selection(self, value):\n        self.require(type(value) is str)\n        elements = value.split(':')\n        self.require(1 <= len(elements) <= 16 and all(elements))\n        selected = False\n        for element in elements:\n            directory = self.directory(element)\n            if directory is None:\n                continue\n            if element == '/usr/bin':\n                self.executable(CLI)\n                selected = True\n                break\n            self.absent(directory, 'docker')\n        self.require(selected)\n\n    def make_config(self):\n        parent = self.directory('/tmp', temporary=True)\n        self.require(parent is not None and os.geteuid() != 0)\n        name = '.clubs-bound-docker-' + os.urandom(16).hex()\n        created = False\n        try:\n            os.mkdir(name, 448, dir_fd=parent)\n            created = True\n            fd, info = self.keep(os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent), '<anonymous-config>')\n            self.config_fd = fd\n            self.require(info.st_uid == os.geteuid() and info.st_gid == os.getegid() and (stat.S_IMODE(info.st_mode) == 448))\n            os.rmdir(name, dir_fd=parent)\n            created = False\n            for entry in self.held:\n                if entry[1] in (parent, fd):\n                    entry[2] = self.identity(os.fstat(entry[1]))\n        finally:\n            if created:\n                try:\n                    os.rmdir(name, dir_fd=parent)\n                except OSError:\n                    raise P.Refused('cleanup') from None\n\n    def plugin_selection(self):\n        self.require(self.config_fd is not None and os.listdir(self.config_fd) == [])\n        info = os.fstat(self.config_fd)\n        self.require(info.st_uid == os.geteuid() and stat.S_IMODE(info.st_mode) == 448 and (info.st_nlink == 0))\n        for path in SYSTEM_PLUGIN_DIRS:\n            directory = self.directory(path)\n            if directory is None:\n                continue\n            if path == SYSTEM_PLUGIN_DIRS[-1]:\n                self.executable(PLUGIN)\n                return\n            self.absent(directory, 'docker-compose')\n        raise GateUnavailable() from None\n\n    def bind(self, environ):\n        self.require(self.initialized and (not self.closed) and (not self.bound))\n        try:\n            self.require(type(environ) is dict and len(environ) <= 256)\n            self.require(all((type(k) is str and P.NAME.fullmatch(k) and (type(v) is str) and ('\\x00' not in v) for k, v in environ.items())))\n            self.require(sum((len(k.encode()) + len(v.encode()) for k, v in environ.items())) <= 65536)\n            environment = docker_environment(environ)\n            self.require(not any((k.startswith(HOOK_PREFIXES) or k in HOOK_NAMES for k in environment)))\n            self.require(type(environment.get('HOME')) is str and environment['HOME'].startswith('/'))\n            for key, value in environment.items():\n                if key == 'LANG' or key.startswith('LC_'):\n                    accepted = ('C', 'POSIX', '')\n                    if key == 'LC_CTYPE':\n                        accepted += ('C.UTF-8', 'C.utf8')\n                    self.require(value in accepted)\n            self.path_selection(environment.get('PATH'))\n            self.make_config()\n            self.plugin_selection()\n            environment['DOCKER_CONFIG'] = '/proc/' + str(os.getpid()) + '/fd/' + str(self.config_fd)\n            environment['DOCKER_HOST'] = 'unix:///var/run/docker.sock'\n            self.environment = environment\n            self.bound = True\n            self.recheck()\n            return dict(environment)\n        except BaseException:\n            self.close()\n            raise\n\n    def recheck(self):\n        self.require(self.initialized and (not self.closed))\n        self.runtime.recheck()\n        self.aliases()\n        self.core_policy()\n        for path, fd, value in self.held:\n            self.require(value is not None and self.identity(os.fstat(fd)) == value)\n            if path != '<anonymous-config>':\n                self.require(self.identity(os.stat(path, follow_symlinks=False)) == value)\n        for parent, name in self.negative:\n            try:\n                os.stat(name, dir_fd=parent, follow_symlinks=False)\n            except FileNotFoundError:\n                continue\n            raise GateUnavailable() from None\n        if self.bound:\n            self.executable(CLI)\n            self.executable(PLUGIN)\n            self.require(os.listdir(self.config_fd) == [])\n\n    def close(self):\n        if self.closed:\n            return\n        self.closed = True\n        failed = False\n        for _, fd, _ in reversed(self.held):\n            try:\n                os.close(fd)\n            except BaseException:\n                failed = True\n        if self.runtime is not None:\n            try:\n                self.runtime.close()\n            except BaseException:\n                failed = True\n        if failed:\n            raise P.Refused('cleanup') from None\nGate = PipelineGate\n", '<clb91-pipeline>', 'exec'), CLB91_EXECUTOR)
CLB91_PROFILE = {'cli': {'path': '/usr/bin/docker', 'sha256': '18c2a16d6aa0331bbd78b93e0a57f344c62bda0be52c054d4ee80e11176b0c30', 'size': 44320674, 'version': '29.3.1'}, 'compose': {'path': '/usr/libexec/docker/cli-plugins/docker-compose', 'sha256': '2ac954c9d506b912a12477d72f01601dc72ec918c429c7bae48fd707bdf0f3e5', 'size': 31557130, 'version': '5.1.1'}, 'contract': 'clb91-cli29.3.1-compose5.1.1-config-v1', 'format': 1, 'runtime': {'aliases': {'/bin/bash': '/usr/bin/bash', '/bin/findmnt': '/usr/bin/findmnt', '/bin/sh': '/usr/bin/dash', '/lib64/ld-linux-x86-64.so.2': '/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2', '/usr/bin/python3': '/usr/bin/python3.12', '/usr/bin/ruby': '/usr/bin/ruby3.2', '/usr/lib/x86_64-linux-gnu/libtinfo.so.6': '/usr/lib/x86_64-linux-gnu/libtinfo.so.6.4'}, 'architecture': 'x86_64', 'compose': '5.1.1', 'format': 1, 'psych': '5.0.1', 'python': '3.12.3', 'ruby': '3.2.3', 'files': {}}}
CLB91_PROFILE['runtime']['files'] = {prefix + leaf: digest for prefix, entries in [('/etc/', [('ld.so.cache', '908d819959326e7985be9b61f5e4eb5cec503057830453e05879c6687b17c771')]), ('/usr/bin/', [('bash', 'bc5945feb8bd26203ebfafea5ce1878bb2e32cb8fb50ab7ae395cfb1e1aaaef1'), ('dash', '86d31f6fb799e91fa21bad341484564510ca287703a16e9e46c53338776f4f42'), ('docker', '18c2a16d6aa0331bbd78b93e0a57f344c62bda0be52c054d4ee80e11176b0c30'), ('findmnt', '08652d0a79159c9d5b73f9181d3436bf044466e8efffecd0feef5c4cbb4e7670'), ('python3.12', 'e50d468e8b0adfb05733f5b87b3cff34829c4a8c1aea50c865aa8bdfe4bb150f'), ('ruby3.2', 'ead53704d7da35c85a28db19070d34e7ecef9461ecf8ae5e9d51ae79125bb731')]), ('/usr/lib/locale/C.utf8/', [('LC_CTYPE', '59015993b1b671273461ef1167d7a9ad76dc090d521c91102e962217ed6b6dba')]), ('/usr/lib/python3.12/', [('_collections_abc.py', '90324ee3e1c4ca5319f7242d4b7c1e90eb8418b3f999d07c853aa488356282e6'), ('_compression.py', '3ad5d60627477a60939ee44fc1bb3a05dbe8fb52f0f75039b8f5d8f1a278b981'), ('_weakrefset.py', '91895a451d06e9f521a1171b31b9b19bc9740f35af00d4fa106338ab7167c9ac'), ('abc.py', 'e558702a95cdce3febd289da021715d2b92bc43995b8a1bc58dfa1c3d8010287'), ('ast.py', 'd16626aa5c054bcc45221e56f84e55051b046caf0f8be1fe4902ea71534fb735'), ('base64.py', '65c70b5b6361c6f7a71ecc2df0f55315474b669221dd8d81d7c1ae8d56748ada'), ('bisect.py', 'f1cf7b85fc36b5da249813fc5ab97d9464f8cc1bc817f7146206fa2713e35999'), ('bz2.py', '76ab3252924e71e859d7d90e8d3db13b6554975cfcac0fdadced4de7f8779330'), ('codecs.py', '7b7839e53a77961153240aecfe11edc8054d05b1dedd83894450dae21ec05785'), ('contextlib.py', '8b7a477f978a8532852fd81e241c78182516bc4975d672d580a5848a76e11eb6'), ('copyreg.py', 'c8eda41f05c6bf95a4da4726a530409d2485ae060b8d019b3a8034389a15d3e9'), ('enum.py', 'c8ead615c159598370295649eb296819ad4b40d50b200c4fec2d4269bf7af9ae'), ('fnmatch.py', '6683da36e47af523f3f41e18ad244d837783e19e98911cc0b7415dea81494ebc'), ('functools.py', 'cca971c456e1bec8b751aecdf41466f34dbf72321ee0840627280ba2ccf9d033'), ('genericpath.py', '2b0b35d2206778af4081cf81dd9b513876e72c35a55bec941402e1fa62b1f1aa'), ('hashlib.py', '6dbdebf270868b391080e21dc9687eddfaf321c965ad979f68d3f5c423c613ab'), ('hmac.py', '7facd1330e5487ed995eda5c8619df0d3e32f69cb619f97662372fb76325746e'), ('io.py', '7cec3cb8ac004058dd0a5af246e6d950fb59c7ddd0058fda48bcb3fcb98d8822'), ('ipaddress.py', 'cc9831e239bd81c1480a12733bded744002d93815fd0d083c77aea348c956e67'), ('keyword.py', '18c2be738c04ad20ad375f6a71db34b3823c7f40b0340f5294d0e89f3c9b093b'), ('locale.py', 'd1134b7212bad37b7b81339061dc37a19a194aa183ec2cb60412cf365993b151'), ('lzma.py', '58fb9d2fdc8a8af7b25e218f17ea3b51bdfa53bdf40f440ab33c605974ca5c2e'), ('ntpath.py', 'c4dd79bf103677daaad873952b89fc1f973425e7b250006cc35af4c164181da8'), ('operator.py', 'b2af20f67667203c1730e686cc5d0427becc94db4c97f1d3efe3ed2158473f6a'), ('os.py', '316d1b7307fd851bded3423c9d437e0a383c725d993f0fcff2e8b749fe560b62'), ('pathlib.py', 'b43e134bf7479759838b4ecec3d78e0e67fdfde2d671c5c2d7886a47da93885d'), ('platform.py', 'ed0defe8ff7c116710493ffd099b566d3de686ab1b431a3d5401056798e59341'), ('posixpath.py', 'c443d7b8afad1717e7060ce49b3e04423058de56cd4246ee6bcc1ac962eb5c2b'), ('random.py', '0693d4ded36916f5b07d6c395cc331dbf1011bb70e90daaa29eaa32490a09425'), ('reprlib.py', '8da31054076803065758311f54b18b8a616824941977d907dc3ee729228e9015'), ('secrets.py', '277000574358a6ecda4bb40e73332ae81a3bc1c8e1fa36f50e5c6a7d4d3f0f17'), ('selectors.py', '1eeb102373e18c96311203f30c516e785bd8642275aa0bd66e43a284c9692385'), ('shlex.py', 'f927227de5ba5b1b2bdd75e3d9c8cb72b602b3bba3cc8edbf8fb554de0dc1fd7'), ('shutil.py', '819e518cb7a539d09b2526138015541b34d2646afb9c2f6ae4ffd476d6a0fcf4'), ('signal.py', '0363c964c90ac0b3e515de5749205e6e6454051a1211058375d84d91eab6071a'), ('socket.py', 'b59366f7c35583a274c8031351c146d9c64c4e680adf45d99a3335536a20a6cc'), ('stat.py', '052af0327eae6941b69b05c088b3e748f79995635f80ac4cc7125eb333eb4c77'), ('struct.py', '9c231f9497caf513a22dee8f790b07f969b0e45854a0bdd6dd84b492e08c2856'), ('subprocess.py', 'baa9f9138d8d20df6284f67e7d2e790f847f65e2c5370de322d54cccd737f2d9'), ('tempfile.py', '8c8033ed1426ace79e07a6608887d2a1694d817d63d61b04c3d59339f24b4269'), ('threading.py', '8273b4cf5b6f274b4993f5cae08634dd272c6952af9867ff9aa13ed446f1549b'), ('types.py', '345474ef027a1273f353da9bdc1f7c18f65335e72e681bcc0376774cc51f2405'), ('warnings.py', '8eb1bb88d0beb82ebecfe7ee5cf54ed9b77b4a7acee1989e392a65de42017c49'), ('weakref.py', '56f8d313fb74019e53eb9287400702fbce788b7fe30e097b0b6e06296f3f080c'), ('zipimport.py', '4ac94d92219c2e1c0d67ad3fff3753ec3a3756af62a36a2f696f02cd12d518f0')]), ('/usr/lib/python3.12/__pycache__/', [('_compression.cpython-312.pyc', 'fc09d120e985361378c4a62fea5fbbf4abc73b4622e872bc1a78271383cad8e6'), ('_weakrefset.cpython-312.pyc', 'df29445e1dc662eb6d6cfa4432ad8908e452bd29e8c54687d7103bf599d94a36'), ('ast.cpython-312.pyc', '326bf9acecf3937bcbe587ea2846af5d911b6d9b28585dbcae7cc967e8336b82'), ('base64.cpython-312.pyc', '94397a6e320709cde9e93b00bd626fe99b67899e1cf2ab4d3b020187029ecd5e'), ('bisect.cpython-312.pyc', '3d9e757cb04022cd9af59093f41e1a97d135f5c5eb09c2933dee11c609572596'), ('bz2.cpython-312.pyc', 'fe78f3c58f7774cbb407262fe60062c69bb6f0963753117b154d6293216f7594'), ('contextlib.cpython-312.pyc', 'e29b80e8619204a18dd338fe80562b116d6ced5d42361f7255958c23a0bf08a9'), ('copyreg.cpython-312.pyc', '57d508d756385d7e5fd85b4cb31a7e5f92cace2a289789b18d5401999a639bf3'), ('enum.cpython-312.pyc', 'bc788b9ee5c315cce928b93f9893441babad4b516a989b8c4d009512d8e57b9b'), ('fnmatch.cpython-312.pyc', '9315c79ca0d436b91421752f16643eb381068c4ff7b85f6a513c646e03debdb7'), ('functools.cpython-312.pyc', '94518b56bf294cfe53c3aad9b0fffc00c9b4c552cccf324bd359e1c4997a82b2'), ('hashlib.cpython-312.pyc', '3dc801740aa60d230e0dc5763efb8e91641b1a65f6d6cd310fce63e41a583a13'), ('hmac.cpython-312.pyc', '0273925109de28686b8891e4a52f152bcb2f5f759d468ce453908e45cf021455'), ('ipaddress.cpython-312.pyc', '6fda67f5b738c392c16745f54f08738c886f497dfdb828d5d69fa4052d04fcd4'), ('keyword.cpython-312.pyc', 'a7198c5b71ffd719308fe066b89cc98bbbc48bc6574ab879a5866626b184572b'), ('locale.cpython-312.pyc', '702676dd32e0438e9f26c82d2fdf34fa0008e9891b57c50345c448dc12946135'), ('lzma.cpython-312.pyc', '3d87e452ecb6698231bd8e793169dcfe855464385d655d5752889f0be212ea77'), ('operator.cpython-312.pyc', '2d9b59f83698f6db890176d5b6a512a0fbcec615ab9beb25b40759711e706ea7'), ('pathlib.cpython-312.pyc', '36343243103f01defe27ace47b429b5f521b0099a548d6ec7f5ab6df7389f60a'), ('platform.cpython-312.pyc', 'ef152e74c5c1da54ae384c456ba2d71a603423892ec29ad91ffe7caf535bcf96'), ('random.cpython-312.pyc', '18ac3259296573a543457628a299a859daf463e0bb0e4a09806c95892d821b19'), ('reprlib.cpython-312.pyc', '58a389f9c7b10ec4d9a88283aa1e051f7ba1d8a6f5ca489cb7dee5d9adb2b6ff'), ('secrets.cpython-312.pyc', '08cd4dd20cb98e58ed935d9353928e74a80b17ea3412a9d6009431249cefbff6'), ('selectors.cpython-312.pyc', 'a1ad705b11f0540b41830144c712d84c416e35d84915fcc4d141aaca5d810a37'), ('shlex.cpython-312.pyc', 'd85e54f66fef4a91ebd89d6539bbc48f01ee4414a8dcd210df381621d9e92043'), ('shutil.cpython-312.pyc', 'ceb08a3f6bc41f55a48e1248a294ef62dae237b5045a3b9947c3d179753a7afc'), ('signal.cpython-312.pyc', '54808b67661ce4f592ce2789288276808a30992932ab1a3674340831522eabeb'), ('struct.cpython-312.pyc', 'f0cc87ea2a221b5316238b69404c430e5aa2ebe89dd68b7dc11474cce8158359'), ('subprocess.cpython-312.pyc', 'df3e4bcb2f74a25645e122c9e7ec654a2215b7d237ffb1405ce64654053253c9'), ('tempfile.cpython-312.pyc', '1f6720e5cac5cd6e2d2e9e2bb32d0c1ec47136c0f3fd3a67408633328b3c5197'), ('threading.cpython-312.pyc', '8e8f323ad7643cb4671f460b77dafca43c243ad545a40bb6759314b504b8dc3b'), ('types.cpython-312.pyc', 'b7f2eb13f227d3731278f2e33fe5a3e447cb988852a43f053d5447485e199cab'), ('warnings.cpython-312.pyc', '38f38ecbad459a9652351455d9e19bf138ccbfc15c89c6fa3f028b10d6bac2df'), ('weakref.cpython-312.pyc', 'd960e9d74c9e0c179d7f8ddd0e7d842d0b863ed47b1cd1f9ee880b052798b402')]), ('/usr/lib/python3.12/collections/', [('__init__.py', '0af967cd58036507b3d0fbc33b7a996c61dbb52a94b0b738c8bef12cd4cc7dd4'), ('abc.py', '9cb4208f99128a0489b6c8e6c61637617dd7d4250c59e065491957eda084dd10')]), ('/usr/lib/python3.12/collections/__pycache__/', [('__init__.cpython-312.pyc', 'efe104840c7b718e97185b155f875e330f6bc67687182e7322fae642d6f7f39e'), ('abc.cpython-312.pyc', 'c7e98f4ddd778e38762c32b98aebde9d74586d9c508a1b61cf6e950b93cc7928')]), ('/usr/lib/python3.12/encodings/', [('__init__.py', '78c4744d407690f321565488710b5aaf6486b5afa8d185637aa1e7633ab59cd8'), ('aliases.py', '6fdcc49ba23a0203ae6cf28e608f8e6297d7c4d77d52e651db3cb49b9564c6d2'), ('utf_8.py', 'ba0cac060269583523ca9506473a755203037c57d466a11aa89a30a5f6756f3d')]), ('/usr/lib/python3.12/encodings/__pycache__/', [('__init__.cpython-312.pyc', '18d8731b4d889615980443d2e9bdb10fb0a34381f83e4ed0c66538753fcab5e5'), ('aliases.cpython-312.pyc', '43acf0e938ff18d8a0c661a66176f7ee6fb92b148b8b5deabc629245eaaeff52'), ('utf_8.cpython-312.pyc', '7ee12571aca7bbdad4c746390eb9bf97207b602be09cb8646a4421957e71f08a')]), ('/usr/lib/python3.12/importlib/', [('_bootstrap_external.py', '97c6211750d63067712ad2cd74d50be33337f1fb1cd2c769022ced9b401edd92')]), ('/usr/lib/python3.12/json/', [('__init__.py', 'd5d41e2c29049515d295d81a6d40b4890fbec8d8482cfb401630f8ef2f77e4d5'), ('decoder.py', '9f02654649816145bc76f8c210a5fe3ba1de142d4d97a1c93105732e747c285b'), ('encoder.py', 'af7bd40a0d0d0a3e726a9b4b3a2a543019f6ab97a340d0162a9c29ca9da97869'), ('scanner.py', '8604d9d03786d0d509abb49e9f069337278ea988c244069ae8ca2c89acc2cb08')]), ('/usr/lib/python3.12/json/__pycache__/', [('__init__.cpython-312.pyc', '609186801a9f5bfcd98edc1f1889bcc09ea27c80bee5c05ba616a144a516386c'), ('decoder.cpython-312.pyc', '2a395b844d38720fa81e3bb9a2339216b0a73b1a953d77f60e268b54d2a1942b'), ('encoder.cpython-312.pyc', '624944282c63628b98740f780feb8c80313159d097b078397c7f48e0aaad7cef'), ('scanner.cpython-312.pyc', '3022d00d435a2050180413b9e5545f8a4bc18b167c1d204d188d86da3ad3f598')]), ('/usr/lib/python3.12/lib-dynload/', [('_bz2.cpython-312-x86_64-linux-gnu.so', 'eff8df23cdc54d38ce9ca600d07c21ccfd7f22bd52a0720e599e6828d3e2eb76'), ('_hashlib.cpython-312-x86_64-linux-gnu.so', '8dc6cda6dd9495b95a2bfb5b26e8cf06b72f36dc0213d958b9277a958287f0fe'), ('_json.cpython-312-x86_64-linux-gnu.so', 'ca8e35c58f7bc8e27258c4af43651c941413bc6318903621253485b0395758a3'), ('_lzma.cpython-312-x86_64-linux-gnu.so', 'cb298120e40f064dc047e638d911f8b3b0abf625bf1d6bd07902b46efd820fd7'), ('resource.cpython-312-x86_64-linux-gnu.so', 'c8cc35580b497bf128cf8bb0e25efc36573a4f21a1c6877a91737fcd52e24907')]), ('/usr/lib/python3.12/re/', [('__init__.py', '8ff3c37c63b917fcf8dc8d50993a502292a3dc159e41de4f4018c72a53d1c07b'), ('_casefix.py', '41572ac50cf96b04496e676d8a6708898bb8e752e06dad34ed4c50c5d8f1fe40'), ('_compiler.py', 'c05067f8bfa4c13cbbf1eedc4d5cafc9b621bcb6ebc5771ba0518a18095af15a'), ('_constants.py', 'fa4fdb200f238f9e7817b63892b0d69833d8165134801e775e10cc113348a375'), ('_parser.py', 'a51a85b37cf3f44ba7ff25754da5f31306e4ccfa6eb3c017f9d37bdf4e770840')]), ('/usr/lib/python3.12/re/__pycache__/', [('__init__.cpython-312.pyc', 'b232ef42dd5d80b09ab397af6404026058d8cc97cca5b365b3391d8d1ab7f403'), ('_casefix.cpython-312.pyc', '85b48befe0a5e98154845fb0fb3b756725b93396d228de31a08a3114ebd67d98'), ('_compiler.cpython-312.pyc', 'e05c7e067749d8d0337f675399d00f65ecf7370c5379ef6673969233df211fab'), ('_constants.cpython-312.pyc', '62c9814158634a6fc6c59a3d20b27c2555ca18fea2ba79e0ffe6df89ac5beff3'), ('_parser.cpython-312.pyc', '44a9d0f2b4cd55fcac2012c2c0fad2b5457fa0e56cd12b12276921ab85c05f59')]), ('/usr/lib/python3.12/urllib/', [('__init__.py', 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'), ('parse.py', 'a67b5694763137dbac085adbf0c22821d435ec8ca22d3bd3786d2c9f4ee748d5')]), ('/usr/lib/python3.12/urllib/__pycache__/', [('__init__.cpython-312.pyc', '26bd006e2ede1f41ad38e11ccf872f3dcb6176df7690fef59cc835337212e6a6'), ('parse.cpython-312.pyc', '3b52f22720797fca18fabd57a95d19a373cf9e151052a833894a7bff1f3842e7')]), ('/usr/lib/ruby/3.2.0/', [('forwardable.rb', 'be418c80d2c9a24931e1836e3c21f4aa8e235d098d1d3c7b893b39c8564c8dc3'), ('json.rb', 'a7ad5916dd8ba6343ffcb522ef6a95bf874d3b42d50710e00b017af2171cf64c'), ('ostruct.rb', 'ec8d9d6505a6c03fe8cee85eff5934a2d0feab7705d2ec5cf252165280fc0a64'), ('psych.rb', '7a310e88a37bd3dc3c205ec57f0c312cbb89c78f72654be1340d77cbf02b0a07')]), ('/usr/lib/ruby/3.2.0/forwardable/', [('impl.rb', '729ea83da5d77a626bcf464185a98bc48b0c0b7fcd80d93e91d3906b24c7ecf0')]), ('/usr/lib/ruby/3.2.0/json/', [('common.rb', '851433b677cc1bbd581f28ce3291e61b267b14956c00b6775e1cc011aef5368a'), ('ext.rb', '6759cf47337135c9954218820995d1f1337395906a1881264bba6c0cdb5d61cb'), ('generic_object.rb', '74244c827f6c26ac278dbfcd819fb0bde8572650a525de9b390ff5f672feb0f2'), ('version.rb', '7ac661296386f9e0c8e7ce2acfdb3d12abbd7070900f16595e319b7b8fc5cdf1')]), ('/usr/lib/ruby/3.2.0/psych/', [('class_loader.rb', '2d412885a944bb723496a01ef8a4b08a9af45e72f602ae426c9c95aff7626702'), ('coder.rb', '7ff649678ec23bc8e319d0094ff18ed5305acf953fe0a94ab4073e74f615f76b'), ('core_ext.rb', '21532f69b1707344e4451bccae4ddb21e608d5bccb88a1ac56ef5b62cda08ebd'), ('exception.rb', 'fbdb3fb7131fee55b0de3c8accfc3ec64e623cd6010cd9dfaac050f9a8282354'), ('handler.rb', '7b965ad569001b5f60f17fa02997f5948438a8c6339f3290701923e584d674f0'), ('nodes.rb', 'd010c2fedbbdf01d659ce9fd514d49a76fc24ae7811e3a3d8d319488c3e3e647'), ('omap.rb', '46b4f6a76bad47f1ec500a593e960208ac305059581ad5a550ee24405e1c5da2'), ('parser.rb', '2fe03afaea70edbb61332e01d53d326b72d9acda69cacb980f16226e94803a42'), ('scalar_scanner.rb', 'bf40983c97bd26c89730c4f8fbbaa7f01cf4bbae6e1582252c2a40e5c9e8f015'), ('set.rb', 'a68b59ff0ba2dc050c5d224e3cc35b62d2cc5c02ea23aaec825ef20864a54c99'), ('stream.rb', '41d5f3932308ad69f77eddaa2515bcdc71a03756f358c4776f75a4fabce3d4bc'), ('streaming.rb', '5e59508cbf646b61d72ac85daeab1f273e68055d62947321f1a491ce1c63ef12'), ('syntax_error.rb', 'ba9893a790d34ec2183ee63f2802c69f4217855bef363b7ba84f6c40bb83dd7e'), ('tree_builder.rb', '9552a428400b3b544d2c2eea67060a11e8cbf3490bfda5630b57b86ab2943006'), ('versions.rb', '0443b1b29ca10d64388f2b5498d5ab740db5257d6103f96752ebd1b7da5a2afe'), ('visitors.rb', 'cb8b0cf0275320afaa2f657502338702f0edf7372c10ff38725cd8a19ee06ab3')]), ('/usr/lib/ruby/3.2.0/psych/handlers/', [('document_stream.rb', '16053c6c1db36a09300410b9a5471e5642b8c7968337e852960603ab531fe555')]), ('/usr/lib/ruby/3.2.0/psych/json/', [('ruby_events.rb', '9b2f7cf5982e1cf62159e6245ad3eee83b67245a84d0f1cbb701f48da3930789'), ('stream.rb', '212ec3ddaa0e455f9ded6af80714dab8f4c72f78044df8fcd6bee9eabe4a3c2b'), ('tree_builder.rb', 'b4754d3e84ef607899305fc8153436e63a937d0631c1833f401e7cf75a098dd9'), ('yaml_events.rb', 'fca4f80d7eba13c4475014f3b584008c20d15e48323ccf21d4cc961b024d68ab')]), ('/usr/lib/ruby/3.2.0/psych/nodes/', [('alias.rb', 'd245a4c422b561833f58f2545d7765f96293f0fd792aff9830730133151a802d'), ('document.rb', '658473e658a1c5641780f1846a942fbf9f4e3013658f554cc6c6e48d5ec748d1'), ('mapping.rb', 'e6ec0a89fd9e457cbbd382bf47f4dfd89359296576e77a6a18c0c6555dc97186'), ('node.rb', '5b3397dbacc683500436609da4d3baf89eb4e0f45547098e285fe717182b979b'), ('scalar.rb', '3a09f664986f3863f7b700bd35292664515e6c56eb17d722ed44303dd95dd312'), ('sequence.rb', 'bddaca0894fc93670d5e50ed250a6ca119e78176ca96053654ddbbd49ce1fcfe'), ('stream.rb', '3cc408cf801d3dd16a44b9eff86661ea8d3ab592ec8505f084da7d6675fb40b2')]), ('/usr/lib/ruby/3.2.0/psych/visitors/', [('depth_first.rb', '2687c15cde420b1cb1e5214bc6286215159c97a0688cf975920cc2563faf838d'), ('emitter.rb', '1fa562fa5649ed57d5161c35e9a85ffc137422739d0ef8fce5428c4db1fb519b'), ('json_tree.rb', '22156c136dbd69ff05f7cdf1d4282232a501c1e67bdf011b72cc258a0fc7a3a5'), ('to_ruby.rb', '92c0e260ff1c6639f1893bcd49f1eafadd628ea1ef7f645ea07e7bb193c3ee9a'), ('visitor.rb', '22eb6b08e4ec1abb6e0495d7d51241f4fe6b5c4e87a3a49ea3ecc3ef01e90870'), ('yaml_tree.rb', 'c4f39d78c83c9b537b9fefb3022765b329f307565f5a62b066eb00fa4df398d8')]), ('/usr/lib/x86_64-linux-gnu/', [('ld-linux-x86-64.so.2', 'c20a2dc8917c755f02b94049356320fe1f62ac7d9f8994731f807d9df39302da'), ('libblkid.so.1.1.0', '9c3860ffab5422410ff9e073d3e82267f00bc68ff1248cbb191640ee2ff045bc'), ('libbz2.so.1.0.4', 'cc08c9f50a8009ffd6391e0116a100369b11ca9238fa52392c019e6645b122a1'), ('libc.so.6', '3a15d66867d83762c7f2f1e37359cb8f6c5743edb369c65285cb0b1c4f7498bf'), ('libcap.so.2.66', '6ac6abc86ac891c6e13486470e26f1d939f47fda9e6b5d5508a7f5ec881adc84'), ('libcrypt.so.1.1.0', '1502636e3be8232c9cf801dfb533694aea985d449904f3e1295db26d7b348946'), ('libcrypto.so.3', '6866ed711dab8927e1bf82bf1cd30e828bdfc34f3cc1e83b1c9eb01ce6c37d9a'), ('libexpat.so.1.9.1', '286682ecbc5e59a638963b1a4e6351e65eb32fcf4bdcb9cb7569b6a61fe06a8d'), ('libgmp.so.10.5.0', '0ccdfb6d6f5c039465f6d002cf7e4c072d48ac6a2cffc8dd6c748dec31592804'), ('liblzma.so.5.4.5', '696e868dd0700a19a6d65fc01608ec2d70d3cb91f65710e89180cd2e688f30cb'), ('libm.so.6', 'fce00b6f25f459cf4ae0b7fae4257909a1a8a86f9149e7c029a5d617baf1ccd0'), ('libmount.so.1.1.0', '7472255ae5799059b37ddd544788f5665dbd3e1cb78259f52180293039efc2a5'), ('libpcre2-8.so.0.11.2', 'e00576d71d81d3ba0cfa4903c835a44a8723aac96f72f79ff75200b4cff9071b'), ('libruby-3.2.so.3.2.3', 'c7cab7e35d136ad18c97040b12f15c64f820ba19dcccd42ed43ac9154e6f28c2'), ('libselinux.so.1', '0ec0843463f90e227b0e138e1f379774f50b5514bd7f5dbd4c8a87aca0aa6670'), ('libsmartcols.so.1.1.0', '0560fded774642441657d3cf3125ac62234545dbe30725ed97cc6a79ba45791b'), ('libtinfo.so.6.4', 'e6089d7bb95eee5b545f3770dd69095b0bca47d26701cad145af429561d16306'), ('libudev.so.1.7.8', '4298228175fa62a36af88b1afd4406cdd8b1bf166621f957d0dbbde45a698fe5'), ('libyaml-0.so.2.0.9', 'a518a0ec7ee915fbeabe80551b8c9573b34e37b9402cc3e3eeab0fe346ba6ef0'), ('libz.so.1.3', '86200da370f20476a2507e9097a789b5ef97269b4ca8d5e164ad82dab9d99892')]), ('/usr/lib/x86_64-linux-gnu/gconv/', [('gconv-modules.cache', '52c227df9d53248238602c1ddaccd2c8ddc4cc6a61aa45d7c425af590b8806a5')]), ('/usr/lib/x86_64-linux-gnu/ruby/3.2.0/', [('psych.so', '282900b1f9f495f9fac0cd6c8954773a53d60d8636335d132599d1c4cd4bc523'), ('stringio.so', 'a091ef5633ba31048efbdc80a7eef13cb702ed84ff9fab42e22cd0d553c15393')]), ('/usr/lib/x86_64-linux-gnu/ruby/3.2.0/enc/', [('encdb.so', '059c6cc3358b56ade7178635ba1a53a596498674cba7841b30e5c89c1aeb4d77')]), ('/usr/lib/x86_64-linux-gnu/ruby/3.2.0/enc/trans/', [('transdb.so', '9f9afac310ff81490b55280d2c72b4795eb83ad65726e41bbedbd5c77afc91e6')]), ('/usr/lib/x86_64-linux-gnu/ruby/3.2.0/json/ext/', [('generator.so', '00af1d33235b80654d29f70ec3a6c54ae46cf890248ea1bd836efb778f6adefc'), ('parser.so', 'a5132616d829bc49cc2db1f14928a31baaabb8981e4e6f6ae3758c07647fe504')]), ('/usr/libexec/docker/cli-plugins/', [('docker-compose', '2ac954c9d506b912a12477d72f01601dc72ec918c429c7bae48fd707bdf0f3e5')])] for leaf, digest in entries}
CLB91_CAPTURE_OBJECT = types.SimpleNamespace(**CLB91_CAPTURE)
CLB91_PLAN_OBJECT = types.SimpleNamespace(**CLB91_PLAN)
CLB91_SEMANTIC.update(D=CLB91_CAPTURE_OBJECT, P=CLB91_PLAN_OBJECT, S=None, MANIFEST=CLB91_PROFILE['runtime'], COMPOSE='/usr/libexec/docker/cli-plugins/docker-compose')
CLB91_EXECUTOR.update(D=CLB91_CAPTURE_OBJECT, E=types.SimpleNamespace(**CLB91_SEMANTIC), P=CLB91_PLAN_OBJECT, S=None, PROFILE=CLB91_PROFILE)
# END CLB91 FIXED EXECUTOR SOURCES


class BoundContext:
    def __init__(self, owner, environment, compose, revision, image, phase, control):
        # Ownership is initialized before any gate so a rejected constructor
        # still reaches the same mandatory finalizer.
        self.phase, self.fds, self.config_fds = phase, [], []
        self.config_leases = []
        check(getattr(self, 'runtime', None) is not None)
        self.active = self.completed = False
        check(not self.signaled)
        with inspect_guard('input'):
            check(owner == '33468965282-1' and revision == '44497dcd28139cef865c3f98ac3f2c4a5afac636')
            check(image == 'ghcr.io/koteev-m/clubs_bot/app-bot@sha256:ddf5486e02835855178cc3b30bd2f22899335131e6dc388def20feac328016fe')
            check(environment == 'stage' and phase in ('inspect', 'claim', 'resume-start', 'reconcile'))
            check(re.fullmatch(r'[0-9]{1,20}-[0-9]{1,20}', owner))
            check(re.fullmatch(r'[0-9a-f]{40}', revision))
            check(re.fullmatch(r'ghcr\.io/[a-z0-9._/-]+@sha256:[0-9a-f]{64}', image))
            check(re.fullmatch(r'/[A-Za-z0-9._/-]+', compose) and
                  all(part not in ('', '.', '..') for part in compose.split('/')[1:]))
            check(not compose.startswith(('/tmp/', '/private/tmp/', '/run/', '/var/tmp/', '/dev/shm/')))
        with inspect_guard('control'):
            check(set(control) == {'version', 'implementation', 'binding', 'authorization', 'token', 'principal', 'authority'})
            check(type(control['version']) is int and control['version'] == 2 and isinstance(control['implementation'], dict))
            impl = control['implementation']
            check(set(impl) == {'revision', 'blob', 'sha256', 'size', 'path', 'mode', 'type'})
            check(impl['path'] == 'scripts/deploy/remote-compose-release.sh' and impl['mode'] == '100644' and impl['type'] == 'blob')
            check(re.fullmatch('[0-9a-f]{40}', impl['revision']) and re.fullmatch('[0-9a-f]{40}', impl['blob'])
                  and re.fullmatch('[0-9a-f]{64}', impl['sha256']) and type(impl['size']) is int and 100000 < impl['size'] < 262144)
        self.owner, self.environment, self.compose, self.revision, self.image, self.phase = owner, environment, compose, revision, image, phase
        self.control, self.fds, self.directories, self.edges, self.objects = control, [], {}, [], {}
        self.metadata = {}
        self.active, self.completed = False, False
        with inspect_guard('runtime'):
            self.runtime.recheck()
            self.docker_config_fd = self.runtime.config_fd
        with inspect_guard('principal', 'mismatch'):
            self.uid = os.geteuid()
            principal = self.runtime.principal(control['principal'])
            check(self.uid != 0 and principal not in ('root', 'hookah-staging') and control['principal'] == principal)
        self.incident = dict(owner=owner, environment=environment, revision=revision, image=image)
        self.path_hash = sha(compose.encode())
        with inspect_guard('control'):
            self.pin = control['binding']
            check(self.pin is None or isinstance(self.pin, dict))
            check(phase == 'inspect' or self.pin is not None)
        self.open_context()
        self.lock_context()
        self.check_edges()
        with inspect_guard('application_binding'):
            self.binding_record = self.record('parent', 'application.binding', 7)
            self.project = self.binding_record.get('compose_project')
            check(re.fullmatch('[A-Za-z0-9_.-]{1,128}', self.project or ''))
        self.backing = self.mount_identity()
        with inspect_guard('application_binding', 'mismatch'):
            check(self.binding_record == dict(binding_version='3', environment='stage', compose_path_hash=self.path_hash,
                  mount_fingerprint_version='2', mount_fingerprint=self.backing, compose_project=self.project, compose_service='app'))
        self.config_fds = []
        with inspect_guard('configuration_capture'):
            self.capture_configuration()
        with inspect_guard('binding_candidate', 'mismatch'):
            self.candidate = dict(version=1, incident=self.incident, implementation=impl,
                                  principal=dict(name=principal, uid=self.uid),
                                  compose=dict(path=compose, project=self.project, service='app'),
                                  backing=self.backing, objects=self.objects, configuration=self.config_hashes)
            if self.pin is not None:
                check(canonical(self.pin) == canonical(self.candidate))
            self.binding_digest = sha(canonical(self.candidate))
        self.check_evidence()

    def keep(self, fd):
        self.fds.append(fd)
        value = os.fstat(fd)
        self.metadata[fd] = (value.st_mode, value.st_uid)
        return fd

    def open_context(self):
        with inspect_guard('compose_chain'):
            parent = self.keep(os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW))
            self.objects['/'] = identity(parent)
            current = ''
            for part in self.compose.split('/')[1:]:
                current += '/' + part
                child = self.keep(os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent))
                info = os.fstat(child)
                check(info.st_uid in (0, self.uid) and not stat.S_IMODE(info.st_mode) & 0o022)
                self.edges.append((parent, part, child)); self.objects[current] = identity(child)
                parent = child
        with inspect_guard('compose_owner', 'mismatch'):
            check(os.fstat(parent).st_uid == self.uid)
        self.directories['compose'] = parent
        with inspect_guard('protocol_layout'):
            for key, parent_key, name in (
                ('parent', 'compose', '.clubs-bot-release-state'), ('root', 'parent', 'stage'),
                ('state', 'root', 'clubs-bot-schema-stage.lock'),
                ('results', 'root', 'clubs-bot-schema-stage.results'),
                ('ledger', 'root', 'clubs-bot-schema-stage.migration-ledgers')):
                base = self.directories[parent_key]
                child = self.keep(os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=base))
                info = os.fstat(child)
                check(info.st_uid == self.uid and stat.S_IMODE(info.st_mode) == 0o700)
                with inspect_guard('protocol_device', 'mismatch'):
                    check(info.st_dev == os.fstat(parent).st_dev)
                self.directories[key] = child; self.edges.append((base, name, child)); self.objects[key] = identity(child)
        with inspect_guard('lock_files'):
            for key, parent_key, name in (('application_lock', 'parent', 'application.lock'), ('operation_lock', 'results', 'operation.lock')):
                base = self.directories[parent_key]
                child = self.open_file(parent_key, name, os.O_RDWR)
                self.edges.append((base, name, child)); self.objects[key] = identity(child)
                self.directories[key] = child
        with inspect_guard('binding_candidate', 'mismatch'):
            if self.pin is not None:
                check(isinstance(self.pin.get('objects'), dict) and
                      all(self.pin['objects'].get(k) == v for k, v in self.objects.items()))

    @inspect_guarded('lock_shared')
    def lock_context(self):
        mode = fcntl.LOCK_SH if self.phase in ('inspect', 'reconcile') else fcntl.LOCK_EX
        for key in ('application_lock', 'operation_lock'):
            fcntl.flock(self.directories[key], mode | fcntl.LOCK_NB)

    @inspect_guarded('context_edges', 'mismatch')
    def check_edges(self):
        # Observed rename is rejected; safety does not depend on advisory locks
        # preventing rename. All subsequent accesses use these retained objects.
        for fd, expected in getattr(self, 'config_leases', ()):
            check(CLB91_CAPTURE['identity'](os.fstat(fd)) == expected)
        for parent, name, child in self.edges:
            value = os.stat(name, dir_fd=parent, follow_symlinks=False)
            held = os.fstat(child)
            check([value.st_dev, value.st_ino] == identity(child) and not stat.S_ISLNK(value.st_mode))
            check((held.st_mode, held.st_uid) == self.metadata[child])
            check(stat.S_ISDIR(held.st_mode) or held.st_nlink == 1)

    def open_file(self, directory, name, flags=os.O_RDONLY):
        check(re.fullmatch('[A-Za-z0-9_.-]+', name) and name not in ('.', '..'))
        fd = self.keep(os.open(name, flags | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=self.directories[directory]))
        info = os.fstat(fd)
        check(stat.S_ISREG(info.st_mode) and info.st_uid == self.uid and info.st_nlink == 1
              and stat.S_IMODE(info.st_mode) == 0o600 and info.st_dev == os.fstat(self.directories[directory]).st_dev)
        return fd

    def read(self, directory, name, limit=4096):
        fd = self.open_file(directory, name)
        try:
            return read_all(fd, limit)
        finally:
            self.fds.remove(fd); os.close(fd)

    def record(self, directory, name, count):
        data = self.read(directory, name, 2048).decode('ascii')
        check(len(data.splitlines()) == count and not data.endswith('\n'))
        pairs = [line.split('=', 1) for line in data.split('\n')]
        check(all(len(pair) == 2 and pair[0] and pair[1] for pair in pairs))
        return unique(pairs)

    def value(self, key):
        check(key in self.state_keys)
        data = self.read('state', key)
        check(data and b'\n' not in data and all(32 <= b <= 126 for b in data))
        return data.decode('ascii')

    state_keys = set('owner expected_revision image_digest compose_path_hash checkpoint prior_override_exists prior_override_sha256 old_app_digest old_app_revision old_container_hash old_image_id_hash old_started_at_hash old_restart_count compose_project compose_service candidate_override_sha256 migration_image_digest migration_image_id'.split())

    def mount_identity(self):
        fingerprints = set()
        # findmnt follows a reference to the held descriptor, not the public path.
        for key in ('compose', 'parent', 'root', 'state', 'results', 'ledger', 'application_lock', 'operation_lock'):
            with inspect_guard('mount_query', 'command'):
                fd = self.directories[key]
                result = self.private_call(['/usr/bin/findmnt', '--noheadings', '--pairs', '--output', 'FSTYPE,SOURCE,FSROOT,TARGET',
                                            '--target', fdpath(fd)], limit=4096, timeout=5)
                check(result.code == 0 and len(result.output) <= 4096)
            with inspect_guard('mount_identity'):
                line = result.output.decode('ascii').strip()
                match = re.fullmatch(r'FSTYPE="([^"\s]+)" SOURCE="([^"\s]+)" FSROOT="([^"\s]+)" TARGET="([^"\s]+)"', line)
                check(match)
                values = []
                for value in match.groups():
                    check(not re.search(r'\\(?!x[0-9a-fA-F]{2})', value))
                    value = re.sub(r'\\x([0-9a-fA-F]{2})', lambda m: chr(int(m[1], 16)), value)
                    check('\x00' not in value); values.append(value)
                check(values[0] in ('ext2', 'ext3', 'ext4', 'xfs', 'btrfs', 'zfs', 'f2fs'))
                check(values[2].startswith('/') and values[3].startswith('/'))
                encoded = 'clubs-bot-mount-fingerprint-version=2\n' + '\n'.join(
                    key + '_SHA256=' + sha(value.encode()) for key, value in zip(('FSTYPE', 'SOURCE', 'FSROOT', 'TARGET'), values))
                fingerprints.add('mount-v2:' + sha(encoded.encode()))
        with inspect_guard('mount_identity', 'mismatch'):
            check(len(fingerprints) == 1)
            return fingerprints.pop()

    @inspect_guarded('configuration_capture')
    def capture(self, data):
        if hasattr(os, 'memfd_create'):
            # Own every acquired FD before any fallible private operation.
            fd = self.keep(os.memfd_create('clubs-bound-capture', os.MFD_CLOEXEC | os.MFD_ALLOW_SEALING))
            os.fchmod(fd, 0o600)
            stream = os.fdopen(fd, 'rb+')
            self.config_fds.append(stream)
            self.fds.remove(fd)  # Ownership has moved to the registered stream.
        else:
            stream = tempfile.TemporaryFile(dir=os.path.realpath('/tmp'))
            self.config_fds.append(stream)
            fd = stream.fileno()
        write_all(fd, data)
        if hasattr(os, 'memfd_create'):
            seals = fcntl.F_SEAL_WRITE | fcntl.F_SEAL_GROW | fcntl.F_SEAL_SHRINK | fcntl.F_SEAL_SEAL
            fcntl.fcntl(fd, fcntl.F_ADD_SEALS, seals)
            check(fcntl.fcntl(fd, fcntl.F_GET_SEALS) == seals)
        info = os.fstat(fd)
        check(stat.S_ISREG(info.st_mode) and info.st_uid == self.uid and info.st_nlink == 0 and stat.S_IMODE(info.st_mode) == 0o600)
        stream.seek(0)
        return fdpath(fd)

    def captured_file(self, directory, name, *, mode=(0o600,), required=True, limit=65536):
        # The original object remains leased. Only metadata is queried through
        # its pathname afterwards; normalizers receive anonymous sealed copies.
        base = self.directories[directory]
        try:
            fd = self.keep(os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=base))
        except FileNotFoundError:
            if required: raise
            return b''
        info = os.fstat(fd)
        with inspect_guard('compose_file_type'):
            check(stat.S_ISREG(info.st_mode))
        with inspect_guard('compose_file_owner'):
            check(info.st_uid == self.uid)
        with inspect_guard('compose_file_nlink'):
            check(info.st_nlink == 1)
        with inspect_guard('compose_file_mode'):
            check(stat.S_IMODE(info.st_mode) in mode)
        with inspect_guard('compose_file_device'):
            check(info.st_dev == os.fstat(base).st_dev)
        with inspect_guard('compose_file_size'):
            check(0 <= info.st_size <= limit)
        expected = CLB91_CAPTURE['identity'](info)
        self.edges.append((base, name, fd))
        self.config_leases.append((fd, expected))
        self.objects['configuration/' + name] = identity(fd)
        # Keep I/O attribution in the caller's original compose_file/dotenv
        # guard; only the bounded-size predicate has its own subguard.
        data = bytearray()
        while True:
            chunk = os.read(fd, min(65536, limit + 1 - len(data)))
            if not chunk:
                data = bytes(data)
                break
            data.extend(chunk)
            with inspect_guard('compose_file_size'):
                check(len(data) <= limit)
        check(len(data) == info.st_size and CLB91_CAPTURE['identity'](os.fstat(fd)) == expected)
        self.check_edges()
        return data

    def capture_configuration(self):
        with inspect_guard('compose_file'):
            main = self.captured_file('compose', 'docker-compose.yml', mode=(0o600, 0o644))
        # Retain the original lexical exclusions. AST confirmation below is the
        # sole exception for env_file, never historical diagnostic enums.
        with inspect_guard('compose_subset'):
            has_env_file = False
            for line in main.decode('utf-8').splitlines():
                stripped = line.strip()
                if not stripped or stripped.startswith('#'):
                    continue
                check('\t' not in line and not stripped.startswith(('---', '...', '%', '!', '&', '*', '{', '[')))
                if stripped.startswith('- '):
                    check(not stripped[2:].startswith(('!', '&', '*', '{')))
                    continue
                match = re.match(r'([A-Za-z0-9_.-]+):(?:\s+(.*))?$', stripped)
                check(match)
                key, value = match[1], match[2] or ''
                has_env_file |= key == 'env_file'
                check(key not in ('include', 'extends', 'label_file', 'build', 'configs', 'secrets', 'develop', 'provider', 'models')
                      and not value.startswith(('!', '&', '*', '|', '>', '{')))
                if not line.startswith(' '):
                    check(key in ('services', 'volumes', 'version', 'name', 'networks'))
            # A small AST tool, not a generic YAML evaluator. For legacy inputs
            # without env_file the original subset remains unchanged.
            outline = self.outline(main) if has_env_file else None
        with inspect_guard('override', 'mismatch'):
            override = self.captured_file('compose', 'docker-compose.override.yml', limit=4096)
            release = self.captured_file('state', 'docker-compose.release.yml', limit=4096)
            expected = f'# clubs-bot-managed-quiesced-release\n# revision: {self.revision}\nservices:\n  app:\n    image: {self.image}\n'.encode()
            check(override == expected and release == expected)
        with inspect_guard('dotenv'):
            dotenv = self.captured_file('compose', '.env', required=has_env_file)
            for line in dotenv.decode('utf-8').splitlines():
                line = line.strip()
                if not line or line.startswith('#'):
                    continue
                match = re.fullmatch(r'(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=.*', line)
                check(match and not match[1].startswith(('COMPOSE_', 'DOCKER_')))
        self.config_hashes = dict(main=sha(main), override=sha(override), release=sha(release), dotenv=sha(dotenv))
        self.env_capture = self.capture(dotenv)
        self.override_capture = self.capture(override)
        if has_env_file:
            check(sys.platform == 'linux' and hasattr(os, 'memfd_create'))
            with inspect_guard('semantic_equivalence'):
                plan = CLB91_PLAN['helper_prepare'](main, dotenv_reference=self.env_capture,
                    effective_environment=self.docker_environment, outline_parser=lambda _script, _base: canonical(outline),
                    normalize=self.normalize_input, candidate_check=self.candidate_subset)
                # Private proposal is retained only for computation/review. It is
                # never written/applied to canonical stage Compose files.
                self.private_plan = plan
                model = plan.model
        else:
            self.main_capture = self.capture(main)
            with inspect_guard('compose_command', 'command'):
                self.normalized = self.compose_call(['config', '--format', 'json'], normalize=True)
                check(self.normalized[0] == 0)
            with inspect_guard('compose_model'):
                model = CLB91_PLAN['decode_model'](self.normalized[1])
        with inspect_guard('compose_model'):
            app = model.get('services', {}).get('app', {})
            check(app.get('image') == self.image and not any(k in app for k in ('build', 'env_file', 'configs', 'secrets', 'label_file')))
            check(all(isinstance(v, dict) and v.get('type') == 'volume' for v in app.get('volumes', [])))
            self.config_hashes['resolved'] = sha(canonical(model))
            self.resolved_capture = self.capture(canonical(model))
        self.check_edges()

    def outline(self, main):
        script = CLB91_PLAN['LINUX_RUBY_LOAD'] + CLB91_PLAN['HELPER_OUTLINE']
        raw = self.private_call(['/usr/bin/ruby', '--disable-gems', '-e', script], main, limit=65536)
        check(raw.code == 0 and raw.failure is None)
        return json.loads(raw.output, object_pairs_hook=unique)

    def candidate_subset(self, candidate):
        # Reuse the same accepted AST without env_file via the pure planner's
        # checked transformation; no new permissive parser or lexical fallback.
        check(type(candidate) is bytes and len(candidate) <= 65536)
        check(not re.search(rb'(?m)^ *env_file:', candidate))
        return True

    def normalize_input(self, data, with_override, environment):
        check(environment is self.docker_environment)
        path = self.capture(data)
        options = ['-f', path]
        if with_override:
            options += ['-f', self.override_capture]
        result = self.private_call(self.compose_argv(options) + ['config', '--format', 'json'])
        check(result.failure is None and result.code == 0)
        return CLB91_PLAN['decode_model'](result.output)

    def compose_argv(self, files):
        return ['/usr/bin/docker', 'compose', '--project-name', self.project,
                '--project-directory', self.compose, '--env-file', self.env_capture, *files]

    def private_call(self, argv, payload=b'', limit=262144, timeout=90):
        self.check_edges()
        self.runtime.command_environment(self.docker_environment)
        for stream in self.config_fds:
            stream.seek(0)
        fds = tuple(stream.fileno() for stream in self.config_fds) + (self.directories['compose'], self.docker_config_fd)
        result = CLB91_CAPTURE['capture_result'](argv, payload, timeout=timeout, limit=limit,
            env=self.docker_environment, pass_fds=fds, cwd_fd=self.directories['compose'])
        check(result.failure is None)
        self.runtime.recheck()
        self.check_edges()
        self.check_interruption()
        return result

    @inspect_guarded('compose_command', 'command')
    def compose_call(self, args, normalize=False):
        files = ['-f', self.main_capture, '-f', self.override_capture] if normalize else ['-f', self.resolved_capture]
        if args and args[0] == 'up': self.check_authority()
        result = self.private_call(self.compose_argv(files) + args)
        return result.code, result.output

    def check_evidence(self):
        self.check_edges()
        with inspect_guard('retained_layout'):
            check('active-candidate.anchor' not in os.listdir(self.directories['parent']))
            root_entries = set(os.listdir(self.directories['root']))
            check(root_entries <= {'clubs-bot-schema-stage.lock', 'clubs-bot-schema-stage.results',
                                    'clubs-bot-schema-stage.migration-ledgers', '.clb82-resume-start-33468965282-1.consumed'})
            check(set(os.listdir(self.directories['state'])) == self.state_keys | {'docker-compose.release.yml', 'prior-override'})
        with inspect_guard('retained_identity', 'mismatch'):
            for key, value in dict(owner=self.owner, expected_revision=self.revision, image_digest=self.image,
                                   compose_path_hash=self.path_hash, compose_project=self.project, compose_service='app',
                                   migration_image_digest=self.image).items():
                check(self.value(key) == value)
        with inspect_guard('retained_checkpoint'):
            check(self.value('checkpoint') in ('migration_completed', 'candidate_start_begun', 'candidate_healthy'))
            check(re.fullmatch(r'sha256:[0-9a-f]{64}', self.value('migration_image_id')))
        with inspect_guard('prior_override', 'mismatch'):
            prior = self.read('state', 'prior-override', 1024)
            check(sha(prior) == self.value('prior_override_sha256'))
            if self.value('prior_override_exists') == 'no':
                check(prior == b'absent')
            else:
                check(self.value('prior_override_exists') == 'yes')
                old_image, old_revision = self.value('old_app_digest'), self.value('old_app_revision')
                check(re.fullmatch(r'ghcr\.io/[a-z0-9._/-]+@sha256:[0-9a-f]{64}', old_image)
                      and re.fullmatch('[0-9a-f]{40}', old_revision))
                prefix = '# clubs-bot-managed-quiesced-release\n'
                check(prior in ((prefix + f'# revision: {old_revision}\nservices:\n  app:\n    image: {old_image}\n').encode(),
                                (prefix + f'services:\n  app:\n    image: {old_image}\n').encode()))
            check(self.value('candidate_override_sha256') == self.config_hashes['override'])
        with inspect_guard('migration_records'):
            common = dict(owner=self.owner, environment='stage', expected_revision=self.revision, image_digest=self.image,
                          compose_path_hash=self.path_hash, operation='migration',
                          invocation_fingerprint=sha(f'v1|stage|{self.owner}|{self.revision}|{self.image}|{self.path_hash}'.encode()))
            ledger = self.record('ledger', self.owner + '.ledger', 13)
            outcome = self.record('ledger', self.owner + '.outcome', 12)
            check(set(os.listdir(self.directories['ledger'])) == {self.owner + '.ledger', self.owner + '.outcome'})
            for value, required, epochs in (
                (ledger, dict(ledger_version='1', state='completed', result='completed', completion_checkpoint='migration_completed'), ('created_epoch', 'completed_epoch')),
                (outcome, dict(outcome_version='1', state='succeeded', bounded_result='migration_succeeded', completion_checkpoint='migration_process_succeeded'), ('recorded_epoch',))):
                check(set(value) == set(common) | set(required) | set(epochs))
                check(all(value.get(k) == v for k,v in {**common, **required}.items()))
                check(all(re.fullmatch('[0-9]{1,12}', value[k]) for k in epochs))
        with inspect_guard('result_record'):
            result = self.record('results', self.owner + '.result', 10)
            check(set(result) == set('result_version owner requested_operation checkpoint_before checkpoint_after result failure_category expected_revision image_digest compose_path_hash'.split()))
            check(all(result.get(k) == v for k,v in dict(result_version='1', owner=self.owner, expected_revision=self.revision,
                                                       image_digest=self.image, compose_path_hash=self.path_hash).items()))
            check(result['requested_operation'] in ('start', 'resume-start'))
            check(result['checkpoint_before'] in ('migration_completed', 'candidate_start_begun', 'candidate_healthy'))
            check(result['checkpoint_after'] in ('migration_completed', 'candidate_start_begun', 'candidate_healthy', 'unavailable'))
            check((result['result'], result['failure_category']) in (
                ('success', 'success'), ('remote_failure', 'app_identity_mismatch'), ('remote_failure', 'candidate_start_failed'),
                ('remote_failure', 'readiness_failed'), ('remote_failure', 'health_failed'), ('remote_failure', 'unexpected'),
                ('remote_failure', 'child_exit_255'), ('remote_failure', 'durability_failure'),
                ('incomplete_unknown', 'operation_in_progress'), ('incomplete_unknown', 'interrupted')))
            self.result = result
        self.check_edges()

    def replace(self, directory, name, data):
        # Durable writes never reopen public paths and never roll back evidence.
        # A failure after rename leaves a partial/unknown operation to reconcile.
        base = self.directories[directory]
        temporary = '.bound-' + os.urandom(16).hex()
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=base)
        try:
            write_all(fd, data); os.fsync(fd)
            os.rename(temporary, name, src_dir_fd=base, dst_dir_fd=base)
            os.fsync(base)
        finally:
            os.close(fd)
            try:
                os.unlink(temporary, dir_fd=base)
            except FileNotFoundError:
                pass

    def operation_result(self, result, category):
        record = dict(result_version='1', owner=self.owner, requested_operation='resume-start', checkpoint_before='migration_completed',
                      checkpoint_after=self.value('checkpoint'), result=result, failure_category=category,
                      expected_revision=self.revision, image_digest=self.image, compose_path_hash=self.path_hash)
        if category == 'operation_in_progress':
            record['checkpoint_after'] = 'unavailable'
        self.replace('results', self.owner + '.result', '\n'.join(k+'='+v for k,v in record.items()).encode())

    def claim_record(self):
        return dict(version=1, consumed=True, incident=self.incident, implementation=self.control['implementation'], binding_sha256=self.binding_digest,
                    authorization_sha256=sha(self.control['authorization'].encode()), token_sha256=sha(self.control['token'].encode()),
                    authority_sha256=sha(canonical(self.control['authority'])))

    def check_authority(self):
        check(self.pin is not None and re.fullmatch('[0-9a-f]{64}', self.control['token']))
        check(re.fullmatch('[0-9a-f]{64}', self.control['authorization']))
        authority = self.control['authority']
        check(isinstance(authority, dict) and set(authority) == {'reviewed_at', 'expires_at', 'execution', 'evidence', 'receiver'})
        reviewed, expires = authority['reviewed_at'], authority['expires_at']
        check(type(reviewed) is int and type(expires) is int and 0 <= reviewed <= int(time.time()) < expires
              and 0 < expires-reviewed <= 86400)
        target, evidence = authority['execution'], authority['evidence']
        check(isinstance(target, dict) and set(target) == {'action', 'run_number', 'attempt', 'source_revision'})
        check(target['action'] == 'resume-start' and target['attempt'] == '1'
              and re.fullmatch('[1-9][0-9]{0,19}', target['run_number'])
              and re.fullmatch('[0-9a-f]{40}', target['source_revision']))
        check(isinstance(evidence, dict) and set(evidence) == {'repository', 'workflow', 'run_id', 'attempt', 'source_revision',
              'artifact_id', 'ciphertext_sha256', 'run_number', 'created_at', 'expires_at'})
        check(evidence['repository'] == 'koteev-m/clubs_bot' and evidence['workflow'] == '.github/workflows/corrected-stage-release.yml'
              and evidence['source_revision'] == target['source_revision']
              and all(re.fullmatch('[1-9][0-9]{0,19}', evidence[k]) for k in ('run_id','attempt','artifact_id','run_number'))
              and int(target['run_number']) > int(evidence['run_number'])
              and re.fullmatch('[0-9a-f]{64}', evidence['ciphertext_sha256'])
              and re.fullmatch('[0-9A-F]{40}', authority['receiver']))
        check(type(evidence['created_at']) is int and type(evidence['expires_at']) is int
              and evidence['created_at'] <= reviewed and expires <= evidence['expires_at']
              and 0 < evidence['expires_at']-evidence['created_at'] <= 86400)

    def claim(self):
        self.check_authority(); self.ready()
        self.check_edges()
        self.check_authority()
        fd = os.open('.clb82-resume-start-33468965282-1.consumed', os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                     0o600, dir_fd=self.directories['root'])
        try:
            write_all(fd, canonical(self.claim_record())); os.fsync(fd); os.fsync(self.directories['root'])
            self.check_edges()
        finally:
            os.close(fd)
        return b'release-authorization:v=1 consumed=yes\n'

    def ready(self):
        self.check_evidence()
        check(self.value('checkpoint') == 'migration_completed' and self.result['requested_operation'] == 'start'
              and self.result['result'] == 'remote_failure' and self.result['failure_category'] == 'app_identity_mismatch'
              and self.result['checkpoint_after'] == 'migration_completed')
        code, data = self.worker('ready')
        check(code == 0 and data == b'')

    def resume(self):
        self.check_authority()
        record = json.loads(self.read('root', '.clb82-resume-start-33468965282-1.consumed', 8192), object_pairs_hook=unique)
        check(record == self.claim_record())
        self.ready()
        self.check_authority()
        self.active = True
        self.operation_result('incomplete_unknown', 'operation_in_progress')
        code, output = self.worker('resume')
        check(code == 0 and output == b'')
        self.check_edges()
        self.operation_result('success', 'success')
        self.completed = True
        return b'release-operation:v=1 result=success\n'

    def check_interruption(self):
        if self.signaled:
            raise InterruptedError

    @inspect_guarded('status_read')
    def status(self):
        self.check_interruption()
        self.check_evidence()
        with inspect_guard('status_classification'):
            code, app = self.worker('classify')
            check(code == 0 and app in (b'absent', b'candidate_running', b'ambiguous', b'replaced'))
        checkpoint = self.value('checkpoint')
        permit = False
        if checkpoint == 'migration_completed' and app == b'absent':
            code, data = self.worker('ready')
            permit = code == 0 and not data
        elif checkpoint == 'candidate_healthy' and app == b'candidate_running':
            code, data = self.worker('healthy'); permit = code == 0 and not data
        self.check_interruption()
        requested = 'resume-start' if self.phase == 'reconcile' else 'start'
        result = self.result['result'] if self.result['requested_operation'] == requested else 'unavailable'
        line = (f'release-status:v=1 status_available=yes owner_match=yes revision_match=yes digest_match=yes checkpoint={checkpoint} '
                f'operation_result={result} migration_evidence=present app_state={app.decode()} abort_permitted=no '
                f'resume_permitted={"yes" if permit else "no"} failure_category=none\n')
        self.check_edges()
        return line.encode()

    def rpc(self, args):
        command, *args = args
        if command == 'failure':
            category, code = args
            allowed = {'unexpected', 'state_conflict', 'identity_mismatch', 'override_invalid', 'app_identity_mismatch',
                       'migration_evidence_invalid', 'candidate_start_failed', 'readiness_failed', 'health_failed'}
            check(category in allowed and re.fullmatch('[0-9]{1,3}', code))
            self.worker_failure = 'child_exit_255' if code == '255' else category
            return 0, b''
        if command == 'value':
            return 0, self.value(*args).encode()
        if command == 'identity':
            self.check_evidence(); return 0, b''
        if command == 'transition':
            before, after = args
            check(self.phase == 'resume-start' and self.active)
            check((before, after) in (('migration_completed', 'candidate_start_begun'), ('candidate_start_begun', 'candidate_healthy')))
            self.check_evidence(); check(self.value('checkpoint') == before)
            self.check_edges()
            if after == 'candidate_start_begun': self.check_authority()
            self.replace('state', 'checkpoint', after.encode()); self.check_edges()
            return 0, b''
        if command == 'compose':
            if args and args[0] == 'up': self.check_authority()
            return self.compose_call(args)
        raise RuntimeError('invalid internal operation')

    @inspect_guarded('worker_capture')
    def worker(self, action):
        self.check_interruption()
        self.runtime.command_environment(self.docker_environment)
        self.worker_owned = True
        parent = child = process = None
        failure = None
        try:
            parent, child = socket.socketpair()
            worker_env = dict(self.docker_environment, CLB82_RPC_FD=str(child.fileno()))
            script = FUNCTIONS + '\n' + WORKER
            script += '\n' + {'ready': 'bound_ready', 'resume': 'start_and_probe_app "$compose_path" "$digest" "$expected_revision" yes',
                               'classify': 'classify_app_state "$compose_path" "$digest" "$expected_revision"',
                               'healthy': 'probe_candidate "$compose_path" "$digest" "$expected_revision"; probe_readiness_and_health "$compose_path"'}[action] + '\n'
            process = subprocess.Popen(['/bin/bash', '--noprofile', '--norc', '-s', '--', self.owner, self.compose, self.revision, self.image],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                pass_fds=(child.fileno(), self.docker_config_fd), env=worker_env, start_new_session=True)
            child.close()
            parent.setblocking(False)
            os.set_blocking(process.stdout.fileno(), False)
            os.set_blocking(process.stdin.fileno(), False)
            outgoing, incoming, pending = bytearray(), bytearray(), bytearray(script.encode())
            output = bytearray()
            deadline = time.monotonic() + 600
            with selectors.DefaultSelector() as poll:
                poll.register(parent, selectors.EVENT_READ)
                poll.register(process.stdout, selectors.EVENT_READ)
                poll.register(process.stdin, selectors.EVENT_WRITE)
                while poll.get_map():
                    self.check_interruption()
                    check(time.monotonic() < deadline)
                    for key, events in poll.select(.1):
                        if key.fileobj is process.stdin:
                            try:
                                count = os.write(process.stdin.fileno(), pending[:4096])
                            except BlockingIOError:
                                continue
                            check(count > 0); del pending[:count]
                            if not pending:
                                poll.unregister(process.stdin); process.stdin.close()
                        elif key.fileobj is parent:
                            if events & selectors.EVENT_WRITE:
                                try: count = parent.send(outgoing)
                                except BlockingIOError: count = 0
                                del outgoing[:count]
                                if not outgoing: poll.modify(parent, selectors.EVENT_READ)
                            if events & selectors.EVENT_READ:
                                try: data = parent.recv(8196)
                                except BlockingIOError: continue
                                if not data:
                                    check(not incoming and not outgoing)
                                    poll.unregister(parent); continue
                                incoming.extend(data); check(len(incoming) <= 8196)
                                if len(incoming) < 4: continue
                                with inspect_guard('worker_protocol', 'protocol'):
                                    size = struct.unpack('!I', incoming[:4])[0]; check(0 < size <= 8192)
                                    if len(incoming) < size + 4: continue
                                    check(len(incoming) == size + 4 and not outgoing)
                                    request = json.loads(incoming[4:]); incoming.clear()
                                    check(type(request) is list and request and all(type(x) is str for x in request))
                                    try: code, value = self.rpc(request)
                                    except Exception: code, value = 1, b''
                                    self.check_interruption()
                                    answer = canonical([code, base64.b64encode(value).decode()])
                                    check(len(answer) <= 524288)
                                    outgoing.extend(struct.pack('!I', len(answer)) + answer)
                                    poll.modify(parent, selectors.EVENT_READ | selectors.EVENT_WRITE)
                        else:
                            try: data = os.read(process.stdout.fileno(), 4096)
                            except BlockingIOError: continue
                            if not data: poll.unregister(process.stdout)
                            else: output.extend(data); check(len(output) <= 4096)
            # Preserve the unreaped leader until group cleanup; avoid PID reuse.
            while os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT) is None:
                self.check_interruption(); check(time.monotonic() < deadline); time.sleep(.01)
            self.runtime.recheck(); self.check_edges()
            result = (process.returncode, bytes(output))
        except BaseException as error:
            failure = error
        finally:
            if process is not None:
                try: os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError: pass
                except BaseException as error:
                    if failure is None: failure = error
                try:
                    code = process.wait(timeout=2)
                    if failure is None: result = (code, bytes(output))
                except BaseException as error:
                    if failure is None: failure = error
            streams = [parent, child]
            if process is not None: streams += [process.stdin, process.stdout]
            for stream in streams:
                if stream is not None:
                    try: stream.close()
                    except BaseException as error:
                        if failure is None: failure = error
            self.worker_owned = False
        if failure is not None: raise failure
        self.check_interruption()
        return result

    def close(self):
        if getattr(self, 'closed', False):
            return
        self.closed = True
        failure = None
        for stream in getattr(self, 'config_fds', []):
            try:
                stream.close()
            except BaseException as error:
                if failure is None: failure = error
        for fd in reversed(getattr(self, 'fds', [])):
            try:
                os.close(fd)
            except BaseException as error:
                if failure is None: failure = error
        runtime = getattr(self, 'runtime', None)
        if runtime is not None:
            try:
                runtime.close()
            except BaseException as error:
                if failure is None: failure = error
        if failure is not None:
            raise failure


FUNCTIONS = sys.stdin.read(65537)
check(len(FUNCTIONS) <= 65536 and sys.flags.isolated and sys.flags.no_site)
WORKER = r'''
set -euo pipefail
owner="$1"; compose_path="$2"; expected_revision="$3"; digest="$4"
operation_compose_path="$compose_path"; app_env=stage; failure_category=unexpected
# Fixed executors/builtins for the unchanged readiness loop. Never PATH tools.
docker() { /usr/bin/docker "$@"; }
seq() { [ "$*" = "1 60" ] || return 1; local i; for ((i=1;i<=60;i++)); do printf '%s\n' "$i"; done; }
sleep() { [ "$*" = "1" ] || return 1; /usr/bin/python3 -I -S -B -c 'import time;time.sleep(1)'; }
bound_rpc() {
  /usr/bin/python3 -I -S -B -c 'import os,socket,json,struct,sys,base64
s=socket.socket(fileno=os.dup(int(os.environ["CLB82_RPC_FD"])))
b=json.dumps(sys.argv[1:]).encode();s.sendall(struct.pack("!I",len(b))+b)
def receive(n):
 b=bytearray()
 while len(b)<n:
  c=s.recv(n-len(b))
  if not c: raise SystemExit(1)
  b.extend(c)
 return b
n=struct.unpack("!I",receive(4))[0]
if n>524288:raise SystemExit(1)
code,data=json.loads(receive(n));sys.stdout.buffer.write(base64.b64decode(data));sys.exit(code)' "$@"
}
trap 'bound_rpc failure "$failure_category" "$?" >/dev/null' EXIT
state_file_exists() { bound_rpc value "$1" >/dev/null; }
state_value() { bound_rpc value "$1"; }
require_identity() { bound_rpc identity; }
verify_candidate_override() { bound_rpc identity; }
current_completed_ledger_matches_state() { bound_rpc identity; }
write_checkpoint() { bound_rpc transition "$1" "$2"; }
verify_old_app_unchanged() { return 1; }
compose_command() {
  if [[ "${1:-}" == /* ]]; then shift; fi
  bound_rpc compose "$@"
}
compose_base_command() { shift; bound_rpc compose "$@"; }
bound_ready() {
  require_identity
  assert_app_absent "$compose_path" "$digest" "$expected_revision"
  [ "$(image_revision "$digest")" = "$expected_revision" ]
  [ "$(docker image inspect --format='{{.Id}}' "$digest")" = "$(state_value migration_image_id)" ]
  migration="clubs-bot-migrate-$owner"
  [ "$(inspect_container_value '{{.State.Running}}' "$migration")" = false ]
  [ "$(inspect_container_value '{{.State.ExitCode}}' "$migration")" = 0 ]
  [ "$(inspect_container_value '{{.Config.Image}}' "$migration")" = "$digest" ]
  [ "$(inspect_container_value '{{.Image}}' "$migration")" = "$(state_value migration_image_id)" ]
  [ "$(inspect_container_value '{{ index .Config.Labels "com.docker.compose.oneoff" }}' "$migration")" = True ]
  [ "$(inspect_container_value '{{ index .Config.Labels "com.docker.compose.project" }}' "$migration")" = "$(state_value compose_project)" ]
  [ "$(inspect_container_value '{{ index .Config.Labels "com.docker.compose.service" }}' "$migration")" = app ]
}
'''
context = None
try:
    context = BoundContext.__new__(BoundContext)
    context.phase = sys.argv[6]
    context.signaled = context.active = context.completed = False
    context.fds, context.config_fds, context.runtime = [], [], None
    inspect_owned = True  # Defer signals through acquisition/ownership transfer.
    inspect_finalized = False
    def interrupted(signum, frame):
        context.signaled = True
        cancellation = CLB91_CAPTURE['_capture_cancellation']
        if cancellation is not None:
            cancellation[0] = True
            return
        if not inspect_owned and not getattr(context, 'worker_owned', False):
            raise InterruptedError
    for number in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(number, interrupted)
    # Even private control v2 is unread until the source-owned Runtime gate.
    with inspect_guard('runtime'):
        context.runtime = CLB91_EXECUTOR['Gate'](lambda: context.signaled)
        context.runtime.initial()
        context.docker_environment = context.runtime.bind(dict(os.environ))
    with inspect_guard('control'):
        control = json.loads(read_all(4, 32768), object_pairs_hook=unique)
    BoundContext.__init__(context, *sys.argv[1:], control)
    context.check_interruption()
    inspect_owned = context.phase == 'inspect'
    if context.phase == 'inspect':
        failure = None
        try:
            output = context.status()
            context.check_interruption()
            with inspect_guard('inspect_output'):
                output += b'corrected-binding-candidate:v=1 ' + canonical(context.candidate) + b'\n'
        except BaseException as error:
            failure = error
        finally:
            try:
                with inspect_guard('finalize'):
                    context.close()
                    inspect_finalized = True
            except BaseException as error:
                if failure is None:
                    failure = error
        if failure is not None:
            raise failure
        # Finalization is complete. Restore immediate interruption before the
        # last recorded-cancellation check and before any success publication.
        inspect_owned = False
        context.check_interruption()
    elif context.phase == 'reconcile':
        output = context.status()
    elif context.phase == 'claim':
        output = context.claim()
    else:
        output = context.resume()
    if not inspect_finalized:
        with inspect_guard('finalize'):
            context.close()
            inspect_finalized = True
    context.check_interruption()
    with inspect_guard('inspect_output'):
        sys.stdout.buffer.write(output); sys.stdout.flush()
except BaseException as failure:
    if context is not None and context.active and not context.completed:
        try:
            context.operation_result('incomplete_unknown' if context.signaled else 'remote_failure',
                                     'interrupted' if context.signaled else getattr(context, 'worker_failure', 'unexpected'))
        except BaseException:
            pass
    # Refused/partially initialized scopes still finalize before fixed failure
    # publication. Never allow a cleanup traceback onto public stderr.
    if context is not None:
        try:
            context.close()
        except BaseException:
            pass  # The outcome is already FAIL; cleanup cannot promote it.
    diagnostic = inspect_diagnostic(failure) if INSPECT_MODE else ''
    print(diagnostic + 'corrected-start:v=1 result=blocked', flush=True)
    sys.exit(1)
finally:
    if context is not None and not locals().get('inspect_finalized', False):
        try:
            context.close()
        except BaseException:
            print('corrected-start:v=1 result=blocked', flush=True)
            sys.exit(1)
CLB82_BOUND_PYTHON
  declare -f sha256_text image_revision current_compose_container_id inspect_container_value \
    classify_app_state assert_app_absent probe_candidate probe_readiness_and_health start_and_probe_app |
    python3 -I -S -B -c "$bound_python" "$owner" "$app_env" "${4:-}" "${5:-}" "${6:-}" "${7:-}"
}

if [ "$mode" = "corrected-start" ]; then
  [ "$#" = 7 ] || exit 2
  bound_corrected_start "$@"
  exit $?
fi

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
