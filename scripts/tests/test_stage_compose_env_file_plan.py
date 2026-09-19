#!/usr/bin/env python3
"""Real offline Compose semantics; no Engine, host config or live snapshots."""
import contextlib
import importlib.util
import io
import json
import os
import runpy
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('private_plan', ROOT/'scripts/deploy/stage-compose-env-file-plan.py')
planner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(planner)
# Test-only pathname loading, with synthetic inputs and no credentials.
planner.DIAGNOSTIC = runpy.run_path(str(ROOT/'scripts/deploy/stage-compose-diagnostic-operation.py'))
planner.CAPTURE = planner.DIAGNOSTIC['capture_result']
planner.SAFE_ROOT = runpy.run_path(str(ROOT/'scripts/deploy/release_private_root.py'))['open_canonical_root']
COMPOSE = next((p for p in (
    '/Applications/Docker.app/Contents/Resources/cli-plugins/docker-compose',
    '/usr/libexec/docker/cli-plugins/docker-compose', '/usr/lib/docker/cli-plugins/docker-compose',
    '/usr/local/lib/docker/cli-plugins/docker-compose') if Path(p).is_file()), shutil.which('docker-compose'))
OVERRIDE = ('# clubs-bot-managed-quiesced-release\n# revision: '+planner.DIAGNOSTIC['REVISION']+
            '\nservices:\n  app:\n    image: '+planner.DIAGNOSTIC['IMAGE']+'\n').encode()
CANARY = 'SYNTHETIC_PRIVATE_CANARY_dollars$${literal}'


def independent_json(value):
    """Separate serialization oracle; never call the production comparator.

    JSON serialization distinguishes bool/int/float, signed zero and array order;
    validate exact Python JSON types first (json.dumps alone accepts tuples/keys).
    """
    pending = [value]
    visited = 0
    while pending:
        item = pending.pop()
        visited += 1
        if visited > 10000 or type(item) not in (type(None), bool, int, float, str, list, dict):
            raise ValueError('non_json')
        if type(item) is dict:
            if any(type(key) is not str for key in item):
                raise ValueError('non_json')
            pending.extend(item.values())
        elif type(item) is list:
            pending.extend(item)
    return json.dumps(value, sort_keys=True, allow_nan=False, ensure_ascii=True, separators=(',', ':'))


def independently_equal(left, right):
    return independent_json(left) == independent_json(right)


class TypedJsonTest(unittest.TestCase):
    def test_nested_type_changes_and_presence_order_policy(self):
        pairs = [(True, 1), (False, 0), (1, 1.0), (0.0, -0.0), (1, '1'),
                 (None, ''), ({}, []), ({'A': None}, {}), ({'A': ''}, {'A': None}),
                 ([1, 2], [2, 1])]
        for index, (left, right) in enumerate(pairs):
            a = {'services': {'db': {'nested': [left]}}}
            b = {'services': {'db': {'nested': [right]}}}
            with self.subTest(index=index):
                self.assertFalse(independently_equal(a, b))
                self.assertFalse(planner.same_json(a, b))
                self.assertFalse(planner.same_json(b, a))

    def test_equal_json_and_object_key_order(self):
        left = {'empty': '', 'null': None, 'values': [True, False, 1, 1.0, -0.0, {}, []]}
        right = dict(reversed(list(left.items())))
        self.assertTrue(independently_equal(left, right))
        self.assertTrue(planner.same_json(left, right))

    def test_non_json_and_nonfinite_never_equivalent(self):
        class IntegerSubclass(int):
            pass
        cycle = []; cycle.append(cycle)
        values = [float('nan'), float('inf'), -float('inf'), (1,), {1}, b'private',
                  object(), IntegerSubclass(1), {1: 'private'}, {True: 'private'}, cycle]
        for index, value in enumerate(values):
            with self.subTest(index=index):
                with self.assertRaises(planner.Refused) as refused:
                    planner.same_json({'nested': [value]}, {'nested': [value]})
                self.assertEqual(str(refused.exception), 'model')
                with self.assertRaises(ValueError):
                    independently_equal({'nested': [value]}, {'nested': [value]})


def base(environment='      A: ${A}\n', references='    env_file: [.env]\n', extras=''):
    return ('services:\n  app:\n    image: fixture:local\n' + references +
            ('    environment:\n'+environment if environment else '') + extras +
            '  db:\n    image: postgres:fixture\n    environment:\n      OTHER: ${OTHER:-fallback}\n'
            '    command: ["echo", "$$UNCHANGED"]\n'
            '  caddy:\n    image: caddy:fixture\n    volumes:\n      - ./Caddyfile:/etc/caddy/Caddyfile:ro\n').encode()


class SemanticTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(COMPOSE, 'real offline Compose unavailable; no semantic PASS')
        parent = str(Path(os.environ.get('RUNNER_TEMP') or tempfile.gettempdir()).resolve())
        self.temp = tempfile.TemporaryDirectory(prefix='clb91-semantic-test-', dir=parent)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.assertFalse(self.root.is_relative_to(ROOT))
        self.calls = []
        self.real_capture = planner.CAPTURE

    def prepare(self, content, dotenv, interpolation=None):
        def audit(argv, payload=b'', **kwargs):
            self.calls.append(list(argv))
            # No inherited tokens, user Docker config, SSH agent or Engine call.
            env = kwargs['env']
            self.assertNotIn('SSH_AUTH_SOCK', env)
            self.assertNotIn('GH_TOKEN', env)
            self.assertNotIn('GITHUB_TOKEN', env)
            self.assertTrue(Path(env['HOME']).is_relative_to(self.root))
            self.assertTrue(Path(env['DOCKER_CONFIG']).is_relative_to(self.root))
            self.assertFalse((Path(env['DOCKER_CONFIG'])/'config.json').exists())
            self.assertTrue(env['DOCKER_HOST'].endswith('/no-daemon.sock'))
            self.assertEqual(argv[:4], ['/bin/sh','-c','cd "$1" && shift && exec "$@"','clb91-private-cwd'])
            self.assertEqual(argv[4],env['HOME'])
            command=argv[5:]
            if command[0] == COMPOSE:
                self.assertTrue(command[1:] == ['version','--short'] or command[-3:] == ['config','--format','json'])
            else:
                self.assertEqual(command[:3], ['/usr/bin/ruby','--disable-gems','-e'])
            return self.real_capture(argv, payload, **kwargs)
        out, err = io.StringIO(), io.StringIO()
        try:
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err), patch.object(planner,'CAPTURE',audit):
                result = planner.prepare(content, dotenv, OVERRIDE, interpolation=interpolation or {},
                    project='clb91-private-test', compose=COMPOSE, temporary_root=str(self.root))
        finally:
            self.assertEqual(out.getvalue(), '')
            self.assertEqual(err.getvalue(), '')
            self.assertEqual(list(self.root.iterdir()), [])
        self.assertNotIn(CANARY, repr(result)+result.public())
        self.assertIn('future=requires_recheck application=not_authorized', result.public())
        self.assertNotIn(b'env_file:', result.candidate)
        return result

    def models(self, original, candidate, dotenv, interpolation=None):
        """Independent direct real CLI, not the production capture/compare code."""
        with tempfile.TemporaryDirectory(dir=self.root) as directory:
            root=Path(directory); (root/'config').mkdir(mode=0o700)
            (root/'.env').write_bytes(dotenv); (root/'override.yml').write_bytes(OVERRIDE)
            env=dict(interpolation or {},PATH='/usr/bin:/bin',HOME=directory,DOCKER_CONFIG=str(root/'config'),
                     DOCKER_HOST='unix://'+directory+'/no-daemon.sock')
            models=[]
            for name, data in (('before.yml',original),('after.yml',candidate)):
                (root/name).write_bytes(data)
                result=subprocess.run([COMPOSE,'--project-name','clb91-private-test','--project-directory',directory,
                    '--env-file',str(root/'.env'),'-f',str(root/name),'-f',str(root/'override.yml'),
                    'config','--format','json'],env=env,cwd=directory,capture_output=True,timeout=10)
                self.assertEqual(result.returncode,0,'independent real Compose normalization failed (output private)')
                models.append(json.loads(result.stdout))
            return models

    def prove(self, content, dotenv, strategy, expected, interpolation=None):
        result=self.prepare(content,dotenv,interpolation)
        self.assertEqual(result.strategy,strategy)
        before,after=self.models(content,result.candidate,dotenv,interpolation)
        self.assertTrue(independently_equal(before, after),'whole model changed (private values withheld)')
        self.assertTrue(independently_equal(before['services']['app']['environment'], expected),
                        'effective environment differs (private)')
        self.assertEqual(result.candidate.count(b'SYNTHETIC_PRIVATE_CANARY'), content.count(b'SYNTHETIC_PRIVATE_CANARY'))
        return result

    def test_entirely_redundant_exact_deletion_and_other_services(self):
        content=base(); dot=b'A=private\n'
        result=self.prove(content,dot,'remove',{'A':'private'})
        self.assertEqual(result.candidate, content.replace(b'    env_file: [.env]\n',b''))

    def test_missing_environment_entry_becomes_reference_not_frozen_value(self):
        result=self.prove(base('      EXISTING: unchanged\n'),b'A=private\n','explicit',{'A':'private','EXISTING':'unchanged'})
        self.assertIn(b'A: ${A?CLB91 required contribution}\n',result.candidate)
        self.assertNotIn(b'A: private',result.candidate)

    def test_no_environment_mapping_creates_only_needed_block(self):
        self.prove(base(''),b'A=private\n','explicit',{'A':'private'})

    def test_environment_override_has_priority_even_over_empty_dotenv(self):
        for value in ('private',''):
            self.prove(base('      A: explicit\n'),('A='+value+'\n').encode(),'remove',{'A':'explicit'})

    def test_absent_empty_and_null_are_distinct(self):
        result=self.prove(base('      EMPTY: ""\n      UNSET:\n'),b'', 'remove',{'EMPTY':'','UNSET':None})
        self.assertIn(b'UNSET:\n',result.candidate)
        self.prove(base('      KEEP: constant\n'),b'EMPTY=\n','explicit',{'KEEP':'constant','EMPTY':''})
        self.prove(base('      A:\n'),b'A=private\n','remove',{'A':'private'})
        with self.assertRaises(planner.Refused) as failed:
            self.prepare(base(),b'A\n')
        self.assertEqual(failed.exception.reason,'unsupported') # Same helper dotenv guard.

    def test_dollars_quotes_comments_and_supported_interpolation(self):
        dots=(b"A='private$literal${still-literal}'\n",b'A="private#inside" # comment\n',
              b'A=private # comment\n',b'B=private\nA=${B}$$\n',b'A=${MISSING:-fallback}\n')
        for dot in dots:
            with self.subTest(index=dots.index(dot)):
                content=base('      B: explicit\n')
                result=self.prepare(content,dot)
                a,b=self.models(content,result.candidate,dot)
                self.assertTrue(independently_equal(a, b),'dollar/interpolation semantics changed (private)')
        dot=("A='"+CANARY+"'\n").encode()
        result=self.prepare(base('      B: explicit\n'),dot)
        self.assertNotIn(CANARY.encode(),result.candidate)

    def test_repeated_canonical_references_and_last_dotenv_assignment(self):
        for refs in ('    env_file: ["./.env", .env, ".env"]\n',
                     '    env_file:\n      - .env\n      - "./.env" # canonical duplicate\n'):
            content=base('      EXISTING: unchanged\n',refs)
            self.prove(content,b'A=first\nA=last\n','explicit',{'A':'last','EXISTING':'unchanged'})

    def test_ambient_priority_mismatch_refuses_instead_of_losing_dotenv_value(self):
        with self.assertRaises(planner.Refused) as failed:
            self.prepare(base(''),b'A=dotenv\n',{'A':'ambient'})
        self.assertEqual(failed.exception.reason,'different')
        self.prove(base('      A: ${A}\n'),b'A=dotenv\n','remove',{'A':'ambient'},{'A':'ambient'})

    def test_existing_unset_override_is_not_replaced_or_dropped(self):
        self.prove(base('      A:\n      NO_VALUE:\n'),b'A=dotenv\n','remove',{'A':'dotenv','NO_VALUE':None})

    def test_future_dotenv_changes_require_new_proof(self):
        content=base('      B: ${B:-fallback}\n')
        initial=self.prepare(content,b'A=one\n')
        rotated=self.prepare(content,b'A=two\n')
        self.assertEqual(initial.candidate,rotated.candidate)
        a,b=self.models(content,initial.candidate,b'A=two\n'); self.assertTrue(independently_equal(a, b))
        a,b=self.models(content,initial.candidate,b'A=two\nNEW=added\n'); self.assertFalse(independently_equal(a, b))
        new=self.prepare(content,b'A=two\nNEW=added\n'); self.assertNotEqual(new.candidate,initial.candidate)
        with self.assertRaises(planner.Refused):
            self.prepare(content,b'A=two\n',{'A':'ambient-changed'})
        # A missing formerly contributed name must not silently become empty.
        with self.assertRaises(planner.Refused):
            with tempfile.TemporaryDirectory(dir=self.root) as directory:
                p=Path(directory); (p/'.env').write_bytes(b''); (p/'base.yml').write_bytes(initial.candidate)
                env=dict(PATH='/usr/bin:/bin',HOME=directory,DOCKER_CONFIG=directory,
                         DOCKER_HOST='unix://'+directory+'/no-daemon.sock')
                planner.capture([COMPOSE,'--project-name','fixture','--project-directory',directory,
                    '--env-file',str(p/'.env'),'-f',str(p/'base.yml'),'config','--format','json'],env)
        self.assertEqual(self.prepare(content,b'').strategy,'remove')

    def test_malformed_external_and_ambiguous_inputs_stop_before_compose(self):
        bad=(base().replace(b'[.env]',b'[.env'), base().replace(b'[.env]',b'[other.env]'),
             base().replace(b'[.env]',b'.env'),base().replace(b'    image:',b'    include:'),
             base().replace(b'  app:',b'  "app":'),base()+b'\xff',
             base(extras='    labels: ["\n    env_file: .env\n    image: PRIVATE"]\n'),
             base().replace(b'      A: ${A}',b'      A: first\n      A: second'))
        for content in bad:
            with self.subTest(index=bad.index(content)), self.assertRaises(planner.Refused):
                self.prepare(content,b'A=private\n')
        self.assertEqual(self.calls,[])
        with self.assertRaises(planner.Refused):
            self.prepare(base(),b'COMPOSE_FILE=private-other.yml\n')
        with self.assertRaises(planner.Refused):
            self.prepare(base(),b'A=private\n',{'DOCKER_HOST':'private'})

    def test_unsupported_service_field_and_environment_list_refuse(self):
        for content in (base(extras='    credential_spec:\n      file: private.json\n'),
                        base('      - A\n')):
            with self.assertRaises(planner.Refused):
                self.prepare(content,b'A=private\n')

    def test_fault_matrix_no_result_or_canary_on_acquisition_cleanup_cancel(self):
        kwargs=dict(interpolation={},project='clb91-private-test',compose=COMPOSE,temporary_root=str(self.root))
        for error in (OSError('PRIVATE'), RuntimeError('PRIVATE'), KeyboardInterrupt('PRIVATE')):
            with self.subTest(error=type(error).__name__), patch.object(planner,'CAPTURE',side_effect=error):
                with self.assertRaises(planner.Refused) as failed:
                    planner.prepare(base(),b'A=private\n',OVERRIDE,**kwargs)
                self.assertNotIn('PRIVATE',str(failed.exception))
                self.assertEqual(list(self.root.iterdir()),[])
        original_exit=planner.tempfile.TemporaryDirectory.__exit__
        def fail_finalization(resource,*args):
            original_exit(resource,*args)
            raise RuntimeError('PRIVATE')
        with patch.object(planner.tempfile.TemporaryDirectory,'__exit__',fail_finalization):
            with self.assertRaises(planner.Refused) as failed:
                planner.prepare(base(),b'A=private\n',OVERRIDE,**kwargs)
            self.assertEqual(failed.exception.reason,'cleanup')
        self.assertEqual(list(self.root.iterdir()),[])

    def test_whole_model_drift_and_resolved_reuse_drift_refuse(self):
        real=planner.CAPTURE
        for phase in ('removed.yml','after-resolved.json'):
            def corrupt(argv,*args,**kwargs):
                result=real(argv,*args,**kwargs)
                if argv[-3:]==['config','--format','json'] and any(x.endswith('/'+phase) for x in argv):
                    model=json.loads(result.output)
                    model['services']['db']['restart']='always'
                    return types.SimpleNamespace(code=0,output=json.dumps(model).encode(),failure=None)
                return result
            with self.subTest(phase=phase),patch.object(planner,'CAPTURE',corrupt):
                with self.assertRaises(planner.Refused):
                    planner.prepare(base(),b'A=private\n',OVERRIDE,interpolation={},project='fixture',
                                    compose=COMPOSE,temporary_root=str(self.root))
            self.assertEqual(list(self.root.iterdir()),[])

    def test_prepare_refuses_boolean_number_changes_at_each_model_decision(self):
        # Comparator-contract fault injection, NOT evidence that real Compose
        # turns a boolean into an integer. Use one actual normalized template,
        # then substitute all config responses to isolate each decision point.
        template = self.models(base(), base(), b'A=synthetic\n')[0]
        for phase in ('removed.yml', 'explicit.yml', 'before-resolved.json', 'after-resolved.json'):
            calls = []
            def typed_drift(argv, *args, **kwargs):
                if argv[-3:] == ['config', '--format', 'json']:
                    model = json.loads(json.dumps(template))
                    if phase == 'explicit.yml' and any(value.endswith('/removed.yml') for value in argv):
                        model['services']['app']['environment'].pop('A')
                    matched = any(value.endswith('/'+phase) for value in argv)
                    model['services']['db']['init'] = 1 if matched else True
                    if matched:
                        calls.append(phase)
                    return types.SimpleNamespace(code=0, output=json.dumps(model).encode(), failure=None)
                return self.real_capture(argv, *args, **kwargs)
            content = base('      KEEP: value\n') if phase == 'explicit.yml' else base()
            with self.subTest(phase=phase), patch.object(planner, 'CAPTURE', typed_drift):
                with self.assertRaises(planner.Refused):
                    planner.prepare(content, b'A=synthetic\n', OVERRIDE, interpolation={},
                                    project='fixture', compose=COMPOSE, temporary_root=str(self.root))
            self.assertEqual(calls, [phase])
            self.assertEqual(list(self.root.iterdir()), [])

    def test_prepare_refuses_nonfinite_normalizer_numbers(self):
        template = self.models(base(), base(), b'A=synthetic\n')[0]
        for token in ('NaN', 'Infinity', '-Infinity', '1e999'):
            def nonfinite(argv, *args, **kwargs):
                if argv[-3:] == ['config', '--format', 'json']:
                    raw = json.dumps(template).encode()
                    raw = raw[:-1] + b', "probe": ' + token.encode() + b'}'
                    return types.SimpleNamespace(code=0, output=raw, failure=None)
                return self.real_capture(argv, *args, **kwargs)
            with self.subTest(token=token), patch.object(planner, 'CAPTURE', nonfinite):
                with self.assertRaises(planner.Refused) as refused:
                    planner.prepare(base(), b'A=synthetic\n', OVERRIDE, interpolation={},
                                    project='fixture', compose=COMPOSE, temporary_root=str(self.root))
                self.assertEqual(refused.exception.reason, 'model')
            self.assertEqual(list(self.root.iterdir()), [])

    def test_local_comparisons_use_one_complete_synthetic_context(self):
        contexts = []
        def observe(argv, *args, **kwargs):
            if argv[-3:] == ['config', '--format', 'json']:
                contexts.append((argv[argv.index('--project-name')+1],
                                 argv[argv.index('--project-directory')+1],
                                 argv[argv.index('--env-file')+1], dict(kwargs['env'])))
            return self.real_capture(argv, *args, **kwargs)
        with patch.object(planner, 'CAPTURE', observe):
            planner.prepare(base('      KEEP: ${KEEP}\n'), b'A=synthetic\n', OVERRIDE,
                            interpolation={'KEEP':'synthetic-context'}, project='fixture',
                            compose=COMPOSE, temporary_root=str(self.root))
        self.assertEqual(len(contexts), 5)
        self.assertTrue(all(independently_equal(list(contexts[0]), list(value)) for value in contexts))
        self.assertEqual(contexts[0][0], 'fixture')
        self.assertTrue(Path(contexts[0][1]).is_relative_to(self.root))
        self.assertEqual(contexts[0][3]['KEEP'], 'synthetic-context')
        self.assertEqual(list(self.root.iterdir()), [])

    def test_real_compose_project_directory_changes_relative_bind_model(self):
        # Concrete runtime-adaptation blocker: equality inside one temporary
        # project directory does NOT prove the model in the canonical directory.
        with tempfile.TemporaryDirectory(dir=self.root) as private:
            root = Path(private)
            canonical = root/'canonical'; canonical.mkdir(mode=0o700)
            captured = root/'captured'; captured.mkdir(mode=0o700)
            (root/'config').mkdir(mode=0o700)
            (root/'.env').write_bytes(b'A=synthetic\n')
            content = base().replace(b'    env_file: [.env]\n', b'')
            (root/'compose.yml').write_bytes(content)
            env = dict(PATH='/usr/bin:/bin', HOME=private, DOCKER_CONFIG=str(root/'config'),
                       DOCKER_HOST='unix://'+private+'/no-daemon.sock')
            models = []
            for directory in (canonical, captured):
                result = subprocess.run([COMPOSE, '--project-name', 'fixture', '--project-directory', str(directory),
                    '--env-file', str(root/'.env'), '-f', str(root/'compose.yml'), 'config', '--format', 'json'],
                    cwd=private, env=env, capture_output=True, timeout=10)
                self.assertEqual(result.returncode, 0, 'private context probe failed')
                model = json.loads(result.stdout); models.append(model)
                self.assertEqual(model['services']['caddy']['volumes'][0]['source'], str(directory/'Caddyfile'))
            self.assertFalse(independently_equal(*models))

    def test_real_compose_env_file_is_not_redirected_by_interpolation_env_file(self):
        # No live paths: prove why just restoring --project-directory is NOT a
        # safe capture adapter. A service's .env is a separate file read.
        with tempfile.TemporaryDirectory(dir=self.root) as private:
            root = Path(private); canonical = root/'canonical'; canonical.mkdir(mode=0o700)
            (root/'config').mkdir(mode=0o700)
            (root/'captured.env').write_bytes(b'A=captured-synthetic\n')
            (canonical/'.env').write_bytes(b'A=pathname-synthetic\n')
            (root/'compose.yml').write_bytes(base('      KEEP: constant\n'))
            env = dict(PATH='/usr/bin:/bin', HOME=private, DOCKER_CONFIG=str(root/'config'),
                       DOCKER_HOST='unix://'+private+'/no-daemon.sock')
            result = subprocess.run([COMPOSE, '--project-name', 'fixture', '--project-directory', str(canonical),
                '--env-file', str(root/'captured.env'), '-f', str(root/'compose.yml'), 'config', '--format', 'json'],
                cwd=private, env=env, capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 0, 'private source-resolution probe failed')
            self.assertTrue(independently_equal(json.loads(result.stdout)['services']['app']['environment'],
                                               {'A':'pathname-synthetic', 'KEEP':'constant'}))

    def test_bounds_absent_dotenv_unsafe_root_and_bad_override_refuse(self):
        kwargs=dict(interpolation={},project='fixture',compose=COMPOSE,temporary_root=str(self.root))
        for content,dot,override in ((base(),None,OVERRIDE),(base(),b'A=' + b'x'*65536,OVERRIDE),
                                    (base(),b'A=x\n',OVERRIDE+b'  db:\n    image: other\n')):
            with self.assertRaises(planner.Refused):
                planner.prepare(content,dot,override,**kwargs)
        for temporary_root in (str(ROOT),'/private/tmp'):
            with self.assertRaises(planner.Refused):
                planner.prepare(base(),b'A=x\n',OVERRIDE,**dict(kwargs,temporary_root=temporary_root))

    def test_explicit_references_preserve_other_bytes_comments_and_final_newline(self):
        content=base('      B: explicit # retain\n').rstrip(b'\n')
        result=self.prepare(content,b'A=private\n')
        wanted=content.replace(b'    env_file: [.env]\n',b'').replace(b'      B: explicit # retain\n',
            b'      B: explicit # retain\n      A: ${A?CLB91 required contribution}\n')
        self.assertEqual(result.candidate,wanted)

    def test_unknown_version_refuses_before_parser_and_snapshot_writes(self):
        calls=[]
        def wrong(argv,*args,**kwargs):
            calls.append(argv)
            return types.SimpleNamespace(code=0,output=b'2.unknown\n',failure=None)
        with patch.object(planner,'CAPTURE',wrong),self.assertRaises(planner.Refused) as failed:
            planner.prepare(base(),b'A=private\n',OVERRIDE,interpolation={},project='clb91-private-test',
                            compose=COMPOSE,temporary_root=str(self.root))
        self.assertEqual(failed.exception.reason,'version')
        self.assertEqual(len(calls),1)
        self.assertEqual(list(self.root.iterdir()),[])

    def test_public_entrypoint_does_not_accept_files_or_disclose_inputs(self):
        r=subprocess.run([sys.executable,'-I','-S','-B',str(ROOT/'scripts/deploy/stage-compose-env-file-plan.py')],
                         input=CANARY.encode(),capture_output=True,timeout=10)
        self.assertNotEqual(r.returncode,0)
        self.assertEqual(r.stdout,b'')
        self.assertNotIn(CANARY.encode(),r.stderr)
        self.assertIn(b'private_api_only',r.stderr)

    def test_private_cwd_and_caller_environment_never_become_inputs(self):
        with patch.dict(os.environ,{'A':CANARY,'GH_TOKEN':CANARY,'SSH_AUTH_SOCK':'/private-canary',
                                   'COMPOSE_FILE':'/private-canary','RUBYOPT':'-r/private-canary'}):
            self.prove(base('      A: ${A:-fallback}\n'),b'A=explicit-snapshot\n',
                       'remove',{'A':'explicit-snapshot'})
        env=dict(PATH='/usr/bin:/bin',HOME=str(self.root),DOCKER_CONFIG=str(self.root),
                 DOCKER_HOST='unix://'+str(self.root)+'/no-daemon.sock')
        self.assertEqual(planner.capture(['/bin/pwd','-P'],env).decode().strip(),str(self.root))

    def test_tool_control_references_and_implicit_environment_are_unsupported(self):
        for name in ('HOME','PATH','PWD','TMPDIR','COMPOSE_FILE','DOCKER_HOST','RUBYOPT','GODEBUG'):
            for content,dot in ((base('      A: ${'+name+'}\n'),b'A=x\n'),
                                (base(),('A=${'+name+'}\n').encode()),
                                (base('      '+name+':\n'),b'A=x\n')):
                with self.subTest(name=name),self.assertRaises(planner.Refused) as failed:
                    self.prepare(content,dot)
                self.assertEqual(failed.exception.reason,'unsupported')

    def test_other_service_control_environment_and_list_forms_refuse(self):
        original = b'    environment:\n      OTHER: ${OTHER:-fallback}\n'
        for index, fields in enumerate((b'    environment:\n      HOME:\n',
                                        b'    environment: [PATH]\n',
                                        b'    environment:\n      - PATH\n',
                                        b'    environment:\n      TMPDIR: literal\n')):
            with self.subTest(index=index):
                with self.assertRaises(planner.Refused):
                    self.prepare(base().replace(original, fields), b'A=synthetic\n')
        self.assertFalse(any(argv[-3:] == ['config', '--format', 'json'] for argv in self.calls))

    def test_tilde_bind_sources_refuse_without_rejecting_literal_tilde_values(self):
        for index, source in enumerate(('~/fixture', '~other/fixture', '${MOUNT}')):
            content = base().replace(b'./Caddyfile:/etc/caddy/Caddyfile:ro',
                                     (source+':/etc/caddy/Caddyfile:ro').encode())
            with self.subTest(index=index):
                with self.assertRaises(planner.Refused):
                    self.prepare(content, b'A=synthetic\n', {'MOUNT':'~/fixture'})
        self.assertFalse(any(argv[-3:] == ['config', '--format', 'json'] for argv in self.calls))
        self.prove(base('      A: ${A}\n      TILDE: "~/literal"\n'), b'A=synthetic\n',
                   'remove', {'A':'synthetic', 'TILDE':'~/literal'})

    def test_dotenv_lexical_boundary_is_not_a_multiline_parser(self):
        # Each active physical line fits the unchanged helper regex; the real
        # Compose parser alone decides the quoted multiline value's semantics.
        self.prove(base(), b"A='line1\nB=line2'\n", 'remove', {'A':'line1\nB=line2'})

    def test_shell_special_context_is_refused_in_every_input_channel(self):
        names = ('IFS', 'OPTIND', 'OPTARG', 'PPID', 'UID', 'EUID', 'RANDOM', 'SECONDS',
                 'LINENO', 'BASH', 'BASH_VERSION', 'BASH_EXECUTION_STRING', 'BASH_SUBSHELL')
        for name in names:
            cases = (
                (base('      A: ${'+name+'}\n'), b'A=synthetic\n', {}),
                (base(), (name+'=synthetic\nA=synthetic\n').encode(), {}),
                (base(), b'A=synthetic\n', {name:'88'}),
                (base().replace(b'      OTHER: ${OTHER:-fallback}',
                                ('      '+name+':').encode()), b'A=synthetic\n', {}),
            )
            for index, (content, dot, interpolation) in enumerate(cases):
                with self.subTest(name=name, channel=index), self.assertRaises(planner.Refused):
                    self.prepare(content, dot, interpolation)
        self.assertFalse(any(argv[-3:] == ['config', '--format', 'json'] for argv in self.calls))

    def test_decoded_yaml_control_references_refuse_in_every_scalar_scope(self):
        escaped = '"\\u0024{HOME}"'
        cases = [base('      A: '+value+'\n') for value in
                 (escaped, '"${H\\u004fME}"', '"\\x24{IFS}"')]
        cases.extend((
            base().replace(b'      OTHER: ${OTHER:-fallback}', ('      OTHER: '+escaped).encode()),
            base(extras='    labels:\n      private: '+escaped+'\n'),
            base(extras='    command: [echo, '+escaped+']\n'),
            base(extras='    healthcheck:\n      test: [CMD, echo, '+escaped+']\n'),
            ('name: '+escaped+'\n').encode()+base(),
        ))
        for index, content in enumerate(cases):
            with self.subTest(index=index), self.assertRaises(planner.Refused):
                self.prepare(content, b'A=synthetic\n')
        self.assertFalse(any(argv[-3:] == ['config', '--format', 'json'] for argv in self.calls))
        self.prove(base('      A: "\\x41"\n'), b'A=synthetic\n', 'remove', {'A':'A'})


if __name__ == '__main__':
    unittest.main(verbosity=2)
