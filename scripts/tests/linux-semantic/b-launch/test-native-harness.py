"""Portable protocol/source/lifecycle regressions; NEVER native acceptance.

No namespace, mount, credentials, capabilities, LSM or Linux syscalls execute.
Real unprivileged fork/mmap/FD/cleanup controls complement adversarial inputs.
"""
import ast
from contextlib import ExitStack, redirect_stdout, redirect_stderr
import errno
import io
from types import SimpleNamespace
from copy import deepcopy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import signal
import struct
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

HERE = Path(__file__).resolve().parent

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    sys.modules[name] = value
    spec.loader.exec_module(value)
    return value

E = load('native_entry_test', HERE/'native-entry.py')
R = load('native_protocol_test', HERE/'native-protocol.py')
P = load('native_prototype_test', HERE/'prototype.py')
H = load('native_harness_test', HERE/'native-harness.py')
H.P, H.R = P, R
REV, DIGEST = E.BASE, 'c'*64


def report(): return R.new(REV, DIGEST)

def native_report():
    value = report()
    value.update(execution='NATIVE',run_id='1234',attempt='1',cleanup='PASS')
    value['prerequisites'] = dict.fromkeys(R.PREREQUISITES,'PASS')
    return value


class Protocol(unittest.TestCase):
    def test_default_is_not_run_and_bounded(self):
        value = report()
        raw = R.encode(value)
        self.assertLess(len(raw),4096)
        self.assertEqual(R.parse(raw,2,REV,DIGEST),value)
        self.assertEqual(set(value['controls'].values()),{'NOT_RUN'})

    def test_pass_needs_every_control(self):
        for name in R.CONTROLS:
            with self.subTest(control=name):
                value = native_report()
                value.update(verdict='PASS',reason='none')
                value['controls'] = dict.fromkeys(R.CONTROLS,'PASS')
                value['controls'][name] = 'NOT_RUN'
                with self.assertRaises(R.Invalid): R.encode(value)

    def test_cleanup_not_confirmed_never_passes(self):
        for status in ('NOT_RUN','FAIL','UNCONFIRMED'):
            value = native_report()
            value.update(verdict='PASS',reason='none',cleanup=status)
            value['controls'] = dict.fromkeys(R.CONTROLS,'PASS')
            with self.assertRaises(R.Invalid): R.encode(value)

    def test_synthetic_cannot_be_native(self):
        value = report()
        value.update(verdict='PASS',reason='none',cleanup='PASS')
        value['controls'] = dict.fromkeys(R.CONTROLS,'PASS')
        with self.assertRaises(R.Invalid): R.encode(value)

    def test_unknown_missing_duplicate_outputs(self):
        raw = R.encode(report())
        for data in (raw[:-2]+b',"secret":"CANARY"}',b'{"format":1,"format":1}',
                     b'{}',raw+raw,b'{"x":NaN}',b'x'*4097):
            with self.subTest(data=len(data)):
                with self.assertRaises((R.Invalid,ValueError)):
                    R.parse(data,2,REV,DIGEST)

    def test_exact_source_and_run_binding(self):
        value = native_report()
        value.update(verdict='FAIL',reason='control')
        value['controls'][R.CONTROLS[0]] = 'FAIL'
        for key,bad in (('revision','d'*40),('source_contract','d'*64),('run_id','0'),
                        ('attempt','2'),('repository','foreign/repo'),('format',True)):
            candidate = deepcopy(value); candidate[key] = bad
            with self.assertRaises(R.Invalid): R.validate(candidate,REV,DIGEST)

    def test_exit_consistency(self):
        for code in (0,1,True):
            with self.assertRaises(R.Invalid): R.parse(R.encode(report()),code,REV,DIGEST)

    def test_failure_is_terminal(self):
        value = native_report(); ledger = R.Ledger(value)
        ledger.record(R.CONTROLS[0],False)
        with self.assertRaises(R.Invalid): ledger.record(R.CONTROLS[1],True)
        with self.assertRaises(R.Invalid): ledger.complete()
        self.assertEqual(value['controls'][R.CONTROLS[0]],'FAIL')

    def test_duplicate_out_of_order_missing_results(self):
        for first in (R.CONTROLS[1], 'unknown'):
            with self.assertRaises(R.Invalid): R.Ledger(native_report()).record(first,True)
        ledger = R.Ledger(native_report()); ledger.record(R.CONTROLS[0],True)
        with self.assertRaises(R.Invalid): ledger.record(R.CONTROLS[0],True)
        with self.assertRaises(R.Invalid): ledger.complete()

    def test_prerequisite_failure_leaves_native_not_run(self):
        value = report(); value['prerequisites']['source'] = 'PASS'
        def refusal(result):
            H.check('lsm',lambda:P.require(False),result)
        with patch.object(H,'preflight',refusal),patch.object(H,'run_case') as run:
            H.run(value)
        run.assert_not_called()
        self.assertEqual(value['verdict'],'FAIL')
        self.assertEqual(set(value['controls'].values()),{'NOT_RUN'})
        self.assertEqual(R.parse(R.encode(value),1,REV,DIGEST),value)

    def test_interrupt_before_setup_never_passes(self):
        value = report()
        with patch.object(H,'preflight',side_effect=KeyboardInterrupt),patch.object(H,'run_case') as run:
            H.run(value)
        run.assert_not_called()
        R.parse(R.encode(value),1,REV,DIGEST)

    def test_actual_parent_term_cancellation_restores_handlers(self):
        value = report()
        previous = {name:signal.getsignal(name) for name in
                    (signal.SIGALRM,signal.SIGINT,signal.SIGTERM,signal.SIGHUP)}
        with patch.object(H,'preflight',side_effect=lambda _:os.kill(os.getpid(),signal.SIGTERM)):
            H.run(value)
        self.assertEqual(value['verdict'],'FAIL')
        self.assertEqual(value['reason'],'interrupted')
        self.assertEqual(set(value['controls'].values()),{'NOT_RUN'})
        self.assertEqual({name:signal.getsignal(name) for name in previous},previous)
        R.parse(R.encode(value),1,REV,DIGEST)

    def test_control_cleanup_exception_remains_unconfirmed(self):
        value = report()
        def prerequisites(result):
            result['prerequisites'] = dict.fromkeys(R.PREREQUISITES,'PASS')
        # Fault-inject only orchestration, never claim a kernel control ran.
        with patch.object(H,'preflight',prerequisites),patch.dict(os.environ,GITHUB_RUN_ID='1234'):
            with patch.object(H.resource,'setrlimit'),patch.object(H,'run_case',side_effect=P.Refused):
                H.run(value)
        self.assertEqual(value['verdict'],'FAIL')
        self.assertEqual(value['cleanup'],'UNCONFIRMED')
        self.assertEqual(value['controls'][R.CONTROLS[0]],'FAIL')
        R.parse(R.encode(value),1,REV,DIGEST)

    def test_false_control_cannot_be_replaced_by_success(self):
        value = report()
        def prerequisites(result):
            result['prerequisites'] = dict.fromkeys(R.PREREQUISITES,'PASS')
        with patch.object(H,'preflight',prerequisites),patch.dict(os.environ,GITHUB_RUN_ID='1234'):
            with patch.object(H.resource,'setrlimit'),patch.object(H,'run_case',return_value=False) as control:
                H.run(value)
        self.assertEqual(control.call_count,1)
        self.assertEqual(value['verdict'],'FAIL')
        self.assertEqual(value['controls'][R.CONTROLS[0]],'FAIL')
        R.parse(R.encode(value),1,REV,DIGEST)


class BackingDiagnostic(unittest.TestCase):
    """Real parser/report routing with synthetic stat/FD reads, never /opt/Linux setup."""
    CANARY = 'PRIVATE-MOUNT-SOURCE-UID-PATH'

    def exercise(self, *, uid=0, gid=0, mode=0o755, filesystem='ext4',
                 fdinfo=b'mnt_id: 10\n', open_error=None, stat_error=None,
                 table_error=None, close_error=None, success=False):
        kernel = Mock()
        kernel.table.return_value = P.mount_table(
            ('10 1 8:1 / / rw - '+filesystem+' /'+self.CANARY+' rw\n').encode())
        if table_error is not None: kernel.table.side_effect = table_error
        info = SimpleNamespace(st_uid=uid,st_gid=gid,st_mode=mode)
        def private_read(path, operation):
            self.assertEqual((path,operation),('/proc/self/fdinfo/91','rb'))
            if isinstance(fdinfo,BaseException): raise fdinfo
            return io.BytesIO(fdinfo)
        value = report();value['prerequisites'].update(source='PASS',procfs='PASS')
        before = deepcopy(value['prerequisites'])
        stdout,stderr = io.StringIO(),io.StringIO()
        with ExitStack() as stack:
            opened = stack.enter_context(patch.object(H.os,'open',return_value=91,side_effect=open_error))
            stack.enter_context(patch.object(H.os,'fstat',return_value=info,side_effect=stat_error))
            closed = stack.enter_context(patch.object(H.os,'close',side_effect=close_error))
            stack.enter_context(patch('builtins.open',side_effect=private_read))
            stack.enter_context(redirect_stdout(stdout));stack.enter_context(redirect_stderr(stderr))
            if success:
                H.check_backing(kernel,value)
            else:
                stack.enter_context(patch.object(H,'preflight',side_effect=lambda v:H.check_backing(kernel,v)))
                run_case = stack.enter_context(patch.object(H,'run_case'))
                H.run(value)
                run_case.assert_not_called()
        opened.assert_called_once_with('/opt',os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        if open_error is None: closed.assert_called_once_with(91)
        else: closed.assert_not_called()
        self.assertEqual(stdout.getvalue()+stderr.getvalue(),'')
        self.assertEqual({k:v for k,v in value['prerequisites'].items() if k!='backing'},
                         {k:v for k,v in before.items() if k!='backing'})
        if success:
            self.assertEqual(value['prerequisites']['backing'],'PASS')
            self.assertEqual(value['execution'],'NOT_RUN')
        else:
            self.assertEqual(value['verdict'],'FAIL')
            self.assertEqual(value['prerequisites']['backing'],'FAIL')
            self.assertEqual(value['execution'],'NOT_RUN')
            self.assertEqual(value['cleanup'],'NOT_RUN')
            self.assertEqual(len(value['controls']),24)
            self.assertEqual(set(value['controls'].values()),{'NOT_RUN'})
            raw = R.encode(value);self.assertEqual(R.parse(raw,1,REV,DIGEST),value)
            for forbidden in (self.CANARY,b'/opt',b'Traceback',b'PermissionError',b'st_uid'):
                if isinstance(forbidden,str):forbidden=forbidden.encode()
                self.assertNotIn(forbidden,raw)
            self.assertLessEqual(len(raw),4096)
        return value,kernel

    def test_exact_allowed_filesystems_and_metadata_still_pass(self):
        self.assertEqual(H.ALLOWED_FS,('ext2','ext3','ext4','xfs','btrfs','zfs','f2fs'))
        for filesystem in H.ALLOWED_FS:
            with self.subTest(filesystem=filesystem): self.exercise(filesystem=filesystem,success=True)

    def test_open_failure_no_retry_or_fallback(self):
        for error in (errno.EACCES,errno.ENOENT,errno.ELOOP,errno.ENOTDIR):
            with self.subTest(errno=error):
                value,kernel=self.exercise(open_error=OSError(error,self.CANARY))
                self.assertEqual(value['reason'],'backing_open');kernel.table.assert_not_called()

    def test_uid_gid_mode_each_refuse_before_backing_lookup(self):
        for fields in (dict(uid=1701),dict(gid=2701),dict(mode=0o777),dict(mode=0o750),dict(mode=0o2755)):
            with self.subTest(fields=fields):
                value,kernel=self.exercise(**fields)
                self.assertEqual(value['reason'],'backing_metadata');kernel.table.assert_not_called()

    def test_fdinfo_missing_ambiguous_oversize_and_unknown_mount_refuse(self):
        for raw in (b'',b'mnt_id: 10\nmnt_id: 10\n',b'mnt_id: 99\n',b'X'*4097):
            with self.subTest(size=len(raw)):
                value,kernel=self.exercise(fdinfo=raw)
                self.assertEqual(value['reason'],'backing_lookup');kernel.table.assert_called_once()

    def test_mountinfo_read_and_parse_failures_refuse(self):
        for error in (OSError(errno.EIO,self.CANARY),P.Refused(),UnicodeError(self.CANARY)):
            value,kernel=self.exercise(table_error=error)
            self.assertEqual(value['reason'],'backing_lookup');kernel.table.assert_called_once()

    def test_fdinfo_read_error_remains_lookup_failure(self):
        value,kernel=self.exercise(fdinfo=OSError(errno.EIO,self.CANARY))
        self.assertEqual(value['reason'],'backing_lookup');kernel.table.assert_called_once()

    def test_cancellation_during_backing_closes_fd_and_remains_interrupted(self):
        before=signal.getsignal(signal.SIGTERM)
        value,_=self.exercise(stat_error=lambda _:os.kill(os.getpid(),signal.SIGTERM))
        self.assertEqual(value['reason'],'interrupted')
        self.assertEqual(signal.getsignal(signal.SIGTERM),before)

    def test_disallowed_filesystems_never_pass_or_reveal_type(self):
        for filesystem in ('overlay','tmpfs',self.CANARY):
            value,kernel=self.exercise(filesystem=filesystem)
            self.assertEqual(value['reason'],'backing_filesystem');kernel.table.assert_called_once()

    def test_unexpected_open_and_metadata_errors_are_fixed_refusal(self):
        for fields in (dict(open_error=RuntimeError(self.CANARY)),
                       dict(stat_error=OSError(errno.EIO,self.CANARY)),
                       dict(stat_error=RuntimeError(self.CANARY))):
            value,kernel=self.exercise(**fields)
            self.assertEqual(value['reason'],'backing_diagnostic');kernel.table.assert_not_called()

    def test_close_error_after_success_cannot_pass(self):
        value,_=self.exercise(close_error=OSError(errno.EBADF,self.CANARY))
        self.assertEqual(value['reason'],'backing_diagnostic')

    def test_close_error_after_mismatch_remains_fail(self):
        value,_=self.exercise(uid=1701,close_error=OSError(errno.EBADF,self.CANARY))
        self.assertEqual(value['reason'],'backing_diagnostic')

    def test_detailed_reason_protocol_is_pre_execution_fail_only(self):
        for reason in R.BACKING_REASONS:
            value=report();value.update(verdict='FAIL',reason=reason)
            value['prerequisites']['backing']='FAIL'
            self.assertEqual(R.parse(R.encode(value),1,REV,DIGEST),value)
            for field,bad in (('execution','NATIVE'),('verdict','PASS'),('cleanup','PASS')):
                changed=deepcopy(value);changed[field]=bad
                with self.assertRaises(R.Invalid):R.encode(changed)
            value['prerequisites']['backing']='PASS'
            value['prerequisites']['lsm']='FAIL'
            with self.assertRaises(R.Invalid):R.encode(value)
        value=report();value.update(verdict='FAIL',reason='backing_'+self.CANARY)
        value['prerequisites']['backing']='FAIL'
        with self.assertRaises(R.Invalid):R.encode(value)


class Sources(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name).resolve()
        for name in E.NAMES:
            (self.directory/name).write_bytes((HERE/name).read_bytes())
        self.seal()

    def seal(self):
        value = dict(format=1,base=E.BASE,tree=E.TREE,files={name:hashlib.sha256(
            (self.directory/name).read_bytes()).hexdigest() for name in E.NAMES})
        raw = (json.dumps(value,sort_keys=True,indent=2)+'\n').encode()
        (self.directory/'native-sources.json').write_bytes(raw)
        self.digest = hashlib.sha256(raw).hexdigest()

    def test_exact_capture(self):
        buffers = E.sources(self.directory,self.digest)
        self.assertEqual(set(buffers),set(E.NAMES))
        self.assertTrue(all(buffers[name] == (HERE/name).read_bytes() for name in E.NAMES))

    def test_each_source_drift_refuses(self):
        for name in E.NAMES:
            path = self.directory/name; raw = path.read_bytes()
            path.write_bytes(raw+b'\n')
            with self.assertRaises(ValueError): E.sources(self.directory,self.digest)
            path.write_bytes(raw)

    def test_manifest_drift_and_unknown_file(self):
        path = self.directory/'native-sources.json'
        data = json.loads(path.read_bytes()); data['files']['other.py'] = 'a'*64
        raw = json.dumps(data).encode(); path.write_bytes(raw)
        with self.assertRaises(ValueError): E.sources(self.directory,hashlib.sha256(raw).hexdigest())
        with self.assertRaises(ValueError): E.sources(self.directory,'0'*64)

    def test_wrong_base_tree_and_duplicate_manifest(self):
        path = self.directory/'native-sources.json'
        for key in ('base','tree'):
            self.seal(); value = json.loads(path.read_bytes()); value[key] = 'e'*40
            raw = json.dumps(value).encode(); path.write_bytes(raw)
            with self.assertRaises(ValueError): E.sources(self.directory,hashlib.sha256(raw).hexdigest())
        raw = b'{"format":1,"format":1}'
        path.write_bytes(raw)
        with self.assertRaises(ValueError): E.sources(self.directory,hashlib.sha256(raw).hexdigest())

    def test_symlink_refusal(self):
        path = self.directory/E.NAMES[0]
        path.rename(self.directory/'retained')
        path.symlink_to('retained')
        with self.assertRaises((ValueError,OSError)): E.sources(self.directory,self.digest)

    def test_hardlink_refusal(self):
        os.link(self.directory/E.NAMES[0],self.directory/'alias')
        with self.assertRaises(ValueError): E.sources(self.directory,self.digest)

    def test_mode_refusal(self):
        for mode in (0o666,0o664):
            os.chmod(self.directory/E.NAMES[0],mode)
            with self.assertRaises(ValueError): E.sources(self.directory,self.digest)

    def test_wrong_owner_observation_refusal(self):
        # Adversarial stat input only; no chown/privileged operation.
        original = E.os.fstat
        class OtherOwner:
            def __init__(self,real): self.real = real
            def __getattr__(self,name): return 987654 if name == 'st_uid' else getattr(self.real,name)
        with patch.object(E.os,'fstat',side_effect=lambda fd:OtherOwner(original(fd))):
            with self.assertRaises(ValueError): E.sources(self.directory,self.digest)

    def test_short_read_refusal(self):
        with patch.object(E.os,'read',return_value=b''):
            with self.assertRaises(ValueError): E.sources(self.directory,self.digest)

    def test_path_replacement_during_read_refuses(self):
        original = E.os.read; changed = []
        def replace(fd,size):
            raw = original(fd,size)
            if raw and not changed:
                changed.append(True)
                path = self.directory/'native-sources.json'
                path.rename(self.directory/'old-manifest')
                path.write_bytes(raw)
            return raw
        with patch.object(E.os,'read',side_effect=replace):
            with self.assertRaises(ValueError): E.sources(self.directory,self.digest)

    def test_native_root_and_directory_symlink_refusal(self):
        with self.assertRaises(ValueError): E.sources(self.directory,self.digest,native=True)
        alias = self.directory/'alias'; alias.symlink_to(self.directory,target_is_directory=True)
        with self.assertRaises(ValueError): E.sources(alias,self.digest)

    def invoke(self,args):
        read_fd,write_fd = os.pipe(); saved = os.dup(1)
        try:
            os.dup2(write_fd,1); os.close(write_fd)
            with patch.object(E,'__file__',str(self.directory/'native-entry.py')):
                result = E.main(args)
        finally: os.dup2(saved,1); os.close(saved)
        try: raw = os.read(read_fd,8192)
        finally: os.close(read_fd)
        return result,raw

    def test_entry_defaults_to_not_run(self):
        code,raw = self.invoke(['--source-contract',self.digest,'--revision',REV])
        self.assertEqual(code,2)
        self.assertEqual(R.parse(raw,code,REV,self.digest)['execution'],'NOT_RUN')

    def test_darwin_native_request_refuses_before_setup(self):
        # Platform forced to Darwin even on a future portable Linux test runner.
        with patch.object(sys,'platform','darwin'):
            code,raw = self.invoke(['--source-contract',self.digest,'--revision',REV,'--execute-disposable-native'])
        value = R.parse(raw,code,REV,self.digest)
        self.assertEqual(value['prerequisites']['platform'],'FAIL')
        self.assertEqual(set(value['controls'].values()),{'NOT_RUN'})
        self.assertEqual(value['execution'],'NOT_RUN')

    def test_invalid_entry_has_only_fixed_failure(self):
        code,raw = self.invoke(['CANARY-PRIVATE-PATH'])
        self.assertEqual(code,1)
        self.assertNotIn(b'CANARY',raw)
        self.assertNotIn(str(self.directory).encode(),raw)
        self.assertEqual(json.loads(raw)['native'],'UNCONFIRMED')
        with self.assertRaises(R.Invalid): R.parse(raw,code,REV,self.digest)

    def test_short_public_write_cannot_pass(self):
        with patch.object(E.os,'write',return_value=1):
            with patch.object(E,'__file__',str(self.directory/'native-entry.py')):
                self.assertEqual(E.main(['--source-contract',self.digest,'--revision',REV]),1)


class Boundaries(unittest.TestCase):
    def test_fixed_arguments(self):
        args = ['--source-contract',DIGEST,'--revision',REV]
        self.assertEqual(E.arguments(args),(DIGEST,REV,False))
        for bad in ([],args+['/tmp'],args+['--execute-disposable-native','extra'],
                    ['--revision',REV,'--source-contract',DIGEST],
                    ['--source-contract','bad','--revision',REV]):
            with self.assertRaises(ValueError): E.arguments(bad)

    def test_lsm_unknown_and_incomplete_refuse(self):
        H.lsm_policy(b'capability,landlock,yama\n',b'unconfined\n','0')
        for raw,context,seccomp in ((b'capability,unknown\n',b'unconfined\n','0'),
                (b'capability,landlock,landlock\n',b'unconfined\n','0'),
                (b'capability,landlock\n',b'enforcing\n','0'),
                (b'capability,landlock\n',b'unconfined\n','2'),
                (b'capability\n',b'unconfined\n','0')):
            with self.assertRaises(P.Refused): H.lsm_policy(raw,context,seccomp)

    def test_execution_source_and_environment_refuse(self):
        env = dict(GITHUB_REPOSITORY=R.REPOSITORY,GITHUB_WORKFLOW='CLB-91 B Native Acceptance',
            GITHUB_EVENT_NAME='workflow_dispatch',GITHUB_SHA=REV,GITHUB_RUN_ATTEMPT='1',
            RUNNER_OS='Linux',RUNNER_ARCH='X64',GITHUB_RUN_ID='1234',
            GITHUB_WORKFLOW_REF=R.REPOSITORY+'/'+R.WORKFLOW+'@refs/heads/main')
        with patch.dict(os.environ,env,clear=True): H.execution(report())
        for key,value in (('GITHUB_SHA','x'*40),('GITHUB_RUN_ATTEMPT','2'),
                ('GITHUB_EVENT_NAME','push'),('LD_PRELOAD','CANARY'),('PYTHONPATH','CANARY')):
            changed = dict(env); changed[key] = value
            with patch.dict(os.environ,changed,clear=True):
                with self.assertRaises(P.Refused): H.execution(report())

    def test_no_program_network_or_dynamic_target_entry(self):
        # Source audit supplements tests; it is NOT kernel acceptance.
        for name in E.NAMES:
            tree = ast.parse((HERE/name).read_bytes())
            for node in ast.walk(tree):
                if isinstance(node,ast.Import):
                    self.assertFalse({'subprocess','socket'} & {a.name for a in node.names})
                if isinstance(node,ast.Call) and isinstance(node.func,ast.Attribute):
                    self.assertNotIn(node.func.attr,('execv','execve','system','popen','setns','chroot'))
        kernel = P.LinuxKernel.__new__(P.LinuxKernel)
        with self.assertRaises(P.Refused): kernel.call('unshare',0x10000000)
        with self.assertRaises(P.Refused): kernel.call('mount',None,b'/application',None,4096,None)
        with self.assertRaises(P.Refused): H.syscall(None,999)

    def test_real_source_contract(self):
        path = HERE/'native-sources.json'
        raw = path.read_bytes()
        self.assertEqual(set(E.sources(HERE,hashlib.sha256(raw).hexdigest())),set(E.NAMES))

    def test_refusal_attribution_not_unknown_exception(self):
        H.expect_refusal(lambda:P.require(False))
        with self.assertRaises(P.Refused): H.expect_refusal(lambda:None)
        with self.assertRaises(P.Refused): H.expect_refusal(lambda:(_ for _ in ()).throw(RuntimeError('CANARY')))

    def test_lifecycle_needs_reap_and_cleanup(self):
        value = H.Lifecycle(); value.spawned(123)
        self.assertFalse(value.confirmed())
        value.finished(None,True,False); self.assertFalse(value.confirmed())
        with self.assertRaises(P.Refused): value.finished(0,False,True)


class PortableLifecycle(unittest.TestCase):
    def test_actual_shared_memory_fork_ack(self):
        mailbox = H.Mailbox(); self.addCleanup(mailbox.close)
        received = []; state = H.Lifecycle()
        def tick():
            message = mailbox.receive()
            if message:
                received.append(message); mailbox.ack()
            return False
        P.supervise(lambda:mailbox.send('held',{}),timeout=1,cancelled=tick,observer=state)
        self.assertTrue(state.confirmed())
        self.assertEqual([v['event'] for v in received],['held'])
        with self.assertRaises(ChildProcessError): os.waitpid(state.pid,os.WNOHANG)

    def test_actual_timeout_and_cancel_reap(self):
        for cancel in (False,True):
            state = H.Lifecycle()
            with self.assertRaises(P.Refused):
                P.supervise(lambda:time.sleep(5),timeout=.04,
                            cancelled=lambda:cancel and state.pid is not None,observer=state)
            self.assertTrue(state.confirmed())
            self.assertTrue(state.outcome[1])
            self.assertTrue(os.WIFSIGNALED(state.outcome[0]))
            with self.assertRaises(ChildProcessError): os.waitpid(state.pid,os.WNOHANG)

    def test_cancel_before_spawn_has_no_owned_child(self):
        state = H.Lifecycle()
        with self.assertRaises(P.Refused):
            P.supervise(lambda:None,timeout=1,cancelled=lambda:True,observer=state)
        self.assertIsNone(state.pid)
        self.assertIsNone(state.outcome)

    def test_mailbox_nonce_bounds_and_missing_fields(self):
        for message in (dict(nonce='wrong',event='held',payload={}),
                        dict(nonce='unused',event='unknown',payload={}),{}):
            mailbox = H.Mailbox()
            try:
                raw = json.dumps(message).encode()
                mailbox.memory[16:16+len(raw)] = raw
                mailbox.memory[:8] = struct.pack('!II',1,len(raw))
                with self.assertRaises((P.Refused,R.Invalid)): mailbox.receive()
            finally: mailbox.close()
        for sequence,size in ((2,1),(1,8193),(1,0)):
            mailbox = H.Mailbox()
            try:
                mailbox.memory[:8] = struct.pack('!II',sequence,size)
                with self.assertRaises(P.Refused): mailbox.receive()
            finally: mailbox.close()

    def test_mailbox_repeated_sequence_not_duplicate_pass(self):
        mailbox = H.Mailbox()
        try:
            raw = json.dumps(dict(nonce=mailbox.nonce,event='held',payload={})).encode()
            mailbox.memory[16:16+len(raw)] = raw
            mailbox.memory[:8] = struct.pack('!II',1,len(raw))
            self.assertEqual(mailbox.receive()['event'],'held')
            self.assertIsNone(mailbox.receive())
        finally: mailbox.close()
        with self.assertRaises(ValueError): len(mailbox.memory)

    def fixture(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        base = Path(temp.name)/'fixture'; base.mkdir()
        value = H.Fixture(P.Principal(1701,2701,(2701,)),'portable')
        value.remember(str(base),True)
        return value,base

    def test_cleanup_only_owned_objects(self):
        fixture,base = self.fixture(); leaf = base/'own'; leaf.write_bytes(b'synthetic')
        fixture.remember(str(leaf))
        with patch.object(P,'FIXTURE',str(base)): fixture.cleanup()
        self.assertFalse(base.exists())

    def test_cleanup_unknown_object_fails_without_deleting_it(self):
        fixture,base = self.fixture(); unknown = base/'unknown'; unknown.write_bytes(b'CANARY')
        with patch.object(P,'FIXTURE',str(base)):
            with self.assertRaises(P.Refused): fixture.cleanup()
        self.assertEqual(unknown.read_bytes(),b'CANARY')

    def test_cleanup_substitution_does_not_follow_link(self):
        fixture,base = self.fixture(); leaf = base/'own'; leaf.write_bytes(b'own')
        fixture.remember(str(leaf)); leaf.unlink()
        outside = base.parent/'outside'; outside.write_bytes(b'CANARY')
        leaf.symlink_to(outside)
        with patch.object(P,'FIXTURE',str(base)):
            with self.assertRaises(P.Refused): fixture.cleanup()
        self.assertEqual(outside.read_bytes(),b'CANARY')
        self.assertTrue(leaf.is_symlink())


if __name__ == '__main__': unittest.main(verbosity=2)
