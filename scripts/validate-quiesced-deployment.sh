#!/usr/bin/env bash
set -euo pipefail

repository_root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
deploy_workflow="$repository_root/.github/workflows/deploy-ssh.yml"
migrate_workflow="$repository_root/.github/workflows/db-migrate.yml"
runner_script="$repository_root/scripts/deploy/quiesced-release.sh"
remote_script="$repository_root/scripts/deploy/remote-compose-release.sh"

fail() {
  echo "quiesced-deployment-contract: $1" >&2
  exit 1
}

for required_file in "$deploy_workflow" "$migrate_workflow" "$runner_script" "$remote_script"; do
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
if grep -Fq 'flywayMigrate' "$migrate_workflow"; then
  fail "db-migrate contains an unguarded standalone migration"
fi

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
  mark_remote_migrated \
  assert_remote_app_absent \
  start_remote_release \
  finish_remote_release)"
[ "$release_body" = "$expected_release_body" ] ||
  fail "release order must be prepare -> absent -> migrate -> absent -> start -> finish"

[ "$(grep -Fc 'flywayMigrate --no-parallel --console=plain' "$runner_script")" = "1" ] ||
  fail "runner must execute exactly one managed Flyway migration"
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
  'running app is not pinned to the verified digest'
  'http://127.0.0.1:8080/ready'
  'http://127.0.0.1:8080/health'
)
for contract in "${required_remote_contract[@]}"; do
  grep -Fq "$contract" "$remote_script" || fail "remote helper lacks: $contract"
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
start_line="$(printf '%s\n' "$start_body" | grep -n -m1 'compose_command up -d --no-deps app' | cut -d: -f1)"
image_line="$(printf '%s\n' "$start_body" | grep -n -m1 'running_reference=' | cut -d: -f1)"
ready_line="$(printf '%s\n' "$start_body" | grep -n -m1 '/ready' | cut -d: -f1)"
health_line="$(printf '%s\n' "$start_body" | grep -n -m1 '/health' | cut -d: -f1)"
if printf '%s\n' "$start_body" | grep -Fq 'promote_persistent_override'; then
  fail "durable verified digest must be promoted during quiesce, before migration"
fi
if [ -z "$first_absent_line" ] || [ -z "$start_line" ] || [ -z "$image_line" ] ||
  [ -z "$ready_line" ] || [ -z "$health_line" ] ||
  [ "$first_absent_line" -ge "$start_line" ] || [ "$start_line" -ge "$image_line" ] ||
  [ "$image_line" -ge "$ready_line" ] || [ "$ready_line" -ge "$health_line" ]; then
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

echo "quiesced-deployment-contract: OK"
