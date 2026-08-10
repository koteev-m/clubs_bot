#!/usr/bin/env bash
set -euo pipefail

mode="${1:-}"
owner="${2:-}"
app_env="${3:-}"

case "$app_env" in
  stage|prod) ;;
  *) echo "remote-release: invalid environment" >&2; exit 2 ;;
esac
if [[ ! "$owner" =~ ^[0-9]+-[0-9]+$ ]]; then
  echo "remote-release: invalid owner" >&2
  exit 2
fi

lock_dir="/tmp/clubs-bot-schema-${app_env}.lock"
managed_override_marker="# clubs-bot-managed-quiesced-release"
migration_container_name=""

state_value() {
  local name="$1"
  local value
  value="$(cat "$lock_dir/$name")"
  if [[ "$value" == *$'\n'* || -z "$value" ]]; then
    echo "remote-release: invalid state $name" >&2
    exit 1
  fi
  printf '%s' "$value"
}

require_owner() {
  if [ ! -d "$lock_dir" ] || [ "$(state_value owner)" != "$owner" ]; then
    echo "remote-release: maintenance lock is absent or belongs to another release" >&2
    exit 1
  fi
}

require_phase() {
  local expected_phase="$1"
  local actual_phase
  require_owner
  actual_phase="$(state_value phase)"
  if [ "$actual_phase" != "$expected_phase" ]; then
    echo "remote-release: expected phase $expected_phase, found $actual_phase" >&2
    exit 1
  fi
}

compose_command() {
  local compose_path override_file
  compose_path="$(state_value compose_path)"
  override_file="$(state_value active_override_file)"
  cd "$compose_path"
  docker compose -f docker-compose.yml -f "$override_file" "$@"
}

assert_app_absent() {
  local container_ids
  container_ids="$(compose_command ps -aq app)"
  if [ -n "$container_ids" ]; then
    echo "remote-release: app container still exists during maintenance" >&2
    compose_command ps app >&2 || true
    exit 1
  fi
}

validate_compose_contract() {
  local compose_path="$1"
  local persistent_override
  persistent_override="$compose_path/docker-compose.override.yml"
  cd "$compose_path"
  test -f docker-compose.yml
  docker compose -f docker-compose.yml config --services | grep -Fxq app
  if [ -e "$persistent_override" ] &&
    [ "$(head -n 1 "$persistent_override")" != "$managed_override_marker" ]; then
    echo "remote-release: refusing to replace an unmanaged docker-compose.override.yml" >&2
    exit 1
  fi
}

preflight_image() {
  local compose_path="$1"
  local image_repository="$2"
  local image_tag="$3"
  local expected_revision="$4"
  local ghcr_username="$5"
  local ghcr_token tag_reference revision digest digest_prefix digest_hash

  validate_compose_contract "$compose_path"
  IFS= read -r ghcr_token || [ -n "$ghcr_token" ]
  if [ -z "$ghcr_token" ]; then
    echo "remote-release: empty registry token" >&2
    exit 2
  fi
  printf '%s\n' "$ghcr_token" | docker login ghcr.io -u "$ghcr_username" --password-stdin >/dev/null
  unset ghcr_token

  tag_reference="${image_repository}:${image_tag}"
  revision=""
  for _ in $(seq 1 60); do
    if docker pull "$tag_reference" >/dev/null 2>&1; then
      revision="$(docker image inspect --format='{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$tag_reference")"
      if [ "$revision" = "$expected_revision" ]; then
        break
      fi
    fi
    sleep 5
  done
  if [ "$revision" != "$expected_revision" ]; then
    echo "remote-release: published image revision does not match release revision" >&2
    exit 1
  fi

  digest="$(docker image inspect --format='{{ index .RepoDigests 0 }}' "$tag_reference")"
  digest_prefix="${image_repository}@sha256:"
  digest_hash="${digest#"$digest_prefix"}"
  if [ "$digest_hash" = "$digest" ] || [[ ! "$digest_hash" =~ ^[0-9a-f]{64}$ ]]; then
    echo "remote-release: image has no verified repository digest" >&2
    exit 1
  fi
  docker pull "$digest" >/dev/null
  echo "remote-release: verified revision=$revision digest=$digest" >&2
  printf '%s' "$digest"
}

create_maintenance_state() {
  local compose_path="$1"
  local digest="$2"
  local expected_revision="$3"
  local release_override persistent_override revision

  validate_compose_contract "$compose_path"
  revision="$(docker image inspect --format='{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$digest")"
  if [ "$revision" != "$expected_revision" ]; then
    echo "remote-release: digest revision changed before quiesce" >&2
    exit 1
  fi
  if ! mkdir "$lock_dir" 2>/dev/null; then
    echo "remote-release: maintenance lock already exists; inspect it before manual removal" >&2
    exit 1
  fi
  chmod 700 "$lock_dir"
  release_override="$lock_dir/docker-compose.release.yml"
  persistent_override="$compose_path/docker-compose.override.yml"
  cat >"$release_override" <<YAML
services:
  app:
    image: $digest
YAML
  chmod 600 "$release_override"
  printf '%s' "$owner" >"$lock_dir/owner"
  printf '%s' "quiescing" >"$lock_dir/phase"
  printf '%s' "$compose_path" >"$lock_dir/compose_path"
  printf '%s' "$release_override" >"$lock_dir/active_override_file"
  printf '%s' "$persistent_override" >"$lock_dir/persistent_override_file"
  printf '%s' "$digest" >"$lock_dir/image_digest"
  printf '%s' "$expected_revision" >"$lock_dir/expected_revision"
}

stop_and_remove_app() {
  compose_command stop --timeout 60 app
  compose_command rm -f app
  assert_app_absent
}

promote_persistent_override() {
  local compose_path persistent_override digest temporary_override
  compose_path="$(state_value compose_path)"
  persistent_override="$(state_value persistent_override_file)"
  digest="$(state_value image_digest)"
  validate_compose_contract "$compose_path"
  temporary_override="${persistent_override}.tmp.${owner}"
  cat >"$temporary_override" <<YAML
$managed_override_marker
services:
  app:
    image: $digest
YAML
  chmod 600 "$temporary_override"
  mv "$temporary_override" "$persistent_override"
  printf '%s' "$persistent_override" >"$lock_dir/active_override_file"
  cd "$compose_path"
  docker compose config --images | grep -Fxq "$digest"
}

cleanup_migration_container() {
  local exit_status=$?
  if [ -n "$migration_container_name" ]; then
    docker rm -f "$migration_container_name" >/dev/null 2>&1 || true
  fi
  trap - EXIT
  exit "$exit_status"
}

emit_safe_migration_diagnostics() {
  local migration_log_file="$1"
  local migration_exit_code="$2"
  local line completed_applied="" failed_phase="" failed_category=""
  local state="initial" parse_failed=0
  local LC_ALL=C

  if [[ ! "$migration_exit_code" =~ ^(0|[1-9][0-9]*)$ ]]; then
    echo "remote-release: migration diagnostic protocol rejected invalid exit status; raw output suppressed" >&2
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
    echo "remote-release: migration diagnostic protocol rejected non-canonical bytes; raw output suppressed" >&2
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
    echo "remote-release: migration diagnostic protocol rejected non-canonical output; raw output suppressed" >&2
    return 1
  fi

  printf '%s\n' "migration-safe:v=1 event=started" >&2
  if [ "$migration_exit_code" = "0" ]; then
    printf 'migration-safe:v=1 event=completed applied=%s\n' "$completed_applied" >&2
    return 0
  fi

  printf 'migration-safe:v=1 event=failed phase=%s category=%s\n' \
    "$failed_phase" \
    "$failed_category" >&2
}

capture_and_forward_safe_migration_diagnostics() {
  local migration_container_id="$1"
  local migration_exit_code="$2"
  local migration_log_file="$lock_dir/migration-container.log"

  umask 077
  if ! docker logs "$migration_container_id" >"$migration_log_file" 2>&1; then
    echo "remote-release: migration logs unavailable; raw output suppressed" >&2
    return 1
  fi
  chmod 600 "$migration_log_file"
  emit_safe_migration_diagnostics "$migration_log_file" "$migration_exit_code"
  echo "remote-release: complete migration output is retained only in restricted remote maintenance state" >&2
}

migrate_verified_image() {
  local digest expected_revision revision expected_image_id migration_container_id
  local migration_reference migration_image_id migration_exit_code configured_digest_count

  require_phase "quiesced"
  assert_app_absent
  digest="$(state_value image_digest)"
  expected_revision="$(state_value expected_revision)"
  revision="$(docker image inspect --format='{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$digest")"
  if [ "$revision" != "$expected_revision" ]; then
    echo "remote-release: migration image revision does not match maintenance state" >&2
    exit 1
  fi
  expected_image_id="$(docker image inspect --format='{{.Id}}' "$digest")"
  configured_digest_count="$(compose_command config --images | grep -Fxc "$digest")"
  if [ "$configured_digest_count" != "1" ]; then
    echo "remote-release: Compose app image is not uniquely pinned to the verified digest" >&2
    exit 1
  fi

  migration_container_name="clubs-bot-migrate-${owner}"
  if docker inspect "$migration_container_name" >/dev/null 2>&1; then
    echo "remote-release: migration container already exists; inspect it before retry" >&2
    exit 1
  fi
  printf '%s' "migrating" >"$lock_dir/phase"
  trap cleanup_migration_container EXIT
  migration_container_id="$(
    compose_command run \
      --detach \
      --no-deps \
      --pull never \
      --name "$migration_container_name" \
      --entrypoint /opt/app/bin/app-bot-migrate \
      -e APP_ENV="$app_env" \
      -e FLYWAY_MODE=migrate-and-validate \
      -e QUIESCED_RELEASE_MIGRATION=required \
      app
  )"
  if [[ ! "$migration_container_id" =~ ^[0-9a-f]{64}$ ]]; then
    echo "remote-release: invalid migration container id" >&2
    exit 1
  fi

  migration_reference="$(docker inspect --format='{{.Config.Image}}' "$migration_container_id")"
  migration_image_id="$(docker inspect --format='{{.Image}}' "$migration_container_id")"
  if [ "$migration_reference" != "$digest" ] || [ "$migration_image_id" != "$expected_image_id" ]; then
    echo "remote-release: migration container is not the verified release image" >&2
    exit 1
  fi

  migration_exit_code="$(docker wait "$migration_container_id")"
  if [[ ! "$migration_exit_code" =~ ^[0-9]+$ ]]; then
    echo "remote-release: migration returned an invalid exit status" >&2
    exit 1
  fi
  capture_and_forward_safe_migration_diagnostics "$migration_container_id" "$migration_exit_code"
  if [ "$migration_exit_code" != "0" ]; then
    echo "remote-release: verified-image migration failed with exit=$migration_exit_code" >&2
    exit 1
  fi
  if [ "$(docker inspect --format='{{.Config.Image}}' "$migration_container_id")" != "$digest" ] ||
    [ "$(docker inspect --format='{{.Image}}' "$migration_container_id")" != "$expected_image_id" ]; then
    echo "remote-release: migration image identity changed during execution" >&2
    exit 1
  fi

  docker rm "$migration_container_id" >/dev/null
  migration_container_name=""
  trap - EXIT
  assert_app_absent
  printf '%s' "$digest" >"$lock_dir/migration_image_digest"
  printf '%s' "$expected_image_id" >"$lock_dir/migration_image_id"
  printf '%s' "migrated" >"$lock_dir/phase"
}

start_and_probe_app() {
  local digest migration_digest migration_image_id container_id running_reference running_image_id
  assert_app_absent
  printf '%s' "starting" >"$lock_dir/phase"
  digest="$(state_value image_digest)"
  migration_digest="$(state_value migration_image_digest)"
  migration_image_id="$(state_value migration_image_id)"
  if [ "$migration_digest" != "$digest" ]; then
    echo "remote-release: application digest differs from migration digest" >&2
    exit 1
  fi
  compose_command up -d --no-deps app
  container_id="$(compose_command ps -q app)"
  if [ -z "$container_id" ]; then
    echo "remote-release: new app container was not created" >&2
    exit 1
  fi
  running_reference="$(docker inspect --format='{{.Config.Image}}' "$container_id")"
  if [ "$running_reference" != "$digest" ]; then
    echo "remote-release: running app is not pinned to the verified digest" >&2
    exit 1
  fi
  running_image_id="$(docker inspect --format='{{.Image}}' "$container_id")"
  if [ "$running_image_id" != "$migration_image_id" ]; then
    echo "remote-release: running app image id differs from migration image id" >&2
    exit 1
  fi
  echo "remote-release: running digest=$running_reference image_id=$running_image_id" >&2

  for _ in $(seq 1 60); do
    if compose_command exec -T app curl -fsS http://127.0.0.1:8080/ready >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  if ! compose_command exec -T app curl -fsS http://127.0.0.1:8080/ready >/dev/null 2>&1; then
    echo "remote-release: readiness timeout" >&2
    compose_command logs --no-color --tail 200 app >&2 || true
    exit 1
  fi

  for _ in $(seq 1 60); do
    if compose_command exec -T app curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1; then
      printf '%s' "healthy" >"$lock_dir/phase"
      return 0
    fi
    sleep 1
  done
  echo "remote-release: health timeout" >&2
  compose_command logs --no-color --tail 200 app >&2 || true
  exit 1
}

case "$mode" in
  preflight)
    compose_path="${4:-}"
    image_repository="${5:-}"
    image_tag="${6:-}"
    expected_revision="${7:-}"
    ghcr_username="${8:-}"
    if [[ ! "$compose_path" =~ ^/[a-zA-Z0-9._/-]+$ ]] ||
      [[ ! "$image_repository" =~ ^ghcr\.io/[a-z0-9._/-]+$ ]] ||
      [[ ! "$image_tag" =~ ^[a-zA-Z0-9._-]+$ ]] ||
      [[ ! "$expected_revision" =~ ^[0-9a-fA-F]{40}$ ]] ||
      [[ ! "$ghcr_username" =~ ^[a-zA-Z0-9._-]+$ ]]; then
      echo "remote-release: invalid preflight input" >&2
      exit 2
    fi
    preflight_image \
      "$compose_path" \
      "$image_repository" \
      "$image_tag" \
      "$expected_revision" \
      "$ghcr_username"
    ;;
  quiesce)
    compose_path="${4:-}"
    digest="${5:-}"
    expected_revision="${6:-}"
    if [[ ! "$compose_path" =~ ^/[a-zA-Z0-9._/-]+$ ]] ||
      [[ ! "$digest" =~ ^ghcr\.io/[a-z0-9._/-]+@sha256:[0-9a-f]{64}$ ]] ||
      [[ ! "$expected_revision" =~ ^[0-9a-fA-F]{40}$ ]]; then
      echo "remote-release: invalid quiesce input" >&2
      exit 2
    fi
    create_maintenance_state "$compose_path" "$digest" "$expected_revision"
    promote_persistent_override
    stop_and_remove_app
    printf '%s' "quiesced" >"$lock_dir/phase"
    ;;
  assert-absent)
    require_owner
    assert_app_absent
    ;;
  migrate)
    migrate_verified_image
    ;;
  start)
    require_phase "migrated"
    start_and_probe_app
    ;;
  cleanup)
    require_phase "healthy"
    persistent_override="$(state_value persistent_override_file)"
    if [ "$(head -n 1 "$persistent_override")" != "$managed_override_marker" ]; then
      echo "remote-release: durable verified-digest override is missing" >&2
      exit 1
    fi
    rm -f "$lock_dir/docker-compose.release.yml"
    rm -f \
      "$lock_dir/owner" \
      "$lock_dir/phase" \
      "$lock_dir/compose_path" \
      "$lock_dir/active_override_file" \
      "$lock_dir/persistent_override_file" \
      "$lock_dir/image_digest" \
      "$lock_dir/expected_revision" \
      "$lock_dir/migration_image_digest" \
      "$lock_dir/migration_image_id" \
      "$lock_dir/migration-container.log"
    rmdir "$lock_dir"
    ;;
  *)
    echo "remote-release: expected preflight, quiesce, assert-absent, migrate, start or cleanup" >&2
    exit 2
    ;;
esac
