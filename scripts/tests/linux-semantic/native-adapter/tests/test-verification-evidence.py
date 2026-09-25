#!/usr/bin/env python3
"""Pure protocol tests only. Matching hashes are synthetic binding fixtures."""
import copy,importlib.util,json,pathlib,unittest
P=pathlib.Path;HERE=P(__file__).resolve().parent
s=importlib.util.spec_from_file_location('evidence',HERE/'verification-evidence.py');E=importlib.util.module_from_spec(s);s.loader.exec_module(E)
L={'clb95_launcher':1,'status':'forced','launcher_source_sha256':E.L.SOURCE_SHA256,'launcher_uid':0,'launcher_euid':0,'nofile_entry_soft':1048576,'nofile_entry_hard':1048576,'nofile_forced_soft':1024,'nofile_post_force_soft':1024,'nofile_post_force_hard':1048576}
W={'version':1,'role':'worker','stage':'pre_exec','basis':'getrlimit','identity_verified':True,'status':'CONFIRMED','nofile_soft':1024,'nofile_hard':1024,'record_bytes':56,'stderr_total':56}
R={'verdict':'PASS','cleanup':'confirmed','cleanup_error':False,'adapter_started':True,'negative_controls':{k:True for k in ['wrong_uid','symlink','runtime_hash','actual_tmpfs_backing']},'adapter_result':{'primary':'semantic_complete','exit':0,'signal':0,'cleanup_error':False,'original_recheck':True,'view_held_fd_identity':True,'worker_rlimit':W}}
def encode(r):return (json.dumps(r,separators=(',',':'))+'\n').encode()
class Checks(unittest.TestCase):
 def merge(self,r=None,l=None,**binding):
  args={'expected_launcher_sha256':'1'*64,'observed_launcher_sha256':'1'*64,'expected_adapter_sha256':'2'*64,'observed_adapter_sha256':'2'*64};args.update(binding)
  return E.merge(encode(L) if l is None else l,encode(R) if r is None else r,**args)
 def test_01_merge(self):
  x=self.merge();self.assertEqual(x['worker'],W);self.assertEqual(x['launcher']['nofile_post_force_soft'],1024);self.assertLess(len(encode(x)),2048)
 def test_02_mismatches(self):
  for key,value in [('nofile_soft',1023),('nofile_hard',1098),('stage','post_exec'),('role','parent'),('identity_verified',False),('identity_verified',1),('stderr_total',112),('record_bytes',55)]:
   r=copy.deepcopy(R);r['adapter_result']['worker_rlimit'][key]=value
   with self.assertRaises(E.Refused):self.merge(encode(r))
 def test_03_missing(self):
  r=copy.deepcopy(R);del r['adapter_result']['worker_rlimit']
  with self.assertRaises(E.Refused):self.merge(encode(r))
 def test_04_duplicate(self):
  raw=encode(R).replace(b'"version":1',b'"version":1,"version":1')
  with self.assertRaisesRegex(E.Refused,'helper_json'):self.merge(raw)
 def test_05_malformed_oversize(self):
  for raw in [b'{',b'[]',encode(R)*2,b' '*65537]:
   with self.assertRaises(E.Refused):self.merge(raw)
 def test_06_hash_mismatch(self):
  with self.assertRaisesRegex(E.Refused,'execution_identity'):self.merge(observed_adapter_sha256='3'*64)
  with self.assertRaisesRegex(E.Refused,'execution_identity'):self.merge(observed_launcher_sha256='3'*64)
 def test_07_arbitrary_launcher_stderr(self):
  for raw in [b'',encode(L)*2,encode(L)+b'CANARY',b'x'*1025,encode(L).replace(b'forced',b'changed')]:
   with self.assertRaisesRegex(E.Refused,'launcher_evidence'):self.merge(l=raw)
 def test_08_exec_failure(self):
  r=copy.deepcopy(R);r['adapter_result'].update(primary='worker_response',exit=120,child_error='exec')
  with self.assertRaisesRegex(E.Refused,'adapter_failed'):self.merge(encode(r))
 def test_09_cleanup_failure(self):
  r=copy.deepcopy(R);r['cleanup']='UNKNOWN';r['cleanup_error']=True
  with self.assertRaisesRegex(E.Refused,'helper_failed'):self.merge(encode(r))
 def test_10_negative_failure(self):
  r=copy.deepcopy(R);r['negative_controls']['wrong_uid']=False
  with self.assertRaisesRegex(E.Refused,'negative_controls'):self.merge(encode(r))
 def test_11_private_text_not_exported(self):
  r=copy.deepcopy(R);r['adapter_result']['worker_rlimit']['CANARY']='/private/path'
  try:self.merge(encode(r))
  except E.Refused as e:self.assertEqual(str(e),'worker_schema')
  else:self.fail('accepted')
 def test_12_captured_merge(self):
  command={'primary':None,'privileged_fixture':True,'exit':0,'launcher_evidence':{**L,'launcher_binary_sha256':'1'*64}}
  args={'expected_launcher_sha256':'1'*64,'expected_adapter_sha256':'2'*64,'observed_adapter_sha256':'2'*64}
  self.assertEqual(E.merge_captured(command,encode(R),**args)['worker'],W)
  for patch in [{'exit':120},{'primary':'launcher_extra_stderr'},{'privileged_fixture':False}]:
   with self.assertRaisesRegex(E.Refused,'capture_failed'):E.merge_captured({**command,**patch},encode(R),**args)
 def test_13_worker_frame_on_outer_stderr_refused(self):
  with self.assertRaisesRegex(E.Refused,'launcher_evidence'):
   self.merge(l=encode(L)+b'clb97:v1 role=worker stage=pre_exec soft=1024 hard=1024\n')
if __name__=='__main__':unittest.main(verbosity=2)
