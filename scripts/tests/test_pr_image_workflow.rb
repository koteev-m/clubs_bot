#!/usr/bin/env ruby
require "minitest/autorun"
require_relative "../validate-workflow-capabilities"

class PrImageWorkflowTest < Minitest::Test
  def setup
    path = File.expand_path("../../.github/workflows/docker-image.yml", __dir__)
    @workflow = WorkflowYamlSafety.safe_load_workflow(File.read(path), WorkflowCapabilityPolicy::PR_IMAGE_WORKFLOW)
  end

  def validate
    WorkflowCapabilityPolicy.validate_pr_image_workflow(@workflow)
  end

  def rejects
    capture_io do
      error = assert_raises(SystemExit) { validate }
      assert_equal 1, error.status
    end
  end

  def job
    @workflow.fetch("jobs").fetch("image-security")
  end

  def step(name)
    job.fetch("steps").find { |s| s["name"] == name }
  end

  def test_current_workflow
    validate
  end

  def test_missing_architecture
    job["strategy"]["matrix"]["include"].pop
    rejects
  end

  def test_self_hosted_runner
    job["strategy"]["matrix"]["include"][1]["runner"] = "self-hosted"
    rejects
  end

  def test_fail_fast_hides_second_result
    job["strategy"]["fail-fast"] = true
    rejects
  end

  def test_skipped_or_fail_open_scanner
    %w[if continue-on-error].each do |key|
      scan = step("Trivy PR image scan")
      scan[key] = key == "if" ? "false" : true
      rejects
      scan.delete(key)
    end
  end

  def test_source_scan_cannot_replace_image_scan
    step("Trivy PR image scan")["with"]["scan-type"] = "fs"
    rejects
  end

  def test_mutable_scan_reference
    step("Trivy PR image scan")["with"]["image-ref"] = "app-bot:latest"
    rejects
  end

  def test_nonblocking_exit_code
    step("Trivy PR image scan")["with"]["exit-code"] = 0
    rejects
  end

  def test_weakened_severity
    step("Trivy PR image scan")["with"]["severity"] = "CRITICAL"
    rejects
  end

  def test_push_forbidden
    step("Build PR image (no push)")["with"]["push"] = true
    rejects
  end

  def test_build_must_load_image
    step("Build PR image (no push)")["with"]["load"] = false
    rejects
  end

  def test_checkout_must_use_exact_candidate
    step("Checkout")["with"]["ref"] = "main"
    rejects
  end

  def test_brotli_assertion_required
    step("Verify runtime image")["run"].sub!("1.1.0-2ubuntu0.1", "1.1.0-2build2")
    rejects
  end

  def test_aggregate_cannot_accept_failure
    @workflow["jobs"]["build-and-push"]["steps"][0]["run"] = "true"
    rejects
  end

  def test_aggregate_must_run_after_failed_matrix
    @workflow["jobs"]["build-and-push"].delete("if")
    rejects
  end
end
