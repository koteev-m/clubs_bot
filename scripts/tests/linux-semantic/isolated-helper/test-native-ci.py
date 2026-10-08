#!/usr/bin/env python3
"""Portable negative contract tests; never attest synthetic identity as native."""
import copy
import hashlib
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest

HERE=Path(__file__).resolve().parent
m=runpy.run_path(str(HERE/'native-ci.py'))
launcher=runpy.run_path(str(HERE/'run-local.py'))


def evidence():
    return dict(format=1,verdict='PASS',sha='a'*40,run_id='123',attempt='1',image='sha256:'+'b'*64,
        manifest_sha256=m['MANIFEST'],helper_sha256=m['HELPER'],steps={n:'PASS' for n in m['STEPS']})


class NativeControls(unittest.TestCase):
    def test_original_helper_bytes_and_source_pins(self):
        self.assertEqual(hashlib.sha256((HERE/'helper.py').read_bytes()).hexdigest(),m['HELPER'])
        runpy.run_path(str(HERE/'helper.py'))['sources'](m['ROOT']/'scripts/deploy')

    def test_native_emulation_and_identity_fail_closed(self):
        env=dict(GITHUB_SHA='a'*40,GITHUB_RUN_ID='12',GITHUB_RUN_ATTEMPT='1',RUNNER_ARCH='X64',GITHUB_EVENT_NAME='workflow_dispatch')
        m['native_guard']('Linux','x86_64',env,'')
        for system,machine,maps in [('Darwin','arm64',''),('Linux','aarch64',''),('Linux','x86_64','/run/rosetta/rosetta'),('Linux','x86_64','/usr/bin/qemu-x86_64')]:
            with self.assertRaises(ValueError):m['native_guard'](system,machine,env,maps)
        for key in env:
            bad=dict(env);bad[key]='invalid'
            with self.assertRaises(ValueError):m['native_guard']('Linux','x86_64',bad,'')

    def test_worker_keeps_isolation_and_merges_stderr(self):
        for suite in (False,True):
            argv=m['worker_argv'](launcher,'synthetic',Path('/disposable'),'sha256:'+'a'*64,'CODE',suite)
            for value in ('--log-driver=none','--pull=never','--read-only','--network=none','--user=1000:1000','--cap-drop=ALL','--security-opt=no-new-privileges'):
                self.assertIn(value,argv)
            self.assertIn('exec "$@" 2>&1',argv)
            self.assertEqual(argv[-6:],['/usr/bin/python3.12','-I','-S','-B','-c','CODE'])
            self.assertEqual([argv[i+1] for i,x in enumerate(argv) if x=='--mount'],['type=bind,src=/disposable,dst=/source,readonly'])
            for forbidden in ('--privileged','unconfined','SYS_PTRACE','docker.sock','HOME='):
                self.assertNotIn(forbidden,' '.join(argv))

    def test_evidence_exact_schema_types_bounds_and_no_canary(self):
        good=evidence();m['validate_evidence'](good)
        cases=[]
        for key in good:
            bad=copy.deepcopy(good);del bad[key];cases.append(bad)
        for key,value in [('format',True),('verdict','CANARY'),('image','tag'),('sha','bad'),('manifest_sha256','0'*64),('helper_sha256','0'*64),('trace','CANARY'),('run_id','x'*5000)]:
            bad=copy.deepcopy(good);bad[key]=value;cases.append(bad)
        for state in ('NOT_RUN','FAIL','CANARY',True):
            bad=copy.deepcopy(good);bad['steps']['context']=state;cases.append(bad)
        for bad in cases:
            with self.assertRaises((ValueError,TypeError)):m['validate_evidence'](bad)

    def test_checker_rejects_extra_symlink_duplicate_and_large_records(self):
        checker=runpy.run_path(str(m['PREP']/'check-artifacts.py'))
        with tempfile.TemporaryDirectory() as td:
            root=Path(td).resolve();base=root/'clb191-isolated-helper';base.mkdir();file=base/'result.json'
            file.write_text(json.dumps(evidence()));self.assertEqual(checker['check'](root)['checked_files'],1)
            raw=file.read_text()
            for bad in (raw.replace('"format": 1','"format": 1, "format": 1'),raw+' '*5000):
                file.write_text(bad)
                with self.assertRaises(ValueError):checker['check'](root)
            file.write_text(raw);(base/'trace.log').write_text('CANARY')
            with self.assertRaises(ValueError):checker['check'](root)
            (base/'trace.log').unlink();file.unlink();file.symlink_to(root/'absent')
            with self.assertRaises(ValueError):checker['check'](root)

    def test_suite_sink_bounds_and_unexpected_output_refusal(self):
        scope={};exec(m['SUITE'].split("root=")[0],scope)
        sink=scope['Sink']();sink.write('x'*65536)
        self.assertEqual(sink.getvalue(),'')
        with self.assertRaises(ValueError):sink.write('x')
        with tempfile.TemporaryDirectory() as td:
            file=Path(td)/'synthetic.py'
            code=m['SUITE'].replace("'/source/scripts/tests/'",repr(td+'/')).replace('FILE',repr(file.name)).replace('COUNT','1')
            # Portable output-policy unit check only; filesystem/native checks run in CI.
            code=code.split('assert not list')[0]
            for output,expected in [('pass',0),("print('SYNTHETIC_CANARY')",1),
                                     ("print('SYNTHETIC_CANARY',file=sys.stderr)",1)]:
                file.write_text('import unittest,sys\nclass Case(unittest.TestCase):\n def test_one(self): '+output+'\n')
                result=subprocess.run([sys.executable,'-c',code],capture_output=True)
                self.assertEqual(result.returncode,expected)
                self.assertNotIn(b'SYNTHETIC_CANARY',result.stdout+result.stderr)

    def test_preparation_rejects_foreign_identity_and_runtime(self):
        prep=runpy.run_path(str(m['PREP']/'prepare-native-inputs.py'))
        env=dict(GITHUB_SHA='a'*40,GITHUB_RUN_ID='12',GITHUB_RUN_ATTEMPT='1',RUNNER_ARCH='X64',GITHUB_EVENT_NAME='workflow_dispatch')
        status=dict(verdict='EXACT_NATIVE_INPUTS_PREPARED',source_pins_sha256=prep['SOURCE_PINS'],
            rootfs_sha256=prep['ROOTFS'],candidate_manifest_sha256=prep['MANIFEST'],run_identity=env,
            verified_reference=dict(input_lock_sha256=prep['INPUTS'],manifest_sha256=m['MANIFEST'],
                packages_sha256=hashlib.sha256((m['PREP']/'reference/amd64-packages.txt').read_bytes()).hexdigest()),reference_image='sha256:'+'b'*64)
        self.assertEqual(m['preparation'](status,prep,env),status['reference_image'])
        for key in status:
            bad=copy.deepcopy(status);bad[key]={} if isinstance(bad[key],dict) else 'wrong'
            with self.assertRaises((ValueError,TypeError)):m['preparation'](bad,prep,env)

    def test_no_native_override_before_writes(self):
        with tempfile.TemporaryDirectory() as td:
            output=Path(td)/'output'
            result=subprocess.run([sys.executable,'-I','-S','-B',str(HERE/'native-ci.py'),
                '--prepared',td,'--output',str(output)],capture_output=True,env={'PATH':os.environ['PATH']})
            self.assertEqual(result.returncode,1);self.assertFalse(output.exists())
            self.assertEqual(result.stdout,b'CLB191_NATIVE_PREFLIGHT_REFUSED\n')


if __name__=='__main__':unittest.main(verbosity=2)
