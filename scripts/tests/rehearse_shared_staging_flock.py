#!/usr/bin/env python3
"""Opt-in Linux/real Docker PG16 isolation rehearsal; owns exactly two projects.

Run only in a disposable Linux container with an explicitly approved local Docker
socket. No host mount, published port, external network, restore or migration.
"""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('flock', ROOT / 'scripts/deploy/shared_staging_flock.py')
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)


def run(args, *, data=None):
    p = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    if p.returncode:
        raise RuntimeError('fixture command failed: ' + args[0] + ' rc=' + str(p.returncode))
    return p.stdout


def inventory(project, token):
    result = {}
    for kind, listing in (('container', ['ps', '-aq', '--no-trunc']),
                          ('volume', ['volume', 'ls', '-q']), ('network', ['network', 'ls', '-q', '--no-trunc'])):
        names = run(['docker', *listing, '--filter', 'label=com.docker.compose.project=' + project]).decode().split()
        records = []
        for name in names:
            item = json.loads(run(['docker', kind, 'inspect', name]))[0]
            labels = item['Config']['Labels'] if kind == 'container' else item['Labels']
            assert labels['com.docker.compose.project'] == project
            assert labels['clb91.flock.test'] == token
            if kind == 'container':
                assert not item['HostConfig']['PortBindings']
                assert all(m['Type'] == 'volume' for m in item['Mounts'])
            records.append({'id': item.get('Id', item['Name']), 'name': item['Name'],
                            'created': item.get('Created', item.get('CreatedAt')), 'labels': labels})
        result[kind] = sorted(records, key=lambda x: x['id'])
    return result


def main():
    assert sys.platform == 'linux' and os.environ.get('CLB_FLOCK_DISPOSABLE_DOCKER') == '1'
    running = run(['docker', 'ps', '-q', '--no-trunc']).decode().split()
    assert len(running) == 1 and running[0].startswith(os.uname().nodename), 'requires quiet daemon'
    runner = json.loads(run(['docker', 'inspect', running[0]]))[0]
    assert runner['Config']['Labels']['clb91.flock.runner'] == os.environ['CLB_FLOCK_RUNNER_TOKEN']
    image = json.loads(run(['docker', 'image', 'inspect', 'postgres:16-alpine']))[0]
    assert 'PG_MAJOR=16' in image['Config']['Env']
    token = uuid.uuid4().hex
    projects = ['clb91-flock-' + token[:16], 'clb91-neighbor-' + token[:16]]
    report = {'invocation': token, 'projects': projects, 'postgres_image': image['Id'], 'checks': {}}
    with tempfile.TemporaryDirectory(prefix='clb91-pg-flock-', dir='/tmp') as temp:
        root = Path(temp)
        lockfile = root / 'shared.lock'
        lockfile.touch()
        lockfile.chmod(0o660)
        labels = {'clb91.flock.test': token}
        model = {'services': {'db': {'image': image['Id'], 'pull_policy': 'never', 'labels': labels,
                                    'environment': {'POSTGRES_PASSWORD': 'synthetic-only-' + token},
                                    'volumes': ['data:/var/lib/postgresql/data'], 'networks': ['database'],
                                    'healthcheck': {'test': ['CMD-SHELL', 'pg_isready -U postgres'],
                                                    'interval': '1s', 'timeout': '2s', 'retries': 40}}},
                 'volumes': {'data': {'labels': labels}},
                 'networks': {'database': {'internal': True, 'labels': labels}}}
        compose = root / 'compose.json'
        compose.write_text(json.dumps(model))
        commands = {p: ['docker', 'compose', '-p', p, '-f', str(compose)] for p in projects}
        owned = []
        try:
            for project in projects:
                assert all(not v for v in inventory(project, token).values())
                owned.append(project)
                with M.HostLock(lockfile) as lock:
                    result = M.run_owned(commands[project] + ['up', '-d', '--pull', 'never', '--wait', '--wait-timeout', '60'],
                                         b'', lock, time.monotonic() + 90)
                    assert result[0] == 0
                records = inventory(project, token)
                cid = records['container'][0]['id']
                sentinel = project + '-persisted'
                with M.HostLock(lockfile):
                    run(['docker', 'exec', '-i', cid, 'psql', '-U', 'postgres', '-v', 'ON_ERROR_STOP=1'],
                        data=("CREATE TABLE sentinel(value text PRIMARY KEY); INSERT INTO sentinel VALUES ('"
                              + sentinel + "');").encode())
            before = {p: inventory(p, token) for p in projects}
            assert before[projects[0]]['volume'][0]['id'] != before[projects[1]]['volume'][0]['id']
            report['before'] = before
            # An independent Linux process tries an actual Docker mutation; it
            # cannot reach the CLI while the first participant owns the lock.
            marker = root / 'docker-effect'
            code = (f"import runpy; m=runpy.run_path({M.__file__!r}); "
                    f"\nwith m['HostLock']({str(lockfile)!r}):\n"
                    f" open({str(marker)!r},'w').write('intent')\n"
                    f" m['subprocess'].run({commands[projects[0]] + ['restart', 'db']!r},check=True)\n")
            with M.HostLock(lockfile):
                attempt = subprocess.run([sys.executable, '-I', '-S', '-B', '-c', code],
                                         capture_output=True, timeout=5)
                assert attempt.returncode != 0 and not marker.exists()
                assert before == {p: inventory(p, token) for p in projects}
            report['checks']['busy_before_docker_intent'] = True
            with M.HostLock(lockfile) as lock:
                result = M.run_owned(commands[projects[0]] + ['restart', 'db'], b'', lock, time.monotonic() + 90)
                assert result[0] == 0
            after = {p: inventory(p, token) for p in projects}
            assert before == after
            report['after'] = after
            report['checks']['exact_neighbor_and_owned_identities_unchanged'] = True
            for project in projects:
                cid = after[project]['container'][0]['id']
                with M.HostLock(lockfile):
                    deadline = time.monotonic() + 30
                    while time.monotonic() < deadline:
                        item = json.loads(run(['docker', 'container', 'inspect', cid]))[0]
                        if item['State']['Health']['Status'] == 'healthy': break
                        time.sleep(0.1)
                    assert item['State']['Health']['Status'] == 'healthy'
                    value = run(['docker', 'exec', cid, 'psql', '-U', 'postgres', '-Atc', 'SELECT value FROM sentinel']).decode().strip()
                    assert value == project + '-persisted'
            report['checks']['both_independent_pg16_sentinels_persisted'] = True
        finally:
            for project in reversed(owned):
                # Refuse cleanup if any resource is not positively ours. Never
                # enumerate/remove by a loose prefix or use global prune.
                inventory(project, token)
                with M.HostLock(lockfile):
                    run(commands[project] + ['down', '--volumes'])
                assert all(not v for v in inventory(project, token).values())
            report['checks']['only_owned_resources_removed'] = True
    print(json.dumps(report, sort_keys=True, indent=2))


if __name__ == '__main__':
    main()
