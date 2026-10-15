# Exact unprivileged manual canary. No checkout, credential, native or release path.
module Clb91BPostwaitCanaryWorkflow
  PATH = '.github/workflows/clb91-b-postwait-canary.yml'
  ENVIRONMENT = {'name' => 'clb91-b-native-test', 'deployment' => false}.freeze
  SCRIPT = <<~'BASH'
    if [[ "${CANARY_MATCH-}" == "true" ]]; then
      printf '%s\n' PASS
    else
      printf '%s\n' FAIL
      exit 1
    fi
  BASH

  def self.validate(policy, workflow)
    policy.validate_exact_value(workflow, {
      'name' => 'CLB-91 B Post-Waiting Variable Canary',
      'on' => {'workflow_dispatch' => nil},
      'permissions' => {'contents' => 'read'},
      'concurrency' => {'group' => 'clb91-b-postwait-canary-single-v1', 'cancel-in-progress' => false},
      'jobs' => {'canary' => {
        'if' => "github.repository == 'koteev-m/clubs_bot' && github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main' && github.actor == 'koteev-m' && github.triggering_actor == 'koteev-m' && github.run_attempt == 1",
        'runs-on' => 'ubuntu-latest', 'environment' => ENVIRONMENT, 'timeout-minutes' => 2,
        'steps' => [{
          'name' => 'Compare post-Waiting canary without exposing its value',
          'shell' => 'bash',
          'env' => {'CANARY_MATCH' => "${{ vars.CLB91_B_POSTWAIT_CANARY == format('{0}:{1}', github.run_id, github.run_attempt) }}"},
          'run' => SCRIPT,
        }],
      }},
    }, "#{PATH}: exact post-Waiting canary contract")
  end
end
