"""Incident-only fixed Docker CLI/Compose gate; verified injected sources only.

No entrypoint, source discovery, normalizer, writer, or profile-learning API.
The authenticated helper embeds D, E, P, S and PROFILE. E is the existing
semantic Runtime namespace, rebound by that loader to this exact profile.
Root/kernel and the initial interpreter remain trusted, as in E.Runtime.
"""
import os
import re
import resource
import stat

D = E = P = S = PROFILE = None
CLI = '/usr/bin/docker'
PLUGIN = '/usr/libexec/docker/cli-plugins/docker-compose'
CONTRACT = 'clb91-cli29.3.1-compose5.1.1-config-v1'
CLI_SHA256 = '18c2a16d6aa0331bbd78b93e0a57f344c62bda0be52c054d4ee80e11176b0c30'
PLUGIN_SHA256 = '2ac954c9d506b912a12477d72f01601dc72ec918c429c7bae48fd707bdf0f3e5'
SYSTEM_PLUGIN_DIRS = ('/usr/local/lib/docker/cli-plugins',
    '/usr/local/libexec/docker/cli-plugins', '/usr/lib/docker/cli-plugins',
    '/usr/libexec/docker/cli-plugins')
REMOVED_PREFIXES = ('COMPOSE_', 'DOCKER_', 'PYTHON', 'BASH_FUNC_')
REMOVED_NAMES = frozenset(('BASH_ENV', 'ENV', 'SHELLOPTS', 'BASHOPTS', 'CDPATH'))
# These surviving names can change executable loading, Go diagnostics, telemetry,
# proxying, desktop integration or file lookup. Rejection preserves the original
# helper semantics: no silently rewritten application interpolation environment.
HOOK_PREFIXES = ('LD_', 'DYLD_', 'RUBY', 'GEM_', 'BUNDLE_', 'OTEL_',
                 'OTLP_', 'GRPC_', 'GO')
HOOK_NAMES = frozenset(('SSL_CERT_FILE', 'SSL_CERT_DIR', 'REQUESTS_CA_BUNDLE',
    'CURL_CA_BUNDLE', 'ALL_PROXY', 'all_proxy', 'HTTP_PROXY', 'http_proxy',
    'HTTPS_PROXY', 'https_proxy', 'NO_PROXY', 'no_proxy', 'XDG_CONFIG_HOME',
    'XDG_DATA_HOME', 'XDG_CACHE_HOME', 'XDG_RUNTIME_DIR', 'TZ', 'TMPDIR',
    'TMP', 'TEMP', 'IRBRC', 'LOCPATH', 'NLSPATH', 'TERMINFO', 'TERMINFO_DIRS'))


class GateUnavailable(Exception):
    """No paths, observed digests, environment values or underlying errors."""


def docker_environment(environ):
    """Exactly the existing BoundContext filter, before its fixed config keys."""
    return {k: v for k, v in environ.items() if not k.startswith(REMOVED_PREFIXES)
            and k not in REMOVED_NAMES}


class PipelineGate:
    def __init__(self, cancelled):
        self.cancelled = cancelled
        self.runtime = None
        self.held, self.negative, self.directories = [], [], {}
        self.alias_leases = {}
        self.environment = None
        self.config_fd = None
        self.principal_name = None
        self.initialized, self.bound, self.closed = False, False, False

    def require(self, condition):
        if self.cancelled():
            raise P.Refused('interrupted') from None
        if not condition:
            raise GateUnavailable() from None

    def profile(self):
        # PROFILE is embedded/authenticated source, never an observed live hash
        # or caller-provided "approved" flag. The fixed behavior contract also
        # requires review of CLI/plugin startup, metadata and config pre-runs.
        self.require(type(PROFILE) is dict and set(PROFILE) ==
                     {'format', 'contract', 'runtime', 'cli', 'compose'})
        self.require(type(PROFILE['format']) is int and PROFILE['format'] == 1
                     and PROFILE['contract'] == CONTRACT)
        for name, path, version, digest, size in (
            ('cli', CLI, '29.3.1', CLI_SHA256, 44320674),
            ('compose', PLUGIN, '5.1.1', PLUGIN_SHA256, 31557130)):
            value = PROFILE[name]
            self.require(type(value) is dict and set(value) ==
                         {'path', 'version', 'sha256', 'size'})
            self.require(value == dict(path=path, version=version, sha256=digest, size=size)
                         and type(value['size']) is int)
        manifest = PROFILE['runtime']
        self.require(type(manifest) is dict and type(manifest.get('format')) is int
                     and manifest['format'] == 1
                     and type(manifest.get('files')) is dict)
        self.require(manifest['files'].get(CLI) == CLI_SHA256
                     and manifest['files'].get(PLUGIN) == PLUGIN_SHA256)
        # The loader must bind the existing checker to this exact object. A
        # separate/old standalone manifest cannot authorize the plugin pipeline.
        namespace = E.Runtime.manifest.__globals__
        self.require(namespace.get('MANIFEST') is manifest
                     and namespace.get('COMPOSE') == PLUGIN
                     and namespace.get('D') is D and namespace.get('P') is P
                     and namespace.get('S') is S)
        self.require(E.Runtime.FILE_BYTES == 33554432
                     and E.Runtime.TOTAL_BYTES == 134217728)

    def aliases(self):
        # Alias bytes are public. Root owns every component and protected parent;
        # retain no-follow identities as well as the canonical Runtime target.
        for alias, expected in PROFILE['runtime']['aliases'].items():
            self.require(type(alias) is str and alias.startswith('/')
                         and os.path.realpath(alias) == expected)
            current = ''
            for part in alias.split('/')[1:]:
                self.require(part not in ('', '.', '..'))
                current += '/' + part
                info = os.stat(current, follow_symlinks=False)
                self.require(info.st_uid == 0 and
                    (stat.S_ISLNK(info.st_mode) or not info.st_mode & 0o022))
                value = self.identity(info)
                self.require(current not in self.alias_leases or self.alias_leases[current] == value)
                self.alias_leases[current] = value

    def core_policy(self, install=False):
        if install:
            # Only this process's limits; no system configuration is changed.
            resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
        self.require(resource.getrlimit(resource.RLIMIT_CORE) == (0, 0))
        # Linux ignores RLIMIT_CORE when piping core files to a handler. Such a
        # host requires a separately accepted nondumpable primitive; no fallback.
        with open('/proc/sys/kernel/core_pattern', 'rb') as stream:
            pattern = stream.read(4097)
        self.require(0 < len(pattern) <= 4096 and b'\x00' not in pattern
                     and not pattern.startswith(b'|'))

    def initial(self):
        self.require(not self.closed and not self.initialized)
        try:
            self.profile()
            class FixedRuntime(E.Runtime):
                # Exact signed CLI is 44,320,674 bytes. Keep the original
                # bounded checker; only this fixed profile gets larger caps.
                FILE_BYTES, TOTAL_BYTES = 67108864, 201326592
            self.runtime = FixedRuntime(self.cancelled)
            self.runtime.open()
            self.runtime.require_compatible()
            self.require(all(os.fstat(fd).st_nlink == 1 for _, fd, _ in self.runtime.held))
            self.aliases()
            self.core_policy(install=True)
            # Public-only closure/version probe; this pipeline has no named
            # private workspace and never uses the standalone writer boundary.
            self.runtime.ruby_probe('/')
            self.runtime.recheck()
            self.initialized = True
        except BaseException:
            self.close()
            raise

    def keep(self, fd, path):
        entry = [path, fd, None]
        self.held.append(entry)  # Ownership precedes fstat, including failure.
        self.require(len(self.held) <= 48)
        info = os.fstat(fd)
        entry[2] = self.identity(info)
        return fd, info

    @staticmethod
    def identity(info):
        # Directory timestamps/size can change through unrelated /tmp activity.
        # Keep every authority field and check directory contents separately;
        # ordinary files retain the complete original D.identity comparison.
        value = D.identity(info)
        return value[:6] if stat.S_ISDIR(info.st_mode) else value

    def directory(self, path, temporary=False):
        self.require(type(path) is str and path.startswith('/') and
                     all(part not in ('', '.', '..') for part in path.split('/')[1:]))
        if path in self.directories:
            return self.directories[path]
        if '/' not in self.directories:
            fd, info = self.keep(os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW), '/')
            self.require(stat.S_ISDIR(info.st_mode) and info.st_uid == 0
                         and not info.st_mode & 0o022)
            self.directories['/'] = fd
        parent, current = self.directories['/'], ''
        for part in path.split('/')[1:]:
            current += '/' + part
            if current in self.directories:
                parent = self.directories[current]
                if parent is None:
                    return None
                continue
            try:
                child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                                dir_fd=parent)
            except FileNotFoundError:
                self.negative.append((parent, part))
                self.directories[current] = None
                return None
            fd, info = self.keep(child, current)
            self.require(stat.S_ISDIR(info.st_mode) and info.st_uid == 0)
            self.require(not info.st_mode & 0o022 or
                         (temporary and current == '/tmp' and info.st_mode & stat.S_ISVTX))
            self.require(len(self.held) <= 48 and len(self.negative) <= 48)
            self.directories[current] = fd
            parent = fd
        return parent

    def absent(self, parent, name):
        try:
            os.stat(name, dir_fd=parent, follow_symlinks=False)
        except FileNotFoundError:
            self.negative.append((parent, name))
            self.require(len(self.negative) <= 48)
            return
        raise GateUnavailable() from None

    def executable(self, path):
        values = [entry for entry in self.runtime.held if entry[0] == path]
        self.require(len(values) == 1)
        _, fd, identity = values[0]
        info = os.fstat(fd)
        self.require(D.identity(info) == identity and stat.S_ISREG(info.st_mode)
                     and info.st_uid == 0 and not info.st_mode & 0o022
                     and info.st_mode & 0o111 and info.st_nlink == 1
                     and info.st_size == PROFILE['cli' if path == CLI else 'compose']['size'])

    def public_account_file(self, name):
        parent = self.directory('/etc')
        self.require(parent is not None and name in ('passwd', 'nsswitch.conf'))
        fd, info = self.keep(os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                                    dir_fd=parent), '/etc/' + name)
        self.require(stat.S_ISREG(info.st_mode) and info.st_uid == 0
                     and stat.S_IMODE(info.st_mode) in (0o600, 0o644)
                     and info.st_nlink == 1 and 0 < info.st_size <= 65536)
        output = bytearray()
        while True:
            self.require(len(output) <= 65536)
            chunk = os.read(fd, min(4096, 65537 - len(output)))
            if not chunk:
                break
            output.extend(chunk)
        self.require(len(output) == info.st_size and D.identity(os.fstat(fd)) == D.identity(info))
        self.require(b'\x00' not in output and b'\r' not in output)
        return bytes(output).decode('utf-8')

    def principal(self, expected):
        """Prove the actual effective principal without NSS or an id subprocess."""
        self.require(self.initialized and not self.closed and self.principal_name is None)
        self.require(type(expected) is str and re.fullmatch('[a-z_][a-z0-9_-]{0,31}', expected)
                     and expected not in ('root', 'hookah-staging'))
        self.require(os.getuid() == os.geteuid() != 0 and os.getgid() == os.getegid())
        nss = self.public_account_file('nsswitch.conf')
        passwd_rules = []
        for line in nss.splitlines():
            line = line.split('#', 1)[0].strip()
            if line and line.split(':', 1)[0].strip() == 'passwd':
                passwd_rules.append(line)
        self.require(len(passwd_rules) == 1 and re.fullmatch(r'passwd\s*:\s*files\s*', passwd_rules[0]))
        passwd = self.public_account_file('passwd')
        matches = []
        for line in passwd.splitlines():
            if not line or line.startswith('#'):
                continue
            fields = line.split(':')
            self.require(len(fields) == 7 and re.fullmatch('[0-9]+', fields[2])
                         and re.fullmatch('[0-9]+', fields[3]))
            if fields[0] == expected or int(fields[2]) == os.geteuid():
                matches.append(fields)
        self.require(len(matches) == 1 and matches[0][0] == expected
                     and int(matches[0][2]) == os.geteuid()
                     and int(matches[0][3]) == os.getegid())
        self.principal_name = expected
        self.recheck()
        return expected

    def command_environment(self, value):
        self.require(self.bound and self.principal_name is not None
                     and type(value) is dict and value == self.environment)
        self.recheck()
        return value

    def path_selection(self, value):
        self.require(type(value) is str)
        elements = value.split(':')
        self.require(1 <= len(elements) <= 16 and all(elements))
        selected = False
        for element in elements:
            directory = self.directory(element)
            if directory is None:
                continue
            if element == '/usr/bin':
                self.executable(CLI)
                selected = True
                break
            self.absent(directory, 'docker')
        self.require(selected)

    def make_config(self):
        parent = self.directory('/tmp', temporary=True)
        self.require(parent is not None and os.geteuid() != 0)
        name = '.clubs-bound-docker-' + os.urandom(16).hex()
        created = False
        try:
            os.mkdir(name, 0o700, dir_fd=parent)
            created = True
            fd, info = self.keep(os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                                         dir_fd=parent), '<anonymous-config>')
            self.config_fd = fd
            self.require(info.st_uid == os.geteuid() and info.st_gid == os.getegid()
                         and stat.S_IMODE(info.st_mode) == 0o700)
            os.rmdir(name, dir_fd=parent)
            created = False
            # Only the known own mkdir/rmdir changes this public temporary node.
            for entry in self.held:
                if entry[1] in (parent, fd):
                    entry[2] = self.identity(os.fstat(entry[1]))
        finally:
            if created:
                try:
                    os.rmdir(name, dir_fd=parent)
                except OSError:
                    raise P.Refused('cleanup') from None

    def plugin_selection(self):
        # getPluginDirs: ExtraDirs, config/cli-plugins, then these system dirs.
        # The held empty anonymous config has no config.json/ExtraDirs and no
        # user cli-plugins; neither candidate location can acquire new entries.
        self.require(self.config_fd is not None and os.listdir(self.config_fd) == [])
        info = os.fstat(self.config_fd)
        self.require(info.st_uid == os.geteuid() and stat.S_IMODE(info.st_mode) == 0o700
                     and info.st_nlink == 0)
        for path in SYSTEM_PLUGIN_DIRS:
            directory = self.directory(path)
            if directory is None:
                continue
            if path == SYSTEM_PLUGIN_DIRS[-1]:
                self.executable(PLUGIN)
                return
            self.absent(directory, 'docker-compose')
        raise GateUnavailable() from None

    def bind(self, environ):
        self.require(self.initialized and not self.closed and not self.bound)
        try:
            self.require(type(environ) is dict and len(environ) <= 256)
            self.require(all(type(k) is str and P.NAME.fullmatch(k) and type(v) is str
                             and '\x00' not in v for k, v in environ.items()))
            self.require(sum(len(k.encode()) + len(v.encode()) for k, v in environ.items()) <= 65536)
            environment = docker_environment(environ)
            self.require(not any(k.startswith(HOOK_PREFIXES) or k in HOOK_NAMES for k in environment))
            # HOME is preserved, not rewritten. The accepted source contract uses
            # the explicit empty Docker config for all configuration/desktop reads.
            self.require(type(environment.get('HOME')) is str and environment['HOME'].startswith('/'))
            # Preserve actual values; unknown locale databases are not this
            # fixed executable closure. Only the pinned C.utf8 CTYPE is extra.
            for key, value in environment.items():
                if key == 'LANG' or key.startswith('LC_'):
                    accepted = ('C', 'POSIX', '')
                    if key == 'LC_CTYPE': accepted += ('C.UTF-8', 'C.utf8')
                    self.require(value in accepted)
            self.path_selection(environment.get('PATH'))
            self.make_config()
            self.plugin_selection()
            environment['DOCKER_CONFIG'] = '/proc/' + str(os.getpid()) + '/fd/' + str(self.config_fd)
            environment['DOCKER_HOST'] = 'unix:///var/run/docker.sock'
            self.environment = environment
            self.bound = True
            self.recheck()
            return dict(environment)
        except BaseException:
            self.close()
            raise

    def recheck(self):
        self.require(self.initialized and not self.closed)
        self.runtime.recheck()
        self.aliases()
        self.core_policy()
        for path, fd, value in self.held:
            self.require(value is not None and self.identity(os.fstat(fd)) == value)
            if path != '<anonymous-config>':
                self.require(self.identity(os.stat(path, follow_symlinks=False)) == value)
        for parent, name in self.negative:
            try:
                os.stat(name, dir_fd=parent, follow_symlinks=False)
            except FileNotFoundError:
                continue
            raise GateUnavailable() from None
        if self.bound:
            self.executable(CLI)
            self.executable(PLUGIN)
            self.require(os.listdir(self.config_fd) == [])

    def close(self):
        if self.closed:
            return
        self.closed = True
        failed = False
        for _, fd, _ in reversed(self.held):
            try:
                os.close(fd)
            except BaseException:
                failed = True
        if self.runtime is not None:
            try:
                self.runtime.close()
            except BaseException:
                failed = True
        if failed:
            raise P.Refused('cleanup') from None


Gate = PipelineGate
