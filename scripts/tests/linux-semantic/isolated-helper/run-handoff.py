#!/usr/bin/env python3
"""CLB-192 local supervisor. Fixed cached image, built-in fixtures, no live inputs."""
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import tempfile
import time
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
IMAGE = 'sha256:2982ea157465e5735f589429a166531e09a46e71d012adc5006d179de20a6a73'
CANCELLED = False
WATCHED = (signal.SIGTERM, signal.SIGHUP, signal.SIGINT)


def install_signals():
    def stop(*unused):
        global CANCELLED
        CANCELLED = True
        raise KeyboardInterrupt()
    for sig in WATCHED:
        signal.signal(sig, stop)


POLICY = ['--pull=never', '--platform=linux/amd64', '--log-driver=none', '--read-only',
          '--network=none', '--user=1000:1000', '--cap-drop=ALL',
          '--security-opt=no-new-privileges', '--pids-limit=64', '--memory=768m']


def command(name, export, code, nonce, case, *, transport=None):
    # Only a source digest and fixed loader travel in argv, never fixture values
    # or snapshot bytes. The reviewed supervisor exports this immutable code.
    digest = hashlib.sha256(code.encode()).hexdigest()
    bootstrap = ("import os,stat,hashlib; "
        "p='/source/clb192-entry.py'; "
        "f=os.open(p,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK); s=os.fstat(f); "
        "assert stat.S_ISREG(s.st_mode) and s.st_size<=65536; "
        "b=os.read(f,65537); "
        "assert len(b)==s.st_size and os.path.samestat(s,os.lstat(p)); "
        "os.close(f); assert hashlib.sha256(b).hexdigest()=="+repr(digest)+"; "
        "exec(compile(b,p,'exec'))")
    args = ['docker', 'create', '--name', name, '--label', 'clb192.supervisor='+str(os.getpid()), '-i', *POLICY,
            '--mount', 'type=bind,src='+str(export)+',dst=/source,readonly']
    if transport is None:
        args += ['--mount', 'type=volume,dst=/opt/clubs-bot-stage',
                 '--mount', 'type=volume,dst=/run/user/1000']
        role = 'producer'
    else:
        if not re.fullmatch('[0-9a-f]{64}', transport):
            raise ValueError('unexpected anonymous volume identity')
        args += ['--mount', 'type=volume,src='+transport+',dst=/handoff,readonly',
                 '--tmpfs', '/opt/clubs-bot-stage:uid=1000,gid=1000,mode=0700,nosuid,nodev,noexec',
                 '--tmpfs', '/run/user/1000:uid=1000,gid=1000,mode=0700,nosuid,nodev,noexec']
        role = 'worker'
    return args + ['--entrypoint', '/usr/bin/env', IMAGE, '-i', 'PATH=/usr/bin:/bin', 'LC_ALL=C',
                   '/usr/bin/python3.12', '-I', '-S', '-B', '-c', bootstrap, role, nonce, case]


def collect(process, payload, timeout=5):
    """Bounded public response delivery/collection; never handles snapshot bytes."""
    selector = selectors.DefaultSelector()
    raw = bytearray()
    try:
        process.stdin.write(payload)
        process.stdin.close()
        os.set_blocking(process.stdout.fileno(), False)
        selector.register(process.stdout, selectors.EVENT_READ)
        deadline = time.monotonic() + timeout
        while True:
            if time.monotonic() >= deadline:
                raise TimeoutError()
            for key, _ in selector.select(min(.1, max(0, deadline-time.monotonic()))):
                part = os.read(key.fileobj.fileno(), 513-len(raw))
                if not part:
                    process.wait(timeout=2)
                    return bytes(raw)
                raw.extend(part)
                if len(raw) > 512:
                    raise ValueError('public output bound')
    finally:
        selector.close()


def run_case(h, p, op, export, code, case):
    entry = export/'clb192-entry.py'
    if entry.exists():
        if entry.read_bytes() != code.encode():
            raise RuntimeError('entry source changed')
    else:
        with entry.open('xb') as stream:
            stream.write(code.encode())
    nonce = uuid.uuid4().hex
    names = ['clb192-producer-'+nonce, 'clb192-worker-'+nonce]
    volumes = []
    process = None
    docker_env = {k: os.environ[k] for k in ('PATH', 'HOME')}
    def bounded(argv, payload=b'', timeout=10, limit=8192):
        result = op.D.capture_result(argv, payload=payload, env=docker_env, timeout=timeout, limit=limit)
        if result.failure is not None:
            raise RuntimeError('bounded container command failed')
        return result
    result = p['public'](nonce)
    try:
        created = bounded(command(names[0], export, code, nonce, case))
        if created.code != 0:
            raise RuntimeError('producer creation failed')
        inspected = bounded(['docker', 'inspect', '--format', '{{json .Mounts}}', names[0]])
        if inspected.code != 0:
            raise RuntimeError('mount inspection failed')
        mounts = json.loads(inspected.output)
        volumes = [m['Name'] for m in mounts if m['Type'] == 'volume']
        if len(volumes) != 2 or any(not re.fullmatch('[0-9a-f]{64}', v) for v in volumes):
            raise RuntimeError('unexpected fixture volume identity')
        transport = next(m['Name'] for m in mounts if m['Destination'] == '/run/user/1000')
        if bounded(command(names[1], export, code, nonce, case, transport=transport)).code != 0:
            raise RuntimeError('worker creation failed')
        process = subprocess.Popen(['docker', 'start', '-ai', names[0]], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=docker_env,
            close_fds=True, start_new_session=True)
        executed = bounded(['docker', 'start', '-a', names[1]], timeout=35, limit=512)
        worker_result = p['response'](executed.output, nonce)
        expected_code = 0 if json.loads(worker_result)['result'] == 'equivalent' else 1
        state = bounded(['docker', 'inspect', '--format', '{{.State.ExitCode}}', names[1]])
        if state.output.strip() != str(expected_code).encode():
            raise RuntimeError('worker exit identity mismatch')
        # A refused worker may never open the FIFO. Stop the blocked synthetic
        # producer; no positive result is possible and finally owns all cleanup.
        if json.loads(worker_result)['result'] != 'unavailable':
            producer_result = p['response'](collect(process, worker_result), nonce)
            if producer_result == worker_result and process.returncode == expected_code:
                result = producer_result
    except (Exception, KeyboardInterrupt):
        result = p['public'](nonce)
    finally:
        # Cancellation must unwind through daemon-resource cleanup. Repeated
        # TERM/HUP/INT cannot interrupt cleanup or leave fixture volumes behind.
        def mark_cancelled(*unused):
            global CANCELLED
            CANCELLED = True
        saved_signals = {sig: signal.signal(sig, mark_cancelled) for sig in WATCHED}
        clean = True
        # Worker first: the transport volume remains referenced by the producer
        # until both containers have gone. Never delete by a global pattern.
        for name in reversed(names):
            try:
                bounded(['docker', 'rm', '-f', '-v', name])
                check = bounded(['docker', 'container', 'ls', '-aq', '--filter', 'name=^/'+name+'$'])
                clean &= check.code == 0 and check.output == b''
            except BaseException:
                clean = False
        if process is not None:
            try:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)
                process.stdin.close()
                process.stdout.close()
            except BaseException:
                clean = False
        for volume in volumes:
            try:
                listing = bounded(['docker', 'volume', 'ls', '-q', '--filter', 'name=^'+volume+'$'])
                clean &= listing.code == 0 and listing.output == b''
            except BaseException:
                clean = False
        if not clean or CANCELLED:
            result = p['public'](nonce)
        for sig, previous in saved_signals.items():
            signal.signal(sig, previous)
    return dict(case=case, result=json.loads(result)['result'], strategy=json.loads(result)['strategy'],
                cleanup='confirmed_absent' if clean else 'unverified')


def main():
    install_signals()
    # No runtime/image/root/project/input arguments: only reviewed built-in cases.
    if len(os.sys.argv) != 1:
        raise SystemExit('CLB192 local launcher takes no arguments')
    code = (HERE/'handoff.py').read_text()
    p = {'__name__': 'clb192_captured_protocol'}
    exec(compile(code, str(HERE/'handoff.py'), 'exec'), p)
    helper_raw = (HERE/'helper.py').read_bytes()
    if hashlib.sha256(helper_raw).hexdigest() != p['HELPER_SHA256']:
        raise SystemExit('CLB192 helper source mismatch')
    h = {'__name__': 'clb192_captured_helper'}
    exec(compile(helper_raw, str(HERE/'helper.py'), 'exec'), h)
    op = h['sources'](ROOT/'scripts/deploy')
    with tempfile.TemporaryDirectory(prefix='clb192-export-') as temporary:
        export = Path(temporary).resolve()
        sources = export/'scripts/deploy'
        sources.mkdir(parents=True)
        for name, digest in h['PINS'].items():
            raw = (ROOT/'scripts/deploy'/name).read_bytes()
            if hashlib.sha256(raw).hexdigest() != digest:
                raise SystemExit('CLB192 production source mismatch')
            (sources/name).write_bytes(raw)
        helper_dir = export/'scripts/tests/linux-semantic/isolated-helper'
        helper_dir.mkdir(parents=True)
        (helper_dir/'helper.py').write_bytes(helper_raw)
        records = []
        for case in ('remove', 'explicit', 'different', 'empty'):
            if CANCELLED:
                break
            records.append(run_case(h, p, op, export, code, case))
    print(json.dumps(dict(scope='CLB192_LOCAL_SYNTHETIC_ONLY', cases=records,
        handoff_sha256=hashlib.sha256(code.encode()).hexdigest(), image=IMAGE,
        native_acceptance='UNVERIFIED', stage_acceptance=False, transaction_readiness=False)))
    return 0 if [r['result'] for r in records] == ['equivalent', 'equivalent', 'different', 'equivalent'] else 2


if __name__ == '__main__':
    raise SystemExit(main())
