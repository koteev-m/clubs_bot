#!/usr/bin/env python3
"""Synthetic UID-only tests: no network, SSH agent, protected inputs or host probes."""
import base64
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import shlex
import signal
import struct
import subprocess
import sys
import tempfile
import types
import unittest
from contextlib import contextmanager
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('principal_uid', ROOT / 'scripts/deploy/stage-principal-uid.py')
r = importlib.util.module_from_spec(spec); spec.loader.exec_module(r)
KEY = b'k' * 32


def environment():
    return dict(PATH=os.defpath, TMPDIR='/synthetic', SSH_AUTH_SOCK='/synthetic/agent',
                GITHUB_REPOSITORY='koteev-m/clubs_bot', GITHUB_EVENT_NAME='workflow_dispatch',
                GITHUB_REF='refs/heads/main', GITHUB_REF_TYPE='branch', REPOSITORY_DEFAULT_BRANCH='main',
                GITHUB_SHA='ab'*20, GITHUB_WORKFLOW_SHA='ab'*20, GITHUB_RUN_ID='500', GITHUB_RUN_ATTEMPT='1',
                GITHUB_WORKFLOW_REF='koteev-m/clubs_bot/'+r.WORKFLOW+'@refs/heads/main',
                APP_ENV='stage', CONFIRMATION=r.CONFIRMATION, SSH_USER='private-fixture-user',
                SSH_HOST='178.20.209.5', SSH_PORT='22', SSH_KNOWN_HOSTS='synthetic-pin')


def success(uid=1000):
    return dict(account_match='PASS', non_root='PASS', uid=uid, reason='none')


def frame(value, key=KEY):
    raw = r.canonical(value)
    return r.FRAME_PREFIX+hmac.new(key, raw, hashlib.sha256).hexdigest().encode()+b' '+raw+b'\n'


def result(output=b'', code=0, failure=None):
    return types.SimpleNamespace(output=output, code=code, failure=failure)


class ContractTest(unittest.TestCase):
    def test_manual_main_exact_revision_run_attempt_refusals(self):
        env=environment(); r.validate(env)
        mutations=dict(GITHUB_REPOSITORY='other/repo', GITHUB_EVENT_NAME='push', GITHUB_REF='refs/heads/topic',
                       GITHUB_REF_TYPE='tag', REPOSITORY_DEFAULT_BRANCH='topic', APP_ENV='prod', CONFIRMATION='',
                       GITHUB_RUN_ATTEMPT='2', GITHUB_RUN_ID='0500', GITHUB_SHA='f'*39,
                       GITHUB_WORKFLOW_SHA='f'*40, GITHUB_WORKFLOW_REF='unapproved')
        for key,value in mutations.items():
            with self.subTest(key=key), self.assertRaises(ValueError): r.validate({**env,key:value})
        for key in env:
            if key.startswith('GITHUB_'):
                with self.subTest(missing=key), self.assertRaises(ValueError): r.validate({k:v for k,v in env.items() if k!=key})

    def test_target_missing_secrets_endpoint_and_root_refusals(self):
        env=environment(); r.validate_target(env)
        for key,value in [('SSH_USER',''),('SSH_USER','root'),('SSH_USER','hookah-staging'),('SSH_USER','u;id'),
                          ('SSH_USER','a'*65),('SSH_HOST','example.invalid'),('SSH_PORT','2222'),('SSH_AUTH_SOCK','')]:
            with self.subTest(key=key,value=value), self.assertRaises(ValueError): r.validate_target({**env,key:value})

    def test_exact_single_ed25519_pin_positive_and_refusals(self):
        env=environment(); key=b'\x00\x00\x00\x0bssh-ed25519\x00\x00\x00\x20'+b'x'*32
        env['SSH_KNOWN_HOSTS']='178.20.209.5 ssh-ed25519 '+base64.b64encode(key).decode()+'\n'
        digest=base64.b64decode('Li2AIDm9/OG8CHWQw16qhDfzbRM7E9uLNjPeKOZ9ST0=')
        with patch.object(r.hashlib,'sha256',return_value=types.SimpleNamespace(digest=lambda:digest)):
            r.validate_pin(env)
            for pin in ['',env['SSH_KNOWN_HOSTS']*2,env['SSH_KNOWN_HOSTS'].replace('178.20.209.5','*'),
                        '@cert-authority '+env['SSH_KNOWN_HOSTS'],env['SSH_KNOWN_HOSTS'].replace('ssh-ed25519','ssh-rsa')]:
                with self.subTest(pin_category=pin[:12]), self.assertRaises(ValueError): r.validate_pin({**env,'SSH_KNOWN_HOSTS':pin})
        with self.assertRaises(ValueError): r.validate_pin(env)

    def test_transport_no_fallback_forwarding_retry_or_username_in_remote_code(self):
        env=environment(); argv=r.ssh_argv(env,'/synthetic/pin-fd')
        command=argv[-1]; self.assertNotIn(env['SSH_USER'],command)
        self.assertNotIn('id -un',command)
        remote=shlex.split(command)
        self.assertEqual(remote[:8],['/usr/bin/env','-i','PATH=/usr/bin:/bin','LC_ALL=C','/usr/bin/python3','-I','-S','-B'])
        for item in ['ConnectionAttempts=1','StrictHostKeyChecking=yes','HostKeyAlgorithms=ssh-ed25519',
                     'IdentityFile=none','CertificateFile=none','PasswordAuthentication=no','KbdInteractiveAuthentication=no',
                     'ProxyCommand=none','ProxyJump=none','ForwardAgent=no','ForwardX11=no','ClearAllForwardings=yes',
                     'ControlPath=none','ControlMaster=no','UpdateHostKeys=no','GlobalKnownHostsFile=/dev/null']:
            self.assertIn(item,argv)


class ProtocolTest(unittest.TestCase):
    def test_numeric_uid_positive_limits_root_and_types_refuse(self):
        for uid in (1,1000,r.UID_MAX): self.assertEqual(r.parse_result(frame(success(uid)),0,KEY)['uid'],uid)
        for uid in (0,-1,r.UID_MAX+1,True,'1000',1.0,None):
            with self.subTest(uid=uid), self.assertRaises(ValueError): r.parse_result(frame(success(uid)),0,KEY)

    def test_authentication_replay_malformed_duplicate_oversized_and_ambiguous(self):
        good=frame(success())
        for raw,code,key in [(good,255,KEY),(good,1,KEY),(good,0,b'z'*32),(good+b'extra',0,KEY),
                             (good[:-1],0,KEY),(good.replace(b'1000',b'1001'),0,KEY),(b'x'*(r.LIMIT+1),0,KEY)]:
            with self.subTest(code=code,length=len(raw)), self.assertRaises(ValueError): r.parse_result(raw,code,key)
        for raw in (b'{"uid":1,"uid":2}',b'{}',r.canonical({**success(),'username':'canary'})):
            signed=r.FRAME_PREFIX+hmac.new(KEY,raw,hashlib.sha256).hexdigest().encode()+b' '+raw+b'\n'
            with self.assertRaises(ValueError): r.parse_result(signed,0,KEY)

    def test_refusal_and_unknown_cannot_attest_independent_host(self):
        for reason in ('platform','root','identity','interrupted'):
            body=dict(account_match='UNKNOWN',non_root='FAIL' if reason=='root' else 'UNKNOWN',uid=None,reason=reason)
            r.parse_result(frame(body),1,KEY)
            public,code=r.publication(r.record(environment(),reason,body),environment())
            self.assertEqual(code,1); parsed=json.loads(public[len(r.PREFIX):])
            self.assertEqual(parsed['independent_host_binding'],'UNKNOWN'); self.assertEqual(parsed['startup_trust'],'UNKNOWN')

    def test_public_secret_mask_prediction_and_persisted_mask_unknown(self):
        env=environment(); line,code=r.publication(r.record(env,'none',success()),env)
        self.assertEqual(code,0)
        expected=dict(revision=env['GITHUB_SHA'],run_id=env['GITHUB_RUN_ID'],attempt=1)
        self.assertEqual(r.parse_public(line,expected)['uid'],1000)
        self.assertEqual(r.parse_public(line.replace(b'1000',b'***'),expected)['result'],'UNKNOWN')
        for secret in ('1000','500','ab','1'):
            secret_env={**env,'SSH_USER':secret}
            refusal,code=r.publication(r.record(env,'none',success()),secret_env)
            self.assertEqual(code,1); self.assertEqual(r.parse_public(refusal,expected)['reason'],'publication')
        for field,value in [('revision','f'*40),('run_id','501'),('attempt',2)]:
            with self.subTest(field=field),self.assertRaises(ValueError): r.parse_public(line,{**expected,field:value})
        for flag in ('independent_host_binding','startup_trust'):
            parsed=json.loads(line[len(r.PREFIX):]); parsed[flag]='PASS'
            with self.assertRaises(ValueError): r.parse_public(r.PREFIX+r.canonical(parsed)+b'\n',expected)
        for private in (env['SSH_USER'],env['SSH_HOST'],env['SSH_KNOWN_HOSTS'],hashlib.sha256(env['SSH_USER'].encode()).hexdigest()):
            self.assertNotIn(private.encode(),line)


class BootstrapTest(unittest.TestCase):
    def invoke(self, uid=1000, euid=1000, username='private-fixture-user', platform='linux'):
        # Synthetic OS/account answers, actual isolated interpreter/framing/process.
        prefix='import os,pwd,sys,types\n'
        prefix+=f'os.getuid=lambda:{uid!r}\nos.geteuid=lambda:{euid!r}\nsys.platform={platform!r}\n'
        prefix+=f'pwd.getpwuid=lambda unused:types.SimpleNamespace(pw_name={username!r})\n'
        control=r.canonical(dict(principal='private-fixture-user'))
        return subprocess.run([sys.executable,'-I','-S','-B','-c',prefix+r.BOOTSTRAP],
                              input=KEY+struct.pack('!I',len(control))+control,stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE,timeout=5)

    def test_actual_bootstrap_uid_selfreport_only(self):
        process=self.invoke(); self.assertEqual(process.returncode,0); self.assertEqual(process.stderr,b'')
        self.assertEqual(r.parse_result(process.stdout,0,KEY),success())
        self.assertNotIn(b'private-fixture-user',process.stdout)

    def test_root_mismatch_setuid_and_unsupported_platform_refuse(self):
        for args,reason in [(dict(uid=0,euid=0),'root'),(dict(username='wrong-fixture'),'identity'),
                            (dict(euid=1001),'identity'),(dict(platform='darwin'),'platform')]:
            with self.subTest(reason=reason):
                process=self.invoke(**args); self.assertEqual(process.returncode,1); self.assertEqual(process.stderr,b'')
                self.assertEqual(r.parse_result(process.stdout,1,KEY)['reason'],reason)


class SourceTest(unittest.TestCase):
    def test_pinned_dependency_and_git_source_closure_positive(self):
        primitive=r.load_primitive(); env=environment()
        paths=(*primitive.LOCAL_SOURCES,primitive.REMOTE_PATH,r.RUNNER,r.WORKFLOW)
        raw={p:(ROOT/p).read_bytes() for p in paths}
        def capture(argv,**kwargs):
            args=argv[4:]
            if args==['rev-parse','HEAD']: return result((env['GITHUB_SHA']+'\n').encode())
            if args[0]=='ls-tree':
                path=args[-1]; data=raw[path]; blob=hashlib.sha1(f'blob {len(data)}\0'.encode()+data).hexdigest()
                return result(('100644 blob '+blob+'\t'+path+'\0').encode())
            if args[0]=='cat-file':
                return result(next(data for data in raw.values() if hashlib.sha1(f'blob {len(data)}\0'.encode()+data).hexdigest()==args[-1]))
            raise AssertionError('unexpected Git command')
        with patch.object(primitive,'capture_result',side_effect=capture):
            observed,transport=r.snapshot(env,primitive,[False]); self.assertEqual(observed,raw)
            self.assertTrue(callable(transport.capture_result))
            with patch.object(r,'read_source',side_effect=lambda p:raw[p]+b'changed'):
                with self.assertRaises(ValueError): r.snapshot(env,primitive,[False])
        with patch.object(primitive,'capture_result',return_value=result(b'other-head\n')):
            with self.assertRaises(ValueError): r.snapshot(env,primitive,[False])

    def test_wrong_pin_or_symlink_refuses_before_dependency_execution(self):
        with patch.object(r,'read_source',return_value=b'poison'),patch.object(r,'exec',create=True) as execute:
            with self.assertRaises(ValueError): r.load_primitive()
            execute.assert_not_called()
        with tempfile.TemporaryDirectory() as directory:
            base=Path(directory); (base/'scripts/deploy').mkdir(parents=True)
            (base/r.PRIMITIVE).symlink_to(ROOT/r.PRIMITIVE)
            with patch.object(r,'ROOT',base),self.assertRaises(ValueError): r.load_primitive()


class LifecycleTest(unittest.TestCase):
    def run_main(self, mode='success', drift=False, pin_error=False, args=None):
        env=environment(); cancelled=[False]; ssh_calls=[]; cleanup=[]
        primitive=types.SimpleNamespace(_capture_cancellation=None,capture_result=lambda *a,**k:result((env['GITHUB_SHA']+'\n').encode()))
        @contextmanager
        def pin(unused):
            with tempfile.TemporaryFile() as handle:
                try: yield handle.fileno(),'/synthetic/pin-fd'
                finally: cleanup.append(True)
        def capture(argv,payload,**kwargs):
            ssh_calls.append((argv,kwargs)); self.assertEqual(kwargs['timeout'],30); self.assertEqual(kwargs['limit'],4096)
            self.assertEqual(set(kwargs['env']),{'PATH','SSH_AUTH_SOCK','LC_ALL'})
            self.assertNotIn(env['SSH_USER'],argv[-1]); self.assertIn(env['SSH_USER'].encode(),payload)
            if mode=='interrupt': cancelled[0]=True
            if mode in ('timeout','ambiguous','interrupt'): return result(b'',124,'capture_timeout')
            if mode=='malformed': return result(b'raw-private-canary',0)
            return result(frame(success(),payload[:32]))
        transport=types.SimpleNamespace(pinned_hosts=pin,capture_result=capture,_capture_cancellation=None)
        with patch.object(r,'load_primitive',return_value=primitive),patch.object(r,'snapshot',return_value=({'fixed':b'original'},transport)), \
             patch.object(r,'read_source',return_value=b'changed' if drift else b'original'),patch.object(r.signal,'signal'), \
             patch.object(r,'validate_pin',side_effect=ValueError() if pin_error else None):
            line,code=r.main(env,args or [],cancelled)
        return line,code,ssh_calls,cleanup

    def test_positive_one_ssh_and_cleanup_before_publication(self):
        line,code,calls,cleanup=self.run_main(); self.assertEqual(code,0); self.assertEqual(len(calls),1); self.assertEqual(cleanup,[True])
        value=json.loads(line[len(r.PREFIX):]); self.assertEqual(value['uid'],1000); self.assertEqual(value['result'],'PASS')
        self.assertEqual(value['independent_host_binding'],'UNKNOWN'); self.assertEqual(value['startup_trust'],'UNKNOWN')

    def test_timeout_interruption_ambiguous_malformed_no_retry_or_leak(self):
        for mode in ('timeout','interrupt','ambiguous','malformed'):
            with self.subTest(mode=mode):
                line,code,calls,cleanup=self.run_main(mode); self.assertEqual(code,1); self.assertEqual(len(calls),1)
                self.assertEqual(cleanup,[True]); self.assertNotIn(b'canary',line); self.assertNotIn(b'private-fixture-user',line)
                self.assertIsNone(json.loads(line[len(r.PREFIX):])['uid'])

    def test_source_drift_refuses_after_single_connection(self):
        line,code,calls,cleanup=self.run_main(drift=True); self.assertEqual(code,1); self.assertEqual(len(calls),1)
        self.assertEqual(json.loads(line[len(r.PREFIX):])['reason'],'source'); self.assertEqual(cleanup,[True])

    def test_bad_pin_or_validate_never_connects(self):
        line,code,calls,cleanup=self.run_main(pin_error=True); self.assertEqual(code,1); self.assertEqual(calls,[])
        self.assertEqual(json.loads(line[len(r.PREFIX):])['reason'],'host_pin')
        line,code,calls,cleanup=self.run_main(args=['--validate']); self.assertEqual(code,0); self.assertEqual(calls,[])


class WorkflowTest(unittest.TestCase):
    def test_exact_capability_positive_and_refusal_mutations(self):
        script='''require "validate-workflow-capabilities"
w,_=WorkflowCapabilityPolicy.load_workflow(Pathname.new(ARGV[0]),StagePrincipalUidWorkflow::PATH)
case ARGV[1]
when "trigger"; w["on"]["push"]={}
when "input"; w["on"]["workflow_dispatch"]["inputs"]["user"]={"type"=>"string"}
when "environment"; w["jobs"]["identity"]["environment"]="prod"
when "permission"; w["permissions"]["contents"]="write"
when "concurrency"; w["concurrency"]["group"]="other"
when "cancel"; w["concurrency"]["cancel-in-progress"]=true
when "checkout"; w["jobs"]["identity"]["steps"][0]["with"]["ref"]="main"
when "credential_order"; s=w["jobs"]["identity"]["steps"];s[1],s[2]=s[2],s[1]
when "public_key"; w["jobs"]["identity"]["steps"][2]["with"]["log-public-key"]=true
when "artifact"; w["jobs"]["identity"]["steps"] << {"uses"=>"actions/upload-artifact@unapproved"}
end
StagePrincipalUidWorkflow.validate(WorkflowCapabilityPolicy,w)
'''
        for mutation in ('none','trigger','input','environment','permission','concurrency','cancel','checkout','credential_order','public_key','artifact'):
            process=subprocess.run(['ruby','-I',str(ROOT/'scripts'),'-e',script,str(ROOT),mutation],capture_output=True,timeout=10)
            self.assertEqual(process.returncode==0,mutation=='none',mutation)
        self.assertEqual((ROOT/'scripts/selfcheck-quality-gates.sh').read_text().count('test_stage_principal_uid.py'),1)


if __name__ == '__main__': unittest.main()
