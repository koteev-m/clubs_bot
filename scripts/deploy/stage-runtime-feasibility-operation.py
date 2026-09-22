"""Fixed non-secret closure observations, never an installer or runtime approval.

I, REFERENCE, INPUTS and PACKAGE_TEXT are verified captured sources/data supplied
by the bootstrap. I is the unchanged inventory safety/protocol primitive module.
No discovery, subprocess, environment, target config, or filesystem mutation.
"""
import errno
import hashlib
import json
import os
import re
import stat
import sys
import time

PREFIX = 'runtime-feasibility:v=1 '
BODY_LIMIT = 131072
MAX_FDS = 320
MAX_BYTES = 256 * 1024 * 1024
MAX_FILE = 64 * 1024 * 1024
REFERENCE_SHA = '93a9d29cba93770fab9cc6605709a3b159cfb7ce2c627677ff77bd9cacd62008'
DOCKER = ('/usr/bin/docker', '/usr/local/bin/docker', '/usr/bin/docker-compose',
          '/usr/lib/docker/cli-plugins/docker-compose', '/usr/libexec/docker/cli-plugins/docker-compose',
          '/usr/local/lib/docker/cli-plugins/docker-compose')
PACKAGES = ('python3', 'python3-minimal', 'python3.12', 'python3.12-minimal',
    'libpython3-stdlib', 'libpython3.12-minimal', 'libpython3.12-stdlib',
    'ruby', 'ruby3.2', 'libruby', 'libruby3.2', 'ruby-psych', 'dash', 'util-linux',
    'libc6', 'libc-bin', 'libblkid1', 'libbz2-1.0', 'libcap2', 'libcrypt1',
    'libssl3t64', 'libexpat1', 'libffi8', 'libgmp10', 'liblzma5', 'libmount1',
    'libpcre2-8-0', 'libselinux1', 'libsmartcols1', 'libudev1', 'libyaml-0-2', 'zlib1g',
    'docker-ce-cli', 'docker.io', 'docker-compose', 'docker-compose-v2', 'docker-compose-plugin')
PACKAGE_FILE = '/var/lib/dpkg/status'
UNKNOWN = ('unsafe', 'permission', 'malformed', 'unsupported', 'metadata_missing')
REASONS = I.REASONS
need, identity = I.need, I.identity


def canonical(value):
    return (PREFIX + json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True) + '\n').encode('ascii')


def refused(reason):
    return canonical(dict(result='unavailable', reason=reason if reason in REASONS else 'io'))


def paths():
    need(REFERENCE['format'] == 1 and REFERENCE['architecture'] == 'x86_64')
    need(len(REFERENCE['files']) == 191 and len(REFERENCE['aliases']) == 4)
    return tuple(sorted(REFERENCE['files'])) + DOCKER


class SystemFiles(I.SystemFiles):
    """Retain a bounded directory DAG, leaves and negative observations.

    Unlike the short inventory, share parent descriptors to cover the full 191
    files without reopening mutable ancestors or exhausting descriptor bounds.
    """
    def __init__(self, cancelled):
        super().__init__(cancelled)
        self.directories, self.leaves, self.observed = {}, {}, []
        self.allowed = frozenset((*paths(), PACKAGE_FILE))
        self.deadline = time.monotonic() + 75

    def keep(self, fd):
        self.fds.append(fd)
        need(len(self.fds) <= MAX_FDS, 'bounds')
        return fd

    def safe(self, value, kind):
        good = (stat.S_ISDIR(value.st_mode) if kind == 'directory' else
                stat.S_ISREG(value.st_mode) and value.st_nlink == 1)
        if not (good and value.st_uid == 0 and not value.st_mode & 0o022):
            raise I.Observation('unsafe')

    def directory(self, path):
        self.tick()
        if path in self.directories: return self.directories[path]
        if path == '/':
            fd = self.keep(os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW))
            value = os.fstat(fd)
            self.roots.append((fd, identity(value)))
            self.safe(value, 'directory')
        else:
            parent_path, name = path.rsplit('/', 1)
            parent = self.directory(parent_path or '/')
            value = self.metadata(parent, name)
            self.safe(value, 'directory')
            fd = self.keep(os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent))
            actual = os.fstat(fd)
            need(identity(value) == identity(actual), 'identity')
            self.edges.append((parent, name, fd, identity(actual)))
            self.safe(actual, 'directory')
        self.directories[path] = fd
        return fd

    def metadata(self, parent, name):
        try: value = os.stat(name, dir_fd=parent, follow_symlinks=False)
        except FileNotFoundError:
            self.missing.append((parent, name)); raise
        self.observed.append((parent, name, identity(value)))
        return value

    def open(self, path, hops=0):
        self.tick()
        need(path in self.allowed, 'request')
        if path in self.leaves: return self.leaves[path], path, 'direct'
        parent_path, name = path.rsplit('/', 1)
        parent = self.directory(parent_path)
        value = self.metadata(parent, name)
        self.safe(value, 'file')  # No file symlink, even into the allowlist.
        fd = self.keep(os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent))
        actual = os.fstat(fd)
        need(identity(value) == identity(actual), 'identity')
        self.edges.append((parent, name, fd, identity(actual)))
        self.safe(actual, 'file')
        self.leaves[path] = fd
        return fd, path, 'direct'

    def recheck(self):
        try:
            super().recheck()
            for parent, name, before in self.observed:
                need(identity(os.stat(name, dir_fd=parent, follow_symlinks=False)) == before, 'identity')
        except FileNotFoundError:
            raise I.Unavailable('identity') from None

    def read(self, path, limit, digest=False):
        fd, final, layout = self.open(path)
        before = identity(os.fstat(fd))
        need(before[6] <= limit, 'bounds')
        self.recheck()
        chunks, size, hasher = [], 0, hashlib.sha256()
        while True:
            self.tick()
            data = os.read(fd, min(65536, limit + 1 - size))
            self.total += len(data); size += len(data)
            need(self.total <= MAX_BYTES and size <= limit, 'bounds')
            if not data: break
            if digest: hasher.update(data)
            else: chunks.append(data)
        need(identity(os.fstat(fd)) == before, 'identity')
        self.recheck()
        return (hasher.hexdigest() if digest else b''.join(chunks)), final, layout


def observation(error):
    if isinstance(error, I.Observation): return error.args[0]
    if isinstance(error, OSError):
        if error.errno == errno.ENOENT: return 'missing'
        if error.errno in (errno.EACCES, errno.EPERM): return 'permission'
        if error.errno in (errno.ELOOP, errno.ENOTDIR): return 'unsafe'
    raise error


def artifact(files, path):
    row = dict(status='unknown', reason='unsafe', sha256=None, mode=None, size=None)
    try:
        digest, _, _ = files.read(path, MAX_FILE, digest=True)
        value = os.fstat(files.leaves[path])
        expected = REFERENCE['files'].get(path)
        row.update(status=('match' if digest == expected else 'mismatch') if expected else 'observed',
                   reason='none', sha256=digest, mode=format(stat.S_IMODE(value.st_mode), '04o'), size=value.st_size)
    except (I.Observation, OSError) as error:
        reason = observation(error)
        row.update(status='missing' if reason == 'missing' else 'unknown', reason=reason)
    return row


def alias(files, path, expected):
    row = dict(status='unknown', reason='unsafe', target='not_observed')
    try:
        actual_path = path
        if path.startswith('/bin/'):
            root = files.directory('/')
            v = files.metadata(root, 'bin')
            if not (stat.S_ISLNK(v.st_mode) and v.st_uid == 0 and v.st_nlink == 1):
                raise I.Observation('unsafe')
            target = os.readlink('bin', dir_fd=root)
            files.links.append((root, 'bin', identity(v), target))
            if target not in ('usr/bin', '/usr/bin'): raise I.Observation('unsafe')
            actual_path = '/usr/bin/' + path.rsplit('/', 1)[1]
        parent_path, name = actual_path.rsplit('/', 1)
        parent = files.directory(parent_path)
        v = files.metadata(parent, name)
        if stat.S_ISLNK(v.st_mode):
            if v.st_uid != 0 or v.st_nlink != 1: raise I.Observation('unsafe')
            target = os.readlink(name, dir_fd=parent)
            need(len(target) <= 4096, 'bounds')
            files.links.append((parent, name, identity(v), target))
            resolved = os.path.normpath(target if target.startswith('/') else parent_path+'/'+target)
        else:
            files.safe(v, 'file'); resolved = actual_path
        # Never open a link target, including a mismatching approved path.
        row.update(status='match' if resolved == expected else 'mismatch', reason='none',
                   target=resolved if resolved in REFERENCE['files'] else 'outside_allowlist')
    except (I.Observation, OSError) as error:
        reason = observation(error); row.update(status='missing' if reason == 'missing' else 'unknown', reason=reason)
    return row


def packages(files):
    def blank(status, reason):
        return {name:dict(status=status, reason=reason, version=None, architecture=None, hold=None, selection=None) for name in PACKAGES}
    try: raw, _, _ = files.read(PACKAGE_FILE, 4*1024*1024)
    except (I.Observation, OSError) as error:
        reason = observation(error)
        return blank('unknown', 'metadata_missing' if reason == 'missing' else reason)
    try:
        stanzas = raw.decode('utf-8').split('\n\n')
        need(len(stanzas) <= 8192 and all(len(s.encode('utf-8')) <= 65536 for s in stanzas), 'bounds')
        result, seen = blank('missing', 'missing'), set()
        for stanza in stanzas:
            if not stanza.strip(): continue
            fields, previous = {}, None
            selected = ('Package','Status','Version','Architecture')
            for line in stanza.splitlines():
                if line[:1].isspace():
                    if previous in selected: raise ValueError()
                    continue
                key, colon, tail = line.partition(':')
                previous = key
                if key in selected:
                    if not colon or not tail.startswith(' ') or key in fields: raise ValueError()
                    fields[key] = tail[1:]
            name = fields.get('Package')
            # Missing rows require a fully parsed package-record identity set.
            # Malformed headers must not masquerade as absence of a selected pkg.
            if type(name) is not str or not re.fullmatch('[a-z0-9][a-z0-9+.-]{1,127}',name):
                raise ValueError()
            if name not in PACKAGES: continue
            if name in seen: raise ValueError()
            seen.add(name)
            status = fields.get('Status','').split()
            version, arch = fields.get('Version',''), fields.get('Architecture')
            if (len(status) != 3 or status[0] not in ('install','hold','deinstall','purge','unknown')
                or status[1:] != ['ok','installed'] or not I.VERSION.fullmatch(version) or arch not in I.PACKAGE_ARCHES):
                result[name].update(status='unknown',reason='unsupported'); continue
            result[name].update(status='observed',reason='none',version=version,architecture=arch,hold=status[0]=='hold',selection=status[0])
        return result
    except (UnicodeError, ValueError): return blank('unknown','malformed')


def private_root(files):
    # Metadata only for exactly /run/user/<current accepted principal>. No listing
    # or content read, creation, chmod, environment lookup or public UID/path.
    row = dict(status='unknown', reason='unsafe', owner_access=False, mode=None)
    try:
        parent = files.directory('/run/user')
        name = str(os.geteuid()); value = files.metadata(parent, name)
        if not (stat.S_ISDIR(value.st_mode) and value.st_uid == os.geteuid() and not value.st_mode & 0o022):
            raise I.Observation('unsafe')
        fd = files.keep(os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent))
        need(identity(value)==identity(os.fstat(fd)), 'identity')
        files.edges.append((parent,name,fd,identity(value)))
        row.update(status='observed',reason='none',owner_access=bool(value.st_mode & 0o700 == 0o700),mode=format(stat.S_IMODE(value.st_mode),'04o'))
    except (I.Observation,OSError) as error:
        reason=observation(error); row.update(status='missing' if reason=='missing' else 'unknown',reason=reason)
    return row


def own_closure(files):
    def classify(values):
        need(len(values)<=1024, 'bounds')
        known=sorted(set(v for v in values if v in REFERENCE['files']))
        return dict(known=known, outside_allowlist=len(set(v for v in values if v not in REFERENCE['files'])))
    # Read only this process's procfs, never follow the executable link for reads.
    try: executable=os.readlink('/proc/self/exe')
    except OSError as error:
        if error.errno not in (errno.ENOENT,errno.EACCES,errno.EPERM): raise
        executable=None
    modules=[]
    for module in tuple(sys.modules.values()):
        for attr in ('__file__','__cached__'):
            value=getattr(module,attr,None)
            if type(value) is str and value and not value.startswith('/clb91-source/'):
                modules.append(value)
    maps=dict(known=[],outside_allowlist=0,status='unknown',reason='permission')
    fd=None
    try:
        # /proc/self is a kernel alias to this process only; no raw path is emitted.
        fd=files.keep(os.open('/proc/self/maps',os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK))
        need(stat.S_ISREG(os.fstat(fd).st_mode),'identity')
        chunks=[]; size=0
        while True:
            files.tick(); data=os.read(fd,min(65536,65537-size)); size+=len(data)
            need(size<=65536,'bounds')
            if not data: break
            chunks.append(data)
        raw=b''.join(chunks).decode('ascii')
        values=[]
        for line in raw.splitlines():
            fields=line.split(None,5)
            if len(fields)<5: raise ValueError()
            if len(fields)==6 and fields[-1].startswith('/'): values.append(fields[-1])
        maps=dict(classify(values),status='observed',reason='none')
    except OSError as error:
        reason=observation(error); maps['reason']='unsupported' if reason=='missing' else reason
    except (UnicodeError,ValueError): maps['reason']='malformed'
    return dict(executable=executable if executable in REFERENCE['files'] else 'outside_allowlist',
                modules=classify(modules), maps=maps)


def comparison(value):
    """Local deterministic comparison, also recomputed by the strict consumer.

    Package metadata is not cryptographic provenance. No transaction is proposed.
    """
    expected=dict(line.split('=',1) for line in PACKAGE_TEXT.splitlines())
    archives={p['package']:p for p in INPUTS['packages']}
    package_rows={}
    for name,row in value['packages'].items():
        target=expected.get(name)
        arch=archives.get(name,{}).get('architecture')
        package_rows[name]=dict(reference_version=target, reference_architecture=arch,
            architecture_comparison='not_recorded' if arch is None else 'unknown' if row['architecture'] is None else
                'match' if row['architecture']==arch else 'mismatch',
            comparison='unknown' if row['status']=='unknown' else 'missing' if row['status']=='missing' else
                'not_in_reference' if target is None else 'version_match' if row['version']==target else 'version_mismatch',
            provenance='signed_archive_reference_available' if name in archives else
                       'official_base_reference' if target else 'not_established')
    groups={'python':[], 'ruby_psych':[], 'system_libraries':[], 'loader_cache':[], 'other':[]}
    for path,row in value['files'].items():
        if row['status']=='match': continue
        group=('loader_cache' if path=='/etc/ld.so.cache' else 'python' if '/python' in path else
            'ruby_psych' if '/ruby' in path or '/libruby' in path else
            'system_libraries' if '/usr/lib/' in path else 'other')
        groups[group].append(path)
    return dict(files={k:sum(r['status']==k for r in value['files'].values()) for k in ('match','mismatch','missing','unknown')},
        packages=package_rows, decisions=groups,
        package_provenance='host_not_authenticated', installation='separate_transaction_review_required',
        recovery_toolchain='not_approved', runtime_acceptance='not_proven')


def partial(value):
    c=value['closure']
    return (any(row['status']=='unknown' for group in ('files','aliases','docker','packages') for row in value[group].values())
        or any(v=='unknown' for v in value['bootstrap'].values())
        or value['private_root']['status']!='observed' or c['maps']['status']!='observed'
        or c['maps']['outside_allowlist'] or c['modules']['outside_allowlist'] or c['executable']=='outside_allowlist')


def collect(cancelled):
    files=SystemFiles(cancelled); body=refused('io')
    try:
        value=dict(result='observed',trust='observed_not_approved',reference_sha256=REFERENCE_SHA,
            files={p:artifact(files,p) for p in sorted(REFERENCE['files'])},
            aliases={p:alias(files,p,v) for p,v in REFERENCE['aliases'].items()},
            docker={p:artifact(files,p) for p in DOCKER}, packages=packages(files),
            bootstrap=I.bootstrap_info(),private_root=private_root(files))
        value['closure']=own_closure(files)
        files.recheck()
        value['comparison']=comparison(value)
        value['completeness']='partial' if partial(value) else 'complete'
        body=canonical(value); need(len(body)<=BODY_LIMIT,'bounds')
    except I.Unavailable as error: body=refused(error.args[0])
    except (KeyboardInterrupt,InterruptedError): body=refused('interrupted')
    except BaseException: body=refused('io')
    finally:
        try: files.close()
        except BaseException: body=refused('cleanup')
    if cancelled(): body=refused('interrupted')
    return body


def parse_body(body, code):
    need(type(code) is int and code in (0,1))
    need(type(body) is bytes and len(body)<=BODY_LIMIT and body.startswith(PREFIX.encode()) and body.endswith(b'\n'))
    value=json.loads(body[len(PREFIX):-1],object_pairs_hook=I.unique)
    need(canonical(value)==body)
    if value.get('result')=='unavailable':
        need(code==1 and set(value)=={'result','reason'} and value['reason'] in REASONS)
        return body.decode().rstrip('\n'),code
    need(code==0 and set(value)=={'result','trust','reference_sha256','files','aliases','docker','packages','bootstrap','private_root','closure','comparison','completeness'})
    need(value['result']=='observed' and value['trust']=='observed_not_approved' and value['reference_sha256']==REFERENCE_SHA)
    def state(row, positive):
        need(type(row) is dict and row['status'] in (*positive,'missing','unknown'))
        need(row['reason']==('missing' if row['status']=='missing' else 'none') if row['status']!='unknown' else row['reason'] in UNKNOWN)
    for field,expected in (('files',REFERENCE['files']),('docker',DOCKER)):
        need(set(value[field])==set(expected))
        for path,row in value[field].items():
            need(set(row)=={'status','reason','sha256','mode','size'})
            state(row,('match','mismatch') if field=='files' else ('observed',))
            if row['status'] in ('match','mismatch','observed'):
                need(type(row['sha256']) is str and re.fullmatch('[0-9a-f]{64}',row['sha256']))
                need(type(row['mode']) is str and re.fullmatch('[0-7]{4}',row['mode']) and not int(row['mode'],8)&0o022)
                need(type(row['size']) is int and 0<=row['size']<=MAX_FILE)
                if field=='files': need((row['sha256']==REFERENCE['files'][path])==(row['status']=='match'))
            else: need(row['sha256'] is row['mode'] is row['size'] is None)
    need(set(value['aliases'])==set(REFERENCE['aliases']))
    for path,row in value['aliases'].items():
        need(set(row)=={'status','reason','target'}); state(row,('match','mismatch'))
        if row['status'] in ('match','mismatch'):
            need(row['target'] in (*REFERENCE['files'],'outside_allowlist'))
            need((row['target']==REFERENCE['aliases'][path])==(row['status']=='match'))
        else: need(row['target']=='not_observed')
    need(set(value['packages'])==set(PACKAGES))
    for row in value['packages'].values():
        need(set(row)=={'status','reason','version','architecture','hold','selection'}); state(row,('observed',))
        if row['status']=='observed':
            need(type(row['version']) is str and I.VERSION.fullmatch(row['version']) and row['architecture'] in I.PACKAGE_ARCHES and type(row['hold']) is bool and row['selection'] in ('install','hold','deinstall','purge','unknown') and row['hold']==(row['selection']=='hold'))
        else: need(row['version'] is row['architecture'] is row['hold'] is row['selection'] is None)
    info=value['bootstrap']
    # Reuse the exact fixed bootstrap vocabulary, without relying on host equality.
    need(set(info)==set(I.bootstrap_info()))
    need(info['source']=='bootstrap_memory' and info['os_family'] in ('linux','darwin','freebsd','unknown') and info['architecture'] in I.ARCHES)
    need(type(info['bits']) is int and info['bits'] in (32,64) and info['implementation'] in ('cpython','pypy','unknown'))
    need(type(info['version']) is str and re.fullmatch('[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}',info['version']))
    need(all(type(info[k]) is bool for k in ('isolated','no_site','no_user_site','bytecode_disabled')))
    row=value['private_root']; need(set(row)=={'status','reason','owner_access','mode'}); state(row,('observed',))
    need(type(row['owner_access']) is bool)
    if row['status']=='observed':
        need(type(row['mode']) is str and re.fullmatch('[0-7]{4}',row['mode']) and not int(row['mode'],8)&0o022)
        need(row['owner_access']==bool(int(row['mode'],8)&0o700==0o700))
    else: need(row['mode'] is None and row['owner_access'] is False)
    c=value['closure']; need(set(c)=={'executable','modules','maps'})
    need(c['executable'] in (*REFERENCE['files'],'outside_allowlist'))
    for key in ('modules','maps'):
        row=c[key]; need(set(row)==({'known','outside_allowlist'} if key=='modules' else {'known','outside_allowlist','status','reason'}))
        need(type(row['known']) is list and all(type(x) is str and x in REFERENCE['files'] for x in row['known']) and row['known']==sorted(set(row['known'])))
        need(type(row['outside_allowlist']) is int and 0<=row['outside_allowlist']<=1024)
        if key=='maps':
            need(row['status'] in ('observed','unknown') and (row['reason']=='none' if row['status']=='observed' else row['reason'] in UNKNOWN))
            if row['status']=='unknown': need(row['known']==[] and row['outside_allowlist']==0)
    # JSON canonical comparison is type-sensitive (no bool == int ambiguity).
    need(json.dumps(value['comparison'],sort_keys=True)==json.dumps(comparison(value),sort_keys=True))
    need(value['completeness']==('partial' if partial(value) else 'complete'))
    return body.decode().rstrip('\n'),code
