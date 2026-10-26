#!/usr/bin/env python3
"""Offline pin admission/transport/validator regressions; never connect to SSH."""
import base64
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / 'scripts/deploy/release_ssh_pin.py'
WORKFLOWS = (('deploy-ssh.yml', 'deploy'), ('db-migrate.yml', 'migrate'))
spec = importlib.util.spec_from_file_location('release_ssh_pin', HELPER)
M = importlib.util.module_from_spec(spec)
spec.loader.exec_module(M)
PUBLIC = (ROOT / 'scripts/tests/fixtures/clb91-stage-host.pub').read_text().strip()
SYNTHETIC = 'ssh-ed25519 ' + base64.b64encode(M.KEY_PREFIX + bytes(range(32))).decode()


def environment(profile='stage', host='178.20.209.5', port='22', public=PUBLIC):
    endpoint = host if port == '22' else f'[{host}]:{port}'
    return dict(APP_ENV=profile, SSH_USER='release-user', SSH_HOST=host, SSH_PORT=port,
                SSH_KNOWN_HOSTS=endpoint + ' ' + public + '\n')


class PinTest(unittest.TestCase):
    def test_stage_real_public_key_and_exact_endpoint(self):
        env = environment()
        expected = env['SSH_KNOWN_HOSTS'].encode()
        self.assertEqual(M.validate(env), expected)
        for pin in (env['SSH_KNOWN_HOSTS'].rstrip('\n'),
                    env['SSH_KNOWN_HOSTS'].replace('178.20.209.5 ', '[178.20.209.5]:22 ')):
            self.assertEqual(M.validate(dict(env, SSH_KNOWN_HOSTS=pin)), expected)

    def test_missing_malformed_wrong_algorithm_and_fingerprint(self):
        original = environment()['SSH_KNOWN_HOSTS']
        key = PUBLIC.split()[1]
        bad = ['', original.replace(key, '!'), original.replace(key, key[:-4]),
               original.replace('ssh-ed25519', 'ssh-rsa'),
               '178.20.209.5 ' + SYNTHETIC + '\n',
               original.replace(key, base64.b64encode(b'invalid-wire-format').decode()),
               original.replace(key, base64.b64encode(M.KEY_PREFIX + bytes(31)).decode()),
               original.replace(key, key + '='), original.replace('ssh-ed25519 ', 'ssh-ed25519\t')]
        for pin in bad:
            with self.subTest(category=bad.index(pin)), self.assertRaises(ValueError):
                M.validate(dict(environment(), SSH_KNOWN_HOSTS=pin))

    def test_extra_untrusted_records_and_patterns(self):
        original = environment()['SSH_KNOWN_HOSTS']
        bad = [original * 2, original + '\n', '#comment\n' + original, original.rstrip() + ' comment\n',
               '@cert-authority ' + original, '@revoked ' + original,
               original.replace('178.20.209.5 ', '* '),
               original.replace('178.20.209.5 ', '178.20.209.5,evil.invalid '),
               original.replace('178.20.209.5 ', '|1|aaaa|bbbb '), original.replace('\n', '\r\n')]
        for pin in bad:
            with self.subTest(category=bad.index(pin)), self.assertRaises(ValueError):
                M.validate(dict(environment(), SSH_KNOWN_HOSTS=pin))

    def test_stage_wrong_host_port_user_and_profile(self):
        for key, value in [('SSH_HOST', 'other.invalid'), ('SSH_PORT', '2222'),
                           ('SSH_PORT', '022'), ('SSH_PORT', '+22'), ('SSH_PORT', '0'),
                           ('SSH_PORT', '65536'), ('SSH_USER', '-oProxyCommand=bad'),
                           ('APP_ENV', 'dev')]:
            with self.subTest(field=key, value=value), self.assertRaises(ValueError):
                M.validate(dict(environment(), **{key: value}))
        with self.assertRaises(ValueError):
            M.validate(environment(host='other.invalid'))
        with self.assertRaises(ValueError):
            M.validate(environment(port='2222'))

    def test_prod_separate_environment_key_and_endpoint(self):
        for port in ('22', '2222'):
            env = environment('prod', 'prod.example', port, SYNTHETIC)
            self.assertEqual(M.validate(env), env['SSH_KNOWN_HOSTS'].encode())
        for env in (environment('prod'), environment('prod', 'prod.example'),
                    environment('prod', public=SYNTHETIC)):
            with self.assertRaises(ValueError):
                M.validate(env)

    def test_validation_cli_redacts_private_configuration(self):
        env = {**os.environ, **environment(), 'SSH_KNOWN_HOSTS': 'private-token-must-not-leak'}
        result = subprocess.run([sys.executable, '-I', '-S', '-B', str(HELPER), '--validate'],
                                env=env, capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, '')
        self.assertEqual(result.stderr, 'quiesced-release: trusted SSH pin unavailable\n')
        self.assertNotIn(env['SSH_KNOWN_HOSTS'], result.stderr)

    def test_workflow_contract_mutations_refuse_before_agent_or_release(self):
        ruby = """require 'yaml'; require 'validate-workflow-capabilities'
job=YAML.safe_load(STDIN.read, aliases: true).fetch('jobs').fetch(ARGV[1])
WorkflowCapabilityPolicy.validate_deploy_job_contract('.github/workflows/'+ARGV[0], ARGV[1], job)
"""
        for file, job in WORKFLOWS:
            raw = (ROOT / '.github/workflows' / file).read_text()
            self.assertNotIn('ssh-keyscan', raw)
            self.assertLess(raw.index('Validate pinned SSH host'), raw.index('Setup SSH agent'))
            mutations = [raw.replace('SSH_KNOWN_HOSTS: ${{ secrets.SSH_KNOWN_HOSTS }}', 'SSH_KNOWN_HOSTS: ""', 1),
                         raw.replace('EXPECTED_REVISION: ${{ github.sha }}', 'EXPECTED_REVISION: old-revision'),
                         raw.replace(' --validate', ' --validate || true'),
                         raw.replace('StrictHostKeyChecking=yes', 'StrictHostKeyChecking=no') + '\n#changed\n']
            # Move agent access in front of validation, retaining both exact steps.
            begin = raw.index('      - name: Validate pinned SSH host')
            middle = raw.index('      - name: Setup SSH agent')
            end = raw.index('      - name: Quiesce, migrate')
            mutations[-1] = raw[:begin] + raw[middle:end] + raw[begin:middle] + raw[end:]
            for changed in [raw, *mutations]:
                r = subprocess.run(['ruby', '-I'+str(ROOT / 'scripts'), '-e', ruby, file, job],
                                   input=changed, capture_output=True, text=True, timeout=10)
                self.assertEqual(r.returncode == 0, changed == raw, r.stderr)

    def test_transport_policy_refuses_each_strict_option_and_site_bypass(self):
        with tempfile.TemporaryDirectory(prefix='clb91-pin-policy-') as tmp:
            root = Path(tmp)
            for name in ('quiesced-release.sh', 'remote-compose-release.sh', 'shared_staging_flock.py',
                         'release_ssh_pin.py', 'release_private_root.py'):
                target = root / 'scripts/deploy' / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / 'scripts/deploy' / name, target)
            subprocess.run(['git', '-c', 'init.templateDir=', 'init', '-q', str(root)], check=True)
            subprocess.run(['git', '-C', str(root), 'add', 'scripts'], check=True)
            runner = root / 'scripts/deploy/quiesced-release.sh'
            original = runner.read_text()
            command = ['ruby', '-I'+str(ROOT / 'scripts'), '-rvalidate-workflow-capabilities', '-e',
                       'WorkflowCapabilityPolicy.validate_remote_credential_contract(Pathname.new(ARGV[0]))', str(root)]
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 0)
            options = ['StrictHostKeyChecking=yes', 'GlobalKnownHostsFile=/dev/null',
                       'KnownHostsCommand=none', 'VerifyHostKeyDNS=no', 'UpdateHostKeys=no',
                       'ProxyCommand=none', 'ProxyJump=none', 'ControlPath=none', 'ControlMaster=no',
                       'HostKeyAlgorithms=ssh-ed25519', 'IdentityFile=none']
            changes = [original.replace(v, v+'-changed', 1) for v in options]
            for needle in ['ssh "${ssh_options[@]}" -p', 'ssh "${ssh_options[@]}" -T',
                           'scp "${ssh_options[@]}" -P']:
                changes.append(original.replace(needle, needle.replace('"${ssh_options[@]}" ', ''), 1))
            for changed in changes:
                runner.write_text(changed)
                r = subprocess.run(command, capture_output=True, timeout=10)
                self.assertNotEqual(r.returncode, 0)
            runner.write_text(original)


@unittest.skipUnless(sys.platform == 'linux', 'anonymous parent descriptor transport requires Linux procfs')
class TransportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location('release_runner_tests', ROOT / 'scripts/tests/test_quiesced_release_state.py')
        cls.R = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.R)

    def test_valid_pin_real_bootstrap_all_transport_options_and_cleanup(self):
        with self.R.RunnerHarness() as h:
            r = h.run()
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertTrue(h.ssh_log.read_text())
            self.assertTrue(h.scp_log.read_text())
            self.assertEqual(list(Path(h.pin_directory.name).iterdir()), [])

    def test_invalid_pin_blocks_ssh_and_scp_without_effects(self):
        for key, value in [('SSH_KNOWN_HOSTS', ''), ('SSH_KNOWN_HOSTS', 'invalid'),
                           ('SSH_KNOWN_HOSTS', '178.20.209.5 ' + SYNTHETIC),
                           ('SSH_HOST', 'other.invalid'), ('SSH_PORT', '2222')]:
            with self.subTest(field=key), self.R.RunnerHarness() as h:
                h.env[key] = value
                r = h.run()
                self.assertEqual(r.returncode, 2)
                self.assertEqual(h.ssh_log.read_text(), '')
                self.assertEqual(h.scp_log.read_text(), '')
                self.assertEqual(list(Path(h.pin_directory.name).iterdir()), [])

    def test_internal_flag_and_missing_descriptor_cannot_skip_validation(self):
        with self.R.RunnerHarness() as h:
            for marker in ('', '18', '19', '-1'):
                env = dict(h.env, CLUBS_RELEASE_PIN_FD=marker)
                r = subprocess.run(['bash', str(self.R.RUNNER), '--pinned-ssh-v1'],
                                   env=env, capture_output=True, text=True, timeout=10)
                self.assertEqual(r.returncode, 2)
            self.assertEqual(h.ssh_log.read_text(), '')
            self.assertEqual(h.scp_log.read_text(), '')

    def test_forged_inherited_fd_wrong_content_mode_link_or_access_is_refused(self):
        with self.R.RunnerHarness() as h:
            env = dict(h.env, CLUBS_RELEASE_PIN_FD='18')
            expected = M.validate(env)
            for category in ('content', 'mode', 'linked', 'writable'):
                with self.subTest(category=category), tempfile.TemporaryFile() as backing:
                    backing.write(expected if category != 'content' else b'untrusted key')
                    backing.flush()
                    os.fchmod(backing.fileno(), 0o644 if category == 'mode' else 0o600)
                    if category == 'linked':
                        target = Path(h.pin_directory.name) / 'linked-pin'
                        target.write_bytes(expected)
                        target.chmod(0o600)
                        descriptor = os.open(target, os.O_RDONLY)
                    elif category == 'writable':
                        descriptor = os.dup(backing.fileno())
                    else:
                        descriptor = os.open(f'/proc/self/fd/{backing.fileno()}', os.O_RDONLY)
                    os.dup2(descriptor, 18, inheritable=True)
                    if descriptor != 18:
                        os.close(descriptor)
                    try:
                        r = subprocess.run(['bash', str(self.R.RUNNER), '--pinned-ssh-v1'],
                                           env=env, pass_fds=(18,), capture_output=True, timeout=10)
                        self.assertEqual(r.returncode, 2)
                    finally:
                        os.close(18)
            self.assertEqual(h.ssh_log.read_text(), '')
            self.assertEqual(h.scp_log.read_text(), '')

    def test_untrusted_runner_temp_has_no_tmp_fallback(self):
        with self.R.RunnerHarness() as h:
            for path in ('', '/tmp', '/does-not-exist'):
                h.env['RUNNER_TEMP'] = path
                self.assertEqual(h.run().returncode, 2)
            self.assertEqual(h.ssh_log.read_text(), '')
            self.assertEqual(h.scp_log.read_text(), '')


if __name__ == '__main__':
    unittest.main(verbosity=2)
