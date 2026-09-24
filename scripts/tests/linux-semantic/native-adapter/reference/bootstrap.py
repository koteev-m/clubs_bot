
import sys, os, json, struct, hashlib, hmac, signal, types, re, base64
watched = (signal.SIGHUP, signal.SIGINT, signal.SIGTERM, signal.SIGALRM)
cancelled = [False]
modules = {}
def stop(*unused):
    cancelled[0] = True
    capture = getattr(modules.get('stage-compose-diagnostic-operation.py'), '_capture_cancellation', None)
    if capture is not None: capture[0] = True
    else: raise InterruptedError()
def unique(pairs):
    result = {}
    for key, value in pairs:
        assert key not in result
        result[key] = value
    return result
for sig in watched: signal.signal(sig, stop)
signal.alarm(90)
try:
    data = sys.stdin.buffer.read(525349)
    assert 36 < len(data) <= 525348
    nonce, size = data[:32], struct.unpack('!I', data[32:36])[0]
    assert 0 < size <= 1024 and len(data) > 36 + size
    control = json.loads(data[36:36+size], object_pairs_hook=unique)
    assert set(control) == {'principal', 'sha256'}
    assert type(control['principal']) is str and re.fullmatch('[a-zA-Z0-9_][a-zA-Z0-9._-]*', control['principal'])
    encoded = data[36+size:]
    assert len(encoded) <= 524288 and hashlib.sha256(encoded).hexdigest() == control['sha256']
    sources = json.loads(encoded, object_pairs_hook=unique)
    names = ('stage-compose-diagnostic-operation.py', 'stage-compose-env-file-plan.py',
             'release_private_root.py', 'stage-compose-env-semantic-operation.py',
             'stage-compose-env-semantic-runtime.json')
    assert set(sources) == set(names)
    raw = {name: base64.b64decode(sources[name], validate=True) for name in names}
    assert all(0 < len(value) <= 65536 for value in raw.values())
    manifest = json.loads(raw[names[-1]], object_pairs_hook=unique)
    # Verify and compile the complete captured closure before its first exec.
    compiled = {name: compile(raw[name], '/clb91-source/scripts/deploy/'+name, 'exec') for name in names[:-1]}
    for name in names[:-1]:
        assert not cancelled[0]
        module = types.ModuleType('fixed_private_semantic')
        module.__file__ = '/clb91-source/scripts/deploy/'+name
        exec(compiled[name], module.__dict__)
        modules[name] = module
    module = modules[names[3]]
    module.D, module.P, module.S, module.MANIFEST = modules[names[0]], modules[names[1]], modules[names[2]], manifest
    body = module.diagnose(control['principal'], lambda: cancelled[0])
    signal.pthread_sigmask(signal.SIG_BLOCK, watched)
    signal.alarm(0)
    if cancelled[0] or set(signal.sigpending()).intersection(watched): body = module.interrupted_body(body)
    code = 1 if b' result=unavailable ' in body else 0
    module.parse_body(body, code)
    report = {'body': body.decode('ascii'), 'cleanup_errors': module.LAST_CLEANUP_ERRORS}
    assert all(x in ('capture_close','runtime_close') for x in report['cleanup_errors'])
    public = json.dumps(report,sort_keys=True,separators=(',',':')).encode('ascii')
    tag = hmac.new(nonce, public, hashlib.sha256).hexdigest().encode('ascii')
    frame = b'clb91-isolated-auth:v=1 tag=' + tag + b' ' + public + b'\n'
    assert len(frame) <= 4096 and os.write(1, frame) == len(frame)
except BaseException:
    sys.exit(1)
sys.exit(code)
