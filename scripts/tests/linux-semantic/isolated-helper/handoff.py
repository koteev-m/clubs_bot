#!/usr/bin/env python3
"""CLB-192 synthetic-only capture/worker. Never an authorized stage entrypoint.

A reviewed local supervisor owns both processes and their private FIFO volume.
This is a local trust assumption, NOT attestation of host/runtime/Docker trust.
The producer creates its own synthetic inputs; it cannot select existing files.
"""
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import signal
import stat
import sys
import time

HELPER_SHA256 = 'db2f01218abb739ec172d1c3ad037eb1efa2cd098db315b84d06dcaae73ea39e'
ROOT = Path('/source/scripts/tests/linux-semantic/isolated-helper')
CANONICAL = '/opt/clubs-bot-stage'
PROJECT = 'clubs-bot-stage'
TRANSPORT = '/run/user/1000'
LIMIT = 196608
FIELDS = ['format', 'request', 'project', 'base', 'dotenv', 'override', 'interpolation']
RESULT_FIELDS = ['format', 'request', 'result', 'strategy', 'scope', 'future', 'application']
CANARY = 'CLB192_SYNTHETIC_VALUE'


class Refused(Exception):
    pass


def need(ok):
    if not ok:
        raise Refused()


def unique(pairs):
    result = {}
    for key, value in pairs:
        need(key not in result)
        result[key] = value
    return result


def request_id(value):
    need(type(value) is str and re.fullmatch('[0-9a-f]{32}', value) is not None)
    return value


def load():
    # Fixed path, exact CLB-191 bytes, no filesystem module import/cache.
    path = ROOT / 'helper.py'
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        before = os.fstat(fd)
        need(stat.S_ISREG(before.st_mode) and before.st_size <= 16384)
        raw = os.read(fd, 16385)
        need(len(raw) == before.st_size and os.path.samestat(before, os.lstat(path)))
        need(hashlib.sha256(raw).hexdigest() == HELPER_SHA256)
    finally:
        os.close(fd)
    h = {'__name__': 'clb192_captured_helper'}
    exec(compile(raw, str(path), 'exec'), h)
    return h, h['sources'](Path('/source/scripts/deploy'))


def parse(op, raw, expected):
    request_id(expected)
    need(type(raw) is bytes and 0 < len(raw) <= LIMIT)
    data = json.loads(raw, object_pairs_hook=unique)
    need(type(data) is dict and list(data) == FIELDS)
    need(type(data['format']) is int and data['format'] == 1)
    need(request_id(data['request']) == expected)
    project = data['project']
    need(type(project) is dict and list(project) == ['name', 'directory'])
    need(project == dict(name=PROJECT, directory=CANONICAL))
    for field, limit in [('base', 65536), ('dotenv', 65536), ('override', 4096)]:
        need(type(data[field]) is str and len(data[field].encode()) <= limit)
    # Missing/null dotenv refuses; present empty remains b'', as in the planner.
    op.P.check_inputs(*(data[k].encode() for k in ('base', 'dotenv', 'override')),
                      data['interpolation'], project['name'])
    return data


def public(nonce, outcome='unavailable', strategy=None):
    request_id(nonce)
    need((outcome, strategy) in {('unavailable', None), ('different', None),
                               ('equivalent', 'remove'), ('equivalent', 'explicit')})
    return json.dumps(dict(format=1, request=nonce, result=outcome, strategy=strategy,
        scope='snapshot', future='requires_recheck', application='not_authorized'),
        separators=(',', ':')).encode('ascii') + b'\n'


def response(raw, nonce):
    need(type(raw) is bytes and len(raw) <= 512)
    value = json.loads(raw, object_pairs_hook=unique)
    need(type(value) is dict and list(value) == RESULT_FIELDS)
    need(raw == public(nonce, value['result'], value['strategy']))
    return raw


def evaluate(op, raw, nonce):
    data = parse(op, raw, nonce)
    try:
        plan = op.P.prepare(*(data[k].encode() for k in ('base', 'dotenv', 'override')),
            interpolation=data['interpolation'], project=data['project']['name'],
            compose=op.COMPOSE, temporary_root=TRANSPORT,
            canonical_directory=data['project']['directory'])
        return public(nonce, 'equivalent', plan.strategy)
    except op.P.Refused as error:
        if error.reason != 'different':
            raise
        # Return inside Runtime ownership so post-runtime recheck/close still run.
        return public(nonce, 'different')


def worker(h, op, nonce, reader, cancelled):
    # Only the CLB-191 request evaluator is replaced. Its exact, pinned execute()
    # retains every Runtime guard BEFORE reader(), after planner, and on close.
    previous = h['execute'].__globals__['evaluate']
    h['execute'].__globals__['evaluate'] = lambda operation, raw: evaluate(operation, raw, nonce)
    try:
        return response(h['execute'](op, reader, cancelled), nonce)
    finally:
        h['execute'].__globals__['evaluate'] = previous


def fifo_read(path):
    parent = os.open(str(Path(path).parent), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    fd = None
    try:
        value = os.fstat(parent)
        need(value.st_uid == os.getuid() and stat.S_IMODE(value.st_mode) == 0o700)
        deadline = time.monotonic() + 25
        while fd is None:
            try:
                fd = os.open(Path(path).name, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW, dir_fd=parent)
            except FileNotFoundError:
                need(time.monotonic() < deadline)
                time.sleep(.01)
        initial = os.fstat(fd)
        need(stat.S_ISFIFO(initial.st_mode) and initial.st_uid == os.getuid()
             and initial.st_nlink == 1 and stat.S_IMODE(initial.st_mode) == 0o600)
        data = bytearray()
        while True:
            need(time.monotonic() < deadline)
            try:
                part = os.read(fd, min(65536, LIMIT + 1 - len(data)))
            except BlockingIOError:
                part = None
            if part == b'' and data:
                break
            if part:
                data.extend(part)
                need(len(data) <= LIMIT)
            else:
                time.sleep(.01)
        need(os.path.samestat(initial, os.stat(Path(path).name, dir_fd=parent, follow_symlinks=False)))
        return bytes(data)
    finally:
        if fd is not None:
            os.close(fd)
        os.close(parent)


def fixture(op, case):
    """Create only fixed synthetic originals on a fresh disposable ext4 volume."""
    need(case in ('remove', 'explicit', 'different', 'empty'))
    d = op.D
    root = Path(CANONICAL)
    need(list(root.iterdir()) == [])
    parent = root / '.clubs-bot-release-state'
    private = parent / 'stage'
    for path in (parent, private, private/'clubs-bot-schema-stage.lock',
                 private/'clubs-bot-schema-stage.results', private/'clubs-bot-schema-stage.migration-ledgers'):
        path.mkdir(mode=0o700)
    def write(path, raw):
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        try:
            need(os.write(fd, raw) == len(raw))
        finally:
            os.close(fd)
    for path in (parent/'application.lock', private/'clubs-bot-schema-stage.results'/'operation.lock'):
        write(path, b'')
    # A fixture binding uses observed, not invented, filesystem facts. The
    # unchanged capture then independently repeats its full backing predicate.
    observed = d.capture_result(['findmnt', '--noheadings', '--pairs', '--output',
        'FSTYPE,SOURCE,FSROOT,TARGET', '--target', CANONICAL], env={'PATH': '/usr/bin:/bin', 'LC_ALL': 'C'},
        timeout=2, limit=4096)
    need(observed.failure is None and observed.code == 0)
    match = re.fullmatch(r'FSTYPE="([^"\s]+)" SOURCE="([^"\s]+)" FSROOT="([^"\s]+)" TARGET="([^"\s]+)"',
                        observed.output.decode('ascii').strip())
    need(match is not None and '\\' not in observed.output.decode('ascii'))
    encoded = 'clubs-bot-mount-fingerprint-version=2\n' + '\n'.join(
        key + '_SHA256=' + d.sha(value.encode())
        for key, value in zip(('FSTYPE', 'SOURCE', 'FSROOT', 'TARGET'), match.groups()))
    binding = dict(binding_version='3', environment='stage', compose_path_hash=d.sha(CANONICAL.encode()),
        mount_fingerprint_version='2', mount_fingerprint='mount-v2:'+d.sha(encoded.encode()),
        compose_project=PROJECT, compose_service='app')
    write(parent/'application.binding', '\n'.join(k+'='+v for k,v in binding.items()).encode())
    base = b'services:\n  app:\n    image: fixture:local\n    env_file: [.env]\n'
    if case == 'remove':
        base += b'    environment:\n      A: ${A}\n'
    override = ('# clubs-bot-managed-quiesced-release\n# revision: '+d.REVISION+
                '\nservices:\n  app:\n    image: '+d.IMAGE+'\n').encode()
    write(root/'docker-compose.yml', base)
    write(root/'.env', b'' if case == 'empty' else ('A='+CANARY+os.urandom(16).hex()+'\n').encode())
    write(root/'docker-compose.override.yml', override)
    write(private/'clubs-bot-schema-stage.lock'/'docker-compose.release.yml', override)


def local_capture_trust(op):
    """Local synthetic prerequisite only; never establishes a stage trust anchor."""
    need(sys.platform == 'linux' and sys.flags.isolated and sys.flags.no_site and os.getuid() == 1000)
    need(dict(os.environ) == {'PATH': '/usr/bin:/bin', 'LC_ALL': 'C'})
    need(pwd.getpwuid(os.getuid()).pw_name not in ('root', 'hookah-staging'))
    status = Path('/proc/self/status').read_text()
    need(re.search(r'^CapEff:\s+0+$', status, re.M) is not None)
    need(re.search(r'^NoNewPrivs:\s+1$', status, re.M) is not None)
    need(re.search(r'^Seccomp:\s+2$', status, re.M) is not None)
    # Some kernels expose DOWN tunnel devices even in network=none. Check
    # actual routing, not interface names; no CAP_NET_ADMIN can add routes.
    need(len(Path('/proc/net/route').read_text().splitlines()) == 1)
    need(all(line.split()[-1] == 'lo' for line in Path('/proc/net/ipv6_route').read_text().splitlines()))
    for path in (CANONICAL, TRANSPORT):
        fd = op.S.open_canonical_root(path)
        os.close(fd)
    need(not os.path.exists('/var/run/docker.sock'))


def snapshot(op, context, nonce, interpolation):
    context.open()
    d = op.D
    main = context.bounded_read(context.file('compose', 'docker-compose.yml', modes=(0o600, 0o644)), 65536)
    d.require(d.scan(main)['violations'] == ('key_env_file',) and d.env_file_structure(main) ==
              dict(env_file_occurrences='one', env_file_shapes=('service/app/sequence/canonical_dotenv',)), 'unsupported')
    override = context.read_file('compose', 'docker-compose.override.yml')
    release = context.read_file('state', 'docker-compose.release.yml')
    context.data[('compose', 'docker-compose.override.yml')] = override
    context.data[('state', 'docker-compose.release.yml')] = release
    records = d.StaticRecords(context.data, {}, context.project)
    d.require(records.evaluate('managed_override') == 'pass' and
              records.evaluate('managed_release') == 'pass', 'identity')
    dotenv = context.bounded_read(context.file('compose', '.env'), 65536)
    data = dict(format=1, request=request_id(nonce),
        project=dict(name=context.project, directory=d.COMPOSE_PATH),
        base=main.decode(), dotenv=dotenv.decode(), override=override.decode(), interpolation=interpolation)
    raw = json.dumps(data, separators=(',', ':')).encode()
    parse(op, raw, nonce)
    context.recheck()
    d.require(context.mount_identity() == context.backing, 'backing')
    return raw


def produce(op, nonce, case, exchange, cancelled):
    context = None
    result = public(nonce)
    try:
        local_capture_trust(op)  # BEFORE fixture creation or private snapshot reads.
        fixture(op, case)
        context = op.D.ReadOnlyCapture(pwd.getpwuid(os.getuid()).pw_name, cancelled)
        raw = snapshot(op, context, nonce, {'A': 'different'} if case == 'different' else {})
        result = response(exchange(raw), nonce)
        context.recheck()
        op.D.require(context.mount_identity() == context.backing, 'backing')
        op.D.require(not cancelled(), 'interrupted')
    except BaseException:
        result = public(nonce)
    finally:
        if context is not None:
            try:
                context.close()
            except BaseException:
                result = public(nonce)
    return result


def exchange_fifo(h, raw):
    path = Path(TRANSPORT) / 'request'
    os.mkfifo(path, 0o600)
    fd = None
    try:
        # Alarm bounds open, pipe backpressure and waiting for supervisor result.
        fd = os.open(path, os.O_WRONLY | os.O_NOFOLLOW)
        initial = os.fstat(fd)
        need(stat.S_ISFIFO(initial.st_mode) and initial.st_uid == os.getuid()
             and stat.S_IMODE(initial.st_mode) == 0o600 and initial.st_nlink == 1)
        view = memoryview(raw)
        while view:
            written = os.write(fd, view)
            need(written > 0)
            view = view[written:]
        os.close(fd)
        fd = None
        # Public bounded response only; stdin is Docker-supervisor owned FIFO.
        previous = h['read_input'].__globals__['LIMIT']
        h['read_input'].__globals__['LIMIT'] = 512
        try:
            return h['read_input']()
        finally:
            h['read_input'].__globals__['LIMIT'] = previous
    finally:
        if fd is not None:
            os.close(fd)
        path.unlink()


def main():
    # Control argv contains only a role, public nonce and built-in fixture enum.
    need(len(sys.argv) == 4 and sys.argv[1] in ('producer', 'worker'))
    role, nonce, case = sys.argv[1:]
    request_id(nonce)
    need(case in ('remove', 'explicit', 'different', 'empty'))
    cancelled = [False]
    op = None
    def stop(*unused):
        cancelled[0] = True
        if op is not None:
            op.D._capture_cancellation[0] = True
        raise InterruptedError()
    for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP, signal.SIGALRM):
        signal.signal(sig, stop)
    signal.alarm(45 if role == 'producer' else 30)
    result = public(nonce)
    try:
        h, op = load()
        if role == 'producer':
            result = produce(op, nonce, case, lambda raw: exchange_fifo(h, raw), lambda: cancelled[0])
        else:
            need(sys.platform == 'linux' and sys.flags.isolated and sys.flags.no_site and os.getuid() == 1000)
            need(dict(os.environ) == {'PATH': '/usr/bin:/bin', 'LC_ALL': 'C'})
            result = worker(h, op, nonce, lambda: fifo_read('/handoff/request'), lambda: cancelled[0])
    except BaseException:
        result = public(nonce)
    finally:
        signal.alarm(0)
    os.write(1, result)
    return 0 if json.loads(result)['result'] == 'equivalent' else 1


if __name__ == '__main__':
    sys.exit(main())
