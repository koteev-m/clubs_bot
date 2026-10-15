"""Portable YAML/policy and real unprivileged Bash tests; NOT GitHub delivery proof."""
from copy import deepcopy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = '.github/workflows/clb91-b-postwait-canary.yml'
EXPRESSION = "${{ vars.CLB91_B_POSTWAIT_CANARY == format('{0}:{1}', github.run_id, github.run_attempt) }}"


def load():
    result = subprocess.run(['ruby', '-I', str(ROOT/'scripts'), '-r', 'validate-workflow-yaml',
        '-r', 'json', '-e', 'puts JSON.generate(WorkflowYamlSafety.safe_load_workflow(File.binread(ARGV[0]),ARGV[0]))',
        str(ROOT/WORKFLOW)], capture_output=True, check=True, timeout=10)
    return json.loads(result.stdout)


def validate(value):
    return subprocess.run(['ruby', '-I', str(ROOT/'scripts'), '-r', 'validate-workflow-capabilities',
        '-r', 'json', '-e', 'Clb91BPostwaitCanaryWorkflow.validate(WorkflowCapabilityPolicy,JSON.parse(STDIN.read))'],
        input=json.dumps(value).encode(), capture_output=True, timeout=5).returncode


class Policy(unittest.TestCase):
    def test_current_exact_workflow(self):
        self.assertEqual(validate(load()), 0)

    def test_no_automatic_trigger_or_dispatch_input(self):
        for trigger in ('push', 'pull_request', 'workflow_run', 'schedule'):
            value=load();value['on'][trigger]=None
            self.assertNotEqual(validate(value), 0)
        value=load();value['on']['workflow_dispatch']={'inputs':{'command':{'type':'string'}}}
        self.assertNotEqual(validate(value), 0)

    def test_each_identity_restriction_is_mandatory(self):
        value=load();guard=value['jobs']['canary']['if']
        predicates=guard.split(' && ')
        self.assertEqual(len(predicates),6)
        for clause in predicates:
            changed=deepcopy(value);changed['jobs']['canary']['if']=' && '.join(x for x in predicates if x!=clause)
            self.assertNotEqual(validate(changed),0)

    def test_whole_job_environment_and_no_deployment(self):
        for env in (None,'clb91-b-native-test',{'name':'stage','deployment':False},
                    {'name':'clb91-b-native-test','deployment':True}):
            value=load()
            if env is None:del value['jobs']['canary']['environment']
            else:value['jobs']['canary']['environment']=env
            self.assertNotEqual(validate(value),0)

    def test_no_extra_permissions_job_runner_or_credential(self):
        for permission in ('actions','contents','deployments','id-token','packages'):
            value=load();value['permissions'][permission]='write'
            self.assertNotEqual(validate(value),0)
        for field,item in (('runs-on','self-hosted'),('runs-on','ubuntu-slim'),('container','ubuntu'),
                           ('secrets','inherit'),('services',{}),('continue-on-error',True),('timeout-minutes',3)):
            value=load();value['jobs']['canary'][field]=item
            self.assertNotEqual(validate(value),0)
        value=load();value['jobs']['extra']=value['jobs']['canary']
        self.assertNotEqual(validate(value),0)

    def test_no_checkout_upload_external_action_or_extra_command(self):
        for step in ({'uses':'actions/checkout@'+'a'*40}, {'uses':'actions/upload-artifact@'+'a'*40},
                     {'run':'sudo true'}, {'run':'docker info'}, {'run':'curl https://example.invalid'}):
            value=load();value['jobs']['canary']['steps'].append(step)
            self.assertNotEqual(validate(value),0)

    def test_only_step_level_boolean_no_raw_value_or_fallback(self):
        value=load();job=value['jobs']['canary'];step=job['steps'][0]
        self.assertNotIn('env',value);self.assertNotIn('env',job)
        self.assertEqual(step['env'],{'CANARY_MATCH':EXPRESSION})
        self.assertNotIn('${{',step['run'])
        for expr in ('${{ vars.CLB91_B_POSTWAIT_CANARY }}', '${{ secrets.CLB91_B_POSTWAIT_CANARY }}',
                     '${{ vars.CLB91_B_NATIVE_APPROVAL_V2 }}', '${{ true }}'):
            changed=deepcopy(value);changed['jobs']['canary']['steps'][0]['env']['CANARY_MATCH']=expr
            self.assertNotEqual(validate(changed),0)
        changed=deepcopy(value);changed['env']={'CANARY_MATCH':EXPRESSION}
        self.assertNotEqual(validate(changed),0)
        changed=deepcopy(value);changed['jobs']['canary']['steps'][0]['env']['RAW']='${{ vars.CLB91_B_POSTWAIT_CANARY }}'
        self.assertNotEqual(validate(changed),0)

    def test_concurrency_is_distinct_fixed_and_never_cancel_native(self):
        value=load()
        self.assertEqual(value['concurrency'],{'group':'clb91-b-postwait-canary-single-v1','cancel-in-progress':False})
        for key,item in (('group','clb91-b-native-acceptance-single-v1'),('cancel-in-progress',True)):
            changed=deepcopy(value);changed['concurrency'][key]=item
            self.assertNotEqual(validate(changed),0)

    def test_run_command_cannot_be_substituted_or_extended(self):
        value=load()
        for command in ('true','echo "$CANARY_MATCH"',value['jobs']['canary']['steps'][0]['run']+'printf secret'):
            changed=deepcopy(value);changed['jobs']['canary']['steps'][0]['run']=command
            self.assertNotEqual(validate(changed),0)

    def test_malformed_duplicate_yaml_refuses(self):
        for text in ('jobs: [', 'on: push\non: workflow_dispatch\n'):
            r=subprocess.run(['ruby','-I',str(ROOT/'scripts'),'-r','validate-workflow-yaml','-e',
                'WorkflowYamlSafety.safe_load_workflow(STDIN.read,ARGV[0])',WORKFLOW],
                input=text.encode(),capture_output=True,timeout=5)
            self.assertNotEqual(r.returncode,0)

    def test_selfcheck_includes_canary_suite_once(self):
        source=(ROOT/'scripts/selfcheck-quality-gates.sh').read_text()
        self.assertEqual(source.count('python3 -I -S -B "$ROOT_DIR/scripts/tests/test_clb91_b_postwait_canary.py"'),1)


class Output(unittest.TestCase):
    def invoke(self, value):
        script=load()['jobs']['canary']['steps'][0]['run']
        env={'PATH':'/nonexistent'}
        if value is not None:env['CANARY_MATCH']=value
        return subprocess.run(['/bin/bash','--noprofile','--norc','-e','-o','pipefail','-c',script],
                              env=env,capture_output=True,timeout=3)

    def test_validated_boolean_pass(self):
        r=self.invoke('true');self.assertEqual((r.returncode,r.stdout,r.stderr),(0,b'PASS\n',b''))

    def test_missing_empty_wrong_boolean_fail(self):
        for value in (None,'','false','TRUE','1','true\n','CANARY-PRIVATE'):
            r=self.invoke(value);self.assertEqual((r.returncode,r.stdout,r.stderr),(1,b'FAIL\n',b''))

    def test_hostile_value_is_never_executed_or_printed(self):
        with tempfile.TemporaryDirectory() as directory:
            target=Path(directory)/'sentinel'
            r=self.invoke('$(touch '+str(target)+'); echo CANARY-PRIVATE')
            self.assertEqual((r.returncode,r.stdout,r.stderr),(1,b'FAIL\n',b''))
            self.assertFalse(target.exists())

    def test_bash_syntax(self):
        result=subprocess.run(['/bin/bash','-n'],input=load()['jobs']['canary']['steps'][0]['run'].encode(),capture_output=True,timeout=3)
        self.assertEqual(result.returncode,0)


if __name__=='__main__':unittest.main(verbosity=2)
