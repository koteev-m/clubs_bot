import hashlib
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
        manifest=(ROOT/'scripts/deploy/stage-compose-env-semantic-runtime.json').read_bytes()
        self.assertEqual(target['manifest_sha256'],hashlib.sha256(manifest).hexdigest())
        self.assertEqual(set(c.ACCEPTED_PATHS),set(json.loads(manifest)['files']))
        self.assertNotIn('ruby-psych',[x[0] for x in c.REQUEST])
        self.assertNotIn('udev',[x[0] for x in c.REQUEST])
    def test_openssl_security_targets_are_explicit_exact_frozen_debs(self):
        target=json.loads((ROOT/'scripts/deploy/stage-package-plan-targets.json').read_text())
        expected={
            'libssl3t64':'219f43b1cd836a4da550938db5fda93160d269d39a4edcf1f1ce698a470db797',
            'openssl':'675b84971ffd4467707008c25ef7520f90ea7c23ef27b7a76b0dccf1d7c4dc3f',
        }
        self.assertEqual(target['task'],'CLB-157')
        self.assertEqual(target['main'],c.MAIN)
        self.assertEqual(c.MAIN,'0c934da1b76ad6916feaf2bb52d88a9b42ca2f27')
        self.assertEqual(len(c.REQUEST),21)
        self.assertEqual(len({name for name,_ in c.REQUEST}),21)
        for name,digest in expected.items():
            with self.subTest(package=name):
                self.assertEqual([version for package,version in c.REQUEST if package==name],
                                 ['3.0.13-0ubuntu3.16'])
                rows=[row for row in target['request'] if row['package']==name]
                self.assertEqual(len(rows),1)
                self.assertEqual(rows[0]['architecture'],'amd64')
                self.assertEqual(rows[0]['expected_transition'],'version_transition')
                self.assertEqual(rows[0]['trusted_reference_source'],{
                    'kind':'frozen_ubuntu_deb_lock',
                    'archive_path':f'pool/main/o/openssl/{name}_3.0.13-0ubuntu3.16_amd64.deb',
                    'sha256':digest,
                })
        self.assertEqual(next(row for row in target['request'] if row['package']=='openssl')['target_runtime_files'],[])
        plan=c.parse_plan(b'Inst libssl3t64 [3.0.13-0ubuntu3.15] (3.0.13-0ubuntu3.16 Ubuntu:24.04/noble-security [amd64])\nInst openssl [3.0.13-0ubuntu3.15] (3.0.13-0ubuntu3.16 Ubuntu:24.04/noble-security [amd64])\n')
        self.assertEqual(plan['expanded_packages'],[])
        self.assertEqual([row['package'] for row in plan['high_impact_effects']],['libssl3t64','openssl'])

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


class PackageStateIdentityTests(unittest.TestCase):
    # Actual Noble amd64 dpkg-query output for a fixed-request Multi-Arch: same
    # package. Qualification is dpkg identity syntax, not package expansion.
    LIBC6 = b'libc6:amd64\t2.39-0ubuntu8.9\tamd64\tii \tno\toptional\n'

    def state(self, output, names=('libc6',), holds=b'', code=0):
        fmt='${binary:Package}\t${Version}\t${Architecture}\t${db:Status-Abbrev}\t${Essential}\t${Priority}\n'
        def command(argv, *bounds):
            if argv == ['dpkg-query','-W','-f='+fmt,*names]:
                self.assertEqual(bounds,(65536,20))
                return code,output,b''
            self.assertEqual(argv,['apt-mark','showhold'])
            self.assertEqual(bounds,(32768,12))
            return 0,holds,b''
        with patch.object(c,'run',side_effect=command):
            return c.package_state(list(names))

    def test_expected_unqualified_native_package(self):
        rows=self.state(self.LIBC6.replace(b'libc6:amd64',b'libc6'))
        self.assertEqual(rows,{'libc6':dict(version='2.39-0ubuntu8.9',architecture='amd64',
            status_abbrev='ii ',essential='no',priority='optional',held=False)})

    def test_expected_native_qualified_package_uses_requested_key(self):
        self.assertIn('libc6',dict(c.REQUEST))
        rows=self.state(self.LIBC6)
        self.assertEqual(rows,self.state(self.LIBC6.replace(b'libc6:amd64',b'libc6')))
        self.assertNotIn('libc6:amd64',rows)

    def test_unqualified_all_and_partial_missing_packages_remain_observed(self):
        rows=self.state(b'ruby\t1:3.2~ubuntu1\tall\tii \tno\toptional\n',
                        names=('ruby','openssl'),code=1)
        self.assertEqual(rows['ruby']['architecture'],'all')
        self.assertEqual(rows['ruby']['version'],'1:3.2~ubuntu1')
        self.assertIsNone(rows['openssl']['version'])
        self.assertEqual(set(rows),{'ruby','openssl'})

    def test_unexpected_package_cannot_expand_requested_set(self):
        for name in (b'other',b'other:amd64'):
            with self.subTest(name=name),self.assertRaisesRegex(c.Refuse,'UNEXPECTED_PACKAGE_RECORD'):
                self.state(self.LIBC6.replace(b'libc6:amd64',name))

    def test_wrong_or_nonconcrete_architecture_qualifier_refused(self):
        for arch in (b'arm64',b'i386',b'all',b'any',b'native'):
            for field in (arch,b'amd64'):
                raw=self.LIBC6.replace(b'libc6:amd64',b'libc6:'+arch).replace(b'\tamd64\t',b'\t'+field+b'\t')
                with self.subTest(qualifier=arch,field=field),self.assertRaisesRegex(c.Refuse,'UNEXPECTED_PACKAGE_RECORD'):
                    self.state(raw)

    def test_native_qualifier_must_match_architecture_field(self):
        for arch in (b'arm64',b'all',b''):
            with self.subTest(arch=arch),self.assertRaisesRegex(c.Refuse,'UNEXPECTED_PACKAGE_RECORD'):
                self.state(self.LIBC6.replace(b'\tamd64\t',b'\t'+arch+b'\t'))

    def test_duplicate_native_and_unqualified_collision_in_both_orders(self):
        plain=self.LIBC6.replace(b'libc6:amd64',b'libc6')
        for output in (plain+plain,self.LIBC6+self.LIBC6,plain+self.LIBC6,self.LIBC6+plain):
            with self.subTest(output=output),self.assertRaisesRegex(c.Refuse,'DUPLICATE_PACKAGE_RECORD'):
                self.state(output)

    def test_malformed_name_and_record_shape_remain_refused(self):
        for output in (self.LIBC6.replace(b'libc6:amd64',b'libc6:amd64:amd64'),
                       self.LIBC6.replace(b'libc6:amd64',b'libc6/amd64'),
                       self.LIBC6.replace(b'\toptional',b'')):
            with self.subTest(output=output),self.assertRaisesRegex(c.Refuse,'DUPLICATE_PACKAGE_RECORD'):
                self.state(output)

    def test_record_line_bound_remains_fail_closed(self):
        with self.assertRaisesRegex(c.Refuse,'UNEXPECTED_PACKAGE_RECORD'):
            self.state(self.LIBC6.replace(b'optional',b'x'*513))

    def test_holds_match_same_native_identity_without_foreign_aliasing(self):
        for output in (self.LIBC6,self.LIBC6.replace(b'libc6:amd64',b'libc6')):
            for holds,expected in ((b'libc6\n',True),(b'libc6:amd64\n',True),
                                   (b'libc6:arm64\n',False),(b'',False)):
                with self.subTest(output=output,holds=holds):
                    self.assertEqual(self.state(output,holds=holds)['libc6']['held'],expected)
        rows=self.state(b'ruby\t1:3.2~ubuntu1\tall\tii \tno\toptional\n',
                        names=('ruby',),holds=b'ruby:amd64\n')
        self.assertFalse(rows['ruby']['held'])

    def test_invalid_holds_and_fatal_query_remain_refused(self):
        plain=self.LIBC6.replace(b'libc6:amd64',b'libc6')
        with self.assertRaisesRegex(c.Refuse,'DPKG_QUERY_FAILED'):
            self.state(plain,code=2)
        for holds in (b'libc6/amd64\n',b'libc6:amd64:all\n'):
            with self.subTest(holds=holds),self.assertRaisesRegex(c.Refuse,'UNSAFE_HOLDS'):
                self.state(plain,holds=holds)


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


class ReleaseReferenceTests(unittest.TestCase):
    PREFIX = 'archive.ubuntu.com_ubuntu_dists_noble_'
    DIGEST = hashlib.sha256(b'data').hexdigest()

    @classmethod
    def entry(cls, path, digest=None, size=4):
        return f' {digest or cls.DIGEST} {size} {path}\n'

    @classmethod
    def large_release(cls):
        # Mixed architectures, components and compression alternatives, with the
        # locally needed uncompressed reference deliberately beyond old line 256.
        paths = [f'component{i}/binary-{arch}/Packages{suffix}'
                 for i in range(40) for arch in ('amd64', 'arm64')
                 for suffix in ('', '.gz', '.xz', '.bz2')]
        paths += ['main/binary-amd64/Packages', 'universe/binary-amd64/Packages']
        return ('Origin: Ubuntu\nSuite: noble\nSHA256:\n' +
                ''.join(cls.entry(path) for path in paths) +
                '-----BEGIN PGP SIGNATURE-----\n\nsynthetic-signature\n-----END PGP SIGNATURE-----\n').encode()

    @classmethod
    def indexes(cls, body, components=('main',), signature='verified', suffix='.lz4', second_release=False):
        from tempfile import TemporaryDirectory
        from types import SimpleNamespace
        from contextlib import ExitStack
        key='/usr/share/keyrings/ubuntu-archive-keyring.gpg'
        original_stat=c.os.stat
        def metadata(path, **kwargs):
            if path == key:
                return SimpleNamespace(st_mode=0o100644, st_size=4, st_uid=0)
            return original_stat(path, **kwargs)
        with TemporaryDirectory() as directory, ExitStack() as stack:
            root=Path(directory)
            release=root/(cls.PREFIX+'InRelease'); release.write_bytes(body)
            paths=[str(release)]
            if second_release:
                other=root/(cls.PREFIX+'Release'); other.write_bytes(body); paths.append(str(other))
            for component in components:
                path=root/(cls.PREFIX+component+'_binary-amd64_Packages'+suffix)
                path.write_bytes(b'data'); paths.append(str(path))
            stack.enter_context(patch.object(c, 'files_in', return_value=sorted(paths)))
            stack.enter_context(patch.object(c.os, 'stat', side_effect=metadata))
            stack.enter_context(patch.object(c, 'decompressed_index_digest', return_value=(cls.DIGEST,4)))
            command=stack.enter_context(patch.object(c, 'run', return_value=(
                0 if signature=='verified' else 1, b'[GNUPG:] VALIDSIG '+b'A'*40+b'\n', b'')))
            rows=c.index_rows([] if signature=='unknown' else [{'signed_by':key}])
            if signature!='unknown':
                command.assert_called_once_with(['gpgv','--status-fd','1','--keyring',key,str(release)],32768,12)
            else:command.assert_not_called()
            return rows

    def test_large_release_all_local_signed_relations_and_bounded_evidence(self):
        rows=self.indexes(self.large_release(), ('main','universe'))
        release=next(row for row in rows if row['file'].endswith('InRelease'))
        self.assertEqual(release['amd64_package_refs'],[
            {'path':component+'/binary-amd64/Packages','sha256':self.DIGEST,'size':4}
            for component in ('main','universe')])
        packages=[row for row in rows if 'signed_relation' in row]
        self.assertEqual(len(packages),2)
        for row in packages:
            self.assertEqual(row['signed_relation'],'MATCHED_VERIFIED_RELEASE')
            self.assertEqual(row['release_file'],release['file'])
            self.assertEqual(row['uncompressed_sha256'],self.DIGEST)
        self.assertLess(len(json.dumps(rows)),4096)

    # Frozen representative entry from https://archive.ubuntu.com/ubuntu/dists/noble/Release
    # (25 Apr 2024). Package bytes/signature remain synthetic and offline.
    ICON_ENTRY = (b' 650ebdd9cef3bfb3af47d512d1a96a788d2cf636b94c19c6b2870d203931de4d'
                  b'            59904 main/dep11/icons-128x128@2.tar\n')

    def test_ubuntu_dep11_at2_scanned_not_retained_with_signed_package_relation(self):
        package=self.entry('main/binary-amd64/Packages').encode()
        for entries in (self.ICON_ENTRY+package, package+self.ICON_ENTRY):
            with self.subTest(entries=entries):
                body=b'Origin: Ubuntu\nSuite: noble\nCodename: noble\nSHA256:\n'+entries
                rows=self.indexes(body)
                release=next(row for row in rows if row['file'].endswith('InRelease'))
                self.assertEqual(release['amd64_package_refs'],[
                    {'path':'main/binary-amd64/Packages','sha256':self.DIGEST,'size':4}])
                pkg=next(row for row in rows if 'signed_relation' in row)
                self.assertEqual(pkg['signed_relation'],'MATCHED_VERIFIED_RELEASE')
                self.assertEqual(pkg['release_file'],release['file'])
                self.assertEqual(pkg['uncompressed_sha256'],self.DIGEST)
                with self.assertRaisesRegex(c.Refuse,'MALFORMED_INDEX_HASH'):
                    self.indexes(body+b' malformed continuation\n')
                with self.assertRaisesRegex(c.Refuse,'MALFORMED_INDEX_HASH'):
                    self.indexes(body+b'SHA256:\n'+package)
                with self.assertRaisesRegex(c.Refuse,'INDEX_RELATION_AMBIGUOUS'):
                    self.indexes(body+package)

    def test_at_paths_preserve_strict_entry_grammar_and_bounds(self):
        package=self.entry('main/binary-amd64/Packages')
        icon='main/dep11/icons-128x128@2.tar'
        invalid_paths=(icon+'?token=secret', icon+'?query',
                       'https://user@example.org/'+icon, icon+'#fragment',
                       icon+'=value', icon+'%20', icon+' extra', icon+'\textra',
                       icon+'\x00', icon+'\x01', icon+'\x7f', icon+'\rhidden',
                       icon+'\nhidden', '@'+'x'*180)
        tails=[self.entry(path) for path in invalid_paths]
        tails += [self.entry(icon,digest=digest) for digest in
                  ('a'*63,'a'*65,'A'*64,'g'*64)]
        tails += [self.entry(icon,size=size) for size in ('-1','abc','1234567890123456')]
        for tail in tails:
            with self.subTest(tail=tail),self.assertRaisesRegex(c.Refuse,'MALFORMED_INDEX_HASH'):
                self.indexes(b'SHA256:\n'+package.encode()+self.ICON_ENTRY+tail.encode())
        # Exact old boundaries still accept; the unrelated @ path is not evidence.
        body=('SHA256:\n'+self.entry('@'+'x'*179,size='9'*15)+package).encode()
        self.assertEqual(self.indexes(body)[1]['signed_relation'],'MATCHED_VERIFIED_RELEASE')

    def test_existing_small_release_signature_mismatch_and_missing_relation(self):
        for count in (1,32):
            body=('SHA256:\n'+''.join(self.entry(f'c{i}/binary-amd64/Packages') for i in range(count))).encode()
            components=tuple(f'c{i}' for i in range(count))
            for signature,expected in (('verified','MATCHED_VERIFIED_RELEASE'),('unknown','MATCHED_RELEASE_SIGNATURE_UNKNOWN'),('invalid','MATCHED_RELEASE_SIGNATURE_UNKNOWN')):
                with self.subTest(count=count,signature=signature):
                    rows=self.indexes(body,components,signature=signature)
                    self.assertEqual(len(rows[0]['amd64_package_refs']),count)
                    self.assertEqual([r['signed_relation'] for r in rows[1:]],[expected]*count)
        for digest,size in (('b'*64,4),(self.DIGEST,5)):
            rows=self.indexes(('SHA256:\n'+self.entry('main/binary-amd64/Packages',digest,size)).encode())
            self.assertEqual(rows[1]['signed_relation'],'MISMATCH_SIGNED_REFERENCE')
        for body in (b'Origin: Ubuntu\n',('SHA256:\n'+self.entry('other/binary-amd64/Packages')).encode()):
            self.assertEqual(self.indexes(body)[1]['signed_relation'],'UNKNOWN')

    def test_malformed_entries_including_unretained_tail_fail_closed(self):
        good=self.entry('main/binary-amd64/Packages')
        tails=(' bad 4 unrelated/path\n', ' '+self.DIGEST+' -1 unrelated/path\n',
               ' '+self.DIGEST+' 4 private?token=x\n', ' malformed\n',
               self.DIGEST+' 4 main/binary-amd64/Packages\n', ' \n',
               ' '+self.DIGEST+' 1234567890123456 path\n',
               ' '+self.DIGEST+' 4 '+'x'*181+'\n', ' '+'x'*65536+'\n')
        for tail in tails:
            for padding in (0,300):
                body=('SHA256:\n'+good+self.entry('other/binary-arm64/Packages')*padding+tail).encode()
                with self.subTest(tail=tail,padding=padding),self.assertRaisesRegex(c.Refuse,'MALFORMED_INDEX_HASH'):
                    self.indexes(body)
        for body in ('SHA256:\n','SHA256: garbage\n'+good,'SHA256:\n'+good+'SHA256:\n'+good):
            with self.subTest(body=body),self.assertRaisesRegex(c.Refuse,'MALFORMED_INDEX_HASH'):
                self.indexes(body.encode())

    def test_full_section_boundary_and_no_final_newline(self):
        for count in (255,256,257,1024):
            body='SHA256:\n'+self.entry('other/binary-arm64/Packages')*count+self.entry('main/binary-amd64/Packages')
            with self.subTest(count=count):
                self.assertEqual(self.indexes(body.rstrip('\n').encode())[1]['signed_relation'],'MATCHED_VERIFIED_RELEASE')

    def test_blank_signature_separator_does_not_truncate_section(self):
        ref=self.entry('main/binary-amd64/Packages')
        for padding in (0,300):
            prefix='SHA256:\n'+ref+self.entry('other/binary-arm64/Packages')*padding+'\n'
            for newline in ('\n','\r\n'):
                body=(prefix+'-----BEGIN PGP SIGNATURE-----\n').replace('\n',newline).encode()
                with self.subTest(padding=padding,newline=newline):
                    self.assertEqual(self.indexes(body)[1]['signed_relation'],'MATCHED_VERIFIED_RELEASE')
            for tail,reason in ((' malformed\n','MALFORMED_INDEX_HASH'),(ref,'INDEX_RELATION_AMBIGUOUS')):
                with self.subTest(padding=padding,reason=reason),self.assertRaisesRegex(c.Refuse,reason):
                    self.indexes((prefix+tail).encode())
        for tail in ('','-----BEGIN PGP SIGNATURE-----\n'):
            with self.assertRaisesRegex(c.Refuse,'MALFORMED_INDEX_HASH'):
                self.indexes(('SHA256:\n\n'+tail).encode())

    def test_near_file_bound_valid_section_is_fully_scanned(self):
        last=self.entry('main/binary-amd64/Packages')
        line=self.entry('other/binary-arm64/Packages')
        count=(2097152-len('SHA256:\n')-len(last))//len(line)
        body=('SHA256:\n'+line*count+last).encode()
        self.assertGreater(len(body),2097000)
        self.assertEqual(self.indexes(body)[1]['signed_relation'],'MATCHED_VERIFIED_RELEASE')

    def test_oversized_section_and_retained_overflow_fail_closed(self):
        line=self.entry('other/binary-arm64/Packages')
        body=('SHA256:\n'+line*(2097152//len(line)+1)).encode()
        with self.assertRaisesRegex(c.Refuse,'UNSAFE_OR_LARGE_FILE'):self.indexes(body)
        components=tuple(f'c{i}' for i in range(33))
        body=('SHA256:\n'+''.join(self.entry(x+'/binary-amd64/Packages') for x in components)).encode()
        with self.assertRaisesRegex(c.Refuse,'INDEX_REFS_LIMIT'):self.indexes(body,components)
        with patch.object(c,'START',c.time.monotonic()-101):
            with self.assertRaisesRegex(c.Refuse,'TIME_LIMIT'):self.indexes(self.large_release())

    def test_duplicate_collision_compressed_and_multiple_release_ambiguity(self):
        ref=self.entry('main/binary-amd64/Packages')
        for body,kwargs in (
            ('SHA256:\n'+ref*2,{}),
            ('SHA256:\n'+ref+self.entry('other/binary-arm64/Packages')*300+ref,{}),
            ('SHA256:\n'+ref+self.entry('main/binary-amd64/Packages.lz4'),{}),
            ('SHA256:\n'+ref,{'second_release':True}),
            ('SHA256:\n'+self.entry('main/sub/binary-amd64/Packages')+self.entry('main_sub/binary-amd64/Packages'),{'components':('main_sub',)}),
        ):
            with self.subTest(kwargs=kwargs,body_length=len(body)),self.assertRaisesRegex(c.Refuse,'INDEX_RELATION_AMBIGUOUS'):
                self.indexes(body.encode(),**kwargs)

    def test_compressed_only_reference_does_not_false_match(self):
        body=('SHA256:\n'+self.entry('main/binary-amd64/Packages.gz')).encode()
        self.assertEqual(self.indexes(body)[1]['signed_relation'],'UNKNOWN')
        body=('SHA256:\n'+self.entry('main/binary-amd64/Packages')).encode()
        self.assertEqual(self.indexes(body,suffix='')[1]['signed_relation'],'MATCHED_VERIFIED_RELEASE')


if __name__=='__main__':unittest.main()
