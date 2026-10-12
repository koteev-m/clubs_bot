"""Fixed synthetic native bootstrap. Embedded by build.py, never a stage CLI."""
import base64
import hashlib
import hmac
import json
import os
import signal
import stat
import struct
import sys
import types

BUNDLE_SHA = '@BUNDLE_SHA@'
FRAME_BYTES = 0  # replaced only in the generated test contract


def need(value):
    if not value:
        raise ValueError('namespace_bootstrap_refused')


def unique(pairs):
    result = {}
    for key, value in pairs:
        need(key not in result)
        result[key] = value
    return result


def read_exact(fd, size):
    out = bytearray()
    while len(out) < size:
        part = os.read(fd, size - len(out))
        need(part)
        out.extend(part)
    return bytes(out)


def write_all(fd, raw):
    view = memoryview(raw)
    while view:
        count = os.write(fd, view)
        need(count > 0)
        view = view[count:]


def read_bounded(fd, limit):
    need(stat.S_ISFIFO(os.fstat(fd).st_mode))
    out = bytearray()
    while len(out) <= limit:
        part = os.read(fd, min(65536, limit + 1 - len(out)))
        if not part:
            return bytes(out)
        out.extend(part)
    raise ValueError('namespace_bootstrap_refused')


def load(raw):
    need(36 < len(raw) == FRAME_BYTES <= 525348)
    nonce, size = raw[:32], struct.unpack('!I', raw[32:36])[0]
    need(0 < size <= 1024)
    control = json.loads(raw[36:36+size], object_pairs_hook=unique)
    encoded = raw[36+size:]
    need(control == {'principal': 'prototype', 'sha256': BUNDLE_SHA})
    need(hashlib.sha256(encoded).hexdigest() == BUNDLE_SHA)
    sources = json.loads(encoded, object_pairs_hook=unique)
    names = ('stage-compose-diagnostic-operation.py', 'stage-compose-env-file-plan.py',
             'release_private_root.py', 'stage-compose-env-semantic-operation.py',
             'stage-compose-env-semantic-runtime.json', 'helper.py', 'handoff.py')
    need(set(sources) == set(names))
    sources = {name: base64.b64decode(sources[name], validate=True) for name in names}
    need(all(0 < len(value) <= 65536 for value in sources.values()))
    # Complete source bundle is authenticated before compile or execution.
    compiled = {name: compile(raw, '/clb195-source/scripts/deploy/'+name, 'exec')
                for name, raw in sources.items() if name.endswith('.py')}
    modules = {}
    for name, code in compiled.items():
        module = types.ModuleType('clb195_captured')
        module.__file__ = '/clb195-source/scripts/deploy/'+name
        exec(code, module.__dict__)
        modules[name] = module
    op = modules[names[3]]
    op.D, op.P, op.S = (modules[n] for n in names[:3])
    op.MANIFEST = json.loads(sources[names[4]], object_pairs_hook=unique)
    op.P.DIAGNOSTIC, op.P.CAPTURE, op.P.SAFE_ROOT = vars(op.D), op.D.capture_result, op.S.open_canonical_root
    return nonce, op, vars(modules['helper.py']), modules['handoff.py']


def produce(op, protocol, nonce, exchange, environ, cancelled):
    """Actual Runtime, capture and locks; no fixture creation or trust shortcut."""
    runtime = op.Runtime(cancelled)
    context = None
    result = None
    cleanup = []
    try:
        runtime.open()
        runtime.private_root('/run/user/1000')
        runtime.require_compatible()
        runtime.ruby_probe('/run/user/1000')
        runtime.recheck()
        need(not cancelled())
        # environ is a lazy reader: no interpolation copy before Runtime.
        # The fixed C exec environment is complete, not a selected projection.
        values = environ()
        need(values == {'PATH': '/usr/bin:/bin', 'HOME': '/run/user/1000', 'LC_ALL': 'C'})
        interpolation = op.interpolation_context(values)
        context = op.D.ReadOnlyCapture('prototype', cancelled)
        raw = protocol.snapshot(op, context, nonce, interpolation)
        runtime.recheck()
        result = protocol.response(exchange(raw), nonce)
        context.recheck()
        op.D.require(context.mount_identity() == context.backing, 'backing')
        runtime.recheck()
        need(not cancelled())
    finally:
        if context is not None:
            try:
                context.close()
            except BaseException:
                cleanup.append('capture_close')
        try:
            runtime.close()
        except BaseException:
            cleanup.append('runtime_close')
        # No positive result may escape a cleanup failure, including on refusal.
        need(not cleanup)
    return result


def main():
    cancelled = [False]
    op = None
    def stop(*unused):
        cancelled[0] = True
        if op is not None:
            op.D._capture_cancellation[0] = True
        raise InterruptedError()
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP, signal.SIGALRM):
        signal.signal(sig, stop)
    signal.alarm(60)
    nonce = None
    try:
        need(len(sys.argv) == 2 and sys.argv[1] in ('producer', 'worker'))
        need(os.getuid() == os.geteuid() == 1000)
        nonce, op, helper, protocol = load(read_exact(0, FRAME_BYTES))
        os.close(0)
        request = nonce[:16].hex()
        if sys.argv[1] == 'worker':
            # Parent C code gives this process a separate mount/PID/network view.
            # No source files or producer /proc entries exist in that view.
            need(os.getpid() == 1 and os.listdir('/opt/clubs-bot-stage') == [])
            need(not os.path.exists('/var/run/docker.sock'))
            result = protocol.worker(helper, op, request,
                lambda: read_bounded(3, protocol.LIMIT), lambda: cancelled[0])
            os.close(3)
            write_all(1, result)
            return 0
        def exchange(raw):
            need(len(raw) <= protocol.LIMIT)
            write_all(3, raw)
            os.close(3)  # EOF terminates exactly one snapshot.
            reply = read_bounded(4, 512)
            os.close(4)
            return reply
        result = json.loads(produce(op, protocol, request, exchange,
            lambda: dict(os.environ), lambda: cancelled[0]))
        if result['result'] == 'equivalent':
            body = ('compose-env-semantic:v=1 result=equivalent strategy='+result['strategy']+
                    ' scope=snapshot future=requires_recheck application=not_authorized\n')
            code = 0
        else:
            body, code = 'compose-env-semantic:v=1 result=unavailable reason=different\n', 1
        op.parse_body(body.encode(), code)
        report = json.dumps(dict(body=body, cleanup_errors=[]), sort_keys=True, separators=(',', ':')).encode()
        tag = hmac.new(nonce, report, hashlib.sha256).hexdigest().encode()
        frame = b'clb91-isolated-auth:v=1 tag='+tag+b' '+report+b'\n'
        need(len(frame) <= 4096 and not cancelled[0])
        write_all(1, frame)
        return code
    except BaseException:
        # No private exception, partial response, source or stderr publication.
        return 1
    finally:
        signal.alarm(0)


if __name__ == '__main__':
    raise SystemExit(main())
