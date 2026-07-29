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

echo "selfcheck: OK"
