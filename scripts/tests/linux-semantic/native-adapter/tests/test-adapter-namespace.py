#!/usr/bin/env python3
"""Adapter child actual handoff -> private_view -> cleanup; no root/kernel namespaces.
Individual syscall/lease/verifier boundaries only. Parent recheck excerpt executes
on parent anchors. Actual bounded parsers and child-error classifier are included.
"""
import argparse,hashlib,importlib.util,json,pathlib,re,subprocess,tempfile,unittest
P=pathlib.Path;HERE=P(__file__).resolve().parent
s=importlib.util.spec_from_file_location('helper_handoff_tests',HERE/'test-namespace-handoff.py');h=importlib.util.module_from_spec(s);s.loader.exec_module(h)
EXTRA=r'''
#define MS_REMOUNT 32
#define MS_RDONLY 1
#define MS_NOEXEC 8
#define OUTPUT_LIMIT 4096
struct statfs {long f_type;};
struct captured {int code;size_t err_used,err_bytes;unsigned char err[64];};
static unsigned semantic_calls,backing_reads,tuple_reads,source_leased,private_rechecks,cleanup_attempts;
static unsigned parent_checks[3],parent_stat;static int private_bind_fd[2],cleanup_finished;
static char child_stderr[64];static size_t child_stderr_n;static int child_exit;
static int t_fstatfs(int fd,struct statfs *fs){if(fds[fd].kind!=6||!fds[fd].live)abort();backing_reads++;fs->f_type=is("backing")?0x01021994:0xef53;return is("statfs")?-1:0;}
static struct mount_tuple parent_tuple={"/original","/opt/clubs-bot-stage","ext4","PRIVATE_DEVICE_CANARY"};
static int read_mount_tuple(struct mount_tuple *out){tuple_reads++;*out=parent_tuple;if(is("tuple")&&tuple_reads==1)strcpy(out->source,"OTHER_PRIVATE_CANARY");return is("tuple_read")?-1:0;}
static int lease_source(int fd,struct lease_set *ls){if(fds[fd].kind!=6||!fds[fd].live)abort();source_leased++;ls->n=1;ls->fd=fd;if(fds[fd].st.st_uid!=1000||fds[fd].st.st_gid!=1000||(fds[fd].st.st_mode&07777)!=0700)abort();return is("source_lease")?-1:0;}
static int safe_runtime_path(int root,const char*p){if(fds[root].kind!=1||fds[root].ns!=1||strcmp(p,"/tmp/clb91-native-PRIVATE_CANARY_91/runtime"))abort();return is("path")?-1:0;}
static int verify_view_source(int src,const struct lease_set *ls){if(fds[src].ns!=1||ls->fd!=src)abort();return is("view_source")?-1:0;}
static int t_chdir(const char*p){(void)p;return 0;}
static int t_chroot(const char*p){(void)p;return is("chroot")?-1:0;}
static void t_exit(int code){child_exit=code;longjmp(done,1);}
static ssize_t t_write(int fd,const void*b,size_t n){if(fd!=2||child_stderr_n+n>=sizeof child_stderr)abort();memcpy(child_stderr+child_stderr_n,b,n);child_stderr_n+=n;child_stderr[child_stderr_n]=0;return n;}
static void mount_null_device(const char*p){(void)p;/* separate unchanged /dev/null syscall branch */}
#define fstatfs t_fstatfs
#define chdir t_chdir
#define chroot t_chroot
#define write t_write
#define _exit t_exit
'''
MAIN=r'''
static void final_parent_rechecks(void) {
 struct lease_set boundary={1,10},runtime={1,12},source={1,13};struct stat original=fds[13].st,after;int src=13,rechecks=0,code=0;
 @PARENT@
 printf("\"parent_rechecks\":%s,",rechecks?"true":"false");
}
int main(int argc,char**argv) {
 if(argc!=2)return 98;scenario=argv[1];populate(10,1,0);populate(11,2,0);populate(12,3,0);populate(13,6,0);fds[13].st.st_uid=fds[13].st.st_gid=1000;fds[13].st.st_mode=S_IFDIR|0700;current_ns=1;
 struct lease_set boundary={1,10},runtime={1,12},source={1,13};
 struct namespace_args a={.runtime="/tmp/clb91-native-PRIVATE_CANARY_91/runtime",.runtimefd=12,.srcfd=13,.boundary=&boundary,.runtime_leases=&runtime,.source=&source,.outer=&parent_tuple,.in=0,.out=1,.err=2};a.runtime_identity=fds[12].st;a.source_identity=fds[13].st;
 if(is("old_model")||is("old_source_model")) {
  if(is("old_source_model"))a.runtimefd=alloc(3,1);
  /* Exact old private_view call receives parent runtime/source in child model. */
  if(!setjmp(done))private_view(a.runtime,a.runtimefd,a.srcfd,a.source,a.outer);
 }else {if(budget_guard_model())return 97;if(!setjmp(done))namespace_child(&a);}
 struct captured r={.code=child_exit,.err_used=child_stderr_n,.err_bytes=child_stderr_n};memcpy(r.err,child_stderr,child_stderr_n);
 unsigned live_child=0;for(int i=40;i<256;i++)live_child+=fds[i].live;
 printf("{\"fd_budget\":");print_fd_budget(&namespace_budget);printf(",");final_parent_rechecks();
 printf("\"primary\":\"%s\",\"exit\":%d,\"semantic_calls\":%u,\"binds\":%d,\"mount_fd_ns\":[%d,%d],\"mount_fd_ids\":[%d,%d],\"runtime_verifier_fd\":%d,\"source_leased\":%u,\"tuple_reads\":%u,\"child_live_fds\":%u,\"parent_live\":[%d,%d,%d],\"parent_closed\":[%d,%d,%d],\"parent_checks\":[%u,%u,%u],\"cleanup_finished\":%s,\"cleanup_attempts\":%u,\"invalid_close\":%d,\"error_bytes\":%zu}\n",child_error(&r),child_exit,semantic_calls,binds,binds?fds[private_bind_fd[0]].ns:-1,binds>1?fds[private_bind_fd[1]].ns:-1,private_bind_fd[0],private_bind_fd[1],verify_fd,source_leased,tuple_reads,live_child,fds[10].live,fds[12].live,fds[13].live,closes[10],closes[12],closes[13],parent_checks[0],parent_checks[1],parent_checks[2],cleanup_finished?"true":"false",cleanup_attempts,invalid_close,child_stderr_n);
 return 0;
}
'''
def build(root,dest):
 adapter=(root/'adapter/adapter.c').read_text();pre=h.PRE
 # Compose REAL parent admission with real child EMFILE/cleanup flow in this
 # executable. Only getrlimit/procfs and downstream namespace calls are modeled.
 a=pre.index('/* Read-only admission syscall/contract boundary.');b=pre.index('static int closes[256]',a);pre=pre[:a]+pre[b:]
 spec=importlib.util.spec_from_file_location('fd_budget_boundary',root/'tests/test-fd-budget.py');fb=importlib.util.module_from_spec(spec);spec.loader.exec_module(fb)
 bp=fb.PRE.replace('static volatile int interrupted;','').replace('static const char *scenario;','').replace('static int is(const char*s){return !strcmp(scenario,s);}','')
 bp=re.sub(r'\bcloses\b','budget_closes',bp)
 kinds=re.findall(r'^\{"[^"\n]+",([123]),',(root/'adapter/generated_contract.h').read_text(),re.M)
 tables=adapter[adapter.index('static const char*srcdirs[]='):adapter.index('static int source_optional(')]
 budget='\n#undef open\n#undef close\n'+bp+'\n#include '+json.dumps(str(root/'adapter/fd-budget.h'))+'\n'+fb.POST[:fb.POST.index('static void setup_names(void)')]+tables
 budget+='''
static int budget_guard_model(void) {
 const int kinds[]={'''+','.join(kinds)+'''};
 model_count=sizeof kinds/sizeof *kinds;for(size_t i=0;i<model_count;i++)model_entries[i].kind=kinds[i];
 observed_soft=1098;observed_hard=4096;names_n=read_at=0;add_name(".");add_name("..");
 for(int i=0;i<555;i++)add_id(i);add_id(own_fd);
 return adapter_fd_budget(&namespace_budget);
}
#define open t_open
#define close t_close
'''

 pre=pre.replace('#include <setjmp.h>','#include <setjmp.h>\n#include <sys/resource.h>')
 pre=pre.replace('struct mount_tuple {int x;};','struct mount_tuple {char root[MAX_PATH],target[MAX_PATH],type[32],source[MAX_PATH];};')
 pre=pre.replace('static int alloc(', 'static unsigned parent_checks[3];\nstatic int alloc(')
 # Declarations later needed by syscall boundary; EXTRA does not redeclare them.
 pre=pre.replace('static int closes[256]', 'static int private_bind_fd[2],cleanup_finished;\nstatic unsigned cleanup_attempts;\nstatic int closes[256]')
 pre=pre.replace('if(kind==2)fds[q].st=fds[11].st;', 'if(kind==2)fds[q].st=fds[11].st;if(kind==6)fds[q].st=fds[13].st;')
 pre=pre.replace('unsigned id=fds[fd].ns?307:27;', 'unsigned id=fds[fd].ns?(fds[fd].kind==6?308:307):(fds[fd].kind==6?28:27);')
 pre=pre.replace('if((is("work_mount_missing")', 'if((is("runtime_fd_stale")&&fds[fd].kind==3)||(is("source_fd_stale")&&fds[fd].kind==6))id=27;\n  if((is("work_mount_missing")')
 pre=pre.replace('PRIVATE_DEVICE_CANARY rw\\n");\n  if(is("duplicate"))', 'PRIVATE_DEVICE_CANARY rw\\n308 307 8:1 /original /opt/clubs-bot-stage rw - ext4 PRIVATE_DEVICE_CANARY rw\\n");\n  if(is("duplicate"))',1)
 start=pre.index('static void child_fail(');end=pre.index('static int t_dup2',start);pre=pre[:start]+pre[end:]
 start=pre.index('static void private_view(');end=pre.index('#define open ',start);pre=pre[:start]+pre[end:]
 start=pre.index('static int t_mount(');end=pre.index('static int t_mkdirat',start)
 pre=pre[:start]+r'''static int t_mount(const char*src,const char*dst,const char*type,unsigned long flags,const void*data) {
 (void)type;(void)data;
 if(flags==MS_BIND){unsigned fd;if(sscanf(src,"/proc/self/fd/%u",&fd)!=1||fd>=256||!fds[fd].live||binds>=2)abort();
  if((binds==0&&strcmp(dst,"/tmp/clb91-native-PRIVATE_CANARY_91/runtime"))||(binds==1&&strcmp(dst,"/tmp/clb91-native-PRIVATE_CANARY_91/runtime/opt/clubs-bot-stage")))abort();
  private_bind_fd[binds++]=fd;
  if(fds[fd].ns!=current_ns||is("mount_fail")||is("mount_close")){errno=EINVAL;return -1;}return 0;
 }
 if(flags==(MS_REC|MS_PRIVATE)){if(src||strcmp(dst,"/"))abort();return 0;}
 return 0; /* unchanged remount/tmpfs/proc actions: syscall-only model */
}
'''+pre[end:]
 # Fail closed at the verifier's real descriptor-allocation error boundary.
 pre=pre.replace('return is("runtime_contract")?-1:0;', 'if(is("fd_exhausted")){errno=EMFILE;return -1;}return is("runtime_contract")?-1:0;')
 # Explicit ledger of parent and child lease use. Parent descriptors never close.
 pre=pre.replace('return is("recheck")?-1:0;', 'if(s->fd==10)parent_checks[0]++;if(s->fd==12)parent_checks[1]++;if(s->fd==13)parent_checks[2]++;return is("recheck")||(is("source_drift")&&s->n&&fds[s->fd].kind==6&&s->fd>=40)?-1:0;')
 pre=pre.replace('return is("close_leases")&&old==12?-1:0;', 'cleanup_attempts++;return is("close_leases")&&old>=40?-1:0;')
 pre=pre.replace('(is("close_anchor")&&fd==11)', '(is("close_current")&&fds[fd].kind==3&&fd>=40)||(is("mount_close")&&fds[fd].kind==3&&fd>=40)')
 extra=EXTRA.replace('static unsigned parent_checks[3],parent_stat;static int private_bind_fd[2],cleanup_finished;', 'static unsigned parent_stat;').replace(',cleanup_attempts;', ';')
 support='\n'.join(h.function(adapter,n) for n in ('same_stat','test_work_path','open_beneath','supported_backing','tuple_equal','child_path'))
 funcs=adapter[adapter.index('/* Parent FDs remain trust anchors.'):adapter.index('static void close_extra_fds(')]
 view=adapter[adapter.index('static void private_view('):adapter.index('static void namespace_child(')]
 child=adapter[adapter.index('static void namespace_child('):adapter.index('  r.rlim_cur=r.rlim_max=768ULL',adapter.index('static void namespace_child('))]+'  cleanup_finished=1;semantic_calls++;\n}\n'
 parent=adapter[adapter.index('  rechecks=!(recheck(&boundary)'):adapter.index('  finish:alarm(0);')]
 classifier=adapter[adapter.index('static const char*child_error('):adapter.index('static int semantic_body(')]
 body=pre+extra+budget+support+'\n#include '+json.dumps(str(root/'tests/fixture-bind-probe.h'))+'\n'+funcs+view+child+classifier+MAIN.replace('@PARENT@',parent)
 src=dest/'adapter-boundary.c';src.write_text(body);out=dest/'adapter-boundary'
 q=subprocess.run([CC,*SAN,'-std=c11','-O1','-Wall','-Wextra','-Wno-unused-function','-Wno-unused-variable','-Wno-misleading-indentation',str(src),'-o',str(out)],capture_output=True,timeout=30)
 if q.returncode:raise RuntimeError(q.stderr.decode())
 return out
class Adapter(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.tmp=tempfile.TemporaryDirectory(prefix='adapter handoff ');cls.t=P(cls.tmp.name);cls.bin=build(HERE.parent,cls.t)
 @classmethod
 def tearDownClass(cls):cls.tmp.cleanup()
 def run_case(self,case):
  q=subprocess.run([str(self.bin),case],capture_output=True,timeout=5);self.assertEqual(q.returncode,0,q.stderr.decode());self.assertFalse(q.stderr);self.assertLess(len(q.stdout),4096)
  for value in (b'PRIVATE',b'/proc/',b'/tmp/',b'ext4'):self.assertNotIn(value,q.stdout)
  return json.loads(q.stdout)
 def refused(self,case,reason):
  r=self.run_case(case);self.assertEqual(r['primary'],reason);self.assertEqual(r['exit'],120);self.assertEqual(r['semantic_calls'],0);self.assertEqual(r['binds'],0);self.assertEqual(r['child_live_fds'],0);self.assertEqual(r['parent_live'],[1,1,1]);self.assertEqual(r['parent_closed'],[0,0,0]);self.assertEqual(r['invalid_close'],0);return r
 def test_01_old_model(self):
  r=self.run_case('old_model');self.assertEqual(r['primary'],'runtime_mount');self.assertEqual(r['binds'],1);self.assertEqual(r['mount_fd_ns'][0],0);self.assertEqual(r['semantic_calls'],0)
 def test_02_current_handles_two_bind_calls(self):
  r=self.run_case('ok');self.assertEqual(r['semantic_calls'],1);self.assertEqual(r['binds'],2);self.assertEqual(r['mount_fd_ns'],[1,1]);self.assertNotIn(12,r['mount_fd_ids']);self.assertNotIn(13,r['mount_fd_ids']);self.assertEqual(r['runtime_verifier_fd'],r['mount_fd_ids'][0]);self.assertEqual(r['source_leased'],1);self.assertEqual(r['tuple_reads'],2);self.assertTrue(r['cleanup_finished']);self.assertEqual(r['child_live_fds'],0);self.assertEqual(r['parent_live'],[1,1,1]);self.assertTrue(r['parent_rechecks']);self.assertTrue(all(x>=2 for x in r['parent_checks']))
 def test_03_stale_runtime_rejected(self):self.refused('runtime_fd_stale','child_runtime_mount')
 def test_04_stale_source_rejected(self):self.refused('source_fd_stale','child_source_mount')
 def test_05_runtime_identity(self):self.refused('runtime_mismatch','child_runtime_identity')
 def test_06_source_identity(self):self.refused('source_mismatch','child_source_identity')
 def test_07_runtime_contract(self):self.refused('runtime_contract','child_runtime_contract')
 def test_08_source_lease_drift(self):self.refused('source_drift','child_anchor_recheck')
 def test_09_outer_tuple(self):self.refused('tuple','child_outer_tuple');self.refused('tuple_read','child_outer_tuple')
 def test_10_lease_creation(self):self.refused('source_lease','child_source_leases')
 def test_11_missing_member(self):self.refused('runtime_mount_missing','child_runtime_mount');self.refused('source_mount_missing','child_source_mount')
 def test_12_symlink_path(self):self.refused('runtime_symlink','child_runtime_open');self.refused('path','child_runtime_path')
 def test_13_backing(self):self.refused('backing','child_source_backing');self.refused('statfs','child_source_backing')
 def test_14_primary_plus_cleanup(self):
  r=self.run_case('mount_close');self.assertEqual(r['primary'],'runtime_mount+child_cleanup');self.assertEqual(r['exit'],120);self.assertEqual(r['semantic_calls'],0);self.assertEqual(r['child_live_fds'],0);self.assertLessEqual(r['error_bytes'],64)
 def test_15_cleanup_before_semantic(self):
  for case in ('close_current','close_leases'):
   r=self.run_case(case);self.assertEqual(r['primary'],'child_cleanup');self.assertEqual(r['semantic_calls'],0);self.assertEqual(r['binds'],2);self.assertEqual(r['child_live_fds'],0);self.assertEqual(r['parent_closed'],[0,0,0])
 def test_16_proc_close_secondary(self):self.refused('close_proc','child_runtime_mount+child_cleanup')
 def test_17_parent_recheck_refusal_preserved(self):
  r=self.refused('recheck','child_anchor_recheck');self.assertFalse(r['parent_rechecks'])
 def test_18_view_failure_cleanup(self):
  for case,reason in [('chroot','chroot'),('view_source','view_identity')]:
   r=self.run_case(case);self.assertEqual(r['primary'],reason);self.assertEqual(r['semantic_calls'],0);self.assertEqual(r['child_live_fds'],0)
 def test_19_no_bind_api_flags_retry(self):
  s=(HERE.parent/'adapter/adapter.c').read_text();v=s[s.index('static void private_view('):s.index('static void namespace_child(')];self.assertEqual(v.count('mount(orig,runtime,NULL,MS_BIND,NULL)'),1);self.assertEqual(v.count('mount(orig,dst,NULL,MS_BIND,NULL)'),1)
  for bad in ('open_tree','move_mount','mount_setattr','MS_BIND|MS_REC'):self.assertNotIn(bad,s)
  self.assertIn('CLONE_NEWNS|CLONE_NEWNET|CLONE_NEWPID|SIGCHLD',s)

 def test_20_parent_and_contract_blocks_byte_identical(self):
  text=(HERE.parent/'adapter/adapter.c').read_text()
  for label,start,end,expected in [('parent_rechecks_cleanup', '  launched=1;', '  printf("{\\\"adapter', 'cf4057a0f6302a1c9ba2e4913e7dd1b08f00b0e0fa00d51013d1aba735b4d2cf'), ('private_view', 'static void private_view(', 'static void namespace_child(', 'c93a060cb1467ac358812c6cf5f52c63960c492818200bb065d12f9c0364ebf9'), ('lease_source', 'static int lease_source(', 'static int verify_view_source(', 'b6292db619ca4a6274e325d754bc3ed3fe9df5e3dd1814a732f79ac7cacb1c3e'), ('verify_runtime', 'static int verify_runtime_owned(', '#if defined(__linux__) && !defined(ADAPTER_TEST)', 'fce54534544b51165942519aa83a1ab1a9f0ef579a52ef3cc9e322285bd69526'), ('auth', 'static int semantic_body(', '#if defined(__linux__) && !defined(ADAPTER_TEST)', 'cd764c3adbac8c2e9fb9dcfe336426f61831cc7645cb1d350eb2f5a6710da4c7')]:
   a=text.index(start);b=text.index(end,a);region=text[a:b]
   if label=='parent_rechecks_cleanup':
    # CLB-97 changes only capture mode here; retain the original byte-level
    # parent recheck/cleanup proof after reversing precisely that argument.
    added='perr[0],RUN_SECONDS,CLB97_WORKER_CAPTURE,&result);'
    self.assertEqual(region.count(added),1)
    region=region.replace(added,'perr[0],RUN_SECONDS,1,&result);')
   self.assertEqual(hashlib.sha256(region.encode()).hexdigest(),expected,label)

 def test_21_descriptor_exhaustion_refused_without_limit_change(self):
  r=self.refused('fd_exhausted','child_runtime_contract');self.assertGreater(r['cleanup_attempts'],0);self.assertTrue(r['fd_budget']['fd_budget_sufficient']);self.assertEqual(r['fd_budget']['required_total_peak'],1098)

 def test_22_old_source_model(self):
  r=self.run_case('old_source_model');self.assertEqual(r['primary'],'source_mount');self.assertEqual(r['binds'],2);self.assertEqual(r['mount_fd_ns'],[1,0]);self.assertEqual(r['semantic_calls'],0)

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--cc',default='/usr/bin/clang');p.add_argument('--ubsan',action='store_true');o,rest=p.parse_known_args();CC=o.cc;SAN=['-fsanitize=undefined','-fno-sanitize-recover=all'] if o.ubsan else [];unittest.main(argv=['test-adapter-namespace.py',*rest])
