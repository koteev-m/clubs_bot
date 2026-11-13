# One exact stage-only observation capability, never a generic SSH exemption.
module StageReadonlySnapshotWorkflow
  PATH = ".github/workflows/stage-readonly-snapshot.yml"

  def self.validate(policy, workflow)
    env = {"REPOSITORY_DEFAULT_BRANCH" => "${{ github.event.repository.default_branch }}",
           "APP_ENV" => "stage", "CONFIRMATION" => "${{ inputs.confirmation }}",
           "COMPOSE_PATH" => "${{ secrets.COMPOSE_PATH }}"}
    ssh = {"APP_ENV" => "stage", "SSH_USER" => "${{ secrets.SSH_USER }}",
           "SSH_HOST" => "${{ secrets.SSH_HOST }}", "SSH_PORT" => "${{ secrets.SSH_PORT || '22' }}",
           "SSH_KNOWN_HOSTS" => "${{ secrets.SSH_KNOWN_HOSTS }}"}
    expected = {
      "name" => "Stage protected read-only snapshot",
      "on" => {"workflow_dispatch" => {"inputs" => {"confirmation" => {
        "description" => "Type CLB_STAGE_PROTECTED_READONLY_SNAPSHOT:read-only after separate snapshot authorization",
        "required" => true, "default" => "", "type" => "string",
      }}}},
      "permissions" => {"contents" => "read"},
      "concurrency" => {"group" => "clb-stage-readonly-snapshot", "cancel-in-progress" => false},
      "jobs" => {"snapshot" => {"runs-on" => "ubuntu-latest", "timeout-minutes" => 5,
        "environment" => "stage", "steps" => [
          {"name" => "Checkout exact workflow revision", "uses" => policy::CHECKOUT_ACTION,
           "with" => {"ref" => "${{ github.sha }}", "persist-credentials" => false}},
          {"name" => "Validate fixed stage request before credentials", "shell" => "bash", "env" => env,
           "run" => "python3 -I -S -B scripts/deploy/stage-readonly-snapshot.py --validate"},
          {"name" => "Validate existing pinned stage SSH trust", "shell" => "bash", "env" => ssh,
           "run" => "python3 -I -S -B scripts/deploy/release_ssh_pin.py --validate"},
          {"name" => "Setup deployment SSH principal", "uses" => policy::SSH_AGENT_ACTION,
           "with" => {"ssh-private-key" => "${{ secrets.SSH_PRIVATE_KEY }}"}},
          {"name" => "Collect bounded sanitized metadata once", "shell" => "bash", "env" => env.merge(ssh),
           "run" => "python3 -I -S -B scripts/deploy/stage-readonly-snapshot.py"},
        ]}},
    }
    policy.validate_exact_value(workflow, expected, "#{PATH}: fixed protected read-only capability")
  end
end
