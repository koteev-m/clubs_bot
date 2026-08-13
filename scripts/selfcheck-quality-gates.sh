#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOW_YAML_VALIDATOR="$ROOT_DIR/scripts/validate-workflow-yaml.rb"
# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/sha256-portable.sh"
TMP_DIR="$(mktemp -d)"
DETEKT_DYNAMIC_MODULE_DIR="$ROOT_DIR/detekt-selfcheck-fixture"
DETEKT_DYNAMIC_MODULE_OWNED=0
DEPENDENCY_DYNAMIC_MODULE_DIR="$ROOT_DIR/dependency-selfcheck-fixture"
DEPENDENCY_DYNAMIC_MODULE_OWNED=0
DETEKT_PREPARE_LINK=""
DETEKT_PREPARE_LINK_OWNED=0

cleanup() {
  if [ "$DETEKT_PREPARE_LINK_OWNED" = "1" ] && [ -L "$DETEKT_PREPARE_LINK" ]; then
    rm -f "$DETEKT_PREPARE_LINK"
  fi
  if [ "$DETEKT_DYNAMIC_MODULE_OWNED" = "1" ]; then
    rm -rf "$DETEKT_DYNAMIC_MODULE_DIR"
  fi
  if [ "$DEPENDENCY_DYNAMIC_MODULE_OWNED" = "1" ]; then
    rm -rf "$DEPENDENCY_DYNAMIC_MODULE_DIR"
  fi
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

fail() {
  echo "selfcheck: $1" >&2
  exit 1
}

assert_empty() {
  local value="${1:-}"
  if [ -n "$value" ]; then
    fail "expected empty output, got: $value"
  fi
}

assert_eq() {
  local left="$1"
  local right="$2"
  if [ "$left" != "$right" ]; then
    fail "expected '$right', got '$left'"
  fi
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  if [[ "$haystack" != *"$needle"* ]]; then
    fail "expected output to contain '$needle', got: $haystack"
  fi
}

assert_not_contains() {
  local haystack="$1"
  local needle="$2"
  if [[ "$haystack" == *"$needle"* ]]; then
    fail "expected output not to contain '$needle', got: $haystack"
  fi
}

retry_log="$TMP_DIR/retry-failure.log"
if RETRY_MAX_ATTEMPTS=2 RETRY_DELAY_SECONDS=0 \
  "$ROOT_DIR/scripts/retry-command.sh" bash -c 'exit 7' >"$retry_log" 2>&1; then
  fail "expected retry-command.sh to propagate the final command failure"
else
  retry_status=$?
  assert_eq "$retry_status" "7"
fi
assert_contains "$(cat "$retry_log")" "Command failed after 2 attempts (exit 7)"

(
  cd "$TMP_DIR"
  git init -q
  git config user.email "selfcheck@example.com"
  git config user.name "selfcheck"

  mkdir -p src
  cat > src/App.kt <<'KOT'
fun main() = println("ok")
KOT
  git add src/App.kt
  git commit -q -m "root"

  root_out="$($ROOT_DIR/scripts/changed-kotlin-files.sh)"
  assert_eq "$root_out" "src/App.kt"

  empty_out="$(VERIFY_FROM_SHA=HEAD VERIFY_TO_SHA=HEAD "$ROOT_DIR/scripts/changed-kotlin-files.sh")"
  assert_empty "$empty_out"

  ktlint_skip_out="$(
    VERIFY_FROM_SHA=HEAD VERIFY_TO_SHA=HEAD KTLINT_REPO_DIR="$TMP_DIR" GRADLEW_BIN=__missing_gradlew__ \
      "$ROOT_DIR/scripts/ktlint-changed.sh"
  )"
  assert_contains "$ktlint_skip_out" "No changed Kotlin files"

  fake_gradlew="$TMP_DIR/fake-gradlew"
  fake_gradle_args="$TMP_DIR/fake-gradle-args"
  cat > "$fake_gradlew" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" > "$FAKE_GRADLE_ARGS_FILE"
exit "${FAKE_GRADLE_STATUS:-0}"
SH
  chmod +x "$fake_gradlew"
  cat > src/App.kt <<'KOT'
fun main() = println("updated")
KOT
  git add src/App.kt
  git commit -q -m "kotlin update"
  ktlint_out="$(
    VERIFY_FROM_SHA=HEAD~1 VERIFY_TO_SHA=HEAD KTLINT_REPO_DIR="$TMP_DIR" \
      GRADLEW_BIN="$fake_gradlew" FAKE_GRADLE_ARGS_FILE="$fake_gradle_args" \
      "$ROOT_DIR/scripts/ktlint-changed.sh"
  )"
  assert_contains "$ktlint_out" "running baseline-aware ktlintCheck"
  assert_eq "$(cat "$fake_gradle_args")" "ktlintCheck --console=plain"

  if VERIFY_FROM_SHA=HEAD~1 VERIFY_TO_SHA=HEAD KTLINT_REPO_DIR="$TMP_DIR" \
    GRADLEW_BIN="$fake_gradlew" FAKE_GRADLE_ARGS_FILE="$fake_gradle_args" FAKE_GRADLE_STATUS=9 \
    "$ROOT_DIR/scripts/ktlint-changed.sh" >/dev/null 2>&1; then
    fail "expected ktlint-changed.sh to propagate ktlintCheck failure"
  else
    ktlint_status=$?
    assert_eq "$ktlint_status" "9"
  fi

  echo "note" > README.md
  git add README.md
  git commit -q -m "non-kotlin"

  no_kotlin_out="$($ROOT_DIR/scripts/changed-kotlin-files.sh)"
  assert_empty "$no_kotlin_out"

  source_branch="$(git branch --show-current)"

  git checkout -q -b feature/merge-base
  cat > src/Feature.kt <<'KOT'
fun feature() = 1
KOT
  git add src/Feature.kt
  git commit -q -m "feature adds kotlin"
  feature_head="$(git rev-parse HEAD)"

  git checkout -q "$source_branch"
  cat > src/Mainline.kt <<'KOT'
fun mainline() = 2
KOT
  git add src/Mainline.kt
  git commit -q -m "mainline kotlin"

  merge_base_out="$(VERIFY_FROM_SHA="$feature_head" VERIFY_TO_SHA=HEAD "$ROOT_DIR/scripts/changed-kotlin-files.sh")"
  assert_eq "$merge_base_out" "src/Mainline.kt"

  rm src/Mainline.kt
  git add -A
  git commit -q -m "delete kotlin"
  deleted_out="$(VERIFY_FROM_SHA=HEAD~1 VERIFY_TO_SHA=HEAD "$ROOT_DIR/scripts/changed-kotlin-files.sh")"
  assert_empty "$deleted_out"
)

if DOCKER_BIN=__missing_docker__ "$ROOT_DIR/scripts/verify.sh" secret-scan; then
  fail "expected secret-scan to fail without docker"
else
  status=$?
  assert_eq "$status" "2"
fi

secret_contract_files="$TMP_DIR/secret-contract-files.txt"
{
  git -C "$ROOT_DIR" ls-files -- ".env.example" "scripts/*env*.sh"
  git -C "$ROOT_DIR" ls-files --others --exclude-standard -- ".env.example" "scripts/*env*.sh"
} | LC_ALL=C sort -u | while IFS= read -r relative_path; do
  if [ -f "$ROOT_DIR/$relative_path" ]; then
    printf '%s\n' "$relative_path"
  fi
done > "$secret_contract_files"

secret_contract_file_list="$(cat "$secret_contract_files")"
assert_contains "$secret_contract_file_list" "scripts/dev-env.example.sh"
assert_not_contains "$secret_contract_file_list" "scripts/dev-env.sh"

if ! git -C "$ROOT_DIR" check-ignore --no-index -q scripts/dev-env.sh; then
  fail "expected the local scripts/dev-env.sh to remain ignored"
fi
if git -C "$ROOT_DIR" check-ignore --no-index -q scripts/dev-env.example.sh; then
  fail "expected scripts/dev-env.example.sh to remain trackable"
fi

while IFS= read -r relative_path; do
  if LC_ALL=C grep -E -q '[0-9]{6,12}:[A-Za-z0-9_-]{30,}' "$ROOT_DIR/$relative_path"; then
    fail "tracked dev/example environment file contains a Telegram token literal: $relative_path"
  fi
  if grep -q 'gitleaks:allow' "$ROOT_DIR/$relative_path"; then
    fail "tracked dev/example environment file contains an inline gitleaks allow marker: $relative_path"
  fi
done < "$secret_contract_files"

for allowlist_path in .gitleaksignore .gitleaks.toml gitleaks.toml; do
  if [ -e "$ROOT_DIR/$allowlist_path" ]; then
    fail "unexpected gitleaks ignore/allowlist file: $allowlist_path"
  fi
done

fake_docker="$TMP_DIR/fake-docker"
fake_docker_args="$TMP_DIR/fake-docker-args.txt"
fake_docker_log="$TMP_DIR/fake-docker.log"
cat > "$fake_docker" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$FAKE_DOCKER_ARGS_FILE"
exit 23
SH
chmod +x "$fake_docker"

if FAKE_DOCKER_ARGS_FILE="$fake_docker_args" DOCKER_BIN="$fake_docker" \
  "$ROOT_DIR/scripts/verify.sh" secret-scan >"$fake_docker_log" 2>&1; then
  fail "expected secret-scan to propagate a started scanner failure"
else
  status=$?
  assert_eq "$status" "23"
fi

gitleaks_image="$(sed -n 's/^GITLEAKS_IMAGE=//p' "$ROOT_DIR/scripts/quality-gates.env")"
assert_contains "$gitleaks_image" "@sha256:"
fake_docker_arg_list="$(cat "$fake_docker_args")"
assert_contains "$fake_docker_arg_list" "$gitleaks_image"
assert_contains "$fake_docker_arg_list" "detect"
assert_contains "$fake_docker_arg_list" "--source"
assert_contains "$fake_docker_arg_list" "--redact"
assert_not_contains "$fake_docker_arg_list" "--exit-code 0"

usage_out="$("$ROOT_DIR/scripts/refresh-verification-metadata.sh" unknown 2>&1 || true)"
assert_contains "$usage_out" "Usage: scripts/refresh-verification-metadata.sh [default]"

verify_usage_out="$("$ROOT_DIR/scripts/verify.sh" unknown 2>&1 || true)"
assert_contains "$verify_usage_out" "Usage: scripts/verify.sh [full|ci|lint|secret-scan]"

dependency_guard_out="$("$ROOT_DIR/gradlew" dependencyGuard --console=plain)"
for module in app-bot core-data core-domain core-security core-telemetry core-testing tools:perf; do
  assert_contains "$dependency_guard_out" "Task :${module}:dependencyGuard"
done
assert_not_contains "$dependency_guard_out" "0 artifacts checked"

detekt_task_graph="$("$ROOT_DIR/gradlew" detekt --dry-run --console=plain)"
detekt_analysis_count="$({ printf '%s\n' "$detekt_task_graph" | awk '
  /^:[^ ]+:detekt[^ ]+ SKIPPED$/ && $0 !~ /:recordDetekt/ { count++ }
  END { print count + 0 }
'; })"
detekt_status_count="$({ printf '%s\n' "$detekt_task_graph" | awk '
  /^:[^ ]+:recordDetekt[^ ]+SarifStatus SKIPPED$/ { count++ }
  END { print count + 0 }
'; })"
if [ "$detekt_analysis_count" -le 0 ] || [ "$detekt_analysis_count" -ne "$detekt_status_count" ]; then
  fail "dynamic Detekt analysis/status task graph is empty or incomplete"
fi

ktlint_buildsrc_probe="$TMP_DIR/ktlint-buildsrc-coverage.init.gradle"
cat > "$ktlint_buildsrc_probe" <<'GRADLE'
gradle.projectsEvaluated {
    def root = gradle.rootProject
    def ktlintTask = root.tasks.findByName("runKtlintCheckOverKotlinScripts")
    if (ktlintTask != null) {
        def expectedSources = root.fileTree("buildSrc/src/main/kotlin") {
            include "**/*.kt"
        }.files
        def missingSources = expectedSources - ktlintTask.source.files
        if (!missingSources.isEmpty()) {
            throw new GradleException(
                "Root ktlint contract does not cover buildSrc Kotlin sources: " +
                    missingSources.collect { root.relativePath(it) }.sort().join(", ")
            )
        }
        println "quality-gate: buildSrc ktlint coverage verified"
    }
}
GRADLE
ktlint_buildsrc_probe_out="$(
  "$ROOT_DIR/gradlew" help --no-configuration-cache \
    --init-script "$ktlint_buildsrc_probe" --console=plain
)"
assert_contains "$ktlint_buildsrc_probe_out" "quality-gate: buildSrc ktlint coverage verified"

logs_policy_fixture="$TMP_DIR/logs-policy-fixture"
logs_policy_report="$TMP_DIR/logs-policy-report.txt"
logs_policy_safe_log="$TMP_DIR/logs-policy-safe.log"
logs_policy_match_log="$TMP_DIR/logs-policy-match.log"
logs_policy_rg_error_log="$TMP_DIR/logs-policy-rg-error.log"
missing_ripgrep="$TMP_DIR/__missing_rg__"
fake_ripgrep="$TMP_DIR/fake-rg"
logs_policy_probe="$TMP_DIR/logs-policy-fallback.init.gradle"

mkdir -p \
  "$logs_policy_fixture/.hidden" \
  "$logs_policy_fixture/main" \
  "$logs_policy_fixture/src/test" \
  "$logs_policy_fixture/dist" \
  "$logs_policy_fixture/node_modules/example"
cat > "$logs_policy_fixture/main/Safe.kt" <<'KOT'
logger.info("bookingId={}", bookingId)
KOT
cat > "$logs_policy_fixture/src/test/Ignored.kt" <<'KOT'
logger.info("qr={}", rawQr)
KOT
cat > "$logs_policy_fixture/dist/ignored.js" <<'JS'
logger.info("qr={}", rawQr)
JS
cat > "$logs_policy_fixture/node_modules/example/ignored.ts" <<'TS'
logger.info("qr={}", rawQr)
TS
cat > "$logs_policy_fixture/main/Ignored.txt" <<'TXT'
logger.info("qr={}", rawQr)
TXT

cat > "$logs_policy_probe" <<'GRADLE'
gradle.projectsEvaluated {
    def root = gradle.rootProject
    def appBot = root.findProject(":app-bot")
    if (appBot != null) {
        def scanTask = appBot.tasks.findByName("checkLogsPolicy")
        def sourceDir = System.getProperty("logsPolicySelfcheckSourceDir")
        def reportPath = System.getProperty("logsPolicySelfcheckReport")
        def executable = System.getProperty("logsPolicySelfcheckExecutable")
        if (scanTask == null || sourceDir == null || reportPath == null || executable == null) {
            throw new GradleException("Logs policy self-check probe is not configured")
        }
        scanTask.sourceDirs.setFrom(root.file(sourceDir))
        scanTask.reportFile.set(root.file(reportPath))
        scanTask.ripgrepExecutable.set(executable)
        println "quality-gate: logs policy fallback probe configured"
    }
}
GRADLE

run_logs_policy_fixture() {
  local executable="$1"
  local log_file="$2"
  "$ROOT_DIR/gradlew" \
    --no-configuration-cache \
    --rerun-tasks \
    -DlogsPolicySelfcheckSourceDir="$logs_policy_fixture" \
    -DlogsPolicySelfcheckReport="$logs_policy_report" \
    -DlogsPolicySelfcheckExecutable="$executable" \
    --init-script "$logs_policy_probe" \
    :app-bot:checkLogsPolicy \
    -x :app-bot:test \
    --console=plain >"$log_file" 2>&1
}

if ! run_logs_policy_fixture "$missing_ripgrep" "$logs_policy_safe_log"; then
  fail "expected logs policy JVM fallback to pass for safe and excluded sources"
fi
assert_contains "$(cat "$logs_policy_safe_log")" "repository-native JVM fallback"
assert_empty "$(cat "$logs_policy_report")"

cat > "$logs_policy_fixture/.hidden/Unsafe.kt" <<'KOT'
logger.info("qr={}", rawQr)
KOT
if run_logs_policy_fixture "$missing_ripgrep" "$logs_policy_match_log"; then
  fail "expected logs policy JVM fallback to reject an included SEC-02 violation"
fi
assert_contains "$(cat "$logs_policy_match_log")" "repository-native JVM fallback"
assert_contains "$(cat "$logs_policy_match_log")" "Logs policy check failed"
assert_contains "$(cat "$logs_policy_report")" ".hidden/Unsafe.kt:1:"

cat > "$fake_ripgrep" <<'SH'
#!/usr/bin/env bash
echo "synthetic ripgrep failure" >&2
exit 2
SH
chmod +x "$fake_ripgrep"
if run_logs_policy_fixture "$fake_ripgrep" "$logs_policy_rg_error_log"; then
  fail "expected a started ripgrep process error to remain fail-closed"
fi
assert_not_contains "$(cat "$logs_policy_rg_error_log")" "repository-native JVM fallback"
assert_contains "$(cat "$logs_policy_rg_error_log")" "ripgrep failed with exit code 2"
assert_contains "$(cat "$logs_policy_report")" "synthetic ripgrep failure"

assert_exact_line() {
  local file="$1"
  local expected="$2"
  local count
  count="$(awk -v expected="$expected" '$0 == expected { count++ } END { print count + 0 }' "$file")"
  if [ "$count" -ne 1 ]; then
    fail "expected one exact line in $file: $expected"
  fi
}

assert_exact_line_count() {
  local file="$1"
  local expected="$2"
  local expected_count="$3"
  local actual_count
  actual_count="$(
    awk -v expected="$expected" '$0 == expected { count++ } END { print count + 0 }' "$file"
  )"
  if [ "$actual_count" -ne "$expected_count" ]; then
    fail "expected $expected_count exact lines in $file, found $actual_count: $expected"
  fi
}

assert_step_line() {
  local file="$1"
  local step_name="$2"
  local expected="$3"
  if ! awk -v target="      - name: $step_name" -v expected="$expected" '
    $0 == target { in_step = 1; steps++; next }
    in_step && /^      - / { in_step = 0 }
    in_step && $0 == expected { matches++ }
    END { exit !(steps == 1 && matches == 1) }
  ' "$file"; then
    fail "step contract changed in $file: $step_name"
  fi
}

assert_step_with_line() {
  local file="$1"
  local step_name="$2"
  local expected="$3"
  if ! awk -v target="      - name: $step_name" -v expected="$expected" '
    function indentation(line, prefix) {
      prefix = line
      sub(/[^ ].*$/, "", prefix)
      return length(prefix)
    }
    function is_content(line) {
      return line !~ /^[[:space:]]*($|#)/
    }
    $0 == target {
      in_step = 1
      steps++
      next
    }
    in_step && is_content($0) && indentation($0) <= 6 {
      in_step = 0
      in_with = 0
    }
    in_step && $0 == "        with:" {
      in_with = 1
      with_blocks++
      next
    }
    in_with && is_content($0) && indentation($0) <= 8 {
      in_with = 0
    }
    in_with && $0 == expected { matches++ }
    END { exit !(steps == 1 && with_blocks == 1 && matches == 1) }
  ' "$file"; then
    fail "step with-contract changed in $file: $step_name"
  fi
}

assert_job_line() {
  local file="$1"
  local job_name="$2"
  local expected="$3"
  if ! awk -v target="  $job_name:" -v expected="$expected" '
    $0 == target { in_job = 1; jobs++; next }
    in_job && /^  [[:alnum:]_-]+:[[:space:]]*$/ { in_job = 0 }
    in_job && $0 == expected { matches++ }
    END { exit !(jobs == 1 && matches == 1) }
  ' "$file"; then
    fail "job contract changed in $file: $job_name"
  fi
}

normalize_step_with_contract() {
  local file="$1"
  local job_name="$2"
  local step_name="$3"
  awk \
    -v job_target="  $job_name:" \
    -v step_target="      - name: $step_name" '
    function indentation(line, prefix) {
      prefix = line
      sub(/[^ ].*$/, "", prefix)
      return length(prefix)
    }
    function is_content(line) {
      return line !~ /^[[:space:]]*($|#)/
    }
    $0 == job_target {
      in_job = 1
      jobs++
      next
    }
    in_job && /^  [[:alnum:]_-]+:[[:space:]]*$/ {
      in_job = 0
      in_step = 0
      in_with = 0
    }
    in_job && $0 == step_target {
      in_step = 1
      steps++
      next
    }
    in_step && is_content($0) && indentation($0) <= 6 {
      in_step = 0
      in_with = 0
    }
    in_step && $0 == "        with:" {
      in_with = 1
      with_blocks++
      next
    }
    in_with && is_content($0) && indentation($0) <= 8 {
      in_with = 0
    }
    in_with && is_content($0) {
      normalized = $0
      sub(/^[[:space:]]+/, "", normalized)
      print normalized
    }
    END {
      if (jobs != 1 || steps != 1 || with_blocks != 1) {
        exit 42
      }
    }
  ' "$file"
}

assert_step_with_contract() {
  local file="$1"
  local job_name="$2"
  local step_name="$3"
  local expected="$4"
  local actual
  if ! actual="$(normalize_step_with_contract "$file" "$job_name" "$step_name")"; then
    fail "step with-contract is missing or ambiguous in $file: $job_name/$step_name"
  fi
  if [ "$actual" != "$expected" ]; then
    fail "step with-contract changed in $file: $job_name/$step_name"
  fi
}

normalize_step_run_contract() {
  local file="$1"
  local job_name="$2"
  local step_name="$3"
  awk \
    -v job_target="  $job_name:" \
    -v step_target="      - name: $step_name" '
    function indentation(line, indent) {
      indent = line
      sub(/[^ ].*$/, "", indent)
      return length(indent)
    }
    function is_content(line) {
      return line !~ /^[[:space:]]*($|#)/
    }
    $0 == job_target {
      in_job = 1
      jobs++
      next
    }
    in_job && /^  [[:alnum:]_-]+:[[:space:]]*$/ {
      in_job = 0
      in_step = 0
      in_run = 0
    }
    in_job && $0 == step_target {
      in_step = 1
      steps++
      next
    }
    in_step && is_content($0) && indentation($0) <= 6 {
      in_step = 0
      in_run = 0
    }
    in_step && $0 == "        run: |" {
      in_run = 1
      run_blocks++
      next
    }
    in_run && is_content($0) && indentation($0) <= 8 {
      in_run = 0
    }
    in_run && is_content($0) {
      normalized = $0
      sub(/^[[:space:]]+/, "", normalized)
      print normalized
    }
    END {
      if (jobs != 1 || steps != 1 || run_blocks != 1) {
        exit 42
      }
    }
  ' "$file"
}

normalize_anchored_step_run_contract() {
  local file="$1"
  local anchor_name="$2"
  local step_name="$3"
  awk \
    -v anchor_target="      - &$anchor_name" \
    -v name_target="        name: \"$step_name\"" '
    function indentation(line, indent) {
      indent = line
      sub(/[^ ].*$/, "", indent)
      return length(indent)
    }
    function is_content(line) {
      return line !~ /^[[:space:]]*($|#)/
    }
    $0 == anchor_target {
      in_step = 1
      anchors++
      next
    }
    in_step && /^      - / {
      in_step = 0
      in_run = 0
    }
    in_step && $0 == name_target {
      names++
    }
    in_step && $0 ~ /^        (if|continue-on-error):/ {
      forbidden_keys++
    }
    in_step && $0 == "        run: |" {
      in_run = 1
      run_blocks++
      next
    }
    in_run && is_content($0) && indentation($0) <= 8 {
      in_run = 0
    }
    in_run && is_content($0) {
      normalized = $0
      sub(/^[[:space:]]+/, "", normalized)
      print normalized
    }
    END {
      if (anchors != 1 || names != 1 || run_blocks != 1 || forbidden_keys != 0) {
        exit 42
      }
    }
  ' "$file"
}

assert_step_run_contract() {
  local file="$1"
  local job_name="$2"
  local step_name="$3"
  local expected="$4"
  local actual
  if ! actual="$(normalize_step_run_contract "$file" "$job_name" "$step_name")"; then
    fail "step run-contract is missing or ambiguous in $file: $job_name/$step_name"
  fi
  if [ "$actual" != "$expected" ]; then
    fail "step run-contract changed in $file: $job_name/$step_name"
  fi
}

validate_packaged_launcher() {
  local launcher_file="$1"
  local expected_main_class="io.ktor.server.netty.EngineMain"
  local forbidden_main_class="com.example.bot.ApplicationKt"

  if [ ! -f "$launcher_file" ]; then
    echo "packaged launcher is missing: $launcher_file" >&2
    return 1
  fi
  if [ ! -r "$launcher_file" ]; then
    echo "packaged launcher is not readable: $launcher_file" >&2
    return 1
  fi

  if ! awk \
    -v expected="$expected_main_class" \
    -v forbidden="$forbidden_main_class" '
      {
        normalized = $0
        sub(/^[[:space:]]+/, "", normalized)
        lower = tolower(normalized)
        if (substr(normalized, 1, 1) == "#") {
          next
        }
        if (substr(normalized, 1, 2) == "::") {
          next
        }
        if (lower ~ /^@?rem([[:space:]]|$)/) {
          next
        }

        if (index($0, forbidden) > 0) {
          forbidden_found = 1
        }
        for (field = 1; field <= NF; field++) {
          if ($field == expected) {
            expected_found = 1
          }
        }
      }
      END { exit !(expected_found && !forbidden_found) }
    ' "$launcher_file"; then
    echo "packaged launcher main-class contract failed: $launcher_file" >&2
    return 1
  fi
}

validate_packaged_migration_launcher() {
  local boundary_file="$1"
  local launcher_file="$2"
  local expected_main_class="com.example.bot.tools.QuiescedMigrateMainKt"

  if [ ! -f "$boundary_file" ] || [ ! -r "$boundary_file" ] || [ ! -x "$boundary_file" ]; then
    echo "fixed migration boundary is missing, unreadable or non-executable: $boundary_file" >&2
    return 1
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
    grep -Fq -- "$boundary_contract" "$boundary_file" || {
      echo "fixed migration boundary lacks: $boundary_contract" >&2
      return 1
    }
  done
  if grep -Eq 'QuiescedMigrateMainKt|EngineMain|ApplicationKt|\$@|\$\*' "$boundary_file"; then
    echo "fixed migration boundary bypasses its private launcher contract: $boundary_file" >&2
    return 1
  fi

  if [ ! -f "$launcher_file" ] || [ ! -r "$launcher_file" ] || [ ! -x "$launcher_file" ]; then
    echo "private packaged migration launcher is missing, unreadable or non-executable: $launcher_file" >&2
    return 1
  fi
  grep -Fq "$expected_main_class" "$launcher_file" || {
    echo "packaged migration launcher main class is missing: $launcher_file" >&2
    return 1
  }
  if grep -Eq 'io\.ktor\.server\.netty\.EngineMain|com\.example\.bot\.ApplicationKt' "$launcher_file"; then
    echo "packaged migration launcher references application startup: $launcher_file" >&2
    return 1
  fi
}

assert_step_direct_key_line() {
  local file="$1"
  local job_name="$2"
  local step_name="$3"
  local key="$4"
  local expected="$5"
  if ! awk \
    -v job_target="  $job_name:" \
    -v step_target="      - name: $step_name" \
    -v prefix="        $key:" \
    -v expected="$expected" '
      function indentation(line, indent) {
        indent = line
        sub(/[^ ].*$/, "", indent)
        return length(indent)
      }
      function is_content(line) {
        return line !~ /^[[:space:]]*($|#)/
      }
      $0 == job_target {
        in_job = 1
        jobs++
        next
      }
      in_job && /^  [[:alnum:]_-]+:[[:space:]]*$/ {
        in_job = 0
        in_step = 0
      }
      in_job && $0 == step_target {
        in_step = 1
        steps++
        next
      }
      in_step && is_content($0) && indentation($0) <= 6 {
        in_step = 0
      }
      in_step && index($0, prefix) == 1 {
        keys++
        if ($0 == expected) {
          matches++
        }
      }
      END { exit !(jobs == 1 && steps == 1 && keys == 1 && matches == 1) }
    ' "$file"; then
    fail "direct '$key' contract changed in step '$job_name/$step_name' in $file"
  fi
}

assert_step_has_no_direct_key() {
  local file="$1"
  local job_name="$2"
  local step_name="$3"
  local key="$4"
  if ! awk \
    -v job_target="  $job_name:" \
    -v step_target="      - name: $step_name" \
    -v prefix="        $key:" '
      function indentation(line, indent) {
        indent = line
        sub(/[^ ].*$/, "", indent)
        return length(indent)
      }
      function is_content(line) {
        return line !~ /^[[:space:]]*($|#)/
      }
      $0 == job_target {
        in_job = 1
        jobs++
        next
      }
      in_job && /^  [[:alnum:]_-]+:[[:space:]]*$/ {
        in_job = 0
        in_step = 0
      }
      in_job && $0 == step_target {
        in_step = 1
        steps++
        next
      }
      in_step && is_content($0) && indentation($0) <= 6 {
        in_step = 0
      }
      in_step && index($0, prefix) == 1 {
        keys++
      }
      END { exit !(jobs == 1 && steps == 1 && keys == 0) }
    ' "$file"; then
    fail "step '$job_name/$step_name' must not define direct key '$key' in $file"
  fi
}

assert_job_direct_key_line() {
  local file="$1"
  local job_name="$2"
  local key="$3"
  local expected="$4"
  if ! awk \
    -v target="  $job_name:" \
    -v prefix="    $key:" \
    -v expected="$expected" '
      $0 == target {
        in_job = 1
        jobs++
        next
      }
      in_job && /^  [[:alnum:]_-]+:[[:space:]]*$/ {
        in_job = 0
      }
      in_job && index($0, prefix) == 1 {
        keys++
        if ($0 == expected) {
          matches++
        }
      }
      END { exit !(jobs == 1 && keys == 1 && matches == 1) }
    ' "$file"; then
    fail "direct '$key' contract changed in job '$job_name' in $file"
  fi
}

assert_job_has_no_direct_key() {
  local file="$1"
  local job_name="$2"
  local key="$3"
  if ! awk \
    -v target="  $job_name:" \
    -v prefix="    $key:" '
      $0 == target {
        in_job = 1
        jobs++
        next
      }
      in_job && /^  [[:alnum:]_-]+:[[:space:]]*$/ {
        in_job = 0
      }
      in_job && index($0, prefix) == 1 {
        keys++
      }
      END { exit !(jobs == 1 && keys == 0) }
    ' "$file"; then
    fail "job '$job_name' must not define direct key '$key' in $file"
  fi
}

assert_step_uses_sha_pinned_action() {
  local file="$1"
  local step_name="$2"
  local action_name="$3"
  if ! awk \
    -v target="      - name: $step_name" \
    -v prefix="        uses: $action_name@" '
      $0 == target { in_step = 1; steps++; next }
      in_step && /^      - / { in_step = 0 }
      in_step && index($0, prefix) == 1 {
        uses++
        sha = substr($0, length(prefix) + 1)
        sub(/[[:space:]].*$/, "", sha)
        if (length(sha) == 40 && sha !~ /[^0-9a-f]/) {
          valid_pins++
        }
      }
      END { exit !(steps == 1 && uses == 1 && valid_pins == 1) }
    ' "$file"; then
    fail "$action_name must remain SHA-pinned in step '$step_name' in $file"
  fi
}

assert_sha_pinned_action() {
  local file="$1"
  local action_name="$2"
  local sha
  sha="$(sed -nE "s|^[[:space:]]*uses: ${action_name}@([0-9a-f]+).*|\\1|p" "$file")"
  case "$sha" in
    ""|*[!0-9a-f]*)
      fail "$action_name must remain SHA-pinned in $file"
      ;;
  esac
  if [ "${#sha}" -ne 40 ]; then
    fail "$action_name must use a full commit SHA in $file"
  fi
}

validate_metadata_wiring() {
  local file="$1"
  local build_step_name="$2"
  assert_step_uses_sha_pinned_action \
    "$file" \
    "Extract metadata (tags, labels)" \
    "docker/metadata-action"
  assert_step_line "$file" "Extract metadata (tags, labels)" "        id: meta"
  assert_step_uses_sha_pinned_action \
    "$file" \
    "$build_step_name" \
    "docker/build-push-action"
  assert_step_with_line "$file" "$build_step_name" "          tags: $metadata_tags_expression"
  assert_step_with_line "$file" "$build_step_name" "          labels: $metadata_labels_expression"
}

validate_publish_provenance_job_guard() {
  local file="$1"
  assert_job_line "$file" "verify-and-provenance" "    if: $non_pr_if"
}

normalize_top_level_permissions_contract() {
  local file="$1"
  awk '
    $0 == "permissions:" {
      in_permissions = 1
      blocks++
      next
    }
    in_permissions && /^[^[:space:]]/ {
      in_permissions = 0
    }
    in_permissions && /^[[:space:]]/ {
      normalized = $0
      sub(/^[[:space:]]+/, "", normalized)
      if (normalized !~ /^($|#)/) {
        print normalized
      }
    }
    END {
      if (blocks != 1) {
        exit 42
      }
    }
  ' "$file"
}

normalize_top_level_trigger_contract() {
  local file="$1"
  awk '
    $0 == "on:" {
      in_triggers = 1
      blocks++
      next
    }
    in_triggers && /^[^[:space:]]/ {
      in_triggers = 0
    }
    in_triggers && /^[[:space:]]/ {
      normalized = $0
      sub(/^  /, "", normalized)
      if (normalized !~ /^[[:space:]]*($|#)/) {
        print normalized
      }
    }
    END {
      if (blocks != 1) {
        exit 42
      }
    }
  ' "$file"
}

validate_detekt_report_manifest() {
  local manifest_file="$1"
  python3 - "$manifest_file" "$ROOT_DIR" <<'PY'
from pathlib import Path
import sys

probe_path = Path(sys.argv[1])
root = Path(sys.argv[2]).absolute()


def reject(message):
    print(f"detekt-report-contract: {message}", file=sys.stderr)
    raise SystemExit(1)


try:
    lines = probe_path.read_text(encoding="utf-8").splitlines()
except OSError as error:
    reject(f"cannot read dynamic task probe: {error}")
if not lines:
    reject("dynamic Detekt task inventory is empty")

rows = []
for line_number, line in enumerate(lines, start=1):
    fields = line.split("|")
    if len(fields) != 7:
        reject(f"line {line_number} must contain seven fields")
    project, task, html_path, txt_path, sarif_path, sarif_required, enabled = fields
    if project != ":" and not project.startswith(":"):
        reject(f"line {line_number} project path is invalid")
    if (
        not task
        or task == "detekt"
        or task in {".", ".."}
        or ".." in task
        or any(separator in task for separator in ("/", "\\", ":"))
    ):
        reject(f"line {line_number} task name is unsafe or aggregate")
    module_path = project.lstrip(":").replace(":", "/")
    prefix = f"{module_path}/" if module_path else ""
    expected_paths = {
        "html": root / f"{prefix}build/reports/detekt/{task}/detekt.html",
        "txt": root / f"{prefix}build/reports/detekt/{task}/detekt.txt",
        "sarif": root / f"{prefix}build/reports/detekt/{task}/detekt.sarif",
    }
    for report_type, report_path in (
        ("html", html_path),
        ("txt", txt_path),
        ("sarif", sarif_path),
    ):
        if Path(report_path).absolute() != expected_paths[report_type]:
            reject(f"{project}:{task} {report_type} output is not the fixed managed path")
    if sarif_required != "true":
        reject(f"{project}:{task} does not require SARIF")
    if enabled != "true":
        reject(f"{project}:{task} is unexpectedly disabled")
    rows.append((project, task, html_path, txt_path, sarif_path))

identities = [(row[0], row[1]) for row in rows]
if len(identities) != len(set(identities)):
    reject("dynamic Detekt task identities are duplicated")
for report_index, report_type in ((2, "html"), (3, "txt"), (4, "sarif")):
    paths = [row[report_index].replace("\\", "/") for row in rows]
    if len(paths) != len(set(paths)):
        reject(f"{report_type} outputs overlap between Detekt tasks")
PY
}

validate_detekt_expected_manifest() {
  local manifest_file="$1"
  local probe_manifest="$2"
  python3 - "$manifest_file" "$probe_manifest" "$ROOT_DIR" <<'PY'
import json
from pathlib import Path
import sys

manifest_path = Path(sys.argv[1])
probe_path = Path(sys.argv[2])
root = Path(sys.argv[3]).resolve()


def reject(message):
    print(f"detekt-expected-manifest: {message}", file=sys.stderr)
    raise SystemExit(1)


def module_path(project_path):
    if project_path == ":":
        return ""
    if not project_path.startswith(":"):
        reject(f"invalid project path: {project_path}")
    parts = project_path.removeprefix(":").split(":")
    if any(
        not part
        or part in {".", ".."}
        or ".." in part
        or "/" in part
        or "\\" in part
        for part in parts
    ):
        reject(f"invalid project path: {project_path}")
    return "/".join(parts)


def identity(task_name):
    if task_name.startswith("detekt") and len(task_name) > len("detekt"):
        suffix = task_name[len("detekt"):]
        return suffix[0].lower() + suffix[1:]
    return task_name


def contract(project_path, task_name):
    module = module_path(project_path)
    prefix = f"{module}/" if module else ""
    task_path = f":{task_name}" if project_path == ":" else f"{project_path}:{task_name}"
    report_path = f"{prefix}build/reports/detekt/{task_name}/detekt.sarif"
    category = f"detekt/{module or 'root'}/{identity(task_name)}"
    status_path = f"{prefix}build/reports/detekt/status/{task_name}.json"
    return task_path, report_path, category, status_path


try:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
except (OSError, UnicodeError, json.JSONDecodeError) as error:
    reject(f"cannot parse manifest: {error}")
if not isinstance(manifest, dict):
    reject("manifest root must be an object")
if set(manifest) != {"schema", "version", "entries"}:
    reject("manifest root fields changed")
if manifest.get("schema") != "clubs-bot/detekt-sarif-manifest":
    reject("manifest schema changed")
if manifest.get("version") != 2:
    reject("manifest version changed")

dynamic_tasks = []
for line_number, line in enumerate(probe_path.read_text(encoding="utf-8").splitlines(), 1):
    fields = line.split("|")
    if len(fields) != 7:
        reject(f"probe line {line_number} is malformed")
    project_path, task_name = fields[0], fields[1]
    dynamic_tasks.append((project_path, task_name))
if not dynamic_tasks or len(dynamic_tasks) != len(set(dynamic_tasks)):
    reject("dynamic task inventory is empty or duplicated")

status_keys = {
    "schema", "version", "taskPath", "projectPath", "taskName", "reportPath",
    "category", "sourceCount", "state", "taskExecuted", "taskSkipped",
    "taskSkipMessage", "taskNoSource", "taskFailed", "reportExists",
}
expected_entries = []
expected_status_paths = []
states = {}
for project_path, task_name in sorted(dynamic_tasks):
    task_path, report_path, category, status_path = contract(project_path, task_name)
    expected_status_paths.append(status_path)
    try:
        status = json.loads((root / status_path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        reject(f"missing or malformed execution status for {task_path}: {error}")
    if not isinstance(status, dict) or set(status) != status_keys:
        reject(f"status fields changed for {task_path}")
    expected_identity = {
        "taskPath": task_path,
        "projectPath": project_path,
        "taskName": task_name,
        "reportPath": report_path,
        "category": category,
    }
    if any(status.get(key) != value for key, value in expected_identity.items()):
        reject(f"status identity changed for {task_path}")
    if status.get("schema") != "clubs-bot/detekt-sarif-status" or status.get("version") != 1:
        reject(f"status schema changed for {task_path}")
    source_count = status.get("sourceCount")
    if not isinstance(source_count, int) or isinstance(source_count, bool) or source_count < 0:
        reject(f"status sourceCount is invalid for {task_path}")
    if status.get("taskExecuted") is not True:
        reject(f"task did not execute for {task_path}")
    for key in ("taskSkipped", "taskNoSource", "taskFailed", "reportExists"):
        if not isinstance(status.get(key), bool):
            reject(f"status {key} is invalid for {task_path}")
    task_skip_message = status.get("taskSkipMessage")
    if not isinstance(task_skip_message, str):
        reject(f"status taskSkipMessage is invalid for {task_path}")
    state = status.get("state")
    states[task_path] = state
    if state == "NO_SOURCE":
        if (
            source_count != 0
            or status.get("taskSkipped") is not True
            or status.get("taskNoSource") is not True
            or status.get("taskFailed") is not False
            or status.get("reportExists") is not False
            or (root / report_path).exists()
        ):
            reject(f"NO_SOURCE status is inconsistent for {task_path}")
        continue
    if state != "REPORT_REQUIRED":
        reject(f"task status is incomplete for {task_path}")
    valid_incremental_skip = (
        status.get("taskSkipped") is True
        and (
            task_skip_message == "FROM-CACHE"
            or (
                task_skip_message == "UP-TO-DATE"
                and status.get("reportExists") is True
            )
        )
    )
    if (
        source_count <= 0
        or status.get("taskNoSource") is not False
        or (status.get("taskSkipped") is True and not valid_incremental_skip)
        or status.get("reportExists") is not True
    ):
        reject(f"required report status is inconsistent for {task_path}")
    if not (root / report_path).is_file():
        reject(f"required report is missing for {task_path}")
    expected_entries.append({
        **expected_identity,
        "sourceCount": source_count,
    })

actual_status_paths = sorted(
    path.relative_to(root).as_posix()
    for path in root.glob("**/build/reports/detekt/status/*.json")
)
if actual_status_paths != sorted(expected_status_paths):
    reject("status inventory is incomplete or contains stale tasks")
expected_entries.sort(key=lambda entry: entry["taskPath"])
entries = manifest.get("entries")
if entries != expected_entries:
    reject("manifest entries do not match post-execution Detekt statuses")
if len(entries) != 13:
    reject("current applicable Detekt task snapshot is not 13 entries")
if states.get(":tools:perf:detektTest") != "NO_SOURCE":
    reject(":tools:perf:detektTest is not proven NO_SOURCE")
for key in ("taskPath", "reportPath", "category"):
    values = [entry[key] for entry in entries]
    if len(values) != len(set(values)):
        reject(f"manifest {key} values are not unique")
if [entry["taskPath"] for entry in entries] != sorted(entry["taskPath"] for entry in entries):
    reject("manifest entries are not sorted")
for entry in entries:
    report_path = entry["reportPath"]
    if (
        Path(report_path).is_absolute()
        or "\\" in report_path
        or "." in Path(report_path).parts
        or ".." in Path(report_path).parts
    ):
        reject(f"manifest reportPath is not normalized: {report_path}")
    if entry["category"].endswith("/"):
        reject("manifest base category must not contain a trailing run component")
PY
}

assert_detekt_step_order() {
  local file="$1"
  if ! awk '
    $0 == "  detekt:" { in_job = 1; jobs++; next }
    in_job && /^  [[:alnum:]_-]+:[[:space:]]*$/ { in_job = 0 }
    in_job && $0 == "      - name: Prepare Detekt SARIF" { prepare = NR; prepares++ }
    in_job && $0 == "      - name: Run detekt" { scanner = NR; scanners++ }
    in_job && $0 == "      - name: Finalize Detekt SARIF manifest" { finalizer = NR; finalizers++ }
    in_job && $0 == "      - name: Collect Detekt SARIF" { collector = NR; collectors++ }
    in_job && $0 == "      - name: Upload detekt HTML report" { html = NR; htmls++ }
    in_job && $0 == "      - name: Upload detekt SARIF to Code Scanning" { upload = NR; uploads++ }
    END {
      valid = jobs == 1 && prepares == 1 && scanners == 1 && finalizers == 1 && collectors == 1 && htmls == 1 && uploads == 1 && prepare < scanner && scanner < finalizer && finalizer < collector && collector < html && html < upload
      exit !valid
    }
  ' "$file"; then
    fail "Detekt prepare/scanner/finalizer/collector/upload step order changed in $file"
  fi
}

validate_detekt_static_workflow() {
  local file="$1"
  local permissions_contract

  if ! permissions_contract="$(normalize_top_level_permissions_contract "$file")"; then
    fail "top-level permissions contract is missing or ambiguous in $file"
  fi
  if [ "$permissions_contract" != "$detekt_permissions_contract" ]; then
    fail "top-level permissions contract changed in $file"
  fi

  assert_job_has_no_direct_key "$file" "detekt" "continue-on-error"
  assert_job_has_no_direct_key "$file" "detekt" "if"
  assert_detekt_step_order "$file"

  assert_step_direct_key_line \
    "$file" \
    "detekt" \
    "Prepare Detekt SARIF" \
    "run" \
    "        run: ./gradlew prepareDetektSarif --no-configuration-cache --console=plain"
  assert_step_has_no_direct_key "$file" "detekt" "Prepare Detekt SARIF" "if"
  assert_step_has_no_direct_key \
    "$file" \
    "detekt" \
    "Prepare Detekt SARIF" \
    "continue-on-error"

  assert_step_direct_key_line \
    "$file" \
    "detekt" \
    "Run detekt" \
    "run" \
    "        run: ./gradlew detekt --continue --no-configuration-cache --console=plain"
  assert_step_has_no_direct_key "$file" "detekt" "Run detekt" "if"
  assert_step_has_no_direct_key "$file" "detekt" "Run detekt" "continue-on-error"

  assert_step_direct_key_line \
    "$file" \
    "detekt" \
    "Finalize Detekt SARIF manifest" \
    "if" \
    "        if: always()"
  assert_step_direct_key_line \
    "$file" \
    "detekt" \
    "Finalize Detekt SARIF manifest" \
    "run" \
    "        run: ./gradlew finalizeDetektSarifManifest --no-configuration-cache --console=plain"
  assert_step_has_no_direct_key \
    "$file" \
    "detekt" \
    "Finalize Detekt SARIF manifest" \
    "continue-on-error"

  assert_step_direct_key_line \
    "$file" \
    "detekt" \
    "Collect Detekt SARIF" \
    "if" \
    "        if: always()"
  assert_step_direct_key_line \
    "$file" \
    "detekt" \
    "Collect Detekt SARIF" \
    "id" \
    "        id: detekt-sarif-collector"
  assert_step_direct_key_line \
    "$file" \
    "detekt" \
    "Collect Detekt SARIF" \
    "run" \
    "        run: python3 scripts/merge-detekt-sarif.py --manifest build/reports/detekt/expected-sarif.json --output build/reports/detekt/combined/detekt.sarif"
  assert_step_has_no_direct_key \
    "$file" \
    "detekt" \
    "Collect Detekt SARIF" \
    "continue-on-error"

  assert_step_direct_key_line \
    "$file" \
    "detekt" \
    "Upload detekt HTML report" \
    "if" \
    "        if: $detekt_html_report_guard"
  assert_step_uses_sha_pinned_action \
    "$file" \
    "Upload detekt HTML report" \
    "actions/upload-artifact"
  assert_step_with_contract \
    "$file" \
    "detekt" \
    "Upload detekt HTML report" \
    "$detekt_html_with_contract"

  assert_step_direct_key_line \
    "$file" \
    "detekt" \
    "Upload detekt SARIF to Code Scanning" \
    "if" \
    "        if: $detekt_sarif_report_guard"
  assert_step_has_no_direct_key \
    "$file" \
    "detekt" \
    "Upload detekt SARIF to Code Scanning" \
    "continue-on-error"
  assert_step_uses_sha_pinned_action \
    "$file" \
    "Upload detekt SARIF to Code Scanning" \
    "github/codeql-action/upload-sarif"
  assert_step_with_contract \
    "$file" \
    "detekt" \
    "Upload detekt SARIF to Code Scanning" \
    "$detekt_sarif_with_contract"
}

validate_container_smoke_workflow() {
  local file="$1"
  local protected_step
  local forbidden_key

  for forbidden_key in "continue-on-error" "if"; do
    assert_job_has_no_direct_key "$file" "smoke" "$forbidden_key"
  done

  for protected_step in "Probe /ready (gating)" "Probe /health"; do
    for forbidden_key in "continue-on-error" "if"; do
      assert_step_has_no_direct_key \
        "$file" \
        "smoke" \
        "$protected_step" \
        "$forbidden_key"
    done
  done

  assert_step_run_contract \
    "$file" \
    "smoke" \
    "Run app container" \
    "$container_smoke_run_contract"
  assert_step_run_contract \
    "$file" \
    "smoke" \
    "Probe /ready (gating)" \
    "$container_smoke_ready_contract"
  assert_step_run_contract \
    "$file" \
    "smoke" \
    "Probe /health" \
    "$container_smoke_health_contract"

  if awk '
    index($0, "jdbc:postgresql://127.0.0.1:") ||
      index($0, "ALLOW_INSECURE_DEV") {
      found = 1
    }
    END { exit !found }
  ' "$file"; then
    fail "Container Smoke must use service networking and fail-closed RBAC"
  fi
}

validate_tests_workflow_contract() {
  local file="$1"
  local permissions_contract
  local trigger_contract
  local jobs_contract
  local job_name
  local guard_name
  local unit_run
  local integration_run
  local logs_policy_run
  local postgres_wait_run
  local repository_guard_run

  assert_exact_line "$file" "name: Tests"

  if ! trigger_contract="$(normalize_top_level_trigger_contract "$file")"; then
    fail "Tests workflow top-level trigger block is missing or ambiguous: $file"
  fi
  if [ "$trigger_contract" != $'pull_request:\npush:\n  branches: [ main ]\nworkflow_dispatch:' ]; then
    fail "Tests workflow trigger contract changed: $file"
  fi

  if grep -Eq '^[[:space:]]*(pull_request_target|workflow_run):' "$file"; then
    fail "Tests workflow gained a privileged trigger: $file"
  fi

  if ! permissions_contract="$(normalize_top_level_permissions_contract "$file")"; then
    fail "Tests workflow top-level permissions are missing or ambiguous: $file"
  fi
  if [ "$permissions_contract" != "contents: read" ]; then
    fail "Tests workflow permissions are not read-only: $file"
  fi

  if ! jobs_contract="$(awk '
    $0 == "jobs:" { in_jobs = 1; jobs_blocks++; next }
    in_jobs && /^  [[:alnum:]_-]+:[[:space:]]*$/ {
      job = $0
      sub(/^  /, "", job)
      sub(/:[[:space:]]*$/, "", job)
      print job
    }
    END { if (jobs_blocks != 1) exit 42 }
  ' "$file")"; then
    fail "Tests workflow jobs block is missing or ambiguous: $file"
  fi
  if [ "$jobs_contract" != $'unit-tests\nintegration-tests' ]; then
    fail "Tests workflow must contain exactly unit-tests and integration-tests: $file"
  fi

  for job_name in unit-tests integration-tests; do
    assert_job_line "$file" "$job_name" "    runs-on: ubuntu-latest"
    assert_job_has_no_direct_key "$file" "$job_name" "if"
    assert_job_has_no_direct_key "$file" "$job_name" "continue-on-error"
    assert_job_has_no_direct_key "$file" "$job_name" "permissions"
  done
  if grep -Fq "continue-on-error:" "$file"; then
    fail "Tests workflow contains a fail-open continue-on-error contract: $file"
  fi

  for guard_name in \
    "Guard: no project-level repositories" \
    "Guard: no dynamic dependency versions" \
    "Guard: no dynamic versions in plugin blocks" \
    "Guard: no dynamic versions in version catalogs"; do
    assert_exact_line "$file" "        name: \"$guard_name\""
  done

  if ! repository_guard_run="$(
    normalize_anchored_step_run_contract \
      "$file" \
      "guard-no-project-repositories" \
      "Guard: no project-level repositories"
  )"; then
    fail "Gradle repository guard step is missing, ambiguous, or fail-open in $file"
  fi
  assert_eq \
    "$repository_guard_run" \
    $'./gradlew help \\\n--dependency-verification=strict \\\n--no-configuration-cache \\\n--no-build-cache \\\n--console=plain'
  assert_job_line "$file" "unit-tests" "      - &guard-no-project-repositories"
  assert_job_line "$file" "integration-tests" "      - *guard-no-project-repositories"

  if ! awk '
    $0 == "  unit-tests:" {
      in_job = 1
      jobs++
      next
    }
    in_job && /^  [[:alnum:]_-]+:[[:space:]]*$/ { in_job = 0 }
    in_job && $0 == "      - &validate-wrapper" && stage == 0 { stage = 1; next }
    in_job && $0 == "      - &setup-jdk" && stage == 1 { stage = 2; next }
    in_job && $0 == "      - &setup-gradle" && stage == 2 { stage = 3; next }
    in_job && $0 == "      - &env-versions" && stage == 3 { stage = 4; next }
    in_job && $0 == "      - &guard-no-project-repositories" && stage == 4 {
      stage = 5
      next
    }
    in_job && $0 == "      - name: Run unit tests (retry x3)" && stage == 5 {
      stage = 6
    }
    END { exit !(jobs == 1 && stage == 6) }
  ' "$file"; then
    fail "unit-tests repository guard must run after Wrapper/JDK/Gradle setup and before tests: $file"
  fi

  if ! awk '
    $0 == "  integration-tests:" {
      in_job = 1
      jobs++
      next
    }
    in_job && /^  [[:alnum:]_-]+:[[:space:]]*$/ { in_job = 0 }
    in_job && $0 == "      - *validate-wrapper" && stage == 0 { stage = 1; next }
    in_job && $0 == "      - *setup-jdk" && stage == 1 { stage = 2; next }
    in_job && $0 == "      - *setup-gradle" && stage == 2 { stage = 3; next }
    in_job && $0 == "      - *env-versions" && stage == 3 { stage = 4; next }
    in_job && $0 == "      - *guard-no-project-repositories" && stage == 4 {
      stage = 5
      next
    }
    in_job && $0 == "      - name: Wait for Postgres (5432)" && stage == 5 {
      stage = 6
    }
    END { exit !(jobs == 1 && stage == 6) }
  ' "$file"; then
    fail "integration-tests repository guard must run after Wrapper/JDK/Gradle setup and before tests: $file"
  fi

  if ! unit_run="$(normalize_step_run_contract "$file" "unit-tests" "Run unit tests (retry x3)")"; then
    fail "unit test command is missing or ambiguous in $file"
  fi
  assert_step_has_no_direct_key "$file" "unit-tests" "Run unit tests (retry x3)" "if"
  assert_step_has_no_direct_key \
    "$file" \
    "unit-tests" \
    "Run unit tests (retry x3)" \
    "continue-on-error"
  assert_contains \
    "$unit_run" \
    'if ./gradlew clean test --no-configuration-cache $extra --console=plain --stacktrace; then'
  assert_contains "$unit_run" "exit 1"

  assert_job_line "$file" "integration-tests" "    services:"
  assert_job_line "$file" "integration-tests" "      postgres:"
  assert_job_line "$file" "integration-tests" "        image: postgres:16-alpine@sha256:46258a3eb38adf37e77ca5bd41f93ca8b1034f925cf37190a7a8015ba151f3ca"

  if ! integration_run="$(normalize_step_run_contract "$file" "integration-tests" "Run integration tests (retry x3)")"; then
    fail "integration test command is missing or ambiguous in $file"
  fi
  assert_step_has_no_direct_key \
    "$file" \
    "integration-tests" \
    "Run integration tests (retry x3)" \
    "if"
  assert_step_has_no_direct_key \
    "$file" \
    "integration-tests" \
    "Run integration tests (retry x3)" \
    "continue-on-error"
  assert_contains \
    "$integration_run" \
    'if ./gradlew test -PrunIT=true --no-configuration-cache $extra --console=plain --stacktrace; then'
  assert_contains "$integration_run" "exit 1"

  if ! logs_policy_run="$(normalize_step_run_contract "$file" "integration-tests" "Logs policy scan (SEC-02) (retry x2)")"; then
    fail "integration logs-policy command is missing or ambiguous in $file"
  fi
  assert_step_has_no_direct_key \
    "$file" \
    "integration-tests" \
    "Logs policy scan (SEC-02) (retry x2)" \
    "if"
  assert_step_has_no_direct_key \
    "$file" \
    "integration-tests" \
    "Logs policy scan (SEC-02) (retry x2)" \
    "continue-on-error"
  assert_contains \
    "$logs_policy_run" \
    'if ./gradlew :app-bot:checkLogsPolicy --no-configuration-cache $extra --no-daemon --stacktrace; then'
  assert_contains "$logs_policy_run" "exit 1"

  if ! postgres_wait_run="$(normalize_step_run_contract "$file" "integration-tests" "Wait for Postgres (5432)")"; then
    fail "PostgreSQL readiness step is missing or ambiguous in $file"
  fi
  assert_step_has_no_direct_key \
    "$file" \
    "integration-tests" \
    "Wait for Postgres (5432)" \
    "if"
  assert_contains "$postgres_wait_run" "nc -z 127.0.0.1 5432"
  assert_contains "$postgres_wait_run" "exit 1"
}

active_workflow_lines() {
  local workflow_dir="$1"
  local workflow_file
  while IFS= read -r workflow_file; do
    awk '
      {
        sub(/\r$/, "")
        normalized = $0
        sub(/^[[:space:]]+/, "", normalized)
        sub(/[[:space:]]+$/, "", normalized)
        if (normalized == "" || substr(normalized, 1, 1) == "#") {
          next
        }
        print normalized
      }
    ' "$workflow_file"
  done < <(
    find "$workflow_dir" -type f \( -name '*.yml' -o -name '*.yaml' \) -print |
      LC_ALL=C sort
  )
}

validate_trivy_action_inventory() {
  local workflow_dir="$1"
  local active_lines
  local action_lines
  local action_count
  local invalid_action_lines
  local trivyignores_count

  active_lines="$(active_workflow_lines "$workflow_dir")"
  action_lines="$(
    printf '%s\n' "$active_lines" |
      awk 'index($0, "aquasecurity/trivy-action@") { print }'
  )"
  action_count="$(printf '%s\n' "$action_lines" | awk 'NF { count++ } END { print count + 0 }')"
  if [ "$action_count" -ne 4 ]; then
    echo "expected exactly four active trivy-action references, found $action_count" >&2
    return 1
  fi

  invalid_action_lines="$(
    printf '%s\n' "$action_lines" |
      awk -v expected="$approved_trivy_action_active_line" 'NF && $0 != expected { print }'
  )"
  if [ -n "$invalid_action_lines" ]; then
    echo "trivy-action reference is not the approved SHA/comment contract" >&2
    return 1
  fi

  if printf '%s\n' "$active_lines" |
    awk 'index($0, "aquasecurity/setup-trivy@") { found = 1 } END { exit !found }'; then
    echo "direct setup-trivy use requires an explicit approved contract" >&2
    return 1
  fi
  if printf '%s\n' "$active_lines" |
    awk 'index($0, "aquasec/trivy") { found = 1 } END { exit !found }'; then
    echo "direct aquasec/trivy image use requires an explicit approved contract" >&2
    return 1
  fi
  if printf '%s\n' "$active_lines" |
    awk '$0 ~ /^ignorefile:/ { found = 1 } END { exit !found }'; then
    echo "unsupported Trivy input ignorefile is present" >&2
    return 1
  fi

  trivyignores_count="$(
    printf '%s\n' "$active_lines" |
      awk '$0 == "trivyignores: .trivyignore" { count++ } END { print count + 0 }'
  )"
  if [ "$trivyignores_count" -ne 4 ]; then
    echo "expected exactly four supported trivyignores inputs" >&2
    return 1
  fi
}

validate_trivy_filesystem_workflow() {
  local file="$1"
  assert_exact_line "$file" "  pull_request:"
  assert_job_has_no_direct_key "$file" "trivy" "if"
  assert_job_has_no_direct_key "$file" "trivy" "continue-on-error"
  assert_step_direct_key_line \
    "$file" \
    "trivy" \
    "Trivy filesystem scan" \
    "uses" \
    "        $approved_trivy_action_active_line"
  assert_step_has_no_direct_key "$file" "trivy" "Trivy filesystem scan" "if"
  assert_step_has_no_direct_key "$file" "trivy" "Trivy filesystem scan" "continue-on-error"
  assert_step_with_contract \
    "$file" \
    "trivy" \
    "Trivy filesystem scan" \
    "$trivy_filesystem_with_contract"

  assert_step_direct_key_line \
    "$file" \
    "trivy" \
    "Upload Trivy filesystem SARIF to code scanning" \
    "if" \
    "        if: $trivy_filesystem_report_guard"
  assert_step_has_no_direct_key \
    "$file" \
    "trivy" \
    "Upload Trivy filesystem SARIF to code scanning" \
    "continue-on-error"
  assert_step_with_contract \
    "$file" \
    "trivy" \
    "Upload Trivy filesystem SARIF to code scanning" \
    "$trivy_filesystem_sarif_with_contract"

  assert_step_direct_key_line \
    "$file" \
    "trivy" \
    "Persist Trivy filesystem report artifact" \
    "if" \
    "        if: $trivy_filesystem_report_guard"
  assert_step_has_no_direct_key \
    "$file" \
    "trivy" \
    "Persist Trivy filesystem report artifact" \
    "continue-on-error"
  assert_step_with_contract \
    "$file" \
    "trivy" \
    "Persist Trivy filesystem report artifact" \
    "$trivy_filesystem_artifact_with_contract"
}

validate_trivy_runtime_workflow() {
  local file="$1"
  assert_job_has_no_direct_key "$file" "trivy" "if"
  assert_job_has_no_direct_key "$file" "trivy" "continue-on-error"

  assert_step_direct_key_line \
    "$file" \
    "trivy" \
    "Inventory Trivy JVM runtime artifacts" \
    "uses" \
    "        $approved_trivy_action_active_line"
  assert_step_has_no_direct_key "$file" "trivy" "Inventory Trivy JVM runtime artifacts" "if"
  assert_step_has_no_direct_key \
    "$file" \
    "trivy" \
    "Inventory Trivy JVM runtime artifacts" \
    "continue-on-error"
  assert_step_with_contract \
    "$file" \
    "trivy" \
    "Inventory Trivy JVM runtime artifacts" \
    "$trivy_runtime_inventory_with_contract"

  assert_step_direct_key_line \
    "$file" \
    "trivy" \
    "Trivy JVM runtime scan" \
    "uses" \
    "        $approved_trivy_action_active_line"
  assert_step_has_no_direct_key "$file" "trivy" "Trivy JVM runtime scan" "if"
  assert_step_has_no_direct_key "$file" "trivy" "Trivy JVM runtime scan" "continue-on-error"
  assert_step_with_contract \
    "$file" \
    "trivy" \
    "Trivy JVM runtime scan" \
    "$trivy_runtime_with_contract"

  assert_step_direct_key_line \
    "$file" \
    "trivy" \
    "Upload Trivy JVM runtime SARIF to code scanning" \
    "if" \
    "        if: $trivy_runtime_report_guard"
  assert_step_with_contract \
    "$file" \
    "trivy" \
    "Upload Trivy JVM runtime SARIF to code scanning" \
    "$trivy_runtime_sarif_with_contract"

  assert_step_direct_key_line \
    "$file" \
    "trivy" \
    "Persist Trivy JVM runtime report artifact" \
    "if" \
    "        if: $trivy_runtime_artifact_guard"
  assert_step_with_contract \
    "$file" \
    "trivy" \
    "Persist Trivy JVM runtime report artifact" \
    "$trivy_runtime_artifact_with_contract"
}

validate_trivy_image_workflow() {
  local file="$1"
  assert_job_has_no_direct_key "$file" "trivy-image" "if"
  assert_job_has_no_direct_key "$file" "trivy-image" "continue-on-error"
  assert_step_direct_key_line \
    "$file" \
    "trivy-image" \
    "Trivy image scan" \
    "uses" \
    "        $approved_trivy_action_active_line"
  assert_step_has_no_direct_key "$file" "trivy-image" "Trivy image scan" "if"
  assert_step_has_no_direct_key "$file" "trivy-image" "Trivy image scan" "continue-on-error"
  assert_step_with_contract \
    "$file" \
    "trivy-image" \
    "Trivy image scan" \
    "$trivy_image_with_contract"

  assert_step_direct_key_line \
    "$file" \
    "trivy-image" \
    "Upload Trivy image SARIF to code scanning" \
    "if" \
    "        if: $trivy_image_report_guard"
  assert_step_has_no_direct_key \
    "$file" \
    "trivy-image" \
    "Upload Trivy image SARIF to code scanning" \
    "continue-on-error"
  assert_step_with_contract \
    "$file" \
    "trivy-image" \
    "Upload Trivy image SARIF to code scanning" \
    "$trivy_image_sarif_with_contract"

  assert_step_direct_key_line \
    "$file" \
    "trivy-image" \
    "Persist Trivy image report artifact" \
    "if" \
    "        if: $trivy_image_report_guard"
  assert_step_has_no_direct_key \
    "$file" \
    "trivy-image" \
    "Persist Trivy image report artifact" \
    "continue-on-error"
  assert_step_with_contract \
    "$file" \
    "trivy-image" \
    "Persist Trivy image report artifact" \
    "$trivy_image_artifact_with_contract"
}

validate_keyless_sca_workflows() {
  local dependency_submission_file="$1"
  local sca_file="$2"
  local security_scan_file="$3"

  python3 - \
    "$dependency_submission_file" \
    "$sca_file" \
    "$security_scan_file" <<'PY'
from pathlib import Path
import re
import sys


DEPENDENCY_SUBMISSION_USES = (
    "gradle/actions/dependency-submission@"
    "3f131e8634966bd73d06cc69884922b02e6faf92 # v6.2.0"
)
CHECKOUT_USES = "actions/checkout@692973e3d937129bcbf40652eb9f2f61becf3332 # v4.1.7"
SETUP_JAVA_USES = "actions/setup-java@b36c23c0d998641eff861008f374ee103c25ac73 # v4.4.0"
SETUP_GRADLE_USES = "gradle/actions/setup-gradle@d9c87d481d55275bb5441eef3fe0e46805f9ef70 # v3.5.0"
TRIVY_USES = (
    "aquasecurity/trivy-action@"
    "57a97c7e7821a5776cebc9bb87c984fa69cba8f1 # v0.35.0, post-incident safe release"
)
UPLOAD_SARIF_USES = (
    "github/codeql-action/upload-sarif@"
    "7c9a7896f03bb1f3de14c5663ed46759e27443e0 # v4.31.9"
)
UPLOAD_ARTIFACT_USES = (
    "actions/upload-artifact@"
    "b4b15b8c7c6ac21ea08fcf65892d2ee8f75cf882 # v4.4.3"
)


def reject(message):
    print(f"keyless-sca-contract: {message}", file=sys.stderr)
    raise SystemExit(1)


class Workflow:
    def __init__(self, path):
        self.path = Path(path)
        try:
            self.lines = self.path.read_text(encoding="utf-8").splitlines()
        except OSError as error:
            reject(f"cannot read {self.path}: {error}")
        if any("\t" in line for line in self.lines):
            reject(f"tabs are not permitted in workflow indentation: {self.path}")

    @staticmethod
    def _indent(line):
        return len(line) - len(line.lstrip(" "))

    @staticmethod
    def _content(line):
        stripped = line.strip()
        return bool(stripped) and not stripped.startswith("#")

    def _header_indexes(self, start, end, indent, key):
        prefix = " " * indent + key + ":"
        return [
            index
            for index in range(start, end)
            if self._content(self.lines[index]) and self.lines[index].startswith(prefix)
            and self.lines[index][: len(prefix)] == prefix
            and (
                len(self.lines[index]) == len(prefix)
                or self.lines[index][len(prefix)] in " "
            )
        ]

    def _one_header(self, start, end, indent, key):
        indexes = self._header_indexes(start, end, indent, key)
        if len(indexes) != 1:
            reject(
                f"expected exactly one {key!r} key at indent {indent} in {self.path}; "
                f"found {len(indexes)}"
            )
        return indexes[0]

    def _block_end(self, header_index, header_indent, limit=None):
        end = len(self.lines) if limit is None else limit
        for index in range(header_index + 1, end):
            line = self.lines[index]
            if self._content(line) and self._indent(line) <= header_indent:
                return index
        return end

    def scalar(self, start, end, indent, key, required=True):
        indexes = self._header_indexes(start, end, indent, key)
        if not indexes and not required:
            return None
        if len(indexes) != 1:
            reject(
                f"expected exactly one scalar {key!r} at indent {indent} in {self.path}; "
                f"found {len(indexes)}"
            )
        index = indexes[0]
        prefix = " " * indent + key + ":"
        raw_value = self.lines[index][len(prefix) :].strip()
        if raw_value not in (">", ">-", "|", "|-"):
            return raw_value
        block_end = self._block_end(index, indent, end)
        values = []
        for line in self.lines[index + 1 : block_end]:
            if not self._content(line):
                continue
            if self._indent(line) < indent + 2:
                reject(f"malformed block scalar for {key!r} in {self.path}")
            values.append(line[indent + 2 :].strip())
        separator = " " if raw_value.startswith(">") else "\n"
        return separator.join(values)

    def mapping(self, start, end, indent, key, required=True):
        indexes = self._header_indexes(start, end, indent, key)
        if not indexes and not required:
            return None
        if len(indexes) != 1:
            reject(
                f"expected exactly one mapping {key!r} at indent {indent} in {self.path}; "
                f"found {len(indexes)}"
            )
        header = indexes[0]
        prefix = " " * indent + key + ":"
        if self.lines[header][len(prefix) :].strip():
            reject(f"{key!r} must use a block mapping in {self.path}")
        block_end = self._block_end(header, indent, end)
        child_indent = indent + 2
        keys = []
        for index in range(header + 1, block_end):
            line = self.lines[index]
            if not self._content(line) or self._indent(line) != child_indent:
                continue
            match = re.match(r"^\s{%d}([^:#][^:]*):" % child_indent, line)
            if not match:
                reject(f"malformed {key!r} mapping entry in {self.path}: {line.strip()}")
            keys.append(match.group(1))
        if len(keys) != len(set(keys)):
            reject(f"duplicate key in {key!r} mapping in {self.path}")
        return {
            child_key: self.scalar(header + 1, block_end, child_indent, child_key)
            for child_key in keys
        }

    def top_scalar(self, key):
        return self.scalar(0, len(self.lines), 0, key)

    def top_mapping(self, key):
        return self.mapping(0, len(self.lines), 0, key)

    def top_block(self, key):
        header = self._one_header(0, len(self.lines), 0, key)
        prefix = key + ":"
        if self.lines[header][len(prefix) :].strip():
            reject(f"top-level {key!r} must use block form in {self.path}")
        end = self._block_end(header, 0)
        return "\n".join(
            line[2:]
            for line in self.lines[header + 1 : end]
            if self._content(line)
        )

    def jobs(self):
        jobs_header = self._one_header(0, len(self.lines), 0, "jobs")
        jobs_end = self._block_end(jobs_header, 0)
        names = []
        for index in range(jobs_header + 1, jobs_end):
            line = self.lines[index]
            if not self._content(line) or self._indent(line) != 2:
                continue
            match = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
            if not match:
                reject(f"malformed job declaration in {self.path}: {line.strip()}")
            names.append(match.group(1))
        if len(names) != len(set(names)):
            reject(f"duplicate job id in {self.path}")
        return names

    def job_range(self, job_name):
        jobs_header = self._one_header(0, len(self.lines), 0, "jobs")
        jobs_end = self._block_end(jobs_header, 0)
        job_header = self._one_header(jobs_header + 1, jobs_end, 2, job_name)
        return job_header + 1, self._block_end(job_header, 2, jobs_end)

    def job_scalar(self, job_name, key, required=True):
        start, end = self.job_range(job_name)
        return self.scalar(start, end, 4, key, required)

    def job_mapping(self, job_name, key, required=True):
        start, end = self.job_range(job_name)
        return self.mapping(start, end, 4, key, required)

    def steps(self, job_name):
        job_start, job_end = self.job_range(job_name)
        steps_header = self._one_header(job_start, job_end, 4, "steps")
        steps_end = self._block_end(steps_header, 4, job_end)
        names = []
        for index in range(steps_header + 1, steps_end):
            line = self.lines[index]
            if not self._content(line) or self._indent(line) != 6:
                continue
            match = re.match(r"^      - name:\s*(.+?)\s*$", line)
            if not match:
                reject(f"every step must have an explicit name in {self.path}: {line.strip()}")
            names.append(match.group(1))
        if len(names) != len(set(names)):
            reject(f"duplicate step name in job {job_name!r} in {self.path}")
        return names

    def step_range(self, job_name, step_name):
        job_start, job_end = self.job_range(job_name)
        steps_header = self._one_header(job_start, job_end, 4, "steps")
        steps_end = self._block_end(steps_header, 4, job_end)
        target = "      - name: " + step_name
        indexes = [
            index
            for index in range(steps_header + 1, steps_end)
            if self.lines[index] == target
        ]
        if len(indexes) != 1:
            reject(
                f"expected exactly one step {step_name!r} in job {job_name!r} in {self.path}"
            )
        start = indexes[0]
        return start + 1, self._block_end(start, 6, steps_end)

    def step_scalar(self, job_name, step_name, key, required=True):
        start, end = self.step_range(job_name, step_name)
        return self.scalar(start, end, 8, key, required)

    def step_mapping(self, job_name, step_name, key, required=True):
        start, end = self.step_range(job_name, step_name)
        return self.mapping(start, end, 8, key, required)

    def assert_no_fail_open(self, job_name, step_names):
        if self.job_scalar(job_name, "continue-on-error", required=False) is not None:
            reject(f"job {job_name!r} has continue-on-error in {self.path}")
        for step_name in step_names:
            if self.step_scalar(job_name, step_name, "continue-on-error", required=False) is not None:
                reject(f"step {step_name!r} has continue-on-error in {self.path}")
            run = self.step_scalar(job_name, step_name, "run", required=False)
            if run is not None and "|| true" in run:
                reject(f"step {step_name!r} hides failure with || true in {self.path}")

    def assert_steps_unguarded(self, job_name, step_names):
        for step_name in step_names:
            if self.step_scalar(job_name, step_name, "if", required=False) is not None:
                reject(f"blocking step {step_name!r} has an execution guard in {self.path}")


def expect(actual, expected, description):
    if actual != expected:
        reject(f"{description} changed: expected {expected!r}, got {actual!r}")


def expect_absent(value, description):
    if value is not None:
        reject(f"{description} must be absent")


dependency = Workflow(sys.argv[1])
sca = Workflow(sys.argv[2])
security = Workflow(sys.argv[3])

expect(dependency.top_scalar("name"), "Dependency Submission", "dependency workflow name")
expect(
    dependency.top_block("on"),
    "push:\n  branches: [ main ]\nworkflow_dispatch:",
    "dependency workflow triggers",
)
expect(
    dependency.top_mapping("permissions"),
    {"contents": "write"},
    "trusted main dependency submission permissions",
)
expect(dependency.jobs(), ["submit"], "dependency workflow jobs")

submission_steps = [
    "Checkout",
    "Set up JDK 21",
    "Verify resolved production dependency graph (blocking)",
    "Submit resolved dependency graph (blocking)",
]
expect(dependency.steps("submit"), submission_steps, "dependency submission steps")
expect(
    dependency.job_scalar("submit", "if"),
    "github.event_name == 'push' || github.ref == 'refs/heads/main'",
    "manual submission main-branch guard",
)
expect(
    dependency.job_mapping("submit", "permissions"),
    {"contents": "write"},
    "dependency submission job permissions",
)
expect(dependency.step_scalar("submit", "Checkout", "uses"), CHECKOUT_USES, "checkout pin")
expect(
    dependency.step_mapping("submit", "Checkout", "with"),
    {"persist-credentials": "false"},
    "trusted checkout credential isolation",
)
expect(
    dependency.step_scalar("submit", "Set up JDK 21", "uses"),
    SETUP_JAVA_USES,
    "JDK setup pin",
)
expect(
    dependency.step_mapping("submit", "Set up JDK 21", "with"),
    {"distribution": "temurin", "java-version": "'21'"},
    "JDK 21 contract",
)
expect(
    dependency.step_scalar(
        "submit", "Verify resolved production dependency graph (blocking)", "run"
    ),
    (
        "./gradlew verifyResolvedProductionDependencyGraph "
        "--dependency-verification=strict --no-configuration-cache --console=plain"
    ),
    "pre-submission resolved graph verification",
)
expect(
    dependency.step_scalar(
        "submit", "Submit resolved dependency graph (blocking)", "uses"
    ),
    DEPENDENCY_SUBMISSION_USES,
    "dependency submission action pin",
)
expect(
    dependency.step_mapping(
        "submit", "Submit resolved dependency graph (blocking)", "with"
    ),
    {
        "gradle-version": "wrapper",
        "validate-wrappers": "true",
        "cache-provider": "basic",
        "dependency-graph": "generate-and-submit",
        "dependency-resolution-task": (
            "verifyResolvedProductionDependencyGraph "
            "ForceDependencyResolutionPlugin_resolveAllDependencies"
        ),
        "dependency-graph-report-dir": "build/reports/dependency-submission",
        "dependency-graph-continue-on-failure": "false",
        "additional-arguments": (
            "--dependency-verification=strict --no-configuration-cache --stacktrace"
        ),
    },
    "trusted dependency submission inputs",
)
dependency.assert_no_fail_open("submit", submission_steps)
dependency.assert_steps_unguarded("submit", submission_steps)

expect(sca.top_scalar("name"), "SCA Gate", "required SCA workflow name")
expect(
    sca.top_block("on"),
    "push:\n  branches: [ main ]\npull_request:",
    "SCA triggers",
)
expect(sca.top_mapping("permissions"), {"contents": "read"}, "SCA permissions")
expect(sca.jobs(), ["dependency-check"], "required SCA job id")
expect_absent(sca.job_scalar("dependency-check", "if", required=False), "SCA job guard")
expect(
    sca.job_mapping("dependency-check", "permissions"),
    {"contents": "read"},
    "SCA job permissions",
)
sca_steps = [
    "Checkout",
    "Set up JDK 21",
    "Gradle cache & setup",
    "Verify resolved production dependency graph (blocking)",
]
expect(sca.steps("dependency-check"), sca_steps, "SCA graph-integrity steps")
expect(sca.step_scalar("dependency-check", "Checkout", "uses"), CHECKOUT_USES, "SCA checkout pin")
expect(
    sca.step_mapping("dependency-check", "Checkout", "with"),
    {"persist-credentials": "false"},
    "SCA checkout credential isolation",
)
expect(
    sca.step_scalar("dependency-check", "Set up JDK 21", "uses"),
    SETUP_JAVA_USES,
    "SCA JDK pin",
)
expect(
    sca.step_mapping("dependency-check", "Set up JDK 21", "with"),
    {"distribution": "temurin", "java-version": "'21'"},
    "SCA JDK 21 contract",
)
expect(
    sca.step_scalar("dependency-check", "Gradle cache & setup", "uses"),
    SETUP_GRADLE_USES,
    "SCA Gradle setup pin",
)
expect(
    sca.step_mapping("dependency-check", "Gradle cache & setup", "with"),
    {"cache-disabled": "false", "gradle-version": "wrapper", "cache-read-only": "true"},
    "read-only PR Gradle cache contract",
)
expect(
    sca.step_scalar(
        "dependency-check", "Verify resolved production dependency graph (blocking)", "run"
    ),
    (
        "./gradlew verifyResolvedProductionDependencyGraph "
        "--dependency-verification=strict --no-configuration-cache --console=plain"
    ),
    "SCA resolved graph verifier invocation",
)
sca.assert_no_fail_open("dependency-check", sca_steps)
sca.assert_steps_unguarded("dependency-check", sca_steps)

expect(security.top_scalar("name"), "Security Scan (Trivy)", "Security Scan name")
expect(security.top_mapping("permissions"), {"contents": "read", "security-events": "write"}, "Trivy permissions")
expect(security.jobs(), ["trivy"], "Trivy jobs")
security_steps = [
    "Checkout",
    "Set up JDK 21",
    "Gradle cache & setup",
    "Build resolved JVM runtime dependencies (blocking)",
    "Prepare trusted Trivy output directory",
    "Trivy filesystem scan",
    "Assert fresh Trivy filesystem output",
    "Upload Trivy filesystem SARIF to code scanning",
    "Persist Trivy filesystem report artifact",
    "Assert fresh Trivy JVM inventory path",
    "Inventory Trivy JVM runtime artifacts",
    "Verify Trivy JVM analyzer coverage",
    "Assert fresh Trivy JVM SARIF path",
    "Trivy JVM runtime scan",
    "Assert Trivy JVM runtime outputs",
    "Upload Trivy JVM runtime SARIF to code scanning",
    "Persist Trivy JVM runtime report artifact",
]
expect(security.steps("trivy"), security_steps, "Trivy step ordering")
expect(security.step_scalar("trivy", "Checkout", "uses"), CHECKOUT_USES, "Trivy checkout pin")
expect(
    security.step_mapping("trivy", "Checkout", "with"),
    {"persist-credentials": "false"},
    "Trivy checkout credential isolation",
)
expect(security.step_scalar("trivy", "Set up JDK 21", "uses"), SETUP_JAVA_USES, "Trivy JDK pin")
expect(security.step_scalar("trivy", "Gradle cache & setup", "uses"), SETUP_GRADLE_USES, "Trivy Gradle setup pin")
expect(
    security.step_scalar(
        "trivy", "Build resolved JVM runtime dependencies (blocking)", "run"
    ),
    (
        "./gradlew :app-bot:installDist verifyResolvedProductionDependencyGraph "
        "--dependency-verification=strict --no-configuration-cache --console=plain"
    ),
    "Trivy resolved JVM artifact build",
)
expect(
    security.step_scalar("trivy", "Prepare trusted Trivy output directory", "id"),
    "trivy-output",
    "trusted Trivy output step id",
)
expect(
    security.step_scalar("trivy", "Prepare trusted Trivy output directory", "shell"),
    "bash",
    "trusted Trivy output shell",
)
expect(
    security.step_mapping("trivy", "Prepare trusted Trivy output directory", "env"),
    {
        "TRIVY_RUNNER_TEMP": "${{ runner.temp }}",
        "TRIVY_RUN_ID": "${{ github.run_id }}",
        "TRIVY_RUN_ATTEMPT": "${{ github.run_attempt }}",
    },
    "trusted Trivy context inputs",
)
expect(
    security.step_scalar("trivy", "Prepare trusted Trivy output directory", "run"),
    "\n".join(
        [
            "set -euo pipefail",
            'physical_runner_temp="$(cd -P -- "$TRIVY_RUNNER_TEMP" && pwd -P)"',
            'physical_workspace="$(cd -P -- "$GITHUB_WORKSPACE" && pwd -P)"',
            'test "$physical_runner_temp" = "$TRIVY_RUNNER_TEMP"',
            'output_dir="$physical_runner_temp/clubs-bot-trivy-$TRIVY_RUN_ID-$TRIVY_RUN_ATTEMPT"',
            'case "$output_dir/" in',
            '"$physical_workspace/"*)',
            'echo "trusted Trivy output directory must be outside the checkout" >&2',
            "exit 1",
            ";;",
            "esac",
            'if [ -e "$output_dir" ] || [ -L "$output_dir" ]; then',
            'echo "trusted Trivy output directory already exists" >&2',
            "exit 1",
            "fi",
            'mkdir -m 0700 -- "$output_dir"',
            'test -d "$output_dir"',
            'test ! -L "$output_dir"',
            "for output_name in \\",
            "trivy-filesystem.sarif \\",
            "trivy-jvm-inventory.json \\",
            "trivy-jvm-runtime.sarif; do",
            'test ! -e "$output_dir/$output_name"',
            'test ! -L "$output_dir/$output_name"',
            "done",
            "{",
            "printf 'directory=%s\\n' \"$output_dir\"",
            "printf 'filesystem_sarif=%s\\n' \"$output_dir/trivy-filesystem.sarif\"",
            "printf 'jvm_inventory=%s\\n' \"$output_dir/trivy-jvm-inventory.json\"",
            "printf 'jvm_sarif=%s\\n' \"$output_dir/trivy-jvm-runtime.sarif\"",
            '} >> "$GITHUB_OUTPUT"',
        ]
    ),
    "trusted Trivy output preflight",
)
expect(
    security.step_scalar("trivy", "Trivy filesystem scan", "uses"),
    TRIVY_USES,
    "Trivy action pin",
)
expect(
    security.step_mapping("trivy", "Trivy filesystem scan", "with"),
    {
        "scan-type": "fs",
        "scan-ref": ".",
        "severity": "HIGH,CRITICAL",
        "limit-severities-for-sarif": "true",
        "trivyignores": ".trivyignore",
        "format": "sarif",
        "output": "${{ steps.trivy-output.outputs.filesystem_sarif }}",
        "exit-code": "1",
        "version": "v0.69.3",
    },
    "Trivy filesystem blocking scanner inputs",
)
expect(
    security.step_scalar("trivy", "Assert fresh Trivy filesystem output", "id"),
    "trivy-filesystem-output",
    "Trivy filesystem assertion id",
)
expect(
    security.step_scalar("trivy", "Assert fresh Trivy filesystem output", "if"),
    "${{ always() && steps.trivy-output.outcome == 'success' }}",
    "Trivy filesystem assertion failure guard",
)
expect(
    security.step_mapping("trivy", "Assert fresh Trivy filesystem output", "env"),
    {
        "TRIVY_OUTPUT_DIR": "${{ steps.trivy-output.outputs.directory }}",
        "TRIVY_REPORT": "${{ steps.trivy-output.outputs.filesystem_sarif }}",
    },
    "Trivy filesystem assertion paths",
)
expect(
    security.step_scalar("trivy", "Assert fresh Trivy filesystem output", "run"),
    "\n".join(
        [
            "set -euo pipefail",
            'test -d "$TRIVY_OUTPUT_DIR"',
            'test ! -L "$TRIVY_OUTPUT_DIR"',
            'test "$(dirname -- "$TRIVY_REPORT")" = "$TRIVY_OUTPUT_DIR"',
            'test -f "$TRIVY_REPORT"',
            'test ! -L "$TRIVY_REPORT"',
            'test -s "$TRIVY_REPORT"',
        ]
    ),
    "fresh Trivy filesystem output assertion",
)
expect(
    security.step_scalar(
        "trivy", "Upload Trivy filesystem SARIF to code scanning", "uses"
    ),
    UPLOAD_SARIF_USES,
    "Trivy filesystem SARIF upload pin",
)
expect(
    security.step_scalar(
        "trivy", "Upload Trivy filesystem SARIF to code scanning", "if"
    ),
    "${{ always() && steps.trivy-filesystem-output.outcome == 'success' }}",
    "Trivy filesystem upload guard",
)
expect(
    security.step_mapping(
        "trivy", "Upload Trivy filesystem SARIF to code scanning", "with"
    ),
    {
        "sarif_file": "${{ steps.trivy-output.outputs.filesystem_sarif }}",
        "checkout_path": "${{ github.workspace }}",
        "category": "trivy-filesystem",
    },
    "Trivy filesystem SARIF upload inputs",
)
expect(
    security.step_scalar(
        "trivy", "Persist Trivy filesystem report artifact", "uses"
    ),
    UPLOAD_ARTIFACT_USES,
    "Trivy filesystem artifact pin",
)
expect(
    security.step_scalar(
        "trivy", "Persist Trivy filesystem report artifact", "if"
    ),
    "${{ always() && steps.trivy-filesystem-output.outcome == 'success' }}",
    "Trivy filesystem artifact guard",
)
expect(
    security.step_mapping(
        "trivy", "Persist Trivy filesystem report artifact", "with"
    ),
    {
        "name": "trivy-filesystem-report",
        "path": "${{ steps.trivy-output.outputs.filesystem_sarif }}",
        "if-no-files-found": "error",
    },
    "Trivy filesystem artifact inputs",
)
expect(
    security.step_mapping("trivy", "Assert fresh Trivy JVM inventory path", "env"),
    {
        "TRIVY_OUTPUT_DIR": "${{ steps.trivy-output.outputs.directory }}",
        "TRIVY_REPORT": "${{ steps.trivy-output.outputs.jvm_inventory }}",
    },
    "Trivy JVM inventory freshness paths",
)
expect(
    security.step_scalar("trivy", "Assert fresh Trivy JVM inventory path", "run"),
    "\n".join(
        [
            "set -euo pipefail",
            'test -d "$TRIVY_OUTPUT_DIR"',
            'test ! -L "$TRIVY_OUTPUT_DIR"',
            'test "$(dirname -- "$TRIVY_REPORT")" = "$TRIVY_OUTPUT_DIR"',
            'test ! -e "$TRIVY_REPORT"',
            'test ! -L "$TRIVY_REPORT"',
        ]
    ),
    "Trivy JVM inventory pre-existing path rejection",
)
expect(
    security.step_scalar("trivy", "Inventory Trivy JVM runtime artifacts", "uses"),
    TRIVY_USES,
    "Trivy JVM inventory action pin",
)
expect(
    security.step_mapping("trivy", "Inventory Trivy JVM runtime artifacts", "with"),
    {
        "scan-type": "rootfs",
        "scan-ref": "app-bot/build/install/app-bot",
        "scanners": "vuln",
        "list-all-pkgs": "true",
        "trivyignores": ".trivyignore",
        "format": "json",
        "output": "${{ steps.trivy-output.outputs.jvm_inventory }}",
        "exit-code": "0",
        "version": "v0.69.3",
    },
    "Trivy JVM inventory inputs",
)
expect(
    security.step_scalar("trivy", "Verify Trivy JVM analyzer coverage", "run"),
    (
        "python3 scripts/verify-trivy-java-inventory.py "
        '"${{ github.workspace }}" '
        '"${{ steps.trivy-output.outputs.directory }}" '
        "trivy-jvm-inventory.json app-bot/build/install/app-bot"
    ),
    "Trivy JVM inventory verifier invocation",
)
expect(
    security.step_mapping("trivy", "Assert fresh Trivy JVM SARIF path", "env"),
    {
        "TRIVY_OUTPUT_DIR": "${{ steps.trivy-output.outputs.directory }}",
        "TRIVY_REPORT": "${{ steps.trivy-output.outputs.jvm_sarif }}",
    },
    "Trivy JVM SARIF freshness paths",
)
expect(
    security.step_scalar("trivy", "Assert fresh Trivy JVM SARIF path", "run"),
    "\n".join(
        [
            "set -euo pipefail",
            'test -d "$TRIVY_OUTPUT_DIR"',
            'test ! -L "$TRIVY_OUTPUT_DIR"',
            'test "$(dirname -- "$TRIVY_REPORT")" = "$TRIVY_OUTPUT_DIR"',
            'test ! -e "$TRIVY_REPORT"',
            'test ! -L "$TRIVY_REPORT"',
        ]
    ),
    "Trivy JVM SARIF pre-existing path rejection",
)
expect(
    security.step_scalar("trivy", "Trivy JVM runtime scan", "uses"),
    TRIVY_USES,
    "Trivy JVM runtime action pin",
)
expect(
    security.step_mapping("trivy", "Trivy JVM runtime scan", "with"),
    {
        "scan-type": "rootfs",
        "scan-ref": "app-bot/build/install/app-bot",
        "scanners": "vuln",
        "severity": "HIGH,CRITICAL",
        "limit-severities-for-sarif": "true",
        "trivyignores": ".trivyignore",
        "format": "sarif",
        "output": "${{ steps.trivy-output.outputs.jvm_sarif }}",
        "exit-code": "1",
        "version": "v0.69.3",
    },
    "Trivy JVM blocking scanner inputs",
)
expect(
    security.step_scalar("trivy", "Assert Trivy JVM runtime outputs", "id"),
    "trivy-jvm-output",
    "Trivy JVM output assertion id",
)
expect(
    security.step_scalar("trivy", "Assert Trivy JVM runtime outputs", "if"),
    "${{ always() && steps.trivy-output.outcome == 'success' }}",
    "Trivy JVM output assertion guard",
)
expect(
    security.step_mapping("trivy", "Assert Trivy JVM runtime outputs", "env"),
    {
        "TRIVY_OUTPUT_DIR": "${{ steps.trivy-output.outputs.directory }}",
        "TRIVY_INVENTORY": "${{ steps.trivy-output.outputs.jvm_inventory }}",
        "TRIVY_SARIF": "${{ steps.trivy-output.outputs.jvm_sarif }}",
    },
    "Trivy JVM output assertion paths",
)
expect(
    security.step_scalar("trivy", "Assert Trivy JVM runtime outputs", "run"),
    "\n".join(
        [
            "set -euo pipefail",
            'test -d "$TRIVY_OUTPUT_DIR"',
            'test ! -L "$TRIVY_OUTPUT_DIR"',
            'for report in "$TRIVY_INVENTORY" "$TRIVY_SARIF"; do',
            'test "$(dirname -- "$report")" = "$TRIVY_OUTPUT_DIR"',
            'test -f "$report"',
            'test ! -L "$report"',
            'test -s "$report"',
            "done",
        ]
    ),
    "fresh Trivy JVM output assertion",
)
expect(
    security.step_scalar(
        "trivy", "Upload Trivy JVM runtime SARIF to code scanning", "uses"
    ),
    UPLOAD_SARIF_USES,
    "Trivy JVM SARIF upload pin",
)
expect(
    security.step_scalar(
        "trivy", "Upload Trivy JVM runtime SARIF to code scanning", "if"
    ),
    "${{ always() && steps.trivy-jvm-output.outcome == 'success' }}",
    "Trivy JVM SARIF upload guard",
)
expect(
    security.step_mapping(
        "trivy", "Upload Trivy JVM runtime SARIF to code scanning", "with"
    ),
    {
        "sarif_file": "${{ steps.trivy-output.outputs.jvm_sarif }}",
        "checkout_path": "${{ github.workspace }}",
        "category": "trivy-jvm-runtime",
    },
    "Trivy JVM SARIF upload inputs",
)
expect(
    security.step_scalar(
        "trivy", "Persist Trivy JVM runtime report artifact", "uses"
    ),
    UPLOAD_ARTIFACT_USES,
    "Trivy JVM artifact pin",
)
expect(
    security.step_scalar(
        "trivy", "Persist Trivy JVM runtime report artifact", "if"
    ),
    "${{ always() && steps.trivy-jvm-output.outcome == 'success' }}",
    "Trivy JVM artifact guard",
)
expect(
    security.step_mapping(
        "trivy", "Persist Trivy JVM runtime report artifact", "with"
    ),
    {
        "name": "trivy-jvm-runtime-report",
        "path": (
            "${{ steps.trivy-output.outputs.jvm_inventory }}\n"
            "${{ steps.trivy-output.outputs.jvm_sarif }}"
        ),
        "if-no-files-found": "error",
    },
    "Trivy JVM artifact inputs",
)
for shell_step in (
    "Prepare trusted Trivy output directory",
    "Assert fresh Trivy filesystem output",
    "Assert fresh Trivy JVM inventory path",
    "Assert fresh Trivy JVM SARIF path",
    "Assert Trivy JVM runtime outputs",
):
    expect(
        security.step_scalar("trivy", shell_step, "shell"),
        "bash",
        f"{shell_step} shell",
    )
security.assert_no_fail_open("trivy", security_steps)
security.assert_steps_unguarded(
    "trivy",
    [
        "Checkout",
        "Set up JDK 21",
        "Gradle cache & setup",
        "Build resolved JVM runtime dependencies (blocking)",
        "Prepare trusted Trivy output directory",
        "Trivy filesystem scan",
        "Assert fresh Trivy JVM inventory path",
        "Inventory Trivy JVM runtime artifacts",
        "Verify Trivy JVM analyzer coverage",
        "Assert fresh Trivy JVM SARIF path",
        "Trivy JVM runtime scan",
    ],
)

for workflow in (dependency, sca, security):
    if "secrets." in "\n".join(workflow.lines):
        reject(f"keyless dependency workflow references a repository secret: {workflow.path}")
for forbidden in (
    "workflow_" + "run",
    "pull_request_" + "target",
    "download-and-" + "submit",
    "generate-and-" + "upload",
    "actions/dependency-" + "review-action@",
):
    for workflow in (dependency, sca):
        if forbidden in "\n".join(workflow.lines):
            reject(f"forbidden PR submission/review contract remains in {workflow.path}")
PY
}

validate_dependency_submission_verification_metadata() {
  local metadata_file="$1"

  python3 - "$metadata_file" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def reject(message):
    print(f"dependency-submission-metadata: {message}", file=sys.stderr)
    raise SystemExit(1)


path = Path(sys.argv[1])
try:
    root = ET.parse(path).getroot()
except (OSError, ET.ParseError) as error:
    reject(f"verification metadata is unreadable: {type(error).__name__}")

namespace = "https://schema.gradle.org/dependency-verification"
tag = lambda name: f"{{{namespace}}}{name}"
if root.tag != tag("verification-metadata"):
    reject("unexpected verification metadata root")
configuration = root.find(tag("configuration"))
if configuration is None:
    reject("configuration is missing")
if configuration.attrib:
    reject("configuration must not have attributes")
configuration_children = list(configuration)
if [child.tag for child in configuration_children] != [
    tag("verify-metadata"),
    tag("verify-signatures"),
]:
    reject("verification configuration must contain only the exact strict settings")
if any(child.attrib or list(child) for child in configuration_children):
    reject("verification configuration settings must be scalar")
verify_metadata, verify_signatures = configuration_children
if (verify_metadata.text or "").strip() != "true":
    reject("Gradle metadata verification must remain enabled")
if (verify_signatures.text or "").strip() != "false":
    reject("Gradle signature verification mode changed")
if root.findall(f".//{tag('trusted-artifacts')}") or root.findall(
    f".//{tag('trusting')}"
):
    reject("broad trusted artifact selectors are forbidden")
components = root.find(tag("components"))
if components is None:
    reject("components are missing")
matches = [
    component
    for component in components.findall(tag("component"))
    if component.attrib
    == {
        "group": "org.gradle",
        "name": "github-dependency-graph-gradle-plugin",
        "version": "1.4.1",
    }
]
if len(matches) != 1:
    reject("expected exactly one exact component for injected plugin 1.4.1")

expected = {
    "github-dependency-graph-gradle-plugin-1.4.1.jar": (
        "571cc95ec821649ca14c3895d637e8071af8f219f1f2aa4588cff3a95ea429c5"
    ),
    "github-dependency-graph-gradle-plugin-1.4.1.module": (
        "ed793bb8d17435a8b37260a5d72808b76db832e9ca58d136980e34336b2c18b2"
    ),
}
artifacts = matches[0].findall(tag("artifact"))
actual_names = [artifact.attrib.get("name") for artifact in artifacts]
if len(actual_names) != len(set(actual_names)):
    reject("injected plugin artifact entries are duplicated")
if set(actual_names) != set(expected):
    reject("injected plugin must verify exactly its runtime JAR and module metadata")
for artifact in artifacts:
    name = artifact.attrib.get("name")
    if artifact.attrib != {"name": name}:
        reject(f"unexpected artifact selector attributes for {name}")
    children = list(artifact)
    if len(children) != 1 or children[0].tag != tag("sha256"):
        reject(f"{name} must have exactly one SHA-256 checksum")
    checksum = children[0]
    if checksum.attrib != {
        "value": expected[name],
        "origin": "Verified from Gradle Plugin Portal",
    }:
        reject(f"{name} SHA-256 or provenance changed")
PY
}

validate_docker_workflow_contracts() {
  local repository_root="$1"

  if [ "$#" != "1" ]; then
    echo "workflow-capabilities: expected REPOSITORY_ROOT" >&2
    return 2
  fi

  # Structured effective permissions and credential capabilities are the trust root.
  # The validator literal scan is bounded defense-in-depth; it does not claim
  # arbitrary shell/interpreter semantic analysis.
  ruby "$repository_root/scripts/validate-workflow-capabilities.rb" "$repository_root"
}
validate_keyless_action_inventory() {
  local workflow_dir="$1"
  python3 - "$workflow_dir" <<'PY'
from pathlib import Path
import re
import sys

workflow_dir = Path(sys.argv[1])
approved = {
    "gradle/actions/dependency-submission": (
        "3f131e8634966bd73d06cc69884922b02e6faf92",
        "# v6.2.0",
        1,
    ),
}
counts = {name: 0 for name in approved}
for workflow in sorted(workflow_dir.glob("*.y*ml")):
    workflow_text = workflow.read_text(encoding="utf-8")
    for forbidden in (
        "workflow_" + "run:",
        "download-and-" + "submit",
        "generate-and-" + "upload",
        "actions/dependency-" + "review-action@",
    ):
        if forbidden in workflow_text:
            print(
                f"keyless-action-inventory: forbidden PR submission/review contract in {workflow.name}",
                file=sys.stderr,
            )
            raise SystemExit(1)
    for line in workflow_text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "uses:" not in stripped:
            continue
        match = re.fullmatch(r"uses:\s*([^@\s]+)@([^\s]+)(?:\s+(#.*))?", stripped)
        if not match:
            continue
        action, revision, comment = match.groups()
        if action == "actions/dependency-" + "review-action":
            print(
                f"keyless-action-inventory: Dependency Review is forbidden in {workflow.name}",
                file=sys.stderr,
            )
            raise SystemExit(1)
        if action not in approved:
            continue
        expected_revision, expected_comment, expected_count = approved[action]
        if revision != expected_revision or (comment or "") != expected_comment:
            print(
                f"keyless-action-inventory: unapproved reference for {action} in {workflow.name}",
                file=sys.stderr,
            )
            raise SystemExit(1)
        counts[action] += 1
for action, (_, _, expected_count) in approved.items():
    if counts[action] != expected_count:
        print(
            f"keyless-action-inventory: expected {expected_count} references for {action}, "
            f"found {counts[action]}",
            file=sys.stderr,
        )
        raise SystemExit(1)
PY
}

validate_removed_nvd_owasp_inventory() {
  local root_dir="$1"
  python3 - "$root_dir" <<'PY'
from pathlib import Path
import subprocess
import sys

root = Path(sys.argv[1])
forbidden = (
    "NV" + "D_API_KEY",
    "org.owasp." + "dependencycheck",
    "dependency" + "Check",
    "Dependency" + "CheckExtension",
    "dependency" + "CheckAggregate",
    "dependency" + "CheckUpdate",
    "sca" + "Check",
    "sca" + "Preflight",
    "sca" + "WarmCacheMark",
    "dependency" + "CheckDataDir",
    "cache-warm." + "marker",
    "cache-warm." + "manifest",
    "dependency-check-" + "data",
)
if (root / ".git").exists():
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=root,
        check=True,
        stdout=subprocess.PIPE,
    )
    relative_paths = [
        raw_relative.decode("utf-8", errors="strict")
        for raw_relative in result.stdout.split(b"\0")
        if raw_relative
    ]
else:
    relative_paths = [
        path.relative_to(root).as_posix()
        for path in root.rglob("*")
        if path.is_file()
    ]
violations = []
for relative in relative_paths:
    path = root / relative
    if not path.is_file():
        continue
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue
    matched = [token for token in forbidden if token in text]
    if matched:
        violations.append(relative)
if violations:
    print(
        "legacy-sca-inventory: forbidden NVD/OWASP contract remains in: "
        + ", ".join(sorted(violations)),
        file=sys.stderr,
    )
    raise SystemExit(1)
PY
}

validate_resolved_graph_build_contract() {
  local build_file="$1"
  python3 - "$build_file" <<'PY'
from pathlib import Path
import re
import sys

build_file = Path(sys.argv[1])
text = build_file.read_text(encoding="utf-8")
required_contracts = {
    'pluginManager.withPlugin("java")': "JVM projects are not discovered dynamically",
    ".filterIsInstance<UnresolvedDependencyResult>()": (
        "unresolved runtime dependencies are not rejected"
    ),
    "resolutionResult.allDependencies": "the complete dependency result set is not inspected",
    "runtimeArtifactFiles.from(runtimeClasspath)": (
        "runtime artifact input does not preserve runtimeClasspath build dependencies"
    ),
    "runtimeArtifactFiles.files": "task-owned runtime artifact input is not inspected",
    '"resolvedArtifacts" to resolvedArtifactFiles.size': "resolved artifacts are not recorded",
    "val jvmProjectPath = project.path": "JVM project identity is not captured dynamically",
    "expectedProjectPaths.add(jvmProjectPath)": "expected JVM project identities are not dynamic",
    "projectGraphFiles.from(resolvedProjectDependencyGraph.flatMap": (
        "per-project reports are not wired dynamically"
    ),
}
for contract, message in required_contracts.items():
    if contract not in text:
        raise SystemExit(f"resolved-graph-build-contract: {message}")

task_match = re.search(
    r"abstract class ResolvedProjectDependencyGraph\s*:\s*DefaultTask\(\)\s*\{(.*?)\n\}",
    text,
    flags=re.DOTALL,
)
if task_match is None or not re.search(
    r"@get:Classpath\s+abstract val runtimeArtifactFiles:\s*ConfigurableFileCollection",
    task_match.group(1),
):
    raise SystemExit(
        "resolved-graph-build-contract: runtime artifacts are not a declared classpath input"
    )
if "runtimeConfiguration.incoming.artifacts.artifactFiles.files" in text:
    raise SystemExit(
        "resolved-graph-build-contract: detached runtime artifact resolution bypasses task inputs"
    )

current_projects = {
    ":app-bot",
    ":core-data",
    ":core-domain",
    ":core-security",
    ":core-telemetry",
    ":core-testing",
    ":tools:perf",
}
for match in re.finditer(r"listOf\s*\((.*?)\)", text, flags=re.DOTALL):
    literals = set(re.findall(r'["\'](:[^"\']+)["\']', match.group(1)))
    if current_projects.issubset(literals):
        raise SystemExit("resolved-graph-build-contract: current seven-module allowlist is forbidden")
PY
}

assert_validation_rejected() {
  local fixture_name="$1"
  shift
  local fixture_log="$TMP_DIR/$fixture_name.log"
  local fixture_status

  if ( "$@" ) >"$fixture_log" 2>&1; then
    fail "negative fixture unexpectedly passed: $fixture_name"
  else
    fixture_status=$?
  fi
  echo "quality-gate: negative fixture $fixture_name rejected (exit $fixture_status)"
}

assert_workflow_yaml_rejected() {
  local fixture_name="$1"
  local fixture_root="$2"
  local expected_diagnostic="$3"
  local fixture_log="$TMP_DIR/$fixture_name.log"
  local fixture_status
  local fixture_output

  if ruby "$WORKFLOW_YAML_VALIDATOR" "$fixture_root" >"$fixture_log" 2>&1; then
    fail "negative fixture unexpectedly passed: $fixture_name"
  else
    fixture_status=$?
  fi
  assert_eq "$fixture_status" "1"
  fixture_output="$(cat "$fixture_log")"
  assert_contains "$fixture_output" "$expected_diagnostic"
  echo "quality-gate: negative fixture $fixture_name rejected (exit $fixture_status)"
}

assert_workflow_yaml_cli_rejected() {
  local fixture_name="$1"
  shift
  local fixture_log="$TMP_DIR/$fixture_name.log"
  local fixture_status
  local fixture_output

  if ruby "$WORKFLOW_YAML_VALIDATOR" "$@" >"$fixture_log" 2>&1; then
    fail "negative fixture unexpectedly passed: $fixture_name"
  else
    fixture_status=$?
  fi
  assert_eq "$fixture_status" "2"
  fixture_output="$(cat "$fixture_log")"
  assert_contains "$fixture_output" "usage: validate-workflow-yaml.rb [repository-root]"
  echo "quality-gate: negative fixture $fixture_name rejected (exit $fixture_status)"
}

write_native_gradle_repository_settings() {
  local fixture_root="$1"
  mkdir -p "$fixture_root"
  cat >"$fixture_root/settings.gradle.kts" <<'KOTLIN'
import org.gradle.api.initialization.resolve.RepositoriesMode

rootProject.name = "repository-policy-fixture"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
KOTLIN
}

assert_native_gradle_repository_policy_rejected() {
  local fixture_name="$1"
  local fixture_root="$2"
  local expected_build_file="$3"
  local fixture_log="$TMP_DIR/$fixture_name.log"
  local fixture_status
  local fixture_output

  if "$ROOT_DIR/gradlew" \
    -p "$fixture_root" \
    help \
    --dependency-verification=strict \
    --no-configuration-cache \
    --no-build-cache \
    --console=plain >"$fixture_log" 2>&1; then
    fail "native Gradle repository-policy fixture unexpectedly passed: $fixture_name"
  else
    fixture_status=$?
  fi

  assert_eq "$fixture_status" "1"
  fixture_output="$(cat "$fixture_log")"
  assert_contains \
    "$fixture_output" \
    "Build was configured to prefer settings repositories over project repositories"
  assert_contains "$fixture_output" "was added by build file '$expected_build_file'"
  assert_not_contains "$fixture_output" "Script compilation error"
  assert_not_contains "$fixture_output" "Unresolved reference"
  echo "quality-gate: native Gradle fixture $fixture_name rejected (exit $fixture_status)"
}

assert_native_gradle_repository_policy_accepted() {
  local fixture_name="$1"
  local fixture_root="$2"
  local fixture_log="$TMP_DIR/$fixture_name.log"
  local fixture_status
  local fixture_output

  if "$ROOT_DIR/gradlew" \
    -p "$fixture_root" \
    help \
    --dependency-verification=strict \
    --no-configuration-cache \
    --no-build-cache \
    --console=plain >"$fixture_log" 2>&1; then
    fixture_status=0
  else
    fixture_status=$?
  fi

  assert_eq "$fixture_status" "0"
  fixture_output="$(cat "$fixture_log")"
  assert_contains "$fixture_output" "BUILD SUCCESSFUL"
  echo "quality-gate: native Gradle fixture $fixture_name accepted (exit $fixture_status)"
}

assert_tests_workflow_contract_rejected() {
  local fixture_name="$1"
  local fixture_file="$2"
  local fixture_root="$TMP_DIR/$fixture_name-repository"
  local fixture_workflow="$fixture_root/.github/workflows/tests.yml"
  local fixture_log="$TMP_DIR/$fixture_name.log"
  local fixture_status

  git init -q "$fixture_root"
  mkdir -p "$fixture_root/.github/workflows"
  cp "$fixture_file" "$fixture_workflow"
  git -C "$fixture_root" add .github/workflows/tests.yml
  assert_eq \
    "$(git -C "$fixture_root" ls-files)" \
    ".github/workflows/tests.yml"
  if ! ruby "$WORKFLOW_YAML_VALIDATOR" "$fixture_root" \
    >"$TMP_DIR/$fixture_name-yaml.log" 2>&1; then
    fail "workflow contract fixture is not valid YAML: $fixture_name"
  fi

  if (validate_tests_workflow_contract "$fixture_workflow") \
    >"$fixture_log" 2>&1; then
    fail "Tests workflow contract fixture unexpectedly passed: $fixture_name"
  else
    fixture_status=$?
  fi
  assert_eq "$fixture_status" "1"
  assert_contains "$(cat "$fixture_log")" "selfcheck:"
  echo "quality-gate: Tests workflow fixture $fixture_name rejected (exit $fixture_status)"
}

replace_exact_line_once() {
  local source_file="$1"
  local target_file="$2"
  local expected="$3"
  local replacement="$4"
  python3 - "$source_file" "$target_file" "$expected" "$replacement" <<'PY'
import os
from pathlib import Path
import stat
import sys
import tempfile

HELPER_NAME = "replace_exact_line_once"

source_file = Path(sys.argv[1])
target_file = Path(sys.argv[2])
expected = os.fsencode(sys.argv[3])
replacement = os.fsencode(sys.argv[4])
expected_description = f"exact-line(length={len(expected)})"


def fail(message):
    print(f"{HELPER_NAME}: {message}", file=sys.stderr)
    raise SystemExit(1)


def split_line_ending(line):
    if line.endswith(b"\r\n"):
        return line[:-2], b"\r\n"
    if line.endswith(b"\n") or line.endswith(b"\r"):
        return line[:-1], line[-1:]
    return line, b""


if b"\n" in expected or b"\r" in expected:
    fail(
        f"source={source_file} expected={expected_description} "
        "match_count=not-evaluated reason=expected-is-not-one-line"
    )
if b"\n" in replacement or b"\r" in replacement:
    fail(
        f"source={source_file} expected={expected_description} "
        "match_count=not-evaluated reason=replacement-is-not-one-line"
    )

try:
    source_bytes = source_file.read_bytes()
    source_mode = stat.S_IMODE(source_file.stat().st_mode)
except OSError as error:
    fail(
        f"source={source_file} expected={expected_description} "
        f"match_count=not-evaluated reason=source-read-failed:{error.strerror}"
    )

lines = source_bytes.splitlines(keepends=True)
matching_indexes = [
    index
    for index, line in enumerate(lines)
    if split_line_ending(line)[0] == expected
]
match_count = len(matching_indexes)
if match_count != 1:
    fail(
        f"source={source_file} expected={expected_description} "
        f"match_count={match_count}"
    )

matching_index = matching_indexes[0]
mutated_lines = []
for index, line in enumerate(lines):
    if index == matching_index:
        _, line_ending = split_line_ending(line)
        mutated_lines.append(replacement + line_ending)
    else:
        mutated_lines.append(line)
mutated_bytes = b"".join(mutated_lines)

temporary_path = None
try:
    with tempfile.NamedTemporaryFile(
        mode="wb",
        dir=target_file.parent,
        prefix=f".{target_file.name}.",
        delete=False,
    ) as temporary_file:
        temporary_path = Path(temporary_file.name)
        temporary_file.write(mutated_bytes)
        temporary_file.flush()
        os.fsync(temporary_file.fileno())
    os.chmod(temporary_path, source_mode)
    os.replace(temporary_path, target_file)
    temporary_path = None
except OSError as error:
    fail(
        f"source={source_file} expected={expected_description} "
        f"match_count={match_count} reason=target-write-failed:{error.strerror}"
    )
finally:
    if temporary_path is not None:
        try:
            temporary_path.unlink()
        except FileNotFoundError:
            pass
PY
}

assert_replace_exact_line_rejected() {
  local fixture_name="$1"
  local source_file="$2"
  local target_file="$3"
  local expected="$4"
  local replacement="$5"
  local expected_match_count="$6"
  local fixture_log="$TMP_DIR/$fixture_name.log"
  local fixture_output
  local fixture_status

  if replace_exact_line_once \
    "$source_file" \
    "$target_file" \
    "$expected" \
    "$replacement" >"$fixture_log" 2>&1; then
    fail "negative fixture unexpectedly passed: $fixture_name"
  else
    fixture_status=$?
  fi

  assert_eq "$fixture_status" "1"
  fixture_output="$(cat "$fixture_log")"
  assert_contains "$fixture_output" "replace_exact_line_once:"
  assert_contains "$fixture_output" "source=$source_file"
  assert_contains "$fixture_output" "expected=exact-line(length="
  assert_contains "$fixture_output" "match_count=$expected_match_count"
  assert_not_contains "$fixture_output" "exit 42"
  echo "quality-gate: negative fixture $fixture_name rejected (exit $fixture_status)"
}

insert_job_direct_line() {
  local source_file="$1"
  local target_file="$2"
  local job_name="$3"
  local insertion="$4"
  awk -v target="  $job_name:" -v insertion="$insertion" '
    $0 == target {
      jobs++
      print
      print insertion
      inserted++
      next
    }
    { print }
    END {
      if (jobs != 1 || inserted != 1) {
        exit 42
      }
    }
  ' "$source_file" >"$target_file"
}

replace_job_line_once() {
  local source_file="$1"
  local target_file="$2"
  local job_name="$3"
  local expected="$4"
  local replacement="$5"

  python3 - \
    "$source_file" \
    "$target_file" \
    "$job_name" \
    "$expected" \
    "$replacement" <<'PY'
import os
from pathlib import Path
import tempfile
import sys

source = Path(sys.argv[1])
target = Path(sys.argv[2])
job_name = sys.argv[3]
expected = sys.argv[4]
replacement = sys.argv[5]
lines = source.read_text(encoding="utf-8").splitlines(keepends=True)
job_header = f"  {job_name}:"
job_indexes = [
    index
    for index, line in enumerate(lines)
    if line.rstrip("\r\n") == job_header
]
if len(job_indexes) != 1:
    raise SystemExit(
        f"replace_job_line_once: expected one job {job_name!r}; found {len(job_indexes)}",
    )
job_start = job_indexes[0]
job_end = len(lines)
for index in range(job_start + 1, len(lines)):
    logical = lines[index].rstrip("\r\n")
    if logical and not logical.lstrip().startswith("#"):
        indentation = len(logical) - len(logical.lstrip(" "))
        if indentation <= 2:
            job_end = index
            break
matches = [
    index
    for index in range(job_start + 1, job_end)
    if lines[index].rstrip("\r\n") == expected
]
if len(matches) != 1:
    raise SystemExit(
        "replace_job_line_once: "
        f"job={job_name!r} expected-line-count={len(matches)}",
    )
match = matches[0]
line_ending = "\r\n" if lines[match].endswith("\r\n") else "\n"
if not lines[match].endswith(("\r", "\n")):
    line_ending = ""
lines[match] = replacement + line_ending
target.parent.mkdir(parents=True, exist_ok=True)
temporary_name = None
try:
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        newline="",
        dir=target.parent,
        prefix=f".{target.name}.",
        delete=False,
    ) as temporary:
        temporary_name = temporary.name
        temporary.writelines(lines)
        temporary.flush()
        os.fsync(temporary.fileno())
    os.replace(temporary_name, target)
    temporary_name = None
finally:
    if temporary_name is not None:
        Path(temporary_name).unlink(missing_ok=True)
PY
}

insert_step_direct_line() {
  local source_file="$1"
  local target_file="$2"
  local job_name="$3"
  local step_name="$4"
  local insertion="$5"
  awk \
    -v job_target="  $job_name:" \
    -v step_target="      - name: $step_name" \
    -v insertion="$insertion" '
      $0 == job_target {
        in_job = 1
        jobs++
        print
        next
      }
      in_job && /^  [[:alnum:]_-]+:[[:space:]]*$/ {
        in_job = 0
      }
      in_job && $0 == step_target {
        steps++
        print
        print insertion
        inserted++
        next
      }
      { print }
      END {
        if (jobs != 1 || steps != 1 || inserted != 1) {
          exit 42
        }
      }
    ' "$source_file" >"$target_file"
}

remove_workflow_step_once() {
  local source_file="$1"
  local target_file="$2"
  local job_name="$3"
  local step_name="$4"

  python3 - "$source_file" "$target_file" "$job_name" "$step_name" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1])
target = Path(sys.argv[2])
job_name = sys.argv[3]
step_name = sys.argv[4]
lines = source.read_text(encoding="utf-8").splitlines(keepends=True)

job_header = f"  {job_name}:"
job_indexes = [
    index for index, line in enumerate(lines) if line.rstrip("\r\n") == job_header
]
if len(job_indexes) != 1:
    raise SystemExit(
        f"remove_workflow_step_once: expected one job {job_name!r}; found {len(job_indexes)}"
    )
job_start = job_indexes[0]
job_end = len(lines)
for index in range(job_start + 1, len(lines)):
    logical = lines[index].rstrip("\r\n")
    if logical and not logical.lstrip().startswith("#"):
        indentation = len(logical) - len(logical.lstrip(" "))
        if indentation <= 2:
            job_end = index
            break

step_header = f"      - name: {step_name}"
step_indexes = [
    index
    for index in range(job_start + 1, job_end)
    if lines[index].rstrip("\r\n") == step_header
]
if len(step_indexes) != 1:
    raise SystemExit(
        "remove_workflow_step_once: "
        f"expected one step {step_name!r}; found {len(step_indexes)}"
    )
step_start = step_indexes[0]
step_end = job_end
for index in range(step_start + 1, job_end):
    if lines[index].startswith("      - name: "):
        step_end = index
        break

target.write_text("".join(lines[:step_start] + lines[step_end:]), encoding="utf-8")
PY
}

replace_step_line_once() {
  local source_file="$1"
  local target_file="$2"
  local job_name="$3"
  local step_name="$4"
  local expected="$5"
  local replacement="$6"
  awk \
    -v job_target="  $job_name:" \
    -v step_target="      - name: $step_name" \
    -v expected="$expected" \
    -v replacement="$replacement" '
      function indentation(line, indent) {
        indent = line
        sub(/[^ ].*$/, "", indent)
        return length(indent)
      }
      function is_content(line) {
        return line !~ /^[[:space:]]*($|#)/
      }
      $0 == job_target {
        in_job = 1
        jobs++
        print
        next
      }
      in_job && /^  [[:alnum:]_-]+:[[:space:]]*$/ {
        in_job = 0
        in_step = 0
      }
      in_job && $0 == step_target {
        in_step = 1
        steps++
        print
        next
      }
      in_step && is_content($0) && indentation($0) <= 6 {
        in_step = 0
      }
      in_step && $0 == expected {
        matches++
        print replacement
        next
      }
      { print }
      END {
        if (jobs != 1 || steps != 1 || matches != 1) {
          exit 42
        }
      }
    ' "$source_file" >"$target_file"
}

insert_anchored_step_direct_line() {
  local source_file="$1"
  local target_file="$2"
  local anchor_name="$3"
  local step_name="$4"
  local insertion="$5"
  awk \
    -v anchor_target="      - &$anchor_name" \
    -v name_target="        name: \"$step_name\"" \
    -v insertion="$insertion" '
      $0 == anchor_target {
        in_step = 1
        anchors++
        print
        next
      }
      in_step && /^      - / { in_step = 0 }
      in_step && $0 == name_target {
        names++
        print
        print insertion
        inserted++
        next
      }
      { print }
      END {
        if (anchors != 1 || names != 1 || inserted != 1) {
          exit 42
        }
      }
    ' "$source_file" >"$target_file"
}

remove_tests_repository_guard() {
  local source_file="$1"
  local target_file="$2"
  python3 - "$source_file" "$target_file" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1])
target = Path(sys.argv[2])
lines = source.read_text(encoding="utf-8").splitlines(keepends=True)
anchor = "      - &guard-no-project-repositories"
alias = "      - *guard-no-project-repositories"
anchor_indexes = [
    index for index, line in enumerate(lines) if line.rstrip("\r\n") == anchor
]
alias_indexes = [
    index for index, line in enumerate(lines) if line.rstrip("\r\n") == alias
]
if len(anchor_indexes) != 1 or len(alias_indexes) != 1:
    raise SystemExit("remove_tests_repository_guard: guard anchor/alias is ambiguous")

anchor_start = anchor_indexes[0]
anchor_end = len(lines)
for index in range(anchor_start + 1, len(lines)):
    logical = lines[index].rstrip("\r\n")
    if logical.startswith("      - "):
        anchor_end = index
        break

removed = lines[anchor_start:anchor_end]
if not any("Guard: no project-level repositories" in line for line in removed):
    raise SystemExit("remove_tests_repository_guard: target step name not found")

alias_index = alias_indexes[0]
kept = [
    line
    for index, line in enumerate(lines)
    if not (anchor_start <= index < anchor_end) and index != alias_index
]
target.write_text("".join(kept), encoding="utf-8")
PY
}

move_exact_line_before() {
  local source_file="$1"
  local target_file="$2"
  local moving_line="$3"
  local before_line="$4"
  python3 - "$source_file" "$target_file" "$moving_line" "$before_line" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1])
target = Path(sys.argv[2])
moving_line = sys.argv[3]
before_line = sys.argv[4]
lines = source.read_text(encoding="utf-8").splitlines(keepends=True)

moving = [
    index for index, line in enumerate(lines) if line.rstrip("\r\n") == moving_line
]
before = [
    index for index, line in enumerate(lines) if line.rstrip("\r\n") == before_line
]
if len(moving) != 1 or len(before) != 1:
    raise SystemExit("move_exact_line_before: moving/target line is ambiguous")

line = lines.pop(moving[0])
before_index = next(
    index for index, candidate in enumerate(lines)
    if candidate.rstrip("\r\n") == before_line
)
lines.insert(before_index, line)
target.write_text("".join(lines), encoding="utf-8")
PY
}

move_build_labels_outside_with() {
  local source_file="$1"
  local target_file="$2"
  local build_step_name="$3"
  local expected="$4"
  awk \
    -v target="      - name: $build_step_name" \
    -v expected="$expected" '
    $0 == target {
      in_build_step = 1
      steps++
      print
      next
    }
    in_build_step && $0 == "        with:" {
      print "        env:"
      print expected
      print
      inserted++
      next
    }
    in_build_step && $0 == expected {
      removed++
      next
    }
    in_build_step && /^      - / {
      in_build_step = 0
    }
    { print }
    END {
      if (steps != 1 || removed != 1 || inserted != 1) {
        exit 42
      }
    }
  ' "$source_file" >"$target_file"
}

mutate_publish_provenance_guard() {
  local source_file="$1"
  local target_file="$2"
  local move_to_step="$3"
  awk -v move_to_step="$move_to_step" '
    BEGIN {
      target = "  verify-and-provenance:"
      guard = "    if: github.event_name != '\''pull_request'\''"
      step_guard = "        if: github.event_name != '\''pull_request'\''"
    }
    $0 == target {
      in_target_job = 1
      jobs++
      print
      next
    }
    in_target_job && /^  [[:alnum:]_-]+:[[:space:]]*$/ {
      in_target_job = 0
    }
    in_target_job && $0 == guard {
      guards++
      next
    }
    in_target_job && move_to_step == "yes" && !inserted && /^      - name:/ {
      print
      print step_guard
      inserted = 1
      next
    }
    { print }
    END {
      if (jobs != 1 || guards != 1 || (move_to_step == "yes" && inserted != 1)) {
        exit 42
      }
    }
  ' "$source_file" >"$target_file"
}

replace_helper_source_fixture="$TMP_DIR/replace-exact-line-source"
replace_helper_plain_fixture="$TMP_DIR/replace-exact-line-plain"
replace_helper_expression_fixture="$TMP_DIR/replace-exact-line-expression"
replace_helper_quotes_fixture="$TMP_DIR/replace-exact-line-quotes"
replace_helper_backslash_fixture="$TMP_DIR/replace-exact-line-backslash"
replace_helper_expected_fixture="$TMP_DIR/replace-exact-line-expected"
printf '%s\n' \
  "ordinary=before" \
  '${{ steps.meta.outputs.tags }}' \
  'quoted="before value"' \
  'trailing-backslash=before \' >"$replace_helper_source_fixture"

replace_exact_line_once \
  "$replace_helper_source_fixture" \
  "$replace_helper_plain_fixture" \
  "ordinary=before" \
  "ordinary=after"
replace_exact_line_once \
  "$replace_helper_plain_fixture" \
  "$replace_helper_expression_fixture" \
  '${{ steps.meta.outputs.tags }}' \
  '${{ steps.metadata.outputs.tags }}'
replace_exact_line_once \
  "$replace_helper_expression_fixture" \
  "$replace_helper_quotes_fixture" \
  'quoted="before value"' \
  'quoted="after value"'
replace_exact_line_once \
  "$replace_helper_quotes_fixture" \
  "$replace_helper_backslash_fixture" \
  'trailing-backslash=before \' \
  'trailing-backslash=after \'

printf '%s\n' \
  "ordinary=after" \
  '${{ steps.metadata.outputs.tags }}' \
  'quoted="after value"' \
  'trailing-backslash=after \' >"$replace_helper_expected_fixture"
if ! cmp -s "$replace_helper_backslash_fixture" "$replace_helper_expected_fixture"; then
  fail "replace_exact_line_once did not preserve the positive fixture byte-for-byte"
fi
echo "quality-gate: exact-line mutation helper positive fixtures verified"

replace_helper_zero_target="$TMP_DIR/replace-exact-line-zero-target"
assert_replace_exact_line_rejected \
  "replace-exact-line-zero-match" \
  "$replace_helper_source_fixture" \
  "$replace_helper_zero_target" \
  "ordinary=missing" \
  "ordinary=after" \
  "0"
if [ -e "$replace_helper_zero_target" ]; then
  fail "replace_exact_line_once created a target after zero matches"
fi

replace_helper_duplicate_source="$TMP_DIR/replace-exact-line-duplicate-source"
replace_helper_duplicate_target="$TMP_DIR/replace-exact-line-duplicate-target"
replace_helper_duplicate_baseline="$TMP_DIR/replace-exact-line-duplicate-baseline"
printf '%s\n' \
  "duplicate=before" \
  "middle=unchanged" \
  "duplicate=before" >"$replace_helper_duplicate_source"
printf '%s\n' "target=sentinel" >"$replace_helper_duplicate_target"
cp "$replace_helper_duplicate_target" "$replace_helper_duplicate_baseline"
assert_replace_exact_line_rejected \
  "replace-exact-line-duplicate-match" \
  "$replace_helper_duplicate_source" \
  "$replace_helper_duplicate_target" \
  "duplicate=before" \
  "duplicate=after" \
  "2"
if ! cmp -s "$replace_helper_duplicate_target" "$replace_helper_duplicate_baseline"; then
  fail "replace_exact_line_once modified the target after duplicate matches"
fi
echo "quality-gate: exact-line mutation helper failure atomicity verified"

non_pr_expression="\${{ github.event_name != 'pull_request' }}"
non_pr_if="github.event_name != 'pull_request'"
metadata_tags_expression="\${{ steps.meta.outputs.tags }}"
metadata_labels_expression="\${{ steps.meta.outputs.labels }}"
docker_image_workflow="$ROOT_DIR/.github/workflows/docker-image.yml"
docker_publish_workflow="$ROOT_DIR/.github/workflows/docker-publish.yml"
security_scan_workflow="$ROOT_DIR/.github/workflows/security-scan.yml"
dependency_submission_workflow="$ROOT_DIR/.github/workflows/dependency-submission.yml"
sca_workflow="$ROOT_DIR/.github/workflows/sca.yml"
container_smoke_workflow="$ROOT_DIR/.github/workflows/container-smoke.yml"
static_check_workflow="$ROOT_DIR/.github/workflows/static-check.yml"
tests_workflow="$ROOT_DIR/.github/workflows/tests.yml"
detekt_permissions_contract='contents: read
security-events: write'
detekt_html_report_guard="\${{ always() && hashFiles('**/build/reports/detekt/detekt*/detekt.html') != '' }}"
detekt_sarif_report_guard="\${{ always() && steps.detekt-sarif-collector.outcome == 'success' && hashFiles('build/reports/detekt/combined/detekt.sarif') != '' }}"
detekt_html_with_contract='name: detekt-html
path: "**/build/reports/detekt/detekt*/detekt.html"
if-no-files-found: error'
detekt_sarif_with_contract='sarif_file: build/reports/detekt/combined/detekt.sarif'
approved_trivy_action_active_line="uses: aquasecurity/trivy-action@57a97c7e7821a5776cebc9bb87c984fa69cba8f1 # v0.35.0, post-incident safe release"
trivy_filesystem_report_guard="\${{ always() && steps.trivy-filesystem-output.outcome == 'success' }}"
trivy_runtime_report_guard="\${{ always() && steps.trivy-jvm-output.outcome == 'success' }}"
trivy_runtime_artifact_guard="$trivy_runtime_report_guard"
trivy_image_report_guard="\${{ always() && hashFiles('trivy-image-results.sarif') != '' }}"
trivy_filesystem_with_contract='scan-type: fs
scan-ref: .
severity: HIGH,CRITICAL
limit-severities-for-sarif: true
trivyignores: .trivyignore
format: sarif
output: ${{ steps.trivy-output.outputs.filesystem_sarif }}
exit-code: 1
version: v0.69.3'
trivy_runtime_inventory_with_contract='scan-type: rootfs
scan-ref: app-bot/build/install/app-bot
scanners: vuln
list-all-pkgs: true
trivyignores: .trivyignore
format: json
output: ${{ steps.trivy-output.outputs.jvm_inventory }}
exit-code: 0
version: v0.69.3'
trivy_runtime_with_contract='scan-type: rootfs
scan-ref: app-bot/build/install/app-bot
scanners: vuln
severity: HIGH,CRITICAL
limit-severities-for-sarif: true
trivyignores: .trivyignore
format: sarif
output: ${{ steps.trivy-output.outputs.jvm_sarif }}
exit-code: 1
version: v0.69.3'
trivy_image_with_contract='scan-type: image
image-ref: ${{ needs.build-and-push.outputs.image-ref }}
severity: HIGH,CRITICAL
limit-severities-for-sarif: true
trivyignores: .trivyignore
format: sarif
output: trivy-image-results.sarif
exit-code: 1
version: v0.69.3'
trivy_filesystem_sarif_with_contract='sarif_file: ${{ steps.trivy-output.outputs.filesystem_sarif }}
checkout_path: ${{ github.workspace }}
category: trivy-filesystem'
trivy_runtime_sarif_with_contract='sarif_file: ${{ steps.trivy-output.outputs.jvm_sarif }}
checkout_path: ${{ github.workspace }}
category: trivy-jvm-runtime'
trivy_image_sarif_with_contract='sarif_file: trivy-image-results.sarif
category: trivy-release-image'
trivy_filesystem_artifact_with_contract='name: trivy-filesystem-report
path: ${{ steps.trivy-output.outputs.filesystem_sarif }}
if-no-files-found: error'
trivy_runtime_artifact_with_contract='name: trivy-jvm-runtime-report
path: |
${{ steps.trivy-output.outputs.jvm_inventory }}
${{ steps.trivy-output.outputs.jvm_sarif }}
if-no-files-found: error'
trivy_image_artifact_with_contract='name: trivy-image-report
path: trivy-image-results.sarif
if-no-files-found: error'
container_smoke_run_contract='docker run -d --name app-bot-ci \
--network "${{ job.services.postgres.network }}" \
-p 8080:8080 \
-e APP_PROFILE="DEV" \
-e RBAC_ENABLED="true" \
-e DATABASE_URL="jdbc:postgresql://postgres:5432/botdb" \
-e DATABASE_USER="botuser" \
-e DATABASE_PASSWORD="botpass" \
-e TELEGRAM_BOT_TOKEN="000000:TEST" \
-e WEBHOOK_SECRET_TOKEN="test" \
-e OWNER_TELEGRAM_ID="0" \
-e HQ_CHAT_ID="0" \
-e CLUB1_CHAT_ID="0" \
-e CLUB2_CHAT_ID="0" \
-e CLUB3_CHAT_ID="0" \
-e CLUB4_CHAT_ID="0" \
app-bot:ci'
container_smoke_ready_contract='for i in {1..60}; do
if curl -fsS http://127.0.0.1:8080/ready >/dev/null; then
exit 0
fi
sleep 1
done
echo "ready failed" && exit 1'
container_smoke_health_contract='for i in {1..60}; do
if curl -fsS http://127.0.0.1:8080/health >/dev/null; then
exit 0
fi
sleep 1
done
echo "health failed" && exit 1'

workflow_cli_default_output="$(ruby "$WORKFLOW_YAML_VALIDATOR")"
assert_contains \
  "$workflow_cli_default_output" \
  "quality-gate: workflow YAML syntax verified ("
workflow_cli_explicit_output="$(ruby "$WORKFLOW_YAML_VALIDATOR" "$ROOT_DIR")"
assert_contains \
  "$workflow_cli_explicit_output" \
  "quality-gate: workflow YAML syntax verified ("
echo "quality-gate: workflow YAML validator accepts zero or one repository-root argument"

assert_workflow_yaml_cli_rejected \
  "workflow-yaml-cli-extra-argument" \
  "$ROOT_DIR" \
  "unexpected"
assert_workflow_yaml_cli_rejected \
  "workflow-yaml-cli-two-extra-arguments" \
  "$ROOT_DIR" \
  "unexpected" \
  "another"
assert_workflow_yaml_cli_rejected \
  "workflow-yaml-cli-unknown-flag" \
  "$ROOT_DIR" \
  "--unknown"
assert_workflow_yaml_cli_rejected \
  "workflow-yaml-cli-empty-extra-argument" \
  "$ROOT_DIR" \
  ""

validate_tests_workflow_contract "$tests_workflow"
echo "quality-gate: Tests workflow unit/integration contract verified"

root_settings_file="$ROOT_DIR/settings.gradle.kts"
buildsrc_build_file="$ROOT_DIR/buildSrc/build.gradle.kts"
buildsrc_settings_file="$ROOT_DIR/buildSrc/settings.gradle.kts"

assert_exact_line \
  "$root_settings_file" \
  "import org.gradle.api.initialization.resolve.RepositoriesMode"
assert_exact_line "$root_settings_file" "dependencyResolutionManagement {"
assert_exact_line \
  "$root_settings_file" \
  "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)"
assert_exact_line_count "$root_settings_file" "        mavenCentral()" "2"
assert_exact_line_count \
  "$root_settings_file" \
  '        maven("https://maven-central.storage-download.googleapis.com/maven2") {' \
  "2"
if grep -Eq 'mavenLocal\(|http://|credentials[[:space:]]*\{' "$root_settings_file"; then
  fail "root settings contain an unapproved repository contract"
fi

assert_exact_line \
  "$buildsrc_settings_file" \
  "import org.gradle.api.initialization.resolve.RepositoriesMode"
assert_exact_line "$buildsrc_settings_file" 'rootProject.name = "buildSrc"'
assert_exact_line "$buildsrc_settings_file" "pluginManagement {"
assert_exact_line "$buildsrc_settings_file" "dependencyResolutionManagement {"
assert_exact_line \
  "$buildsrc_settings_file" \
  "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)"
assert_exact_line "$buildsrc_settings_file" "        gradlePluginPortal()"
assert_exact_line "$buildsrc_settings_file" "        mavenCentral()"
buildsrc_build_contract="$(cat "$buildsrc_build_file")"
assert_eq "$buildsrc_build_contract" $'plugins {\n    `kotlin-dsl`\n}'
if grep -Eq 'https?://|mavenLocal\(|credentials[[:space:]]*\{' "$buildsrc_settings_file"; then
  fail "buildSrc settings contain an unapproved repository URL"
fi
echo "quality-gate: root and buildSrc settings-level repository contracts verified"

workflow_unquoted_fixture="$TMP_DIR/workflow-yaml-unquoted-second-colon"
git init -q "$workflow_unquoted_fixture"
mkdir -p "$workflow_unquoted_fixture/.github/workflows"
printf '%s\n' \
  'name: Invalid colon fixture' \
  'on: [push]' \
  'jobs:' \
  '  validate:' \
  '    runs-on: ubuntu-latest' \
  '    steps:' \
  '      - name: Guard: invalid fixture' \
  '        run: echo invalid' \
  >"$workflow_unquoted_fixture/.github/workflows/unquoted.yml"
git -C "$workflow_unquoted_fixture" add .github/workflows/unquoted.yml
if ! grep -Fqx \
  '      - name: Guard: invalid fixture' \
  "$workflow_unquoted_fixture/.github/workflows/unquoted.yml"; then
  fail "workflow-yaml-unquoted-second-colon fixture mutation was not created"
fi
assert_workflow_yaml_rejected \
  "workflow-yaml-unquoted-second-colon" \
  "$workflow_unquoted_fixture" \
  "workflow-yaml: .github/workflows/unquoted.yml:7:"

workflow_structure_fixture="$TMP_DIR/workflow-yaml-unclosed-structure"
git init -q "$workflow_structure_fixture"
mkdir -p "$workflow_structure_fixture/.github/workflows"
printf '%s\n' \
  'name: Invalid structure fixture' \
  'on: [push]' \
  'jobs:' \
  '  validate: [ubuntu-latest' \
  >"$workflow_structure_fixture/.github/workflows/structure.yml"
git -C "$workflow_structure_fixture" add .github/workflows/structure.yml
if ! grep -Fqx \
  '  validate: [ubuntu-latest' \
  "$workflow_structure_fixture/.github/workflows/structure.yml"; then
  fail "workflow-yaml-unclosed-structure fixture mutation was not created"
fi
assert_workflow_yaml_rejected \
  "workflow-yaml-unclosed-structure" \
  "$workflow_structure_fixture" \
  "workflow-yaml: .github/workflows/structure.yml:4:"

workflow_yaml_extension_fixture="$TMP_DIR/workflow-yaml-extension"
git init -q "$workflow_yaml_extension_fixture"
mkdir -p "$workflow_yaml_extension_fixture/.github/workflows"
printf '%s\n' \
  'name: Invalid yaml extension fixture' \
  'on: [push' \
  >"$workflow_yaml_extension_fixture/.github/workflows/invalid.yaml"
git -C "$workflow_yaml_extension_fixture" add .github/workflows/invalid.yaml
assert_eq \
  "$(git -C "$workflow_yaml_extension_fixture" ls-files -- '.github/workflows/*.yaml')" \
  ".github/workflows/invalid.yaml"
assert_workflow_yaml_rejected \
  "workflow-yaml-invalid-yaml-extension" \
  "$workflow_yaml_extension_fixture" \
  "workflow-yaml: .github/workflows/invalid.yaml:2:"

workflow_mixed_fixture="$TMP_DIR/workflow-yaml-invalid-tests-among-valid"
git init -q "$workflow_mixed_fixture"
mkdir -p "$workflow_mixed_fixture/.github/workflows"
printf '%s\n' \
  'name: Valid fixture' \
  'on: [push]' \
  'jobs:' \
  '  validate:' \
  '    runs-on: ubuntu-latest' \
  '    steps:' \
  '      - run: echo valid' \
  >"$workflow_mixed_fixture/.github/workflows/valid.yml"
printf '%s\n' \
  'name: Tests' \
  'on: [pull_request]' \
  'jobs:' \
  '  unit-tests:' \
  '    runs-on: ubuntu-latest' \
  '    steps:' \
  '      - name: Guard: invalid tests fixture' \
  '        run: echo invalid' \
  >"$workflow_mixed_fixture/.github/workflows/tests.yml"
git -C "$workflow_mixed_fixture" add .github/workflows/valid.yml .github/workflows/tests.yml
assert_eq \
  "$(git -C "$workflow_mixed_fixture" ls-files -- '.github/workflows/*.yml' | wc -l | tr -d ' ')" \
  "2"
if ! grep -Fqx \
  '      - name: Guard: invalid tests fixture' \
  "$workflow_mixed_fixture/.github/workflows/tests.yml"; then
  fail "workflow-yaml-invalid-tests-among-valid fixture mutation was not created"
fi
assert_workflow_yaml_rejected \
  "workflow-yaml-invalid-tests-among-valid" \
  "$workflow_mixed_fixture" \
  "workflow-yaml: .github/workflows/tests.yml:7:"

workflow_empty_fixture="$TMP_DIR/workflow-yaml-empty-inventory"
git init -q "$workflow_empty_fixture"
assert_workflow_yaml_rejected \
  "workflow-yaml-empty-inventory" \
  "$workflow_empty_fixture" \
  "workflow-yaml: no tracked workflow files found"

workflow_positive_fixture="$TMP_DIR/workflow-yaml-positive-anchors"
git init -q "$workflow_positive_fixture"
mkdir -p "$workflow_positive_fixture/.github/workflows"
printf '%s\n' \
  'name: Positive anchor fixture' \
  'on: [push]' \
  'jobs:' \
  '  validate:' \
  '    runs-on: ubuntu-latest' \
  '    steps:' \
  '      - &guard' \
  '        name: "Guard: valid fixture"' \
  '        run: echo valid' \
  '      - *guard' \
  >"$workflow_positive_fixture/.github/workflows/anchors.yml"
printf '%s\n' \
  'name: Positive yaml extension fixture' \
  'on: [workflow_dispatch]' \
  'jobs:' \
  '  validate:' \
  '    runs-on: ubuntu-latest' \
  '    steps:' \
  '      - run: echo valid' \
  >"$workflow_positive_fixture/.github/workflows/valid.yaml"
git -C "$workflow_positive_fixture" add \
  .github/workflows/anchors.yml \
  .github/workflows/valid.yaml
if ! grep -Fqx '      - &guard' "$workflow_positive_fixture/.github/workflows/anchors.yml" ||
  ! grep -Fqx '      - *guard' "$workflow_positive_fixture/.github/workflows/anchors.yml" ||
  ! grep -Fqx \
    '        name: "Guard: valid fixture"' \
    "$workflow_positive_fixture/.github/workflows/anchors.yml"; then
  fail "workflow-yaml-positive-anchors fixture was not created"
fi
workflow_positive_inventory="$(
  git -C "$workflow_positive_fixture" ls-files -z -- .github/workflows |
    tr '\0' '\n' |
    LC_ALL=C sort
)"
assert_eq \
  "$workflow_positive_inventory" \
  $'.github/workflows/anchors.yml\n.github/workflows/valid.yaml'
workflow_positive_output="$(ruby "$WORKFLOW_YAML_VALIDATOR" "$workflow_positive_fixture")"
assert_contains \
  "$workflow_positive_output" \
  "quality-gate: workflow YAML syntax verified (2 tracked files)"
echo "quality-gate: tracked .yml/.yaml workflows, anchors, aliases, and quoted names accepted"

native_gradle_standard_fixture="$TMP_DIR/native-gradle-repositories-standard"
write_native_gradle_repository_settings "$native_gradle_standard_fixture"
cat >"$native_gradle_standard_fixture/build.gradle.kts" <<'KOTLIN'
repositories {
    mavenCentral()
}
KOTLIN
assert_exact_line "$native_gradle_standard_fixture/build.gradle.kts" "repositories {"
assert_native_gradle_repository_policy_rejected \
  "native-gradle-repositories-standard" \
  "$native_gradle_standard_fixture" \
  "build.gradle.kts"

native_gradle_allprojects_fixture="$TMP_DIR/native-gradle-repositories-allprojects"
write_native_gradle_repository_settings "$native_gradle_allprojects_fixture"
cat >"$native_gradle_allprojects_fixture/build.gradle.kts" <<'KOTLIN'
allprojects {
    repositories {
        mavenCentral()
    }
}
KOTLIN
assert_exact_line "$native_gradle_allprojects_fixture/build.gradle.kts" "allprojects {"
assert_native_gradle_repository_policy_rejected \
  "native-gradle-repositories-allprojects" \
  "$native_gradle_allprojects_fixture" \
  "build.gradle.kts"

native_gradle_qualified_fixture="$TMP_DIR/native-gradle-repositories-qualified"
write_native_gradle_repository_settings "$native_gradle_qualified_fixture"
cat >"$native_gradle_qualified_fixture/build.gradle.kts" <<'KOTLIN'
project.repositories {
    mavenCentral()
}
KOTLIN
assert_exact_line \
  "$native_gradle_qualified_fixture/build.gradle.kts" \
  "project.repositories {"
assert_native_gradle_repository_policy_rejected \
  "native-gradle-repositories-qualified" \
  "$native_gradle_qualified_fixture" \
  "build.gradle.kts"

native_gradle_regular_interpolation_fixture="$TMP_DIR/native-gradle-regular-interpolation"
write_native_gradle_repository_settings "$native_gradle_regular_interpolation_fixture"
cat >"$native_gradle_regular_interpolation_fixture/build.gradle.kts" <<'KOTLIN'
val configured = "${repositories { mavenCentral() }}"
KOTLIN
assert_exact_line \
  "$native_gradle_regular_interpolation_fixture/build.gradle.kts" \
  'val configured = "${repositories { mavenCentral() }}"'
assert_native_gradle_repository_policy_rejected \
  "native-gradle-regular-interpolation" \
  "$native_gradle_regular_interpolation_fixture" \
  "build.gradle.kts"

native_gradle_triple_interpolation_fixture="$TMP_DIR/native-gradle-triple-interpolation"
write_native_gradle_repository_settings "$native_gradle_triple_interpolation_fixture"
cat >"$native_gradle_triple_interpolation_fixture/build.gradle.kts" <<'KOTLIN'
val configured = """${repositories { mavenCentral() }}"""
KOTLIN
assert_exact_line \
  "$native_gradle_triple_interpolation_fixture/build.gradle.kts" \
  'val configured = """${repositories { mavenCentral() }}"""'
assert_native_gradle_repository_policy_rejected \
  "native-gradle-triple-interpolation" \
  "$native_gradle_triple_interpolation_fixture" \
  "build.gradle.kts"

native_gradle_regular_string_fixture="$TMP_DIR/native-gradle-regular-string"
write_native_gradle_repository_settings "$native_gradle_regular_string_fixture"
cat >"$native_gradle_regular_string_fixture/build.gradle.kts" <<'KOTLIN'
val text = "repositories { mavenCentral() }"
KOTLIN
assert_exact_line \
  "$native_gradle_regular_string_fixture/build.gradle.kts" \
  'val text = "repositories { mavenCentral() }"'
assert_native_gradle_repository_policy_accepted \
  "native-gradle-regular-string" \
  "$native_gradle_regular_string_fixture"

native_gradle_triple_string_fixture="$TMP_DIR/native-gradle-triple-string"
write_native_gradle_repository_settings "$native_gradle_triple_string_fixture"
cat >"$native_gradle_triple_string_fixture/build.gradle.kts" <<'KOTLIN'
val text = """
repositories {
    mavenCentral()
}
"""
KOTLIN
assert_exact_line "$native_gradle_triple_string_fixture/build.gradle.kts" 'val text = """'
assert_native_gradle_repository_policy_accepted \
  "native-gradle-triple-string" \
  "$native_gradle_triple_string_fixture"

native_gradle_comment_fixture="$TMP_DIR/native-gradle-comment"
write_native_gradle_repository_settings "$native_gradle_comment_fixture"
cat >"$native_gradle_comment_fixture/build.gradle.kts" <<'KOTLIN'
// repositories { mavenCentral() }
KOTLIN
assert_exact_line \
  "$native_gradle_comment_fixture/build.gradle.kts" \
  "// repositories { mavenCentral() }"
assert_native_gradle_repository_policy_accepted \
  "native-gradle-comment" \
  "$native_gradle_comment_fixture"

native_gradle_settings_only_fixture="$TMP_DIR/native-gradle-settings-only"
write_native_gradle_repository_settings "$native_gradle_settings_only_fixture"
cat >"$native_gradle_settings_only_fixture/build.gradle.kts" <<'KOTLIN'
val marker = "settings repositories only"
KOTLIN
assert_exact_line \
  "$native_gradle_settings_only_fixture/settings.gradle.kts" \
  "        mavenCentral()"
assert_native_gradle_repository_policy_accepted \
  "native-gradle-settings-only" \
  "$native_gradle_settings_only_fixture"

native_gradle_buildsrc_fixture="$TMP_DIR/native-gradle-buildsrc"
write_native_gradle_repository_settings "$native_gradle_buildsrc_fixture"
printf '%s\n' 'val marker = "root fixture"' \
  >"$native_gradle_buildsrc_fixture/build.gradle.kts"
mkdir -p "$native_gradle_buildsrc_fixture/buildSrc"
cp "$buildsrc_settings_file" "$native_gradle_buildsrc_fixture/buildSrc/settings.gradle.kts"
cp "$buildsrc_build_file" "$native_gradle_buildsrc_fixture/buildSrc/build.gradle.kts"
if ! cmp -s \
  "$buildsrc_settings_file" \
  "$native_gradle_buildsrc_fixture/buildSrc/settings.gradle.kts"; then
  fail "native buildSrc fixture did not preserve the settings contract"
fi
cat >>"$native_gradle_buildsrc_fixture/buildSrc/build.gradle.kts" <<'KOTLIN'

repositories {
    mavenCentral()
}
KOTLIN
assert_exact_line \
  "$native_gradle_buildsrc_fixture/buildSrc/build.gradle.kts" \
  "repositories {"
assert_native_gradle_repository_policy_rejected \
  "native-gradle-buildsrc" \
  "$native_gradle_buildsrc_fixture" \
  "buildSrc/build.gradle.kts"

tests_guard_removed_fixture="$TMP_DIR/tests-guard-removed.yml"
remove_tests_repository_guard "$tests_workflow" "$tests_guard_removed_fixture"
assert_not_contains \
  "$(cat "$tests_guard_removed_fixture")" \
  "Guard: no project-level repositories"
assert_tests_workflow_contract_rejected \
  "tests-guard-removed" \
  "$tests_guard_removed_fixture"

tests_guard_before_setup_fixture="$TMP_DIR/tests-guard-before-setup.yml"
move_exact_line_before \
  "$tests_workflow" \
  "$tests_guard_before_setup_fixture" \
  "      - *guard-no-project-repositories" \
  "      - *validate-wrapper"
assert_tests_workflow_contract_rejected \
  "tests-guard-before-setup" \
  "$tests_guard_before_setup_fixture"

tests_guard_continue_fixture="$TMP_DIR/tests-guard-continue-on-error.yml"
insert_anchored_step_direct_line \
  "$tests_workflow" \
  "$tests_guard_continue_fixture" \
  "guard-no-project-repositories" \
  "Guard: no project-level repositories" \
  "        continue-on-error: true"
assert_tests_workflow_contract_rejected \
  "tests-guard-continue-on-error" \
  "$tests_guard_continue_fixture"

tests_guard_fail_open_fixture="$TMP_DIR/tests-guard-fail-open.yml"
replace_exact_line_once \
  "$tests_workflow" \
  "$tests_guard_fail_open_fixture" \
  "            --console=plain" \
  "            --console=plain || true"
assert_tests_workflow_contract_rejected \
  "tests-guard-fail-open" \
  "$tests_guard_fail_open_fixture"

tests_guard_without_strict_fixture="$TMP_DIR/tests-guard-without-strict.yml"
replace_exact_line_once \
  "$tests_workflow" \
  "$tests_guard_without_strict_fixture" \
  "            --dependency-verification=strict \\" \
  "            # strict dependency verification removed"
assert_tests_workflow_contract_rejected \
  "tests-guard-without-strict" \
  "$tests_guard_without_strict_fixture"

tests_integration_guard_removed_fixture="$TMP_DIR/tests-integration-guard-removed.yml"
replace_exact_line_once \
  "$tests_workflow" \
  "$tests_integration_guard_removed_fixture" \
  "      - *guard-no-project-repositories" \
  "      # integration repository guard removed"
assert_tests_workflow_contract_rejected \
  "tests-integration-guard-removed" \
  "$tests_integration_guard_removed_fixture"

echo "quality-gate: native Gradle repository policy and Tests workflow regressions verified"

detekt_report_probe="$TMP_DIR/detekt-report-contract.init.gradle"
detekt_report_probe_manifest="$TMP_DIR/detekt-report-contract.manifest"
cat >"$detekt_report_probe" <<'GRADLE'
gradle.projectsEvaluated {
    gradle.rootProject.allprojects.sort { left, right ->
        left.path <=> right.path
    }.each { project ->
        project.tasks.findAll { task ->
            def candidate = task.class
            def isDetekt = false
            while (candidate != null) {
                if (candidate.name == "io.gitlab.arturbosch.detekt.Detekt") {
                    isDetekt = true
                    break
                }
                candidate = candidate.superclass
            }
            isDetekt && task.name != "detekt" && task.enabled
        }.sort { left, right ->
            left.path <=> right.path
        }.each { task ->
            println(
                "DETEKT_REPORT|" +
                    project.path + "|" +
                    task.name + "|" +
                    task.reports.html.outputLocation.get().asFile.absolutePath + "|" +
                    task.reports.txt.outputLocation.get().asFile.absolutePath + "|" +
                    task.reports.sarif.outputLocation.get().asFile.absolutePath + "|" +
                    task.reports.sarif.required.get() + "|" +
                    task.enabled
            )
        }
    }
}
GRADLE

collect_detekt_task_probe() {
  "$ROOT_DIR/gradlew" help \
    --no-configuration-cache \
    --init-script "$detekt_report_probe" \
    --console=plain |
    sed -n 's/^DETEKT_REPORT|//p' >"$detekt_report_probe_manifest"
}

validate_detekt_static_workflow "$static_check_workflow"
"$ROOT_DIR/gradlew" prepareDetektSarif --console=plain --no-configuration-cache
prepare_symlink_target="$TMP_DIR/detekt-prepare-symlink-target/build/reports/detekt"
mkdir -p "$prepare_symlink_target"
printf 'prepare symlink sentinel\n' >"$prepare_symlink_target/sentinel"
prepare_symlink_hash="$(sha256_file "$prepare_symlink_target/sentinel")"
prepare_fixture_reports="$ROOT_DIR/tools/perf/build/reports"
mkdir -p "$prepare_fixture_reports"
DETEKT_PREPARE_LINK="$prepare_fixture_reports/detekt"
ln -s "$prepare_symlink_target" "$DETEKT_PREPARE_LINK"
DETEKT_PREPARE_LINK_OWNED=1
prepare_symlink_log="$TMP_DIR/detekt-prepare-top-level-symlink.log"
if "$ROOT_DIR/gradlew" prepareDetektSarif \
  --console=plain \
  --no-configuration-cache >"$prepare_symlink_log" 2>&1; then
  fail "prepareDetektSarif accepted a symlinked managed report directory"
else
  prepare_symlink_exit=$?
fi
assert_contains "$(cat "$prepare_symlink_log")" "contains a symbolic link"
assert_eq "$(sha256_file "$prepare_symlink_target/sentinel")" "$prepare_symlink_hash"
rm -f "$DETEKT_PREPARE_LINK"
DETEKT_PREPARE_LINK_OWNED=0
mkdir -p "$prepare_fixture_reports/detekt"
DETEKT_PREPARE_LINK="$prepare_fixture_reports/detekt/nested-task-link"
ln -s "$prepare_symlink_target" "$DETEKT_PREPARE_LINK"
DETEKT_PREPARE_LINK_OWNED=1
"$ROOT_DIR/gradlew" prepareDetektSarif --console=plain --no-configuration-cache
DETEKT_PREPARE_LINK_OWNED=0
assert_eq "$(sha256_file "$prepare_symlink_target/sentinel")" "$prepare_symlink_hash"
if [ -e "$prepare_fixture_reports/detekt" ]; then
  fail "prepareDetektSarif did not remove its managed report directory"
fi
echo "quality-gate: negative fixture detekt-prepare-top-level-symlink rejected (exit $prepare_symlink_exit)"
echo "quality-gate: Detekt prepare nested symlink target preserved"
"$ROOT_DIR/gradlew" detekt --continue --console=plain --no-configuration-cache
"$ROOT_DIR/gradlew" finalizeDetektSarifManifest --console=plain --no-configuration-cache
collect_detekt_task_probe
validate_detekt_report_manifest "$detekt_report_probe_manifest"
detekt_expected_manifest="$ROOT_DIR/build/reports/detekt/expected-sarif.json"
validate_detekt_expected_manifest \
  "$detekt_expected_manifest" \
  "$detekt_report_probe_manifest"
python3 "$ROOT_DIR/scripts/merge-detekt-sarif.py" \
  --root "$ROOT_DIR" \
  --manifest "build/reports/detekt/expected-sarif.json" \
  --output "build/reports/detekt/combined/detekt.sarif"
python3 - "$ROOT_DIR" <<'PY'
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
manifest = json.loads(
    (root / "build/reports/detekt/expected-sarif.json").read_text(encoding="utf-8")
)
combined = json.loads(
    (root / "build/reports/detekt/combined/detekt.sarif").read_text(encoding="utf-8")
)
sources = [
    json.loads((root / entry["reportPath"]).read_text(encoding="utf-8"))
    for entry in manifest["entries"]
]
source_runs = sum(len(report["runs"]) for report in sources)
source_results = sum(
    len(run.get("results", []))
    for report in sources
    for run in report["runs"]
)
combined_results = sum(len(run.get("results", [])) for run in combined["runs"])
ids = [run.get("automationDetails", {}).get("id") for run in combined["runs"]]
categories = [automation_id.rsplit("/", 1)[0] for automation_id in ids]
if combined.get("version") != "2.1.0":
    raise SystemExit("real combined Detekt SARIF version changed")
if len(manifest["entries"]) != 13 or source_runs != len(combined["runs"]):
    raise SystemExit("real combined Detekt SARIF lost expected reports or runs")
if source_results != combined_results:
    raise SystemExit("real combined Detekt SARIF lost results")
if any(not isinstance(automation_id, str) or not automation_id.endswith("/") for automation_id in ids):
    raise SystemExit("real combined Detekt SARIF automation IDs are invalid")
if len(categories) != len(set(categories)):
    raise SystemExit("real combined Detekt SARIF categories overlap")
PY
echo "quality-gate: Detekt dynamic lifecycle and 13-report snapshot verified"

if [ -e "$DETEKT_DYNAMIC_MODULE_DIR" ]; then
  fail "temporary Detekt fixture module path already exists"
fi
mkdir -p "$DETEKT_DYNAMIC_MODULE_DIR"
DETEKT_DYNAMIC_MODULE_OWNED=1
cat >"$DETEKT_DYNAMIC_MODULE_DIR/build.gradle.kts" <<'KOTLIN'
plugins {
    kotlin("jvm")
}
KOTLIN

detekt_dynamic_init="$TMP_DIR/detekt-dynamic-module.init.gradle"
cat >"$detekt_dynamic_init" <<'GRADLE'
gradle.settingsEvaluated { settings ->
    def expectedRoot = new File(System.getenv("DETEKT_REPOSITORY_ROOT")).canonicalFile
    if (settings.rootDir.canonicalFile == expectedRoot) {
        settings.include(":detekt-selfcheck-fixture")
    }
}

gradle.beforeProject { project ->
    if (project.path == ":detekt-selfcheck-fixture") {
        project.layout.buildDirectory.set(project.layout.projectDirectory.dir("custom-build"))
        project.pluginManager.withPlugin("io.gitlab.arturbosch.detekt") {
            project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                def template = project.tasks.named("detektMain").get()
                def detektType = template.class.superclass
                def generatedSource = project.layout.buildDirectory.file(
                    "generated/detekt-fixture/GeneratedFixture.kt"
                ).get().asFile
                def emptySource = project.layout.buildDirectory.dir(
                    "generated/detekt-fixture-empty"
                ).get().asFile
                project.tasks.withType(detektType).configureEach { task ->
                    task.baseline = project.rootProject.file(
                        "config/detekt/baseline-tools-perf.xml"
                    )
                }
                def producer = project.tasks.register("generateDetektFixtureSource") {
                    outputs.file(generatedSource)
                    doLast {
                        generatedSource.parentFile.mkdirs()
                        generatedSource.text = "class GeneratedFixture\n"
                    }
                }
                project.tasks.register("detektIntegrationTest", detektType) { task ->
                    task.dependsOn(producer)
                    task.setSource(project.files(generatedSource))
                }
                project.tasks.register("detektFunctionalTest", detektType) { task ->
                    task.setSource(project.files(emptySource))
                }
                if (System.getenv("DETEKT_REGISTER_SKIPPED_FIXTURE") == "1") {
                    def skippedSource = project.layout.buildDirectory.file(
                        "generated/detekt-fixture-skipped/SkippedFixture.kt"
                    ).get().asFile
                    def skippedReport = project.rootProject.file(
                        "detekt-selfcheck-fixture/build/reports/detekt/" +
                            "detektSkippedFixture/detekt.sarif"
                    )
                    def skippedProducer = project.tasks.register("generateSkippedDetektFixture") {
                        outputs.files(skippedSource, skippedReport)
                        doLast {
                            skippedSource.parentFile.mkdirs()
                            skippedSource.text = "class SkippedFixture\n"
                            skippedReport.parentFile.mkdirs()
                            skippedReport.text =
                                '{"version":"2.1.0","runs":[' +
                                '{"tool":{"driver":{"name":"detekt"}},"results":[]}]}'
                        }
                    }
                    project.tasks.register("detektSkippedFixture", detektType) { task ->
                        task.dependsOn(skippedProducer)
                        task.setSource(project.files(skippedSource))
                        task.onlyIf { false }
                    }
                }
            }
        }
    }
}
GRADLE

detekt_dynamic_graph="$(
  DETEKT_REPOSITORY_ROOT="$ROOT_DIR" "$ROOT_DIR/gradlew" \
    :detekt-selfcheck-fixture:detekt \
    --dry-run \
    --no-configuration-cache \
    --init-script "$detekt_dynamic_init" \
    --console=plain
)"
for expected_task in \
  ":detekt-selfcheck-fixture:detektIntegrationTest SKIPPED" \
  ":detekt-selfcheck-fixture:recordDetektIntegrationTestSarifStatus SKIPPED" \
  ":detekt-selfcheck-fixture:generateDetektFixtureSource SKIPPED" \
  ":detekt-selfcheck-fixture:detektFunctionalTest SKIPPED" \
  ":detekt-selfcheck-fixture:recordDetektFunctionalTestSarifStatus SKIPPED"; do
  assert_contains "$detekt_dynamic_graph" "$expected_task"
done

DETEKT_REPOSITORY_ROOT="$ROOT_DIR" "$ROOT_DIR/gradlew" \
  :detekt-selfcheck-fixture:detekt \
  -x prepareDetektSarif \
  --continue \
  --rerun-tasks \
  --no-build-cache \
  --no-configuration-cache \
  --init-script "$detekt_dynamic_init" \
  --console=plain
DETEKT_REPOSITORY_ROOT="$ROOT_DIR" "$ROOT_DIR/gradlew" \
  finalizeDetektSarifManifest \
  --no-configuration-cache \
  --init-script "$detekt_dynamic_init" \
  --console=plain
python3 "$ROOT_DIR/scripts/merge-detekt-sarif.py" \
  --root "$ROOT_DIR" \
  --manifest "build/reports/detekt/expected-sarif.json" \
  --output "build/reports/detekt/combined/detekt.sarif"
python3 - "$ROOT_DIR" <<'PY'
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
module = root / "detekt-selfcheck-fixture"
manifest = json.loads(
    (root / "build/reports/detekt/expected-sarif.json").read_text(encoding="utf-8")
)
entries = {entry["taskPath"]: entry for entry in manifest["entries"]}
integration = ":detekt-selfcheck-fixture:detektIntegrationTest"
excluded = {
    ":detekt-selfcheck-fixture:detektFunctionalTest",
    ":detekt-selfcheck-fixture:detektMain",
    ":detekt-selfcheck-fixture:detektTest",
}
if integration not in entries or excluded.intersection(entries):
    raise SystemExit("dynamic Detekt task inclusion/exclusion contract failed")
entry = entries[integration]
if entry["sourceCount"] != 1:
    raise SystemExit("generated-only Detekt source was evaluated before its producer")
if entry["category"] != "detekt/detekt-selfcheck-fixture/integrationTest":
    raise SystemExit("dynamic Detekt category is not identity-derived")
if not (root / entry["reportPath"]).is_file():
    raise SystemExit("dynamic Detekt SARIF report is missing")
if (module / "custom-build/reports/detekt").exists():
    raise SystemExit("Detekt reports followed a mutable project buildDirectory")
status_dir = module / "build/reports/detekt/status"
states = {
    name: json.loads((status_dir / f"{name}.json").read_text(encoding="utf-8"))
    for name in ("detektIntegrationTest", "detektFunctionalTest")
}
if (
    states["detektIntegrationTest"]["state"] != "REPORT_REQUIRED"
    or states["detektIntegrationTest"]["sourceCount"] != 1
    or states["detektIntegrationTest"]["taskExecuted"] is not True
    or states["detektIntegrationTest"]["taskSkipped"] is not False
    or states["detektIntegrationTest"]["taskSkipMessage"] != ""
    or states["detektIntegrationTest"]["taskNoSource"] is not False
    or states["detektIntegrationTest"]["taskFailed"] is not False
    or states["detektIntegrationTest"]["reportExists"] is not True
):
    raise SystemExit("generated-only Detekt status is invalid")
if (
    states["detektFunctionalTest"]["state"] != "NO_SOURCE"
    or states["detektFunctionalTest"]["sourceCount"] != 0
    or states["detektFunctionalTest"]["taskExecuted"] is not True
    or states["detektFunctionalTest"]["taskSkipped"] is not True
    or states["detektFunctionalTest"]["taskSkipMessage"] != "NO-SOURCE"
    or states["detektFunctionalTest"]["taskNoSource"] is not True
    or states["detektFunctionalTest"]["taskFailed"] is not False
    or states["detektFunctionalTest"]["reportExists"] is not False
):
    raise SystemExit("genuine NO_SOURCE Detekt status is invalid")
if (module / "build/reports/detekt/detektFunctionalTest/detekt.sarif").exists():
    raise SystemExit("genuine NO_SOURCE Detekt task unexpectedly produced SARIF")
if len(entries) != 14:
    raise SystemExit("dynamic module should add exactly one applicable report")
PY
echo "quality-gate: future Detekt task and generated-source lifecycle verified"

DETEKT_REGISTER_SKIPPED_FIXTURE=1 \
  DETEKT_REPOSITORY_ROOT="$ROOT_DIR" \
  "$ROOT_DIR/gradlew" \
  :detekt-selfcheck-fixture:detektSkippedFixture \
  -x prepareDetektSarif \
  --rerun-tasks \
  --no-build-cache \
  --no-configuration-cache \
  --init-script "$detekt_dynamic_init" \
  --console=plain
python3 - "$DETEKT_DYNAMIC_MODULE_DIR" <<'PY'
import json
from pathlib import Path
import sys

module = Path(sys.argv[1])
status = json.loads(
    (
        module
        / "build/reports/detekt/status/detektSkippedFixture.json"
    ).read_text(encoding="utf-8")
)
if (
    status["state"] != "INCOMPLETE"
    or status["sourceCount"] != 1
    or status["taskExecuted"] is not True
    or status["taskSkipped"] is not True
    or status["taskSkipMessage"] != "SKIPPED"
    or status["taskNoSource"] is not False
    or status["taskFailed"] is not False
    or status["reportExists"] is not True
):
    raise SystemExit("skipped Detekt task was not classified as incomplete")
PY
skipped_status_log="$TMP_DIR/detekt-finalizer-skipped-analysis.log"
if DETEKT_REGISTER_SKIPPED_FIXTURE=1 \
  DETEKT_REPOSITORY_ROOT="$ROOT_DIR" \
  "$ROOT_DIR/gradlew" \
  finalizeDetektSarifManifest \
  --no-configuration-cache \
  --init-script "$detekt_dynamic_init" \
  --console=plain >"$skipped_status_log" 2>&1; then
  fail "finalizer accepted a skipped Detekt analysis with a substituted SARIF"
else
  skipped_status_exit=$?
fi
assert_contains "$(cat "$skipped_status_log")" "Incomplete Detekt execution status"
rm -f "$DETEKT_DYNAMIC_MODULE_DIR/build/reports/detekt/status/detektSkippedFixture.json"
rm -rf "$DETEKT_DYNAMIC_MODULE_DIR/build/reports/detekt/detektSkippedFixture"
DETEKT_REPOSITORY_ROOT="$ROOT_DIR" "$ROOT_DIR/gradlew" \
  finalizeDetektSarifManifest \
  --no-configuration-cache \
  --init-script "$detekt_dynamic_init" \
  --console=plain
echo "quality-gate: negative fixture detekt-skipped-analysis rejected (exit $skipped_status_exit)"

status_fixture_directory="$DETEKT_DYNAMIC_MODULE_DIR/build/reports/detekt/status"
status_fixture_file="$status_fixture_directory/detektIntegrationTest.json"
status_file_target="$TMP_DIR/detekt-status-file-symlink-target.json"
mv "$status_fixture_file" "$status_file_target"
status_file_hash="$(sha256_file "$status_file_target")"
ln -s "$status_file_target" "$status_fixture_file"
status_file_symlink_log="$TMP_DIR/detekt-finalizer-status-file-symlink.log"
if DETEKT_REPOSITORY_ROOT="$ROOT_DIR" \
  "$ROOT_DIR/gradlew" \
  finalizeDetektSarifManifest \
  --no-configuration-cache \
  --init-script "$detekt_dynamic_init" \
  --console=plain >"$status_file_symlink_log" 2>&1; then
  fail "finalizer accepted a symlinked Detekt status file"
else
  status_file_symlink_exit=$?
fi
if [ -e "$detekt_expected_manifest" ]; then
  fail "symlinked Detekt status file left an expected manifest"
fi
assert_eq "$(sha256_file "$status_file_target")" "$status_file_hash"
if [ ! -L "$status_fixture_file" ]; then
  fail "status-file symlink fixture did not create the intended mutation"
fi
rm -f "$status_fixture_file"
mv "$status_file_target" "$status_fixture_file"
echo "quality-gate: negative fixture detekt-status-file-symlink rejected (exit $status_file_symlink_exit)"

status_directory_target="$TMP_DIR/detekt-status-directory-symlink-target"
mv "$status_fixture_directory" "$status_directory_target"
status_directory_sentinel="$status_directory_target/detektIntegrationTest.json"
status_directory_hash="$(sha256_file "$status_directory_sentinel")"
ln -s "$status_directory_target" "$status_fixture_directory"
status_directory_symlink_log="$TMP_DIR/detekt-finalizer-status-directory-symlink.log"
if DETEKT_REPOSITORY_ROOT="$ROOT_DIR" \
  "$ROOT_DIR/gradlew" \
  finalizeDetektSarifManifest \
  --no-configuration-cache \
  --init-script "$detekt_dynamic_init" \
  --console=plain >"$status_directory_symlink_log" 2>&1; then
  fail "finalizer accepted a symlinked Detekt status directory"
else
  status_directory_symlink_exit=$?
fi
if [ -e "$detekt_expected_manifest" ]; then
  fail "symlinked Detekt status directory left an expected manifest"
fi
assert_eq "$(sha256_file "$status_directory_sentinel")" "$status_directory_hash"
if [ ! -L "$status_fixture_directory" ]; then
  fail "status-directory symlink fixture did not create the intended mutation"
fi
rm -f "$status_fixture_directory"
mv "$status_directory_target" "$status_fixture_directory"
echo "quality-gate: negative fixture detekt-status-directory-symlink rejected (exit $status_directory_symlink_exit)"

missing_status_file="$DETEKT_DYNAMIC_MODULE_DIR/build/reports/detekt/status/detektIntegrationTest.json"
rm -f "$missing_status_file"
missing_status_log="$TMP_DIR/detekt-finalizer-missing-task-status.log"
if env \
  DETEKT_REPOSITORY_ROOT="$ROOT_DIR" \
  "$ROOT_DIR/gradlew" \
  finalizeDetektSarifManifest \
  --no-configuration-cache \
  --init-script "$detekt_dynamic_init" \
  --console=plain >"$missing_status_log" 2>&1; then
  fail "negative fixture unexpectedly passed: detekt-finalizer-missing-task-status"
else
  missing_status_exit=$?
fi
assert_contains "$(cat "$missing_status_log")" "Detekt status inventory is incomplete or unexpected"
if [ -e "$detekt_expected_manifest" ]; then
  fail "missing Detekt task status left a stale expected manifest"
fi
printf 'stale combined report\n' >"$ROOT_DIR/build/reports/detekt/combined/detekt.sarif"
assert_validation_rejected \
  "detekt-collector-after-missing-task-status" \
  python3 \
  "$ROOT_DIR/scripts/merge-detekt-sarif.py" \
  --root "$ROOT_DIR" \
  --manifest "build/reports/detekt/expected-sarif.json" \
  --output "build/reports/detekt/combined/detekt.sarif"
if [ -e "$ROOT_DIR/build/reports/detekt/combined/detekt.sarif" ]; then
  fail "collector left stale combined SARIF after missing task status"
fi
echo "quality-gate: negative fixture detekt-finalizer-missing-task-status rejected (exit $missing_status_exit)"

rm -rf "$DETEKT_DYNAMIC_MODULE_DIR"
DETEKT_DYNAMIC_MODULE_OWNED=0
"$ROOT_DIR/gradlew" finalizeDetektSarifManifest --console=plain --no-configuration-cache
collect_detekt_task_probe
validate_detekt_expected_manifest \
  "$detekt_expected_manifest" \
  "$detekt_report_probe_manifest"
python3 "$ROOT_DIR/scripts/merge-detekt-sarif.py" \
  --root "$ROOT_DIR" \
  --manifest "build/reports/detekt/expected-sarif.json" \
  --output "build/reports/detekt/combined/detekt.sarif"

PYTHONDONTWRITEBYTECODE=1 python3 - \
  "$ROOT_DIR/scripts/merge-detekt-sarif.py" \
  "$TMP_DIR/detekt-collector-fixtures" <<'PY'
from copy import deepcopy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

collector = Path(sys.argv[1])
fixtures_root = Path(sys.argv[2])
fixtures_root.mkdir(parents=True, exist_ok=True)
manifest_path = "build/reports/detekt/expected-sarif.json"
output_path = "build/reports/detekt/combined/detekt.sarif"


def task_identity(task_name):
    if task_name.startswith("detekt") and len(task_name) > len("detekt"):
        suffix = task_name[len("detekt"):]
        return suffix[0].lower() + suffix[1:]
    return task_name


def entry(project_path, task_name, source_count=1):
    parts = [] if project_path == ":" else project_path.removeprefix(":").split(":")
    module = "/".join(parts)
    prefix = f"{module}/" if module else ""
    return {
        "taskPath": f":{task_name}" if project_path == ":" else f"{project_path}:{task_name}",
        "projectPath": project_path,
        "taskName": task_name,
        "reportPath": f"{prefix}build/reports/detekt/{task_name}/detekt.sarif",
        "category": f"detekt/{module or 'root'}/{task_identity(task_name)}",
        "sourceCount": source_count,
    }


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, sort_keys=True) + "\n", encoding="utf-8")


def write_manifest(root, entries):
    write_json(
        root / manifest_path,
        {
            "schema": "clubs-bot/detekt-sarif-manifest",
            "version": 2,
            "entries": sorted(entries, key=lambda item: item["taskPath"]),
        },
    )


def report(runs=None):
    return {
        "$schema": "https://json.schemastore.org/sarif-2.1.0.json",
        "version": "2.1.0",
        "runs": runs
        if runs is not None
        else [{"tool": {"driver": {"name": "detekt"}}, "results": []}],
    }


def write_sources(root, entries, reports=None):
    reports = reports or {}
    for item in entries:
        write_json(root / item["reportPath"], reports.get(item["taskPath"], report()))


def seed_valid_contract(root):
    item = entry(":app-bot", "detektMain")
    write_manifest(root, [item])
    write_sources(root, [item])
    return item


def fresh(name):
    root = fixtures_root / name
    if root.exists():
        shutil.rmtree(root)
    root.mkdir(parents=True)
    return root


def invoke(root, *, output=output_path):
    return subprocess.run(
        [
            sys.executable,
            str(collector),
            "--root",
            str(root),
            "--manifest",
            manifest_path,
            "--output",
            output,
        ],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def expect_rejected(name, root, *, output=output_path, stale_removed=False):
    result = invoke(root, output=output)
    if result.returncode == 0:
        raise SystemExit(f"{name}: invalid fixture unexpectedly passed")
    if stale_removed and os.path.lexists(root / output_path):
        raise SystemExit(f"{name}: stale combined output survived rejection")
    print(f"quality-gate: negative fixture {name} rejected (exit {result.returncode})")


def expect_unexpected_sarif_rejected(name, relative_path):
    root = fresh(name)
    item = seed_valid_contract(root)
    expected_source = root / item["reportPath"]
    unexpected_source = root / relative_path
    write_json(unexpected_source, report())
    expected_hash = digest(expected_source)
    unexpected_hash = digest(unexpected_source)
    stale = root / output_path
    stale.parent.mkdir(parents=True)
    stale.write_text("stale", encoding="utf-8")

    result = invoke(root)
    if result.returncode != 1:
        raise SystemExit(
            f"{name}: expected collector exit 1, got {result.returncode}"
        )
    if os.path.lexists(stale):
        raise SystemExit(f"{name}: stale combined output survived rejection")
    if digest(expected_source) != expected_hash:
        raise SystemExit(f"{name}: expected source report changed")
    if digest(unexpected_source) != unexpected_hash:
        raise SystemExit(f"{name}: unexpected source report changed")
    if relative_path not in result.stderr:
        raise SystemExit(f"{name}: diagnostic omitted repository-relative path")
    if str(root) in result.stderr:
        raise SystemExit(f"{name}: diagnostic exposed an absolute fixture path")
    print(f"quality-gate: negative fixture {name} rejected (exit 1)")


valid = fresh("valid")
valid_entries = [
    entry(":app-bot", "detektMain"),
    entry(":app-bot", "detektTest"),
    entry(":future:module", "detektIntegrationTest"),
]
rich_run = {
    "tool": {"driver": {"name": "detekt", "semanticVersion": "1.23.8"}},
    "artifacts": [{"location": {"uri": "app-bot/src/main/kotlin/Fixture.kt"}}],
    "originalUriBaseIds": {"%SRCROOT%": {"uri": "file:///workspace/"}},
    "invocations": [{"executionSuccessful": True}],
    "properties": {"fixture": "rich"},
    "taxonomies": [{"name": "fixture-taxonomy"}],
    "automationDetails": {"guid": "fixture-guid", "description": {"text": "fixture"}},
    "results": [
        {
            "ruleId": "FixtureRule",
            "message": {"text": "fixture"},
            "locations": [
                {
                    "physicalLocation": {
                        "artifactLocation": {
                            "uri": "app-bot/src/main/kotlin/Fixture.kt",
                            "uriBaseId": "%SRCROOT%",
                        },
                        "region": {"startLine": 7},
                    }
                }
            ],
            "partialFingerprints": {"detekt/v1": "fixture-fingerprint"},
        }
    ],
}
valid_reports = {
    ":app-bot:detektMain": report([rich_run]),
    ":app-bot:detektTest": report(
        [
            {"tool": {"driver": {"name": "detekt"}}, "results": []},
            {
                "tool": {"driver": {"name": "detekt"}},
                "results": [{"ruleId": "FixtureTest"}],
            },
        ]
    ),
}
write_manifest(valid, valid_entries)
write_sources(valid, valid_entries, valid_reports)
first = invoke(valid)
if first.returncode != 0:
    raise SystemExit(f"valid collector fixture failed: {first.stderr}")
first_bytes = (valid / output_path).read_bytes()
second = invoke(valid)
if second.returncode != 0 or (valid / output_path).read_bytes() != first_bytes:
    raise SystemExit("collector output is not deterministic")
combined = json.loads((valid / output_path).read_text(encoding="utf-8"))
source_reports = [
    json.loads((valid / item["reportPath"]).read_text(encoding="utf-8"))
    for item in sorted(valid_entries, key=lambda item: item["taskPath"])
]
source_runs = [run for source in source_reports for run in source["runs"]]
if len(combined["runs"]) != len(source_runs):
    raise SystemExit("collector lost SARIF runs")
source_results = sum(len(run.get("results", [])) for run in source_runs)
combined_results = sum(len(run.get("results", [])) for run in combined["runs"])
if source_results != combined_results:
    raise SystemExit("collector lost SARIF results")
for actual, original in zip(combined["runs"], source_runs):
    expected = deepcopy(original)
    expected_details = dict(expected.get("automationDetails") or {})
    expected_details["id"] = actual["automationDetails"]["id"]
    expected["automationDetails"] = expected_details
    if actual != expected:
        raise SystemExit("collector changed a source run beyond automationDetails.id")
ids = [run["automationDetails"]["id"] for run in combined["runs"]]
categories = [automation_id.rsplit("/", 1)[0] for automation_id in ids]
if any(not automation_id.endswith("/") for automation_id in ids):
    raise SystemExit("collector automation ID lacks trailing slash")
if len(categories) != len(set(categories)):
    raise SystemExit("collector effective categories overlap")
rich_result = combined["runs"][0]["results"][0]
if rich_result["locations"][0]["physicalLocation"]["artifactLocation"]["uri"] != (
    "app-bot/src/main/kotlin/Fixture.kt"
):
    raise SystemExit("collector changed a SARIF location URI")
if rich_result["partialFingerprints"] != {"detekt/v1": "fixture-fingerprint"}:
    raise SystemExit("collector removed SARIF fingerprints")
print("quality-gate: Detekt SARIF deterministic rich preservation verified")

diagnostics = fresh("diagnostic-files")
diagnostic_item = seed_valid_contract(diagnostics)
diagnostic_directory = (diagnostics / diagnostic_item["reportPath"]).parent
html_report = diagnostic_directory / "detekt.html"
text_report = diagnostic_directory / "detekt.txt"
html_report.write_bytes(b"<html>detekt fixture</html>\n")
text_report.write_bytes(b"detekt fixture\n")
html_hash = digest(html_report)
text_hash = digest(text_report)
diagnostic_result = invoke(diagnostics)
if diagnostic_result.returncode != 0:
    raise SystemExit(
        f"collector rejected allowed HTML/TXT diagnostics: {diagnostic_result.stderr}"
    )
if not (diagnostics / output_path).is_file():
    raise SystemExit("collector did not create combined SARIF for HTML/TXT diagnostics")
if digest(html_report) != html_hash or digest(text_report) != text_hash:
    raise SystemExit("collector changed allowed HTML/TXT diagnostics")
print("quality-gate: Detekt HTML/TXT diagnostic fixture accepted")

for unexpected_name, unexpected_relative in (
    (
        "detekt-direct-legacy-sarif",
        "app-bot/build/reports/detekt/detekt.sarif",
    ),
    (
        "detekt-nested-legacy-sarif",
        "app-bot/build/reports/detekt/legacy/detekt.sarif",
    ),
    (
        "detekt-unknown-task-sarif",
        "app-bot/build/reports/detekt/detektUnknown/detekt.sarif",
    ),
    (
        "detekt-extra-task-sarif",
        "app-bot/build/reports/detekt/detektMain/extra.sarif",
    ),
    (
        "detekt-alternative-name-sarif",
        "app-bot/build/reports/detekt/detektMain/report.sarif",
    ),
):
    expect_unexpected_sarif_rejected(unexpected_name, unexpected_relative)

empty = fresh("empty-manifest")
write_manifest(empty, [])
stale = empty / output_path
stale.parent.mkdir(parents=True)
stale.write_text("stale", encoding="utf-8")
expect_rejected("detekt-empty-manifest", empty, stale_removed=True)

malformed_manifest = fresh("malformed-manifest")
manifest_file = malformed_manifest / manifest_path
manifest_file.parent.mkdir(parents=True)
manifest_file.write_text("{not-json", encoding="utf-8")
stale = malformed_manifest / output_path
stale.parent.mkdir(parents=True)
stale.write_text("stale", encoding="utf-8")
expect_rejected("detekt-malformed-manifest", malformed_manifest, stale_removed=True)

missing = fresh("missing-report")
missing_entries = [entry(":app-bot", "detektMain"), entry(":app-bot", "detektTest")]
write_manifest(missing, missing_entries)
write_sources(missing, missing_entries[:1])
stale = missing / output_path
stale.parent.mkdir(parents=True)
stale.write_text("stale", encoding="utf-8")
expect_rejected("detekt-missing-one-report", missing, stale_removed=True)

for name, source_value in (
    ("malformed-source", "{not-json"),
    ("missing-runs", json.dumps({"version": "2.1.0"})),
    (
        "invalid-run-object",
        json.dumps({"version": "2.1.0", "runs": ["not-an-object"]}),
    ),
    (
        "invalid-result-object",
        json.dumps(
            {
                "version": "2.1.0",
                "runs": [
                    {
                        "tool": {"driver": {"name": "detekt"}},
                        "results": [1],
                    }
                ],
            }
        ),
    ),
):
    root = fresh(name)
    item = entry(":app-bot", "detektMain")
    write_manifest(root, [item])
    source = root / item["reportPath"]
    source.parent.mkdir(parents=True)
    source.write_text(source_value + "\n", encoding="utf-8")
    stale = root / output_path
    stale.parent.mkdir(parents=True)
    stale.write_text("stale", encoding="utf-8")
    expect_rejected(f"detekt-{name}", root, stale_removed=True)

extra = fresh("extra-report")
extra_item = entry(":app-bot", "detektMain")
write_manifest(extra, [extra_item])
write_sources(extra, [extra_item])
extra_report = extra / entry(":app-bot", "detektTest")["reportPath"]
write_json(extra_report, report())
expect_rejected("detekt-extra-managed-report", extra)

file_link = fresh("source-file-symlink")
file_link_item = entry(":app-bot", "detektMain")
write_manifest(file_link, [file_link_item])
target = file_link / "safe/target.sarif"
write_json(target, report())
target_hash = digest(target)
source_link = file_link / file_link_item["reportPath"]
source_link.parent.mkdir(parents=True)
source_link.symlink_to(target)
stale = file_link / output_path
stale.parent.mkdir(parents=True)
stale.write_text("stale", encoding="utf-8")
expect_rejected("detekt-source-file-symlink", file_link, stale_removed=True)
if digest(target) != target_hash:
    raise SystemExit("source-file symlink target changed")

directory_link = fresh("source-directory-symlink")
directory_link_item = entry(":app-bot", "detektMain")
write_manifest(directory_link, [directory_link_item])
real_directory = directory_link / "safe/task-directory"
write_json(real_directory / "detekt.sarif", report())
target_hash = digest(real_directory / "detekt.sarif")
logical_directory = (directory_link / directory_link_item["reportPath"]).parent
logical_directory.parent.mkdir(parents=True)
logical_directory.symlink_to(real_directory, target_is_directory=True)
stale = directory_link / output_path
stale.parent.mkdir(parents=True)
stale.write_text("stale", encoding="utf-8")
expect_rejected("detekt-source-directory-symlink", directory_link, stale_removed=True)
if digest(real_directory / "detekt.sarif") != target_hash:
    raise SystemExit("source-directory symlink target changed")

cross_link = fresh("cross-task-symlink")
cross_entries = [entry(":app-bot", "detektMain"), entry(":app-bot", "detektTest")]
write_manifest(cross_link, cross_entries)
write_json(cross_link / cross_entries[0]["reportPath"], report())
main_directory = (cross_link / cross_entries[0]["reportPath"]).parent
test_directory = (cross_link / cross_entries[1]["reportPath"]).parent
test_directory.parent.mkdir(parents=True, exist_ok=True)
test_directory.symlink_to(main_directory, target_is_directory=True)
target_hash = digest(cross_link / cross_entries[0]["reportPath"])
stale = cross_link / output_path
stale.parent.mkdir(parents=True)
stale.write_text("stale", encoding="utf-8")
expect_rejected("detekt-cross-task-directory-symlink", cross_link, stale_removed=True)
if digest(cross_link / cross_entries[0]["reportPath"]) != target_hash:
    raise SystemExit("cross-task symlink target changed")

outside_root = fresh("outside-source-symlink")
outside_item = entry(":app-bot", "detektMain")
write_manifest(outside_root, [outside_item])
outside_target = fixtures_root / "outside-target.sarif"
write_json(outside_target, report())
outside_hash = digest(outside_target)
outside_link = outside_root / outside_item["reportPath"]
outside_link.parent.mkdir(parents=True)
outside_link.symlink_to(outside_target)
stale = outside_root / output_path
stale.parent.mkdir(parents=True)
stale.write_text("stale", encoding="utf-8")
expect_rejected("detekt-source-symlink-outside-root", outside_root, stale_removed=True)
if digest(outside_target) != outside_hash:
    raise SystemExit("outside symlink target changed")

hardlink_root = fresh("duplicate-physical-source")
hardlink_entries = [entry(":app-bot", "detektMain"), entry(":app-bot", "detektTest")]
write_manifest(hardlink_root, hardlink_entries)
first_source = hardlink_root / hardlink_entries[0]["reportPath"]
write_json(first_source, report())
second_source = hardlink_root / hardlink_entries[1]["reportPath"]
second_source.parent.mkdir(parents=True)
os.link(first_source, second_source)
source_hash = digest(first_source)
stale = hardlink_root / output_path
stale.parent.mkdir(parents=True)
stale.write_text("stale", encoding="utf-8")
expect_rejected("detekt-duplicate-physical-source", hardlink_root, stale_removed=True)
if digest(first_source) != source_hash:
    raise SystemExit("hardlinked source changed")

for name, target_relative, output_argument in (
    ("arbitrary-agents-output", "AGENTS.md", "AGENTS.md"),
    (
        "arbitrary-kotlin-output",
        "app-bot/src/main/kotlin/com/example/Application.kt",
        "app-bot/src/main/kotlin/com/example/Application.kt",
    ),
    (
        "traversal-output",
        "../traversal-output-target.txt",
        "../traversal-output-target.txt",
    ),
):
    root = fresh(name)
    seed_valid_contract(root)
    target = root / target_relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(b"tracked-like fixture bytes\n")
    before = digest(target)
    expect_rejected(f"detekt-{name}", root, output=output_argument)
    if digest(target) != before:
        raise SystemExit(f"{name}: arbitrary target changed")

absolute = fresh("absolute-output")
seed_valid_contract(absolute)
absolute_target = absolute / "absolute-target.txt"
absolute_target.write_bytes(b"absolute target\n")
before = digest(absolute_target)
expect_rejected("detekt-absolute-output", absolute, output=str(absolute_target))
if digest(absolute_target) != before:
    raise SystemExit("absolute output target changed")

output_link_root = fresh("output-file-symlink")
seed_valid_contract(output_link_root)
output_target = output_link_root / "safe-output-target.sarif"
output_target.write_bytes(b"safe output target\n")
before = digest(output_target)
output_link = output_link_root / output_path
output_link.parent.mkdir(parents=True, exist_ok=True)
output_link.symlink_to(output_target)
expect_rejected("detekt-output-file-symlink", output_link_root)
if digest(output_target) != before or not output_link.is_symlink():
    raise SystemExit("output symlink target changed or link was followed")

output_directory_root = fresh("output-directory-symlink")
seed_valid_contract(output_directory_root)
output_directory_target = output_directory_root / "safe-combined-directory"
output_directory_target.mkdir()
sentinel = output_directory_target / "sentinel"
sentinel.write_bytes(b"combined directory target\n")
before = digest(sentinel)
combined_link = output_directory_root / "build/reports/detekt/combined"
combined_link.parent.mkdir(parents=True, exist_ok=True)
combined_link.symlink_to(output_directory_target, target_is_directory=True)
expect_rejected("detekt-output-directory-symlink", output_directory_root)
if digest(sentinel) != before or not combined_link.is_symlink():
    raise SystemExit("combined directory symlink target changed or link was followed")

output_parent_root = fresh("output-parent-symlink")
parent_target = output_parent_root / "safe-reports-directory"
parent_target.mkdir()
sentinel = parent_target / "sentinel"
sentinel.write_bytes(b"reports parent target\n")
before = digest(sentinel)
reports_link = output_parent_root / "build/reports"
reports_link.parent.mkdir()
reports_link.symlink_to(parent_target, target_is_directory=True)
seed_valid_contract(output_parent_root)
expect_rejected("detekt-output-parent-symlink", output_parent_root)
if digest(sentinel) != before or not reports_link.is_symlink():
    raise SystemExit("output parent symlink target changed or link was followed")

manifest_link_root = fresh("manifest-file-symlink")
manifest_item = seed_valid_contract(manifest_link_root)
manifest_link = manifest_link_root / manifest_path
manifest_target = manifest_link_root / "safe-manifest-target.json"
manifest_link.replace(manifest_target)
manifest_hash = digest(manifest_target)
manifest_link.symlink_to(manifest_target)
expect_rejected("detekt-manifest-file-symlink", manifest_link_root)
if digest(manifest_target) != manifest_hash or not manifest_link.is_symlink():
    raise SystemExit("manifest symlink target changed or link was followed")

spec = importlib.util.spec_from_file_location("merge_detekt_sarif", collector)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
for name, automation_ids in {
    "main-test-without-trailing-run": [
        "detekt/app-bot/main",
        "detekt/app-bot/test",
    ],
    "multi-run-without-trailing-run": [
        "detekt/app-bot/test/run-1",
        "detekt/app-bot/test/run-2",
    ],
    "same-effective-category": [
        "detekt/core-data/main/run-a",
        "detekt/core-data/main/run-b",
    ],
}.items():
    try:
        module.validate_automation_ids(automation_ids)
    except module.SarifContractError:
        print(f"quality-gate: negative fixture detekt-category-{name} rejected (exit 1)")
    else:
        raise SystemExit(f"detekt-category-{name}: invalid IDs unexpectedly passed")
PY

detekt_old_upload_glob_fixture="$TMP_DIR/static-check-old-detekt-glob.yml"
replace_exact_line_once \
  "$static_check_workflow" \
  "$detekt_old_upload_glob_fixture" \
  "          sarif_file: build/reports/detekt/combined/detekt.sarif" \
  '          sarif_file: "**/build/reports/detekt/detekt.sarif"'
assert_validation_rejected \
  "detekt-old-sarif-upload-glob" \
  validate_detekt_static_workflow \
  "$detekt_old_upload_glob_fixture"

detekt_unguarded_upload_fixture="$TMP_DIR/static-check-unguarded-detekt-upload.yml"
replace_step_line_once \
  "$static_check_workflow" \
  "$detekt_unguarded_upload_fixture" \
  "detekt" \
  "Upload detekt SARIF to Code Scanning" \
  "        if: $detekt_sarif_report_guard" \
  "        if: always()"
assert_validation_rejected \
  "detekt-unguarded-sarif-upload" \
  validate_detekt_static_workflow \
  "$detekt_unguarded_upload_fixture"

detekt_upload_continue_fixture="$TMP_DIR/static-check-detekt-upload-continue.yml"
insert_step_direct_line \
  "$static_check_workflow" \
  "$detekt_upload_continue_fixture" \
  "detekt" \
  "Upload detekt SARIF to Code Scanning" \
  "        continue-on-error: true"
assert_validation_rejected \
  "detekt-sarif-upload-continue-on-error" \
  validate_detekt_static_workflow \
  "$detekt_upload_continue_fixture"

detekt_scanner_continue_fixture="$TMP_DIR/static-check-detekt-scanner-continue.yml"
insert_step_direct_line \
  "$static_check_workflow" \
  "$detekt_scanner_continue_fixture" \
  "detekt" \
  "Run detekt" \
  "        continue-on-error: true"
assert_validation_rejected \
  "detekt-scanner-continue-on-error" \
  validate_detekt_static_workflow \
  "$detekt_scanner_continue_fixture"

for scanner_guard in "false" "always()"; do
  scanner_guard_name="$(printf '%s' "$scanner_guard" | tr -cd '[:alnum:]')"
  detekt_scanner_guard_fixture="$TMP_DIR/static-check-detekt-scanner-if-$scanner_guard_name.yml"
  insert_step_direct_line \
    "$static_check_workflow" \
    "$detekt_scanner_guard_fixture" \
    "detekt" \
    "Run detekt" \
    "        if: $scanner_guard"
  assert_validation_rejected \
    "detekt-scanner-if-$scanner_guard_name" \
    validate_detekt_static_workflow \
    "$detekt_scanner_guard_fixture"
done

detekt_scanner_without_continue_fixture="$TMP_DIR/static-check-detekt-without-gradle-continue.yml"
replace_step_line_once \
  "$static_check_workflow" \
  "$detekt_scanner_without_continue_fixture" \
  "detekt" \
  "Run detekt" \
  "        run: ./gradlew detekt --continue --no-configuration-cache --console=plain" \
  "        run: ./gradlew detekt --no-configuration-cache --console=plain"
assert_validation_rejected \
  "detekt-scanner-without-gradle-continue" \
  validate_detekt_static_workflow \
  "$detekt_scanner_without_continue_fixture"

detekt_finalizer_guard_fixture="$TMP_DIR/static-check-detekt-finalizer-success-only.yml"
replace_step_line_once \
  "$static_check_workflow" \
  "$detekt_finalizer_guard_fixture" \
  "detekt" \
  "Finalize Detekt SARIF manifest" \
  "        if: always()" \
  "        if: success()"
assert_validation_rejected \
  "detekt-finalizer-without-always" \
  validate_detekt_static_workflow \
  "$detekt_finalizer_guard_fixture"

detekt_finalizer_continue_fixture="$TMP_DIR/static-check-detekt-finalizer-continue.yml"
insert_step_direct_line \
  "$static_check_workflow" \
  "$detekt_finalizer_continue_fixture" \
  "detekt" \
  "Finalize Detekt SARIF manifest" \
  "        continue-on-error: true"
assert_validation_rejected \
  "detekt-finalizer-continue-on-error" \
  validate_detekt_static_workflow \
  "$detekt_finalizer_continue_fixture"

detekt_collector_guard_fixture="$TMP_DIR/static-check-detekt-collector-success-only.yml"
replace_step_line_once \
  "$static_check_workflow" \
  "$detekt_collector_guard_fixture" \
  "detekt" \
  "Collect Detekt SARIF" \
  "        if: always()" \
  "        if: success()"
assert_validation_rejected \
  "detekt-collector-without-always" \
  validate_detekt_static_workflow \
  "$detekt_collector_guard_fixture"

detekt_collector_id_fixture="$TMP_DIR/static-check-detekt-collector-wrong-id.yml"
replace_step_line_once \
  "$static_check_workflow" \
  "$detekt_collector_id_fixture" \
  "detekt" \
  "Collect Detekt SARIF" \
  "        id: detekt-sarif-collector" \
  "        id: wrong-collector"
assert_validation_rejected \
  "detekt-collector-wrong-id" \
  validate_detekt_static_workflow \
  "$detekt_collector_id_fixture"

detekt_collector_run_contract="        run: python3 scripts/merge-detekt-sarif.py --manifest build/reports/detekt/expected-sarif.json --output build/reports/detekt/combined/detekt.sarif"
detekt_collector_manifest_fixture="$TMP_DIR/static-check-detekt-collector-wrong-manifest.yml"
replace_step_line_once \
  "$static_check_workflow" \
  "$detekt_collector_manifest_fixture" \
  "detekt" \
  "Collect Detekt SARIF" \
  "$detekt_collector_run_contract" \
  "        run: python3 scripts/merge-detekt-sarif.py --manifest build/reports/detekt/wrong-manifest.json --output build/reports/detekt/combined/detekt.sarif"
assert_validation_rejected \
  "detekt-collector-wrong-manifest" \
  validate_detekt_static_workflow \
  "$detekt_collector_manifest_fixture"

detekt_collector_output_fixture="$TMP_DIR/static-check-detekt-collector-wrong-output.yml"
replace_step_line_once \
  "$static_check_workflow" \
  "$detekt_collector_output_fixture" \
  "detekt" \
  "Collect Detekt SARIF" \
  "$detekt_collector_run_contract" \
  "        run: python3 scripts/merge-detekt-sarif.py --manifest build/reports/detekt/expected-sarif.json --output build/reports/detekt/combined/wrong.sarif"
assert_validation_rejected \
  "detekt-collector-wrong-output" \
  validate_detekt_static_workflow \
  "$detekt_collector_output_fixture"

detekt_missing_contents_permission_fixture="$TMP_DIR/static-check-detekt-missing-contents-permission.yml"
replace_exact_line_once \
  "$static_check_workflow" \
  "$detekt_missing_contents_permission_fixture" \
  "  contents: read" \
  "  # contents permission removed by negative fixture"
assert_validation_rejected \
  "detekt-missing-contents-permission" \
  validate_detekt_static_workflow \
  "$detekt_missing_contents_permission_fixture"

detekt_missing_security_events_permission_fixture="$TMP_DIR/static-check-detekt-missing-security-events-permission.yml"
replace_exact_line_once \
  "$static_check_workflow" \
  "$detekt_missing_security_events_permission_fixture" \
  "  security-events: write" \
  "  # security-events permission removed by negative fixture"
assert_validation_rejected \
  "detekt-missing-security-events-permission" \
  validate_detekt_static_workflow \
  "$detekt_missing_security_events_permission_fixture"

validate_docker_workflow_contracts "$ROOT_DIR"

validate_removed_nvd_owasp_inventory "$ROOT_DIR"
validate_keyless_action_inventory "$ROOT_DIR/.github/workflows"
validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_workflow" \
  "$security_scan_workflow"
echo "quality-gate: keyless dependency-security workflow contract verified"

verification_metadata="$ROOT_DIR/gradle/verification-metadata.xml"
validate_dependency_submission_verification_metadata "$verification_metadata"

plugin_metadata_version_fixture="$TMP_DIR/verification-metadata-plugin-version.xml"
replace_exact_line_once \
  "$verification_metadata" \
  "$plugin_metadata_version_fixture" \
  '      <component group="org.gradle" name="github-dependency-graph-gradle-plugin" version="1.4.1">' \
  '      <component group="org.gradle" name="github-dependency-graph-gradle-plugin" version="1.4.0">'
assert_validation_rejected \
  "dependency-submission-plugin-version-missing" \
  validate_dependency_submission_verification_metadata \
  "$plugin_metadata_version_fixture"

plugin_metadata_checksum_fixture="$TMP_DIR/verification-metadata-plugin-checksum.xml"
replace_exact_line_once \
  "$verification_metadata" \
  "$plugin_metadata_checksum_fixture" \
  '            <sha256 value="571cc95ec821649ca14c3895d637e8071af8f219f1f2aa4588cff3a95ea429c5" origin="Verified from Gradle Plugin Portal"/>' \
  '            <sha256 value="0000000000000000000000000000000000000000000000000000000000000000" origin="Verified from Gradle Plugin Portal"/>'
assert_validation_rejected \
  "dependency-submission-plugin-checksum-changed" \
  validate_dependency_submission_verification_metadata \
  "$plugin_metadata_checksum_fixture"

plugin_metadata_module_checksum_fixture="$TMP_DIR/verification-metadata-plugin-module-checksum.xml"
replace_exact_line_once \
  "$verification_metadata" \
  "$plugin_metadata_module_checksum_fixture" \
  '            <sha256 value="ed793bb8d17435a8b37260a5d72808b76db832e9ca58d136980e34336b2c18b2" origin="Verified from Gradle Plugin Portal"/>' \
  '            <sha256 value="0000000000000000000000000000000000000000000000000000000000000000" origin="Verified from Gradle Plugin Portal"/>'
assert_validation_rejected \
  "dependency-submission-plugin-module-checksum-changed" \
  validate_dependency_submission_verification_metadata \
  "$plugin_metadata_module_checksum_fixture"

remove_dependency_submission_metadata_artifact() {
  local source_file="$1"
  local target_file="$2"
  local artifact_name="$3"
  local companion_name="$4"

  python3 - "$source_file" "$target_file" "$artifact_name" "$companion_name" <<'PY'
from pathlib import Path
import shutil
import sys
import xml.etree.ElementTree as ET

source = Path(sys.argv[1])
target = Path(sys.argv[2])
artifact_name = sys.argv[3]
companion_name = sys.argv[4]
namespace = "https://schema.gradle.org/dependency-verification"
tag = lambda name: f"{{{namespace}}}{name}"
tree = ET.parse(source)
root = tree.getroot()
components = root.find(tag("components"))
if components is None:
    raise SystemExit("metadata artifact fixture: components are missing")
matches = [
    component
    for component in components.findall(tag("component"))
    if component.attrib
    == {
        "group": "org.gradle",
        "name": "github-dependency-graph-gradle-plugin",
        "version": "1.4.1",
    }
]
if len(matches) != 1:
    raise SystemExit("metadata artifact fixture: exact component count changed")
component = matches[0]
targets = [
    artifact
    for artifact in component.findall(tag("artifact"))
    if artifact.attrib == {"name": artifact_name}
]
companions = [
    artifact
    for artifact in component.findall(tag("artifact"))
    if artifact.attrib == {"name": companion_name}
]
if len(targets) != 1 or len(companions) != 1:
    raise SystemExit("metadata artifact fixture: target or companion count changed")
component.remove(targets[0])
ET.register_namespace("", namespace)
tree.write(target, encoding="utf-8", xml_declaration=True)
shutil.copymode(source, target)

mutated = ET.parse(target).getroot()
mutated_components = mutated.find(tag("components"))
mutated_match = [
    value
    for value in mutated_components.findall(tag("component"))
    if value.attrib
    == {
        "group": "org.gradle",
        "name": "github-dependency-graph-gradle-plugin",
        "version": "1.4.1",
    }
]
if len(mutated_match) != 1:
    raise SystemExit("metadata artifact fixture: mutated component count changed")
names = [
    artifact.attrib.get("name")
    for artifact in mutated_match[0].findall(tag("artifact"))
]
if artifact_name in names or names.count(companion_name) != 1:
    raise SystemExit("metadata artifact fixture: intended removal was not isolated")
PY
}

plugin_metadata_missing_jar_fixture="$TMP_DIR/verification-metadata-plugin-missing-jar.xml"
remove_dependency_submission_metadata_artifact \
  "$verification_metadata" \
  "$plugin_metadata_missing_jar_fixture" \
  "github-dependency-graph-gradle-plugin-1.4.1.jar" \
  "github-dependency-graph-gradle-plugin-1.4.1.module"
assert_validation_rejected \
  "dependency-submission-plugin-jar-missing" \
  validate_dependency_submission_verification_metadata \
  "$plugin_metadata_missing_jar_fixture"

plugin_metadata_missing_module_fixture="$TMP_DIR/verification-metadata-plugin-missing-module.xml"
remove_dependency_submission_metadata_artifact \
  "$verification_metadata" \
  "$plugin_metadata_missing_module_fixture" \
  "github-dependency-graph-gradle-plugin-1.4.1.module" \
  "github-dependency-graph-gradle-plugin-1.4.1.jar"
assert_validation_rejected \
  "dependency-submission-plugin-module-missing" \
  validate_dependency_submission_verification_metadata \
  "$plugin_metadata_missing_module_fixture"

plugin_metadata_trusted_group_fixture="$TMP_DIR/verification-metadata-plugin-trusted-group.xml"
replace_exact_line_once \
  "$verification_metadata" \
  "$plugin_metadata_trusted_group_fixture" \
  '      <verify-signatures>false</verify-signatures>' \
  '      <verify-signatures>false</verify-signatures><trusted-artifacts><trust group="org.gradle"/></trusted-artifacts>'
assert_validation_rejected \
  "dependency-submission-plugin-broad-trust" \
  validate_dependency_submission_verification_metadata \
  "$plugin_metadata_trusted_group_fixture"
echo "quality-gate: injected dependency plugin verification metadata contract verified"

oci_verifier_script="$ROOT_DIR/scripts/verify-oci-provenance.py"
oci_verifier_test="$ROOT_DIR/scripts/tests/test_verify_oci_provenance.py"
oci_verifier_test_package="$ROOT_DIR/scripts/tests/__init__.py"
for required_oci_file in \
  "$oci_verifier_script" \
  "$oci_verifier_test" \
  "$oci_verifier_test_package"; do
  if [ ! -f "$required_oci_file" ] || [ -L "$required_oci_file" ]; then
    fail "OCI verifier source/test path is not a regular non-symlink file: $required_oci_file"
  fi
done
oci_verifier_mode="$(
  stat -c '%a' "$oci_verifier_script" 2>/dev/null ||
    stat -f '%Lp' "$oci_verifier_script"
)"
if [ "$oci_verifier_mode" != "644" ]; then
  fail "OCI verifier script mode must be 0644, got $oci_verifier_mode"
fi
for required_oci_test_file in "$oci_verifier_test" "$oci_verifier_test_package"; do
  oci_test_mode="$(
    stat -c '%a' "$required_oci_test_file" 2>/dev/null ||
      stat -f '%Lp' "$required_oci_test_file"
  )"
  if [ "$oci_test_mode" != "644" ]; then
    fail "OCI verifier test file mode must be 0644, got $oci_test_mode"
  fi
done

unittest_failure_dir="$TMP_DIR/unittest-failure"
mkdir -p "$unittest_failure_dir"
cat >"$unittest_failure_dir/test_intentional_failure.py" <<'PY'
import unittest


class IntentionalFailure(unittest.TestCase):
    def test_failure_status_propagates(self):
        self.fail("intentional unittest runner probe")
PY
if PYTHONDONTWRITEBYTECODE=1 PYTHONPATH="$unittest_failure_dir" \
  python3 -m unittest -v test_intentional_failure \
  >"$TMP_DIR/unittest-intentional-failure.log" 2>&1; then
  fail "Python unittest runner returned zero for an intentional failure"
else
  unittest_failure_status=$?
fi
if [ "$unittest_failure_status" -eq 0 ]; then
  fail "Python unittest failure did not produce a nonzero status"
fi

if (
  cd "$ROOT_DIR"
  PYTHONDONTWRITEBYTECODE=1 \
    python3 -m unittest -v scripts.tests.test_verify_oci_provenance
); then
  :
else
  oci_test_status=$?
  fail "checked-out OCI verifier behavioral tests failed (exit $oci_test_status)"
fi
echo "quality-gate: checked-out OCI verifier tests executed and failures propagate"
echo "quality-gate: test/implementation independence relies on external review and rulesets"

validate_resolved_graph_build_contract "$ROOT_DIR/build.gradle.kts"

"$ROOT_DIR/gradlew" \
  verifyResolvedProductionDependencyGraph \
  --dependency-verification=strict \
  --no-configuration-cache \
  --console=plain
python3 - "$ROOT_DIR" <<'PY'
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
aggregate_path = root / "build/reports/dependencies/resolved-production-dependencies.json"
try:
    aggregate = json.loads(aggregate_path.read_text(encoding="utf-8"))
except (OSError, UnicodeError, json.JSONDecodeError) as error:
    raise SystemExit(f"resolved-graph-contract: aggregate report is unreadable: {error}")
if (
    not isinstance(aggregate, dict)
    or aggregate.get("schema") != "clubs-bot/resolved-production-dependency-graph"
    or aggregate.get("version") != 1
):
    raise SystemExit("resolved-graph-contract: aggregate schema/version is invalid")
projects = aggregate.get("projects")
if not isinstance(projects, list) or not projects:
    raise SystemExit("resolved-graph-contract: aggregate contains 0 JVM projects")
project_paths = []
direct_count = 0
transitive_count = 0
artifact_count = 0
for index, project in enumerate(projects):
    if not isinstance(project, dict):
        raise SystemExit(f"resolved-graph-contract: project entry {index} is not an object")
    project_path = project.get("projectPath")
    if not isinstance(project_path, str) or not project_path.startswith(":"):
        raise SystemExit(f"resolved-graph-contract: project entry {index} has invalid identity")
    if project.get("configuration") != "runtimeClasspath":
        raise SystemExit(f"resolved-graph-contract: {project_path} is not runtimeClasspath")
    direct = project.get("directDependencies")
    resolved = project.get("resolvedModules")
    transitive = project.get("transitiveModules")
    artifacts = project.get("resolvedArtifacts")
    if not isinstance(direct, list) or not direct:
        raise SystemExit(f"resolved-graph-contract: {project_path} has no direct dependencies")
    if not isinstance(resolved, list) or not resolved:
        raise SystemExit(f"resolved-graph-contract: {project_path} has no resolved modules")
    if not isinstance(transitive, list):
        raise SystemExit(f"resolved-graph-contract: {project_path} transitive graph is invalid")
    if not isinstance(artifacts, int) or isinstance(artifacts, bool) or artifacts <= 0:
        raise SystemExit(f"resolved-graph-contract: {project_path} has no resolved artifacts")
    if not set(transitive).issubset(set(resolved)):
        raise SystemExit(f"resolved-graph-contract: {project_path} has invalid transitive modules")
    relative_project = project_path.lstrip(":").replace(":", "/")
    project_report = root / relative_project / "build/reports/dependencies/runtime-dependencies.json"
    if project_path == ":":
        project_report = root / "build/reports/dependencies/runtime-dependencies.json"
    try:
        project_value = json.loads(project_report.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SystemExit(
            f"resolved-graph-contract: current project report is missing for {project_path}: {error}",
        )
    if project_value != project:
        raise SystemExit(f"resolved-graph-contract: aggregate entry differs for {project_path}")
    project_paths.append(project_path)
    direct_count += len(direct)
    transitive_count += len(transitive)
    artifact_count += artifacts
if project_paths != sorted(project_paths) or len(project_paths) != len(set(project_paths)):
    raise SystemExit("resolved-graph-contract: project identities are not sorted and unique")
if direct_count == 0 or transitive_count == 0 or artifact_count == 0:
    raise SystemExit("resolved-graph-contract: direct/transitive/artifact production graph is empty")
print(
    "quality-gate: resolved JVM production graph verified "
    f"({len(projects)} projects, {direct_count} direct, {transitive_count} transitive, "
    f"{artifact_count} artifacts)",
)
PY

hardcoded_graph_fixture="$TMP_DIR/build-hardcoded-jvm-projects.gradle.kts"
cp "$ROOT_DIR/build.gradle.kts" "$hardcoded_graph_fixture"
printf '%s\n' \
  'val hardcodedResolvedGraphProjects = listOf(":app-bot", ":core-data", ":core-domain", ":core-security", ":core-telemetry", ":core-testing", ":tools:perf")' \
  >>"$hardcoded_graph_fixture"
assert_validation_rejected \
  "dependency-graph-hardcoded-seven-module-limit" \
  validate_resolved_graph_build_contract \
  "$hardcoded_graph_fixture"

detached_runtime_artifact_fixture="$TMP_DIR/build-detached-runtime-artifacts.gradle.kts"
replace_exact_line_once \
  "$ROOT_DIR/build.gradle.kts" \
  "$detached_runtime_artifact_fixture" \
  "                runtimeArtifactFiles.from(runtimeClasspath)" \
  "                runtimeArtifactFiles.from(files())"
assert_validation_rejected \
  "dependency-graph-detached-runtime-artifact-input" \
  validate_resolved_graph_build_contract \
  "$detached_runtime_artifact_fixture"

empty_graph_init="$TMP_DIR/empty-resolved-graph.init.gradle"
cat >"$empty_graph_init" <<'GRADLE'
gradle.projectsEvaluated {
    def expectedRoot = new File(System.getenv('DEPENDENCY_REPOSITORY_ROOT')).canonicalFile
    if (rootProject.rootDir.canonicalFile == expectedRoot) {
        def aggregate = rootProject.tasks.named('verifyResolvedProductionDependencyGraph').get()
        aggregate.projectGraphFiles.setFrom([])
    }
}
GRADLE
empty_graph_log="$TMP_DIR/empty-resolved-graph.log"
if DEPENDENCY_REPOSITORY_ROOT="$ROOT_DIR" "$ROOT_DIR/gradlew" \
  verifyResolvedProductionDependencyGraph \
  --init-script "$empty_graph_init" \
  --rerun-tasks \
  --dependency-verification=strict \
  --no-configuration-cache \
  --console=plain >"$empty_graph_log" 2>&1; then
  fail "negative fixture unexpectedly passed: dependency-graph-empty"
else
  empty_graph_status=$?
fi
assert_contains "$(cat "$empty_graph_log")" "contains 0 JVM projects"
echo "quality-gate: negative fixture dependency-graph-empty rejected (exit $empty_graph_status)"

missing_module_init="$TMP_DIR/missing-resolved-graph-module.init.gradle"
cat >"$missing_module_init" <<'GRADLE'
gradle.projectsEvaluated {
    def expectedRoot = new File(System.getenv('DEPENDENCY_REPOSITORY_ROOT')).canonicalFile
    if (rootProject.rootDir.canonicalFile == expectedRoot) {
        def aggregate = rootProject.tasks.named('verifyResolvedProductionDependencyGraph').get()
        def filtered = aggregate.projectGraphFiles.files.findAll { report ->
            !report.path.replace('\\', '/').endsWith(
                '/core-domain/build/reports/dependencies/runtime-dependencies.json'
            )
        }
        aggregate.projectGraphFiles.setFrom(filtered)
    }
}
GRADLE
missing_module_log="$TMP_DIR/missing-resolved-graph-module.log"
if DEPENDENCY_REPOSITORY_ROOT="$ROOT_DIR" "$ROOT_DIR/gradlew" \
  verifyResolvedProductionDependencyGraph \
  --init-script "$missing_module_init" \
  --rerun-tasks \
  --dependency-verification=strict \
  --no-configuration-cache \
  --console=plain >"$missing_module_log" 2>&1; then
  fail "negative fixture unexpectedly passed: dependency-graph-missing-jvm-module"
else
  missing_module_status=$?
fi
assert_contains "$(cat "$missing_module_log")" "project inventory mismatch"
assert_contains "$(cat "$missing_module_log")" ":core-domain"
echo "quality-gate: negative fixture dependency-graph-missing-jvm-module rejected (exit $missing_module_status)"

if [ -e "$DEPENDENCY_DYNAMIC_MODULE_DIR" ]; then
  fail "temporary dependency graph fixture module path already exists"
fi
mkdir -p \
  "$DEPENDENCY_DYNAMIC_MODULE_DIR/producer" \
  "$DEPENDENCY_DYNAMIC_MODULE_DIR/consumer"
DEPENDENCY_DYNAMIC_MODULE_OWNED=1
cat >"$DEPENDENCY_DYNAMIC_MODULE_DIR/producer/build.gradle.kts" <<'KOTLIN'
plugins {
    java
}

dependencies {
    runtimeOnly(project(":core-domain"))
}

tasks.named<org.gradle.jvm.tasks.Jar>("jar") {
    archiveFileName.set("dependency-selfcheck-producer.jar")
    if (System.getenv("DEPENDENCY_FIXTURE_REMOVE_JAR") == "1") {
        doLast {
            archiveFile.get().asFile.delete()
        }
    }
}
KOTLIN
cat >"$DEPENDENCY_DYNAMIC_MODULE_DIR/consumer/build.gradle.kts" <<'KOTLIN'
plugins {
    java
}

dependencies {
    runtimeOnly(project(":dependency-selfcheck-fixture:producer"))
}
KOTLIN
dependency_dynamic_init="$TMP_DIR/dependency-dynamic-module.init.gradle"
cat >"$dependency_dynamic_init" <<'GRADLE'
gradle.settingsEvaluated { settings ->
    def expectedRoot = new File(System.getenv('DEPENDENCY_REPOSITORY_ROOT')).canonicalFile
    if (settings.rootDir.canonicalFile == expectedRoot) {
        settings.include(
            ':dependency-selfcheck-fixture:producer',
            ':dependency-selfcheck-fixture:consumer'
        )
    }
}
GRADLE
dependency_producer_jar="$DEPENDENCY_DYNAMIC_MODULE_DIR/producer/build/libs/dependency-selfcheck-producer.jar"
dependency_consumer_report="$DEPENDENCY_DYNAMIC_MODULE_DIR/consumer/build/reports/dependencies/runtime-dependencies.json"
dependency_consumer_log="$TMP_DIR/dependency-project-artifact-producer.log"
if [ -e "$DEPENDENCY_DYNAMIC_MODULE_DIR/producer/build" ] || [ -e "$dependency_producer_jar" ]; then
  fail "dependency producer fixture started with stale build outputs"
fi
DEPENDENCY_REPOSITORY_ROOT="$ROOT_DIR" "$ROOT_DIR/gradlew" \
  :dependency-selfcheck-fixture:consumer:verifyResolvedProductionDependencyGraph \
  --init-script "$dependency_dynamic_init" \
  --rerun-tasks \
  --no-build-cache \
  --dependency-verification=strict \
  --no-configuration-cache \
  --console=plain >"$dependency_consumer_log" 2>&1
if [ ! -f "$dependency_producer_jar" ]; then
  fail "consumer graph verifier did not build its producer project JAR"
fi
assert_contains \
  "$(cat "$dependency_consumer_log")" \
  "> Task :dependency-selfcheck-fixture:producer:jar"
python3 - "$dependency_consumer_report" <<'PY'
import json
from pathlib import Path
import sys

report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if report.get("projectPath") != ":dependency-selfcheck-fixture:consumer":
    raise SystemExit("consumer graph fixture has the wrong project identity")
if "project::dependency-selfcheck-fixture:producer" not in report.get(
    "directDependencies", []
):
    raise SystemExit("consumer graph omitted its producer project dependency")
if not report.get("resolvedModules") or report.get("resolvedArtifacts", 0) <= 0:
    raise SystemExit("consumer project dependency graph/artifact set is empty")
PY
echo "quality-gate: clean consumer graph verifier built its producer project JAR"

DEPENDENCY_REPOSITORY_ROOT="$ROOT_DIR" "$ROOT_DIR/gradlew" \
  verifyResolvedProductionDependencyGraph \
  --init-script "$dependency_dynamic_init" \
  --rerun-tasks \
  --no-build-cache \
  --dependency-verification=strict \
  --no-configuration-cache \
  --console=plain
python3 - "$ROOT_DIR/build/reports/dependencies/resolved-production-dependencies.json" <<'PY'
import json
from pathlib import Path
import sys

report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
projects = {entry["projectPath"]: entry for entry in report["projects"]}
producer = projects.get(":dependency-selfcheck-fixture:producer")
consumer = projects.get(":dependency-selfcheck-fixture:consumer")
if producer is None or consumer is None:
    raise SystemExit("future JVM producer/consumer modules were omitted from the resolved graph")
if "project::core-domain" not in producer.get("directDependencies", []):
    raise SystemExit("future producer project dependency was not recorded")
if "project::dependency-selfcheck-fixture:producer" not in consumer.get(
    "directDependencies", []
):
    raise SystemExit("future consumer project dependency was not recorded")
for fixture in (producer, consumer):
    if not fixture.get("resolvedModules") or fixture.get("resolvedArtifacts", 0) <= 0:
        raise SystemExit("future JVM module runtime graph/artifact set is empty")
if len(projects) != 9:
    raise SystemExit("future JVM fixture did not add exactly two projects")
PY

rm -rf \
  "$DEPENDENCY_DYNAMIC_MODULE_DIR/producer/build" \
  "$DEPENDENCY_DYNAMIC_MODULE_DIR/consumer/build"
if [ -e "$dependency_producer_jar" ]; then
  fail "dependency producer fixture JAR remained after fixture output cleanup"
fi
missing_producer_log="$TMP_DIR/dependency-graph-missing-producer-artifact.log"
if DEPENDENCY_REPOSITORY_ROOT="$ROOT_DIR" DEPENDENCY_FIXTURE_REMOVE_JAR=1 \
  "$ROOT_DIR/gradlew" \
  :dependency-selfcheck-fixture:consumer:verifyResolvedProductionDependencyGraph \
  --init-script "$dependency_dynamic_init" \
  --rerun-tasks \
  --no-build-cache \
  --dependency-verification=strict \
  --no-configuration-cache \
  --console=plain >"$missing_producer_log" 2>&1; then
  fail "negative fixture unexpectedly passed: dependency-graph-missing-producer-artifact"
else
  missing_producer_status=$?
fi
assert_contains \
  "$(cat "$missing_producer_log")" \
  "> Task :dependency-selfcheck-fixture:producer:jar"
assert_contains \
  "$(cat "$missing_producer_log")" \
  "Resolved runtime artifacts are missing for :dependency-selfcheck-fixture:consumer: dependency-selfcheck-producer.jar"
if [ -e "$dependency_producer_jar" ]; then
  fail "missing producer artifact fixture unexpectedly left its JAR behind"
fi
echo "quality-gate: negative fixture dependency-graph-missing-producer-artifact rejected (exit $missing_producer_status)"

rm -rf "$DEPENDENCY_DYNAMIC_MODULE_DIR"
DEPENDENCY_DYNAMIC_MODULE_OWNED=0
echo "quality-gate: future JVM producer/consumer dynamic graph inclusion verified"

"$ROOT_DIR/gradlew" \
  verifyResolvedProductionDependencyGraph \
  --rerun-tasks \
  --dependency-verification=strict \
  --no-configuration-cache \
  --console=plain

unresolved_dependency_init="$TMP_DIR/unresolved-runtime-dependency.init.gradle"
cat >"$unresolved_dependency_init" <<'GRADLE'
gradle.afterProject { project, state ->
    if (project.path == ':core-domain') {
        project.dependencies.add(
            'runtimeOnly',
            'invalid.example:unresolvable-runtime-selfcheck:0.0.0-does-not-exist'
        )
    }
}
GRADLE
unresolved_dependency_log="$TMP_DIR/unresolved-runtime-dependency.log"
if "$ROOT_DIR/gradlew" \
  verifyResolvedProductionDependencyGraph \
  --init-script "$unresolved_dependency_init" \
  --offline \
  --rerun-tasks \
  --dependency-verification=strict \
  --no-configuration-cache \
  --console=plain >"$unresolved_dependency_log" 2>&1; then
  fail "negative fixture unexpectedly passed: dependency-graph-unresolved-runtime"
else
  unresolved_dependency_status=$?
fi
if ! grep -Fq "Unresolved runtime dependencies for :core-domain" "$unresolved_dependency_log"; then
  fail "dependency-graph-unresolved-runtime fixture failed for an unexpected reason"
fi
echo "quality-gate: negative fixture dependency-graph-unresolved-runtime rejected (exit $unresolved_dependency_status)"

nvd_inventory_fixture="$TMP_DIR/nvd-inventory"
mkdir -p "$nvd_inventory_fixture"
printf '%s\n' 'NV''D_API_KEY=forbidden' >"$nvd_inventory_fixture/workflow.yml"
assert_validation_rejected \
  "legacy-nvd-reference-reintroduced" \
  validate_removed_nvd_owasp_inventory \
  "$nvd_inventory_fixture"

owasp_inventory_fixture="$TMP_DIR/owasp-inventory"
mkdir -p "$owasp_inventory_fixture"
printf '%s\n' 'id("org.owasp.''dependencycheck")' >"$owasp_inventory_fixture/build.gradle.kts"
assert_validation_rejected \
  "legacy-owasp-plugin-reintroduced" \
  validate_removed_nvd_owasp_inventory \
  "$owasp_inventory_fixture"

historical_inventory_fixture="$TMP_DIR/historical-inventory"
mkdir -p "$historical_inventory_fixture"
git -C "$historical_inventory_fixture" init -q
mkdir -p "$historical_inventory_fixture/reports"
printf '%s\n' 'dependency''CheckAnalyze' \
  >"$historical_inventory_fixture/reports/Q3_ci_detekt_clean.md"
git -C "$historical_inventory_fixture" add reports/Q3_ci_detekt_clean.md
assert_validation_rejected \
  "legacy-historical-report-reference-reintroduced" \
  validate_removed_nvd_owasp_inventory \
  "$historical_inventory_fixture"

required_check_inventory_fixture="$TMP_DIR/required-check-inventory"
mkdir -p "$required_check_inventory_fixture"
git -C "$required_check_inventory_fixture" init -q
mkdir -p "$required_check_inventory_fixture/docs"
printf '%s\n' 'SCA Gate / dependency-check' \
  >"$required_check_inventory_fixture/docs/required-check.md"
git -C "$required_check_inventory_fixture" add docs/required-check.md
validate_removed_nvd_owasp_inventory "$required_check_inventory_fixture"
echo "quality-gate: positive fixture required-check-identity accepted"

dependency_submission_action_fixture="$TMP_DIR/dependency-submission-mutable-action.yml"
replace_step_line_once \
  "$dependency_submission_workflow" \
  "$dependency_submission_action_fixture" \
  "submit" \
  "Submit resolved dependency graph (blocking)" \
  "        uses: gradle/actions/dependency-submission@3f131e8634966bd73d06cc69884922b02e6faf92 # v6.2.0" \
  "        uses: gradle/actions/dependency-submission@v6"
assert_validation_rejected \
  "dependency-submission-mutable-action" \
  validate_keyless_sca_workflows \
  "$dependency_submission_action_fixture" \
  "$sca_workflow" \
  "$security_scan_workflow"

dependency_submission_continue_fixture="$TMP_DIR/dependency-submission-continue-on-failure.yml"
replace_step_line_once \
  "$dependency_submission_workflow" \
  "$dependency_submission_continue_fixture" \
  "submit" \
  "Submit resolved dependency graph (blocking)" \
  "          dependency-graph-continue-on-failure: false" \
  "          dependency-graph-continue-on-failure: true"
assert_validation_rejected \
  "dependency-submission-continue-on-failure" \
  validate_keyless_sca_workflows \
  "$dependency_submission_continue_fixture" \
  "$sca_workflow" \
  "$security_scan_workflow"

dependency_submission_pr_fixture="$TMP_DIR/dependency-submission-pull-request.yml"
replace_exact_line_once \
  "$dependency_submission_workflow" \
  "$dependency_submission_pr_fixture" \
  "  workflow_dispatch:" \
  "  pull_request:"
assert_validation_rejected \
  "dependency-submission-pull-request-trigger" \
  validate_keyless_sca_workflows \
  "$dependency_submission_pr_fixture" \
  "$sca_workflow" \
  "$security_scan_workflow"

dependency_submission_guard_fixture="$TMP_DIR/dependency-submission-unguarded-dispatch.yml"
replace_job_line_once \
  "$dependency_submission_workflow" \
  "$dependency_submission_guard_fixture" \
  "submit" \
  "    if: github.event_name == 'push' || github.ref == 'refs/heads/main'" \
  "    if: always()"
assert_validation_rejected \
  "dependency-submission-dispatch-without-main-guard" \
  validate_keyless_sca_workflows \
  "$dependency_submission_guard_fixture" \
  "$sca_workflow" \
  "$security_scan_workflow"

dependency_submission_write_fixture="$TMP_DIR/dependency-submission-without-write.yml"
replace_job_line_once \
  "$dependency_submission_workflow" \
  "$dependency_submission_write_fixture" \
  "submit" \
  "      contents: write" \
  "      contents: read"
assert_validation_rejected \
  "dependency-submission-without-contents-write" \
  validate_keyless_sca_workflows \
  "$dependency_submission_write_fixture" \
  "$sca_workflow" \
  "$security_scan_workflow"

sca_write_fixture="$TMP_DIR/sca-contents-write.yml"
replace_job_line_once \
  "$sca_workflow" \
  "$sca_write_fixture" \
  "dependency-check" \
  "      contents: read" \
  "      contents: write"
assert_validation_rejected \
  "sca-pr-contents-write" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_write_fixture" \
  "$security_scan_workflow"

sca_secret_fixture="$TMP_DIR/sca-secret-reference.yml"
insert_step_direct_line \
  "$sca_workflow" \
  "$sca_secret_fixture" \
  "dependency-check" \
  "Verify resolved production dependency graph (blocking)" \
  '        env: ${{ secrets.SELF_CHECK_TOKEN }}'
assert_validation_rejected \
  "sca-pr-secret-reference" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_secret_fixture" \
  "$security_scan_workflow"

sca_trusted_trigger_fixture="$TMP_DIR/sca-trusted-trigger.yml"
replace_exact_line_once \
  "$sca_workflow" \
  "$sca_trusted_trigger_fixture" \
  "  pull_request:" \
  "  workflow_""run:"
assert_validation_rejected \
  "sca-workflow-""run-trigger" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_trusted_trigger_fixture" \
  "$security_scan_workflow"

sca_dependency_review_fixture="$TMP_DIR/sca-dependency-review.yml"
replace_step_line_once \
  "$sca_workflow" \
  "$sca_dependency_review_fixture" \
  "dependency-check" \
  "Gradle cache & setup" \
  "        uses: gradle/actions/setup-gradle@d9c87d481d55275bb5441eef3fe0e46805f9ef70 # v3.5.0" \
  "        uses: actions/dependency-""review-action@v5"
assert_validation_rejected \
  "sca-dependency-review-reintroduced" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_dependency_review_fixture" \
  "$security_scan_workflow"

sca_graph_removed_fixture="$TMP_DIR/sca-without-graph-verifier.yml"
replace_step_line_once \
  "$sca_workflow" \
  "$sca_graph_removed_fixture" \
  "dependency-check" \
  "Verify resolved production dependency graph (blocking)" \
  "          ./gradlew verifyResolvedProductionDependencyGraph" \
  "          ./gradlew help"
assert_validation_rejected \
  "sca-graph-verifier-removed" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_graph_removed_fixture" \
  "$security_scan_workflow"

trivy_jvm_coverage_fixture="$TMP_DIR/security-scan-without-install-dist.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_jvm_coverage_fixture" \
  "trivy" \
  "Build resolved JVM runtime dependencies (blocking)" \
  "          ./gradlew :app-bot:installDist verifyResolvedProductionDependencyGraph" \
  "          ./gradlew verifyResolvedProductionDependencyGraph"
assert_validation_rejected \
  "trivy-jvm-artifact-coverage-removed" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_workflow" \
  "$trivy_jvm_coverage_fixture"

trivy_exit_fixture="$TMP_DIR/security-scan-exit-code-zero.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_exit_fixture" \
  "trivy" \
  "Trivy JVM runtime scan" \
  "          exit-code: 1" \
  "          exit-code: 0"
assert_validation_rejected \
  "trivy-jvm-runtime-exit-code-zero" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_workflow" \
  "$trivy_exit_fixture"

trivy_runtime_removed_fixture="$TMP_DIR/security-scan-without-jvm-runtime-scan.yml"
remove_workflow_step_once \
  "$security_scan_workflow" \
  "$trivy_runtime_removed_fixture" \
  "trivy" \
  "Trivy JVM runtime scan"
assert_validation_rejected \
  "trivy-jvm-runtime-scan-removed" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_workflow" \
  "$trivy_runtime_removed_fixture"

trivy_runtime_fs_fixture="$TMP_DIR/security-scan-jvm-runtime-filesystem.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_runtime_fs_fixture" \
  "trivy" \
  "Trivy JVM runtime scan" \
  "          scan-type: rootfs" \
  "          scan-type: fs"
assert_validation_rejected \
  "trivy-jvm-runtime-rootfs-replaced-with-filesystem" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_workflow" \
  "$trivy_runtime_fs_fixture"

trivy_runtime_severity_fixture="$TMP_DIR/security-scan-jvm-runtime-critical-only.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_runtime_severity_fixture" \
  "trivy" \
  "Trivy JVM runtime scan" \
  "          severity: HIGH,CRITICAL" \
  "          severity: CRITICAL"
assert_validation_rejected \
  "trivy-jvm-runtime-severity-weakened" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_workflow" \
  "$trivy_runtime_severity_fixture"

trivy_filesystem_sarif_limit_missing_fixture="$TMP_DIR/security-scan-filesystem-sarif-limit-missing.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_filesystem_sarif_limit_missing_fixture" \
  "trivy" \
  "Trivy filesystem scan" \
  "          limit-severities-for-sarif: true" \
  ""
assert_validation_rejected \
  "trivy-filesystem-sarif-limit-missing" \
  validate_trivy_filesystem_workflow \
  "$trivy_filesystem_sarif_limit_missing_fixture"

trivy_runtime_sarif_limit_missing_fixture="$TMP_DIR/security-scan-jvm-sarif-limit-missing.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_runtime_sarif_limit_missing_fixture" \
  "trivy" \
  "Trivy JVM runtime scan" \
  "          limit-severities-for-sarif: true" \
  ""
assert_validation_rejected \
  "trivy-jvm-sarif-limit-missing" \
  validate_trivy_runtime_workflow \
  "$trivy_runtime_sarif_limit_missing_fixture"

trivy_filesystem_sarif_limit_false_fixture="$TMP_DIR/security-scan-filesystem-sarif-limit-false.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_filesystem_sarif_limit_false_fixture" \
  "trivy" \
  "Trivy filesystem scan" \
  "          limit-severities-for-sarif: true" \
  "          limit-severities-for-sarif: false"
assert_validation_rejected \
  "trivy-filesystem-sarif-limit-false" \
  validate_trivy_filesystem_workflow \
  "$trivy_filesystem_sarif_limit_false_fixture"

trivy_runtime_sarif_limit_false_fixture="$TMP_DIR/security-scan-jvm-sarif-limit-false.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_runtime_sarif_limit_false_fixture" \
  "trivy" \
  "Trivy JVM runtime scan" \
  "          limit-severities-for-sarif: true" \
  "          limit-severities-for-sarif: false"
assert_validation_rejected \
  "trivy-jvm-sarif-limit-false" \
  validate_trivy_runtime_workflow \
  "$trivy_runtime_sarif_limit_false_fixture"

trivy_sarif_limit_only_one_fixture="$TMP_DIR/security-scan-sarif-limit-only-filesystem.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_sarif_limit_only_one_fixture" \
  "trivy" \
  "Trivy JVM runtime scan" \
  "          limit-severities-for-sarif: true" \
  ""
assert_validation_rejected \
  "trivy-sarif-limit-present-only-for-filesystem" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_workflow" \
  "$trivy_sarif_limit_only_one_fixture"

trivy_runtime_continue_fixture="$TMP_DIR/security-scan-jvm-runtime-continue.yml"
insert_step_direct_line \
  "$security_scan_workflow" \
  "$trivy_runtime_continue_fixture" \
  "trivy" \
  "Trivy JVM runtime scan" \
  "        continue-on-error: true"
assert_validation_rejected \
  "trivy-jvm-runtime-continue-on-error" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_workflow" \
  "$trivy_runtime_continue_fixture"

trivy_verifier_removed_fixture="$TMP_DIR/security-scan-without-jvm-inventory-verifier.yml"
remove_workflow_step_once \
  "$security_scan_workflow" \
  "$trivy_verifier_removed_fixture" \
  "trivy" \
  "Verify Trivy JVM analyzer coverage"
assert_validation_rejected \
  "trivy-jvm-inventory-verifier-removed" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_workflow" \
  "$trivy_verifier_removed_fixture"

trivy_wrong_root_fixture="$TMP_DIR/security-scan-jvm-runtime-wrong-root.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_wrong_root_fixture" \
  "trivy" \
  "Trivy JVM runtime scan" \
  "          scan-ref: app-bot/build/install/app-bot" \
  "          scan-ref: app-bot/build/libs"
assert_validation_rejected \
  "trivy-jvm-runtime-wrong-scan-root" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_workflow" \
  "$trivy_wrong_root_fixture"

trivy_duplicate_reports_fixture="$TMP_DIR/security-scan-duplicate-trivy-reports.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_duplicate_reports_fixture" \
  "trivy" \
  "Trivy JVM runtime scan" \
  "          output: \${{ steps.trivy-output.outputs.jvm_sarif }}" \
  "          output: \${{ steps.trivy-output.outputs.filesystem_sarif }}"
replace_step_line_once \
  "$trivy_duplicate_reports_fixture" \
  "$trivy_duplicate_reports_fixture.changed" \
  "trivy" \
  "Upload Trivy JVM runtime SARIF to code scanning" \
  "          sarif_file: \${{ steps.trivy-output.outputs.jvm_sarif }}" \
  "          sarif_file: \${{ steps.trivy-output.outputs.filesystem_sarif }}"
replace_step_line_once \
  "$trivy_duplicate_reports_fixture.changed" \
  "$trivy_duplicate_reports_fixture" \
  "trivy" \
  "Upload Trivy JVM runtime SARIF to code scanning" \
  "          category: trivy-jvm-runtime" \
  "          category: trivy-filesystem"
assert_validation_rejected \
  "trivy-runtime-sarif-and-category-duplicate-filesystem" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_workflow" \
  "$trivy_duplicate_reports_fixture"

trivy_checkout_output_fixture="$TMP_DIR/security-scan-checkout-output.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_checkout_output_fixture" \
  "trivy" \
  "Trivy filesystem scan" \
  "          output: \${{ steps.trivy-output.outputs.filesystem_sarif }}" \
  "          output: trivy-filesystem.sarif"
assert_validation_rejected \
  "trivy-authoritative-output-returned-to-checkout" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_workflow" \
  "$trivy_checkout_output_fixture"

trivy_preflight_removed_fixture="$TMP_DIR/security-scan-without-trusted-output-preflight.yml"
remove_workflow_step_once \
  "$security_scan_workflow" \
  "$trivy_preflight_removed_fixture" \
  "trivy" \
  "Prepare trusted Trivy output directory"
assert_validation_rejected \
  "trivy-trusted-output-preflight-removed" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_workflow" \
  "$trivy_preflight_removed_fixture"

trivy_existing_directory_allowed_fixture="$TMP_DIR/security-scan-existing-output-directory-allowed.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_existing_directory_allowed_fixture" \
  "trivy" \
  "Prepare trusted Trivy output directory" \
  '          if [ -e "$output_dir" ] || [ -L "$output_dir" ]; then' \
  "          if false; then"
assert_validation_rejected \
  "trivy-pre-existing-output-directory-allowed" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_workflow" \
  "$trivy_existing_directory_allowed_fixture"

trivy_existing_file_allowed_fixture="$TMP_DIR/security-scan-existing-output-file-allowed.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_existing_file_allowed_fixture" \
  "trivy" \
  "Prepare trusted Trivy output directory" \
  '            test ! -e "$output_dir/$output_name"' \
  '            test -n "$output_dir/$output_name"'
assert_validation_rejected \
  "trivy-pre-existing-output-file-allowed" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_workflow" \
  "$trivy_existing_file_allowed_fixture"

trivy_upload_mismatch_fixture="$TMP_DIR/security-scan-stale-upload-path.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_upload_mismatch_fixture" \
  "trivy" \
  "Upload Trivy JVM runtime SARIF to code scanning" \
  "          sarif_file: \${{ steps.trivy-output.outputs.jvm_sarif }}" \
  "          sarif_file: \${{ steps.trivy-output.outputs.filesystem_sarif }}"
assert_validation_rejected \
  "trivy-upload-path-does-not-match-scanner-output" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_workflow" \
  "$trivy_upload_mismatch_fixture"

trivy_workspace_temp_fixture="$TMP_DIR/security-scan-workspace-as-trusted-temp.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_workspace_temp_fixture" \
  "trivy" \
  "Prepare trusted Trivy output directory" \
  "          TRIVY_RUNNER_TEMP: \${{ runner.temp }}" \
  "          TRIVY_RUNNER_TEMP: \${{ github.workspace }}"
assert_validation_rejected \
  "trivy-trusted-directory-replaced-with-checkout" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_workflow" \
  "$trivy_workspace_temp_fixture"

trivy_order_fixture="$TMP_DIR/security-scan-trivy-before-install-dist.yml"
python3 - "$security_scan_workflow" "$trivy_order_fixture" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1])
target = Path(sys.argv[2])
lines = source.read_text(encoding="utf-8").splitlines(keepends=True)
names = [
    "Build resolved JVM runtime dependencies (blocking)",
    "Trivy JVM runtime scan",
]
indexes = {}
for index, line in enumerate(lines):
    logical = line.rstrip("\r\n")
    for name in names:
        if logical == f"      - name: {name}":
            indexes.setdefault(name, []).append(index)
if any(len(indexes.get(name, [])) != 1 for name in names):
    raise SystemExit("trivy-order-fixture: expected one build and one scanner step")
build_start = indexes[names[0]][0]
scan_start = indexes[names[1]][0]
if build_start >= scan_start:
    raise SystemExit("trivy-order-fixture: source ordering is already invalid")
scan_end = len(lines)
for index in range(scan_start + 1, len(lines)):
    if lines[index].startswith("      - name: "):
        scan_end = index
        break
mutated = lines[:build_start] + lines[scan_start:scan_end] + lines[build_start:scan_start] + lines[scan_end:]
target.write_text("".join(mutated), encoding="utf-8")
PY
assert_validation_rejected \
  "trivy-jvm-runtime-before-install-dist" \
  validate_keyless_sca_workflows \
  "$dependency_submission_workflow" \
  "$sca_workflow" \
  "$trivy_order_fixture"

trivy_inventory_repository="$TMP_DIR/trivy-inventory-repository"
trivy_inventory_trusted="$TMP_DIR/trivy-inventory-trusted"
trivy_inventory_scan_root="app-bot/build/install/app-bot"
mkdir -p \
  "$trivy_inventory_repository/$trivy_inventory_scan_root/lib" \
  "$trivy_inventory_trusted"
printf '%s\n' "schema-faithful runtime JAR fixture" \
  >"$trivy_inventory_repository/$trivy_inventory_scan_root/lib/runtime-1.0.0.jar"
trivy_inventory_repository="$(cd -P -- "$trivy_inventory_repository" && pwd -P)"
trivy_inventory_trusted="$(cd -P -- "$trivy_inventory_trusted" && pwd -P)"

trivy_inventory_positive="$trivy_inventory_trusted/positive.json"
cat >"$trivy_inventory_positive" <<'JSON'
{
  "SchemaVersion": 2,
  "Trivy": {"Version": "0.69.3"},
  "ArtifactName": "app-bot/build/install/app-bot",
  "ArtifactType": "filesystem",
  "Results": [
    {
      "Target": "Java",
      "Class": "lang-pkgs",
      "Type": "jar",
      "Packages": [
        {
          "Name": "example:runtime",
          "Identifier": {"PURL": "pkg:maven/example/runtime@1.0.0", "UID": "runtime"},
          "Version": "1.0.0",
          "AnalyzedBy": "jar",
          "FilePath": "lib/runtime-1.0.0.jar"
        },
        {
          "Name": "example:embedded",
          "Identifier": {"PURL": "pkg:maven/example/embedded@2.0.0", "UID": "embedded"},
          "Version": "2.0.0",
          "AnalyzedBy": "jar",
          "FilePath": "lib/runtime-1.0.0.jar"
        }
      ]
    }
  ]
}
JSON

python3 - "$trivy_inventory_positive" "$trivy_inventory_trusted" <<'PY'
from copy import deepcopy
import json
from pathlib import Path
import sys

source = Path(sys.argv[1])
target_dir = Path(sys.argv[2])
positive = json.loads(source.read_text(encoding="utf-8"))


def write(name, payload):
    (target_dir / name).write_text(
        json.dumps(payload, indent=2) + "\n", encoding="utf-8"
    )


def mutated_package(**changes):
    payload = deepcopy(positive)
    package = payload["Results"][0]["Packages"][0]
    package.update(changes)
    payload["Results"][0]["Packages"] = [package]
    return payload


decoupled = deepcopy(positive)
first = deepcopy(decoupled["Results"][0]["Packages"][0])
second = deepcopy(decoupled["Results"][0]["Packages"][1])
first.update({"AnalyzedBy": "jar", "FilePath": "not-an-archive.txt"})
second.update({"AnalyzedBy": "gomod", "FilePath": "lib/runtime-1.0.0.jar"})
decoupled["Results"][0]["Packages"] = [first, second]
write("decoupled.json", decoupled)

duplicate = deepcopy(positive)
duplicate["Results"].append(deepcopy(duplicate["Results"][0]))
write("duplicate-java-result.json", duplicate)

conflicting = deepcopy(positive)
conflicting["Results"].append(
    {"Target": "Java", "Class": "lang-pkgs", "Type": "gomod", "Packages": []}
)
write("conflicting-java-result.json", conflicting)
write("wrong-analyzer.json", mutated_package(AnalyzedBy="gomod"))
write("non-archive-path.json", mutated_package(FilePath="lib/runtime-1.0.0.txt"))
write("escaping-package-path.json", mutated_package(FilePath="../outside.jar"))
write("absolute-package-path.json", mutated_package(FilePath="/tmp/runtime.jar"))
write("missing-package-jar.json", mutated_package(FilePath="lib/missing.jar"))
write("symlink-package-jar.json", mutated_package(FilePath="lib/runtime-link.jar"))

empty_results = deepcopy(positive)
empty_results["Results"] = []
write("empty-results.json", empty_results)

no_packages = deepcopy(positive)
del no_packages["Results"][0]["Packages"]
write("java-without-packages.json", no_packages)

escaping_artifact = deepcopy(positive)
escaping_artifact["ArtifactName"] = "../../outside"
write("escaping-artifact-root.json", escaping_artifact)

wrong_artifact = deepcopy(positive)
wrong_artifact["ArtifactName"] = "app-bot/build/libs"
write("wrong-artifact-root.json", wrong_artifact)

wrong_artifact_type = deepcopy(positive)
wrong_artifact_type["ArtifactType"] = "repository"
write("wrong-artifact-type.json", wrong_artifact_type)

pom_only = deepcopy(positive)
pom_only["Results"] = [
    {
        "Target": "pom.xml",
        "Class": "lang-pkgs",
        "Type": "pom",
        "Packages": [
            {
                "Name": "example:pom",
                "Identifier": {"PURL": "pkg:maven/example/pom@1.0.0"},
                "Version": "1.0.0",
                "AnalyzedBy": "pom",
                "FilePath": "pom.xml",
            }
        ],
    }
]
write("pom-only.json", pom_only)
PY

trivy_inventory_positive_out="$(
  python3 "$ROOT_DIR/scripts/verify-trivy-java-inventory.py" \
    "$trivy_inventory_repository" \
    "$trivy_inventory_trusted" \
    positive.json \
    "$trivy_inventory_scan_root"
)"
assert_contains "$trivy_inventory_positive_out" "runtime_packages=2"
assert_contains "$trivy_inventory_positive_out" "coupled_jar_packages=2"
assert_contains "$trivy_inventory_positive_out" "runtime_archives=1"

for trivy_fixture in \
  decoupled \
  duplicate-java-result \
  conflicting-java-result \
  wrong-analyzer \
  non-archive-path \
  escaping-package-path \
  absolute-package-path \
  missing-package-jar \
  empty-results \
  java-without-packages \
  escaping-artifact-root \
  wrong-artifact-root \
  wrong-artifact-type \
  pom-only; do
  assert_validation_rejected \
    "trivy-jvm-inventory-$trivy_fixture" \
    python3 "$ROOT_DIR/scripts/verify-trivy-java-inventory.py" \
    "$trivy_inventory_repository" \
    "$trivy_inventory_trusted" \
    "$trivy_fixture.json" \
    "$trivy_inventory_scan_root"
done

ln -s runtime-1.0.0.jar \
  "$trivy_inventory_repository/$trivy_inventory_scan_root/lib/runtime-link.jar"
assert_validation_rejected \
  "trivy-jvm-inventory-package-jar-symlink" \
  python3 "$ROOT_DIR/scripts/verify-trivy-java-inventory.py" \
  "$trivy_inventory_repository" \
  "$trivy_inventory_trusted" \
  symlink-package-jar.json \
  "$trivy_inventory_scan_root"

ln -s positive.json "$trivy_inventory_trusted/report-link.json"
assert_validation_rejected \
  "trivy-jvm-inventory-report-symlink" \
  python3 "$ROOT_DIR/scripts/verify-trivy-java-inventory.py" \
  "$trivy_inventory_repository" \
  "$trivy_inventory_trusted" \
  report-link.json \
  "$trivy_inventory_scan_root"

mkfifo "$trivy_inventory_trusted/report-fifo.json"
assert_validation_rejected \
  "trivy-jvm-inventory-report-fifo" \
  python3 "$ROOT_DIR/scripts/verify-trivy-java-inventory.py" \
  "$trivy_inventory_repository" \
  "$trivy_inventory_trusted" \
  report-fifo.json \
  "$trivy_inventory_scan_root"

printf '%s\n' '{"SchemaVersion": 2, "Results": [' \
  >"$trivy_inventory_trusted/malformed.json"
assert_validation_rejected \
  "trivy-jvm-inventory-malformed" \
  python3 "$ROOT_DIR/scripts/verify-trivy-java-inventory.py" \
  "$trivy_inventory_repository" \
  "$trivy_inventory_trusted" \
  malformed.json \
  "$trivy_inventory_scan_root"

assert_validation_rejected \
  "trivy-jvm-inventory-absolute-report-path" \
  python3 "$ROOT_DIR/scripts/verify-trivy-java-inventory.py" \
  "$trivy_inventory_repository" \
  "$trivy_inventory_trusted" \
  "$trivy_inventory_positive" \
  "$trivy_inventory_scan_root"
assert_validation_rejected \
  "trivy-jvm-inventory-absolute-expected-root" \
  python3 "$ROOT_DIR/scripts/verify-trivy-java-inventory.py" \
  "$trivy_inventory_repository" \
  "$trivy_inventory_trusted" \
  positive.json \
  "/app-bot/build/install/app-bot"
assert_validation_rejected \
  "trivy-jvm-inventory-escaping-expected-root" \
  python3 "$ROOT_DIR/scripts/verify-trivy-java-inventory.py" \
  "$trivy_inventory_repository" \
  "$trivy_inventory_trusted" \
  positive.json \
  "app-bot/build/install/../install/app-bot"
echo "quality-gate: Trivy JVM analyzer inventory fixtures verified"

if ! validate_trivy_action_inventory "$ROOT_DIR/.github/workflows"; then
  fail "Trivy action inventory contract failed"
fi
validate_trivy_filesystem_workflow "$security_scan_workflow"
validate_trivy_runtime_workflow "$security_scan_workflow"
validate_trivy_image_workflow "$docker_publish_workflow"
echo "quality-gate: Trivy workflow contract verified"

validate_container_smoke_workflow "$container_smoke_workflow"
echo "quality-gate: Container Smoke runtime/network contract verified"

"$ROOT_DIR/gradlew" :app-bot:installDist --rerun-tasks --console=plain
validate_packaged_launcher "$ROOT_DIR/app-bot/build/install/app-bot/bin/app-bot"
validate_packaged_launcher "$ROOT_DIR/app-bot/build/install/app-bot/bin/app-bot.bat"
validate_packaged_migration_launcher \
  "$ROOT_DIR/app-bot/build/install/app-bot/bin/app-bot-migrate" \
  "$ROOT_DIR/app-bot/build/install/app-bot/bin/app-bot-migrate-java"
echo "quality-gate: packaged EngineMain launchers verified"

dockerignore_file="$ROOT_DIR/.dockerignore"
approved_dockerignore_contract='.git
.github
.githooks
.idea
.vscode
*.iml
out/
.gradle
.kotlin
build
buildSrc/.gradle
buildSrc/.kotlin
buildSrc/build
app-bot/build
core-domain/build
core-data/build
core-security/build
core-telemetry/build
core-testing/build
tools/build
tools/perf/build
node_modules
miniapp/node_modules
miniapp/dist
*.log
*.tmp
.env
.env.*
env.env
scripts/dev-env.sh
scripts/dev-env.local.sh
docker-compose*.yml
.git/
.gitignore
.DS_Store
node_modules/
app-bot/src/main/resources/miniapp/dist/*'

normalize_dockerignore_contract() {
  awk '
    {
      sub(/\r$/, "")
      normalized = $0
      sub(/^[[:space:]]+/, "", normalized)
      sub(/[[:space:]]+$/, "", normalized)
      if (normalized == "" || substr(normalized, 1, 1) == "#") {
        next
      }
      print normalized
    }
  ' "$1"
}

validate_dockerignore_contract() {
  local file="$1"
  local actual_contract
  actual_contract="$(normalize_dockerignore_contract "$file")"
  if [ "$actual_contract" != "$approved_dockerignore_contract" ]; then
    echo "Dockerignore active-rule contract does not match the approved ordered rules" >&2
    return 1
  fi
}

if ! validate_dockerignore_contract "$dockerignore_file"; then
  fail "full Dockerignore contract validation failed"
fi

dockerignore_generated_rules="$(
  awk '
    NF && $1 !~ /^#/ && (index($0, "build") || $0 == ".gradle" || $0 == ".kotlin") { print }
  ' "$dockerignore_file" | LC_ALL=C sort
)"
expected_generated_rules="$(
  printf '%s\n' \
    ".gradle" \
    ".kotlin" \
    "build" \
    "buildSrc/.gradle" \
    "buildSrc/.kotlin" \
    "buildSrc/build" \
    "app-bot/build" \
    "core-domain/build" \
    "core-data/build" \
    "core-security/build" \
    "core-telemetry/build" \
    "core-testing/build" \
    "tools/build" \
    "tools/perf/build" |
    LC_ALL=C sort
)"
assert_eq "$dockerignore_generated_rules" "$expected_generated_rules"

for dockerignore_rule in \
  ".git" \
  "node_modules" \
  "miniapp/node_modules" \
  "miniapp/dist" \
  ".env" \
  ".env.*" \
  "env.env" \
  "scripts/dev-env.sh" \
  "scripts/dev-env.local.sh"; do
  assert_exact_line "$dockerignore_file" "$dockerignore_rule"
done

if awk 'NF && $1 !~ /^#/ && $0 ~ /^!/' "$dockerignore_file" | grep -q .; then
  fail ".dockerignore must not use broad re-includes"
fi
if [ ! -f "$ROOT_DIR/buildSrc/src/main/kotlin/com/example/build/LogsPolicyScanTask.kt" ]; then
  fail "LogsPolicyScanTask source is missing"
fi

dockerignore_wide_kotlin_fixture="$TMP_DIR/dockerignore-wide-kotlin"
cp "$dockerignore_file" "$dockerignore_wide_kotlin_fixture"
printf '\n%s\n' "**/*.kt" >>"$dockerignore_wide_kotlin_fixture"
assert_validation_rejected \
  "dockerignore-wide-kotlin" \
  validate_dockerignore_contract \
  "$dockerignore_wide_kotlin_fixture"

dockerignore_wide_src_fixture="$TMP_DIR/dockerignore-wide-src-main"
cp "$dockerignore_file" "$dockerignore_wide_src_fixture"
printf '\n%s\n' "**/src/main/**" >>"$dockerignore_wide_src_fixture"
assert_validation_rejected \
  "dockerignore-wide-src-main" \
  validate_dockerignore_contract \
  "$dockerignore_wide_src_fixture"

workflow_capability_validator="$ROOT_DIR/scripts/validate-workflow-capabilities.rb"
workflow_yaml_validator="$ROOT_DIR/scripts/validate-workflow-yaml.rb"

copy_workflow_capability_fixture() {
  local fixture_name="$1"
  local fixture_root="$TMP_DIR/workflow-capability-$fixture_name"
  mkdir -p \
    "$fixture_root/.github/workflows" \
    "$fixture_root/docs/ops" \
    "$fixture_root/gradle" \
    "$fixture_root/scripts/deploy"
  git -C "$fixture_root" init -q
  while IFS= read -r -d '' workflow; do
    cp "$ROOT_DIR/$workflow" "$fixture_root/$workflow"
  done < <(
    git -C "$ROOT_DIR" ls-files -z -- \
      ':(glob).github/workflows/*.yml' \
      ':(glob).github/workflows/*.yaml'
  )
  cp "$ROOT_DIR/scripts/deploy/quiesced-release.sh" "$fixture_root/scripts/deploy/"
  cp "$ROOT_DIR/scripts/deploy/remote-compose-release.sh" "$fixture_root/scripts/deploy/"
  cp "$ROOT_DIR/scripts/verify-oci-provenance.py" "$fixture_root/scripts/"
  cp "$ROOT_DIR/gradle/verification-metadata.xml" "$fixture_root/gradle/"
  cp "$ROOT_DIR/docs/ops/secrets-rotation.md" "$fixture_root/docs/ops/"
  git -C "$fixture_root" add \
    .github/workflows \
    docs/ops \
    gradle/verification-metadata.xml \
    scripts/deploy \
    scripts/verify-oci-provenance.py
  printf '%s\n' "$fixture_root"
}

mutate_workflow_capability_fixture() {
  local fixture_root="$1"
  local fixture_case="$2"
  ruby -I"$ROOT_DIR/scripts" -rvalidate-workflow-yaml \
    - "$fixture_root" "$fixture_case" <<'RUBY'
require "pathname"

root = Pathname.new(ARGV.fetch(0))
fixture_case = ARGV.fetch(1)
workflows = root.join(".github/workflows")

def replace_once(path, old, replacement)
  text = File.binread(path)
  old = old.b
  replacement = replacement.b
  abort "fixture source changed for #{path}: #{old.inspect}" unless text.scan(old).length == 1
  File.binwrite(path, text.sub(old, replacement))
end

def swap_once(path, first, second)
  text = File.binread(path)
  abort "fixture source changed for #{path}: #{first.inspect}" unless text.scan(first).length == 1
  abort "fixture source changed for #{path}: #{second.inspect}" unless text.scan(second).length == 1
  sentinel = "__CAPABILITY_FIXTURE_SWAP_SENTINEL__"
  abort "fixture swap sentinel already exists: #{path}" if text.include?(sentinel)
  text = text.sub(first, sentinel).sub(second, first).sub(sentinel, second)
  File.binwrite(path, text)
end

def add_workflow(workflows, name, body)
  path = workflows.join("#{name}.yml")
  abort "fixture workflow already exists: #{path}" if path.exist?
  File.binwrite(path, body)
end

def read_only_workflow(job_body)
  header = <<~YAML
    name: Capability fixture
    on:
      workflow_dispatch:
    permissions:
      contents: read
    jobs:
      inspect:
        runs-on: ubuntu-latest
  YAML
  header + job_body.each_line.map { |line| "    #{line}" }.join
end

def workflow_with_top_level(top_level)
  header = <<~YAML
    name: YAML alias safety fixture
    on:
      workflow_dispatch:
    permissions:
      contents: read
  YAML
  footer = <<~YAML
    jobs:
      inspect:
        runs-on: ubuntu-latest
        steps:
          - run: echo fixture
  YAML
  header + top_level + footer
end

def alias_chain(last_index)
  lines = ["chain_0: &chain_0", "  value: base"]
  1.upto(last_index) do |index|
    lines << "chain_#{index}: &chain_#{index}"
    lines << "  previous: *chain_#{index - 1}"
  end
  lines.join("\n") + "\n"
end

def alias_references(count)
  lines = ["shared: &shared", "  value: safe", "references:"]
  count.times { lines << "  - *shared" }
  lines.join("\n") + "\n"
end

def step_blocks(path)
  lines = File.readlines(path)
  starts = lines.each_index.select { |index| lines[index].start_with?("      - name:") }
  abort "fixture has no named steps: #{path}" if starts.empty?
  prefix = lines[0...starts.first]
  blocks = starts.each_with_index.map do |start, index|
    finish = starts.fetch(index + 1, lines.length)
    lines[start...finish]
  end
  [prefix, blocks]
end

def step_name(block)
  match = block.first.match(/\A      - name: (.+)\n\z/)
  abort "fixture has malformed named step: #{block.first.inspect}" unless match
  match[1]
end

def write_step_blocks(path, prefix, blocks)
  File.binwrite(path, (prefix + blocks.flatten).join)
end

def append_named_step(path, body)
  prefix, blocks = step_blocks(path)
  block = body.each_line.map { |line| "      #{line}" }
  write_step_blocks(path, prefix, blocks + [block])
end

def insert_after_named_step(path, name, body)
  prefix, blocks = step_blocks(path)
  index = blocks.index { |block| step_name(block) == name }
  abort "fixture step is missing: #{name}" unless index
  block = body.each_line.map { |line| "      #{line}" }
  blocks.insert(index + 1, block)
  write_step_blocks(path, prefix, blocks)
end

def swap_named_steps(path, first, second)
  prefix, blocks = step_blocks(path)
  first_index = blocks.index { |block| step_name(block) == first }
  second_index = blocks.index { |block| step_name(block) == second }
  abort "fixture steps are missing: #{first}, #{second}" unless first_index && second_index
  blocks[first_index], blocks[second_index] = blocks[second_index], blocks[first_index]
  write_step_blocks(path, prefix, blocks)
end

def duplicate_named_step(path, name)
  prefix, blocks = step_blocks(path)
  index = blocks.index { |block| step_name(block) == name }
  abort "fixture step is missing: #{name}" unless index
  blocks.insert(index + 1, blocks[index].dup)
  write_step_blocks(path, prefix, blocks)
end

def named_job_step_bounds(path, job_name, step_name)
  lines = File.readlines(path)
  job_header = "  #{job_name}:\n"
  job_indexes = lines.each_index.select { |index| lines[index] == job_header }
  abort "fixture job count changed: #{job_name}" unless job_indexes.length == 1
  job_start = job_indexes.first
  job_end = lines.length
  ((job_start + 1)...lines.length).each do |index|
    logical = lines[index].sub(/\r?\n\z/, "")
    next if logical.empty? || logical.lstrip.start_with?("#")
    indentation = logical.length - logical.lstrip.length
    if indentation <= 2
      job_end = index
      break
    end
  end
  step_header = "      - name: #{step_name}\n"
  step_indexes = (job_start...job_end).select do |index|
    lines[index] == step_header
  end
  abort "fixture step count changed: #{job_name}/#{step_name}" unless
    step_indexes.length == 1
  step_start = step_indexes.first
  step_end = job_end
  ((step_start + 1)...job_end).each do |index|
    if lines[index].start_with?("      - name: ")
      step_end = index
      break
    end
  end
  [lines, step_start, step_end]
end

def remove_named_job_step(path, job_name, step_name)
  lines, step_start, step_end = named_job_step_bounds(path, job_name, step_name)
  File.binwrite(path, (lines[0...step_start] + lines[step_end..]).join)
end

def insert_after_named_job_step(path, job_name, step_name, body)
  lines, _step_start, step_end = named_job_step_bounds(path, job_name, step_name)
  insertion = body.each_line.map { |line| "      #{line}" }
  File.binwrite(path, (lines[0...step_end] + insertion + lines[step_end..]).join)
end

def append_after_rollout(path, prose)
  marker = "<!-- capability-rollout-order:end -->"
  replace_once(path, marker, "#{marker}\n\n#{prose}")
end

rollout_markdown_outside_fixtures = {
  "rollout-plain-token-outside" => "GHCR_TOKEN",
  "rollout-plain-pat-outside" => "PAT",
  "rollout-plain-ghcr-credentials-outside" => "GHCR credentials",
  "rollout-markdown-star-token-single" => "*GHCR\\_TOKEN*",
  "rollout-markdown-star-token-double" => "**GHCR\\_TOKEN**",
  "rollout-markdown-star-token-triple" => "***GHCR\\_TOKEN***",
  "rollout-markdown-star-username-single" => "*GHCR\\_USERNAME*",
  "rollout-markdown-star-pat-double" => "**PAT**",
  "rollout-markdown-star-ghcr-credentials" => "*GHCR credentials*",
  "rollout-markdown-star-legacy-ghcr-credentials" => "**legacy GHCR credentials**",
  "rollout-markdown-star-registry-credentials" => "*registry credentials*",
  "rollout-markdown-star-russian-legacy-credentials" => "*устаревшие учётные данные*",
  "rollout-markdown-star-russian-ghcr-credentials" => "*учётные данные GHCR*",
  "rollout-markdown-star-russian-ghcr-secrets" => "*секреты GHCR*",
  "rollout-markdown-star-token-punctuation" => "(*GHCR\\_TOKEN*)",
  "rollout-markdown-star-token-double-punctuation" => "**GHCR\\_TOKEN**.",
  "rollout-markdown-star-token-link-single" =>
    "[*GHCR\\\\_TOKEN*](https://example.invalid)",
  "rollout-markdown-star-token-link-double" =>
    "[**GHCR\\\\_TOKEN**](https://example.invalid)",
  "rollout-markdown-review-token-underscore-single" => "_GHCR_TOKEN_",
  "rollout-markdown-review-token-underscore-double" => "__GHCR_TOKEN__",
  "rollout-markdown-review-username-underscore-single" => "_GHCR_USERNAME_",
  "rollout-markdown-review-pat-underscore-single" => "_PAT_",
  "rollout-markdown-review-ghcr-credentials-underscore-single" => "_GHCR credentials_",
  "rollout-markdown-token-underscore-triple" => "___GHCR_TOKEN___",
  "rollout-markdown-legacy-ghcr-credentials-underscore-double" =>
    "__legacy GHCR credentials__",
  "rollout-markdown-russian-ghcr-secrets-underscore-single" => "_секреты GHCR_",
  "rollout-markdown-token-underscore-punctuation" => "(_GHCR_TOKEN_)",
  "rollout-markdown-token-underscore-double-punctuation" => "__GHCR_TOKEN__.",
  "rollout-markdown-token-underscore-link-single" =>
    "[_GHCR_TOKEN_](https://example.invalid)",
  "rollout-markdown-token-underscore-link-double" =>
    "[__GHCR_TOKEN__](https://example.invalid)",
  "rollout-markdown-target-after-end-marker" => "_GHCR_TOKEN_",
  "rollout-neutral-reference-plus-emphasized-target" =>
    "See [RETIRE_LEGACY_GHCR_CREDENTIALS]. _GHCR_TOKEN_",
  "rollout-action-id-outside" => "[DELETE_GHCR_TOKEN]",
  "rollout-bare-action-id-outside" => "DELETE_GHCR_TOKEN",
  "rollout-action-id-plus-emphasized-target" => "[DELETE_GHCR_TOKEN] _GHCR_TOKEN_",
}.freeze

valid_rollout_boundary_fixtures = {
  "valid-rollout-boundary-pattern" => "PATTERN",
  "valid-rollout-boundary-compatibility" => "COMPATIBILITY",
  "valid-rollout-boundary-tokenizer" => "GHCR_TOKENIZER",
  "valid-rollout-boundary-token-alias" => "MY_GHCR_TOKEN_ALIAS",
  "valid-rollout-boundary-credentials-version" => "REGISTRY_CREDENTIALS_VERSION",
  "valid-rollout-boundary-action-alias" => "MY_DELETE_GHCR_TOKEN_ALIAS",
  "valid-rollout-unrelated-emphasis-single" => "*important*",
  "valid-rollout-unrelated-emphasis-double" => "**security**",
}.freeze

case fixture_case
when "alias-self-recursive"
  add_workflow(workflows, fixture_case, workflow_with_top_level(<<~YAML))
    env: &loop
      SELF: *loop
  YAML
when "alias-mutual-cycle"
  add_workflow(workflows, fixture_case, workflow_with_top_level(<<~YAML))
    first: &first
      next: *second
    second: &second
      next: *first
  YAML
when "alias-cycle-length-three"
  add_workflow(workflows, fixture_case, workflow_with_top_level(<<~YAML))
    first: &first
      next: *second
    second: &second
      next: *third
    third: &third
      next: *first
  YAML
when "alias-recursive-sequence"
  add_workflow(workflows, fixture_case, workflow_with_top_level(<<~YAML))
    env: &loop
      VALUES:
        - *loop
  YAML
when "alias-recursive-merge"
  add_workflow(workflows, fixture_case, workflow_with_top_level(<<~YAML))
    defaults: &defaults
      <<: *defaults
  YAML
when "alias-unknown"
  add_workflow(workflows, fixture_case, workflow_with_top_level(<<~YAML))
    env:
      VALUE: *missing
  YAML
when "alias-forward"
  add_workflow(workflows, fixture_case, workflow_with_top_level(<<~YAML))
    env:
      VALUE: *later
    later: &later
      OK: true
  YAML
when "alias-duplicate-anchor"
  add_workflow(workflows, fixture_case, workflow_with_top_level(<<~YAML))
    first: &same
      value: one
    second: &same
      value: two
  YAML
when "alias-depth-over-limit"
  add_workflow(
    workflows,
    fixture_case,
    workflow_with_top_level(alias_chain(WorkflowYamlSafety::MAX_ALIAS_GRAPH_DEPTH + 1))
  )
when "alias-edge-count-over-limit"
  add_workflow(
    workflows,
    fixture_case,
    workflow_with_top_level(alias_references(WorkflowYamlSafety::MAX_ALIAS_EDGES + 1))
  )
when "alias-malformed-identity"
  add_workflow(
    workflows,
    fixture_case,
    workflow_with_top_level("env:\n  VALUE: *\n")
  )
when "alias-recursive-nested-policy-map"
  add_workflow(workflows, fixture_case, <<~YAML)
    name: Nested recursive alias fixture
    on:
      workflow_dispatch:
    permissions:
      contents: read
    jobs:
      inspect:
        runs-on: ubuntu-latest
        steps:
          - env: &loop
              SELF: *loop
            run: echo fixture
  YAML
when "yaml-unsafe-object-tag"
  add_workflow(
    workflows,
    fixture_case,
    workflow_with_top_level("unsafe: !ruby/object:Object {}\n")
  )
when "yaml-symbol-value"
  add_workflow(
    workflows,
    fixture_case,
    workflow_with_top_level("unsafe: :symbol\n")
  )
when "yaml-custom-tag"
  add_workflow(
    workflows,
    fixture_case,
    workflow_with_top_level("unsafe: !fixture tagged\n")
  )
when "yaml-multiple-documents"
  add_workflow(
    workflows,
    fixture_case,
    workflow_with_top_level("safe: true\n") + "---\nsecond: document\n"
  )
when "valid-shared-alias"
  add_workflow(workflows, fixture_case, workflow_with_top_level(<<~YAML))
    shared: &shared
      SAFE_VALUE: safe
    first: *shared
    second: *shared
  YAML
when "valid-nonconflicting-merge"
  add_workflow(workflows, fixture_case, workflow_with_top_level(<<~YAML))
    defaults: &defaults
      SAFE_ONE: one
    env:
      <<: *defaults
      SAFE_TWO: two
  YAML
when "valid-alias-chain-below-limit"
  add_workflow(
    workflows,
    fixture_case,
    workflow_with_top_level(alias_chain(WorkflowYamlSafety::MAX_ALIAS_GRAPH_DEPTH - 1))
  )
when "valid-wide-alias-near-limit"
  add_workflow(
    workflows,
    fixture_case,
    workflow_with_top_level(alias_references(WorkflowYamlSafety::MAX_ALIAS_EDGES - 1))
  )
when "valid-repeated-alias-reference"
  add_workflow(workflows, fixture_case, workflow_with_top_level(<<~YAML))
    shared: &shared
      SAFE_VALUE: safe
    references:
      - *shared
      - *shared
      - *shared
  YAML
when "workflow-without-permissions"
  add_workflow(workflows, fixture_case, <<~YAML)
    name: Missing workflow permissions
    on:
      workflow_dispatch:
    jobs:
      inspect:
        permissions:
          contents: read
        runs-on: ubuntu-latest
        steps:
          - run: echo safe
  YAML
when "ambiguous-inherited-default"
  replace_once(
    workflows.join("build.yml"),
    "permissions:\n  contents: read\n\n",
    ""
  )
when "write-all"
  replace_once(
    workflows.join("build-static.yml"),
    "permissions:\n  contents: read",
    "permissions: write-all"
  )
when "third-packages-write", "third-id-token-write", "pr-packages-write"
  trigger = fixture_case == "pr-packages-write" ? "pull_request" : "workflow_dispatch"
  permission = fixture_case == "third-id-token-write" ? "id-token" : "packages"
  add_workflow(workflows, fixture_case, <<~YAML)
    name: Unauthorized capability
    on:
      #{trigger}:
    permissions:
      contents: read
      #{permission}: write
    jobs:
      inspect:
        runs-on: ubuntu-latest
        steps:
          - run: echo unsafe
  YAML
when "noncanonical-login-pat"
  body = <<~YAML
    steps:
      - uses: docker/login-action@9780b0c442fbb1117ed29e0efdff1e18412f7567
        with:
          registry: ghcr.io
          username: fixture
          password: ${{ secrets.GHCR_TOKEN }}
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "noncanonical-ghcr-token", "noncanonical-cr-pat"
  secret = fixture_case == "noncanonical-cr-pat" ? "CR_PAT" : "GHCR_TOKEN"
  body = <<~YAML
    env:
      TOKEN: ${{ secrets.#{secret} }}
    steps:
      - run: echo safe
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "dynamic-secrets"
  body = <<~YAML
    env:
      TOKEN: "${{ secrets['GHCR_TOKEN'] }}"
    steps:
      - run: echo safe
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "tojson-secrets"
  body = <<~YAML
    env:
      SECRET_DUMP: ${{ toJSON(secrets) }}
    steps:
      - run: echo safe
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "secrets-inherit"
  add_workflow(workflows, fixture_case, <<~YAML)
    name: Reusable secret inheritance
    on:
      workflow_dispatch:
    permissions:
      contents: read
    jobs:
      call:
        permissions:
          contents: read
        uses: example/repository/.github/workflows/reusable.yml@0123456789abcdef0123456789abcdef01234567
        secrets: inherit
  YAML
when "future-prod-environment"
  body = <<~YAML
    environment: prod
    steps:
      - run: echo safe
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "future-ssh-secret"
  body = <<~YAML
    env:
      KEY: ${{ secrets.SSH_PRIVATE_KEY }}
    steps:
      - run: echo safe
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "deploy-extra-ssh", "db-extra-ssh"
  workflow = fixture_case.start_with?("deploy") ? "deploy-ssh.yml" : "db-migrate.yml"
  append_named_step(workflows.join(workflow), <<~YAML)
    - name: Unexpected remote execution
      env:
        SSH_USER: ${{ secrets.SSH_USER }}
        SSH_HOST: ${{ secrets.SSH_HOST }}
        SSH_PORT: ${{ secrets.SSH_PORT }}
      run: ssh -p "$SSH_PORT" "$SSH_USER@$SSH_HOST" "python3 -c 'print(1)'"
  YAML
when "deploy-extra-scp", "db-extra-scp"
  workflow = fixture_case.start_with?("deploy") ? "deploy-ssh.yml" : "db-migrate.yml"
  append_named_step(workflows.join(workflow), <<~YAML)
    - name: Unexpected remote copy
      env:
        SSH_USER: ${{ secrets.SSH_USER }}
        SSH_HOST: ${{ secrets.SSH_HOST }}
        SSH_PORT: ${{ secrets.SSH_PORT }}
      run: scp -P "$SSH_PORT" artifact "$SSH_USER@$SSH_HOST:/tmp/artifact"
  YAML
when "deploy-extra-remote-action", "db-extra-remote-action"
  workflow = fixture_case.start_with?("deploy") ? "deploy-ssh.yml" : "db-migrate.yml"
  append_named_step(workflows.join(workflow), <<~YAML)
    - name: Unexpected remote action
      uses: appleboy/ssh-action@0123456789abcdef0123456789abcdef01234567
      with:
        host: ${{ secrets.SSH_HOST }}
        username: ${{ secrets.SSH_USER }}
        key: ${{ secrets.SSH_PRIVATE_KEY }}
        port: ${{ secrets.SSH_PORT }}
        script: python3 -c 'print(1)'
  YAML
when "deploy-changed-orchestrator", "db-changed-orchestrator"
  workflow = fixture_case.start_with?("deploy") ? "deploy-ssh.yml" : "db-migrate.yml"
  replace_once(
    workflows.join(workflow),
    "        run: scripts/deploy/quiesced-release.sh",
    "        run: scripts/deploy/quiesced-release.sh --unexpected-remote-command"
  )
when "deploy-reordered-setup-orchestrator", "db-reordered-setup-orchestrator"
  workflow = fixture_case.start_with?("deploy") ? "deploy-ssh.yml" : "db-migrate.yml"
  swap_named_steps(
    workflows.join(workflow),
    "Setup SSH agent",
    "Quiesce, migrate and start verified image"
  )
when "deploy-duplicate-orchestrator", "db-duplicate-orchestrator"
  workflow = fixture_case.start_with?("deploy") ? "deploy-ssh.yml" : "db-migrate.yml"
  duplicate_named_step(workflows.join(workflow), "Quiesce, migrate and start verified image")
when "deploy-remote-after-orchestrator", "db-remote-after-orchestrator"
  workflow = fixture_case.start_with?("deploy") ? "deploy-ssh.yml" : "db-migrate.yml"
  insert_after_named_step(
    workflows.join(workflow),
    "Quiesce, migrate and start verified image",
    <<~YAML
      - name: Remote command after orchestrator
        env:
          SSH_USER: ${{ secrets.SSH_USER }}
          SSH_HOST: ${{ secrets.SSH_HOST }}
          SSH_PORT: ${{ secrets.SSH_PORT }}
        run: ssh -p "$SSH_PORT" "$SSH_USER@$SSH_HOST" "echo unexpected"
    YAML
  )
when "vars-ghcr-token", "vars-registry-password"
  variable = fixture_case == "vars-ghcr-token" ? "GHCR_TOKEN" : "REGISTRY_PASSWORD"
  body = <<~YAML
    env:
      SAFE_ALIAS: ${{ vars.#{variable} }}
    steps:
      - run: echo delegated
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "env-ghcr-token-ref"
  body = <<~YAML
    steps:
      - env:
          SAFE_ALIAS: ${{ env.GHCR_TOKEN }}
        run: echo delegated
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "registry-token-key", "docker-password-key"
  env_name = fixture_case == "registry-token-key" ? "REGISTRY_TOKEN" : "DOCKER_PASSWORD"
  body = <<~YAML
    env:
      #{env_name}: inert
    steps:
      - run: echo delegated
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "registry-intermediate-alias"
  body = <<~YAML
    env:
      SOURCE_ALIAS: ${{ vars.GHCR_TOKEN }}
    steps:
      - env:
          DELEGATED_ALIAS: ${{ env.SOURCE_ALIAS }}
        run: echo delegated
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "registry-arbitrary-interpreter"
  body = <<~YAML
    steps:
      - run: |
          python3 -c 'print("delegated registry flow")' "${{ vars.GHCR_TOKEN }}"
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "dynamic-vars-index", "dynamic-env-index"
  source = fixture_case == "dynamic-vars-index" ? "vars['GHCR_TOKEN']" : "env['GHCR_TOKEN']"
  body = <<~YAML
    env:
      SAFE_ALIAS: "${{ #{source} }}"
    steps:
      - run: echo delegated
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "empty-workflow-permissions"
  add_workflow(workflows, fixture_case, <<~YAML)
    name: Empty workflow permissions
    on:
      workflow_dispatch:
    permissions: {}
    jobs:
      inspect:
        permissions:
          contents: read
        runs-on: ubuntu-latest
        steps:
          - run: echo unsafe
  YAML
when "empty-privileged-job-permissions"
  replace_once(
    workflows.join("deploy-ssh.yml"),
    "    permissions:\n      contents: read\n      packages: read\n",
    "    permissions: {}\n"
  )
when "empty-ordinary-job-permissions"
  add_workflow(workflows, fixture_case, <<~YAML)
    name: Empty ordinary job permissions
    on:
      workflow_dispatch:
    permissions:
      contents: read
    jobs:
      inspect:
        permissions: {}
        runs-on: ubuntu-latest
        steps:
          - run: echo unsafe
  YAML
when "checkout-missing-with"
  replace_once(
    workflows.join("deploy-ssh.yml"),
    "        uses: actions/checkout@692973e3d937129bcbf40652eb9f2f61becf3332 # v4.1.7\n        with:\n          persist-credentials: false\n",
    "        uses: actions/checkout@692973e3d937129bcbf40652eb9f2f61becf3332 # v4.1.7\n"
  )
when "checkout-persist-true", "checkout-persist-quoted-true", "checkout-persist-quoted-false"
  value = {
    "checkout-persist-true" => "true",
    "checkout-persist-quoted-true" => '"true"',
    "checkout-persist-quoted-false" => '"false"',
  }.fetch(fixture_case)
  replace_once(
    workflows.join("deploy-ssh.yml"),
    "          persist-credentials: false",
    "          persist-credentials: #{value}"
  )
when "checkout-second"
  duplicate_named_step(workflows.join("deploy-ssh.yml"), "Checkout")
when *rollout_markdown_outside_fixtures.keys
  append_after_rollout(
    root.join("docs/ops/secrets-rotation.md"),
    rollout_markdown_outside_fixtures.fetch(fixture_case)
  )
when *valid_rollout_boundary_fixtures.keys
  append_after_rollout(
    root.join("docs/ops/secrets-rotation.md"),
    valid_rollout_boundary_fixtures.fetch(fixture_case)
  )
when "rollout-markdown-target-in-step-one"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "1. [CAPABILITY_POLICY_COMMIT] Capability policy reviewed and committed.",
    "1. [CAPABILITY_POLICY_COMMIT] Capability policy reviewed and committed; _GHCR_TOKEN_."
  )
when "rollout-markdown-target-in-step-seven"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "7. [STAGE_DEPLOY_GITHUB_TOKEN_GREEN] Complete a successful stage deployment through `github.token`.",
    "7. [STAGE_DEPLOY_GITHUB_TOKEN_GREEN] Complete a successful stage deployment through `github.token`; _GHCR_TOKEN_."
  )
when "rollout-markdown-target-before-start-marker"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "<!-- capability-rollout-order:start -->",
    "_GHCR_TOKEN_\n\n<!-- capability-rollout-order:start -->"
  )
when "valid-rollout-action-isolation"
  path = root.join("docs/ops/secrets-rotation.md")
  step_eight = File.binread(path).lines.find do |line|
    line.include?("[RETIRE_LEGACY_GHCR_CREDENTIALS]")
  end
  abort "rollout action-isolation fixture is missing step 8" unless step_eight
  action_ids = %w[
    DELETE_GHCR_TOKEN
    DELETE_UNUSED_GHCR_USERNAME
    REVOKE_LEGACY_PAT
    CLEAN_REMOTE_DOCKER_CREDENTIALS
  ]
  action_ids.each do |action_id|
    abort "rollout action ID is not exact: #{action_id}" unless
      step_eight.scan("[#{action_id}]").length == 1
  end
  append_after_rollout(path, "Action isolation remains exact and bounded.")
when "premature-secrets-removal"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "## GHCR rollout gate\n",
    "## GHCR rollout gate\n\nDelete `GHCR_TOKEN` and revoke the old PAT before merge.\n"
  )
when "rollout-remove-after-ci-before-stage"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "## GHCR rollout gate\n",
    "## GHCR rollout gate\n\nRemove `GHCR_TOKEN` after hosted PR CI, before stage deploy.\n"
  )
when "rollout-russian-clean-before-stage"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "## GHCR rollout gate\n",
    "## GHCR rollout gate\n\nочистить `GHCR_TOKEN` после CI до stage deploy.\n"
  )
when "rollout-revoke-pat-before-stage"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "## GHCR rollout gate\n",
    "## GHCR rollout gate\n\nRevoke PAT after access confirmation but before stage deployment.\n"
  )
when "rollout-delete-credentials-before-merge"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "## GHCR rollout gate\n",
    "## GHCR rollout gate\n\nDelete credentials before merge.\n"
  )
when "rollout-remove-username-after-push"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "## GHCR rollout gate\n",
    "## GHCR rollout gate\n\nRemove `GHCR_USERNAME` immediately after push.\n"
  )
when "rollout-destroy-legacy-ghcr-before-merge"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "## GHCR rollout gate\n",
    "## GHCR rollout gate\n\nDestroy legacy GHCR credentials before merge.\n"
  )
when "rollout-purge-ghcr-before-stage"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "## GHCR rollout gate\n",
    "## GHCR rollout gate\n\nPurge GHCR credentials before stage deployment.\n"
  )
when "rollout-russian-liquidate-before-stage"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "## GHCR rollout gate\n",
    "## GHCR rollout gate\n\nликвидировать устаревшие учётные данные до stage deploy.\n"
  )
when "rollout-destroy-ghcr-without-timing"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "## GHCR rollout gate\n",
    "## GHCR rollout gate\n\nDestroy GHCR credentials.\n"
  )
when "rollout-disable-token-without-timing"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "## GHCR rollout gate\n",
    "## GHCR rollout gate\n\nDisable GHCR_TOKEN now.\n"
  )
when "rollout-archive-username-after-ci"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "## GHCR rollout gate\n",
    "## GHCR rollout gate\n\nArchive GHCR_USERNAME after CI.\n"
  )
when "rollout-invalidate-pat-before-stage"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "## GHCR rollout gate\n",
    "## GHCR rollout gate\n\nInvalidate PAT before stage.\n"
  )
when "rollout-target-in-step-one"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "1. [CAPABILITY_POLICY_COMMIT] Capability policy reviewed and committed.",
    "1. [CAPABILITY_POLICY_COMMIT] Capability policy reviewed; legacy GHCR credentials."
  )
when "rollout-target-in-step-seven"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "7. [STAGE_DEPLOY_GITHUB_TOKEN_GREEN] Complete a successful stage deployment through `github.token`.",
    "7. [STAGE_DEPLOY_GITHUB_TOKEN_GREEN] Complete a successful stage deployment through `github.token`; GHCR credentials."
  )
when "rollout-target-after-end-marker"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "<!-- capability-rollout-order:end -->",
    "<!-- capability-rollout-order:end -->\n\nDestroy registry credentials."
  )
when "rollout-target-before-start-marker"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "<!-- capability-rollout-order:start -->",
    "Archive legacy credentials.\n\n<!-- capability-rollout-order:start -->"
  )
when "rollout-duplicate-token-action"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "[DELETE_GHCR_TOKEN] delete `GHCR_TOKEN`;",
    "[DELETE_GHCR_TOKEN] delete `GHCR_TOKEN`; [DELETE_GHCR_TOKEN] delete `GHCR_TOKEN`;"
  )
when "rollout-duplicate-token-target"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "delete `GHCR_TOKEN`;",
    "delete `GHCR_TOKEN` and `GHCR_TOKEN`;"
  )
when "rollout-renamed-token-target"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "delete `GHCR_TOKEN`;",
    "delete `GHCR_ACCESS_TOKEN`;"
  )
when "rollout-missing-token-action"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "[DELETE_GHCR_TOKEN] delete `GHCR_TOKEN`; ",
    ""
  )
when "rollout-missing-pat-action"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "[REVOKE_LEGACY_PAT] revoke legacy `PAT`; ",
    ""
  )
when "rollout-renamed-retirement-action"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "[CLEAN_REMOTE_DOCKER_CREDENTIALS]",
    "[PURGE_REMOTE_DOCKER_CREDENTIALS]"
  )
when "rollout-registry-credentials-outside"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "## GHCR rollout gate\n",
    "## GHCR rollout gate\n\nArchive registry credentials.\n"
  )
when "rollout-russian-ghcr-secrets-outside"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "## GHCR rollout gate\n",
    "## GHCR rollout gate\n\nсекреты GHCR.\n"
  )
when "rollout-retirement-without-timing"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "## GHCR rollout gate\n",
    "## GHCR rollout gate\n\nArchive legacy credentials.\n"
  )
when "rollout-duplicate-block"
  path = root.join("docs/ops/secrets-rotation.md")
  text = File.binread(path)
  marker_block = text.match(
    /<!-- capability-rollout-order:start -->.*?<!-- capability-rollout-order:end -->/m
  )
  abort "canonical rollout block is missing" unless marker_block
  replace_once(
    path,
    "<!-- capability-rollout-order:end -->",
    "<!-- capability-rollout-order:end -->\n\n#{marker_block[0]}"
  )
when "rollout-missing-step-seven", "rollout-missing-step-eight"
  path = root.join("docs/ops/secrets-rotation.md")
  id = fixture_case.end_with?("seven") ?
    "STAGE_DEPLOY_GITHUB_TOKEN_GREEN" : "RETIRE_LEGACY_GHCR_CREDENTIALS"
  text = File.binread(path)
  matching = text.lines.select { |line| line.include?("[#{id}]") }
  abort "rollout fixture step is ambiguous: #{id}" unless matching.length == 1
  File.binwrite(path, text.sub(matching.first, ""))
when "rollout-reordered-merge-stage"
  path = root.join("docs/ops/secrets-rotation.md")
  text = File.binread(path)
  merge_line = text.lines.find { |line| line.include?("[MERGE_PR]") }
  stage_line = text.lines.find { |line| line.include?("[STAGE_DEPLOY_GITHUB_TOKEN_GREEN]") }
  abort "rollout reorder fixture source changed" unless merge_line && stage_line
  swap_once(path, merge_line, stage_line)
when "rollout-extra-retirement-step"
  path = root.join("docs/ops/secrets-rotation.md")
  text = File.binread(path)
  step_eight = text.lines.find { |line| line.include?("[RETIRE_LEGACY_GHCR_CREDENTIALS]") }
  abort "rollout step 8 is missing" unless step_eight
  replace_once(
    path,
    step_eight,
    "8. [EARLY_RETIREMENT] Remove credentials before merge.\n#{step_eight}"
  )
when "rollout-unknown-step-id"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "[HOSTED_PR_CI_GREEN]",
    "[HOSTED_PIPELINE_GREEN]"
  )
when "rollout-multiple-step-eight"
  path = root.join("docs/ops/secrets-rotation.md")
  text = File.binread(path)
  step_eight = text.lines.find { |line| line.include?("[RETIRE_LEGACY_GHCR_CREDENTIALS]") }
  abort "rollout step 8 is missing" unless step_eight
  replace_once(path, step_eight, step_eight + step_eight)
when "rollout-retirement-identifier-outside"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "<!-- capability-rollout-order:end -->",
    "<!-- capability-rollout-order:end -->\n\nLegacy `GHCR_TOKEN` operator note."
  )
when "rollout-end-before-start"
  swap_once(
    root.join("docs/ops/secrets-rotation.md"),
    "<!-- capability-rollout-order:start -->",
    "<!-- capability-rollout-order:end -->"
  )
when "valid-rollout-unrelated-prose"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "<!-- capability-rollout-order:end -->",
    "<!-- capability-rollout-order:end -->\n\nUnrelated operator prose remains allowed outside the bounded contract."
  )
when "valid-rollout-reference-id"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "<!-- capability-rollout-order:end -->",
    "<!-- capability-rollout-order:end -->\n\nSee [RETIRE_LEGACY_GHCR_CREDENTIALS]."
  )
when "valid-ghcr-package-access-prose"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "<!-- capability-rollout-order:end -->",
    "<!-- capability-rollout-order:end -->\n\nGHCR package access uses the job-scoped `github.token`."
  )
when "valid-general-security-prose"
  replace_once(
    root.join("docs/ops/secrets-rotation.md"),
    "<!-- capability-rollout-order:end -->",
    "<!-- capability-rollout-order:end -->\n\nSecurity-sensitive access is reviewed and audited."
  )
when "duplicate-workflow-permissions"
  replace_once(
    workflows.join("deploy-ssh.yml"),
    "permissions:\n  contents: read\n",
    "permissions:\n  packages: write\npermissions:\n  contents: read\n"
  )
when "duplicate-job-permissions"
  replace_once(
    workflows.join("deploy-ssh.yml"),
    "    permissions:\n      contents: read\n      packages: read\n",
    "    permissions:\n      packages: write\n    permissions:\n      contents: read\n      packages: read\n"
  )
when "duplicate-env"
  add_workflow(workflows, fixture_case, <<~YAML)
    name: Duplicate env
    on: [workflow_dispatch]
    permissions:
      contents: read
    jobs:
      inspect:
        runs-on: ubuntu-latest
        env:
          SAFE_VALUE: first
          SAFE_VALUE: second
        steps:
          - run: echo unsafe
  YAML
when "duplicate-jobs-key"
  add_workflow(workflows, fixture_case, <<~YAML)
    name: Duplicate jobs key
    on: [workflow_dispatch]
    permissions:
      contents: read
    jobs: {}
    jobs:
      inspect:
        runs-on: ubuntu-latest
        steps:
          - run: echo unsafe
  YAML
when "duplicate-step-run"
  add_workflow(workflows, fixture_case, <<~YAML)
    name: Duplicate step run
    on: [workflow_dispatch]
    permissions:
      contents: read
    jobs:
      inspect:
        runs-on: ubuntu-latest
        steps:
          - run: echo first
            run: echo second
  YAML
when "duplicate-nested-with"
  add_workflow(workflows, fixture_case, <<~YAML)
    name: Duplicate nested with
    on: [workflow_dispatch]
    permissions:
      contents: read
    jobs:
      inspect:
        runs-on: ubuntu-latest
        steps:
          - uses: actions/checkout@692973e3d937129bcbf40652eb9f2f61becf3332
            with:
              persist-credentials: false
              persist-credentials: true
  YAML
when "duplicate-anchor-merge"
  add_workflow(workflows, fixture_case, <<~YAML)
    name: Duplicate anchor merge
    on: [workflow_dispatch]
    permissions:
      contents: read
    jobs:
      inspect:
        runs-on: ubuntu-latest
        env: &safe_env
          SAFE_VALUE: first
        steps:
          - env:
              <<: *safe_env
              SAFE_VALUE: second
            run: echo unsafe
  YAML
when "deploy-ghcr-token"
  replace_once(
    workflows.join("deploy-ssh.yml"),
    "          REGISTRY_READ_TOKEN: ${{ github.token }}",
    "          GHCR_TOKEN: ${{ secrets.GHCR_TOKEN }}"
  )
when "deploy-token-without-packages-read"
  replace_once(
    workflows.join("deploy-ssh.yml"),
    "      contents: read\n      packages: read",
    "      contents: read"
  )
when "deploy-packages-write"
  replace_once(
    workflows.join("deploy-ssh.yml"),
    "      packages: read",
    "      packages: write"
  )
when "canonical-loses-job-isolation"
  replace_once(
    workflows.join("docker-publish.yml"),
    "    permissions:\n      contents: read\n      packages: write\n      id-token: write\n",
    ""
  )
when "canonical-workflow-write"
  replace_once(
    workflows.join("docker-publish.yml"),
    "permissions:\n  contents: read",
    "permissions:\n  contents: read\n  packages: write"
  )
when "canonical-provenance-identity-regexp"
  replace_once(
    workflows.join("docker-publish.yml"),
    "            --certificate-identity \"https://github.com/\$GITHUB_WORKFLOW_REF\" \\",
    "            --certificate-identity-regexp \"https://github.com/.+\" \\"
  )
when "canonical-verifier-invocation-removed"
  replace_once(
    workflows.join("docker-publish.yml"),
    "          python3 scripts/verify-oci-provenance.py \\\n",
    "          # Verifier invocation removed by structural negative fixture.\n"
  )
when "canonical-verifier-wrong-script-path"
  replace_once(
    workflows.join("docker-publish.yml"),
    "python3 scripts/verify-oci-provenance.py",
    "python3 scripts/verify-provenance-untrusted.py"
  )
when "canonical-verifier-inline-python"
  replace_once(
    workflows.join("docker-publish.yml"),
    "            --event-name \"$EXPECTED_EVENT_NAME\"\n",
    "            --event-name \"$EXPECTED_EVENT_NAME\"\n" \
    "          python3 - <<'PY'\n" \
    "          print(\"duplicate inline verifier\")\n" \
    "          PY\n"
  )
when "canonical-verifier-extra-argument"
  replace_once(
    workflows.join("docker-publish.yml"),
    "            --event-name \"$EXPECTED_EVENT_NAME\"\n",
    "            --event-name \"$EXPECTED_EVENT_NAME\" \\\n" \
    "            --allow-unsigned\n"
  )
when "canonical-verifier-mutable-image-ref"
  replace_once(
    workflows.join("docker-publish.yml"),
    "          REGISTRY_ACTOR: ${{ github.actor }}\n" \
    "          IMAGE_REF: ${{ needs.build-and-push.outputs.image-ref }}\n" \
    "          EXPECTED_REPOSITORY: ${{ github.repository }}",
    "          REGISTRY_ACTOR: ${{ github.actor }}\n" \
    "          IMAGE_REF: ${{ env.IMAGE_NAME }}:latest\n" \
    "          EXPECTED_REPOSITORY: ${{ github.repository }}"
  )
when "canonical-provenance-artifact-always"
  replace_once(
    workflows.join("docker-publish.yml"),
    "      - name: Upload provenance attestation\n        uses:",
    "      - name: Upload provenance attestation\n        if: always()\n        uses:"
  )
when "canonical-provenance-extra-step"
  insert_after_named_job_step(
    workflows.join("docker-publish.yml"),
    "verify-and-provenance",
    "Upload provenance attestation",
    <<~YAML
      - name: Unrelated publisher fixture
        run: echo unrelated
    YAML
  )
when "canonical-signature-step-removed"
  remove_named_job_step(
    workflows.join("docker-publish.yml"),
    "verify-and-provenance",
    "Verify image signature (keyless)"
  )
when "canonical-verifier-step-removed"
  remove_named_job_step(
    workflows.join("docker-publish.yml"),
    "verify-and-provenance",
    "Verify OCI graph and SLSA provenance"
  )
when "canonical-provenance-upload-step-removed"
  remove_named_job_step(
    workflows.join("docker-publish.yml"),
    "verify-and-provenance",
    "Upload provenance attestation"
  )
when "canonical-trivy-step-removed"
  remove_named_job_step(
    workflows.join("docker-publish.yml"),
    "trivy-image",
    "Trivy image scan"
  )
when "canonical-publisher-extra-step"
  insert_after_named_job_step(
    workflows.join("docker-publish.yml"),
    "build-and-push",
    "Build & Push",
    <<~YAML
      - name: Unrelated publisher fixture
        run: echo unrelated
    YAML
  )
when "canonical-trivy-limit-missing"
  replace_once(
    workflows.join("docker-publish.yml"),
    "          limit-severities-for-sarif: true\n",
    "          # limit-severities-for-sarif removed by negative fixture\n"
  )
when "canonical-trivy-limit-false", "canonical-trivy-limit-quoted-false",
     "canonical-trivy-limit-quoted-true"
  value = {
    "canonical-trivy-limit-false" => "false",
    "canonical-trivy-limit-quoted-false" => '"false"',
    "canonical-trivy-limit-quoted-true" => '"true"',
  }.fetch(fixture_case)
  replace_once(
    workflows.join("docker-publish.yml"),
    "          limit-severities-for-sarif: true",
    "          limit-severities-for-sarif: #{value}"
  )
when "canonical-trivy-severity-critical"
  replace_once(
    workflows.join("docker-publish.yml"),
    "          severity: HIGH,CRITICAL",
    "          severity: CRITICAL"
  )
when "canonical-trivy-severity-medium-high-critical"
  replace_once(
    workflows.join("docker-publish.yml"),
    "          severity: HIGH,CRITICAL",
    "          severity: MEDIUM,HIGH,CRITICAL"
  )
when "canonical-trivy-exit-zero"
  replace_once(
    workflows.join("docker-publish.yml"),
    "          exit-code: 1",
    "          exit-code: 0"
  )
when "canonical-trivy-mutable-image-ref"
  replace_once(
    workflows.join("docker-publish.yml"),
    '          image-ref: ${{ needs.build-and-push.outputs.image-ref }}',
    '          image-ref: ${{ env.IMAGE_NAME }}:latest'
  )
when "dependency-submission-strict-removed"
  replace_once(
    workflows.join("dependency-submission.yml"),
    "          additional-arguments: >-\n            --dependency-verification=strict\n            --no-configuration-cache\n            --stacktrace",
    "          additional-arguments: >-\n            --no-configuration-cache\n            --stacktrace"
  )
when "dependency-submission-extra-step"
  insert_after_named_step(
    workflows.join("dependency-submission.yml"),
    "Submit resolved dependency graph (blocking)",
    <<~YAML
      - name: Unrelated dependency publisher fixture
        run: echo unrelated
    YAML
  )
when "interpreter-packages-write"
  add_workflow(workflows, fixture_case, <<~YAML)
    name: Delegated publisher capability
    on:
      workflow_dispatch:
    permissions:
      contents: read
      packages: write
    jobs:
      inspect:
        runs-on: ubuntu-latest
        steps:
          - run: python3 -c 'print("docker push ghcr.io/example/image:tag")'
  YAML
when "interpreter-registry-secret"
  body = <<~YAML
    env:
      REGISTRY_TOKEN: ${{ secrets.GHCR_TOKEN }}
    steps:
      - run: python3 -c 'print("docker push ghcr.io/example/image:tag")'
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "default-docker-config"
  replace_once(
    root.join("scripts/deploy/remote-compose-release.sh"),
    'docker --config "$registry_config_dir" login',
    "docker login"
  )
when "temporary-config-cleanup-removed"
  replace_once(
    root.join("scripts/deploy/remote-compose-release.sh"),
    "  trap cleanup_registry_config EXIT\n",
    ""
  )
when "literal-docker-push", "literal-docker-image-push", "literal-buildx-push"
  command = {
    "literal-docker-push" => "docker push ghcr.io/example/image:tag",
    "literal-docker-image-push" => "docker image push ghcr.io/example/image:tag",
    "literal-buildx-push" => "docker buildx build --push .",
  }.fetch(fixture_case)
  body = <<~YAML
    steps:
      - run: #{command}
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "literal-build-push-action"
  body = <<~YAML
    steps:
      - uses: docker/build-push-action@4f58ea79222b3b9dc2c8bbdd6debcef730109a75
        with:
          push: true
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "duplicate-release-metadata"
  body = <<~YAML
    steps:
      - uses: docker/metadata-action@8e5442c4ef9f78752691e2d8f8d19755c6f78e81
        with:
          images: ghcr.io/example/image
          tags: |
            type=sha,format=short
            type=semver,pattern={{version}},prefix=v
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "self-hosted-runner"
  add_workflow(workflows, fixture_case, <<~YAML)
    name: Persistent runner credential risk
    on:
      workflow_dispatch:
    permissions:
      contents: read
    jobs:
      inspect:
        runs-on: self-hosted
        steps:
          - run: python3 -c 'print("delegated execution")'
  YAML
when "valid-read-only-pr"
  add_workflow(workflows, fixture_case, <<~YAML)
    name: Read-only PR fixture
    on:
      pull_request:
    permissions:
      contents: read
    jobs:
      inspect:
        runs-on: ubuntu-latest
        steps:
          - run: echo safe
  YAML
when "valid-arbitrary-interpreter"
  body = <<~YAML
    steps:
      - run: python3 -c 'print("docker push is inert text")'
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "valid-read-only-docker"
  body = <<~YAML
    steps:
      - run: |
          docker pull ghcr.io/example/public-image:fixture
          docker image inspect ghcr.io/example/public-image:fixture
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "valid-inherited-permissions"
  body = <<~YAML
    steps:
      - run: echo inherited
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "valid-job-permission-override"
  add_workflow(workflows, fixture_case, <<~YAML)
    name: Valid job permission override
    on:
      workflow_dispatch:
    permissions:
      contents: read
    jobs:
      inspect:
        permissions:
          contents: read
        runs-on: ubuntu-latest
        steps:
          - run: echo overridden
  YAML
when "valid-anchor-alias"
  add_workflow(workflows, fixture_case, <<~YAML)
    name: Valid anchor alias
    on:
      workflow_dispatch:
    permissions:
      contents: read
    jobs:
      inspect:
        runs-on: ubuntu-latest
        steps:
          - &safe_step
            name: Safe anchored step
            run: echo safe
          - *safe_step
  YAML
when "valid-unrelated-env"
  body = <<~YAML
    env:
      DISPLAY_NAME: clubs-bot
    steps:
      - run: echo "$DISPLAY_NAME"
  YAML
  add_workflow(workflows, fixture_case, read_only_workflow(body))
when "valid-code-scanning", "valid-dependency-submission", "valid-deploy",
     "valid-canonical-publisher", "valid-built-in-registry-token", "valid-corrected-docs",
     "valid-current-alias-inventory", "valid-current-rollout-document"
  # The isolated tracked production tree itself exercises these exact allowlisted jobs.
else
  abort "unknown capability fixture: #{fixture_case}"
end
RUBY
  git -C "$fixture_root" add -A
}

assert_capability_fixture_invalid() {
  local fixture_name="$1"
  local fixture_root
  fixture_root="$(copy_workflow_capability_fixture "$fixture_name")"
  mutate_workflow_capability_fixture "$fixture_root" "$fixture_name"
  ruby "$workflow_yaml_validator" "$fixture_root" >/dev/null ||
    fail "capability fixture is not valid tracked YAML: $fixture_name"
  bash -n "$fixture_root/scripts/deploy/quiesced-release.sh" ||
    fail "capability fixture runner syntax is invalid: $fixture_name"
  bash -n "$fixture_root/scripts/deploy/remote-compose-release.sh" ||
    fail "capability fixture remote syntax is invalid: $fixture_name"
  if ruby "$workflow_capability_validator" "$fixture_root" >"$TMP_DIR/$fixture_name.out" 2>&1; then
    fail "capability validator accepted invalid fixture: $fixture_name"
  fi
  echo "quality-gate: negative capability fixture $fixture_name rejected"
}

assert_capability_fixture_valid() {
  local fixture_name="$1"
  local fixture_root
  fixture_root="$(copy_workflow_capability_fixture "$fixture_name")"
  mutate_workflow_capability_fixture "$fixture_root" "$fixture_name"
  ruby "$workflow_yaml_validator" "$fixture_root" >/dev/null ||
    fail "positive capability fixture is not valid tracked YAML: $fixture_name"
  bash -n "$fixture_root/scripts/deploy/quiesced-release.sh" ||
    fail "positive capability fixture runner syntax is invalid: $fixture_name"
  bash -n "$fixture_root/scripts/deploy/remote-compose-release.sh" ||
    fail "positive capability fixture remote syntax is invalid: $fixture_name"
  ruby "$workflow_capability_validator" "$fixture_root" >/dev/null ||
    fail "capability validator rejected positive fixture: $fixture_name"
  echo "quality-gate: positive capability fixture $fixture_name accepted"
}

assert_duplicate_capability_fixture_invalid() {
  local fixture_name="$1"
  local fixture_root
  fixture_root="$(copy_workflow_capability_fixture "$fixture_name")"
  mutate_workflow_capability_fixture "$fixture_root" "$fixture_name"
  if ruby "$workflow_yaml_validator" "$fixture_root" >"$TMP_DIR/$fixture_name.yaml.out" 2>&1; then
    fail "workflow YAML validator accepted duplicate-key fixture: $fixture_name"
  fi
  if ruby "$workflow_capability_validator" "$fixture_root" >"$TMP_DIR/$fixture_name.out" 2>&1; then
    fail "capability validator accepted duplicate-key fixture: $fixture_name"
  fi
  echo "quality-gate: duplicate-key fixture $fixture_name rejected before safe_load"
}

assert_no_ruby_backtrace() {
  local fixture_name="$1"
  shift
  if grep -E \
    'SystemStackError|stack level too deep|\.\.\. [0-9]+ levels|^[[:space:]]*from .*:[0-9]+:in|\.rb:[0-9]+:in `' \
    "$@" >/dev/null; then
    fail "YAML safety fixture emitted a Ruby backtrace: $fixture_name"
  fi
}

assert_yaml_safety_fixture_invalid() {
  local fixture_name="$1"
  local fixture_root yaml_log capability_log yaml_status capability_status fixture_path
  fixture_root="$(copy_workflow_capability_fixture "$fixture_name")"
  mutate_workflow_capability_fixture "$fixture_root" "$fixture_name"
  fixture_path="$fixture_root/.github/workflows/$fixture_name.yml"
  [ -f "$fixture_path" ] && [ ! -L "$fixture_path" ] ||
    fail "YAML safety fixture source is not a regular file: $fixture_name"
  git -C "$fixture_root" ls-files --error-unmatch \
    ".github/workflows/$fixture_name.yml" >/dev/null ||
    fail "YAML safety fixture source is not tracked: $fixture_name"
  yaml_log="$TMP_DIR/$fixture_name.yaml.out"
  capability_log="$TMP_DIR/$fixture_name.capability.out"

  if ruby "$workflow_yaml_validator" "$fixture_root" >"$yaml_log" 2>&1; then
    fail "workflow YAML reader accepted unsafe alias fixture: $fixture_name"
  else
    yaml_status=$?
  fi
  if ruby "$workflow_capability_validator" "$fixture_root" >"$capability_log" 2>&1; then
    fail "capability reader accepted unsafe alias fixture: $fixture_name"
  else
    capability_status=$?
  fi
  assert_eq "$yaml_status" "1"
  assert_eq "$capability_status" "1"
  assert_no_ruby_backtrace "$fixture_name" "$yaml_log" "$capability_log"
  echo "quality-gate: both YAML readers rejected bounded fixture $fixture_name without backtrace"
}

assert_yaml_safety_fixture_valid() {
  local fixture_name="$1"
  local fixture_root yaml_log capability_log
  fixture_root="$(copy_workflow_capability_fixture "$fixture_name")"
  mutate_workflow_capability_fixture "$fixture_root" "$fixture_name"
  yaml_log="$TMP_DIR/$fixture_name.yaml.out"
  capability_log="$TMP_DIR/$fixture_name.capability.out"

  ruby "$workflow_yaml_validator" "$fixture_root" >"$yaml_log" 2>&1 ||
    fail "workflow YAML reader rejected safe alias fixture: $fixture_name"
  ruby "$workflow_capability_validator" "$fixture_root" >"$capability_log" 2>&1 ||
    fail "capability reader rejected safe alias fixture: $fixture_name"
  assert_no_ruby_backtrace "$fixture_name" "$yaml_log" "$capability_log"
  if [ "$fixture_name" = "valid-current-alias-inventory" ]; then
    assert_eq \
      "$(git -C "$fixture_root" ls-files -- '.github/workflows/*.yml' '.github/workflows/*.yaml' | wc -l | tr -d ' ')" \
      "20"
    grep -Fqx '      - &checkout' "$fixture_root/.github/workflows/tests.yml" ||
      fail "current tests.yml anchor fixture is missing"
    grep -Fqx '      - *checkout' "$fixture_root/.github/workflows/tests.yml" ||
      fail "current tests.yml alias fixture is missing"
  fi
  echo "quality-gate: both YAML readers accepted bounded fixture $fixture_name"
}

for yaml_safety_fixture in \
  alias-self-recursive \
  alias-mutual-cycle \
  alias-cycle-length-three \
  alias-recursive-sequence \
  alias-recursive-merge \
  alias-unknown \
  alias-forward \
  alias-duplicate-anchor \
  alias-depth-over-limit \
  alias-edge-count-over-limit \
  alias-malformed-identity \
  alias-recursive-nested-policy-map \
  yaml-unsafe-object-tag \
  yaml-symbol-value \
  yaml-custom-tag \
  yaml-multiple-documents; do
  assert_yaml_safety_fixture_invalid "$yaml_safety_fixture"
done

for yaml_safety_fixture in \
  valid-current-alias-inventory \
  valid-shared-alias \
  valid-nonconflicting-merge \
  valid-alias-chain-below-limit \
  valid-wide-alias-near-limit \
  valid-repeated-alias-reference; do
  assert_yaml_safety_fixture_valid "$yaml_safety_fixture"
done

for capability_fixture in \
  workflow-without-permissions \
  ambiguous-inherited-default \
  write-all \
  third-packages-write \
  third-id-token-write \
  pr-packages-write \
  noncanonical-login-pat \
  noncanonical-ghcr-token \
  noncanonical-cr-pat \
  dynamic-secrets \
  tojson-secrets \
  secrets-inherit \
  future-prod-environment \
  future-ssh-secret \
  deploy-extra-ssh \
  db-extra-ssh \
  deploy-extra-scp \
  db-extra-scp \
  deploy-extra-remote-action \
  db-extra-remote-action \
  deploy-changed-orchestrator \
  db-changed-orchestrator \
  deploy-reordered-setup-orchestrator \
  db-reordered-setup-orchestrator \
  deploy-duplicate-orchestrator \
  db-duplicate-orchestrator \
  deploy-remote-after-orchestrator \
  db-remote-after-orchestrator \
  vars-ghcr-token \
  vars-registry-password \
  env-ghcr-token-ref \
  registry-token-key \
  docker-password-key \
  registry-intermediate-alias \
  registry-arbitrary-interpreter \
  dynamic-vars-index \
  dynamic-env-index \
  empty-workflow-permissions \
  empty-privileged-job-permissions \
  empty-ordinary-job-permissions \
  checkout-missing-with \
  checkout-persist-true \
  checkout-persist-quoted-true \
  checkout-persist-quoted-false \
  checkout-second \
  rollout-plain-token-outside \
  rollout-plain-pat-outside \
  rollout-plain-ghcr-credentials-outside \
  rollout-markdown-star-token-single \
  rollout-markdown-star-token-double \
  rollout-markdown-star-token-triple \
  rollout-markdown-star-username-single \
  rollout-markdown-star-pat-double \
  rollout-markdown-star-ghcr-credentials \
  rollout-markdown-star-legacy-ghcr-credentials \
  rollout-markdown-star-registry-credentials \
  rollout-markdown-star-russian-legacy-credentials \
  rollout-markdown-star-russian-ghcr-credentials \
  rollout-markdown-star-russian-ghcr-secrets \
  rollout-markdown-star-token-punctuation \
  rollout-markdown-star-token-double-punctuation \
  rollout-markdown-star-token-link-single \
  rollout-markdown-star-token-link-double \
  rollout-markdown-review-token-underscore-single \
  rollout-markdown-review-token-underscore-double \
  rollout-markdown-review-username-underscore-single \
  rollout-markdown-review-pat-underscore-single \
  rollout-markdown-review-ghcr-credentials-underscore-single \
  rollout-markdown-token-underscore-triple \
  rollout-markdown-legacy-ghcr-credentials-underscore-double \
  rollout-markdown-russian-ghcr-secrets-underscore-single \
  rollout-markdown-token-underscore-punctuation \
  rollout-markdown-token-underscore-double-punctuation \
  rollout-markdown-token-underscore-link-single \
  rollout-markdown-token-underscore-link-double \
  rollout-markdown-target-in-step-one \
  rollout-markdown-target-in-step-seven \
  rollout-markdown-target-before-start-marker \
  rollout-markdown-target-after-end-marker \
  rollout-neutral-reference-plus-emphasized-target \
  rollout-action-id-outside \
  rollout-bare-action-id-outside \
  rollout-action-id-plus-emphasized-target \
  premature-secrets-removal \
  rollout-remove-after-ci-before-stage \
  rollout-russian-clean-before-stage \
  rollout-revoke-pat-before-stage \
  rollout-delete-credentials-before-merge \
  rollout-remove-username-after-push \
  rollout-destroy-legacy-ghcr-before-merge \
  rollout-purge-ghcr-before-stage \
  rollout-russian-liquidate-before-stage \
  rollout-destroy-ghcr-without-timing \
  rollout-disable-token-without-timing \
  rollout-archive-username-after-ci \
  rollout-invalidate-pat-before-stage \
  rollout-target-in-step-one \
  rollout-target-in-step-seven \
  rollout-target-after-end-marker \
  rollout-target-before-start-marker \
  rollout-duplicate-token-action \
  rollout-duplicate-token-target \
  rollout-renamed-token-target \
  rollout-missing-token-action \
  rollout-missing-pat-action \
  rollout-renamed-retirement-action \
  rollout-registry-credentials-outside \
  rollout-russian-ghcr-secrets-outside \
  rollout-retirement-without-timing \
  rollout-duplicate-block \
  rollout-missing-step-seven \
  rollout-missing-step-eight \
  rollout-reordered-merge-stage \
  rollout-extra-retirement-step \
  rollout-unknown-step-id \
  rollout-multiple-step-eight \
  rollout-retirement-identifier-outside \
  rollout-end-before-start \
  deploy-ghcr-token \
  deploy-token-without-packages-read \
  deploy-packages-write \
  canonical-loses-job-isolation \
  canonical-workflow-write \
  canonical-provenance-identity-regexp \
  canonical-verifier-invocation-removed \
  canonical-verifier-wrong-script-path \
  canonical-verifier-inline-python \
  canonical-verifier-extra-argument \
  canonical-verifier-mutable-image-ref \
  canonical-provenance-artifact-always \
  canonical-provenance-extra-step \
  canonical-signature-step-removed \
  canonical-verifier-step-removed \
  canonical-provenance-upload-step-removed \
  canonical-trivy-step-removed \
  canonical-publisher-extra-step \
  canonical-trivy-limit-missing \
  canonical-trivy-limit-false \
  canonical-trivy-limit-quoted-false \
  canonical-trivy-limit-quoted-true \
  canonical-trivy-severity-critical \
  canonical-trivy-severity-medium-high-critical \
  canonical-trivy-exit-zero \
  canonical-trivy-mutable-image-ref \
  dependency-submission-strict-removed \
  dependency-submission-extra-step \
  interpreter-packages-write \
  interpreter-registry-secret \
  default-docker-config \
  temporary-config-cleanup-removed \
  literal-docker-push \
  literal-docker-image-push \
  literal-buildx-push \
  literal-build-push-action \
  duplicate-release-metadata \
  self-hosted-runner; do
  assert_capability_fixture_invalid "$capability_fixture"
done


for duplicate_fixture in \
  duplicate-workflow-permissions \
  duplicate-job-permissions \
  duplicate-env \
  duplicate-jobs-key \
  duplicate-step-run \
  duplicate-nested-with \
  duplicate-anchor-merge; do
  assert_duplicate_capability_fixture_invalid "$duplicate_fixture"
done

for capability_fixture in \
  valid-read-only-pr \
  valid-code-scanning \
  valid-dependency-submission \
  valid-deploy \
  valid-canonical-publisher \
  valid-arbitrary-interpreter \
  valid-read-only-docker \
  valid-inherited-permissions \
  valid-job-permission-override \
  valid-anchor-alias \
  valid-unrelated-env \
  valid-built-in-registry-token \
  valid-corrected-docs \
  valid-current-rollout-document \
  valid-rollout-action-isolation \
  valid-rollout-boundary-pattern \
  valid-rollout-boundary-compatibility \
  valid-rollout-boundary-tokenizer \
  valid-rollout-boundary-token-alias \
  valid-rollout-boundary-credentials-version \
  valid-rollout-boundary-action-alias \
  valid-rollout-unrelated-emphasis-single \
  valid-rollout-unrelated-emphasis-double \
  valid-rollout-unrelated-prose \
  valid-rollout-reference-id \
  valid-ghcr-package-access-prose \
  valid-general-security-prose; do
  assert_capability_fixture_valid "$capability_fixture"
done

validate_temporary_remote_docker_credentials() {
  local fixture_root="$TMP_DIR/temporary-remote-docker-credentials"
  local fake_bin="$fixture_root/bin"
  local fake_home="$fixture_root/home"
  local compose_path="$fixture_root/compose"
  local docker_log="$fixture_root/docker.log"
  local stdout_file="$fixture_root/stdout.log"
  local stderr_file="$fixture_root/stderr.log"
  local token="fixture-registry-token-do-not-log"
  local revision="0123456789abcdef0123456789abcdef01234567"
  local repository="ghcr.io/example/clubs_bot/app-bot"
  local digest_hash="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  local default_config="$fake_home/.docker/config.json"
  local default_before default_after default_mode_before default_mode_after
  local case_name expected_status actual_status registry_config credential_path

  mkdir -p "$fake_bin" "$fake_home/.docker" "$compose_path"
  printf '%s\n' '{"auths":{"sentinel.invalid":{"auth":"unchanged"}}}' >"$default_config"
  chmod 600 "$default_config"
  printf '%s\n' 'services:' '  app:' '    image: local-only' >"$compose_path/docker-compose.yml"

  cat >"$fake_bin/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

config_dir=""
if [ "${1:-}" = "--config" ]; then
  config_dir="${2:-}"
  shift 2
fi
command_name="${1:-}"
shift || true

record_config() {
  local mode
  mode="$(stat -c '%a' "$config_dir" 2>/dev/null || stat -f '%Lp' "$config_dir")"
  printf 'config=%s\tmode=%s\tcommand=%s\n' \
    "$config_dir" "$mode" "$command_name" >>"$FAKE_DOCKER_LOG"
}

case "$command_name" in
  compose)
    printf '%s\n' "$*" >>"$FAKE_DOCKER_LOG"
    printf '%s\n' app
    ;;
  login)
    [ -n "$config_dir" ]
    case " $* " in
      *" --password-stdin "*) ;;
      *) exit 91 ;;
    esac
    IFS= read -r supplied_token || [ -n "$supplied_token" ]
    [ "$supplied_token" = "$FAKE_EXPECTED_TOKEN" ]
    unset supplied_token
    record_config
    printf '%s\n' '{"auths":{"ghcr.io":{"auth":"temporary-only"}}}' >"$config_dir/config.json"
    printf 'credential=%s\n' "$config_dir/config.json" >>"$FAKE_DOCKER_LOG"
    [ "$FAKE_FAILURE" != "login" ] || exit 23
    ;;
  pull)
    [ -n "$config_dir" ]
    record_config
    reference="${1:-}"
    printf 'reference=%s\n' "$reference" >>"$FAKE_DOCKER_LOG"
    if [ "$FAKE_FAILURE" = "digest-pull" ] && [[ "$reference" == *@sha256:* ]]; then
      exit 24
    fi
    ;;
  image)
    [ "${1:-}" = "inspect" ]
    if [[ "$*" == *"Config.Labels"* ]]; then
      printf '%s\n' "$FAKE_EXPECTED_REVISION"
    elif [[ "$*" == *"RepoDigests"* ]]; then
      printf '%s@sha256:%s\n' "$FAKE_IMAGE_REPOSITORY" "$FAKE_DIGEST_HASH"
    else
      exit 92
    fi
    ;;
  *)
    exit 93
    ;;
esac
SH
  chmod 700 "$fake_bin/docker"

  default_before="$(shasum -a 256 "$default_config")"
  default_mode_before="$(stat -c '%a' "$default_config" 2>/dev/null || stat -f '%Lp' "$default_config")"

  for case_name in success login digest-pull; do
    : >"$docker_log"
    : >"$stdout_file"
    : >"$stderr_file"
    expected_status=0
    [ "$case_name" = "success" ] || expected_status=1
    set +e
    printf '%s\n' "$token" |
      env \
        PATH="$fake_bin:$PATH" \
        HOME="$fake_home" \
        FAKE_DOCKER_LOG="$docker_log" \
        FAKE_EXPECTED_TOKEN="$token" \
        FAKE_EXPECTED_REVISION="$revision" \
        FAKE_IMAGE_REPOSITORY="$repository" \
        FAKE_DIGEST_HASH="$digest_hash" \
        FAKE_FAILURE="$case_name" \
        bash "$ROOT_DIR/scripts/deploy/remote-compose-release.sh" \
          preflight \
          123-1 \
          stage \
          "$compose_path" \
          "$repository" \
          fixture \
          "$revision" \
          fixture-user >"$stdout_file" 2>"$stderr_file"
    actual_status=$?
    set -e

    if { [ "$expected_status" = "0" ] && [ "$actual_status" != "0" ]; } ||
      { [ "$expected_status" != "0" ] && [ "$actual_status" = "0" ]; }; then
      fail "temporary Docker credential case $case_name returned $actual_status"
    fi
    registry_config="$(awk -F '\t' '/^config=/{sub(/^config=/, "", $1); print $1}' "$docker_log" | sort -u)"
    [ -n "$registry_config" ] || fail "temporary Docker config was not observed: $case_name"
    [ "$(printf '%s\n' "$registry_config" | wc -l | tr -d ' ')" = "1" ] ||
      fail "multiple Docker configs were used: $case_name"
    case "$registry_config" in
      /tmp/clubs-bot-docker-config.123-1.*) ;;
      *) fail "unexpected temporary Docker config path: $registry_config" ;;
    esac
    [ ! -e "$registry_config" ] || fail "temporary Docker config survived $case_name"
    if awk -F '\t' '/^config=/ && $2 != "mode=700" { exit 1 }' "$docker_log"; then
      :
    else
      fail "temporary Docker config mode is not 0700: $case_name"
    fi
    credential_path="$(sed -n 's/^credential=//p' "$docker_log")"
    [ "$credential_path" = "$registry_config/config.json" ] ||
      fail "credential was written outside the temporary Docker config: $case_name"
    if grep -Fq "$token" "$docker_log" "$stdout_file" "$stderr_file"; then
      fail "registry token leaked into structural test output: $case_name"
    fi
    if [ "$case_name" = "success" ]; then
      [ "$(cat "$stdout_file")" = "$repository@sha256:$digest_hash" ] ||
        fail "temporary Docker credential success returned the wrong digest"
      [ "$(grep -c $'\tcommand=login$' "$docker_log")" = "1" ] ||
        fail "temporary Docker login count changed"
      [ "$(grep -c $'\tcommand=pull$' "$docker_log")" = "2" ] ||
        fail "temporary Docker tag/digest pull count changed"
    fi
  done

  default_after="$(shasum -a 256 "$default_config")"
  default_mode_after="$(stat -c '%a' "$default_config" 2>/dev/null || stat -f '%Lp' "$default_config")"
  [ "$default_after" = "$default_before" ] || fail "default Docker config content changed"
  [ "$default_mode_after" = "$default_mode_before" ] || fail "default Docker config mode changed"
  echo "quality-gate: temporary remote Docker credentials cleaned on success/failure"
}

validate_temporary_remote_docker_credentials
validate_docker_workflow_contracts "$ROOT_DIR"
echo "quality-gate: workflow capability policy and bounded literal defense fixtures verified"

legacy_trivy_action_sha_prefix="b6643a29fecd7f34b3597bc6acb0a98b03d33"
legacy_trivy_action_sha="${legacy_trivy_action_sha_prefix}ff8"

trivy_old_action_fixture="$TMP_DIR/security-scan-old-trivy-action.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_old_action_fixture" \
  "trivy" \
  "Trivy filesystem scan" \
  "        $approved_trivy_action_active_line" \
  "        uses: aquasecurity/trivy-action@$legacy_trivy_action_sha # 0.33.1"
assert_validation_rejected \
  "trivy-old-action-sha" \
  validate_trivy_filesystem_workflow \
  "$trivy_old_action_fixture"

trivy_latest_fixture="$TMP_DIR/security-scan-trivy-latest.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_latest_fixture" \
  "trivy" \
  "Trivy filesystem scan" \
  "          version: v0.69.3" \
  "          version: latest"
assert_validation_rejected \
  "trivy-version-latest" \
  validate_trivy_filesystem_workflow \
  "$trivy_latest_fixture"

trivy_disallowed_patch_fixture="$TMP_DIR/docker-publish-trivy-v0.69.4.yml"
replace_exact_line_once \
  "$docker_publish_workflow" \
  "$trivy_disallowed_patch_fixture" \
  "          version: v0.69.3" \
  "          version: v0.69.4"
assert_validation_rejected \
  "trivy-version-v0.69.4" \
  validate_trivy_image_workflow \
  "$trivy_disallowed_patch_fixture"

trivy_ignorefile_fixture="$TMP_DIR/security-scan-trivy-ignorefile.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_ignorefile_fixture" \
  "trivy" \
  "Trivy filesystem scan" \
  "          trivyignores: .trivyignore" \
  "          ignorefile: .trivyignore"
assert_validation_rejected \
  "trivy-unsupported-ignorefile" \
  validate_trivy_filesystem_workflow \
  "$trivy_ignorefile_fixture"

trivy_fail_open_fixture="$TMP_DIR/docker-publish-trivy-exit-zero.yml"
replace_exact_line_once \
  "$docker_publish_workflow" \
  "$trivy_fail_open_fixture" \
  "          exit-code: 1" \
  "          exit-code: 0"
assert_validation_rejected \
  "trivy-fail-open-exit-code" \
  validate_trivy_image_workflow \
  "$trivy_fail_open_fixture"

trivy_job_continue_fixture="$TMP_DIR/security-scan-trivy-job-continue.yml"
insert_job_direct_line \
  "$security_scan_workflow" \
  "$trivy_job_continue_fixture" \
  "trivy" \
  "    continue-on-error: true"
assert_validation_rejected \
  "trivy-fail-open-job-continue" \
  validate_trivy_filesystem_workflow \
  "$trivy_job_continue_fixture"

trivy_unguarded_sarif_fixture="$TMP_DIR/security-scan-trivy-unguarded-sarif.yml"
replace_step_line_once \
  "$security_scan_workflow" \
  "$trivy_unguarded_sarif_fixture" \
  "trivy" \
  "Upload Trivy filesystem SARIF to code scanning" \
  "        if: $trivy_filesystem_report_guard" \
  "        if: always()"
assert_validation_rejected \
  "trivy-unguarded-sarif-upload" \
  validate_trivy_filesystem_workflow \
  "$trivy_unguarded_sarif_fixture"

container_smoke_ready_continue_fixture="$TMP_DIR/container-smoke-ready-continue.yml"
insert_step_direct_line \
  "$container_smoke_workflow" \
  "$container_smoke_ready_continue_fixture" \
  "smoke" \
  "Probe /ready (gating)" \
  "        continue-on-error: true"
assert_validation_rejected \
  "container-smoke-ready-continue-on-error" \
  validate_container_smoke_workflow \
  "$container_smoke_ready_continue_fixture"

container_smoke_health_if_false_fixture="$TMP_DIR/container-smoke-health-if-false.yml"
insert_step_direct_line \
  "$container_smoke_workflow" \
  "$container_smoke_health_if_false_fixture" \
  "smoke" \
  "Probe /health" \
  "        if: false"
assert_validation_rejected \
  "container-smoke-health-if-false" \
  validate_container_smoke_workflow \
  "$container_smoke_health_if_false_fixture"

container_smoke_job_continue_fixture="$TMP_DIR/container-smoke-job-continue.yml"
insert_job_direct_line \
  "$container_smoke_workflow" \
  "$container_smoke_job_continue_fixture" \
  "smoke" \
  "    continue-on-error: true"
assert_validation_rejected \
  "container-smoke-job-continue-on-error" \
  validate_container_smoke_workflow \
  "$container_smoke_job_continue_fixture"

container_smoke_job_if_false_fixture="$TMP_DIR/container-smoke-job-if-false.yml"
insert_job_direct_line \
  "$container_smoke_workflow" \
  "$container_smoke_job_if_false_fixture" \
  "smoke" \
  "    if: false"
assert_validation_rejected \
  "container-smoke-job-if-false" \
  validate_container_smoke_workflow \
  "$container_smoke_job_if_false_fixture"

container_smoke_ready_if_always_fixture="$TMP_DIR/container-smoke-ready-if-always.yml"
insert_step_direct_line \
  "$container_smoke_workflow" \
  "$container_smoke_ready_if_always_fixture" \
  "smoke" \
  "Probe /ready (gating)" \
  "        if: always()"
assert_validation_rejected \
  "container-smoke-ready-if-always" \
  validate_container_smoke_workflow \
  "$container_smoke_ready_if_always_fixture"

container_smoke_loopback_fixture="$TMP_DIR/container-smoke-loopback.yml"
replace_exact_line_once \
  "$container_smoke_workflow" \
  "$container_smoke_loopback_fixture" \
  '            -e DATABASE_URL="jdbc:postgresql://postgres:5432/botdb" \' \
  '            -e DATABASE_URL="jdbc:postgresql://127.0.0.1:5432/botdb" \'
assert_validation_rejected \
  "container-smoke-loopback-database" \
  validate_container_smoke_workflow \
  "$container_smoke_loopback_fixture"

container_smoke_missing_network_fixture="$TMP_DIR/container-smoke-missing-network.yml"
replace_exact_line_once \
  "$container_smoke_workflow" \
  "$container_smoke_missing_network_fixture" \
  '            --network "${{ job.services.postgres.network }}" \' \
  "            # service network removed"
assert_validation_rejected \
  "container-smoke-missing-service-network" \
  validate_container_smoke_workflow \
  "$container_smoke_missing_network_fixture"

container_smoke_insecure_rbac_fixture="$TMP_DIR/container-smoke-insecure-rbac.yml"
replace_exact_line_once \
  "$container_smoke_workflow" \
  "$container_smoke_insecure_rbac_fixture" \
  '            -e RBAC_ENABLED="true" \' \
  '            -e ALLOW_INSECURE_DEV="true" \'
assert_validation_rejected \
  "container-smoke-insecure-rbac" \
  validate_container_smoke_workflow \
  "$container_smoke_insecure_rbac_fixture"

unix_application_main_fixture="$TMP_DIR/app-bot-unix-application-main"
printf '%s\n' \
  '#!/usr/bin/env sh' \
  'exec java com.example.bot.ApplicationKt "$@"' >"$unix_application_main_fixture"
assert_validation_rejected \
  "packaged-unix-application-main" \
  validate_packaged_launcher \
  "$unix_application_main_fixture"

windows_application_main_fixture="$TMP_DIR/app-bot-windows-application-main.bat"
printf '%s\n' \
  '@echo off' \
  '"%JAVA_EXE%" com.example.bot.ApplicationKt %*' >"$windows_application_main_fixture"
assert_validation_rejected \
  "packaged-windows-application-main" \
  validate_packaged_launcher \
  "$windows_application_main_fixture"

missing_expected_main_fixture="$TMP_DIR/app-bot-missing-expected-main"
printf '%s\n' \
  '#!/usr/bin/env sh' \
  'exec java com.example.bot.UnexpectedMain "$@"' >"$missing_expected_main_fixture"
assert_validation_rejected \
  "packaged-missing-expected-main" \
  validate_packaged_launcher \
  "$missing_expected_main_fixture"

mixed_main_fixture="$TMP_DIR/app-bot-mixed-main"
printf '%s\n' \
  '#!/usr/bin/env sh' \
  'exec java io.ktor.server.netty.EngineMain com.example.bot.ApplicationKt "$@"' >"$mixed_main_fixture"
assert_validation_rejected \
  "packaged-engine-and-application-main" \
  validate_packaged_launcher \
  "$mixed_main_fixture"

payment_hardening_validator="$ROOT_DIR/scripts/validate-payment-hardening.py"
payment_hardening_runtime="$ROOT_DIR/scripts/verify-payment-hardening-runtime.sh"
python3 "$payment_hardening_validator" --structural "$ROOT_DIR"

validate_lint_payment_runtime_contract() {
  local workflow_file="$1"
  ruby - "$workflow_file" <<'RUBY'
require "psych"

path = ARGV.fetch(0)
begin
  workflow = Psych.safe_load(File.binread(path), aliases: false)
rescue Psych::SyntaxError, SystemCallError => error
  warn "lint-payment-runtime-contract: workflow is unreadable or malformed: #{error.class}"
  exit 1
end

def reject_contract(message)
  warn "lint-payment-runtime-contract: #{message}"
  exit 1
end

reject_contract("workflow root must be a mapping") unless workflow.is_a?(Hash)
reject_contract("workflow name changed") unless workflow["name"] == "Lint"
triggers = workflow["on"] || workflow[true]
reject_contract("workflow triggers must be a mapping") unless triggers.is_a?(Hash)
reject_contract("pull_request trigger is missing") unless triggers.key?("pull_request")
push = triggers["push"]
reject_contract("push trigger must target main") unless push.is_a?(Hash) && push["branches"] == ["main"]
reject_contract("permissions must be exactly contents: read") unless workflow["permissions"] == {"contents" => "read"}

jobs = workflow["jobs"]
lint = jobs.is_a?(Hash) ? jobs["lint"] : nil
reject_contract("lint job is missing") unless lint.is_a?(Hash)
reject_contract("lint job must not have if") if lint.key?("if")
reject_contract("lint job must not continue on error") if lint["continue-on-error"]
steps = lint["steps"]
reject_contract("lint steps are missing") unless steps.is_a?(Array)
reject_contract("a lint step continues on error") if steps.any? { |step| step.is_a?(Hash) && step["continue-on-error"] }

checkout_index = steps.index { |step| step.is_a?(Hash) && step["name"] == "Checkout" }
jdk_index = steps.index { |step| step.is_a?(Hash) && step["name"] == "Set up JDK 21" }
gradle_index = steps.index { |step| step.is_a?(Hash) && step["name"] == "Gradle cache & setup" }
gate_indexes = steps.each_index.select do |index|
  step = steps[index]
  step.is_a?(Hash) && step["name"] == "Payment hardening required runtime"
end
reject_contract("authoritative runtime step must appear exactly once") unless gate_indexes.length == 1
gate_index = gate_indexes.fetch(0)
reject_contract("checkout/JDK/Gradle setup must precede the runtime gate") unless [checkout_index, jdk_index, gradle_index].all? && checkout_index < jdk_index && jdk_index < gradle_index && gradle_index < gate_index

checkout = steps.fetch(checkout_index)
reject_contract("checkout must disable persisted credentials") unless checkout.fetch("with", {})["persist-credentials"] == false
gate = steps.fetch(gate_index)
reject_contract("runtime gate must not have if") if gate.key?("if")
reject_contract("runtime gate must not continue on error") if gate["continue-on-error"]
reject_contract("runtime gate must not use environment-selected mode") if gate.key?("env")
run = gate["run"]
reject_contract("runtime gate must be a direct run block") unless run.is_a?(String)
normalized = run.lines.map(&:strip).reject(&:empty?).join(" ").gsub(/\\\s+/, "")
expected = "python3 scripts/validate-payment-hardening.py --run-required-runtime ."
reject_contract("authoritative runtime command changed") unless normalized == expected
reject_contract("runtime gate is fail-open") if run.include?("|| true")
reject_contract("runtime gate uses structural-only mode") if run.include?("structural")
reject_contract("workflow contains structural-only runtime wiring") if steps.any? { |step| step.is_a?(Hash) && step["run"].is_a?(String) && step["run"].include?("--structural") }

puts "quality-gate: lint payment runtime contract verified"
RUBY
}

validate_lint_payment_runtime_contract "$ROOT_DIR/.github/workflows/lint.yml"

assert_payment_hardening_rejected() {
  local fixture_name="$1"
  local expected_rule="$2"
  local fixture_root="$3"
  local fixture_log="$TMP_DIR/$fixture_name.log"
  local fixture_status
  local fixture_output

  if python3 "$payment_hardening_validator" "$fixture_root" >"$fixture_log" 2>&1; then
    fail "payment hardening negative fixture unexpectedly passed: $fixture_name"
  else
    fixture_status=$?
  fi
  assert_eq "$fixture_status" "1"
  fixture_output="$(cat "$fixture_log")"
  assert_contains "$fixture_output" "[$expected_rule]"
  echo "quality-gate: payment hardening fixture $fixture_name rejected by $expected_rule"
}

assert_payment_hardening_accepted() {
  local fixture_name="$1"
  local fixture_root="$2"
  local fixture_log="$TMP_DIR/$fixture_name.log"

  if ! python3 "$payment_hardening_validator" "$fixture_root" >"$fixture_log" 2>&1; then
    fail "payment hardening safe fixture $fixture_name was rejected: $(cat "$fixture_log")"
  fi
  assert_contains "$(cat "$fixture_log")" "payment-hardening-contract: OK"
  echo "quality-gate: payment hardening safe fixture $fixture_name accepted"
}

sensitive_logging_suite="com.example.bot.logging.SensitiveIdempotencyLoggingTest"

payment_hardening_fixture_base="$TMP_DIR/payment-hardening-base"
payment_hardening_files=(
  "Dockerfile"
  "docker-compose.yml"
  ".github/workflows/lint.yml"
  ".github/workflows/deploy-ssh.yml"
  ".github/workflows/db-migrate.yml"
  "app-bot/build.gradle.kts"
  "app-bot/src/main/dist/bin/app-bot-migrate"
  "app-bot/src/main/kotlin/com/example/bot/tools/QuiescedMigrateMain.kt"
  "app-bot/src/main/resources/quiesced-migration-logback.xml"
  "app-bot/src/test/kotlin/com/example/bot/tools/QuiescedMigrateMainTest.kt"
  "core-data/src/main/resources/db/migration/postgresql/V056__atomic_booking_refunds.sql"
  "core-data/src/main/resources/db/migration/h2/V056__atomic_booking_refunds.sql"
  "core-data/src/main/kotlin/com/example/bot/data/db/DbConfig.kt"
  "core-data/src/test/kotlin/com/example/bot/data/db/FlywayConfigTest.kt"
  "core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt"
  "core-security/src/main/kotlin/com/example/bot/security/webhook/WebhookSecurityPlugin.kt"
  "app-bot/src/main/kotlin/com/example/bot/routes/BookingFinalizeRoutes.kt"
  "app-bot/src/main/kotlin/com/example/bot/promo/BookingTemplateService.kt"
  "app-bot/src/main/kotlin/com/example/bot/payments/finalize/DefaultPaymentsFinalizeService.kt"
  "core-data/src/main/kotlin/com/example/bot/data/db/DbTransactions.kt"
  "app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt"
  "app-bot/src/main/kotlin/com/example/bot/routes/PaymentsFinalizeRoutes.kt"
  "app-bot/src/main/kotlin/com/example/bot/plugins/JsonErrorPages.kt"
  "app-bot/src/main/kotlin/com/example/bot/logging/SqlThrowableLogging.kt"
  "app-bot/src/main/kotlin/com/example/bot/logging/DenySensitiveTurboFilter.kt"
  "app-bot/src/main/resources/logback.xml"
  "app-bot/src/test/kotlin/com/example/bot/logging/SensitiveIdempotencyLoggingTest.kt"
  "app-bot/src/test/kotlin/com/example/bot/logging/SqlThrowableLoggingPersistenceTest.kt"
  "app-bot/src/test/kotlin/com/example/bot/payments/PaymentsPersistenceTest.kt"
  "docs/dr.md"
  "docs/invariants.md"
  "docs/ops/release-rollback.md"
  "docs/runtime-db-resiliency.md"
  "gradle/libs.versions.toml"
  "scripts/selfcheck-quality-gates.sh"
  "scripts/validate-payment-hardening.py"
  "scripts/verify-payment-hardening-runtime.sh"
  "scripts/validate-workflow-yaml.rb"
  "scripts/validate-quiesced-deployment.sh"
  "scripts/deploy/quiesced-release.sh"
  "scripts/deploy/remote-compose-release.sh"
)
for relative_path in "${payment_hardening_files[@]}"; do
  mkdir -p "$payment_hardening_fixture_base/$(dirname "$relative_path")"
  cp "$ROOT_DIR/$relative_path" "$payment_hardening_fixture_base/$relative_path"
done
(
  cd "$payment_hardening_fixture_base"
  git init -q
  git add -- .
)

copy_payment_hardening_fixture() {
  local fixture_name="$1"
  local fixture_root="$TMP_DIR/$fixture_name"
  cp -R "$payment_hardening_fixture_base" "$fixture_root"
  printf '%s' "$fixture_root"
}

remove_payment_statement_once() {
  local file="$1"
  local marker="$2"
  python3 - "$file" "$marker" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
marker = sys.argv[2]
text = path.read_text(encoding="utf-8")
if text.count(marker) != 1:
    raise SystemExit(f"payment fixture marker count changed: {marker}")
start = text.rfind("\n", 0, text.index(marker)) + 1
end = text.find(";", text.index(marker))
if end < 0:
    raise SystemExit(f"payment fixture statement is unterminated: {marker}")
path.write_text(text[:start] + text[end + 1 :], encoding="utf-8")
PY
}

replace_payment_text_once() {
  local file="$1"
  local expected="$2"
  local replacement="$3"
  python3 - "$file" "$expected" "$replacement" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
expected = sys.argv[2]
replacement = sys.argv[3]
text = path.read_text(encoding="utf-8")
if text.count(expected) != 1:
    raise SystemExit("payment fixture exact-text source changed")
path.write_text(text.replace(expected, replacement), encoding="utf-8")
PY
}

assert_lint_payment_runtime_rejected() {
  local fixture_name="$1"
  local fixture_root="$2"
  local fixture_log="$TMP_DIR/$fixture_name.log"
  local fixture_status

  if ! ruby "$WORKFLOW_YAML_VALIDATOR" "$fixture_root" >"$TMP_DIR/$fixture_name.yaml.log" 2>&1; then
    fail "lint workflow negative fixture is not valid YAML: $fixture_name"
  fi
  if validate_lint_payment_runtime_contract "$fixture_root/.github/workflows/lint.yml" >"$fixture_log" 2>&1; then
    fail "lint payment runtime negative fixture unexpectedly passed: $fixture_name"
  else
    fixture_status=$?
  fi
  assert_eq "$fixture_status" "1"
  assert_contains "$(cat "$fixture_log")" "lint-payment-runtime-contract:"
  echo "quality-gate: lint payment runtime fixture $fixture_name rejected"
}

lint_structural_only="$(copy_payment_hardening_fixture lint-payment-structural-only)"
replace_payment_text_once \
  "$lint_structural_only/.github/workflows/lint.yml" \
  '--run-required-runtime' \
  '--structural'
assert_lint_payment_runtime_rejected "lint-payment-structural-only" "$lint_structural_only"

lint_continue_on_error="$(copy_payment_hardening_fixture lint-payment-continue-on-error)"
replace_payment_text_once \
  "$lint_continue_on_error/.github/workflows/lint.yml" \
  '      - name: Payment hardening required runtime
        run: |' \
  '      - name: Payment hardening required runtime
        continue-on-error: true
        run: |'
assert_lint_payment_runtime_rejected "lint-payment-continue-on-error" "$lint_continue_on_error"

lint_step_if_false="$(copy_payment_hardening_fixture lint-payment-step-if-false)"
replace_payment_text_once \
  "$lint_step_if_false/.github/workflows/lint.yml" \
  '      - name: Payment hardening required runtime
        run: |' \
  '      - name: Payment hardening required runtime
        if: false
        run: |'
assert_lint_payment_runtime_rejected "lint-payment-step-if-false" "$lint_step_if_false"

lint_step_if_always="$(copy_payment_hardening_fixture lint-payment-step-if-always)"
replace_payment_text_once \
  "$lint_step_if_always/.github/workflows/lint.yml" \
  '      - name: Payment hardening required runtime
        run: |' \
  '      - name: Payment hardening required runtime
        if: always()
        run: |'
assert_lint_payment_runtime_rejected "lint-payment-step-if-always" "$lint_step_if_always"

lint_job_if="$(copy_payment_hardening_fixture lint-payment-job-if)"
replace_payment_text_once \
  "$lint_job_if/.github/workflows/lint.yml" \
  '  lint:
    runs-on: ubuntu-latest' \
  '  lint:
    if: always()
    runs-on: ubuntu-latest'
assert_lint_payment_runtime_rejected "lint-payment-job-if" "$lint_job_if"

lint_changed_command="$(copy_payment_hardening_fixture lint-payment-changed-command)"
replace_payment_text_once \
  "$lint_changed_command/.github/workflows/lint.yml" \
  'python3 scripts/validate-payment-hardening.py' \
  'python3 -m py_compile scripts/validate-payment-hardening.py'
assert_lint_payment_runtime_rejected "lint-payment-changed-command" "$lint_changed_command"

lint_or_true="$(copy_payment_hardening_fixture lint-payment-or-true)"
replace_payment_text_once \
  "$lint_or_true/.github/workflows/lint.yml" \
  '            .' \
  '            . || true'
assert_lint_payment_runtime_rejected "lint-payment-or-true" "$lint_or_true"

lint_before_setup="$(copy_payment_hardening_fixture lint-payment-before-setup)"
python3 - "$lint_before_setup/.github/workflows/lint.yml" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
gate = """      - name: Payment hardening required runtime
        run: |
          python3 scripts/validate-payment-hardening.py \\
            --run-required-runtime \\
            .

"""
anchor = "      - name: Set up JDK 21\n"
if text.count(gate) != 1 or text.count(anchor) != 1:
    raise SystemExit("lint gate ordering fixture source changed")
text = text.replace(gate, "").replace(anchor, gate + anchor)
path.write_text(text, encoding="utf-8")
PY
assert_lint_payment_runtime_rejected "lint-payment-before-setup" "$lint_before_setup"

payment_migration_symlink="$(copy_payment_hardening_fixture payment-protected-migration-symlink)"
rm "$payment_migration_symlink/core-data/src/main/resources/db/migration/postgresql/V056__atomic_booking_refunds.sql"
ln -s \
  "$payment_migration_symlink/core-data/src/main/resources/db/migration/h2/V056__atomic_booking_refunds.sql" \
  "$payment_migration_symlink/core-data/src/main/resources/db/migration/postgresql/V056__atomic_booking_refunds.sql"
assert_payment_hardening_rejected "payment-protected-migration-symlink" "PH-FILE" "$payment_migration_symlink"

payment_test_symlink="$(copy_payment_hardening_fixture payment-protected-test-symlink)"
rm "$payment_test_symlink/app-bot/src/test/kotlin/com/example/bot/payments/PaymentsPersistenceTest.kt"
ln -s \
  "$payment_test_symlink/app-bot/src/test/kotlin/com/example/bot/logging/SensitiveIdempotencyLoggingTest.kt" \
  "$payment_test_symlink/app-bot/src/test/kotlin/com/example/bot/payments/PaymentsPersistenceTest.kt"
assert_payment_hardening_rejected "payment-protected-test-symlink" "PH-FILE" "$payment_test_symlink"

payment_doc_symlink="$(copy_payment_hardening_fixture payment-protected-doc-symlink)"
rm "$payment_doc_symlink/docs/invariants.md"
ln -s "$payment_doc_symlink/.github/workflows/lint.yml" "$payment_doc_symlink/docs/invariants.md"
assert_payment_hardening_rejected "payment-protected-doc-symlink" "PH-FILE" "$payment_doc_symlink"

payment_helper_symlink="$(copy_payment_hardening_fixture payment-protected-helper-symlink)"
rm "$payment_helper_symlink/scripts/verify-payment-hardening-runtime.sh"
ln -s "$payment_helper_symlink/scripts/selfcheck-quality-gates.sh" \
  "$payment_helper_symlink/scripts/verify-payment-hardening-runtime.sh"
assert_payment_hardening_rejected "payment-protected-helper-symlink" "PH-FILE" "$payment_helper_symlink"

payment_parent_symlink="$(copy_payment_hardening_fixture payment-protected-parent-symlink)"
mv "$payment_parent_symlink/docs" "$payment_parent_symlink/docs-real"
ln -s docs-real "$payment_parent_symlink/docs"
assert_payment_hardening_rejected "payment-protected-parent-symlink" "PH-FILE" "$payment_parent_symlink"

payment_migration_log_symlink="$(copy_payment_hardening_fixture payment-protected-migration-log-symlink)"
rm "$payment_migration_log_symlink/app-bot/src/main/resources/quiesced-migration-logback.xml"
ln -s logback.xml \
  "$payment_migration_log_symlink/app-bot/src/main/resources/quiesced-migration-logback.xml"
assert_payment_hardening_rejected \
  "payment-protected-migration-log-symlink" \
  "PH-FILE" \
  "$payment_migration_log_symlink"

payment_migration_boundary_symlink="$(copy_payment_hardening_fixture payment-protected-migration-boundary-symlink)"
rm "$payment_migration_boundary_symlink/app-bot/src/main/dist/bin/app-bot-migrate"
ln -s ../../../../build.gradle.kts \
  "$payment_migration_boundary_symlink/app-bot/src/main/dist/bin/app-bot-migrate"
assert_payment_hardening_rejected \
  "payment-protected-migration-boundary-symlink" \
  "PH-FILE" \
  "$payment_migration_boundary_symlink"

payment_migration_log_throwable="$(copy_payment_hardening_fixture payment-migration-log-throwable-pattern)"
replace_payment_text_once \
  "$payment_migration_log_throwable/app-bot/src/main/resources/quiesced-migration-logback.xml" \
  '<pattern>%msg%n%nopex</pattern>' \
  '<pattern>%msg%n%ex</pattern>'
assert_payment_hardening_rejected \
  "payment-migration-log-throwable-pattern" \
  "PH-LOG-MIGRATION" \
  "$payment_migration_log_throwable"

payment_migration_flyway_logging="$(copy_payment_hardening_fixture payment-migration-flyway-logging)"
replace_payment_text_once \
  "$payment_migration_flyway_logging/app-bot/src/main/resources/quiesced-migration-logback.xml" \
  '<logger name="org.flywaydb" level="OFF" additivity="false" />' \
  '<logger name="org.flywaydb" level="INFO" additivity="false" />'
assert_payment_hardening_rejected \
  "payment-migration-flyway-logging" \
  "PH-LOG-MIGRATION" \
  "$payment_migration_flyway_logging"

payment_migration_raw_failure="$(copy_payment_hardening_fixture payment-migration-raw-failure-message)"
replace_payment_text_once \
  "$payment_migration_raw_failure/app-bot/src/main/kotlin/com/example/bot/tools/QuiescedMigrateMain.kt" \
  '    } catch (failure: Exception) {
        eventSink(MigrationSafeEvent.Failed(phase, classifyFailure(phase, failure)))' \
  '    } catch (failure: Exception) {
        System.err.println(failure.message)
        eventSink(MigrationSafeEvent.Failed(phase, classifyFailure(phase, failure)))'
assert_payment_hardening_rejected \
  "payment-migration-raw-failure-message" \
  "PH-LOG-MIGRATION" \
  "$payment_migration_raw_failure"

payment_migration_test_removed="$(copy_payment_hardening_fixture payment-migration-redaction-test-removed)"
replace_payment_text_once \
  "$payment_migration_test_removed/app-bot/src/test/kotlin/com/example/bot/tools/QuiescedMigrateMainTest.kt" \
  'fun `real entrypoint suppresses connection canaries and stack traces`()' \
  'fun `real entrypoint emits connection diagnostics`()'
assert_payment_hardening_rejected \
  "payment-migration-redaction-test-removed" \
  "PH-TEST-MISSING" \
  "$payment_migration_test_removed"

payment_non_regular="$(copy_payment_hardening_fixture payment-protected-non-regular)"
rm "$payment_non_regular/docs/invariants.md"
mkfifo "$payment_non_regular/docs/invariants.md"
assert_payment_hardening_rejected "payment-protected-non-regular" "PH-FILE" "$payment_non_regular"

payment_runner_missing="$(copy_payment_hardening_fixture payment-protected-runner-missing)"
rm "$payment_runner_missing/scripts/validate-payment-hardening.py"
assert_payment_hardening_rejected "payment-protected-runner-missing" "PH-FILE" "$payment_runner_missing"

payment_missing_payments_index="$(copy_payment_hardening_fixture payment-missing-payments-index)"
remove_payment_statement_once \
  "$payment_missing_payments_index/core-data/src/main/resources/db/migration/postgresql/V056__atomic_booking_refunds.sql" \
  "CREATE INDEX IF NOT EXISTS payments_booking_idx"
assert_payment_hardening_rejected \
  "payment-missing-payments-index" \
  "PH-MIGRATION-INDEX" \
  "$payment_missing_payments_index"

payment_missing_refunds_index="$(copy_payment_hardening_fixture payment-missing-refunds-index)"
remove_payment_statement_once \
  "$payment_missing_refunds_index/core-data/src/main/resources/db/migration/postgresql/V056__atomic_booking_refunds.sql" \
  "CREATE INDEX payment_refunds_booking_idx"
assert_payment_hardening_rejected \
  "payment-missing-refunds-index" \
  "PH-MIGRATION-INDEX" \
  "$payment_missing_refunds_index"

payment_typed_null_result="$(copy_payment_hardening_fixture payment-typed-null-result)"
replace_exact_line_once \
  "$payment_typed_null_result/core-data/src/main/resources/db/migration/postgresql/V056__atomic_booking_refunds.sql" \
  "$payment_typed_null_result/typed-null.changed.sql" \
  "                THEN refund_result_amount_minor IS NOT NULL" \
  "                THEN TRUE"
mv \
  "$payment_typed_null_result/typed-null.changed.sql" \
  "$payment_typed_null_result/core-data/src/main/resources/db/migration/postgresql/V056__atomic_booking_refunds.sql"
assert_payment_hardening_rejected \
  "payment-typed-null-result" \
  "PH-MIGRATION-TYPED" \
  "$payment_typed_null_result"

payment_positive_null_source="$(copy_payment_hardening_fixture payment-positive-null-source)"
replace_exact_line_once \
  "$payment_positive_null_source/core-data/src/main/resources/db/migration/postgresql/V056__atomic_booking_refunds.sql" \
  "$payment_positive_null_source/positive-null.changed.sql" \
  "                            AND refund_source_kind = 'ATOMIC_ACTION'" \
  "                            AND (refund_source_kind = 'ATOMIC_ACTION' OR refund_source_kind IS NULL)"
mv \
  "$payment_positive_null_source/positive-null.changed.sql" \
  "$payment_positive_null_source/core-data/src/main/resources/db/migration/postgresql/V056__atomic_booking_refunds.sql"
assert_payment_hardening_rejected \
  "payment-positive-null-source" \
  "PH-MIGRATION-TYPED" \
  "$payment_positive_null_source"

payment_zero_legacy="$(copy_payment_hardening_fixture payment-zero-legacy-not-blocked)"
replace_exact_line_once \
  "$payment_zero_legacy/core-data/src/main/resources/db/migration/postgresql/V056__atomic_booking_refunds.sql" \
  "$payment_zero_legacy/zero-legacy.changed.sql" \
  "            IF NEW.reason::numeric <= 0 THEN" \
  "            IF NEW.reason::numeric < 0 THEN"
mv \
  "$payment_zero_legacy/zero-legacy.changed.sql" \
  "$payment_zero_legacy/core-data/src/main/resources/db/migration/postgresql/V056__atomic_booking_refunds.sql"
assert_payment_hardening_rejected \
  "payment-zero-legacy-not-blocked" \
  "PH-MIGRATION-LEGACY" \
  "$payment_zero_legacy"

payment_weakened_source="$(copy_payment_hardening_fixture payment-weakened-source-consistency)"
replace_exact_line_once \
  "$payment_weakened_source/core-data/src/main/resources/db/migration/postgresql/V056__atomic_booking_refunds.sql" \
  "$payment_weakened_source/source-consistency.changed.sql" \
  "                AND source_action = 'REFUND'" \
  "                AND source_action IN ('REFUND', 'CANCEL')"
mv \
  "$payment_weakened_source/source-consistency.changed.sql" \
  "$payment_weakened_source/core-data/src/main/resources/db/migration/postgresql/V056__atomic_booking_refunds.sql"
assert_payment_hardening_rejected \
  "payment-weakened-source-consistency" \
  "PH-MIGRATION-SOURCE" \
  "$payment_weakened_source"

payment_missing_search_path="$(copy_payment_hardening_fixture payment-missing-search-path)"
replace_payment_text_once \
  "$payment_missing_search_path/core-data/src/main/resources/db/migration/postgresql/V056__atomic_booking_refunds.sql" \
  $'CREATE OR REPLACE FUNCTION "${flyway:defaultSchema}".payment_refund_block_booking(\n    target_booking_id uuid,\n    target_reason varchar\n) RETURNS void\nLANGUAGE plpgsql\nSET search_path = pg_catalog, "${flyway:defaultSchema}"' \
  $'CREATE OR REPLACE FUNCTION "${flyway:defaultSchema}".payment_refund_block_booking(\n    target_booking_id uuid,\n    target_reason varchar\n) RETURNS void\nLANGUAGE plpgsql'
assert_payment_hardening_rejected \
  "payment-missing-search-path" \
  "PH-MIGRATION-SEARCH-PATH" \
  "$payment_missing_search_path"

payment_unqualified_relation="$(copy_payment_hardening_fixture payment-unqualified-relation)"
replace_payment_text_once \
  "$payment_unqualified_relation/core-data/src/main/resources/db/migration/postgresql/V056__atomic_booking_refunds.sql" \
  $'    THEN\n        INSERT INTO "${flyway:defaultSchema}".payment_refunds (' \
  $'    THEN\n        INSERT INTO payment_refunds ('
assert_payment_hardening_rejected \
  "payment-unqualified-relation" \
  "PH-MIGRATION-QUALIFICATION" \
  "$payment_unqualified_relation"

raw_mdc_fixture_paths=(
  "core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt"
  "core-security/src/main/kotlin/com/example/bot/security/webhook/WebhookSecurityPlugin.kt"
  "app-bot/src/main/kotlin/com/example/bot/routes/BookingFinalizeRoutes.kt"
  "app-bot/src/main/kotlin/com/example/bot/promo/BookingTemplateService.kt"
)
raw_mdc_fixture_index=0
for relative_path in "${raw_mdc_fixture_paths[@]}"; do
  raw_mdc_fixture_index=$((raw_mdc_fixture_index + 1))
  fixture_name="payment-raw-mdc-$raw_mdc_fixture_index"
  fixture_root="$(copy_payment_hardening_fixture "$fixture_name")"
  printf '%s\n' \
    'private fun unsafeIdempotencyLoggingFixture(rawKey: String) {' \
    '    org.slf4j.MDC.put("idempotencyKey", rawKey)' \
    '}' >> "$fixture_root/$relative_path"
  assert_payment_hardening_rejected \
    "$fixture_name" \
    "PH-LOG-RAW-SINK" \
    "$fixture_root"
done

payment_finalize_raw_log="$(copy_payment_hardening_fixture payment-finalize-raw-log)"
cat >> \
  "$payment_finalize_raw_log/app-bot/src/main/kotlin/com/example/bot/payments/finalize/DefaultPaymentsFinalizeService.kt" <<'KOT'
private fun unsafeIdempotencyLoggingFixture(rawKey: String) {
    org.slf4j.LoggerFactory.getLogger("fixture").info("idemKey={}", rawKey)
}
KOT
assert_payment_hardening_rejected \
  "payment-finalize-raw-log" \
  "PH-LOG-RAW-SINK" \
  "$payment_finalize_raw_log"

payment_db_exception_message="$(copy_payment_hardening_fixture payment-db-exception-message)"
replace_payment_text_once \
  "$payment_db_exception_message/core-data/src/main/kotlin/com/example/bot/data/db/DbTransactions.kt" \
  $'                classification.sqlState ?: "<none>",\n                ex.javaClass.simpleName,' \
  $'                classification.sqlState ?: "<none>",\n                ex.message,'
assert_payment_hardening_rejected \
  "payment-db-exception-message" \
  "PH-LOG-SQL-BOUNDARY" \
  "$payment_db_exception_message"

payment_logging_class_disabled="$(copy_payment_hardening_fixture payment-logging-class-disabled)"
replace_payment_text_once \
  "$payment_logging_class_disabled/app-bot/src/test/kotlin/com/example/bot/logging/SensitiveIdempotencyLoggingTest.kt" \
  "class SensitiveIdempotencyLoggingTest {" \
  $'@org.junit.jupiter.api.Disabled\nclass SensitiveIdempotencyLoggingTest {'
assert_payment_hardening_rejected \
  "payment-logging-class-disabled" \
  "PH-TEST-DISABLED" \
  "$payment_logging_class_disabled"

payment_logging_method_disabled="$(copy_payment_hardening_fixture payment-logging-method-disabled)"
replace_payment_text_once \
  "$payment_logging_method_disabled/app-bot/src/test/kotlin/com/example/bot/logging/SensitiveIdempotencyLoggingTest.kt" \
  $'    @Test\n    fun `booking finalize route never serializes raw idempotency key`()' \
  $'    @Test\n    @org.junit.jupiter.api.Disabled\n    fun `booking finalize route never serializes raw idempotency key`()'
assert_payment_hardening_rejected \
  "payment-logging-method-disabled" \
  "PH-TEST-DISABLED" \
  "$payment_logging_method_disabled"

payment_logging_test_missing="$(copy_payment_hardening_fixture payment-logging-test-missing)"
rm \
  "$payment_logging_test_missing/app-bot/src/test/kotlin/com/example/bot/logging/SensitiveIdempotencyLoggingTest.kt"
assert_payment_hardening_rejected \
  "payment-logging-test-missing" \
  "PH-FILE" \
  "$payment_logging_test_missing"

payment_sql_logging_class_disabled="$(copy_payment_hardening_fixture payment-sql-logging-class-disabled)"
replace_payment_text_once \
  "$payment_sql_logging_class_disabled/app-bot/src/test/kotlin/com/example/bot/logging/SqlThrowableLoggingPersistenceTest.kt" \
  "class SqlThrowableLoggingPersistenceTest : PostgresAppTest() {" \
  $'@org.junit.jupiter.api.Disabled\nclass SqlThrowableLoggingPersistenceTest : PostgresAppTest() {'
assert_payment_hardening_rejected \
  "payment-sql-logging-class-disabled" \
  "PH-TEST-DISABLED" \
  "$payment_sql_logging_class_disabled"

payment_sql_logging_method_disabled="$(copy_payment_hardening_fixture payment-sql-logging-method-disabled)"
replace_payment_text_once \
  "$payment_sql_logging_method_disabled/app-bot/src/test/kotlin/com/example/bot/logging/SqlThrowableLoggingPersistenceTest.kt" \
  $'    @Test\n    fun `postgres sql throwable never reaches payment route status pages or json logs`()' \
  $'    @Test\n    @org.junit.jupiter.api.Disabled\n    fun `postgres sql throwable never reaches payment route status pages or json logs`()'
assert_payment_hardening_rejected \
  "payment-sql-logging-method-disabled" \
  "PH-TEST-DISABLED" \
  "$payment_sql_logging_method_disabled"

payment_sql_logging_test_missing="$(copy_payment_hardening_fixture payment-sql-logging-test-missing)"
replace_payment_text_once \
  "$payment_sql_logging_test_missing/app-bot/src/test/kotlin/com/example/bot/logging/SqlThrowableLoggingPersistenceTest.kt" \
  'fun `postgres sql throwable never reaches payment route status pages or json logs`()' \
  'fun `removed sql topology regression fixture`()'
assert_payment_hardening_rejected \
  "payment-sql-logging-test-missing" \
  "PH-TEST-MISSING" \
  "$payment_sql_logging_test_missing"

payment_raw_rbac_logger="$(copy_payment_hardening_fixture payment-raw-rbac-logger)"
cat >> "$payment_raw_rbac_logger/core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt" <<'KOT'
private fun unsafeRawLoggerFixture(rawKey: String) {
    org.slf4j.LoggerFactory.getLogger("fixture").error("raw={}", rawKey)
}
KOT
assert_payment_hardening_rejected \
  "payment-raw-rbac-logger" \
  "PH-LOG-RAW-SINK" \
  "$payment_raw_rbac_logger"

payment_raw_cancel_refund_logger="$(copy_payment_hardening_fixture payment-raw-cancel-refund-logger)"
cat >> "$payment_raw_cancel_refund_logger/app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt" <<'KOT'
private fun unsafeRawRouteLoggerFixture(rawKey: String) {
    logger.error("raw={}", rawKey)
}
KOT
assert_payment_hardening_rejected \
  "payment-raw-cancel-refund-logger" \
  "PH-LOG-RAW-SINK" \
  "$payment_raw_cancel_refund_logger"

payment_new_production_logger="$(copy_payment_hardening_fixture payment-new-production-logger)"
mkdir -p "$payment_new_production_logger/some-module/src/main/kotlin/com/example/fixture"
cat > "$payment_new_production_logger/some-module/src/main/kotlin/com/example/fixture/UnsafeLogger.kt" <<'KOT'
package com.example.fixture

private val logger = org.slf4j.LoggerFactory.getLogger("fixture")

fun unsafeNewProductionLogger(idempotencyKey: String) {
    logger.error("raw={}", idempotencyKey)
}
KOT
git -C "$payment_new_production_logger" add -- some-module/src/main/kotlin/com/example/fixture/UnsafeLogger.kt
assert_payment_hardening_rejected \
  "payment-new-production-logger" \
  "PH-LOG-RAW-SINK" \
  "$payment_new_production_logger"

payment_non_regular_production="$(copy_payment_hardening_fixture payment-non-regular-production)"
mkdir -p "$payment_non_regular_production/some-module/src/main/kotlin/com/example/fixture"
ln -s \
  "$payment_non_regular_production/core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt" \
  "$payment_non_regular_production/some-module/src/main/kotlin/com/example/fixture/LinkedProduction.kt"
git -C "$payment_non_regular_production" add -- some-module/src/main/kotlin/com/example/fixture/LinkedProduction.kt
assert_payment_hardening_rejected \
  "payment-non-regular-production" \
  "PH-INVENTORY" \
  "$payment_non_regular_production"

payment_new_production_mdc="$(copy_payment_hardening_fixture payment-new-production-mdc)"
mkdir -p "$payment_new_production_mdc/some-module/src/main/kotlin/com/example/fixture"
cat > "$payment_new_production_mdc/some-module/src/main/kotlin/com/example/fixture/UnsafeMdc.kt" <<'KOT'
package com.example.fixture

fun unsafeNewProductionMdc(idempotencyKey: String) {
    val firstAlias = idempotencyKey
    val secondAlias = firstAlias
    org.slf4j.MDC.put("idempotencyKey", secondAlias)
}
KOT
git -C "$payment_new_production_mdc" add -- some-module/src/main/kotlin/com/example/fixture/UnsafeMdc.kt
assert_payment_hardening_rejected \
  "payment-new-production-mdc" \
  "PH-LOG-RAW-SINK" \
  "$payment_new_production_mdc"

payment_throwable_alias="$(copy_payment_hardening_fixture payment-throwable-alias)"
cat >> "$payment_throwable_alias/app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt" <<'KOT'
private fun unsafeThrowableAliasFixture(originalFailure: Throwable) {
    val firstAlias =
        originalFailure
    val secondAlias =
        firstAlias
    logger.error("unsafe", secondAlias)
}
KOT
assert_payment_hardening_rejected \
  "payment-throwable-alias" \
  "PH-LOG-SQL-BOUNDARY" \
  "$payment_throwable_alias"

payment_raw_alias="$(copy_payment_hardening_fixture payment-raw-idempotency-alias)"
mkdir -p "$payment_raw_alias/some-module/src/main/kotlin/com/example/fixture"
cat > "$payment_raw_alias/some-module/src/main/kotlin/com/example/fixture/UnsafeAlias.kt" <<'KOT'
package com.example.fixture

private val logger = org.slf4j.LoggerFactory.getLogger("fixture")

fun unsafeAlias(headers: Map<String, String>) {
    val source =
        headers["Idempotency-Key"]
    val firstAlias =
        source
    val secondAlias =
        firstAlias
    logger.warn("raw={}", secondAlias)
}
KOT
git -C "$payment_raw_alias" add -- some-module/src/main/kotlin/com/example/fixture/UnsafeAlias.kt
assert_payment_hardening_rejected \
  "payment-raw-idempotency-alias" \
  "PH-LOG-RAW-SINK" \
  "$payment_raw_alias"

payment_unknown_helper_return="$(copy_payment_hardening_fixture payment-unknown-helper-return)"
mkdir -p "$payment_unknown_helper_return/some-module/src/main/kotlin/com/example/fixture"
cat > "$payment_unknown_helper_return/some-module/src/main/kotlin/com/example/fixture/UnsafeHelperReturn.kt" <<'KOT'
package com.example.fixture

private val logger = org.slf4j.LoggerFactory.getLogger("fixture")

private fun identity(value: String): String = value

fun unsafeHelperReturn(idempotencyKey: String) {
    val alias = identity(idempotencyKey)
    logger.error("raw={}", alias)
}
KOT
git -C "$payment_unknown_helper_return" add -- some-module/src/main/kotlin/com/example/fixture/UnsafeHelperReturn.kt
assert_payment_hardening_rejected \
  "payment-unknown-helper-return" \
  "PH-LOG-RAW-SINK" \
  "$payment_unknown_helper_return"

payment_reviewed_projection_spoof="$(copy_payment_hardening_fixture payment-reviewed-projection-spoof)"
mkdir -p "$payment_reviewed_projection_spoof/app-bot/src/main/kotlin/com/example/bot/booking"
cp \
  "$ROOT_DIR/app-bot/src/main/kotlin/com/example/bot/booking/BookingService.kt" \
  "$payment_reviewed_projection_spoof/app-bot/src/main/kotlin/com/example/bot/booking/BookingService.kt"
cat >> "$payment_reviewed_projection_spoof/app-bot/src/main/kotlin/com/example/bot/booking/BookingService.kt" <<'KOT'
private data class UnsafeReviewedProjectionFixture(val error: String)

private fun <T> unsafeProjectionIdentityFixture(value: T): T = value

private fun unsafeReviewedProjectionFixture(idempotencyKey: String) {
    val result = unsafeProjectionIdentityFixture(UnsafeReviewedProjectionFixture(idempotencyKey))
    org.slf4j.LoggerFactory.getLogger("fixture").error("raw={}", result.error)
}
KOT
git -C "$payment_reviewed_projection_spoof" add -- app-bot/src/main/kotlin/com/example/bot/booking/BookingService.kt
assert_payment_hardening_rejected \
  "payment-reviewed-projection-spoof" \
  "PH-LOG-RAW-SINK" \
  "$payment_reviewed_projection_spoof"

payment_raw_reassignment="$(copy_payment_hardening_fixture payment-raw-reassignment)"
mkdir -p "$payment_raw_reassignment/some-module/src/main/kotlin/com/example/fixture"
cat > "$payment_raw_reassignment/some-module/src/main/kotlin/com/example/fixture/UnsafeReassignment.kt" <<'KOT'
package com.example.fixture

private val logger = org.slf4j.LoggerFactory.getLogger("fixture")

fun unsafeReassignment(idempotencyKey: String) {
    var alias = ""
    alias = idempotencyKey
    logger.error("raw={}", alias)
}
KOT
git -C "$payment_raw_reassignment" add -- some-module/src/main/kotlin/com/example/fixture/UnsafeReassignment.kt
assert_payment_hardening_rejected \
  "payment-raw-reassignment" \
  "PH-LOG-RAW-SINK" \
  "$payment_raw_reassignment"

payment_mixed_fingerprint="$(copy_payment_hardening_fixture payment-mixed-fingerprint-raw)"
cat >> "$payment_mixed_fingerprint/core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt" <<'KOT'
private fun unsafeMixedFingerprintFixture(idempotencyKey: String) {
    val auditFingerprint = fingerprint(idempotencyKey, "POST", "/fixture", "access_granted")
    org.slf4j.LoggerFactory.getLogger("fixture").error(
        "fingerprint={} raw={}",
        auditFingerprint,
        idempotencyKey,
    )
}
KOT
assert_payment_hardening_rejected \
  "payment-mixed-fingerprint-raw" \
  "PH-LOG-RAW-SINK" \
  "$payment_mixed_fingerprint"

payment_local_fingerprint_shadow="$(copy_payment_hardening_fixture payment-local-fingerprint-shadow)"
cat >> "$payment_local_fingerprint_shadow/core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt" <<'KOT'
private fun unsafeLocalFingerprintShadow(idempotencyKey: String) {
    fun fingerprint(value: String): String = value
    val leaked = fingerprint(idempotencyKey)
    org.slf4j.LoggerFactory.getLogger("fixture").error("raw={}", leaked)
}
KOT
assert_payment_hardening_rejected \
  "payment-local-fingerprint-shadow" \
  "PH-SYMBOL-CONTRACT" \
  "$payment_local_fingerprint_shadow"

payment_member_fingerprint_shadow="$(copy_payment_hardening_fixture payment-member-fingerprint-shadow)"
cat >> "$payment_member_fingerprint_shadow/core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt" <<'KOT'
private object UnsafeFingerprintShadow {
    fun fingerprint(value: String): String = value
}

private fun unsafeMemberFingerprintShadow(idempotencyKey: String) {
    val leaked = UnsafeFingerprintShadow.fingerprint(idempotencyKey)
    org.slf4j.LoggerFactory.getLogger("fixture").error("raw={}", leaked)
}
KOT
assert_payment_hardening_rejected \
  "payment-member-fingerprint-shadow" \
  "PH-SYMBOL-CONTRACT" \
  "$payment_member_fingerprint_shadow"

payment_fingerprint_overload="$(copy_payment_hardening_fixture payment-fingerprint-overload)"
cat >> "$payment_fingerprint_overload/core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt" <<'KOT'
private fun fingerprint(value: String): String = value
KOT
assert_payment_hardening_rejected \
  "payment-fingerprint-overload" \
  "PH-SYMBOL-CONTRACT" \
  "$payment_fingerprint_overload"

payment_raw_tracing="$(copy_payment_hardening_fixture payment-raw-tracing)"
mkdir -p "$payment_raw_tracing/some-module/src/main/kotlin/com/example/fixture"
cat > "$payment_raw_tracing/some-module/src/main/kotlin/com/example/fixture/UnsafeTracing.kt" <<'KOT'
package com.example.fixture

interface FixtureSpan {
    fun setAttribute(name: String, value: String)
}

fun unsafeTracing(idempotencyKey: String, span: FixtureSpan) {
    val alias = idempotencyKey
    span.setAttribute("payment.idempotency", alias)
}
KOT
git -C "$payment_raw_tracing" add -- some-module/src/main/kotlin/com/example/fixture/UnsafeTracing.kt
assert_payment_hardening_rejected \
  "payment-raw-tracing" \
  "PH-LOG-RAW-SINK" \
  "$payment_raw_tracing"

payment_generic_tracing="$(copy_payment_hardening_fixture payment-generic-tracing)"
mkdir -p "$payment_generic_tracing/some-module/src/main/kotlin/com/example/fixture"
cat > "$payment_generic_tracing/some-module/src/main/kotlin/com/example/fixture/UnsafeGenericTracing.kt" <<'KOT'
package com.example.fixture

interface GenericSpan {
    fun <T> setAttribute(name: String, value: T)
}

fun unsafeGenericTracing(idempotencyKey: String, span: GenericSpan) {
    val alias = idempotencyKey
    span.setAttribute<String>("raw", alias)
}
KOT
git -C "$payment_generic_tracing" add -- some-module/src/main/kotlin/com/example/fixture/UnsafeGenericTracing.kt
assert_payment_hardening_rejected \
  "payment-generic-tracing" \
  "PH-LOG-RAW-SINK" \
  "$payment_generic_tracing"

payment_raw_require="$(copy_payment_hardening_fixture payment-raw-require-message)"
mkdir -p "$payment_raw_require/some-module/src/main/kotlin/com/example/fixture"
cat > "$payment_raw_require/some-module/src/main/kotlin/com/example/fixture/UnsafeRequire.kt" <<'KOT'
package com.example.fixture

fun unsafeRequire(idempotencyKey: String) {
    val alias = idempotencyKey
    require(false) { "raw=$alias" }
}
KOT
git -C "$payment_raw_require" add -- some-module/src/main/kotlin/com/example/fixture/UnsafeRequire.kt
assert_payment_hardening_rejected \
  "payment-raw-require-message" \
  "PH-LOG-RAW-SINK" \
  "$payment_raw_require"

payment_raw_check="$(copy_payment_hardening_fixture payment-raw-check-message)"
mkdir -p "$payment_raw_check/some-module/src/main/kotlin/com/example/fixture"
cat > "$payment_raw_check/some-module/src/main/kotlin/com/example/fixture/UnsafeCheck.kt" <<'KOT'
package com.example.fixture

fun unsafeCheck(idempotencyKey: String) {
    val alias = idempotencyKey
    check(false) { "raw=$alias" }
}
KOT
git -C "$payment_raw_check" add -- some-module/src/main/kotlin/com/example/fixture/UnsafeCheck.kt
assert_payment_hardening_rejected \
  "payment-raw-check-message" \
  "PH-LOG-RAW-SINK" \
  "$payment_raw_check"

payment_raw_exception="$(copy_payment_hardening_fixture payment-raw-exception)"
mkdir -p "$payment_raw_exception/some-module/src/main/kotlin/com/example/fixture"
cat > "$payment_raw_exception/some-module/src/main/kotlin/com/example/fixture/UnsafeException.kt" <<'KOT'
package com.example.fixture

fun unsafeException(idempotencyKey: String): Nothing {
    val alias = idempotencyKey
    throw IllegalStateException("idempotency=$alias")
}
KOT
git -C "$payment_raw_exception" add -- some-module/src/main/kotlin/com/example/fixture/UnsafeException.kt
assert_payment_hardening_rejected \
  "payment-raw-exception" \
  "PH-LOG-RAW-SINK" \
  "$payment_raw_exception"

payment_safe_business="$(copy_payment_hardening_fixture payment-safe-business-lookup)"
mkdir -p "$payment_safe_business/some-module/src/main/kotlin/com/example/fixture"
cat > "$payment_safe_business/some-module/src/main/kotlin/com/example/fixture/SafeBusiness.kt" <<'KOT'
package com.example.fixture

fun safeLookup(idempotencyKey: String, stored: Map<String, String>): String? = stored[idempotencyKey]
KOT
git -C "$payment_safe_business" add -- some-module/src/main/kotlin/com/example/fixture/SafeBusiness.kt
assert_payment_hardening_accepted "payment-safe-business-lookup" "$payment_safe_business"

payment_safe_fingerprint="$(copy_payment_hardening_fixture payment-safe-fingerprint)"
cat >> "$payment_safe_fingerprint/core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt" <<'KOT'
private fun safeReviewedFingerprintFixture(idempotencyKey: String) {
    val auditFingerprint = fingerprint(idempotencyKey, "POST", "/fixture", "access_granted")
    org.slf4j.LoggerFactory.getLogger("fixture").info("auditFingerprint={}", auditFingerprint)
}
KOT
assert_payment_hardening_accepted "payment-safe-fingerprint" "$payment_safe_fingerprint"

payment_exact_result_projection_spoof="$(copy_payment_hardening_fixture payment-exact-result-projection-spoof)"
cat >> "$payment_exact_result_projection_spoof/app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt" <<'KOT'
private data class FakeResult(val idempotent: String)

private fun unsafeExactResultProjectionSpoof(idempotencyKey: String) {
    val result = FakeResult(idempotencyKey)
    logger.error("raw={}", result.idempotent)
}
KOT
assert_payment_hardening_rejected \
  "payment-exact-result-projection-spoof" \
  "PH-LOG-RAW-SINK" \
  "$payment_exact_result_projection_spoof"

payment_safe_escaped_literal="$(copy_payment_hardening_fixture payment-safe-escaped-idempotency-literal)"
mkdir -p "$payment_safe_escaped_literal/some-module/src/main/kotlin/com/example/fixture"
cat > "$payment_safe_escaped_literal/some-module/src/main/kotlin/com/example/fixture/SafeEscapedLiteral.kt" <<'KOT'
package com.example.fixture

private val logger = org.slf4j.LoggerFactory.getLogger("fixture")

fun safeEscapedLiteral(idempotencyKey: String) {
    logger.info("\$idempotencyKey")
}
KOT
git -C "$payment_safe_escaped_literal" add -- some-module/src/main/kotlin/com/example/fixture/SafeEscapedLiteral.kt
assert_payment_hardening_accepted "payment-safe-escaped-idempotency-literal" "$payment_safe_escaped_literal"

payment_safe_non_sql="$(copy_payment_hardening_fixture payment-safe-non-sql-throwable)"
mkdir -p "$payment_safe_non_sql/some-module/src/main/kotlin/com/example/fixture"
cat > "$payment_safe_non_sql/some-module/src/main/kotlin/com/example/fixture/SafeThrowable.kt" <<'KOT'
package com.example.fixture

private val logger = org.slf4j.LoggerFactory.getLogger("fixture")

fun safeNonSqlDiagnostic(failure: Throwable) {
    logger.error("unrelated failure", failure)
}
KOT
git -C "$payment_safe_non_sql" add -- some-module/src/main/kotlin/com/example/fixture/SafeThrowable.kt
assert_payment_hardening_accepted "payment-safe-non-sql-throwable" "$payment_safe_non_sql"

payment_finalize_route_throwable="$(copy_payment_hardening_fixture payment-finalize-route-throwable)"
cat >> "$payment_finalize_route_throwable/app-bot/src/main/kotlin/com/example/bot/routes/PaymentsFinalizeRoutes.kt" <<'KOT'
private fun unsafeFinalizeThrowableFixture(unexpected: Throwable) {
    logger.error(unexpected) { "unsafe" }
}
KOT
assert_payment_hardening_rejected \
  "payment-finalize-route-throwable" \
  "PH-LOG-SQL-BOUNDARY" \
  "$payment_finalize_route_throwable"

payment_json_error_throwable="$(copy_payment_hardening_fixture payment-json-error-throwable)"
cat >> "$payment_json_error_throwable/app-bot/src/main/kotlin/com/example/bot/plugins/JsonErrorPages.kt" <<'KOT'
private fun unsafeJsonThrowableFixture(cause: Throwable) {
    org.slf4j.LoggerFactory.getLogger("fixture").error("unsafe", cause)
}
KOT
assert_payment_hardening_rejected \
  "payment-json-error-throwable" \
  "PH-LOG-SQL-BOUNDARY" \
  "$payment_json_error_throwable"

payment_sql_helper_direct_throwable="$(copy_payment_hardening_fixture payment-sql-helper-direct-throwable)"
cat >> "$payment_sql_helper_direct_throwable/app-bot/src/main/kotlin/com/example/bot/logging/SqlThrowableLogging.kt" <<'KOT'
private fun unsafeSqlHelperThrowableFixture(original: Throwable) {
    org.slf4j.LoggerFactory.getLogger("fixture").error("failed", original)
}
KOT
assert_payment_hardening_rejected \
  "payment-sql-helper-direct-throwable" \
  "PH-LOG-SQL-BOUNDARY" \
  "$payment_sql_helper_direct_throwable"

payment_sql_helper_name_spoof="$(copy_payment_hardening_fixture payment-sql-helper-name-spoof)"
cat >> "$payment_sql_helper_name_spoof/app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt" <<'KOT'
private object UnsafeSqlLogger {
    fun errorSqlSafe(value: Throwable) {
        logger.error("failed", value)
    }
}

private fun unsafeSqlHelperNameSpoof(original: Throwable) {
    UnsafeSqlLogger.errorSqlSafe(original)
}
KOT
assert_payment_hardening_rejected \
  "payment-sql-helper-name-spoof" \
  "PH-SYMBOL-CONTRACT" \
  "$payment_sql_helper_name_spoof"

payment_local_warn_sql_safe="$(copy_payment_hardening_fixture payment-local-warn-sql-safe)"
cat >> "$payment_local_warn_sql_safe/app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt" <<'KOT'
private fun unsafeLocalWarnSqlSafe(original: Throwable) {
    fun warnSqlSafe(value: Throwable) {
        logger.error("failed", value)
    }
    warnSqlSafe(original)
}
KOT
assert_payment_hardening_rejected \
  "payment-local-warn-sql-safe" \
  "PH-SYMBOL-CONTRACT" \
  "$payment_local_warn_sql_safe"

payment_throwable_reassignment="$(copy_payment_hardening_fixture payment-throwable-reassignment)"
cat >> "$payment_throwable_reassignment/app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt" <<'KOT'
private fun unsafeThrowableReassignmentFixture(original: Throwable) {
    var alias: Any? = null
    alias = original
    logger.error("failed {}", alias)
}
KOT
assert_payment_hardening_rejected \
  "payment-throwable-reassignment" \
  "PH-LOG-SQL-BOUNDARY" \
  "$payment_throwable_reassignment"

payment_fluent_set_cause="$(copy_payment_hardening_fixture payment-fluent-set-cause)"
cat >> "$payment_fluent_set_cause/app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt" <<'KOT'
private fun unsafeFluentCauseFixture(original: Throwable) {
    org.slf4j.LoggerFactory
        .getLogger("fixture")
        .atError()
        .setCause(original)
        .log("failed")
}
KOT
assert_payment_hardening_rejected \
  "payment-fluent-set-cause" \
  "PH-LOG-SQL-BOUNDARY" \
  "$payment_fluent_set_cause"

payment_fluent_raw_argument="$(copy_payment_hardening_fixture payment-fluent-raw-argument)"
mkdir -p "$payment_fluent_raw_argument/some-module/src/main/kotlin/com/example/fixture"
cat > "$payment_fluent_raw_argument/some-module/src/main/kotlin/com/example/fixture/UnsafeFluentArgument.kt" <<'KOT'
package com.example.fixture

fun unsafeFluentArgument(idempotencyKey: String) {
    val alias = idempotencyKey
    org.slf4j.LoggerFactory
        .getLogger("fixture")
        .atError()
        .addArgument(alias)
        .log("raw={}")
}
KOT
git -C "$payment_fluent_raw_argument" add -- some-module/src/main/kotlin/com/example/fixture/UnsafeFluentArgument.kt
assert_payment_hardening_rejected \
  "payment-fluent-raw-argument" \
  "PH-LOG-RAW-SINK" \
  "$payment_fluent_raw_argument"

payment_fluent_raw_key_value="$(copy_payment_hardening_fixture payment-fluent-raw-key-value)"
mkdir -p "$payment_fluent_raw_key_value/some-module/src/main/kotlin/com/example/fixture"
cat > "$payment_fluent_raw_key_value/some-module/src/main/kotlin/com/example/fixture/UnsafeFluentKeyValue.kt" <<'KOT'
package com.example.fixture

fun unsafeFluentKeyValue(idempotencyKey: String) {
    val alias = idempotencyKey
    org.slf4j.LoggerFactory
        .getLogger("fixture")
        .atError()
        .addKeyValue("raw", alias)
        .log("failed")
}
KOT
git -C "$payment_fluent_raw_key_value" add -- some-module/src/main/kotlin/com/example/fixture/UnsafeFluentKeyValue.kt
assert_payment_hardening_rejected \
  "payment-fluent-raw-key-value" \
  "PH-LOG-RAW-SINK" \
  "$payment_fluent_raw_key_value"

payment_exposed_filter_missing="$(copy_payment_hardening_fixture payment-exposed-filter-missing)"
replace_payment_text_once \
  "$payment_exposed_filter_missing/app-bot/src/main/kotlin/com/example/bot/logging/DenySensitiveTurboFilter.kt" \
  "containsSqlThrowable(t, params) || isUnsafeExposedTransactionFailure(level, format)" \
  "false"
assert_payment_hardening_rejected \
  "payment-exposed-filter-missing" \
  "PH-LOG-EXPOSED" \
  "$payment_exposed_filter_missing"

payment_zero_service_disabled="$(copy_payment_hardening_fixture payment-zero-service-disabled)"
replace_payment_text_once \
  "$payment_zero_service_disabled/app-bot/src/test/kotlin/com/example/bot/payments/PaymentsPersistenceTest.kt" \
  $'    @Test\n    fun `refund explicit zero persists terminal success without mutation`()' \
  $'    @Test\n    @org.junit.jupiter.api.Disabled\n    fun `refund explicit zero persists terminal success without mutation`()'
assert_payment_hardening_rejected \
  "payment-zero-service-disabled" \
  "PH-TEST-DISABLED" \
  "$payment_zero_service_disabled"

payment_zero_route_disabled="$(copy_payment_hardening_fixture payment-zero-route-disabled)"
replace_payment_text_once \
  "$payment_zero_route_disabled/app-bot/src/test/kotlin/com/example/bot/payments/PaymentsPersistenceTest.kt" \
  $'    @Test\n    fun `refund explicit zero production RBAC route replays stable public result without mutation`()' \
  $'    @Test\n    @org.junit.jupiter.api.Disabled\n    fun `refund explicit zero production RBAC route replays stable public result without mutation`()'
assert_payment_hardening_rejected \
  "payment-zero-route-disabled" \
  "PH-TEST-DISABLED" \
  "$payment_zero_route_disabled"

payment_zero_no_rbac="$(copy_payment_hardening_fixture payment-zero-no-rbac)"
replace_payment_text_once \
  "$payment_zero_no_rbac/app-bot/src/test/kotlin/com/example/bot/payments/PaymentsPersistenceTest.kt" \
  '"app.RBAC_ENABLED" to "true"' \
  '"app.RBAC_ENABLED" to "false"'
assert_payment_hardening_rejected \
  "payment-zero-no-rbac" \
  "PH-TEST-ZERO" \
  "$payment_zero_no_rbac"

payment_zero_no_principal="$(copy_payment_hardening_fixture payment-zero-no-production-principal)"
replace_payment_text_once \
  "$payment_zero_no_principal/app-bot/src/test/kotlin/com/example/bot/payments/PaymentsPersistenceTest.kt" \
  'call.request.headers["X-Telegram-Id"]' \
  'call.request.headers["X-Fixture-Bypass"]'
assert_payment_hardening_rejected \
  "payment-zero-no-production-principal" \
  "PH-TEST-ZERO" \
  "$payment_zero_no_principal"

payment_zero_missing_denial="$(copy_payment_hardening_fixture payment-zero-missing-denial)"
replace_payment_text_once \
  "$payment_zero_missing_denial/app-bot/src/test/kotlin/com/example/bot/payments/PaymentsPersistenceTest.kt" \
  'assertEquals(HttpStatusCode.Forbidden, denied.status)' \
  'assertEquals(HttpStatusCode.OK, denied.status)'
assert_payment_hardening_rejected \
  "payment-zero-missing-denial" \
  "PH-TEST-ZERO" \
  "$payment_zero_missing_denial"

payment_zero_test_missing="$(copy_payment_hardening_fixture payment-zero-test-missing)"
replace_payment_text_once \
  "$payment_zero_test_missing/app-bot/src/test/kotlin/com/example/bot/payments/PaymentsPersistenceTest.kt" \
  $'fun `refund explicit zero persists terminal success without mutation`()' \
  $'fun `disabled zero regression fixture`()'
assert_payment_hardening_rejected \
  "payment-zero-test-missing" \
  "PH-TEST-MISSING" \
  "$payment_zero_test_missing"

payment_test_raw_string_decoy="$(copy_payment_hardening_fixture payment-test-raw-string-decoy)"
replace_payment_text_once \
  "$payment_test_raw_string_decoy/app-bot/src/test/kotlin/com/example/bot/payments/PaymentsPersistenceTest.kt" \
  'fun `refund explicit zero persists terminal success without mutation`()' \
  'fun `removed explicit zero regression fixture`()'
cat >> "$payment_test_raw_string_decoy/app-bot/src/test/kotlin/com/example/bot/payments/PaymentsPersistenceTest.kt" <<'KOT'
private val explicitZeroTestDeclarationDecoy = """
    @Test
    fun `refund explicit zero persists terminal success without mutation`() {}
"""
KOT
assert_payment_hardening_rejected \
  "payment-test-raw-string-decoy" \
  "PH-TEST-MISSING" \
  "$payment_test_raw_string_decoy"

payment_test_nested_same_name="$(copy_payment_hardening_fixture payment-test-nested-same-name)"
replace_payment_text_once \
  "$payment_test_nested_same_name/app-bot/src/test/kotlin/com/example/bot/payments/PaymentsPersistenceTest.kt" \
  'fun `refund explicit zero persists terminal success without mutation`()' \
  'fun `renamed top level explicit zero fixture`()'
cat >> "$payment_test_nested_same_name/app-bot/src/test/kotlin/com/example/bot/payments/PaymentsPersistenceTest.kt" <<'KOT'
private class RequiredTestNameContainer {
    class PaymentsPersistenceTest {
        @Test
        fun `refund explicit zero persists terminal success without mutation`() = Unit
    }
}
KOT
assert_payment_hardening_rejected \
  "payment-test-nested-same-name" \
  "PH-TEST-MISSING" \
  "$payment_test_nested_same_name"

payment_test_other_package="$(copy_payment_hardening_fixture payment-test-other-package)"
replace_payment_text_once \
  "$payment_test_other_package/app-bot/src/test/kotlin/com/example/bot/payments/PaymentsPersistenceTest.kt" \
  'package com.example.bot.payments' \
  'package com.example.fixture.payments'
assert_payment_hardening_rejected \
  "payment-test-other-package" \
  "PH-TEST-MISSING" \
  "$payment_test_other_package"

payment_zero_no_error_pages="$(copy_payment_hardening_fixture payment-zero-no-json-error-pages)"
replace_payment_text_once \
  "$payment_zero_no_error_pages/app-bot/src/test/kotlin/com/example/bot/payments/PaymentsPersistenceTest.kt" \
  '                    installJsonErrorPages()' \
  '                    // installJsonErrorPages removed by negative fixture'
assert_payment_hardening_rejected \
  "payment-zero-no-json-error-pages" \
  "PH-TEST-ZERO" \
  "$payment_zero_no_error_pages"

payment_zero_weak_forbidden="$(copy_payment_hardening_fixture payment-zero-weak-forbidden-envelope)"
replace_payment_text_once \
  "$payment_zero_weak_forbidden/app-bot/src/test/kotlin/com/example/bot/payments/PaymentsPersistenceTest.kt" \
  '                assertEquals(ErrorCodes.forbidden, deniedBody.code)' \
  '                assertTrue(denied.bodyAsText().contains("forbidden"))'
assert_payment_hardening_rejected \
  "payment-zero-weak-forbidden-envelope" \
  "PH-TEST-ZERO" \
  "$payment_zero_weak_forbidden"

payment_test_result_zero="$TMP_DIR/payment-test-result-zero.xml"
cat > "$payment_test_result_zero" <<'XML'
<testsuite name="com.example.bot.logging.SensitiveIdempotencyLoggingTest" tests="0" skipped="0" failures="0" errors="0" />
XML
assert_validation_rejected \
  "payment-test-result-zero" \
  python3 "$payment_hardening_validator" --verify-junit-xml \
    "$payment_test_result_zero" \
    "$sensitive_logging_suite" \
    "0" \
    "booking finalize route never serializes raw idempotency key"
assert_contains "$(cat "$TMP_DIR/payment-test-result-zero.log")" "[PH-TEST-RESULT]"

payment_test_result_skipped="$TMP_DIR/payment-test-result-skipped.xml"
cat > "$payment_test_result_skipped" <<'XML'
<testsuite name="com.example.bot.logging.SensitiveIdempotencyLoggingTest" tests="1" skipped="1" failures="0" errors="0">
  <testcase classname="com.example.bot.logging.SensitiveIdempotencyLoggingTest" name="booking finalize route never serializes raw idempotency key()"><skipped /></testcase>
</testsuite>
XML
assert_validation_rejected \
  "payment-test-result-skipped" \
  python3 "$payment_hardening_validator" --verify-junit-xml \
    "$payment_test_result_skipped" \
    "$sensitive_logging_suite" \
    "0" \
    "booking finalize route never serializes raw idempotency key"
assert_contains "$(cat "$TMP_DIR/payment-test-result-skipped.log")" "[PH-TEST-RESULT]"

payment_test_result_missing="$TMP_DIR/payment-test-result-missing.xml"
assert_validation_rejected \
  "payment-test-result-missing" \
  python3 "$payment_hardening_validator" --verify-junit-xml \
    "$payment_test_result_missing" \
    "$sensitive_logging_suite" \
    "0" \
    "booking finalize route never serializes raw idempotency key"
assert_contains "$(cat "$TMP_DIR/payment-test-result-missing.log")" "[PH-TEST-RESULT]"

payment_test_result_stale="$TMP_DIR/payment-test-result-stale.xml"
cat > "$payment_test_result_stale" <<'XML'
<testsuite name="com.example.bot.logging.SensitiveIdempotencyLoggingTest" tests="1" skipped="0" failures="0" errors="0">
  <testcase classname="com.example.bot.logging.SensitiveIdempotencyLoggingTest" name="booking finalize route never serializes raw idempotency key()" />
</testsuite>
XML
payment_stale_after="$(python3 - "$payment_test_result_stale" <<'PY'
from pathlib import Path
import sys
print(Path(sys.argv[1]).stat().st_mtime_ns + 1)
PY
)"
assert_validation_rejected \
  "payment-test-result-stale" \
  python3 "$payment_hardening_validator" --verify-junit-xml \
    "$payment_test_result_stale" \
    "$sensitive_logging_suite" \
    "$payment_stale_after" \
    "booking finalize route never serializes raw idempotency key"
assert_contains "$(cat "$TMP_DIR/payment-test-result-stale.log")" "[PH-TEST-RESULT]"

payment_test_result_equal_mtime="$TMP_DIR/payment-test-result-equal-mtime.xml"
cat > "$payment_test_result_equal_mtime" <<'XML'
<testsuite name="com.example.bot.logging.SensitiveIdempotencyLoggingTest" tests="1" skipped="0" failures="0" errors="0">
  <testcase classname="com.example.bot.logging.SensitiveIdempotencyLoggingTest" name="booking finalize route never serializes raw idempotency key()" />
</testsuite>
XML
payment_equal_mtime="$(python3 - "$payment_test_result_equal_mtime" <<'PY'
from pathlib import Path
import sys
print(Path(sys.argv[1]).lstat().st_mtime_ns)
PY
)"
assert_validation_rejected \
  "payment-test-result-equal-mtime" \
  python3 "$payment_hardening_validator" --verify-junit-xml \
    "$payment_test_result_equal_mtime" \
    "$sensitive_logging_suite" \
    "$payment_equal_mtime" \
    "booking finalize route never serializes raw idempotency key"
assert_contains "$(cat "$TMP_DIR/payment-test-result-equal-mtime.log")" "[PH-TEST-RESULT]"

payment_test_result_failure="$TMP_DIR/payment-test-result-failure.xml"
cat > "$payment_test_result_failure" <<'XML'
<testsuite name="com.example.bot.logging.SensitiveIdempotencyLoggingTest" tests="1" skipped="0" failures="1" errors="0">
  <testcase classname="com.example.bot.logging.SensitiveIdempotencyLoggingTest" name="booking finalize route never serializes raw idempotency key()"><failure /></testcase>
</testsuite>
XML
assert_validation_rejected \
  "payment-test-result-failure" \
  python3 "$payment_hardening_validator" --verify-junit-xml \
    "$payment_test_result_failure" \
    "$sensitive_logging_suite" \
    "0" \
    "booking finalize route never serializes raw idempotency key"
assert_contains "$(cat "$TMP_DIR/payment-test-result-failure.log")" "[PH-TEST-RESULT]"

payment_test_result_error="$TMP_DIR/payment-test-result-error.xml"
cat > "$payment_test_result_error" <<'XML'
<testsuite name="com.example.bot.logging.SensitiveIdempotencyLoggingTest" tests="1" skipped="0" failures="0" errors="1">
  <testcase classname="com.example.bot.logging.SensitiveIdempotencyLoggingTest" name="booking finalize route never serializes raw idempotency key()"><error /></testcase>
</testsuite>
XML
assert_validation_rejected \
  "payment-test-result-error" \
  python3 "$payment_hardening_validator" --verify-junit-xml \
    "$payment_test_result_error" \
    "$sensitive_logging_suite" \
    "0" \
    "booking finalize route never serializes raw idempotency key"
assert_contains "$(cat "$TMP_DIR/payment-test-result-error.log")" "[PH-TEST-RESULT]"

payment_test_result_wrong_case="$TMP_DIR/payment-test-result-wrong-case.xml"
cat > "$payment_test_result_wrong_case" <<'XML'
<testsuite name="com.example.bot.logging.SensitiveIdempotencyLoggingTest" tests="1" skipped="0" failures="0" errors="0">
  <testcase classname="com.example.bot.logging.SensitiveIdempotencyLoggingTest" name="unrelated test()" />
</testsuite>
XML
assert_validation_rejected \
  "payment-test-result-wrong-case" \
  python3 "$payment_hardening_validator" --verify-junit-xml \
    "$payment_test_result_wrong_case" \
    "$sensitive_logging_suite" \
    "0" \
    "booking finalize route never serializes raw idempotency key"
assert_contains "$(cat "$TMP_DIR/payment-test-result-wrong-case.log")" "[PH-TEST-RESULT]"

payment_testcontainers_old_version="$(copy_payment_hardening_fixture payment-testcontainers-old-version)"
replace_payment_text_once \
  "$payment_testcontainers_old_version/gradle/libs.versions.toml" \
  'testcontainers= "1.21.4"' \
  'testcontainers= "1.19.7"'
assert_payment_hardening_rejected \
  "payment-testcontainers-old-version" \
  "PH-TESTCONTAINERS" \
  "$payment_testcontainers_old_version"

payment_testcontainers_mixed_module="$(copy_payment_hardening_fixture payment-testcontainers-mixed-module)"
replace_payment_text_once \
  "$payment_testcontainers_mixed_module/gradle/libs.versions.toml" \
  'testcontainers-postgresql = { module = "org.testcontainers:postgresql",       version.ref = "testcontainers" }' \
  'testcontainers-postgresql = { module = "org.testcontainers:postgresql",       version = "1.19.7" }'
assert_payment_hardening_rejected \
  "payment-testcontainers-mixed-module" \
  "PH-TESTCONTAINERS" \
  "$payment_testcontainers_mixed_module"

payment_fake_pass_helper="$(copy_payment_hardening_fixture payment-runtime-helper-fake-pass)"
cat > "$payment_fake_pass_helper/scripts/verify-payment-hardening-runtime.sh" <<'SH'
#!/usr/bin/env bash
echo PASS
exit 0
SH
chmod +x "$payment_fake_pass_helper/scripts/verify-payment-hardening-runtime.sh"
assert_payment_hardening_rejected \
  "payment-runtime-helper-fake-pass" \
  "PH-RUNTIME-CONTRACT" \
  "$payment_fake_pass_helper"

payment_structural_output="$(python3 "$payment_hardening_validator" --structural "$ROOT_DIR")"
assert_contains "$payment_structural_output" "payment-hardening-mode: STRUCTURAL_ONLY_NON_AUTHORITATIVE"
assert_not_contains "$payment_structural_output" "payment-hardening-runtime: PASS"

payment_runtime_fixture_base="$TMP_DIR/payment-runtime-base"
mkdir -p \
  "$payment_runtime_fixture_base/scripts" \
  "$payment_runtime_fixture_base/app-bot/src/test/kotlin/com/example/bot/logging" \
  "$payment_runtime_fixture_base/app-bot/src/test/kotlin/com/example/bot/payments"
cp "$payment_hardening_validator" "$payment_runtime_fixture_base/scripts/validate-payment-hardening.py"
cp "$payment_hardening_runtime" "$payment_runtime_fixture_base/scripts/verify-payment-hardening-runtime.sh"
cp "$ROOT_DIR/app-bot/src/test/kotlin/com/example/bot/logging/SensitiveIdempotencyLoggingTest.kt" \
  "$payment_runtime_fixture_base/app-bot/src/test/kotlin/com/example/bot/logging/"
cp "$ROOT_DIR/app-bot/src/test/kotlin/com/example/bot/logging/SqlThrowableLoggingPersistenceTest.kt" \
  "$payment_runtime_fixture_base/app-bot/src/test/kotlin/com/example/bot/logging/"
cp "$ROOT_DIR/app-bot/src/test/kotlin/com/example/bot/payments/PaymentsPersistenceTest.kt" \
  "$payment_runtime_fixture_base/app-bot/src/test/kotlin/com/example/bot/payments/"
cat > "$payment_runtime_fixture_base/gradlew" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$@" > "$PWD/runtime-args.txt"
case "${FAKE_RUNTIME_CASE:-pass}" in
  exit-nonzero)
    exit 23
    ;;
  no-xml)
    exit 0
    ;;
esac

results="$PWD/app-bot/build/test-results/test"
mkdir -p "$results"

cat > "$results/TEST-com.example.bot.logging.SensitiveIdempotencyLoggingTest.xml" <<'XML'
<testsuite name="com.example.bot.logging.SensitiveIdempotencyLoggingTest" tests="6" skipped="0" failures="0" errors="0">
  <testcase classname="com.example.bot.logging.SensitiveIdempotencyLoggingTest" name="booking finalize route never serializes raw idempotency key()" />
  <testcase classname="com.example.bot.logging.SensitiveIdempotencyLoggingTest" name="booking template service never serializes generated idempotency key()" />
  <testcase classname="com.example.bot.logging.SensitiveIdempotencyLoggingTest" name="rbac audit fingerprint does not expose raw key to json logs()" />
  <testcase classname="com.example.bot.logging.SensitiveIdempotencyLoggingTest" name="webhook keeps business key but never serializes it through mdc()" />
  <testcase classname="com.example.bot.logging.SensitiveIdempotencyLoggingTest" name="payments finalize logs presence only for long and short keys()" />
  <testcase classname="com.example.bot.logging.SensitiveIdempotencyLoggingTest" name="db transaction logs never serialize sql exception detail()" />
</testsuite>
XML

sql_tests="1"
sql_skipped="0"
sql_failures="0"
sql_errors="0"
sql_case='<testcase classname="com.example.bot.logging.SqlThrowableLoggingPersistenceTest" name="postgres sql throwable never reaches payment route status pages or json logs()" />'
case "${FAKE_RUNTIME_CASE:-pass}" in
  skipped)
    sql_skipped="1"
    sql_case='<testcase classname="com.example.bot.logging.SqlThrowableLoggingPersistenceTest" name="postgres sql throwable never reaches payment route status pages or json logs()"><skipped /></testcase>'
    ;;
  failure)
    sql_failures="1"
    sql_case='<testcase classname="com.example.bot.logging.SqlThrowableLoggingPersistenceTest" name="postgres sql throwable never reaches payment route status pages or json logs()"><failure /></testcase>'
    ;;
  error)
    sql_errors="1"
    sql_case='<testcase classname="com.example.bot.logging.SqlThrowableLoggingPersistenceTest" name="postgres sql throwable never reaches payment route status pages or json logs()"><error /></testcase>'
    ;;
  zero)
    sql_tests="0"
    sql_case=""
    ;;
esac
cat > "$results/TEST-com.example.bot.logging.SqlThrowableLoggingPersistenceTest.xml" <<XML
<testsuite name="com.example.bot.logging.SqlThrowableLoggingPersistenceTest" tests="$sql_tests" skipped="$sql_skipped" failures="$sql_failures" errors="$sql_errors">
  $sql_case
</testsuite>
XML

first_payment='refund explicit zero persists terminal success without mutation'
if [ "${FAKE_RUNTIME_CASE:-pass}" = "missing-explicit-zero" ]; then
  first_payment='unrelated payment testcase'
fi
cat > "$results/TEST-com.example.bot.payments.PaymentsPersistenceTest.xml" <<XML
<testsuite name="com.example.bot.payments.PaymentsPersistenceTest" tests="2" skipped="0" failures="0" errors="0">
  <testcase classname="com.example.bot.payments.PaymentsPersistenceTest" name="$first_payment()" />
  <testcase classname="com.example.bot.payments.PaymentsPersistenceTest" name="refund explicit zero production RBAC route replays stable public result without mutation()" />
</testsuite>
XML

case "${FAKE_RUNTIME_CASE:-pass}" in
  missing-xml)
    rm "$results/TEST-com.example.bot.logging.SqlThrowableLoggingPersistenceTest.xml"
    ;;
  stale)
    touch -t 200001010000 "$results/TEST-com.example.bot.logging.SqlThrowableLoggingPersistenceTest.xml"
    ;;
esac
SH
chmod +x \
  "$payment_runtime_fixture_base/gradlew" \
  "$payment_runtime_fixture_base/scripts/verify-payment-hardening-runtime.sh"

copy_payment_runtime_fixture() {
  local fixture_name="$1"
  local fixture_root="$TMP_DIR/$fixture_name"
  cp -R "$payment_runtime_fixture_base" "$fixture_root"
  printf '%s' "$fixture_root"
}

assert_required_runtime_rejected() {
  local fixture_name="$1"
  local fixture_case="$2"
  local fixture_root="$3"
  local fixture_log="$TMP_DIR/$fixture_name.log"
  local fixture_status

  if FAKE_RUNTIME_CASE="$fixture_case" python3 \
    "$fixture_root/scripts/validate-payment-hardening.py" \
    --run-required-runtime \
    "$fixture_root" >"$fixture_log" 2>&1; then
    fail "required payment runtime negative fixture unexpectedly passed: $fixture_name"
  else
    fixture_status=$?
  fi
  assert_eq "$fixture_status" "1"
  assert_contains "$(cat "$fixture_log")" "payment-hardening-contract: [PH-"
  echo "quality-gate: required payment runtime fixture $fixture_name rejected"
}

payment_runtime_positive="$(copy_payment_runtime_fixture payment-runtime-positive)"
payment_runtime_positive_output="$(FAKE_RUNTIME_CASE=pass "$payment_runtime_positive/scripts/verify-payment-hardening-runtime.sh")"
assert_contains "$payment_runtime_positive_output" "payment-hardening-runtime: PASS"
assert_eq "$(grep -c -x -- '--tests' "$payment_runtime_positive/runtime-args.txt")" "9"
assert_eq "$(wc -l < "$payment_runtime_positive/runtime-args.txt" | tr -d ' ')" "24"
required_runtime_filters=(
  "com.example.bot.logging.SensitiveIdempotencyLoggingTest.booking finalize route never serializes raw idempotency key"
  "com.example.bot.logging.SensitiveIdempotencyLoggingTest.booking template service never serializes generated idempotency key"
  "com.example.bot.logging.SensitiveIdempotencyLoggingTest.rbac audit fingerprint does not expose raw key to json logs"
  "com.example.bot.logging.SensitiveIdempotencyLoggingTest.webhook keeps business key but never serializes it through mdc"
  "com.example.bot.logging.SensitiveIdempotencyLoggingTest.payments finalize logs presence only for long and short keys"
  "com.example.bot.logging.SensitiveIdempotencyLoggingTest.db transaction logs never serialize sql exception detail"
  "com.example.bot.logging.SqlThrowableLoggingPersistenceTest.postgres sql throwable never reaches payment route status pages or json logs"
  "com.example.bot.payments.PaymentsPersistenceTest.refund explicit zero persists terminal success without mutation"
  "com.example.bot.payments.PaymentsPersistenceTest.refund explicit zero production RBAC route replays stable public result without mutation"
)
for required_filter in "${required_runtime_filters[@]}"; do
  assert_eq "$(grep -F -c -x -- "$required_filter" "$payment_runtime_positive/runtime-args.txt")" "1"
done
for required_argument in \
  ':app-bot:test' \
  '-PrunIT=true' \
  '--rerun-tasks' \
  '--no-build-cache' \
  '--no-configuration-cache' \
  '--console=plain'; do
  assert_eq "$(grep -F -c -x -- "$required_argument" "$payment_runtime_positive/runtime-args.txt")" "1"
done

payment_runtime_not_executable="$(copy_payment_runtime_fixture payment-runtime-gradlew-not-executable)"
chmod -x "$payment_runtime_not_executable/gradlew"
assert_required_runtime_rejected \
  "payment-runtime-gradlew-not-executable" \
  "pass" \
  "$payment_runtime_not_executable"
if [ -e "$payment_runtime_not_executable/runtime-args.txt" ]; then
  fail "non-executable fake Gradle was unexpectedly invoked"
fi

payment_runtime_exit="$(copy_payment_runtime_fixture payment-runtime-gradle-exit)"
assert_required_runtime_rejected "payment-runtime-gradle-exit" "exit-nonzero" "$payment_runtime_exit"

payment_runtime_short_manifest="$(copy_payment_runtime_fixture payment-runtime-short-manifest)"
python3 - "$payment_runtime_short_manifest/scripts/validate-payment-hardening.py" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
marker = "booking template service never serializes generated idempotency key"
position = text.index(marker)
start = text.rfind("    RequiredRuntimeTest(\n", 0, position)
end = text.index("\n    ),", position) + len("\n    ),")
if start < 0:
    raise SystemExit("required runtime manifest fixture source changed")
path.write_text(text[:start] + text[end:], encoding="utf-8")
PY
assert_required_runtime_rejected "payment-runtime-short-manifest" "pass" "$payment_runtime_short_manifest"

payment_runtime_no_xml="$(copy_payment_runtime_fixture payment-runtime-no-xml)"
assert_required_runtime_rejected "payment-runtime-no-xml" "no-xml" "$payment_runtime_no_xml"

payment_runtime_missing_zero="$(copy_payment_runtime_fixture payment-runtime-missing-explicit-zero)"
assert_required_runtime_rejected \
  "payment-runtime-missing-explicit-zero" \
  "missing-explicit-zero" \
  "$payment_runtime_missing_zero"

payment_runtime_missing_xml="$(copy_payment_runtime_fixture payment-runtime-missing-xml)"
assert_required_runtime_rejected "payment-runtime-missing-xml" "missing-xml" "$payment_runtime_missing_xml"

payment_runtime_stale="$(copy_payment_runtime_fixture payment-runtime-stale-xml)"
assert_required_runtime_rejected "payment-runtime-stale-xml" "stale" "$payment_runtime_stale"

payment_runtime_skipped="$(copy_payment_runtime_fixture payment-runtime-skipped)"
assert_required_runtime_rejected "payment-runtime-skipped" "skipped" "$payment_runtime_skipped"

payment_runtime_failure="$(copy_payment_runtime_fixture payment-runtime-failure)"
assert_required_runtime_rejected "payment-runtime-failure" "failure" "$payment_runtime_failure"

payment_runtime_error="$(copy_payment_runtime_fixture payment-runtime-error)"
assert_required_runtime_rejected "payment-runtime-error" "error" "$payment_runtime_error"

payment_runtime_zero="$(copy_payment_runtime_fixture payment-runtime-zero-tests)"
assert_required_runtime_rejected "payment-runtime-zero-tests" "zero" "$payment_runtime_zero"

echo "quality-gate: payment hardening contract verified"

quiesced_contract_validator="$ROOT_DIR/scripts/validate-quiesced-deployment.sh"
"$quiesced_contract_validator" "$ROOT_DIR"

quiesced_fixture_base="$TMP_DIR/quiesced-deployment-base"
mkdir -p \
  "$quiesced_fixture_base/.github/workflows" \
  "$quiesced_fixture_base/scripts/deploy" \
  "$quiesced_fixture_base/app-bot/src/main/dist/bin" \
  "$quiesced_fixture_base/app-bot/src/main/kotlin/com/example/bot/tools" \
  "$quiesced_fixture_base/app-bot/src/main/resources" \
  "$quiesced_fixture_base/app-bot/src/test/kotlin/com/example/bot/tools" \
  "$quiesced_fixture_base/core-data/src/main/kotlin/com/example/bot/data/db"
cp "$ROOT_DIR/.github/workflows/deploy-ssh.yml" "$quiesced_fixture_base/.github/workflows/"
cp "$ROOT_DIR/.github/workflows/db-migrate.yml" "$quiesced_fixture_base/.github/workflows/"
cp "$ROOT_DIR/scripts/deploy/quiesced-release.sh" "$quiesced_fixture_base/scripts/deploy/"
cp "$ROOT_DIR/scripts/deploy/remote-compose-release.sh" "$quiesced_fixture_base/scripts/deploy/"
cp "$ROOT_DIR/Dockerfile" "$quiesced_fixture_base/"
cp "$ROOT_DIR/docker-compose.yml" "$quiesced_fixture_base/"
cp "$ROOT_DIR/app-bot/build.gradle.kts" "$quiesced_fixture_base/app-bot/"
cp \
  "$ROOT_DIR/app-bot/src/main/dist/bin/app-bot-migrate" \
  "$quiesced_fixture_base/app-bot/src/main/dist/bin/"
cp \
  "$ROOT_DIR/app-bot/src/main/kotlin/com/example/bot/tools/QuiescedMigrateMain.kt" \
  "$quiesced_fixture_base/app-bot/src/main/kotlin/com/example/bot/tools/"
cp \
  "$ROOT_DIR/app-bot/src/main/resources/quiesced-migration-logback.xml" \
  "$quiesced_fixture_base/app-bot/src/main/resources/"
cp \
  "$ROOT_DIR/app-bot/src/test/kotlin/com/example/bot/tools/QuiescedMigrateMainTest.kt" \
  "$quiesced_fixture_base/app-bot/src/test/kotlin/com/example/bot/tools/"
cp \
  "$ROOT_DIR/core-data/src/main/kotlin/com/example/bot/data/db/DbConfig.kt" \
  "$quiesced_fixture_base/core-data/src/main/kotlin/com/example/bot/data/db/"

copy_quiesced_fixture() {
  local fixture_name="$1"
  local fixture_root="$TMP_DIR/$fixture_name"
  cp -R "$quiesced_fixture_base" "$fixture_root"
  printf '%s' "$fixture_root"
}

quiesced_migration_before_stop="$(copy_quiesced_fixture quiesced-migration-before-stop)"
python3 - "$quiesced_migration_before_stop/scripts/deploy/quiesced-release.sh" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
old = """  preflight_remote_release
  quiesce_remote_release
  assert_remote_app_absent
  run_database_migration
"""
new = """  run_database_migration
  preflight_remote_release
  quiesce_remote_release
  assert_remote_app_absent
"""
if text.count(old) != 1:
    raise SystemExit("migration-before-stop fixture source changed")
path.write_text(text.replace(old, new), encoding="utf-8")
PY
assert_validation_rejected \
  "quiesced-migration-before-stop" \
  "$quiesced_contract_validator" \
  "$quiesced_migration_before_stop"

quiesced_old_rollback="$(copy_quiesced_fixture quiesced-old-image-rollback)"
printf '%s\n' 'previous_tag="pre-v056"' >> \
  "$quiesced_old_rollback/scripts/deploy/quiesced-release.sh"
assert_validation_rejected \
  "quiesced-old-image-rollback" \
  "$quiesced_contract_validator" \
  "$quiesced_old_rollback"

quiesced_missing_stop_verification="$(copy_quiesced_fixture quiesced-missing-stop-verification)"
python3 - "$quiesced_missing_stop_verification/scripts/deploy/remote-compose-release.sh" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
old = """stop_and_remove_app() {
  compose_command stop --timeout 60 app
  compose_command rm -f app
  assert_app_absent
}
"""
new = """stop_and_remove_app() {
  compose_command stop --timeout 60 app
  compose_command rm -f app
}
"""
if text.count(old) != 1:
    raise SystemExit("stop-verification fixture source changed")
path.write_text(text.replace(old, new), encoding="utf-8")
PY
assert_validation_rejected \
  "quiesced-missing-stop-verification" \
  "$quiesced_contract_validator" \
  "$quiesced_missing_stop_verification"

quiesced_different_group="$(copy_quiesced_fixture quiesced-different-concurrency-group)"
replace_exact_line_once \
  "$quiesced_different_group/.github/workflows/db-migrate.yml" \
  "$quiesced_different_group/.github/workflows/db-migrate.changed.yml" \
  '  group: payments-schema-${{ github.event_name == '\''push'\'' && '\''prod'\'' || inputs.environment }}' \
  '  group: unsafe-standalone-migration'
mv \
  "$quiesced_different_group/.github/workflows/db-migrate.changed.yml" \
  "$quiesced_different_group/.github/workflows/db-migrate.yml"
assert_validation_rejected \
  "quiesced-different-concurrency-group" \
  "$quiesced_contract_validator" \
  "$quiesced_different_group"

quiesced_direct_migration="$(copy_quiesced_fixture quiesced-direct-db-migration)"
replace_exact_line_once \
  "$quiesced_direct_migration/.github/workflows/db-migrate.yml" \
  "$quiesced_direct_migration/.github/workflows/db-migrate.changed.yml" \
  '        run: scripts/deploy/quiesced-release.sh' \
  '        run: ./gradlew flywayMigrate --no-parallel --console=plain'
mv \
  "$quiesced_direct_migration/.github/workflows/db-migrate.changed.yml" \
  "$quiesced_direct_migration/.github/workflows/db-migrate.yml"
assert_validation_rejected \
  "quiesced-direct-db-migration" \
  "$quiesced_contract_validator" \
  "$quiesced_direct_migration"

quiesced_missing_revision="$(copy_quiesced_fixture quiesced-missing-revision-check)"
python3 - "$quiesced_missing_revision/scripts/deploy/remote-compose-release.sh" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
old = "org.opencontainers.image.revision"
if old not in text:
    raise SystemExit("revision fixture source changed")
path.write_text(text.replace(old, "untrusted.image.revision"), encoding="utf-8")
PY
assert_validation_rejected \
  "quiesced-missing-revision-check" \
  "$quiesced_contract_validator" \
  "$quiesced_missing_revision"

quiesced_missing_post_migration_check="$(copy_quiesced_fixture quiesced-missing-post-migration-check)"
python3 - "$quiesced_missing_post_migration_check/scripts/deploy/quiesced-release.sh" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
needle = "  assert_remote_app_absent\n"
first = text.find(needle, text.find("run_release()"))
second = text.find(needle, first + len(needle))
if first < 0 or second < 0:
    raise SystemExit("post-migration fixture source changed")
text = text[:second] + text[second + len(needle):]
path.write_text(text, encoding="utf-8")
PY
assert_validation_rejected \
  "quiesced-missing-post-migration-check" \
  "$quiesced_contract_validator" \
  "$quiesced_missing_post_migration_check"

quiesced_failure_cleanup="$(copy_quiesced_fixture quiesced-failure-releases-lock)"
python3 - "$quiesced_failure_cleanup/scripts/deploy/quiesced-release.sh" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
needle = '    echo "quiesced-release: failure is fail-closed; maintenance lock owner=$remote_owner is retained" >&2\n'
if text.count(needle) != 1:
    raise SystemExit("failure-cleanup fixture source changed")
text = text.replace(needle, needle + "    remote_command cleanup >/dev/null 2>&1 || true\n")
path.write_text(text, encoding="utf-8")
PY
assert_validation_rejected \
  "quiesced-failure-releases-lock" \
  "$quiesced_contract_validator" \
  "$quiesced_failure_cleanup"

quiesced_removes_durable_digest="$(copy_quiesced_fixture quiesced-removes-durable-digest)"
python3 - "$quiesced_removes_durable_digest/scripts/deploy/remote-compose-release.sh" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
needle = '    rm -f "$lock_dir/docker-compose.release.yml"\n'
if text.count(needle) != 1:
    raise SystemExit("durable-digest fixture source changed")
text = text.replace(needle, '    rm -f "$persistent_override"\n' + needle)
path.write_text(text, encoding="utf-8")
PY
assert_validation_rejected \
  "quiesced-removes-durable-digest" \
  "$quiesced_contract_validator" \
  "$quiesced_removes_durable_digest"

quiesced_late_digest_promotion="$(copy_quiesced_fixture quiesced-late-digest-promotion)"
python3 - "$quiesced_late_digest_promotion/scripts/deploy/remote-compose-release.sh" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
quiesce = """    promote_persistent_override
    stop_and_remove_app
"""
start = """  assert_app_absent
  printf '%s' "starting" >"$lock_dir/phase"
"""
if text.count(quiesce) != 1 or text.count(start) != 1:
    raise SystemExit("late-digest-promotion fixture source changed")
text = text.replace(quiesce, "    stop_and_remove_app\n")
text = text.replace(start, "  assert_app_absent\n  promote_persistent_override\n" + start.split("\n", 1)[1])
path.write_text(text, encoding="utf-8")
PY
assert_validation_rejected \
  "quiesced-late-digest-promotion" \
  "$quiesced_contract_validator" \
  "$quiesced_late_digest_promotion"

quiesced_confirmation_bypass="$(copy_quiesced_fixture quiesced-confirmation-bypass)"
replace_exact_line_once \
  "$quiesced_confirmation_bypass/.github/workflows/db-migrate.yml" \
  "$quiesced_confirmation_bypass/.github/workflows/db-migrate.changed.yml" \
  '        if: ${{ !inputs.confirm_quiesced_release }}' \
  '        if: false'
mv \
  "$quiesced_confirmation_bypass/.github/workflows/db-migrate.changed.yml" \
  "$quiesced_confirmation_bypass/.github/workflows/db-migrate.yml"
assert_validation_rejected \
  "quiesced-confirmation-bypass" \
  "$quiesced_contract_validator" \
  "$quiesced_confirmation_bypass"

quiesced_confirmation_no_exit="$(copy_quiesced_fixture quiesced-confirmation-no-exit)"
python3 - "$quiesced_confirmation_no_exit/.github/workflows/db-migrate.yml" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
guard = """          echo "A standalone migration is forbidden; confirm the full quiesced release." >&2
          exit 1
"""
if text.count(guard) != 1:
    raise SystemExit("confirmation-no-exit fixture source changed")
path.write_text(text.replace(guard, guard.splitlines(keepends=True)[0]), encoding="utf-8")
PY
assert_validation_rejected \
  "quiesced-confirmation-no-exit" \
  "$quiesced_contract_validator" \
  "$quiesced_confirmation_no_exit"

quiesced_confirmation_default="$(copy_quiesced_fixture quiesced-confirmation-default-true)"
replace_exact_line_once \
  "$quiesced_confirmation_default/.github/workflows/db-migrate.yml" \
  "$quiesced_confirmation_default/.github/workflows/db-migrate.changed.yml" \
  '        default: false' \
  '        default: true'
mv \
  "$quiesced_confirmation_default/.github/workflows/db-migrate.changed.yml" \
  "$quiesced_confirmation_default/.github/workflows/db-migrate.yml"
assert_validation_rejected \
  "quiesced-confirmation-default-true" \
  "$quiesced_contract_validator" \
  "$quiesced_confirmation_default"

quiesced_different_environment="$(copy_quiesced_fixture quiesced-different-environment)"
replace_exact_line_once \
  "$quiesced_different_environment/.github/workflows/db-migrate.yml" \
  "$quiesced_different_environment/.github/workflows/db-migrate.changed.yml" \
  '    environment: ${{ github.event_name == '\''push'\'' && '\''prod'\'' || inputs.environment }}' \
  '    environment: unsafe-standalone'
mv \
  "$quiesced_different_environment/.github/workflows/db-migrate.changed.yml" \
  "$quiesced_different_environment/.github/workflows/db-migrate.yml"
assert_validation_rejected \
  "quiesced-different-environment" \
  "$quiesced_contract_validator" \
  "$quiesced_different_environment"

quiesced_runner_checkout_migration="$(copy_quiesced_fixture quiesced-runner-checkout-migration)"
replace_exact_line_once \
  "$quiesced_runner_checkout_migration/scripts/deploy/quiesced-release.sh" \
  "$quiesced_runner_checkout_migration/scripts/deploy/quiesced-release.changed.sh" \
  '  remote_command migrate' \
  '  "$repository_root/gradlew" flywayMigrate --no-parallel --console=plain'
mv \
  "$quiesced_runner_checkout_migration/scripts/deploy/quiesced-release.changed.sh" \
  "$quiesced_runner_checkout_migration/scripts/deploy/quiesced-release.sh"
assert_validation_rejected \
  "quiesced-runner-checkout-migration" \
  "$quiesced_contract_validator" \
  "$quiesced_runner_checkout_migration"

quiesced_mutable_migration_image="$(copy_quiesced_fixture quiesced-mutable-migration-image)"
python3 - "$quiesced_mutable_migration_image/scripts/deploy/remote-compose-release.sh" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
start = text.index("migrate_verified_image() {")
old = '  digest="$(state_value image_digest)"\n'
position = text.find(old, start)
if position < 0:
    raise SystemExit("mutable-migration-image fixture source changed")
text = text[:position] + '  digest="nightconcierge/app-bot:latest"\n' + text[position + len(old):]
path.write_text(text, encoding="utf-8")
PY
assert_validation_rejected \
  "quiesced-mutable-migration-image" \
  "$quiesced_contract_validator" \
  "$quiesced_mutable_migration_image"

quiesced_different_final_digest="$(copy_quiesced_fixture quiesced-different-final-digest)"
replace_exact_line_once \
  "$quiesced_different_final_digest/scripts/deploy/remote-compose-release.sh" \
  "$quiesced_different_final_digest/scripts/deploy/remote-compose-release.changed.sh" \
  '  if [ "$migration_digest" != "$digest" ]; then' \
  '  if [ "$migration_digest" != "$migration_digest" ]; then'
mv \
  "$quiesced_different_final_digest/scripts/deploy/remote-compose-release.changed.sh" \
  "$quiesced_different_final_digest/scripts/deploy/remote-compose-release.sh"
assert_validation_rejected \
  "quiesced-different-final-digest" \
  "$quiesced_contract_validator" \
  "$quiesced_different_final_digest"

quiesced_checkout_bind_mount="$(copy_quiesced_fixture quiesced-checkout-bind-mount)"
replace_exact_line_once \
  "$quiesced_checkout_bind_mount/scripts/deploy/remote-compose-release.sh" \
  "$quiesced_checkout_bind_mount/scripts/deploy/remote-compose-release.changed.sh" \
  '      --no-deps \' \
  '      --volume "$PWD:/opt/app" --no-deps \'
mv \
  "$quiesced_checkout_bind_mount/scripts/deploy/remote-compose-release.changed.sh" \
  "$quiesced_checkout_bind_mount/scripts/deploy/remote-compose-release.sh"
assert_validation_rejected \
  "quiesced-checkout-bind-mount" \
  "$quiesced_contract_validator" \
  "$quiesced_checkout_bind_mount"

quiesced_early_migrated_phase="$(copy_quiesced_fixture quiesced-early-migrated-phase)"
python3 - "$quiesced_early_migrated_phase/scripts/deploy/remote-compose-release.sh" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
phase = '  printf \'%s\' "migrated" >"$lock_dir/phase"\n'
wait = '  migration_exit_code="$(docker wait "$migration_container_id")"\n'
if text.count(phase) != 1 or text.count(wait) != 1:
    raise SystemExit("early-migrated fixture source changed")
text = text.replace(phase, "")
text = text.replace(wait, phase + wait)
path.write_text(text, encoding="utf-8")
PY
assert_validation_rejected \
  "quiesced-early-migrated-phase" \
  "$quiesced_contract_validator" \
  "$quiesced_early_migrated_phase"

quiesced_ignored_migration_exit="$(copy_quiesced_fixture quiesced-ignored-migration-exit)"
replace_exact_line_once \
  "$quiesced_ignored_migration_exit/scripts/deploy/remote-compose-release.sh" \
  "$quiesced_ignored_migration_exit/scripts/deploy/remote-compose-release.changed.sh" \
  '  if [ "$migration_exit_code" != "0" ]; then' \
  '  if false; then'
mv \
  "$quiesced_ignored_migration_exit/scripts/deploy/remote-compose-release.changed.sh" \
  "$quiesced_ignored_migration_exit/scripts/deploy/remote-compose-release.sh"
assert_validation_rejected \
  "quiesced-ignored-migration-exit" \
  "$quiesced_contract_validator" \
  "$quiesced_ignored_migration_exit"

quiesced_start_after_failure="$(copy_quiesced_fixture quiesced-start-after-migration-failure)"
replace_exact_line_once \
  "$quiesced_start_after_failure/scripts/deploy/quiesced-release.sh" \
  "$quiesced_start_after_failure/scripts/deploy/quiesced-release.changed.sh" \
  '  remote_command migrate' \
  '  remote_command migrate || true'
mv \
  "$quiesced_start_after_failure/scripts/deploy/quiesced-release.changed.sh" \
  "$quiesced_start_after_failure/scripts/deploy/quiesced-release.sh"
assert_validation_rejected \
  "quiesced-start-after-migration-failure" \
  "$quiesced_contract_validator" \
  "$quiesced_start_after_failure"

quiesced_full_app_migration="$(copy_quiesced_fixture quiesced-full-app-migration)"
replace_exact_line_once \
  "$quiesced_full_app_migration/scripts/deploy/remote-compose-release.sh" \
  "$quiesced_full_app_migration/scripts/deploy/remote-compose-release.changed.sh" \
  '      --entrypoint /opt/app/bin/app-bot-migrate \' \
  '      --entrypoint /opt/app/bin/app-bot \'
mv \
  "$quiesced_full_app_migration/scripts/deploy/remote-compose-release.changed.sh" \
  "$quiesced_full_app_migration/scripts/deploy/remote-compose-release.sh"
assert_validation_rejected \
  "quiesced-full-app-migration" \
  "$quiesced_contract_validator" \
  "$quiesced_full_app_migration"

quiesced_private_launcher_bypass="$(copy_quiesced_fixture quiesced-private-launcher-bypass)"
replace_exact_line_once \
  "$quiesced_private_launcher_bypass/scripts/deploy/remote-compose-release.sh" \
  "$quiesced_private_launcher_bypass/scripts/deploy/remote-compose-release.changed.sh" \
  '      --entrypoint /opt/app/bin/app-bot-migrate \' \
  '      --entrypoint /opt/app/bin/app-bot-migrate-java \'
mv \
  "$quiesced_private_launcher_bypass/scripts/deploy/remote-compose-release.changed.sh" \
  "$quiesced_private_launcher_bypass/scripts/deploy/remote-compose-release.sh"
assert_validation_rejected \
  "quiesced-private-launcher-bypass" \
  "$quiesced_contract_validator" \
  "$quiesced_private_launcher_bypass"

quiesced_boundary_keeps_java_opts="$(copy_quiesced_fixture quiesced-boundary-keeps-java-opts)"
replace_exact_line_once \
  "$quiesced_boundary_keeps_java_opts/app-bot/src/main/dist/bin/app-bot-migrate" \
  "$quiesced_boundary_keeps_java_opts/app-bot/src/main/dist/bin/app-bot-migrate.changed" \
  'unset JAVA_TOOL_OPTIONS' \
  ': JAVA_TOOL_OPTIONS is intentionally retained'
mv \
  "$quiesced_boundary_keeps_java_opts/app-bot/src/main/dist/bin/app-bot-migrate.changed" \
  "$quiesced_boundary_keeps_java_opts/app-bot/src/main/dist/bin/app-bot-migrate"
assert_validation_rejected \
  "quiesced-boundary-keeps-java-opts" \
  "$quiesced_contract_validator" \
  "$quiesced_boundary_keeps_java_opts"

quiesced_boundary_forwards_args="$(copy_quiesced_fixture quiesced-boundary-forwards-args)"
replace_exact_line_once \
  "$quiesced_boundary_forwards_args/app-bot/src/main/dist/bin/app-bot-migrate" \
  "$quiesced_boundary_forwards_args/app-bot/src/main/dist/bin/app-bot-migrate.changed" \
  'exec "$private_launcher"' \
  'exec "$private_launcher" "$@"'
mv \
  "$quiesced_boundary_forwards_args/app-bot/src/main/dist/bin/app-bot-migrate.changed" \
  "$quiesced_boundary_forwards_args/app-bot/src/main/dist/bin/app-bot-migrate"
assert_validation_rejected \
  "quiesced-boundary-forwards-args" \
  "$quiesced_contract_validator" \
  "$quiesced_boundary_forwards_args"

quiesced_remote_empty_java_opts="$(copy_quiesced_fixture quiesced-remote-empty-java-opts)"
replace_exact_line_once \
  "$quiesced_remote_empty_java_opts/scripts/deploy/remote-compose-release.sh" \
  "$quiesced_remote_empty_java_opts/scripts/deploy/remote-compose-release.changed.sh" \
  '      -e QUIESCED_RELEASE_MIGRATION=required \' \
  '      -e QUIESCED_RELEASE_MIGRATION=required -e JAVA_TOOL_OPTIONS= \'
mv \
  "$quiesced_remote_empty_java_opts/scripts/deploy/remote-compose-release.changed.sh" \
  "$quiesced_remote_empty_java_opts/scripts/deploy/remote-compose-release.sh"
assert_validation_rejected \
  "quiesced-remote-empty-java-opts" \
  "$quiesced_contract_validator" \
  "$quiesced_remote_empty_java_opts"

quiesced_missing_image_entrypoint="$(copy_quiesced_fixture quiesced-missing-image-entrypoint)"
replace_exact_line_once \
  "$quiesced_missing_image_entrypoint/Dockerfile" \
  "$quiesced_missing_image_entrypoint/Dockerfile.changed" \
  ' && test -x /opt/app/bin/app-bot-migrate \' \
  ' && test -x /opt/app/bin/app-bot \'
mv "$quiesced_missing_image_entrypoint/Dockerfile.changed" "$quiesced_missing_image_entrypoint/Dockerfile"
assert_validation_rejected \
  "quiesced-missing-image-entrypoint" \
  "$quiesced_contract_validator" \
  "$quiesced_missing_image_entrypoint"

quiesced_raw_migration_logs="$(copy_quiesced_fixture quiesced-raw-migration-logs)"
replace_exact_line_once \
  "$quiesced_raw_migration_logs/scripts/deploy/remote-compose-release.sh" \
  "$quiesced_raw_migration_logs/scripts/deploy/remote-compose-release.changed.sh" \
  '  capture_and_forward_safe_migration_diagnostics "$migration_container_id" "$migration_exit_code"' \
  '  docker logs "$migration_container_id" >&2'
mv \
  "$quiesced_raw_migration_logs/scripts/deploy/remote-compose-release.changed.sh" \
  "$quiesced_raw_migration_logs/scripts/deploy/remote-compose-release.sh"
assert_validation_rejected \
  "quiesced-raw-migration-logs" \
  "$quiesced_contract_validator" \
  "$quiesced_raw_migration_logs"

quiesced_unanchored_safe_filter="$(copy_quiesced_fixture quiesced-unanchored-safe-filter)"
replace_payment_text_once \
  "$quiesced_unanchored_safe_filter/scripts/deploy/remote-compose-release.sh" \
  '^migration-safe:v=1\ event=completed\ applied=(0|[1-9][0-9]{0,9})$' \
  '^migration-safe:v=1\ event=completed\ applied=(.+)'
assert_validation_rejected \
  "quiesced-unanchored-safe-filter" \
  "$quiesced_contract_validator" \
  "$quiesced_unanchored_safe_filter"

quiesced_forwards_unmatched_line="$(copy_quiesced_fixture quiesced-forwards-unmatched-line)"
replace_exact_line_once \
  "$quiesced_forwards_unmatched_line/scripts/deploy/remote-compose-release.sh" \
  "$quiesced_forwards_unmatched_line/scripts/deploy/remote-compose-release.changed.sh" \
  '  printf '\''%s\n'\'' "migration-safe:v=1 event=started" >&2' \
  '  printf '\''%s\n'\'' "$line" >&2'
mv \
  "$quiesced_forwards_unmatched_line/scripts/deploy/remote-compose-release.changed.sh" \
  "$quiesced_forwards_unmatched_line/scripts/deploy/remote-compose-release.sh"
assert_validation_rejected \
  "quiesced-forwards-unmatched-line" \
  "$quiesced_contract_validator" \
  "$quiesced_forwards_unmatched_line"

quiesced_rethrows_migration_failure="$(copy_quiesced_fixture quiesced-rethrows-migration-failure)"
replace_payment_text_once \
  "$quiesced_rethrows_migration_failure/app-bot/src/main/kotlin/com/example/bot/tools/QuiescedMigrateMain.kt" \
  '        eventSink(MigrationSafeEvent.Failed(phase, classifyFailure(phase, failure)))
        EXIT_FAILURE' \
  '        eventSink(MigrationSafeEvent.Failed(phase, classifyFailure(phase, failure)))
        throw failure'
assert_validation_rejected \
  "quiesced-rethrows-migration-failure" \
  "$quiesced_contract_validator" \
  "$quiesced_rethrows_migration_failure"

quiesced_logs_migration_message="$(copy_quiesced_fixture quiesced-logs-migration-message)"
replace_payment_text_once \
  "$quiesced_logs_migration_message/app-bot/src/main/kotlin/com/example/bot/tools/QuiescedMigrateMain.kt" \
  '        eventSink(MigrationSafeEvent.Failed(phase, classifyFailure(phase, failure)))' \
  '        System.err.println(failure.message)
        eventSink(MigrationSafeEvent.Failed(phase, classifyFailure(phase, failure)))'
assert_validation_rejected \
  "quiesced-logs-migration-message" \
  "$quiesced_contract_validator" \
  "$quiesced_logs_migration_message"

quiesced_logs_migration_throwable="$(copy_quiesced_fixture quiesced-logs-migration-throwable)"
replace_payment_text_once \
  "$quiesced_logs_migration_throwable/app-bot/src/main/kotlin/com/example/bot/tools/QuiescedMigrateMain.kt" \
  '        eventSink(MigrationSafeEvent.Failed(phase, classifyFailure(phase, failure)))' \
  '        val unsafeLogger = LoggerFactory.getLogger("UnsafeMigration")
        unsafeLogger.error("migration failed", failure)
        eventSink(MigrationSafeEvent.Failed(phase, classifyFailure(phase, failure)))'
assert_validation_rejected \
  "quiesced-logs-migration-throwable" \
  "$quiesced_contract_validator" \
  "$quiesced_logs_migration_throwable"

quiesced_missing_log_config="$(copy_quiesced_fixture quiesced-missing-migration-log-config)"
rm "$quiesced_missing_log_config/app-bot/src/main/resources/quiesced-migration-logback.xml"
assert_validation_rejected \
  "quiesced-missing-migration-log-config" \
  "$quiesced_contract_validator" \
  "$quiesced_missing_log_config"

quiesced_flyway_logging="$(copy_quiesced_fixture quiesced-flyway-logging-enabled)"
replace_payment_text_once \
  "$quiesced_flyway_logging/app-bot/src/main/resources/quiesced-migration-logback.xml" \
  '<logger name="org.flywaydb" level="OFF" additivity="false" />' \
  '<logger name="org.flywaydb" level="INFO" additivity="false" />'
assert_validation_rejected \
  "quiesced-flyway-logging-enabled" \
  "$quiesced_contract_validator" \
  "$quiesced_flyway_logging"

quiesced_main_uses_migration_logging="$(copy_quiesced_fixture quiesced-main-uses-migration-logging)"
replace_payment_text_once \
  "$quiesced_main_uses_migration_logging/app-bot/build.gradle.kts" \
  '            "-XX:+ExitOnOutOfMemoryError",' \
  '            "-XX:+ExitOnOutOfMemoryError",
            "-Dlogback.configurationFile=$quiescedMigrationLogConfig",'
assert_validation_rejected \
  "quiesced-main-uses-migration-logging" \
  "$quiesced_contract_validator" \
  "$quiesced_main_uses_migration_logging"

quiesced_failure_becomes_success="$(copy_quiesced_fixture quiesced-migration-failure-exit-zero)"
replace_payment_text_once \
  "$quiesced_failure_becomes_success/app-bot/src/main/kotlin/com/example/bot/tools/QuiescedMigrateMain.kt" \
  'private const val EXIT_FAILURE = 1' \
  'private const val EXIT_FAILURE = 0'
assert_validation_rejected \
  "quiesced-migration-failure-exit-zero" \
  "$quiesced_contract_validator" \
  "$quiesced_failure_becomes_success"

quiesced_early_completed_event="$(copy_quiesced_fixture quiesced-early-completed-event)"
python3 - "$quiesced_early_completed_event/app-bot/src/main/kotlin/com/example/bot/tools/QuiescedMigrateMain.kt" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
run = "        val result = migration(::advance)\n"
complete = "        eventSink(MigrationSafeEvent.Completed(result.migrationsExecuted))\n"
if text.count(run) != 1 or text.count(complete) != 1:
    raise SystemExit("early-completed-event fixture source changed")
text = text.replace(
    run + complete,
    "        val result = QuiescedMigrationResult(0)\n" + complete + "        migration(::advance)\n",
)
path.write_text(text, encoding="utf-8")
PY
assert_validation_rejected \
  "quiesced-early-completed-event" \
  "$quiesced_contract_validator" \
  "$quiesced_early_completed_event"

safe_filter_harness="$TMP_DIR/quiesced-safe-log-filter.sh"
{
  printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail'
  awk '
    /^emit_safe_migration_diagnostics\(\) \{/ { inside = 1 }
    inside { print }
    inside && /^}/ { exit }
  ' "$ROOT_DIR/scripts/deploy/remote-compose-release.sh"
  printf '%s\n' 'emit_safe_migration_diagnostics "$1" "$2"'
} >"$safe_filter_harness"
chmod +x "$safe_filter_harness"

assert_safe_filter_accepts() {
  local fixture_name="$1"
  local migration_exit="$2"
  local input_file="$3"
  local output_file="$TMP_DIR/${fixture_name}.output"

  "$safe_filter_harness" "$input_file" "$migration_exit" >"$output_file" 2>&1 ||
    fail "strict migration parser rejected canonical fixture: $fixture_name"
  cmp -s "$input_file" "$output_file" ||
    fail "strict migration parser did not reconstruct the canonical fixture: $fixture_name"
}

safe_filter_success_zero="$TMP_DIR/quiesced-safe-filter-success-zero.log"
printf '%s\n' \
  'migration-safe:v=1 event=started' \
  'migration-safe:v=1 event=completed applied=0' >"$safe_filter_success_zero"
assert_safe_filter_accepts "safe-filter-success-zero" 0 "$safe_filter_success_zero"

safe_filter_success_max="$TMP_DIR/quiesced-safe-filter-success-max.log"
printf '%s\n' \
  'migration-safe:v=1 event=started' \
  'migration-safe:v=1 event=completed applied=2147483647' >"$safe_filter_success_max"
assert_safe_filter_accepts "safe-filter-success-max" 0 "$safe_filter_success_max"

safe_failure_events=(
  'migration-safe:v=1 event=failed phase=bootstrap category=configuration'
  'migration-safe:v=1 event=failed phase=configuration category=connection'
  'migration-safe:v=1 event=failed phase=migration category=authentication'
  'migration-safe:v=1 event=failed phase=validation category=migration'
  'migration-safe:v=1 event=failed phase=pending-check category=validation'
  'migration-safe:v=1 event=failed phase=migration category=cancelled'
  'migration-safe:v=1 event=failed phase=migration category=unexpected'
)
safe_failure_index=0
for safe_failure_event in "${safe_failure_events[@]}"; do
  safe_failure_index=$((safe_failure_index + 1))
  safe_filter_failure_input="$TMP_DIR/quiesced-safe-filter-failure-${safe_failure_index}.log"
  printf '%s\n' \
    'migration-safe:v=1 event=started' \
    "$safe_failure_event" >"$safe_filter_failure_input"
  assert_safe_filter_accepts \
    "safe-filter-failure-${safe_failure_index}" \
    1 \
    "$safe_filter_failure_input"
done

safe_filter_negative_dir="$TMP_DIR/quiesced-safe-filter-negative"
safe_filter_negative_manifest="$TMP_DIR/quiesced-safe-filter-negative.tsv"
mkdir -p "$safe_filter_negative_dir"
python3 - "$safe_filter_negative_dir" "$safe_filter_negative_manifest" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
manifest = Path(sys.argv[2])
started = b"migration-safe:v=1 event=started\n"
completed = b"migration-safe:v=1 event=completed applied=1\n"
failed = b"migration-safe:v=1 event=failed phase=migration category=connection\n"
cases = [
    ("jdbc-between-events", 0, "protocol", started + b"jdbc:postgresql://raw-sentinel.invalid/db\n" + completed),
    ("unknown-before-started", 0, "protocol", b"raw-sentinel-before\n" + started + completed),
    ("unknown-after-completed", 0, "protocol", started + completed + b"raw-sentinel-after\n"),
    ("completed-before-started", 0, "protocol", completed + started),
    ("failed-before-started", 1, "protocol", failed + started),
    ("duplicate-started", 0, "protocol", started + started + completed),
    ("duplicate-completed", 0, "protocol", started + completed + completed),
    ("duplicate-failed", 1, "protocol", started + failed + failed),
    ("completed-and-failed", 0, "protocol", started + completed + failed),
    ("missing-terminal", 0, "protocol", started),
    ("missing-started", 0, "protocol", completed),
    ("blank-line", 0, "protocol", started + b"\n" + completed),
    ("leading-whitespace", 0, "protocol", b" migration-safe:v=1 event=started\n" + completed),
    ("trailing-whitespace", 0, "protocol", started + b"migration-safe:v=1 event=completed applied=1 \n"),
    ("extra-field", 0, "protocol", started + b"migration-safe:v=1 event=completed applied=1 extra=value\n"),
    ("reordered-fields", 0, "protocol", started + b"migration-safe:event=completed v=1 applied=1\n"),
    ("unknown-version", 0, "protocol", b"migration-safe:v=2 event=started\n" + completed),
    ("unknown-event", 0, "protocol", started + b"migration-safe:v=1 event=unknown\n"),
    ("unknown-phase", 1, "protocol", started + b"migration-safe:v=1 event=failed phase=unknown category=connection\n"),
    ("unknown-category", 1, "protocol", started + b"migration-safe:v=1 event=failed phase=migration category=unknown\n"),
    ("applied-negative", 0, "protocol", started + b"migration-safe:v=1 event=completed applied=-1\n"),
    ("applied-plus", 0, "protocol", started + b"migration-safe:v=1 event=completed applied=+1\n"),
    ("applied-leading-zero", 0, "protocol", started + b"migration-safe:v=1 event=completed applied=01\n"),
    ("applied-over-int", 0, "protocol", started + b"migration-safe:v=1 event=completed applied=2147483648\n"),
    ("applied-huge", 0, "protocol", started + b"migration-safe:v=1 event=completed applied=9999999999\n"),
    ("ansi-escape", 0, "bytes", started + b"\x1b[31mraw-sentinel\x1b[0m\n" + completed),
    ("carriage-return", 0, "bytes", started + b"raw-sentinel\r\n" + completed),
    ("tab", 0, "bytes", started + b"raw\tsentinel\n" + completed),
    ("other-control", 0, "bytes", started + b"raw\x01sentinel\n" + completed),
    ("nul-byte", 0, "bytes", started + b"raw\x00sentinel\n" + completed),
    ("non-ascii", 0, "bytes", started + "небезопасно\n".encode("utf-8") + completed),
    ("exit-zero-with-failed", 0, "protocol", started + failed),
    ("nonzero-with-completed", 1, "protocol", started + completed),
    ("jvm-picked-up", 0, "protocol", b"Picked up JAVA_TOOL_OPTIONS: raw-sentinel\n" + started + completed),
]
rows = []
for name, exit_code, rejection, payload in cases:
    (root / f"{name}.log").write_bytes(payload)
    rows.append(f"{name}\t{exit_code}\t{rejection}")
manifest.write_text("\n".join(rows) + "\n", encoding="ascii")
PY

while IFS=$'\t' read -r fixture_name migration_exit rejection_kind; do
  negative_input="$safe_filter_negative_dir/${fixture_name}.log"
  negative_output="$safe_filter_negative_dir/${fixture_name}.output"
  if "$safe_filter_harness" "$negative_input" "$migration_exit" >"$negative_output" 2>&1; then
    fail "strict migration parser accepted invalid fixture: $fixture_name"
  fi
  if [ "$rejection_kind" = "bytes" ]; then
    expected_rejection='remote-release: migration diagnostic protocol rejected non-canonical bytes; raw output suppressed'
  else
    expected_rejection='remote-release: migration diagnostic protocol rejected non-canonical output; raw output suppressed'
  fi
  assert_eq "$(cat "$negative_output")" "$expected_rejection"
done <"$safe_filter_negative_manifest"
echo "quality-gate: strict migration diagnostic protocol fixtures verified"

echo "quality-gate: quiesced deployment workflow contract verified"

dockerfile="$ROOT_DIR/Dockerfile"
copy_line="$(awk '$0 == "COPY . ." { print NR }' "$dockerfile")"
placeholder_line="$(awk 'index($0, "mkdir -p miniapp/dist") { print NR }' "$dockerfile")"
install_line="$(awk 'index($0, "./gradlew --no-daemon :app-bot:installDist -x test") { print NR }' "$dockerfile")"
if [ -z "$copy_line" ] || [ -z "$placeholder_line" ] || [ -z "$install_line" ] ||
  [ "$copy_line" -ge "$placeholder_line" ] || [ "$placeholder_line" -ge "$install_line" ]; then
  fail "Dockerfile COPY/installDist contract changed"
fi

echo "quality-gate: Docker workflow/context contract verified"

echo "selfcheck: OK"
