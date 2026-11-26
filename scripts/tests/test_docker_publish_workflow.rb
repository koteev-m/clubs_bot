#!/usr/bin/env ruby

require "minitest/autorun"
require "open3"
require "tmpdir"
require_relative "../validate-workflow-capabilities"

class DockerPublishWorkflowTest < Minitest::Test
  POLICY = WorkflowCapabilityPolicy
  PATH = POLICY::CANONICAL_PUBLISHER

  def setup
    path = File.expand_path("../../#{PATH}", __dir__)
    @jobs = WorkflowYamlSafety.safe_load_workflow(File.read(path), PATH).fetch("jobs")
  end

  def validate(job_name)
    job = @jobs.fetch(job_name)
    POLICY.validate_canonical_job_contract(PATH, job_name, job)
    POLICY.validate_registry_and_literal_policy(PATH, job_name, job, job.fetch("permissions"))
  end

  def rejects(job_name)
    capture_io do
      error = assert_raises(SystemExit) { validate(job_name) }
      assert_equal 1, error.status
    end
  end

  def step(job_name, step_name)
    @jobs.fetch(job_name).fetch("steps").find { |entry| entry["name"] == step_name }
  end

  def test_canonical_jobs
    @jobs.each_key { |job_name| validate(job_name) }
  end

  def test_missing_native_arm_build
    @jobs.fetch("build-and-push").dig("strategy", "matrix", "include").pop
    rejects("build-and-push")
  end

  def test_arm_cannot_use_emulation_runner
    @jobs.fetch("build-and-push").dig("strategy", "matrix", "include").last["runner"] = "ubuntu-24.04"
    rejects("build-and-push")
  end

  def test_build_cannot_update_release_tag
    step("build-and-push", "Build & Push")["with"]["tags"] = "${{ env.IMAGE_NAME }}:latest"
    rejects("build-and-push")
  end

  def test_builder_cannot_write_shared_cache_or_import_pr_cache
    build = step("build-and-push", "Build & Push")["with"]
    build["cache-to"] = "type=gha,mode=max,scope=buildkit"
    rejects("build-and-push")
    build["cache-to"] = "type=gha,mode=max,scope=release-image-${{ matrix.arch }}"
    build["cache-from"] = "type=gha,scope=pr-image-${{ matrix.arch }}"
    rejects("build-and-push")
  end

  def test_failed_build_cannot_be_bypassed
    @jobs.fetch("assemble-index")["if"] = "always()"
    rejects("assemble-index")
  end

  def test_publication_requires_every_gate
    job = @jobs.fetch("publish-tags")
    dependencies = job.fetch("needs")
    dependencies.each do |omitted|
      job["needs"] = dependencies - [omitted]
      rejects("publish-tags")
    end
    job["needs"] = dependencies
    job["if"] = "always()"
    rejects("publish-tags")
  end

  def test_publication_cannot_use_mutable_tag
    step("publish-tags", "Publish verified release tags")["env"]["IMAGE_REF"] = "${{ env.IMAGE_NAME }}:main"
    rejects("publish-tags")
  end

  def test_index_creation_outside_canonical_publisher_is_rejected
    job = @jobs.fetch("assemble-index")
    capture_io do
      error = assert_raises(SystemExit) do
        POLICY.validate_registry_and_literal_policy(".github/workflows/other.yml", "assemble-index", {
          "steps" => [step("assemble-index", "Assemble candidate OCI index")],
        }, job.fetch("permissions"))
      end
      assert_equal 1, error.status
    end
  end

  def test_provenance_verifier_requires_native_statement_contract
    verifier = step("verify-and-provenance", "Verify OCI graph and SLSA provenance")
    verifier["run"] = verifier["run"].delete_suffix("--native-build\n")
    rejects("verify-and-provenance")
  end

  def test_trivy_must_scan_both_architectures
    @jobs.fetch("trivy-image").dig("strategy", "matrix", "arch").pop
    rejects("trivy-image")
  end

  def test_mixed_build_attempts_stop_before_assembly
    script = step("assemble-index", "Assemble candidate OCI index").fetch("run")
    env = {"AMD64_ATTEMPT" => "1", "ARM64_ATTEMPT" => "2", "PATH" => ""}
    message, status = Open3.capture2e(env, "/bin/bash", "-c", script)
    refute status.success?
    assert_includes message, "Re-run all jobs"
  end

  def test_verification_uses_the_producer_attempt_on_rerun
    verifier = step("verify-and-provenance", "Verify OCI graph and SLSA provenance")
    verifier["env"]["EXPECTED_RUN_ATTEMPT"] = "${{ github.run_attempt }}"
    rejects("verify-and-provenance")
  end

  def test_release_tag_updates_require_the_shared_queue
    @jobs.fetch("publish-tags").delete("concurrency")
    rejects("publish-tags")
  end

  def test_promotion_skips_only_newer_tags_and_fails_closed_on_lookup_error
    script = step("publish-tags", "Publish verified release tags").fetch("run")
    Dir.mktmpdir do |dir|
      docker = File.join(dir, "docker")
      File.write(docker, <<~'SH')
        #!/bin/bash
        set -eu
        case "$3" in
          inspect)
            case "$5" in
              *:main) echo '{"annotations":{"io.github.actions.run-number":"12"}}' ;;
              *:legacy) echo '{}' ;;
              *:missing) echo "ERROR: $5: not found" >&2; exit 1 ;;
              *) echo "registry unavailable" >&2; exit 1 ;;
            esac ;;
          create)
            printf '%s\n' "$@" > "$RUNNER_TEMP/create-args"
            printf '{"containerimage.descriptor":{"digest":"%s"}}\n' "${IMAGE_REF#*@}" > "$5" ;;
          *) exit 2 ;;
        esac
      SH
      File.chmod(0o700, docker)
      image = "ghcr.io/fixture/app-bot"
      env = {
        "PATH" => "#{dir}:#{ENV.fetch('PATH')}", "RUNNER_TEMP" => dir,
        "GITHUB_RUN_NUMBER" => "10", "IMAGE_NAME" => image,
        "IMAGE_REF" => "#{image}@sha256:#{'a' * 64}",
      }
      args = File.join(dir, "create-args")
      run = lambda do |tags|
        refs = tags.map { |tag| "#{image}:#{tag}" }.join("\n")
        Open3.capture2e(env.merge("RELEASE_TAGS" => refs), "/bin/bash", "-c", script)
      end
      message, status = run.call(%w[main])
      assert status.success?, message
      refute File.exist?(args)
      message, status = run.call(%w[legacy broken])
      refute status.success?, message
      refute File.exist?(args)
      message, status = run.call(%w[main legacy missing])
      assert status.success?, message
      refs = File.readlines(args, chomp: true)
      refute_includes refs, "#{image}:main"
      assert_includes refs, "#{image}:legacy"
      assert_includes refs, "#{image}:missing"
    end
  end
end
