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
GCC_WARNINGS = frozenset(('array-bounds', 'discarded-qualifiers', 'format',
    'format-overflow', 'format-truncation', 'implicit-function-declaration',
    'incompatible-pointer-types', 'int-conversion', 'maybe-uninitialized',
    'restrict', 'return-type', 'sign-compare', 'sizeof-pointer-memaccess',
    'stringop-overflow', 'stringop-truncation', 'type-limits',
    'uninitialized', 'unused-but-set-variable', 'unused-function',
    'unused-parameter', 'unused-variable'))


def gcc_diagnostic(stderr):
    """Publish only a source identity, bounded line and fixed warning name."""
    for line in stderr[:65536].splitlines():
        match = re.fullmatch(
            rb'(?:[^\r\n]*[/\\])?(adapter\.c|core-tests\.c|generated_contract\.h)'
            rb':([1-9][0-9]{0,5})(?::[1-9][0-9]{0,3})?: (?:fatal )?error: [^\r\n]*',
            line)
        if match is None:
            continue
        option = re.search(rb'\[-Werror=([a-z0-9-]{1,48})\]$', line)
        warning = option[1].decode('ascii') if option else 'none'
        if warning not in GCC_WARNINGS and warning != 'none':
            warning = 'other'
        return (match[1].decode('ascii'), int(match[2]), warning)
    return ('UNKNOWN', 0, 'UNKNOWN')


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
            before=('  write(2,reason,strlen(reason));\n'
                    '  if(cleanup)write(2,"+child_cleanup",14);')
            after=('  size_t reason_len=strlen(reason);\n'
                   '  if(write(2,reason,reason_len)!=(ssize_t)reason_len)_exit(120);\n'
                   '  if(cleanup&&write(2,"+child_cleanup",14)!=14)_exit(120);')
            expected=original[:start]+(HERE/'namespace.h').read_text()+'\n'+original[end:]
            self.assertEqual(expected.count(before),1)
            self.assertEqual(generated,expected.replace(before,after,1))
            self.assertEqual(hashlib.sha256(generated.encode()).hexdigest(),info['adapter'])
            for changed in (original.replace('write(2,reason,strlen(reason));','write(2,reason,1);'),
                            original.replace('if(cleanup)write(2,"+child_cleanup",14);',
                                             'if(cleanup)write(2,"+child_cleanup",13);'),
                            original+'\n'+before+'\n'):
                with self.assertRaises(ValueError):build.adapter_variant(changed)
            self.check_child_fail_contract(original,generated,Path(td))
            original_core=(build.NATIVE/'adapter/core-tests.c').read_text()
            generated_core=(dest/'adapter/core-tests.c').read_text()
            self.assertEqual(hashlib.sha256(generated_core.encode()).hexdigest(),info['core_tests'])
            for line in original_core.splitlines():
                if line.lstrip().startswith('check('):
                    self.assertIn(line,generated_core)
            for changed in (original_core.replace('symlinkat("input"','symlinkat("other"'),
                            original_core+'\n  symlinkat("old",d,"link");\n'):
                with self.assertRaises(ValueError):build.core_test_variant(changed)
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

    def check_child_fail_contract(self,original,generated,root):
        # Extract the exact generated function. Synthetic handles/write fault injection
        # runs without root or namespaces; an explicit attribute covers both libc profiles.
        def function(source):
            start=source.index('static void child_fail(const char*reason)')
            return source[start:source.index('static void close_extra_fds(',start)]
        prefix='''#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
struct child_handles {int unused;};
static struct child_handles handles,*active_child_handles=&handles;
static int cleanup,fault,short_result,closed,calls;
static int child_handles_close(struct child_handles *h) {
  if(h!=&handles||active_child_handles||closed++)_exit(90);
  if(write(1,"C",1)!=1)_exit(91);
  return cleanup;
}
static ssize_t checked_write(int,const void *,size_t) __attribute__((warn_unused_result));
static ssize_t checked_write(int fd,const void *data,size_t size) {
  ++calls;
  if(fd!=2||closed!=1||active_child_handles)_exit(92);
  if(calls==1) {if(size!=5||memcmp(data,"pipes",5))_exit(93);}
  else if(calls==2) {if(!cleanup||size!=14||memcmp(data,"+child_cleanup",14))_exit(94);}
  else _exit(95);
  if(write(1,calls==1?"1":"2",1)!=1)_exit(96);
  if(calls==fault) {errno=EIO;return short_result?(ssize_t)size-1:-1;}
  return write(fd,data,size);
}
#define write checked_write
'''
        suffix='''int main(int argc,char **argv) {
  if(argc!=4)return 97;
  cleanup=atoi(argv[1]);fault=atoi(argv[2]);short_result=atoi(argv[3]);
  child_fail("pipes");return 98;
}
'''
        source=root/'child-fail.c';binary=root/'child-fail'
        command=['cc','-std=c11','-O2','-D_FORTIFY_SOURCE=2','-Wall','-Wextra','-Werror',
                 str(source),'-o',str(binary)]
        for text,passes in ((original,False),(generated,True)):
            source.write_text(prefix+function(text)+suffix)
            result=subprocess.run(command,capture_output=True,timeout=45)
            self.assertEqual(result.returncode==0,passes)
            if not passes:self.assertGreaterEqual(result.stderr.count(b'unused-result'),2)
            else:self.assertEqual(result.stderr,b'')
        for cleanup in (0,1):
            for fault in range(cleanup+2):
                for short_result in (0,1):
                    result=subprocess.run([str(binary),str(cleanup),str(fault),str(short_result)],
                                          capture_output=True,timeout=5)
                    self.assertEqual(result.returncode,120)
                    self.assertEqual(result.stdout,b'C1' if fault==1 or not cleanup else b'C12')
                    self.assertEqual(result.stderr,b'' if fault==1 else
                                     b'pipes' if fault==2 or not cleanup else b'pipes+child_cleanup')

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

    def test_portable_report_is_bounded_and_cannot_turn_failure_into_pass(self):
        diagnostic=(b'/tmp/PRIVATE_CANARY/variant/adapter/adapter.c:123:4: error: '
                    b'PRIVATE_CANARY [-Werror=unused-variable]\n')
        self.assertEqual(gcc_diagnostic(diagnostic),('adapter.c',123,'unused-variable'))
        self.assertEqual(gcc_diagnostic(b'/tmp/PRIVATE_CANARY:1: error: PRIVATE_CANARY\n'),
                         ('UNKNOWN',0,'UNKNOWN'))
        self.assertEqual(gcc_diagnostic(diagnostic.replace(b'unused-variable',b'PRIVATE_CANARY')),
                         ('adapter.c',123,'none'))
        cases={'test_generated_common_c_core_real_lifecycle'}
        pass_line=b'CLB195_PORTABLE_V2 status=PASS tests=14 failed=none c_stage=none\n'
        failure=(b'CLB195_PORTABLE_V2 status=FAIL tests=14 '
                 b'failed=test_generated_common_c_core_real_lifecycle c_stage=compile_nonzero\n')
        self.assertEqual(ci.portable_result(pass_line,0,cases),(True,'none','none'))
        self.assertEqual(ci.portable_result(failure,1,cases),
                         (False,'test_generated_common_c_core_real_lifecycle','compile_nonzero'))
        for raw,code in [(pass_line,1),(failure,0),(pass_line+b'PRIVATE_CANARY',0),
                         (b'CLB195_PORTABLE_V2 status=FAIL tests=14 failed=none c_stage=none\n',1),
                         (b'CLB195_PORTABLE_V2 status=FAIL tests=14 failed=test_unknown c_stage=none\n',1),
                         (b'CLB195_PORTABLE_V2 status=PASS tests=14 failed=none c_stage=compile_nonzero\n',0),
                         (failure.replace(b'compile_nonzero',b'PRIVATE_CANARY'),1)]:
            with self.subTest(raw=raw[:45],code=code):
                self.assertFalse(ci.portable_result(raw,code,cases)[0])
        for stage, outcomes in (
            ('compile_timeout', [subprocess.TimeoutExpired('cc',45)]),
            ('compile_nonzero', [subprocess.CompletedProcess([],1,b'',b'PRIVATE_CANARY')]),
            ('core_exit_nonzero', [subprocess.CompletedProcess([],0,b'',b''),
                                   subprocess.CompletedProcess([],1,b'',b'')]),
            ('core_timeout', [subprocess.CompletedProcess([],0,b'',b''),
                              subprocess.TimeoutExpired('core',10)]),
            ('core_stderr_nonempty', [subprocess.CompletedProcess([],0,b'',b''),
                                      subprocess.CompletedProcess([],0,b'',b'PRIVATE_CANARY')]),
            ('core_stdout_bounds', [subprocess.CompletedProcess([],0,b'',b''),
                                    subprocess.CompletedProcess([],0,b'x'*16384,b'')]),
            ('summary_parse', [subprocess.CompletedProcess([],0,b'',b''),
                               subprocess.CompletedProcess([],0,b'not-json\n',b'')]),
            ('summary_schema', [subprocess.CompletedProcess([],0,b'',b''),
                                subprocess.CompletedProcess([],0,b'{}\n',b'')]),
            ('summary_failed', [subprocess.CompletedProcess([],0,b'',b''),
                                subprocess.CompletedProcess([],0,
                                    b'{"summary":{"failed":1,"native_namespace":"NOT_RUN"}}\n',b'')]),
            ('summary_namespace', [subprocess.CompletedProcess([],0,b'',b''),
                                   subprocess.CompletedProcess([],0,
                                       b'{"summary":{"failed":0,"native_namespace":"BAD"}}\n',b'')])):
            case=Tests('test_generated_common_c_core_real_lifecycle')
            with self.subTest(stage=stage),patch.object(subprocess,'run',side_effect=outcomes),\
                    self.assertRaises((AssertionError,subprocess.TimeoutExpired,ValueError,KeyError)):
                case.test_generated_common_c_core_real_lifecycle()
            self.assertEqual(case.c_core_stage,stage)

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
        self.c_core_stage='source_load'
        driver=runpy.run_path(str(build.NATIVE/'tests/native-driver.py'))
        with tempfile.TemporaryDirectory() as td:
            self.c_core_stage='materialize'
            root=Path(td);build.materialize(root/'variant',driver)
            command=['cc','-std=c11','-O1','-Wall','-Wextra','-Werror',
                     '-Wno-unused-function','-Wno-misleading-indentation',
                     str(root/'variant/adapter/core-tests.c'),'-o',str(root/'core')]
            self.c_core_stage='compile_timeout'
            compiled=subprocess.run(command,capture_output=True,timeout=45)
            self.c_core_stage='compile_nonzero'
            if compiled.returncode:
                self.c_core_gcc_diag=gcc_diagnostic(compiled.stderr)
            self.assertEqual(compiled.returncode,0,compiled.stderr.decode(errors='replace')[:2048])
            self.c_core_stage='core_timeout'
            result=subprocess.run([str(root/'core')],capture_output=True,timeout=10)
            self.c_core_stage='core_exit_nonzero'
            self.assertEqual(result.returncode,0)
            self.c_core_stage='core_stderr_nonempty'
            self.assertEqual(result.stderr,b'')
            self.c_core_stage='core_stdout_bounds'
            self.assertLess(len(result.stdout),16384)
            self.c_core_stage='summary_parse'
            records=[json.loads(line) for line in result.stdout.splitlines()]
            self.c_core_stage='summary_schema'
            summary=records[-1]['summary']
            failed=summary['failed']
            self.c_core_stage='summary_failed'
            self.assertEqual(failed,0)
            self.c_core_stage='summary_namespace'
            self.assertEqual(summary['native_namespace'],'NOT_RUN')
            core_path=root/'variant/adapter/core-tests.c'
            original_core=core_path.read_text()
            # Enforce result-use on both libc profiles; this is not Ubuntu acceptance.
            annotations=('extern int symlinkat(const char *,int,const char *) '
                         '__attribute__((warn_unused_result));\n'
                         'extern int pipe(int [2]) __attribute__((warn_unused_result));\n'
                         'extern ssize_t write(int,const void *,size_t) '
                         '__attribute__((warn_unused_result));\n'
                         'extern ssize_t pwrite(int,const void *,size_t,off_t) '
                         '__attribute__((warn_unused_result));\n')
            for source,passes in (((build.NATIVE/'adapter/core-tests.c').read_text(),False),
                                  (original_core,True)):
                core_path.write_text(source.replace('#include "adapter.c"\n',
                                                   '#include "adapter.c"\n'+annotations))
                self.c_core_stage='compile_timeout'
                compiled=subprocess.run(command,capture_output=True,timeout=45)
                self.c_core_stage='unclassified'
                self.assertEqual(compiled.returncode==0,passes)
                if not passes:self.assertIn(b'unused-result',compiled.stderr)
            # A failed replacement must not pass merely because recheck sees absence.
            faults=[]
            for target,control in (('input','symlink_fixture_created'),
                                   ('old','symlink_fixture_replaced')):
                shim=('static int fail_symlink(const char *target,int fd,const char *name) {\n'
                      '  if(!strcmp(target,"'+target+'")) {errno=EIO;return -1;}\n'
                      '  return symlinkat(target,fd,name);\n}\n'
                      '#define symlinkat fail_symlink\n')
                faults.append((shim,control))
            faults.extend((
                ('static int fail_pipe(int fd[2]) {\n  static unsigned calls;\n'
                 '  if(++calls==2) {errno=EMFILE;return -1;}\n  return pipe(fd);\n}\n'
                 '#define pipe fail_pipe\n','actual_partial_output_refused'),
                ('static pid_t fail_fork(void) {errno=EAGAIN;return -1;}\n'
                 '#define fork fail_fork\n','actual_partial_output_refused'),
                ('static ssize_t short_write(int fd,const void *data,size_t size) {\n'
                 '  if(fd==1&&size==7)return 0;\n  return write(fd,data,size);\n}\n'
                 '#define write short_write\n','actual_partial_output_refused'),
                ('static ssize_t fail_pwrite(int fd,const void *data,size_t size,off_t offset) {\n'
                 '  if(size==1&&offset==0) {errno=EIO;return -1;}\n'
                 '  return pwrite(fd,data,size,offset);\n}\n'
                 '#define pwrite fail_pwrite\n','held_file_mutation_written'),
            ))
            for shim,control in faults:
                core_path.write_text(original_core.replace('#include "adapter.c"\n',
                                                           '#include "adapter.c"\n'+shim))
                self.c_core_stage='compile_timeout'
                compiled=subprocess.run(command,capture_output=True,timeout=45)
                self.c_core_stage='compile_nonzero'
                if compiled.returncode:self.c_core_gcc_diag=gcc_diagnostic(compiled.stderr)
                self.assertEqual(compiled.returncode,0)
                self.c_core_stage='core_timeout'
                result=subprocess.run([str(root/'core')],capture_output=True,timeout=10)
                self.c_core_stage='core_exit_nonzero'
                self.assertEqual(result.returncode,1)
                self.c_core_stage='core_stderr_nonempty'
                self.assertEqual(result.stderr,b'')
                self.c_core_stage='core_stdout_bounds'
                self.assertLess(len(result.stdout),16384)
                self.c_core_stage='summary_parse'
                records=[json.loads(line) for line in result.stdout.splitlines()]
                self.c_core_stage='summary_failed'
                self.assertGreater(records[-1]['summary']['failed'],0)
                self.assertIn({'test':control,'pass':False},records)
                self.assertIn({'test':'owned_fixture_cleanup','pass':True},records)
                self.c_core_stage='summary_namespace'
                self.assertEqual(records[-1]['summary']['native_namespace'],'NOT_RUN')
        self.c_core_stage='none'

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
        # Exercise the real build orchestration with synthetic operations only.
        # This proves attribution and fail-closed propagation, not native execution.
        stages=('materialize','adapter_compile','adapter_read','adapter_elf',
                'core_compile','core_read','core_elf',
                'fixture_compile','fixture_read','fixture_elf')
        expected_flags=['/usr/bin/gcc','-std=c11','-O2','-static','-fno-pie','-no-pie',
                        '-fstack-protector-strong','-D_FORTIFY_SOURCE=2','-Wl,--build-id=none',
                        '-Wall','-Wextra','-Werror','-Wno-unused-function',
                        '-Wno-misleading-indentation']
        self.assertEqual(list(ci.STATIC_FLAGS),expected_flags)
        for fault in (None,)+stages:
            events=[]; commands=[{'exit':1,'primary':'timeout'}]; diagnostic={}
            tree=Path('/synthetic/source'); binaries=Path('/synthetic/bin'); sources={}
            class BuildFailure(Exception): pass
            def event(stage):
                events.append(stage)
                if stage==fault:
                    raise BuildFailure('PRIVATE_CANARY /private/path')
            def materialize(dest,old,identities):
                self.assertEqual((dest,identities),(tree,sources));event('materialize')
            def capture(argv,seconds,records):
                self.assertEqual(argv[:len(expected_flags)],expected_flags)
                self.assertEqual(seconds,60)
                name=Path(argv[argv.index('-o')+1]).name
                stage='core' if name=='core-tests' else name
                source=tree/('tests/native-fixture-runner.c' if name=='fixture' else 'adapter/'+name+'.c')
                self.assertEqual(argv[len(expected_flags):len(expected_flags)+3],
                                 [str(source),'-o',str(binaries/name)])
                self.assertEqual(argv[len(expected_flags)+3:],extra if name=='fixture' else [])
                records.append({'exit':1 if fault==stage+'_compile' else 0,'primary':None,
                                'stderr':'PRIVATE_CANARY','argv':argv})
                event(stage+'_compile')
                return b''
            def read(path,bound):
                self.assertEqual(bound,16*1024*1024)
                stage='core' if path.name=='core-tests' else path.name
                event(stage+'_read');return stage.encode()
            def elf(raw):
                event(raw.decode()+'_elf');return {'sha256':'a'*64}
            old={'capture':capture,'read_exact':read,'elf_static':elf}
            extra=['-DADAPTER_EXEC_SHA256="'+'a'*64+'"']
            def run():
                with patch.object(ci,'adapter_capture',side_effect=lambda o,a,s,c,d:o['capture'](a,s,c)) as observed:
                    try:
                        ci.build_programs(tree,binaries,{'materialize':materialize},old,commands,sources,diagnostic)
                        return ci.compile_static(tree,binaries,'fixture',old,commands,diagnostic,extra)
                    finally:
                        self.assertEqual([Path(call.args[1][len(expected_flags)]).name
                                          for call in observed.call_args_list],
                                         [name for stage,name in (('adapter_compile','adapter.c'),
                                             ('fixture_compile','native-fixture-runner.c')) if stage in events])
            with self.subTest(fault=fault):
                if fault is None:
                    self.assertEqual(run(),{'sha256':'a'*64})
                    self.assertEqual(events,list(stages))
                    self.assertEqual(diagnostic,{'stage':'none','reason':'none'})
                else:
                    with self.assertRaises(BuildFailure):run()
                    self.assertEqual(events,list(stages[:stages.index(fault)+1]))
                    reason='command_nonzero' if fault.endswith('_compile') else 'operation_failed'
                    self.assertEqual(ci.build_suffix(diagnostic),
                                     ' build_stage='+fault+' build_reason='+reason)
        # Only a new, bounded transport record may classify a command failure.
        for primary,exit_code,reason in (
                ('timeout',-15,'timeout'),('cancelled',-15,'cancelled'),
                ('output_bound',1,'output_bound'),('process_unconfirmed',None,'process_unconfirmed'),
                (None,2,'command_nonzero'),('PRIVATE_CANARY',2,'operation_failed'),
                (None,True,'operation_failed'),(None,0,'operation_failed')):
            commands=[];diagnostic={}
            def fail():
                commands.append({'primary':primary,'exit':exit_code,'stderr':'PRIVATE_CANARY'})
                raise BuildFailure('PRIVATE_CANARY')
            with self.subTest(primary=primary,exit=exit_code),self.assertRaises(BuildFailure):
                ci.build_step(diagnostic,'adapter_compile',fail,commands)
            self.assertEqual(diagnostic['reason'],reason)
        for stage in ci.BUILD_STAGES:
            diagnostic={}
            def fail():raise BuildFailure('PRIVATE_CANARY')
            with self.assertRaises(BuildFailure):ci.build_step(diagnostic,stage,fail)
            self.assertEqual(diagnostic,{'stage':stage,'reason':'operation_failed'})
        for error in (KeyboardInterrupt('PRIVATE_CANARY'),SystemExit('PRIVATE_CANARY')):
            diagnostic={}
            def fail():raise error
            with self.assertRaises(type(error)):ci.build_step(diagnostic,'materialize',fail)
            self.assertEqual(diagnostic['reason'],'operation_failed')
        for bad in ({},{'stage':'PRIVATE_CANARY','reason':'timeout'},
                    {'stage':'adapter_compile','reason':'PRIVATE_CANARY'}):
            self.assertEqual(ci.build_suffix(bad),' build_stage=UNKNOWN build_reason=UNKNOWN')
        warning=(b'/private/CANARY/adapter.c:1000:2: error: CANARY [-Werror=unused-result]\n')
        self.assertEqual(ci.gcc_diagnostic(warning),('adapter.c',1000,'unused-result'))
        fixture_warning=warning.replace(b'adapter.c',b'native-fixture-runner.c')
        for name in ('native-fixture-runner.c','fixture-bind-probe.h',
                     'fixture-namespace-handles.h','fixture-resource-envelope.h'):
            self.assertEqual(ci.gcc_diagnostic(warning.replace(b'adapter.c',name.encode())),
                             (name,1000,'unused-result'))
            self.assertEqual(ci.gcc_suffix({'gcc':(name,1000,'unused-result')}),
                             ' gcc_source='+name+' gcc_line=1000 gcc_warning=unused-result')
        for raw in (b'CANARY', b'/private/CANARY/other.c:2: error: CANARY\n',
                    warning.replace(b':1000:',b':1000000:'), warning+b'x'*65536,
                    b'\x1b[31m'+warning, 'CANARY', None):
            self.assertEqual(ci.gcc_diagnostic(raw),('UNKNOWN',0,'UNKNOWN'))
        self.assertEqual(ci.gcc_diagnostic(warning.replace(b'unused-result',b'unsupported-warning')),
                         ('adapter.c',1000,'other'))
        unknown=' gcc_source=UNKNOWN gcc_line=0 gcc_warning=UNKNOWN'
        for value in (('CANARY',3,'none'),('adapter.c',True,'none'),
                      ('adapter.c',1000000,'none'),('adapter.c',3,'CANARY'),None):
            self.assertEqual(ci.gcc_suffix({'gcc':value}),unknown)
        # Real unprivileged bounded transport, synthetic process only. No GCC/root/namespace.
        driver=runpy.run_path(str(build.NATIVE/'tests/native-driver.py'))
        for stderr,code in ((warning,1),(fixture_warning,1),(b'CANARY',1),(warning,0),(fixture_warning,0)):
            commands=[]; diagnostic={}
            command=[sys.executable,'-I','-S','-B','-c',
                     'import os;os.write(2,'+repr(stderr)+');raise SystemExit('+str(code)+')']
            if code:
                with self.assertRaises(driver['Refused']):
                    ci.adapter_capture(driver,command,5,commands,diagnostic)
                self.assertEqual(diagnostic['gcc'],ci.gcc_diagnostic(stderr))
                self.assertNotIn('CANARY',ci.gcc_suffix(diagnostic))
            else:
                self.assertEqual(ci.adapter_capture(driver,command,5,commands,diagnostic),b'')
                self.assertNotIn('gcc',diagnostic)
            self.assertEqual(commands[-1]['exit'],code)
            self.assertNotIn('stderr',commands[-1])
        commands=[];diagnostic={}
        with self.assertRaises(driver['Refused']):
            ci.adapter_capture(driver,[sys.executable,'-I','-S','-B','-c',
                                       'import time;time.sleep(5)'],0.05,commands,diagnostic)
        self.assertEqual(commands[-1]['primary'],'timeout')
        self.assertNotIn('gcc',diagnostic)
        self.assertEqual(ci.gcc_suffix(diagnostic),unknown)


if __name__=='__main__':
    # CI exposes fixed identifiers/stages and a bounded GCC location, never raw stderr.
    class BoundedResult(unittest.TestResult):
        c_core_stage='none'
        c_core_gcc_diag=('UNKNOWN',0,'UNKNOWN')

        def addFailure(self,test,err):
            super().addFailure(test,err)
            if test._testMethodName == 'test_generated_common_c_core_real_lifecycle':
                stage=getattr(test,'c_core_stage','unclassified')
                self.c_core_stage=stage if stage in ci.C_STAGES else 'unclassified'
                self.c_core_gcc_diag=getattr(test,'c_core_gcc_diag',('UNKNOWN',0,'UNKNOWN'))

        def addError(self,test,err):
            super().addError(test,err)
            if test._testMethodName == 'test_generated_common_c_core_real_lifecycle':
                stage=getattr(test,'c_core_stage','unclassified')
                self.c_core_stage=stage if stage in ci.C_STAGES else 'unclassified'
                self.c_core_gcc_diag=getattr(test,'c_core_gcc_diag',('UNKNOWN',0,'UNKNOWN'))

    cases = unittest.defaultTestLoader.loadTestsFromTestCase(Tests)
    result = BoundedResult()
    cases.run(result)
    failed = sorted({test._testMethodName for test, _ in result.failures + result.errors})
    complete = (result.testsRun == 14 and len(cases._tests) == 14 and
                not result.skipped and not result.expectedFailures and
                not result.unexpectedSuccesses and not result.shouldStop)
    verdict = 'PASS' if result.wasSuccessful() and complete else 'FAIL'
    print('CLB195_PORTABLE_V2 status='+verdict+' tests='+str(result.testsRun)+
          ' failed='+(','.join(failed) if failed else 'none')+
          ' c_stage='+result.c_core_stage)
    if verdict == 'FAIL' and result.c_core_stage == 'compile_nonzero':
        source, line, warning = result.c_core_gcc_diag
        print('CLB195_GCC_DIAG source='+source+' line='+str(line)+
              ' warning='+warning, file=sys.stderr)
    raise SystemExit(0 if verdict == 'PASS' else 1)
