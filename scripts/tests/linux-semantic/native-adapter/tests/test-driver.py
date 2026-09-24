#!/usr/bin/env python3
"""Local architecture-independent controls; no namespaces/sudo/network."""
import argparse,importlib.util,json,pathlib,struct,subprocess,sys,tempfile,unittest
P=pathlib.Path;HERE=P(__file__).resolve().parent
s=importlib.util.spec_from_file_location('driver',HERE/'native-driver.py');d=importlib.util.module_from_spec(s);s.loader.exec_module(d)
class Tests(unittest.TestCase):
 def test_sources(self):self.assertEqual(d.source_identity()['manifest'],'68fc3b06b2198b06f1583c0bb1157669c4df6d8c9548934ed3c79315d6e82649')
 def test_guard(self):
  d.native_guard(True,'Linux','x86_64',{'RUNNER_ARCH':'X64','GITHUB_EVENT_NAME':'workflow_dispatch'},1001)
  for auth,system,arch,event,uid in [(False,'Linux','x86_64','workflow_dispatch',1001),(True,'Darwin','arm64','workflow_dispatch',1001),(True,'Linux','aarch64','workflow_dispatch',1001),(True,'Linux','x86_64','push',1001),(True,'Linux','x86_64','workflow_dispatch',0)]:
   with self.subTest(system=system,arch=arch,event=event),self.assertRaises(d.Refused):d.native_guard(auth,system,arch,{'RUNNER_ARCH':'X64','GITHUB_EVENT_NAME':event},uid)
 def test_no_permission_no_writes(self):
  with tempfile.TemporaryDirectory() as t:
   p=P(t)/'absent';r=subprocess.run([sys.executable,'-I','-S','-B',str(HERE/'native-driver.py'),'--runtime-tar',str(p),'--work',str(p)],capture_output=True)
   self.assertEqual(r.returncode,1);self.assertEqual(json.loads(r.stdout)['primary'],'future_permission_not_indicated');self.assertFalse(p.exists())
 def test_static_elf(self):
  raw=bytearray(120);raw[:7]=b'\x7fELF\x02\x01\x01';struct.pack_into('<HHIQQQIHHHHHH',raw,16,2,62,1,0,64,0,0,64,56,1,0,0,0);struct.pack_into('<IIQQQQQQ',raw,64,1,5,0,0,0,120,120,4096)
  self.assertEqual(d.elf_static(raw)['machine'],'x86_64')
  for offset,value in [(18,183),(64,2),(64,3)]:
   bad=bytearray(raw);struct.pack_into('<H',bad,offset,value)
   with self.subTest(offset=offset,value=value),self.assertRaises(d.Refused):d.elf_static(bad)
  with self.assertRaises(d.Refused):d.elf_static(raw[:100])
 def test_bounded_read_symlink(self):
  with tempfile.TemporaryDirectory() as t:
   p=P(t)/'x';p.write_bytes(b'abc');q=P(t)/'link';q.symlink_to(p)
   self.assertEqual(d.read_exact(p,3),b'abc')
   with self.assertRaises(d.Refused):d.read_exact(p,2)
   with self.assertRaises(OSError):d.read_exact(q,3)
 def test_capture_nonzero_preserved(self):
  cmds=[];b=d.capture([sys.executable,'-I','-S','-B','-c','import sys; print("{\\"verdict\\":\\"FAIL\\"}");sys.exit(1)'],3,cmds,require_exit=False)
  self.assertEqual(json.loads(b)['verdict'],'FAIL');self.assertEqual(cmds[-1]['exit'],1)
 def test_capture_timeout_real_process(self):
  cmds=[]
  with self.assertRaisesRegex(d.Refused,'command_timeout'):d.capture([sys.executable,'-I','-S','-B','-c','import time;time.sleep(2)'],.05,cmds)
  self.assertIsNotNone(cmds[-1]['exit']);self.assertEqual(cmds[-1]['primary'],'timeout')
 def test_capture_bounds_private_stderr(self):
  cmds=[]
  with tempfile.TemporaryDirectory() as t:
   source=P(t)/'synthetic.py';source.write_text('import sys,time;sys.stderr.write("PRIVATE-CANARY"*6000);sys.stderr.flush();time.sleep(1)')
   with self.assertRaisesRegex(d.Refused,'output_bound'):d.capture([sys.executable,'-I','-S','-B',str(source)],3,cmds)
  self.assertNotIn('PRIVATE-CANARY',json.dumps(cmds));self.assertLessEqual(cmds[-1]['stderr_bytes'],65536)
 def test_evidence_budget_failclosed(self):
  with tempfile.TemporaryDirectory() as t:
   p=P(t)
   with self.assertRaisesRegex(d.Refused,'report_bound'):d.write_evidence(p,{'result.json':{'text':'x'*524288}})
   self.assertFalse(list(p.iterdir()))
 def test_cleanup_exact_objects(self):
  with tempfile.TemporaryDirectory() as t:
   root=P(t)/'own';root.mkdir();(root/'runtime').mkdir();(root/'runtime/file').write_text('original');identity=root.stat();inv=d.fixture_inventory(root)
   (root/'extra').write_text('foreign')
   with self.assertRaisesRegex(d.Refused,'shape_or_identity'):d.cleanup_fixture(root,identity,inv)
   self.assertTrue((root/'extra').exists());(root/'extra').unlink()
   d.cleanup_fixture(root,identity,inv);self.assertFalse(root.exists())
 def test_exact_runtime_no_rebuild(self):
  if RUNTIME_TAR is None:self.skipTest('exact runtime tar not supplied; no download or implicit local lookup')
  p=P(RUNTIME_TAR)
  raw,entries=d.validate_tar(p);self.assertEqual(len(raw),59392000);self.assertEqual(len(entries),267)
if __name__=='__main__':
 ap=argparse.ArgumentParser();ap.add_argument('--runtime-tar');args=ap.parse_args();RUNTIME_TAR=args.runtime_tar
 r=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Tests));print(json.dumps({'tests':r.testsRun,'failures':len(r.failures),'errors':len(r.errors),'skips':len(r.skipped),'verdict':'PASS' if r.wasSuccessful() else 'FAIL','scope':'driver controls; static ELF fixture synthetic; real bounded local child capture; no privileged/native adapter execution'})+'\n');raise SystemExit(not r.wasSuccessful())
