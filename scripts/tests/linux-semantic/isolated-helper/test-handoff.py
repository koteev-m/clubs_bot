#!/usr/bin/env python3
"""CLB-192 portable controls + explicit Linux component evidence (not native gate)."""
import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
p = runpy.run_path(str(HERE/'handoff.py'))
h = runpy.run_path(str(HERE/'helper.py'))
f = runpy.run_path(str(HERE/'test-helper.py'))
NONCE = 'a'*32


def launcher_module():
    module = runpy.run_path(str(HERE/'run-handoff.py'))
    # Only the native coordinator's verified preparation may supply this test
    # fixture override via runpy init_globals; no deployed CLI override exists.
    image = globals().get('CLB192_TEST_IMAGE')
    if image is not None:
        module['command'].__globals__['IMAGE'] = image
    owner = globals().get('CLB192_TEST_OWNER')
    if owner is not None:
        import re
        if not re.fullmatch('[0-9a-f]{32}', owner): raise ValueError('test_owner')
        original = module['command']
        def owned(*args, **kwargs):
            argv = original(*args, **kwargs)
            argv[2:2] = ['--label', 'clb192.native_suite='+owner]
            return argv
        module['run_case'].__globals__['command'] = owned
    return module


def request(op, case='remove'):
    old = json.loads(f['fixture'](op, case))
    return json.dumps(dict(format=1, request=NONCE,
        project=dict(name=p['PROJECT'], directory=p['CANONICAL']),
        **{k: old[k] for k in ('base', 'dotenv', 'override', 'interpolation')})).encode()


class Controls(unittest.TestCase):
    def setUp(self):
        self.op = h['sources'](ROOT/'scripts/deploy')

    def test_exact_helper_tamper_refuses_before_execution(self):
        import hashlib
        self.assertEqual(hashlib.sha256((HERE/'helper.py').read_bytes()).hexdigest(), p['HELPER_SHA256'])
        with tempfile.TemporaryDirectory() as td:
            path = Path(td)/'helper.py'
            path.write_bytes((HERE/'helper.py').read_bytes()+b'\nraise AssertionError("executed")\n')
            with patch.dict(p['load'].__globals__, ROOT=Path(td)):
                with self.assertRaises(p['Refused']): p['load']()
            path.unlink()
            path.symlink_to(HERE/'helper.py')
            with patch.dict(p['load'].__globals__, ROOT=Path(td)):
                with self.assertRaises(OSError): p['load']()

    def test_strict_request_fields_identity_bounds_and_present_empty(self):
        raw = request(self.op)
        valid = p['parse'](self.op, raw, NONCE)
        cases = [b'', raw[:-1], b'x'*(p['LIMIT']+1), b'[]',
                 raw.replace(b'"format": 1', b'"format": 1, "format": 1'),
                 json.dumps(dict(reversed(list(valid.items())))).encode()]
        for key in valid:
            altered = dict(valid)
            del altered[key]
            cases.append(json.dumps(altered).encode())
        for key, value in [('format', True), ('format', 2), ('request', 'b'*32),
                           ('request', 'bad'), ('extra', 1), ('dotenv', None),
                           ('dotenv', 'A='+'x'*65536), ('base', []), ('override', 'x'*4097),
                           ('project', {'name': 'clb191-synthetic', 'directory': p['CANONICAL']}),
                           ('project', {'name': p['PROJECT'], 'directory': '/tmp'}),
                           ('interpolation', {'PATH': 'CANARY'}),
                           ('interpolation', {'A': 'x'*65537}), ('interpolation', {'A': None})]:
            altered = dict(valid)
            altered[key] = value
            cases.append(json.dumps(altered).encode())
        cases.append(raw.replace(b'"name":', b'"name":"duplicate", "name":'))
        for index, data in enumerate(cases):
            with self.subTest(index=index), self.assertRaises(Exception):
                p['parse'](self.op, data, NONCE)
        valid['dotenv'] = ''
        self.assertEqual(p['parse'](self.op, json.dumps(valid).encode(), NONCE)['dotenv'], '')
        valid['interpolation'] = {'A': ''}
        self.assertEqual(p['parse'](self.op, json.dumps(valid).encode(), NONCE)['interpolation'], {'A': ''})

    def test_result_binding_canonical_fields_and_canary(self):
        for outcome, strategy in [('equivalent', 'remove'), ('equivalent', 'explicit'),
                                  ('different', None), ('unavailable', None)]:
            raw = p['public'](NONCE, outcome, strategy)
            self.assertEqual(p['response'](raw, NONCE), raw)
            for bad in [raw.replace(NONCE.encode(), b'b'*32), raw+b'x', raw.replace(b'"format":1',
                         b'"format":1,"extra":true'), b'CANARY', b'x'*513]:
                with self.assertRaises(Exception):
                    p['response'](bad, NONCE)

    def test_runtime_precedes_input_in_new_worker_and_cleanup_overrides_success(self):
        for stage in ('open', 'private_root', 'require_compatible', 'ruby_probe', 'recheck', 'close'):
            events = []
            class Runtime:
                def __init__(self, cancelled): pass
                def __getattr__(self, name):
                    def call(*args):
                        events.append(name)
                        if name == stage: raise RuntimeError('synthetic failure')
                    return call
            op = types.SimpleNamespace(Runtime=Runtime)
            def read():
                events.append('read')
                return b''
            # Ordering/cleanup fault injection only. Semantic tests below use real prepare().
            with patch.dict(p['worker'].__globals__, evaluate=lambda *args: p['public'](NONCE, 'equivalent', 'remove')):
                with self.assertRaises(RuntimeError):
                    p['worker'](h, op, NONCE, read, lambda: False)
            self.assertEqual(events[-1], 'close')
            if stage != 'close':
                self.assertNotIn('read', events)

    def test_unverified_capture_prerequisite_refuses_before_fixture_or_exchange(self):
        calls = []
        def refuse(op): raise p['Refused']()
        with patch.dict(p['produce'].__globals__, local_capture_trust=refuse,
                        fixture=lambda *args: calls.append('fixture')):
            raw = p['produce'](self.op, NONCE, 'remove', lambda raw: calls.append('exchange'), lambda: False)
        self.assertEqual(raw, p['public'](NONCE))
        self.assertEqual(calls, [])

    def test_fifo_refuses_regular_file_and_symlink_and_closes_fds(self):
        with tempfile.TemporaryDirectory() as td:
            parent = Path(td).resolve()
            regular = parent/'regular'
            regular.write_bytes(b'CANARY')
            link = parent/'link'
            link.symlink_to(regular)
            for path in (regular, link):
                with self.assertRaises(Exception): p['fifo_read'](str(path))
            fifo = parent/'request'
            os.mkfifo(fifo, 0o600)
            child = subprocess.Popen([sys.executable, '-I', '-S', '-B', '-c',
                'import os,sys; f=os.open(sys.argv[1],os.O_WRONLY); os.write(f,b"synthetic"); os.close(f)', str(fifo)])
            try:
                self.assertEqual(p['fifo_read'](str(fifo)), b'synthetic')
                self.assertEqual(child.wait(timeout=5), 0)
            finally:
                if child.poll() is None: child.kill()
                child.wait()

    def test_launcher_worker_cannot_mount_producer_originals_or_select_runtime(self):
        launcher = launcher_module()
        argv = launcher['command']('clb192-test', Path('/export'), 'code', NONCE, 'remove', transport='0'*64)
        for flag in ('--log-driver=none', '--network=none', '--read-only', '--cap-drop=ALL',
                     '--security-opt=no-new-privileges', '--user=1000:1000', '--pull=never'):
            self.assertIn(flag, argv)
        mounts = [argv[i+1] for i,v in enumerate(argv) if v == '--mount']
        self.assertEqual(mounts, ['type=bind,src=/export,dst=/source,readonly',
            'type=volume,src='+'0'*64+',dst=/handoff,readonly'])
        self.assertNotIn('code', argv)
        self.assertFalse(any('docker.sock' in v or 'seccomp=' in v or '--privileged' in v for v in argv))
        with self.assertRaises(ValueError):
            launcher['command']('test', Path('/export'), 'code', NONCE, 'remove', transport='/arbitrary')

    def test_cancellation_during_cleanup_cannot_publish_positive(self):
        import io
        import signal
        launcher = launcher_module()
        answer = p['public'](NONCE, 'equivalent', 'remove')
        mounts = [dict(Type='volume', Name='0'*64, Destination='/run/user/1000'),
                  dict(Type='volume', Name='1'*64, Destination=p['CANONICAL'])]
        signalled = []
        def capture(argv, **kwargs):
            output = b''
            if '{{json .Mounts}}' in argv: output = json.dumps(mounts).encode()
            elif '{{.State.ExitCode}}' in argv: output = b'0\n'
            elif argv[1:3] == ['start','-a']: output = answer
            elif argv[1] == 'rm' and not signalled:
                signalled.append(True)
                os.kill(os.getpid(), signal.SIGTERM)
            return self.op.D.CaptureResult(0, output)
        fake = types.SimpleNamespace(stdin=io.BytesIO(), stdout=io.BytesIO(), returncode=0,
                                     poll=lambda:0, wait=lambda **kwargs:0)
        # Only a cleanup ordering fault injection; real Docker TERM/HUP and real
        # normalization are independently exercised in DockerComponents.
        with tempfile.TemporaryDirectory() as td, patch.dict(launcher['run_case'].__globals__,
                CANCELLED=False, collect=lambda *args:answer,
                uuid=types.SimpleNamespace(uuid4=lambda:types.SimpleNamespace(hex=NONCE)),
                subprocess=types.SimpleNamespace(Popen=lambda *args,**kwargs:fake,
                    PIPE=subprocess.PIPE, DEVNULL=subprocess.DEVNULL)):
            result = launcher['run_case'](h,p,types.SimpleNamespace(D=types.SimpleNamespace(capture_result=capture)),
                Path(td),'synthetic code','remove', docker_env={'PATH': '/usr/bin:/bin'})
            self.assertTrue(launcher['run_case'].__globals__['CANCELLED'])
            self.assertEqual(result['result'], 'unavailable')
            self.assertEqual(result['cleanup'], 'confirmed_absent')
        self.assertEqual(signalled, [True])

    def test_parent_public_capture_bounded_and_child_reaped(self):
        launcher = launcher_module()
        for code, error in [('import os; os.read(0,512); os.write(1,b"x"*513)', ValueError),
                            ('import time; time.sleep(10)', TimeoutError)]:
            child = subprocess.Popen([sys.executable, '-I', '-S', '-B', '-c', code],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            try:
                with self.assertRaises(error): launcher['collect'](child, b'public', timeout=.2)
            finally:
                if child.poll() is None: child.kill()
                child.wait()
                child.stdin.close()
                child.stdout.close()


@unittest.skipUnless(sys.platform == 'linux' and os.environ.get('CLB192_COMPONENT_TEST') == '1',
                     'explicit Linux synthetic component suite; not Runtime/native acceptance')
class Components(unittest.TestCase):
    def setUp(self):
        self.op = h['sources'](ROOT/'scripts/deploy')
        self.before = set(os.listdir('/proc/self/fd'))
        self.exchanges = 0
        self.environment = patch.dict(os.environ, {'PATH': '/usr/bin:/bin', 'LC_ALL': 'C'}, clear=True)
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def tearDown(self):
        for path in Path(p['CANONICAL']).iterdir():
            if path.is_dir(): shutil.rmtree(path)
            else: path.unlink()
        self.assertEqual(set(os.listdir('/proc/self/fd')), self.before)

    def exchange(self, raw):
        # Separate actual process, owned anonymous stdin pipe, exact same parser,
        # planner and sealed memfd backend. Explicit component-only evaluation;
        # the executable worker's Runtime gate is never replaced or disabled.
        code = """import runpy,sys
p=runpy.run_path('/source/scripts/tests/linux-semantic/isolated-helper/handoff.py')
h,op=p['load']()
r=p['evaluate'](op,h['read_input'](),sys.argv[1])
import os
os.write(1,r)
"""
        result = self.op.D.capture_result([sys.executable, '-I', '-S', '-B', '-c', code, NONCE],
            payload=raw, env={'PATH': '/usr/bin:/bin', 'LC_ALL': 'C'}, timeout=15, limit=512)
        self.assertIsNone(result.failure)
        self.assertEqual(result.code, 0)
        self.assertNotIn(p['CANARY'].encode(), result.output)
        answer = p['response'](result.output, NONCE)
        self.exchanges += 1
        return answer

    def test_real_capture_pipe_process_planner_remove(self):
        self.assertEqual(p['produce'](self.op, NONCE, 'remove', self.exchange, lambda: False),
                         p['public'](NONCE, 'equivalent', 'remove'))

    def test_real_capture_pipe_process_planner_explicit(self):
        self.assertEqual(p['produce'](self.op, NONCE, 'explicit', self.exchange, lambda: False),
                         p['public'](NONCE, 'equivalent', 'explicit'))

    def test_real_capture_pipe_process_planner_different(self):
        self.assertEqual(p['produce'](self.op, NONCE, 'different', self.exchange, lambda: False),
                         p['public'](NONCE, 'different'))

    def test_real_capture_pipe_process_present_empty(self):
        self.assertEqual(p['produce'](self.op, NONCE, 'empty', self.exchange, lambda: False),
                         p['public'](NONCE, 'equivalent', 'remove'))

    def test_real_identity_drift_after_worker_denies_success(self):
        def drift(raw):
            answer = self.exchange(raw)
            (Path(p['CANONICAL'])/'.env').write_bytes(b'A=changed\n')
            return answer
        self.assertEqual(p['produce'](self.op, NONCE, 'remove', drift, lambda: False), p['public'](NONCE))
        self.assertEqual(self.exchanges, 1)

    def test_real_lock_path_drift_after_worker_denies_success(self):
        def drift(raw):
            answer = self.exchange(raw)
            lock = Path(p['CANONICAL'])/'.clubs-bot-release-state/application.lock'
            lock.unlink()
            lock.touch(mode=0o600)
            return answer
        self.assertEqual(p['produce'](self.op, NONCE, 'remove', drift, lambda: False), p['public'](NONCE))
        self.assertEqual(self.exchanges, 1)

    def test_capture_cancellation_and_worker_crash(self):
        for cancelled in (True, False):
            def crash(raw):
                result = self.op.D.capture_result([sys.executable, '-I', '-S', '-B', '-c',
                    'import os; os._exit(9)'], payload=raw, env={'PATH': '/usr/bin:/bin', 'LC_ALL': 'C'},
                    timeout=2, limit=512)
                return result.output
            answer = p['produce'](self.op, NONCE, 'remove', crash, lambda: cancelled)
            self.assertEqual(answer, p['public'](NONCE))
            for path in Path(p['CANONICAL']).iterdir():
                if path.is_dir(): shutil.rmtree(path)
                else: path.unlink()

    def test_real_locks_retained_until_worker_recheck_and_closed(self):
        lock = Path(p['CANONICAL'])/'.clubs-bot-release-state/application.lock'
        def exchange(raw):
            fd = os.open(lock, os.O_RDONLY)
            try:
                with self.assertRaises(BlockingIOError):
                    self.op.D.fcntl.flock(fd, self.op.D.fcntl.LOCK_EX | self.op.D.fcntl.LOCK_NB)
            finally:
                os.close(fd)
            return self.exchange(raw)
        self.assertEqual(p['produce'](self.op, NONCE, 'remove', exchange, lambda: False),
                         p['public'](NONCE, 'equivalent', 'remove'))
        fd = os.open(lock, os.O_RDONLY)
        try:
            self.op.D.fcntl.flock(fd, self.op.D.fcntl.LOCK_EX | self.op.D.fcntl.LOCK_NB)
        finally:
            os.close(fd)

    def test_actual_isolation_no_external_network_socket_or_source_writes(self):
        import socket
        p['local_capture_trust'](self.op)
        self.assertFalse(Path('/var/run/docker.sock').exists())
        self.assertNotIn('HOME', os.environ)
        for path in ('/source/clb192-write-forbidden', '/clb192-write-forbidden'):
            with self.assertRaises(OSError):
                fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                os.close(fd)
        with socket.socket() as sock:
            sock.settimeout(.2)
            with self.assertRaises(OSError): sock.connect(('203.0.113.1', 9))

    def test_cleanup_failure_suppresses_real_positive(self):
        original = self.op.D.ReadOnlyCapture.close
        def failed(context):
            original(context)
            raise OSError('synthetic cleanup failure')
        with patch.object(self.op.D.ReadOnlyCapture, 'close', failed):
            self.assertEqual(p['produce'](self.op, NONCE, 'remove', self.exchange, lambda: False), p['public'](NONCE))
        self.assertEqual(self.exchanges, 1)


@unittest.skipUnless(os.environ.get('CLB192_DOCKER_COMPONENT_TEST') == '1',
                     'explicit two-container FIFO component experiment; no Runtime acceptance')
class DockerComponents(unittest.TestCase):
    def test_supervisor_term_hup_remove_real_containers_and_volumes(self):
        import signal
        import time
        bindings = {k: globals()[k] for k in ('CLB192_TEST_IMAGE', 'CLB192_TEST_OWNER') if k in globals()}
        driver = ("import runpy,sys\n"+
            "tests=runpy.run_path("+repr(str(HERE/'test-handoff.py'))+",init_globals="+repr(bindings)+")\n"+
            "m=tests['launcher_module'](); original=m['run_case'].__globals__['command']\n"+
            "def slow(*args,**kwargs):\n"+
            " a=original(*args,**kwargs)\n"+
            " if kwargs.get('transport') is not None: a[a.index('/usr/bin/python3.12')+5]='import time; time.sleep(30)'\n"+
            " return a\n"+
            "m['main'].__globals__['command']=slow\n"+
            "sys.exit(m['main']())\n")
        for sig in (signal.SIGTERM, signal.SIGHUP):
            child = subprocess.Popen([sys.executable, '-I', '-S', '-B', '-c', driver],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            names, volumes = [], []
            def docker(*args):
                return subprocess.run(['docker', *args], capture_output=True, timeout=5, check=True).stdout
            try:
                deadline = time.monotonic()+15
                while time.monotonic() < deadline:
                    names = docker('ps', '--filter', 'label=clb192.supervisor='+str(child.pid),
                                   '--format', '{{.Names}}').decode().splitlines()
                    if len(names) == 2: break
                    if child.poll() is not None: break
                    time.sleep(.05)
                self.assertEqual(len(names), 2, 'producer and slow worker must actually run before cancellation')
                for name in names:
                    mounts = json.loads(docker('inspect', '--format', '{{json .Mounts}}', name))
                    volumes.extend(m['Name'] for m in mounts if m['Type']=='volume')
                child.send_signal(sig)
                time.sleep(.05)
                if child.poll() is None: child.send_signal(sig)
                out, err = child.communicate(timeout=15)
                self.assertEqual(child.returncode, 2)
                self.assertLessEqual(len(out), 2048)
                self.assertEqual(err, b'')
                record = json.loads(out)
                self.assertEqual(len(record['cases']), 1)
                self.assertEqual(record['cases'][0]['result'], 'unavailable')
                self.assertEqual(record['cases'][0]['cleanup'], 'confirmed_absent')
                self.assertEqual(docker('ps','-aq','--filter','label=clb192.supervisor='+str(child.pid)), b'')
                for volume in set(volumes):
                    self.assertEqual(docker('volume','ls','-q','--filter','name=^'+volume+'$'), b'')
            finally:
                if child.poll() is None: child.kill()
                child.wait()
                child.stdout.close()
                child.stderr.close()
                # Test failure cleanup remains scoped to this child supervisor.
                for name in names:
                    subprocess.run(['docker','rm','-f','-v',name], stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL, timeout=5)

    def test_two_containers_real_fifo_capture_planner_result_and_volume_cleanup(self):
        launcher = launcher_module()
        op = h['sources'](ROOT/'scripts/deploy')
        source = (HERE/'handoff.py').read_text()
        # Test-only component process: same capture and transport, direct real
        # evaluate() instead of Runtime execution. No source/guard is modified;
        # run-handoff.py has no flag/fallback selecting this test entrypoint.
        code = ("p={'__name__':'clb192_component'}; exec(compile("+repr(source)+
                ",'<clb192-component>','exec'),p)\n"+
                "import os,sys,json\n"+
                "if sys.argv[1]=='producer': sys.exit(p['main']())\n"+
                "h,op=p['load']()\n"+
                "r=p['evaluate'](op,p['fifo_read']('/handoff/request'),sys.argv[2])\n"+
                "os.write(1,r)\n"+
                "sys.exit(0 if json.loads(r)['result']=='equivalent' else 1)\n")
        with tempfile.TemporaryDirectory(prefix='clb192-component-export-') as td:
            export = Path(td).resolve()
            export.chmod(0o755)
            (export/'scripts/deploy').mkdir(parents=True)
            for name in h['PINS']:
                (export/'scripts/deploy'/name).write_bytes((ROOT/'scripts/deploy'/name).read_bytes())
            helper = export/'scripts/tests/linux-semantic/isolated-helper/helper.py'
            helper.parent.mkdir(parents=True)
            helper.write_bytes((HERE/'helper.py').read_bytes())
            for case, outcome, strategy in [('remove','equivalent','remove'),
                    ('explicit','equivalent','explicit'), ('different','different',None),
                    ('empty','equivalent','remove')]:
                with self.subTest(case=case):
                    result = launcher['run_case'](h, p, op, export, code, case)
                    self.assertEqual(result, dict(case=case, result=outcome, strategy=strategy,
                                                  cleanup='confirmed_absent'))
                    self.assertNotIn(p['CANARY'], json.dumps(result))
            for role in ('worker', 'producer'):
                # Real stderr noise is captured in the same bounded response;
                # it cannot be discarded and still yield a positive result.
                noise = "os.write(2,b'CLB192_STDERR_CANARY'); "
                changed = (code.replace('os.write(1,r)', noise+'os.write(1,r)') if role == 'worker' else
                           code.replace("if sys.argv[1]=='producer': ", "if sys.argv[1]=='producer': "+noise))
                (export/'clb192-entry.py').unlink()
                with self.subTest(stderr=role):
                    result = launcher['run_case'](h,p,op,export,changed,'remove')
                    self.assertEqual(result,dict(case='remove',result='unavailable',strategy=None,
                                                 cleanup='confirmed_absent'))


@unittest.skipUnless(sys.platform == 'linux' and os.environ.get('CLB192_CAPTURE_REFUSAL_TEST') == '1',
                     'explicit unsupported-backing fixture only')
class UnsupportedBacking(unittest.TestCase):
    def test_real_tmpfs_capture_refuses_without_snapshot_or_worker(self):
        op = h['sources'](ROOT/'scripts/deploy')
        before = set(os.listdir('/proc/self/fd'))
        called = []
        with patch.dict(os.environ, {'PATH': '/usr/bin:/bin', 'LC_ALL': 'C'}, clear=True):
            raw = p['produce'](op, NONCE, 'remove', lambda raw: called.append(True), lambda: False)
        self.assertEqual(raw, p['public'](NONCE))
        self.assertEqual(called, [])
        self.assertEqual(set(os.listdir('/proc/self/fd')), before)
        # Existing source is unchanged; the real mount (not a mocked result)
        # is unsupported and capture.open() must give the specific backing gate.
        import pwd
        context = op.D.ReadOnlyCapture(pwd.getpwuid(os.getuid()).pw_name, lambda: False)
        try:
            with self.assertRaises(op.D.Unavailable) as caught: context.open()
            self.assertEqual(caught.exception.args, ('backing',))
        finally:
            context.close()


if __name__ == '__main__':
    unittest.main(verbosity=2)
