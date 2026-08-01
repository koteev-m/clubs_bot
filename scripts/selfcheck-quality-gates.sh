#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/sha256-portable.sh"
TMP_DIR="$(mktemp -d)"
DETEKT_DYNAMIC_MODULE_DIR="$ROOT_DIR/detekt-selfcheck-fixture"
DETEKT_DYNAMIC_MODULE_OWNED=0
DETEKT_PREPARE_LINK=""
DETEKT_PREPARE_LINK_OWNED=0
SCA_CACHE_DIR="$TMP_DIR/sca-cache"
SCA_MARKER="$SCA_CACHE_DIR/cache-warm.marker"
SCA_MANIFEST="$SCA_CACHE_DIR/cache-warm.manifest"
SCA_VALID_LOG="$TMP_DIR/sca-valid.log"
SCA_MARKER_ONLY_LOG="$TMP_DIR/sca-marker-only.log"
SCA_JUNK_LOG="$TMP_DIR/sca-junk.log"
SCA_STALE_LOG="$TMP_DIR/sca-stale.log"
SCA_SAME_SIZE_LOG="$TMP_DIR/sca-same-size.log"

cleanup() {
  if [ "$DETEKT_PREPARE_LINK_OWNED" = "1" ] && [ -L "$DETEKT_PREPARE_LINK" ]; then
    rm -f "$DETEKT_PREPARE_LINK"
  fi
  if [ "$DETEKT_DYNAMIC_MODULE_OWNED" = "1" ]; then
    rm -rf "$DETEKT_DYNAMIC_MODULE_DIR"
  fi
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

fail() {
  echo "selfcheck: $1" >&2
  exit 1
}

epoch_millis() {
  if ts="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
  )" && [ -n "$ts" ]; then
    printf '%s\n' "$ts"
    return 0
  fi

  if date +%s%3N >/dev/null 2>&1; then
    date +%s%3N
    return 0
  fi

  printf '%s000\n' "$(date +%s)"
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

mkdir -p "$SCA_CACHE_DIR"
printf 'warmedAt=%s\nmaxAgeHours=168\n' "$(epoch_millis)" > "$SCA_MARKER"
if NVD_API_KEY= ./gradlew --no-configuration-cache -PdependencyCheckDataDir="$SCA_CACHE_DIR" scaPreflight --console=plain >"$SCA_MARKER_ONLY_LOG" 2>&1; then
  fail "expected scaPreflight to fail on marker-only cache"
fi
assert_contains "$(cat "$SCA_MARKER_ONLY_LOG")" "Warm marker/manifest not found"

printf 'warmedAt=%s\nmaxAgeHours=168\n' "$(epoch_millis)" > "$SCA_MARKER"
mkdir -p "$SCA_CACHE_DIR/data/cache"
printf 'payload' > "$SCA_CACHE_DIR/data/cache/nvd.json"
payload_size="$(wc -c < "$SCA_CACHE_DIR/data/cache/nvd.json" | tr -d ' ')"
payload_sha="$(sha256_file "$SCA_CACHE_DIR/data/cache/nvd.json")"
payload_digest="$(printf 'data/cache/nvd.json:%s:%s' "$payload_size" "$payload_sha" | sha256_stdin)"
printf 'payloadFileCount=1\npayloadTotalBytes=%s\npayloadDigest=%s\nfile=data/cache/nvd.json|%s|%s\n' \
  "$payload_size" "$payload_digest" "$payload_size" "$payload_sha" > "$SCA_MANIFEST"
if ! NVD_API_KEY= ./gradlew --no-configuration-cache -PdependencyCheckDataDir="$SCA_CACHE_DIR" scaPreflight --console=plain >"$SCA_VALID_LOG" 2>&1; then
  fail "expected scaPreflight to pass with valid cache manifest"
fi

printf 'warmedAt=%s\nmaxAgeHours=168\n' "$(epoch_millis)" > "$SCA_MARKER"
printf 'payload-2' > "$SCA_CACHE_DIR/data/cache/nvd2.json"
payload2_size="$(wc -c < "$SCA_CACHE_DIR/data/cache/nvd2.json" | tr -d ' ')"
payload2_sha="$(sha256_file "$SCA_CACHE_DIR/data/cache/nvd2.json")"
payload_multidigest="$(
  {
    printf 'data/cache/nvd.json:%s:%s\n' "$payload_size" "$payload_sha"
    printf 'data/cache/nvd2.json:%s:%s' "$payload2_size" "$payload2_sha"
  } | sha256_stdin
)"
printf 'payloadFileCount=2\npayloadTotalBytes=%s\npayloadDigest=%s\nfile=data/cache/nvd.json|%s|%s\nfile=data/cache/nvd2.json|%s|%s\n' \
  "$((payload_size + payload2_size))" "$payload_multidigest" "$payload_size" "$payload_sha" "$payload2_size" "$payload2_sha" > "$SCA_MANIFEST"
if ! NVD_API_KEY= ./gradlew --no-configuration-cache -PdependencyCheckDataDir="$SCA_CACHE_DIR" scaPreflight --console=plain >"$SCA_VALID_LOG" 2>&1; then
  fail "expected scaPreflight to pass with valid multi-file cache manifest"
fi

printf 'payloadFileCount=1\npayloadTotalBytes=%s\npayloadDigest=%s\nfile=data/cache/nvd.json|%s|%s\n' \
  "$payload_size" "$payload_digest" "$payload_size" "$payload_sha" > "$SCA_MANIFEST"
if NVD_API_KEY= ./gradlew --no-configuration-cache -PdependencyCheckDataDir="$SCA_CACHE_DIR" scaPreflight --console=plain >"$SCA_JUNK_LOG" 2>&1; then
  fail "expected scaPreflight to fail on file-set mismatch (missing entry)"
fi
assert_contains "$(cat "$SCA_JUNK_LOG")" "warm manifest does not match cache payload"

printf 'payloadFileCount=1\npayloadTotalBytes=9999\npayloadDigest=junk\nfile=data/cache/nvd.json|%s|junk\n' "$payload_size" > "$SCA_MANIFEST"
if NVD_API_KEY= ./gradlew --no-configuration-cache -PdependencyCheckDataDir="$SCA_CACHE_DIR" scaPreflight --console=plain >"$SCA_JUNK_LOG" 2>&1; then
  fail "expected scaPreflight to fail on junk payload manifest"
fi
assert_contains "$(cat "$SCA_JUNK_LOG")" "warm manifest does not match cache payload"

printf 'payloadFileCount=1\npayloadTotalBytes=%s\npayloadDigest=%s\nfile=data/cache/nvd.json|%s|%s\n' \
  "$payload_size" "$payload_digest" "$payload_size" "$payload_sha" > "$SCA_MANIFEST"
rm -f "$SCA_CACHE_DIR/data/cache/nvd2.json"
printf 'warmedAt=1\nmaxAgeHours=168\n' > "$SCA_MARKER"
if NVD_API_KEY= ./gradlew --no-configuration-cache -PdependencyCheckDataDir="$SCA_CACHE_DIR" scaPreflight --console=plain >"$SCA_STALE_LOG" 2>&1; then
  fail "expected scaPreflight to fail on stale cache marker"
fi
assert_contains "$(cat "$SCA_STALE_LOG")" "local cache is stale"

printf 'warmedAt=%s\nmaxAgeHours=168\n' "$(epoch_millis)" > "$SCA_MARKER"
printf 'abc1234' > "$SCA_CACHE_DIR/data/cache/nvd.json"
if NVD_API_KEY= ./gradlew --no-configuration-cache -PdependencyCheckDataDir="$SCA_CACHE_DIR" scaPreflight --console=plain >"$SCA_SAME_SIZE_LOG" 2>&1; then
  fail "expected scaPreflight to fail on same-size-different-content payload"
fi
assert_contains "$(cat "$SCA_SAME_SIZE_LOG")" "warm manifest does not match cache payload"

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
assert_contains "$usage_out" "Usage: scripts/refresh-verification-metadata.sh [default|sca]"

verify_usage_out="$("$ROOT_DIR/scripts/verify.sh" unknown 2>&1 || true)"
assert_contains "$verify_usage_out" "Usage: scripts/verify.sh [full|ci|lint|secret-scan|sca-warm-cache]"

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
  if [ "$action_count" -ne 2 ]; then
    echo "expected exactly two active trivy-action references, found $action_count" >&2
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
  if [ "$trivyignores_count" -ne 2 ]; then
    echo "expected exactly two supported trivyignores inputs" >&2
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
    "Upload Trivy SARIF to code scanning" \
    "if" \
    "        if: $trivy_filesystem_report_guard"
  assert_step_has_no_direct_key \
    "$file" \
    "trivy" \
    "Upload Trivy SARIF to code scanning" \
    "continue-on-error"
  assert_step_with_contract \
    "$file" \
    "trivy" \
    "Upload Trivy SARIF to code scanning" \
    "$trivy_filesystem_sarif_with_contract"

  assert_step_direct_key_line \
    "$file" \
    "trivy" \
    "Persist Trivy report artifact" \
    "if" \
    "        if: $trivy_filesystem_report_guard"
  assert_step_has_no_direct_key \
    "$file" \
    "trivy" \
    "Persist Trivy report artifact" \
    "continue-on-error"
  assert_step_with_contract \
    "$file" \
    "trivy" \
    "Persist Trivy report artifact" \
    "$trivy_filesystem_artifact_with_contract"
}

validate_trivy_image_workflow() {
  local file="$1"
  assert_job_direct_key_line \
    "$file" \
    "trivy-image" \
    "if" \
    "    if: $non_pr_if"
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
container_smoke_workflow="$ROOT_DIR/.github/workflows/container-smoke.yml"
static_check_workflow="$ROOT_DIR/.github/workflows/static-check.yml"
detekt_permissions_contract='contents: read
security-events: write'
detekt_html_report_guard="\${{ always() && hashFiles('**/build/reports/detekt/detekt*/detekt.html') != '' }}"
detekt_sarif_report_guard="\${{ always() && steps.detekt-sarif-collector.outcome == 'success' && hashFiles('build/reports/detekt/combined/detekt.sarif') != '' }}"
detekt_html_with_contract='name: detekt-html
path: "**/build/reports/detekt/detekt*/detekt.html"
if-no-files-found: error'
detekt_sarif_with_contract='sarif_file: build/reports/detekt/combined/detekt.sarif'
approved_trivy_action_active_line="uses: aquasecurity/trivy-action@57a97c7e7821a5776cebc9bb87c984fa69cba8f1 # v0.35.0, post-incident safe release"
trivy_filesystem_report_guard="\${{ always() && hashFiles('trivy-results.sarif') != '' }}"
trivy_image_report_guard="\${{ always() && hashFiles('trivy-image-results.sarif') != '' }}"
trivy_filesystem_with_contract='scan-type: fs
scan-ref: .
severity: HIGH,CRITICAL
trivyignores: .trivyignore
format: sarif
output: trivy-results.sarif
exit-code: 1
version: v0.69.3'
trivy_image_with_contract='scan-type: image
image-ref: ${{ needs.build-and-push.outputs.image-ref }}
severity: HIGH,CRITICAL
trivyignores: .trivyignore
format: sarif
output: trivy-image-results.sarif
exit-code: 1
version: v0.69.3'
trivy_filesystem_sarif_with_contract='sarif_file: trivy-results.sarif'
trivy_image_sarif_with_contract='sarif_file: trivy-image-results.sarif'
trivy_filesystem_artifact_with_contract='name: trivy-report
path: trivy-results.sarif
if-no-files-found: error'
trivy_image_artifact_with_contract='name: trivy-image-report
path: trivy-image-results.sarif'
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

for workflow_file in "$docker_image_workflow" "$docker_publish_workflow"; do
  assert_exact_line "$workflow_file" "    branches: [ main ]"
  assert_exact_line "$workflow_file" "    tags: [ 'v*' ]"
  assert_exact_line "$workflow_file" "  pull_request:"
  assert_exact_line "$workflow_file" "  workflow_dispatch:"

  metadata_tags="$(
    awk '
      $0 == "      - name: Extract metadata (tags, labels)" { in_step = 1 }
      in_step && $0 == "          tags: |" { in_tags = 1; next }
      in_tags && $0 ~ /^            type=/ { sub(/^            /, ""); print; next }
      in_tags && $0 !~ /^            / { exit }
    ' "$workflow_file"
  )"
  expected_metadata_tags="$(
    printf '%s\n' \
      "type=sha,format=short" \
      "type=ref,event=branch" \
      "type=semver,pattern={{version}},prefix=v" \
      "type=semver,pattern={{major}}.{{minor}},prefix=v"
  )"
  assert_eq "$metadata_tags" "$expected_metadata_tags"
  assert_not_contains "$metadata_tags" "branch=main"

  assert_sha_pinned_action "$workflow_file" "docker/metadata-action"
  assert_sha_pinned_action "$workflow_file" "docker/build-push-action"
  assert_step_line "$workflow_file" "Log in to GHCR" "        if: $non_pr_if"
done

validate_metadata_wiring "$docker_image_workflow" "Build and (optionally) Push"
validate_metadata_wiring "$docker_publish_workflow" "Build & (optionally) Push"

assert_step_with_line \
  "$docker_image_workflow" \
  "Build and (optionally) Push" \
  "          push: $non_pr_expression"
assert_step_with_line \
  "$docker_image_workflow" \
  "Build and (optionally) Push" \
  "          provenance: $non_pr_expression"
assert_step_with_line \
  "$docker_publish_workflow" \
  "Build & (optionally) Push" \
  "          push: $non_pr_expression"
assert_step_with_line \
  "$docker_publish_workflow" \
  "Build & (optionally) Push" \
  "          provenance: $non_pr_expression"

for protected_step in \
  "Sign image (keyless)" \
  "Generate SBOM (CycloneDX via Syft)" \
  "Upload SBOM"; do
  assert_step_line "$docker_publish_workflow" "$protected_step" "        if: $non_pr_if"
done

validate_publish_provenance_job_guard "$docker_publish_workflow"

if ! validate_trivy_action_inventory "$ROOT_DIR/.github/workflows"; then
  fail "Trivy action inventory contract failed"
fi
validate_trivy_filesystem_workflow "$security_scan_workflow"
validate_trivy_image_workflow "$docker_publish_workflow"
echo "quality-gate: Trivy workflow contract verified"

validate_container_smoke_workflow "$container_smoke_workflow"
echo "quality-gate: Container Smoke runtime/network contract verified"

"$ROOT_DIR/gradlew" :app-bot:installDist --rerun-tasks --console=plain
validate_packaged_launcher "$ROOT_DIR/app-bot/build/install/app-bot/bin/app-bot"
validate_packaged_launcher "$ROOT_DIR/app-bot/build/install/app-bot/bin/app-bot.bat"
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

workflow_wrong_id_fixture="$TMP_DIR/docker-image-wrong-metadata-id.yml"
replace_exact_line_once \
  "$docker_image_workflow" \
  "$workflow_wrong_id_fixture" \
  "        id: meta" \
  "        id: metadata"
assert_validation_rejected \
  "docker-image-wrong-metadata-id" \
  validate_metadata_wiring \
  "$workflow_wrong_id_fixture" \
  "Build and (optionally) Push"

workflow_wrong_tags_fixture="$TMP_DIR/docker-publish-wrong-tags-binding.yml"
replace_exact_line_once \
  "$docker_publish_workflow" \
  "$workflow_wrong_tags_fixture" \
  "          tags: $metadata_tags_expression" \
  "          tags: \${{ steps.metadata.outputs.tags }}"
assert_validation_rejected \
  "docker-publish-wrong-tags-binding" \
  validate_metadata_wiring \
  "$workflow_wrong_tags_fixture" \
  "Build & (optionally) Push"

workflow_missing_labels_fixture="$TMP_DIR/docker-publish-labels-outside-build-with.yml"
move_build_labels_outside_with \
  "$docker_publish_workflow" \
  "$workflow_missing_labels_fixture" \
  "Build & (optionally) Push" \
  "          labels: $metadata_labels_expression"
assert_validation_rejected \
  "docker-publish-labels-outside-build-with" \
  validate_metadata_wiring \
  "$workflow_missing_labels_fixture" \
  "Build & (optionally) Push"

workflow_missing_job_guard_fixture="$TMP_DIR/docker-publish-missing-provenance-job-guard.yml"
mutate_publish_provenance_guard \
  "$docker_publish_workflow" \
  "$workflow_missing_job_guard_fixture" \
  "no"
assert_validation_rejected \
  "docker-publish-missing-provenance-job-guard" \
  validate_publish_provenance_job_guard \
  "$workflow_missing_job_guard_fixture"

workflow_step_only_guard_fixture="$TMP_DIR/docker-publish-step-only-provenance-guard.yml"
mutate_publish_provenance_guard \
  "$docker_publish_workflow" \
  "$workflow_step_only_guard_fixture" \
  "yes"
assert_validation_rejected \
  "docker-publish-step-only-provenance-guard" \
  validate_publish_provenance_job_guard \
  "$workflow_step_only_guard_fixture"

legacy_trivy_action_sha_prefix="b6643a29fecd7f34b3597bc6acb0a98b03d33"
legacy_trivy_action_sha="${legacy_trivy_action_sha_prefix}ff8"

trivy_old_action_fixture="$TMP_DIR/security-scan-old-trivy-action.yml"
replace_exact_line_once \
  "$security_scan_workflow" \
  "$trivy_old_action_fixture" \
  "        $approved_trivy_action_active_line" \
  "        uses: aquasecurity/trivy-action@$legacy_trivy_action_sha # 0.33.1"
assert_validation_rejected \
  "trivy-old-action-sha" \
  validate_trivy_filesystem_workflow \
  "$trivy_old_action_fixture"

trivy_latest_fixture="$TMP_DIR/security-scan-trivy-latest.yml"
replace_exact_line_once \
  "$security_scan_workflow" \
  "$trivy_latest_fixture" \
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
replace_exact_line_once \
  "$security_scan_workflow" \
  "$trivy_ignorefile_fixture" \
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
  "Upload Trivy SARIF to code scanning" \
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
