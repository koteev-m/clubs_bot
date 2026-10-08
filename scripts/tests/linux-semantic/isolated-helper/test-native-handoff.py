#!/usr/bin/env python3
"""CLB-192 portable integration/evidence controls, not native acceptance."""
import copy
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
m = runpy.run_path(str(HERE/'native-handoff-ci.py'))
old = runpy.run_path(str(HERE/'native-ci.py'))
checker = runpy.run_path(str(HERE.parent/'native-adapter/ci/check-artifacts.py'))


def evidence():
    return dict(format=1, verdict='PASS', sha='a'*40, run_id='123', attempt='1', native='PASS',
        image='sha256:'+'b'*64, manifest_sha256=m['MANIFEST'], sources=dict(m['SOURCE_PINS']),
        steps={n:'PASS' for n in m['STEPS']}, cleanup='PASS')


def dependencies(root, data):
    prior = root/'clb191-isolated-helper/result.json'
    prior.parent.mkdir(parents=True)
    prior.write_text(json.dumps(dict(format=1,verdict='PASS',sha=data['sha'],run_id=data['run_id'],
        attempt=data['attempt'],image=data['image'],manifest_sha256=old['MANIFEST'],
        helper_sha256=old['HELPER'],steps={n:'PASS' for n in old['STEPS']})))
    prep = root/'clb91-adapter-inputs/evidence/status.json'
    prep.parent.mkdir(parents=True)
    prep.write_text(json.dumps(dict(verdict='EXACT_NATIVE_INPUTS_PREPARED',reference_image=data['image'],
        run_identity=dict(GITHUB_SHA=data['sha'],GITHUB_RUN_ID=data['run_id'],GITHUB_RUN_ATTEMPT=data['attempt']))))


class NativeHandoffControls(unittest.TestCase):
    def test_exact_source_closure_and_each_tamper_before_exec(self):
        captured = m['source_bytes']()
        self.assertEqual(set(captured), set(m['SOURCE_PINS']))
        with tempfile.TemporaryDirectory() as td:
            root = Path(td).resolve()
            for name, raw in captured.items(): (root/name).write_bytes(raw)
            for name, raw in captured.items():
                with self.subTest(name=name):
                    (root/name).write_bytes(raw+b'\nraise AssertionError("must never execute")\n')
                    with self.assertRaises(ValueError): m['source_bytes'](root)
                    (root/name).write_bytes(raw)
            target = root/'handoff.py'
            target.unlink(); target.symlink_to(HERE/'handoff.py')
            with self.assertRaises(OSError): m['source_bytes'](root)

    def test_evidence_exact_identity_states_source_runtime_and_bounds(self):
        good = evidence()
        m['validate_evidence'](good, good)
        cases = []
        for key in good:
            bad = copy.deepcopy(good); del bad[key]; cases.append(bad)
        for key,value in [('format',True),('verdict','CANARY'),('attempt','2'),('trace','CANARY'),
                          ('native','NOT_RUN'),('cleanup','NOT_RUN'),('image','mac-tag'),
                          ('manifest_sha256','0'*64),('sources',{}),('sha','x'*5000)]:
            bad=copy.deepcopy(good);bad[key]=value;cases.append(bad)
        for stage in m['STEPS']:
            for state in ('FAIL','NOT_RUN',True):
                bad=copy.deepcopy(good);bad['steps'][stage]=state;cases.append(bad)
        for bad in cases:
            with self.assertRaises((ValueError,TypeError)): m['validate_evidence'](bad)
        for key in ('sha','run_id','attempt'):
            changed=dict(good);changed[key]='b'*40 if key=='sha' else '2'
            with self.assertRaises(ValueError): m['validate_evidence'](good,changed)
        bad=copy.deepcopy(good);bad['verdict']='FAIL'
        with self.assertRaises(ValueError):m['validate_evidence'](bad)
        bad['steps']['remove']='FAIL'
        m['validate_evidence'](bad)

    def test_artifact_corruption_extra_duplicate_size_symlink_and_substitution(self):
        with tempfile.TemporaryDirectory() as td, patch.dict(os.environ,{},clear=True):
            root=Path(td).resolve();base=root/'clb192-handoff';base.mkdir();path=base/'result.json'
            good=evidence();dependencies(root,good);raw=json.dumps(good)
            path.write_text(raw)
            self.assertEqual(checker['check'](root)['checked_files'],3)
            for broken in (raw[:-1], raw+' '*4096, raw.replace('"format": 1','"format": 1,"format": 1')):
                path.write_text(broken)
                with self.assertRaises(ValueError):checker['check'](root)
            for key,value in [('sha','c'*40),('image','sha256:'+'c'*64),('sources',{})]:
                changed=copy.deepcopy(good);changed[key]=value;path.write_text(json.dumps(changed))
                with self.assertRaises(ValueError):checker['check'](root)
            path.write_text(raw)
            with patch.dict(os.environ,dict(GITHUB_SHA='c'*40,GITHUB_RUN_ID='123',GITHUB_RUN_ATTEMPT='1')):
                with self.assertRaises(ValueError):checker['check'](root)
            (base/'extra.log').write_text('CANARY')
            with self.assertRaises(ValueError):checker['check'](root)
            (base/'extra.log').unlink();path.unlink();path.symlink_to(root/'absent')
            with self.assertRaises(ValueError):checker['check'](root)

    def test_missing_prior_acceptance_cannot_publish_native_pass(self):
        with tempfile.TemporaryDirectory() as td, patch.dict(os.environ,{},clear=True):
            root=Path(td).resolve();base=root/'clb192-handoff';base.mkdir()
            (base/'result.json').write_text(json.dumps(evidence()))
            with self.assertRaises(ValueError):checker['check'](root)

    def test_explicit_verified_image_and_stderr_policy(self):
        launcher=runpy.run_path(str(HERE/'run-handoff.py'))
        image='sha256:'+'a'*64
        for transport in (None,'b'*64):
            args=launcher['command']('test',Path('/export'),'source','a'*32,'remove',transport=transport,image=image)
            self.assertIn(image,args)
            self.assertNotIn(launcher['IMAGE'],args)
            self.assertIn('exec "$@" 2>&1',args)
            for flag in ('--log-driver=none','--read-only','--network=none','--user=1000:1000',
                         '--cap-drop=ALL','--security-opt=no-new-privileges','--pull=never'):
                self.assertIn(flag,args)
            self.assertNotIn('source',args)
        with self.assertRaises(ValueError):launcher['command']('x',Path('/x'),'code','a'*32,'remove',image='tag')

    def test_suite_failure_skip_or_noise_cannot_emit_success(self):
        with tempfile.TemporaryDirectory() as td:
            path=Path(td)/'case.py'
            code=m['suite_code'](path,('Case',),1)
            for body,ok in [('pass',True),('self.fail("synthetic")',False),
                            ('self.skipTest("unavailable")',False),('print("SYNTHETIC_CANARY")',False),
                            ('print("x"*65537)',False)]:
                path.write_text('import unittest\nclass Case(unittest.TestCase):\n def test_one(self): '+body+'\n')
                result=subprocess.run([sys.executable,'-I','-S','-B','-c',code],capture_output=True)
                self.assertEqual(result.returncode==0,ok)
                self.assertEqual(result.stdout,b'CLB192_SUITE_OK\n' if ok else b'')
                self.assertNotIn(b'SYNTHETIC_CANARY',result.stdout+result.stderr)

    def test_outer_supervisor_interruption_cleanup_and_cleanup_failure(self):
        import types
        launcher=runpy.run_path(str(HERE/'run-handoff.py'))
        owner='a'*32; calls=[]
        def interrupted(*args,**kwargs): raise KeyboardInterrupt()
        op=types.SimpleNamespace(D=types.SimpleNamespace(capture_result=interrupted))
        def control(argv):
            calls.append(argv)
            if argv[1]=='ps': return b'c'*12 if len(calls)==1 else b''
            if argv[1]=='inspect': return json.dumps([dict(Type='volume',Name='d'*64)]).encode()
            return b''
        with self.assertRaises(KeyboardInterrupt):
            m['supervised_suite'](op,launcher,control,'',{},owner)
        self.assertIn(['docker','rm','-f','-v','c'*12],calls)
        self.assertEqual(calls[-1],['docker','volume','ls','-q','--filter','name=^'+'d'*64+'$'])
        calls.clear()
        def failed(argv):
            if argv[1]=='rm': raise OSError('cleanup unavailable')
            return control(argv)
        with self.assertRaises(OSError):m['supervised_suite'](op,launcher,failed,'',{},owner)

    def test_no_native_override_or_output_without_real_prerequisites(self):
        with tempfile.TemporaryDirectory() as td:
            output=Path(td)/'must-not-exist'
            result=subprocess.run([sys.executable,'-I','-S','-B',str(HERE/'native-handoff-ci.py'),
                '--prepared',td,'--output',str(output)],capture_output=True,env={'PATH':os.environ['PATH']})
            self.assertEqual(result.returncode,1)
            self.assertEqual(result.stdout,b'CLB192_NATIVE_PREFLIGHT_REFUSED\n')
            self.assertFalse(output.exists())


if __name__=='__main__':unittest.main(verbosity=2)
