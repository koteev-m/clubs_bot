"""Fixed CLB-91 private semantic dry-run; supplied verified dependencies only.

No entrypoint, writer, install, Docker Engine or arbitrary input path. The source
loader supplies D (unchanged capture), P (planner), S (safe root), and MANIFEST.
Root-installed Linux runtime files are verified before target capture begins.
"""
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import stat
import sys

D = P = S = MANIFEST = None
PREFIX = 'compose-env-semantic:v=1'
CONFIRMATION = 'CLB-91:35371386455:private-env-semantic-dry-run'
COMPOSE = '/usr/local/bin/docker-compose'
REASONS = frozenset(('runtime', 'request', 'principal', 'layout', 'identity', 'busy',
    'backing', 'bounds', 'io', 'interrupted', 'cleanup', 'transport', 'protocol',
    'input', 'unsupported', 'version', 'parser', 'model', 'different'))


def refused(reason):
    return (PREFIX + ' result=unavailable reason=' + (reason if reason in REASONS else 'io') + '\n').encode()


# Fixed aggregate prerequisites, never per-file names or observed runtime values.
PREREQUISITES = ('platform', 'manifest', 'availability', 'path_safety', 'integrity',
                 'interpreter', 'modules', 'aliases', 'maps', 'descriptors', 'private_root', 'ruby')
PHASES = ('initial', 'pre_capture', 'post_open', 'capture', 'prepare', 'post_prepare', 'finalize')
STATUSES = ('pass', 'fail', 'not_evaluated')
BODY_LIMIT = 512


class Evidence:
    def __init__(self):
        self.phase, self.capture = 'initial', 'not_started'
        self.first = None
        self.observations = {key: [] for key in PREREQUISITES}

    def record(self, guard, status):
        self.observations[guard].append(status)
        if status == 'fail' and self.first is None:
            self.first = (self.phase, guard)

    def statuses(self):
        return {key: ('fail' if 'fail' in values else
                      'pass' if values and all(x == 'pass' for x in values) else 'not_evaluated')
                for key, values in self.observations.items()}

    def refused(self, reason):
        if reason in ('interrupted', 'cleanup'):
            # An interrupted/incomplete gate is not a full positive compatibility map.
            for key in PREREQUISITES:
                self.record(key, 'not_evaluated')
        phase, guard = self.first or (self.phase, 'none')
        line = refused(reason).decode().rstrip('\n')
        line += ' phase=' + phase + ' guard=' + guard + ' private_capture=' + self.capture
        line += ''.join(' ' + key + '=' + value for key, value in self.statuses().items())
        return (line + '\n').encode('ascii')


def parse_body(body, code):
    assert type(body) is bytes and len(body) <= BODY_LIMIT
    success = re.fullmatch(rb'compose-env-semantic:v=1 result=equivalent strategy=(remove|explicit) scope=snapshot future=requires_recheck application=not_authorized\n', body)
    failure = re.fullmatch(rb'compose-env-semantic:v=1 result=unavailable reason=([a-z_]+)\n', body)
    if success or failure:
        assert (success and code == 0) or (failure and code == 1 and failure[1].decode() in REASONS)
    else:
        # Exact inventory and order; compact legacy/local failures make no capture claim.
        assert code == 1 and body.endswith(b'\n') and body.count(b'\n') == 1
        parts = body[:-1].decode('ascii').split(' ')
        keys = ('result', 'reason', 'phase', 'guard', 'private_capture') + PREREQUISITES
        assert parts[0] == PREFIX and len(parts) == len(keys) + 1
        values = {}
        for key, part in zip(keys, parts[1:]):
            assert part.startswith(key + '=')
            values[key] = part[len(key) + 1:]
        assert values['result'] == 'unavailable' and values['reason'] in REASONS
        assert values['phase'] in PHASES and values['guard'] in ('none',) + PREREQUISITES
        assert values['private_capture'] in ('not_started', 'attempted')
        assert all(values[key] in STATUSES for key in PREREQUISITES)
        if values['guard'] != 'none':
            assert values[values['guard']] == 'fail'
        else:
            assert all(values[key] != 'fail' for key in PREREQUISITES)
        if values['reason'] == 'runtime':
            assert values['guard'] != 'none'
        if values['phase'] in ('initial', 'pre_capture'):
            assert values['private_capture'] == 'not_started'
        if values['phase'] in ('post_open', 'capture', 'prepare', 'post_prepare'):
            assert values['private_capture'] == 'attempted'
    return body.decode().rstrip('\n'), code


def interrupted_body(body):
    # Final remote signal handoff retains already established capture attribution.
    # A successful body implies capture happened; a compact fallback proves nothing.
    if b' phase=' in body:
        parse_body(body, 1)
        body = re.sub(rb' reason=[a-z_]+ ', b' reason=interrupted ', body, count=1)
        return body.replace(b'=pass', b'=not_evaluated')
    if b' result=equivalent ' in body:
        evidence = Evidence()
        evidence.phase, evidence.capture = 'finalize', 'attempted'
        return evidence.refused('interrupted')
    return refused('interrupted')


class RuntimeMismatch(Exception):
    """Fixed failure, with the first guard retained in Evidence."""


class Runtime:
    """Unchanged root-installed build acceptance; bounded prerequisite evidence.

    Root/kernel remain trusted. Failed independent checks may be collected, but
    no subprocess or target capture is allowed until the entire initial gate passes.
    """
    def __init__(self, cancelled, evidence=None):
        self.cancelled, self.held = cancelled, []
        self.evidence = evidence or Evidence()
        self.total = 0

    def check(self, ok):
        if self.cancelled():
            raise P.Refused('interrupted')
        if not ok:
            raise RuntimeMismatch()

    def observe(self, guard, check):
        self.check(True)
        try:
            check()
        except P.Refused:
            raise
        except (KeyboardInterrupt, InterruptedError):
            raise
        except Exception:
            self.evidence.record(guard, 'fail')
            return False
        self.evidence.record(guard, 'pass')
        return True

    def require_compatible(self):
        self.check(True)
        if self.evidence.first is not None:
            raise RuntimeMismatch()

    def manifest(self):
        self.check(type(MANIFEST) is dict and MANIFEST.get('format') == 1)
        files = MANIFEST['files']
        self.check(type(files) is dict and 1 <= len(files) <= 256 and COMPOSE in files)
        self.check(all(type(path) is str and type(digest) is str and re.fullmatch('[0-9a-f]{64}', digest)
                       for path, digest in files.items()))
        aliases = MANIFEST['aliases']
        self.check(type(aliases) is dict and len(aliases) <= 256
                   and all(type(k) is str and type(v) is str for k, v in aliases.items()))

    def open(self):
        self.observe('platform', lambda: self.check(sys.platform == 'linux' and platform.machine() == 'aarch64'
                     and sys.flags.isolated and sys.flags.no_site))
        if not self.observe('manifest', self.manifest):
            self.require_compatible()
        files = MANIFEST['files']
        for path, digest in files.items():
            def safe_path():
                self.check(path == os.path.realpath(path) and os.path.isabs(path))
                for parent in Path(path).parents:
                    value = parent.stat()
                    self.check(value.st_uid == 0 and stat.S_ISDIR(value.st_mode) and not value.st_mode & 0o022)
            # Availability uses metadata only, even when the path is unsafe to read.
            available = self.observe('availability', lambda: os.stat(path, follow_symlinks=False))
            safe = self.observe('path_safety', safe_path)
            if not (available and safe):
                self.evidence.record('integrity', 'not_evaluated')
                continue
            fd = None
            def acquire():
                nonlocal fd
                fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
                # Register ownership BEFORE fstat, including an fstat failure.
                self.held.append((path, fd, None))
                value = os.fstat(fd)
                self.held[-1] = (path, fd, D.identity(value))
            if not self.observe('availability', acquire):
                self.evidence.record('integrity', 'not_evaluated')
                continue
            def metadata():
                value = os.fstat(fd)
                self.check(stat.S_ISREG(value.st_mode) and value.st_uid == 0 and not value.st_mode & 0o022
                           and value.st_size <= 33554432)
            if not self.observe('path_safety', metadata):
                self.evidence.record('integrity', 'not_evaluated')
                continue
            def hash_file():
                actual = hashlib.sha256()
                while True:
                    self.check(True)
                    # Shared budget remains finite even after a failed/oversized file.
                    self.check(self.total <= 134217728)
                    data = os.read(fd, min(65536, 134217729 - self.total))
                    self.total += len(data)
                    self.check(self.total <= 134217728)
                    if not data:
                        break
                    actual.update(data)
                self.check(actual.hexdigest() == digest)
            self.observe('integrity', hash_file)
        self.observe('interpreter', lambda: self.check(os.path.realpath('/proc/self/exe') in files))
        self.observe('modules', self.modules)
        self.recheck()

    def modules(self):
        for module in tuple(sys.modules.values()):
            for attribute in ('__file__', '__cached__'):
                path = getattr(module, attribute, None)
                if path:
                    if attribute == '__file__':
                        self.check(Path(path).is_file())
                    if Path(path).is_file():
                        self.check(os.path.realpath(path) in MANIFEST['files'])

    def mapped_files(self):
        with open('/proc/self/maps', 'rb') as stream:
            raw = stream.read(65537)
        self.check(len(raw) <= 65536)
        for line in raw.decode('utf-8').splitlines():
            fields = line.split(None, 5)
            if len(fields) == 6 and fields[-1].startswith('/'):
                self.check(fields[-1] in MANIFEST['files'])

    def private_root(self, temporary_root):
        def probe():
            fd = S.open_canonical_root(temporary_root)
            try:
                self.check(True)
            finally:
                try:
                    os.close(fd)
                except OSError:
                    raise P.Refused('cleanup') from None
        self.observe('private_root', probe)

    def ruby_probe(self, temporary_root):
        self.require_compatible()  # Never execute an unverified interpreter/alias.
        script = P.LINUX_RUBY_LOAD + """require 'psych'; require 'json'
maps=File.read('/proc/self/maps',65537)
raise unless maps.bytesize<=65536
paths=maps.lines.filter_map { |l| p=l.split(nil,6)[5]&.strip; p if p&.start_with?('/') }
puts JSON.generate({ruby:RUBY_VERSION,psych:Psych::VERSION,files:($LOADED_FEATURES.grep(%r{^/})+paths).uniq})
"""
        def probe():
            try:
                result = D.capture_result(['/usr/bin/ruby', '--disable-gems', '-e', script],
                    env=dict(PATH='/usr/bin:/bin', HOME=temporary_root, LC_ALL='C'), timeout=5, limit=32768)
            except D.CaptureCleanupError:
                raise P.Refused('cleanup') from None
            if result.failure == 'capture_interrupted':
                raise P.Refused('interrupted')
            self.check(result.failure is None and result.code == 0)
            data = json.loads(result.output)
            self.check(set(data) == {'ruby','psych','files'} and data['ruby'] == MANIFEST['ruby']
                       and data['psych'] == MANIFEST['psych'] and type(data['files']) is list
                       and len(data['files']) <= 256)
            self.check(all(type(path) is str and os.path.realpath(path) in MANIFEST['files'] for path in data['files']))
        self.observe('ruby', probe)
        self.recheck()

    def recheck(self):
        self.observe('maps', self.mapped_files)
        def aliases():
            for alias, expected in MANIFEST['aliases'].items():
                self.check(os.path.realpath(alias) == expected)
        self.observe('aliases', aliases)
        def descriptors():
            for path, fd, value in self.held:
                self.check(value is not None and D.identity(os.fstat(fd)) == value
                           and D.identity(os.stat(path, follow_symlinks=False)) == value)
        self.observe('descriptors', descriptors)
        self.require_compatible()

    def close(self):
        failed = False
        for _, fd, _ in reversed(self.held):
            try:
                os.close(fd)
            except BaseException:
                failed = True
        self.held.clear()
        if failed:
            raise P.Refused('cleanup')


def interpolation_context(environ):
    # Same application variables throughout all BEFORE/AFTER/reuse commands.
    # Tool/shell controls are deliberately private; references to them in any
    # input scalar are rejected by the planner, never silently approximated.
    result = {k: v for k, v in environ.items() if not P.controlled(k)}
    P.need(len(result) <= 256 and all(P.NAME.fullmatch(k) and type(v) is str and '\x00' not in v
                                     for k, v in result.items()), 'input')
    P.need(sum(len(k.encode())+len(v.encode()) for k, v in result.items()) <= 65536, 'bounds')
    return result


def diagnose(principal, cancelled):
    evidence = Evidence()
    runtime = Runtime(cancelled, evidence)
    context = None
    body = refused('io')
    try:
        # Independent non-executing observations still run after an initial mismatch.
        temporary_root = '/run/user/' + str(os.geteuid())
        try:
            runtime.open()
        except RuntimeMismatch:
            pass
        runtime.private_root(temporary_root)
        runtime.require_compatible()
        runtime.ruby_probe(temporary_root)
        evidence.phase = 'pre_capture'
        runtime.recheck()
        # Latch BEFORE private interpolation capture and open(), which itself
        # captures application.binding. An attempt does not prove a read succeeded.
        evidence.capture, evidence.phase = 'attempted', 'capture'
        interpolation = interpolation_context(dict(os.environ))
        context = D.ReadOnlyCapture(principal, cancelled)
        context.open()
        evidence.phase = 'post_open'
        runtime.recheck()
        evidence.phase = 'capture'
        main = context.bounded_read(context.file('compose', 'docker-compose.yml', modes=(0o600, 0o644)), 65536)
        D.require(D.scan(main)['violations'] == ('key_env_file',) and D.env_file_structure(main) ==
                  dict(env_file_occurrences='one', env_file_shapes=('service/app/sequence/canonical_dotenv',)), 'unsupported')
        override = context.read_file('compose', 'docker-compose.override.yml')
        release = context.read_file('state', 'docker-compose.release.yml')
        context.data[('compose', 'docker-compose.override.yml')] = override
        context.data[('state', 'docker-compose.release.yml')] = release
        records = D.StaticRecords(context.data, {}, context.project)
        D.require(records.evaluate('managed_override') == 'pass'
                  and records.evaluate('managed_release') == 'pass', 'identity')
        # This separate semantic authority is the only new secret-bearing read.
        dotenv = context.bounded_read(context.file('compose', '.env'), 65536)
        P.DIAGNOSTIC, P.CAPTURE, P.SAFE_ROOT = vars(D), D.capture_result, S.open_canonical_root
        evidence.phase = 'prepare'
        plan = P.prepare(main, dotenv, override, interpolation=interpolation,
            project=context.project, compose=COMPOSE, temporary_root=temporary_root,
            canonical_directory=D.COMPOSE_PATH)
        evidence.phase = 'post_prepare'
        context.recheck()
        D.require(context.mount_identity() == context.backing, 'backing')
        runtime.recheck()
        D.require(not cancelled(), 'interrupted')
        body = (PREFIX + ' result=equivalent strategy=' + plan.strategy +
                ' scope=snapshot future=requires_recheck application=not_authorized\n').encode()
        # The private candidate/model/snapshot bytes are never serialized outward.
    except RuntimeMismatch:
        body = evidence.refused('runtime')
    except P.Refused as error:
        body = evidence.refused(error.reason)
    except D.Unavailable as error:
        body = evidence.refused(error.args[0] if error.args else 'io')
    except (KeyboardInterrupt, InterruptedError):
        body = evidence.refused('interrupted')
    except BaseException:
        body = evidence.refused('io')
    finally:
        evidence.phase = 'finalize'
        for owned in (context, runtime):
            if owned is not None:
                try:
                    owned.close()
                except BaseException:
                    body = evidence.refused('cleanup')
    if cancelled():
        body = evidence.refused('interrupted')
    return body
