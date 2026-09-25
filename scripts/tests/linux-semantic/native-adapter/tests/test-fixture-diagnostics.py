#!/usr/bin/env python3
"""Compile the exact fixture-source region and exact finish block with call faults.

No helper/native main, root, mounts, namespaces or adapter execution.
The exact source region and finish/report use individual call fault boundaries.
Saved-identity reads are instrumented: a failed capture never reads uninitialized
C memory, but every attempted read is counted and fails the regression. Separate
real unprivileged directory/FD tests execute the exact source cleanup condition.
Native-only preparation, ownership promotion and mounts are NOT_RUN. No test
hooks or runtime bypass enter the published helper.
"""
import argparse,hashlib,importlib.util,json,pathlib,re,subprocess,sys,tempfile,unittest
P=pathlib.Path;HERE=P(__file__).resolve().parent
HEADER=r'''
#define _GNU_SOURCE 1
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <stdint.h>
#include <errno.h>
#include <sys/types.h>
#define PATH_MAX 4096
#define MAX_PATH 1024
#define OUTPUT_LIMIT 4096
#define O_RDONLY 0
#define O_DIRECTORY 0x10
#define O_NOFOLLOW 0x20
#define O_CLOEXEC 0x40
#define O_NONBLOCK 0x80
#define S_IFREG 0100000
#define S_IFDIR 0040000
#define S_ISREG(m) (((m)&0170000)==S_IFREG)
#define MS_BIND 4096
#define AT_REMOVEDIR 0x200
#define SOURCE_ROOT "/opt/clubs-bot-stage"
struct stat {unsigned long st_dev,st_ino;unsigned st_uid,st_gid,st_mode;};
struct statfs {long f_type;};
struct lease_set {int x;};struct mount_tuple {int x;};
struct captured {int code;size_t used;unsigned char out[OUTPUT_LIMIT+1];};
/* This suite begins after admission; only exact numeric printer is included.
 * Admission/enumeration is executed by the separate FD-budget suite. */
struct fd_budget {
 uint64_t soft,hard,open_count,additional,total;
 int limits_known,soft_infinite,hard_infinite,count_known,required_known,sufficient,close_error;
};static struct fd_budget namespace_budget;
static int interrupted,untracked_created;static unsigned adapter_calls;
static const char*srcdirs[]={".clubs-bot-release-state",".clubs-bot-release-state/stage",".clubs-bot-release-state/stage/clubs-bot-schema-stage.lock",".clubs-bot-release-state/stage/clubs-bot-schema-stage.results",".clubs-bot-release-state/stage/clubs-bot-schema-stage.migration-ledgers"};
static const char *fault;static int fault_n,fault_error,cleanup_fault,in_finish,short_random,probe_errno_bad;
static int captured_identity,identity_reads,invalid_identity_reads,cleanup_stats,source_unlinks;
static unsigned long identity_read(const struct stat*s,int inode) {
 identity_reads++;if(!captured_identity){invalid_identity_reads++;return 0;}
 return inode?s->st_ino:s->st_dev;
}
static int calls[64],ncalls;static char names[64][48];static int ordinals[64],zeros[64];
static int visit(const char*n,int reset_expected) {
 int ord=0;for(int i=0;i<ncalls;i++)if(!strcmp(names[i],n))ord++;
 if(ncalls>=64)abort();strcpy(names[ncalls],n);ordinals[ncalls]=ord;zeros[ncalls]=(errno==0);ncalls++;
 if(reset_expected&&errno!=0)probe_errno_bad++;
 if(!strcmp(fault,n)&&ord==fault_n){if(fault_error>=0)errno=fault_error;return 1;}
 errno=EDOM;return 0;
}
static int f_mkdirat(int fd,const char*p,int mode){if(fd!=3||strcmp(p,"source")||mode!=0700)abort();return visit("mkdir",1)?-1:0;}
static int f_openat(int fd,const char*p,int flags){if(fd!=3||strcmp(p,"source")||flags!=(O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC))abort();return visit("open",1)?-1:7;}
static int f_fchown(int fd,unsigned uid,unsigned gid){if(in_finish)return 0;if(fd!=7||uid!=1000||gid!=1000)abort();return visit("chown",1)?-1:0;}
static int f_fstat(int fd,struct stat*s){
 if(in_finish){cleanup_stats++;if(cleanup_fault==2){errno=EIO;return -1;}}
 else {if(visit("stat",1))return -1;captured_identity=1;}
 s->st_dev=1;s->st_ino=in_finish&&cleanup_fault==3?3:2;return 0;
}
static int f_fstatfs(int fd,struct statfs*s){if(fd!=7)abort();if(visit("statfs",1))return -1;s->f_type=!strcmp(fault,"backing_policy")?0x01021994L:0xef53L;return 0;}
static int f_mkdir(const char*p,int mode){if(strcmp(p,SOURCE_ROOT)||mode!=0700)abort();return visit("mountpoint",1)?-1:0;}
static int f_mount(const char*src,const char*dst,const char*type,unsigned long flags,const void*data){if(strcmp(src,"/proc/self/fd/7")||strcmp(dst,SOURCE_ROOT)||type||flags!=MS_BIND||data)abort();return visit("bind",1)?-1:0;}
static int f_read_mount_tuple(struct mount_tuple*t){(void)t;return visit("mountinfo",1)?-1:0;}
static int f_snprintf(char*s,size_t n,const char*fmt,...) {
 const char*stage=!strcmp(fmt,"/proc/self/fd/%d")?"fdpath":(!strcmp(fmt,"%s/runtime")||!strcmp(fmt,"%s/source"))?"paths":"format";
 if(visit(stage,0))return (int)n;va_list a;va_start(a,fmt);int r=vsnprintf(s,n,fmt,a);va_end(a);return r;
}
static int f_made(int fd,const char*p,int dir,const void*b,size_t n) {
 if(fd!=7)abort();const char*stage=dir?"directory":strstr(p,"operation.lock")||strstr(p,"application.lock")?"lock":!strcmp(p,".env")?"dotenv":"compose";
 (void)b;(void)n;return visit(stage,1)?-1:0;
}
static ssize_t f_getrandom(void*p,size_t n,unsigned flags){if(n!=32||flags)abort();if(visit("random",1))return -1;memset(p,1,n);return short_random?31:32;}
static void hex(const unsigned char*p,size_t n,char*out){(void)p;(void)n;memset(out,'a',64);out[64]=0;}
static unsigned f_alarm(unsigned t){if(t==30)in_finish=1;return 0;}
static int f_cleanup_owned(int fd){(void)fd;errno=EACCES;return 0;}
static int f_unlinkat(int fd,const char*p,int flags){(void)fd;(void)flags;if(!strcmp(p,"source")){source_unlinks++;if(cleanup_fault==4){errno=EACCES;return -1;}}return 0;}
static int f_recheck(struct lease_set*p){(void)p;errno=EACCES;return cleanup_fault==1?-1:0;}
static int f_close_leases(struct lease_set*p){(void)p;return 0;}
static int f_remove_exact_runtime(int fd){(void)fd;return 0;}
static int f_close(int fd){(void)fd;errno=EACCES;return 0;}
#define mkdirat f_mkdirat
#define openat f_openat
#define fchown f_fchown
#define fstat f_fstat
#define fstatfs f_fstatfs
#define mkdir f_mkdir
#define mount f_mount
#define read_mount_tuple f_read_mount_tuple
#define snprintf f_snprintf
#define made f_made
#define getrandom f_getrandom
#define alarm f_alarm
#define cleanup_owned f_cleanup_owned
#define unlinkat f_unlinkat
#define recheck f_recheck
#define close_leases f_close_leases
#define remove_exact_runtime f_remove_exact_runtime
#define close f_close
'''
DECL=r'''
static int under_test(void) {
 int work=3,runtime=4,source=-1,adapter=5,global=6,status=1,cleanup=0;
 char runtimepath[PATH_MAX],sourcepath[PATH_MAX],fdpath[64],canary[65],dotenv[128];unsigned char random[32];
 /* Reads before capture are intercepted, not hidden by zero initialization. */
 struct stat workst={1,2,1001,1001},sourcest;struct statfs fs={0};struct mount_tuple outer={0};struct lease_set rt={0};
 struct captured result={.code=-1};const char*primary="request";char*argv[]={"test","--work","/synthetic/PRIVATE-PATH-CANARY-91",NULL};
 /* Helper lifecycle declarations are extracted, including future regressions. */
 size_t i;
'''
TAIL=r'''
int main(int argc,char**argv) {
 if(argc!=6)return 98;fault=argv[1];fault_n=atoi(argv[2]);fault_error=atoi(argv[3]);cleanup_fault=atoi(argv[4]);short_random=atoi(argv[5]);errno=ERANGE;
 bp_scenario=!strcmp(fault,"probe_missing")?"missing":!strcmp(fault,"probe_close")?"close_error":"ok";
 if(!strncmp(fault,"probe_",6))fault="bind";
 int rc=under_test();
 printf("{\"boundary\":{\"errno_reset_violations\":%d,\"calls\":[",probe_errno_bad);
 for(int i=0;i<ncalls;i++)printf("%s{\"name\":\"%s\",\"ordinal\":%d,\"errno_zero\":%s}",i?",":"",names[i],ordinals[i],zeros[i]?"true":"false");
 printf("],\"identity_captured\":%s,\"identity_reads\":%d,\"invalid_identity_reads\":%d,\"cleanup_stats\":%d,\"source_unlinks\":%d}}\n",captured_identity?"true":"false",identity_reads,invalid_identity_reads,cleanup_stats,source_unlinks);return rc;
}
'''
def source_parts(root):
 helper=(root/'tests/native-fixture-runner.c').read_text();adapter=(root/'adapter/adapter.c').read_text()
 start=re.search(r'  primary="fixture_source(?:_mkdir)?";',helper).start();end=helper.index('  primary="native_backing_binding";',start)
 region=helper[start:end];finish=helper[helper.index('  finish:alarm(30);'):helper.rindex('\n#endif')]
 decl=re.search(r'  int source_created=.*?;',helper).group()+'\n'
 if 'int fixture_errno=' in helper:
  decl+=re.search(r'  int fixture_errno=.*?\n  unsigned long fixture_magic=0;',helper,re.S).group()+'\n'
 policy=re.search(r'static int supported_backing\(unsigned long t\)  \{.*?\n\}',adapter,re.S).group()
 if 'struct bind_probe bind_observation=' in helper:
  decl+=re.search(r'  struct bind_probe bind_observation=.*?;',helper).group()+'\n'
 return helper,region,finish,decl,policy

def compile_boundary(root,dest):
 helper,region,finish,decl,policy=source_parts(root)
 finish=finish.replace('sourcest.st_dev','identity_read(&sourcest,0)').replace('sourcest.st_ino','identity_read(&sourcest,1)')
 spec=importlib.util.spec_from_file_location('probe_test_boundary',root/'tests/test-bind-probe.py');probe=importlib.util.module_from_spec(spec);spec.loader.exec_module(probe)
 # Actual collector and parsers, with only individual read-only syscalls replaced.
 probe_include='\n#undef fstat\n#undef close\n#undef snprintf\n'+probe.boundary_include(root)+'\n#define fstat f_fstat\n#define close f_close\n#define snprintf f_snprintf\n'
 src=dest/'fault-boundary.c';budget=(root/'adapter/fd-budget.h').read_text();budget_print=budget[budget.index('static void fd_budget_value('):budget.rindex('#endif')]
 src.write_text(HEADER+budget_print+probe_include+policy+'\n'+DECL+decl+'\n namespace_budget=(struct fd_budget){.limits_known=1,.soft=1038,.hard=4096,.count_known=1,.open_count=520,.required_known=1,.additional=518,.total=1038,.sufficient=1};\n'+region+'\n  primary="fixture_source_region_complete";status=0;\n'+finish+'\n'+TAIL)
 cmd=[CC]+SANITIZERS+['-std=c11','-O1','-Wall','-Wextra','-Wno-unused-function','-Wno-unused-variable','-Wno-unused-parameter','-Wno-misleading-indentation',str(src),'-o',str(dest/'fault-boundary')]
 q=subprocess.run(cmd,capture_output=True,timeout=30)
 if q.returncode:raise RuntimeError(q.stderr.decode())
 return dest/'fault-boundary'


REAL_HEADER=r"""
#define _GNU_SOURCE 1
#include <sys/stat.h>
#include <sys/types.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <stdlib.h>
#include <stdio.h>
static int ready,reads,invalid,rechecks,unlinks,scenario;
static unsigned long identity_read(const struct stat*s,int inode) {
 reads++;if(!ready){invalid++;return 0;}return inode?s->st_ino:s->st_dev;
}
static int observed_stat(int fd,struct stat*s) {
 rechecks++;if(scenario==5){errno=EIO;return -1;}return fstat(fd,s);
}
static int observed_unlink(int fd,const char*p,int flags) {
 unlinks++;return unlinkat(fd,p,flags);
}
int main(int argc,char**argv) {
 if(argc!=3)return 98;scenario=atoi(argv[2]);int work=open(argv[1],O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
 if(work<0)return 97;int source=-1,cleanup=0;struct stat sourcest;
"""
REAL_SETUP=r"""
 if(scenario!=0){if(mkdirat(work,"source",0700))return 96;source_created=1;}
 if(scenario>=2){source=openat(work,"source",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(source<0)return 95;}
 if(scenario>=4){if(fstat(source,&sourcest))return 94;ready=1;
"""
REAL_END=r"""
 if(scenario==6)sourcest.st_ino++; /* Recheck must refuse a mismatched saved identity. */
 if(scenario==7){int f=openat(source,"untracked",O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW,0600);if(f<0||close(f))return 93;}
 if(scenario==8&&close(source))return 92;
#define fstat observed_stat
#define unlinkat observed_unlink
"""
REAL_TAIL=r"""
#undef fstat
#undef unlinkat
 struct stat after;int exists=fstatat(work,"source",&after,AT_SYMLINK_NOFOLLOW)==0;
 int close_error=(source>=0&&scenario!=8&&close(source))||close(work);
 printf("{\"cleanup_error\":%s,\"source_remains\":%s,\"captured\":%s,\"identity_reads\":%d,\"invalid_identity_reads\":%d,\"rechecks\":%d,\"unlinks\":%d,\"close_error\":%s}\n",cleanup?"true":"false",exists?"true":"false",ready?"true":"false",reads,invalid,rechecks,unlinks,close_error?"true":"false");
 return close_error?91:0;
}
"""
def compile_real_cleanup(root,dest):
 helper,_,finish,_,_=source_parts(root)
 decl=re.search(r'  int source_created=.*?;',helper).group()+'\n'
 block=finish[finish.index('  if(source_created)'):finish.index('  if(recheck(&rt))')]
 # Instrument reads, not validity decisions; old helper still attempts invalid
 # reads and is rejected by the same tests without triggering undefined behavior.
 block=block.replace('sourcest.st_dev','identity_read(&sourcest,0)').replace('sourcest.st_ino','identity_read(&sourcest,1)')
 capture='source_identity_captured=1;' if 'source_identity_captured=1;' in helper else ''
 src=dest/'real-source-cleanup.c';src.write_text(REAL_HEADER+decl+REAL_SETUP+capture+'\n}\n'+REAL_END+block+REAL_TAIL)
 binary=dest/'real-source-cleanup';cmd=[CC]+SANITIZERS+['-std=c11','-O1','-Wall','-Wextra','-Wno-unused-variable',str(src),'-o',str(binary)]
 q=subprocess.run(cmd,capture_output=True,timeout=30)
 if q.returncode:raise RuntimeError(q.stderr.decode())
 return binary

class Tests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.tmp=tempfile.TemporaryDirectory(prefix='fixture diagnostic ');cls.binary=compile_boundary(ROOT,P(cls.tmp.name));cls.real_binary=compile_real_cleanup(ROOT,P(cls.tmp.name))
 @classmethod
 def tearDownClass(cls):cls.tmp.cleanup()
 def run_case(self,call,ordinal=0,error=5,cleanup=0,short=0):
  q=subprocess.run([str(self.binary),call,str(ordinal),str(error),str(cleanup),str(short)],capture_output=True,timeout=3)
  self.assertEqual(q.stderr,b'');self.assertLess(len(q.stdout),4096)
  for canary in (b'PRIVATE-PATH-CANARY-91',b'/synthetic/',b'/proc/self/fd/',b'/opt/',b'aaaaaaaaaaaaaaaa'):
   self.assertNotIn(canary,q.stdout)
  lines=q.stdout.splitlines();self.assertEqual(len(lines),2);report=json.loads(lines[0]);boundary=json.loads(lines[1])['boundary']
  self.assertEqual(report['adapter_invocations'],0);self.assertFalse(report['adapter_started']);self.assertIsNone(report['adapter_result'])
  return q.returncode,report,boundary
 def test_early_cleanup_never_reads_uncaptured_identity(self):
  # Fails on the previous helper at chown/stat: old cleanup attempted a saved
  # identity read although the initial fstat had never succeeded.
  for call,created in [('mkdir',False),('open',True),('chown',True),('stat',True)]:
   with self.subTest(call=call):
    rc,r,b=self.run_case(call)
    self.assertEqual(rc,1);self.assertEqual(r['primary'],'fixture_source_'+call)
    self.assertFalse(b['identity_captured']);self.assertEqual(b['identity_reads'],0)
    self.assertEqual(b['invalid_identity_reads'],0);self.assertEqual(b['cleanup_stats'],0)
    self.assertEqual(b['source_unlinks'],0)
    self.assertEqual(r['cleanup'],'UNKNOWN' if created else 'confirmed')
    self.assertEqual(r['cleanup_error'],created);self.assertEqual(r['fixture_diagnostic']['errno'],5)
 def test_captured_identity_rechecked_before_source_unlink(self):
  for call in ('statfs','backing_policy'):
   with self.subTest(call=call):
    _,r,b=self.run_case(call)
    self.assertTrue(b['identity_captured']);self.assertEqual(b['identity_reads'],2)
    self.assertEqual(b['invalid_identity_reads'],0);self.assertEqual(b['cleanup_stats'],1)
    self.assertEqual(b['source_unlinks'],1);self.assertEqual(r['cleanup'],'confirmed')
    self.assertEqual(r['primary'],'fixture_source_'+call)
 def test_source_recheck_mismatch_unlink_failure_preserves_primary(self):
  for fault,reads,unlinks in [(2,0,0),(3,2,0),(4,2,1)]:
   with self.subTest(fault=fault):
    _,r,b=self.run_case('statfs',cleanup=fault)
    self.assertEqual(r['primary'],'fixture_source_statfs');self.assertEqual(r['fixture_diagnostic']['errno'],5)
    self.assertTrue(r['cleanup_error']);self.assertEqual(r['cleanup'],'UNKNOWN')
    self.assertEqual(b['identity_reads'],reads);self.assertEqual(b['invalid_identity_reads'],0)
    self.assertEqual(b['source_unlinks'],unlinks)
 def test_real_directory_fd_cleanup_lifecycle(self):
  # True mkdir/open/fstat/unlink/close on owned disposable directories. Scenarios
  # 1-3 model the specified earlier syscall failure prestate; no root fchown or
  # mount is run. Scenario 5 is the sole cleanup-syscall fault boundary.
  for scenario in range(9):
   with self.subTest(scenario=scenario),tempfile.TemporaryDirectory(prefix='fixture cleanup ') as td:
    q=subprocess.run([str(self.real_binary),td,str(scenario)],capture_output=True,timeout=3)
    self.assertEqual(q.returncode,0);self.assertEqual(q.stderr,b'');self.assertLess(len(q.stdout),512)
    r=json.loads(q.stdout);self.assertFalse(r['close_error']);self.assertEqual(r['invalid_identity_reads'],0)
    self.assertEqual(r['captured'],scenario>=4)
    self.assertEqual(r['cleanup_error'],scenario not in (0,4))
    self.assertEqual(r['source_remains'],scenario not in (0,4))
    self.assertEqual(r['rechecks'],int(scenario>=4))
    self.assertEqual(r['unlinks'],int(scenario in (4,7)))
    if scenario<4:self.assertEqual(r['identity_reads'],0)
    # Harness cleanup below is distinct from helper cleanup; it removes only this
    # test's retained fixture after checking UNKNOWN/remaining-state evidence.
    self.assertEqual((P(td)/'source').exists(),r['source_remains'])
 def test_each_direct_failure_errno_and_cleanup_retention(self):
  for call in ('mkdir','open','chown','stat','statfs','mountpoint','bind','random'):
   for cleanup in (0,1):
    with self.subTest(call=call,cleanup=cleanup):
     rc,r,b=self.run_case(call,cleanup=cleanup);self.assertEqual(rc,1);self.assertEqual(r['primary'],'fixture_source_'+call)
     self.assertEqual({k:r['fixture_diagnostic'][k] for k in ('errno','fs_magic','entry')},{'errno':5,'fs_magic':0xef53 if call in ('mountpoint','bind','random') else None,'entry':None});self.assertEqual(b['errno_reset_violations'],0)
     if cleanup:self.assertTrue(r['cleanup_error']);self.assertEqual(r['cleanup'],'UNKNOWN')
 def test_reset_and_failure_without_errno(self):
  for call in ('mkdir','open','chown','stat','statfs','mountpoint','bind','random'):
   with self.subTest(call=call):
    _,r,b=self.run_case(call,error=-1);self.assertIsNone(r['fixture_diagnostic']['errno']);self.assertEqual(b['errno_reset_violations'],0)
 def test_backing_policy_is_observation_not_statfs_error(self):
  _,r,b=self.run_case('backing_policy');self.assertEqual(r['primary'],'fixture_source_backing_policy');self.assertEqual({k:r['fixture_diagnostic'][k] for k in ('errno','fs_magic','entry')},{'errno':None,'fs_magic':0x01021994,'entry':None})
  self.assertNotIn('mountpoint',[c['name'] for c in b['calls']]);_,r,_=self.run_case('statfs');self.assertIsNone(r['fixture_diagnostic']['fs_magic'])
 def test_bind_vs_mountinfo_no_composite_errno_claim(self):
  for call,error in [('bind',13),('mountinfo',13),('mountinfo',0),('mountinfo',-1)]:
   with self.subTest(call=call,error=error):
    _,r,b=self.run_case(call,error=error);self.assertEqual(r['primary'],'fixture_source_'+call);self.assertEqual(r['fixture_diagnostic']['errno'],13 if call=='bind' else None);self.assertEqual(b['errno_reset_violations'],0)
 def test_all_remaining_source_operations_and_ordinals(self):
  for call,count in [('fdpath',1),('paths',2),('directory',5),('lock',2),('dotenv',1),('compose',3)]:
   for n in range(count):
    with self.subTest(call=call,n=n):
     rc,r,_=self.run_case(call,n);self.assertEqual(rc,1);self.assertEqual(r['primary'],'fixture_source_'+call)
     self.assertEqual({k:r['fixture_diagnostic'][k] for k in ('errno','fs_magic','entry')},{'errno':None,'fs_magic':0xef53,'entry':n if call in ('directory','lock','compose') else None})
 def test_fd_budget_telemetry_survives_later_primary(self):
  _,r,_=self.run_case('bind',error=22)
  self.assertEqual(r['primary'],'fixture_source_bind')
  self.assertEqual(r['fd_budget'],dict(nofile_soft=1038,nofile_hard=4096,open_fd_count=520,required_additional_peak=518,required_total_peak=1038,fd_budget_sufficient=True))
 def test_numeric_diagnostic_budget(self):
  _,r,_=self.run_case('bind',error=2147483647);self.assertEqual(r['fixture_diagnostic']['errno'],2147483647)
  self.assertLess(len(json.dumps(r).encode()),4096)
 def test_short_random_has_no_errno(self):
  _,r,_=self.run_case('none',short=1);self.assertEqual(r['primary'],'fixture_source_random');self.assertIsNone(r['fixture_diagnostic']['errno'])
 def test_region_success_and_order_not_native_pass(self):
  rc,r,b=self.run_case('none');self.assertEqual(rc,0);self.assertEqual(r['primary'],'fixture_source_region_complete');self.assertEqual({k:r['fixture_diagnostic'][k] for k in ('errno','fs_magic','entry')},{'errno':None,'fs_magic':0xef53,'entry':None})
  self.assertEqual(b['errno_reset_violations'],0)
  names=[c['name'] for c in b['calls']];self.assertEqual(names[:11],['mkdir','open','chown','stat','statfs','mountpoint','fdpath','bind','mountinfo','paths','paths'])
 def test_bind_einval_retains_probe_and_single_attempt(self):
  _,r,b=self.run_case('bind',error=22);d=r['fixture_diagnostic']
  self.assertEqual(r['primary'],'fixture_source_bind');self.assertEqual(d['errno'],22)
  self.assertEqual(d['fs_magic'],0xef53);self.assertEqual(d['probe_status'],'complete')
  self.assertEqual((d['source_mount_class'],d['target_mount_class']),('private','private'))
  self.assertEqual(d['source_submount_count'],0);self.assertEqual(d['userns_class'],'initial_identity')
  self.assertEqual(sum(c['name']=='bind' for c in b['calls']),1);self.assertEqual(r['cleanup'],'confirmed')
 def test_incomplete_probe_and_probe_close_error_do_not_replace_bind(self):
  for call in ('probe_missing','probe_close'):
   _,r,b=self.run_case(call,error=22);d=r['fixture_diagnostic']
   self.assertEqual(r['primary'],'fixture_source_bind');self.assertEqual(d['errno'],22)
   self.assertEqual(d['probe_status'],'incomplete');self.assertEqual(d['probe_cleanup_error'],call=='probe_close')
   self.assertEqual(r['cleanup_error'],call=='probe_close');self.assertEqual(sum(c['name']=='bind' for c in b['calls']),1)
 def test_actual_driver_capture_write_artifact_preserves_diagnostic(self):
  spec=importlib.util.spec_from_file_location('diagnostic_driver',ROOT/'tests/native-driver.py');driver=importlib.util.module_from_spec(spec);spec.loader.exec_module(driver)
  # Actual capture + writer/checker, but no native driver main or sudo call.
  commands=[];raw=driver.capture([str(self.binary),'bind','0','13','1','0'],3,commands,require_exit=False)
  helper=json.loads(raw.splitlines()[0]);self.assertEqual(commands[-1]['exit'],1)
  with tempfile.TemporaryDirectory() as td:
   root=P(td).resolve();folder=root/'clb91-native-adapter/evidence';folder.mkdir(parents=True)
   driver.write_evidence(folder,{'result.json':{'primary':'native_fixture_exit','native_tests':helper}})
   spec=importlib.util.spec_from_file_location('diagnostic_artifacts',ROOT/'ci/check-artifacts.py');checker=importlib.util.module_from_spec(spec);spec.loader.exec_module(checker)
   self.assertEqual(checker.check(root)['checked_files'],1);saved=json.loads((folder/'result.json').read_bytes());self.assertEqual(saved['native_tests']['fixture_diagnostic']['errno'],13);self.assertTrue(saved['native_tests']['cleanup_error']);self.assertEqual(saved['native_tests']['fixture_diagnostic']['source_mount_class'],'private')

if __name__=='__main__':
 ap=argparse.ArgumentParser();ap.add_argument('--root',type=P,default=HERE.parent);ap.add_argument('--cc',default='cc');ap.add_argument('--ubsan',action='store_true');a=ap.parse_args();ROOT=a.root.resolve();CC=a.cc;SANITIZERS=['-fsanitize=undefined','-fno-sanitize-recover=all'] if a.ubsan else []
 result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Tests))
 print(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skips':len(result.skipped),'verdict':'PASS' if result.wasSuccessful() else 'FAIL','ubsan':bool(SANITIZERS),'scope':'exact source region + finish/report, instrumented saved-identity reads, real unprivileged source cleanup; actual Python capture/writer/checker; native-only setup and privileged syscalls NOT_RUN'}));raise SystemExit(not result.wasSuccessful())
