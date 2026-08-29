#!/usr/bin/env bash
set -euo pipefail

repository_root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
deploy_workflow="$repository_root/.github/workflows/deploy-ssh.yml"
migrate_workflow="$repository_root/.github/workflows/db-migrate.yml"
runner_script="$repository_root/scripts/deploy/quiesced-release.sh"
remote_script="$repository_root/scripts/deploy/remote-compose-release.sh"
state_test="$repository_root/scripts/tests/test_quiesced_release_state.py"
dockerfile="$repository_root/Dockerfile"
compose_file="$repository_root/docker-compose.yml"
app_build="$repository_root/app-bot/build.gradle.kts"
migration_main="$repository_root/app-bot/src/main/kotlin/com/example/bot/tools/QuiescedMigrateMain.kt"
migration_test="$repository_root/app-bot/src/test/kotlin/com/example/bot/tools/QuiescedMigrateMainTest.kt"
migration_log_config="$repository_root/app-bot/src/main/resources/quiesced-migration-logback.xml"
migration_boundary="$repository_root/app-bot/src/main/dist/bin/app-bot-migrate"
flyway_config="$repository_root/core-data/src/main/kotlin/com/example/bot/data/db/DbConfig.kt"

fail() {
  echo "quiesced-deployment-contract: $1" >&2
  exit 1
}

for required_file in \
  "$deploy_workflow" \
  "$migrate_workflow" \
  "$runner_script" \
  "$remote_script" \
  "$state_test" \
  "$dockerfile" \
  "$compose_file" \
  "$app_build" \
  "$migration_main" \
  "$migration_test" \
  "$migration_log_config" \
  "$migration_boundary" \
  "$flyway_config"; do
  [ -f "$required_file" ] || fail "missing ${required_file#"$repository_root/"}"
done
[ -x "$runner_script" ] || fail "release runner is not executable"
[ -x "$state_test" ] || fail "release-state executable test is not executable"
[ "$(grep -c '^    def test_' "$state_test")" = "73" ] ||
  fail "release-state executable regression count must be exactly 73"
bash -n "$runner_script" || fail "release runner is not valid Bash"
bash -n "$remote_script" || fail "remote helper is not valid Bash"

expected_group='  group: payments-schema-${{ github.event_name == '\''push'\'' && '\''prod'\'' || inputs.environment }}'
for workflow in "$deploy_workflow" "$migrate_workflow"; do
  [ "$(grep -Fxc "$expected_group" "$workflow")" = "1" ] ||
    fail "workflow lacks the shared environment concurrency group: ${workflow##*/}"
  [ "$(grep -Fxc '  cancel-in-progress: false' "$workflow")" = "1" ] ||
    fail "workflow may cancel a schema release: ${workflow##*/}"
  grep -Fqx 'permissions:' "$workflow" || fail "permissions missing: ${workflow##*/}"
  grep -Fqx '  contents: read' "$workflow" || fail "contents permission is not read-only: ${workflow##*/}"
  if grep -Eq '^[[:space:]]+[a-zA-Z_-]+:[[:space:]]*write([[:space:]]|$)' "$workflow"; then
    fail "workflow gained write permission: ${workflow##*/}"
  fi
  [ "$(grep -Fxc '        run: scripts/deploy/quiesced-release.sh' "$workflow")" = "1" ] ||
    fail "workflow bypasses the shared release orchestrator: ${workflow##*/}"
  [ "$(grep -Fxc '    environment: ${{ github.event_name == '\''push'\'' && '\''prod'\'' || inputs.environment }}' "$workflow")" = "1" ] ||
    fail "workflow job does not use the shared protected environment: ${workflow##*/}"
  if grep -Eq 'flywayMigrate|DATABASE_(URL|USER|PASSWORD)|setup-gradle|setup-java|wrapper-validation-action' "$workflow"; then
    fail "workflow contains checkout migration tooling or independent database configuration: ${workflow##*/}"
  fi
done

if grep -Eq '^  push:' "$migrate_workflow"; then
  fail "db-migrate must not trigger independently on a tag"
fi
confirmation_block="$({
  awk '
    /^      confirm_quiesced_release:/ { inside = 1 }
    inside && /^        [a-zA-Z_]+:/ && $1 != "description:" &&
      $1 != "required:" && $1 != "default:" && $1 != "type:" { exit }
    inside { print }
  ' "$migrate_workflow"
})"
for confirmation_line in \
  '      confirm_quiesced_release:' \
  '        required: true' \
  '        default: false' \
  '        type: boolean'; do
  printf '%s\n' "$confirmation_block" | grep -Fqx "$confirmation_line" ||
    fail "db-migrate confirmation is not required, false-by-default boolean"
done
confirmation_guard="$({
  awk '
    /^      - name: Require full quiesced release confirmation$/ { inside = 1 }
    inside && /^      - name:/ && $0 !~ /Require full quiesced release confirmation/ { exit }
    inside { print }
  ' "$migrate_workflow"
})"
for guard_line in \
  '      - name: Require full quiesced release confirmation' \
  '        if: ${{ !inputs.confirm_quiesced_release }}' \
  '        run: |' \
  '          echo "A standalone migration is forbidden; confirm the full quiesced release." >&2' \
  '          exit 1'; do
  printf '%s\n' "$confirmation_guard" | grep -Fqx "$guard_line" ||
    fail "db-migrate confirmation guard does not fail closed"
done
if grep -Eiq 'previous[_ -]?tag|auto-?rollback|rollback.*old|old.*rollback' \
  "$deploy_workflow" "$migrate_workflow" "$runner_script" "$remote_script"; then
  fail "pre-migration image rollback is present"
fi

extract_function() {
  local function_name="$1"
  local source="$2"
  awk -v declaration="${function_name}() {" '
    $0 == declaration { inside = 1; next }
    inside && /^}/ { exit }
    inside { print }
  ' "$source"
}

extract_python_test() {
  local test_name="$1"
  local source="$2"
  awk -v declaration="    def ${test_name}" '
    index($0, declaration) == 1 { inside = 1 }
    inside && seen && /^    def test_/ { exit }
    inside && /^class / { exit }
    inside { print; seen = 1 }
  ' "$source"
}

line_in_body() {
  local body="$1"
  local needle="$2"
  printf '%s\n' "$body" | grep -n -m1 -F -- "$needle" | cut -d: -f1
}

assert_order() {
  local body="$1"
  shift
  local previous=0 needle current_line
  for needle in "$@"; do
    current_line="$(line_in_body "$body" "$needle" || true)"
    [ -n "$current_line" ] || fail "ordered contract lacks: $needle"
    [ "$current_line" -gt "$previous" ] || fail "ordered contract changed around: $needle"
    previous="$current_line"
  done
}

release_body="$(extract_function run_release "$runner_script")"
expected_release_body="$(printf '%s\n' \
  '  upload_remote_helper' \
  '  preflight_remote_release' \
  '  execute_release_operation prepare "$COMPOSE_PATH" "$verified_digest" "$EXPECTED_REVISION"' \
  '  execute_release_operation publish "$COMPOSE_PATH" "$verified_digest" "$EXPECTED_REVISION"' \
  '  execute_release_operation quiesce "$COMPOSE_PATH" "$verified_digest" "$EXPECTED_REVISION"' \
  '  execute_release_operation migrate "$COMPOSE_PATH" "$verified_digest" "$EXPECTED_REVISION"' \
  '  execute_release_operation start "$COMPOSE_PATH" "$verified_digest" "$EXPECTED_REVISION"' \
  '  execute_release_operation cleanup "$COMPOSE_PATH" "$verified_digest" "$EXPECTED_REVISION"' \
  '  execute_release_operation helper-cleanup "$COMPOSE_PATH" "$verified_digest" "$EXPECTED_REVISION"')"
[ "$release_body" = "$expected_release_body" ] ||
  fail "runner release order or single-invocation contract changed"
[ "$(grep -Fc 'remote_command "$requested_operation" "$@"' "$runner_script")" = "1" ] ||
  fail "mutating SSH operation is not single-shot"
[ "$(grep -Fc 'scp -P "$SSH_PORT"' "$runner_script")" = "1" ] ||
  fail "remote helper upload is not single-shot"
if grep -Eq 'remote_command[[:space:]]+(abort|resume)' "$runner_script"; then
  fail "runner automatically invokes abort or resume"
fi
for runner_contract in \
  'ssh_exit_code=$?' \
  '[ "$ssh_exit_code" = "255" ]' \
  'query_status_once "$requested_operation" "$digest"' \
  confirmed_remote_failure \
  transport_loss_with_durable_checkpoint \
  completed_but_acknowledgement_lost \
  status_unavailable \
  'echo "quiesced-release: recovery=$recovery" >&2' \
  'chmod 600 "$operation_stdout" "$operation_stderr"' \
  'chmod 600 "$status_stdout" "$status_stderr"' \
  'unset REGISTRY_READ_TOKEN' \
  'unset registry_read_token'; do
  grep -Fq -- "$runner_contract" "$runner_script" ||
    fail "runner result/status contract lacks: $runner_contract"
done
for volatile_root in '/tmp|/tmp/*' '/var/tmp|/var/tmp/*' '/run|/run/*' '/dev/shm|/dev/shm/*'; do
  grep -Fq "$volatile_root" "$runner_script" ||
    fail "runner does not reject volatile Compose root: $volatile_root"
done
if grep -Eq '(cat|tee)[[:space:]]+"\$(operation|status)_stderr"|printf.*\$(operation|status)_stderr' "$runner_script"; then
  fail "runner can expose raw SSH stderr"
fi

checkpoint_contract=(
  maintenance_prepared
  prior_state_captured
  candidate_override_published
  app_stop_intent
  app_quiesced
  migration_started
  migration_completed
  candidate_start_begun
  candidate_healthy
  cleanup_started
  cleanup_completed
)
for checkpoint in "${checkpoint_contract[@]}"; do
  grep -Fq "$checkpoint" "$remote_script" || fail "remote checkpoint is missing: $checkpoint"
done
transition_body="$(extract_function checkpoint_transition_allowed "$remote_script")"
for transition in \
  maintenance_prepared:prior_state_captured \
  prior_state_captured:candidate_override_published \
  candidate_override_published:app_stop_intent \
  app_stop_intent:app_quiesced \
  app_quiesced:migration_started \
  migration_started:migration_completed \
  migration_completed:candidate_start_begun \
  candidate_start_begun:candidate_healthy \
  candidate_healthy:cleanup_started; do
  printf '%s\n' "$transition_body" | grep -Fq "$transition" ||
    fail "checkpoint transition is missing: $transition"
done

protocol_path_body="$(extract_function configure_protocol_paths "$remote_script")"
for durable_path_contract in \
  'state_parent="$compose_path/.clubs-bot-release-state"' \
  'state_root="$state_parent/$app_env"' \
  'application_lock_file="$state_parent/application.lock"' \
  'application_binding_file="$state_parent/application.binding"' \
  'active_anchor_file="$state_parent/active-candidate.anchor"' \
  'ledger_dir="$state_root/clubs-bot-schema-${app_env}.migration-ledgers"'; do
  printf '%s\n' "$protocol_path_body" | grep -Fq "$durable_path_contract" ||
    fail "durable Compose-scoped state path lacks: $durable_path_contract"
done
if printf '%s\n' "$protocol_path_body" | grep -Fq 'state_root="/tmp'; then
  fail "authoritative production state root remains volatile"
fi
persistent_filesystem_body="$(extract_function validate_persistent_filesystem "$remote_script")"
for persistent_contract in \
  '/tmp|/tmp/*' '/var/tmp|/var/tmp/*' '/run|/run/*' '/var/run|/var/run/*' '/dev/shm|/dev/shm/*' \
  'ext2|ext3|ext4|xfs|btrfs|zfs|f2fs'; do
  printf '%s\n' "$persistent_filesystem_body" | grep -Fq "$persistent_contract" ||
    fail "persistent filesystem contract lacks: $persistent_contract"
done
mount_pair_value_body="$(extract_function validate_findmnt_pair_value "$remote_script")"
mount_pair_decoder_body="$(extract_function decode_findmnt_pair_value "$remote_script")"
mount_pair_parser_body="$(extract_function parse_findmnt_pair_record "$remote_script")"
mount_fingerprint_body="$(extract_function compute_mount_fingerprint "$remote_script")"
mount_identity_body="$(extract_function read_mount_identity "$remote_script")"
backing_match_body="$(extract_function authoritative_backing_matches "$remote_script")"
backing_tree_body="$(extract_function validate_existing_authoritative_backing "$remote_script")"
for mount_detector_contract in \
  'command -v findmnt' \
  'findmnt --noheadings --pairs --output FSTYPE,SOURCE,FSROOT,TARGET --target "$path"' \
  '[ -n "$record" ]' \
  '[[ "$record" != *$'\''\n'\''* ]]' \
  'parse_findmnt_pair_record "$record"'; do
  printf '%s\n' "$mount_identity_body" | grep -Fq "$mount_detector_contract" ||
    fail "mount-aware filesystem detector lacks: $mount_detector_contract"
done
[ "$(printf '%s\n' "$mount_identity_body" | grep -Fc 'findmnt --noheadings --pairs --output FSTYPE,SOURCE,FSROOT,TARGET --target "$path"')" = "1" ] ||
  fail "one mount identity read must execute exactly one coherent findmnt command"
if grep -Fq 'mount_field_value() {' "$remote_script"; then
  fail "mount identity still permits independent field-by-field findmnt calls"
fi
for mount_pair_contract in \
  'local fields=(FSTYPE SOURCE FSROOT TARGET)' \
  'prefix="${field}=\""' \
  'validate_findmnt_pair_value "$value"' \
  '[ -z "$remaining" ] || return 1' \
  '[ -n "$parsed_mount_fstype" ]' \
  '[ -n "$parsed_mount_source" ]' \
  '[ -n "$parsed_mount_fsroot" ]' \
  '[ -n "$parsed_mount_target" ]'; do
  printf '%s\n' "$mount_pair_parser_body" | grep -Fq "$mount_pair_contract" ||
    fail "strict exact-field findmnt pairs parser lacks: $mount_pair_contract"
done
for mount_pair_value_contract in \
  '[ -n "$value" ]' \
  '[ "${#value}" -le 1024 ]' \
  '[[ "$value" != *[[:space:]]* ]]' \
  '\\x[0-9a-fA-F][0-9a-fA-F]*' \
  '*) return 1'; do
  printf '%s\n' "$mount_pair_value_body" | grep -Fq "$mount_pair_value_contract" ||
    fail "bounded findmnt pairs value grammar lacks: $mount_pair_value_contract"
done
for mount_pair_decoder_contract in \
  '[[ "$remainder" == \\x[0-9a-fA-F][0-9a-fA-F]* ]]' \
  '[ "$hex" != "00" ]' \
  "printf -v byte '%b'" \
  'decoded_findmnt_value="$decoded"'; do
  printf '%s\n' "$mount_pair_decoder_body" | grep -Fq "$mount_pair_decoder_contract" ||
    fail "strict no-eval findmnt pairs decoder lacks: $mount_pair_decoder_contract"
done
printf '%s\n' "$mount_pair_parser_body" | grep -Fq 'decode_findmnt_pair_value "$value"' ||
  fail "mount parser does not decode its validated machine value before fingerprinting"
if printf '%s\n' "$mount_pair_decoder_body" | grep -Eq '(^|[^a-zA-Z])eval([^a-zA-Z]|$)'; then
  fail "mount pairs decoder uses eval"
fi
for mount_fingerprint_contract in \
  'filesystem_type_hash="$(sha256_text "$filesystem_type")"' \
  'source_hash="$(sha256_text "$source")"' \
  'filesystem_root_hash="$(sha256_text "$filesystem_root")"' \
  'mount_target_hash="$(sha256_text "$mount_target")"' \
  'clubs-bot-mount-fingerprint-version=$mount_fingerprint_version' \
  'FSTYPE_SHA256=$filesystem_type_hash' \
  'SOURCE_SHA256=$source_hash' \
  'FSROOT_SHA256=$filesystem_root_hash' \
  'TARGET_SHA256=$mount_target_hash' \
  'mount-v${mount_fingerprint_version}:$(sha256_text "$canonical")' \
  '^mount-v2:[0-9a-f]{64}$'; do
  printf '%s\n' "$mount_fingerprint_body" | grep -Fq "$mount_fingerprint_contract" ||
    fail "versioned collision-safe mount fingerprint lacks: $mount_fingerprint_contract"
done
assert_order "$mount_fingerprint_body" \
  'clubs-bot-mount-fingerprint-version=$mount_fingerprint_version' \
  'FSTYPE_SHA256=$filesystem_type_hash' \
  'SOURCE_SHA256=$source_hash' \
  'FSROOT_SHA256=$filesystem_root_hash' \
  'TARGET_SHA256=$mount_target_hash'
if grep -Fq 'mount-v1|' "$remote_script"; then
  fail "ambiguous delimiter-based mount fingerprint remains accepted"
fi
for backing_match_contract in \
  'read_mount_identity "$path"' \
  '[ "$detected_filesystem_type" = "$approved_filesystem_type" ]' \
  '[ "$detected_mount_fingerprint" = "$approved_mount_fingerprint" ]'; do
  printf '%s\n' "$backing_match_body" | grep -Fq "$backing_match_contract" ||
    fail "authoritative backing comparison lacks: $backing_match_contract"
done
if grep -Fq "stat -f -c '%T'" "$remote_script"; then
  fail "production filesystem decision still depends on GNU stat representation"
fi
for authoritative_path_contract in \
  '"$state_parent"' \
  '"$application_lock_file"' \
  '"$application_binding_file"' \
  '"$state_root"' \
  '"$active_anchor_file"' \
  '"$lock_dir"' \
  '"$finalizing_dir"' \
  '"$result_dir"' \
  '"$ledger_dir"' \
  '"$operation_lock_file"' \
  '"$disposal_dir"' \
  'authoritative_backing_matches "$entry"'; do
  printf '%s\n' "$backing_tree_body" | grep -Fq "$authoritative_path_contract" ||
    fail "actual authoritative subtree backing validation lacks: $authoritative_path_contract"
done
for path_contract in \
  'path_chain_has_no_symlink "$compose_path"' \
  '[ -f "$compose_path/docker-compose.yml" ]' \
  '[ ! -L "$compose_path/docker-compose.yml" ]'; do
  grep -Fq "$path_contract" "$remote_script" || fail "persistent path contract lacks: $path_contract"
done

binding_body="$(extract_function ensure_application_binding "$remote_script")
$(extract_function application_binding_valid "$remote_script")
$(extract_function write_application_binding "$remote_script")"
for binding_contract in \
  'binding_version=3' environment compose_path_hash 'mount_fingerprint_version=$mount_fingerprint_version' \
  mount_fingerprint compose_project compose_service \
  '[ "$binding_mount_version" = "$mount_fingerprint_version" ]' \
  '^mount-v2:[0-9a-f]{64}$' \
  'mount_fingerprint" = "$approved_mount_fingerprint"' \
  application_binding_valid write_application_binding; do
  printf '%s\n' "$binding_body" | grep -Fq "$binding_contract" ||
    fail "application environment binding lacks: $binding_contract"
done
grep -Fq 'flock -n 7' "$remote_script" || fail "shared cross-environment application lock is missing"

protocol_root_body="$(extract_function ensure_protocol_root "$remote_script")"
[ "$(printf '%s\n' "$protocol_root_body" | grep -Fc 'validate_existing_authoritative_backing')" -ge 3 ] ||
  fail "mutation path does not revalidate actual authoritative backing at every authority boundary"
assert_order "$protocol_root_body" \
  'validate_existing_authoritative_backing' \
  'acquire_application_lock' \
  'ensure_application_binding "$compose_path"' \
  'ensure_durable_directory "$state_root"' \
  'trusted_directory "$state_root" 700'

durable_directory_body="$(extract_function ensure_durable_directory "$remote_script")"
created_directory_body="$(printf '%s\n' "$durable_directory_body" | sed -n '/mkdir "\$directory"/,$p')"
printf '%s\n' "$durable_directory_body" | grep -Fq 'authoritative_backing_matches "$parent"' ||
  fail "durable directory creation does not validate nearest existing backing"
assert_order "$created_directory_body" \
  'mkdir "$directory"' \
  'trusted_directory "$directory" 700' \
  'fsync_parent_directory "$directory"'
printf '%s\n' "$created_directory_body" |
  grep -Fq 'fsync_parent_directory "$directory" || ! fsync_parent_directory "$parent"' ||
  fail "durable directory creation does not sync both created directory and parent"

durable_replace_body="$(extract_function atomic_write_value "$remote_script")
$(extract_function durable_commit_temporary "$remote_script")"
assert_order "$durable_replace_body" \
  'temporary="$(mktemp "${target}.tmp.XXXXXX")"' \
  'chmod 600 "$temporary"' \
  'fsync_file_content "$temporary"' \
  'mv -f -- "$temporary" "$target"' \
  'fsync_parent_directory "$parent"'
for durability_contract in \
  'sync "$path"' \
  'sync "$directory"' \
  ensure_durable_directory \
  durable_rename_directory \
  durable_remove_directory \
  durability_failure; do
  grep -Fq -- "$durability_contract" "$remote_script" ||
    fail "durable write/directory contract lacks: $durability_contract"
done

for state_contract in \
  'initializing_dir="$(mktemp -d' \
  'durable_rename_directory "$initializing_dir" "$lock_dir"' \
  'atomic_write_value()' \
  'temporary="$(mktemp "${target}.tmp.XXXXXX")"' \
  'chmod 600 "$temporary"' \
  'mv -f -- "$temporary" "$target"' \
  'chmod 700 "$initializing_dir"' \
  'ensure_durable_directory "$result_dir"' \
  'flock -n 7' \
  'flock -n 9' \
  'flock -s -n 6' \
  'flock -s -n 8' \
  guard_operation_identity \
  'require_identity "$compose_path" "$expected_revision" "$digest"' \
  old_app_digest \
  old_app_revision \
  old_container_hash \
  old_image_id_hash \
  old_started_at_hash \
  old_restart_count \
  compose_project \
  compose_service \
  prior_override_sha256 \
  candidate_override_sha256 \
  verify_stored_prior_override \
  verify_effective_candidate_override \
  '# clubs-bot-managed-quiesced-release'; do
  grep -Fq -- "$state_contract" "$remote_script" ||
    fail "durable state contract lacks: $state_contract"
done

result_writer="$(extract_function write_operation_result "$remote_script")"
for result_field_name in \
  result_version owner requested_operation checkpoint_before checkpoint_after result failure_category \
  expected_revision image_digest compose_path_hash; do
  printf '%s\n' "$result_writer" | grep -Fq "$result_field_name=" ||
    fail "operation result omits: $result_field_name"
done
for result_value in success remote_failure incomplete_unknown child_exit_255 interrupted; do
  grep -Fq "$result_value" "$remote_script" || fail "result category is missing: $result_value"
done
grep -Fq 'trap finalize_operation_result EXIT' "$remote_script" ||
  fail "remote result has no EXIT finalizer"
result_reader="$(extract_function read_result_category "$remote_script")"
for result_reader_contract in \
  'validate_operation "$record_operation"' \
  '[ "$record_operation" != "$requested_operation" ]' \
  'printf '\''%s'\'' "unavailable"'; do
  printf '%s\n' "$result_reader" | grep -Fq "$result_reader_contract" ||
    fail "operation result reconciliation lacks: $result_reader_contract"
done

status_body="$(extract_function status_operation "$remote_script")"
status_root_body="$(extract_function validate_protocol_root_readonly "$remote_script")"
grep -Fq 'release-status:v=1 status_available=yes owner_match=%s revision_match=%s digest_match=%s checkpoint=%s operation_result=%s migration_evidence=%s app_state=%s abort_permitted=%s resume_permitted=%s failure_category=none' "$remote_script" ||
  fail "read-only status schema changed"
for status_root_contract in \
  validate_persistent_filesystem trusted_directory trusted_authoritative_file \
  validate_existing_authoritative_backing 'flock -s -n 6' application_binding_valid active_anchor_valid; do
  printf '%s\n' "$status_root_body" | grep -Fq "$status_root_contract" ||
    fail "read-only status root-chain validation lacks: $status_root_contract"
done
grep -Fq 'failure_category=untrusted_state_root' "$remote_script" ||
  fail "untrusted status root lacks bounded failure category"
for forbidden_status_action in \
  'compose_command stop' \
  'compose_command rm' \
  'compose_command run' \
  'compose_command up' \
  atomic_write_value \
  write_checkpoint \
  'docker rm'; do
  if printf '%s\n' "$status_body" | grep -Fq "$forbidden_status_action"; then
    fail "read-only status contains a mutation: $forbidden_status_action"
  fi
done
if printf '%s\n' "$status_body" | grep -Eq 'printf.*(compose_path|container_id|migration_log|stderr)'; then
  fail "status can expose an internal path, id or raw output"
fi
for status_fail_closed_contract in \
  'verify_stored_prior_override 2>/dev/null && stored_prior_ok=yes' \
  'verify_effective_candidate_override "$compose_path" "$stored_digest" "$stored_revision"' \
  '[ "$operation_result" = "malformed" ]'; do
  printf '%s\n' "$status_body" | grep -Fq "$status_fail_closed_contract" ||
    fail "status permission guard lacks: $status_fail_closed_contract"
done

abort_body="$(extract_function abort_pre_quiesce "$remote_script")"
for abort_guard in \
  'require_identity "$compose_path" "$expected_revision" "$digest"' \
  'maintenance_prepared|prior_state_captured|candidate_override_published|app_stop_intent' \
  'verify_old_app_unchanged "$compose_path"' \
  migration_evidence_category \
  validate_state_cleanup_allowlist \
  'restore_prior_override "$compose_path" "$digest" "$expected_revision" || return 1' \
  'write_completed_record "abort_completed"'; do
  printf '%s\n' "$abort_body" | grep -Fq "$abort_guard" ||
    fail "abort contract lacks: $abort_guard"
done
assert_order "$abort_body" \
  'verify_stored_prior_override || {' \
  'if ! verify_prior_override_current "$compose_path"' \
  'write_checkpoint "$checkpoint" "abort_started"' \
  'restore_prior_override "$compose_path" "$digest" "$expected_revision" || return 1'
if printf '%s\n' "$abort_body" | grep -Fq cleanup_completed; then
  fail "pre-quiesce abort accepts normal release completion"
fi
if printf '%s\n' "$abort_body" | grep -Eq 'compose_command (stop|rm|run|up)|docker (stop|rm|run)|flyway|psql'; then
  fail "abort can perform container lifecycle or database work"
fi
allowlist_body="$(extract_function validate_state_cleanup_allowlist "$remote_script")"
printf '%s\n' "$allowlist_body" | grep -Fq '*) return 1' ||
  fail "cleanup does not reject unknown state before deletion"

resume_case="$({
  awk '
    /^  resume\)/ { inside = 1; next }
    inside && /^    ;;/ { exit }
    inside { print }
  ' "$remote_script"
})"
for resume_target in quiesce migrate start cleanup; do
  printf '%s\n' "$resume_case" | grep -Fq "$resume_target" ||
    fail "explicit resume omits target: $resume_target"
done

quiesce_body="$(extract_function continue_quiesce "$remote_script")"
assert_order "$quiesce_body" \
  'write_checkpoint "candidate_override_published" "app_stop_intent"' \
  'stop_and_remove_app "$compose_path" "$digest" "$expected_revision"' \
  'write_checkpoint "app_stop_intent" "app_quiesced"'
printf '%s\n' "$quiesce_body" | grep -Fq 'old_running|absent' ||
  fail "quiesce cannot reconcile unchanged-old versus absent app"
printf '%s\n' "$quiesce_body" | grep -Fq 'verify_effective_candidate_override "$compose_path" "$digest" "$expected_revision"' ||
  fail "resume quiesce can bypass effective Compose candidate verification"

for executable_regression in \
  test_abort_rejects_tampered_prior_before_intent_and_status_denies \
  test_resume_rejects_candidate_without_effective_compose_proof \
  test_abort_rejects_normal_release_completion \
  test_status_reports_no_result_for_not_yet_invoked_operation \
  test_transport_loss_before_result_record_uses_prior_checkpoint \
  test_status_rejects_wrong_identity_and_malformed_result \
  test_migration_success_first_durable_evidence_write_failure_is_fail_closed \
  test_migration_success_completion_ledger_write_failure_is_fail_closed \
  test_migration_success_completion_parent_directory_fsync_failure_is_fail_closed \
  test_completed_ledger_survives_completion_checkpoint_fsync_failure \
  test_completed_ledger_survives_migration_container_removal_failure \
  test_completed_ledger_survives_terminal_release_record_failure \
  test_cold_process_reentry_discards_volatile_files_but_keeps_authority \
  test_durability_failures_never_false_advance_checkpoint \
  test_old_terminal_artifacts_pruned_at_count_and_age_boundary \
  test_recent_terminal_and_incomplete_or_current_artifacts_are_preserved \
  test_active_candidate_anchor_beats_clock_count_and_timestamp_ties \
  test_anchor_update_failures_preserve_previous_authority_until_durable_replace \
  test_malformed_or_symlinked_anchor_blocks_pruning \
  test_volatile_paths_reject_before_authority \
  test_mount_detector_accepts_every_allowlisted_filesystem_from_one_coherent_record \
  test_mount_detector_failures_and_unsupported_types_reject_before_authority \
  test_mount_fingerprint_is_collision_safe_and_old_binding_format_rejects \
  test_mixed_mount_snapshots_cannot_form_application_authority \
  test_symlinked_root_rejects_and_supported_persistent_fixture_is_accepted \
  test_one_compose_root_binds_exactly_one_environment \
  test_concurrent_stage_prod_binding_has_one_winner_and_survives_restart \
  test_binding_parent_sync_failure_and_malformed_binding_fail_closed \
  test_binding_file_fsync_failure_has_no_false_authority \
  test_binding_rename_failure_has_no_false_authority \
  test_nested_authoritative_mounts_reject_mutation_status_and_cold_reentry \
  test_intermediate_parent_symlink_rejects_mutation_and_status \
  test_status_write_audit_detects_every_forbidden_category \
  test_status_rejects_every_untrusted_root_chain_without_writes \
  test_valid_persistent_root_status_is_truthful_and_read_only \
  test_runner_rejects_volatile_compose_root_before_upload_or_ssh \
  test_symlink_or_malformed_terminal_artifact_is_rejected_without_broad_cleanup; do
  grep -Fq "def $executable_regression" "$state_test" ||
    fail "release-state executable regression is missing: $executable_regression"
done

allowlisted_mount_test="$(extract_python_test \
  test_mount_detector_accepts_every_allowlisted_filesystem_from_one_coherent_record "$state_test")"
for accepted_filesystem in ext2 ext3 ext4 xfs btrfs zfs f2fs; do
  printf '%s\n' "$allowlisted_mount_test" | grep -Fq "\"$accepted_filesystem\"" ||
    fail "allowlisted filesystem lacks executable positive coverage: $accepted_filesystem"
done
for coherent_record_contract in \
  '"--noheadings"' \
  '"--pairs"' \
  '"--output"' \
  '"FSTYPE,SOURCE,FSROOT,TARGET"' \
  'self.assertEqual(2, len(compose_identity_reads), commands)' \
  'self.assertEqual("3", binding["binding_version"])' \
  'self.assertEqual("2", binding["mount_fingerprint_version"])' \
  'r"^mount-v2:[0-9a-f]{64}$"'; do
  printf '%s\n' "$allowlisted_mount_test" | grep -Fq "$coherent_record_contract" ||
    fail "coherent allowlisted mount executable proof lacks: $coherent_record_contract"
done

mount_rejection_test="$(extract_python_test \
  test_mount_detector_failures_and_unsupported_types_reject_before_authority "$state_test")"
for rejected_mount_case in \
  tmpfs ramfs devtmpfs overlay unknownfs failure empty multiple extra-fields \
  missing-fstype missing-source missing-fsroot missing-target duplicate-field reordered-fields \
  malformed-escaping malformed-pairs; do
  printf '%s\n' "$mount_rejection_test" | grep -Fq "\"$rejected_mount_case\"" ||
    fail "mount detector/parser rejection lacks executable case: $rejected_mount_case"
done

mount_collision_test="$(extract_python_test \
  test_mount_fingerprint_is_collision_safe_and_old_binding_format_rejects "$state_test")"
for collision_contract in \
  '"device|/branch", "/leaf"' \
  '"device", "/branch|/leaf"' \
  'self.assertEqual(2, len(set(fingerprints)))' \
  '"binding_version=2"' \
  'removeprefix("mount-v2:")'; do
  printf '%s\n' "$mount_collision_test" | grep -Fq "$collision_contract" ||
    fail "collision-safe fingerprint executable proof lacks: $collision_contract"
done

mixed_snapshot_test="$(extract_python_test \
  test_mixed_mount_snapshots_cannot_form_application_authority "$state_test")"
for mixed_snapshot_contract in \
  'configure_findmnt_behavior("mixed-snapshots")' \
  'self.assertFalse(harness.application_binding.exists())' \
  '"FSTYPE,SOURCE,FSROOT,TARGET"' \
  'self.assert_no_mutating_effects(harness)'; do
  printf '%s\n' "$mixed_snapshot_test" | grep -Fq "$mixed_snapshot_contract" ||
    fail "mixed mount snapshot rejection proof lacks: $mixed_snapshot_contract"
done

status_untrusted_test="$(extract_python_test \
  test_status_rejects_every_untrusted_root_chain_without_writes "$state_test")"
for untrusted_status_case in \
  shared-root-symlink environment-root-symlink compose-root-symlink shared-root-mode \
  environment-root-mode malformed-binding wrong-environment-binding symlinked-binding \
  unsupported-filesystem mount-detector-failure; do
  printf '%s\n' "$status_untrusted_test" | grep -Fq "\"$untrusted_status_case\"" ||
    fail "zero-write status proof lacks invalid-root case: $untrusted_status_case"
done
for status_snapshot_contract in \
  'def snapshot_authoritative_tree' \
  'before_tree = snapshot_authoritative_tree(harness)' \
  'after_tree = snapshot_authoritative_tree(harness)' \
  'testcase.assertEqual(before_tree, after_tree)' \
  'harness.status_filesystem_write_counts()' \
  'testcase.assertEqual([], harness.lifecycle_commands())'; do
  grep -Fq "$status_snapshot_contract" "$state_test" ||
    fail "status filesystem zero-write executable proof lacks: $status_snapshot_contract"
done
for status_write_category in \
  mkdir create open_for_write chmod rename unlink rmdir fsync truncate prune; do
  grep -Fq "\"$status_write_category\"" "$state_test" ||
    fail "status filesystem write audit lacks category: $status_write_category"
done

migration_body="$(extract_function migrate_verified_image "$remote_script")"
for forbidden_migration_construct in gradlew flywayMigrate IMAGE_TAG --volume ' -v ' '/opt/app/bin/app-bot '; do
  if printf '%s\n' "$migration_body" | grep -Fq -- "$forbidden_migration_construct"; then
    fail "verified-image migration contains forbidden construct: $forbidden_migration_construct"
  fi
done
[ "$(printf '%s\n' "$migration_body" | grep -Fc 'compose_command "$compose_path" run')" = "1" ] ||
  fail "verified-image migration must execute exactly once"
assert_order "$migration_body" \
  'write_migration_started_ledger "$compose_path" "$expected_revision" "$digest"' \
  'write_checkpoint "app_quiesced" "migration_started"' \
  'compose_command "$compose_path" run' \
  'docker wait "$migration_container_id"' \
  'capture_and_forward_safe_migration_diagnostics "$migration_container_id" "$migration_exit_code"' \
  'atomic_write_value "$state_dir/migration_image_digest" "$digest"' \
  'write_migration_success_outcome "$compose_path" "$expected_revision" "$digest"' \
  'write_migration_completed_ledger "$compose_path" "$expected_revision" "$digest"' \
  'write_checkpoint "migration_started" "migration_completed"'
for migration_contract in \
  '--entrypoint /opt/app/bin/app-bot-migrate' \
  --no-deps \
  '--pull never' \
  QUIESCED_RELEASE_MIGRATION=required \
  'migration_reference" != "$digest"' \
  'migration_image_id" != "$expected_image_id"' \
  'migration_exit_code" =~ ^[0-9]+$'; do
  printf '%s\n' "$migration_body" | grep -Fq -- "$migration_contract" ||
    fail "verified-image migration lacks: $migration_contract"
done

start_body="$(extract_function start_and_probe_app "$remote_script")"
assert_order "$start_body" \
  'verify_candidate_override "$compose_path" "$digest" "$expected_revision"' \
  'assert_app_absent "$compose_path" "$digest" "$expected_revision"' \
  'migration_digest="$(state_value migration_image_digest)"' \
  'write_checkpoint "migration_completed" "candidate_start_begun"' \
  'compose_command up -d --no-deps --pull never app' \
  'write_checkpoint "candidate_start_begun" "candidate_healthy"'
for start_guard in \
  'probe_candidate "$compose_path" "$digest" "$expected_revision"' \
  'probe_readiness_and_health "$compose_path"'; do
  printf '%s\n' "$start_body" | grep -Fq "$start_guard" || fail "candidate start lacks: $start_guard"
done

cleanup_body="$(extract_function cleanup_successful_release "$remote_script")"
for cleanup_guard in \
  candidate_healthy \
  cleanup_started \
  verify_candidate_override \
  probe_candidate \
  probe_readiness_and_health \
  validate_state_cleanup_allowlist \
  remove_completed_migration_container \
  'write_completed_record "cleanup_completed"' \
  remove_allowlisted_state; do
  printf '%s\n' "$cleanup_body" | grep -Fq "$cleanup_guard" ||
    fail "successful cleanup lacks: $cleanup_guard"
done
cleanup_terminal_body="$(printf '%s\n' "$cleanup_body" | sed -n '/remove_completed_migration_container "\$digest"/,$p')"
assert_order "$cleanup_terminal_body" \
  'remove_completed_migration_container "$digest"' \
  'write_completed_record "cleanup_completed"' \
  'write_active_candidate_anchor "$compose_path" "$expected_revision" "$digest"' \
  'remove_allowlisted_state'
printf '%s\n' "$cleanup_body" | grep -Fq 'remove_completed_migration_container "$digest"' ||
  fail "migration container cleanup is not completion-ledger gated"
if printf '%s\n' "$cleanup_body" | grep -Eq 'migration-ledgers|\.ledger|\.outcome'; then
  fail "normal active-state cleanup removes durable migration authority"
fi

for ledger_contract in \
  ledger_version environment owner expected_revision image_digest compose_path_hash operation state \
  invocation_fingerprint result completion_checkpoint created_epoch completed_epoch \
  migration_outcome_requires_incident_reconciliation already_released \
  guard_new_release_against_migration_authority current_completed_ledger_matches_state; do
  grep -Fq "$ledger_contract" "$remote_script" ||
    fail "durable migration authority lacks: $ledger_contract"
done

anchor_body="$(extract_function write_active_candidate_anchor "$remote_script")"
for anchor_contract in \
  anchor_version environment binding_fingerprint expected_revision image_digest \
  migration_ledger_key migration_ledger_fingerprint terminal_receipt_key \
  active_anchor_valid; do
  printf '%s\n' "$anchor_body" | grep -Fq "$anchor_contract" ||
    fail "active candidate anchor lacks: $anchor_contract"
done

retention_body="$(extract_function prune_terminal_artifacts "$remote_script")"
for retention_contract in \
  'terminal_retention_days=30' \
  'terminal_retention_count=32' \
  'result_value" = "incomplete_unknown' \
  'artifact_owner" != "$owner' \
  'artifact_owner" != "$active_owner' \
  'artifact_owner" != "$anchored_owner' \
  'active_anchor_valid "$operation_compose_path" yes' \
  'anchored_owner="$(active_anchor_owner)"' \
  write_terminal_prune_marker \
  resume_terminal_prunes \
  '"$ledger_dir/$artifact_owner.ledger"' \
  'durable_remove_file "$path"' \
  'clubs-bot-release-$artifact_owner.sh'; do
  grep -Fq "$retention_contract" "$remote_script" ||
    fail "bounded retention contract lacks: $retention_contract"
done
if printf '%s\n' "$retention_body" | grep -Eq 'rm[[:space:]]+-r|rm[[:space:]]+-rf'; then
  fail "bounded retention uses recursive cleanup"
fi
for runbook_contract in \
  'COMPOSE_PATH/.clubs-bot-release-state/<stage|prod>' \
  'ровно один Linux-вызов' \
  '`findmnt --noheadings --pairs --output FSTYPE,SOURCE,FSROOT,TARGET --target <path>`' \
  'missing/duplicate/extra/reordered fields' \
  '`mount-v2`' \
  'fixed-label digests' \
  'fixed order' \
  'approved mount fingerprint' \
  'nested mount' \
  'каждый authoritative path' \
  '`ext2`, `ext3`, `ext4`, `xfs`, `btrfs`, `zfs`, `f2fs`' \
  'каждый из этих семи filesystem types' \
  'application.binding' \
  'Binding version `3`' \
  'mount fingerprint version `2`' \
  'active-candidate anchor' \
  'status_available=no' \
  'persistent filesystem writes' \
  'no-follow before/after tree snapshots' \
  'zero-count audit' \
  'content `fsync`' \
  'migration_outcome_requires_incident_reconciliation' \
  'Ordinary workflow' \
  'incident/adoption procedure' \
  'минимум `30` дней' \
  '`32` новейших'; do
  grep -Fq "$runbook_contract" "$repository_root/docs/ops/release-rollback.md" ||
    fail "release runbook lacks: $runbook_contract"
done

safe_event_body="$({
  awk '
    /^emit_safe_migration_diagnostics\(\) \{/ { inside = 1; next }
    inside && /^}/ { exit }
    inside { print }
  ' "$remote_script"
})"
safe_capture_body="$({
  awk '
    /^capture_and_forward_safe_migration_diagnostics\(\) \{/ { inside = 1; next }
    inside && /^}/ { exit }
    inside { print }
  ' "$remote_script"
})"
[ -n "$safe_event_body" ] && [ -n "$safe_capture_body" ] ||
  fail "migration safe-log forwarding functions are missing"
[ "$(grep -Fc 'docker logs ' "$remote_script")" = "1" ] ||
  fail "remote helper must capture migration logs exactly once"
printf '%s\n' "$safe_capture_body" |
  grep -Fq 'docker logs "$migration_container_id" >"$migration_log_temporary" 2>&1' ||
  fail "migration logs are not captured into a restricted temporary file"
printf '%s\n' "$safe_capture_body" | grep -Fq 'chmod 600 "$migration_log_temporary"' ||
  fail "temporary migration output is not restricted to its remote owner"
printf '%s\n' "$safe_capture_body" | grep -Fq 'cleanup_migration_log_temporary || return 1' ||
  fail "temporary migration output is not removed after bounded parsing"
if grep -Fq 'local migration_log_file="$state_dir/migration-container.log"' "$remote_script"; then
  fail "raw migration output is retained in durable release state"
fi
for safe_log_contract in \
  'local state="initial" parse_failed=0' \
  'od -An -tu1 -v "$migration_log_file"' \
  'byte != 10 && (byte < 32 || byte > 126)' \
  'seen && valid && last == 10' \
  'case "$state" in' \
  'initial)' \
  'started)' \
  'completed|failed)' \
  '[ "$line" = "migration-safe:v=1 event=started" ]' \
  '^migration-safe:v=1\ event=completed\ applied=(0|[1-9][0-9]{0,9})$' \
  '^migration-safe:v=1\ event=failed\ phase=(bootstrap|configuration|migration|validation|pending-check)\ category=(configuration|connection|authentication|migration|validation|cancelled|unexpected)$' \
  '[[ "$completed_applied" > "2147483647" ]]' \
  'parse_failed=1' \
  'migration diagnostic protocol rejected non-canonical output' \
  'raw output suppressed'; do
  printf '%s\n' "$safe_event_body" "$safe_capture_body" | grep -Fq "$safe_log_contract" ||
    fail "migration log allowlist lacks: $safe_log_contract"
done
if printf '%s\n' "$safe_event_body" | grep -Eq '(echo|printf|cat|tee|sed|grep).*\$line'; then
  fail "migration log allowlist forwards an untrusted raw line"
fi
if printf '%s\n' "$safe_event_body" | grep -Eq '(^|[[:space:]])(cat|tee|sed|grep)([[:space:]]|$)'; then
  fail "migration log allowlist contains a pass-through command"
fi
printf '%s\n' "$safe_capture_body" | grep -Fq 'emit_safe_migration_diagnostics "$migration_log_temporary" "$migration_exit_code"' ||
  fail "captured migration output bypasses the exact allowlist parser"

app_service_body="$({
  awk '
    /^  app:/ { inside = 1; next }
    inside && /^  [a-zA-Z0-9_-]+:/ { exit }
    inside { print }
  ' "$compose_file"
})"
if printf '%s\n' "$app_service_body" | grep -Eq '^[[:space:]]+volumes:'; then
  fail "app service must not bind-mount checkout content into the release image"
fi

for artifact_contract in \
  quiescedMigrationStartScripts \
  'applicationName = "app-bot-migrate-java"' \
  'com.example.bot.tools.QuiescedMigrateMainKt' \
  '-Dlogback.configurationFile=$quiescedMigrationLogConfig'; do
  grep -Fq -- "$artifact_contract" "$app_build" || fail "installDist migration launcher lacks: $artifact_contract"
done
if [ ! -x "$migration_boundary" ]; then
  fail "public fixed migration boundary is not executable"
fi
for boundary_contract in \
  'unset JAVA_TOOL_OPTIONS' \
  'unset JDK_JAVA_OPTIONS' \
  'unset _JAVA_OPTIONS' \
  'unset JAVA_OPTS' \
  'unset APP_BOT_MIGRATE_OPTS' \
  'unset APP_BOT_MIGRATE_JAVA_OPTS' \
  'JAVA_HOME=/opt/java/openjdk' \
  'private_launcher=/opt/app/bin/app-bot-migrate-java' \
  'exec "$private_launcher"'; do
  grep -Fq -- "$boundary_contract" "$migration_boundary" ||
    fail "public fixed migration boundary lacks: $boundary_contract"
done
if grep -Eq 'QuiescedMigrateMainKt|EngineMain|ApplicationKt|\$@|\$\*' "$migration_boundary"; then
  fail "public fixed migration boundary can bypass the private launcher or forward arbitrary arguments"
fi
application_build_body="$(awk '
  /^application \{/ { inside = 1 }
  /^val quiescedMigrationStartScripts/ { exit }
  inside { print }
' "$app_build")"
if printf '%s\n' "$application_build_body" | grep -Fq 'quiescedMigrationLogConfig'; then
  fail "normal application launcher inherits migration-only logging"
fi
for docker_contract in \
  ' && test -x /opt/app/bin/app-bot-migrate \' \
  ' && test -x /opt/app/bin/app-bot-migrate-java \' \
  ' && test ! -e /opt/app/gradlew \' ; do
  [ "$(grep -Fxc "$docker_contract" "$dockerfile")" = "1" ] ||
    fail "runtime image contract lacks an exact line: $docker_contract"
done
for migration_contract in \
  QUIESCED_RELEASE_MIGRATION \
  fromQuiescedMigrationEnv \
  'jdbc:postgresql:' \
  'flyway.migrate()' \
  'flyway.validateWithResult()' \
  'flyway.info().pending()' \
  '.loggers("slf4j")' \
  'migration-safe:v=1 event=started' \
  'migration-safe:v=1 event=completed applied=' \
  'migration-safe:v=1 event=failed phase=' \
  'catch (failure: Exception)' \
  'catch (_: Error)' \
  'private const val EXIT_FAILURE = 1'; do
  grep -Fq "$migration_contract" "$migration_main" || fail "migration entrypoint lacks: $migration_contract"
done
if grep -Eq 'EngineMain|ApplicationKt|io\.ktor|telegram|scheduler|worker' "$migration_main"; then
  fail "migration entrypoint references application/server worker startup"
fi
if grep -Eq '(^|[^[:alnum:]_])throw([^[:alnum:]_]|$)|\.message|printStackTrace|\.(trace|debug|info|warn|error)\([^)]*(failure|throwable)' "$migration_main"; then
  fail "migration entrypoint can expose raw exception details"
fi
migration_process_body="$(awk '
  /^internal fun runQuiescedMigrationProcess\(/ { inside = 1 }
  inside && /^private fun createFlyway\(/ { exit }
  inside { print }
' "$migration_main")"
process_run_line="$(printf '%s\n' "$migration_process_body" | grep -n -m1 'val result = migration' | cut -d: -f1)"
process_complete_line="$(printf '%s\n' "$migration_process_body" | grep -n -m1 'MigrationSafeEvent.Completed' | cut -d: -f1)"
if [ -z "$process_run_line" ] || [ -z "$process_complete_line" ] ||
  [ "$process_run_line" -ge "$process_complete_line" ]; then
  fail "migration completion event can precede migrate, validate or pending checks"
fi
for logging_contract in \
  '<contextName>quiesced-migration</contextName>' \
  '<pattern>%msg%n%nopex</pattern>' \
  '<logger name="org.flywaydb" level="OFF" additivity="false" />' \
  '<logger name="QuiescedMigrations" level="INFO" additivity="false">' \
  '<root level="OFF" />'; do
  grep -Fq "$logging_contract" "$migration_log_config" ||
    fail "migration-only logging configuration lacks: $logging_contract"
done
[ "$(grep -Fc '<appender name=' "$migration_log_config")" = "1" ] ||
  fail "migration-only logging configuration must have one safe console appender"
if grep -Eiq 'FileAppender|RollingFileAppender|<file>|%(ex|exception|throwable)|\$\{' "$migration_log_config"; then
  fail "migration-only logging configuration can expose uncontrolled data"
fi
grep -Fq 'baselineOnMigrate = false' "$flyway_config" ||
  fail "quiesced migration does not fail closed on missing schema history"
grep -Fq 'classpath:db/migration/postgresql' "$migration_test" ||
  fail "migration entrypoint tests do not pin PostgreSQL classpath resources"

echo "quiesced-deployment-contract: OK"
