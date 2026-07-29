#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/sha256-portable.sh"
TMP_DIR="$(mktemp -d)"
SCA_CACHE_DIR="$TMP_DIR/sca-cache"
SCA_MARKER="$SCA_CACHE_DIR/cache-warm.marker"
SCA_MANIFEST="$SCA_CACHE_DIR/cache-warm.manifest"
SCA_VALID_LOG="$TMP_DIR/sca-valid.log"
SCA_MARKER_ONLY_LOG="$TMP_DIR/sca-marker-only.log"
SCA_JUNK_LOG="$TMP_DIR/sca-junk.log"
SCA_STALE_LOG="$TMP_DIR/sca-stale.log"
SCA_SAME_SIZE_LOG="$TMP_DIR/sca-same-size.log"

cleanup() {
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
for module in app-bot core-data core-domain core-security core-telemetry core-testing tools:perf; do
  assert_contains "$detekt_task_graph" ":${module}:detektMain SKIPPED"
  assert_contains "$detekt_task_graph" ":${module}:detektTest SKIPPED"
done

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
  awk -v expected="$expected" -v replacement="$replacement" '
    $0 == expected {
      matches++
      if (matches == 1) {
        print replacement
        next
      }
    }
    { print }
    END { if (matches != 1) exit 42 }
  ' "$source_file" >"$target_file"
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

non_pr_expression="\${{ github.event_name != 'pull_request' }}"
non_pr_if="github.event_name != 'pull_request'"
metadata_tags_expression="\${{ steps.meta.outputs.tags }}"
metadata_labels_expression="\${{ steps.meta.outputs.labels }}"
docker_image_workflow="$ROOT_DIR/.github/workflows/docker-image.yml"
docker_publish_workflow="$ROOT_DIR/.github/workflows/docker-publish.yml"
security_scan_workflow="$ROOT_DIR/.github/workflows/security-scan.yml"
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
