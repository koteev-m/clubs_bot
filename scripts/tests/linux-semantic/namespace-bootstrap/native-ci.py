"""Manual native-only CLB-195 test; no stage credentials or production entrypoint."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import tempfile

HERE = Path(__file__).resolve().parent
BOUND = 4096
CONTROLS = ('positive', 'wrong_uid', 'symlink', 'runtime_hash', 'actual_tmpfs_backing')
PHASES = ('prior', 'source', 'portable', 'runtime', 'build', 'core', 'capture', 'cleanup', 'complete')
C_STAGES = ('unclassified', 'source_load', 'materialize', 'compile_timeout', 'compile_nonzero',
            'core_timeout', 'core_exit_nonzero', 'core_stderr_nonempty',
            'core_stdout_bounds', 'summary_parse', 'summary_schema',
            'summary_failed', 'summary_namespace')


def need(value):
    if not value:
        raise ValueError('clb195_native_refused')


def validate_evidence(data, identity=None):
    need(set(data) == {'format','verdict','sha','run_id','attempt','sources','build','steps','cleanup','phase','native'})
    need(type(data['format']) is int and data['format'] == 1)
    need(data['verdict'] in ('PASS','FAIL'))
    need(re.fullmatch('[0-9a-f]{40}', data['sha']) is not None)
    for key in ('run_id','attempt'):
        need(re.fullmatch('[1-9][0-9]{0,19}', data[key]) is not None)
    need(data['attempt'] == '1')
    if identity is not None:
        need(all(data[k] == identity[k] for k in ('sha','run_id','attempt')))
    need(set(data['steps']) == {'prior','portable','core','capture'})
    need(all(v in ('PASS','NOT_RUN') for v in data['steps'].values()))
    need(data['cleanup'] in ('NOT_RUN','PASS','UNKNOWN'))
    need(data['phase'] in PHASES)
    need(type(data['native']) is dict and set(data['native']) == set(CONTROLS))
    need(all(value in ('PASS', 'NOT_RUN') for value in data['native'].values()))
    need(type(data['sources']) is dict and set(data['sources']) == {'build.py','bootstrap.py','namespace.h','native-ci.py','test-bootstrap.py'})
    for name, value in data['sources'].items():
        need(value == hashlib.sha256((HERE/name).read_bytes()).hexdigest())
    need(type(data['build']) is dict and set(data['build']) <= {'adapter','fixture'})
    need(all(re.fullmatch('[0-9a-f]{64}', v) for v in data['build'].values()))
    if data['verdict'] == 'PASS':
        need(all(v == 'PASS' for v in data['steps'].values()) and data['cleanup'] == 'PASS')
        need(set(data['build']) == {'adapter','fixture'})
        need(data['phase'] == 'complete' and all(v == 'PASS' for v in data['native'].values()))


def native_result(report, exit_code):
    """Do not accept missing/empty controls or truthy non-boolean values."""
    need(exit_code == 0 and report.get('verdict') == 'PASS')
    need(report.get('cleanup') == 'confirmed' and report.get('cleanup_error') is False)
    controls = report['negative_controls']
    need(type(controls) is dict and set(controls) == set(CONTROLS[1:]))
    need(all(value is True for value in controls.values()))
    adapter = report['adapter_result']
    need(adapter['primary'] == 'semantic_complete' and adapter['exit'] == 0)
    need(adapter['original_recheck'] is True and adapter['view_held_fd_identity'] is True)
    need(adapter['cleanup_error'] is False)
    return {name:'PASS' for name in CONTROLS}


def portable_result(answer, exit_code, available):
    """Return only checked fixed test names and C stage; never child stderr."""
    match = re.fullmatch(rb'CLB195_PORTABLE_V2 status=(PASS|FAIL) tests=14 '
                         rb'failed=(none|test_[a-z_]+(?:,test_[a-z_]+)*) '
                         rb'c_stage=([a-z_]+)\n', answer)
    if match is None:
        return False, 'UNKNOWN', 'UNKNOWN'
    stage = match[3].decode('ascii')
    if match[1] == b'PASS':
        if stage != 'none':
            return False, 'UNKNOWN', 'UNKNOWN'
        return exit_code == 0 and match[2] == b'none', 'none', 'none'
    names = match[2].decode('ascii').split(',')
    if (exit_code == 0 or names == ['none'] or len(names) != len(set(names)) or
            set(names) - available or
            (stage in C_STAGES) != ('test_generated_common_c_core_real_lifecycle' in names) or
            stage not in C_STAGES + ('none',)):
        return False, 'UNKNOWN', 'UNKNOWN'
    return False, ','.join(names), stage


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--prepared', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    build = runpy.run_path(str(HERE/'build.py'))
    path = build['NATIVE']/'tests/native-driver.py'
    raw = build['checked'](path,build['PINS']['tests/native-driver.py'])
    old = {'__file__':str(path),'__name__':'clb195_native_driver'}
    exec(compile(raw,str(path),'exec'),old)
    old['native_guard'](True)
    need(not any(x in Path('/proc/self/maps').read_text().lower() for x in ('rosetta','qemu')))
    runner = Path(os.environ['RUNNER_TEMP'])
    need(runner.is_absolute() and runner.resolve() == runner)
    prepared, output = Path(args.prepared), Path(args.output)
    need(prepared == runner/'clb91-adapter-inputs' and prepared.resolve() == prepared)
    need(output == runner/'clb195-namespace' and not output.exists() and not output.is_symlink())
    need(os.environ['GITHUB_RUN_ATTEMPT'] == '1')
    record = dict(format=1, verdict='FAIL', sha=os.environ['GITHUB_SHA'], run_id=os.environ['GITHUB_RUN_ID'],
                  attempt=os.environ['GITHUB_RUN_ATTEMPT'], sources={n:build['sha']((HERE/n).read_bytes())
                  for n in ('build.py','bootstrap.py','namespace.h','native-ci.py','test-bootstrap.py')},
                  build={}, steps={n:'NOT_RUN' for n in ('prior','portable','core','capture')}, cleanup='NOT_RUN',
                  phase='prior', native={n:'NOT_RUN' for n in CONTROLS})
    output.mkdir(mode=0o700)
    fixture = None
    inventory = None
    commands = []
    privileged = False
    portable_failed = 'UNKNOWN'
    portable_stage = 'UNKNOWN'
    try:
        previous = runpy.run_path(str(HERE.parent/'isolated-helper/native-handoff-ci.py'))
        oldhelper = runpy.run_path(str(HERE.parent/'isolated-helper/native-ci.py'))
        prior = oldhelper['read_json'](runner/'clb192-handoff/result.json',4096)
        previous['validate_evidence'](prior, {k:record[k] for k in ('sha','run_id','attempt')})
        need(prior['verdict'] == 'PASS')
        status = oldhelper['read_json'](prepared/'evidence/status.json')
        need(status['verdict'] == 'EXACT_NATIVE_INPUTS_PREPARED')
        need(status['reference_image'] == prior['image'])
        need(all(status['run_identity'][env] == record[key] for key,env in
                 [('sha','GITHUB_SHA'),('run_id','GITHUB_RUN_ID'),('attempt','GITHUB_RUN_ATTEMPT')]))
        record['steps']['prior'] = 'PASS'
        record['phase'] = 'source'
        capture = old['capture']
        repo = HERE.parents[3]
        need(capture(['/usr/bin/git','-C',str(repo),'rev-parse','HEAD'],10,commands).strip().decode() == record['sha'])
        for name,digest in record['sources'].items():
            relative = str((HERE/name).relative_to(repo))
            blob = capture(['/usr/bin/git','-C',str(repo),'show',record['sha']+':'+relative],10,commands)
            need(build['sha'](blob) == digest)
        record['phase'] = 'portable'
        answer = capture(['/usr/bin/python3','-I','-S','-B',str(HERE/'test-bootstrap.py')],90,commands,
                         require_exit=False)
        portable_source = build['checked'](HERE/'test-bootstrap.py',record['sources']['test-bootstrap.py'])
        available = set(re.findall(rb'^\s+def (test_[a-z_]+)\(', portable_source, re.M))
        passed, portable_failed, portable_stage = portable_result(
            answer, commands[-1].get('exit'), {name.decode('ascii') for name in available})
        need(passed)
        record['steps']['portable'] = 'PASS'
        record['phase'] = 'runtime'
        raw, entries = old['validate_tar'](prepared/'runtime/native/rootfs.tar')
        with tempfile.TemporaryDirectory(prefix='clb195-build-',dir=runner) as td:
            tree = Path(td)/'source'
            record['phase'] = 'build'
            build['materialize'](tree, old,record['sources'])
            flags = ['/usr/bin/gcc','-std=c11','-O2','-static','-fno-pie','-no-pie',
                     '-fstack-protector-strong','-D_FORTIFY_SOURCE=2','-Wl,--build-id=none',
                     '-Wall','-Wextra','-Werror','-Wno-unused-function','-Wno-misleading-indentation']
            binaries = Path(td)
            for name in ('adapter','core-tests'):
                capture(flags+[str(tree/'adapter'/(name+'.c')),'-o',str(binaries/name)],60,commands)
                old['elf_static'](old['read_exact'](binaries/name,16*1024*1024))
            record['phase'] = 'core'
            core = [json.loads(line) for line in capture([str(binaries/'core-tests')],20,commands).splitlines()]
            need(core and core[-1]['summary']['failed'] == 0)
            record['steps']['core'] = 'PASS'
            record['build']['adapter'] = build['sha']((binaries/'adapter').read_bytes())
            record['phase'] = 'build'
            capture(flags+[str(tree/'tests/native-fixture-runner.c'),'-o',str(binaries/'fixture'),
                    '-DADAPTER_EXEC_SHA256="'+record['build']['adapter']+'"'],60,commands)
            elf = old['elf_static'](old['read_exact'](binaries/'fixture',16*1024*1024))
            record['build']['fixture'] = elf['sha256']
            fixture = Path(tempfile.mkdtemp(prefix='clb91-native-',dir='/tmp'))
            identity = fixture.stat()
            old['extract_runtime'](raw,entries,fixture/'runtime')
            inventory = old['fixture_inventory'](fixture)
            record['phase'] = 'capture'
            privileged = True
            response = capture(['/usr/bin/sudo','-n','--',str(binaries/'fixture'),'--work',str(fixture),
                        '--adapter',str(binaries/'adapter')],600,commands,privileged=True,require_exit=False)
            report = json.loads(response)
            record['cleanup'] = 'PASS' if report.get('cleanup') == 'confirmed' else 'UNKNOWN'
            record['native'] = native_result(report, commands[-1].get('exit'))
            record['steps']['capture'] = 'PASS'
        record['verdict'],record['phase'] = 'PASS','complete'
    except BaseException:
        record['verdict'] = 'FAIL'
    finally:
        if fixture is not None:
            try:
                if privileged:
                    need(record['cleanup'] == 'PASS')
                    old['cleanup_fixture'](fixture,identity,{})
                else:
                    need(inventory is not None)
                    old['cleanup_fixture'](fixture,identity,inventory)
            except BaseException:
                record['cleanup'],record['verdict'] = 'UNKNOWN','FAIL'
                record['phase'] = 'cleanup'
        validate_evidence(record)
        encoded = (json.dumps(record,sort_keys=True,separators=(',',':'))+'\n').encode()
        need(len(encoded) <= BOUND)
        fd = os.open(output/'result.json',os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
        try:
            need(os.write(fd,encoded) == len(encoded))
        finally:
            os.close(fd)
    suffix = (' portable_test='+portable_failed+' c_stage='+portable_stage
              if record['verdict'] == 'FAIL' and record['phase'] == 'portable' else '')
    print('CLB195_NAMESPACE_'+record['verdict']+suffix)
    return 0 if record['verdict'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
