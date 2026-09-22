#!/usr/bin/env python3
"""Offline pinned post-action regression. Never starts an agent or loads keys."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PIN = 'e83874834305fe9a4a2997156cb26c5de65a8555'
OLD = 'dc588b651fe13675774614f8e6a936a468676387'
WORKFLOWS = ('db-migrate', 'deploy-ssh', 'release-status', 'corrected-stage-release',
             'stage-compose-diagnostic', 'stage-runtime-inventory', 'stage-runtime-feasibility',
             'stage-compose-mode-repair', 'stage-compose-env-semantic')
FIXTURE = ROOT / 'scripts/tests/fixtures/ssh-agent-v0.10.0.json'

# Exact upstream source and extracted dist module bodies are evaluated in a VM.
# All imports are synthetic stubs: no OS lookup, environment forwarding or exec.
JS = r'''
const fs = require('fs'), vm = require('vm');
const f = JSON.parse(fs.readFileSync(0, 'utf8'));
const calls = [], logs = [];
const cp = {execFileSync: (cmd, args, options) => {
  if (typeof cmd !== 'string' || !cmd) throw Error('undefined command');
  calls.push([cmd, args, options]);
}};
const os = {userInfo: () => ({homedir: '/synthetic'}), homedir: () => '/synthetic'};
const core = {getInput: key => key === 'ssh-agent-cmd' ? f.input : ''};
let paths;
function run(code) {
  const module = {exports: {}};
  const imports = {os, '@actions/core': core, child_process: cp, './paths.js': paths};
  const ids = {87: os, 470: core, 129: cp, 972: paths};
  const get = (map, key) => {if (!(key in map)) throw Error('unexpected import'); return map[key];};
  vm.runInNewContext(code, {module, exports: module.exports,
    require: key => get(imports, key), __webpack_require__: key => get(ids, key),
    process: {env: {}}, console: {log: (...v) => logs.push(v.join(' '))}}, {timeout: 1000});
  return module.exports;
}
paths = run(f.paths);
run(f.cleanup);
process.stdout.write(JSON.stringify({calls, logs}));
'''


class SshAgentPinTest(unittest.TestCase):
    def setUp(self):
        self.fixture = json.loads(FIXTURE.read_bytes())

    def test_exact_upstream_fixture_and_post_registration(self):
        self.assertEqual(hashlib.sha256(FIXTURE.read_bytes()).hexdigest(),
                         'e3658f5d41b93c35bb078d249229912806f3dde36f45282ebe81d3d08206ee2d')
        self.assertEqual(self.fixture['commit'], PIN)
        self.assertEqual(self.fixture['repository'], 'webfactory/ssh-agent')
        self.assertEqual(self.fixture['tag'], 'v0.10.0')
        for name, raw in self.fixture['files'].items():
            self.assertEqual(hashlib.sha256(raw.encode()).hexdigest(), self.fixture['sha256'][name])
        for module, name, replacements in (
            ('175', 'cleanup.js', {"require('child_process')": '__webpack_require__(129)',
                                   "require('./paths.js')": '__webpack_require__(972)'}),
            ('972', 'paths.js', {"require('os')": '__webpack_require__(87)',
                                "require('@actions/core')": '__webpack_require__(470)'}),
        ):
            source = self.fixture['files'][name]
            for before, after in replacements.items():
                source = source.replace(before, after)
            # Upstream source/dist differ only in explanatory full-line comments.
            def executable_lines(raw):
                return '\n'.join(line for line in raw.strip().splitlines() if not line.lstrip().startswith('//'))
            self.assertEqual(executable_lines(source), executable_lines(self.fixture['dist_modules'][module]))
        parsed = subprocess.run(['ruby', '-ryaml', '-rjson', '-e',
            'puts JSON.generate(YAML.safe_load(STDIN.read))'],
            input=self.fixture['files']['action.yml'], text=True, capture_output=True, check=True)
        self.assertEqual(json.loads(parsed.stdout)['runs'], {
            'using': 'node24', 'main': 'dist/index.js', 'post': 'dist/cleanup.js', 'post-if': 'always()'})

    def cleanup(self, paths, cleanup, value=''):
        result = subprocess.run(['node', '-e', JS], input=json.dumps(
            {'paths': paths, 'cleanup': cleanup, 'input': value}),
            text=True, capture_output=True, check=True, timeout=10)
        return json.loads(result.stdout)

    def test_source_and_dist_cleanup_resolve_default_and_custom_commands(self):
        for paths, cleanup in ((self.fixture['files']['paths.js'], self.fixture['files']['cleanup.js']),
                               (self.fixture['dist_modules']['972'], self.fixture['dist_modules']['175'])):
            for custom in ('', '/synthetic/custom-agent'):
                with self.subTest(dist='__webpack_require__' in paths, custom=bool(custom)):
                    result = self.cleanup(paths, cleanup, custom)
                    self.assertEqual(result, {'calls': [[custom or 'ssh-agent', ['-k'], {'stdio': 'inherit'}]],
                                              'logs': ['Stopping SSH agent']})

    def test_old_export_contract_reproduces_swallowed_cleanup_failure(self):
        # Deterministic v0.9.0 contract: only sshAgentCmdDefault was exported.
        for cleanup in (self.fixture['files']['cleanup.js'], self.fixture['dist_modules']['175']):
            result = self.cleanup("module.exports = {sshAgentCmdDefault: 'ssh-agent'};", cleanup)
            self.assertEqual(result['calls'], [])
            self.assertIn('undefined command', result['logs'])
            self.assertIn('Error stopping the SSH agent, proceeding anyway', result['logs'])

    def test_canonical_workflow_policy_and_stale_pin_refusals(self):
        paths = {ROOT / '.github/workflows' / (name + '.yml') for name in WORKFLOWS}
        actual = {p for p in (ROOT / '.github/workflows').glob('*.yml') if 'webfactory/ssh-agent@' in p.read_text()}
        self.assertEqual(actual, paths)
        for p in paths:
            self.assertEqual(p.read_text().count('webfactory/ssh-agent@' + PIN), 1)
            self.assertNotIn(OLD, p.read_text())
        for file in ('scripts/validate-workflow-capabilities.rb', 'scripts/validate-quiesced-deployment.sh'):
            raw = (ROOT / file).read_text()
            self.assertEqual(raw.count(PIN), 1)
            self.assertNotIn(OLD, raw)
        with tempfile.TemporaryDirectory(prefix='clb91-ssh-pin-') as directory:
            root = Path(directory)
            for name in ('scripts', '.github'):
                shutil.copytree(ROOT / name, root / name)
            (root / 'gradle').mkdir()
            shutil.copyfile(ROOT / 'gradle/verification-metadata.xml', root / 'gradle/verification-metadata.xml')
            (root / 'docs/ops').mkdir(parents=True)
            shutil.copyfile(ROOT / 'docs/ops/secrets-rotation.md', root / 'docs/ops/secrets-rotation.md')
            subprocess.run(['git', '-c', 'init.templateDir=', 'init', '-q', str(root)], check=True)
            subprocess.run(['git', '-C', str(root), 'add', '--', '.'], check=True)
            def validate():
                return subprocess.run(['ruby', str(root / 'scripts/validate-workflow-capabilities.rb'), str(root)],
                                      capture_output=True, timeout=20)
            baseline = validate()
            self.assertEqual(baseline.returncode, 0, baseline.stderr.decode())
            for source in sorted(paths):
                with self.subTest(workflow=source.name):
                    target = root / source.relative_to(ROOT)
                    original = target.read_text()
                    target.write_text(original.replace(PIN, OLD))
                    result = validate()
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(source.name.encode(), result.stdout + result.stderr)
                    target.write_text(original)


if __name__ == '__main__':
    unittest.main(verbosity=2)
