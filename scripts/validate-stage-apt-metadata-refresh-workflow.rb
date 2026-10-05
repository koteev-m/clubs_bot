# Exact fixed maintenance capability registration; no generic SSH job exemption.
module StageAptMetadataRefreshWorkflow
  PATH = ".github/workflows/stage-apt-metadata-refresh.yml"

  def self.validate(policy, workflow)
    checkout = {
      "name" => "Checkout exact workflow revision", "uses" => policy::CHECKOUT_ACTION,
      "with" => {"ref" => "${{ github.sha }}", "persist-credentials" => false},
    }
    env = {"REPOSITORY_DEFAULT_BRANCH" => "${{ github.event.repository.default_branch }}",
           "APP_ENV" => "stage", "CONFIRMATION" => "${{ inputs.confirmation }}"}
    validate = {"name" => "Validate fixed request before credentials", "shell" => "bash",
                "env" => env, "run" => "python3 -I -S -B scripts/deploy/stage-apt-metadata-refresh.py --validate"}
    expected = {
      "name" => "Stage APT Metadata Refresh (maintenance)",
      "on" => {"workflow_dispatch" => {"inputs" => {"confirmation" => {
        "description" => "Type CLB-175:refresh-stage-apt-metadata after separate authorization for this exact run; reruns are refused",
        "required" => true, "default" => "", "type" => "string",
      }}}},
      "permissions" => {"contents" => "read"},
      "concurrency" => {"group" => "payments-schema-stage", "cancel-in-progress" => false},
      "jobs" => {
        "validate" => {"runs-on" => "ubuntu-latest", "timeout-minutes" => 5,
                       "steps" => [checkout, validate]},
        "refresh" => {"needs" => "validate", "runs-on" => "ubuntu-latest", "timeout-minutes" => 10,
                     "environment" => "stage", "steps" => [checkout,
          validate.merge("name" => "Revalidate first attempt before credentials"),
          {"name" => "Setup deployment SSH principal", "uses" => policy::SSH_AGENT_ACTION,
           "with" => {"ssh-private-key" => "${{ secrets.SSH_PRIVATE_KEY }}"}},
          {"name" => "Refresh fixed stage APT metadata once", "shell" => "bash",
           "run" => "python3 -I -S -B scripts/deploy/stage-apt-metadata-refresh.py",
           "env" => env.merge("TMPDIR" => "${{ runner.temp }}", "SSH_USER" => "${{ secrets.SSH_USER }}",
                              "SSH_HOST" => "${{ secrets.SSH_HOST }}", "SSH_PORT" => "${{ secrets.SSH_PORT || '22' }}",
                              "SSH_KNOWN_HOSTS" => "${{ secrets.SSH_KNOWN_HOSTS }}")},
        ]},
      },
    }
    policy.validate_exact_value(workflow, expected, "#{PATH}: fixed metadata-only maintenance capability")
  end
end
