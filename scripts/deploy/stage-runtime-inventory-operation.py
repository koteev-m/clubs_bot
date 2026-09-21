"""CLB-91 non-secret system inventory. No subprocess, target config or writer.

Only the already accepted isolated Python bootstrap runs. REFERENCE is supplied
from verified Git source, never learned from this host. Observed != approved.
"""
import errno
import hashlib
import json
import os
import re
import stat
import sys
import time

REFERENCE = None
PREFIX = 'runtime-inventory:v=1 '
BODY_LIMIT = 8192
REASONS = ('request', 'transport', 'protocol', 'interrupted', 'cleanup', 'io', 'identity', 'bounds')
UNKNOWN = ('unsafe', 'permission', 'oversized', 'malformed', 'unsupported', 'metadata_missing')
PACKAGES = ('python3', 'python3-minimal', 'ruby', 'ruby-psych', 'docker-compose',
            'docker-compose-v2', 'docker-compose-plugin', 'libc6', 'libssl3',
            'libssl3t64', 'libyaml-0-2', 'libffi8')
PYTHON = tuple('/usr' + prefix + '/bin/python3' + suffix
               for prefix in ('', '/local') for suffix in ('', '.8', '.9', '.10', '.11', '.12', '.13', '.14'))
RUBY = tuple('/usr' + prefix + '/bin/ruby' + suffix
             for prefix in ('', '/local') for suffix in ('', '3.0', '3.1', '3.2', '3.3', '3.4'))
SLOTS = {
    'python_system': '/usr/bin/python3', 'python_local': '/usr/local/bin/python3',
    'compose_standalone': '/usr/local/bin/docker-compose', 'compose_system': '/usr/bin/docker-compose',
    'compose_plugin_usr': '/usr/lib/docker/cli-plugins/docker-compose',
    'compose_plugin_libexec': '/usr/libexec/docker/cli-plugins/docker-compose',
    'compose_plugin_local': '/usr/local/lib/docker/cli-plugins/docker-compose',
    'ruby_system': '/usr/bin/ruby', 'ruby_local': '/usr/local/bin/ruby',
    **{'psych_' + str(v): '/usr/lib/ruby/3.' + str(v) + '.0/psych.rb' for v in range(5)},
}
RELEASE = ('/etc/os-release', '/usr/lib/os-release')
PACKAGE_FILE = '/var/lib/dpkg/status'
KINDS = {**{p: 'python' for p in PYTHON}, **{p: 'ruby' for p in RUBY},
         **{p: ('psych' if k.startswith('psych_') else 'compose') for k, p in SLOTS.items()
            if not k.startswith(('python_', 'ruby_'))},
         **{p: 'release' for p in RELEASE}, PACKAGE_FILE: 'packages'}
ARCHES = ('x86_64', 'aarch64', 'i386', 'i686', 'armv7l', 'ppc64le', 's390x', 'riscv64', 'unknown')
PACKAGE_ARCHES = ('amd64', 'arm64', 'i386', 'armhf', 'ppc64el', 's390x', 'riscv64', 'all')
DISTROS = ('ubuntu', 'debian', 'alpine', 'rhel', 'centos', 'rocky', 'almalinux', 'fedora', 'arch', 'opensuse', 'sles', 'nixos')
PLACEMENTS = ('usr_bin', 'usr_local_bin', 'usr_plugin', 'usr_libexec_plugin', 'usr_local_plugin', 'ruby_stdlib', 'unknown')
VERSION = re.compile(r'[0-9](?:[0-9.:+~_-]|ubuntu|deb|build|dfsg|really|git|bpo|rc|alpha|beta|pre|rpt|kali){0,63}')


class Unavailable(Exception):
    pass


class Observation(Exception):
    pass


def need(ok, reason='protocol'):
    if not ok:
        raise Unavailable(reason)


def identity(s):
    return (s.st_dev, s.st_ino, s.st_mode, s.st_uid, s.st_gid, s.st_nlink,
            s.st_size, s.st_mtime_ns, s.st_ctime_ns)


def canonical(value):
    return (PREFIX + json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True) + '\n').encode('ascii')


def refused(reason):
    return canonical(dict(result='unavailable', reason=reason if reason in REASONS else 'io'))


def placement(path):
    if path.startswith('/usr/local/bin/'): return 'usr_local_bin'
    if path.startswith('/usr/bin/'): return 'usr_bin'
    if path.startswith('/usr/lib/docker/'): return 'usr_plugin'
    if path.startswith('/usr/libexec/docker/'): return 'usr_libexec_plugin'
    if path.startswith('/usr/local/lib/docker/'): return 'usr_local_plugin'
    if path.startswith('/usr/lib/ruby/'): return 'ruby_stdlib'
    return 'unknown'


class SystemFiles:
    """Finite no-follow system graph; never follows a link into an arbitrary path."""
    def __init__(self, cancelled):
        self.cancelled, self.fds, self.edges, self.links = cancelled, [], [], []
        self.roots, self.missing = [], []
        self.total, self.deadline = 0, time.monotonic() + 75

    def tick(self):
        need(not self.cancelled(), 'interrupted')
        need(time.monotonic() < self.deadline, 'bounds')

    def keep(self, fd):
        self.fds.append(fd)  # own before any potentially failing metadata syscall
        need(len(self.fds) <= 256, 'bounds')
        return fd

    def safe(self, value, kind):
        if kind == 'directory':
            good = stat.S_ISDIR(value.st_mode) and not value.st_mode & 0o022
        elif kind == 'link':
            good = stat.S_ISLNK(value.st_mode) and value.st_nlink == 1
        else:
            modes = (0o444, 0o644) if kind in ('psych', 'release', 'packages') else (0o555, 0o755)
            good = stat.S_ISREG(value.st_mode) and value.st_nlink == 1 and stat.S_IMODE(value.st_mode) in modes
        if not (value.st_uid == 0 and good): raise Observation('unsafe')

    def open(self, path, hops=0):
        self.tick()
        if path not in KINDS or hops > 4: raise Observation('unsafe')
        kind = KINDS[path]
        parent = self.keep(os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW))
        self.safe(os.fstat(parent), 'directory')
        self.roots.append((parent, identity(os.fstat(parent))))
        parts = path.split('/')[1:]
        for name in parts[:-1]:
            self.tick()
            try:
                child = self.keep(os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent))
            except FileNotFoundError:
                self.missing.append((parent, name))
                raise
            self.safe(os.fstat(child), 'directory')
            self.edges.append((parent, name, child, identity(os.fstat(child))))
            parent = child
        name = parts[-1]
        try:
            value = os.stat(name, dir_fd=parent, follow_symlinks=False)
        except FileNotFoundError:
            self.missing.append((parent, name))
            raise
        if stat.S_ISLNK(value.st_mode):
            self.safe(value, 'link')
            target = os.readlink(name, dir_fd=parent)
            self.links.append((parent, name, identity(value), target))
            resolved = os.path.normpath(target if target.startswith('/') else os.path.join(os.path.dirname(path), target))
            if KINDS.get(resolved) != kind: raise Observation('unsafe')
            fd, final, _ = self.open(resolved, hops + 1)
            return fd, final, 'symlink'
        self.safe(value, kind)
        fd = self.keep(os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent))
        actual = os.fstat(fd)
        self.safe(actual, kind)
        need(identity(value) == identity(actual), 'identity')
        self.edges.append((parent, name, fd, identity(actual)))
        return fd, path, 'direct'

    def recheck(self):
        self.tick()
        for fd, before in self.roots:
            need(identity(os.fstat(fd)) == before, 'identity')
        for parent, name in self.missing:
            try: os.stat(name, dir_fd=parent, follow_symlinks=False)
            except FileNotFoundError: continue
            raise Unavailable('identity')
        for parent, name, fd, before in self.edges:
            need(identity(os.fstat(fd)) == before and
                 identity(os.stat(name, dir_fd=parent, follow_symlinks=False)) == before, 'identity')
        for parent, name, before, target in self.links:
            need(identity(os.stat(name, dir_fd=parent, follow_symlinks=False)) == before
                 and os.readlink(name, dir_fd=parent) == target, 'identity')

    def read(self, path, limit, digest=False):
        try:
            fd, final, layout = self.open(path)
        except OSError as error:
            if error.errno == errno.ENOENT: raise Observation('missing') from None
            if error.errno in (errno.EACCES, errno.EPERM): raise Observation('permission') from None
            if error.errno in (errno.ELOOP, errno.ENOTDIR): raise Observation('unsafe') from None
            raise
        if os.fstat(fd).st_size > limit: raise Observation('oversized')
        before = identity(os.fstat(fd))
        self.recheck()
        chunks, size, hasher = [], 0, hashlib.sha256()
        while True:
            self.tick()
            data = os.read(fd, min(65536, limit + 1 - size))
            self.total += len(data); size += len(data)
            need(self.total <= 192 * 1024 * 1024, 'bounds')
            if size > limit: raise Observation('oversized')
            if not data: break
            if digest: hasher.update(data)
            else: chunks.append(data)
        need(identity(os.fstat(fd)) == before, 'identity')
        self.recheck()
        return (hasher.hexdigest() if digest else b''.join(chunks)), final, layout

    def close(self):
        failed = False
        for fd in reversed(self.fds):
            try: os.close(fd)
            except BaseException: failed = True
        self.fds.clear()
        need(not failed, 'cleanup')


def artifact(files, path, source='safe_file'):
    value = dict(status='unknown', reason='unsupported', source=source, placement='unknown',
                 layout='unknown', sha256='-', reference='not_observed')
    if path not in KINDS or KINDS[path] in ('release', 'packages'): return value
    try:
        digest, final, layout = files.read(path, 64 * 1024 * 1024, digest=True)
        expected = REFERENCE['files'].get(final)
        value.update(status='observed', reason='none', placement=placement(final), layout=layout,
                     sha256=digest, reference=('not_listed' if expected is None else
                         'same_digest' if digest == expected else 'different_digest'))
    except Observation as error:
        reason = error.args[0]
        value.update(status='missing' if reason == 'missing' else 'unknown', reason=reason)
    return value


def distro(files):
    value = dict(status='unknown', reason='metadata_missing', source='not_observed', id='-', version='-')
    for path, source in zip(RELEASE, ('etc_os_release', 'usr_os_release')):
        try:
            raw, _, _ = files.read(path, 16384)
            value['source'] = source
            fields = {}
            lines = raw.decode('utf-8').splitlines()
            if len(lines) > 256: raise Observation('malformed')
            for line in lines:
                key, separator, val = line.partition('=')
                if key not in ('ID', 'VERSION_ID'): continue
                if not separator or key in fields: raise Observation('malformed')
                if val[:1] in ('"', "'"):
                    if len(val) < 2 or val[-1] != val[0]: raise Observation('malformed')
                    val = val[1:-1]
                fields[key] = val
            if fields.get('ID') not in DISTROS or not re.fullmatch(r'[0-9]{1,4}(?:\.[0-9]{1,4}){0,3}', fields.get('VERSION_ID', '')):
                raise Observation('unsupported')
            value.update(status='observed', reason='none', id=fields['ID'], version=fields['VERSION_ID'])
            return value
        except UnicodeError: value['reason'] = 'malformed'; return value
        except Observation as error:
            if error.args[0] == 'missing': continue
            value['reason'] = error.args[0]; return value
    return value


def packages(files):
    def empty(reason):
        return {name: dict(status='unknown', reason=reason, source='dpkg_status', version='-', architecture='-') for name in PACKAGES}
    try:
        raw, _, _ = files.read(PACKAGE_FILE, 4194304)
    except Observation as error:
        return empty('metadata_missing' if error.args[0] == 'missing' else error.args[0])
    try:
        stanzas = raw.decode('utf-8').split('\n\n')
        if len(stanzas) > 8192 or any(len(s) > 65536 for s in stanzas): raise ValueError()
        result = {name: dict(status='missing', reason='missing', source='dpkg_status', version='-', architecture='-') for name in PACKAGES}
        seen = set()
        for stanza in stanzas:
            fields = {}
            for line in stanza.splitlines():
                if line[:1].isspace(): continue
                key, separator, value = line.partition(': ')
                if key in ('Package', 'Status', 'Version', 'Architecture'):
                    if not separator or key in fields: raise ValueError()
                    fields[key] = value
            name = fields.get('Package')
            if name not in PACKAGES: continue
            if name in seen: raise ValueError()
            seen.add(name)
            if fields.get('Status') != 'install ok installed':
                result[name].update(status='unknown', reason='unsupported'); continue
            version, arch = fields.get('Version', ''), fields.get('Architecture')
            if len(version) > 64 or not VERSION.fullmatch(version) or arch not in PACKAGE_ARCHES:
                result[name].update(status='unknown', reason='unsupported'); continue
            result[name].update(status='observed', reason='none', version=version, architecture=arch)
        return result
    except (UnicodeError, ValueError):
        return empty('malformed')


def bootstrap_info():
    family = sys.platform if sys.platform in ('linux', 'darwin', 'freebsd') else 'unknown'
    machine = os.uname().machine
    return dict(source='bootstrap_memory', os_family=family,
        architecture=machine if machine in ARCHES else 'unknown', bits=64 if sys.maxsize > 2**32 else 32,
        implementation=sys.implementation.name if sys.implementation.name in ('cpython', 'pypy') else 'unknown',
        version='.'.join(str(x) for x in sys.version_info[:3]), isolated=bool(sys.flags.isolated),
        no_site=bool(sys.flags.no_site), no_user_site=bool(sys.flags.no_user_site),
        bytecode_disabled=bool(sys.dont_write_bytecode))


def collect(cancelled):
    files = SystemFiles(cancelled)
    body = refused('io')
    try:
        info = bootstrap_info()
        artifacts = {key: artifact(files, path) for key, path in SLOTS.items()}
        try: active = os.readlink('/proc/self/exe')
        except OSError as error:
            if error.errno not in (errno.ENOENT, errno.EACCES, errno.EPERM, errno.EINVAL): raise
            active = None
        artifacts['python_bootstrap'] = artifact(files, active if active in PYTHON else None, source='kernel_link_safe_file')
        distribution, installed = distro(files), packages(files)
        files.recheck()
        partial = (any(v == 'unknown' for v in info.values()) or distribution['status'] != 'observed'
                   or any(v['status'] == 'unknown' for v in (*artifacts.values(), *installed.values())))
        body = canonical(dict(result='observed', completeness='partial' if partial else 'complete',
            trust='observed_not_approved', bootstrap=info, distro=distribution, artifacts=artifacts, packages=installed))
        need(len(body) <= BODY_LIMIT, 'bounds')
    except Observation:
        body = refused('io')
    except Unavailable as error:
        body = refused(error.args[0])
    except (KeyboardInterrupt, InterruptedError):
        body = refused('interrupted')
    except BaseException:
        body = refused('io')
    finally:
        try: files.close()
        except BaseException: body = refused('cleanup')
    if cancelled(): body = refused('interrupted')
    return body


def unique(pairs):
    result = {}
    for key, value in pairs:
        need(key not in result)
        result[key] = value
    return result


def parse_body(body, code):
    need(type(body) is bytes and len(body) <= BODY_LIMIT and body.startswith(PREFIX.encode()) and body.endswith(b'\n'))
    value = json.loads(body[len(PREFIX):-1], object_pairs_hook=unique)
    need(canonical(value) == body)
    if value.get('result') == 'unavailable':
        need(code == 1 and set(value) == {'result','reason'} and value['reason'] in REASONS)
        return body.decode().rstrip('\n'), code
    need(code == 0 and set(value) == {'result','completeness','trust','bootstrap','distro','artifacts','packages'})
    need(value['result'] == 'observed' and value['trust'] == 'observed_not_approved')
    info = value['bootstrap']
    need(set(info) == {'source','os_family','architecture','bits','implementation','version','isolated','no_site','no_user_site','bytecode_disabled'})
    need(info['source'] == 'bootstrap_memory' and info['os_family'] in ('linux','darwin','freebsd','unknown') and info['architecture'] in ARCHES)
    need(type(info['bits']) is int and info['bits'] in (32,64) and info['implementation'] in ('cpython','pypy','unknown'))
    need(type(info['version']) is str and re.fullmatch(r'[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}',info['version']))
    need(all(type(info[k]) is bool for k in ('isolated','no_site','no_user_site','bytecode_disabled')))
    def status(row):
        need(row['status'] in ('observed','missing','unknown'))
        need(row['reason'] == 'none' if row['status']=='observed' else row['reason']=='missing' if row['status']=='missing' else row['reason'] in UNKNOWN)
    d = value['distro']; need(set(d)=={'status','reason','source','id','version'}); status(d)
    need(d['source'] in ('etc_os_release','usr_os_release','not_observed') and d['status']!='missing')
    if d['status']=='observed': need(d['id'] in DISTROS and re.fullmatch(r'[0-9]{1,4}(?:\.[0-9]{1,4}){0,3}',d['version']))
    else: need(d['id']==d['version']=='-')
    need(set(value['artifacts'])==set(SLOTS)|{'python_bootstrap'})
    for key, row in value['artifacts'].items():
        need(set(row)=={'status','reason','source','placement','layout','sha256','reference'}); status(row)
        need(row['source']==('kernel_link_safe_file' if key=='python_bootstrap' else 'safe_file'))
        if row['status']=='observed':
            need(row['placement'] in PLACEMENTS[:-1] and row['layout'] in ('direct','symlink'))
            need(re.fullmatch('[0-9a-f]{64}',row['sha256']) and row['reference'] in ('same_digest','different_digest','not_listed'))
        else: need(row['placement']==row['layout']=='unknown' and row['sha256']=='-' and row['reference']=='not_observed')
    need(set(value['packages'])==set(PACKAGES))
    for row in value['packages'].values():
        need(set(row)=={'status','reason','source','version','architecture'} and row['source']=='dpkg_status'); status(row)
        if row['status']=='observed': need(type(row['version']) is str and len(row['version'])<=64 and VERSION.fullmatch(row['version']) and row['architecture'] in PACKAGE_ARCHES)
        else: need(row['version']==row['architecture']=='-')
    partial = (any(v=='unknown' for v in info.values()) or d['status']!='observed'
               or any(v['status']=='unknown' for v in (*value['artifacts'].values(),*value['packages'].values())))
    need(value['completeness']==('partial' if partial else 'complete'))
    return body.decode().rstrip('\n'), code
