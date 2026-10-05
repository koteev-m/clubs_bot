#!/usr/bin/env python3
"""ACTUAL wrapper tests. Only runnable in an explicitly marked Docker fixture.
No package installation/download; network must be disabled by docker --network none.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
if not (sys.platform == 'linux' and os.geteuid() == 0 and Path('/.dockerenv').exists()
        and (os.environ.get('CLB175_DISPOSABLE') == '1'
             or sys.argv[1:2] == ['--record-invocation'])):
    raise SystemExit('Refuse: requires CLB175_DISPOSABLE=1 networkless disposable Docker root filesystem')
INSTALL = Path('/usr/local/sbin/clubs-stage-apt-metadata-refresh')
ARTIFACT = ROOT/'scripts/deploy/stage-apt-metadata-refresh-wrapper'
# Public, locally generated disposable test signing fixture; private key discarded.
SIGNED_FIXTURE = {'Packages': 'UGFja2FnZTogY2xiMTc1LXNpZ25lZC1maXh0dXJlClZlcnNpb246IDEKQXJjaGl0ZWN0dXJlOiBhbGwKRGVzY3JpcHRpb246IG5ldmVyIGluc3RhbGxlZAoK', 'InRelease': 'LS0tLS1CRUdJTiBQR1AgU0lHTkVEIE1FU1NBR0UtLS0tLQpIYXNoOiBTSEE1MTIKCk9yaWdpbjogQ0xCMTc1IGRpc3Bvc2FibGUgZml4dHVyZQpMYWJlbDogQ0xCMTc1CkRhdGU6IE1vbiwgMDUgT2N0IDIwMjYgMDA6MDA6MDAgVVRDCkFyY2hpdGVjdHVyZXM6IGFybTY0IGFtZDY0IGFsbApTSEEyNTY6CiAwMDFmOTNmMzVkMGRkNzkyYzM3Y2IzZmJkOGNmMWNmMDcyMjZkMGJlYzQxYTA5YTA1MWE4NGE1M2Q5Y2JiNWJmIDkwIFBhY2thZ2VzCi0tLS0tQkVHSU4gUEdQIFNJR05BVFVSRS0tLS0tCgppSFVFQVJZS0FCMFdJUVR4a2VLSWdiSFROcExaUThER0EwemlWTGZTdndVQ2FzTmFtd0FLQ1JER0EwemlWTGZTCnYybXpBUDR5YXYycWlZMmNCTE9udTQxbmZNWkRyN0JQeXNUZUduV2RWVjArV2VudGRnRCtPeE1HT1duMmZzMTYKcXppb2ZvMU55R2ZydHNZWUUzV3c0Ri96RDcwYkZnUT0KPTU1a20KLS0tLS1FTkQgUEdQIFNJR05BVFVSRS0tLS0tCg==', 'fixture.gpg': 'mDMEasNamxYJKwYBBAHaRw8BAQdAc7qOKz3PdsaZWLibtUQ6fkPuLPKDarZ57fgfAYlaExa0GWNsYjE3NS1kaXNwb3NhYmxlLWZpeHR1cmWIkwQTFgoAOxYhBPGR4oiBsdM2ktlDwMYDTOJUt9K/BQJqw1qbAhsDBQsJCAcCAiICBhUKCQgLAgQWAgMBAh4HAheAAAoJEMYDTOJUt9K/hhQA/3gN8jX9h742JjsY5TkowQEk2T/yRfREbr3nkVXiCSQDAQC2RZZkW45qJZxYTVUywxc7lsdwg4Log45k0BfWYmoCDA=='}


class ActualWrapperTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        INSTALL.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(ARTIFACT,INSTALL); INSTALL.chmod(0o755)
        # Instrument only the disposable APT binary to count actual update attempts.
        shutil.copy2('/usr/bin/apt-get','/usr/bin/clb175-test-apt-get')
        Path('/usr/bin/apt-get').write_text('''#!/bin/dash
# The instrumentation shell adds PWD; the real APT binary does not.
unset PWD
if [ "$1" = update ]; then
 /usr/bin/printf 'update\\n' >> /tmp/clb175-attempts
 /usr/bin/python3 -I -S -B /repo/scripts/tests/test_stage_apt_metadata_refresh_linux.py --record-invocation "$@"
fi
exec /usr/bin/clb175-test-apt-get "$@"
''')
        Path('/usr/bin/apt-get').chmod(0o755)
        for name in ('lock','lock-frontend'):
            (Path('/var/lib/dpkg')/name).touch(exist_ok=True)
        shutil.rmtree('/etc/apt/sources.list.d'); Path('/etc/apt/sources.list.d').mkdir()
        cls.repo=Path('/srv/clb175-test-repo'); cls.repo.mkdir(exist_ok=True)
        (cls.repo/'Packages').write_text('Package: clb175-fixture\nVersion: 1\nArchitecture: all\nDescription: never installed\n\n')
        Path('/etc/apt/sources.list').write_text('deb [trusted=yes] copy:/srv/clb175-test-repo ./\n')

    def setUp(self):
        # Each repository fixture is independent, including signing state.
        # Fixture setup only; the wrapper itself never performs this cleanup.
        shutil.rmtree('/var/lib/apt/lists')
        Path('/var/lib/apt/lists').mkdir(mode=0o755)
        (self.repo/'InRelease').unlink(missing_ok=True)
        (self.repo/'Packages').write_text('Package: clb175-fixture\nVersion: 1\nArchitecture: all\nDescription: never installed\n\n')
        for name in ('/tmp/clb175-attempts','/tmp/clb175-sentinel','/tmp/clb175-escaped-cache'):
            Path(name).unlink(missing_ok=True)
        hook='APT::Update::Pre-Invoke { "/usr/bin/touch /tmp/clb175-sentinel"; };\n'
        for name in ('Post-Invoke','Post-Invoke-Success','Auth-Failure'):
            hook += 'APT::Update::'+name+' { "/usr/bin/touch /tmp/clb175-sentinel"; };\n'
        hook += 'Binary::apt-get::APT::Update::Pre-Invoke { "/usr/bin/touch /tmp/clb175-sentinel"; };\n'
        hook += 'Acquire::http::Proxy-Auto-Detect "/usr/bin/touch /tmp/clb175-sentinel";\n'
        hook += 'Dir::Cache::pkgcache "/tmp/clb175-escaped-cache";\n'
        Path('/etc/apt/apt.conf.d/99clb175-test-hooks').write_text(hook)
        Path('/etc/apt/apt.conf').write_text(hook)
        Path('/tmp/clb175-hostile-env.conf').write_text(hook)
        Path('/etc/apt/sources.list').write_text('deb [trusted=yes] copy:/srv/clb175-test-repo ./\n')

    def run_wrapper(self,*args):
        before=hashlib.sha256(Path('/var/lib/dpkg/status').read_bytes()).hexdigest()
        done=subprocess.run([str(INSTALL),*args],capture_output=True,timeout=90,
            env=dict(os.environ,PATH='/evil',HOME='/evil',LC_ALL='hostile',LANG='hostile',
                     APT_CONFIG='/tmp/clb175-hostile-env.conf',PYTHONPATH='/evil',
                     PYTHONSTARTUP='/evil',ENV='/evil',BASH_ENV='/evil',
                     http_proxy='http://127.0.0.1:1',https_proxy='http://127.0.0.1:1'))
        self.assertLess(len(done.stdout),16384)
        self.assertEqual(before,hashlib.sha256(Path('/var/lib/dpkg/status').read_bytes()).hexdigest())
        self.assertFalse(Path('/tmp/clb175-sentinel').exists())
        self.assertFalse(Path('/tmp/clb175-escaped-cache').exists())
        if args: return done,None
        body=json.loads(done.stdout)
        self.assertEqual(body['wrapper_sha256'],hashlib.sha256(ARTIFACT.read_bytes()).hexdigest())
        return done,body

    def attempts(self):
        path=Path('/tmp/clb175-attempts')
        return path.read_text().splitlines() if path.exists() else []

    def test_01_positive_control_hook_actually_executes(self):
        done=subprocess.run(['/usr/bin/apt-get','-o','Dir::Cache::pkgcache=',
                             '-o','Dir::Cache::srcpkgcache=','update'],capture_output=True,timeout=30)
        self.assertEqual(done.returncode,0,done.stderr.decode())
        self.assertTrue(Path('/tmp/clb175-sentinel').exists())

    def test_02_real_refresh_metadata_only_hooks_never_execute(self):
        # Force a meaningful index/content change rather than accepting a no-op.
        (self.repo/'Packages').write_text('Package: clb175-fixture\nVersion: 2\nArchitecture: all\nDescription: never installed\n\n')
        done,body=self.run_wrapper()
        self.assertEqual(done.returncode,0,done.stdout.decode())
        self.assertEqual(body['result'],'REFRESHED')
        self.assertEqual(body['apt_exit_status'],0)
        self.assertEqual(body['dpkg_before'],body['dpkg_after'])
        self.assertEqual(body['status_before'],body['status_after'])
        self.assertEqual(body['config_before'],body['config_after'])
        self.assertEqual(body['extended_state_before'],body['extended_state_after'])
        self.assertNotEqual(body['lists_before']['sha256'],body['lists_after']['sha256'])
        self.assertEqual(self.attempts(),['update'])
        self.assertEqual(body['attempts'],1)
        self.assertNotIn('copy:',done.stdout.decode())
        query=subprocess.run(['/usr/bin/dpkg-query','-W','clb175-fixture'],capture_output=True)
        self.assertNotEqual(query.returncode,0)
        self.assertFalse(Path('/var/cache/apt/pkgcache.bin').exists())
        self.assertFalse(Path('/var/cache/apt/srcpkgcache.bin').exists())
        invocation=json.loads(Path('/tmp/clb175-invocation.json').read_text())
        self.assertEqual(invocation['argv'],['update'])
        self.assertEqual(set(invocation['env']),{'PATH','HOME','LANG','LC_ALL','DEBIAN_FRONTEND','APT_CONFIG'})
        self.assertEqual(invocation['env']['LC_ALL'],'C')
        self.assertEqual(invocation['env']['HOME'],'/')
        self.assertTrue(invocation['sealed'])
        self.assertIn('Dir::Etc::main "/dev/null";',invocation['dump'])
        self.assertIn('Dir::Etc::parts "/dev/null";',invocation['dump'])
        self.assertIn('Acquire::Retries "0";',invocation['dump'])
        for marker in ('sentinel','Proxy-Auto-Detect','escaped-cache','Binary::apt-get::'):
            self.assertNotIn(marker,invocation['dump'])

    def test_03_failure_no_retry_no_cleanup(self):
        Path('/etc/apt/sources.list').write_text('deb [trusted=yes] copy:/srv/clb175-no-such-repo ./\n')
        retained=Path('/var/lib/apt/lists/clb175-obsolete-index'); retained.write_text('retain')
        done,body=self.run_wrapper()
        self.assertNotEqual(done.returncode,0)
        self.assertEqual(body['result'],'REFUSED')
        self.assertEqual(body['reason'],'UPDATE_FAILED')
        self.assertEqual(body['apt_exit_status'],100)
        self.assertEqual(self.attempts(),['update'])
        self.assertEqual(retained.read_text(),'retain')
        self.assertEqual(body['dpkg_before'],body['dpkg_after'])
        self.assertIsNotNone(body['lists_after'])
        self.assertGreater(body['diagnostics']['bytes'],0)

    def test_04_args_refused_without_apt(self):
        done,unused=self.run_wrapper('update')
        self.assertEqual(done.returncode,64)
        self.assertEqual(self.attempts(),[])

    def test_05_nonroot_refused_without_apt(self):
        done=subprocess.run([str(INSTALL)],capture_output=True,
            preexec_fn=lambda: (os.setgid(65534),os.setuid(65534)))
        self.assertEqual(done.returncode,1)
        self.assertEqual(json.loads(done.stdout)['attempts'],0)
        self.assertEqual(self.attempts(),[])

    def test_07_signed_source_keyring_is_read_without_hook_execution(self):
        import base64
        for name, encoded in SIGNED_FIXTURE.items():
            (self.repo/name).write_bytes(base64.b64decode(encoded))
        shutil.copyfile(self.repo/'fixture.gpg','/usr/share/keyrings/clb175-fixture.gpg')
        Path('/etc/apt/sources.list').write_text(
            'deb [signed-by=/usr/share/keyrings/clb175-fixture.gpg] copy:/srv/clb175-test-repo ./\n')
        done, body=self.run_wrapper()
        self.assertEqual(done.returncode,0,done.stdout.decode())
        self.assertEqual(body['result'],'REFRESHED')
        self.assertEqual(self.attempts(),['update'])
        self.assertEqual(body['dpkg_before'],body['dpkg_after'])

    def test_08_package_state_change_cannot_report_success(self):
        before=Path('/var/lib/dpkg/status').read_bytes()
        apt=Path('/usr/bin/apt-get'); original=apt.read_text()
        apt.write_text(original.replace('exec /usr/bin/clb175-test-apt-get',
            '/usr/bin/printf \"\\n\" >> /var/lib/dpkg/status\nexec /usr/bin/clb175-test-apt-get'))
        try:
            done=subprocess.run([str(INSTALL)],capture_output=True,timeout=90)
            body=json.loads(done.stdout)
            self.assertEqual(done.returncode,1)
            self.assertEqual(body['reason'],'PACKAGE_STATE_CHANGED')
            self.assertEqual(body['result'],'REFUSED')
            self.assertEqual(body['attempts'],1)
        finally:
            apt.write_text(original)
            Path('/var/lib/dpkg/status').write_bytes(before)

    @unittest.skipUnless(Path('/usr/bin/strace').exists(), 'preinstalled strace unavailable')
    @unittest.skipIf('/run/rosetta/rosetta' in Path('/proc/self/maps').read_text(),
                     'Rosetta does not expose native child syscalls to strace; native audit required')
    def test_09_actual_syscalls_have_no_unrelated_persistent_writes(self):
        import re
        trace=Path('/tmp/clb175-audit')
        calls='openat,rename,renameat,renameat2,unlink,unlinkat,mkdir,mkdirat,rmdir,chmod,chown,utimensat,execve'
        done=subprocess.run(['/usr/bin/strace','-f','-e','trace='+calls,'-o',str(trace),str(INSTALL)],
                            capture_output=True,timeout=90)
        self.assertEqual(done.returncode,0,done.stdout.decode())
        self.assertFalse(Path('/tmp/clb175-sentinel').exists())
        rows=trace.read_text().splitlines()
        for row in rows:
            if 'openat(' in row and any(flag in row for flag in ('O_WRONLY','O_RDWR','O_CREAT','O_TRUNC')):
                names=re.findall(r'"([^"\n]+)"',row)
                self.assertTrue(names, row)
                self.assertTrue(names[0].startswith(('/var/lib/apt/lists/', '/tmp/clb175-'))
                                or names[0] in ('/dev/null','/var/lib/dpkg/lock','/var/lib/dpkg/lock-frontend'), row)
            if 'execve(' in row and '= 0' in row:
                self.assertNotIn('/usr/bin/touch',row)
                self.assertNotIn('systemctl',row)
                self.assertNotIn('service',row)
                if '/usr/bin/dpkg"' in row:
                    self.assertIn('--print-foreign-architectures',row)
        apt_pids={row.split()[0] for row in rows
                  if any('execve("'+binary+'"' in row for binary in
                         ('/usr/bin/clb175-test-apt-get','/usr/bin/apt-config'))}
        self.assertTrue(apt_pids)
        # Wrapper fingerprints /etc/apt as data; APT must not LOAD apt.conf*.
        self.assertFalse(any(row.split()[0] in apt_pids and '/etc/apt/apt.conf' in row
                             for row in rows))

    def test_06_dpkg_lock_refuses_without_apt(self):
        import fcntl
        with open('/var/lib/dpkg/lock-frontend','r+') as locked:
            fcntl.lockf(locked,fcntl.LOCK_EX|fcntl.LOCK_NB)
            done,body=self.run_wrapper()
        self.assertEqual(done.returncode,1)
        self.assertEqual(body['attempts'],0)
        self.assertEqual(self.attempts(),[])


if __name__=='__main__':
    if sys.argv[1:2] == ['--record-invocation']:
        import fcntl
        fd=int(os.environ['APT_CONFIG'].rsplit('/',1)[1])
        seals=fcntl.fcntl(fd,fcntl.F_GET_SEALS)
        mask=fcntl.F_SEAL_WRITE|fcntl.F_SEAL_GROW|fcntl.F_SEAL_SHRINK|fcntl.F_SEAL_SEAL
        dump=subprocess.check_output(['/usr/bin/apt-config','dump'],pass_fds=(fd,)).decode()
        Path('/tmp/clb175-invocation.json').write_text(json.dumps(
            dict(argv=sys.argv[2:],env=dict(os.environ),sealed=(seals&mask)==mask,dump=dump)))
    else:
        unittest.main()
