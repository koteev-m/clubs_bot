#!/usr/bin/env python3
"""Architecture-independent local recipe controls; no Docker/network/native exec."""
import ast,hashlib,importlib.util,json,pathlib,shutil,subprocess,sys,tempfile,unittest
P=pathlib.Path;HERE=P(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('native_inputs',HERE/'prepare-native-inputs.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class RecipeTests(unittest.TestCase):
 def test_exact_sources(self):self.assertEqual(len(m.verify_sources()),12)
 def test_each_source_and_ledger_drift_refused(self):
  with tempfile.TemporaryDirectory() as td:
   ref=P(td)/'reference';shutil.copytree(HERE/'reference',ref)
   for name in json.loads((ref/'SOURCE-PINS.json').read_bytes()):
    raw=(ref/name).read_bytes();(ref/name).write_bytes(raw+b'\n')
    with self.subTest(name=name),self.assertRaisesRegex(ValueError,'source_identity'):m.verify_sources(ref)
    (ref/name).write_bytes(raw)
   (ref/'SOURCE-PINS.json').write_bytes(b'{}')
   with self.assertRaisesRegex(ValueError,'source_pins_identity'):m.verify_sources(ref)
 def test_native_manual_gate(self):
  m.native_guard('Linux','x86_64','X64','workflow_dispatch')
  for args in [('Darwin','arm64','X64','workflow_dispatch'),('Linux','aarch64','X64','workflow_dispatch'),('Linux','x86_64','ARM64','workflow_dispatch'),('Linux','x86_64','X64','push')]:
   with self.subTest(args=args),self.assertRaisesRegex(ValueError,'native_manual'):m.native_guard(*args)
 def test_builder_explicit_binding_no_source_rewrite(self):
  argv=m.builder_argv(P('/synthetic/build.py'),P('/synthetic/output space'),'sha256:'+'a'*64,P('/synthetic/input space.deb'))
  self.assertEqual(argv[4:],['/synthetic/build.py','/synthetic/output space','--labels','native','--image','sha256:'+'a'*64,'--archive','/synthetic/input space.deb'])
  self.assertFalse(hasattr(m,'adapt_builder'))
  with self.assertRaisesRegex(ValueError,'image_identity'):m.builder_argv('x','y','tag','/synthetic/input.deb')
  for path in ('relative.deb','/input,ro.deb','/safe/../input.deb'):
   with self.subTest(path=path),self.assertRaisesRegex(ValueError,'archive_path'):m.builder_argv('x','y','sha256:'+'a'*64,path)
  spec=importlib.util.spec_from_file_location('builder',HERE/'reference/build.py');builder=importlib.util.module_from_spec(spec);spec.loader.exec_module(builder)
  with tempfile.TemporaryDirectory() as td:
   path=P(td).resolve()/'bad.deb';path.write_bytes(b'not an archive')
   with self.assertRaisesRegex(ValueError,'archive_identity'):builder.bound_inputs('sha256:'+'a'*64,path)
   link=P(td).resolve()/'link.deb';link.symlink_to(path)
   with self.assertRaisesRegex(ValueError,'archive_symlink'):builder.bound_inputs('sha256:'+'a'*64,link)
   dest=P(td)/'must-not-exist'
   q=subprocess.run([sys.executable,'-I','-S','-B',str(HERE/'reference/build.py'),str(dest)],capture_output=True)
   self.assertEqual(q.returncode,2);self.assertFalse(dest.exists())
 def test_entrypoint_stops_before_writes_without_permission(self):
  with tempfile.TemporaryDirectory() as td:
   dest=P(td)/'new'
   p=subprocess.run([sys.executable,'-I','-S','-B',str(HERE/'prepare-native-inputs.py'),str(dest)],capture_output=True)
   self.assertEqual(p.returncode,1);self.assertFalse(dest.exists());self.assertEqual(json.loads(p.stdout)['reason'],'future_permission_not_indicated')
 def test_wrong_runtime_fails(self):
  with tempfile.TemporaryDirectory() as td:
   p=P(td);(p/'rootfs.tar').write_bytes(b'wrong')
   with self.assertRaisesRegex(ValueError,'rootfs_identity'):m.checked_final(p)
 def test_owned_container_cleanup_and_refusal(self):
  with tempfile.TemporaryDirectory() as td:
   cidfile=P(td)/'cid';cid='b'*64;token='a'*32;cidfile.write_text(cid)
   seen=[]
   def control(args):
    seen.append(args)
    if '--filter' in args:return cid if 'label=' in args[-1] else ''
    if 'inspect' in args:return json.dumps({'clb91.native-input':token})
    return ''
   self.assertEqual(m.cleanup_owned(cidfile,token,control)['status'],'confirmed_absent')
   self.assertEqual(sum('rm' in x for x in seen),1)
   seen.clear()
   def wrong(args):
    seen.append(args)
    if 'inspect' in args:return json.dumps({'clb91.native-input':'foreign'})
    return cid
   with self.assertRaisesRegex(ValueError,'label_mismatch'):m.cleanup_owned(cidfile,token,wrong)
   self.assertFalse(any('rm' in x for x in seen))
 def test_workflow_only_manual_job_changed(self):
  raw=(HERE/'tests.yml.candidate').read_bytes()
  prefix=raw.split(b'\n  amd64-runtime-prototype:\n')[0]
  self.assertEqual(hashlib.sha256(prefix).hexdigest(),'010df3d58cf1ab89ef3978c517f3e6c124649cb656f918c4674aa30c2a319a60')
  q=subprocess.run(['ruby','-ryaml','-rjson','-e','puts JSON.generate(YAML.safe_load(STDIN.read, aliases: true))'],input=raw,capture_output=True,check=True)
  doc=json.loads(q.stdout);self.assertEqual(set(doc['jobs']),{'unit-tests','integration-tests','amd64-runtime-prototype'})
  j=doc['jobs']['amd64-runtime-prototype'];self.assertEqual(j['if'],"github.event_name == 'workflow_dispatch'");self.assertEqual(j['runs-on'],'ubuntu-24.04');self.assertEqual(j['timeout-minutes'],75);self.assertEqual(j['permissions'],{'contents':'read'});self.assertNotIn('environment',j)
  self.assertEqual(j['steps'][0]['uses'],'actions/checkout@692973e3d937129bcbf40652eb9f2f61becf3332');self.assertEqual(j['steps'][-1]['uses'],'actions/upload-artifact@b4b15b8c7c6ac21ea08fcf65892d2ee8f75cf882')
  text=raw[len(prefix):].decode()
  for forbidden in ('secrets.','environment:','continue-on-error','privileged','unconfined','docker.sock','gh workflow','apt-get'):
   self.assertNotIn(forbidden,text)
  self.assertIn('--authorized-external-preparation',text);self.assertIn('--authorized-native-test',text)
 def test_artifact_allowlist_bounds_and_symlink(self):
  spec=importlib.util.spec_from_file_location('native_artifacts',HERE/'check-artifacts.py');a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)
  with tempfile.TemporaryDirectory() as td:
   root=P(td);base=root/'clb91-native-adapter/evidence';base.mkdir(parents=True)
   (base/'result.json').write_text('{"verdict":"BLOCKED"}')
   self.assertEqual(a.check(root)['checked_files'],1)
   (base/'extra.json').write_text('{}')
   with self.assertRaisesRegex(ValueError,'allowlist'):a.check(root)
   (base/'extra.json').unlink();(base/'compiler.json').symlink_to(base/'result.json')
   with self.assertRaisesRegex(ValueError,'symlink'):a.check(root)
   (base/'compiler.json').unlink();(base/'result.json').write_bytes(b' '*1048577)
   with self.assertRaisesRegex(ValueError,'bound'):a.check(root)
if __name__=='__main__':
 r=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(RecipeTests));print(json.dumps({'tests':r.testsRun,'failures':len(r.failures),'errors':len(r.errors),'skips':len(r.skipped),'verdict':'PASS' if r.wasSuccessful() else 'FAIL','external_actions':0,'scope':'local syntax/identity/native-prerequisite/adaptation controls; no native/namespace execution'})+'\n');raise SystemExit(not r.wasSuccessful())
