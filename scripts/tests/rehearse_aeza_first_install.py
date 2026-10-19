#!/usr/bin/env python3
"""Opt-in local, synthetic PG16/validator rehearsal. Does NOT run migrations or app.

Real app/FSM baseline remains unverified when no qualifying published image and
full synthetic schema fixture are available. No production acceptance assertion.
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import uuid
import zlib

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('contract', ROOT / 'scripts/tests/test_aeza_first_install.py')
T = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(T)
IMAGE = 'sha256:810731421097b8cc8d91a0a2b5d4139a1ea10c8cea3b6081c70c6d70ac53e1a1'


def run(args, *, data=None, timeout=120, ok=True):
    p = subprocess.run(args, input=data, capture_output=True, timeout=timeout, check=False)
    if ok and p.returncode != 0:
        raise RuntimeError('synthetic rehearsal command refused: ' + args[0] + ' exit=' + str(p.returncode) + '\n' + p.stderr.decode()[-4000:])
    return p


def ids(project, kind):
    args = {'container': ['ps', '-aq', '--no-trunc'], 'network': ['network', 'ls', '-q', '--no-trunc'],
            'volume': ['volume', 'ls', '-q']}[kind]
    return run(['docker', *args, '--filter', 'label=com.docker.compose.project=' + project]).stdout.decode().split()


def inspect(kind, value):
    return json.loads(run(['docker', kind, 'inspect', value]).stdout)[0]


def cleanup(project):
    for kind in ('container', 'network', 'volume'):
        for value in ids(project, kind):
            item = inspect(kind, value)
            labels = item['Config']['Labels'] if kind == 'container' else item['Labels']
            assert labels.get('com.docker.compose.project') == project
            args = ['docker', 'rm', '-f', value] if kind == 'container' else ['docker', kind, 'rm', value]
            run(args)
    assert all(not ids(project, kind) for kind in ('container', 'network', 'volume'))


def quote(value):
    return "'" + value.replace("'", "''") + "'"


def synthetic_history():
    # Flyway SQL checksum: UTF-8 lines without line terminators; no migration SQL is executed.
    files = list((ROOT / 'core-data/src/main/resources/db/migration/postgresql').glob('V*.sql'))
    files += list((ROOT / 'core-data/src/main/resources/db/migration/common').glob('V*.sql'))
    files.sort(key=lambda p: tuple(int(x) for x in p.name.split('__')[0][1:].replace('_', '.').split('.')))
    sql = '''CREATE TABLE public.flyway_schema_history (
 installed_rank integer PRIMARY KEY, version varchar(50), description varchar(200) NOT NULL,
 type varchar(20) NOT NULL, script varchar(1000) NOT NULL, checksum integer,
 installed_by varchar(100) NOT NULL, installed_on timestamp NOT NULL DEFAULT now(),
 execution_time integer NOT NULL, success boolean NOT NULL);
CREATE TABLE public.synthetic_sentinel (id integer PRIMARY KEY, value text);
INSERT INTO public.synthetic_sentinel VALUES (1, 'clb91 synthetic only');
GRANT SELECT ON public.flyway_schema_history TO clubs;
'''
    for rank, path in enumerate(files, 1):
        version, description = path.stem[1:].split('__', 1)
        checksum = zlib.crc32(''.join(path.read_text(encoding='utf-8-sig').splitlines()).encode())
        if checksum >= 2**31:
            checksum -= 2**32
        values = [str(rank), quote(version.replace('_', '.')), quote(description.replace('_', ' ')),
                  "'SQL'", quote(path.name), str(checksum), "'synthetic_fixture'", 'now()', '0', 'true']
        sql += 'INSERT INTO public.flyway_schema_history VALUES (' + ','.join(values) + ');\n'
    assert len(files) > 0
    return sql.encode(), len(files)


def main():
    project = 'clb91-install-' + uuid.uuid4().hex[:12]
    evidence = Path(tempfile.mkdtemp(prefix='clb91-first-rehearsal-')).resolve()
    os.chmod(evidence, 0o700)
    root = evidence / 'root'
    T.fixture(root)
    report = {'project': project, 'evidence': str(evidence), 'image': IMAGE, 'checks': {}}
    checks = report['checks']
    image = inspect('image', IMAGE)
    report['image_platform'] = image['Os'] + '/' + image['Architecture']
    report['fsm_image_eligible'] = bool(re.fullmatch(r'[0-9a-f]{40}', image['Config']['Labels'].get('org.opencontainers.image.revision', ''))
                                      and image['RepoDigests'] and image['RepoDigests'][0].startswith('ghcr.io/'))
    assert report['fsm_image_eligible'] is False
    env = (root / '.env').read_text().replace(T.ENV['CLB_APP_IMAGE'], IMAGE)
    pg = inspect('image', 'postgres:16-alpine')
    assert 'PG_MAJOR=16' in pg['Config']['Env'] and pg['Architecture'] == image['Architecture']
    report['postgres_image'] = pg['RepoDigests'][0]
    env = env.replace(T.ENV['CLB_PG_IMAGE'], report['postgres_image'])
    env = env.replace(T.ENV['CLB_PLATFORM'], report['image_platform'])
    (root / '.env').write_text(env)
    compose = ['docker', 'compose', '-p', project, '--project-directory', str(root), '--env-file', str(root / '.env'), '-f', str(root / 'docker-compose.yml')]
    model = json.loads(run(compose + ['config', '--format', 'json']).stdout)
    assert set(model['services']) == {'db', 'app'}
    app, db = model['services']['app'], model['services']['db']
    assert app['ports'] == [{'mode': 'ingress', 'host_ip': '127.0.0.1', 'target': 8080, 'published': '8081', 'protocol': 'tcp'}]
    assert not db.get('ports') and set(db['networks']) == {'database'} and model['networks']['database']['internal']
    assert not any('container_name' in s or s.get('privileged') for s in model['services'].values())
    assert app['environment']['FLYWAY_MODE'] == 'validate'
    assert 'FLYWAY_VALIDATE_ONLY' not in app['environment']  # FSM must be able to override mode later.
    assert not app.get('volumes') and not app.get('secrets')
    checks['production_compose_topology'] = 'PASS'
    # Test-only stricter model, never installed as a production override.
    app.pop('ports')
    app['networks'] = {'database': None}
    model['networks'].pop('application')
    model['services']['app']['restart'] = 'no'
    model['services']['db']['restart'] = 'no'
    testfile = root / 'rehearsal.json'
    testfile.write_text(json.dumps(model))
    testfile.chmod(0o600)
    compose = ['docker', 'compose', '-p', project, '-f', str(testfile)]
    assert all(not ids(project, kind) for kind in ('container', 'network', 'volume'))
    try:
        run(compose + ['config', '--quiet'])
        run(compose + ['up', '-d', '--no-deps', '--pull', 'never', '--wait', '--wait-timeout', '60', 'db'], timeout=80)
        dbid, = ids(project, 'container')
        dbinfo = inspect('container', dbid)
        assert not dbinfo['HostConfig']['PortBindings']
        network, = ids(project, 'network')
        assert inspect('network', network)['Internal']
        assert inspect('network', network)['Containers'].keys() == {dbid}
        checks['isolated_pg16_creation'] = 'PASS'
        sql = compose + ['exec', '-T', 'db', 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'clubs']
        roles = run(sql, data=b"SELECT rolname,rolsuper,rolcreatedb,rolcreaterole,rolreplication FROM pg_roles WHERE rolname='clubs';").stdout
        assert roles == b'clubs|f|f|f|f\n'
        version = run(sql, data=b'SHOW server_version;').stdout.decode().strip()
        assert version.startswith('16.')
        report['postgres_version'] = version
        checks['controlled_role'] = 'PASS'
        validate = compose + ['run', '--rm', '--no-deps', '--pull', 'never', '--entrypoint', '/opt/app/bin/app-bot-validate-schema', 'app']
        def validator(expected, override=None):
            command = validate if override is None else validate[:-1] + ['-e', override, 'app']
            result = run(command, timeout=80, ok=False)
            assert result.returncode == expected
            assert result.stdout == b'schema-validation:v=1 result=' + (b'compatible\n' if expected == 0 else b'rejected\n')
            assert ids(project, 'container') == [dbid]
        validator(1)
        checks['empty_schema_refused_without_app'] = 'PASS'
        fixture_sql, count = synthetic_history()
        run(sql, data=fixture_sql)
        report['synthetic_history_rows'] = count
        dumpcmd = compose + ['exec', '-T', 'db', 'pg_dump', '-U', 'postgres', '-d', 'clubs', '--no-owner', '--no-privileges']
        def dump():
            return b'\n'.join(line for line in run(dumpcmd).stdout.splitlines() if not line.startswith((b'\\restrict ', b'\\unrestrict ')))
        before = dump()
        validator(0)
        assert before == dump()
        checks['synthetic_history_validation_unchanged_dump'] = 'PASS'
        validator(1, 'DATABASE_PASSWORD=synthetic_wrong')
        validator(1, 'FLYWAY_MODE=migrate-and-validate')
        assert before == dump()
        checks['invalid_db_and_mode_refused_unchanged_dump'] = 'PASS'
        run(sql, data=b'UPDATE flyway_schema_history SET checksum=checksum+1 WHERE installed_rank=1;')
        before_bad = dump()
        validator(1)
        assert before_bad == dump()
        checks['checksum_mismatch_refused_unchanged_dump'] = 'PASS'
        checks['ordinary_app_never_created'] = 'PASS'
        checks['healthy_app_fsm_baseline'] = 'NOT_RUN: image metadata ineligible; complete synthetic application schema unavailable'
    except Exception:
        for cid in ids(project, 'container'):
            # Only this synthetic project: retain diagnostics privately before owned cleanup.
            (evidence / (cid + '.log')).write_bytes(run(['docker', 'logs', cid], ok=False).stdout + run(['docker', 'logs', cid], ok=False).stderr)
        raise
    finally:
        cleanup(project)
        checks['owned_cleanup'] = 'PASS'
        (evidence / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
