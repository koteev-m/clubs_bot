"""Portable contract/boundary tests. Full namespace acceptance is native-only."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import runpy
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent


def load(name):
    spec = importlib.util.spec_from_file_location('clb195_'+name, HERE/(name+'.py'))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


b = load('bootstrap')
build = load('build')
ci = load('native-ci')
p = runpy.run_path(str(build.HELPER/'handoff.py'))
NONCE = 'a'*32
ENV = {'PATH':'/usr/bin:/bin','HOME':'/run/user/1000','LC_ALL':'C'}


class Tests(unittest.TestCase):
    def boundary(self, fault=None, reply=None):
        events = []
        def event(name):
            events.append(name)
            if fault == name:
                raise ValueError('PRIVATE_CANARY')
        class Runtime:
            def __init__(self, cancelled): pass
            def __getattr__(self, name):
                return lambda *args: event('runtime.'+name)
        class Context:
            backing = 'original'
            def __init__(self, principal, cancelled):
                event('principal'); b.need(principal == 'prototype')
            def recheck(self): event('identity/locks')
            def mount_identity(self):
                event('mount'); return 'other' if fault == 'mount_mismatch' else self.backing
            def close(self): event('context.close')
        op = types.SimpleNamespace(Runtime=Runtime, interpolation_context=lambda values: {},
             D=types.SimpleNamespace(ReadOnlyCapture=Context,require=lambda ok,*a:b.need(ok)))
        def snapshot(op, context, nonce, interpolation):
            event('private_read'); return b'PRIVATE_CANARY'
        protocol = types.SimpleNamespace(snapshot=snapshot,response=p['response'])
        def env():
            event('environment'); return dict(ENV)
        def exchange(raw):
            event('handoff'); self.assertEqual(raw,b'PRIVATE_CANARY')
            return reply if reply is not None else p['public'](NONCE,'equivalent','remove')
        return op,protocol,exchange,env,events

    def test_success_orders_runtime_before_private_and_rechecks_before_close(self):
        op,proto,x,env,events = self.boundary()
        result = b.produce(op,proto,NONCE,x,env,lambda:False)
        self.assertEqual(result,p['public'](NONCE,'equivalent','remove'))
        self.assertLess(events.index('runtime.ruby_probe'),events.index('environment'))
        self.assertLess(events.index('runtime.recheck'),events.index('private_read'))
        self.assertLess(events.index('handoff'),events.index('identity/locks'))
        self.assertEqual(events[-2:],['context.close','runtime.close'])

    def test_prerequisite_refuses_before_any_environment_or_capture(self):
        for fault in ('runtime.open','runtime.private_root','runtime.require_compatible',
                      'runtime.ruby_probe','runtime.recheck'):
            op,proto,x,env,events = self.boundary(fault)
            with self.subTest(fault=fault),self.assertRaises(Exception):
                b.produce(op,proto,NONCE,x,env,lambda:False)
            self.assertNotIn('environment',events)
            self.assertNotIn('private_read',events)
            self.assertEqual(events[-1],'runtime.close')

    def test_capture_drift_principal_mount_lock_and_cleanup_refuse(self):
        for fault in ('principal','private_read','handoff','identity/locks','mount_mismatch',
                      'context.close','runtime.close'):
            op,proto,x,env,events = self.boundary(fault)
            with self.subTest(fault=fault),self.assertRaises(Exception):
                b.produce(op,proto,NONCE,x,env,lambda:False)
            self.assertIn('runtime.close',events)

    def test_interpolation_context_cannot_be_partial_or_augmented(self):
        for env in ({}, {k:v for k,v in ENV.items() if k!='HOME'},dict(ENV,A='PRIVATE_CANARY')):
            op,proto,x,_,events = self.boundary()
            with self.assertRaises(Exception):
                b.produce(op,proto,NONCE,x,lambda:env,lambda:False)
            self.assertNotIn('private_read',events)

    def test_replay_tamper_extra_output_and_cancel_do_not_publish_success(self):
        for reply in (p['public']('b'*32,'equivalent','remove'),b'PRIVATE_CANARY',
                      p['public'](NONCE,'equivalent','remove')+b'x',b'x'*513):
            op,proto,x,env,events = self.boundary(reply=reply)
            with self.assertRaises(Exception):
                b.produce(op,proto,NONCE,x,env,lambda:False)
            self.assertEqual(events[-2:],['context.close','runtime.close'])
        for when in ('before','after'):
            op,proto,x,env,events = self.boundary()
            with self.assertRaises(Exception):
                b.produce(op,proto,NONCE,x,env,lambda:when=='before' or 'handoff' in events)

    def test_real_pipe_eof_and_bound(self):
        for raw,limit,passes in [(b'abc',3,True),(b'abcd',3,False)]:
            rd,wr=os.pipe()
            try:
                b.write_all(wr,raw);os.close(wr);wr=-1
                if passes:self.assertEqual(b.read_bounded(rd,limit),raw)
                else:
                    with self.assertRaises(Exception):b.read_bounded(rd,limit)
            finally:
                os.close(rd)
                if wr>=0:os.close(wr)

    def test_real_blocked_read_timeout_and_signal(self):
        # Actual pipe read interrupted by a signal; no subprocess raw output.
        code = ('import runpy,os,signal\n'
                'b=runpy.run_path('+repr(str(HERE/'bootstrap.py'))+')\n'
                'r,w=os.pipe()\n'
                'def stop(*a): raise InterruptedError()\n'
                'signal.signal(signal.SIGALRM,stop);signal.alarm(1)\n'
                'try:\n b["read_bounded"](r,512);raise AssertionError()\n'
                'except InterruptedError: pass\n'
                'finally: os.close(r);os.close(w)\n')
        result=subprocess.run([sys.executable,'-I','-S','-B','-c',code],capture_output=True,timeout=4)
        self.assertEqual((result.returncode,result.stdout,result.stderr),(0,b'',b''))

    def test_exact_builder_and_pinned_reuse(self):
        driver=runpy.run_path(str(build.NATIVE/'tests/native-driver.py'))
        with tempfile.TemporaryDirectory() as td:
            dest=Path(td)/'candidate'; info=build.materialize(dest,driver)
            original=(build.NATIVE/'adapter/adapter.c').read_text()
            generated=(dest/'adapter/adapter.c').read_text()
            start=original.index('static void namespace_child(')
            end=original.index('static int namespace_start(',start)
            self.assertEqual(generated,original[:start]+(HERE/'namespace.h').read_text()+'\n'+original[end:])
            header=(dest/'adapter/generated_contract.h').read_text()
            raw=re.search(r'BOOTSTRAP\[\] = \{(.*?)\n\};',header,re.S)[1]
            raw=bytes(int(x,16) for x in re.findall(r'0x([0-9a-f]{2})',raw))[:-1]
            self.assertEqual(hashlib.sha256(raw).hexdigest(),info['bootstrap'])
            need=compile(raw,'generated','exec')
            ns={'__name__':'test'};exec(need,ns)
            array=re.search(r'REQUEST_TAIL\[\] = \{(.*?)\n\};',header,re.S)[1]
            tail=bytes(int(x,16) for x in re.findall(r'0x([0-9a-f]{2})',array))[:-1]
            frame=b'a'*32+tail
            nonce,op,helper,protocol=ns['load'](frame)
            self.assertEqual(nonce,b'a'*32)
            self.assertEqual(protocol.PROJECT,'clubs-bot-stage')
            for bad in (frame[:-1],frame+b'x',frame[:100]+bytes([frame[100]^1])+frame[101:]):
                with self.assertRaises(Exception):ns['load'](bad)
        with tempfile.TemporaryDirectory() as td:
            path=Path(td)/'source';path.write_bytes(b'tampered')
            with self.assertRaises(ValueError):build.checked(path,'0'*64)

    def test_evidence_cannot_turn_pending_cleanup_into_pass(self):
        data=dict(format=1,verdict='PASS',sha='a'*40,run_id='1',attempt='1',
             sources={n:hashlib.sha256((HERE/n).read_bytes()).hexdigest() for n in
                      ('build.py','bootstrap.py','namespace.h','native-ci.py','test-bootstrap.py')},
             build={'adapter':'a'*64,'fixture':'b'*64},
             steps={n:'PASS' for n in ('prior','portable','core','capture')},cleanup='PASS',
             phase='complete',native={n:'PASS' for n in ci.CONTROLS})
        ci.validate_evidence(data)
        for field in ('prior','portable','core','capture'):
            bad=json.loads(json.dumps(data));bad['steps'][field]='NOT_RUN'
            with self.assertRaises(Exception):ci.validate_evidence(bad)
        data['cleanup']='UNKNOWN'
        with self.assertRaises(Exception):ci.validate_evidence(data)

    def test_native_result_requires_exact_complete_boolean_controls(self):
        good=dict(verdict='PASS',cleanup='confirmed',cleanup_error=False,
             negative_controls={n:True for n in ci.CONTROLS[1:]},
             adapter_result=dict(primary='semantic_complete',exit=0,original_recheck=True,
                                 view_held_fd_identity=True,cleanup_error=False))
        self.assertEqual(ci.native_result(good,0),{n:'PASS' for n in ci.CONTROLS})
        for controls in ({}, {'wrong_uid':True}, {n:1 for n in ci.CONTROLS[1:]},
                         dict(good['negative_controls'],extra=True),
                         dict(good['negative_controls'],runtime_hash=False)):
            with self.subTest(controls=controls),self.assertRaises(Exception):
                ci.native_result(dict(good,negative_controls=controls),0)
        for name,value in [('primary','unknown'),('original_recheck',False),
                           ('view_held_fd_identity',False),('cleanup_error',True)]:
            bad=dict(good,adapter_result=dict(good['adapter_result'],**{name:value}))
            with self.assertRaises(Exception):ci.native_result(bad,0)
        with self.assertRaises(Exception):ci.native_result(good,1)

    def test_artifact_boundary_rejects_leaks_stale_identity_and_missing_prior(self):
        checker=runpy.run_path(str(build.NATIVE/'ci/check-artifacts.py'))['check']
        data=dict(format=1,verdict='FAIL',sha='a'*40,run_id='1',attempt='1',
             sources={n:hashlib.sha256((HERE/n).read_bytes()).hexdigest() for n in
                      ('build.py','bootstrap.py','namespace.h','native-ci.py','test-bootstrap.py')},
             build={},steps={n:'NOT_RUN' for n in ('prior','portable','core','capture')},cleanup='NOT_RUN',
             phase='prior',native={n:'NOT_RUN' for n in ci.CONTROLS})
        with tempfile.TemporaryDirectory() as td,patch.dict(os.environ,{},clear=True):
            root=Path(td).resolve();folder=root/'clb195-namespace';folder.mkdir()
            path=folder/'result.json';path.write_text(json.dumps(data))
            self.assertEqual(checker(root)['checked_files'],1)
            for change in ('private','identity','sources','bound','prior'):
                bad=json.loads(json.dumps(data))
                if change=='private':bad['snapshot']='PRIVATE_CANARY'
                if change=='sources':bad['sources']['bootstrap.py']='0'*64
                if change=='prior':
                    bad.update(verdict='PASS',cleanup='PASS',build={'adapter':'a'*64,'fixture':'b'*64})
                    bad['steps']={k:'PASS' for k in bad['steps']}
                    bad['phase']='complete';bad['native']={n:'PASS' for n in ci.CONTROLS}
                path.write_text(json.dumps(bad)+(' '*4096 if change=='bound' else ''))
                environment={'GITHUB_SHA':'b'*40,'GITHUB_RUN_ID':'1','GITHUB_RUN_ATTEMPT':'1'} if change=='identity' else {}
                with self.subTest(change=change),patch.dict(os.environ,environment,clear=True),self.assertRaises(Exception):
                    checker(root)
            path.write_text(json.dumps(data));(folder/'private.txt').write_text('PRIVATE_CANARY')
            with self.assertRaises(Exception):checker(root)

    def test_generated_common_c_core_real_lifecycle(self):
        # Actual common native-adapter C primitives on the host, no namespaces/root.
        # This checks the newly generated variant's framing, held-file drift,
        # real child timeout/signal and owned cleanup; never a native PASS.
        driver=runpy.run_path(str(build.NATIVE/'tests/native-driver.py'))
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);build.materialize(root/'variant',driver)
            command=['cc','-std=c11','-O1','-Wall','-Wextra','-Werror',
                     '-Wno-unused-function','-Wno-misleading-indentation',
                     str(root/'variant/adapter/core-tests.c'),'-o',str(root/'core')]
            compiled=subprocess.run(command,capture_output=True,timeout=45)
            self.assertEqual(compiled.returncode,0,compiled.stderr.decode()[:2048])
            result=subprocess.run([str(root/'core')],capture_output=True,timeout=10)
            self.assertEqual(result.returncode,0)
            self.assertEqual(result.stderr,b'')
            self.assertLess(len(result.stdout),16384)
            records=[json.loads(line) for line in result.stdout.splitlines()]
            self.assertEqual(records[-1]['summary']['failed'],0)
            self.assertEqual(records[-1]['summary']['native_namespace'],'NOT_RUN')

    def test_native_isolation_source_contract(self):
        text=(HERE/'namespace.h').read_text()
        for word in ('CLONE_NEWNS|CLONE_NEWPID|CLONE_NEWNET','MS_REC|MS_PRIVATE',
                     'SYS_close_range,5U','setresuid(1000,1000,1000)',
                     'PR_SET_NO_NEW_PRIVS','PR_SET_PDEATHSIG','alarm(100)',
                     'signal(SIGALRM,ns195_stop)', 'ns195_stop(int sig) {_exit(128+sig);}',
                     'mount("tmpfs",SOURCE_ROOT','mount("proc","/proc"'):
            self.assertIn(word,text)
        for forbidden in ('system(', 'popen(', '/var/run/docker.sock', 'setns('):
            self.assertNotIn(forbidden,text)


if __name__=='__main__':
    unittest.main()
