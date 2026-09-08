# Exact capability boundary for the single incident executor. Called by the
# repository workflow validator; descriptions/default identities are data, while
# every executable step and privilege-bearing field is compared structurally.
module CorrectedStageWorkflow
  PATH = ".github/workflows/corrected-stage-release.yml"
  KEYS = {
    "APP_ENV" => "environment", "INCIDENT_TAG" => "incident_tag",
    "RELEASE_OWNER" => "release_owner", "EXPECTED_REVISION" => "expected_revision",
    "IMAGE_DIGEST" => "image_digest", "IMPLEMENTATION" => "implementation",
    "ACTION" => "action", "AUTHORIZATION" => "authorization", "PRIOR_STATUS" => "prior_status",
  }.freeze

  def self.validate(policy, workflow)
    reject = ->(reason) { policy.reject("#{PATH}: corrected stage #{reason}") }
    reject.call("top-level fields changed") unless workflow.keys.sort == %w[concurrency jobs name on permissions].sort
    reject.call("name changed") unless workflow["name"] == "Corrected Stage Release (manual)"
    reject.call("permissions changed") unless workflow["permissions"] == {"contents" => "read"}
    reject.call("concurrency changed") unless workflow["concurrency"] == {"group" => "payments-schema-stage", "cancel-in-progress" => false}
    trigger = workflow["on"]
    reject.call("manual trigger changed") unless trigger.is_a?(Hash) && trigger.keys == ["workflow_dispatch"]
    dispatch = trigger["workflow_dispatch"]
    reject.call("dispatch changed") unless dispatch.is_a?(Hash) && dispatch.keys == ["inputs"]
    inputs = dispatch["inputs"]
    reject.call("input inventory changed") unless inputs.is_a?(Hash) && inputs.keys.sort == KEYS.values.sort
    inputs.each do |key, input|
      choice = %w[action environment].include?(key)
      fields = %w[description required default type] + (choice ? ["options"] : [])
      reject.call("input contract changed") unless input.keys.sort == fields.sort &&
        input["description"].is_a?(String) && input["required"] == (key != "authorization") &&
        input["type"] == (choice ? "choice" : "string") && input["default"].is_a?(String)
    end
    reject.call("inspect default changed") unless inputs["action"]["default"] == "inspect" &&
      inputs["action"]["options"] == ["inspect", "resume-start"] && inputs["authorization"]["default"] == ""
    reject.call("stage boundary changed") unless inputs["environment"]["default"] == "stage" && inputs["environment"]["options"] == ["stage"]

    checkout = {
      "name" => "Checkout exact workflow revision", "uses" => policy::CHECKOUT_ACTION,
      "with" => {"ref" => "${{ github.sha }}", "fetch-depth" => 0, "persist-credentials" => false},
    }
    base_env = {"REPOSITORY_DEFAULT_BRANCH" => "${{ github.event.repository.default_branch }}", "PYTHONDONTWRITEBYTECODE" => "1"}
    authority_env = {"CLB82_APPROVED_IMPLEMENTATION" => "${{ vars.CLB82_APPROVED_IMPLEMENTATION }}",
                     "CLB82_AUTHORIZED_ROOT_BINDING" => "${{ vars.CLB82_AUTHORIZED_ROOT_BINDING }}"}
    reject.call("unpublished implementation default changed") unless inputs["implementation"]["default"] == ""
    expected = {
      "validate" => {
        "name" => "validate-corrected-stage-request", "runs-on" => "ubuntu-latest", "timeout-minutes" => 5,
        "permissions" => {"contents" => "read", "actions" => "read"},
        "outputs" => KEYS.to_h { |key, _| [key, "${{ steps.validate.outputs.#{key} }}"] },
        "steps" => [checkout, {
          "name" => "Validate exact request before secrets", "id" => "validate", "shell" => "bash",
          "env" => base_env.merge(KEYS.to_h { |key, input| [key, "${{ inputs.#{input} }}"] }),
          "run" => "python3 -I -S -B scripts/deploy/corrected-stage-release.py --validate",
        }, {
          "name" => "Verify original status evidence through GitHub", "shell" => "bash",
          "env" => base_env.merge(KEYS.to_h { |key, _| [key, "${{ steps.validate.outputs.#{key} }}"] }).merge(
            "GITHUB_TOKEN" => "${{ secrets.GITHUB_TOKEN }}"
          ),
          "run" => "python3 -I -S -B scripts/deploy/corrected-stage-release.py --verify-prior",
        }],
      },
      "execute" => {
        "name" => "deployment-principal-corrected-stage", "needs" => "validate", "runs-on" => "ubuntu-latest",
        "timeout-minutes" => 10, "permissions" => {"contents" => "read", "actions" => "read"}, "environment" => "stage",
        "steps" => [checkout, {
          "name" => "Revalidate attempt before SSH credentials", "shell" => "bash",
          "env" => base_env.merge(KEYS.to_h { |key, _| [key, "${{ needs.validate.outputs.#{key} }}"] }).merge(authority_env),
          "run" => "python3 -I -S -B scripts/deploy/corrected-stage-release.py --validate-execution",
        }, {
          "name" => "Setup deployment SSH principal", "uses" => policy::SSH_AGENT_ACTION,
          "with" => {"ssh-private-key" => "${{ secrets.SSH_PRIVATE_KEY }}"},
        }, {
          "name" => "Execute bounded corrected helper", "shell" => "bash",
          "env" => base_env.merge(KEYS.to_h { |key, _| [key, "${{ needs.validate.outputs.#{key} }}"] }).merge(
            "GITHUB_TOKEN" => "${{ secrets.GITHUB_TOKEN }}",
            "TMPDIR" => "${{ runner.temp }}", "SSH_USER" => "${{ secrets.SSH_USER }}",
            "SSH_HOST" => "${{ secrets.SSH_HOST }}", "SSH_PORT" => "${{ secrets.SSH_PORT || '22' }}",
            "COMPOSE_PATH" => "${{ secrets.COMPOSE_PATH }}", "SSH_KNOWN_HOSTS" => "${{ secrets.SSH_KNOWN_HOSTS }}"
          ).merge(authority_env),
          "run" => "python3 -I -S -B scripts/deploy/corrected-stage-release.py",
        }],
      },
    }
    policy.validate_exact_value(workflow["jobs"], expected, "#{PATH}: corrected stage jobs")
  end
end

# Local structure/identity check, in addition to the executable CLI regressions.
# Do not import the candidate runner while validating its constants.
if $PROGRAM_NAME == __FILE__
  require "digest"
  root = File.expand_path(ARGV.fetch(0, File.join(__dir__, "..")))
  helper_path = File.join(root, "scripts/deploy/remote-compose-release.sh")
  helper = File.binread(helper_path)
  runner = File.binread(File.join(root, "scripts/deploy/corrected-stage-release.py"))
  tests = File.binread(File.join(root, "scripts/tests/test_corrected_stage_release.py"))
  reject_bound = lambda do |message|
    abort "corrected-bound-contract: #{message}"
  end
  expected = {
    "IMPLEMENTATION_BLOB" => Digest::SHA1.hexdigest("blob #{helper.bytesize}\0" + helper).inspect,
    "IMPLEMENTATION_SHA256" => Digest::SHA256.hexdigest(helper).inspect,
    "IMPLEMENTATION_SIZE" => helper.bytesize.to_s,
  }
  expected.each do |name, value|
    reject_bound.call("working helper identity mismatch: #{name}") unless
      runner.lines.count { |line| line.chomp == "#{name} = #{value}" } == 1
  end
  reject_bound.call("helper mode changed") unless File.stat(helper_path).mode & 0o777 == 0o644
  %w[open_context lock_context check_edges open_file capture_configuration check_evidence claim resume operation_result].each do |name|
    reject_bound.call("missing bound implementation #{name}") unless helper.include?("    def #{name}(")
  end
  ["dir_fd=self.directories['root']", "src_dir_fd=base, dst_dir_fd=base", "os.O_NOFOLLOW",
   "os.fsync(self.directories['root'])", "fcntl.LOCK_NB", "self.ready()", "self.claim_record()",
   "self.resolved_capture", "os.fchdir(self.directories['compose'])"].each do |contract|
    reject_bound.call("missing descriptor/claim contract") unless helper.include?(contract)
  end
  reject_bound.call("standalone claim transport returned") if runner.include?("REMOTE_CLAIM")
  %w[root_and_parent_substitution_must_not_authorize_two_resumes
     nested_directories_and_lock_substitution_after_context_open
     between_claim_and_resume_copied_context_rejects_same_token
     captured_configuration_interpolation_relative_paths_and_public_swap
     finalizer_writes_held_root_and_never_substituted_root
     f2_late_competing_claim_blocks_resume_without_retry
     cli_transport_principal_guard_pins_bytes_and_secret_redaction].each do |name|
    reject_bound.call("missing mandatory bound regression #{name}") unless
      tests.lines.count { |line| line.chomp == "    def test_#{name}(self):" } == 1
  end
  reject_bound.call("root regression may not be skipped") if tests.match?(/@unittest\.(skip|expectedFailure)/)
  puts "corrected-bound-contract: OK"
end
