"""Run only in the fixed reference image; emit normalized limited-rootfs tar.

No installed package scripts run. All payload files are from the approved fixed
closure, except its host-wide loader cache (deliberately not exported). Bytecode
is independently regenerated from pinned source bytes on every invocation.
"""
import hashlib, io, json, os, pathlib, py_compile, stat, struct, subprocess, sys, tarfile

P = pathlib.Path
M = json.loads(P('/build/accepted-manifest.json').read_bytes())
assert len(M['files']) == 191
assert hashlib.sha256(P('/build/accepted-manifest.json').read_bytes()).hexdigest() == '93a9d29cba93770fab9cc6605709a3b159cfb7ce2c627677ff77bd9cacd62008'
assert hashlib.sha256(P(sys.executable).read_bytes()).hexdigest() == 'e50d468e8b0adfb05733f5b87b3cff34829c4a8c1aea50c865aa8bdfe4bb150f'
assert hashlib.sha256(P('/usr/sbin/ldconfig.real').read_bytes()).hexdigest() == 'd879c9a8a41240aeb7fd0f3221116fc4cf41cc6c64c24e10541d1bafbe537ab1'
entries = {}
metadata = {'pyc': [], 'ELF': [], 'source_image': 'sha256:7fac9bfeaaa66a163f206b80309432f608f079f36cddec25034b323b02612d2d'}
extra = json.loads(P('/build/additional-inputs.json').read_bytes())
archive = P('/input-libruby.deb').read_bytes()
assert hashlib.sha256(archive).hexdigest() == extra['archive']['sha256']
dump = subprocess.run(['/usr/bin/dpkg-deb', '--fsys-tarfile', '/input-libruby.deb'], capture_output=True, check=True, timeout=20)
assert len(dump.stdout) <= 33554432 and len(dump.stderr) <= 65536
archive_files = {}
with tarfile.open(fileobj=io.BytesIO(dump.stdout), mode='r:') as tf:
    for path, info in extra['files'].items():
        member = tf.getmember('.' + path)
        raw = tf.extractfile(member).read()
        assert member.isfile() and member.uid == member.gid == 0
        assert hashlib.sha256(raw).hexdigest() == info['sha256']
        assert raw == P(path).read_bytes()
        archive_files[path] = raw
metadata['additional_archive_inputs'] = extra

def add_file(path, data, mode=0o444, mtime=0):
    assert path.startswith('/') and '..' not in P(path).parts
    entries[path] = ('file', data, mode, mtime)

def add_link(path, target):
    entries[path] = ('symlink', target, 0o777, 0)

def dynamic(data):
    if not data.startswith(b'\x7fELF'):
        return [], None
    assert data[4:6] == b'\x02\x01', 'fixed ELF64 little endian input expected'
    assert struct.unpack_from('<H', data, 18)[0] == 62, 'amd64 only'
    phoff = struct.unpack_from('<Q', data, 32)[0]
    phsize, phnum = struct.unpack_from('<HH', data, 54)
    loads, dyn, interp = [], None, None
    for i in range(phnum):
        t, _, off, va, _, sz, _, _ = struct.unpack_from('<IIQQQQQQ', data, phoff + i * phsize)
        if t == 1: loads.append((va, va + sz, off))
        elif t == 2: dyn = (off, sz)
        elif t == 3: interp = data[off:off + sz].rstrip(b'\0').decode('ascii')
    if dyn is None: return [], interp
    tags = []
    for pos in range(dyn[0], dyn[0] + dyn[1], 16):
        tag, value = struct.unpack_from('<qQ', data, pos)
        if tag == 0: break
        tags.append((tag, value))
    strings = [v for k, v in tags if k == 5]
    needed = [v for k, v in tags if k == 1]
    if not needed: return [], interp
    assert len(strings) == 1
    ptr = strings[0]
    bases = [off + ptr - a for a, b, off in loads if a <= ptr < b]
    assert len(bases) == 1
    def s(offset):
        start = bases[0] + offset
        return data[start:data.index(b'\0', start)].decode('ascii')
    return [s(n) for n in needed], interp

wanted = dict(M['files'])
wanted.update({path: info['sha256'] for path, info in extra['files'].items()})
for path, expected in sorted(wanted.items()):
    p = P(path)
    raw = p.read_bytes()
    assert hashlib.sha256(raw).hexdigest() == expected, path
    assert p.stat().st_uid == p.stat().st_gid == 0 and not (p.stat().st_mode & 0o022)
    if path == '/etc/ld.so.cache': continue
    mtime = 1788171506 if path.endswith('.py') else 0
    if path.endswith('.pyc'):
        src = p.parent.parent / (p.name.split('.cpython-312')[0] + '.py')
        assert str(src) in M['files']
        assert int(src.stat().st_mtime) == 1788171506
        local = P('/tmp/build-source.py')
        local.write_bytes(src.read_bytes()); os.utime(local, (1788171506, 1788171506))
        out = '/tmp/generated.pyc'
        py_compile.compile(str(local), out, dfile=str(src), doraise=True, optimize=0, invalidation_mode=py_compile.PycInvalidationMode.TIMESTAMP)
        raw = P(out).read_bytes()
        assert hashlib.sha256(raw).hexdigest() == expected, path
        metadata['pyc'].append({'path': path, 'source': str(src), 'mtime': 1788171506, 'optimize': 0, 'mode': 'TIMESTAMP', 'sha256': expected})
        local.unlink(); P(out).unlink()
    add_file(path, raw, stat.S_IMODE(p.stat().st_mode) & ~0o222, mtime)
    needed, interp = dynamic(raw)
    if raw.startswith(b'\x7fELF'):
        metadata['ELF'].append({'path': path, 'needed': needed, 'interpreter': interp})
    for soname in needed:
        assert '/' not in soname
        lib = P('/usr/lib/x86_64-linux-gnu') / soname
        target = str(lib.resolve(strict=True))
        assert target in wanted, (path, soname, target)
        if lib.is_symlink(): add_link(str(lib), os.readlink(lib))
        else: assert str(lib) == target
    if interp:
        assert interp == '/lib64/ld-linux-x86-64.so.2'

for path in ('/bin', '/lib', '/lib64', '/usr/lib64/ld-linux-x86-64.so.2', '/usr/bin/python3', '/usr/bin/ruby', '/usr/bin/sh'):
    assert P(path).is_symlink(), path
    add_link(path, os.readlink(path))

# All generator inputs are public, exact and enumerated. This executable is
# removed before the final runtime image. Cache outputs never come from image A.
add_file('/usr/sbin/ldconfig.real', P('/usr/sbin/ldconfig.real').read_bytes(), 0o555)
add_file('/etc/ld.so.conf', b'/usr/lib/x86_64-linux-gnu\n')
add_file('/etc/passwd', b'root:x:0:0:root:/nonexistent:/usr/bin/dash\nprototype:x:1000:1000:synthetic principal:/nonexistent:/usr/bin/dash\n')
add_file('/etc/group', b'root:x:0:\nprototype:x:1000:\n')
dirs = {'/tmp', '/run', '/run/user', '/run/user/1000', '/opt', '/opt/clubs-bot-stage', '/proc', '/dev', '/work'}
for path in entries:
    dirs.update(str(p) for p in P(path).parents if str(p) != '/')
dirs -= set(entries)
out = tarfile.open(fileobj=sys.stdout.buffer, mode='w|', format=tarfile.USTAR_FORMAT)
for path in sorted(dirs | set(entries)):
    ti = tarfile.TarInfo(path.lstrip('/')); ti.uid = ti.gid = 0; ti.uname = ti.gname = ''; ti.mtime = 0
    if path in dirs: ti.type = tarfile.DIRTYPE; ti.mode = 0o555; out.addfile(ti); continue
    kind, data, mode, mtime = entries[path]; ti.mode = mode; ti.mtime = mtime
    if kind == 'symlink': ti.type = tarfile.SYMTYPE; ti.linkname = data; out.addfile(ti)
    else: ti.size = len(data); out.addfile(ti, io.BytesIO(data))
out.close()
sys.stderr.write(json.dumps(metadata, sort_keys=True) + '\n')
