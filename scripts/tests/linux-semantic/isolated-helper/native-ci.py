#!/usr/bin/env python3
"""CLB-191 test-only native coordinator; consumes existing verified preparation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import runpy
import signal
import sys
import tempfile
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
PREP = HERE.parent/'native-adapter/ci'
MANIFEST = '7ec2c9354972024933677eaf752768820d17577f6fd5fd3491c2d7ce85b7b342'
HELPER = 'db2f01218abb739ec172d1c3ad037eb1efa2cd098db315b84d06dcaae73ea39e'
STEPS = ('preparation', 'isolation', 'remove', 'explicit', 'different', 'components', 'context')
BOUND = 4096


def need(value):
    if not value:
        raise ValueError('clb191_check_failed')


def read_json(path, bound=65536):
    need(path.is_file() and not path.is_symlink() and path.stat().st_size <= bound)
    raw = path.read_bytes()
    need(len(raw) <= bound)
    def unique(pairs):
        result = {}
        for key, value in pairs:
            need(key not in result)
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=unique)


def native_guard(system, machine, env, maps):
    need((system, machine, env.get('RUNNER_ARCH'), env.get('GITHUB_EVENT_NAME')) ==
         ('Linux', 'x86_64', 'X64', 'workflow_dispatch'))
    need(len(maps) <= 65536 and not any(x in maps.lower() for x in ('rosetta', 'qemu')))
    need(re.fullmatch('[0-9a-f]{40}', env.get('GITHUB_SHA', '')) is not None)
    for name in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'):
        need(re.fullmatch('[1-9][0-9]{0,19}', env.get(name, '')) is not None)


def preparation(status, prep, env):
    prep['verify_sources']()
    need(status.get('verdict') == 'EXACT_NATIVE_INPUTS_PREPARED')
    need(status.get('source_pins_sha256') == prep['SOURCE_PINS'])
    need(status.get('rootfs_sha256') == prep['ROOTFS'])
    need(status.get('candidate_manifest_sha256') == prep['MANIFEST'])
    identity = status.get('run_identity', {})
    need(identity == {k: env[k] for k in ('GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT',
                                         'RUNNER_ARCH', 'GITHUB_EVENT_NAME')})
    reference = status.get('verified_reference', {})
    need(reference.get('input_lock_sha256') == prep['INPUTS'])
    need(reference.get('manifest_sha256') == MANIFEST)
    expected = hashlib.sha256((PREP/'reference/amd64-packages.txt').read_bytes()).hexdigest()
    need(reference.get('packages_sha256') == expected)
    image = status.get('reference_image', '')
    need(re.fullmatch('sha256:[0-9a-f]{64}', image) is not None)
    return image


def validate_evidence(data):
    need(type(data) is dict and set(data) == {'format', 'verdict', 'sha', 'run_id', 'attempt',
         'image', 'manifest_sha256', 'helper_sha256', 'steps'})
    need(type(data['format']) is int and data['format'] == 1)
    need(data['verdict'] in ('PASS', 'FAIL'))
    for key, pattern in (('sha', '[0-9a-f]{40}'), ('run_id', '[1-9][0-9]{0,19}'),
                         ('attempt', '[1-9][0-9]{0,19}'), ('image', '(?:|sha256:[0-9a-f]{64})')):
        need(type(data[key]) is str and re.fullmatch(pattern, data[key]) is not None)
    need(data['manifest_sha256'] == MANIFEST and data['helper_sha256'] == HELPER)
    need(type(data['steps']) is dict and set(data['steps']) == set(STEPS))
    need(all(type(v) is str and v in ('NOT_RUN', 'PASS', 'FAIL') for v in data['steps'].values()))
    if data['verdict'] == 'PASS':
        need(all(v == 'PASS' for v in data['steps'].values()) and bool(data['image']))
    need(len(json.dumps(data).encode()) <= BOUND)


PROBE = r'''
import hashlib,json,os,pathlib,platform,socket
p=pathlib.Path
assert platform.system()=='Linux' and platform.machine()=='x86_64'
maps=p('/proc/self/maps').read_text()
assert len(maps)<=65536 and not any(x in maps.lower() for x in ('rosetta','qemu'))
m=json.loads(p('/source/scripts/deploy/stage-compose-env-semantic-runtime.json').read_bytes())
assert len(m['files'])==191 and len(m['aliases'])==4
assert all(hashlib.sha256(p(n).read_bytes()).hexdigest()==v for n,v in m['files'].items())
assert all(os.path.realpath(n)==v for n,v in m['aliases'].items())
s=p('/proc/self/status').read_text()
assert os.getuid()==1000 and 'CapEff:\t0000000000000000' in s
assert 'NoNewPrivs:\t1' in s and 'Seccomp:\t2' in s
assert not p('/var/run/docker.sock').exists() and 'HOME' not in os.environ
for n in ('/clb191-write', '/source/clb191-write'):
 try: open(n,'wb'); raise AssertionError()
 except OSError: pass
s=socket.socket();s.settimeout(.5)
try: s.connect(('192.0.2.1',443));raise AssertionError()
except OSError: pass
finally:s.close()
print('CLB191_ISOLATION_OK')
'''

# Same existing tests, with bounded discard sinks; direct output must be empty.
# No mocked normalizer, skip or partial test run may produce the fixed success frame.
SUITE = r'''
import contextlib,io,os,pathlib,runpy,unittest
class Sink(io.StringIO):
 def __init__(self):super().__init__();self.count=0
 def write(self,value):
  self.count+=len(value.encode('utf-8'))
  if self.count>65536:raise ValueError('suite_output_bound')
  return len(value)
root='/source/scripts/tests/'
file=FILE
out,err,report=Sink(),Sink(),Sink()
with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
 module=runpy.run_path(root+file)
 classes=[v for v in module.values() if isinstance(v,type) and issubclass(v,unittest.TestCase) and v.__module__=='<run_path>']
 suite=unittest.TestSuite(unittest.defaultTestLoader.loadTestsFromTestCase(v) for v in classes)
 result=unittest.TextTestRunner(stream=report).run(suite)
assert result.wasSuccessful() and not result.skipped and result.testsRun==COUNT
assert out.count==0 and err.count==0
assert not list(pathlib.Path('/run/user/1000').iterdir())
assert not list(pathlib.Path('/opt/clubs-bot-stage').iterdir())
assert not list(pathlib.Path('/work/runner-temp').iterdir())
print('CLB191_SUITE_OK')
'''


def worker_argv(launcher, name, export, image, code, suite=False):
    argv = launcher['container_argv'](name, export, image, code)
    # Capture stderr too: a canary/traceback on either channel invalidates exact output.
    pos = argv.index('--entrypoint')
    argv[pos:] = ['--entrypoint', '/bin/sh', image, '-c', 'exec "$@" 2>&1',
                  'clb191-capture', '/usr/bin/env', *argv[pos+3:]]
    if suite:
        argv[2:2] = ['--tmpfs', '/work/runner-temp:uid=1000,gid=1000,mode=0700,nosuid,nodev,noexec']
        pos = argv.index('LC_ALL=C')+1
        argv[pos:pos] = ['TMPDIR=/work/runner-temp', 'RUNNER_TEMP=/work/runner-temp',
                        'CLB191_COMPONENT_TEST=1']
        argv[argv.index('PATH=/usr/bin:/bin')] = 'PATH=/usr/local/bin:/usr/bin:/bin'
    return argv


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--prepared', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    maps = Path('/proc/self/maps').read_text() if sys.platform == 'linux' else ''
    native_guard(platform.system(), platform.machine(), os.environ, maps)
    prepared = Path(args.prepared)
    runner_temp = Path(os.environ.get('RUNNER_TEMP', ''))
    need(runner_temp.is_absolute() and runner_temp.resolve() == runner_temp)
    need(prepared == runner_temp/'clb91-adapter-inputs' and prepared.resolve() == prepared)
    output = Path(args.output)
    need(output == runner_temp/'clb191-isolated-helper')
    need(output.is_absolute() and not output.exists() and not output.is_symlink())
    need(output.parent.is_dir() and output.parent.resolve() == output.parent)
    output.mkdir(mode=0o700)
    record = dict(format=1, verdict='FAIL', sha=os.environ['GITHUB_SHA'],
        run_id=os.environ['GITHUB_RUN_ID'], attempt=os.environ['GITHUB_RUN_ATTEMPT'], image='',
        manifest_sha256=MANIFEST, helper_sha256=HELPER, steps={n:'NOT_RUN' for n in STEPS})
    step = 'preparation'
    def stop(*unused):
        raise InterruptedError()
    previous = {n: signal.signal(n, stop) for n in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP)}
    try:
        helper = runpy.run_path(str(HERE/'helper.py'))
        need(hashlib.sha256((HERE/'helper.py').read_bytes()).hexdigest() == HELPER)
        op = helper['sources'](ROOT/'scripts/deploy')
        launcher = runpy.run_path(str(HERE/'run-local.py'))
        fixtures = runpy.run_path(str(HERE/'test-helper.py'))
        prep = runpy.run_path(str(PREP/'prepare-native-inputs.py'))
        image = preparation(read_json(Path(args.prepared)/'evidence/status.json'), prep, os.environ)
        record['image'] = image
        # No ambient Docker endpoint/config/credentials; reuse preparation's empty CLI home.
        env = dict(PATH='/usr/local/bin:/usr/bin:/bin', HOME=str(Path(args.prepared)/'home'),
                   DOCKER_CONFIG=str(Path(args.prepared)/'docker-config'), LC_ALL='C')
        def control(argv):
            result = op.D.capture_result(argv, env=env, timeout=10, limit=4096)
            need(result.failure is None and result.code == 0)
            return result.output.strip()
        need(control(['docker','info','--format','{{.Architecture}}']) in (b'x86_64',b'amd64'))
        need(control(['docker','image','inspect',image,'--format','{{.Id}} {{.Architecture}}']) ==
             (image+' amd64').encode())
        record['steps'][step] = 'PASS'
        with tempfile.TemporaryDirectory(prefix='clb191-export-', dir=output.parent) as td:
            export = Path(td); export.chmod(0o755)
            files = ['scripts/deploy/'+n for n in helper['PINS']]
            files += ['scripts/tests/test_stage_compose_env_file_plan.py',
                      'scripts/tests/test_stage_compose_env_file_context.py']
            files += ['scripts/tests/linux-semantic/isolated-helper/'+n for n in
                      ('helper.py','run-local.py','test-helper.py')]
            for relative in files:
                target=export/relative;target.parent.mkdir(parents=True,exist_ok=True)
                target.write_bytes((ROOT/relative).read_bytes());target.chmod(0o444)
            def worker(code, payload=b'', suite=False):
                name='clb191-native-'+uuid.uuid4().hex
                try:
                    return op.D.capture_result(worker_argv(launcher,name,export,image,code,suite),
                        payload=payload, env=env, timeout=180 if suite else 45, limit=4096)
                finally:
                    ids=control(['docker','ps','-aq','--filter','name=^/'+name+'$'])
                    if ids:
                        need(re.fullmatch(b'[0-9a-f]{12,64}',ids) is not None)
                        control(['docker','rm','-f',name])
                    need(not control(['docker','ps','-aq','--filter','name=^/'+name+'$']))
            step='isolation'; result=worker(PROBE)
            need(result.failure is None and result.code==0 and result.output==b'CLB191_ISOLATION_OK\n')
            record['steps'][step]='PASS'
            code=(HERE/'helper.py').read_text()
            for step in ('remove','explicit','different'):
                result=worker(code,fixtures['fixture'](op,step))
                summary=launcher['summarize'](step,result)
                need(summary['different'] if step=='different' else summary['equivalent'])
                record['steps'][step]='PASS'
            for step,file,count in [('components','linux-semantic/isolated-helper/test-helper.py',8),
                                    ('context','test_stage_compose_env_file_context.py',34)]:
                code=SUITE.replace('FILE',repr(file)).replace('COUNT',str(count))
                result=worker(code,suite=True)
                need(result.failure is None and result.code==0 and result.output==b'CLB191_SUITE_OK\n')
                record['steps'][step]='PASS'
        record['verdict']='PASS'
    except BaseException:
        record['steps'][step]='FAIL'
    finally:
        for number, handler in previous.items():
            signal.signal(number, handler)
    validate_evidence(record)
    (output/'result.json').write_text(json.dumps(record,sort_keys=True)+'\n')
    print(json.dumps(record,sort_keys=True))
    return 0 if record['verdict']=='PASS' else 1


if __name__=='__main__':
    try:
        raise SystemExit(main())
    except (ValueError, OSError):
        print('CLB191_NATIVE_PREFLIGHT_REFUSED')
        raise SystemExit(1)
