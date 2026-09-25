#!/usr/bin/env python3
"""Real local file/persistence + modeled Linux xattr tests; never sudo/root.
ELF fixtures are DATA. The Popen boundary runs ONLY benign unprivileged Python.
Linux capability absence is NOT claimed by Darwin syscall models.
"""
import errno,importlib.util,json,os,pathlib,stat,struct,subprocess,sys,tempfile,types,unittest
from unittest import mock
P=pathlib.Path;HERE=P(__file__).resolve().parent
s=importlib.util.spec_from_file_location('driver',HERE/'native-driver.py');D=importlib.util.module_from_spec(s);s.loader.exec_module(D);G=D.PG

def elf():
 r=bytearray(120);r[:7]=b'\x7fELF\x02\x01\x01';struct.pack_into('<HHIQQQIHHHHHH',r,16,2,62,1,0,64,0,0,64,56,1,0,0,0);struct.pack_into('<IIQQQQQQ',r,64,1,5,0,0,0,120,120,1);return bytes(r)
class Checks(unittest.TestCase):
 def setUp(self):
  self.t=tempfile.TemporaryDirectory(prefix='clb99 evidence ');self.root=P(self.t.name).resolve();self.build=self.root/'clb91-native-adapter/build';self.build.mkdir(parents=True);self.evidence=self.build.parent/'evidence';self.evidence.mkdir(mode=0o700);self.support=self.root/'clb95-support-build';self.support.mkdir(mode=0o700)
  self.paths={'launcher':self.support/'clb95-launcher','helper':self.build/'native-fixture-runner','adapter':self.build/'adapter'}
  for p in self.paths.values():p.write_bytes(elf());p.chmod(0o755)
  (self.support/'binding.h').write_bytes(b'benign test binding; not a compiled executable')
  self.sources=D.build_source_identities();self.compiler={'adapter':D.elf_static(elf()),'fixture_runner':D.elf_static(elf()),'verification_launcher':{**D.elf_static(elf()),'source_sha256':self.sources['launcher'],'binding_sha256':D.sha((self.support/'binding.h').read_bytes())},'executable':{'sha256':'1'*64,'path':'not-exported'},'version':'benign compiler fixture','inputs':{'selected_inputs':{k:{'sha256':'2'*64,'bytes':1,'path':'not-exported'} for k in G.TOOLS}}}
  self.e=mock.patch.dict(os.environ,{'RUNNER_TEMP':str(self.root)});self.e.start();self.commands=[];self.invocations=0;self.events=[]
 def tearDown(self):self.e.stop();self.t.cleanup()
 def absent(self,fd,name):
  self.assertIs(type(fd),int);self.assertEqual(name,'security.capability');self.events.append('capability_check');raise OSError(errno.ENODATA,'synthetic private canary')
 def launcher_line(self):
  return (json.dumps({'clb95_launcher':1,'status':'forced','launcher_source_sha256':D.LE.SOURCE_SHA256,'launcher_uid':0,'launcher_euid':0,'nofile_entry_soft':1048576,'nofile_entry_hard':1048576,'nofile_forced_soft':1024,'nofile_post_force_soft':1024,'nofile_post_force_hard':1048576})+'\n').encode()
 def gate(self):return D.prepare_pre_sudo(self.evidence,self.build,self.compiler,self.sources)
 def run_path(self,after=None,stderr=None,exit_code=0):
  real=subprocess.Popen
  def benign(argv,**kw):
   self.invocations+=1;self.events.append('invoke')
   self.assertEqual(argv[:3],['/usr/bin/sudo','-n','--']);self.assertEqual(argv[3],str(self.paths['launcher']))
   doc=json.loads((self.evidence/'compiler.json').read_bytes());self.assertEqual(doc['phase'],'pre_sudo');self.assertEqual(set(doc['binaries']),set(G.ROLES));self.assertTrue(all(x['file_capabilities_absent'] for x in doc['binaries'].values()))
   self.assertEqual((self.evidence/'compiler.json').stat().st_mode&0o7777,0o600)
   payload=self.launcher_line() if stderr is None else stderr
   # UID0 in payload is parser fixture data, never execution evidence.
   return real([sys.executable,'-I','-S','-B','-c',f'import os;os.write(2,{payload!r});os.write(1,b"{{}}");raise SystemExit({exit_code})'],**kw)
  with mock.patch.object(D.subprocess,'Popen',side_effect=benign):
   permit=self.gate()
   if after:after(permit)
   result=D.capture_launcher('/tmp/clb91-native-fixture123',self.compiler['verification_launcher']['sha256'],self.commands,pre_sudo_permit=permit)
  return permit,result
 def linux(self):
  return mock.patch.object(G.sys,'platform','linux')
 def rejected(self,prepare=None,cap=None,after=None):
  if prepare:prepare()
  with self.linux(),mock.patch.object(G.os,'getxattr',side_effect=cap or self.absent,create=True):
   with self.assertRaises((G.Refused,D.Refused,OSError,ValueError)) as exc:self.run_path(after=after)
  self.assertEqual(self.invocations,0);self.assertFalse(D.privileged_attempted(self.commands));self.assertNotIn('synthetic private canary',str(exc.exception));return exc.exception
 def test_01_positive_ordering(self):
  fsync=os.fsync;replace=os.replace;read=G.read_saved
  def sync(fd):self.events.append('fsync');return fsync(fd)
  def swap(*a,**k):self.events.append('atomic_replace');return replace(*a,**k)
  def saved(*a):self.events.append('readback');return read(*a)
  with self.linux(),mock.patch.object(G.os,'getxattr',side_effect=self.absent,create=True),mock.patch.object(G.os,'fsync',side_effect=sync),mock.patch.object(G.os,'replace',side_effect=swap),mock.patch.object(G,'read_saved',side_effect=saved):p,out=self.run_path()
  self.assertEqual(out,b'{}');self.assertEqual(self.invocations,1);self.assertTrue(p.used)
  self.assertLess(self.events.index('capability_check'),self.events.index('atomic_replace'));self.assertLess(self.events.index('atomic_replace'),self.events.index('readback'));self.assertLess(self.events.index('readback'),self.events.index('invoke'))
  self.assertEqual(self.events.count('capability_check'),6);self.assertEqual(self.events.count('fsync'),2)
  raw=p.raw;self.assertLessEqual(len(raw),G.BOUND);self.assertNotIn(str(self.root).encode(),raw);self.assertNotIn(b'not-exported',raw);self.assertNotIn(b'path',raw)
  with self.assertRaisesRegex(G.Refused,'consumed'):p.consume(self.compiler['verification_launcher']['sha256'])
 def test_02_launcher_hash(self):self.rejected(lambda:self.paths['launcher'].write_bytes(elf()+b'x'))
 def test_03_helper_hash(self):self.rejected(lambda:self.paths['helper'].write_bytes(elf()+b'x'))
 def test_04_adapter_hash(self):self.rejected(lambda:self.paths['adapter'].write_bytes(elf()+b'x'))
 def test_05_symlink(self):
  def mutate():p=self.paths['helper'];p.unlink();p.symlink_to(self.paths['adapter'])
  self.rejected(mutate)
 def test_06_nonregular(self):
  def mutate():p=self.paths['helper'];p.unlink();p.mkdir()
  self.rejected(mutate)
 def test_07_architecture(self):
  def mutate():raw=bytearray(elf());raw[18]=183;self.paths['adapter'].write_bytes(raw);self.compiler['adapter']['sha256']=D.sha(raw)
  self.rejected(mutate)
 def stat_refusal(self,**updates):
  real=os.fstat;ino=self.paths['helper'].stat().st_ino
  def changed(fd):
   a=real(fd)
   if a.st_ino!=ino:return a
   fields={n:getattr(a,n) for n in ('st_dev','st_ino','st_mode','st_uid','st_gid','st_nlink','st_size','st_mtime_ns','st_ctime_ns')};fields.update(updates);return types.SimpleNamespace(**fields)
  with mock.patch.object(G.os,'fstat',side_effect=changed):self.rejected()
 def test_08_owner(self):self.stat_refusal(st_uid=os.getuid()+1)
 def test_09_gid(self):self.stat_refusal(st_gid=os.getgid()+1)
 def test_10_mode(self):self.rejected(lambda:self.paths['adapter'].chmod(0o777))
 def test_11_setuid(self):self.stat_refusal(st_mode=stat.S_IFREG|0o4755)
 def test_12_setgid(self):self.stat_refusal(st_mode=stat.S_IFREG|0o2755)
 def test_13_capability_present(self):self.rejected(cap=lambda *a:b'\x01\x02')
 def test_14_capability_empty(self):self.rejected(cap=lambda *a:b'')
 def test_15_capability_access(self):self.rejected(cap=lambda *a:(_ for _ in ()).throw(OSError(errno.EACCES,'hidden')))
 def test_16_capability_unsupported(self):self.rejected(cap=lambda *a:(_ for _ in ()).throw(OSError(errno.ENOTSUP,'hidden')))
 def test_17_capability_unknown(self):self.rejected(cap=lambda *a:(_ for _ in ()).throw(OSError(errno.EINVAL,'hidden')))
 def test_18_nonlinux_no_claim(self):
  with mock.patch.object(G.sys,'platform','darwin'),mock.patch.object(G.os,'getxattr',create=True) as f:
   with self.assertRaisesRegex(G.Refused,'unavailable'):self.run_path()
   f.assert_not_called()
  self.assertEqual(self.invocations,0)
 def test_19_write_failure(self):
  with mock.patch.object(G.os,'write',side_effect=OSError(errno.ENOSPC,'write')):self.rejected()
 def test_20_atomic_replace_failure(self):
  with mock.patch.object(G.os,'replace',side_effect=OSError(errno.EIO,'replace')):self.rejected()
 def test_21_readback_failure(self):
  with mock.patch.object(G,'read_saved',side_effect=G.Refused('pre_sudo_readback')):self.rejected()
 def test_22_build_missing(self):self.rejected(lambda:self.compiler.pop('fixture_runner'))
 def test_23_build_malformed(self):self.rejected(lambda:self.compiler['adapter'].update(bytes=True))
 def test_24_toolchain_missing(self):self.rejected(lambda:self.compiler['inputs']['selected_inputs'].pop('ld'))
 def test_25_binding_drift(self):self.rejected(lambda:(self.support/'binding.h').write_bytes(b'changed'))
 def test_26_source_drift(self):self.rejected(lambda:self.sources.update(adapter='0'*64))
 def test_27_after_gate_inode_swap(self):
  def after(_):p=self.paths['helper'];tmp=p.with_name('replacement');tmp.write_bytes(elf());tmp.chmod(0o755);os.replace(tmp,p)
  self.rejected(after=after)
 def test_28_after_gate_evidence_swap(self):self.rejected(after=lambda _:(self.evidence/'compiler.json').write_bytes(b'{}\n'))
 def test_29_after_gate_capability(self):
  count=[0]
  def cap(*a):
   count[0]+=1
   if count[0]>3:return b''
   return self.absent(*a)
  self.rejected(cap=cap)
 def test_30_gate_required_on_capture(self):
  with mock.patch.object(D.subprocess,'Popen') as p:
   for privileged in [True,False]:
    with self.assertRaisesRegex(D.Refused,'pre_sudo_gate_required'):D.capture(['/usr/bin/sudo','-n'],1,[],privileged=privileged)
   with self.assertRaisesRegex(D.Refused,'pre_sudo_gate_required'):D.capture_launcher('/tmp/clb91-native-fixture123','0'*64,[])
   p.assert_not_called()
 def test_31_no_overwrite(self):
  (self.evidence/'compiler.json').write_text('old facts');self.rejected();self.assertEqual((self.evidence/'compiler.json').read_text(),'old facts')
 def test_32_fsync_failure(self):
  with mock.patch.object(G.os,'fsync',side_effect=OSError(errno.EIO,'sync')):self.rejected()
 def test_33_evidence_mode(self):self.rejected(lambda:self.evidence.chmod(0o755))
 def test_34_hardlink(self):
  os.link(self.paths['helper'],self.root/'alias');self.rejected()
 def test_35_partial_write_complete(self):
  real=os.write
  def short(fd,b):return real(fd,b[:max(1,len(b)//2)])
  with self.linux(),mock.patch.object(G.os,'getxattr',side_effect=self.absent,create=True),mock.patch.object(G.os,'write',side_effect=short):self.run_path()
  self.assertEqual(self.invocations,1)
 def test_36_zero_write(self):
  with mock.patch.object(G.os,'write',return_value=0):self.rejected()
 def test_37_launcher_stderr_policy(self):
  with self.linux(),mock.patch.object(G.os,'getxattr',side_effect=self.absent,create=True):
   with self.assertRaises(D.Refused):self.run_path(stderr=self.launcher_line()+b'unexpected\n')
  self.assertEqual(self.invocations,1)
 def test_38_launcher_failure_preserved(self):
  with self.linux(),mock.patch.object(G.os,'getxattr',side_effect=self.absent,create=True):self.run_path(exit_code=1)
  self.assertEqual(self.commands[-1]['exit'],1);self.assertEqual(self.invocations,1)
 def test_39_no_source_runtime_mutator(self):
  import inspect
  raw=inspect.getsource(G)
  for forbidden in ['setrlimit(', 'prlimit(', 'subprocess', 'setuid(', 'setgid(', 'chmod(']:self.assertNotIn(forbidden,raw)
  self.assertEqual(set(D.SOURCE_PATHS),set(G.SOURCE_ROLES))
 def test_40_evidence_immutable_final_writer(self):
  with self.assertRaisesRegex(D.Refused,'immutable'):D.write_evidence(self.evidence,{'compiler.json':{}})
 def test_41_metadata_fixed_numeric_fields(self):
  with self.linux(),mock.patch.object(G.os,'getxattr',side_effect=self.absent,create=True):p=self.gate()
  doc=json.loads(p.raw)
  for r in doc['binaries'].values():
   self.assertEqual(r['mode'],0o755);self.assertEqual(r['uid'],os.getuid());self.assertEqual(r['gid'],os.getgid())
   self.assertTrue(r['regular']);self.assertFalse(r['symlink']);self.assertFalse(r['setuid']);self.assertFalse(r['setgid']);self.assertEqual(r['identity_status'],'PASS')
  self.assertEqual(self.invocations,0)
 def test_42_helper_capability_present(self):
  count=[0]
  def cap(*a):
   count[0]+=1
   if count[0]==2:return b'capability'
   return self.absent(*a)
  self.rejected(cap=cap)
 def test_43_adapter_capability_present(self):
  count=[0]
  def cap(*a):
   count[0]+=1
   if count[0]==3:return b'capability'
   return self.absent(*a)
  self.rejected(cap=cap)
 def test_44_final_evidence_separate_and_bounded(self):
  with self.linux(),mock.patch.object(G.os,'getxattr',side_effect=self.absent,create=True):permit=self.gate()
  before=(self.evidence/'compiler.json').read_bytes()
  D.write_evidence(self.evidence,{'result.json':{'runtime':'NOT_RUN'}})
  self.assertEqual((self.evidence/'compiler.json').read_bytes(),before);self.assertEqual(permit.validate_saved(),D.sha(before))
  with self.assertRaisesRegex(D.Refused,'report_bound'):D.write_evidence(self.evidence,{'too-large.json':{'x':'x'*524288}})
 def test_45_evidence_readback_schema_not_substitutable(self):
  real=G.read_fd
  def changed(fd,bound):
   raw=real(fd,bound)
   if b'"phase":"pre_sudo"' in raw:return raw.replace(b'"phase":"pre_sudo"',b'"phase":"postsudo"')
   return raw
  with mock.patch.object(G,'read_fd',side_effect=changed):self.rejected()
 def test_46_interrupted_write_recovers(self):
  real=os.write;count=[0]
  def interrupted(fd,b):
   count[0]+=1
   if count[0]==1:raise InterruptedError()
   return real(fd,b)
  with self.linux(),mock.patch.object(G.os,'getxattr',side_effect=self.absent,create=True),mock.patch.object(G.os,'write',side_effect=interrupted):self.run_path()
  self.assertEqual(self.invocations,1)
 def test_47_unbounded_interrupt_refused(self):
  with mock.patch.object(G.os,'write',side_effect=InterruptedError()):self.rejected()
 def test_48_directory_fsync_failure(self):
  real=os.fsync;count=[0]
  def changed(fd):
   count[0]+=1
   if count[0]==2:raise OSError(errno.EIO,'directory')
   return real(fd)
  with mock.patch.object(G.os,'fsync',side_effect=changed):self.rejected()
 def test_49_consume_refusal_main_unprivileged_cleanup(self):
  import contextlib,io
  base=self.root/'main-case';base.mkdir();compiler=self.compiler
  real_capture=D.capture;real_read=D.read_exact;real_prepare=D.prepare_pre_sudo
  def capture(argv,seconds,commands,**kw):
   if argv[0]=='/usr/bin/sudo':return real_capture(argv,seconds,commands,**kw)
   commands.append({'argv':argv,'privileged_fixture':False,'invocation_attempted':True,'exit':0})
   if '--version' in argv:return b'benign compiler fixture\n'
   if '-o' in argv:P(argv[argv.index('-o')+1]).write_bytes(elf());return b''
   return b'{"summary":{"failed":0}}\n'
  def prepare(*a):
   permit=real_prepare(*a);p=base/'clb91-native-adapter/build/native-fixture-runner';p.write_bytes(elf()+b'changed after gate');return permit
  def read(path,bound):
   if str(path).startswith('/usr/'):return b'benign compiler bytes'
   return real_read(path,bound)
  with self.linux(),mock.patch.dict(os.environ,{'RUNNER_TEMP':str(base)}),mock.patch.object(G.os,'getxattr',side_effect=self.absent,create=True),mock.patch.object(sys,'argv',['driver','--work',str(base/'clb91-native-adapter'),'--runtime-tar','unused','--authorized-native-test']),mock.patch.object(D,'native_guard'),mock.patch.object(D,'source_identity',return_value={}),mock.patch.object(D,'validate_tar',return_value=(b'',[])),mock.patch.object(D,'extract_runtime',side_effect=lambda raw,entries,p:p.mkdir()),mock.patch.object(D,'compiler_inputs',return_value=compiler['inputs']),mock.patch.object(D,'verification_regressions'),mock.patch.object(D,'capture',side_effect=capture),mock.patch.object(D,'read_exact',side_effect=read),mock.patch.object(D,'prepare_pre_sudo',side_effect=prepare),mock.patch.object(D.subprocess,'Popen') as popen,contextlib.redirect_stdout(io.StringIO()):
   self.assertEqual(D.main(),1);popen.assert_not_called()
  report=json.loads((base/'clb91-native-adapter/evidence/result.json').read_bytes());self.assertEqual(report['primary'],'pre_sudo_binary_hash');self.assertEqual(report['local_fixture_cleanup'],'confirmed');self.assertNotEqual(report['cleanup'],'UNKNOWN')
  commands=json.loads((base/'clb91-native-adapter/evidence/commands.json').read_bytes());self.assertFalse(D.privileged_attempted(commands));self.assertFalse(commands[-1]['invocation_attempted'])
if __name__=='__main__':unittest.main(verbosity=2)
