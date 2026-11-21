#!/usr/bin/env ruby

require_relative "validate-workflow-yaml"

def reject_contract(message)
  warn "lint-release-state-contract: #{message}"
  exit 1
end

def reject_custom_shell_override(scope)
  reject_contract("custom shell override is forbidden: #{scope}")
end

path = ARGV.fetch(0)
begin
  workflow = WorkflowYamlSafety.safe_load_workflow(
    File.binread(path),
    ".github/workflows/lint.yml"
  )
rescue WorkflowYamlSafety::ModelError, Psych::SyntaxError, SystemCallError, ArgumentError => error
  reject_contract("workflow is unreadable or malformed: #{error.message}")
end

reject_contract("workflow name changed") unless workflow["name"] == "Lint"
reject_custom_shell_override("workflow defaults") if workflow.key?("defaults")
reject_contract("workflow-level concurrency is forbidden") if workflow.key?("concurrency")
triggers = workflow["on"] || workflow[true]
reject_contract("workflow triggers must be a mapping") unless triggers.is_a?(Hash)
reject_contract("triggers must cover all pull requests") unless triggers.keys.sort == %w[pull_request push] && triggers["pull_request"].nil?
push = triggers["push"]
unless push == {"branches" => ["main"]}
  reject_contract("push trigger must target only main")
end
unless workflow["permissions"] == {"contents" => "read"}
  reject_contract("permissions must be exactly contents: read")
end

jobs = workflow["jobs"]
reject_contract("jobs must be a mapping") unless jobs.is_a?(Hash)
shards = %w[authority executor import-root support]
workers = ["lint-scope", "lint-core"] + shards.map { |shard| "corrected-#{shard}" }
unless jobs.keys == workers + ["lint", "release-state"]
  reject_contract("mandatory worker/aggregator job inventory changed")
end
full_scope = "needs.lint-scope.outputs.mode == 'full'"
lint = jobs["lint-core"]
release_state = jobs["release-state"]
unless lint.is_a?(Hash) && release_state.is_a?(Hash)
  reject_contract("lint and release-state jobs must be mappings")
end
unless lint["name"] == "lint-core" && release_state["name"] == "release-state"
  reject_contract("stable job names changed")
end
unless lint["runs-on"] == "ubuntu-latest" && release_state["runs-on"] == "ubuntu-latest"
  reject_contract("jobs must use the supported runner")
end
reject_contract("lint timeout changed") unless lint["timeout-minutes"] == 120
reject_contract("release-state timeout must be exactly 50 minutes") unless release_state["timeout-minutes"] == 50

[["lint-core", lint], ["release-state", release_state]].each do |job_name, job|
  reject_custom_shell_override("#{job_name} job defaults") if job.key?("defaults")
  expected_if = job_name == "release-state" ? "always()" : nil
  reject_contract("invalid job scope condition") unless job["if"] == expected_if
  reject_contract("jobs must depend only on the change range") unless job["needs"] == "lint-scope"
  reject_contract("job continues on error") if job.key?("continue-on-error")
  reject_contract("job-level permissions must inherit the shared read-only boundary") if job.key?("permissions")
end

lint_concurrency = lint["concurrency"]
unless lint_concurrency == {
  "group" => "lint-${{ github.workflow }}-${{ github.ref }}",
  "cancel-in-progress" => true,
}
  reject_contract("lint concurrency must remain ref-oriented and cancelling")
end
candidate_sha = "${{ github.event_name == 'pull_request' && github.event.pull_request.head.sha || github.sha }}"
release_concurrency = release_state["concurrency"]
unless release_concurrency == {
  "group" => "release-state-#{candidate_sha}",
  "cancel-in-progress" => false,
}
  reject_contract("release-state concurrency must be exact-SHA keyed and non-cancelling")
end

def checked_steps(job, job_name)
  steps = job["steps"]
  reject_contract("#{job_name} steps must be an array") unless steps.is_a?(Array)
  unless steps.all? { |step| step.is_a?(Hash) && step["name"].is_a?(String) }
    reject_contract("every #{job_name} step must be a named mapping")
  end
  names = steps.map { |step| step["name"] }
  reject_contract("#{job_name} step names are duplicated") unless names.uniq.length == names.length
  steps
end

def one_step(steps, name)
  matches = steps.select { |step| step["name"] == name }
  reject_contract("step #{name.inspect} must appear exactly once") unless matches.length == 1
  matches.fetch(0)
end

lint_steps = checked_steps(lint, "lint")
release_steps = checked_steps(release_state, "release-state")
expected_lint_steps = [
  "Checkout",
  "Set up JDK 21",
  "Gradle cache & setup",
  "Payment hardening required runtime",
  "Quality gate regression self-check",
  "Run detekt gate (blocking, baseline-aware)",
  "Run ktlint gate (baseline-aware; Kotlin changes only)",
  "Upload lint reports",
]
expected_release_steps = [
  "Require verified change range",
  "Checkout exact candidate",
  "Verify Python 3.11+",
  "Validate release-state structure",
  "Run strict release-state suite",
]
unless lint_steps.map { |step| step["name"] } == expected_lint_steps
  reject_contract("lint step inventory changed")
end
unless release_steps.map { |step| step["name"] } == expected_release_steps
  reject_contract("release-state step inventory changed")
end

(lint_steps + release_steps).each do |step|
  reject_contract("step continues on error") if step.key?("continue-on-error")
  if step.key?("run") && step.key?("shell")
    reject_custom_shell_override("run step")
  end
  run = step["run"]
  reject_contract("step hides failure with || true") if run.is_a?(String) && run.include?("|| true")
end

scope_result = one_step(release_steps, "Require verified change range")
unless scope_result == {
  "name" => "Require verified change range",
  "env" => {"SCOPE_RESULT" => "${{ needs.lint-scope.result }}", "MODE" => "${{ needs.lint-scope.outputs.mode }}"},
  "run" => <<~'BASH',
    test "$SCOPE_RESULT" = success
    [[ "$MODE" == docs || "$MODE" == ui || "$MODE" == full ]]
  BASH
}
  reject_contract("release-state must fail on missing or unsuccessful change range")
end

checkout = one_step(release_steps, "Checkout exact candidate")
reject_contract("release-state checkout is conditional") if checkout.key?("if")
unless checkout["uses"] == "actions/checkout@692973e3d937129bcbf40652eb9f2f61becf3332"
  reject_contract("release-state checkout action pin changed")
end
unless checkout["with"] == {
  "ref" => candidate_sha,
  "fetch-depth" => 0,
  "persist-credentials" => false,
}
  reject_contract("release-state checkout must use the exact candidate SHA")
end

python_check = one_step(release_steps, "Verify Python 3.11+")
python_run = python_check["run"]
unless python_run.is_a?(String) && python_run.include?("sys.version_info < (3, 11)") &&
    python_run.include?("raise SystemExit")
  reject_contract("release-state Python version check is not fail-closed")
end
reject_contract("Python version check is conditional") if python_check.key?("if")

delegated = one_step(lint_steps, "Quality gate regression self-check")
structural = one_step(release_steps, "Validate release-state structure")
suite = one_step(release_steps, "Run strict release-state suite")
reject_contract("structural validator is conditional") if structural.key?("if")
[delegated, suite].each do |step|
  reject_contract("full suite scope changed") unless step["if"] == full_scope
end
unless delegated["run"] == "./scripts/selfcheck-quality-gates.sh --ci-delegated-release-state-and-corrected-stage"
  reject_contract("lint must invoke the exact delegated selfcheck mode")
end
unless structural["run"] == "./scripts/validate-quiesced-deployment.sh ."
  reject_contract("release-state structural validator command changed")
end
strict_command = "PYTHONDONTWRITEBYTECODE=1 python3 scripts/tests/test_quiesced_release_state.py --strict-ci"
reject_contract("release-state strict suite command changed") unless suite["run"] == strict_command

all_runs = (lint_steps + release_steps).each_with_object([]) do |step, runs|
  runs << step["run"] if step["run"].is_a?(String)
end
unless all_runs.count { |run| run.include?("scripts/validate-quiesced-deployment.sh") } == 1
  reject_contract("structural validator must be invoked exactly once")
end
unless all_runs.count { |run| run.include?("scripts/tests/test_quiesced_release_state.py") } == 1
  reject_contract("full release-state suite must be invoked exactly once")
end
unless all_runs.count { |run| run.include?("--ci-delegated-release-state-and-corrected-stage") } == 1
  reject_contract("lint delegation mode must be invoked exactly once")
end
if lint_steps.any? { |step| step["run"].is_a?(String) && step["run"].include?("test_quiesced_release_state") }
  reject_contract("lint delegated mode must not own the full release-state suite")
end
release_surface = release_steps.flat_map { |step| [step["uses"], step["run"]] }.compact.join("\n")
if release_surface.match?(/setup-java|gradle|docker/i)
  reject_contract("release-state gained unrelated JDK, Gradle, or Docker setup")
end


# Each class shard has the same ephemeral runner, checkout and cancellation
# policy as the former in-process suite. Distinct groups prevent sibling jobs
# from cancelling each other; release-state keeps its separate non-cancelling lock.
shards.each do |shard|
  name = "corrected-#{shard}"
  job = jobs.fetch(name)
  reject_contract("#{name} must be a mapping") unless job.is_a?(Hash)
  reject_custom_shell_override("#{name} job defaults") if job.key?("defaults")
  steps = checked_steps(job, name)
  steps.each do |step|
    reject_custom_shell_override("#{name} run step") if step.key?("shell")
  end
  expected_job = {
    "name" => name, "needs" => "lint-scope", "if" => full_scope, "runs-on" => "ubuntu-latest", "timeout-minutes" => 120,
    "concurrency" => {
      "group" => "lint-#{name}-${{ github.workflow }}-${{ github.ref }}",
      "cancel-in-progress" => true,
    },
    "steps" => [
      {
        "name" => "Checkout",
        "uses" => "actions/checkout@692973e3d937129bcbf40652eb9f2f61becf3332",
        "with" => {"fetch-depth" => 0, "persist-credentials" => false},
      },
      {
        "name" => "Verify Python 3.11+",
        "run" => python_run.sub("release-state-python:", "corrected-stage-python:"),
      },
      {
        "name" => "Run complete corrected-stage shard",
        "run" => "python3 -B scripts/run-corrected-stage-shard.py #{shard}",
      },
    ],
  }
  reject_contract("#{name} shard execution contract changed") unless job == expected_job
end

aggregator = jobs.fetch("lint")
reject_contract("lint aggregator must be a mapping") unless aggregator.is_a?(Hash)
reject_custom_shell_override("lint aggregator defaults") if aggregator.key?("defaults")
checked_steps(aggregator, "lint").each do |step|
  reject_custom_shell_override("lint aggregator run step") if step.key?("shell")
end
expected_aggregator = {
  "name" => "lint", "if" => "always()", "needs" => workers,
  "runs-on" => "ubuntu-latest", "timeout-minutes" => 5,
  "steps" => [{
    "name" => "Require every lint worker to succeed",
    "env" => {"LINT_NEEDS_JSON" => "${{ toJSON(needs) }}"},
    "run" => <<~'PYTHON',
      python3 - <<'PY'
      import json
      import os

      shards = {"corrected-authority", "corrected-executor", "corrected-import-root", "corrected-support"}
      needs = json.loads(os.environ["LINT_NEEDS_JSON"])
      if not isinstance(needs, dict) or set(needs) != shards | {"lint-core", "lint-scope"}:
          raise SystemExit("lint gate: missing or unexpected worker")
      if needs["lint-scope"]["result"] != "success" or needs["lint-core"]["result"] != "success":
          raise SystemExit("lint gate: required scope/core check did not succeed")
      mode = needs["lint-scope"]["outputs"].get("mode")
      if mode not in ("docs", "ui", "full"):
          raise SystemExit("lint gate: missing or invalid scope")
      expected = "success" if mode == "full" else "skipped"
      if any(needs[shard]["result"] != expected for shard in shards):
          raise SystemExit("lint gate: unexpected corrected-stage shard result")
      print("lint gate: required workers succeeded for", mode)
      PY
    PYTHON
  }],
}
reject_contract("lint aggregator must require every worker success, always and fail-closed") unless
  aggregator == expected_aggregator

# The core gates still execute on every PR/main run, in their existing order.
lint_steps.each do |step|
  next if step == delegated
  next if step["name"] == "Upload lint reports" && step["if"] == "always()"
  reject_contract("core step became conditional") if step.key?("if")
end
expected_setup = [
  {
    "name" => "Checkout",
    "uses" => "actions/checkout@692973e3d937129bcbf40652eb9f2f61becf3332",
    "with" => {"fetch-depth" => 0, "persist-credentials" => false},
  },
  {
    "name" => "Set up JDK 21",
    "uses" => "actions/setup-java@b36c23c0d998641eff861008f374ee103c25ac73",
    "with" => {"distribution" => "temurin", "java-version" => "21"},
  },
  {
    "name" => "Gradle cache & setup",
    "uses" => "gradle/actions/setup-gradle@d9c87d481d55275bb5441eef3fe0e46805f9ef70",
    "with" => {"cache-disabled" => false, "gradle-version" => "wrapper", "cache-read-only" => false},
  },
]
reject_contract("core checkout/JDK/cache setup changed") unless lint_steps.first(3) == expected_setup

scope = jobs.fetch("lint-scope")
expected_scope = {
  "runs-on" => "ubuntu-latest", "timeout-minutes" => 10,
  "outputs" => {"mode" => "${{ steps.range.outputs.mode }}"},
  "steps" => [
    expected_setup.first,
    {
      "name" => "Check lint workflow and change range", "id" => "range",
      "env" => {"LINT_BASE" => "${{ github.event_name == 'pull_request' && github.event.pull_request.base.sha || github.event.before }}",
                "LINT_CANDIDATE" => candidate_sha},
      "run" => <<~'BASH',
        ruby scripts/validate-lint-workflow.rb .github/workflows/lint.yml
        if [[ "$GITHUB_EVENT_NAME" == push && "$LINT_BASE" == 0000000000000000000000000000000000000000 ]]; then
          echo 'mode=full' >> "$GITHUB_OUTPUT"
        elif git diff --quiet "$LINT_BASE" "$GITHUB_SHA" -- scripts/lint-scope.py; then
          python3 -I -S -B scripts/lint-scope.py >> "$GITHUB_OUTPUT"
        else
          status=$?
          [[ "$status" == 1 ]] || exit "$status"
          echo 'mode=full' >> "$GITHUB_OUTPUT"
        fi
      BASH
    },
    {
      "name" => "Validate payment hardening structure", "if" => "steps.range.outputs.mode != 'full'",
      "run" => "python3 -B scripts/validate-payment-hardening.py --structural .",
    },
    {
      "name" => "Build changed Mini App UI", "if" => "steps.range.outputs.mode == 'ui'",
      "working-directory" => "miniapp",
      "run" => <<~'BASH',
        npx --yes pnpm@9.15.9 install --frozen-lockfile --ignore-scripts
        npm run typecheck
        npm run build -- --outDir "$RUNNER_TEMP/lint-miniapp-dist"
      BASH
    },
  ],
}
reject_contract("lint scope decision or fast checks changed") unless scope == expected_scope

expected_reports = {
  "name" => "Upload lint reports", "if" => "always()",
  "uses" => "actions/upload-artifact@b4b15b8c7c6ac21ea08fcf65892d2ee8f75cf882",
  "with" => {
    "name" => "lint-reports",
    "path" => "**/build/reports/detekt/**\n**/build/reports/ktlint/**\n",
    "if-no-files-found" => "ignore",
  },
}
reject_contract("lint reports contract changed") unless lint_steps.last == expected_reports
reject_contract("detekt command changed") unless one_step(lint_steps,
  "Run detekt gate (blocking, baseline-aware)")["run"] == "./gradlew detektGate --console=plain --stacktrace"
ktlint = one_step(lint_steps, "Run ktlint gate (baseline-aware; Kotlin changes only)")
reject_contract("ktlint command changed") unless ktlint["run"] == "./scripts/ktlint-changed.sh" && ktlint["env"] == {
  "VERIFY_TO_SHA" => "${{ github.sha }}",
  "VERIFY_FROM_SHA" => "${{ github.event_name == 'pull_request' && github.event.pull_request.base.sha || github.event.before }}",
}

puts "quality-gate: lint/release-state delegation contract verified"
