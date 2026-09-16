#!/usr/bin/env python3
"""CLB-91 disposable filesystem tests. Never connect to SSH/stage."""
import ast
import contextlib
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import pwd
import signal
import stat
import struct
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts/deploy' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


operation = load('stage-compose-mode-operation')
runner = load('stage-compose-mode-repair')


class OperationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='clb91-mode-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.assertFalse(self.root.is_relative_to(ROOT))
        self.paths = {'/': self.root}
        for key, parent, name, directory in operation.LAYOUT:
            path = self.paths[parent] / name
            self.paths[key] = path
            if directory:
                path.mkdir(mode=0o700)
            else:
                path.write_bytes(b'SYNTHETIC CONTENT\n' if key == 'file' else b'')
                path.chmod(0o664 if key == 'file' else 0o600)
        self.neighbor = self.paths['compose'] / 'neighbor'
        self.neighbor.write_bytes(b'NEIGHBOR')
        self.original_open, self.original_stat = os.open, os.fstat
        self.root_inode = self.root.stat().st_ino
        self.file_inode = self.paths['file'].stat().st_ino

    def invoke(self, *, chmod=None, meta=None, opened=None, acl=(), cancelled=lambda: False):
        allocated = []
        def opening(path, flags, *args, **kwargs):
            # Only filesystem location is redirected; full production flow runs.
            fd = self.original_open(self.root if path == '/' else path, flags, *args, **kwargs)
            allocated.append(fd)
            if opened:
                opened(path, fd)
            return fd
        def metadata(fd):
            value = self.original_stat(fd)
            fields = {key: getattr(value, key) for key in
                      ('st_dev','st_ino','st_uid','st_gid','st_mode','st_nlink','st_size','st_mtime_ns','st_ctime_ns')}
            if value.st_ino == self.root_inode:
                fields['st_uid'] = 0  # Synthetic root, never chown a real object.
            if meta:
                meta(fields)
            return SimpleNamespace(**fields)
        original_chmod = os.fchmod
        with patch.object(operation.os, 'open', side_effect=opening), \
             patch.object(operation.os, 'fstat', side_effect=metadata), \
             patch.object(operation.os, 'fchmod', side_effect=chmod or original_chmod) as writes, \
             patch.object(operation.os, 'read', side_effect=AssertionError('content read')), \
             patch.object(operation.os, 'listxattr', create=True, return_value=list(acl)), \
             patch.object(operation, 'check_filesystem'):
            answer = operation.repair(pwd.getpwuid(os.geteuid()).pw_name, cancelled)
            count = writes.call_count
            if count:
                self.assertEqual(writes.call_args.args[1], 0o600)
        for fd in allocated:
            with self.assertRaises(OSError):
                self.original_stat(fd)
        self.assertEqual(self.neighbor.read_bytes(), b'NEIGHBOR')
        for key in ('application_lock', 'operation_lock'):
            if self.paths[key].is_file():
                self.assertEqual(self.paths[key].read_bytes(), b'')
        return answer, count

    def test_invalid_mode_one_descriptor_write_and_no_other_change(self):
        before = operation.identity(self.paths['file'].stat())
        neighbor = operation.identity(self.neighbor.stat())
        self.assertEqual(self.invoke(), (('changed', None), 1))
        after = operation.identity(self.paths['file'].stat())
        self.assertEqual(before[:4], after[:4])
        self.assertEqual(before[5:8], after[5:8])
        self.assertEqual(stat.S_IMODE(after[4]), 0o600)
        self.assertEqual(self.paths['file'].read_bytes(), b'SYNTHETIC CONTENT\n')
        self.assertEqual(operation.identity(self.neighbor.stat()), neighbor)

    def test_valid_modes_zero_writes(self):
        for mode in (0o600, 0o644):
            with self.subTest(mode=mode):
                self.paths['file'].chmod(mode)
                self.assertEqual(self.invoke(), (('already_valid', None), 0))
                self.assertEqual(stat.S_IMODE(self.paths['file'].stat().st_mode), mode)

    def test_symlink_target(self):
        self.paths['file'].unlink()
        self.paths['file'].symlink_to(self.neighbor)
        self.assertEqual(self.invoke(), (('blocked', 'io'), 0))

    def test_nonregular_fifo_and_directory(self):
        self.paths['file'].unlink()
        os.mkfifo(self.paths['file'], 0o600)
        self.assertEqual(self.invoke(), (('blocked', 'identity'), 0))
        self.paths['file'].unlink()
        self.paths['file'].mkdir(mode=0o700)
        self.assertEqual(self.invoke(), (('blocked', 'identity'), 0))

    def test_hardlink(self):
        os.link(self.paths['file'], self.paths['compose'] / 'extra')
        self.assertEqual(self.invoke(), (('blocked', 'identity'), 0))

    def test_owner_and_device_mismatch(self):
        for field in ('st_uid', 'st_dev'):
            def mismatch(value):
                if value['st_ino'] == self.file_inode:
                    value[field] += 1
            with self.subTest(field=field):
                self.assertEqual(self.invoke(meta=mismatch), (('blocked', 'identity'), 0))

    def test_path_substitution_before_mutation(self):
        def replace(path, fd):
            if path == 'docker-compose.yml':
                self.paths['file'].rename(self.paths['compose'] / 'old')
                self.paths['file'].write_bytes(b'REPLACEMENT')
                self.paths['file'].chmod(0o664)
        self.assertEqual(self.invoke(opened=replace), (('blocked', 'identity'), 0))
        self.assertEqual(stat.S_IMODE((self.paths['compose'] / 'old').stat().st_mode), 0o664)
        self.assertEqual(stat.S_IMODE(self.paths['file'].stat().st_mode), 0o664)

    def test_chain_symlink_and_writable_directory(self):
        self.paths['compose'].chmod(0o770)
        self.assertEqual(self.invoke(), (('blocked', 'layout'), 0))
        self.paths['compose'].chmod(0o700)
        self.paths['opt'].rename(self.root / 'moved')
        self.paths['opt'].symlink_to(self.root / 'moved', target_is_directory=True)
        self.assertEqual(self.invoke(), (('blocked', 'io'), 0))

    def test_both_busy_locks(self):
        for key in ('application_lock', 'operation_lock'):
            fd = os.open(self.paths[key], os.O_RDONLY)
            try:
                operation.fcntl.flock(fd, operation.fcntl.LOCK_EX | operation.fcntl.LOCK_NB)
                self.assertEqual(self.invoke(), (('blocked', 'busy'), 0))
            finally:
                os.close(fd)

    def test_missing_and_malformed_locks_are_not_created_or_repaired(self):
        for key in ('application_lock', 'operation_lock'):
            path = self.paths[key]
            path.chmod(0o644)
            self.assertEqual(self.invoke(), (('blocked', 'layout'), 0))
            path.unlink()
            self.assertEqual(self.invoke(), (('blocked', 'io'), 0))
            self.assertFalse(path.exists())
            path.write_bytes(b'')
            path.chmod(0o600)

    def test_acl_and_capability_rejection(self):
        for name in ('system.posix_acl_access', 'system.posix_acl_default', 'system.nfs4_acl', 'security.capability'):
            self.assertEqual(self.invoke(acl=[name]), (('blocked', 'acl'), 0))
        with patch.object(operation.os, 'listxattr', create=True, side_effect=OSError('SECRET')):
            with self.assertRaises(operation.Blocked) as caught:
                operation.check_acl(123)
            self.assertEqual(caught.exception.args, ('acl',))

    def test_failed_chmod_is_ambiguous_without_retry(self):
        self.assertEqual(self.invoke(chmod=PermissionError('SECRET')), (('ambiguous', 'write'), 1))
        self.assertEqual(stat.S_IMODE(self.paths['file'].stat().st_mode), 0o664)

    def test_readback_mismatch_is_ambiguous_without_retry(self):
        self.assertEqual(self.invoke(chmod=lambda fd, mode: None), (('ambiguous', 'readback'), 1))

    def test_write_then_error_never_rolls_back(self):
        real_chmod = os.fchmod
        def uncertain(fd, mode):
            real_chmod(fd, mode)
            raise OSError('SECRET')
        self.assertEqual(self.invoke(chmod=uncertain), (('ambiguous', 'write'), 1))
        self.assertEqual(stat.S_IMODE(self.paths['file'].stat().st_mode), 0o600)

    def test_cancellation_before_and_during_write(self):
        self.assertEqual(self.invoke(cancelled=lambda: True), (('blocked', 'interrupted'), 0))
        recorded = [False]
        real_chmod = os.fchmod
        def cancel(fd, mode):
            real_chmod(fd, mode)
            recorded[0] = True
        self.assertEqual(self.invoke(chmod=cancel, cancelled=lambda: recorded[0]),
                         (('ambiguous', 'interrupted'), 1))

    def test_cleanup_error_after_write_is_ambiguous_and_all_fds_closed(self):
        original_close = os.close
        seen = []
        def close(fd):
            inode = self.original_stat(fd).st_ino
            seen.append(fd)
            original_close(fd)
            if inode == self.file_inode:
                raise OSError('SECRET')
        with patch.object(operation.os, 'close', side_effect=close):
            self.assertEqual(self.invoke(), (('ambiguous', 'cleanup'), 1))
        self.assertEqual(len(seen), len(set(seen)))
        self.assertEqual(len(seen), 1 + len(operation.LAYOUT))

    def test_substitution_after_write_cannot_mutate_replacement(self):
        real_chmod = os.fchmod
        def replace(fd, mode):
            real_chmod(fd, mode)
            self.paths['file'].rename(self.paths['compose'] / 'old')
            self.paths['file'].write_bytes(b'REPLACEMENT')
            self.paths['file'].chmod(0o664)
        self.assertEqual(self.invoke(chmod=replace), (('ambiguous', 'readback'), 1))
        self.assertEqual(stat.S_IMODE(self.paths['file'].stat().st_mode), 0o664)
        self.assertEqual(stat.S_IMODE((self.paths['compose'] / 'old').stat().st_mode), 0o600)

    def test_principal_rejected_before_open(self):
        with patch.object(operation.os, 'open') as opened:
            self.assertEqual(operation.repair('root', lambda: False), ('blocked', 'principal'))
            opened.assert_not_called()

    def test_filesystem_allowlist_and_unsupported_platform(self):
        class Call:
            def __call__(self, fd, buffer):
                operation.ctypes.c_long.from_buffer(buffer).value = self.magic
                return 0
        call = Call()
        with patch.object(operation.sys, 'platform', 'linux'), \
             patch.object(operation.platform, 'machine', return_value='x86_64'), \
             patch.object(operation.ctypes, 'CDLL', return_value=SimpleNamespace(fstatfs=call)):
            for magic in (0xef53, 0x58465342, 0x9123683e):
                call.magic = magic
                operation.check_filesystem(9)
            call.magic = 0x6969  # NFS: never assume flock/ACL parity.
            with self.assertRaises(operation.Blocked):
                operation.check_filesystem(9)
        with patch.object(operation.sys, 'platform', 'darwin'):
            with self.assertRaises(operation.Blocked):
                operation.check_filesystem(9)


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.env = dict(GITHUB_REPOSITORY='koteev-m/clubs_bot', GITHUB_EVENT_NAME='workflow_dispatch',
                        GITHUB_REF='refs/heads/main', GITHUB_REF_TYPE='branch', REPOSITORY_DEFAULT_BRANCH='main',
                        APP_ENV='stage', CONFIRMATION=runner.CONFIRMATION, GITHUB_RUN_ATTEMPT='1',
                        GITHUB_SHA='a'*40, GITHUB_WORKFLOW_REF='koteev-m/clubs_bot/' + runner.WORKFLOW_PATH + '@refs/heads/main',
                        COMPOSE_PATH='/opt/clubs-bot-stage', SSH_USER='synthetic', SSH_HOST='fixture.invalid', SSH_PORT='22')
        self.nonce = b'n'*32

    def frame(self, body):
        return b'clb91-mode-auth:v=1 tag=' + hmac.new(self.nonce, body, hashlib.sha256).hexdigest().encode() + b' ' + body

    def test_exact_output_contract_and_rejection(self):
        bodies = [(b'compose-mode-repair:v=1 result=changed\n', 0),
                  (b'compose-mode-repair:v=1 result=already_valid\n', 0)]
        bodies += [(f'compose-mode-repair:v=1 result=blocked reason={r}\n'.encode(), 1) for r in runner.BLOCKED]
        bodies += [(f'compose-mode-repair:v=1 result=ambiguous reason={r}\n'.encode(), 1) for r in runner.AMBIGUOUS]
        for body, code in bodies:
            self.assertEqual(runner.parse_result(self.frame(body), code, self.nonce), (body.decode().strip(), code))
        good = bodies[0][0]
        bad = [good, self.frame(good)*2, b'raw SECRET'+self.frame(good), self.frame(good)+b'extra',
               self.frame(good.replace(b'\n', b'\r\n')), self.frame(good[:-1]),
               self.frame(good.replace(b'changed', b'unknown')),
               self.frame(b'compose-mode-repair:v=1 result=blocked reason=SECRET\n')]
        for raw in bad:
            with self.assertRaises(ValueError):
                runner.parse_result(raw, 0, self.nonce)
        with self.assertRaises(ValueError):
            runner.parse_result(self.frame(good), 1, self.nonce)
        with self.assertRaises(ValueError):
            runner.parse_result(self.frame(good), 0, b'x'*32)

    def test_dispatch_boundary_and_rerun_rejection(self):
        runner.validate(self.env)
        for key in ('GITHUB_REPOSITORY','GITHUB_EVENT_NAME','GITHUB_REF','GITHUB_REF_TYPE',
                    'REPOSITORY_DEFAULT_BRANCH','APP_ENV','CONFIRMATION','GITHUB_RUN_ATTEMPT',
                    'GITHUB_SHA','GITHUB_WORKFLOW_REF'):
            with self.subTest(key=key), self.assertRaises(ValueError):
                runner.validate(dict(self.env, **{key:'unexpected'}))
        runner.validate_target(self.env)
        for key, value in (('SSH_USER','root'), ('SSH_USER','hookah-staging'), ('SSH_HOST','x;id'),
                           ('SSH_PORT','0'), ('COMPOSE_PATH','/tmp/other')):
            with self.assertRaises(ValueError):
                runner.validate_target(dict(self.env, **{key:value}))

    def test_hardened_ssh_options_match_production_corrected_runner(self):
        source = (ROOT / 'scripts/deploy/corrected-stage-release.py').read_text()
        tree = ast.parse(source)
        vectors = [n for n in ast.walk(tree) if isinstance(n, ast.List) and n.elts
                   and isinstance(n.elts[0], ast.Constant) and n.elts[0].value == 'ssh']
        self.assertEqual(len(vectors), 1)
        expected = eval(compile(ast.Expression(vectors[0]), '<ssh-parity>', 'eval'),
                        {'env': self.env, 'reference': '/PRIVATE_PIN', 'command': 'unused'})
        actual = runner.ssh_argv(self.env, '/PRIVATE_PIN')
        self.assertEqual(actual[:-1], expected[:-1])
        self.assertEqual(actual.count('ConnectionAttempts=1'), 1)
        self.assertIn('test "$(id -un)" = synthetic', actual[-1])
        self.assertIn('test "$(id -u)" != 0', actual[-1])
        self.assertNotIn('ssh-keyscan', actual)

    def test_one_transport_secret_isolation_and_no_retry_on_unknown_outcome(self):
        transport = runner.load_transport()
        for sig in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
            old = signal.getsignal(sig)
            self.addCleanup(signal.signal, sig, old)
        with tempfile.TemporaryFile() as pin:
            @contextlib.contextmanager
            def pinned(env):
                yield pin.fileno(), '/PRIVATE_PIN'
            with patch.object(runner, 'load_transport', return_value=transport), \
                 patch.object(runner, 'snapshot', return_value=b'fixed-source'), \
                 patch.object(transport, 'pinned_hosts', side_effect=pinned), \
                 patch.object(transport, 'capture_result', return_value=transport.CaptureResult(255, b'RAW SECRET')) as capture:
                line, code = runner.main(dict(self.env, PRIVATE_SECRET='SECRET'), [])
                self.assertEqual((line, code), ('compose-mode-repair:v=1 result=ambiguous reason=transport', 1))
                self.assertEqual(capture.call_count, 1)
                self.assertEqual(capture.call_args.kwargs['env'], {'LC_ALL':'C'})
                self.assertEqual(capture.call_args.kwargs['limit'], 256)

    def test_authenticated_result_published_only_after_pin_cleanup(self):
        transport = runner.load_transport()
        for sig in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
            self.addCleanup(signal.signal, sig, signal.getsignal(sig))
        with tempfile.TemporaryFile() as pin:
            cleanup_fails = [False]
            @contextlib.contextmanager
            def pinned(env):
                yield pin.fileno(), '/PRIVATE_PIN'
                if cleanup_fails[0]:
                    raise OSError('SECRET')
            def reply(argv, payload, **kwargs):
                body = b'compose-mode-repair:v=1 result=changed\n'
                nonce = payload[:32]
                raw = b'clb91-mode-auth:v=1 tag=' + hmac.new(nonce,body,hashlib.sha256).hexdigest().encode() + b' ' + body
                return transport.CaptureResult(0, raw)
            with patch.object(runner, 'load_transport', return_value=transport), \
                 patch.object(runner, 'snapshot', return_value=b'fixed-source'), \
                 patch.object(transport, 'pinned_hosts', side_effect=pinned), \
                 patch.object(transport, 'capture_result', side_effect=reply) as capture:
                self.assertEqual(runner.main(self.env, []), ('compose-mode-repair:v=1 result=changed',0))
                self.assertEqual(capture.call_count, 1)
                capture.reset_mock()
                cleanup_fails[0] = True
                self.assertEqual(runner.main(self.env, []), ('compose-mode-repair:v=1 result=ambiguous reason=transport',1))
                self.assertEqual(capture.call_count, 1)

    def test_bootstrap_authentication_real_python_without_remote_files(self):
        # Only the result provider is synthetic here; run the actual bootstrap.
        for result, reason in (('changed', None), ('already_valid', None), ('blocked', 'busy'), ('ambiguous', 'write')):
            source = f'def repair(principal, cancelled):\n return {result!r}, {reason!r}\n'.encode()
            control = json.dumps(dict(principal='synthetic', sha256=hashlib.sha256(source).hexdigest())).encode()
            payload = self.nonce + struct.pack('!I',len(control)) + control + source
            child = subprocess.run([sys.executable, '-I','-S','-B','-c',runner.BOOTSTRAP],
                                   input=payload, capture_output=True, timeout=3)
            self.assertEqual(child.stderr, b'')
            line, code = runner.parse_result(child.stdout, child.returncode, self.nonce)
            self.assertIn('result='+result, line)
            self.assertEqual(code, 0 if reason is None else 1)

    def test_remote_source_has_only_one_mutation_site_and_no_content_read(self):
        tree = ast.parse((ROOT / runner.REMOTE_PATH).read_text())
        calls = [n.func.attr for n in ast.walk(tree) if isinstance(n, ast.Call)
                 and isinstance(n.func, ast.Attribute) and isinstance(n.func.value, ast.Name)
                 and n.func.value.id == 'os']
        self.assertEqual(calls.count('fchmod'), 1)
        self.assertFalse(set(calls) & {'read','write','chmod','chown','fchown','unlink','rename','mkdir','system'})

    def test_git_snapshot_uses_dispatched_commit_not_working_file(self):
        with tempfile.TemporaryDirectory(prefix='clb91-mode-git-') as directory:
            root = Path(directory).resolve()
            self.assertFalse(root.is_relative_to(ROOT))
            subprocess.run(['git','init','--bare','-q',directory], check=True)
            def git(*args, data=None):
                return subprocess.check_output(['git','--git-dir='+directory, *args], input=data).strip()
            source = (ROOT / runner.REMOTE_PATH).read_bytes()
            blob = git('hash-object','-w','--stdin',data=source).decode()
            tree = git('mktree',data=f'100644 blob {blob}\tstage-compose-mode-operation.py\n'.encode()).decode()
            tree = git('mktree',data=f'040000 tree {tree}\tdeploy\n'.encode()).decode()
            tree = git('mktree',data=f'040000 tree {tree}\tscripts\n'.encode()).decode()
            sha = git('-c','user.name=fixture','-c','user.email=fixture@example.invalid','commit-tree',tree,data=b'local fixture\n').decode()
            git('update-ref','HEAD',sha)
            transport = runner.load_transport()
            with patch.object(runner,'ROOT',root):
                self.assertEqual(runner.snapshot(transport, dict(self.env,GITHUB_SHA=sha)), source)
                with self.assertRaises(ValueError):
                    runner.snapshot(transport, self.env)

    def test_local_source_import_excludes_bytecode_and_startup_paths(self):
        module = runner.load_transport()
        self.assertEqual(module.capture_result.__code__.co_filename,
                         str(ROOT/'scripts/deploy/corrected-stage-release.py'))
        self.assertEqual(module.pinned_hosts.__wrapped__.__code__.co_filename,
                         str(ROOT/'scripts/deploy/corrected-stage-release.py'))

    def test_workflow_exact_security_contract_and_negative_mutations(self):
        ruby = '''require "json"; require "validate-workflow-capabilities";
w,_=WorkflowCapabilityPolicy.load_workflow(Pathname.new(ARGV[0]),StageComposeModeWorkflow::PATH);
case ARGV[1]
when "path"; w["on"]["workflow_dispatch"]["inputs"]["path"]={"type"=>"string"}
when "environment"; w["jobs"]["repair"]["environment"]="prod"
when "concurrency"; w["concurrency"]["group"]="other"
when "credentials"; w["jobs"]["repair"]["steps"][1],w["jobs"]["repair"]["steps"][2]=w["jobs"]["repair"]["steps"][2],w["jobs"]["repair"]["steps"][1]
when "write"; w["permissions"]["contents"]="write"
when "command"; w["jobs"]["repair"]["steps"][-1]["run"]="arbitrary command"
end
StageComposeModeWorkflow.validate(WorkflowCapabilityPolicy,w)
'''
        for mutation in ('none','path','environment','concurrency','credentials','write','command'):
            child = subprocess.run(['ruby','-I',str(ROOT/'scripts'),'-e',ruby,str(ROOT),mutation],
                                   capture_output=True, text=True, timeout=10)
            self.assertEqual(child.returncode == 0, mutation == 'none', child.stderr)


if __name__ == '__main__':
    unittest.main(verbosity=2)
