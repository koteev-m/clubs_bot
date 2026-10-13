#!/usr/bin/env python3
"""Synthetic callback/AST regressions; no Compose, Engine or stage acceptance."""
import ast
import contextlib
import importlib.util
import io
import json
import math
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PLANNER = ROOT / 'scripts/deploy/stage-compose-env-file-plan.py'
spec = importlib.util.spec_from_file_location('helper_private_plan', PLANNER)
planner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(planner)
CANARY = 'SYNTHETIC_PRIVATE_CANARY_$${literal}'
REFERENCE = '/proc/12345/fd/7'


def base(reference='[.env]', app_environment='      A: ${A}\n', extra=''):
    return ('services:\n  app:\n    image: fixture:local\n    env_file: ' + reference + '\n' +
            ('    environment:\n' + app_environment if app_environment else '') + extra +
            '  db:\n    image: postgres:fixture\n    environment:\n      OTHER: ${OTHER:-fallback}\n'
            '    command: ["echo", "$$UNCHANGED"]\n'
            '  caddy:\n    image: caddy:fixture\n    volumes:\n'
            '      - ./Caddyfile:/etc/caddy/Caddyfile:ro\n').encode()


def typed_model(values=None):
    return {'name': 'incident-fixture', 'services': {
        'app': {'image': 'fixture:local', 'environment': {'A': CANARY} if values is None else values},
        'db': {'image': 'postgres:fixture', 'environment': {'OTHER': 'actual'},
               'command': ['echo', '$UNCHANGED'], 'flags': [True, 1, 1.0, -0.0]},
        'caddy': {'image': 'caddy:fixture', 'volumes': [{'type': 'bind',
                  'source': '/fixture/project/Caddyfile', 'target': '/etc/caddy/Caddyfile', 'read_only': True}]}}}


def outline(script, data):
    # Test-only real Psych parser on synthetic bytes. Production Runtime trust,
    # executables and process supervision are independently checked by helper.
    result = subprocess.run(['/usr/bin/ruby', '--disable-gems', '-e', script], input=data,
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5,
        env={'PATH': '/usr/bin:/bin', 'HOME': '/private/tmp', 'LC_ALL': 'C'})
    if result.returncode:
        raise planner.Refused('parser')
    return result.stdout


class HelperPlanTest(unittest.TestCase):
    def setUp(self):
        self.env = {'PATH': '/actual/protected/bin:/usr/bin:/bin', 'HOME': '/actual/principal',
                    'LC_ALL': 'actual-locale', 'A': CANARY, 'OTHER': 'actual',
                    'EMPTY': '', 'DOCKER_HOST': 'unix:///var/run/docker.sock',
                    'DOCKER_CONFIG': '/proc/12345/fd/8', 'SSH_AUTH_SOCK': '/actual/agent'}
        self.calls = []

    def prepare(self, models=None, *, content=None, normalize=None, parser=outline,
                candidate_check=lambda _: True, environment=None):
        models = [typed_model(), typed_model()] if models is None else models
        selected = self.env if environment is None else environment
        frozen = dict(selected) if type(selected) is dict else None
        pending = iter(models)

        def fixture(data, with_override, context):
            self.calls.append((data, with_override))
            self.assertIs(context, selected)
            self.assertEqual(context, frozen)
            if normalize is not None:
                return normalize(data, with_override, context)
            if not with_override:
                return json.loads(data)
            return next(pending)

        return planner.helper_prepare(base() if content is None else content,
            dotenv_reference=REFERENCE, effective_environment=selected,
            outline_parser=parser, normalize=fixture, candidate_check=candidate_check)

    def test_only_single_literal_canonical_envfile_shape_is_accepted(self):
        references = ['[.env]', "['.env']", '[".env"]', '\n      - .env']
        for reference in references:
            with self.subTest(reference=reference):
                result = self.prepare(content=base(reference))
                self.assertEqual('remove', result.strategy)
                self.assertNotIn(b'env_file:', result.candidate)
                self.assertIn(REFERENCE.encode(), self.calls[-4][0])

    def test_standalone_ast_keeps_its_existing_repeated_canonical_shape(self):
        for reference in ('[./.env]', '[.env, ./.env]'):
            with self.subTest(reference=reference):
                value = json.loads(outline(planner.OUTLINE, base(reference)))
                self.assertEqual(1 if reference == '[./.env]' else 2, len(value['dotenv_spans']))

    def test_noncanonical_other_forms_and_other_ast_paths_refuse_before_normalization(self):
        rejected = [base('[./.env]'), base('[.env, .env]'), base('.env'), base('[other.env]'),
                    base('[${FILE}]'), base('["\\u002eenv"]'), base('[{path: .env}]'),
                    base(extra='    env_file: [.env]\n'),
                    base().replace(b'  db:\n', b'  db:\n    env_file: [.env]\n'),
                    base().replace(b'    image: fixture:local\n', b'    image: &image fixture:local\n'),
                    base().replace(b'postgres:fixture', b'*image'),
                    base().replace(b'    env_file: [.env]', b'    env_file: !custom [.env]'),
                    base(extra='    build: .\n'), base(extra='    secrets: [private]\n'),
                    base(extra='    healthcheck:\n      env_file: [.env]\n'),
                    base() + b'include: child.yml\n',
                    base() + b'volumes:\n  other:\n    external: true\n']
        for index, content in enumerate(rejected):
            with self.subTest(index=index):
                self.calls.clear()
                with self.assertRaises(planner.Refused):
                    self.prepare(content=content)
                self.assertEqual([], self.calls)

    def test_same_complete_effective_environment_is_forwarded_unchanged(self):
        original = dict(self.env)
        result = self.prepare(content=base(app_environment='      A: ${A}\n      PATH: ${PATH}\n'))
        self.assertEqual(original, self.env)
        self.assertEqual([True, True, False, False], [item[1] for item in self.calls])
        self.assertEqual(typed_model(), result.model)
        self.assertIn(b'PATH: ${PATH}', result.candidate)

    def test_explicit_restores_only_missing_names_without_freezing_values(self):
        self.env['B'] = 'effective-priority'
        before = typed_model({'A': CANARY, 'B': 'effective-priority'})
        after = typed_model({'A': CANARY})
        result = self.prepare([before, after, before])
        self.assertEqual('explicit', result.strategy)
        self.assertIn(b'B: ${B?CLB91 required contribution}', result.candidate)
        self.assertNotIn(b'effective-priority', result.candidate)
        self.assertEqual(before, result.model)
        self.assertEqual([True, True, True, False, False], [item[1] for item in self.calls])

    def test_actual_control_name_value_can_be_restored_without_synthetic_substitution(self):
        before = typed_model({'A': CANARY, 'PATH': self.env['PATH']})
        result = self.prepare([before, typed_model(), before])
        self.assertIn(b'PATH: ${PATH?CLB91 required contribution}', result.candidate)
        self.assertEqual(self.env['PATH'], result.model['services']['app']['environment']['PATH'])

    def test_null_missing_is_not_replaced_and_empty_string_is_not_null(self):
        with self.assertRaises(planner.Refused) as refused:
            self.prepare([typed_model({'A': None}), typed_model({})],
                         content=base(app_environment=''))
        self.assertEqual('different', str(refused.exception))
        for left, right in [(None, ''), ('', None)]:
            with self.subTest(left=left):
                with self.assertRaises(planner.Refused):
                    self.prepare([typed_model({'A': left}), typed_model({'A': right})])
        result = self.prepare([typed_model({'A': ''}), typed_model({'A': ''})])
        self.assertEqual('', result.model['services']['app']['environment']['A'])

    def test_empty_missing_contribution_preserves_empty_value(self):
        before = typed_model({'A': CANARY, 'B': ''})
        result = self.prepare([before, typed_model(), before])
        self.assertEqual('explicit', result.strategy)
        self.assertEqual('', result.model['services']['app']['environment']['B'])

    def test_explicit_does_not_hide_existing_priority_or_other_field_difference(self):
        before = typed_model({'A': 'higher-priority', 'B': 'captured'})
        removed = typed_model({'A': 'lower-priority'})
        explicit = typed_model({'A': 'lower-priority', 'B': 'captured'})
        with self.assertRaises(planner.Refused) as refused:
            self.prepare([before, removed, explicit])
        self.assertEqual('different', str(refused.exception))
        self.assertEqual(3, len(self.calls))

    def test_full_other_service_types_arrays_paths_and_presence_are_compared(self):
        mutations = [lambda m: m['services']['db'].__setitem__('flags', [1, 1, 1.0, -0.0]),
                     lambda m: m['services']['db'].__setitem__('flags', [True, 1, 1.0, 0.0]),
                     lambda m: m['services']['db'].__setitem__('optional', None),
                     lambda m: m['services']['db'].__setitem__('command', ['$UNCHANGED', 'echo']),
                     lambda m: m['services']['caddy']['volumes'][0].__setitem__('source', '/wrong/Caddyfile')]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                after = typed_model(); mutate(after)
                with self.assertRaises(planner.Refused) as refused:
                    self.prepare([typed_model(), after])
                self.assertEqual('different', str(refused.exception))

    def test_resolved_before_and_after_are_both_normalized_and_literal_dollars_survive(self):
        value = typed_model({'A': CANARY, 'DOLLAR': '$${value} $$$$'})
        result = self.prepare([value, value])
        self.assertEqual('$${value} $$$$', result.model['services']['app']['environment']['DOLLAR'])
        self.assertEqual([True, True, False, False], [item[1] for item in self.calls])
        self.assertTrue(all(b'env_file' not in payload for payload, override in self.calls if not override))
        for failed_round_trip in (1, 2):
            counter = [0]
            def changed(data, with_override, _):
                if with_override:
                    return value
                counter[0] += 1
                model = json.loads(data)
                if counter[0] == failed_round_trip:
                    model['services']['app']['environment']['DOLLAR'] = '${value} $$'
                return model
            with self.subTest(failed_round_trip=failed_round_trip):
                with self.assertRaises(planner.Refused) as refused:
                    self.prepare(normalize=changed)
                self.assertEqual('model', str(refused.exception))

    def test_callback_model_alias_mutation_cannot_rewrite_before_proof(self):
        reused = typed_model()
        count = [0]
        def aliased(_, with_override, __):
            count[0] += 1
            self.assertTrue(with_override)
            if count[0] == 2:
                reused['services']['db']['image'] = 'substituted'
            return reused
        with self.assertRaises(planner.Refused) as refused:
            self.prepare(normalize=aliased)
        self.assertEqual('different', str(refused.exception))

    def test_missing_extra_and_changed_context_between_calls_refuse(self):
        mutations = [lambda env: env.pop('OTHER'), lambda env: env.update(EXTRA=CANARY),
                     lambda env: env.update(PATH='/synthetic/path')]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                self.setUp()
                def drift(_, __, context):
                    mutate(context)
                    return typed_model()
                with self.assertRaises(planner.Refused) as refused:
                    self.prepare(normalize=drift)
                self.assertEqual('input', str(refused.exception))

    def test_malformed_environment_or_models_never_prove_equivalence(self):
        for context in ([], {'A': None}, {'A': 'bad\x00'}, {'BAD=NAME': CANARY}):
            with self.subTest(context_type=type(context).__name__):
                with self.assertRaises(planner.Refused):
                    self.prepare(environment=context)
        cycle = []; cycle.append(cycle)
        invalid = [[], typed_model({'A': True}), {'services': {'app': {'environment': []}}},
                   dict(typed_model(), extension=float('nan')), dict(typed_model(), extension=(1,)),
                   dict(typed_model(), extension=cycle)]
        for index, model in enumerate(invalid):
            with self.subTest(index=index):
                with self.assertRaises(planner.Refused):
                    self.prepare([model, model])

    def test_subset_validator_runs_before_compose_and_failures_preserve_refusal(self):
        with self.assertRaises(planner.Refused) as refused:
            self.prepare(candidate_check=lambda _: False)
        self.assertEqual('unsupported', str(refused.exception))
        self.assertEqual([], self.calls)
        def cleanup_failed(_):
            raise planner.Refused('cleanup')
        with self.assertRaises(planner.Refused) as refused:
            self.prepare(candidate_check=cleanup_failed)
        self.assertEqual('cleanup', str(refused.exception))
        self.assertEqual([], self.calls)

    def test_raw_error_timeout_signal_and_cleanup_never_become_pass_or_leak(self):
        failures = [(RuntimeError(CANARY), 'input'), (TimeoutError(CANARY), 'input'),
                    (InterruptedError(CANARY), 'interrupted'), (KeyboardInterrupt(CANARY), 'interrupted'),
                    (planner.Refused('cleanup'), 'cleanup')]
        for failure, reason in failures:
            output, error = io.StringIO(), io.StringIO()
            def fail(*_):
                raise failure
            with self.subTest(reason=reason), contextlib.redirect_stdout(output), contextlib.redirect_stderr(error):
                with self.assertRaises(planner.Refused) as refused:
                    self.prepare(normalize=fail)
                self.assertEqual(reason, str(refused.exception))
            self.assertEqual('', output.getvalue())
            self.assertEqual('', error.getvalue())
            self.assertNotIn(CANARY, str(refused.exception))

    def test_all_four_or_five_normalization_failures_remain_failure(self):
        for strategy in ('remove', 'explicit'):
            before = typed_model({'A': CANARY, 'B': 'required'}) if strategy == 'explicit' else typed_model()
            config = [before, typed_model(), before] if strategy == 'explicit' else [before, before]
            for fail_at in range(1, 6 if strategy == 'explicit' else 5):
                counter, pending = [0], iter(config)
                def failure(data, with_override, _):
                    counter[0] += 1
                    if counter[0] == fail_at:
                        raise planner.Refused('io')
                    return next(pending) if with_override else json.loads(data)
                with self.subTest(strategy=strategy, fail_at=fail_at):
                    with self.assertRaises(planner.Refused) as refused:
                        self.prepare(normalize=failure)
                    self.assertEqual('io', str(refused.exception))

    def test_projection_uses_only_dotenv_scalar_span_and_public_result_has_no_private_bytes(self):
        content = base(app_environment='      A: "literal .env ' + CANARY + '"\n')
        plan = self.prepare(content=content)
        projection = self.calls[-4][0]
        self.assertIn(b'"' + REFERENCE.encode() + b'"', projection)
        self.assertIn(b'literal .env', projection)
        self.assertNotIn(REFERENCE.encode(), plan.candidate)
        for public in (str(plan), repr(plan), plan.public()):
            self.assertNotIn(CANARY, public)
            self.assertNotIn(REFERENCE, public)
            self.assertNotIn('/actual/', public)

    def test_invalid_fd_reference_parser_and_size_refuse(self):
        for reference in ('/original/.env', '/dev/fd/7', '/proc/12345/fd/7/extra'):
            with self.subTest(reference=reference), self.assertRaises(planner.Refused):
                planner.helper_prepare(base(), dotenv_reference=reference, effective_environment=self.env,
                    outline_parser=outline, normalize=lambda *_: typed_model(), candidate_check=lambda _: True)
        for raw in (b'', b'{}', b'{"remove":0,"remove":1}', b'not-json'):
            with self.subTest(raw_length=len(raw)), self.assertRaises(planner.Refused):
                self.prepare(parser=lambda *_: raw)
        with self.assertRaises(planner.Refused):
            self.prepare(content=b'x' * (planner.FILE_LIMIT + 1))
        with self.assertRaises(planner.Refused):
            self.prepare([typed_model({'A': 'x' * planner.MODEL_LIMIT})])

    def test_bounded_private_decoder_rejects_duplicates_nonfinite_and_raw_errors(self):
        value = typed_model()
        self.assertEqual(value, planner.decode_model(json.dumps(value).encode()))
        raw = json.dumps(value).encode()
        failures = [b'', b'not-json-' + CANARY.encode(), b'{"services":{},"services":{}}',
                    b'[]', b'x' * (planner.MODEL_LIMIT + 1)]
        failures += [raw[:-1] + b',"extra":' + token + b'}'
                     for token in (b'NaN', b'Infinity', b'-Infinity', b'1e999')]
        for index, content in enumerate(failures):
            with self.subTest(index=index), self.assertRaises(planner.Refused) as refused:
                planner.decode_model(content)
            self.assertNotIn(CANARY, str(refused.exception))

    def test_source_owned_embeddable_nodes_have_only_explicit_stdlib_dependencies(self):
        raw = PLANNER.read_text()
        tree = ast.parse(raw)
        wanted = set(planner.HELPER_SOURCE_NODES)
        nodes = []
        for node in tree.body:
            name = getattr(node, 'name', None)
            if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
                name = node.targets[0].id
            if name in wanted:
                wanted.remove(name); nodes.append(ast.get_source_segment(raw, node))
        self.assertEqual(set(), wanted)
        embedded = '\n\n'.join(nodes).encode()
        self.assertLess(len(embedded), 22000)
        namespace = {'json': json, 'math': math, 're': re}
        exec(compile(embedded, '<approved-embedded-planner>', 'exec'), namespace)
        value = typed_model()
        def normalize(data, with_override, context):
            self.assertIs(context, self.env)
            return value if with_override else json.loads(data)
        result = namespace['helper_prepare'](base(), dotenv_reference=REFERENCE,
            effective_environment=self.env, outline_parser=outline, normalize=normalize,
            candidate_check=lambda _: True)
        self.assertEqual('remove', result.strategy)
        self.assertEqual(value, result.model)
        self.assertNotIn('DIAGNOSTIC', namespace)
        self.assertNotIn('CAPTURE', namespace)
        self.assertNotIn('SAFE_ROOT', namespace)


if __name__ == '__main__':
    unittest.main()
