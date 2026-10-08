#!/usr/bin/env python3
"""CLB-191 synthetic-only Docker launcher. No build/pull/stage/SSH/host mutations."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import subprocess
import tempfile
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]



def container_argv(name, export, image, code):
    return ['docker', 'run', '--name', name, '--pull=never', '--platform', 'linux/amd64',
                '--rm', '--log-driver=none', '-i', '--read-only', '--network=none', '--user=1000:1000',
                '--cap-drop=ALL', '--security-opt=no-new-privileges', '--pids-limit=64',
                '--memory=768m', '--tmpfs', '/run/user/1000:uid=1000,gid=1000,mode=0700,nosuid,nodev,noexec',
                '--tmpfs', '/opt/clubs-bot-stage:uid=1000,gid=1000,mode=0700,nosuid,nodev,noexec',
                '--mount', 'type=bind,src='+str(export)+',dst=/source,readonly',
                '--entrypoint', '/usr/bin/env', image, '-i', 'PATH=/usr/bin:/bin', 'LC_ALL=C',
                '/usr/bin/python3.12', '-I', '-S', '-B', '-c', code]

def summarize(case, result):
    expected = ('compose-env-file-plan:v=1 result=equivalent strategy='+case+
                ' scope=snapshot future=requires_recheck application=not_authorized\n').encode()
    equivalent = result.failure is None and result.code == 0 and result.output == expected
    refused = (result.failure is None and result.code == 1 and
               result.output == b'clb191-local:v=1 result=refused\n')
    different = (result.failure is None and result.code == 1 and
                 result.output == b'clb191-local:v=1 result=different\n')
    return dict(case=case, equivalent=equivalent, refused=refused, different=different,
                bounded_capture_failure=result.failure, cleanup='confirmed_absent')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--image', required=True, help='explicit local sha256 image ID')
    args = parser.parse_args()
    if not re.fullmatch('sha256:[0-9a-f]{64}', args.image): parser.error('exact image ID required')
    helper = runpy.run_path(str(HERE/'helper.py'))
    op = helper['sources'](ROOT/'scripts/deploy')
    tests = runpy.run_path(str(HERE/'test-helper.py'))
    code = (HERE/'helper.py').read_text()
    records = []
    docker_env = {k: os.environ[k] for k in ('PATH', 'HOME')}
    # Same existing bounded subprocess capture; never relay child stderr or raw output.
    for case in ('remove', 'explicit', 'different'):
        name = 'clb191-' + uuid.uuid4().hex
        with tempfile.TemporaryDirectory(prefix='clb191-export-') as td:
            export = Path(td).resolve()
            sources = export/'scripts/deploy'; sources.mkdir(parents=True)
            for source, digest in helper['PINS'].items():
                raw = (ROOT/'scripts/deploy'/source).read_bytes()
                if hashlib.sha256(raw).hexdigest() != digest: raise RuntimeError('source drift')
                (sources/source).write_bytes(raw)
            argv = container_argv(name, export, args.image, code)
            try:
                result = op.D.capture_result(argv, payload=tests['fixture'](op, case),
                    env=docker_env, timeout=45, limit=4096)
            finally:
                # Only the random name created by this invocation may be removed.
                inspect = subprocess.run(['docker', 'container', 'inspect', name],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10, env=docker_env)
                if inspect.returncode == 0:
                    subprocess.run(['docker', 'rm', '-f', name], check=True,
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10, env=docker_env)
                remaining = subprocess.run(['docker', 'container', 'ls', '-aq', '--filter', 'name=^/'+name+'$'],
                    capture_output=True, check=True, timeout=10, env=docker_env)
                if remaining.stdout: raise RuntimeError('container cleanup failed')
            # Never publish arbitrary child bytes, even on a broken runtime.
            records.append(summarize(case, result))
    print(json.dumps(dict(scope='LOCAL_SYNTHETIC_ONLY', image=args.image,
        helper_sha256=hashlib.sha256(code.encode()).hexdigest(), cases=records,
        native_acceptance='NOT_ATTESTED_BY_CONTAINER', stage_acceptance=False)))
    return 0 if records[0]['equivalent'] and records[1]['equivalent'] and records[2]['different'] else 2


if __name__ == '__main__': raise SystemExit(main())
