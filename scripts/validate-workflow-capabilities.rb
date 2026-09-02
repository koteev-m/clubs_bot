#!/usr/bin/env ruby

require "open3"
require "pathname"
require "set"
require "yaml"
require_relative "validate-workflow-yaml"

module WorkflowCapabilityPolicy
  module_function

  CANONICAL_PUBLISHER = ".github/workflows/docker-publish.yml"
  CANONICAL_PUBLISHER_JOB = "build-and-push"
  PROVENANCE_VERIFIER_PATH = "scripts/verify-oci-provenance.py"
  GRADLE_VERIFICATION_METADATA_PATH = "gradle/verification-metadata.xml"
  DEPENDENCY_SUBMISSION_WORKFLOW = ".github/workflows/dependency-submission.yml"
  RELEASE_STATUS_WORKFLOW = ".github/workflows/release-status.yml"
  DEPLOY_JOBS = {
    [".github/workflows/deploy-ssh.yml", "deploy"] =>
      "${{ github.event_name == 'push' && 'prod' || inputs.environment }}",
    [".github/workflows/db-migrate.yml", "migrate"] =>
      "${{ github.event_name == 'push' && 'prod' || inputs.environment }}",
  }.freeze
  DEPLOY_SECRETS = Set.new(
    %w[COMPOSE_PATH SSH_HOST SSH_PORT SSH_PRIVATE_KEY SSH_USER]
  ).freeze
  RELEASE_STATUS_SECRETS = Set.new(
    %w[COMPOSE_PATH SSH_HOST SSH_KNOWN_HOSTS SSH_PORT SSH_PRIVATE_KEY SSH_USER]
  ).freeze
  CHECKOUT_ACTION = "actions/checkout@692973e3d937129bcbf40652eb9f2f61becf3332"
  SETUP_JAVA_ACTION = "actions/setup-java@b36c23c0d998641eff861008f374ee103c25ac73"
  DEPENDENCY_SUBMISSION_ACTION =
    "gradle/actions/dependency-submission@3f131e8634966bd73d06cc69884922b02e6faf92"
  SSH_AGENT_ACTION = "webfactory/ssh-agent@dc588b651fe13675774614f8e6a936a468676387"
  PUBLISHER_LOGIN_ACTION = "docker/login-action@9780b0c442fbb1117ed29e0efdff1e18412f7567"
  COSIGN_INSTALLER_ACTION = "sigstore/cosign-installer@1aa8e0f2454b781fbf0fbf306a4c9533a0c57409"
  TRIVY_ACTION = "aquasecurity/trivy-action@57a97c7e7821a5776cebc9bb87c984fa69cba8f1"
  UPLOAD_SARIF_ACTION = "github/codeql-action/upload-sarif@7c9a7896f03bb1f3de14c5663ed46759e27443e0"
  UPLOAD_ARTIFACT_ACTION = "actions/upload-artifact@b4b15b8c7c6ac21ea08fcf65892d2ee8f75cf882"
  INLINE_PROVENANCE_MARKERS = [
    "python3 - <<",
    "class SafeRedirectHandler",
    "SLSA_PROVENANCE =",
  ].freeze
  GRADLE_PLUGIN_141_METADATA = <<~'XML'.chomp
    <component group="org.gradle" name="github-dependency-graph-gradle-plugin" version="1.4.1">
    <artifact name="github-dependency-graph-gradle-plugin-1.4.1.jar">
    <sha256 value="571cc95ec821649ca14c3895d637e8071af8f219f1f2aa4588cff3a95ea429c5" origin="Verified from Gradle Plugin Portal"/>
    </artifact>
    <artifact name="github-dependency-graph-gradle-plugin-1.4.1.module">
    <sha256 value="ed793bb8d17435a8b37260a5d72808b76db832e9ca58d136980e34336b2c18b2" origin="Verified from Gradle Plugin Portal"/>
    </artifact>
    </component>
  XML

  CANONICAL_STEP_NAMES = {
    "build-and-push" => [
      "Checkout",
      "Set up QEMU (multi-arch emulation)",
      "Set up Docker Buildx",
      "Extract metadata (tags, labels)",
      "Log in to GHCR",
      "Build & Push",
      "Install cosign",
      "Sign image (keyless)",
      "Capture image reference",
      "Generate SBOM (CycloneDX via Syft)",
      "Upload SBOM",
    ],
    "verify-and-provenance" => [
      "Checkout",
      "Install cosign",
      "Verify image signature (keyless)",
      "Verify OCI graph and SLSA provenance",
      "Upload provenance attestation",
    ],
    "trivy-image" => [
      "Checkout",
      "Trivy image scan",
      "Upload Trivy image SARIF to code scanning",
      "Persist Trivy image report artifact",
    ],
  }.freeze

  DEPLOY_ORCHESTRATOR_ENV = {
    "SSH_USER" => "${{ secrets.SSH_USER }}",
    "SSH_HOST" => "${{ secrets.SSH_HOST }}",
    "SSH_PORT" => "${{ secrets.SSH_PORT || '22' }}",
    "COMPOSE_PATH" => "${{ secrets.COMPOSE_PATH }}",
    "REGISTRY_USERNAME" => "${{ github.actor }}",
    "REGISTRY_READ_TOKEN" => "${{ github.token }}",
    "IMAGE_REPOSITORY" => "ghcr.io/${{ github.repository }}/app-bot",
    "EXPECTED_REVISION" => "${{ github.sha }}",
  }.freeze

  EXPECTED_PRIVILEGED_JOBS = {
    [".github/workflows/deploy-ssh.yml", "deploy"] => {
      "runs-on" => "ubuntu-latest",
      "timeout-minutes" => 30,
      "permissions" => {"contents" => "read", "packages" => "read"},
      "environment" => "${{ github.event_name == 'push' && 'prod' || inputs.environment }}",
      "env" => {
        "APP_ENV" => "${{ github.event_name == 'push' && 'prod' || inputs.environment }}",
      },
      "steps" => [
        {
          "name" => "Checkout",
          "uses" => CHECKOUT_ACTION,
          "with" => {"persist-credentials" => false},
        },
        {
          "name" => "Derive immutable release inputs",
          "id" => "vars",
          "shell" => "bash",
          "run" => <<~'BASH',
            set -euo pipefail
            if [[ "${GITHUB_REF_TYPE:-}" == "tag" && "${GITHUB_REF_NAME:-}" =~ ^v.* ]]; then
              image_tag="${GITHUB_REF_NAME}"
            else
              image_tag="${{ inputs.image_tag }}"
            fi
            test -n "$image_tag"
            echo "image_tag=$image_tag" >> "$GITHUB_OUTPUT"
          BASH
        },
        {
          "name" => "Setup SSH agent",
          "uses" => SSH_AGENT_ACTION,
          "with" => {"ssh-private-key" => "${{ secrets.SSH_PRIVATE_KEY }}"},
        },
        {
          "name" => "Add host to known_hosts",
          "shell" => "bash",
          "run" => <<~'BASH',
            set -euo pipefail
            ssh-keyscan -p "${{ secrets.SSH_PORT || '22' }}" "${{ secrets.SSH_HOST }}" >> "$HOME/.ssh/known_hosts"
          BASH
        },
        {
          "name" => "Quiesce, migrate and start verified image",
          "env" => DEPLOY_ORCHESTRATOR_ENV.merge(
            "IMAGE_TAG" => "${{ steps.vars.outputs.image_tag }}"
          ),
          "run" => "scripts/deploy/quiesced-release.sh",
        },
        {
          "name" => "Done",
          "run" => 'echo "Quiesced release complete for ${APP_ENV}"',
        },
      ],
    },
    [".github/workflows/db-migrate.yml", "migrate"] => {
      "runs-on" => "ubuntu-latest",
      "timeout-minutes" => 30,
      "permissions" => {"contents" => "read", "packages" => "read"},
      "environment" => "${{ github.event_name == 'push' && 'prod' || inputs.environment }}",
      "env" => {"APP_ENV" => "${{ inputs.environment }}"},
      "steps" => [
        {
          "name" => "Require full quiesced release confirmation",
          "if" => "${{ !inputs.confirm_quiesced_release }}",
          "run" => <<~'BASH',
            echo "A standalone migration is forbidden; confirm the full quiesced release." >&2
            exit 1
          BASH
        },
        {
          "name" => "Checkout",
          "uses" => CHECKOUT_ACTION,
          "with" => {"persist-credentials" => false},
        },
        {
          "name" => "Setup SSH agent",
          "uses" => SSH_AGENT_ACTION,
          "with" => {"ssh-private-key" => "${{ secrets.SSH_PRIVATE_KEY }}"},
        },
        {
          "name" => "Add host to known_hosts",
          "shell" => "bash",
          "run" => <<~'BASH',
            set -euo pipefail
            ssh-keyscan -p "${{ secrets.SSH_PORT || '22' }}" "${{ secrets.SSH_HOST }}" >> "$HOME/.ssh/known_hosts"
          BASH
        },
        {
          "name" => "Quiesce, migrate and start verified image",
          "env" => DEPLOY_ORCHESTRATOR_ENV.merge(
            "IMAGE_TAG" => "${{ inputs.image_tag }}"
          ),
          "run" => "scripts/deploy/quiesced-release.sh",
        },
        {
          "name" => "Done",
          "run" => 'echo "Quiesced database release complete for ${APP_ENV}"',
        },
      ],
    },
  }.freeze

  EXPECTED_EFFECTIVE_PERMISSIONS = {
    [CANONICAL_PUBLISHER, CANONICAL_PUBLISHER_JOB] => {
      "contents" => "read",
      "packages" => "write",
      "id-token" => "write",
    },
    [CANONICAL_PUBLISHER, "verify-and-provenance"] => {
      "contents" => "read",
      "packages" => "read",
    },
    [CANONICAL_PUBLISHER, "trivy-image"] => {
      "contents" => "read",
      "packages" => "read",
      "security-events" => "write",
    },
    [".github/workflows/dependency-submission.yml", "submit"] => {
      "contents" => "write",
    },
    [".github/workflows/release.yml", "release"] => {
      "contents" => "write",
    },
    [".github/workflows/secret-scan.yml", "gitleaks"] => {
      "contents" => "read",
      "security-events" => "write",
    },
    [".github/workflows/security-scan.yml", "trivy"] => {
      "contents" => "read",
      "security-events" => "write",
    },
    [".github/workflows/static-check.yml", "detekt"] => {
      "contents" => "read",
      "security-events" => "write",
    },
    [".github/workflows/deploy-ssh.yml", "deploy"] => {
      "contents" => "read",
      "packages" => "read",
    },
    [".github/workflows/db-migrate.yml", "migrate"] => {
      "contents" => "read",
      "packages" => "read",
    },
    [RELEASE_STATUS_WORKFLOW, "validate"] => {
      "contents" => "read",
    },
    [RELEASE_STATUS_WORKFLOW, "status"] => {
      "contents" => "read",
    },
  }.freeze

  EXPECTED_TRIGGERS = {
    CANONICAL_PUBLISHER => {
      "push" => {"branches" => ["main"], "tags" => ["v*"]},
      "workflow_dispatch" => nil,
    },
    ".github/workflows/dependency-submission.yml" => {
      "push" => {"branches" => ["main"]},
      "workflow_dispatch" => nil,
    },
    ".github/workflows/release.yml" => {
      "push" => {"tags" => ["v*"]},
    },
    ".github/workflows/secret-scan.yml" => {
      "pull_request" => nil,
      "push" => {"branches" => ["main"]},
    },
    ".github/workflows/security-scan.yml" => {
      "push" => {"branches" => ["main", "master"], "tags" => ["v*"]},
      "pull_request" => nil,
      "workflow_dispatch" => nil,
    },
    ".github/workflows/static-check.yml" => {
      "pull_request" => nil,
      "push" => {"branches" => ["main", "master"]},
    },
    ".github/workflows/deploy-ssh.yml" => {
      "workflow_dispatch" => {
        "inputs" => {
          "environment" => {
            "description" => "Target environment",
            "required" => true,
            "default" => "stage",
            "type" => "choice",
            "options" => ["stage", "prod"],
          },
          "image_tag" => {
            "description" => "Published app-bot image tag for this Git revision",
            "required" => true,
            "type" => "string",
          },
        },
      },
      "push" => {"tags" => ["v*"]},
    },
    ".github/workflows/db-migrate.yml" => {
      "workflow_dispatch" => {
        "inputs" => {
          "environment" => {
            "description" => "Target environment",
            "required" => true,
            "default" => "stage",
            "type" => "choice",
            "options" => ["stage", "prod"],
          },
          "image_tag" => {
            "description" => "Published app-bot image tag for this Git revision",
            "required" => true,
            "type" => "string",
          },
          "confirm_quiesced_release" => {
            "description" => "Stop the app, migrate, and start only the selected new image",
            "required" => true,
            "default" => false,
            "type" => "boolean",
          },
        },
      },
    },
    RELEASE_STATUS_WORKFLOW => {
      "workflow_dispatch" => {
        "inputs" => {
          "environment" => {
            "description" => "Target environment",
            "required" => true,
            "type" => "choice",
            "options" => ["stage", "prod"],
          },
          "incident_tag" => {
            "description" => "Exact incident tag to observe",
            "required" => true,
            "type" => "string",
          },
          "release_owner" => {
            "description" => "Exact retained release owner",
            "required" => true,
            "type" => "string",
          },
          "expected_revision" => {
            "description" => "Exact expected Git revision",
            "required" => true,
            "type" => "string",
          },
          "image_digest" => {
            "description" => "Exact app-bot image digest",
            "required" => true,
            "type" => "string",
          },
          "requested_operation" => {
            "description" => "Exact retained release operation",
            "required" => true,
            "type" => "choice",
            "options" => %w[
              preflight prepare publish quiesce migrate start cleanup abort
              retention helper-cleanup resume-quiesce resume-migrate
              resume-start resume-cleanup
            ],
          },
        },
      },
    },
  }.freeze

  PERMISSION_NAMES = Set.new(
    %w[
      actions artifact-metadata attestations checks contents deployments
      discussions id-token issues models packages pages pull-requests
      security-events statuses vulnerability-alerts
    ]
  ).freeze
  REGISTRY_SECRET_NAMES = Set.new(
    %w[
      CR_PAT GHCR_TOKEN GHCR_USERNAME PACKAGE_TOKEN REGISTRY_TOKEN
      REGISTRY_USERNAME
    ]
  ).freeze
  REGISTRY_NAME_TOKENS = %w[GHCR REGISTRY DOCKER PACKAGE CONTAINER].freeze
  CREDENTIAL_NAME_TOKENS = %w[
    TOKEN PAT PASSWORD PASS SECRET CREDENTIAL AUTH KEY USERNAME
  ].freeze
  ROLLOUT_START_MARKER = "<!-- capability-rollout-order:start -->"
  ROLLOUT_END_MARKER = "<!-- capability-rollout-order:end -->"
  ROLLOUT_STEP_IDS = %w[
    CAPABILITY_POLICY_COMMIT
    DEPENDENCY_REMEDIATION_COMMIT
    PUSH_BOTH_COMMITS
    HOSTED_PR_CI_GREEN
    MERGE_PR
    GHCR_ACTIONS_READ_CONFIRMED
    STAGE_DEPLOY_GITHUB_TOKEN_GREEN
    RETIRE_LEGACY_GHCR_CREDENTIALS
  ].freeze
  ROLLOUT_RETIREMENT_ACTION_IDS = %w[
    DELETE_GHCR_TOKEN
    DELETE_UNUSED_GHCR_USERNAME
    REVOKE_LEGACY_PAT
    CLEAN_REMOTE_DOCKER_CREDENTIALS
  ].freeze
  ROLLOUT_REQUIRED_RETIREMENT_TARGETS = %w[
    GHCR_TOKEN
    GHCR_USERNAME
    PAT
    REMOTE_DOCKER_CREDENTIAL_CLEANUP
  ].freeze
  ROLLOUT_NEUTRAL_REFERENCE = "[RETIRE_LEGACY_GHCR_CREDENTIALS]"

  def rollout_escaped_identifier_source(identifier)
    identifier
      .split("_", -1)
      .map { |part| Regexp.escape(part) }
      .join('(?:\\\\)*_')
  end

  def rollout_markdown_target_pattern(body, boundary, options = 0)
    variants = ["(?<!#{boundary})#{body}(?!#{boundary})"]
    1.upto(3) do |width|
      delimiter = "_" * width
      variants << "(?<!#{boundary})#{delimiter}#{body}#{delimiter}(?!#{boundary})"
    end
    1.upto(3) do |width|
      delimiter = "\\*" * width
      variants <<
        "(?<!#{boundary})(?<!\\*)#{delimiter}#{body}#{delimiter}" \
        "(?!\\*)(?!#{boundary})"
    end
    Regexp.new("(?:#{variants.join('|')})", options)
  end

  # The outer boundary deliberately retains underscore as an identifier byte.
  # Markdown delimiters are consumed only by explicit 1/2/3-width alternatives,
  # so action IDs and compound identifiers never expose a target substring.
  ROLLOUT_RETIREMENT_TARGET_PATTERNS = [
    [
      "GHCR_TOKEN",
      rollout_markdown_target_pattern(
        rollout_escaped_identifier_source("GHCR_TOKEN"),
        "[A-Za-z0-9_]"
      )
    ],
    [
      "GHCR_USERNAME",
      rollout_markdown_target_pattern(
        rollout_escaped_identifier_source("GHCR_USERNAME"),
        "[A-Za-z0-9_]"
      )
    ],
    ["PAT", rollout_markdown_target_pattern("PAT", "[A-Za-z0-9_]")],
    [
      "REMOTE_DOCKER_CREDENTIAL_CLEANUP",
      rollout_markdown_target_pattern(
        rollout_escaped_identifier_source("REMOTE_DOCKER_CREDENTIAL_CLEANUP"),
        "[A-Za-z0-9_]"
      )
    ],
    [
      "legacy GHCR credentials",
      rollout_markdown_target_pattern(
        "legacy[[:space:]]+GHCR[[:space:]]+credentials?",
        '[\p{L}\p{N}_]',
        Regexp::IGNORECASE
      )
    ],
    [
      "GHCR credentials",
      rollout_markdown_target_pattern(
        "GHCR[[:space:]]+credentials?",
        '[\p{L}\p{N}_]',
        Regexp::IGNORECASE
      )
    ],
    [
      "registry credentials",
      rollout_markdown_target_pattern(
        "registry[[:space:]]+credentials?",
        '[\p{L}\p{N}_]',
        Regexp::IGNORECASE
      )
    ],
    [
      "legacy credentials",
      rollout_markdown_target_pattern(
        "legacy[[:space:]]+credentials?",
        '[\p{L}\p{N}_]',
        Regexp::IGNORECASE
      )
    ],
    [
      "устаревшие учётные данные",
      rollout_markdown_target_pattern(
        "устаревшие[[:space:]]+уч[её]тные[[:space:]]+данные",
        '[\p{L}\p{N}_]',
        Regexp::IGNORECASE
      )
    ],
    [
      "учётные данные GHCR",
      rollout_markdown_target_pattern(
        "уч[её]тные[[:space:]]+данные[[:space:]]+GHCR",
        '[\p{L}\p{N}_]',
        Regexp::IGNORECASE
      )
    ],
    [
      "секреты GHCR",
      rollout_markdown_target_pattern(
        "секреты[[:space:]]+GHCR",
        '[\p{L}\p{N}_]',
        Regexp::IGNORECASE
      )
    ],
    [
      "credentials",
      rollout_markdown_target_pattern(
        "credentials?",
        '[\p{L}\p{N}_]',
        Regexp::IGNORECASE
      )
    ],
  ].freeze
  ROLLOUT_STEP_LINES = [
    "1. [CAPABILITY_POLICY_COMMIT] Capability policy reviewed and committed.",
    "2. [DEPENDENCY_REMEDIATION_COMMIT] Fix all 22 HIGH findings in a separate commit; they remain blocking until then.",
    "3. [PUSH_BOTH_COMMITS] Push both commits without rewriting history.",
    "4. [HOSTED_PR_CI_GREEN] Hosted PR CI completed successfully.",
    "5. [MERGE_PR] Merge the PR only after hosted PR CI is green.",
    "6. [GHCR_ACTIONS_READ_CONFIRMED] Confirm GHCR Manage Actions Read access.",
    "7. [STAGE_DEPLOY_GITHUB_TOKEN_GREEN] Complete a successful stage deployment through `github.token`.",
    "8. [RETIRE_LEGACY_GHCR_CREDENTIALS] Only after successful step 7: [DELETE_GHCR_TOKEN] delete `GHCR_TOKEN`; [DELETE_UNUSED_GHCR_USERNAME] delete `GHCR_USERNAME` if unused; [REVOKE_LEGACY_PAT] revoke legacy `PAT`; [CLEAN_REMOTE_DOCKER_CREDENTIALS] complete `REMOTE_DOCKER_CREDENTIAL_CLEANUP` by inspecting and cleaning the remote Docker config and temporary directories.",
  ].freeze
  MAX_POLICY_TRAVERSAL_DEPTH = WorkflowYamlSafety::MAX_LOADED_CONTAINER_DEPTH
  MAX_POLICY_TRAVERSAL_NODES = 100_000
  RELEASE_METADATA_TAGS = [
    "type=sha,format=short",
    "type=ref,event=branch",
    "type=semver,pattern={{version}},prefix=v",
    "type=semver,pattern={{major}}.{{minor}},prefix=v",
  ].freeze

  def reject(message)
    warn "workflow-capabilities: #{message}"
    exit 1
  end

  def parse_permissions(value, context)
    case value
    when Hash
      reject("#{context}: permissions map must not be empty") if value.empty?
      value.each_with_object({}) do |(name, access), normalized|
        reject("#{context}: permission name is not a string") unless name.is_a?(String)
        reject("#{context}: unknown permission #{name.inspect}") unless PERMISSION_NAMES.include?(name)
        reject("#{context}: invalid access for #{name}") unless %w[none read write].include?(access)
        if name == "id-token" && access == "read"
          reject("#{context}: id-token only supports write or none")
        end
        normalized[name] = access unless access == "none"
      end
    when "write-all"
      reject("#{context}: write-all is forbidden")
    when "read-all"
      reject("#{context}: read-all is not an explicit minimal permission map")
    else
      reject("#{context}: permissions must be an explicit mapping")
    end
  end

  def visible_workflow_paths(root)
    WorkflowYamlSafety.visible_workflows(root)
  rescue WorkflowYamlSafety::ModelError => error
    reject(error.message.sub(/\Aworkflow-yaml: /, ""))
  end

  def ensure_regular_path(root, relative_path)
    current = root.to_s
    relative_path.split("/").each_with_index do |component, index|
      current = File.join(current, component)
      status = File.lstat(current)
      reject("#{relative_path}: path contains a symlink") if status.symlink?
      if index == relative_path.split("/").length - 1
        reject("#{relative_path}: visible path is not a regular file") unless status.file?
      else
        reject("#{relative_path}: parent is not a directory") unless status.directory?
      end
    end
  rescue SystemCallError => error
      reject("#{relative_path}: cannot inspect visible path (#{error.class})")
  end

  def validate_gradle_plugin_metadata(root)
    ensure_regular_path(root, GRADLE_VERIFICATION_METADATA_PATH)
    metadata = File.binread(root.join(GRADLE_VERIFICATION_METADATA_PATH))
    normalized = metadata.lines.map(&:strip).join("\n")
    count = normalized.scan(GRADLE_PLUGIN_141_METADATA).length
    reject("Gradle dependency-submission plugin 1.4.1 metadata changed") unless count == 1
  rescue SystemCallError => error
    reject("Gradle verification metadata is unreadable (#{error.class})")
  end


  def load_workflow(root, relative_path)
    ensure_regular_path(root, relative_path)
    raw = File.binread(root.join(relative_path))
    data = WorkflowYamlSafety.safe_load_workflow(raw, relative_path)
    [data, raw]
  rescue WorkflowYamlSafety::ModelError => error
    reject(error.message)
  rescue Psych::Exception, ArgumentError => error
    reject("#{relative_path}: malformed YAML (#{error.class})")
  rescue SystemStackError
    reject("#{relative_path}: depth: bounded YAML validation failed")
  rescue SystemCallError => error
    reject("#{relative_path}: workflow is unreadable (#{error.class})")
  end

  def workflow_triggers(workflow, path)
    keys = ["on", true].select { |key| workflow.key?(key) }
    reject("#{path}: trigger block is missing or ambiguous") unless keys.length == 1
    value = workflow.fetch(keys.first)
    reject("#{path}: trigger block must be a mapping") unless value.is_a?(Hash)
    value
  end

  def each_string(value, location = [], &block)
    active = {}
    visited = 0
    stack = [[:enter, value, location, 0]]
    until stack.empty?
      action, current, current_location, depth = stack.pop
      if action == :exit
        active.delete(current.object_id)
        next
      end
      if action == :key
        block.call(current, current_location)
        next
      end

      visited += 1
      if visited > MAX_POLICY_TRAVERSAL_NODES
        reject("policy traversal node count exceeds #{MAX_POLICY_TRAVERSAL_NODES}")
      end
      reject("policy traversal depth exceeds #{MAX_POLICY_TRAVERSAL_DEPTH}") if
        depth > MAX_POLICY_TRAVERSAL_DEPTH

      case current
      when String
        block.call(current, current_location)
      when Hash
        object_id = current.object_id
        reject("policy traversal encountered a recursive mapping") if active.key?(object_id)
        active[object_id] = true
        stack << [:exit, current, current_location, depth]
        current.to_a.reverse_each do |key, child|
          stack << [:enter, child, current_location + [key.to_s], depth + 1]
          if key.is_a?(String)
            stack << [:key, key, current_location + ["<key>"], depth + 1]
          end
        end
      when Array
        object_id = current.object_id
        reject("policy traversal encountered a recursive sequence") if active.key?(object_id)
        active[object_id] = true
        stack << [:exit, current, current_location, depth]
        (current.length - 1).downto(0) do |index|
          stack << [
            :enter,
            current[index],
            current_location + [index.to_s],
            depth + 1,
          ]
        end
      end
    end
  end

  def registry_credential_name?(name)
    return false unless name.is_a?(String)
    normalized = name.gsub(/([a-z0-9])([A-Z])/, '\1_\2')
      .upcase
      .gsub(/[^A-Z0-9]+/, "_")
      .gsub(/\A_+|_+\z/, "")
    tokens = normalized.split("_")
    registry = normalized.include?("CR_PAT") ||
      !(tokens & REGISTRY_NAME_TOKENS).empty?
    credential = normalized.include?("CR_PAT") ||
      !(tokens & CREDENTIAL_NAME_TOKENS).empty?
    registry && credential
  end

  def validate_env_map(value, context, allowed_registry_env = {})
    return if value.nil?
    reject("#{context}: env must be a mapping") unless value.is_a?(Hash)
    value.each do |name, source|
      reject("#{context}: env name must be a string") unless name.is_a?(String)
      next unless registry_credential_name?(name)
      next if allowed_registry_env[name] == source
      reject("#{context}: registry-looking credential env is forbidden: #{name}")
    end
  end

  def scalar_inventory(value, context)
    secret_names = Set.new
    github_token_refs = 0
    each_string(value) do |text, location|
      if text.match?(/\bsecrets\s*\[/i)
        reject("#{context}: dynamic secrets access at #{location.join('.')}")
      end
      if text.match?(/\btoJSON\s*\(\s*secrets\s*\)/i)
        reject("#{context}: toJSON(secrets) is forbidden")
      end
      if text.match?(/\b(?:vars|env)\s*\[/i)
        reject("#{context}: dynamic vars/env access at #{location.join('.')}")
      end
      text.scan(/\bsecrets\.([A-Za-z_][A-Za-z0-9_]*)/) do |match|
        name = match.first
        if registry_credential_name?(name)
          reject("#{context}: registry-looking credential secret is forbidden: #{name}")
        end
        secret_names << name
      end
      text.scan(/\b(?:vars|env)\.([A-Za-z_][A-Za-z0-9_]*)/i) do |match|
        name = match.first
        next unless registry_credential_name?(name)
        reject(
          "#{context}: registry-looking vars/env reference at #{location.join('.')}: #{name}"
        )
      end
      github_token_refs += text.scan(/\bgithub\.token\b/).length
    end
    [secret_names, github_token_refs]
  end

  def step_list(job, context)
    steps = job["steps"]
    return [] if steps.nil?
    reject("#{context}: steps must be a list") unless steps.is_a?(Array)
    steps.each do |step|
      reject("#{context}: malformed step") unless step.is_a?(Hash)
    end
    steps
  end

  def validate_exact_value(actual, expected, context)
    visited = 0
    stack = [[actual, expected, context, 0]]
    until stack.empty?
      current, wanted, current_context, depth = stack.pop
      visited += 1
      if visited > MAX_POLICY_TRAVERSAL_NODES
        reject("#{context}: exact-value traversal node count exceeds #{MAX_POLICY_TRAVERSAL_NODES}")
      end
      if depth > MAX_POLICY_TRAVERSAL_DEPTH
        reject("#{context}: exact-value traversal depth exceeds #{MAX_POLICY_TRAVERSAL_DEPTH}")
      end

      if wanted.is_a?(Hash)
        reject("#{current_context}: expected a mapping") unless current.is_a?(Hash)
        missing = wanted.keys - current.keys
        unexpected = current.keys - wanted.keys
        reject("#{current_context}: missing field #{missing.first.inspect}") unless missing.empty?
        reject("#{current_context}: unexpected field #{unexpected.first.inspect}") unless unexpected.empty?
        wanted.to_a.reverse_each do |name, wanted_value|
          stack << [
            current[name],
            wanted_value,
            "#{current_context}/#{name}",
            depth + 1,
          ]
        end
      elsif wanted.is_a?(Array)
        reject("#{current_context}: expected a list") unless current.is_a?(Array)
        unless current.length == wanted.length
          reject(
            "#{current_context}: expected list length #{wanted.length}, got #{current.length}"
          )
        end
        (wanted.length - 1).downto(0) do |index|
          stack << [
            current[index],
            wanted[index],
            "#{current_context}[#{index}]",
            depth + 1,
          ]
        end
      elsif current != wanted
        reject("#{current_context}: expected #{wanted.inspect}, got #{current.inspect}")
      end
    end
  end

  def validate_exact_steps(actual, expected, context)
    reject("#{context}: steps must be a list") unless actual.is_a?(Array)
    unless actual.length == expected.length
      reject("#{context}: exact step count changed; expected #{expected.length}, got #{actual.length}")
    end
    actual.zip(expected).each_with_index do |(actual_step, expected_step), index|
      reject("#{context}/step[#{index}]: step must be a mapping") unless actual_step.is_a?(Hash)
      identity = expected_step["name"] || expected_step["id"] || "step #{index}"
      validate_exact_value(actual_step, expected_step, "#{context}/step[#{index}] #{identity}")
    end
  end

  def validate_canonical_step_inventory(job_name, job, context)
    expected_names = CANONICAL_STEP_NAMES.fetch(job_name)
    steps = step_list(job, context)
    actual_names = steps.map.with_index do |step, index|
      name = step["name"]
      reject("#{context}/step[#{index}]: every canonical step must have a name") unless
        name.is_a?(String) && !name.empty?
      name
    end
    reject("#{context}: canonical step names must be unique") unless
      actual_names.uniq.length == actual_names.length
    unless actual_names == expected_names
      reject(
        "#{context}: exact canonical step inventory changed; " \
        "expected #{expected_names.inspect}, got #{actual_names.inspect}"
      )
    end
    steps
  end

  def validate_build_and_push_job(job, steps, context)
    validate_exact_value(
      job.reject { |key, _value| key == "steps" },
      {
        "if" => "github.event_name == 'push' || github.ref == 'refs/heads/main'",
        "runs-on" => "ubuntu-latest",
        "permissions" => {
          "contents" => "read",
          "packages" => "write",
          "id-token" => "write",
        },
        "outputs" => {
          "image-ref" => "${{ steps.image-ref.outputs.image }}",
        },
      },
      "#{context}/job fields"
    )
    validate_exact_steps(
      steps,
      [
        {
          "name" => "Checkout",
          "uses" => CHECKOUT_ACTION,
          "with" => {"persist-credentials" => false},
        },
        {
          "name" => "Set up QEMU (multi-arch emulation)",
          "uses" => "docker/setup-qemu-action@49b3bc8e6bdd4a60e6116a5414239cba5943d3cf",
        },
        {
          "name" => "Set up Docker Buildx",
          "uses" => "docker/setup-buildx-action@6524bf65af31da8d45b59e8c27de4bd072b392f5",
        },
        {
          "name" => "Extract metadata (tags, labels)",
          "id" => "meta",
          "uses" => "docker/metadata-action@8e5442c4ef9f78752691e2d8f8d19755c6f78e81",
          "with" => {
            "images" => "${{ env.IMAGE_NAME }}",
            "tags" => <<~'TAGS',
              type=sha,format=short
              type=ref,event=branch
              type=semver,pattern={{version}},prefix=v
              type=semver,pattern={{major}}.{{minor}},prefix=v
            TAGS
            "labels" => <<~'LABELS',
              org.opencontainers.image.source=${{ github.repository }}
              org.opencontainers.image.revision=${{ github.sha }}
              org.opencontainers.image.title=app-bot
            LABELS
          },
        },
        {
          "name" => "Log in to GHCR",
          "uses" => PUBLISHER_LOGIN_ACTION,
          "with" => {
            "registry" => "ghcr.io",
            "username" => "${{ github.actor }}",
            "password" => "${{ github.token }}",
          },
        },
        {
          "name" => "Build & Push",
          "id" => "build",
          "uses" => "docker/build-push-action@4f58ea79222b3b9dc2c8bbdd6debcef730109a75",
          "with" => {
            "context" => ".",
            "file" => "Dockerfile",
            "push" => true,
            "platforms" => "linux/amd64,linux/arm64",
            "tags" => "${{ steps.meta.outputs.tags }}",
            "labels" => "${{ steps.meta.outputs.labels }}",
            "cache-from" => "type=gha",
            "cache-to" => "type=gha,mode=max",
            "provenance" => true,
          },
        },
        {
          "name" => "Install cosign",
          "uses" => COSIGN_INSTALLER_ACTION,
        },
        {
          "name" => "Sign image (keyless)",
          "env" => {"COSIGN_EXPERIMENTAL" => "true"},
          "run" => "cosign sign --yes ${{ env.IMAGE_NAME }}@${{ steps.build.outputs.digest }}",
        },
        {
          "name" => "Capture image reference",
          "id" => "image-ref",
          "run" => 'echo "image=${{ env.IMAGE_NAME }}@${{ steps.build.outputs.digest }}" >> "$GITHUB_OUTPUT"',
        },
        {
          "name" => "Generate SBOM (CycloneDX via Syft)",
          "uses" => "anchore/sbom-action@251a468eed47e5082b105c3ba6ee500c0e65a764",
          "with" => {
            "image" => "${{ env.IMAGE_NAME }}@${{ steps.build.outputs.digest }}",
            "format" => "cyclonedx-json",
            "output-file" => "sbom.cdx.json",
          },
        },
        {
          "name" => "Upload SBOM",
          "uses" => UPLOAD_ARTIFACT_ACTION,
          "with" => {
            "name" => "sbom",
            "path" => "sbom.cdx.json",
          },
        },
      ],
      context
    )
  end

  def validate_provenance_job(job, steps, context)
    validate_exact_value(
      job.reject { |key, _value| key == "steps" },
      {
        "needs" => CANONICAL_PUBLISHER_JOB,
        "runs-on" => "ubuntu-latest",
        "permissions" => {
          "contents" => "read",
          "packages" => "read",
        },
      },
      "#{context}/job fields"
    )

    validate_exact_value(
      steps.fetch(0),
      {
        "name" => "Checkout",
        "uses" => CHECKOUT_ACTION,
        "with" => {"persist-credentials" => false},
      },
      "#{context}/checkout"
    )
    validate_exact_value(
      steps.fetch(1),
      {
        "name" => "Install cosign",
        "uses" => COSIGN_INSTALLER_ACTION,
      },
      "#{context}/Cosign installer"
    )
    validate_exact_value(
      steps.fetch(2),
      {
        "name" => "Verify image signature (keyless)",
        "env" => {
          "COSIGN_EXPERIMENTAL" => "true",
          "IMAGE_REF" => "${{ needs.build-and-push.outputs.image-ref }}",
        },
        "run" => <<~'BASH',
          set -euo pipefail
          cosign verify \
            --certificate-oidc-issuer https://token.actions.githubusercontent.com \
            --certificate-identity "https://github.com/$GITHUB_WORKFLOW_REF" \
            --certificate-github-workflow-repository "$GITHUB_REPOSITORY" \
            --certificate-github-workflow-ref "$GITHUB_REF" \
            --certificate-github-workflow-sha "$GITHUB_SHA" \
            --certificate-github-workflow-name "$GITHUB_WORKFLOW" \
            --certificate-github-workflow-trigger "$GITHUB_EVENT_NAME" \
            "$IMAGE_REF"
        BASH
      },
      "#{context}/signed index verification"
    )

    # This is deliberately a structural contract only. Executable in-repo tests
    # cover the currently checked-out implementation. A whole-file SHA would be
    # only a change detector, not semantic proof; simultaneous malicious changes
    # to implementation and tests are controlled by external review/rulesets.
    validate_exact_value(
      steps.fetch(3),
      {
        "name" => "Verify OCI graph and SLSA provenance",
        "shell" => "bash",
        "env" => {
          "GH_TOKEN" => "${{ github.token }}",
          "REGISTRY_ACTOR" => "${{ github.actor }}",
          "IMAGE_REF" => "${{ needs.build-and-push.outputs.image-ref }}",
          "EXPECTED_REPOSITORY" => "${{ github.repository }}",
          "EXPECTED_REF" => "${{ github.ref }}",
          "EXPECTED_SHA" => "${{ github.sha }}",
          "EXPECTED_RUN_ID" => "${{ github.run_id }}",
          "EXPECTED_RUN_ATTEMPT" => "${{ github.run_attempt }}",
          "EXPECTED_WORKFLOW_REF" => "${{ github.workflow_ref }}",
          "EXPECTED_WORKFLOW_NAME" => "${{ github.workflow }}",
          "EXPECTED_EVENT_NAME" => "${{ github.event_name }}",
        },
        "run" => <<~'BASH',
          set -euo pipefail
          python3 scripts/verify-oci-provenance.py \
            --image-ref "$IMAGE_REF" \
            --repository "$EXPECTED_REPOSITORY" \
            --ref "$EXPECTED_REF" \
            --sha "$EXPECTED_SHA" \
            --run-id "$EXPECTED_RUN_ID" \
            --run-attempt "$EXPECTED_RUN_ATTEMPT" \
            --workflow-ref "$EXPECTED_WORKFLOW_REF" \
            --workflow-name "$EXPECTED_WORKFLOW_NAME" \
            --event-name "$EXPECTED_EVENT_NAME"
        BASH
      },
      "#{context}/provenance verifier invocation"
    )

    validate_exact_value(
      steps.fetch(4),
      {
        "name" => "Upload provenance attestation",
        "uses" => UPLOAD_ARTIFACT_ACTION,
        "with" => {
          "name" => "slsa-provenance",
          "path" => "provenance.jsonl",
          "if-no-files-found" => "error",
        },
      },
      "#{context}/verified provenance artifact"
    )
  end

  def validate_trivy_image_job(job, steps, context)
    validate_exact_value(
      job.reject { |key, _value| key == "steps" },
      {
        "needs" => CANONICAL_PUBLISHER_JOB,
        "runs-on" => "ubuntu-latest",
        "permissions" => {
          "contents" => "read",
          "packages" => "read",
          "security-events" => "write",
        },
      },
      "#{context}/job fields"
    )

    validate_exact_value(
      steps.fetch(0),
      {
        "name" => "Checkout",
        "uses" => CHECKOUT_ACTION,
        "with" => {"persist-credentials" => false},
      },
      "#{context}/checkout"
    )
    validate_exact_value(
      steps.fetch(1),
      {
        "name" => "Trivy image scan",
        "uses" => TRIVY_ACTION,
        "with" => {
          "scan-type" => "image",
          "image-ref" => "${{ needs.build-and-push.outputs.image-ref }}",
          "severity" => "HIGH,CRITICAL",
          "limit-severities-for-sarif" => true,
          "trivyignores" => ".trivyignore",
          "format" => "sarif",
          "output" => "trivy-image-results.sarif",
          "exit-code" => 1,
          "version" => "v0.69.3",
        },
      },
      "#{context}/blocking Trivy image scan"
    )
    validate_exact_value(
      steps.fetch(2),
      {
        "name" => "Upload Trivy image SARIF to code scanning",
        "if" => "${{ always() && hashFiles('trivy-image-results.sarif') != '' }}",
        "uses" => UPLOAD_SARIF_ACTION,
        "with" => {
          "sarif_file" => "trivy-image-results.sarif",
          "category" => "trivy-release-image",
        },
      },
      "#{context}/Trivy SARIF upload"
    )
    validate_exact_value(
      steps.fetch(3),
      {
        "name" => "Persist Trivy image report artifact",
        "if" => "${{ always() && hashFiles('trivy-image-results.sarif') != '' }}",
        "uses" => UPLOAD_ARTIFACT_ACTION,
        "with" => {
          "name" => "trivy-image-report",
          "path" => "trivy-image-results.sarif",
          "if-no-files-found" => "error",
        },
      },
      "#{context}/Trivy report artifact"
    )
  end

  def validate_dependency_submission_job(path, job_name, job)
    return unless path == DEPENDENCY_SUBMISSION_WORKFLOW && job_name == "submit"

    context = "#{path}/#{job_name}"
    steps = step_list(job, context)
    validate_exact_value(
      job.reject { |key, _value| key == "steps" },
      {
        "if" => "github.event_name == 'push' || github.ref == 'refs/heads/main'",
        "runs-on" => "ubuntu-latest",
        "timeout-minutes" => 30,
        "permissions" => {"contents" => "write"},
      },
      "#{context}/job fields"
    )
    validate_exact_steps(
      steps,
      [
        {
          "name" => "Checkout",
          "uses" => CHECKOUT_ACTION,
          "with" => {"persist-credentials" => false},
        },
        {
          "name" => "Set up JDK 21",
          "uses" => SETUP_JAVA_ACTION,
          "with" => {
            "distribution" => "temurin",
            "java-version" => "21",
          },
        },
        {
          "name" => "Verify resolved production dependency graph (blocking)",
          "run" =>
            "./gradlew verifyResolvedProductionDependencyGraph " \
            "--dependency-verification=strict --no-configuration-cache --console=plain",
        },
        {
          "name" => "Submit resolved dependency graph (blocking)",
          "uses" => DEPENDENCY_SUBMISSION_ACTION,
          "with" => {
            "gradle-version" => "wrapper",
            "validate-wrappers" => true,
            "cache-provider" => "basic",
            "dependency-graph" => "generate-and-submit",
            "dependency-resolution-task" =>
              "verifyResolvedProductionDependencyGraph " \
              "ForceDependencyResolutionPlugin_resolveAllDependencies",
            "dependency-graph-report-dir" => "build/reports/dependency-submission",
            "dependency-graph-continue-on-failure" => false,
            "additional-arguments" =>
              "--dependency-verification=strict --no-configuration-cache --stacktrace",
          },
        },
      ],
      context
    )
  end

  def validate_canonical_job_contract(path, job_name, job)
    return unless path == CANONICAL_PUBLISHER
    context = "#{path}/#{job_name}"
    steps = validate_canonical_step_inventory(job_name, job, context)
    case job_name
    when "build-and-push"
      validate_build_and_push_job(job, steps, context)
    when "verify-and-provenance"
      validate_provenance_job(job, steps, context)
    when "trivy-image"
      validate_trivy_image_job(job, steps, context)
    end
  end

  # Defense-in-depth only: deliberately recognizes obvious literal command starts.
  # Capability and credential policy, not shell semantic analysis, proves exclusivity.
  def literal_shell_publisher?(run)
    return false unless run.is_a?(String)
    run.each_line.any? do |line|
      stripped = line.lstrip
      next false if stripped.start_with?("#")
      stripped.match?(%r{\A(?:/usr/bin/)?docker\s+(?:image\s+)?push(?:\s|$)}) ||
        stripped.match?(%r{\A(?:/usr/bin/)?docker\s+buildx\b[^\n]*\s--push(?:=true)?(?:\s|$)})
    end
  end

  def metadata_tags(step)
    value = step.dig("with", "tags")
    return [] if value.nil?
    reject("metadata action tags must be a string") unless value.is_a?(String)
    value.lines.map(&:strip).reject(&:empty?)
  end

  def validate_registry_and_literal_policy(path, job_name, job, effective)
    context = "#{path}/#{job_name}"
    canonical = path == CANONICAL_PUBLISHER && job_name == CANONICAL_PUBLISHER_JOB
    login_count = 0
    publisher_count = 0
    metadata_count = 0
    validate_env_map(job["env"], "#{context}/env") if job.key?("env")

    step_list(job, context).each_with_index do |step, index|
      allowed_registry_env = if DEPLOY_JOBS.key?([path, job_name]) &&
                                step["run"] == "scripts/deploy/quiesced-release.sh"
                               {
                                 "REGISTRY_USERNAME" => "${{ github.actor }}",
                                 "REGISTRY_READ_TOKEN" => "${{ github.token }}",
                               }
                             else
                               {}
                             end
      validate_env_map(step["env"], "#{context}/step[#{index}]/env", allowed_registry_env) if
        step.key?("env")
      uses = step["uses"]
      if uses.is_a?(String) && uses.start_with?("docker/login-action@")
        reject("#{context}: Docker login is outside the canonical publisher") unless canonical
        login_count += 1
        expected_login = {
          "name" => "Log in to GHCR",
          "uses" => PUBLISHER_LOGIN_ACTION,
          "with" => {
            "registry" => "ghcr.io",
            "username" => "${{ github.actor }}",
            "password" => "${{ github.token }}",
          },
        }
        validate_exact_value(step, expected_login, "#{context}/canonical GHCR login")
        unless effective["packages"] == (canonical ? "write" : "read")
          reject("#{context}: Docker login lacks the exact packages capability")
        end
      end

      if uses.is_a?(String) && uses.start_with?("docker/build-push-action@")
        push = step.dig("with", "push")
        if push == true || push == "true"
          reject("#{context}: build-push publication is outside canonical publisher") unless canonical
          publisher_count += 1
        end
      end

      if uses.is_a?(String) && uses.start_with?("docker/metadata-action@")
        tags = metadata_tags(step)
        if canonical
          metadata_count += 1
          reject("#{context}: canonical release metadata tags changed") unless tags == RELEASE_METADATA_TAGS
          reject("#{context}: duplicate canonical release metadata tags") unless tags.uniq == tags
        elsif !(tags & RELEASE_METADATA_TAGS).empty?
          reject("#{context}: duplicate release metadata tags outside canonical publisher")
        end
      end

      if literal_shell_publisher?(step["run"])
        reject("#{context}: literal Docker publisher is forbidden (defense-in-depth)")
      end
    end

    return unless canonical
    reject("#{context}: canonical GHCR login count changed") unless login_count == 1
    reject("#{context}: canonical publisher action count changed") unless publisher_count == 1
    reject("#{context}: canonical metadata action count changed") unless metadata_count == 1
  end

  def expected_secret_names(path, job_name)
    key = [path, job_name]
    return DEPLOY_SECRETS if DEPLOY_JOBS.key?(key)
    return RELEASE_STATUS_SECRETS if key == [RELEASE_STATUS_WORKFLOW, "status"]
    return Set.new(["GITHUB_TOKEN"]) if key == [".github/workflows/release.yml", "release"]
    Set.new
  end

  def expected_github_token_refs(path, job_name)
    key = [path, job_name]
    return 1 if key == [CANONICAL_PUBLISHER, CANONICAL_PUBLISHER_JOB]
    return 1 if key == [CANONICAL_PUBLISHER, "verify-and-provenance"]
    return 1 if DEPLOY_JOBS.key?(key)
    0
  end

  def validate_job_secrets(path, job_name, job)
    context = "#{path}/#{job_name}"
    if job["secrets"] == "inherit"
      reject("#{context}: secrets: inherit is forbidden")
    end
    secret_names, github_token_refs = scalar_inventory(job, context)
    expected_names = expected_secret_names(path, job_name)
    unless secret_names == expected_names
      reject(
        "#{context}: secret allowlist mismatch; expected #{expected_names.to_a.sort.inspect}, " \
        "got #{secret_names.to_a.sort.inspect}"
      )
    end
    registry_secrets = secret_names & REGISTRY_SECRET_NAMES
    unless registry_secrets.empty?
      reject("#{context}: registry credential secret is forbidden: #{registry_secrets.to_a.sort.join(', ')}")
    end
    expected_token_refs = expected_github_token_refs(path, job_name)
    unless github_token_refs == expected_token_refs
      reject(
        "#{context}: github.token reference count changed; " \
        "expected #{expected_token_refs}, got #{github_token_refs}"
      )
    end
  end

  def validate_environment(path, job_name, job)
    key = [path, job_name]
    environment = job["environment"]
    expected = DEPLOY_JOBS[key]
    expected = "${{ needs.validate.outputs.environment }}" if
      key == [RELEASE_STATUS_WORKFLOW, "status"]
    if expected
      reject("#{path}/#{job_name}: protected environment contract changed") unless environment == expected
    elsif !environment.nil?
      reject("#{path}/#{job_name}: environment use is not allowlisted")
    end
  end

  def validate_deploy_job_contract(path, job_name, job)
    expected = EXPECTED_PRIVILEGED_JOBS[[path, job_name]]
    return unless expected
    context = "#{path}/#{job_name}"
    reject("#{context}: privileged job must be a mapping") unless job.is_a?(Hash)
    missing = expected.keys - job.keys
    unexpected = job.keys - expected.keys
    reject("#{context}: missing privileged job field #{missing.first.inspect}") unless missing.empty?
    reject("#{context}: unexpected privileged job field #{unexpected.first.inspect}") unless unexpected.empty?
    expected.each do |name, expected_value|
      if name == "steps"
        validate_exact_steps(job[name], expected_value, context)
      else
        validate_exact_value(job[name], expected_value, "#{context}/#{name}")
      end
    end
  end

  def validate_release_status_exact_contract(path, workflow, jobs, raw)
    top_keys = workflow.keys.map { |key| key == true ? "on" : key.to_s }
    reject("#{path}: top-level workflow surface changed") unless
      top_keys.sort == %w[concurrency jobs name on permissions].sort
    concurrency = workflow["concurrency"]
    reject("#{path}: concurrency must be a mapping") unless concurrency.is_a?(Hash)
    reject("#{path}: shared environment concurrency group changed") unless
      concurrency["group"] == "payments-schema-${{ inputs.environment }}"
    reject("#{path}: status concurrency must not cancel") unless
      concurrency["cancel-in-progress"] == false
    reject("#{path}: concurrency surface changed") unless
      concurrency.keys.sort == %w[cancel-in-progress group]
    reject("#{path}: job inventory must be exactly validate and status") unless
      jobs.keys.sort == %w[status validate]
    reject("#{path}: live ssh-keyscan is forbidden") if raw.match?(/\bssh-keyscan\b/)
    reject("#{path}: artifact publication is forbidden") if
      raw.match?(/actions\/upload-artifact@|actions\/upload-pages-artifact@/)
    reject("#{path}: registry login is forbidden") if
      raw.match?(/docker\/login-action@|\bdocker\s+login\b/)
    reject("#{path}: image pull is forbidden") if
      raw.match?(/\bdocker\s+(?:image\s+)?pull\b/)
    reject("#{path}: deploy runner invocation is forbidden") if
      raw.include?("scripts/deploy/quiesced-release.sh")
    reject("#{path}: scp transport is forbidden") if
      raw.match?(/(^|[^[:alnum:]_-])scp(?:[[:space:]]|$)/)
    reject("#{path}: hidden SSH invocation is forbidden") if
      raw.match?(/(?:^|\n)\s*(?:command\s+)?ssh(?:\s|$)/)
    runner_references = raw.scan(
      %r{implementation/scripts/deploy/read-only-release-status\.sh}
    ).length
    reject("#{path}: status runner must be invoked exactly once") unless
      runner_references == 1

    validate_job = jobs.fetch("validate")
    status_job = jobs.fetch("status")
    reject("#{path}/validate: job must be a mapping") unless validate_job.is_a?(Hash)
    reject("#{path}/status: job must be a mapping") unless status_job.is_a?(Hash)
    reject("#{path}/validate: job-level continue-on-error is forbidden") if
      validate_job.key?("continue-on-error")
    reject("#{path}/status: job-level continue-on-error is forbidden") if
      status_job.key?("continue-on-error")
    reject("#{path}: status jobs must not use a job-level condition") if
      validate_job.key?("if") || status_job.key?("if")
    reject("#{path}/validate: environment use is forbidden") if validate_job.key?("environment")
    reject("#{path}/status: needs must be exactly validate") unless
      status_job["needs"] == "validate"
    reject("#{path}/status: protected environment must use validated output") unless
      status_job["environment"] == "${{ needs.validate.outputs.environment }}"

    validate_steps = step_list(validate_job, "#{path}/validate")
    status_steps = step_list(status_job, "#{path}/status")
    unless validate_steps.first.is_a?(Hash) &&
           validate_steps.first["name"] == "Require main dispatch"
      reject("#{path}/validate: main/ref guard must be the first executable validation guard")
    end
    checkout_count = status_steps.count { |step| step["uses"] == CHECKOUT_ACTION }
    reject("#{path}/status: must contain exactly two pinned checkouts") unless
      checkout_count == 2
    incident_execution = status_steps.map { |step| step["run"] }.
      select { |run| run.is_a?(String) }.flat_map(&:lines).any? do |line|
      stripped = line.strip
      next false if stripped.empty? || stripped.start_with?("#")
      stripped.match?(%r{(?:^|[[:space:]])(?:bash|sh|source|\.)[[:space:]]+[^\n]*incident/}) ||
        stripped.match?(%r{(?:^|[[:space:]])incident/[^[:space:]]+})
    end
    reject("#{path}/status: incident checkout code execution is forbidden") if
      incident_execution
    reject("#{path}/validate: guard and sanitizer step inventory changed") unless
      validate_steps.length == 2
    reject("#{path}/status: mandatory seven-step inventory changed") unless
      status_steps.length == 7
    (validate_steps + status_steps).each do |step|
      reject("#{path}: status channel must not continue on error") if
        step.key?("continue-on-error")
      reject("#{path}: mandatory steps must be unconditional") if step.key?("if")
      if step.key?("shell") && step["shell"] != "bash"
        reject("#{path}: mandatory run step shell must be exactly bash")
      end
    end
    shell_source = (validate_steps + status_steps).map { |step| step["run"] }.
      select { |run| run.is_a?(String) }.join("\n")
    if shell_source.match?(/(?:^|\n)\s*set\s+\+e(?:\s|$)|\|\|\s*true(?:\s|$)|\bssh_exit=0\b/)
      reject("#{path}: fail-open custom shell is forbidden")
    end

    expected_outputs = %w[
      environment incident_tag release_owner expected_revision image_digest
      requested_operation
    ].to_h do |name|
      [name, "${{ steps.sanitize.outputs.#{name} }}"]
    end
    expected_guard = {
      "name" => "Require main dispatch",
      "shell" => "bash",
      "env" => {
        "REPOSITORY_DEFAULT_BRANCH" => "${{ github.event.repository.default_branch }}",
      },
      "run" => <<~'BASH',
        set -euo pipefail
        test "$GITHUB_REF" = "refs/heads/main"
        test "$GITHUB_REF_TYPE" = "branch"
        test "$REPOSITORY_DEFAULT_BRANCH" = "main"
      BASH
    }
    expected_sanitizer = {
      "name" => "Validate and sanitize status inputs",
      "id" => "sanitize",
      "shell" => "bash",
      "env" => {
        "INPUT_ENVIRONMENT" => "${{ inputs.environment }}",
        "INPUT_INCIDENT_TAG" => "${{ inputs.incident_tag }}",
        "INPUT_RELEASE_OWNER" => "${{ inputs.release_owner }}",
        "INPUT_EXPECTED_REVISION" => "${{ inputs.expected_revision }}",
        "INPUT_IMAGE_DIGEST" => "${{ inputs.image_digest }}",
        "INPUT_REQUESTED_OPERATION" => "${{ inputs.requested_operation }}",
      },
      "run" => <<~'BASH',
        set -euo pipefail
        reject() {
          printf 'release-status-validation: invalid %s\n' "$1" >&2
          exit 2
        }
        case "$INPUT_ENVIRONMENT" in
          stage|prod) ;;
          *) reject environment ;;
        esac
        [[ "$INPUT_INCIDENT_TAG" =~ ^deploy-(stage|prod)-[0-9a-f]{7,40}$ ]] || reject incident_tag
        tag_environment="${BASH_REMATCH[1]}"
        test "$tag_environment" = "$INPUT_ENVIRONMENT" || reject incident_tag_environment
        [[ "$INPUT_RELEASE_OWNER" =~ ^[0-9]+-[0-9]+$ ]] || reject release_owner
        [[ "$INPUT_EXPECTED_REVISION" =~ ^[0-9a-f]{40}$ ]] || reject expected_revision
        [[ "$INPUT_IMAGE_DIGEST" =~ ^ghcr\.io/koteev-m/clubs_bot/app-bot@sha256:[0-9a-f]{64}$ ]] || reject image_digest
        case "$INPUT_REQUESTED_OPERATION" in
          preflight|prepare|publish|quiesce|migrate|start|cleanup|abort|retention|helper-cleanup|resume-quiesce|resume-migrate|resume-start|resume-cleanup) ;;
          *) reject requested_operation ;;
        esac
        {
          printf 'environment=%s\n' "$INPUT_ENVIRONMENT"
          printf 'incident_tag=%s\n' "$INPUT_INCIDENT_TAG"
          printf 'release_owner=%s\n' "$INPUT_RELEASE_OWNER"
          printf 'expected_revision=%s\n' "$INPUT_EXPECTED_REVISION"
          printf 'image_digest=%s\n' "$INPUT_IMAGE_DIGEST"
          printf 'requested_operation=%s\n' "$INPUT_REQUESTED_OPERATION"
        } >>"$GITHUB_OUTPUT"
      BASH
    }
    unless validate_steps.fetch(0) == expected_guard
      reject("#{path}/validate: main/ref guard must be exact and fail closed")
    end
    unless validate_steps.fetch(1) == expected_sanitizer
      reject("#{path}/validate: sanitizer must enforce the exact input grammar and sanitized outputs")
    end

    expected_status_steps = [
      {
        "name" => "Checkout implementation main",
        "uses" => CHECKOUT_ACTION,
        "with" => {
          "ref" => "refs/heads/main",
          "path" => "implementation",
          "persist-credentials" => false,
        },
      },
      {
        "name" => "Verify implementation revision",
        "shell" => "bash",
        "run" => <<~'BASH',
          set -euo pipefail
          implementation_head="$(git -C implementation rev-parse HEAD)"
          test "$implementation_head" = "$GITHUB_SHA"
        BASH
      },
      {
        "name" => "Checkout incident tag",
        "uses" => CHECKOUT_ACTION,
        "with" => {
          "ref" => "refs/tags/${{ needs.validate.outputs.incident_tag }}",
          "path" => "incident",
          "persist-credentials" => false,
        },
      },
      {
        "name" => "Verify incident revision",
        "shell" => "bash",
        "env" => {
          "EXPECTED_REVISION" => "${{ needs.validate.outputs.expected_revision }}",
        },
        "run" => <<~'BASH',
          set -euo pipefail
          incident_head="$(git -C incident rev-parse HEAD)"
          test "$incident_head" = "$EXPECTED_REVISION"
        BASH
      },
      {
        "name" => "Derive retained helper SHA-256",
        "id" => "helper",
        "shell" => "bash",
        "env" => {
          "EXPECTED_REVISION" => "${{ needs.validate.outputs.expected_revision }}",
        },
        "run" => <<~'BASH',
          set -euo pipefail
          readonly helper_repository_path="scripts/deploy/remote-compose-release.sh"
          readonly max_helper_blob_size=262144
          readonly max_helper_tree_record_bytes=512

          reject_helper() {
            printf 'read-only status channel %s\n' "$1" >&2
            exit 2
          }

          export GIT_NO_REPLACE_OBJECTS=1
          export GIT_OPTIONAL_LOCKS=0
          export GIT_LITERAL_PATHSPECS=1
          export LC_ALL=C

          parse_helper_tree_entry() {
            local tree_record=""
            local trailing_tree_data=""
            local tree_metadata
            local tree_path
            local tree_metadata_pattern='^([0-9]{6}) ([a-z]+) ([0-9a-f]+)$'
            local tree_mode
            local tree_type
            local blob_oid

            if ! IFS= read -r -d '' -n "$max_helper_tree_record_bytes" tree_record; then
              if [[ -z "$tree_record" ]]; then
                return 40
              fi
              return 42
            fi
            if IFS= read -r -d '' -n 1 trailing_tree_data; then
              return 42
            fi
            if [[ -n "$trailing_tree_data" ]] || [[ "$tree_record" != *$'\t'* ]]; then
              return 42
            fi

            tree_metadata="${tree_record%%$'\t'*}"
            tree_path="${tree_record#*$'\t'}"
            if [[ "$tree_path" != "$helper_repository_path" ]] ||
               [[ ! "$tree_metadata" =~ $tree_metadata_pattern ]]; then
              return 42
            fi

            tree_mode="${BASH_REMATCH[1]}"
            tree_type="${BASH_REMATCH[2]}"
            blob_oid="${BASH_REMATCH[3]}"
            if [[ "$tree_type" != "blob" ]] ||
               [[ "$tree_mode" != "100644" && "$tree_mode" != "100755" ]]; then
              return 41
            fi
            printf '%s %s %s\n' "$tree_mode" "$tree_type" "$blob_oid"
          }

          helper_tree_status=0
          helper_tree_identity="$(
            set -o pipefail
            if git -C incident ls-tree --full-tree -z "$EXPECTED_REVISION" -- "$helper_repository_path" |
                 parse_helper_tree_entry; then
              helper_tree_pipeline_status=("${PIPESTATUS[@]}")
            else
              helper_tree_pipeline_status=("${PIPESTATUS[@]}")
            fi
            if (( ${#helper_tree_pipeline_status[@]} != 2 )) ||
               (( helper_tree_pipeline_status[0] != 0 )); then
              exit 42
            fi
            exit "${helper_tree_pipeline_status[1]}"
          )" || helper_tree_status=$?
          case "$helper_tree_status" in
            0) ;;
            40) reject_helper "incident helper tree entry missing" ;;
            41) reject_helper "incident helper tree entry is not a regular blob" ;;
            *) reject_helper "incident helper Git blob identity invalid" ;;
          esac

          helper_tree_identity_pattern='^(100644|100755) (blob) ([0-9a-f]+)$'
          if [[ ! "$helper_tree_identity" =~ $helper_tree_identity_pattern ]]; then
            reject_helper "incident helper Git blob identity invalid"
          fi
          helper_blob_oid="${BASH_REMATCH[3]}"

          if ! git_object_format="$(git -C incident rev-parse --show-object-format=storage)"; then
            reject_helper "incident helper Git blob identity invalid"
          fi
          case "$git_object_format" in
            sha1) expected_oid_length=40 ;;
            sha256) expected_oid_length=64 ;;
            *) reject_helper "incident helper Git blob identity invalid" ;;
          esac
          if [[ ! "$helper_blob_oid" =~ ^[0-9a-f]+$ ]] ||
             (( ${#helper_blob_oid} != expected_oid_length )); then
            reject_helper "incident helper Git blob identity invalid"
          fi

          if ! helper_blob_type="$(git -C incident cat-file -t "$helper_blob_oid")"; then
            reject_helper "incident helper Git blob identity invalid"
          fi
          if [[ "$helper_blob_type" != "blob" ]]; then
            reject_helper "incident helper Git blob identity invalid"
          fi
          if ! helper_blob_size="$(git -C incident cat-file -s "$helper_blob_oid")"; then
            reject_helper "incident helper Git blob identity invalid"
          fi
          if [[ ! "$helper_blob_size" =~ ^[1-9][0-9]{0,6}$ ]] ||
             (( 10#$helper_blob_size > max_helper_blob_size )); then
            reject_helper "incident helper Git blob identity invalid"
          fi

          if ! helper_sha256_line="$(git -C incident cat-file blob "$helper_blob_oid" | sha256sum)"; then
            reject_helper "incident helper Git blob identity invalid"
          fi
          helper_sha256="${helper_sha256_line%% *}"
          if [[ ! "$helper_sha256" =~ ^[0-9a-f]{64}$ ]]; then
            reject_helper "incident helper Git blob identity invalid"
          fi
          printf 'sha256=%s\n' "$helper_sha256" >>"$GITHUB_OUTPUT"
        BASH
      },
      {
        "name" => "Setup deployment SSH principal",
        "uses" => SSH_AGENT_ACTION,
        "with" => {"ssh-private-key" => "${{ secrets.SSH_PRIVATE_KEY }}"},
      },
      {
        "name" => "Read exact retained release status once",
        "shell" => "bash",
        "env" => {
          "TMPDIR" => "${{ runner.temp }}",
          "RUNNER_TEMP" => "${{ runner.temp }}",
          "APP_ENV" => "${{ needs.validate.outputs.environment }}",
          "INCIDENT_TAG" => "${{ needs.validate.outputs.incident_tag }}",
          "RELEASE_OWNER" => "${{ needs.validate.outputs.release_owner }}",
          "EXPECTED_REVISION" => "${{ needs.validate.outputs.expected_revision }}",
          "IMAGE_DIGEST" => "${{ needs.validate.outputs.image_digest }}",
          "REQUESTED_OPERATION" => "${{ needs.validate.outputs.requested_operation }}",
          "EXPECTED_HELPER_SHA256" => "${{ steps.helper.outputs.sha256 }}",
          "SSH_USER" => "${{ secrets.SSH_USER }}",
          "SSH_HOST" => "${{ secrets.SSH_HOST }}",
          "SSH_PORT" => "${{ secrets.SSH_PORT || '22' }}",
          "COMPOSE_PATH" => "${{ secrets.COMPOSE_PATH }}",
          "SSH_KNOWN_HOSTS" => "${{ secrets.SSH_KNOWN_HOSTS }}",
        },
        "run" => "implementation/scripts/deploy/read-only-release-status.sh",
      },
    ]

    unless status_steps.fetch(0) == expected_status_steps.fetch(0)
      reject("#{path}/status: implementation checkout contract changed")
    end
    unless status_steps.fetch(1) == expected_status_steps.fetch(1)
      reject("#{path}/status: implementation HEAD must equal GITHUB_SHA")
    end
    unless status_steps.fetch(2) == expected_status_steps.fetch(2)
      reject("#{path}/status: incident checkout must use validated incident tag")
    end
    unless status_steps.fetch(3) == expected_status_steps.fetch(3)
      reject("#{path}/status: incident HEAD must equal validated expected revision")
    end
    helper_step = status_steps.fetch(4)
    if helper_step["run"].to_s.match?(/[0-9a-f]{64}/)
      reject("#{path}/status: constant helper SHA-256 is forbidden")
    end
    unless helper_step == expected_status_steps.fetch(4)
      reject("#{path}/status: helper SHA-256 must be derived only from incident helper bytes")
    end
    unless status_steps.fetch(5) == expected_status_steps.fetch(5)
      reject("#{path}/status: SSH agent input contract changed")
    end
    unless status_steps.fetch(6) == expected_status_steps.fetch(6)
      runner_secrets, = scalar_inventory(status_steps.fetch(6), "#{path}/status/runner")
      reject("#{path}/status: SSH_KNOWN_HOSTS is required") unless
        runner_secrets.include?("SSH_KNOWN_HOSTS")
      reject("#{path}/status: status runner environment contract changed")
    end

    early_status = status_job.reject { |key, _value| key == "steps" }.merge(
      "steps" => status_steps.first(5)
    )
    early_secrets, early_token_refs = scalar_inventory(
      early_status,
      "#{path}/status/pre-privilege"
    )
    reject("#{path}/status: SSH secret referenced before both revision checks") unless
      early_secrets.empty?
    reject("#{path}/status: github.token use is forbidden") unless early_token_refs.zero?

    expected_validate_job = {
      "name" => "validate-status-request",
      "runs-on" => "ubuntu-latest",
      "timeout-minutes" => 5,
      "permissions" => {"contents" => "read"},
      "outputs" => expected_outputs,
      "steps" => [expected_guard, expected_sanitizer],
    }
    expected_status_job = {
      "name" => "deployment-principal-status",
      "needs" => "validate",
      "runs-on" => "ubuntu-latest",
      "timeout-minutes" => 10,
      "permissions" => {"contents" => "read"},
      "environment" => "${{ needs.validate.outputs.environment }}",
      "steps" => expected_status_steps,
    }
    validate_exact_value(validate_job, expected_validate_job, "#{path}/validate exact contract")
    validate_exact_value(status_job, expected_status_job, "#{path}/status exact contract")
  end

  def validate_release_status_contract(path, workflow, triggers, jobs, raw)
    return unless path == RELEASE_STATUS_WORKFLOW

    validate_release_status_exact_contract(path, workflow, jobs, raw)

    reject("#{path}: workflow display name changed") unless
      workflow["name"] == "Release Status (read-only)"
    reject("#{path}: workflow permissions must be exactly contents: read") unless
      workflow["permissions"] == {"contents" => "read"}
    reject("#{path}: shared environment concurrency group changed") unless
      workflow["concurrency"] == {
        "group" => "payments-schema-${{ inputs.environment }}",
        "cancel-in-progress" => false,
      }
    reject("#{path}: job inventory must be exactly validate and status") unless
      jobs.keys.sort == %w[status validate]
    reject("#{path}: live ssh-keyscan is forbidden") if raw.match?(/\bssh-keyscan\b/)
    reject("#{path}: artifact publication is forbidden") if
      raw.match?(/actions\/upload-artifact@|actions\/upload-pages-artifact@/)
    reject("#{path}: registry login is forbidden") if
      raw.match?(/docker\/login-action@|\bdocker\s+login\b/)
    reject("#{path}: image pull is forbidden") if raw.match?(/\bdocker\s+(?:image\s+)?pull\b/)
    reject("#{path}: deploy runner invocation is forbidden") if
      raw.include?("scripts/deploy/quiesced-release.sh")
    reject("#{path}: scp transport is forbidden") if raw.match?(/(^|[^[:alnum:]_-])scp(?:[[:space:]]|$)/)

    validate_job = jobs.fetch("validate")
    status_job = jobs.fetch("status")
    reject("#{path}/validate: job must be a mapping") unless validate_job.is_a?(Hash)
    reject("#{path}/status: job must be a mapping") unless status_job.is_a?(Hash)
    reject("#{path}/validate: permissions must be explicitly contents: read") unless
      validate_job["permissions"] == {"contents" => "read"}
    reject("#{path}/status: permissions must be explicitly contents: read") unless
      status_job["permissions"] == {"contents" => "read"}
    reject("#{path}/validate: environment use is forbidden") if validate_job.key?("environment")
    reject("#{path}/status: protected environment must use validated output") unless
      status_job["environment"] == "${{ needs.validate.outputs.environment }}"
    reject("#{path}/status: needs must be exactly validate") unless status_job["needs"] == "validate"
    reject("#{path}/validate: timeout must be at most 5 minutes") unless
      validate_job["timeout-minutes"].is_a?(Integer) &&
        validate_job["timeout-minutes"].between?(1, 5)
    reject("#{path}/status: timeout must be at most 10 minutes") unless
      status_job["timeout-minutes"].is_a?(Integer) &&
        status_job["timeout-minutes"].between?(1, 10)
    reject("#{path}: status jobs must use ubuntu-latest") unless
      validate_job["runs-on"] == "ubuntu-latest" && status_job["runs-on"] == "ubuntu-latest"
    reject("#{path}: status jobs must not use a job-level condition") if
      validate_job.key?("if") || status_job.key?("if")

    expected_outputs = %w[
      environment incident_tag release_owner expected_revision image_digest
      requested_operation
    ].to_h do |name|
      [name, "${{ steps.sanitize.outputs.#{name} }}"]
    end
    reject("#{path}/validate: sanitized output contract changed") unless
      validate_job["outputs"] == expected_outputs

    validate_steps = step_list(validate_job, "#{path}/validate")
    status_steps = step_list(status_job, "#{path}/status")
    reject("#{path}/validate: guard and sanitizer step inventory changed") unless
      validate_steps.length == 2
    reject("#{path}/status: mandatory seven-step inventory changed") unless
      status_steps.length == 7
    (validate_steps + status_steps).each do |step|
      reject("#{path}: status channel must not continue on error") if
        step.key?("continue-on-error")
      reject("#{path}: mandatory steps must be unconditional") if step.key?("if")
    end

    first_guard = validate_steps.fetch(0)
    reject("#{path}/validate: main/ref guard must be the first executable validation guard") unless
      first_guard["name"] == "Require main dispatch" &&
        first_guard["shell"] == "bash" &&
        first_guard["run"].is_a?(String) &&
        first_guard["run"].match?(
          /\Aset -euo pipefail\n"?test \"\$GITHUB_REF\" = \"refs\/heads\/main\""?/
        )
    guard_source = first_guard["run"].to_s
    %w[
      test\ "$GITHUB_REF"\ =\ "refs/heads/main"
      test\ "$GITHUB_REF_TYPE"\ =\ "branch"
      test\ "$REPOSITORY_DEFAULT_BRANCH"\ =\ "main"
    ].each do |guard|
      reject("#{path}/validate: main/ref guard contract changed") unless guard_source.include?(guard)
    end
    validate_steps.each do |step|
      reject("#{path}/validate: actions and checkouts are forbidden") if step.key?("uses")
    end

    expected_action_sequence = [
      CHECKOUT_ACTION,
      nil,
      CHECKOUT_ACTION,
      nil,
      nil,
      SSH_AGENT_ACTION,
      nil,
    ]
    actual_action_sequence = status_steps.map { |step| step["uses"] }
    reject("#{path}/status: action and privilege boundary changed") unless
      actual_action_sequence == expected_action_sequence
    first_checkout = status_steps.fetch(0)
    incident_checkout = status_steps.fetch(2)
    reject("#{path}/status: implementation checkout contract changed") unless
      first_checkout["with"] == {
        "ref" => "refs/heads/main",
        "path" => "implementation",
        "persist-credentials" => false,
      }
    reject("#{path}/status: incident checkout must use validated incident tag") unless
      incident_checkout["with"] == {
        "ref" => "refs/tags/${{ needs.validate.outputs.incident_tag }}",
        "path" => "incident",
        "persist-credentials" => false,
      }
    runner_calls = status_steps.count do |step|
      step["run"] == "implementation/scripts/deploy/read-only-release-status.sh"
    end
    reject("#{path}/status: status runner must be invoked exactly once") unless runner_calls == 1

    status_steps.first(5).each_with_index do |step, index|
      secrets, token_refs = scalar_inventory(step, "#{path}/status/step-#{index + 1}")
      reject("#{path}/status: SSH secret referenced before revision checks") unless secrets.empty?
      reject("#{path}/status: github.token use is forbidden") unless token_refs.zero?
    end
    agent_secrets, = scalar_inventory(status_steps.fetch(5), "#{path}/status/ssh-agent")
    reject("#{path}/status: SSH agent secret contract changed") unless
      agent_secrets == Set.new(["SSH_PRIVATE_KEY"])
    runner_secrets, runner_token_refs = scalar_inventory(
      status_steps.fetch(6),
      "#{path}/status/runner"
    )
    expected_runner_secrets = RELEASE_STATUS_SECRETS - Set.new(["SSH_PRIVATE_KEY"])
    reject("#{path}/status: runner secret allowlist changed") unless
      runner_secrets == expected_runner_secrets
    reject("#{path}/status: github.token use is forbidden") unless runner_token_refs.zero?
    reject("#{path}/status: SSH_KNOWN_HOSTS is required") unless
      runner_secrets.include?("SSH_KNOWN_HOSTS")
    validate_exact_value(
      triggers,
      EXPECTED_TRIGGERS.fetch(RELEASE_STATUS_WORKFLOW),
      "#{path}: privileged trigger contract"
    )
  end

  def validate_privileged_trigger(path, triggers, jobs)
    expected = EXPECTED_TRIGGERS[path]
    return unless expected
    validate_exact_value(triggers, expected, "#{path}: privileged trigger contract")
    if path == CANONICAL_PUBLISHER
      guard = jobs.dig(CANONICAL_PUBLISHER_JOB, "if")
      expected_guard = "github.event_name == 'push' || github.ref == 'refs/heads/main'"
      reject("#{path}: workflow_dispatch publisher guard changed") unless guard == expected_guard
    elsif path == ".github/workflows/dependency-submission.yml"
      guard = jobs.dig("submit", "if")
      expected_guard = "github.event_name == 'push' || github.ref == 'refs/heads/main'"
      reject("#{path}: trusted dependency submission guard changed") unless guard == expected_guard
    end
  end

  def function_body(source, name)
    match = source.match(/^#{Regexp.escape(name)}\(\) \{\n(?<body>.*?)^\}/m)
    reject("remote deploy contract: missing #{name} function") unless match
    match[:body]
  end

  def validate_remote_credential_contract(root)
    runner_path = "scripts/deploy/quiesced-release.sh"
    remote_path = "scripts/deploy/remote-compose-release.sh"
    tracked, _stderr, status = Open3.capture3(
      "git", "-C", root.to_s, "ls-files", "-z", "--", runner_path, remote_path
    )
    reject("deploy script inventory failed") unless status.success?
    inventory = tracked.split("\0", -1)
    inventory.pop
    reject("deploy scripts must be tracked") unless inventory.sort == [remote_path, runner_path].sort
    [runner_path, remote_path].each { |path| ensure_regular_path(root, path) }
    runner = File.binread(root.join(runner_path))
    remote = File.binread(root.join(remote_path))

    reject("runner retains legacy GHCR credential contract") if runner.match?(/GHCR_(?:TOKEN|USERNAME)/)
    %w[REGISTRY_USERNAME REGISTRY_READ_TOKEN].each do |name|
      reject("runner lacks #{name} contract") unless runner.include?(name)
    end
    reject("runner enables shell tracing") if runner.match?(/(?:^|\s)set\s+-[^\n]*x/)
    reject("runner enables automatic variable export") if
      runner.match?(/(?:^|\s)set\s+(?:-[^\n\s]*a|(?:-o|--option)\s+allexport)(?:\s|$)/)
    unless runner.match?(/printf '%s\\n' "\$registry_read_token" \|\s*\n\s*remote_command/m)
      reject("runner must send the read token to the remote preflight only through stdin")
    end
    reject("runner fails to drop exported REGISTRY_READ_TOKEN") unless runner.include?("unset REGISTRY_READ_TOKEN")
    reject("runner places a registry token in the SSH command") if
      function_body(runner, "remote_command").match?(/REGISTRY_READ_TOKEN|registry_read_token/)
    expected_runner_token_lines = [
      "REGISTRY_READ_TOKEN",
      'registry_read_token="$REGISTRY_READ_TOKEN"',
      "unset REGISTRY_READ_TOKEN",
      'printf \'%s\\n\' "$registry_read_token" |',
      "unset registry_read_token",
    ].sort
    actual_runner_token_lines = runner.lines.grep(/REGISTRY_READ_TOKEN|registry_read_token/).map(&:strip).sort
    unless actual_runner_token_lines == expected_runner_token_lines
      reject("runner registry token flow changed outside the stdin-only contract")
    end
    capture_index = runner.index('registry_read_token="$REGISTRY_READ_TOKEN"')
    drop_index = runner.index("unset REGISTRY_READ_TOKEN")
    first_child_index = [runner.index("\nscp "), runner.index("\nssh ")].compact.min
    unless capture_index && drop_index && first_child_index &&
           capture_index < drop_index && drop_index < first_child_index
      reject("runner must remove the exported token before its first scp/ssh child")
    end

    reject("remote helper retains legacy GHCR credential contract") if remote.match?(/ghcr_(?:token|username)/i)
    reject("remote helper references persistent Docker credentials") if
      remote.match?(%r{(?:\$HOME|~)/\.docker|\.docker/config\.json|docker\s+logout})
    required_remote_literals = [
      'registry_config_dir="$(mktemp -d "/tmp/clubs-bot-docker-config.${owner}.XXXXXX")"',
      'chmod 700 "$registry_config_dir"',
      'trap cleanup_registry_config EXIT',
      'rm -rf -- "$registry_config_dir"',
      'docker --config "$registry_config_dir" login',
      '--password-stdin',
      'docker --config "$registry_config_dir" pull "$tag_reference"',
      'docker --config "$registry_config_dir" pull "$digest"',
      'compose_command up -d --no-deps --pull never app',
    ]
    required_remote_literals.each do |literal|
      reject("remote helper lacks temporary credential contract: #{literal}") unless remote.include?(literal)
    end
    expected_remote_token_lines = [
      "local registry_read_token tag_reference revision digest digest_prefix digest_hash",
      'IFS= read -r registry_read_token || [ -n "$registry_read_token" ]',
      'if [ -z "$registry_read_token" ]; then',
      'printf \'%s\\n\' "$registry_read_token" |',
      "unset registry_read_token",
    ].sort
    actual_remote_token_lines = remote.lines.grep(/registry_read_token/).map(&:strip).sort
    unless actual_remote_token_lines == expected_remote_token_lines
      reject("remote registry token flow changed outside password-stdin")
    end
    remote.each_line do |line|
      next unless line.match?(/\bdocker\b.*\b(?:login|pull)\b/)
      unless line.include?('docker --config "$registry_config_dir"')
        reject("remote helper has login/pull outside the temporary Docker config")
      end
    end
    state_body = function_body(remote, "create_maintenance_state")
    reject("registry credential leaks into maintenance state") if state_body.match?(/REGISTRY_|registry_read_token/i)
    %w[migrate_verified_image start_and_probe_app].each do |name|
      reject("registry credential leaks into #{name}") if function_body(remote, name).match?(/REGISTRY_|registry_read_token/i)
    end
  end

  def validate_rollout_documentation(root)
    relative_path = "docs/ops/secrets-rotation.md"
    tracked, _stderr, status = Open3.capture3(
      "git", "-C", root.to_s, "ls-files", "--error-unmatch", "--", relative_path
    )
    reject("rollout documentation must be tracked") unless
      status.success? && tracked.strip == relative_path
    ensure_regular_path(root, relative_path)
    document = File.binread(root.join(relative_path)).force_encoding(Encoding::UTF_8)
    reject("#{relative_path}: rollout documentation is not valid UTF-8") unless
      document.valid_encoding?

    start_count = document.scan(ROLLOUT_START_MARKER).length
    end_count = document.scan(ROLLOUT_END_MARKER).length
    reject("#{relative_path}: expected exactly one rollout start marker") unless start_count == 1
    reject("#{relative_path}: expected exactly one rollout end marker") unless end_count == 1

    start_index = document.index(ROLLOUT_START_MARKER)
    end_index = document.index(ROLLOUT_END_MARKER)
    reject("#{relative_path}: rollout end marker precedes start marker") unless
      start_index < end_index
    section_start = start_index + ROLLOUT_START_MARKER.length
    section = document[section_start...end_index]
    section_lines = section.lines.map(&:strip).reject(&:empty?)
    numbered_lines = section_lines.select { |line| line.match?(/\A\d+\.\s/) }
    reject("#{relative_path}: rollout section must contain exactly 8 numbered steps") unless
      numbered_lines.length == 8 && section_lines.length == 8

    parsed_steps = numbered_lines.map do |line|
      match = line.match(/\A(?<number>\d+)\. \[(?<id>[A-Z0-9_]+)\] (?<text>.+)\z/)
      reject("#{relative_path}: malformed rollout step #{line.inspect}") unless match
      [match[:number].to_i, match[:id], line]
    end
    numbers = parsed_steps.map(&:first)
    ids = parsed_steps.map { |step| step[1] }
    reject("#{relative_path}: rollout step numbers must be exactly 1 through 8") unless
      numbers == (1..8).to_a
    reject("#{relative_path}: rollout step IDs must be unique") unless ids.uniq.length == ids.length
    reject("#{relative_path}: rollout step IDs are missing, extra, or reordered") unless
      ids == ROLLOUT_STEP_IDS

    step_eight = parsed_steps.fetch(7)[2]
    step_eight_bracket_ids = step_eight.scan(/\[([A-Z0-9_]+)\]/).flatten
    step_eight_action_ids = step_eight_bracket_ids.drop(1)
    duplicate_action = step_eight_action_ids.find do |action_id|
      step_eight_action_ids.count(action_id) > 1
    end
    if duplicate_action
      reject("#{relative_path}: duplicate retirement action ID in canonical step 8: #{duplicate_action}")
    end
    unknown_action = step_eight_action_ids.find do |action_id|
      !ROLLOUT_RETIREMENT_ACTION_IDS.include?(action_id)
    end
    if unknown_action
      reject("#{relative_path}: unknown retirement action ID in canonical step 8: #{unknown_action}")
    end
    missing_action = ROLLOUT_RETIREMENT_ACTION_IDS.find do |action_id|
      !step_eight_action_ids.include?(action_id)
    end
    if missing_action
      reject("#{relative_path}: missing retirement action ID in canonical step 8: #{missing_action}")
    end
    unless step_eight_action_ids == ROLLOUT_RETIREMENT_ACTION_IDS
      reject("#{relative_path}: retirement action IDs are reordered in canonical step 8")
    end

    ROLLOUT_REQUIRED_RETIREMENT_TARGETS.each do |target|
      pattern = ROLLOUT_RETIREMENT_TARGET_PATTERNS.assoc(target).fetch(1)
      target_count = step_eight.scan(pattern).length
      if target_count.zero?
        reject("#{relative_path}: missing retirement target in canonical step 8: #{target}")
      end
      if target_count > 1
        reject("#{relative_path}: duplicate retirement target in canonical step 8: #{target}")
      end
    end

    parsed_steps.first(7).each do |number, id, line|
      target = rollout_retirement_target(line)
      next unless target

      reject("#{relative_path}: retirement target #{target} in wrong step #{number} [#{id}]")
    end

    before_section = document[0...start_index]
    after_section = document[(end_index + ROLLOUT_END_MARKER.length)..-1].to_s
    outside_section = before_section + after_section
    outside_target = rollout_retirement_target(
      mask_rollout_neutral_reference(outside_section)
    )
    if outside_target
      reject("#{relative_path}: retirement target outside rollout section: #{outside_target}")
    end

    reject("#{relative_path}: exact bounded rollout contract changed") unless
      numbered_lines == ROLLOUT_STEP_LINES

    reject("#{relative_path}: manual GitHub settings contract changed") unless
      document.include?("GitHub settings меняются только вручную")
  end

  def mask_rollout_neutral_reference(text)
    text.gsub(ROLLOUT_NEUTRAL_REFERENCE) do |reference|
      " " * reference.length
    end
  end

  def rollout_retirement_action_id(text)
    exact_ids = text.scan(
      /(?<![A-Za-z0-9_])[A-Z][A-Z0-9_]*(?![A-Za-z0-9_])/
    )
    ROLLOUT_RETIREMENT_ACTION_IDS.find do |candidate|
      exact_ids.include?(candidate)
    end
  end

  def rollout_retirement_target(text)
    action_id = rollout_retirement_action_id(text)
    return action_id if action_id

    target = ROLLOUT_RETIREMENT_TARGET_PATTERNS.find do |_name, pattern|
      text.match?(pattern)
    end
    target&.first
  end

  def run(root)
    ensure_regular_path(root, PROVENANCE_VERIFIER_PATH)
    validate_gradle_plugin_metadata(root)
    paths = visible_workflow_paths(root)
    workflows = {}
    paths.each do |path|
      workflow, raw = load_workflow(root, path)
      workflows[path] = [workflow, raw]
    end
    reject("canonical publisher workflow is missing") unless workflows.key?(CANONICAL_PUBLISHER)
    reject("release-status workflow is missing from visible inventory") unless
      workflows.key?(RELEASE_STATUS_WORKFLOW)

    observed_exact_jobs = Set.new
    workflows.each do |path, (workflow, raw)|
      triggers = workflow_triggers(workflow, path)
      reject("#{path}: workflow permissions are omitted") unless workflow.key?("permissions")
      workflow_permissions = parse_permissions(workflow["permissions"], "#{path}/workflow")
      if path == CANONICAL_PUBLISHER && workflow_permissions.values.include?("write")
        reject("#{path}: canonical publisher may not have workflow-level write")
      end

      jobs = workflow["jobs"]
      reject("#{path}: jobs block must be a non-empty mapping") unless jobs.is_a?(Hash) && !jobs.empty?
      if path == CANONICAL_PUBLISHER
        inline_marker = INLINE_PROVENANCE_MARKERS.find { |marker| raw.include?(marker) }
        reject("#{path}: inline OCI provenance verifier is forbidden") if inline_marker
        expected_jobs = %w[build-and-push trivy-image verify-and-provenance].sort
        reject("#{path}: canonical job inventory changed") unless jobs.keys.sort == expected_jobs
        unless workflow_permissions == {"contents" => "read"}
          reject("#{path}: canonical workflow baseline must be contents: read")
        end
      end
      validate_privileged_trigger(path, triggers, jobs)
      validate_release_status_contract(path, workflow, triggers, jobs, raw)
      top_level = workflow.reject { |key, _value| key == "jobs" }
      trigger_key = workflow.key?("on") ? "on" : true
      if path == CANONICAL_PUBLISHER
        validate_exact_value(
          top_level,
          {
            "name" => "Docker Publish (GHCR)",
            trigger_key => EXPECTED_TRIGGERS.fetch(CANONICAL_PUBLISHER),
            "permissions" => {"contents" => "read"},
            "concurrency" => {
              "group" => "docker-publish-${{ github.ref }}",
              "cancel-in-progress" => true,
            },
            "env" => {
              "IMAGE_NAME" => "ghcr.io/${{ github.repository }}/app-bot",
            },
          },
          "#{path}: canonical workflow fields"
        )
      elsif path == DEPENDENCY_SUBMISSION_WORKFLOW
        validate_exact_value(
          top_level,
          {
            "name" => "Dependency Submission",
            trigger_key => EXPECTED_TRIGGERS.fetch(DEPENDENCY_SUBMISSION_WORKFLOW),
            "permissions" => {"contents" => "write"},
            "concurrency" => {
              "group" => "dependency-submission-${{ github.ref }}",
              "cancel-in-progress" => true,
            },
          },
          "#{path}: dependency submission workflow fields"
        )
      end
      validate_env_map(workflow["env"], "#{path}/workflow/env") if workflow.key?("env")
      top_secrets, top_token_refs = scalar_inventory(top_level, "#{path}/workflow")
      reject("#{path}: workflow-level secret reference is forbidden") unless top_secrets.empty?
      reject("#{path}: workflow-level github.token reference is forbidden") unless top_token_refs.zero?

      jobs.each do |job_name, job|
        context = "#{path}/#{job_name}"
        reject("#{context}: job must be a mapping") unless job.is_a?(Hash)
        effective = if job.key?("permissions")
                      parse_permissions(job["permissions"], "#{context}/permissions")
                    else
                      workflow_permissions
                    end
        expected = EXPECTED_EFFECTIVE_PERMISSIONS[[path, job_name]] || {"contents" => "read"}
        reject("#{context}: effective permissions changed; expected #{expected.inspect}, got #{effective.inspect}") unless
          effective == expected
        observed_exact_jobs << [path, job_name] if EXPECTED_EFFECTIVE_PERMISSIONS.key?([path, job_name])

        if path == CANONICAL_PUBLISHER && !job.key?("permissions")
          reject("#{context}: canonical publisher jobs require job-level permission isolation")
        end
        if DEPLOY_JOBS.key?([path, job_name]) && !job.key?("permissions")
          reject("#{context}: deployment jobs require job-level permission isolation")
        end
        if path == RELEASE_STATUS_WORKFLOW && !job.key?("permissions")
          reject("#{context}: release-status jobs require job-level permission isolation")
        end
        if job.key?("steps") && job["runs-on"] != "ubuntu-latest"
          reject("#{context}: jobs with steps must use an ephemeral ubuntu-latest runner")
        end
        validate_environment(path, job_name, job)
        validate_deploy_job_contract(path, job_name, job)
        validate_dependency_submission_job(path, job_name, job)
        validate_canonical_job_contract(path, job_name, job)
        validate_job_secrets(path, job_name, job)
        validate_registry_and_literal_policy(path, job_name, job, effective)
      end
    end

    required_exact_jobs = Set.new(EXPECTED_EFFECTIVE_PERMISSIONS.keys)
    missing = required_exact_jobs - observed_exact_jobs
    reject("required privileged jobs are missing: #{missing.to_a.inspect}") unless missing.empty?
    validate_remote_credential_contract(root)
    validate_rollout_documentation(root)
    puts(
      "quality-gate: capability policy enforces exact workflow privileges " \
      "and structural verifier invocation (#{paths.length} visible workflows); " \
      "checked-out tests exercise Python behavior, while independent integrity " \
      "depends on external review/rulesets"
    )
  end
end

if $PROGRAM_NAME == __FILE__
  if ARGV.length > 1
    warn "usage: validate-workflow-capabilities.rb [repository-root]"
    exit 2
  end

  repository_root = Pathname.new(
    ARGV.fetch(0) { File.expand_path("..", __dir__) }
  ).expand_path
  WorkflowCapabilityPolicy.reject("repository root is not a directory") unless
    repository_root.directory?
  begin
    WorkflowCapabilityPolicy.run(repository_root)
  rescue Psych::Exception, ArgumentError => error
    WorkflowCapabilityPolicy.reject("validation aborted safely (#{error.class})")
  rescue SystemStackError
    WorkflowCapabilityPolicy.reject("validation exceeded the bounded traversal policy")
  end
end
