"""Bounded public native evidence. No kernel, network, filesystem or launcher."""
import json
import re

REPOSITORY = 'koteev-m/clubs_bot'
WORKFLOW = '.github/workflows/clb91-b-native-acceptance.yml'
PREREQUISITES = ('source', 'platform', 'execution', 'namespaces', 'capabilities',
                 'backing', 'lsm', 'procfs', 'fixture', 'bootstrap')
CONTROLS = ('positive_a', 'positive_b', 'missing_kill', 'missing_setgid',
    'missing_setuid', 'missing_setpcap', 'missing_sys_admin', 'wrong_namespace',
    'unsafe_propagation', 'wrong_backing', 'symlink', 'path_replacement', 'owner',
    'mode', 'principal', 'incomplete_observation', 'setup_failure', 'partial_init',
    'worker_failure', 'timeout', 'cancellation', 'cleanup_failure', 'lsm_denial', 'parent_death')
REASONS = ('none', 'authorization', 'source', 'platform', 'execution', 'namespaces',
           'capabilities', 'backing', 'lsm', 'procfs', 'fixture', 'bootstrap',
           'control', 'interrupted', 'cleanup', 'protocol')
STATUSES = ('PASS', 'FAIL', 'NOT_RUN')
LIMIT = 4096


class Invalid(Exception):
    pass


def need(value):
    if not value:
        raise Invalid() from None


def unique(pairs):
    result = {}
    for key, value in pairs:
        need(key not in result)
        result[key] = value
    return result


def decode(raw, bound=LIMIT):
    need(type(raw) is bytes and 0 < len(raw) <= bound)
    return json.loads(raw, object_pairs_hook=unique,
                      parse_constant=lambda _: (_ for _ in ()).throw(Invalid()))


def new(revision, source_contract):
    need(type(revision) is str and re.fullmatch('[0-9a-f]{40}', revision))
    need(type(source_contract) is str and re.fullmatch('[0-9a-f]{64}', source_contract))
    return dict(format=1, repository=REPOSITORY, workflow=WORKFLOW, revision=revision,
                source_contract=source_contract, run_id='NOT_RUN', attempt='NOT_RUN',
                execution='NOT_RUN', verdict='NOT_RUN', reason='authorization',
                prerequisites=dict.fromkeys(PREREQUISITES, 'NOT_RUN'),
                controls=dict.fromkeys(CONTROLS, 'NOT_RUN'), cleanup='NOT_RUN')


def validate(value, revision, source_contract):
    need(type(value) is dict and set(value) == set(new(revision, source_contract)))
    need(type(value['format']) is int and value['format'] == 1)
    need(value['repository'] == REPOSITORY and value['workflow'] == WORKFLOW)
    need(value['revision'] == revision and value['source_contract'] == source_contract)
    need(value['execution'] in ('NOT_RUN', 'NATIVE') and value['verdict'] in STATUSES)
    need(value['reason'] in REASONS and value['cleanup'] in ('PASS', 'FAIL', 'NOT_RUN', 'UNCONFIRMED'))
    for key, expected in (('prerequisites', PREREQUISITES), ('controls', CONTROLS)):
        need(type(value[key]) is dict and set(value[key]) == set(expected))
        need(all(type(v) is str and v in STATUSES for v in value[key].values()))
    if value['execution'] == 'NATIVE':
        need(type(value['run_id']) is str and re.fullmatch('[1-9][0-9]{0,19}', value['run_id']))
        need(value['attempt'] == '1')
        need(all(v == 'PASS' for v in value['prerequisites'].values()))
    else:
        need(value['run_id'] == value['attempt'] == 'NOT_RUN')
        need(all(v == 'NOT_RUN' for v in value['controls'].values()))
    if value['verdict'] == 'PASS':
        need(value['execution'] == 'NATIVE' and value['reason'] == 'none' and value['cleanup'] == 'PASS')
        need(all(v == 'PASS' for v in value['controls'].values()))
    elif value['verdict'] == 'NOT_RUN':
        need(value['execution'] == 'NOT_RUN' and value['cleanup'] == 'NOT_RUN')
        need(value['reason'] == 'authorization')
    else:
        need(value['reason'] != 'none')
        need('FAIL' in value['prerequisites'].values() or 'FAIL' in value['controls'].values()
             or value['cleanup'] in ('FAIL', 'UNCONFIRMED'))
    return value


def encode(value):
    validate(value, value['revision'], value['source_contract'])
    raw = (json.dumps(value, sort_keys=True, separators=(',', ':'))+'\n').encode('ascii')
    need(len(raw) <= LIMIT)
    return raw


def parse(raw, exit_code, revision, source_contract):
    value = validate(decode(raw),revision,source_contract)
    need(type(exit_code) is int and exit_code == {'PASS':0,'FAIL':1,'NOT_RUN':2}[value['verdict']])
    return value


class Ledger:
    """One ordered result per real control. Missing/duplicate/failure is terminal."""
    def __init__(self, report):
        self.report, self.position, self.failed = report, 0, False

    def record(self, name, passed):
        need(not self.failed and self.position < len(CONTROLS))
        need(name == CONTROLS[self.position] and type(passed) is bool)
        self.report['controls'][name] = 'PASS' if passed else 'FAIL'
        self.position += 1
        self.failed = not passed

    def complete(self):
        need(not self.failed and self.position == len(CONTROLS))
        need(self.report['cleanup'] == 'PASS')
        self.report.update(verdict='PASS', reason='none')
        validate(self.report, self.report['revision'], self.report['source_contract'])
