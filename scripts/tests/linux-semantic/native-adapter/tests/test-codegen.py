#!/usr/bin/env python3
import hashlib,json,pathlib,shutil,subprocess,sys,tempfile,unittest
P=pathlib.Path;ROOT=P(__file__).resolve().parent.parent
class Tests(unittest.TestCase):
 def prepare(self,p):
  shutil.copytree(ROOT/'reference',p/'reference');shutil.copyfile(ROOT/'codegen.py',p/'codegen.py');(p/'adapter').mkdir()
 def run_codegen(self,p):return subprocess.run([sys.executable,'-I','-S','-B',str(p/'codegen.py')],capture_output=True)
 def test_full_contract_deterministic(self):
  with tempfile.TemporaryDirectory() as t:
   p=P(t);self.prepare(p);r=self.run_codegen(p);self.assertEqual(r.returncode,0,r.stderr)
   self.assertEqual((p/'adapter/generated_contract.h').read_bytes(),(ROOT/'adapter/generated_contract.h').read_bytes())
   self.assertEqual(json.loads((p/'generated-identity.json').read_bytes()),json.loads((ROOT/'generated-identity.json').read_bytes()))
 def test_source_drift_before_generation(self):
  with tempfile.TemporaryDirectory() as t:
   p=P(t);self.prepare(p);(p/'reference/bootstrap.py').write_bytes(b'print("PRIVATE-CANARY")')
   r=self.run_codegen(p);self.assertNotEqual(r.returncode,0);self.assertFalse((p/'adapter/generated_contract.h').exists());self.assertNotIn(b'PRIVATE-CANARY',r.stdout+r.stderr)
 def test_ledger_drift_not_observed_pins(self):
  with tempfile.TemporaryDirectory() as t:
   p=P(t);self.prepare(p);(p/'reference/pins.json').write_text('{}');r=self.run_codegen(p);self.assertNotEqual(r.returncode,0);self.assertFalse((p/'adapter/generated_contract.h').exists())
if __name__=='__main__':
 r=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Tests));print(json.dumps({'tests':r.testsRun,'failures':len(r.failures),'errors':len(r.errors),'skips':len(r.skipped),'verdict':'PASS' if r.wasSuccessful() else 'FAIL'})+'\n');raise SystemExit(not r.wasSuccessful())
