"""No sudo/root/native calls: approval, source, exact workflow and transport refusals."""
from copy import deepcopy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch,Mock

ROOT = Path(__file__).resolve().parents[4]
HERE = Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('b_workflow_driver',HERE/'workflow-driver.py')
D=importlib.util.module_from_spec(spec);spec.loader.exec_module(D)
spec=importlib.util.spec_from_file_location('b_workflow_protocol',HERE/'native-protocol.py')
R=importlib.util.module_from_spec(spec);spec.loader.exec_module(R)

def context():
    return dict(GITHUB_REPOSITORY=D.REPO,GITHUB_WORKFLOW=D.NAME,
        GITHUB_WORKFLOW_REF=D.REPO+'/'+D.WORKFLOW+'@refs/heads/main',
        GITHUB_REF='refs/heads/main',GITHUB_EVENT_NAME='workflow_dispatch',GITHUB_SHA='a'*40,
        GITHUB_RUN_ID='1234',GITHUB_RUN_NUMBER='1',GITHUB_RUN_ATTEMPT='1',
        GITHUB_ACTOR='koteev-m',GITHUB_TRIGGERING_ACTOR='koteev-m',
        RUNNER_OS='Linux',RUNNER_ARCH='X64',RUNNER_ENVIRONMENT='github-hosted')

def accepted():
    return dict(format=1,repository=D.REPO,workflow=D.WORKFLOW,revision='a'*40,
        closure_sha256=hashlib.sha256((HERE/'workflow-sources.json').read_bytes()).hexdigest(),
        native_sha256=hashlib.sha256((HERE/'native-sources.json').read_bytes()).hexdigest(),
        run_number='1',expires_at=2000)


def validate_workflow(value):
    # Real repository validator, synthetic JSON model; no Actions execution.
    command=['ruby','-I',str(ROOT/'scripts'),'-r','validate-workflow-capabilities','-r','json','-e',
        'Clb91BNativeWorkflow.validate(WorkflowCapabilityPolicy,JSON.parse(STDIN.read))']
    return subprocess.run(command,input=json.dumps(value).encode(),capture_output=True,timeout=3,check=False)


def workflow():
    command=['ruby','-r','yaml','-r','json','-e',
        'puts JSON.generate(YAML.load_file(ARGV[0]))',str(ROOT/D.WORKFLOW)]
    result=subprocess.run(command,capture_output=True,check=True,timeout=3)
    return json.loads(result.stdout)


class Approval(unittest.TestCase):
    def test_exact_public_record(self):
        self.assertEqual(D.approval(json.dumps(accepted()),context(),'a'*40,1001),accepted())

    def test_missing_duplicate_unknown_approval(self):
        for raw in ('','{}','{"format":1,"format":1}',json.dumps(dict(accepted(),secret='CANARY'))):
            with self.assertRaises((D.Refused,ValueError,KeyError)):
                D.approval(raw,context(),'a'*40,1001)

    def test_revision_cannot_follow_new_main(self):
        value=context();value['GITHUB_SHA']='b'*40
        with self.assertRaises(D.Refused):D.approval(json.dumps(accepted()),value,'b'*40,1001)

    def test_exact_run_attempt_actor_and_host(self):
        for key,value in (('GITHUB_RUN_NUMBER','2'),('GITHUB_RUN_ATTEMPT','2'),
            ('GITHUB_EVENT_NAME','push'),('GITHUB_REF','refs/heads/other'),
            ('GITHUB_REPOSITORY','foreign/repo'),('GITHUB_WORKFLOW','Tests'),
            ('GITHUB_ACTOR','another'),('GITHUB_TRIGGERING_ACTOR','another'),
            ('RUNNER_ENVIRONMENT','self-hosted'),('RUNNER_ARCH','ARM64'),
            ('GITHUB_WORKFLOW_REF',D.REPO+'/'+D.WORKFLOW+'@refs/heads/other')):
            changed=context();changed[key]=value
            with self.subTest(key=key):
                with self.assertRaises(D.Refused):D.approval(json.dumps(accepted()),changed,'a'*40,1001)

    def test_expiry_and_typed_identity(self):
        for key,value in (('expires_at',1000),('expires_at',5000),('expires_at',True),
            ('format',True),('run_number',1),('closure_sha256','a'*63),('native_sha256','unknown')):
            changed=accepted();changed[key]=value
            with self.assertRaises(D.Refused):D.approval(json.dumps(changed),context(),'a'*40,1001)

    def test_missing_record_refuses_without_privilege_call(self):
        with patch.dict(os.environ,context(),clear=True),patch.object(sys,'argv',['driver']):
            with patch.object(D,'capture') as capture,patch('sys.stdout',new_callable=io.StringIO) as output:
                self.assertEqual(D.main(),1)
        capture.assert_not_called();self.assertEqual(output.getvalue(),'CLB91_B_NATIVE_REFUSED\n')

    def test_no_root_operation_on_darwin(self):
        with patch.object(sys,'platform','darwin'):
            with self.assertRaises(D.Refused):D.root_operation('CANARY')
            with self.assertRaises(D.Refused):D.host_tools()


class Source(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.original=Path.cwd();os.chdir(self.temp.name);self.addCleanup(os.chdir,self.original)
        for name in D.SOURCES+(D.MANIFEST,):
            path=Path(name);path.parent.mkdir(parents=True,exist_ok=True)
            path.write_bytes((ROOT/name).read_bytes())

    def test_complete_closure(self):
        model,buffers=D.closure(accepted())
        self.assertEqual(set(model['files']),set(D.SOURCES))
        self.assertEqual(set(buffers),set(D.SOURCES))

    def test_each_runtime_or_workflow_byte_drift(self):
        for name in D.SOURCES:
            p=Path(name);raw=p.read_bytes();p.write_bytes(raw+b'\n')
            with self.assertRaises(D.Refused):D.closure(accepted())
            p.write_bytes(raw)

    def test_wrong_manifest_or_native_pin(self):
        for name in ('closure_sha256','native_sha256'):
            value=accepted();value[name]='0'*64
            with self.assertRaises(D.Refused):D.closure(value)

    def test_symlink_hardlink_mode_and_arbitrary_path_refuse(self):
        path=Path(D.DRIVER);raw=path.read_bytes();path.unlink();path.symlink_to('missing')
        with self.assertRaises(OSError):D.captured(D.DRIVER,65536)
        path.unlink();path.write_bytes(raw);os.link(path,'alias')
        with self.assertRaises(D.Refused):D.captured(D.DRIVER,65536)
        Path('alias').unlink();path.chmod(0o666)
        with self.assertRaises(D.Refused):D.captured(D.DRIVER,65536)
        with self.assertRaises(D.Refused):D.captured('/etc/passwd',65536)

    def test_short_read_refusal(self):
        with patch.object(D.os,'read',return_value=b''):
            with self.assertRaises(D.Refused):D.captured(D.DRIVER,65536)

    def test_source_substitution_during_capture(self):
        original=D.os.read;done=[]
        def read(fd,bound):
            raw=original(fd,bound)
            if raw and not done:
                done.append(True);p=Path(D.DRIVER);p.rename('retained');p.write_bytes(raw)
            return raw
        with patch.object(D.os,'read',side_effect=read):
            with self.assertRaises(D.Refused):D.captured(D.DRIVER,65536)

    def test_repository_source_validator_rejects_drift_and_duplicate_manifest(self):
        args=['ruby','-I',str(ROOT/'scripts'),'-r','validate-workflow-capabilities','-e',
            'Clb91BNativeWorkflow.sources(WorkflowCapabilityPolicy,Pathname.new(ARGV[0]))',str(Path.cwd())]
        def check():return subprocess.run(args,capture_output=True,timeout=3,check=False).returncode
        self.assertEqual(check(),0)
        for name in D.SOURCES:
            p=Path(name);raw=p.read_bytes();p.write_bytes(raw+b'\n')
            self.assertNotEqual(check(),0);p.write_bytes(raw)
        p=Path(D.MANIFEST);raw=p.read_bytes()
        p.write_bytes(b'{"format":1,'+raw[1:])
        self.assertNotEqual(check(),0)


class CapabilityFixture(unittest.TestCase):
    """Exercise the real selfcheck copier and parser against disposable Git trees."""
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        source=(ROOT/'scripts/selfcheck-quality-gates.sh').read_text()
        copier=source[source.index('copy_workflow_capability_fixture() {'):
                      source.index('\nmutate_workflow_capability_fixture() {')]
        script='set -euo pipefail\nfail() { echo "$1" >&2; exit 1; }\n'+copier+'\ncopy_workflow_capability_fixture regression\n'
        result=subprocess.run(['bash','-c',script],env={**os.environ,'ROOT_DIR':str(ROOT),'TMP_DIR':self.temp.name},
                              capture_output=True,text=True,timeout=15)
        self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        self.fixture=Path(result.stdout.strip())

    def validate(self):
        return subprocess.run(['ruby',str(ROOT/'scripts/validate-workflow-capabilities.rb'),str(self.fixture)],
                              capture_output=True,text=True,timeout=5)

    def test_complete_verified_closure_and_visible_inventory(self):
        self.assertEqual(self.validate().returncode,0)
        for name in D.SOURCES+(D.MANIFEST,):
            self.assertEqual((self.fixture/name).read_bytes(),(ROOT/name).read_bytes())
        self.assertEqual({p.name for p in (self.fixture/D.DRIVER).parent.iterdir()},
                         {Path(p).name for p in D.SOURCES+(D.MANIFEST,) if p.startswith(D.HERE)})
        args=['git','-C',str(self.fixture),'ls-files']
        tracked=subprocess.check_output(args,text=True).splitlines()
        untracked=subprocess.check_output(args+['--others','--exclude-standard'],text=True).splitlines()
        expected={'.github/workflows/release-status.yml','.github/workflows/corrected-stage-release.yml'}
        self.assertEqual(set(untracked),expected)
        self.assertFalse(expected.intersection(tracked))
        self.assertEqual(sum(p.startswith('.github/workflows/') for p in tracked+untracked),31)

    def test_each_missing_pinned_source_refuses(self):
        for name in D.SOURCES+(D.MANIFEST,):
            with self.subTest(name=name):
                path=self.fixture/name;raw=path.read_bytes();path.unlink()
                self.assertNotEqual(self.validate().returncode,0)
                path.write_bytes(raw)

    def test_each_modified_pinned_source_refuses(self):
        for name in D.SOURCES:
            with self.subTest(name=name):
                path=self.fixture/name;raw=path.read_bytes();path.write_bytes(raw+b'\n')
                self.assertNotEqual(self.validate().returncode,0)
                path.write_bytes(raw)

    def test_manifest_mismatch_and_ambiguous_schema_refuse(self):
        path=self.fixture/D.MANIFEST;raw=path.read_bytes();model=json.loads(raw)
        model['files'][D.DRIVER]='0'*64
        for changed in ((json.dumps(model,indent=2)+'\n').encode(),b'{"format":1,'+raw[1:],b'{}\n'):
            path.write_bytes(changed)
            self.assertNotEqual(self.validate().returncode,0)

    def test_malformed_and_unexpected_workflow_refuse(self):
        path=self.fixture/D.WORKFLOW
        for raw in (b'jobs: [\n',b'name: unexpected\non: push\npermissions: write-all\njobs: {}\n'):
            path.write_bytes(raw)
            self.assertNotEqual(self.validate().returncode,0)

    def test_other_capability_negatives_remain_enforced(self):
        path=self.fixture/'.github/workflows/unexpected.yml'
        for field in ('permissions: write-all','permissions: {}'):
            path.write_text('name: unexpected\non: push\n'+field+'\njobs:\n  test:\n    runs-on: ubuntu-latest\n    steps:\n      - run: echo synthetic\n')
            self.assertNotEqual(self.validate().returncode,0)


class Policy(unittest.TestCase):
    def test_exact_workflow_passes(self):
        self.assertEqual(validate_workflow(workflow()).returncode,0)

    def test_trigger_permission_runner_and_extra_job_refuse(self):
        value=workflow()
        candidates=[]
        for trigger in ('push','pull_request','workflow_run','schedule'):
            changed=deepcopy(value);changed['on'][trigger]=None;candidates.append(changed)
        for permission in ('actions','id-token','deployments','contents'):
            changed=deepcopy(value);changed['permissions'][permission]='write';candidates.append(changed)
        for runner in ('self-hosted','ubuntu-slim','ubuntu-latest'):
            changed=deepcopy(value);changed['jobs']['native']['runs-on']=runner;candidates.append(changed)
        changed=deepcopy(value);changed['jobs']['extra']=changed['jobs']['native'];candidates.append(changed)
        for changed in candidates:
            self.assertNotEqual(validate_workflow(changed).returncode,0)

    def test_stage_container_secrets_shell_fragments_refuse(self):
        value=workflow()
        for key,item in (('environment','stage'),('container','ubuntu:24.04'),('secrets','inherit'),
                         ('if',"github.event_name == 'workflow_dispatch'")):
            changed=deepcopy(value);changed['jobs']['native'][key]=item
            self.assertNotEqual(validate_workflow(changed).returncode,0)
        changed=deepcopy(value);changed['jobs']['native']['steps'][1]['run']='sudo sh -c "${{ inputs.script }}"'
        self.assertNotEqual(validate_workflow(changed).returncode,0)

    def test_concurrency_timeout_and_artifact_gate(self):
        value=workflow()
        self.assertEqual(value['concurrency'],{'group':'clb91-b-native-acceptance-single-v1','cancel-in-progress':False})
        changes=[]
        for cancel in (True,'${{ true }}'):
            changed=deepcopy(value);changed['concurrency']['cancel-in-progress']=cancel;changes.append(changed)
        for key,item in (('timeout-minutes',11),('continue-on-error',True)):
            changed=deepcopy(value);changed['jobs']['native'][key]=item;changes.append(changed)
        for key,item in (('path','**/*'),('retention-days',4),('if-no-files-found','ignore')):
            changed=deepcopy(value);changed['jobs']['native']['steps'][2]['with'][key]=item;changes.append(changed)
        changed=deepcopy(value);changed['jobs']['native']['steps'][2]['if']='${{ always() }}';changes.append(changed)
        for changed in changes:self.assertNotEqual(validate_workflow(changed).returncode,0)

    def test_checkout_and_command_exactness(self):
        value=workflow()
        for key,item in (('ref','main'),('persist-credentials',True)):
            changed=deepcopy(value);changed['jobs']['native']['steps'][0]['with'][key]=item
            self.assertNotEqual(validate_workflow(changed).returncode,0)
        with patch.object(D.subprocess,'Popen') as spawn:
            for argv in (['sudo','sh'],['/usr/bin/sudo','-n','--','/usr/bin/python3','-I','-S','-B','-c','CANARY','a'*64,'{}']):
                with self.assertRaises(D.Refused):D.capture(argv,lambda:False)
        spawn.assert_not_called()

    def test_no_automatic_integration_of_existing_tests(self):
        actual=(ROOT/'.github/workflows/tests.yml').read_bytes()
        original=subprocess.check_output(['git','show','HEAD:.github/workflows/tests.yml'],cwd=ROOT)
        self.assertEqual(actual,original)


class Artifact(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.original=Path.cwd();os.chdir(self.temp.name);self.addCleanup(os.chdir,self.original)
        temp=Path(self.temp.name)/'work/_temp';temp.mkdir(parents=True)
        out=temp/'_runner_file_commands/set_output_test';out.parent.mkdir();out.touch(mode=0o600)
        self.output=out
        self.env=dict(RUNNER_TEMP=str(temp),GITHUB_OUTPUT=str(out))

    def evidence(self):
        value=R.new(accepted()['revision'],accepted()['native_sha256'])
        value.update(verdict='FAIL',reason='platform')
        value['prerequisites']['platform']='FAIL'
        return R.encode(value)

    def publish(self,data,code=1):
        return D.publish(data,code,R,accepted(),'1234',lambda:False)

    def test_only_fixed_bounded_artifact_after_validation(self):
        with patch.dict(os.environ,self.env):self.publish(self.evidence())
        self.assertEqual(self.output.read_bytes(),b'evidence=validated\n')
        self.assertEqual(Path(D.ARTIFACT).stat().st_mode & 0o777,0o600)
        with patch.dict(os.environ,self.env):
            with self.assertRaises(FileExistsError):self.publish(self.evidence())

    def test_bounds_missing_result_and_symlink_refusal(self):
        with patch.dict(os.environ,self.env):
            for data in (b'x'*4097,b'{}',b'CANARY-PRIVATE',self.evidence()+self.evidence()):
                with self.assertRaises((D.Refused,R.Invalid,ValueError)):self.publish(data)
        Path(D.ARTIFACT).symlink_to(self.output)
        with patch.dict(os.environ,self.env):
            with self.assertRaises(OSError):self.publish(self.evidence())
        self.assertEqual(self.output.read_bytes(),b'')

    def test_sibling_file_command_directory_is_refused(self):
        sibling=Path(self.env['RUNNER_TEMP']).parent/'_runner_file_commands'
        sibling.mkdir();outside=sibling/'set_output_other';outside.touch()
        env=dict(self.env,GITHUB_OUTPUT=str(outside))
        with patch.dict(os.environ,env):
            with self.assertRaises(D.Refused):self.publish(self.evidence())
        self.assertEqual(outside.read_bytes(),b'')

    def test_cancellation_rolls_back_owned_publication(self):
        checks=[]
        def cancelled():
            checks.append(True)
            return len(checks)==3  # after marker append
        with patch.dict(os.environ,self.env):
            with self.assertRaises(D.Refused):
                D.publish(self.evidence(),1,R,accepted(),'1234',cancelled)
        self.assertFalse(Path(D.ARTIFACT).exists())
        self.assertEqual(self.output.read_bytes(),b'')

    def test_real_sigterm_after_capture_cannot_pass(self):
        value=R.new(accepted()['revision'],accepted()['native_sha256'])
        value.update(execution='NATIVE',run_id='1234',attempt='1',cleanup='PASS',verdict='PASS',reason='none')
        value['prerequisites']=dict.fromkeys(R.PREREQUISITES,'PASS')
        value['controls']=dict.fromkeys(R.CONTROLS,'PASS')
        before=signal.getsignal(signal.SIGTERM)
        for phase in ('source','publication'):
            original=D.closure;calls=[]
            def closure(record):
                result=original(record);calls.append(True)
                if phase=='source' and len(calls)==2:os.kill(os.getpid(),signal.SIGTERM)
                return result
            with patch.dict(os.environ,dict(context(),CLB91_B_NATIVE_APPROVAL=json.dumps(accepted()),CLB91_APPROVED_REVISION='a'*40),clear=True):
                with patch.object(sys,'argv',['driver']),patch.object(D.time,'time',return_value=1001):
                    with patch.object(D,'host_tools',return_value=[]),patch.object(D,'recheck_tools'),patch.object(D,'closure',side_effect=closure):
                        with patch.object(D,'capture',return_value=(0,R.encode(value))):
                            with patch.object(D,'publish',side_effect=lambda *args:os.kill(os.getpid(),signal.SIGTERM)) as publish:
                                with patch('sys.stdout',new_callable=io.StringIO) as output:
                                    os.chdir(ROOT)
                                    try:self.assertEqual(D.main(),1)
                                    finally:os.chdir(self.temp.name)
            if phase=='source':publish.assert_not_called()
            self.assertEqual(output.getvalue(),'CLB91_B_NATIVE_REFUSED\n')
            self.assertEqual(signal.getsignal(signal.SIGTERM),before)

    def test_raw_root_output_never_published_by_main(self):
        with patch.dict(os.environ,dict(context(),CLB91_B_NATIVE_APPROVAL=json.dumps(accepted()),CLB91_APPROVED_REVISION='a'*40),clear=True):
            with patch.object(sys,'argv',['driver']),patch.object(D.time,'time',return_value=1001):
                with patch.object(D,'host_tools',return_value=[]),patch.object(D,'recheck_tools'):
                    with patch.object(D,'capture',return_value=(0,b'CANARY-PRIVATE-RAW')):
                        with patch.object(D,'publish') as publish,patch('sys.stdout',new_callable=io.StringIO) as output:
                            os.chdir(ROOT)
                            try:self.assertEqual(D.main(),1)
                            finally:os.chdir(self.temp.name)
        publish.assert_not_called();self.assertEqual(output.getvalue(),'CLB91_B_NATIVE_REFUSED\n')


class PortableTransport(unittest.TestCase):
    """Substitute ONLY Popen with a real ordinary Python child, never sudo."""
    def capture(self,script,cancelled=lambda:False,clock=None):
        original=subprocess.Popen
        def spawn(argv,**kwargs):
            self.assertEqual(argv[0],'/usr/bin/sudo')
            return original([sys.executable,'-I','-S','-B','-c',script],**kwargs)
        args=['/usr/bin/sudo','-n','--','/usr/bin/python3','-I','-S','-B','-c',D.BOOTSTRAP,'a'*64,'{}']
        with patch.object(D.subprocess,'Popen',side_effect=spawn):
            if clock is None:return D.capture(args,cancelled)
            with patch.object(D.time,'monotonic',side_effect=clock):return D.capture(args,cancelled)

    def test_real_bounded_capture_nonzero_not_promoted(self):
        code,data=self.capture('import os;os.write(1,b"synthetic");raise SystemExit(1)')
        self.assertEqual((code,data),(1,b'synthetic'))

    def test_stderr_and_oversized_output_refuse(self):
        for code in ('import os;os.write(2,b"CANARY-PRIVATE-ERROR")',
                     'import os;os.write(1,b"C"*8192)'):
            with self.assertRaises(D.Refused) as failure:self.capture(code)
            self.assertEqual(str(failure.exception),'')

    def test_actual_owned_child_cancellation_and_timeout(self):
        with self.assertRaises(D.Refused):self.capture('import time;time.sleep(5)',lambda:True)
        ticks=iter([0,151])
        with self.assertRaises(D.Refused):
            self.capture('import time;time.sleep(5)',clock=lambda:next(ticks,151))

    def test_unconfirmed_reap_cannot_be_success(self):
        proc=Mock()
        proc.stdout=io.BytesIO();proc.stderr=io.BytesIO()
        proc.poll.return_value=None
        proc.wait.side_effect=subprocess.TimeoutExpired('synthetic',2)
        selector=Mock();selector.get_map.return_value={}
        args=['/usr/bin/sudo','-n','--','/usr/bin/python3','-I','-S','-B','-c',D.BOOTSTRAP,'a'*64,'{}']
        with patch.object(D.subprocess,'Popen',return_value=proc),patch.object(D.selectors,'DefaultSelector',return_value=selector):
            with patch.object(D.os,'set_blocking'):
                # fileno is unusable on BytesIO: fail closed still must try
                # termination and bounded wait, then refuse unconfirmed cleanup.
                with self.assertRaises(D.Refused):D.capture(args,lambda:False)
        proc.terminate.assert_called_once()


if __name__=='__main__':unittest.main(verbosity=2)
