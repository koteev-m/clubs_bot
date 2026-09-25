#!/usr/bin/env python3
"""CLB-97 actual emitter/capture/auth functions + actual extracted worker branch.
No root or namespaces. Model suites never mutate limits; the explicit benign
kernel-emitter case lowers only its own disposable child limits to 1024/1024. Individual syscall mocks only; real pipes,
fork, capture and authentication in transport cases. Static observations survive
longjmp. No mock exists in derivative production sources.
"""
import argparse,hashlib,json,pathlib,re,subprocess,tempfile,unittest
P=pathlib.Path;HERE=P(__file__).resolve().parent
PRE=r'''
#define _GNU_SOURCE 1
#include <sys/types.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
static const char *scenario;
static unsigned reads,writes,execs,groups,gids,uids,prctls,closes,order_bad;
static int real_pipe,exit_seen,step;
static char failure[64];static unsigned char observed[128];static size_t observed_n;
static jmp_buf done;
static int is(const char*s){return !strcmp(scenario,s);}
static int model_getrlimit(int resource,struct rlimit *r) {
 if(resource!=RLIMIT_NOFILE)abort();reads++;
 if(is("kernel"))return getrlimit(resource,r);
 if(step&&step!=5)order_bad++;
 r->rlim_cur=is("soft")?1023:1024;r->rlim_max=is("hard")?1023:1024;
 if(is("read")){errno=EINVAL;return -1;}return 0;
}
static ssize_t model_write(int fd,const void *buf,size_t n) {
 if(fd!=2||n>sizeof observed)abort();writes++;
 if(is("eintr")&&writes==1){errno=EINTR;return -1;}
 if(is("eintr_exhausted")){errno=EINTR;return -1;}
 if(is("write")){errno=EPIPE;return -1;}
 if(is("badfd")){errno=EBADF;return -1;}
 if(is("zero"))return 0;
 if(is("short")){observed_n=n-1;memcpy(observed,buf,n-1);return n-1;}
 observed_n=n;memcpy(observed,buf,n);
 return real_pipe?write(fd,buf,n):(ssize_t)n;
}
#define getrlimit model_getrlimit
#define write model_write
#include "@HEADER@"
#undef getrlimit
#undef write
#define ADAPTER_TEST 1
#include "@ADAPTER@"
static void model_fail(const char *why) {
 if(strlen(why)>=sizeof failure)abort();strcpy(failure,why);exit_seen=120;longjmp(done,1);
}
static int model_groups(size_t n,const gid_t *p){groups++;if(n||p||step!=0)order_bad++;step=1;return is("groups")?-1:0;}
static int model_gid(gid_t a,gid_t b,gid_t c){gids++;if(a!=1000||b!=1000||c!=1000||step!=1)order_bad++;step=2;return is("gid")?-1:0;}
static int model_uid(uid_t a,uid_t b,uid_t c){uids++;if(a!=1000||b!=1000||c!=1000||step!=2)order_bad++;step=3;return is("uid")?-1:0;}
static int model_prctl(int a,int b,int c,int d,int e){prctls++;if(a!=38||b!=1||c||d||e||step!=3)order_bad++;step=4;return is("prctl")?-1:0;}
static void model_close_extra(void){closes++;if(step!=4)order_bad++;step=5;if(is("close"))model_fail("descriptor_close");}
static int model_exec(const char*p,char*const argv[],char*const env[]) {
 execs++;
 if(step!=5||reads!=1||observed_n!=CLB97_WORKER_RECORD_BYTES||
 strcmp(p,"/usr/bin/python3.12")||strcmp(argv[0],p)||strcmp(argv[1],"-I")||strcmp(argv[2],"-S")||strcmp(argv[3],"-B")||strcmp(argv[4],"-c")||argv[5]!=(char*)BOOTSTRAP||argv[6]||
 strcmp(env[0],"PATH=/usr/bin:/bin")||strcmp(env[1],"HOME=/run/user/1000")||strcmp(env[2],"LC_ALL=C")||env[3])order_bad++;
 if(is("exec_failure"))return -1;
 longjmp(done,1);
}
#define setgroups model_groups
#define setresgid model_gid
#define setresuid model_uid
#define prctl model_prctl
#define PR_SET_NO_NEW_PRIVS 38
#define close_extra_fds model_close_extra
#define execve model_exec
#define child_fail model_fail
static void actual_worker_branch(void) {
@BRANCH@
}
#undef setgroups
#undef setresgid
#undef setresuid
#undef prctl
#undef close_extra_fds
#undef execve
#undef child_fail
static void semantic_frame(void) {
 unsigned char key[32]={0};char tag[65];
 const char *body="{\"body\":\"compose-env-semantic:v=1 result=equivalent strategy=remove scope=snapshot future=requires_recheck application=not_authorized\\n\",\"cleanup_errors\":[]}";
 hmac32(key,body,strlen(body),tag);
 char frame[1024];int n=snprintf(frame,sizeof frame,"clb91-isolated-auth:v=1 tag=%s %s\n",tag,body);
 if(write(1,frame,n)!=n)_exit(91);
}
static void transport_case(void) {
 int a[2],b[2],c[2];pid_t pid;struct captured r;unsigned char key[32]={0};const char*j=NULL;size_t len=0;
 if(pipe(a)||pipe(b)||pipe(c))exit(97);
 pid=fork();if(pid<0)exit(98);
 if(pid==0) {
  setpgid(0,0);dup2(a[0],0);dup2(b[1],1);dup2(c[1],2);
  close(a[0]);close(a[1]);close(b[0]);close(b[1]);close(c[0]);close(c[1]);
  signal(SIGPIPE,SIG_IGN);
  if(is("prior_failure")){write(2,"principal",sizeof "principal"-1);_exit(120);}
  if(is("prior_failure_prefix")){write(2,"child_cleanup",sizeof "child_cleanup"-1);_exit(120);}
  if(is("malformed")){write(2,"clb97:v1 WRONG\n",sizeof "clb97:v1 WRONG\n"-1);semantic_frame();_exit(0);}
  if(is("wrong_stage")){write(2,"clb97:v1 role=worker stage=post_exec soft=1024 hard=1024\n",sizeof "clb97:v1 role=worker stage=post_exec soft=1024 hard=1024\n"-1);semantic_frame();_exit(0);}
  if(is("wrong_role")){write(2,"clb97:v1 role=parent stage=pre_exec soft=1024 hard=1024\n",sizeof "clb97:v1 role=parent stage=pre_exec soft=1024 hard=1024\n"-1);semantic_frame();_exit(0);}
  if(is("partial")){write(2,CLB97_WORKER_RECORD,10);_exit(120);}
  if(is("oversized")){char x[4097];memset(x,'X',sizeof x);write(2,x,sizeof x);_exit(0);}
  if(!is("missing")&&!is("old_model_missing")) {
   if(is("chunks")){for(size_t i=0;i<CLB97_WORKER_RECORD_BYTES;i++)write(2,CLB97_WORKER_RECORD+i,1);}
   else {real_pipe=1;if(worker_measure_emit())_exit(120);}
  }
  if(is("duplicate"))write(2,CLB97_WORKER_RECORD,CLB97_WORKER_RECORD_BYTES);
  if(is("trailing"))write(2,"X",sizeof "X"-1);
  if(is("python_stderr"))write(2,"PRIVATE-PATH-CANARY",sizeof "PRIVATE-PATH-CANARY"-1);
  if(is("exec_after")){write(2,"exec",sizeof "exec"-1);_exit(120);}
  semantic_frame();_exit(0);
 }
 setpgid(pid,pid);close(a[0]);close(b[1]);close(c[1]);
 capture_child(pid,a[1],(const unsigned char*)"",0,b[0],c[0],5,
               (is("ordinary_capture")||is("old_model_missing"))?0:CLB97_WORKER_CAPTURE,&r);
 int accepted=!authenticated(&r,key,&j,&len);
 printf("{\"accepted\":%s,\"exit\":%d,\"primary\":\"%s\",\"child_error\":\"%s\",\"cleanup_error\":%s,\"err_bytes\":%zu,\"stderr_total\":%zu,\"evidence\":",accepted?"true":"false",r.code,r.primary,child_error(&r),r.cleanup?"true":"false",r.err_bytes,r.stderr_total);
 print_worker_record(&r);puts("}");
}
static void kernel_emitter_case(void) {
 struct rlimit parent_before,parent_after;int a[2],status;unsigned char data[128];ssize_t used=0,n;
 if(getrlimit(RLIMIT_NOFILE,&parent_before)||pipe(a))exit(97);
 pid_t pid=fork();if(pid<0)exit(98);
 if(pid==0) {
  struct rlimit low={1024,1024},before,after;unsigned char fds_before[1024],fds_after[1024];
  uid_t uid=getuid(),euid=geteuid();gid_t gid=getgid(),egid=getegid();
  close(a[0]);if(dup2(a[1],2)<0)_exit(91);close(a[1]);
  /* Disposable unprivileged fixture setup only, NOT instrumentation. */
  if(setrlimit(RLIMIT_NOFILE,&low)||getrlimit(RLIMIT_NOFILE,&before))_exit(92);
  for(int i=0;i<1024;i++)fds_before[i]=(fcntl(i,F_GETFD)>=0);
  real_pipe=1;const char *failure=worker_measure_emit();
  for(int i=0;i<1024;i++)fds_after[i]=(fcntl(i,F_GETFD)>=0);
  if(failure||getrlimit(RLIMIT_NOFILE,&after)||before.rlim_cur!=after.rlim_cur||before.rlim_max!=after.rlim_max||
     after.rlim_cur!=1024||after.rlim_max!=1024||uid!=getuid()||euid!=geteuid()||gid!=getgid()||egid!=getegid()||memcmp(fds_before,fds_after,sizeof fds_before))_exit(93);
  _exit(0);
 }
 close(a[1]);while(used<(ssize_t)sizeof data&&(n=read(a[0],data+used,sizeof data-(size_t)used))>0)used+=n;
 close(a[0]);if(waitpid(pid,&status,0)!=pid||getrlimit(RLIMIT_NOFILE,&parent_after))exit(94);
 int same=parent_before.rlim_cur==parent_after.rlim_cur&&parent_before.rlim_max==parent_after.rlim_max;
 printf("{\"child_exit\":%d,\"exact_record\":%s,\"parent_limits_unchanged\":%s,\"instrumentation_state_unchanged\":%s,\"fixture_child_setrlimit_calls\":1}\n",WIFEXITED(status)?WEXITSTATUS(status):-1,used==(ssize_t)CLB97_WORKER_RECORD_BYTES&&!memcmp(data,CLB97_WORKER_RECORD,used)?"true":"false",same?"true":"false",WIFEXITED(status)&&WEXITSTATUS(status)==0?"true":"false");
}
int main(int argc,char **argv) {
 if(argc!=3)return 99;scenario=argv[2];signal(SIGPIPE,SIG_IGN);
 if(!strcmp(argv[1],"kernel")){kernel_emitter_case();return 0;}
 if(!strcmp(argv[1],"transport")){transport_case();return 0;}
 if(!strcmp(argv[1],"branch")) {if(!setjmp(done))actual_worker_branch();}
 else {const char *e=worker_measure_emit();if(e)strcpy(failure,e);}
 printf("{\"failure\":\"%s\",\"reads\":%u,\"writes\":%u,\"execs\":%u,\"groups\":%u,\"gids\":%u,\"uids\":%u,\"prctls\":%u,\"closes\":%u,\"order_bad\":%u,\"record_bytes\":%zu,\"exact_record\":%s,\"exit\":%d}\n",failure,reads,writes,execs,groups,gids,uids,prctls,closes,order_bad,observed_n,observed_n==CLB97_WORKER_RECORD_BYTES&&!memcmp(observed,CLB97_WORKER_RECORD,observed_n)?"true":"false",exit_seen);
 return 0;
}
'''

def build(dest):
    text=(ROOT/'adapter/adapter.c').read_text()
    start=text.index('  if(worker==0)  {',text.index('static void namespace_child('))
    end=text.index('  /* PID1 holds',start)
    branch=text[start:end].replace('  if(worker==0)  {','  {',1)
    src=PRE.replace('@BRANCH@',branch).replace('@HEADER@',str(ROOT/'adapter/worker-evidence.h')).replace('@ADAPTER@',str(ROOT/'adapter/adapter.c'))
    (dest/'boundary.c').write_text(src)
    out=dest/'boundary'
    q=subprocess.run([CC,'-std=c11','-O1','-Wall','-Wextra','-Wno-unused-function','-Wno-misleading-indentation',*SAN,str(dest/'boundary.c'),'-o',str(out)],capture_output=True,timeout=60)
    if q.returncode:raise RuntimeError(q.stderr.decode())
    return out

class WorkerEvidence(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory(prefix='clb97 worker ');cls.binary=build(P(cls.tmp.name))
    @classmethod
    def tearDownClass(cls):cls.tmp.cleanup()
    def run_case(self,kind,case):
        q=subprocess.run([str(self.binary),kind,case],capture_output=True,timeout=10)
        self.assertEqual(q.returncode,0,q.stderr.decode());self.assertFalse(q.stderr);self.assertLess(len(q.stdout),4096)
        self.assertNotIn(b'CANARY',q.stdout);self.assertNotIn(b'/proc/',q.stdout)
        return json.loads(q.stdout)
    def branch_refuses(self,case,error):
        r=self.run_case('branch',case);self.assertEqual(r['execs'],0);self.assertEqual(r['failure'],error);self.assertEqual(r['exit'],120)
    def test_01_emit_exact(self):
        r=self.run_case('emit','success');self.assertEqual(r['record_bytes'],56);self.assertTrue(r['exact_record']);self.assertEqual(r['writes'],1)
    def test_02_soft(self):self.branch_refuses('soft','worker_nofile_mismatch')
    def test_03_hard(self):self.branch_refuses('hard','worker_nofile_mismatch')
    def test_04_read_error(self):self.branch_refuses('read','worker_nofile_read')
    def test_05_short_write(self):self.branch_refuses('short','worker_record_write')
    def test_06_zero_write(self):self.branch_refuses('zero','worker_record_write')
    def test_07_eintr(self):
        r=self.run_case('branch','eintr');self.assertEqual(r['writes'],2);self.assertEqual(r['execs'],1);self.assertEqual(r['order_bad'],0)
    def test_08_eintr_bound(self):
        r=self.run_case('branch','eintr_exhausted');self.assertEqual(r['writes'],8);self.assertEqual(r['execs'],0);self.assertEqual(r['failure'],'worker_record_write')
    def test_09_epipe(self):self.branch_refuses('write','worker_record_write')
    def test_10_ebadf(self):self.branch_refuses('badfd','worker_record_write')
    def test_11_actual_branch_order_and_argv(self):
        r=self.run_case('branch','success')
        for k in ['reads','writes','execs','groups','gids','uids','prctls','closes']:self.assertEqual(r[k],1,k)
        self.assertEqual(r['order_bad'],0);self.assertTrue(r['exact_record'])
    def test_12_security_failures_no_record(self):
        for case in ['groups','gid','uid','prctl','close']:
            r=self.run_case('branch',case);self.assertEqual(r['writes'],0);self.assertEqual(r['execs'],0);self.assertEqual(r['reads'],0)
    def test_13_exec_failure_after_record(self):
        r=self.run_case('branch','exec_failure');self.assertTrue(r['exact_record']);self.assertEqual(r['execs'],1);self.assertEqual(r['failure'],'exec')
    def test_14_transport_positive(self):
        r=self.run_case('transport','success');self.assertTrue(r['accepted']);self.assertEqual(r['stderr_total'],56);self.assertEqual(r['err_bytes'],0);self.assertTrue(r['evidence']['identity_verified']);self.assertFalse(r['cleanup_error'])
    def test_15_duplicate(self):self.assertFalse(self.run_case('transport','duplicate')['accepted'])
    def test_16_missing(self):
        r=self.run_case('transport','missing');self.assertFalse(r['accepted']);self.assertEqual(r['primary'],'worker_evidence');self.assertEqual(r['evidence']['status'],'MISSING')
    def test_17_malformed(self):self.assertFalse(self.run_case('transport','malformed')['accepted'])
    def test_18_trailing(self):self.assertFalse(self.run_case('transport','trailing')['accepted'])
    def test_19_python_stderr(self):self.assertFalse(self.run_case('transport','python_stderr')['accepted'])
    def test_20_exec_error_tail_preserved(self):
        r=self.run_case('transport','exec_after');self.assertFalse(r['accepted']);self.assertEqual(r['child_error'],'exec');self.assertEqual(r['exit'],120);self.assertEqual(r['err_bytes'],4);self.assertFalse(r['cleanup_error'])
    def test_21_prior_errors_preserved(self):
        for case,error in [('prior_failure','principal'),('prior_failure_prefix','child_cleanup')]:
            r=self.run_case('transport',case);self.assertFalse(r['accepted']);self.assertEqual(r['child_error'],error);self.assertEqual(r['primary'],'none');self.assertFalse(r['cleanup_error'])
    def test_22_partial(self):
        r=self.run_case('transport','partial');self.assertFalse(r['accepted']);self.assertEqual(r['err_bytes'],10);self.assertEqual(r['evidence']['status'],'INVALID')
    def test_23_wrong_role_and_stage(self):
        for case in ['wrong_role','wrong_stage']:self.assertFalse(self.run_case('transport',case)['accepted'])
    def test_24_oversized(self):
        r=self.run_case('transport','oversized');self.assertFalse(r['accepted']);self.assertEqual(r['primary'],'stderr_bounds')
    def test_25_byte_chunks(self):self.assertTrue(self.run_case('transport','chunks')['accepted'])
    def test_26_helper_parent_ordinary_channel_refuses(self):
        r=self.run_case('transport','ordinary_capture');self.assertFalse(r['accepted']);self.assertEqual(r['evidence']['status'],'NOT_RUN');self.assertEqual(r['err_bytes'],56)
    def test_27_no_fd_or_state_mutators_in_emitter(self):
        text=(ROOT/'adapter/worker-evidence.h').read_text();body=text[text.index('static const char *worker_measure_emit('):]
        # Exhaustive call inventory of the actual small emitter. No setrlimit,
        # fd open/close/dup, allocation, credentials, mount or locale operation.
        calls=set(re.findall(r'\b([A-Za-z_][A-Za-z0-9_]*)\s*\(',body))-{'if','for','sizeof','worker_measure_emit'}
        self.assertEqual(calls,{'getrlimit','write'})
    def test_28_no_hidden_intervening_code(self):
        text=(ROOT/'adapter/adapter.c').read_text();start=text.index('  if(worker==0)  {');end=text.index('  /* PID1 holds',start);branch=text[start:end]
        self.assertEqual(branch.count('worker_measure_emit()'),1)
        suffix=branch.split('worker_measure_emit();',1)[1]
        self.assertEqual(suffix.split('execve(argv[0],argv,env);')[0].strip(),'if(measurement_error)child_fail(measurement_error);\n    }')
        self.assertLess(branch.index('close_extra_fds();'),branch.index('worker_measure_emit()'))
        self.assertLess(text.index('if(setrlimit(RLIMIT_NOFILE,&r))'),text.index('worker=fork();'))
    def test_29_exact_capture_ownership(self):
        text=(ROOT/'adapter/adapter.c').read_text();main=text[text.index('static int native_main('):]
        self.assertEqual(main.count('pipe2(perr,O_CLOEXEC)'),1)
        self.assertEqual(main.count('RUN_SECONDS,CLB97_WORKER_CAPTURE,&result)'),1)
        self.assertLess(main.index('close(perr[1]);'),main.index('RUN_SECONDS,CLB97_WORKER_CAPTURE,&result)'))
        self.assertIn('if(dup2(a->in,0)<0||dup2(a->out,1)<0||dup2(a->err,2)<0)',text)
        helper=(ROOT/'tests/native-fixture-runner.c').read_text();self.assertNotIn('CLB97_WORKER_CAPTURE',helper);self.assertNotIn('worker_measure_emit',helper)
    def test_30_fd_formula_unchanged(self):
        self.assertEqual(hashlib.sha256((ROOT/'adapter/fd-budget.h').read_bytes()).hexdigest(),'d1d3d0bd7323e4f46a2719fa6fd963d154ea9d3af3709ec69045c907816e48b6')
    def test_32_old_model_accepted_no_measurement(self):
        old=self.run_case('transport','old_model_missing')
        self.assertTrue(old['accepted']);self.assertEqual(old['evidence']['status'],'NOT_RUN')
        self.assertFalse(self.run_case('transport','missing')['accepted'])
    def test_33_real_unprivileged_emitter_state(self):
        r=self.run_case('kernel','kernel');self.assertEqual(r['child_exit'],0)
        self.assertTrue(r['exact_record']);self.assertTrue(r['parent_limits_unchanged']);self.assertTrue(r['instrumentation_state_unchanged'])
    def test_31_records_only_from_worker_emitter(self):
        text=(ROOT/'adapter/adapter.c').read_text();self.assertEqual(text.count('worker_measure_emit()'),1)
        # The capture parser stores/matches bytes, never writes to perr. Only
        # exact worker emitter writes the record; Python cannot execute before it.
        self.assertNotIn('write(2,CLB97_WORKER_RECORD',text)
        emitter=(ROOT/'adapter/worker-evidence.h').read_text();self.assertEqual(emitter.count('write(2,CLB97_WORKER_RECORD'),1)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--root',type=P,default=HERE.parent);p.add_argument('--cc',default='cc');p.add_argument('--ubsan',action='store_true');a=p.parse_args();ROOT=a.root.resolve();CC=a.cc;SAN=['-fsanitize=undefined','-fno-sanitize-recover=all'] if a.ubsan else []
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(WorkerEvidence))
    print(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'ubsan':a.ubsan,'root_native':'NOT_RUN'}))
    raise SystemExit(not result.wasSuccessful())
