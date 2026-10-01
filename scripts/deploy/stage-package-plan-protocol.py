"""Fixed CLB-132 evidence envelope. Strict validation and ownership of fixed collector commands."""
import hashlib
import json
import errno
import posixpath
import re
import stat
import sys

PREFIX = b'clb132-package-plan:v=1 '
BODY_LIMIT = 1052672
COLLECTOR_SHA = 'a770d79303795bf9d26a36fa62c4ff89c50b9af5a744dcb692c10c8b72d41321'
TARGET_SHA = '9c9ba991a9edcea28ed9b24ee6f3d2f9928a748883cc923fd621bf012fb42e02'
BASE = '0c934da1b76ad6916feaf2bb52d88a9b42ca2f27'
# Fixed collector refusal vocabulary, including the conditional output-limit reason.
REASONS = frozenset('''APT_CONFIG_FAILED APT_CONFIG_UNEXPECTED APT_POLICY_FAILED ARCHITECTURE_QUERY_FAILED
BOUNDS_OR_UNEXPECTED_DATA COMMAND_TIMEOUT COMMAND_OUTPUT_TOO_LARGE DPKG_QUERY_FAILED DUPLICATE_PACKAGE_RECORD
DUPLICATE_POLICY_FIELD DUPLICATE_SOURCE_FIELD DUPLICATE_SOURCE_OPTION FILE_COUNT_LIMIT GENERATED_IMPACT_LIMIT
HOLD_QUERY_FAILED INDEX_CHANGED_DURING_READ INDEX_DECOMPRESSED_TOO_LARGE INDEX_DECOMPRESSION_ERROR_TOO_LARGE
INDEX_DECOMPRESSION_FAILED INDEX_DECOMPRESSION_TIMEOUT INDEX_REFS_LIMIT INDEX_RELATION_AMBIGUOUS INDEX_TOTAL_TOO_LARGE
MALFORMED_INDEX_HASH MALFORMED_MAPS MALFORMED_PREFERENCE MALFORMED_SOURCE OUTPUT_TOO_LARGE PACKAGE_SET_LIMIT
PLAN_TOO_LARGE POLICY_ORIGIN_LIMIT PREFERENCE_BOUNDS READ_ONLY_COLLECTION_FAILED SCRIPT_COUNT_LIMIT SOURCE_BOUNDS
TIME_LIMIT UNEXPECTED_ARCHITECTURE UNEXPECTED_ARGUMENTS UNEXPECTED_OS UNEXPECTED_PACKAGE_EXPANSION
UNEXPECTED_PACKAGE_RECORD UNEXPECTED_SOURCE_FIELD UNEXPECTED_SOURCE_OPTION UNSAFE_FILENAME UNSAFE_HOLDS UNSAFE_INDEX
UNSAFE_KEY_FILE UNSAFE_KEY_PATH UNSAFE_OR_LARGE_FILE UNSAFE_OR_LARGE_INDEX UNSAFE_PREFERENCE UNSAFE_SOURCE
UNSAFE_SOURCE_OPTION UNSAFE_TEXT WRONG_INTERPRETER_MODE INTERRUPTED INVALID_EVIDENCE'''.split())

# Only these fixed labels may replace a generic collection exception.
PHASE_REASONS = {
    'architecture': 'READ_ONLY_ARCHITECTURE_FAILED',
    'os': 'READ_ONLY_OS_FAILED',
    'sources': 'READ_ONLY_SOURCES_FAILED',
    'indexes': 'READ_ONLY_INDEXES_FAILED',
    'preferences': 'READ_ONLY_PREFERENCES_FAILED',
    'apt_config': 'READ_ONLY_APT_CONFIG_FAILED',
    'package_state': 'READ_ONLY_PACKAGE_STATE_FAILED',
    'apt_policy': 'READ_ONLY_APT_POLICY_FAILED',
    'resolver': 'READ_ONLY_RESOLVER_FAILED',
    'maintainer_scripts': 'READ_ONLY_MAINTAINER_SCRIPTS_FAILED',
    'outside_mapping': 'READ_ONLY_OUTSIDE_MAPPING_FAILED',
    'final_validation': 'READ_ONLY_FINAL_VALIDATION_FAILED',
}
REASONS = REASONS | frozenset(PHASE_REASONS.values())


def need(ok):
    if not ok:
        raise ValueError('invalid package evidence')


def unique(pairs):
    value = {}
    for key, item in pairs:
        need(key not in value)
        value[key] = item
    return value


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')


def keys(value, required, optional=()):
    need(type(value) is dict and set(required) <= set(value) <= set(required) | set(optional))


def text(value, maximum=1024, pattern=None):
    need(type(value) is str and len(value) <= maximum
         and all(32 <= ord(c) <= 126 for c in value))
    need(not re.search(r'(?i)(?:password\s*[:=]|token\s*[:=]|secret\s*[:=]|-----BEGIN|://[^/\s]*@)', value))
    if pattern is not None:
        need(re.fullmatch(pattern, value) is not None)


def integer(value, minimum=0, maximum=2**63-1):
    need(type(value) is int and minimum <= value <= maximum)


def sequence(value, maximum):
    need(type(value) is list and len(value) <= maximum)
    return value


def strings(value, maximum, length=160, pattern=None):
    for item in sequence(value, maximum):
        text(item, length, pattern)


def digest(value):
    text(value, 64, '[0-9a-f]{64}')


def package(value):
    text(value, 121, r'[a-z0-9][a-z0-9+.-]{0,99}(?::[a-z0-9-]{1,20})?')


def uri(value):
    keys(value, ('scheme', 'host', 'port', 'path', 'credential_redacted'))
    need(value['scheme'] in ('http', 'https', 'file'))
    text(value['host'], 160, '[A-Za-z0-9.-]*')
    if value['port'] is not None:
        integer(value['port'], 1, 65535)
    text(value['path'], 240, r'/[A-Za-z0-9._~%/+:-]*')
    need(type(value['credential_redacted']) is bool)
    if value['credential_redacted']:
        need(value['path'] == '/REDACTED')


def identity(value):
    keys(value, ('run_id', 'attempt', 'workflow_sha', 'workflow_sha256', 'runner_sha256',
                 'closure_sha256', 'collector_sha256', 'target_sha256'))
    text(value['run_id'], 20, '[1-9][0-9]{0,19}')
    need(value['attempt'] == '1')  # Reruns require a new authorization and NEW dispatch.
    text(value['workflow_sha'], 40, '[0-9a-f]{40}')
    for key in ('workflow_sha256', 'runner_sha256', 'closure_sha256', 'collector_sha256', 'target_sha256'):
        digest(value[key])
    need(value['collector_sha256'] == COLLECTOR_SHA and value['target_sha256'] == TARGET_SHA)


def evidence(value, collector):
    keys(value, ('schema', 'main', 'target_sha256', 'os', 'architecture', 'foreign_architectures',
                 'sources', 'indexes', 'preferences', 'apt_config', 'package_state', 'apt_policy',
                 'resolver', 'maintainer_scripts', 'outside_mapping', 'candidate_script_coverage',
                 'generated_state_impact', 'systemd_udev_flag', 'generated_state_proof'))
    need(value['schema'] == collector.SCHEMA and value['main'] == BASE and value['target_sha256'] == TARGET_SHA)
    keys(value['os'], ('ID', 'VERSION_ID'), ('VERSION_CODENAME',))
    need(value['os']['ID'] == 'ubuntu' and value['os']['VERSION_ID'] == '24.04' and value['architecture'] == 'amd64')
    if 'VERSION_CODENAME' in value['os']:
        text(value['os']['VERSION_CODENAME'], 80, '[A-Za-z0-9._-]+')
    strings(value['foreign_architectures'], 32, 20, '[a-z0-9-]+')
    for row in sequence(value['sources'], 64):
        keys(row, ('file', 'uris', 'suites', 'components', 'architectures', 'signed_by', 'enabled', 'trusted_override', 'valid_until_override'))
        text(row['file'], 120, '[A-Za-z0-9_.+-]+')
        for item in sequence(row['uris'], 64): uri(item)
        for key in ('suites', 'components', 'architectures'): strings(row[key], 16, 160, '[A-Za-z0-9.+_/-]+')
        text(row['signed_by'], 200)
        need(row['signed_by'] in ('unresolved', 'embedded_public_key') or re.fullmatch(r'/(?:usr/share/keyrings|etc/apt/keyrings|etc/apt/trusted\.gpg\.d)/[A-Za-z0-9_.+-]{1,100}\.(?:gpg|asc)', row['signed_by']))
        need(type(row['enabled']) is bool)
        need(row['trusted_override'] in (None, 'yes', 'no') and row['valid_until_override'] in (None, 'yes', 'no'))
    for row in sequence(value['indexes'], 128):
        keys(row, ('file', 'size', 'mtime_ns', 'sha256'), ('signed_relation', 'uncompressed_sha256', 'uncompressed_size', 'origin', 'label', 'suite', 'codename', 'date', 'valid_until', 'amd64_package_refs', 'signature', 'release_file'))
        text(row['file'], 120, '[A-Za-z0-9_.+-]+'); integer(row['size'], 0, 134217728); integer(row['mtime_ns']); digest(row['sha256'])
        for key in ('origin', 'label', 'suite', 'codename', 'date', 'valid_until', 'release_file'):
            if key in row: text(row[key], 160)
        if 'signed_relation' in row:
            need(row['signed_relation'] in ('UNKNOWN', 'MATCHED_VERIFIED_RELEASE', 'MATCHED_RELEASE_SIGNATURE_UNKNOWN', 'MISMATCH_SIGNED_REFERENCE'))
        if row.get('uncompressed_sha256') is not None: digest(row['uncompressed_sha256'])
        if 'uncompressed_size' in row: integer(row['uncompressed_size'], 0, 268435456)
        for ref in sequence(row.get('amd64_package_refs', []), 32):
            keys(ref, ('path', 'sha256', 'size')); text(ref['path'], 180, '[A-Za-z0-9._+/-]+'); digest(ref['sha256']); integer(ref['size'], 0, 10**15-1)
        if 'signature' in row:
            signature = row['signature']
            if signature.get('status') == 'UNKNOWN': keys(signature, ('status',))
            else:
                keys(signature, ('status', 'fingerprint')); need(signature['status'] == 'VERIFIED_WITH_CONFIGURED_PUBLIC_KEY'); text(signature['fingerprint'], 64, '[0-9A-F]{40,64}')
    for row in sequence(value['preferences'], 128):
        keys(row, ('file',), ('Package', 'Pin', 'Pin-Priority'))
        text(row['file'], 120, '[A-Za-z0-9_.+-]+')
        for key in set(row)-{'file'}: text(row[key], 256)
    keys(value['apt_config'], ('APT::Architecture', 'APT::Install-Recommends', 'APT::Install-Suggests',
        'APT::Get::Allow-Downgrades', 'APT::Get::Upgrade-Allow-New', 'APT::Default-Release',
        'APT::Get::Always-Include-Phased-Updates', 'APT::Get::Never-Include-Phased-Updates',
        'APT::Get::Allow-Change-Held-Packages', 'APT::Get::Allow-Remove-Essential'))
    for item in value['apt_config'].values():
        if item is not None: text(item, 120)
    need(type(value['package_state']) is dict and len(value['package_state']) <= 288)
    for name, row in value['package_state'].items():
        package(name); keys(row, ('version', 'architecture', 'status_abbrev', 'essential', 'priority', 'held'))
        for key in set(row)-{'held'}:
            if row[key] is not None: text(row[key], 120)
        need(type(row['held']) is bool)
    need(type(value['apt_policy']) is dict and len(value['apt_policy']) <= 288)
    for name, row in value['apt_policy'].items():
        package(name); keys(row, (), ('installed', 'candidate', 'versions'))
        for key in ('installed', 'candidate'):
            if key in row: text(row[key], 120, r'[A-Za-z0-9.+:~_()-]+')
        for version in sequence(row.get('versions', []), 1024):
            keys(version, ('version', 'priority', 'origins')); text(version['version'], 100, '[A-Za-z0-9.+:~_-]+'); integer(version['priority'], -99999999, 99999999)
            for origin in sequence(version['origins'], 32):
                keys(origin, ('priority', 'uri', 'suite_component', 'architecture_index')); integer(origin['priority'], -99999999, 99999999); uri(origin['uri'])
                for key in ('suite_component', 'architecture_index'): text(origin[key], 160, '[A-Za-z0-9._+/-]+')
    resolver = value['resolver']
    keys(resolver, ('exit_status', 'requested', 'raw_lines', 'actions', 'expanded_packages', 'systemd_udev_effects', 'high_impact_effects'))
    integer(resolver['exit_status'], -128, 255)
    need(resolver['requested'] == [name+'='+version for name, version in collector.REQUEST])
    strings(resolver['raw_lines'], 4096, 1024)
    plan = collector.parse_plan(('\n'.join(resolver['raw_lines'])).encode('ascii'))
    need(all(resolver[key] == item for key, item in plan.items()))
    for row in sequence(value['maintainer_scripts'], 640):
        keys(row, ('package', 'installed_version', 'script_type', 'sha256', 'matched_side_effects', 'candidate_script'))
        package(row['package']); text(row['installed_version'], 100, '[A-Za-z0-9.+:~_-]+'); digest(row['sha256'])
        need(row['script_type'] in ('preinst', 'postinst', 'prerm', 'postrm', 'triggers') and row['candidate_script'] is False)
        need(type(row['matched_side_effects']) is list and row['matched_side_effects'] == sorted(set(row['matched_side_effects'])) and set(row['matched_side_effects']) <= set(collector.SIDE_EFFECTS))
    outside = value['outside_mapping']
    need(type(outside) is dict and outside.get('classification') == 'UNKNOWN_REQUIRES_CONTRACT_DECISION')
    integer(outside.get('outside_count'), 0, 2048)
    if 'reason' in outside:
        keys(outside, ('classification', 'outside_count', 'reason')); need(outside['reason'] == 'not_exactly_one_current_collector_mapping')
    elif outside.get('path') == 'REDACTED_UNSAFE_PATH':
        keys(outside, ('classification', 'outside_count', 'path')); need(outside['outside_count'] == 1)
    else:
        keys(outside, ('classification', 'outside_count', 'path', 'type', 'owning_package', 'current_collector_mapping', 'same_as_CLB130_bootstrap', 'target_package_coverage_candidate', 'semantic_dependency', 'artifact_class'))
        need(outside['outside_count'] == 1); text(outside['path'], 256, r'/(?:usr|lib|lib64)/[A-Za-z0-9._+/-]+')
        need(outside['type'] in ('regular', 'symlink', 'other') and outside['current_collector_mapping'] is True and outside['same_as_CLB130_bootstrap'] == 'NOT_PROVEN' and outside['semantic_dependency'] == 'UNKNOWN' and outside['artifact_class'] in ('shared_library', 'data_or_other') and type(outside['target_package_coverage_candidate']) is bool)
        if outside['owning_package'] is not None: package(outside['owning_package'])
    need(value['generated_state_impact'] == collector.generated_impact(resolver['actions'], value['maintainer_scripts']))
    need(value['candidate_script_coverage'] == 'UNKNOWN_FOR_NOT_CACHED_CANDIDATES' and value['generated_state_proof'] == 'NOT_ESTABLISHED')
    need(value['systemd_udev_flag'] == ('FORBIDDEN_SYSTEMD_UDEV_EXPANSION_PRESENT' if resolver['systemd_udev_effects'] else 'NO_TRANSITION_OBSERVED'))
    need(len(canonical(value))+1 <= collector.MAX_OUTPUT)


def body(value, code, collector, expected_identity, challenge):
    keys(value, ('schema', 'challenge', 'identity', 'result', 'reason', 'evidence'))
    need(value['schema'] == 'clb132-package-plan-v1' and value['challenge'] == challenge)
    text(challenge, 64, '[0-9a-f]{64}'); identity(value['identity']); need(value['identity'] == expected_identity)
    if value['result'] == 'OBSERVED':
        need(code == 0 and value['reason'] == 'COLLECTION_COMPLETED')
        evidence(value['evidence'], collector)
    else:
        need(code == 1 and value['result'] == 'UNAVAILABLE' and value['reason'] in REASONS and value['evidence'] is None)
    encoded = PREFIX + canonical(value) + b'\n'
    need(len(encoded) <= BODY_LIMIT)
    return encoded


def parse(raw, code, collector, expected_identity, challenge):
    need(type(raw) is bytes and 0 < len(raw) <= BODY_LIMIT and raw.startswith(PREFIX) and raw.endswith(b'\n'))
    value = json.loads(raw[len(PREFIX):-1], object_pairs_hook=unique)
    need(body(value, code, collector, expected_identity, challenge) == raw)
    return raw.decode('ascii').rstrip('\n'), code


# The collector bytes stay frozen. This fixed adapter adds ownership around its
# two Popen sites, preserving argv, stdin/stdout/stderr, environment and results.
# In particular, poll never reaps the leader before group cleanup. No process
# discovery or /proc traversal is used; only children of this invocation exist here.
import os
import signal
import subprocess
import time
import types

CRITICAL = False
CURRENT_OWNER = None


class CommandOwnership:
    def __init__(self, collector, cancelled):
        self.collector, self.cancelled = collector, cancelled
        self.original, self.children = collector.subprocess, []

    def active(self):
        if self.cancelled():
            raise self.collector.Refuse('INTERRUPTED')

    def __enter__(self):
        global CURRENT_OWNER
        CURRENT_OWNER = self
        self.collector.subprocess = types.SimpleNamespace(
            Popen=self.spawn, PIPE=subprocess.PIPE, DEVNULL=subprocess.DEVNULL,
            SubprocessError=subprocess.SubprocessError)
        return self

    def spawn(self, argv, **kwargs):
        global CRITICAL
        self.active()
        need(kwargs.get('start_new_session') is True and kwargs.get('close_fds') is True)
        CRITICAL = True
        try:
            raw = subprocess.Popen(argv, **kwargs)
            child = OwnedCommand(raw, self)
            self.children.append(child)
        finally:
            CRITICAL = False
        self.active()
        return child

    def __exit__(self, *unused):
        global CRITICAL, CURRENT_OWNER
        failed = False
        CRITICAL = True
        try:
            for child in self.children:
                try:
                    child.finish()
                except BaseException:
                    failed = True
        finally:
            self.collector.subprocess = self.original
            CURRENT_OWNER = None
            CRITICAL = False
        if failed:
            raise self.collector.Refuse('READ_ONLY_COLLECTION_FAILED')
        self.active()


class OwnedCommand:
    def __init__(self, raw, owner):
        self.raw, self.owner, self.finished = raw, owner, False
        self.pid, self.stdout, self.stderr = raw.pid, raw.stdout, raw.stderr

    def poll(self):
        if self.finished:
            return self.raw.returncode
        exited = os.waitid(os.P_PID, self.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
        if exited is None:
            return None
        return exited.si_status if exited.si_code == os.CLD_EXITED else -exited.si_status

    def wait(self, timeout=None):
        timeout = 2 if timeout is None else timeout
        need(0 < timeout <= 100)
        deadline = time.monotonic() + timeout
        while self.poll() is None:
            self.owner.active()
            if time.monotonic() >= deadline:
                raise subprocess.TimeoutExpired('fixed package query', timeout)
            time.sleep(.01)
        self.finish()
        self.owner.active()
        return self.raw.returncode

    def finish(self):
        global CRITICAL
        if self.finished:
            return
        prior = CRITICAL
        CRITICAL = True
        failed = False
        denied = False
        try:
            try:
                os.killpg(self.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            except PermissionError:
                denied = True
            except OSError:
                failed = True
            try:
                self.raw.wait(timeout=2)
            except (OSError, subprocess.TimeoutExpired):
                failed = True
            finally:
                for stream in (self.stdout, self.stderr):
                    try:
                        stream.close()
                    except OSError:
                        failed = True
            if denied:
                try:
                    os.killpg(self.pid, 0)
                except ProcessLookupError:
                    pass
                except OSError:
                    failed = True
                else:
                    failed = True
            # Never signal this numerical group again after its leader was reaped.
            self.finished = True
            if failed:
                raise self.owner.collector.Refuse('READ_ONLY_COLLECTION_FAILED')
        finally:
            CRITICAL = prior


class PhaseTracker:
    """Observe pinned collector phase calls without replacing its functions."""

    DIRECT = {
        'bounded_file': 'os',
        'source_rows': 'sources',
        'index_rows': 'indexes',
        'preferences': 'preferences',
        'config_value': 'apt_config',
        'package_state': 'package_state',
        'policy': 'apt_policy',
        'parse_plan': 'resolver',
        'script_metadata': 'maintainer_scripts',
        'outside_mapping': 'outside_mapping',
        'safe_text': 'final_validation',
        'generated_impact': 'final_validation',
    }

    def __init__(self, collector):
        self.phase = None
        self.collect_code = getattr(collector.collect, '__code__', None)
        self.run_code = getattr(collector.run, '__code__', None)
        self.config_code = getattr(collector.config_value, '__code__', None)
        self.direct_codes = {getattr(getattr(collector, name), '__code__', None): phase
                             for name, phase in self.DIRECT.items()}
        self.direct_codes.pop(None, None)
        os_parser = getattr(getattr(collector, 're', None), 'fullmatch', None)
        if getattr(os_parser, '__code__', None) is not None:
            self.direct_codes[os_parser.__code__] = 'os'
        self.transparent_codes = {getattr(getattr(collector, 'need', None), '__code__', None)}
        self.transparent_codes.discard(None)
        self.config_callers = {self.collect_code}
        if self.collect_code is not None:
            self.config_callers.update(code for code in self.collect_code.co_consts
                                       if isinstance(code, type(self.collect_code)) and code.co_name == '<dictcomp>')

    def __enter__(self):
        self.previous = sys.getprofile()
        sys.setprofile(self.observe)
        return self

    def __exit__(self, *_unused):
        sys.setprofile(self.previous)

    def observe(self, frame, event, _arg):
        if event != 'call':
            return
        if frame.f_code is self.collect_code:
            self.phase = 'architecture'
            return
        caller = frame.f_back
        if caller is None:
            return
        if caller.f_code is self.collect_code:
            if frame.f_code is self.run_code:
                argv = frame.f_locals.get('argv')
                if argv in (['dpkg', '--print-architecture'], ['dpkg', '--print-foreign-architectures']):
                    self.phase = 'architecture'
                elif type(argv) is list and argv[:1] == ['apt-get']:
                    self.phase = 'resolver'
                else:
                    self.phase = None
            else:
                if frame.f_code not in self.transparent_codes:
                    self.phase = self.direct_codes.get(frame.f_code)
        elif frame.f_code is self.config_code and caller.f_code in self.config_callers:
            self.phase = 'apt_config'


def _os_release_identity(value):
    return (value.st_dev, value.st_ino, value.st_mode, value.st_uid, value.st_gid,
            value.st_nlink, value.st_size, value.st_mtime_ns, value.st_ctime_ns)


def _safe_os_release_directory(value):
    need(value.st_uid == 0 and stat.S_ISDIR(value.st_mode) and not value.st_mode & 0o022)


def _safe_os_release_file(value):
    need(value.st_uid == 0 and stat.S_ISREG(value.st_mode) and value.st_nlink == 1
         and stat.S_IMODE(value.st_mode) in (0o444, 0o644) and value.st_size <= 4096)


def _read_fixed_os_release():
    """Resolve only the accepted /etc/os-release link through pinned descriptors."""
    fds, edges = [], []
    try:
        root = os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        fds.append(root)
        root_identity = _os_release_identity(os.fstat(root))
        _safe_os_release_directory(os.fstat(root))

        def directory(parent, name):
            before = os.stat(name, dir_fd=parent, follow_symlinks=False)
            _safe_os_release_directory(before)
            fd = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_NONBLOCK,
                         dir_fd=parent)
            fds.append(fd)
            actual = os.fstat(fd)
            _safe_os_release_directory(actual)
            need(_os_release_identity(before) == _os_release_identity(actual))
            edges.append((parent, name, fd, _os_release_identity(actual)))
            return fd

        etc = directory(root, 'etc')
        link = os.stat('os-release', dir_fd=etc, follow_symlinks=False)
        need(link.st_uid == 0 and stat.S_ISLNK(link.st_mode) and link.st_nlink == 1)
        target = os.readlink('os-release', dir_fd=etc)
        resolved = posixpath.normpath(target if target.startswith('/') else
                                      posixpath.join('/etc', target))
        need(resolved == '/usr/lib/os-release')
        link_identity = _os_release_identity(link)

        usr = directory(root, 'usr')
        lib = directory(usr, 'lib')
        before = os.stat('os-release', dir_fd=lib, follow_symlinks=False)
        _safe_os_release_file(before)
        fd = os.open('os-release', os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=lib)
        fds.append(fd)
        actual = os.fstat(fd)
        _safe_os_release_file(actual)
        file_identity = _os_release_identity(actual)
        need(_os_release_identity(before) == file_identity)
        edges.append((lib, 'os-release', fd, file_identity))

        def recheck():
            need(_os_release_identity(os.fstat(root)) == root_identity)
            for parent, name, child, expected in edges:
                need(_os_release_identity(os.fstat(child)) == expected
                     and _os_release_identity(os.stat(name, dir_fd=parent,
                                                      follow_symlinks=False)) == expected)
            need(_os_release_identity(os.stat('os-release', dir_fd=etc,
                                              follow_symlinks=False)) == link_identity
                 and os.readlink('os-release', dir_fd=etc) == target)

        recheck()
        data = os.read(fd, 4097)
        need(len(data) <= 4096 and not os.read(fd, 1))
        recheck()
        return data, actual
    finally:
        failed = False
        for fd in reversed(fds):
            try:
                os.close(fd)
            except OSError:
                failed = True
        need(not failed)


class OsReleaseSlot:
    """Keep the frozen collector call; handle only its one fixed symlink layout."""

    def __init__(self, collector):
        self.collector = collector
        self.original = collector.bounded_file

    def __enter__(self):
        self.collector.bounded_file = self.read
        return self

    def __exit__(self, *_unused):
        self.collector.bounded_file = self.original

    def read(self, path, maximum):
        try:
            return self.original(path, maximum)
        except OSError as error:
            if path != '/etc/os-release' or maximum != 4096 or error.errno != errno.ELOOP:
                raise
            return _read_fixed_os_release()


def collect(collector, expected_identity, challenge, cancelled=lambda: False):
    result = dict(schema='clb132-package-plan-v1', challenge=challenge, identity=expected_identity,
                  result='OBSERVED', reason='COLLECTION_COMPLETED', evidence=None)
    code = 0
    slot = OsReleaseSlot(collector)
    tracker = PhaseTracker(collector)
    tracker.direct_codes[OsReleaseSlot.read.__code__] = 'os'
    try:
        with slot:
            with CommandOwnership(collector, cancelled):
                with tracker:
                    result['evidence'] = collector.collect()
        tracker.phase = 'final_validation'
        evidence(result['evidence'], collector)
    except (collector.Refuse, OSError, UnicodeError, ValueError, collector.subprocess.SubprocessError) as error:
        reason = (str(error) if isinstance(error, collector.Refuse)
                  else PHASE_REASONS.get(tracker.phase, 'INVALID_EVIDENCE'))
        result.update(result='UNAVAILABLE', reason=reason if reason in REASONS else 'INVALID_EVIDENCE', evidence=None)
        code = 1
    return body(result, code, collector, expected_identity, challenge), code
