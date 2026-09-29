import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
PATH=ROOT/'scripts/deploy/stage-package-plan-operation.py'
spec=importlib.util.spec_from_file_location('clb131_collector',PATH)
c=importlib.util.module_from_spec(spec)
spec.loader.exec_module(c)

class CollectorTests(unittest.TestCase):
    def test_frozen_target(self):
        target=json.loads((ROOT/'scripts/deploy/stage-package-plan-targets.json').read_text())
        self.assertEqual([(x['package'],x['version']) for x in target['request']],list(c.REQUEST))
        self.assertEqual(len(c.ACCEPTED_PATHS),191)
        self.assertNotIn('ruby-psych',[x[0] for x in c.REQUEST])
        self.assertNotIn('udev',[x[0] for x in c.REQUEST])
    def test_source_credential_redaction(self):
        self.assertEqual(c.safe_url('https://user:password@example.org/private?token=abc')['path'],'/REDACTED')
        self.assertTrue(c.safe_url('https://example.org/private?token=abc')['credential_redacted'])
        self.assertEqual(c.safe_url('https://archive.ubuntu.com/ubuntu')['path'],'/ubuntu')
    def test_source_malformed_and_duplicate(self):
        with patch.object(c,'files_in',return_value=['/etc/apt/sources.list.d/x.sources']), patch.object(c,'bounded_file',side_effect=[FileNotFoundError(),(b'Types: deb\nTypes: deb\nURIs: https://archive.ubuntu.com/ubuntu\nSuites: noble\n',None)]):
            with self.assertRaisesRegex(c.Refuse,'DUPLICATE_SOURCE_FIELD'):c.source_rows()
        with patch.object(c,'files_in',return_value=[]), patch.object(c,'bounded_file',return_value=(b'deb broken\n',None)):
            with self.assertRaisesRegex(c.Refuse,'MALFORMED_SOURCE'):c.source_rows()
    def test_plan_complete_and_expansion(self):
        p=c.parse_plan(b'Inst ruby (1:3.2 Ubuntu:24.04/noble [amd64])\nInst udev (255 [amd64])\nConf ruby (1:3.2)\nRemv oldthing [1]\n')
        self.assertEqual([x['action'] for x in p['actions']],['Inst','Inst','Conf','Remv'])
        self.assertEqual(p['systemd_udev_effects'][0]['package'],'udev')
        self.assertEqual(p['expanded_packages'],['oldthing','udev'])
        with self.assertRaisesRegex(c.Refuse,'PLAN_TOO_LARGE'):c.parse_plan(b'Inst x (1)\n'*4097)
        huge=b''.join(('Inst x%03d (1)\n'%i).encode() for i in range(129))
        with self.assertRaisesRegex(c.Refuse,'UNEXPECTED_PACKAGE_EXPANSION'):c.parse_plan(huge)
    def test_duplicate_package_record(self):
        sample=b'ruby\t1:3.2\tall\tii \tno\toptional\nruby\t1:3.2\tall\tii \tno\toptional\n'
        def fake_run(argv,*args):
            if argv[0]=='dpkg-query':return 0,sample,b''
            return 0,b'',b''
        with patch.object(c,'run',side_effect=fake_run):
            with self.assertRaisesRegex(c.Refuse,'DUPLICATE_PACKAGE_RECORD'):c.package_state(['ruby'])
    def test_script_categories(self):
        script='ldconfig\nupdate-initramfs -u\nupdate-alternatives\ndeb-systemd-helper\ndeb-systemd-invoke\ninvoke-rc.d\nsystemctl daemon-reload\nupdate-ca-certificates\npy3compile\n'
        hits={k for k,r in c.SIDE_EFFECTS.items() if r.search(script)}
        self.assertTrue({'ldconfig','initramfs','alternatives','systemd_helper','systemd_invoke','invoke_rc_d','systemctl','daemon_reload','certificates','python_bytecompile'}<=hits)
    def test_outside_path_bound(self):
        maps=b'0-1 r--p 0000 00:00 0 /opt/private/.env\n'
        with patch.object(c,'bounded_file',return_value=(maps,None)):
            result=c.outside_mapping([],set())
        self.assertEqual(result['path'],'REDACTED_UNSAFE_PATH')
        self.assertEqual(result['classification'],'UNKNOWN_REQUIRES_CONTRACT_DECISION')
    def test_no_mutation_or_private_path(self):
        source=PATH.read_text()
        for term in ('apt-get update','apt-get install','dpkg --configure','systemctl restart','/opt/clubs-bot-stage/','.env','/proc/self/environ','/proc/self/cmdline'):
            self.assertNotIn(term,source)
        self.assertIn("['apt-get','-s','-o','Debug::NoLocking=1'",source)
        self.assertIn("'Dir::Cache::pkgcache='",source)
        self.assertNotIn('shell=True',source)


class CollectorRegressionTests(unittest.TestCase):
    def test_source_port_identity_and_key_traversal(self):
        one=c.safe_url('https://mirror.example:8443/ubuntu')
        two=c.safe_url('https://mirror.example:9443/ubuntu')
        self.assertNotEqual(one,two)
        self.assertEqual(one['port'],8443)
        raw=b'Types: deb\nURIs: https://archive.ubuntu.com/ubuntu\nSuites: noble\nSigned-By: /etc/apt/keyrings/../../opt/private.gpg\n'
        with patch.object(c,'files_in',return_value=['/etc/apt/sources.list.d/x.sources']), patch.object(c,'bounded_file',side_effect=[FileNotFoundError(),(raw,None)]):
            with self.assertRaisesRegex(c.Refuse,'UNSAFE_KEY_PATH'):c.source_rows()
    def test_cached_index_hash_and_signed_relation(self):
        from types import SimpleNamespace
        prefix='archive.ubuntu.com_ubuntu_dists_noble_'
        release=prefix+'InRelease'
        package=prefix+'main_binary-amd64_Packages.lz4'
        digest='a'*64
        release_body=('Origin: Ubuntu\nSuite: noble\nSHA256:\n '+digest+' 4 main/binary-amd64/Packages\n').encode()
        def fake_files(directory,suffixes,count=32):return ['/var/lib/apt/lists/'+release,'/var/lib/apt/lists/'+package]
        def fake_stat(path,follow_symlinks=False):return SimpleNamespace(st_mode=0o100644,st_size=4,st_mtime_ns=123)
        with patch.object(c,'files_in',side_effect=fake_files),patch.object(c.os,'stat',side_effect=fake_stat),patch.object(c,'bounded_file',return_value=(release_body,None)),patch.object(c,'file_digest',return_value='b'*64),patch.object(c,'decompressed_index_digest',return_value=(digest,4)):
            rows=c.index_rows([])
        pkg=next(x for x in rows if x['file']==package)
        self.assertEqual(pkg['sha256'],'b'*64)
        self.assertEqual(pkg['signed_relation'],'MATCHED_RELEASE_SIGNATURE_UNKNOWN')
        self.assertEqual(pkg['release_file'],release)
    def test_index_hash_limit_and_policy_origin(self):
        from tempfile import TemporaryDirectory
        import hashlib
        with TemporaryDirectory() as folder:
            path=Path(folder)/'Packages'
            path.write_bytes(b'abc')
            self.assertEqual(c.file_digest(str(path),3),hashlib.sha256(b'abc').hexdigest())
            with self.assertRaisesRegex(c.Refuse,'UNSAFE_OR_LARGE_INDEX'):c.file_digest(str(path),2)
        raw=b'ruby:\n  Installed: (none)\n  Candidate: 1:3.2\n  Version table:\n     1:3.2 500\n        500 https://mirror.example:8443/ubuntu noble/main amd64 Packages\n'
        with patch.object(c,'run',return_value=(0,raw,b'')):
            result=c.policy(['ruby'])
        self.assertEqual(result['ruby']['versions'][0]['origins'][0]['uri']['port'],8443)

class BoundsRegressionTests(unittest.TestCase):
    def test_decompressed_index_bound_propagates(self):
        from types import SimpleNamespace
        release='archive.ubuntu.com_ubuntu_dists_noble_InRelease'
        package='archive.ubuntu.com_ubuntu_dists_noble_main_binary-amd64_Packages.lz4'
        def files(directory,suffixes,count=32):return ['/var/lib/apt/lists/'+release,'/var/lib/apt/lists/'+package]
        st=SimpleNamespace(st_mode=0o100644,st_size=4,st_mtime_ns=123)
        with patch.object(c,'files_in',side_effect=files),patch.object(c.os,'stat',return_value=st),patch.object(c,'bounded_file',return_value=(b'Origin: Ubuntu\n',st)),patch.object(c,'file_digest',return_value='a'*64),patch.object(c,'decompressed_index_digest',side_effect=c.Refuse('INDEX_DECOMPRESSED_TOO_LARGE')):
            with self.assertRaisesRegex(c.Refuse,'INDEX_DECOMPRESSED_TOO_LARGE'):c.index_rows([])

if __name__=='__main__':unittest.main()
