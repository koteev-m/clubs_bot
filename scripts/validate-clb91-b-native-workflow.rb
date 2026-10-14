# One exact manual disposable test job, with no stage/release capability exemption.
require 'digest'
require 'json'
module Clb91BNativeWorkflow
  PATH = '.github/workflows/clb91-b-native-acceptance.yml'
  HERE = 'scripts/tests/linux-semantic/b-launch/'
  FILES = %w[native-entry.py native-protocol.py native-harness.py prototype.py native-sources.json workflow-driver.py].map { |n| HERE+n }.push(PATH).freeze

  def self.validate(policy, workflow)
    expected = {
      'name' => 'CLB-91 B Native Acceptance',
      'on' => {'workflow_dispatch' => {'inputs' => {'approved_revision' => {
        'description' => 'Exact published commit independently approved for this single native run',
        'required' => true, 'type' => 'string'}}}},
      'permissions' => {'contents' => 'read'},
      'concurrency' => {'group' => 'clb91-b-native-acceptance-single-v1', 'cancel-in-progress' => false},
      'jobs' => {'native' => {
        'if' => "github.repository == 'koteev-m/clubs_bot' && github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main' && github.run_attempt == 1 && github.actor == 'koteev-m' && github.triggering_actor == 'koteev-m' && github.sha == inputs.approved_revision",
        'runs-on' => 'ubuntu-24.04', 'timeout-minutes' => 10,
        'steps' => [
          {'name' => 'Checkout exact approved revision', 'uses' => policy::CHECKOUT_ACTION,
           'with' => {'ref' => '${{ inputs.approved_revision }}', 'persist-credentials' => false}},
          {'name' => 'One source-bound synthetic native operation', 'id' => 'acceptance', 'shell' => 'bash',
           'env' => {'CLB91_APPROVED_REVISION' => '${{ inputs.approved_revision }}',
                     'CLB91_B_NATIVE_APPROVAL' => '${{ vars.CLB91_B_NATIVE_APPROVAL }}',
                     'RUNNER_ENVIRONMENT' => '${{ runner.environment }}'},
           'run' => '/usr/bin/python3 -I -S -B scripts/tests/linux-semantic/b-launch/workflow-driver.py'},
          {'name' => 'Upload only validated bounded evidence',
           'if' => "${{ !cancelled() && steps.acceptance.outputs.evidence == 'validated' }}",
           'uses' => 'actions/upload-artifact@b4b15b8c7c6ac21ea08fcf65892d2ee8f75cf882',
           'with' => {'name' => 'clb91-b-native-evidence', 'path' => 'clb91-b-native-result.json',
                      'retention-days' => 3, 'if-no-files-found' => 'error'}},
        ],
      }},
    }
    policy.validate_exact_value(workflow, expected, "#{PATH}: exact manual native test capability")
  end

  def self.sources(policy, root)
    manifest = HERE+'workflow-sources.json'
    policy.ensure_regular_path(root,manifest)
    raw = File.binread(root.join(manifest))
    policy.reject('native source manifest exceeds bound') if raw.bytesize > 4096
    model = JSON.parse(raw)
    policy.reject('native source manifest is not canonical unique JSON') unless
      raw == JSON.pretty_generate(model)+"\n"
    policy.reject('native source closure schema changed') unless
      model.is_a?(Hash) && model.keys.sort == %w[files format] && model['format'] == 1 &&
      model['files'].is_a?(Hash) && model['files'].keys.sort == FILES.sort
    FILES.each do |path|
      policy.ensure_regular_path(root,path)
      digest = model['files'][path]
      policy.reject('native source pin mismatch') unless digest.is_a?(String) &&
        digest.match?(/\A[0-9a-f]{64}\z/) && Digest::SHA256.file(root.join(path)).hexdigest == digest
    end
  end
end
