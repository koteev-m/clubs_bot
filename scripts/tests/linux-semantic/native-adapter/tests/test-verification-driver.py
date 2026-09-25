#!/usr/bin/env python3
"""Closed future recipe unit tests. Compiler/capture boundary only is mocked.
No compiler, sudo or helper executes; ELF is data. Failure cases use benign
unprivileged Python children through the existing capture boundary.
"""
import contextlib,importlib.util,io,json,os,pathlib,shutil,struct,subprocess,sys,tempfile,unittest
from unittest import mock
P=pathlib.Path;HERE=P(__file__).resolve().parent
s=importlib.util.spec_from_file_location('driver',HERE/'native-driver.py');D=importlib.util.module_from_spec(s);s.loader.exec_module(D)
def elf():
 r=bytearray(120);r[:7]=b'\x7fELF\x02\x01\x01';struct.pack_into('<HHIQQQIHHHHHH',r,16,2,62,1,0,64,0,0,64,56,1,0,0,0);struct.pack_into('<IIQQQQQQ',r,64,1,5,0,0,0,120,120,1);return bytes(r)
class Checks(unittest.TestCase):
 def setUp(self):
  self.t=tempfile.TemporaryDirectory(prefix='clb97 driver ');self.root=P(self.t.name).resolve();self.build=self.root/'build';self.build.mkdir();self.src=self.root/'native';(self.src/'tests').mkdir(parents=True);shutil.copytree(HERE/'launcher-source',self.src/'tests/launcher-source')
  for name in ['adapter','native-fixture-runner']:(self.build/name).write_bytes(elf());(self.build/name).chmod(0o700)
  self.compiler={'adapter':D.elf_static(elf()),'fixture_runner':D.elf_static(elf())};self.commands=[]
  self.e=mock.patch.dict(os.environ,{'RUNNER_TEMP':str(self.root)});self.e.start();self.h=mock.patch.object(D,'HERE',self.src);self.h.start()
 def tearDown(self):self.h.stop();self.e.stop();self.t.cleanup()
 def compiler_boundary(self,argv,seconds,commands,**kwargs):
  self.assertNotIn('sudo',' '.join(argv));self.assertEqual(argv[0],'/usr/bin/gcc');self.assertEqual(seconds,60)
  self.assertEqual(argv[-2],'-o');out=P(argv[-1]);self.assertEqual(out,self.root/'clb95-support-build/clb95-launcher');out.write_bytes(elf())
  commands.append({'mock':'compile_only'});return b''
 def build_it(self):
  with mock.patch.object(D,'capture',side_effect=self.compiler_boundary) as cap:
   sha=D.build_verification_launcher(self.build,['/usr/bin/gcc','-static'],self.compiler,self.commands)
   self.assertEqual(cap.call_count,1)
  return sha
 def test_01_binding_exact(self):
  h=self.build_it();self.assertEqual(h,D.sha(elf()))
  binding=(self.root/'clb95-support-build/binding.h').read_text()
  for name in ['HELPER_SHA256','ADAPTER_SHA256','LAUNCHER_SOURCE_SHA256','BUILD_UID','BUILD_GID']:self.assertIn('#define '+name+' ',binding)
  self.assertIn(json.dumps(str(self.build/'adapter')),binding)
  self.assertEqual((self.build/'adapter').stat().st_mode&0o7777,0o755)
  self.assertEqual((self.build/'native-fixture-runner').stat().st_mode&0o7777,0o755)
  argv=['/usr/bin/sudo','-n','--',str(self.root/'clb95-support-build/clb95-launcher'),'--work','/tmp/clb91-native-fixture123']
  self.assertEqual(D.verify_launcher_invocation(argv,h,True),self.root/'clb95-support-build/clb95-launcher')
 def test_02_source_tamper_before_compile(self):
  p=self.src/'tests/launcher-source/launcher.c';p.write_bytes(p.read_bytes()+b'\n')
  with mock.patch.object(D,'capture') as cap:
   with self.assertRaisesRegex(D.Refused,'launcher_source_identity'):D.build_verification_launcher(self.build,['/usr/bin/gcc'],self.compiler,[])
   cap.assert_not_called()
 def test_03_source_symlink(self):
  p=self.src/'tests/launcher-source/launcher.c';raw=p.read_bytes();p.unlink();target=self.root/'target';target.write_bytes(raw);p.symlink_to(target)
  with self.assertRaisesRegex(D.Refused,'launcher_source_identity'):D.build_verification_launcher(self.build,[],self.compiler,[])
 def test_04_fresh_directory(self):
  (self.root/'clb95-support-build').mkdir()
  with mock.patch.object(D,'capture') as cap:
   with self.assertRaises(FileExistsError):D.build_verification_launcher(self.build,[],self.compiler,[])
   cap.assert_not_called()
 def test_05_root_build_refused(self):
  with mock.patch.object(D.os,'geteuid',return_value=0),mock.patch.object(D,'capture') as cap:
   with self.assertRaisesRegex(D.Refused,'launcher_build_unprivileged'):D.build_verification_launcher(self.build,[],self.compiler,[])
   cap.assert_not_called()
 def expected_invocations(self):
  # Independent ordered oracle, deliberately not derived from coordinator lists.
  rows=[
   ('test-resource-envelope.py',(),90),('test-resource-envelope.py',('--ubsan',),90),
   ('test-fd-budget.py',(),90),('test-fd-budget.py',('--ubsan',),90),
   ('test-namespace-handoff.py',(),90),('test-namespace-handoff.py',('--ubsan',),90),
   ('test-adapter-namespace.py',(),90),('test-adapter-namespace.py',('--ubsan',),90),
   ('test-fixture-diagnostics.py',(),90),('test-fixture-diagnostics.py',('--ubsan',),90),
   ('test-bind-probe.py',(),90),('test-bind-probe.py',('--ubsan',),90),
   ('test-worker-evidence.py',(),90),('test-worker-evidence.py',('--ubsan',),90),
   ('test-driver.py',('--runtime-tar','/synthetic/exact.tar'),60),
   ('test-pre-sudo-evidence.py',(),60),('test-verification-driver.py',(),60),
   ('test-verification-evidence.py',(),60)]
  return [(['/usr/bin/python3','-I','-S','-B',str(self.src/'tests'/name),*args],seconds,{}) for name,args,seconds in rows]
 def observed_invocations(self):
  seen=[]
  with mock.patch.object(D,'capture',side_effect=lambda argv,seconds,*a,**kw:seen.append((argv,seconds,kw))):
   D.verification_regressions([],P('/synthetic/exact.tar'))
  return seen
 def assert_invocations(self,seen):
  self.assertEqual(len(seen),18)
  self.assertEqual(seen,self.expected_invocations())
  self.assertEqual(len({tuple(argv) for argv,_,_ in seen}),18)
  for argv,_,kw in seen:
   self.assertEqual(argv[:4],['/usr/bin/python3','-I','-S','-B']);self.assertFalse(kw.get('privileged'));self.assertNotEqual(kw.get('require_exit'),False)
  for name in ['test-pre-sudo-evidence.py','test-verification-driver.py','test-verification-evidence.py']:
   self.assertEqual(sum(P(argv[4]).name==name for argv,_,_ in seen),1)
 def test_06_regressions_closed_unprivileged(self):self.assert_invocations(self.observed_invocations())
 def test_07_original_no_fake_worker(self):
  seen=[]
  with mock.patch.object(D,'VERIFICATION_KIND','ORIGINAL_CLB91'),mock.patch.object(D,'capture',side_effect=lambda argv,*a,**kw:seen.append(argv)):
   D.verification_regressions([],P('/synthetic/exact.tar'))
  self.assertEqual(len(seen),13);self.assertFalse(any('test-worker-evidence.py' in ' '.join(x) for x in seen))
 def test_08_native_main_single_launcher(self):
  import inspect
  src=inspect.getsource(D.main)
  self.assertEqual(src.count('capture_launcher('),1);self.assertNotIn("['/usr/bin/sudo'",src)
  self.assertLess(src.index('verification_regressions('),src.index('prepare_pre_sudo('))
  self.assertLess(src.index('build_verification_launcher('),src.index('prepare_pre_sudo('))
  self.assertLess(src.index('prepare_pre_sudo('),src.index('pre_sudo.validate_saved()'))
  self.assertLess(src.index('pre_sudo.validate_saved()'),src.index('capture_launcher('))
  self.assertIn('pre_sudo_permit=pre_sudo',src)
  self.assertNotIn("'compiler.json':compiler",src)
 def test_09_no_limit_mutators_in_coordinator(self):
  raw=(HERE/'native-driver.py').read_text()
  self.assertNotIn('setrlimit(',raw);self.assertNotIn('prlimit(',raw);self.assertNotIn('ulimit ',raw)
 def test_10_missing_each_added_refused(self):
  observed=self.observed_invocations()
  for i in range(15,18):
   with self.subTest(index=i),self.assertRaises(AssertionError):self.assert_invocations(observed[:i]+observed[i+1:])
 def test_11_duplicate_refused(self):
  observed=self.observed_invocations()
  with self.assertRaises(AssertionError):self.assert_invocations(observed+[observed[-1]])
  with self.assertRaises(AssertionError):self.assert_invocations(observed[:-1]+[observed[-2]])
 def test_12_unexpected_refused(self):
  observed=self.observed_invocations();extra=(observed[-1][0][:4]+[str(self.src/'tests/unknown.py')],60,{})
  with self.assertRaises(AssertionError):self.assert_invocations(observed+[extra])
  with self.assertRaises(AssertionError):self.assert_invocations(observed[:-1]+[extra])
 def test_13_old15_refused(self):
  with self.assertRaises(AssertionError):self.assert_invocations(self.observed_invocations()[:15])
 def test_14_order_path_args_or_failure_policy_drift_refused(self):
  observed=self.observed_invocations()
  variants=[observed[:15]+list(reversed(observed[15:])),observed[:-1]+[(observed[-1][0],60,{'require_exit':False})],observed[:-1]+[(observed[-1][0],60,{'privileged':True})],observed[:-1]+[(observed[-1][0]+['--unexpected'],60,{})]]
  for changed in variants:
   with self.assertRaises(AssertionError):self.assert_invocations(changed)
 def actual_capture_failure(self,index,mode):
  expected=self.expected_invocations();target=expected[index][0];calls=[];commands=[]
  capture=D.capture;popen=subprocess.Popen
  def benign(argv,**kw):
   self.assertEqual(argv,target)
   if mode=='missing_interpreter':raise FileNotFoundError('synthetic interpreter missing')
   if mode=='missing_script':
    self.assertFalse(P(argv[4]).exists())
    return popen([sys.executable,*argv[1:]],**kw)
   code='raise SystemExit(7)' if mode=='nonzero' else 'for _ in range(1000000): pass'
   return popen([sys.executable,'-I','-S','-B','-c',code],**kw)
  def boundary(argv,seconds,records,**kw):
   calls.append((argv,seconds,kw))
   if argv==target:return capture(argv,0 if mode=='timeout' else seconds,records,**kw)
   return b''
  error=FileNotFoundError if mode=='missing_interpreter' else D.Refused
  with mock.patch.object(D,'capture',side_effect=boundary),mock.patch.object(D.subprocess,'Popen',side_effect=benign) as invoke:
   with self.assertRaises(error) as exc:D.verification_regressions(commands,P('/synthetic/exact.tar'))
   self.assertEqual(invoke.call_count,1)
  self.assertEqual(calls,expected[:index+1]);self.assertFalse(D.privileged_attempted(commands))
  if mode=='timeout':self.assertEqual(str(exc.exception),'command_timeout');self.assertEqual(commands[-1]['primary'],'timeout')
  elif mode!='missing_interpreter':
   self.assertEqual(str(exc.exception),'command_nonzero');self.assertEqual(commands[-1]['exit'],2 if mode=='missing_script' else 7)
 def test_15_each_added_nonzero_is_terminal(self):
  for i in range(15,18):
   with self.subTest(index=i):self.actual_capture_failure(i,'nonzero')
 def test_16_each_added_missing_script_is_terminal(self):
  for i in range(15,18):
   with self.subTest(index=i):self.actual_capture_failure(i,'missing_script')
 def test_17_each_added_timeout_is_terminal(self):
  for i in range(15,18):
   with self.subTest(index=i):self.actual_capture_failure(i,'timeout')
 def test_18_malformed_variant_refuses_before_capture(self):
  for kind in ['UNKNOWN','',None]:
   with mock.patch.object(D,'VERIFICATION_KIND',kind),mock.patch.object(D,'capture') as cap:
    with self.assertRaisesRegex(D.Refused,'^verification_kind$'):D.verification_regressions([],P('/synthetic/exact.tar'))
    cap.assert_not_called()
 def test_19_each_added_missing_interpreter_is_terminal(self):
  for i in range(15,18):
   with self.subTest(index=i):self.actual_capture_failure(i,'missing_interpreter')
 def test_20_each_added_failure_blocks_main_privilege_path(self):
  # Actual main + regression function. Only earlier build/input boundaries modeled.
  for name in ['test-pre-sudo-evidence.py','test-verification-driver.py','test-verification-evidence.py']:
   base=self.root/name;base.mkdir();seen=[]
   def boundary(argv,seconds,commands,**kw):
    if len(argv)>4 and argv[:4]==['/usr/bin/python3','-I','-S','-B']:
     seen.append(P(argv[4]).name)
     if P(argv[4]).name==name:raise D.Refused('command_nonzero')
     return b''
    if '--version' in argv:return b'benign compiler fixture\n'
    if '-o' in argv:P(argv[argv.index('-o')+1]).write_bytes(elf());return b''
    return b'{"summary":{"failed":0}}\n'
   with contextlib.ExitStack() as stack:
    for patch in [mock.patch.dict(os.environ,{'RUNNER_TEMP':str(base)}),mock.patch.object(sys,'argv',['driver','--work',str(base/'clb91-native-adapter'),'--runtime-tar','unused','--authorized-native-test']),mock.patch.object(D,'native_guard'),mock.patch.object(D,'source_identity',return_value={}),mock.patch.object(D,'validate_tar',return_value=(b'',[])),mock.patch.object(D,'extract_runtime',side_effect=lambda raw,entries,p:p.mkdir()),mock.patch.object(D,'compiler_inputs',return_value={}),mock.patch.object(D,'build_source_identities',return_value={}),mock.patch.object(D,'read_exact',return_value=elf()),mock.patch.object(D,'capture',side_effect=boundary),contextlib.redirect_stdout(io.StringIO())]:stack.enter_context(patch)
    build=stack.enter_context(mock.patch.object(D,'build_verification_launcher'));gate=stack.enter_context(mock.patch.object(D,'prepare_pre_sudo'));priv=stack.enter_context(mock.patch.object(D,'capture_launcher'));process=stack.enter_context(mock.patch.object(D.subprocess,'Popen'))
    self.assertEqual(D.main(),1);build.assert_not_called();gate.assert_not_called();priv.assert_not_called();process.assert_not_called()
   result=json.loads((base/'clb91-native-adapter/evidence/result.json').read_bytes());self.assertEqual(result['verdict'],'BLOCKED');self.assertEqual(result['primary'],'command_nonzero');self.assertEqual(result['local_fixture_cleanup'],'confirmed');self.assertEqual(seen[-1],name)
 def test_21_workflow_transitive_single_coordinator(self):
  raw=(HERE.parents[4]/'.github/workflows/tests.yml').read_text();native=raw.split('  amd64-runtime-prototype:\n',1)[1]
  self.assertEqual(native.count('tests/native-driver.py'),1)
  for name in ['test-pre-sudo-evidence.py','test-verification-driver.py','test-verification-evidence.py']:self.assertNotIn(name,native)
 def test_22_added_suite_contracts_exist(self):
  for name in ['test-pre-sudo-evidence.py','test-verification-driver.py','test-verification-evidence.py']:
   path=HERE/name;self.assertTrue(path.is_file());self.assertFalse(path.is_symlink());self.assertIn("if __name__=='__main__':unittest.main",path.read_text())
 def test_23_missing_each_prior_invocation_refused(self):
  observed=self.observed_invocations()
  for i in range(15):
   with self.subTest(index=i),self.assertRaises(AssertionError):self.assert_invocations(observed[:i]+observed[i+1:])
if __name__=='__main__':unittest.main(verbosity=2)
