"""Local-only CLB-91 private-snapshot planner; no CLI, remote path or live writer.

Import this reviewed module and call prepare() with captured bytes and an explicit
interpolation environment. Private Plan.candidate is NEVER a public patch. Only
Plan.public() is reportable. The result proves one snapshot, not future .env edits
or permission to apply it. Existing diagnostic/helper/authority remain unchanged.
"""
import json
import math
import os
from pathlib import Path
import re
import runpy
import tempfile

ROOT = Path(__file__).resolve().parents[2]
# Existing inert modules: no main, BoundContext, transport or target capture runs.
DIAGNOSTIC = runpy.run_path(str(ROOT / 'scripts/deploy/stage-compose-diagnostic-operation.py'))
CAPTURE = runpy.run_path(str(ROOT / 'scripts/deploy/stage-compose-diagnostic.py'))['capture_result']
SAFE_ROOT = runpy.run_path(str(ROOT / 'scripts/deploy/release_private_root.py'))['open_canonical_root']
SUPPORTED_VERSION = b'5.1.1'
FILE_LIMIT = 65536
MODEL_LIMIT = 262144
NAME = re.compile(r'[A-Za-z_][A-Za-z0-9_]*\Z')
REASONS = frozenset(('input', 'unsupported', 'version', 'parser', 'model', 'different',
                     'io', 'cleanup', 'interrupted', 'bounds'))
CONTROL_KEYS = frozenset(('HOME', 'PATH', 'TMPDIR', 'TMP', 'TEMP', 'ENV', 'BASH_ENV',
                          'SHELLOPTS', 'BASHOPTS', 'CDPATH', 'RUBYOPT', 'RUBYLIB',
                          'GEM_HOME', 'GEM_PATH', 'LD_PRELOAD', 'LD_LIBRARY_PATH',
                          'PWD', 'OLDPWD', 'SHLVL', '_', 'IFS', 'OPTIND', 'OPTARG',
                          'PPID', 'UID', 'EUID', 'RANDOM', 'SECONDS', 'LINENO'))

# Existing repository Psych AST tooling, constrained here to the fixed shape.
# No constructors, aliases, merge expansion, files, custom tags or YAML emitter.
# Output is private structural offsets/names only, never normalized values.
OUTLINE = r'''
require 'psych'; require 'json'
begin
  raw = STDIN.read(65537)
  raise unless raw.bytesize <= 65536 && raw.valid_encoding?
  tree = Psych.parse_stream(raw)
  raise unless tree.children.size == 1
  count = 0
  references = []
  walk = lambda do |n, depth|
    count += 1
    raise if count > 4096 || depth > 32
    raise unless [Psych::Nodes::Stream, Psych::Nodes::Document, Psych::Nodes::Mapping,
                  Psych::Nodes::Sequence, Psych::Nodes::Scalar].include?(n.class)
    raise if (n.respond_to?(:anchor) && n.anchor) || (n.respond_to?(:tag) && n.tag)
    if n.is_a?(Psych::Nodes::Scalar)
      # YAML escapes can synthesize '$' or variable-name letters. Apply the
      # Python control-name policy to decoded references in EVERY scalar scope.
      references.concat(n.value.scan(/\$\{?([A-Za-z_][A-Za-z0-9_]*)/).flatten)
    end
    if n.is_a?(Psych::Nodes::Mapping)
      raise unless n.style == Psych::Nodes::Mapping::BLOCK
      seen = {}
      n.children.each_slice(2) do |k,v|
        raise unless k.is_a?(Psych::Nodes::Scalar) && k.plain &&
                     k.value.match?(/\A[A-Za-z0-9_.-]+\z/) && !seen[k.value]
        seen[k.value] = true
      end
    end
    (n.children || []).each { |child| walk.call(child, depth + 1) }
  end
  walk.call(tree, 0)
  mapping = lambda do |n|
    raise unless n.is_a?(Psych::Nodes::Mapping)
    n.children.each_slice(2).to_h { |k,v| [k.value, [k,v]] }
  end
  root = mapping.call(tree.children[0].root)
  raise unless (root.keys - %w[services volumes networks version name]).empty?
  services = mapping.call(root.fetch('services')[1])
  allowed = %w[image container_name depends_on environment healthcheck volumes ports restart
               expose command entrypoint networks working_dir user read_only init labels
               stop_grace_period env_file]
  environment_names = []
  services.each do |name, pair|
    fields = mapping.call(pair[1])
    raise unless (fields.keys - allowed).empty?
    raise if name != 'app' && fields.key?('env_file')
    if fields.key?('environment')
      entries = mapping.call(fields['environment'][1])
      raise if entries.empty?
      entries.each do |variable, entry|
        raise unless variable.match?(/\A[A-Za-z_][A-Za-z0-9_]*\z/) &&
                     entry[1].is_a?(Psych::Nodes::Scalar) && entry[1].start_line == entry[1].end_line
      end
      environment_names.concat(entries.keys)
    end
    if fields.key?('volumes')
      mounts = fields['volumes'][1]
      raise unless mounts.is_a?(Psych::Nodes::Sequence)
      mounts.children.each do |mount|
        # A tilde bind depends on parser HOME/user lookup, outside the supplied
        # non-control interpolation context. Restrict this check to sources;
        # ordinary environment strings containing '~' retain their semantics.
        raise unless mount.is_a?(Psych::Nodes::Scalar) && mount.start_line == mount.end_line
        # An interpolated source could become '~...' only after expansion;
        # decline dynamic sources instead of adding another interpolation parser.
        source = mount.value.split(':', 2)[0]
        raise if source.start_with?('~') || source.include?('$')
      end
    end
  end
  app = mapping.call(services.fetch('app')[1])
  key, refs = app.fetch('env_file')
  raise unless refs.is_a?(Psych::Nodes::Sequence) && refs.children.size.between?(1,16)
  refs.children.each do |item|
    raise unless item.is_a?(Psych::Nodes::Scalar) && %w[.env ./.env].include?(item.value) &&
                 item.start_line == item.end_line
  end
  last_line = refs.style == Psych::Nodes::Sequence::FLOW ? refs.end_line : refs.children[-1].end_line
  raise if refs.style == Psych::Nodes::Sequence::FLOW && refs.start_line != refs.end_line
  env = app['environment']
  names = []; insert = key.start_line; indent = key.start_column + 2
  if env
    env_fields = mapping.call(env[1])
    names = env_fields.keys
    insert = env_fields.values[-1][1].end_line + 1
    indent = env_fields.values[0][0].start_column
  end
  puts JSON.generate({remove: [key.start_line,last_line+1], insert: insert,
                      indent: indent, has_environment: !!env, names: names,
                      environment_names: environment_names, references: references.uniq})
rescue Exception
  exit 1
end
'''


class Refused(Exception):
    def __init__(self, reason):
        self.reason = reason if reason in REASONS else 'input'
        super().__init__(self.reason)


def need(ok, reason='input'):
    if not ok:
        raise Refused(reason)


class Plan:
    """Sensitive in-memory artifact; repr/str deliberately disclose no bytes."""
    __slots__ = ('candidate', 'strategy')

    def __init__(self, candidate, strategy):
        self.candidate, self.strategy = candidate, strategy

    def __repr__(self):
        return '<private CLB-91 plan; revalidation required>'

    def public(self):
        return ('compose-env-file-plan:v=1 result=equivalent strategy=' + self.strategy
                + ' scope=snapshot future=requires_recheck application=not_authorized')


def controlled(name):
    return name in CONTROL_KEYS or name.startswith(('COMPOSE_', 'DOCKER_', 'PYTHON', 'BASH',
        'DYLD_', 'LD_', 'RUBY', 'GEM_', 'GO', 'OTEL_'))


def unique(pairs):
    result = {}
    for key, value in pairs:
        need(key not in result, 'model')
        result[key] = value
    return result


def same_json(left, right):
    """Strict JSON identity, not Python's bool/number-coercing equality.

    Object order is irrelevant; key presence and array order are significant.
    Conservatively distinguish integer/float and finite float signed zero.
    Non-JSON types, non-string keys, nonfinite numbers and cycles cannot prove
    equivalence. Tokens remain private and are never part of public evidence.
    """
    def identity(value):
        kind = type(value)
        if kind is type(None):
            return ('null',)
        if kind is bool:
            return ('boolean', value)
        if kind is int:
            return ('integer', value)
        if kind is float:
            need(math.isfinite(value), 'model')
            return ('float', value.hex())
        if kind is str:
            return ('string', value)
        if kind is list:
            return ('array', tuple(identity(item) for item in value))
        if kind is dict:
            need(all(type(key) is str for key in value), 'model')
            return ('object', tuple((key, identity(value[key])) for key in sorted(value)))
        raise Refused('model')
    try:
        return identity(left) == identity(right)
    except RecursionError:
        raise Refused('model') from None


def capture(argv, env, payload=b'', limit=MODEL_LIMIT):
    # The reused bounded primitive intentionally has no cwd parameter. Fixed
    # quoted argv handoff keeps both parsers out of the caller's checkout/cwd.
    wrapped = ['/bin/sh', '-c', 'cd "$1" && shift && exec "$@"',
               'clb91-private-cwd', env['HOME'], *argv]
    result = CAPTURE(wrapped, payload, timeout=10, limit=limit, env=env)
    if result.failure == 'capture_interrupted':
        raise Refused('interrupted')
    need(result.failure is None and result.code == 0, 'parser')
    return result.output


def check_inputs(base, dotenv, override, interpolation, project):
    for value, limit in ((base,FILE_LIMIT), (dotenv,FILE_LIMIT), (override,4096)):
        need(type(value) is bytes and len(value) <= limit, 'bounds')
        need(b'\r' not in value and b'\x00' not in value, 'unsupported')
        text = value.decode('utf-8')
        # Private parser HOME/PWD/PATH differ from a deployment principal's.
        # Never silently substitute those values into a claimed proof. Even
        # quoted/escaped occurrences are conservatively unsupported here.
        need(not any(controlled(name) for name in re.findall(r'\$\{?([A-Za-z_][A-Za-z0-9_]*)', text)), 'unsupported')
    # Gate only this local proposal; NEVER relax the existing helper's subset.
    need(DIAGNOSTIC['scan'](base)['violations'] == ('key_env_file',), 'unsupported')
    shape = DIAGNOSTIC['env_file_structure'](base)
    need(shape == dict(env_file_occurrences='one',
        env_file_shapes=('service/app/sequence/canonical_dotenv',)), 'unsupported')
    expected = ('# clubs-bot-managed-quiesced-release\n# revision: ' + DIAGNOSTIC['REVISION']
                + '\nservices:\n  app:\n    image: ' + DIAGNOSTIC['IMAGE'] + '\n').encode()
    need(override == expected, 'unsupported')
    # Exact helper dotenv lexical boundary, deliberately not a dotenv parser.
    for line in dotenv.decode().splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        match = re.fullmatch(r'(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=.*', line)
        need(match is not None and not controlled(match[1]), 'unsupported')
    need(type(interpolation) is dict and len(interpolation) <= 256)
    need(all(type(k) is str and NAME.fullmatch(k) and not controlled(k) and
             type(v) is str and '\x00' not in v for k,v in interpolation.items()))
    need(sum(len(k.encode()) + len(v.encode()) for k,v in interpolation.items()) <= FILE_LIMIT, 'bounds')
    need(type(project) is str and re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,127}', project))


def transform(base, outline, additions=()):
    lines = base.splitlines(keepends=True)
    start, end = outline['remove']
    inserted = []
    if additions:
        if not outline['has_environment']:
            inserted.append(b' ' * (outline['indent'] - 2) + b'environment:\n')
        for name in additions:
            need(NAME.fullmatch(name) and not controlled(name), 'unsupported')
            # Names only: never freeze .env values, nulls or serialized $$ output.
            inserted.append((' ' * outline['indent'] + name + ': ${' + name
                             + '?CLB91 required contribution}\n').encode())
    result = []
    for index in range(len(lines) + 1):
        if index == outline['insert']:
            if result and not result[-1].endswith(b'\n'):
                result[-1] += b'\n'
            result.extend(inserted)
        if index < len(lines) and not start <= index < end:
            result.append(lines[index])
    candidate = b''.join(result)
    need(len(candidate) <= FILE_LIMIT, 'bounds')
    need(not DIAGNOSTIC['scan'](candidate)['violations'], 'unsupported')
    return candidate


def environment(model):
    app = model.get('services', {}).get('app')
    need(type(app) is dict and 'env_file' not in app, 'model')
    values = app.get('environment', {})
    need(type(values) is dict and all(type(k) is str and NAME.fullmatch(k) and
         (v is None or type(v) is str) for k,v in values.items()), 'model')
    return values


def prepare(base, dotenv, override, *, interpolation, project, compose, temporary_root):
    """Return a private local proposal after real, version-checked comparison.

    All input bytes/environment must be supplied explicitly by the private caller.
    This local API never discovers live paths/config/credentials, reads .env from
    the checkout, writes the caller's files, emits values or calls a daemon.
    Any exception carries only a fixed reason. Re-run on EVERY changed input.
    """
    try:
        check_inputs(base, dotenv, override, interpolation, project)
        need(type(compose) is str and os.path.isabs(compose) and Path(compose).is_file(), 'version')
        need(type(temporary_root) is str and not Path(temporary_root).resolve().is_relative_to(ROOT), 'input')
        fd = SAFE_ROOT(temporary_root)
        os.close(fd)
        with tempfile.TemporaryDirectory(prefix='clb91-private-plan-', dir=temporary_root) as workspace:
            root = Path(workspace)
            (root/'config').mkdir(mode=0o700)
            private_env = dict(PATH='/usr/bin:/bin', HOME=workspace, TMPDIR=workspace,
                DOCKER_CONFIG=str(root/'config'), DOCKER_HOST='unix://'+workspace+'/no-daemon.sock')
            env = dict(interpolation, **private_env)
            need(capture([compose, 'version', '--short'], private_env, limit=256).strip() == SUPPORTED_VERSION, 'version')
            outline = json.loads(capture(['/usr/bin/ruby', '-e', OUTLINE], private_env, base, limit=FILE_LIMIT))
            need(not any(controlled(name) for name in outline['environment_names']), 'unsupported')
            need(not any(controlled(name) for name in outline['references']), 'unsupported')

            def private_file(name, data):
                with open(root/name, 'xb', opener=lambda p,f: os.open(p,f,0o600)) as stream:
                    stream.write(data)

            private_file('.env', dotenv)
            private_file('override.yml', override)
            # Local synthetic project context, NOT a canonical stage adapter.
            options = [compose, '--project-name', project, '--project-directory', workspace,
                       '--env-file', str(root/'.env')]

            def normalize(data, name, with_override=True):
                private_file(name, data)
                args = options + ['-f', str(root/name)]
                if with_override:
                    args += ['-f', str(root/'override.yml')]
                raw = capture(args + ['config', '--format', 'json'], env)
                need(len(raw) <= MODEL_LIMIT, 'bounds')
                model = json.loads(raw, object_pairs_hook=unique,
                                   parse_constant=lambda _: (_ for _ in ()).throw(Refused('model')))
                need(type(model) is dict, 'model')
                environment(model)
                return model

            before = normalize(base, 'before.yml')
            candidate = transform(base, outline)
            after = normalize(candidate, 'removed.yml')
            strategy = 'remove'
            if not same_json(before, after):
                old, new = environment(before), environment(after)
                # Replacement can only restore missing variables, never alter an
                # existing environment entry, priority or another model field.
                additions = sorted(old.keys() - new.keys())
                need(0 < len(additions) <= 256 and all(old[k] is not None for k in additions), 'different')
                need(not set(additions).intersection(outline['names']), 'different')
                candidate = transform(base, outline, additions)
                after = normalize(candidate, 'explicit.yml')
                strategy = 'explicit'
            need(same_json(before, after), 'different')
            # Exercise the helper's resolved-JSON reuse, including literal dollars.
            encoded = lambda model: json.dumps(model, sort_keys=True, separators=(',', ':')).encode()
            need(same_json(normalize(encoded(before), 'before-resolved.json', False), before), 'model')
            need(same_json(normalize(encoded(after), 'after-resolved.json', False), after), 'model')
            # Values survive only in private memory; no public hash of secret data.
            plan = Plan(candidate, strategy)
        return plan  # Cleanup must succeed before returning any successful plan.
    except (KeyboardInterrupt, InterruptedError):
        raise Refused('interrupted') from None
    except Refused:
        raise
    except DIAGNOSTIC['Unavailable']:
        raise Refused('unsupported') from None
    except (UnicodeError, ValueError, TypeError, KeyError, RecursionError):
        raise Refused('input') from None
    except OSError:
        raise Refused('io') from None
    except RuntimeError:
        raise Refused('cleanup') from None


if __name__ == '__main__':
    raise SystemExit('compose-env-file-plan:v=1 result=refused reason=private_api_only')
