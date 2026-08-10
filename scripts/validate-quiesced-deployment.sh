#!/usr/bin/env bash
set -euo pipefail

repository_root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
deploy_workflow="$repository_root/.github/workflows/deploy-ssh.yml"
migrate_workflow="$repository_root/.github/workflows/db-migrate.yml"
runner_script="$repository_root/scripts/deploy/quiesced-release.sh"
remote_script="$repository_root/scripts/deploy/remote-compose-release.sh"
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

release_body="$({
  awk '
    /^run_release\(\) \{/ { inside = 1; next }
    inside && /^}/ { exit }
    inside && NF { sub(/^[[:space:]]+/, ""); print }
  ' "$runner_script"
})"
expected_release_body="$(printf '%s\n' \
  preflight_remote_release \
  quiesce_remote_release \
  assert_remote_app_absent \
  run_database_migration \
  assert_remote_app_absent \
  start_remote_release \
  finish_remote_release)"
[ "$release_body" = "$expected_release_body" ] ||
  fail "release order must be prepare -> absent -> migrate -> absent -> start -> finish"

if grep -Eq 'gradlew|flywayMigrate|DATABASE_(URL|USER|PASSWORD)' "$runner_script" "$remote_script"; then
  fail "deployment scripts contain checkout Flyway or runner database configuration"
fi
migration_runner_body="$({
  awk '
    /^run_database_migration\(\) \{/ { inside = 1; next }
    inside && /^}/ { exit }
    inside && NF { sub(/^[[:space:]]+/, ""); print }
  ' "$runner_script"
})"
[ "$migration_runner_body" = "remote_command migrate" ] ||
  fail "runner must delegate migration exclusively to the remote verified-image mode"
grep -Fq 'EXPECTED_REVISION must be a full Git SHA' "$runner_script" ||
  fail "runner does not validate the source revision"

required_remote_contract=(
  'mkdir "$lock_dir"'
  'org.opencontainers.image.revision'
  '.RepoDigests'
  'docker pull "$digest"'
  '# clubs-bot-managed-quiesced-release'
  'docker-compose.override.yml'
  'docker compose config --images'
  'compose_command stop --timeout 60 app'
  'compose_command rm -f app'
  'assert_app_absent'
  'migrate_verified_image'
  'compose_command run'
  '--entrypoint /opt/app/bin/app-bot-migrate'
  '--no-deps'
  '--pull never'
  'QUIESCED_RELEASE_MIGRATION=required'
  'docker wait "$migration_container_id"'
  'capture_and_forward_safe_migration_diagnostics'
  'migration_image_digest'
  'migration_image_id'
  'application digest differs from migration digest'
  'running app image id differs from migration image id'
  'running app is not pinned to the verified digest'
  'http://127.0.0.1:8080/ready'
  'http://127.0.0.1:8080/health'
)
for contract in "${required_remote_contract[@]}"; do
  grep -Fq -- "$contract" "$remote_script" || fail "remote helper lacks: $contract"
done
for forbidden_remote_jvm_assignment in \
  'JAVA_TOOL_OPTIONS=' \
  'JDK_JAVA_OPTIONS=' \
  '_JAVA_OPTIONS=' \
  'JAVA_OPTS=' \
  'APP_BOT_MIGRATE_OPTS=' \
  'APP_BOT_MIGRATE_JAVA_OPTS='; do
  if grep -Fq -- "$forbidden_remote_jvm_assignment" "$remote_script"; then
    fail "remote helper must not create JVM option variables: $forbidden_remote_jvm_assignment"
  fi
done

[ "$(grep -Fc 'remote_command cleanup' "$runner_script")" = "1" ] ||
  fail "maintenance cleanup must occur only on the verified success path"
grep -Fq 'failure is fail-closed; maintenance lock owner=' "$runner_script" ||
  fail "post-quiesce failures do not retain the maintenance guard"
grep -Fq 'quiesce did not complete; remote maintenance state is unknown' "$runner_script" ||
  fail "failed lock acquisition is reported as a retained maintenance lock"
grep -Fq 'require_phase "quiesced"' "$remote_script" ||
  fail "migration completion is not phase-gated"
grep -Fq 'require_phase "migrated"' "$remote_script" ||
  fail "new image start is not migration-gated"
grep -Fq 'require_phase "healthy"' "$remote_script" ||
  fail "maintenance cleanup is not health-gated"

cleanup_body="$({
  awk '
    /^  cleanup\)/ { inside = 1; next }
    inside && /^    ;;/ { exit }
    inside { print }
  ' "$remote_script"
})"
if printf '%s\n' "$cleanup_body" | grep -Fq 'persistent_override'; then
  if printf '%s\n' "$cleanup_body" | grep -Eq 'rm .*persistent_override|rm .*docker-compose\.override'; then
    fail "successful cleanup removes the durable verified-digest override"
  fi
fi
printf '%s\n' "$cleanup_body" | grep -Fq 'durable verified-digest override is missing' ||
  fail "cleanup does not require the durable verified-digest override"
for migration_state in migration_image_digest migration_image_id migration-container.log; do
  printf '%s\n' "$cleanup_body" | grep -Fq "$migration_state" ||
    fail "cleanup does not remove successful migration evidence: $migration_state"
done

stop_body="$({
  awk '
    /^stop_and_remove_app\(\) \{/ { inside = 1; next }
    inside && /^}/ { exit }
    inside && NF { sub(/^[[:space:]]+/, ""); print }
  ' "$remote_script"
})"
expected_stop_body="$(printf '%s\n' \
  'compose_command stop --timeout 60 app' \
  'compose_command rm -f app' \
  'assert_app_absent')"
[ "$stop_body" = "$expected_stop_body" ] ||
  fail "remote app quiesce order changed"

runner_quiesce_body="$({
  awk '
    /^quiesce_remote_release\(\) \{/ { inside = 1; next }
    inside && /^}/ { exit }
    inside { print }
  ' "$runner_script"
})"
attempted_line="$(printf '%s\n' "$runner_quiesce_body" | grep -n -m1 'maintenance_attempted=1' | cut -d: -f1)"
remote_quiesce_line="$(printf '%s\n' "$runner_quiesce_body" | grep -n -m1 'remote_command' | cut -d: -f1)"
acquired_line="$(printf '%s\n' "$runner_quiesce_body" | grep -n -m1 'maintenance_acquired=1' | cut -d: -f1)"
if [ -z "$attempted_line" ] || [ -z "$remote_quiesce_line" ] || [ -z "$acquired_line" ] ||
  [ "$attempted_line" -ge "$remote_quiesce_line" ] || [ "$remote_quiesce_line" -ge "$acquired_line" ]; then
  fail "maintenance acquisition state is not tracked around remote quiesce"
fi

start_body="$({
  awk '
    /^start_and_probe_app\(\) \{/ { inside = 1; next }
    inside && /^}/ { exit }
    inside { print }
  ' "$remote_script"
})"
first_absent_line="$(printf '%s\n' "$start_body" | grep -n -m1 'assert_app_absent' | cut -d: -f1)"
same_digest_line="$(printf '%s\n' "$start_body" | grep -n -m1 'migration_digest" != "$digest' | cut -d: -f1)"
start_line="$(printf '%s\n' "$start_body" | grep -n -m1 'compose_command up -d --no-deps app' | cut -d: -f1)"
image_line="$(printf '%s\n' "$start_body" | grep -n -m1 'running_reference=' | cut -d: -f1)"
same_image_line="$(printf '%s\n' "$start_body" | grep -n -m1 'running_image_id" != "$migration_image_id' | cut -d: -f1)"
ready_line="$(printf '%s\n' "$start_body" | grep -n -m1 '/ready' | cut -d: -f1)"
health_line="$(printf '%s\n' "$start_body" | grep -n -m1 '/health' | cut -d: -f1)"
if printf '%s\n' "$start_body" | grep -Fq 'promote_persistent_override'; then
  fail "durable verified digest must be promoted during quiesce, before migration"
fi
if [ -z "$first_absent_line" ] || [ -z "$same_digest_line" ] || [ -z "$start_line" ] ||
  [ -z "$image_line" ] || [ -z "$same_image_line" ] || [ -z "$ready_line" ] || [ -z "$health_line" ] ||
  [ "$first_absent_line" -ge "$same_digest_line" ] || [ "$same_digest_line" -ge "$start_line" ] ||
  [ "$start_line" -ge "$image_line" ] || [ "$image_line" -ge "$same_image_line" ] ||
  [ "$same_image_line" -ge "$ready_line" ] || [ "$ready_line" -ge "$health_line" ]; then
  fail "new-image start, digest, readiness and health order changed"
fi

quiesce_body="$({
  awk '
    /^  quiesce\)/ { inside = 1; next }
    inside && /^    ;;/ { exit }
    inside { print }
  ' "$remote_script"
})"
state_line="$(printf '%s\n' "$quiesce_body" | grep -n -m1 'create_maintenance_state' | cut -d: -f1)"
promote_line="$(printf '%s\n' "$quiesce_body" | grep -n -m1 'promote_persistent_override' | cut -d: -f1)"
stop_line="$(printf '%s\n' "$quiesce_body" | grep -n -m1 'stop_and_remove_app' | cut -d: -f1)"
quiesced_line="$(printf '%s\n' "$quiesce_body" | grep -n -m1 '"quiesced"' | cut -d: -f1)"
if [ -z "$state_line" ] || [ -z "$promote_line" ] || [ -z "$stop_line" ] || [ -z "$quiesced_line" ] ||
  [ "$state_line" -ge "$promote_line" ] || [ "$promote_line" -ge "$stop_line" ] ||
  [ "$stop_line" -ge "$quiesced_line" ]; then
  fail "verified digest must be durable before app stop and migration"
fi

migration_body="$({
  awk '
    /^migrate_verified_image\(\) \{/ { inside = 1; next }
    inside && /^}/ { exit }
    inside { print }
  ' "$remote_script"
})"
for forbidden_migration_construct in \
  gradlew \
  flywayMigrate \
  IMAGE_TAG \
  --volume \
  ' -v ' \
  '/opt/app/bin/app-bot '; do
  if printf '%s\n' "$migration_body" | grep -Fq -- "$forbidden_migration_construct"; then
    fail "verified-image migration contains forbidden construct: $forbidden_migration_construct"
  fi
done
if printf '%s\n' "$migration_body" | grep -Fq '/opt/app/bin/app-bot-migrate-java'; then
  fail "deployment bypasses the public fixed migration boundary"
fi
[ "$(printf '%s\n' "$migration_body" | grep -Fc 'compose_command run')" = "1" ] ||
  fail "verified-image migration must execute exactly once"
printf '%s\n' "$migration_body" | grep -Fq 'digest="$(state_value image_digest)"' ||
  fail "migration image must come from the verified maintenance digest"
require_quiesced_line="$(printf '%s\n' "$migration_body" | grep -n -m1 'require_phase "quiesced"' | cut -d: -f1)"
migrating_line="$(printf '%s\n' "$migration_body" | grep -n -m1 '"migrating"' | cut -d: -f1)"
run_line="$(printf '%s\n' "$migration_body" | grep -n -m1 'compose_command run' | cut -d: -f1)"
wait_line="$(printf '%s\n' "$migration_body" | grep -n -m1 'docker wait' | cut -d: -f1)"
safe_diagnostics_line="$(printf '%s\n' "$migration_body" | grep -n -m1 'capture_and_forward_safe_migration_diagnostics' | cut -d: -f1)"
exit_check_line="$(printf '%s\n' "$migration_body" | grep -n -m1 'migration_exit_code" != "0"' | cut -d: -f1)"
evidence_line="$(printf '%s\n' "$migration_body" | grep -n -m1 'printf.*migration_image_digest' | cut -d: -f1)"
migrated_line="$(printf '%s\n' "$migration_body" | grep -n -m1 '"migrated"' | cut -d: -f1)"
if [ -z "$require_quiesced_line" ] || [ -z "$migrating_line" ] || [ -z "$run_line" ] ||
  [ -z "$wait_line" ] || [ -z "$safe_diagnostics_line" ] || [ -z "$exit_check_line" ] ||
  [ -z "$evidence_line" ] || [ -z "$migrated_line" ] ||
  [ "$require_quiesced_line" -ge "$migrating_line" ] || [ "$migrating_line" -ge "$run_line" ] ||
  [ "$run_line" -ge "$wait_line" ] || [ "$wait_line" -ge "$safe_diagnostics_line" ] ||
  [ "$safe_diagnostics_line" -ge "$exit_check_line" ] ||
  [ "$exit_check_line" -ge "$evidence_line" ] || [ "$evidence_line" -ge "$migrated_line" ]; then
  fail "verified-image migration phase/evidence order changed"
fi
printf '%s\n' "$migration_body" | grep -Fq 'migration_reference" != "$digest"' ||
  fail "migration container reference is not checked against the verified digest"
printf '%s\n' "$migration_body" | grep -Fq 'migration_image_id" != "$expected_image_id"' ||
  fail "migration container image id is not checked against the verified image id"
printf '%s\n' "$migration_body" | grep -Fq 'trap cleanup_migration_container EXIT' ||
  fail "migration container has no failure cleanup"
printf '%s\n' "$migration_body" | grep -Fq 'migration_exit_code" =~ ^[0-9]+$' ||
  fail "migration exit status is not parsed as one strict numeric value"

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
  grep -Fq 'docker logs "$migration_container_id" >"$migration_log_file" 2>&1' ||
  fail "migration logs are not captured into restricted remote maintenance state"
printf '%s\n' "$safe_capture_body" | grep -Fq 'local migration_log_file="$lock_dir/migration-container.log"' ||
  fail "raw migration output is not retained under the protected maintenance lock"
printf '%s\n' "$safe_capture_body" | grep -Fq 'chmod 600 "$migration_log_file"' ||
  fail "retained migration output is not restricted to its remote owner"
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
printf '%s\n' "$safe_capture_body" | grep -Fq 'emit_safe_migration_diagnostics "$migration_log_file" "$migration_exit_code"' ||
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
