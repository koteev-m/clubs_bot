#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SMOKE_DIR="${REPO_ROOT}/build/reports/smoke"
DEFAULT_RESULTS_DIR="${SMOKE_DIR}/test-default"
NOTIFY_RESULTS_DIR="${SMOKE_DIR}/test-notify"
ADMIN_RESULTS_DIR="${SMOKE_DIR}/test-admin"
APP_BOT_TEST_RESULTS="${REPO_ROOT}/app-bot/build/test-results/test"

log() {
  printf '[smoke] %s\n' "$*"
}

warn() {
  printf '[smoke][warn] %s\n' "$*" >&2
}

section() {
  log "==== $1 ===="
}

run() {
  local cmd=$1
  log "Running command: ${cmd}"
  if bash -c "$cmd"; then
    log "Command succeeded: ${cmd}"
  else
    warn "Command failed: ${cmd}"
  fi
}

prepare_dirs() {
  log "Preparing smoke report directory at ${SMOKE_DIR}"
  rm -rf "${SMOKE_DIR}"
  mkdir -p "${DEFAULT_RESULTS_DIR}" "${NOTIFY_RESULTS_DIR}" "${ADMIN_RESULTS_DIR}"
}

run_gradle_build() {
  log "Running Gradle assemble without lint gates"
  if ./gradlew assemble -x test -x detekt -x ktlintCheck -x ktlintMainSourceSetCheck; then
    return 0
  fi

  warn "Gradle assemble with full exclusions failed; retrying without optional lint exclusions"
  if ./gradlew assemble -x test; then
    return 0
  fi

  warn "Gradle assemble -x test failed; falling back to build -x test"
  if ./gradlew build -x test; then
    return 0
  fi

  warn "Gradle build -x test failed; running plain assemble for diagnostics"
  ./gradlew assemble
}

run_optional_test() {
  local pattern=$1
  log "Running targeted tests for pattern ${pattern} (non-fatal)"
  if ./gradlew test --tests "${pattern}"; then
    return 0
  fi
  warn "Targeted tests for pattern ${pattern} failed (continuing)"
}

has_test() {
  local module_path="$1"
  local pattern="$2"
  local test_dir="${REPO_ROOT}/${module_path}/src/test/kotlin"

  if [[ ! -d "${test_dir}" ]]; then
    return 1
  fi

  grep -R -n --include='*Test.kt' "${pattern}" "${test_dir}" >/dev/null 2>&1
}

run_targeted_smoke() {
  local pattern="$1"
  local hint="$2"
  local module="app-bot"

  if has_test "${module}" "${pattern//\*/}"; then
    log "Running targeted ${hint} (${pattern})"
    if ./gradlew -q :${module}:test --tests "${pattern}"; then
      log "Targeted ${hint} (${pattern}) succeeded"
    else
      warn "Targeted ${hint} (${pattern}) failed (continuing)"
    fi
  else
    echo "[smoke] skip targeted ${hint} (${pattern}) — no match found"
  fi
}

clean_test_results() {
  rm -rf "${APP_BOT_TEST_RESULTS}"
}

cleanup_test_results() {
  clean_test_results
}

run_outbox_admin_tests() {
  clean_test_results
  log "Running outbox admin targeted tests"
  if OUTBOX_ADMIN_ENABLED=true RBAC_ENABLED=true ./gradlew :app-bot:test --tests "*OutboxAdmin*"; then
    log "Outbox admin targeted tests succeeded"
  else
    warn "Outbox admin targeted tests failed"
  fi

  if [ -d "${APP_BOT_TEST_RESULTS}" ]; then
    log "Archiving admin test results"
    rm -rf "${ADMIN_RESULTS_DIR}"
    mkdir -p "${ADMIN_RESULTS_DIR}"
    cp -R "${APP_BOT_TEST_RESULTS}"/. "${ADMIN_RESULTS_DIR}"
  else
    warn "Expected test results directory ${APP_BOT_TEST_RESULTS} missing after admin run"
  fi
}

run_app_bot_tests() {
  local mode=$1
  local dest_dir=$2
  local env_flag=$3

  clean_test_results
  log "Running :app-bot:test (${mode})"
  if USE_NOTIFY_SENDER=${env_flag} ./gradlew :app-bot:test; then
    log ":app-bot:test (${mode}) succeeded"
  else
    warn ":app-bot:test (${mode}) failed"
  fi

  if [ -d "${APP_BOT_TEST_RESULTS}" ]; then
    log "Archiving test results for ${mode}"
    rm -rf "${dest_dir}"
    mkdir -p "${dest_dir}"
    cp -R "${APP_BOT_TEST_RESULTS}"/. "${dest_dir}"
  else
    warn "Expected test results directory ${APP_BOT_TEST_RESULTS} missing after ${mode} run"
  fi
}

run_optional_script() {
  local script_path=$1
  if [ -x "${script_path}" ]; then
    log "Running ${script_path}"
    if python3 "${script_path}"; then
      log "${script_path} completed"
    else
      warn "${script_path} reported issues"
    fi
  elif [ -f "${script_path}" ]; then
    log "Running ${script_path} (non-executable)"
    if python3 "${script_path}"; then
      log "${script_path} completed"
    else
      warn "${script_path} reported issues"
    fi
  fi
}

main() {
  cd "${REPO_ROOT}"
  prepare_dirs
  run_gradle_build

  section "Ktor smoke endpoints"
  run_targeted_smoke '*SmokeRoutesTest' 'routes smoke'
  run_targeted_smoke '*HealthRoutesTest' 'health/ready smoke'
  run_targeted_smoke '*NotifyRoutesWiringTest' 'notify routes smoke'
  run_targeted_smoke '*PaymentsObservabilitySmokeTest' 'payments observability'

  section "Targeted smoke tests (if present)"
  cleanup_test_results
  run "./gradlew :app-bot:test --tests '*MyBookings*' || true"
  run "./gradlew :app-bot:test --tests '*NotifyDefaultWiringTest*' || true"
  run "./gradlew :app-bot:test --tests '*PaymentsPersistenceTest*' || true"
  cleanup_test_results

  run_outbox_admin_tests

  run_app_bot_tests "default" "${DEFAULT_RESULTS_DIR}" "false"
  run_app_bot_tests "notify" "${NOTIFY_RESULTS_DIR}" "true"

  run_optional_script "${REPO_ROOT}/tools/wireup_scan.py"
  run_optional_script "${REPO_ROOT}/tools/unused_scan.py"

  log "Generating smoke report"
  if python3 "${SCRIPT_DIR}/smoke_report.py"; then
    log "Smoke report generated"
  else
    warn "Smoke report generation failed"
  fi

  local report_file="${SMOKE_DIR}/REPORT.md"
  if [ -f "${report_file}" ]; then
    log "First 200 lines of smoke report:"
    head -n 200 "${report_file}"
  else
    warn "Smoke report not found at ${report_file}"
  fi
}

main "$@"
