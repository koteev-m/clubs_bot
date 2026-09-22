#!/usr/bin/env python3
"""Fixed native production-profile checks; historical experiment retained below."""
import difflib
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import subprocess
import sys
import tarfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
LIMIT = 8 * 2**20
INPUTS_SHA256 = 'a365d37e6bf45dacfe672364d2e3493bc3c786e692940e4ee0566b8f97fd5833'
EVIDENCE = ('identity.json', 'status.json', 'prepare.log', 'build.log', 'comparison.txt',
            'experiment.patch', 'runtime.log', 'planner.log', 'context.log', 'semantic.log')
ORIGINAL = {
    'stage-compose-env-semantic-operation.py': '75d011ddadbd25d02c128a6482e63fdc62080aef3ba79cba8ec71d9b2b348b47',
    'stage-compose-env-file-plan.py': '5f86bd080a43dc27cee8bee270ba5edd5436d74d27739120bcc5ef49d9b79a1c',
    'stage-compose-env-semantic-runtime.json': 'd2c7794fa073ab310df46a80880a83fceb0a2eb450aae410e123266f07c83903',
}
SUITES = (
    ('runtime', 'test_stage_compose_env_semantic.py',
     'BootstrapTest.test_real_runtime_build_passes_without_private_capture'),
    ('planner', 'test_stage_compose_env_file_plan.py'),
    ('context', 'test_stage_compose_env_file_context.py'),
    ('semantic', 'test_stage_compose_env_semantic.py'),
)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def exact(expected, actual, label):
    if expected != actual:
        diff = ''.join(difflib.unified_diff(expected.decode().splitlines(True),
            actual.decode().splitlines(True), fromfile=label + ':expected', tofile=label + ':actual'))
        raise ValueError(diff or label + ': byte mismatch')


def native_guard(system, machine, runner_arch, docker_arch):
    if (system, machine, runner_arch) != ('Linux', 'x86_64', 'X64') or docker_arch not in ('x86_64', 'amd64'):
        raise ValueError('native Linux x64 host/runner/daemon required; no emulation fallback')


def adapt(export, candidate):
    # Historical pre-integration experiment only; never used on current sources.
    folder = export / 'scripts/deploy'
    originals = {name: (folder / name).read_bytes() for name in ORIGINAL}
    for name, expected in ORIGINAL.items():
        if digest(originals[name]) != expected:
            raise ValueError('experimental source precondition: ' + name)
    replacements = {
        'stage-compose-env-semantic-operation.py': (b"platform.machine() == 'aarch64'", b"platform.machine() == 'x86_64'"),
        'stage-compose-env-file-plan.py': (b'/usr/lib/aarch64-linux-gnu/ruby/3.2.0', b'/usr/lib/x86_64-linux-gnu/ruby/3.2.0'),
    }
    updates = {'stage-compose-env-semantic-runtime.json': candidate}
    for name, (before, after) in replacements.items():
        if originals[name].count(before) != 1:
            raise ValueError('experimental replacement count: ' + name)
        updates[name] = originals[name].replace(before, after)
    diff = ''
    for name in sorted(updates):
        diff += ''.join(difflib.unified_diff(originals[name].decode().splitlines(True),
            updates[name].decode().splitlines(True), fromfile='a/scripts/deploy/' + name,
            tofile='b/scripts/deploy/' + name))
        (folder / name).write_bytes(updates[name])
    return diff


def integrated_diff(export, candidate):
    """Prove current bytes are exactly the three native-tested adaptations.

    Return the historical comparison for evidence, without modifying the export.
    Inverse checks are anchored to the original source hashes, not current output.
    """
    folder = export / 'scripts/deploy'
    reference = (HERE / 'arm64-runtime-reference.json').read_bytes()
    originals = {'stage-compose-env-semantic-runtime.json': reference}
    replacements = {
        'stage-compose-env-semantic-operation.py': (b"platform.machine() == 'aarch64'", b"platform.machine() == 'x86_64'"),
        'stage-compose-env-file-plan.py': (b'/usr/lib/aarch64-linux-gnu/ruby/3.2.0', b'/usr/lib/x86_64-linux-gnu/ruby/3.2.0'),
    }
    for name, (before, after) in replacements.items():
        actual = (folder / name).read_bytes()
        if actual.count(after) != 1 or before in actual:
            raise ValueError('integrated source precondition: ' + name)
        originals[name] = actual.replace(after, before)
    for name, expected in ORIGINAL.items():
        if digest(originals[name]) != expected:
            raise ValueError('integrated source precondition: ' + name)
    if digest(candidate) != '93a9d29cba93770fab9cc6605709a3b159cfb7ce2c627677ff77bd9cacd62008':
        raise ValueError('native-tested candidate changed')
    exact(candidate, (folder / 'stage-compose-env-semantic-runtime.json').read_bytes(), 'production manifest')
    return ''.join(''.join(difflib.unified_diff(originals[name].decode().splitlines(True),
        (folder / name).read_text().splitlines(True), fromfile='a/scripts/deploy/' + name,
        tofile='b/scripts/deploy/' + name)) for name in sorted(originals))


def container(image, *command, mounts=(), fixtures=False):
    argv = ['docker', 'run', '--rm', '--platform', 'linux/amd64', '--network=none',
        '--read-only', '--user', '1000:1000', '--cap-drop=ALL', '--security-opt=no-new-privileges']
    if fixtures:
        argv += ['--tmpfs', '/work/runner-temp:uid=1000,gid=1000,mode=0700,exec,nosuid,nodev',
                 '--tmpfs', '/run/user/1000:uid=1000,gid=1000,mode=0700',
                 '--tmpfs', '/opt/clubs-bot-stage:uid=1000,gid=1000,mode=0755',
                 '--env', 'CLB91_SYNTHETIC_TRACE_EVIDENCE=1']
    for source, target in mounts:
        argv += ['--mount', f'type=bind,src={source},dst={target},readonly']
    return argv + [image, *command]


def run_logged(argv, output, timeout):
    # Logs are never truncated into apparent success. Own Docker containers
    # have a CID so a timeout/interruption cannot leave a fixture running.
    cidfile = output.parent.parent / (output.stem + '.cid')
    is_container = argv[:2] == ['docker', 'run']
    if is_container:
        if cidfile.exists():
            raise ValueError('container identity file already exists')
        argv = argv[:2] + ['--cidfile', str(cidfile)] + argv[2:]
    code = 127
    with output.open('wb') as stream:
        process = None
        try:
            process = subprocess.Popen(argv, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            code = 124
        except KeyboardInterrupt:
            code = 130
        except OSError:
            code = 127
        finally:
            if process is not None and process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            if is_container and code and cidfile.is_file():
                cid = cidfile.read_text().strip()
                if not re.fullmatch('[0-9a-f]{64}', cid):
                    raise ValueError('invalid owned container ID; cleanup unverified')
                # On nonzero exit this may already have been auto-removed.
                # No failure here can turn the original failed suite green.
                subprocess.run(['docker', 'rm', '-f', cid], stdout=stream,
                               stderr=subprocess.STDOUT, timeout=30, check=False)
    if output.stat().st_size > LIMIT:
        raise ValueError('command evidence exceeds bound: ' + output.name)
    return code


def bounded_artifacts(evidence):
    total = 0
    for p in evidence.iterdir():
        if p.name not in EVIDENCE or p.is_symlink() or not p.is_file() or p.stat().st_size > LIMIT:
            raise ValueError('artifact allowlist/size violation')
        total += p.stat().st_size
    if total > 24 * 2**20:
        raise ValueError('artifact total bound')


def run_suites(image, export, evidence, status):
    # Ordinary failures still collect all requested suites. Cancellation must
    # stop scheduling immediately after status and owned-container cleanup.
    for label, test, *selector in SUITES:
        setup = 'cp -R /source /work/runner-temp/repo && git -c init.templateDir= init -q /work/runner-temp/repo && git -C /work/runner-temp/repo add -- . && cd /work/runner-temp/repo && exec python3 -B scripts/tests/'
        argv = container(image, '/bin/sh', '-c', setup + test + ' ' + ' '.join(selector),
                         mounts=((export, '/source'),), fixtures=True)
        status[label] = run_logged(argv, evidence / (label + '.log'), 600)
        (evidence / 'status.json').write_text(json.dumps(status, sort_keys=True) + '\n')
        if status[label] == 130:
            break
    return int(any(status.values()))


def main():
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    work = Path(os.environ['RUNNER_TEMP']).resolve() / 'clb91-amd64'
    if sys.argv[1:] == ['check-artifacts']:
        bounded_artifacts(work / 'evidence')
        return 0
    if sys.argv[1:]:
        raise ValueError('no caller-selected command or source')
    work.mkdir(mode=0o700)
    evidence = work / 'evidence'
    evidence.mkdir()
    status = {}
    def command(label, argv, timeout):
        code = run_logged(argv, evidence / (label + '.log'), timeout)
        status[label] = code
        (evidence / 'status.json').write_text(json.dumps(status, sort_keys=True) + '\n')
        if code:
            raise ValueError(f'{label}: exit {code}; see bounded evidence')
    try:
        sha = subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip()
        if os.environ.get('GITHUB_EVENT_NAME') != 'workflow_dispatch' or os.environ.get('GITHUB_SHA') != sha:
            raise ValueError('manual exact checkout required')
        for field in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'):
            if not re.fullmatch('[1-9][0-9]*', os.environ.get(field, '')):
                raise ValueError('run identity missing')
        docker_arch = subprocess.check_output(['docker', 'info', '--format', '{{.Architecture}}'], text=True).strip()
        native_guard(platform.system(), platform.machine(), os.environ.get('RUNNER_ARCH'), docker_arch)
        identity = {key: os.environ.get(key) for key in
                    ('GITHUB_WORKFLOW', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_SHA', 'RUNNER_ARCH')}
        identity.update(checkout_sha=sha, host_uname=subprocess.check_output(['uname', '-srm'], text=True).strip(),
                        docker_arch=docker_arch)
        (evidence / 'identity.json').write_text(json.dumps(identity, sort_keys=True) + '\n')
        raw_inputs = (HERE / 'amd64-inputs.json').read_bytes()
        if digest(raw_inputs) != INPUTS_SHA256:
            raise ValueError('frozen inputs changed')
        lock = json.loads(raw_inputs)
        for name, key in (('amd64-runtime-candidate.json', 'runtime_candidate_sha256'),
                          ('amd64-packages.txt', 'installed_packages_sha256')):
            if digest((HERE / name).read_bytes()) != lock[key]:
                raise ValueError('baseline changed: ' + name)
        downloads = work / 'downloads'
        command('prepare', [sys.executable, '-B', str(HERE / 'prepare-amd64-downloads.py'), str(downloads)], 900)
        subprocess.run([sys.executable, '-B', str(HERE / 'prepare-amd64-context.py'),
                        str(downloads), str(work / 'build')], check=True, timeout=60)
        image = 'clb91-native-amd64:' + os.environ['GITHUB_RUN_ID'] + '-' + os.environ['GITHUB_RUN_ATTEMPT']
        command('build', ['docker', 'build', '--platform', 'linux/amd64', '--pull=false', '--no-cache',
                         '--network=none', '-t', image, str(work / 'build')], 600)
        container_uname = subprocess.check_output(container(image, 'uname', '-sm'), text=True).strip()
        if container_uname != 'Linux x86_64':
            raise ValueError('container is not Linux x86_64')
        identity.update(container_uname=container_uname,
            image_id=subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}', image], text=True).strip())
        (evidence / 'identity.json').write_text(json.dumps(identity, sort_keys=True) + '\n')
        # Read only a disposable copy, never mount checkout into a container.
        material = work / 'material'
        material.mkdir(mode=0o755)
        for name, source in (('derive.py', HERE / 'derive-amd64-manifest.py'),
                             ('reference.json', HERE / 'arm64-runtime-reference.json')):
            shutil.copyfile(source, material / name)
        actual_manifest = subprocess.check_output(container(image, 'python3', '-I', '-S', '-B',
            '/material/derive.py', '/material/reference.json', mounts=((material, '/material'),)), timeout=90)
        actual_packages = subprocess.check_output(container(image, 'cat', '/toolchain-packages.txt'), timeout=30)
        try:
            exact((HERE / 'amd64-packages.txt').read_bytes(), actual_packages, 'packages')
            exact((HERE / 'amd64-runtime-candidate.json').read_bytes(), actual_manifest, 'manifest')
        except ValueError as error:
            (evidence / 'comparison.txt').write_text(str(error))
            raise
        (evidence / 'comparison.txt').write_text('packages and 191-file/four-alias manifest: byte-identical\n')
        identity['verified_manifest_profile'] = json.loads(actual_manifest)['versions'] if 'versions' in json.loads(actual_manifest) else {
            k: v for k, v in json.loads(actual_manifest).items() if k not in ('files', 'aliases')}
        (evidence / 'identity.json').write_text(json.dumps(identity, sort_keys=True) + '\n')
        archive = work / 'source.tar'
        with archive.open('wb') as stream:
            subprocess.run(['git', '-C', str(ROOT), 'archive', sha], stdout=stream, check=True)
        export = work / 'source'
        export.mkdir(mode=0o755)
        with tarfile.open(archive) as tar:
            tar.extractall(export, filter='data')
        (evidence / 'experiment.patch').write_text(integrated_diff(export, (HERE / 'amd64-runtime-candidate.json').read_bytes()))
        return run_suites(image, export, evidence, status)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        status['harness'] = 1
        (evidence / 'status.json').write_text(json.dumps(status, sort_keys=True) + '\n')
        print(str(error), file=sys.stderr)
        return 1
    finally:
        bounded_artifacts(evidence)


if __name__ == '__main__':
    sys.exit(main())
