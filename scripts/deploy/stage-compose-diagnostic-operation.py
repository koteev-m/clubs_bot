"""Fixed CLB-91 read-only capture and lexical/static diagnostics; no entrypoint.

No YAML evaluation, BoundContext construction, Docker, referenced-file reads or
target filesystem writes. Reading can still affect filesystem atime/audit state.
"""
import errno
import fcntl
import hashlib
import os
import pwd
import re
import selectors
import signal
import stat
import subprocess
import time

COMPOSE_PATH = '/opt/clubs-bot-stage'
OWNER = '33468965282-1'
REVISION = '44497dcd28139cef865c3f98ac3f2c4a5afac636'
IMAGE = 'ghcr.io/koteev-m/clubs_bot/app-bot@sha256:ddf5486e02835855178cc3b30bd2f22899335131e6dc388def20feac328016fe'
PREFIX = 'compose-diagnostic:v=2'
BODY_LIMIT = 2048
FRAME_LIMIT = 4096
FILE_LIMIT = 65536
TOTAL_LIMIT = 196608
INVENTORY_LIMIT = 64
FD_LIMIT = 48
OPERATION_SECONDS = 20
DENIED_KEYS = ('include', 'extends', 'env_file', 'label_file', 'build', 'configs',
               'secrets', 'develop', 'provider', 'models')
BASIC = frozenset(('utf8_invalid', 'tab_active_line', 'document_prefix',
    'directive_prefix', 'tag_prefix', 'anchor_prefix', 'alias_prefix',
    'flow_mapping_prefix', 'flow_sequence_line_prefix', 'mapping_syntax',
    'block_scalar_value', 'top_level_key', *('key_' + key for key in DENIED_KEYS)))
DETAILS = frozenset(('bom', 'quoted_key', 'merge_key', 'x_extension', 'other'))
STATIC_FIELDS = ('static_inputs', 'managed_override', 'managed_release',
    'dotenv_metadata', 'retained_layout', 'retained_identity',
    'retained_checkpoint', 'prior_override', 'migration_records', 'result_record')
STATUSES = frozenset(('pass', 'invalid', 'not_evaluated'))
REASONS = frozenset(('request', 'principal', 'layout', 'identity', 'busy', 'backing',
                     'bounds', 'io', 'interrupted', 'cleanup', 'transport', 'protocol'))
SHAPE_LIMIT = 8
STRUCTURE_DEPTH = 32
STRUCTURE_LINES = 4096
KNOWN_SERVICES = frozenset(('app', 'db', 'caddy'))
UNKNOWN_SHAPE = 'unresolved/unknown/unresolved/not_applicable'
STRUCTURE_FIELDS = ('env_file_occurrences', 'env_file_shapes')
FIELDS = ('result', 'subset', 'violations', 'mapping_details', 'top_level_details',
          *STRUCTURE_FIELDS, *STATIC_FIELDS)
STATE_KEYS = frozenset('owner expected_revision image_digest compose_path_hash checkpoint prior_override_exists prior_override_sha256 old_app_digest old_app_revision old_container_hash old_image_id_hash old_started_at_hash old_restart_count compose_project compose_service candidate_override_sha256 migration_image_digest migration_image_id'.split())
IDENTITY_VALUES = ('owner', 'expected_revision', 'image_digest', 'compose_path_hash',
                   'compose_project', 'compose_service', 'migration_image_digest')
CHECKPOINT_VALUES = ('checkpoint', 'migration_image_id')
PRIOR_VALUES = ('prior_override_exists', 'prior_override_sha256', 'candidate_override_sha256')
# An executable allowlist, not a list inferred from live Compose keys.
FILE_READS = {
    ('parent', 'application.binding'): 2048,
    ('compose', 'docker-compose.override.yml'): 4096,
    ('state', 'docker-compose.release.yml'): 4096,
    ('state', 'prior-override'): 1024,
    ('ledger', OWNER + '.ledger'): 2048,
    ('ledger', OWNER + '.outcome'): 2048,
    ('results', OWNER + '.result'): 2048,
    **{('state', key): 4096 for key in (*IDENTITY_VALUES, *CHECKPOINT_VALUES,
                                      *PRIOR_VALUES, 'old_app_digest', 'old_app_revision')},
}
DEPENDENCIES = {
    'managed_override': (('compose', 'docker-compose.override.yml'),),
    'managed_release': (('state', 'docker-compose.release.yml'),),
    'retained_identity': tuple(('state', key) for key in IDENTITY_VALUES),
    'retained_checkpoint': tuple(('state', key) for key in CHECKPOINT_VALUES),
    'prior_override': (('state', 'prior-override'), ('compose', 'docker-compose.override.yml'),
                       *(('state', key) for key in PRIOR_VALUES)),
    'migration_records': (('ledger', OWNER + '.ledger'), ('ledger', OWNER + '.outcome')),
    'result_record': (('results', OWNER + '.result'),),
}


class Unavailable(Exception):
    """Whole-report trust/completeness failure; fixed reason only."""


class Invalid(Exception):
    """An observed static predicate is false; not a trust bypass."""


class DependencyUnavailable(Exception):
    """No predicate result can be claimed for an uncaptured dependency."""


class InputUnavailable(Unavailable):
    """A single static file failed metadata checks, before reading its content."""


def require(ok, reason='identity'):
    if not ok:
        raise Unavailable(reason)


def check(ok):
    if not ok:
        raise Invalid()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def lexical_detail(line):
    if line.startswith('\ufeff'):
        return 'bom'
    if re.match(r'''^(?:"(?:[^"\\]|\\.)*"|'(?:[^']|'')*')\s*:''', line):
        return 'quoted_key'
    if line.startswith('<<:'):
        return 'merge_key'
    if line.startswith('x-'):
        return 'x_extension'
    return 'other'


def scan(data):
    """Aggregate the original lexical scope; never interpret YAML semantics."""
    require(type(data) is bytes and len(data) <= FILE_LIMIT, 'bounds')
    violations, mapping, top = set(), set(), set()
    try:
        text = data.decode('utf-8')
    except UnicodeDecodeError:
        violations.add('utf8_invalid')
        text = data.decode('utf-8', 'surrogateescape')

    def prefixes(value):
        for prefix, category in (('!', 'tag_prefix'), ('&', 'anchor_prefix'),
                                 ('*', 'alias_prefix'), ('{', 'flow_mapping_prefix')):
            if value.startswith(prefix):
                violations.add(category)

    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith('#'):
            continue
        if '\t' in line:
            violations.add('tab_active_line')
        if stripped.startswith(('---', '...')):
            violations.add('document_prefix')
        if stripped.startswith('%'):
            violations.add('directive_prefix')
        if stripped.startswith('['):
            violations.add('flow_sequence_line_prefix')
        prefixes(stripped)
        if stripped.startswith('- '):
            prefixes(stripped[2:])  # No additional strip; original list branch.
            continue
        match = re.match(r'([A-Za-z0-9_.-]+):(?:\s+(.*))?$', stripped)
        if not match:
            violations.add('mapping_syntax')
            mapping.add(lexical_detail(stripped))
            continue  # A key/value/top-level predicate is not applicable.
        key, value = match[1], match[2] or ''
        if key in DENIED_KEYS:
            violations.add('key_' + key)
        prefixes(value)
        if value.startswith(('|', '>')):
            violations.add('block_scalar_value')
        if not line.startswith(' ') and key not in ('services', 'volumes', 'version', 'name', 'networks'):
            violations.add('top_level_key')
            top.add('x_extension' if key.startswith('x-') else 'other')
    return dict(violations=tuple(sorted(violations)), mapping_details=tuple(sorted(mapping)),
                top_level_details=tuple(sorted(top)))


def unavailable(reason):
    require(reason in REASONS, 'protocol')
    return f'{PREFIX} result=unavailable reason={reason}\n'.encode('ascii')


def env_file_structure(data):
    """Lexical indentation observations, NOT a YAML parser or Compose model.

    Count exactly the scanner's mapping-branch collisions, even in unsupported
    syntax. Assign locations only for a whole plain indentation outline. An
    ambiguous outline discards ALL locations, never a partial resolved list.
    Values stay private; the only recognized reference literals are .env and
    ./.env, optionally quoted. Nothing is interpolated, normalized or opened.
    """
    lexical = scan(data)
    allowed = {'top_level_key', *('key_' + key for key in DENIED_KEYS)}
    reliable = set(lexical['violations']) <= allowed
    text = data.decode('utf-8', 'surrogateescape')
    reliable &= not any(c in text.replace('\r\n', '\n') for c in '\r\v\f\x1c\x1d\x1e\x85\u2028\u2029')
    nodes, stack, occurrences = [], [], []
    children, seen = {-1: []}, {}
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith('#'):
            continue
        require(len(nodes) < STRUCTURE_LINES, 'bounds')
        indent = len(line) - len(line.lstrip(' '))
        if line[indent:].rstrip(' ') != stripped or any(ord(c) < 32 or (c.isspace() and c != ' ') for c in line):
            reliable = False
        listing = stripped.startswith('- ')
        match = None if listing else re.fullmatch(r'([A-Za-z0-9_.-]+):(?:\s+(.*))?', stripped)
        key = match[1] if match else None
        value = (match[2] or '') if match else stripped[2:] if listing else stripped
        if value.startswith('#'):
            value = ''
        if not listing and match is None:
            reliable = False
        while stack and nodes[stack[-1]]['indent'] >= indent:
            stack.pop()
        require(len(stack) < STRUCTURE_DEPTH, 'bounds')
        parent = stack[-1] if stack else -1
        if parent == -1:
            reliable &= indent == 0 and not listing
        else:
            reliable &= not nodes[parent]['value'] and nodes[parent]['key'] is not None
        siblings = children[parent]
        if siblings:
            first = nodes[siblings[0]]
            reliable &= first['indent'] == indent and first['listing'] == listing
        if key is not None:
            identity = (parent, key)
            if identity in seen:
                reliable = False  # Duplicate maps cannot identify a Compose location.
            seen[identity] = True
        # Reject multiline/ambiguous scalar outlines without evaluating values.
        if value.startswith(('"', "'")):
            reliable &= bool(re.fullmatch(r'''(?:"(?:[^"\\]|\\.)*"|'(?:[^']|'')*')(?: +#.*)?''', value))
        elif value and not value.startswith(('[', '{')) and re.search(r':(?:\s|$)', value):
            reliable = False
        index = len(nodes)
        nodes.append(dict(indent=indent, key=key, value=value, listing=listing, parent=parent))
        children[index] = []
        siblings.append(index)
        if key == 'env_file':
            occurrences.append(index)
        stack.append(index)
    bucket = 'zero' if not occurrences else 'one' if len(occurrences) == 1 else 'multiple'
    if not occurrences:
        return dict(env_file_occurrences=bucket, env_file_shapes=())
    if not reliable:
        return dict(env_file_occurrences=bucket, env_file_shapes=(UNKNOWN_SHAPE,))

    def dotenv_literal(value):
        return bool(re.fullmatch(r'''(?:(?:\./)?\.env|"(?:\./)?\.env"|'(?:\./)?\.env')(?: +#.*)?''', value))

    shapes = set()
    for index in occurrences:
        node, ancestry = nodes[index], []
        parent = node['parent']
        while parent != -1:
            ancestry.append(nodes[parent]['key'])
            parent = nodes[parent]['parent']
        ancestry.reverse()
        service, scope = 'none', 'other'
        if len(ancestry) >= 2 and ancestry[0] == 'services':
            service = ancestry[1] if ancestry[1] in KNOWN_SERVICES else 'other'
            if len(ancestry) == 2:
                scope = 'service'
            elif len(ancestry) == 3 and ancestry[2] == 'environment':
                scope = 'environment'
        value, nested = node['value'], children[index]
        literals = []
        if value.startswith('['):
            form = 'sequence'
            # Only a single-line sequence of exact fixed literals is recognized.
            match = re.fullmatch(r'\[(.*)\](?: +#.*)?', value)
            if match and '#' not in match[1]:
                literals = [part.strip() for part in match[1].split(',')]
        elif value:
            form, literals = 'scalar', [value]
        elif nested:
            form = 'sequence' if nodes[nested[0]]['listing'] else 'mapping'
            if form == 'sequence' and all(not children[child] for child in nested):
                literals = [nodes[child]['value'] for child in nested]
        else:
            form = 'empty'
        reference = 'not_applicable'
        if scope == 'service':
            reference = 'canonical_dotenv' if literals and all(map(dotenv_literal, literals)) else 'other_or_unknown'
        shapes.add('/'.join((scope, service, form, reference)))
        require(len(shapes) <= SHAPE_LIMIT, 'bounds')
    return dict(env_file_occurrences=bucket, env_file_shapes=tuple(sorted(shapes)))


def valid_env_file_shape(shape):
    if shape == UNKNOWN_SHAPE:
        return True
    parts = shape.split('/')
    if len(parts) != 4:
        return False
    scope, service, form, reference = parts
    if form not in ('scalar', 'sequence', 'mapping', 'empty'):
        return False
    if scope in ('service', 'environment'):
        if service not in KNOWN_SERVICES | {'other'}:
            return False
    elif scope != 'other' or service not in KNOWN_SERVICES | {'other', 'none'}:
        return False
    if scope != 'service':
        return reference == 'not_applicable'
    return reference == 'other_or_unknown' or (reference == 'canonical_dotenv' and form in ('scalar', 'sequence'))


def complete(lexical, statuses, structure):
    require(set(lexical) == {'violations', 'mapping_details', 'top_level_details'}, 'protocol')
    require(set(statuses) == set(STATIC_FIELDS), 'protocol')
    fields = dict(result='complete', subset='invalid' if lexical['violations'] else 'clear')
    for key, vocabulary in (('violations', BASIC), ('mapping_details', DETAILS), ('top_level_details', DETAILS)):
        values = lexical[key]
        require(type(values) is tuple and tuple(sorted(set(values))) == values
                and set(values) <= vocabulary, 'protocol')
        fields[key] = ','.join(values) if values else 'none'
    require(bool(lexical['mapping_details']) == ('mapping_syntax' in lexical['violations'])
            and bool(lexical['top_level_details']) == ('top_level_key' in lexical['violations']), 'protocol')
    require(set(structure) == set(STRUCTURE_FIELDS), 'protocol')
    bucket, shapes = (structure[key] for key in STRUCTURE_FIELDS)
    require(bucket in ('zero', 'one', 'multiple') and type(shapes) is tuple
            and len(shapes) <= SHAPE_LIMIT and tuple(sorted(set(shapes))) == shapes
            and all(type(shape) is str and valid_env_file_shape(shape) for shape in shapes), 'protocol')
    require((bucket == 'zero') == (not shapes) == ('key_env_file' not in lexical['violations'])
            and (bucket != 'one' or len(shapes) == 1)
            and (UNKNOWN_SHAPE not in shapes or shapes == (UNKNOWN_SHAPE,)), 'protocol')
    fields['env_file_occurrences'] = bucket
    fields['env_file_shapes'] = ','.join(shapes) if shapes else 'none'
    for key in STATIC_FIELDS:
        require(statuses[key] in (STATUSES | ({'absent'} if key == 'dotenv_metadata' else set())), 'protocol')
        fields[key] = statuses[key]
    body = (PREFIX + ' ' + ' '.join(key + '=' + fields[key] for key in FIELDS) + '\n').encode('ascii')
    require(len(body) <= BODY_LIMIT, 'bounds')
    return body


def parse_body(body, code):
    require(type(body) is bytes and len(body) <= BODY_LIMIT, 'protocol')
    match = re.fullmatch(rb'compose-diagnostic:v=2 result=unavailable reason=([a-z_]+)\n', body)
    if match:
        require(match[1].decode() in REASONS and code == 1, 'protocol')
        return body.decode().rstrip('\n'), 1
    require(code == 0, 'protocol')
    try:
        text = body.decode('ascii')
        require(text.startswith(PREFIX + ' ') and text.endswith('\n'), 'protocol')
        pairs = [part.split('=') for part in text[len(PREFIX) + 1:-1].split(' ')]
        require(all(len(pair) == 2 for pair in pairs)
                and tuple(pair[0] for pair in pairs) == FIELDS, 'protocol')
        values = dict(pairs)
        lexical = {key: (() if values[key] == 'none' else tuple(values[key].split(',')))
                   for key in ('violations', 'mapping_details', 'top_level_details')}
        structure = dict(env_file_occurrences=values['env_file_occurrences'],
                         env_file_shapes=() if values['env_file_shapes'] == 'none' else tuple(values['env_file_shapes'].split(',')))
        require(complete(lexical, {key: values[key] for key in STATIC_FIELDS}, structure) == body, 'protocol')
        return text[:-1], 0
    except (UnicodeError, KeyError, ValueError):
        raise Unavailable('protocol') from None


class StaticRecords:
    """Exact approved static predicates over captured bytes, without BoundContext.

    Tests extract the original scope bodies from the approved helper as oracle.
    No normalization, backend result or inferred binding is supplied here.
    """
    state_keys = STATE_KEYS

    def __init__(self, data, inventories, project):
        self.data, self.inventories, self.project = data, inventories, project
        self.owner, self.revision, self.image = OWNER, REVISION, IMAGE
        self.path_hash = sha(COMPOSE_PATH.encode())

    def read(self, directory, name, limit=4096):
        data = self.data.get((directory, name))
        if data is None:
            raise DependencyUnavailable()
        check(len(data) <= limit)
        return data

    def record(self, directory, name, count):
        data = self.read(directory, name, 2048).decode('ascii')
        check(len(data.splitlines()) == count and not data.endswith('\n'))
        pairs = [line.split('=', 1) for line in data.split('\n')]
        check(all(len(pair) == 2 and pair[0] and pair[1] for pair in pairs))
        result = {}
        for key, value in pairs:
            check(key not in result)
            result[key] = value
        return result

    def value(self, key):
        check(key in self.state_keys)
        data = self.read('state', key)
        check(data and b'\n' not in data and all(32 <= b <= 126 for b in data))
        return data.decode('ascii')

    def managed(self, directory, name):
        expected = f'# clubs-bot-managed-quiesced-release\n# revision: {self.revision}\nservices:\n  app:\n    image: {self.image}\n'.encode()
        check(self.read(directory, name) == expected)

    def retained_layout(self):
        check('active-candidate.anchor' not in self.inventories['parent'])
        check(self.inventories['root'] <= {'clubs-bot-schema-stage.lock', 'clubs-bot-schema-stage.results',
            'clubs-bot-schema-stage.migration-ledgers', '.clb82-resume-start-33468965282-1.consumed'})
        check(self.inventories['state'] == self.state_keys | {'docker-compose.release.yml', 'prior-override'})

    def retained_identity(self):
        for key, value in dict(owner=self.owner, expected_revision=self.revision, image_digest=self.image,
                               compose_path_hash=self.path_hash, compose_project=self.project, compose_service='app',
                               migration_image_digest=self.image).items():
            check(self.value(key) == value)

    def retained_checkpoint(self):
        check(self.value('checkpoint') in ('migration_completed', 'candidate_start_begun', 'candidate_healthy'))
        check(re.fullmatch(r'sha256:[0-9a-f]{64}', self.value('migration_image_id')))

    def prior_override(self):
        prior = self.read('state', 'prior-override', 1024)
        check(sha(prior) == self.value('prior_override_sha256'))
        if self.value('prior_override_exists') == 'no':
            check(prior == b'absent')
        else:
            check(self.value('prior_override_exists') == 'yes')
            old_image, old_revision = self.value('old_app_digest'), self.value('old_app_revision')
            check(re.fullmatch(r'ghcr\.io/[a-z0-9._/-]+@sha256:[0-9a-f]{64}', old_image)
                  and re.fullmatch('[0-9a-f]{40}', old_revision))
            prefix = '# clubs-bot-managed-quiesced-release\n'
            check(prior in ((prefix + f'# revision: {old_revision}\nservices:\n  app:\n    image: {old_image}\n').encode(),
                            (prefix + f'services:\n  app:\n    image: {old_image}\n').encode()))
        check(self.value('candidate_override_sha256') == sha(self.read('compose', 'docker-compose.override.yml')))

    def migration_records(self):
        common = dict(owner=self.owner, environment='stage', expected_revision=self.revision, image_digest=self.image,
                      compose_path_hash=self.path_hash, operation='migration',
                      invocation_fingerprint=sha(f'v1|stage|{self.owner}|{self.revision}|{self.image}|{self.path_hash}'.encode()))
        ledger = self.record('ledger', self.owner + '.ledger', 13)
        outcome = self.record('ledger', self.owner + '.outcome', 12)
        check(self.inventories['ledger'] == {self.owner + '.ledger', self.owner + '.outcome'})
        for value, required, epochs in (
            (ledger, dict(ledger_version='1', state='completed', result='completed', completion_checkpoint='migration_completed'), ('created_epoch', 'completed_epoch')),
            (outcome, dict(outcome_version='1', state='succeeded', bounded_result='migration_succeeded', completion_checkpoint='migration_process_succeeded'), ('recorded_epoch',))):
            check(set(value) == set(common) | set(required) | set(epochs))
            check(all(value.get(k) == v for k, v in {**common, **required}.items()))
            check(all(re.fullmatch('[0-9]{1,12}', value[k]) for k in epochs))

    def result_record(self):
        result = self.record('results', self.owner + '.result', 10)
        check(set(result) == set('result_version owner requested_operation checkpoint_before checkpoint_after result failure_category expected_revision image_digest compose_path_hash'.split()))
        check(all(result.get(k) == v for k, v in dict(result_version='1', owner=self.owner, expected_revision=self.revision,
                                                   image_digest=self.image, compose_path_hash=self.path_hash).items()))
        check(result['requested_operation'] in ('start', 'resume-start'))
        check(result['checkpoint_before'] in ('migration_completed', 'candidate_start_begun', 'candidate_healthy'))
        check(result['checkpoint_after'] in ('migration_completed', 'candidate_start_begun', 'candidate_healthy', 'unavailable'))
        check((result['result'], result['failure_category']) in (
            ('success', 'success'), ('remote_failure', 'app_identity_mismatch'), ('remote_failure', 'candidate_start_failed'),
            ('remote_failure', 'readiness_failed'), ('remote_failure', 'health_failed'), ('remote_failure', 'unexpected'),
            ('remote_failure', 'child_exit_255'), ('remote_failure', 'durability_failure'),
            ('incomplete_unknown', 'operation_in_progress'), ('incomplete_unknown', 'interrupted')))

    def evaluate(self, field):
        dependencies = DEPENDENCIES.get(field, ())
        if field == 'prior_override' and self.data.get(('state', 'prior_override_exists')) == b'yes':
            dependencies += (('state', 'old_app_digest'), ('state', 'old_app_revision'))
        if any(key not in self.data for key in dependencies):
            return 'not_evaluated'
        try:
            if field == 'managed_override':
                self.managed('compose', 'docker-compose.override.yml')
            elif field == 'managed_release':
                self.managed('state', 'docker-compose.release.yml')
            else:
                getattr(self, field)()
            return 'pass'
        except DependencyUnavailable:
            return 'not_evaluated'
        except (Invalid, UnicodeError, KeyError):
            return 'invalid'


def identity(value):
    # Reads may change atime. All observable identity/content metadata remains
    # part of the in-invocation snapshot, including directory/record edges.
    return (value.st_dev, value.st_ino, value.st_uid, value.st_gid, value.st_mode,
            value.st_nlink, value.st_size, value.st_mtime_ns, value.st_ctime_ns)


class InputMissing(InputUnavailable):
    """ENOENT observed at the fixed file open, never a later acquisition error."""


class ReadOnlyCapture:
    """Fixed descriptor graph, shared protocol locks and bounded private reads."""
    def __init__(self, principal, cancelled):
        # Construction allocates nothing; the caller owns cleanup before open().
        self.principal, self.cancelled = principal, cancelled
        self.fds, self.directories, self.snapshots, self.edges = [], {}, {}, []
        self.missing, self.opened, self.data, self.inventories = set(), set(), {}, {}
        self.rejected_edges = {}
        self.total, self.inputs_valid = 0, True
        self.deadline = time.monotonic() + OPERATION_SECONDS

    def tick(self):
        require(not self.cancelled(), 'interrupted')
        require(time.monotonic() < self.deadline, 'bounds')

    def keep(self, fd):
        self.fds.append(fd)
        require(len(self.fds) <= FD_LIMIT, 'bounds')
        self.snapshots[fd] = identity(os.fstat(fd))
        return fd

    def open(self):
        self.tick()
        self.uid = os.geteuid()
        require(self.uid != 0 and self.principal not in ('root', 'hookah-staging')
                and pwd.getpwuid(self.uid).pw_name == self.principal, 'principal')
        root = self.keep(os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_NONBLOCK))
        value = os.fstat(root)
        require(stat.S_ISDIR(value.st_mode) and value.st_uid == 0
                and not stat.S_IMODE(value.st_mode) & 0o022, 'layout')
        self.directories['/'] = root
        for key, parent, name, private in (
            ('opt', '/', 'opt', False), ('compose', 'opt', 'clubs-bot-stage', False),
            ('parent', 'compose', '.clubs-bot-release-state', True), ('root', 'parent', 'stage', True),
            ('state', 'root', 'clubs-bot-schema-stage.lock', True),
            ('results', 'root', 'clubs-bot-schema-stage.results', True),
            ('ledger', 'root', 'clubs-bot-schema-stage.migration-ledgers', True)):
            self.tick()
            base = self.directories[parent]
            child = self.keep(os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=base))
            self.edges.append((base, name, child))
            self.directories[key] = child
            value = os.fstat(child)
            require(stat.S_ISDIR(value.st_mode) and value.st_uid in (0, self.uid)
                    and not stat.S_IMODE(value.st_mode) & 0o022, 'layout')
            if key != 'opt':
                require(value.st_uid == self.uid, 'identity')
            if private:
                require(stat.S_IMODE(value.st_mode) == 0o700, 'layout')
                require(value.st_dev == os.fstat(self.directories['compose']).st_dev, 'identity')
        for key, directory, name in (('application_lock', 'parent', 'application.lock'),
                                     ('operation_lock', 'results', 'operation.lock')):
            fd = self.file(directory, name)
            self.directories[key] = fd
        # Same application -> operation protocol as inspect; no create or write.
        for key in ('application_lock', 'operation_lock'):
            self.tick()
            try:
                fcntl.flock(self.directories[key], fcntl.LOCK_SH | fcntl.LOCK_NB)
            except BlockingIOError:
                raise Unavailable('busy') from None
        self.recheck()
        # Binding/backing trust is established before base/dependent captures.
        self.data[('parent', 'application.binding')] = self.read_file('parent', 'application.binding')
        records = StaticRecords(self.data, {}, None)
        binding = records.record('parent', 'application.binding', 7)
        self.project = binding.get('compose_project')
        require(re.fullmatch('[A-Za-z0-9_.-]{1,128}', self.project or ''), 'backing')
        self.backing = self.mount_identity()
        require(binding == dict(binding_version='3', environment='stage', compose_path_hash=sha(COMPOSE_PATH.encode()),
                mount_fingerprint_version='2', mount_fingerprint=self.backing, compose_project=self.project,
                compose_service='app'), 'backing')
        self.recheck()

    def file(self, directory, name, *, modes=(0o600,)):
        self.tick()
        require((directory, name) not in self.opened, 'identity')
        self.opened.add((directory, name))
        base = self.directories[directory]
        try:
            fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=base)
        except FileNotFoundError:
            self.missing.add((base, name))
            raise InputMissing('io') from None
        except OSError as error:
            # ELOOP is an expected negative observation only when no-follow
            # metadata proves this exact edge is a symlink. No other errno is
            # absence/invalid evidence (including EACCES and resource failures).
            if error.errno != errno.ELOOP:
                raise
            value = os.stat(name, dir_fd=base, follow_symlinks=False)
            require(stat.S_ISLNK(value.st_mode), 'io')
            self.rejected_edges[(base, name)] = identity(value)
            raise InputUnavailable('identity') from None
        self.keep(fd)  # Own before fstat; errors here are acquisition failures.
        self.edges.append((base, name, fd))
        value = os.fstat(fd)
        if not (stat.S_ISREG(value.st_mode) and value.st_uid == self.uid and value.st_nlink == 1
                and stat.S_IMODE(value.st_mode) in modes and value.st_dev == os.fstat(base).st_dev):
            raise InputUnavailable('identity')
        return fd

    def bounded_read(self, fd, limit):
        data = bytearray()
        self.recheck()
        while True:
            self.tick()
            try:
                chunk = os.read(fd, min(65536, limit + 1 - len(data)))
            except InterruptedError:
                raise
            except OSError:
                raise Unavailable('io') from None
            self.total += len(chunk)
            require(self.total <= TOTAL_LIMIT, 'bounds')
            if not chunk:
                break
            data.extend(chunk)
            require(len(data) <= limit, 'bounds')
        self.recheck()
        return bytes(data)

    def read_file(self, directory, name):
        require((directory, name) in FILE_READS, 'layout')
        fd = self.file(directory, name)
        return self.bounded_read(fd, FILE_READS[(directory, name)])

    def inventory(self, key):
        self.tick()
        entries = set()
        with os.scandir(self.directories[key]) as iterator:
            for entry in iterator:
                self.tick()
                require(len(entries) < INVENTORY_LIMIT and len(os.fsencode(entry.name)) <= 255, 'bounds')
                entries.add(entry.name)
        return entries

    def recheck(self):
        self.tick()
        try:
            self.check_observed_edges()
        except InterruptedError:
            raise
        except OSError:
            raise Unavailable('io') from None

    def check_observed_edges(self):
        for fd in self.fds:
            require(identity(os.fstat(fd)) == self.snapshots[fd], 'identity')
        for parent, name, child in self.edges:
            try:
                value = os.stat(name, dir_fd=parent, follow_symlinks=False)
            except FileNotFoundError:
                raise Unavailable('identity') from None
            require(not stat.S_ISLNK(value.st_mode) and identity(value) == self.snapshots[child], 'identity')
        for (parent, name), observed in self.rejected_edges.items():
            try:
                value = os.stat(name, dir_fd=parent, follow_symlinks=False)
            except FileNotFoundError:
                raise Unavailable('identity') from None
            require(identity(value) == observed, 'identity')
        for parent, name in self.missing:
            try:
                os.stat(name, dir_fd=parent, follow_symlinks=False)
            except FileNotFoundError:
                continue
            raise Unavailable('identity')
        for key, entries in self.inventories.items():
            require(self.inventory(key) == entries, 'identity')

    def mount_identity(self):
        # Exact approved findmnt fingerprint predicate, with bounded capture and
        # time limits added. No filesystem enumeration or Docker dependency.
        fingerprints = set()
        for key in ('compose', 'parent', 'root', 'state', 'results', 'ledger', 'application_lock', 'operation_lock'):
            self.tick()
            fd = self.directories[key]
            path = f'/proc/{os.getpid()}/fd/{fd}'
            if not os.path.exists(path):
                path = f'/dev/fd/{fd}'
            require(os.path.exists(path), 'backing')
            result = capture_result(['findmnt', '--noheadings', '--pairs', '--output', 'FSTYPE,SOURCE,FSROOT,TARGET',
                                     '--target', path], timeout=min(2, self.deadline - time.monotonic()),
                                    limit=4096, env={'PATH': os.defpath, 'LC_ALL': 'C'}, pass_fds=(fd,))
            self.tick()
            require(result.failure is None and result.code == 0, 'backing')
            line = result.output.decode('ascii').strip()
            match = re.fullmatch(r'FSTYPE="([^"\s]+)" SOURCE="([^"\s]+)" FSROOT="([^"\s]+)" TARGET="([^"\s]+)"', line)
            require(match, 'backing')
            values = []
            for value in match.groups():
                require(not re.search(r'\\(?!x[0-9a-fA-F]{2})', value), 'backing')
                value = re.sub(r'\\x([0-9a-fA-F]{2})', lambda m: chr(int(m[1], 16)), value)
                require('\x00' not in value, 'backing')
                values.append(value)
            require(values[0] in ('ext2', 'ext3', 'ext4', 'xfs', 'btrfs', 'zfs', 'f2fs'), 'backing')
            require(values[2].startswith('/') and values[3].startswith('/'), 'backing')
            encoded = 'clubs-bot-mount-fingerprint-version=2\n' + '\n'.join(
                key + '_SHA256=' + sha(value.encode()) for key, value in zip(('FSTYPE', 'SOURCE', 'FSROOT', 'TARGET'), values))
            fingerprints.add('mount-v2:' + sha(encoded.encode()))
        require(len(fingerprints) == 1, 'backing')
        return fingerprints.pop()

    def capture_optional_metadata(self):
        # Open and fstat only. .env content is never read or declared valid.
        try:
            self.file('compose', '.env')
            return 'pass'
        except InputMissing:
            return 'absent'
        except InputUnavailable:
            return 'invalid'

    def capture_record(self, directory, name):
        try:
            self.data[(directory, name)] = self.read_file(directory, name)
        except InputUnavailable:
            # Proven unsafe metadata or observed missing input; consumers
            # depending on its contents are not evaluated. Never substitute b''.
            self.inputs_valid = False

    def report(self):
        self.open()
        main_fd = self.file('compose', 'docker-compose.yml', modes=(0o600, 0o644))
        main = self.bounded_read(main_fd, FILE_LIMIT)  # Sole open/read of base file.
        lexical = scan(main)
        structure = env_file_structure(main)
        for key in ('parent', 'root', 'state', 'ledger'):
            self.inventories[key] = self.inventory(key)
        self.recheck()
        dotenv = self.capture_optional_metadata()
        for directory, name in FILE_READS:
            if (directory, name) == ('parent', 'application.binding') or name in ('old_app_digest', 'old_app_revision'):
                continue
            self.capture_record(directory, name)
        # The original prior-override yes branch is the only consumer of these.
        if self.data.get(('state', 'prior_override_exists')) == b'yes':
            for name in ('old_app_digest', 'old_app_revision'):
                self.capture_record('state', name)
        records = StaticRecords(self.data, self.inventories, self.project)
        statuses = {field: records.evaluate(field) for field in STATIC_FIELDS
                    if field not in ('static_inputs', 'dotenv_metadata')}
        statuses['static_inputs'] = 'pass' if self.inputs_valid else 'invalid'
        statuses['dotenv_metadata'] = dotenv
        self.recheck()
        require(self.mount_identity() == self.backing, 'backing')
        self.recheck()  # Includes held base FD, every edge, records and inventories.
        return complete(lexical, statuses, structure)

    def close(self):
        failed = False
        # Reverse ownership order keeps both locks through every validation/read.
        # No explicit unlock, path cleanup or second close of somebody else's FD.
        for fd in reversed(self.fds):
            try:
                os.close(fd)
            except BaseException:
                failed = True
        self.fds.clear()
        if failed:
            raise Unavailable('cleanup')


def diagnose(principal, cancelled):
    context = ReadOnlyCapture(principal, cancelled)
    body = unavailable('io')
    try:
        body = context.report()
    except Unavailable as error:
        body = unavailable(error.args[0] if error.args and error.args[0] in REASONS else 'io')
    except (InterruptedError, KeyboardInterrupt):
        body = unavailable('interrupted')
    except BaseException:
        body = unavailable('io')
    finally:
        try:
            context.close()
        except BaseException:
            body = unavailable('cleanup')
    if cancelled():
        body = unavailable('interrupted')
    return body


# BEGIN EXACT CORRECTED CAPTURE PRIMITIVES
# Kept byte-identical to the approved runner; regression tests compare source.
class CaptureResult:
    """Private bounded bytes plus local termination provenance, never a log record."""
    __slots__ = ("code", "output", "failure")

    def __init__(self, code, output, failure=None):
        self.code, self.output, self.failure = code, output, failure


class CaptureInterrupted(BaseException):
    """Signal cancellation that selectors cannot swallow as an EINTR retry."""


class CaptureCleanupError(OSError):
    """The owned process group could not be cleaned up; never publish OS text."""


# main() runs captures serially on the signal-handling thread. During a capture
# the existing handler records cancellation without unwinding any ownership
# transition. No signal mask/handler changes are inherited by the child.
_capture_cancellation = None


def interrupted(_signum, _frame):
    if _capture_cancellation is not None:
        _capture_cancellation[0] = True
        return
    for watched in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
        signal.signal(watched, signal.SIG_IGN)
    raise CaptureInterrupted


def capture(argv, payload=b"", *, timeout=30, limit=32768, env=None, pass_fds=(), spawn_failed=None):
    """Keep the existing tuple API, including its legacy 124/125 conventions."""
    result = capture_result(argv, payload, timeout=timeout, limit=limit, env=env,
                            pass_fds=pass_fds, spawn_failed=spawn_failed)
    return result.code, result.output


def capture_result(argv, payload=b"", *, timeout=30, limit=32768, env=None, pass_fds=(), spawn_failed=None):
    """Own cancellation before spawn, through cleanup, until outcome selection."""
    global _capture_cancellation
    cancellation = [False]
    previous = _capture_cancellation
    result, failure = None, None
    _capture_cancellation = cancellation
    try:
        result = _capture_owned(argv, payload, timeout=timeout, limit=limit, env=env,
                                pass_fds=pass_fds, spawn_failed=spawn_failed, cancellation=cancellation)
    except BaseException as error:
        failure = error
    finally:
        # This handoff happens only after cleanup (or failed spawn). A signal
        # before it is recorded; one after it uses the original terminal path.
        # Check the recorded outcome AFTER handoff, never cache a success first.
        _capture_cancellation = previous
    if isinstance(failure, CaptureCleanupError):
        raise failure
    if cancellation[0]:
        if previous is not None:
            previous[0] = True
        return CaptureResult(124, result.output if result is not None else b"", "capture_interrupted")
    if failure is not None:
        raise failure
    return result


def _capture_owned(argv, payload, *, timeout, limit, env, pass_fds, spawn_failed, cancellation):
    """No named stdout/stderr captures; bounded memory and bounded process lifetime."""
    output = bytearray()
    offset = 0
    deadline = time.monotonic() + timeout
    try:
        child = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                 stderr=subprocess.DEVNULL, env=env, pass_fds=pass_fds,
                                 start_new_session=True)
    except OSError:
        if spawn_failed is not None:
            spawn_failed()
        raise
    # Keep the leader unreaped until killpg: its PID pins the owned group ID,
    # even after exit, so cleanup cannot target a recycled PID/process group.
    group = child.pid
    try:
        with selectors.DefaultSelector() as poll:
            os.set_blocking(child.stdin.fileno(), False)
            os.set_blocking(child.stdout.fileno(), False)
            poll.register(child.stdout, selectors.EVENT_READ)
            if payload:
                poll.register(child.stdin, selectors.EVENT_WRITE)
            else:
                child.stdin.close()
            while poll.get_map():
                if cancellation[0]:
                    return CaptureResult(124, bytes(output), "capture_interrupted")
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return CaptureResult(124, bytes(output), "capture_timeout")
                for key, _ in poll.select(min(remaining, 0.2)):
                    if key.fileobj is child.stdin:
                        try:
                            offset += os.write(child.stdin.fileno(), payload[offset:offset + 4096])
                        except BrokenPipeError:
                            offset = len(payload)
                        if offset == len(payload):
                            poll.unregister(child.stdin)
                            child.stdin.close()
                    else:
                        chunk = os.read(child.stdout.fileno(), 4096)
                        if not chunk:
                            poll.unregister(child.stdout)
                        else:
                            output.extend(chunk)
                            if len(output) > limit:
                                return CaptureResult(125, b"", "capture_output_limit")
            while True:
                if cancellation[0]:
                    return CaptureResult(124, bytes(output), "capture_interrupted")
                exited = os.waitid(os.P_PID, group, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                if exited is not None:
                    code = exited.si_status if exited.si_code == os.CLD_EXITED else -exited.si_status
                    return CaptureResult(code, bytes(output))
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return CaptureResult(124, bytes(output), "capture_timeout")
                time.sleep(min(remaining, .01))
    except subprocess.TimeoutExpired:
        return CaptureResult(124, bytes(output), "capture_timeout")
    except (InterruptedError, CaptureInterrupted):
        return CaptureResult(124, bytes(output), "capture_interrupted")
    finally:
        # No command retry. Terminate this invocation's group on every outcome,
        # including an exited leader with live descendants and a successful EOF.
        # Cancellation is already deferred, including entry into this finally.
        cleanup_failed = False
        permission_denied = False
        try:
            os.killpg(group, signal.SIGKILL)
        except ProcessLookupError:
            pass
        except PermissionError:
            permission_denied = True
        except OSError:
            cleanup_failed = True
        try:
            child.wait(timeout=2)
        except (OSError, subprocess.TimeoutExpired):
            cleanup_failed = True
        finally:
            for stream in (child.stdin, child.stdout):
                try:
                    if not stream.closed:
                        stream.close()
                except OSError:
                    cleanup_failed = True
        if permission_denied:
            # Darwin can report EPERM for an unreaped, zombie-only group.
            # Accept that case only if the group is now proven absent. This
            # is a non-mutating existence probe after reap, never another
            # kill against a group ID that could have been recycled.
            try:
                os.killpg(group, 0)
            except ProcessLookupError:
                pass
            except OSError:
                cleanup_failed = True
            else:
                cleanup_failed = True
        if cleanup_failed:
            raise CaptureCleanupError() from None


# END EXACT CORRECTED CAPTURE PRIMITIVES
