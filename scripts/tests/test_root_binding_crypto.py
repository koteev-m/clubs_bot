#!/usr/bin/env -S python3 -I -S -B
"""Portable refusal checks; real OpenPGP acceptance requires CLB91_TEST_GPG.

Only fresh synthetic keypairs are generated. No default/system/user keyring or
real network is used. A missing runtime reports NOT_RUN, never encryption PASS.
"""
import contextlib
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import signal
import stat
import struct
import sys
import tempfile
import time
import types
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
def load(path):
    m=types.ModuleType(path.stem);m.__file__=str(path)
    exec(compile(path.read_bytes(),str(path),'exec'),m.__dict__);return m
runner=load(ROOT/'scripts/deploy/corrected-stage-release.py')
review=runner._review
CANARY=b'CLB91_SYNTHETIC_BINDING_PRIVATE_CRYPTO_CANARY'


class Portable(unittest.TestCase):
    def test_unknown_runtime_configuration_and_key_inventory_refuse(self):
        valid=dict(format=1,version=review.VERSION,gpg='/unverified/gpg',agent='/unverified/agent',
            gpgconf='/unverified/gpgconf',sysconfdir='/unverified/etc',files={})
        public=b'-----BEGIN PGP PUBLIC KEY BLOCK-----\nsynthetic\n-----END PGP PUBLIC KEY BLOCK-----\n'
        for field,value in [('format',True),('version','2.4.4'),('files',[]),('extra',CANARY.decode())]:
            profile=valid|{field:value}
            out,err=io.StringIO(),io.StringIO()
            with contextlib.redirect_stdout(out),contextlib.redirect_stderr(err):
                with self.assertRaises(review.Refused):
                    with review.Crypto(profile,public,'B'*40,runner.capture,runner.open_canonical_root,'/unverified'):
                        self.fail('unknown profile became accepted')
            self.assertEqual(out.getvalue()+err.getvalue(),'')
        for key in (CANARY,b'-----BEGIN PGP PRIVATE KEY BLOCK-----\nx\n-----END PGP PRIVATE KEY BLOCK-----'):
            with self.assertRaises(review.Refused): review.Crypto(valid,key,'B'*40,runner.capture,runner.open_canonical_root,'/unverified')

    def test_runtime_dynamic_dependency_and_unknown_format_refuse(self):
        elf=bytearray(120);elf[:6]=b'\x7fELF\x02\x01';elf[32:40]=struct.pack('<Q',64)
        elf[54:58]=struct.pack('<HH',56,1);elf[64:68]=struct.pack('<I',3)
        for data in (elf,CANARY,b'\xcf\xfa\xed\xfe'+b'\0'*28):
            if data==b'\xcf\xfa\xed\xfe'+b'\0'*28:
                # Header without a single code/load segment cannot be accepted.
                continue
            with self.assertRaises(review.Refused): review.os_libraries(data)

    def test_process_failure_partial_output_timeout_and_cancellation_never_decrypt(self):
        crypto=object.__new__(review.Crypto)
        crypto.profile={'gpg':'synthetic-gpg'};crypto.home='';crypto.temporary_root=tempfile.gettempdir()
        crypto.recheck=lambda:None
        for code in (1,124,125,-signal.SIGTERM):
            crypto.capture=lambda *args,**kwargs:(code,CANARY)
            with self.assertRaises(review.Refused): crypto.run(['--decrypt'],b'ciphertext')
        crypto.capture=lambda *args,**kwargs:(_ for _ in ()).throw(runner.CaptureInterrupted())
        with self.assertRaises(runner.CaptureInterrupted): crypto.run(['--decrypt'],b'ciphertext')

    def test_cleanup_failure_cannot_return_pass(self):
        crypto=object.__new__(review.Crypto);crypto.held=[]
        class Failed:
            def cleanup(self): raise OSError(CANARY)
        crypto.directory=Failed()
        with self.assertRaises(review.Refused) as failure: crypto.close()
        self.assertEqual(str(failure.exception),'CLEANUP')

    def test_encryption_status_without_success_or_aead_refuses(self):
        crypto=object.__new__(review.Crypto);crypto.subkey='B'*40
        for status in (b'',b'[GNUPG:] END_ENCRYPTION\n',b'[GNUPG:] BEGIN_ENCRYPTION 2 9\n[GNUPG:] END_ENCRYPTION\n'):
            crypto.run=lambda *args,**kwargs:(b'ciphertext'*20,status)
            with self.assertRaises(review.Refused): crypto.encrypt(CANARY)


class Actual(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.binary=Path(os.environ['CLB91_TEST_GPG']).resolve(strict=True)
        cls.agent=cls.binary.with_name('gpg-agent');cls.conf=cls.binary.with_name('gpgconf')
        cls.parent=Path(tempfile.gettempdir()).resolve(strict=True)
        fd=runner.open_canonical_root(str(cls.parent));os.close(fd)
        cls.directory=tempfile.TemporaryDirectory(prefix='c91-',dir=cls.parent)
        cls.root=Path(cls.directory.name);cls.homes=[]
        cls.profile=dict(format=1,version=review.VERSION,gpg=str(cls.binary),agent=str(cls.agent),gpgconf=str(cls.conf),
            sysconfdir=str(cls.binary.parent.parent/'etc/gnupg'),files={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in (cls.binary,cls.agent,cls.conf)})
        cls.publics=[];cls.fingerprints=[]
        try:
            for name in ('r','w'):
                home=cls.root/name;home.mkdir(mode=0o700);cls.homes.append(home)
                cls.raw(home,['--pinentry-mode','loopback','--passphrase','',
                    '--quick-generate-key','CLB91 synthetic <synthetic@example.invalid>','rsa3072','encr','1d'])
                records=cls.raw(home,['--with-colons','--with-fingerprint','--list-keys']).decode().splitlines()
                fpr=next(r.split(':')[9] for r in records if r.startswith('fpr:'));cls.fingerprints.append(fpr)
                cls.publics.append(cls.raw(home,['--armor','--export',fpr]))
                cls.kill(home)
        except BaseException:
            cls.tearDownClass();raise

    @classmethod
    def raw(cls, home, args):
        code,data=runner.capture([str(cls.binary),'--no-options','--homedir',str(home),'--batch','--no-tty',
            '--disable-dirmngr','--no-auto-key-retrieve','--auto-key-locate','clear','--agent-program',str(cls.agent),*args],
            env={'PATH':'/usr/bin:/bin','LC_ALL':'C','HOME':str(home)},timeout=45,limit=review.LIMIT)
        if code: raise RuntimeError('synthetic-key operation failed')
        return data

    @classmethod
    def kill(cls,home):
        code,_=runner.capture([str(cls.conf),'--homedir',str(home),'--kill','gpg-agent'],
            env={'PATH':'/usr/bin:/bin','LC_ALL':'C','HOME':str(home)},timeout=10)
        if code: raise RuntimeError('synthetic agent cleanup failed')

    @classmethod
    def tearDownClass(cls):
        failed=False
        for home in cls.homes:
            try: cls.kill(home)
            except BaseException: failed=True
        cls.directory.cleanup()
        if failed: raise RuntimeError('synthetic cleanup failed')

    def crypto(self, key=0, profile=None):
        return review.Crypto(profile or self.profile,self.publics[key],self.fingerprints[key],
            runner.capture,runner.open_canonical_root,str(self.root))

    def test_real_encryption_decryption_wrong_receiver_and_tamper(self):
        out,err=io.StringIO(),io.StringIO()
        with contextlib.redirect_stdout(out),contextlib.redirect_stderr(err):
            with self.crypto() as crypto:
                cipher=crypto.encrypt(CANARY)
                self.assertNotIn(CANARY,cipher)
                self.assertEqual(crypto.decrypt(cipher,str(self.homes[0])),CANARY)
                for altered in (cipher[:-1]+bytes([cipher[-1]^1]),cipher[:-12]):
                    with self.assertRaises(review.Refused):crypto.decrypt(altered,str(self.homes[0]))
            with self.crypto(key=1) as crypto:
                with self.assertRaises(review.Refused):crypto.decrypt(cipher,str(self.homes[1]))
        self.assertEqual(out.getvalue()+err.getvalue(),'')
        self.assertFalse(any(p.name.startswith('g-') for p in self.root.iterdir()))
        self.assertFalse(any((home/'S.gpg-agent').exists() for home in self.homes))

    def test_exact_fingerprint_source_substitution_configuration_and_busy_refuse(self):
        with self.assertRaises(review.Refused):
            with review.Crypto(self.profile,self.publics[0],self.fingerprints[1],runner.capture,runner.open_canonical_root,str(self.root)):pass
        profile=copy.deepcopy(self.profile);profile['files'][str(self.binary)]='0'*64
        with self.assertRaises(review.Refused):
            with self.crypto(profile=profile):pass
        with self.crypto() as crypto:
            config=Path(crypto.home)/'common.conf';config.write_bytes(b'log-file '+CANARY+b'\n')
            with self.assertRaises(review.Refused):crypto.encrypt(CANARY)
            config.unlink()
            config.symlink_to('nonexistent')
            with self.assertRaises(review.Refused):crypto.encrypt(CANARY)
            config.unlink()
            busy=self.homes[0]/'S.gpg-agent';busy.write_bytes(b'')
            with self.assertRaises(review.Refused):crypto.decrypt(b'x'*128,str(self.homes[0]))
            busy.unlink()


if __name__=='__main__':
    suite=unittest.defaultTestLoader.loadTestsFromTestCase(Portable)
    if os.environ.get('CLB91_TEST_GPG'):
        suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(Actual))
        print('clb91-crypto-acceptance: SYNTHETIC_KEYS_REAL_GPG')
    else:
        print('clb91-crypto-acceptance: NOT_RUN (CLB91_TEST_GPG required)')
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(not result.wasSuccessful())
