"""Build-only exact native-adapter variant; no execution, downloads or privilege."""
import base64
import hashlib
import json
from pathlib import Path
import re
import struct

HERE = Path(__file__).resolve().parent
NATIVE = HERE.parent/'native-adapter'
HELPER = HERE.parent/'isolated-helper'
PINS = {
    'adapter/adapter.c': '0f863eb458fd6fdec70aed3271bcc4e5ee33a50512d66b475d7bc84b775a712c',
    'adapter/generated_contract.h': '1426c767a5e3b1c6b8b9f80107d74350ff5fef3602ccb6b8dace3693dc643962',
    'tests/native-fixture-runner.c': '9bd541c682d21960bc8b21872141a7b715bd984b7fefee5e2fad39e43896c887',
    'tests/native-driver.py': '43a0bb200df68072d086bfcf0a48809aa60c8b1abec45f0e5e6195fd1184fa63',
    'adapter/fd-budget.h': 'd1d3d0bd7323e4f46a2719fa6fd963d154ea9d3af3709ec69045c907816e48b6',
    'adapter/core-tests.c': 'cfd498380420383e9edc188e5dd8d5602dcb910bced311cfc17822142a682bec',
    'tests/fixture-bind-probe.h': '234c95671f2956e9965926ebc30fb51ea5e0e28b36c1fc6f3d5f1a211ab09037',
    'tests/fixture-namespace-handles.h': '730f2088e3a715b017a531e2b491ff6ec8542724dcfcba9844109772bf7db61a',
    'tests/fixture-resource-envelope.h': 'ad99fbd427104779c928d50d12f045b4660cc143cdb563c6269b42a7e8967c4d',
}
HELPER_PINS = {'helper.py': 'db2f01218abb739ec172d1c3ad037eb1efa2cd098db315b84d06dcaae73ea39e',
               'handoff.py': '5e3f74917525585419eaaa12851092b3b3471097d512160ebcddfeed5e8baa54'}


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def checked(path, digest):
    if path.is_symlink() or not path.is_file():
        raise ValueError('source_type')
    raw = path.read_bytes()
    if len(raw) > 1048576 or sha(raw) != digest:
        raise ValueError('source_identity')
    return raw


def array(name, raw):
    values = raw+b'\0'
    return ('static const unsigned char '+name+'[] = {\n'+
            '\n'.join(','.join('0x%02x'%b for b in values[i:i+24])+','
                      for i in range(0, len(values), 24))+'\n};\n'+
            '#define '+name+'_LEN (sizeof('+name+')-1)')


def materialize(dest, driver, expected=None):
    """Only the reviewed namespace body and embedded bootstrap change."""
    driver['source_identity']()  # original reference ledger/rootfs/codegen pins
    raw = {name: checked(NATIVE/name, digest) for name, digest in PINS.items()}
    helpers = {name: checked(HELPER/name, digest) for name, digest in HELPER_PINS.items()}
    local = {n: checked(HERE/n,expected[n]) if expected is not None else (HERE/n).read_bytes()
             for n in ('build.py','bootstrap.py','namespace.h')}
    reference = NATIVE/'reference'
    pins = json.loads(checked(reference/'pins.json',driver['PINS_SHA']))
    names = ('stage-compose-diagnostic-operation.py', 'stage-compose-env-file-plan.py',
             'release_private_root.py', 'stage-compose-env-semantic-operation.py')
    sources = {name: checked(reference/name,pins[name]['sha256']) for name in names}
    sources['stage-compose-env-semantic-runtime.json'] = checked(reference/'candidate-runtime.json',pins['candidate-runtime.json']['sha256'])
    sources.update(helpers)
    encoded = json.dumps({n: base64.b64encode(v).decode() for n,v in sources.items()},
                         sort_keys=True, separators=(',', ':')).encode()
    control = json.dumps(dict(principal='prototype', sha256=sha(encoded)),
                         sort_keys=True, separators=(',', ':')).encode()
    tail = struct.pack('!I', len(control))+control+encoded
    bootstrap = local['bootstrap.py'].decode().replace("'@BUNDLE_SHA@'", repr(sha(encoded)))
    bootstrap = bootstrap.replace('FRAME_BYTES = 0 ', 'FRAME_BYTES = '+str(32+len(tail))+' ')
    compile(bootstrap, 'clb195-bootstrap', 'exec')
    if len(bootstrap.encode()) > 16384 or len(tail) > 525316:
        raise ValueError('contract_bound')
    text = raw['adapter/adapter.c'].decode()
    start = text.index('static void namespace_child(const struct namespace_args *a)')
    end = text.index('static int namespace_start(void*opaque)', start)
    # Old function closes immediately before namespace_start; replacement is fixed.
    text = text[:start]+local['namespace.h'].decode()+'\n'+text[end:]
    header = raw['adapter/generated_contract.h'].decode()
    for name, data in [('BOOTSTRAP', bootstrap.encode()), ('REQUEST_TAIL', tail)]:
        pattern = r'static const unsigned char '+name+r'\[\] = \{.*?\n\};\n#define '+name+r'_LEN \(sizeof\('+name+r'\)-1\)'
        header, count = re.subn(pattern, lambda _: array(name, data), header, flags=re.S)
        if count != 1:
            raise ValueError('contract_shape')
    dest.mkdir(mode=0o700)
    for name in ('adapter', 'tests'):
        (dest/name).mkdir()
    (dest/'adapter/adapter.c').write_text(text)
    (dest/'adapter/generated_contract.h').write_text(header)
    # Reuse exact original core/lifecycle and synthetic original-object tests.
    for name in ('adapter/fd-budget.h', 'adapter/core-tests.c', 'tests/native-fixture-runner.c',
                 'tests/fixture-bind-probe.h', 'tests/fixture-namespace-handles.h', 'tests/fixture-resource-envelope.h'):
        (dest/name).write_bytes(raw[name])
    fixture = raw['tests/native-fixture-runner.c'].decode()
    if fixture.count('compose_project=clb91-prototype') != 1:
        raise ValueError('fixture_project')
    (dest/'tests/native-fixture-runner.c').write_text(
        fixture.replace('compose_project=clb91-prototype', 'compose_project=clubs-bot-stage'))
    return dict(bundle=sha(encoded), bootstrap=sha(bootstrap.encode()),
                adapter=sha(text.encode()), header=sha(header.encode()),
                local_sources={n:sha(raw) for n,raw in local.items()})
