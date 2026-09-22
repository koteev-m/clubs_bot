#!/usr/bin/env python3
"""Finite closure reader: real synthetic FDs and authenticated Linux bootstrap."""
import base64
import copy
import errno
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import stat
import struct
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/tests'))
import test_stage_runtime_inventory as existing
from runtime_feasibility_fixtures import (FixtureOS, make_fixture, captured_fixture_source,
    run_synthetic, checked_response)
runner=existing.load('stage-runtime-feasibility.py')
I=existing.load('stage-runtime-inventory-operation.py')
operation=types.ModuleType('feasibility')
operation.I=I
operation.REFERENCE=json.loads((ROOT/runner.REFERENCE_PATH).read_bytes())
operation.INPUTS=json.loads((ROOT/runner.INPUTS_PATH).read_bytes())
operation.PACKAGE_TEXT=(ROOT/runner.PACKAGES_PATH).read_text()
exec(compile((ROOT/runner.REMOTE_PATH).read_bytes(),str(ROOT/runner.REMOTE_PATH),'exec'),operation.__dict__)


class SourceTest(existing.SourceTest):
    def setUp(self):
        self.swap=patch.multiple(existing,runner=runner,operation=operation)
        self.swap.start(); self.addCleanup(self.swap.stop)
        super().setUp()

    def test_source_pins_primitives_inputs_and_old_contract_preserved(self):
        for path,digest in runner.SOURCE_PINS.items():
            self.assertEqual(hashlib.sha256((ROOT/path).read_bytes()).hexdigest(),digest)
        old=existing.load('stage-runtime-inventory.py')
        self.assertNotEqual(old.CONFIRMATION,runner.CONFIRMATION)
        marker='# BEGIN EXACT CORRECTED CAPTURE PRIMITIVES'
        end='# END EXACT CORRECTED CAPTURE PRIMITIVES'
        new=(ROOT/runner.RUNNER_PATH).read_text().split(marker,1)[1].split(end,1)[0]
        prior=(ROOT/'scripts/deploy/stage-runtime-inventory.py').read_text().split(marker,1)[1].split(end,1)[0]
        self.assertEqual(new,prior)
        self.assertEqual(runner.ssh_argv(dict(SSH_USER='fixture',SSH_HOST='fixture.invalid',SSH_PORT='22'),'fd')[:-1],
                         old.ssh_argv(dict(SSH_USER='fixture',SSH_HOST='fixture.invalid',SSH_PORT='22'),'fd')[:-1])
        self.assertNotIn('stage-compose-env-semantic-operation.py',runner.REMOTE_SOURCES)
        with self.assertRaises(ValueError): runner.validate(dict(self.env,CONFIRMATION=old.CONFIRMATION))


class FixtureCase(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(dir=existing.fixture_parent())
        self.addCleanup(self.temp.cleanup); self.root=Path(self.temp.name)
        versions=dict(l.split('=',1) for l in operation.PACKAGE_TEXT.splitlines())
        self.put,self.reference=make_fixture(self.root,operation.REFERENCE,{n:v for n,v in versions.items() if n in operation.PACKAGES})
        self.os=FixtureOS(self.root)
        self.addCleanup(lambda:self.assertEqual(self.os.owned,set()))

    def collect(self, cancelled=lambda:False):
        with patch.object(operation,'os',self.os),patch.object(I,'os',self.os),patch.object(operation,'REFERENCE',self.reference):
            raw=operation.collect(cancelled)
            self.assertNotIn(b'PRIVATE',raw); self.assertNotIn(str(self.root).encode(),raw)
            value=json.loads(raw[len(operation.PREFIX):]); code=1 if value['result']=='unavailable' else 0
            operation.parse_body(raw,code)
        return value,code

    def test_full_allowlist_and_useful_exact_mismatch_missing_unknown(self):
        value,code=self.collect(); self.assertEqual(code,0)
        self.assertEqual(value['comparison']['files'],dict(match=191,mismatch=0,missing=0,unknown=0))
        self.assertTrue(all(r['status']=='match' for r in value['aliases'].values()))
        self.assertEqual(len(value['docker']),6)
        self.assertEqual(len(value['packages']),37)
        self.assertEqual(value['packages']['ruby']['status'],'observed')
        self.assertEqual(value['comparison']['runtime_acceptance'],'not_proven')
        self.put('/usr/bin/python3.12',b'different public fixture')
        (self.root/'usr/bin/ruby3.2').unlink()
        (self.root/'etc/ld.so.cache').chmod(0o666)
        value,code=self.collect(); self.assertEqual(code,0)
        self.assertEqual(value['comparison']['files'],dict(match=188,mismatch=1,missing=1,unknown=1))
        self.assertIn('/usr/bin/python3.12',value['comparison']['decisions']['python'])
        self.assertIn('/etc/ld.so.cache',value['comparison']['decisions']['loader_cache'])
        self.assertEqual(value['completeness'],'partial')

    def test_no_tool_execution_and_no_forbidden_reads(self):
        # Canary content would fail if executed. The OS surrogate rejects writes,
        # arbitrary absolute opens and environment lookup during actual collection.
        for path in ('/usr/bin/docker','/usr/local/bin/docker-compose'):
            self.put(path,b'#!/bin/sh\nprintf PRIVATE\nexit 99\n',0o755)
        with patch('subprocess.Popen',side_effect=AssertionError('PRIVATE executed')),patch('os.system',side_effect=AssertionError('PRIVATE shell')):
            value,code=self.collect()
        self.assertEqual(code,0)
        standalone=value['files']['/usr/local/bin/docker-compose']
        self.assertEqual(standalone['status'],'mismatch')
        self.assertEqual(standalone['sha256'],hashlib.sha256(b'#!/bin/sh\nprintf PRIVATE\nexit 99\n').hexdigest())
        self.assertEqual(value['docker']['/usr/bin/docker']['status'],'observed')
        self.assertFalse(any(str(p).startswith(('/opt/','/home/','/root/','/var/run/')) for p in self.os.opens))
        self.assertFalse((self.root/'canary').exists())

    def test_symlink_escape_wrong_type_and_unsafe_parent(self):
        target=self.root/'usr/bin/python3.12'; target.unlink()
        secret=self.put('/home/private',b'PRIVATE secret must not be hashed',0o600)
        target.symlink_to('/home/private')
        value,code=self.collect(); self.assertEqual(code,0)
        self.assertEqual(value['files']['/usr/bin/python3.12']['reason'],'unsafe')
        self.assertNotIn(hashlib.sha256(secret.read_bytes()).hexdigest(),json.dumps(value))
        target.unlink(); os.mkfifo(target)
        self.assertEqual(self.collect()[0]['files']['/usr/bin/python3.12']['reason'],'unsafe')
        target.unlink(); target.mkdir()
        self.assertEqual(self.collect()[0]['files']['/usr/bin/python3.12']['reason'],'unsafe')
        (self.root/'usr/lib/python3.12').chmod(0o777)
        self.assertEqual(self.collect()[0]['files']['/usr/lib/python3.12/ast.py']['reason'],'unsafe')

    def test_wrong_owner_alias_escape_and_missing_parents(self):
        self.os.bad_owner=True
        value,code=self.collect(); self.assertEqual(code,0)
        self.assertTrue(all(r['reason']=='unsafe' for r in value['files'].values()))
        self.os.bad_owner=False
        link=self.root/'usr/bin/ruby'; link.unlink(); link.symlink_to('/home/PRIVATE')
        value,code=self.collect(); self.assertEqual(code,0)
        self.assertEqual(value['aliases']['/usr/bin/ruby'],dict(status='mismatch',reason='none',target='outside_allowlist'))
        self.assertNotIn('/home/',json.dumps(value))

    def test_replacement_in_place_drift_and_missing_becomes_present(self):
        def replace():
            f=self.root/'etc/ld.so.cache'; f.unlink(); self.put('/etc/ld.so.cache',b'new')
        self.os.after_read=replace
        self.assertEqual(self.collect(),({'result':'unavailable','reason':'identity'},1))
        self.os.after_read=lambda:self.put('/etc/ld.so.cache',b'changed in-place')
        self.assertEqual(self.collect(),({'result':'unavailable','reason':'identity'},1))
        # Negative observations also participate in the last recheck.
        original=operation.SystemFiles.recheck
        fired=[False]
        def mutate(files):
            if files.missing and not fired[0]:
                fired[0]=True; self.put('/usr/bin/docker',b'appeared')
            return original(files)
        with patch.object(operation.SystemFiles,'recheck',mutate):
            self.assertEqual(self.collect()[0]['result'],'unavailable')

    def test_io_permissions_descriptor_cleanup_cancellation_and_bounds(self):
        original=self.os.read
        self.os.read=lambda *a:(_ for _ in ()).throw(OSError(errno.EIO,'PRIVATE IO'))
        self.assertEqual(self.collect(),({'result':'unavailable','reason':'io'},1))
        self.os.read=original
        self.os.close_failure=True
        self.assertEqual(self.collect(),({'result':'unavailable','reason':'cleanup'},1)); self.os.close_failure=False
        self.assertEqual(self.collect(lambda:True),({'result':'unavailable','reason':'interrupted'},1))
        with patch.object(operation,'MAX_FDS',4): self.assertEqual(self.collect()[0]['reason'],'bounds')
        with patch.object(operation,'MAX_BYTES',5): self.assertEqual(self.collect()[0]['reason'],'bounds')
        with patch.object(operation,'MAX_FILE',2): self.assertEqual(self.collect()[0]['reason'],'bounds')
        oldopen=self.os.open
        def deny(path,*args,**kwargs):
            if path=='python3.12': raise PermissionError(errno.EACCES,'PRIVATE denied')
            return oldopen(path,*args,**kwargs)
        self.os.open=deny
        value,code=self.collect(); self.assertEqual(code,0); self.assertEqual(value['files']['/usr/bin/python3.12']['reason'],'permission')

    def test_package_hold_malformed_unknown_and_canaries(self):
        for status,hold in [('hold',True),('install',False)]:
            self.put('/var/lib/dpkg/status',('Package: ruby\nStatus: '+status+' ok installed\nVersion: 1:3.2~ubuntu1\nArchitecture: amd64\nDescription: PRIVATE\n').encode())
            value,_=self.collect(); self.assertIs(value['packages']['ruby']['hold'],hold); self.assertEqual(value['packages']['ruby']['selection'],status)
        for raw,reason in [(b'Package: ruby\nPackage: ruby\n','malformed'),(b'Package: ruby\nStatus: install ok installed\nVersion: PRIVATE\nArchitecture: amd64\n','unsupported'),(b'\xff','malformed')]:
            self.put('/var/lib/dpkg/status',raw); value,code=self.collect(); self.assertEqual(code,0)
            self.assertEqual(value['packages']['ruby']['reason'],reason)
        (self.root/'var/lib/dpkg/status').unlink()
        self.assertEqual(self.collect()[0]['packages']['ruby']['status'],'unknown')
        self.put('/var/lib/dpkg/status',b'x'*65537)
        self.assertEqual(self.collect()[0]['reason'],'bounds')
        self.put('/var/lib/dpkg/status',('Package: unrelated\nDescription: '+'\u00e9'*32768).encode())
        self.assertEqual(self.collect()[0]['reason'],'bounds')

    def test_malformed_package_identity_never_becomes_missing(self):
        suffix=b'\nStatus: install ok installed\nVersion: 1:3.2~ubuntu1\nArchitecture: amd64\n'
        for header in (b'Package:ruby',b'Package: ruby ',b'Package: PRIVATE',b'Package: ',b'Package ruby',b'Description: no package header'):
            with self.subTest(header=header):
                self.put('/var/lib/dpkg/status',header+suffix)
                value,code=self.collect(); self.assertEqual(code,0)
                self.assertTrue(all(row['status']=='unknown' and row['reason']=='malformed' for row in value['packages'].values()))
                self.assertEqual(value['completeness'],'partial')
        for record in (b'Package: ruby\n continuation'+suffix,b'Package: ruby'+suffix+b' continuation\n'):
            self.put('/var/lib/dpkg/status',record)
            self.assertEqual(self.collect()[0]['packages']['ruby']['reason'],'malformed')
        self.put('/var/lib/dpkg/status',b'Package: unrelated-package'+suffix+b'Description: PRIVATE ignored\n continuation ignored\n')
        self.assertEqual(self.collect()[0]['packages']['ruby']['status'],'missing')

    def test_own_maps_unknown_paths_never_read_or_output(self):
        self.put('/proc/self/maps',b'0010-0020 r-xp 0000 00:01 1 /home/PRIVATE-secret\n')
        value,code=self.collect(); self.assertEqual(code,0)
        self.assertEqual(value['closure']['maps'],dict(known=[],outside_allowlist=1,status='observed',reason='none'))
        self.assertNotIn('/home/',json.dumps(value)); self.assertNotIn('/home/PRIVATE-secret',self.os.opens)
        self.put('/proc/self/maps',b'x'*65537)
        self.assertEqual(self.collect()[0]['reason'],'bounds')

    def test_system_artifact_size_boundaries_and_hosted_plugin(self):
        path='/usr/libexec/docker/cli-plugins/docker-compose'
        target=self.put(path,b'',0o755)
        for size in (operation.MAX_FILE-1,operation.MAX_FILE,operation.MAX_FILE+1,75108694):
            with self.subTest(size=size):
                with target.open('wb') as stream: stream.truncate(size)
                original=self.os.read
                reads=[0]
                def guarded_read(fd,count):
                    if os.fstat(fd).st_ino==target.stat().st_ino:
                        reads[0]+=1
                        self.assertLessEqual(size,operation.MAX_FILE,'oversized artifact was read')
                    return original(fd,count)
                with patch.object(self.os,'read',guarded_read),patch.object(operation,'os',self.os),patch.object(I,'os',self.os):
                    files=operation.SystemFiles(lambda:False)
                    try:
                        if size>operation.MAX_FILE:
                            with self.assertRaises(I.Unavailable) as error: operation.artifact(files,path)
                            self.assertEqual(error.exception.args,('bounds',)); self.assertEqual(reads[0],0)
                        else:
                            row=operation.artifact(files,path)
                            self.assertEqual(row['status'],'observed'); self.assertEqual(row['size'],size)
                            self.assertGreater(reads[0],0)
                    finally: files.close()

    def test_maximum_report_bound_and_local_comparison(self):
        value,_=self.collect()
        for path,row in value['files'].items():
            row.update(status='mismatch',reason='none',sha256='f'*64,size=operation.MAX_FILE,mode='0755')
        for row in value['docker'].values():
            row.update(status='observed',reason='none',sha256='f'*64,size=operation.MAX_FILE,mode='0755')
        for row in value['packages'].values():
            row.update(status='observed',reason='none',version='1'*64,architecture='amd64',hold=True,selection='hold')
        for row in (value['closure']['maps'],value['closure']['modules']):
            row['known']=sorted(operation.REFERENCE['files']); row['outside_allowlist']=1024
        value['comparison']=operation.comparison(value)
        value['completeness']='partial'
        raw=operation.canonical(value)
        self.assertLessEqual(len(raw),operation.BODY_LIMIT)
        self.assertLessEqual(len(raw)+110,runner.FRAME_LIMIT)
        operation.parse_body(raw,0)
        self.assertEqual(value['comparison']['files']['mismatch'],191)
        self.assertIn('/etc/ld.so.cache',value['comparison']['decisions']['loader_cache'])

    def test_authenticated_strict_schema_tamper_replay_truncation(self):
        value,code=self.collect(); nonce=bytes(range(32))
        def frame(raw):return b'clb91-runtime-feasibility-auth:v=1 tag='+hmac.new(nonce,raw,hashlib.sha256).hexdigest().encode()+b' '+raw
        raw=operation.canonical(value)
        with patch.object(operation,'REFERENCE',self.reference):
            self.assertEqual(runner.parse_result(frame(raw),0,nonce,operation)[1],0)
            for data,status,key in [(frame(raw)[:-1],0,nonce),(frame(raw),1,nonce),(frame(raw),0,b'z'*32),(frame(raw)*2,0,nonce),(b'PRIVATE'+frame(raw),0,nonce)]:
                with self.assertRaises((ValueError,I.Unavailable)):runner.parse_result(data,status,key,operation)
            for mutate in [lambda v:v.update(extra='PRIVATE'),lambda v:v['comparison']['files'].update(match=True),lambda v:v['bootstrap'].update(bits=True),lambda v:v['files']['/etc/ld.so.cache'].update(sha256='PRIVATE'),lambda v:v['closure']['maps']['known'].append('/home/PRIVATE')]:
                v=copy.deepcopy(value); mutate(v)
                with self.assertRaises((ValueError,I.Unavailable,TypeError)):runner.parse_result(frame(operation.canonical(v)),0,nonce,operation)
            for data in (b'x'*(operation.BODY_LIMIT+1),raw.replace(b'{',b'{"result":"observed",',1)):
                with self.assertRaises((ValueError,I.Unavailable)):runner.parse_result(frame(data),0,nonce,operation)


class SubprocessDiagnosticTest(unittest.TestCase):
    def test_eof_before_exit_preserves_status_and_retains_group_identity(self):
        import signal
        real_waitid, real_killpg = os.waitid, os.killpg
        for expected in (0, 1, -signal.SIGTERM):
            with self.subTest(expected=expected):
                released, cleanup = [], []
                def observe(kind, pid, flags):
                    # Called only after all pipe EOFs. The child cannot exit
                    # until this observer explicitly releases its blocked signal.
                    if not released:
                        os.kill(pid, signal.SIGUSR1); released.append(pid)
                    return real_waitid(kind, pid, flags)
                def kill(group, sig):
                    if sig == signal.SIGKILL:
                        info=real_waitid(os.P_PID,group,os.WEXITED|os.WNOHANG|os.WNOWAIT)
                        cleanup.append((group,info.si_pid if info else None))
                    return real_killpg(group,sig)
                end='os._exit('+str(expected)+')' if expected>=0 else 'os.kill(os.getpid(),signal.SIGTERM)'
                program=('import os,signal; signal.pthread_sigmask(signal.SIG_BLOCK,{signal.SIGUSR1}); '
                         'os.write(1,b"complete\\n"); os.close(1); os.close(2); '
                         'signal.sigwait({signal.SIGUSR1}); '+end)
                with patch('os.waitid',observe),patch('os.killpg',kill):
                    result=run_synthetic([sys.executable,'-I','-S','-B','-c',program],b'',1024,timeout=3)
                self.assertEqual((expected,b'complete\n',b''),(result.returncode,result.stdout,result.stderr))
                self.assertEqual([(released[0],released[0])],cleanup)
                with self.assertRaises(ChildProcessError): os.waitpid(released[0],os.WNOHANG)

    def test_eof_without_exit_times_out_and_reaps_leader(self):
        import signal
        import time
        children=[]; real_popen=subprocess.Popen
        def spawn(*args,**kwargs):
            child=real_popen(*args,**kwargs); children.append(child); return child
        program='import os,signal; os.write(1,b"ready"); os.close(1); os.close(2); signal.pause()'
        started=time.monotonic()
        with patch('subprocess.Popen',spawn),self.assertRaises(AssertionError) as error:
            run_synthetic([sys.executable,'-I','-S','-B','-c',program],b'',1024,timeout=0.5)
        self.assertIn('stage=timeout',str(error.exception)); self.assertIn('stdout_bytes=5',str(error.exception))
        self.assertEqual(-signal.SIGKILL,children[0].returncode)
        self.assertLess(time.monotonic()-started,3)
        with self.assertRaises(ChildProcessError): os.waitpid(children[0].pid,os.WNOHANG)

    def test_exited_leader_cleanup_terminates_owned_descendant(self):
        import select
        import signal
        read_fd,write_fd=os.pipe()
        real_popen,real_killpg=subprocess.Popen,os.killpg
        children,cleanup=[],[]
        def spawn(*args,**kwargs):
            kwargs['pass_fds']=(write_fd,)
            child=real_popen(*args,**kwargs); children.append(child); return child
        def kill(group,sig):
            if sig==signal.SIGKILL:
                info=os.waitid(os.P_PID,group,os.WEXITED|os.WNOHANG|os.WNOWAIT)
                cleanup.append((group,info.si_pid if info else None,children[0].returncode))
            return real_killpg(group,sig)
        program="import os,signal,sys\nreport=int(sys.argv[1]); r,w=os.pipe()\nif os.fork()==0:\n os.close(r); os.close(0); os.close(1); os.close(2)\n signal.alarm(2)  # Independent finite lifetime even if the assertion fails.\n os.write(report,b'R'); os.write(w,b'r'); os.close(w)\n signal.pause(); os._exit(1)\nos.close(w); assert os.read(r,1)==b'r'; os.close(r); os.close(report)\nos.close(1); os.close(2); os._exit(0)\n"
        try:
            with patch('subprocess.Popen',spawn),patch('os.killpg',kill):
                result=run_synthetic([sys.executable,'-I','-S','-B','-c',program,str(write_fd)],b'',1024,timeout=1)
            os.close(write_fd); write_fd=None
            self.assertEqual(0,result.returncode)
            self.assertEqual([(children[0].pid,children[0].pid,None)],cleanup)
            self.assertTrue(select.select([read_fd],[],[],1)[0]); self.assertEqual(b'R',os.read(read_fd,1))
            self.assertTrue(select.select([read_fd],[],[],1)[0]); self.assertEqual(b'',os.read(read_fd,1))
            # Only the descendant retained report: EOF proves its termination.
            with self.assertRaises(ChildProcessError): os.waitpid(children[0].pid,os.WNOHANG)
            self.assertTrue(all(s.closed for s in (children[0].stdin,children[0].stdout,children[0].stderr)))
        finally:
            if write_fd is not None: os.close(write_fd)
            os.close(read_fd)

    def test_cleanup_failure_preserves_primary_bounds_and_redaction(self):
        original=os.killpg
        def failed_cleanup(pid,sig):
            try: original(pid,sig)
            except (ProcessLookupError,PermissionError): pass
            raise OSError(errno.EIO,'PRIVATE cleanup detail')
        with patch('os.killpg',failed_cleanup),self.assertRaises(AssertionError) as error:
            run_synthetic([sys.executable,'-I','-S','-B','-c',"import sys; sys.stdout.write('X'*131201)"],b'',runner.FRAME_LIMIT)
        self.assertIn('stage=bounds',str(error.exception))
        self.assertIn('cleanup=failed',str(error.exception))
        self.assertNotIn('PRIVATE',str(error.exception))

    def test_real_process_failures_are_bounded_distinct_and_redacted(self):
        nonce=bytes(range(32))
        def execute(program):
            return run_synthetic([sys.executable,'-I','-S','-B','-c',program],b'',runner.FRAME_LIMIT)
        def checked(response):return checked_response(response,runner,operation,nonce,expected_code=0)
        cases=[('empty',"raise SystemExit(1)"),
               ('subprocess',"import os,signal; os.kill(os.getpid(),signal.SIGTERM)"),
               ('subprocess',"import sys; sys.stderr.write('PRIVATE credential'); raise SystemExit(2)"),
               ('framing',"print('PRIVATE credential')")]
        for stage,program in cases:
            with self.subTest(stage=stage):
                with self.assertRaises(AssertionError) as error: checked(execute(program))
                self.assertIn('stage='+stage,str(error.exception)); self.assertNotIn('PRIVATE',str(error.exception))
                self.assertLess(len(str(error.exception)),256)
        for stream,count in [('stdout',runner.FRAME_LIMIT+1),('stderr',4097)]:
            with self.assertRaises(AssertionError) as error:
                execute("import sys; sys."+stream+".write('X'*"+str(count)+")")
            self.assertIn('stage=bounds',str(error.exception))
        with self.assertRaises(AssertionError) as error:
            run_synthetic(['/missing-synthetic-PRIVATE'],b'',runner.FRAME_LIMIT)
        self.assertIn('stage=spawn',str(error.exception)); self.assertNotIn('PRIVATE',str(error.exception))
        with self.assertRaises(AssertionError) as error:
            run_synthetic([sys.executable,'-I','-S','-B','-c','while True: pass'],b'',runner.FRAME_LIMIT,timeout=0.1)
        self.assertIn('stage=timeout',str(error.exception))

    def test_authentication_schema_and_authenticated_refusal_remain_failures(self):
        nonce=bytes(range(32))
        def frame(body,key=nonce):
            return b'clb91-runtime-feasibility-auth:v=1 tag='+hmac.new(key,body,hashlib.sha256).hexdigest().encode()+b' '+body
        valid=operation.refused('bounds')
        cases=[('authentication',frame(valid,b'z'*32),1),
               ('schema',frame(operation.canonical(dict(result='PRIVATE'))),1),
               ('collect',frame(valid),1)]
        for stage,raw,code in cases:
            # Actual child pipes, then the same authenticating production parser.
            program='import os; os.write(1,'+repr(raw)+'); raise SystemExit('+str(code)+')'
            response=run_synthetic([sys.executable,'-I','-S','-B','-c',program],b'',runner.FRAME_LIMIT)
            with self.assertRaises(AssertionError) as error:
                checked_response(response,runner,operation,nonce,expected_code=0)
            text=str(error.exception); self.assertIn('stage='+stage,text); self.assertNotIn('PRIVATE',text)
            if stage=='collect':self.assertIn('reason=bounds',text); self.assertIn('authenticated=yes',text)
        response=subprocess.CompletedProcess([],1,frame(valid),b'')
        self.assertEqual(checked_response(response,runner,operation,nonce,expected_code=1)[1],1)


class WorkflowTest(unittest.TestCase):
    def test_existing_selfcheck_includes_new_suite_exactly_once(self):
        selfcheck=(ROOT/'scripts/selfcheck-quality-gates.sh').read_text()
        for name in ('test_stage_runtime_feasibility.py','test_stage_runtime_inventory.py'):
            self.assertEqual(selfcheck.count('python3 "$ROOT_DIR/scripts/tests/'+name+'"'),1)
        self.assertIn('"27"',selfcheck.split('if [ "$fixture_name" = "valid-current-alias-inventory" ]; then',1)[1].split('\n  fi',1)[0])

    def test_exact_capability_and_negative_authority_mutations(self):
        # Exercise the actual capability parser, not string-presence assertions.
        import inspect
        text=inspect.getsource(existing.ProtocolTest.test_workflow_exact_authority_and_negative_mutations)
        script=text.split("script = '''",1)[1].split("'''",1)[0].replace('StageRuntimeInventoryWorkflow','StageRuntimeFeasibilityWorkflow').replace('["inventory"]','["feasibility"]')
        for mutation in ('none','input','trigger','environment','concurrency','cancel','credentials','write','command','checkout','validation','confirmation'):
            r=subprocess.run(['ruby','-I',str(ROOT/'scripts'),'-e',script,str(ROOT),mutation],capture_output=True,timeout=10)
            self.assertEqual(r.returncode==0,mutation=='none',r.stderr)


@unittest.skipUnless(sys.platform=='linux','real Linux system interfaces required')
class LinuxTest(unittest.TestCase):
    def setUp(self):
        self.sources={Path(p).name:(ROOT/p).read_bytes() for p in runner.REMOTE_SOURCES}; self.nonce=bytes(range(32))

    def invoke(self,suffix=b'',sources=None,expected_code=None):
        data=dict(sources or self.sources); data[Path(runner.REMOTE_PATH).name]+=suffix
        bundle=json.dumps({k:base64.b64encode(v).decode() for k,v in data.items()},sort_keys=True,separators=(',',':')).encode()
        control=json.dumps(dict(principal='fixture',sha256=hashlib.sha256(bundle).hexdigest())).encode()
        payload=self.nonce+struct.pack('!I',len(control))+control+bundle
        r=run_synthetic(['/usr/bin/python3','-I','-S','-B','-c',runner.BOOTSTRAP],payload,runner.FRAME_LIMIT)
        return checked_response(r,runner,operation,self.nonce,expected_code=expected_code)

    def test_actual_linux_bootstrap_full_closure_no_tools_or_private_reads(self):
        suffix=b'''
# Only these two physical opens implement the surrogate's logical / and maps.
# Pin their pre-audit identity; no prefix-based exemption for the fixture tree.
physical_reads={}
for name,is_directory in ((os.root,True),(os.root+'/proc/self/maps',False)):
    value=os.stat(name,follow_symlinks=False)
    assert (stat.S_ISDIR(value.st_mode) if is_directory else stat.S_ISREG(value.st_mode))
    assert os.path.realpath(name)==name
    physical_reads[name]=(value.st_dev,value.st_ino,value.st_mode)
def audit(event,args):
    if event in ('subprocess.Popen','os.system','os.exec','socket.connect','os.mkdir','os.chmod','os.chown','os.remove','os.rename'):
        raise RuntimeError('PRIVATE forbidden effect')
    if event=='open':
        path,mode,flags=args
        if flags & (os.O_WRONLY|os.O_RDWR|os.O_CREAT|os.O_TRUNC|os.O_APPEND): raise RuntimeError('PRIVATE write')
        if isinstance(path,bytes): raise RuntimeError('PRIVATE byte path')
        if isinstance(path,str) and path.startswith('/'):
            if path not in physical_reads or not flags & os.O_NOFOLLOW: raise RuntimeError('PRIVATE read')
            value=os.stat(path,follow_symlinks=False)
            if os.path.realpath(path)!=path or (value.st_dev,value.st_ino,value.st_mode)!=physical_reads[path]:
                raise RuntimeError('PRIVATE substituted fixture')
original=collect
def collect(cancelled):
    sys.addaudithook(audit)
    return original(cancelled)
'''
        # Positive protocol proof uses a finite synthetic filesystem, not the
        # hosted runner's unpinned tool installation (Compose v2 can exceed 64 MiB).
        versions=dict(l.split('=',1) for l in operation.PACKAGE_TEXT.splitlines())
        with tempfile.TemporaryDirectory(dir=existing.fixture_parent()) as directory:
            make_fixture(directory,operation.REFERENCE,{n:v for n,v in versions.items() if n in operation.PACKAGES})
            suffix=captured_fixture_source(ROOT,directory)+suffix
            line,code=self.invoke(suffix,expected_code=0); self.assertEqual(code,0)
            value=json.loads(line[len(operation.PREFIX):]); self.assertEqual(len(value['files']),191)
            self.assertEqual(value['bootstrap']['os_family'],'linux')
            self.assertEqual(value['files']['/usr/bin/python3.12']['status'],'mismatch')
            self.assertEqual(value['trust'],'observed_not_approved')
            with patch.multiple(existing,runner=runner,operation=operation):
                line2,code2=existing.BootstrapTest.through_runner(self,suffix)
            self.assertEqual(code2,0); parsed=json.loads(line2[len(operation.PREFIX):])
            self.assertEqual(parsed['files'],value['files'])

    def test_positive_fixture_runner_temp_and_tmpdir_selection(self):
        # On hosted Linux this is /home/runner/work/_temp; local Linux controls
        # also run the entire selfcheck block under that synthetic mount.
        with tempfile.TemporaryDirectory(dir=existing.fixture_parent()) as parent:
            first=Path(parent)/'runner'; second=Path(parent)/'tmp'
            first.mkdir(); second.mkdir()
            for runner_temp,tmpdir,expected in ((str(first),str(second),first),('',str(second),second)):
                with self.subTest(selection='RUNNER_TEMP' if runner_temp else 'TMPDIR'):
                    with patch.dict(os.environ,dict(RUNNER_TEMP=runner_temp,TMPDIR=tmpdir)),patch.object(tempfile,'tempdir',None):
                        self.assertEqual(existing.fixture_parent(),str(expected.resolve()))
                        self.test_actual_linux_bootstrap_full_closure_no_tools_or_private_reads()

    def test_positive_fixture_audit_rejects_neighbor_and_substitution(self):
        # Fault injection uses the exact positive suffix and actual child audit.
        invoke=self.invoke
        capture=captured_fixture_source
        with tempfile.TemporaryDirectory(dir=existing.fixture_parent()) as owned:
            for case in ('neighbor','bytes_neighbor','prefix_neighbor','substituted_maps','replaced_maps','substituted_root','forbidden_effect'):
                with self.subTest(case=case):
                    roots=[]
                    def remember(repository,directory):
                        roots.append(Path(directory)); return capture(repository,directory)
                    def attempt(suffix,**kwargs):
                        extra=("\nreal_os=base.os\ncase="+repr(case)+"\n"+'''
neighbor=os.root+'-neighbor'
with open(neighbor,'wb') as stream: stream.write(b'PRIVATE-neighbor-canary')
if case=='substituted_maps':
    real_os.rename(os.root+'/proc/self/maps',os.root+'/proc/self/maps-old')
    real_os.symlink(neighbor,os.root+'/proc/self/maps')
if case=='replaced_maps':
    real_os.rename(os.root+'/proc/self/maps',os.root+'/proc/self/maps-old')
    with open(os.root+'/proc/self/maps','wb') as stream: stream.write(b'PRIVATE replacement')
if case=='substituted_root':
    real_os.rename(os.root,os.root+'-old')
    real_os.symlink(os.root+'-old',os.root)
original_collect=collect
def collect(cancelled):
    sys.addaudithook(audit)
    if case in ('neighbor','bytes_neighbor','prefix_neighbor','forbidden_effect'):
        try:
            if case=='forbidden_effect':
                real_os.mkdir(os.root+'/forbidden-directory')
            else:
                target=neighbor if case in ('neighbor','bytes_neighbor') else os.root+'/../'+os.root.rsplit('/',1)[1]+'-neighbor'
                if case=='bytes_neighbor': target=target.encode()
                fd=real_os.open(target,real_os.O_RDONLY|real_os.O_NOFOLLOW)
                real_os.close(fd)
        except RuntimeError:
            return refused('io')
        return refused('bounds')  # Audit bypass must NOT look like expected refusal.
    return original_collect(cancelled)
''').encode()
                        try:
                            result=invoke(suffix+extra,expected_code=1)
                            self.assertEqual(result,(operation.refused('io').decode().strip(),1))
                            self.assertNotIn('PRIVATE',result[0])
                        finally:
                            # Outside the child audit, restore only this owned
                            # synthetic root so TemporaryDirectory can remove it.
                            root=roots[-1]
                            if case=='substituted_root' and root.is_symlink():
                                root.unlink(); root.with_name(root.name+'-old').rename(root)
                        raise RuntimeError('synthetic verified refusal')
                    with patch.object(existing,'fixture_parent',lambda:owned), \
                         patch(__name__+'.captured_fixture_source',remember), \
                         patch.object(self,'invoke',attempt),self.assertRaisesRegex(RuntimeError,'synthetic verified refusal'):
                        self.test_actual_linux_bootstrap_full_closure_no_tools_or_private_reads()

    def test_hosted_plugin_size_refusal_and_positive_fixture_isolation(self):
        versions=dict(l.split('=',1) for l in operation.PACKAGE_TEXT.splitlines())
        with tempfile.TemporaryDirectory(dir=existing.fixture_parent()) as directory:
            make_fixture(directory,operation.REFERENCE,{n:v for n,v in versions.items() if n in operation.PACKAGES})
            target=Path(directory)/'usr/libexec/docker/cli-plugins/docker-compose'
            target.parent.mkdir(parents=True,exist_ok=True)
            with target.open('wb') as stream: stream.truncate(75108694)
            target.chmod(0o755)
            ambient=captured_fixture_source(ROOT,directory)
            self.assertEqual(self.invoke(ambient),(operation.refused('bounds').decode().strip(),1))
            with self.assertRaises(AssertionError) as error:self.invoke(ambient,expected_code=0)
            self.assertIn('stage=collect',str(error.exception)); self.assertIn('reason=bounds',str(error.exception))
            with patch.multiple(existing,runner=runner,operation=operation):
                self.assertEqual(existing.BootstrapTest.through_runner(self,ambient),(operation.refused('bounds').decode().strip(),1))
            invoke=self.invoke
            with patch.object(self,'invoke',lambda suffix=b'',**kw:invoke(ambient+suffix,**kw)):
                # This exact test used to assert code 0 on the oversized host;
                # now its own finite positive fixture makes it independent.
                self.test_actual_linux_bootstrap_full_closure_no_tools_or_private_reads()

    def test_synthetic_fd_failures_through_bootstrap_and_real_consumer(self):
        versions=dict(l.split('=',1) for l in operation.PACKAGE_TEXT.splitlines())
        with tempfile.TemporaryDirectory(dir=existing.fixture_parent()) as directory:
            make_fixture(directory,operation.REFERENCE,{n:v for n,v in versions.items() if n in operation.PACKAGES})
            base=(ROOT/'scripts/tests/runtime_inventory_fixtures.py').read_text()
            fixture=(ROOT/'scripts/tests/runtime_feasibility_fixtures.py').read_text().replace('from runtime_inventory_fixtures import FixtureOS as BaseOS','BaseOS = base.FixtureOS')
            suffix=('\nbase=__import__("types").ModuleType("fixture_base")\nexec('+repr(base)+',base.__dict__)\n'
                'fixture=__import__("types").ModuleType("fixture")\nfixture.base=base\nexec('+repr(fixture)+',fixture.__dict__)\n'
                'os=fixture.FixtureOS('+repr(directory)+')\nI.os=os\n').encode()
            line,code=self.invoke(suffix); self.assertEqual(code,0)
            self.assertEqual(json.loads(line[len(operation.PREFIX):])['comparison']['files']['mismatch'],191)
            for extra,reason in [(b'os.close_failure=True\n','cleanup'),
                (b"os.read=lambda *a: (_ for _ in ()).throw(OSError(5,'PRIVATE'))\n",'io'),
                (b"os.read=lambda *a: (_ for _ in ()).throw(InterruptedError('PRIVATE'))\n",'interrupted')]:
                with self.subTest(reason=reason):
                    expected=(operation.refused(reason).decode().strip(),1)
                    self.assertEqual(self.invoke(suffix+extra),expected)
                    with patch.multiple(existing,runner=runner,operation=operation):
                        self.assertEqual(existing.BootstrapTest.through_runner(self,suffix+extra),expected)

    def test_real_bootstrap_source_failure_cancellation_cleanup(self):
        broken=dict(self.sources); del broken[Path(runner.INVENTORY_PATH).name]
        with self.assertRaises(AssertionError) as error:self.invoke(sources=broken)
        self.assertIn('stage=empty exit=1 stdout_bytes=0 stderr_bytes=0 authenticated=no',str(error.exception))
        self.assertEqual(self.invoke(b'\ndef collect(cancelled):\n    return refused("cleanup")\n'),(operation.refused('cleanup').decode().strip(),1))
        suffix=b'\noriginal=collect\ndef collect(cancelled):\n    body=original(cancelled)\n    s=__import__("signal")\n    s.pthread_sigmask(s.SIG_BLOCK,[s.SIGTERM])\n    os.kill(os.getpid(),s.SIGTERM)\n    return body\n'
        self.assertEqual(self.invoke(suffix),(operation.refused('interrupted').decode().strip(),1))


if __name__=='__main__':unittest.main()
