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


def parse_body(body, code):
    assert type(body) is bytes and len(body) <= 512
    success = re.fullmatch(rb'compose-env-semantic:v=1 result=equivalent strategy=(remove|explicit) scope=snapshot future=requires_recheck application=not_authorized\n', body)
    failure = re.fullmatch(rb'compose-env-semantic:v=1 result=unavailable reason=([a-z_]+)\n', body)
    assert (success and code == 0) or (failure and code == 1 and failure[1].decode() in REASONS)
    return body.decode().rstrip('\n'), code


class Runtime:
    """Fixed root-installed build allowlist, never a reported version/hash trust.

    Root remains a trusted installation authority, as in the existing directory
    contract. This does not attest a hostile kernel/root or install anything.
    Every pinned descriptor and pathname is revalidated after normalization.
    """
    def __init__(self, cancelled):
        self.cancelled, self.held = cancelled, []

    def check(self, ok):
        if self.cancelled():
            raise P.Refused('interrupted')
        if not ok:
            raise D.Unavailable('runtime')

    def open(self):
        self.check(sys.platform == 'linux' and platform.machine() == 'aarch64'
                   and sys.flags.isolated and sys.flags.no_site)
        self.check(type(MANIFEST) is dict and MANIFEST.get('format') == 1)
        files = MANIFEST['files']
        self.check(1 <= len(files) <= 256 and COMPOSE in files)
        total = 0
        for path, digest in files.items():
            self.check(path == os.path.realpath(path) and os.path.isabs(path))
            for parent in Path(path).parents:
                value = parent.stat()
                self.check(value.st_uid == 0 and stat.S_ISDIR(value.st_mode)
                           and not value.st_mode & 0o022)
            fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            self.held.append((path, fd, D.identity(os.fstat(fd))))
            value = os.fstat(fd)
            self.check(stat.S_ISREG(value.st_mode) and value.st_uid == 0 and not value.st_mode & 0o022
                       and value.st_size <= 33554432)
            actual = hashlib.sha256()
            while True:
                self.check(True)
                data = os.read(fd, 65536)
                total += len(data)
                self.check(total <= 134217728)
                if not data:
                    break
                actual.update(data)
            self.check(actual.hexdigest() == digest)
        # Resident interpreter and every imported stdlib module/cache are covered.
        self.check(os.path.realpath('/proc/self/exe') in files)
        for module in tuple(sys.modules.values()):
            for attribute in ('__file__', '__cached__'):
                path = getattr(module, attribute, None)
                if path:
                    if attribute == '__file__':
                        self.check(Path(path).is_file())
                    if Path(path).is_file():
                        self.check(os.path.realpath(path) in files)
        self.recheck()

    def mapped_files(self):
        with open('/proc/self/maps', 'rb') as stream:
            raw = stream.read(65537)
        self.check(len(raw) <= 65536)
        for line in raw.decode('utf-8').splitlines():
            fields = line.split(None, 5)
            if len(fields) == 6 and fields[-1].startswith('/'):
                self.check(fields[-1] in MANIFEST['files'])

    def ruby_probe(self, temporary_root):
        # No target data has been read. Probe the actual loaded Ruby/Psych/native
        # closure with the SAME fixed load path and clean tool environment.
        script = P.LINUX_RUBY_LOAD + """require 'psych'; require 'json'
maps=File.read('/proc/self/maps',65537)
raise unless maps.bytesize<=65536
paths=maps.lines.filter_map { |l| p=l.split(nil,6)[5]&.strip; p if p&.start_with?('/') }
puts JSON.generate({ruby:RUBY_VERSION,psych:Psych::VERSION,files:($LOADED_FEATURES.grep(%r{^/})+paths).uniq})
"""
        result = D.capture_result(['/usr/bin/ruby', '--disable-gems', '-e', script],
            env=dict(PATH='/usr/bin:/bin', HOME=temporary_root, LC_ALL='C'), timeout=5, limit=32768)
        self.check(result.failure is None and result.code == 0)
        data = json.loads(result.output)
        self.check(set(data) == {'ruby','psych','files'} and data['ruby'] == MANIFEST['ruby']
                   and data['psych'] == MANIFEST['psych'] and type(data['files']) is list
                   and len(data['files']) <= 256)
        self.check(all(type(path) is str and os.path.realpath(path) in MANIFEST['files'] for path in data['files']))
        self.recheck()

    def recheck(self):
        self.mapped_files()
        for alias, expected in MANIFEST['aliases'].items():
            self.check(os.path.realpath(alias) == expected)
        for path, fd, value in self.held:
            self.check(D.identity(os.fstat(fd)) == value
                       and D.identity(os.stat(path, follow_symlinks=False)) == value)

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
    runtime = Runtime(cancelled)
    context = None
    body = refused('io')
    try:
        # Unsupported/missing build refuses before any target config or .env read.
        try:
            runtime.open()
        except (OSError, ValueError, KeyError):
            raise D.Unavailable('runtime') from None
        temporary_root = '/run/user/' + str(os.geteuid())
        try:
            fd = S.open_canonical_root(temporary_root)
            os.close(fd)
            runtime.ruby_probe(temporary_root)
        except (OSError, ValueError, KeyError, RuntimeError):
            raise D.Unavailable('runtime') from None
        interpolation = interpolation_context(dict(os.environ))
        context = D.ReadOnlyCapture(principal, cancelled)
        context.open()  # Exact fixed directory/backing trust and shared locks.
        runtime.recheck()
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
        plan = P.prepare(main, dotenv, override, interpolation=interpolation,
            project=context.project, compose=COMPOSE, temporary_root=temporary_root,
            canonical_directory=D.COMPOSE_PATH)
        context.recheck()
        D.require(context.mount_identity() == context.backing, 'backing')
        runtime.recheck()
        D.require(not cancelled(), 'interrupted')
        body = (PREFIX + ' result=equivalent strategy=' + plan.strategy +
                ' scope=snapshot future=requires_recheck application=not_authorized\n').encode()
        # The private candidate/model/snapshot bytes are never serialized outward.
    except P.Refused as error:
        body = refused(error.reason)
    except D.Unavailable as error:
        body = refused(error.args[0] if error.args else 'io')
    except (KeyboardInterrupt, InterruptedError):
        body = refused('interrupted')
    except BaseException:
        body = refused('io')
    finally:
        for owned in (context, runtime):
            if owned is not None:
                try:
                    owned.close()
                except BaseException:
                    body = refused('cleanup')
    if cancelled():
        body = refused('interrupted')
    return body
