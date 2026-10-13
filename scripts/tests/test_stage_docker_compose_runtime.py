#!/usr/bin/env python3
"""Synthetic gate controls, real unprivileged FDs; not a Linux runtime proof.

FixtureRuntime represents the already authenticated Runtime prerequisite. These
tests do not certify artifact bytes, execute Docker or replace the existing
Runtime integrity tests. Ownership/size metadata is explicitly synthetic; FD
acquisition, no-follow behavior, identity changes and attempted-all cleanup use
real disposable files owned by the test process.
"""
import copy
import io
import os
from pathlib import Path
import stat
import tempfile
import types
import unittest
from contextlib import redirect_stdout, redirect_stderr
from unittest.mock import patch

ROOT = globals().get('TEST_REPOSITORY', Path(__file__).resolve().parents[2])
SOURCE_ROOT = globals().get('TEST_SOURCE_ROOT', ROOT)


def source(path):
    namespace = {'__file__': str(path), '__name__': 'verified_test_source'}
    exec(compile(path.read_bytes(), str(path), 'exec'), namespace)
    return namespace


D = types.SimpleNamespace(**source(ROOT / 'scripts/deploy/stage-compose-diagnostic-operation.py'))
P = types.SimpleNamespace(**source(ROOT / 'scripts/deploy/stage-compose-env-file-plan.py'))
GATE_SOURCE = SOURCE_ROOT / 'scripts/deploy/stage-docker-compose-runtime.py'


class FixtureOS:
    """Exact fixed-path translation; no real system files or privileged writes."""
    def __init__(self, root):
        self.root, self.fds, self.closed, self.fail_close = root, {}, [], None
        self.uid, self.gid = os.geteuid(), os.getegid()
        self.fstat_failure = False
        self.short_file = None

    def __getattr__(self, key):
        return getattr(os, key)

    def physical(self, path):
        return self.root / str(path).lstrip('/')

    def logical(self, path, dir_fd=None):
        return str(path) if dir_fd is None else self.fds[dir_fd].rstrip('/') + '/' + str(path)

    def open(self, path, flags, mode=0o777, *, dir_fd=None):
        logical = self.logical(path, dir_fd)
        fd = os.open(self.physical(logical), flags, mode)
        self.fds[fd] = logical
        return fd

    def metadata(self, value, logical):
        result = {name: getattr(value, name) for name in
                  ('st_dev', 'st_ino', 'st_uid', 'st_gid', 'st_mode', 'st_nlink',
                   'st_size', 'st_mtime_ns', 'st_ctime_ns')}
        if not logical.startswith('/tmp/.clubs-bound-docker-'):
            result['st_uid'] = 0  # Synthetic root ownership, never native proof.
        elif not self.physical(logical).exists():
            result['st_nlink'] = 0  # Linux unlinked-directory fixture on Darwin.
        if logical == '/usr/bin/docker':
            result['st_size'] = 44320674
        elif logical == '/usr/libexec/docker/cli-plugins/docker-compose':
            result['st_size'] = 31557130
        return types.SimpleNamespace(**result)

    def fstat(self, fd):
        if self.fstat_failure:
            self.fstat_failure = False
            raise OSError('PRIVATE_FSTAT_CANARY')
        return self.metadata(os.fstat(fd), self.fds[fd])

    def stat(self, path, *, dir_fd=None, follow_symlinks=True):
        logical = self.logical(path, dir_fd)
        return self.metadata(os.stat(self.physical(logical), follow_symlinks=follow_symlinks), logical)

    def close(self, fd):
        self.closed.append(fd)
        if self.fail_close == fd:
            self.fail_close = None
            raise OSError('PRIVATE_CLOSE_CANARY')
        os.close(fd)
        self.fds.pop(fd, None)

    def mkdir(self, name, mode, *, dir_fd):
        os.mkdir(self.physical(self.logical(name, dir_fd)), mode)

    def rmdir(self, name, *, dir_fd):
        os.rmdir(self.physical(self.logical(name, dir_fd)))

    def read(self, fd, amount):
        if self.fds[fd] == self.short_file:
            return b''
        return os.read(fd, amount)

    def getuid(self):
        return self.uid

    def geteuid(self):
        return self.uid

    def getgid(self):
        return self.gid

    def getegid(self):
        return self.gid


class PipelineGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='clb91-runtime-fixture-')
        self.root = Path(self.temporary.name)
        for path in ('usr/bin', 'usr/local/bin', 'usr/local/lib/docker/cli-plugins',
                     'usr/local/libexec/docker/cli-plugins', 'usr/lib/docker/cli-plugins',
                     'usr/libexec/docker/cli-plugins', 'tmp', 'etc'):
            (self.root / path).mkdir(parents=True, exist_ok=True, mode=0o755)
        (self.root / 'tmp').chmod(0o1777)
        for path in ('usr/bin/docker', 'usr/libexec/docker/cli-plugins/docker-compose'):
            (self.root / path).write_bytes(b'public synthetic executable fixture')
            (self.root / path).chmod(0o755)
        self.fs = FixtureOS(self.root)
        self.passwd = f'root:x:0:0:root:/root:/bin/sh\nstage:x:{self.fs.uid}:{self.fs.gid}:Stage:/home/stage:/bin/sh\n'
        (self.root / 'etc/passwd').write_text(self.passwd)
        (self.root / 'etc/nsswitch.conf').write_text('# Public fixture\npasswd: files # only supported resolver\ngroup: files\n')
        self.gate = types.SimpleNamespace(**source(GATE_SOURCE))
        self.events, self.cancelled = [], False
        self.core, self.core_pattern = (-1, -1), b'core\n'
        self.profile = {'format': 1, 'contract': self.gate.CONTRACT,
            'runtime': {'format': 1, 'files': {self.gate.CLI: self.gate.CLI_SHA256,
                         self.gate.PLUGIN: self.gate.PLUGIN_SHA256}, 'aliases': {}},
            'cli': dict(path=self.gate.CLI, version='29.3.1', sha256=self.gate.CLI_SHA256, size=44320674),
            'compose': dict(path=self.gate.PLUGIN, version='5.1.1', sha256=self.gate.PLUGIN_SHA256, size=31557130)}
        self.safe_root = types.SimpleNamespace()
        fixture = self
        namespace = dict(D=D, P=P, S=self.safe_root, MANIFEST=self.profile['runtime'], COMPOSE=self.gate.PLUGIN)
        exec('def manifest(self): pass', namespace)
        class FixtureRuntime:
            FILE_BYTES, TOTAL_BYTES = 33554432, 134217728
            manifest = namespace['manifest']
            def __init__(self, cancelled):
                self.cancelled, self.held = cancelled, []
            def open(self):
                fixture.events.append('runtime')
                for path in (fixture.gate.CLI, fixture.gate.PLUGIN):
                    fd = fixture.fs.open(path, os.O_RDONLY | os.O_NOFOLLOW)
                    self.held.append((path, fd, D.identity(fixture.fs.fstat(fd))))
            def require_compatible(self):
                fixture.events.append('compatible')
            def private_root(self, path):
                fixture.events.append('private_root')
            def ruby_probe(self, path):
                fixture.events.append('ruby_probe')
            def recheck(self):
                fixture.events.append('runtime_recheck')
                for path, fd, value in self.held:
                    if D.identity(fixture.fs.fstat(fd)) != value or D.identity(fixture.fs.stat(path, follow_symlinks=False)) != value:
                        raise fixture.gate.GateUnavailable()
            def close(self):
                failed = False
                for _, fd, _ in reversed(self.held):
                    try:
                        fixture.fs.close(fd)
                    except OSError:
                        failed = True
                if failed:
                    raise P.Refused('cleanup')
        self.runtime_type = FixtureRuntime
        def limits(kind):
            self.events.append('core_read')
            return self.core
        def set_limits(kind, value):
            self.events.append('core_install')
            self.core = value
        def public_open(path, mode):
            self.assertEqual(path, '/proc/sys/kernel/core_pattern')
            self.assertEqual(mode, 'rb')
            return io.BytesIO(self.core_pattern)
        values = dict(D=D, E=types.SimpleNamespace(Runtime=FixtureRuntime), P=P, S=self.safe_root,
                      PROFILE=self.profile, os=self.fs,
                      resource=types.SimpleNamespace(RLIMIT_CORE=4, getrlimit=limits, setrlimit=set_limits),
                      open=public_open)
        self.module = self.gate.PipelineGate.__init__.__globals__
        self.module.update(values)
        self.instance = self.gate.PipelineGate(lambda: self.cancelled)
        self.environment = dict(PATH='/usr/local/bin:/usr/bin:/bin', HOME='/home/stage',
                                APP_SECRET='PRIVATE_ENV_CANARY', LC_ALL='C')

    def tearDown(self):
        try:
            self.instance.close()
        except Exception:
            pass
        for fd in tuple(self.fs.fds):
            try:
                os.close(fd)
            except OSError:
                pass
        self.temporary.cleanup()

    def initialize(self):
        self.instance.initial()

    def ready(self):
        self.initialize()
        environment = self.instance.bind(self.environment)
        self.assertEqual(self.instance.principal('stage'), 'stage')
        return environment

    def test_exact_environment_and_ordered_gate(self):
        environment = self.ready()
        self.assertLess(self.events.index('runtime'), self.events.index('core_install'))
        self.assertLess(self.events.index('core_install'), self.events.index('ruby_probe'))
        self.assertEqual(environment['APP_SECRET'], 'PRIVATE_ENV_CANARY')
        self.assertEqual(environment['PATH'], self.environment['PATH'])
        self.assertEqual(environment['HOME'], self.environment['HOME'])
        self.assertEqual(environment['DOCKER_HOST'], 'unix:///var/run/docker.sock')
        self.assertEqual(os.listdir(self.instance.config_fd), [])
        self.assertEqual(self.instance.command_environment(environment), environment)
        self.assertEqual(self.instance.runtime.FILE_BYTES, 67108864)
        self.assertEqual(self.instance.runtime.TOTAL_BYTES, 201326592)

    def test_unsupported_effective_locale_and_lookup_hooks_refuse_without_rewriting(self):
        for name, value in [('LANG', 'en_US.UTF-8'), ('LC_ALL', 'C.UTF-8'),
                            ('LOCPATH', 'PRIVATE_LOCALE_CANARY'), ('TERMINFO', 'PRIVATE_CANARY')]:
            with self.subTest(name=name):
                instance = self.gate.PipelineGate(lambda: False)
                instance.initial()
                environment = dict(self.environment, **{name: value})
                expected = dict(environment)
                with self.assertRaises(self.gate.GateUnavailable):
                    instance.bind(environment)
                self.assertEqual(expected, environment)
                self.assertIsNone(instance.config_fd)

    def test_alias_owner_and_no_follow_identity_refuse(self):
        self.profile['runtime']['aliases'] = {'/usr/bin/publicalias': '/usr/bin/docker'}
        real_stat = self.fs.stat
        (self.root / 'usr/bin/publicalias').symlink_to('docker')
        path = self.module['os'].path
        with patch.object(path, 'realpath', return_value='/usr/bin/docker'):
            original = real_stat('/usr/bin/publicalias', follow_symlinks=False)
            def wrong_owner(name, **kwargs):
                value = real_stat(name, **kwargs)
                if name == '/usr/bin/publicalias':
                    self.assertFalse(kwargs['follow_symlinks'])
                    value.st_uid = self.fs.uid
                return value
            with patch.object(self.fs, 'stat', side_effect=wrong_owner):
                with self.assertRaises(self.gate.GateUnavailable):
                    self.initialize()
            self.assertNotIn('ruby_probe', self.events)
            self.assertIsNone(self.instance.environment)
            self.assertTrue(self.instance.closed)

    def test_alias_inode_replacement_fails_recheck(self):
        self.ready()
        self.profile['runtime']['aliases'] = {'/usr/bin/publicalias': '/usr/bin/docker'}
        alias = self.root / 'usr/bin/publicalias'
        alias.symlink_to('docker')
        path = self.module['os'].path
        with patch.object(path, 'realpath', return_value='/usr/bin/docker'):
            self.instance.aliases()
            alias.rename(alias.with_name('held-alias'))
            alias.symlink_to('docker')
            with self.assertRaises(self.gate.GateUnavailable):
                self.instance.recheck()

    def test_missing_profile_never_reads_environment_or_starts_runtime(self):
        self.module['PROFILE'] = None
        with self.assertRaises(self.gate.GateUnavailable):
            self.initialize()
        self.assertEqual(self.events, [])
        self.assertIsNone(self.instance.environment)

    def test_source_runtime_mismatch_cannot_use_old_manifest(self):
        self.runtime_type.manifest.__globals__['MANIFEST'] = copy.deepcopy(self.profile['runtime'])
        with self.assertRaises(self.gate.GateUnavailable):
            self.initialize()
        self.assertEqual(self.events, [])

    def test_wrong_fixed_artifact_identity_refuses(self):
        for field in ('sha256', 'size', 'version', 'path'):
            with self.subTest(field=field):
                candidate = copy.deepcopy(self.profile)
                candidate['cli'][field] = 1 if field == 'size' else 'PRIVATE_PROFILE_CANARY'
                self.module['PROFILE'] = candidate
                instance = self.gate.PipelineGate(lambda: False)
                with self.assertRaises(self.gate.GateUnavailable):
                    instance.initial()
        self.assertEqual(self.events, [])

    def test_profile_cannot_widen_original_runtime_caps(self):
        self.runtime_type.FILE_BYTES = 67108864
        with self.assertRaises(self.gate.GateUnavailable):
            self.initialize()
        self.assertEqual(self.events, [])

    def test_boolean_format_is_not_profile_acceptance(self):
        self.profile['runtime']['format'] = True
        with self.assertRaises(self.gate.GateUnavailable):
            self.initialize()
        self.assertEqual(self.events, [])

    def test_pipe_core_policy_refuses_before_subprocess_probe(self):
        self.core_pattern = b'|/private/crash-handler %p\n'
        with self.assertRaises(self.gate.GateUnavailable):
            self.initialize()
        self.assertNotIn('ruby_probe', self.events)
        self.assertTrue(self.instance.closed)

    def test_core_policy_recheck_cannot_turn_failure_into_pass(self):
        self.ready()
        self.core = (-1, -1)
        with self.assertRaises(self.gate.GateUnavailable):
            self.instance.recheck()

    def test_environment_hooks_refuse_before_config_and_private_principal(self):
        self.initialize()
        self.environment['OTEL_EXPORTER_OTLP_ENDPOINT'] = 'PRIVATE_ENV_CANARY'
        with self.assertRaises(self.gate.GateUnavailable):
            self.instance.bind(self.environment)
        self.assertIsNone(self.instance.config_fd)
        self.assertIsNone(self.instance.principal_name)
        self.assertTrue(self.instance.closed)

    def test_ruby_loader_go_and_trace_hooks_refuse_without_rewriting(self):
        for name in ('RUBYOPT', 'RUBYLIB', 'GEM_HOME', 'BUNDLE_GEMFILE', 'LD_PRELOAD',
                     'DYLD_INSERT_LIBRARIES', 'GODEBUG', 'GOTRACEBACK', 'OTEL_TRACES_EXPORTER'):
            with self.subTest(name=name):
                instance = self.gate.PipelineGate(lambda: False)
                instance.initial()
                environment = dict(self.environment, **{name: 'PRIVATE_HOOK_CANARY'})
                original = dict(environment)
                with self.assertRaises(self.gate.GateUnavailable):
                    instance.bind(environment)
                self.assertEqual(environment, original)
                self.assertTrue(instance.closed)
                self.assertIsNone(instance.config_fd)

    def test_existing_removed_environment_keys_have_exact_filter(self):
        self.environment.update(DOCKER_CONTEXT='ignored', COMPOSE_FILE='ignored', PYTHONPATH='ignored', BASH_ENV='ignored')
        environment = self.ready()
        self.assertNotIn('DOCKER_CONTEXT', environment)
        self.assertNotIn('COMPOSE_FILE', environment)
        self.assertNotIn('PYTHONPATH', environment)
        self.assertNotIn('BASH_ENV', environment)
        self.assertEqual(environment['APP_SECRET'], self.environment['APP_SECRET'])

    def test_missing_home_or_path_and_nul_are_rejected(self):
        for change in ('HOME', 'PATH', 'nul'):
            with self.subTest(change=change):
                instance = self.gate.PipelineGate(lambda: False)
                instance.initial()
                environment = dict(self.environment)
                if change == 'nul':
                    environment['APP_SECRET'] = 'PRIVATE_ENV_CANARY\x00'
                else:
                    del environment[change]
                with self.assertRaises(self.gate.GateUnavailable):
                    instance.bind(environment)
                self.assertTrue(instance.closed)

    def test_earlier_path_executable_is_not_selected(self):
        (self.root / 'usr/local/bin/docker').write_bytes(b'PRIVATE_EXECUTABLE_CANARY')
        self.initialize()
        with self.assertRaises(self.gate.GateUnavailable):
            self.instance.bind(self.environment)

    def test_relative_or_writable_path_is_not_accepted(self):
        self.environment['PATH'] = '.:/usr/bin'
        self.initialize()
        with self.assertRaises(self.gate.GateUnavailable):
            self.instance.bind(self.environment)

    def test_writable_path_ancestor_is_not_accepted(self):
        (self.root / 'usr/local/bin').chmod(0o777)
        self.initialize()
        with self.assertRaises(self.gate.GateUnavailable):
            self.instance.bind(self.environment)

    def test_plugin_parent_symlink_is_not_followed(self):
        directory = self.root / 'usr/lib/docker/cli-plugins'
        directory.rmdir()
        directory.symlink_to('../../../libexec/docker/cli-plugins')
        self.initialize()
        with self.assertRaises(OSError):
            self.instance.bind(self.environment)

    def test_absent_earlier_plugin_directory_is_retained(self):
        directory = self.root / 'usr/local/lib/docker/cli-plugins'
        directory.rmdir()
        self.ready()
        directory.mkdir()
        with self.assertRaises(self.gate.GateUnavailable):
            self.instance.recheck()

    def test_plugin_precedence_cannot_select_earlier_system_binary(self):
        (self.root / 'usr/lib/docker/cli-plugins/docker-compose').write_bytes(b'PRIVATE_PLUGIN_CANARY')
        self.initialize()
        with self.assertRaises(self.gate.GateUnavailable):
            self.instance.bind(self.environment)
        self.assertIsNone(self.instance.environment)

    def test_new_earlier_path_candidate_is_detected(self):
        self.ready()
        (self.root / 'usr/local/bin/docker').write_bytes(b'PRIVATE_REPLACEMENT_CANARY')
        with self.assertRaises(self.gate.GateUnavailable):
            self.instance.recheck()

    def test_new_earlier_plugin_candidate_is_detected(self):
        self.ready()
        (self.root / 'usr/local/lib/docker/cli-plugins/docker-compose').write_bytes(b'PRIVATE_REPLACEMENT_CANARY')
        with self.assertRaises(self.gate.GateUnavailable):
            self.instance.recheck()

    def test_runtime_file_replacement_is_detected(self):
        self.ready()
        path = self.root / 'usr/bin/docker'
        replacement = path.with_name('replacement')
        replacement.write_bytes(b'PRIVATE_REPLACEMENT_CANARY')
        replacement.chmod(0o755)
        replacement.replace(path)
        with self.assertRaises(self.gate.GateUnavailable):
            self.instance.recheck()

    def test_account_principal_and_files_only_resolver(self):
        self.initialize()
        (self.root / 'etc/nsswitch.conf').write_text('passwd: files systemd\n')
        with self.assertRaises(self.gate.GateUnavailable):
            self.instance.principal('stage')
        self.assertIsNone(self.instance.principal_name)

    def test_control_principal_is_not_effective_principal_proof(self):
        self.initialize()
        with self.assertRaises(self.gate.GateUnavailable):
            self.instance.principal('otherstage')
        self.assertIsNone(self.instance.principal_name)

    def test_root_principal_is_always_refused(self):
        self.initialize()
        self.fs.uid = 0
        with self.assertRaises(self.gate.GateUnavailable):
            self.instance.principal('stage')

    def test_effective_uid_gid_and_duplicate_uid_are_not_authority(self):
        for content in (
            f'stage:x:9999:{self.fs.gid}:Stage:/home/stage:/bin/sh\n',
            f'stage:x:{self.fs.uid}:9999:Stage:/home/stage:/bin/sh\n',
            self.passwd + f'alias:x:{self.fs.uid}:{self.fs.gid}:Alias:/home/alias:/bin/sh\n'):
            with self.subTest(content='synthetic account variant'):
                (self.root / 'etc/passwd').write_text(content)
                instance = self.gate.PipelineGate(lambda: False)
                instance.initial()
                try:
                    with self.assertRaises(self.gate.GateUnavailable):
                        instance.principal('stage')
                    self.assertIsNone(instance.principal_name)
                finally:
                    instance.close()

    def test_account_file_mode_is_checked_before_read(self):
        self.initialize()
        (self.root / 'etc/passwd').chmod(0o666)
        with self.assertRaises(self.gate.GateUnavailable):
            self.instance.principal('stage')

    def test_passwd_short_read_is_not_complete_identity(self):
        self.initialize()
        self.fs.short_file = '/etc/passwd'
        with self.assertRaises(self.gate.GateUnavailable):
            self.instance.principal('stage')

    def test_passwd_nofollow_refuses_symlink(self):
        self.initialize()
        path = self.root / 'etc/passwd'
        path.unlink()
        path.symlink_to('nsswitch.conf')
        with self.assertRaises(OSError):
            self.instance.principal('stage')

    def test_account_identity_recheck_catches_mutation(self):
        self.ready()
        (self.root / 'etc/passwd').write_text(self.passwd + 'new:x:9999:9999:New:/none:/bin/false\n')
        with self.assertRaises(self.gate.GateUnavailable):
            self.instance.recheck()

    def test_changed_command_environment_is_refused(self):
        environment = self.ready()
        environment['APP_SECRET'] = 'PRIVATE_CHANGED_ENV_CANARY'
        with self.assertRaises(self.gate.GateUnavailable):
            self.instance.command_environment(environment)

    def test_missing_extra_environment_cannot_reuse_frozen_context(self):
        environment = self.ready()
        missing = dict(environment)
        del missing['APP_SECRET']
        extra = dict(environment, EXTRA_KEY='PRIVATE_EXTRA_ENV_CANARY')
        for candidate in (missing, extra):
            with self.subTest(kind='synthetic environment variant'):
                with self.assertRaises(self.gate.GateUnavailable):
                    self.instance.command_environment(candidate)

    def test_cancellation_remains_failure_after_positive_checks(self):
        self.ready()
        self.cancelled = True
        with self.assertRaises(P.Refused) as caught:
            self.instance.recheck()
        self.assertEqual(caught.exception.reason, 'interrupted')

    def test_acquired_fd_is_owned_before_fstat_failure(self):
        self.initialize()
        self.fs.fstat_failure = True
        with self.assertRaises(OSError):
            self.instance.bind(self.environment)
        self.assertTrue(self.instance.closed)
        self.assertEqual(self.fs.fds, {})

    def test_cleanup_attempts_all_leases_and_runtime_after_failure(self):
        self.ready()
        owned = [fd for _, fd, _ in self.instance.held]
        runtime = [fd for _, fd, _ in self.instance.runtime.held]
        self.fs.fail_close = owned[-1]
        with self.assertRaises(P.Refused) as caught:
            self.instance.close()
        self.assertEqual(caught.exception.reason, 'cleanup')
        self.assertTrue(set(owned + runtime).issubset(self.fs.closed))
        self.assertTrue(self.instance.closed)

    def test_private_data_never_printed_on_success_or_refusal(self):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            self.ready()
            with self.assertRaises(self.gate.GateUnavailable):
                self.instance.command_environment({'PRIVATE_KEY_CANARY': 'PRIVATE_VALUE_CANARY'})
        self.assertEqual(out.getvalue(), '')
        self.assertEqual(err.getvalue(), '')
        self.assertEqual(list((self.root / 'tmp').iterdir()), [])


if __name__ == '__main__':
    unittest.main()
