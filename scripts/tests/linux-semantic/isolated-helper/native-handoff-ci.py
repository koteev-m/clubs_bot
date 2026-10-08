#!/usr/bin/env python3
"""CLB-192 test-only native acceptance; reuse verified CLB-191 preparation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import signal
import stat
import sys
import tempfile
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
BOUND = 4096
MANIFEST = '7ec2c9354972024933677eaf752768820d17577f6fd5fd3491c2d7ce85b7b342'
STEPS = ('native', 'preparation', 'isolation', 'remove', 'explicit', 'different', 'empty',
         'controls', 'components', 'unsupported_backing', 'supervisor', 'runtime_refusal', 'cleanup')
SOURCE_PINS = {'helper.py': 'db2f01218abb739ec172d1c3ad037eb1efa2cd098db315b84d06dcaae73ea39e', 'handoff.py': '5e3f74917525585419eaaa12851092b3b3471097d512160ebcddfeed5e8baa54', 'run-handoff.py': '76df8eeff7c69293e906b9b12eb0bcf876ca54612211aaaef2f1513a797a8d97', 'test-handoff.py': '5d649fdb754b9bed52c0a49be1e83f5ceaf41bc21613929ef1d8d40fc60c3311', 'test-helper.py': '5de67287b385aea48bbab277b5eda732de74149e0c1c76c2bf8a640f7807bb46', 'native-ci.py': '0e2a3e1d0fda31faf7e2ce334d1600a87ea46ec54c004ef6481cc9399b5acd09', 'run-local.py': '981f942b50203dab69c39befa3928dee0f4cde2cd529c634888ae22d870f57dd'}


def need(value):
    if not value:
        raise ValueError('clb192_native_check_failed')


def source_bytes(root=HERE):
    need(root.is_absolute() and root.resolve() == root)
    captured = {}
    for name, digest in SOURCE_PINS.items():
        path = root/name
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        try:
            before = os.fstat(fd)
            need(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= 65536)
            raw = os.read(fd, 65537)
            identity = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
            need(identity(before) == identity(os.fstat(fd)) == identity(os.lstat(path)))
            need(len(raw) == before.st_size and hashlib.sha256(raw).hexdigest() == digest)
            captured[name] = raw
        finally:
            os.close(fd)
    return captured


def module(raw, path):
    scope = {'__name__': 'clb192_native_captured', '__file__': str(path)}
    exec(compile(raw, str(path), 'exec'), scope)
    return scope


def validate_evidence(data, expected=None):
    need(type(data) is dict and set(data) == {'format', 'verdict', 'sha', 'run_id', 'attempt',
        'native', 'image', 'manifest_sha256', 'sources', 'steps', 'cleanup'})
    need(type(data['format']) is int and data['format'] == 1)
    need(data['verdict'] in ('PASS', 'FAIL'))
    for key, pattern in (('sha', '[0-9a-f]{40}'), ('run_id', '[1-9][0-9]{0,19}'),
                         ('attempt', '1'), ('image', '(?:|sha256:[0-9a-f]{64})')):
        need(type(data[key]) is str and re.fullmatch(pattern, data[key]) is not None)
    need(data['manifest_sha256'] == MANIFEST and data['sources'] == SOURCE_PINS)
    need(type(data['steps']) is dict and set(data['steps']) == set(STEPS))
    need(all(type(v) is str and v in ('PASS', 'FAIL', 'NOT_RUN') for v in data['steps'].values()))
    need(data['native'] in ('PASS', 'FAIL', 'NOT_RUN') and data['native'] == data['steps']['native'])
    need(data['cleanup'] in ('PASS', 'FAIL', 'NOT_RUN') and data['cleanup'] == data['steps']['cleanup'])
    if data['verdict'] == 'PASS':
        need(all(v == 'PASS' for v in data['steps'].values()) and bool(data['image']))
    else:
        need('FAIL' in data['steps'].values())
    if expected is not None:
        need(all(data[key] == expected[key] for key in ('sha', 'run_id', 'attempt')))
    need(len(json.dumps(data).encode()) <= BOUND)


SUITE = r'''
import contextlib,io,runpy,unittest,pathlib
class Sink(io.StringIO):
 def __init__(self):super().__init__();self.count=0
 def write(self,value):
  self.count+=len(value.encode())
  if self.count>65536:raise ValueError('suite_output_bound')
  return len(value)
out,err,report=Sink(),Sink(),Sink()
with contextlib.redirect_stdout(out),contextlib.redirect_stderr(err):
 m=runpy.run_path(FILE,init_globals=GLOBALS)
 suite=unittest.TestSuite(unittest.defaultTestLoader.loadTestsFromTestCase(m[n]) for n in CLASSES)
 result=unittest.TextTestRunner(stream=report).run(suite)
assert result.wasSuccessful() and not result.skipped and result.testsRun==COUNT
assert out.count==0 and err.count==0
print('CLB192_SUITE_OK')
'''


def suite_code(file, classes, count, image=None, owner=None):
    return (SUITE.replace('FILE', repr(str(file))).replace('GLOBALS', repr(
        dict(CLB192_TEST_IMAGE=image, CLB192_TEST_OWNER=owner) if image else {})).replace('CLASSES', repr(classes)).replace('COUNT', str(count)))


def supervised_suite(op, launcher, control, code, env, owner, timeout=180, require_owned=False):
    """Reap daemon objects too when bounded host capture kills its process group."""
    need(re.fullmatch('[0-9a-f]{32}', owner) is not None)
    query = ['docker','ps','-aq','--filter','label=clb192.native_suite='+owner]
    try:
        return op.D.capture_result([sys.executable,'-I','-S','-B','-c',code],
                                   env=env, timeout=timeout, limit=4096)
    finally:
        def stopping(*unused):
            launcher['run_case'].__globals__['CANCELLED'] = True
        saved = {n: signal.signal(n, stopping) for n in launcher['WATCHED']}
        try:
            containers = control(query).decode().splitlines()
            if require_owned: need(bool(containers))
            need(len(containers) <= 16 and all(re.fullmatch('[0-9a-f]{12,64}', c) for c in containers))
            volumes = set()
            for container in containers:
                mounts = json.loads(control(['docker','inspect','--format','{{json .Mounts}}',container]))
                volumes.update(m['Name'] for m in mounts if m['Type']=='volume')
            need(all(re.fullmatch('[0-9a-f]{64}', v) for v in volumes))
            if containers: control(['docker','rm','-f','-v',*containers])
            need(not control(query))
            for volume in volumes:
                need(not control(['docker','volume','ls','-q','--filter','name=^'+volume+'$']))
        finally:
            for n, handler in saved.items(): signal.signal(n, handler)
        need(not launcher['run_case'].__globals__['CANCELLED'])


def supervisor_timeout_probe(op, launcher, control, env, export, image):
    owner = uuid.uuid4().hex
    name = 'clb192-timeout-'+owner
    argv = launcher['command'](name, export, 'unused', owner, 'remove', image=image)
    argv[2:2] = ['--label', 'clb192.native_suite='+owner]
    pos = argv.index('--entrypoint')
    argv[pos:] = ['--entrypoint','/bin/sleep',image,'60']
    code = ('import subprocess\nsubprocess.run('+repr(argv)+
            ',check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)\n'+
            'subprocess.run('+repr(['docker','start','-a',name])+
            ',check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)\n')
    result = supervised_suite(op, launcher, control, code, env, owner,
                              timeout=10, require_owned=True)
    need(result.failure == 'capture_timeout' and result.output == b'')


# The actual Runtime reads/hashes the actual runtime files against an intentionally
# wrong expected digest. No fake Runtime, platform override or reader call allowed.
REFUSAL = r'''
h,op=load()
name=next(iter(op.MANIFEST['files']))
op.MANIFEST['files'][name]='0'*64
runtime=op.Runtime(lambda:False)
op.Runtime=lambda cancelled:runtime
read=[False]
def forbidden_reader():
 read[0]=True
 raise AssertionError()
try: worker(h,op,'a'*32,forbidden_reader,lambda:False);raise AssertionError()
except op.RuntimeMismatch: pass
assert not read[0] and not runtime.held
assert runtime.evidence.statuses()['integrity']=='fail'
print('CLB192_RUNTIME_REFUSAL_OK')
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--prepared', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    captured = source_bytes()  # Whole test closure before its first exec.
    old = module(captured['native-ci.py'], HERE/'native-ci.py')
    maps = Path('/proc/self/maps').read_text() if sys.platform == 'linux' else ''
    old['native_guard'](platform.system(), platform.machine(), os.environ, maps)
    need(os.environ['GITHUB_RUN_ATTEMPT'] == '1')
    runner = Path(os.environ.get('RUNNER_TEMP', ''))
    prepared, output = Path(args.prepared), Path(args.output)
    need(runner.is_absolute() and runner.resolve() == runner)
    need(prepared == runner/'clb91-adapter-inputs' and prepared.resolve() == prepared)
    need(output == runner/'clb192-handoff' and not output.exists() and not output.is_symlink())
    output.mkdir(mode=0o700)
    record = dict(format=1, verdict='FAIL', sha=os.environ['GITHUB_SHA'],
        run_id=os.environ['GITHUB_RUN_ID'], attempt=os.environ['GITHUB_RUN_ATTEMPT'],
        native='PASS', image='', manifest_sha256=MANIFEST, sources=SOURCE_PINS,
        steps={n: 'NOT_RUN' for n in STEPS}, cleanup='NOT_RUN')
    record['steps']['native'] = 'PASS'
    step = 'preparation'
    launcher = module(captured['run-handoff.py'], HERE/'run-handoff.py')
    previous = {n: signal.getsignal(n) for n in launcher['WATCHED']}
    launcher['install_signals']()
    try:
        helper = module(captured['helper.py'], HERE/'helper.py')
        protocol = module(captured['handoff.py'], HERE/'handoff.py')
        op = helper['sources'](ROOT/'scripts/deploy')
        prep = module((old['PREP']/'prepare-native-inputs.py').read_bytes(), old['PREP']/'prepare-native-inputs.py')
        image = old['preparation'](old['read_json'](prepared/'evidence/status.json'), prep, os.environ)
        record['image'] = image
        env = dict(PATH='/usr/local/bin:/usr/bin:/bin', HOME=str(prepared/'home'),
                   DOCKER_CONFIG=str(prepared/'docker-config'), LC_ALL='C')
        def control(argv, timeout=10, limit=8192):
            result = op.D.capture_result(argv, env=env, timeout=timeout, limit=limit)
            need(result.failure is None and result.code == 0)
            return result.output.strip()
        need(control(['git', '-C', str(ROOT), 'rev-parse', 'HEAD']) == record['sha'].encode())
        need(control(['docker', 'info', '--format', '{{.Architecture}}']) in (b'x86_64', b'amd64'))
        need(control(['docker', 'image', 'inspect', image, '--format', '{{.Id}} {{.Architecture}}']) ==
             (image+' amd64').encode())
        record['steps'][step] = 'PASS'
        with tempfile.TemporaryDirectory(prefix='clb192-native-export-', dir=runner) as td:
            export = Path(td)
            export.chmod(0o755)
            for name, raw in captured.items():
                path = export/'scripts/tests/linux-semantic/isolated-helper'/name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(raw)
                path.chmod(0o444)
            for name, digest in helper['PINS'].items():
                raw = (ROOT/'scripts/deploy'/name).read_bytes()
                need(hashlib.sha256(raw).hexdigest() == digest)
                path = export/'scripts/deploy'/name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(raw)
                path.chmod(0o444)
            local = module(captured['run-local.py'], HERE/'run-local.py')
            def container(code, *, ext4=False, flag=None):
                name = 'clb192-native-check-'+uuid.uuid4().hex
                volumes = []
                try:
                    argv = old['worker_argv'](local, name, export, image, code)
                    argv[1] = 'create'
                    argv.remove('--rm')
                    if ext4:
                        pos = argv.index('/opt/clubs-bot-stage:uid=1000,gid=1000,mode=0700,nosuid,nodev,noexec')
                        argv[pos-1:pos+1] = ['--mount', 'type=volume,dst=/opt/clubs-bot-stage']
                    if flag:
                        pos = argv.index('--entrypoint')
                        argv[pos:pos] = ['--tmpfs', '/work/runner-temp:uid=1000,gid=1000,mode=0700,nosuid,nodev,noexec']
                        pos = argv.index('LC_ALL=C')+1
                        argv[pos:pos] = ['TMPDIR=/work/runner-temp', flag+'=1']
                    control(argv)
                    mounts = json.loads(control(['docker','inspect','--format','{{json .Mounts}}',name]))
                    volumes = [m['Name'] for m in mounts if m['Type']=='volume']
                    need(all(re.fullmatch('[0-9a-f]{64}', v) for v in volumes))
                    result = op.D.capture_result(['docker','start','-a',name], env=env, timeout=180, limit=4096)
                    state = control(['docker','inspect','--format','{{.State.ExitCode}}',name])
                    need(result.failure is None and result.code == 0 and state == b'0')
                    return result.output
                finally:
                    # Latch cancellation through cleanup without abandoning owned objects.
                    def stopping(*unused):
                        launcher['run_case'].__globals__['CANCELLED'] = True
                    saved = {n: signal.signal(n, stopping) for n in launcher['WATCHED']}
                    try:
                        control(['docker','rm','-f','-v',name])
                        need(not control(['docker','ps','-aq','--filter','name=^/'+name+'$']))
                        for volume in volumes:
                            need(not control(['docker','volume','ls','-q','--filter','name=^'+volume+'$']))
                    finally:
                        for n, handler in saved.items(): signal.signal(n, handler)
                    need(not launcher['run_case'].__globals__['CANCELLED'])
            step = 'isolation'
            need(container(old['PROBE']) == b'CLB191_ISOLATION_OK\n')
            record['steps'][step] = 'PASS'
            for step, outcome, strategy in [('remove','equivalent','remove'), ('explicit','equivalent','explicit'),
                                            ('different','different',None), ('empty','equivalent','remove')]:
                need(not launcher['run_case'].__globals__['CANCELLED'])
                result = launcher['run_case'](helper, protocol, op, export, captured['handoff.py'].decode(), step,
                                               image=image, docker_env=env)
                need(result == dict(case=step,result=outcome,strategy=strategy,cleanup='confirmed_absent'))
                record['steps'][step] = 'PASS'
            testfile = '/source/scripts/tests/linux-semantic/isolated-helper/test-handoff.py'
            for step, classes, count, ext4, flag in [
                    ('controls', ('Controls',), 9, False, 'CLB192_COMPONENT_TEST'),
                    ('components', ('Components',), 10, True, 'CLB192_COMPONENT_TEST'),
                    ('unsupported_backing', ('UnsupportedBacking',), 1, False, 'CLB192_CAPTURE_REFUSAL_TEST')]:
                need(container(suite_code(testfile, classes, count), ext4=ext4, flag=flag) == b'CLB192_SUITE_OK\n')
                record['steps'][step] = 'PASS'
            step = 'supervisor'
            owner = uuid.uuid4().hex
            hostcode = suite_code(export/'scripts/tests/linux-semantic/isolated-helper/test-handoff.py',
                                  ('DockerComponents',), 2, image, owner)
            scratch = export/'supervisor-scratch'
            scratch.mkdir(mode=0o700)
            hostenv = dict(env, CLB192_DOCKER_COMPONENT_TEST='1', TMPDIR=str(scratch))
            result = supervised_suite(op, launcher, control, hostcode, hostenv, owner)
            need(result.failure is None and result.code == 0 and result.output == b'CLB192_SUITE_OK\n')
            supervisor_timeout_probe(op, launcher, control, env, export, image)
            record['steps'][step] = 'PASS'
            step = 'runtime_refusal'
            code = "__name__='clb192_runtime_negative'\n"+captured['handoff.py'].decode()+REFUSAL
            need(container(code) == b'CLB192_RUNTIME_REFUSAL_OK\n')
            record['steps'][step] = 'PASS'
        step = 'cleanup'
        need(not export.exists() and not launcher['run_case'].__globals__['CANCELLED'])
        record['steps'][step] = record['cleanup'] = 'PASS'
        record['verdict'] = 'PASS'
    except BaseException:
        record['steps'][step] = 'FAIL'
        if step == 'cleanup': record['cleanup'] = 'FAIL'
    finally:
        for n, handler in previous.items(): signal.signal(n, handler)
    validate_evidence(record)
    (output/'result.json').write_text(json.dumps(record, sort_keys=True)+'\n')
    print(json.dumps(record, sort_keys=True))
    return 0 if record['verdict'] == 'PASS' else 1


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (ValueError, OSError):
        print('CLB192_NATIVE_PREFLIGHT_REFUSED')
        raise SystemExit(1)
