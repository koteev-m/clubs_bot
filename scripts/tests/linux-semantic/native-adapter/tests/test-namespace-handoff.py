#!/usr/bin/env python3
"""Actual helper handoff/child code, syscall model only; no root or namespaces.
Old/new mount IDs model Linux FD retention, not a native kernel PASS. Real
unprivileged no-follow/path tests separately execute the unchanged open_beneath.
"""
import argparse,json,pathlib,re,subprocess,tempfile,unittest
P=pathlib.Path;HERE=P(__file__).resolve().parent
PRE=r'''
#define _GNU_SOURCE 1
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <stdarg.h>
#include <errno.h>
#include <limits.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <setjmp.h>
#ifdef __APPLE__
#define MODEL_CT st_ctimespec
#else
#define MODEL_CT st_ctim
#endif
#define MAX_PATH 1024
#define SOURCE_ROOT "/opt/clubs-bot-stage"
#define CLONE_NEWNS 0x20000
#define CLONE_NEWNET 0x40000000
#define MS_REC 16384
#define MS_PRIVATE 262144
#define MS_NOSUID 2
#define MS_NODEV 4
#define MS_BIND 4096
#define PR_SET_NO_NEW_PRIVS 38
static int interrupted,cleanup,phase,current_ns,nextfd=40,source_creates,source_opens,source_ns=-1,binds,views,execs,verify_fd=-1;
static const char *scenario,*primary="request";
/* Observe at private_view, before exec/longjmp. Static storage remains defined
 * even when probe_start mutates its caller's automatic probe_args. */
static int observed_runtime_ns=-1,observed_source_ns=-1;
static int observed_runtime_fd=-1,observed_source_fd=-1;
static jmp_buf done;
struct lease_set {unsigned n;int fd;};struct mount_tuple {int x;};
struct item {int live,kind,ns;struct stat st;size_t at;char data[16384];} fds[256];
/* Read-only admission syscall/contract boundary. Actual parser/math/guard
 * integration is separately compiled in test-fd-budget.py. */
static int is(const char *s);
struct fd_budget {int close_error,sufficient;};static struct fd_budget namespace_budget;
static int fd_budget_runtime(uint64_t *n){*n=513;return 0;}
static int fd_budget_admit(uint64_t n,struct fd_budget *b){if(n!=518)abort();b->sufficient=!is("fd_budget");return b->sufficient?0:-1;}
static int closes[256],invalid_close,global_opens;
static const char *workpath="/tmp/clb91-native-PRIVATE_CANARY_91";
static int is(const char*s){return !strcmp(scenario,s);}
static void populate(int fd,int kind,int ns) {
 memset(&fds[fd],0,sizeof fds[fd]);fds[fd].live=1;fds[fd].kind=kind;fds[fd].ns=ns;
 fds[fd].st.st_dev=1;fds[fd].st.st_ino=kind+100;
 fds[fd].st.st_mode=S_IFDIR|(kind==3?0555:kind==2?0700:0755);
 fds[fd].st.st_nlink=2;
}
static int alloc(int kind,int ns){int fd=nextfd++;if(fd>=256)abort();populate(fd,kind,ns);return fd;}
static int t_close(int fd){if(fd<0||fd>=256||!fds[fd].live){invalid_close++;return -1;}fds[fd].live=0;closes[fd]++;if((is("close_anchor")&&fd==11)||(is("close_proc")&&fds[fd].kind==9)||(is("close_fresh")&&fds[fd].kind==1&&fd>=40)){errno=EIO;return -1;}return 0;}
static int t_dup(int fd){if(!fds[fd].live)abort();int q=nextfd++;fds[q]=fds[fd];return q;}
static int t_stat(int fd,struct stat*s){if(!fds[fd].live)abort();*s=fds[fd].st;if(is("stat_fail")&&fd>=40&&fds[fd].kind==2){errno=EIO;return -1;}return 0;}
static int t_openat(int fd,const char*p,int flags,...) {
 if(!fds[fd].live)abort();if(!(flags&O_NOFOLLOW)||!(flags&O_CLOEXEC))abort();
 int kind=0;
 if(!strcmp(p,"tmp")&&fds[fd].kind==1)kind=4;
 else if(!strcmp(p,workpath+5)&&fds[fd].kind==4)kind=2;
 else if(!strcmp(p,"opt")&&fds[fd].kind==1)kind=5;
 else if(!strcmp(p,"clubs-bot-stage")&&fds[fd].kind==5)kind=6;
 else if(!strcmp(p,"runtime")&&fds[fd].kind==2)kind=3;
 else if(!strcmp(p,"source")&&fds[fd].kind==2){kind=6;source_opens++;source_ns=fds[fd].ns;}
 else abort();
 if((is("work_symlink")&&kind==2)||(is("runtime_symlink")&&kind==3)){errno=ELOOP;return -1;}
 int q=alloc(kind,fds[fd].ns);if(kind==2)fds[q].st=fds[11].st;
 if((is("work_mismatch")&&kind==2)||(is("runtime_mismatch")&&kind==3)||(is("source_mismatch")&&kind==6))fds[q].st.st_ino++;
 if(is("work_mode")&&kind==2)fds[q].st.st_mode^=0002;
 if(is("work_owner")&&kind==2)fds[q].st.st_uid=1000;
 if(is("stale_reopen")&&kind==2)fds[q].ns=0;
 return q;
}
static int t_open(const char*p,int flags,...) {
 if(!strcmp(p,"/")){global_opens++;if(flags!=(O_RDONLY|O_DIRECTORY|O_CLOEXEC))abort();if(is("root_open"))return -1;return alloc(1,current_ns);}
 if(flags!=(O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK))abort();
 int q=alloc(9,current_ns);fds[q].st.st_mode=S_IFREG|0400;
 unsigned fd;char extra;
 if(sscanf(p,"/proc/self/fdinfo/%u%c",&fd,&extra)==1){
  if(fd>=256||!fds[fd].live)abort();unsigned id=fds[fd].ns?307:27;
  if((is("work_mount_missing")&&fds[fd].kind==2)||(is("runtime_mount_missing")&&fds[fd].kind==3)||(is("source_mount_missing")&&fds[fd].kind==6))id=99;
  snprintf(fds[q].data,sizeof fds[q].data,"mnt_id:\t%u\n",id);
 }else if(!strcmp(p,"/proc/self/mountinfo")){
  snprintf(fds[q].data,sizeof fds[q].data,"307 1 8:1 / / rw - ext4 PRIVATE_DEVICE_CANARY rw\n");
  if(is("duplicate"))strcat(fds[q].data,"307 1 8:1 / /tmp rw - ext4 PRIVATE_DEVICE_CANARY rw\n");
  if(is("malformed"))strcat(fds[q].data,"PRIVATE_PATH_CANARY\n");
  if(is("submounts"))for(int i=0;i<33;i++){char x[256];snprintf(x,sizeof x,"%d 307 8:1 / %s/m%d rw - ext4 PRIVATE_DEVICE_CANARY rw\n",400+i,workpath,i);strcat(fds[q].data,x);}
 }else abort();return q;
}
static ssize_t t_read(int fd,void*b,size_t n){if(!fds[fd].live||fds[fd].kind!=9)abort();size_t left=strlen(fds[fd].data)-fds[fd].at;if(n>left)n=left;if(n>11)n=11;memcpy(b,fds[fd].data+fds[fd].at,n);fds[fd].at+=n;return n;}
static ssize_t t_readlink(const char*p,char*b,size_t n){(void)p;(void)b;(void)n;abort();}
static int verify_runtime(int fd,struct lease_set*s){if(!fds[fd].live||fds[fd].kind!=3)abort();verify_fd=fd;s->n=1;s->fd=fd;return is("runtime_contract")?-1:0;}
static int recheck(const struct lease_set*s){if(s->n&&!fds[s->fd].live)abort();return is("recheck")?-1:0;}
static int close_leases(struct lease_set*s){int old=s->fd;s->n=0;return is("close_leases")&&old==12?-1:0;}
static int t_chown(int fd,unsigned uid,unsigned gid){if(fd!=11||uid||gid)abort();fds[fd].st.st_uid=uid;fds[fd].st.st_gid=gid;fds[fd].st.MODEL_CT.tv_sec++;return 0;}
static int t_chmod(int fd,unsigned mode){if(fd!=11||mode!=0700)abort();fds[fd].st.st_mode=S_IFDIR|mode;fds[fd].st.MODEL_CT.tv_sec++;return 0;}
static int t_unshare(int flags){if(flags!=(CLONE_NEWNS|CLONE_NEWNET)||phase++)abort();current_ns=1;return 0;}
static int t_mount(const char*src,const char*dst,const char*type,unsigned long flags,const void*data) {
 if(flags==MS_BIND){binds++;if(strcmp(dst,SOURCE_ROOT)||type||data)abort();unsigned fd;if(sscanf(src,"/proc/self/fd/%u",&fd)!=1)abort();if(fds[fd].ns!=current_ns){errno=EINVAL;return -1;}return 0;}
 if(phase==1){if(src||strcmp(dst,"/")||type||flags!=(MS_REC|MS_PRIVATE)||data)abort();phase++;return 0;}
 if(phase==2){if(strcmp(src,"tmpfs")||strcmp(dst,"/opt")||strcmp(type,"tmpfs")||flags!=(MS_NOSUID|MS_NODEV))abort();phase++;return 0;}abort();
}
static int t_mkdirat(int fd,const char*p,int mode){if(!fds[fd].live||fds[fd].kind!=2||strcmp(p,"source")||mode!=0700)abort();source_creates++;source_ns=fds[fd].ns;return 0;}
static void child_fail(const char*s){primary=s;longjmp(done,1);}
static int t_dup2(int a,int b){(void)a;return b;}
static int t_zero(void){return 0;}
#define setgroups(a,b) t_zero()
#define setresgid(a,b,c) t_zero()
#define setresuid(a,b,c) t_zero()
#define prctl(a,b,c,d,e) t_zero()
static void close_extra_fds(void){for(int i=0;i<256;i++)if(fds[i].live)t_close(i);}
static int t_execve(const char*p,char*const*a,char*const*e){(void)p;(void)a;(void)e;execs++;longjmp(done,2);}
static void private_view(const char*p,int r,int s,const struct lease_set*l,const struct mount_tuple*t){(void)p;(void)l;(void)t;if(!fds[r].live||!fds[s].live||fds[r].ns!=current_ns||fds[s].ns!=current_ns)abort();views++;observed_runtime_ns=fds[r].ns;observed_source_ns=fds[s].ns;observed_runtime_fd=r;observed_source_fd=s;}
#define open t_open
#define openat t_openat
#define close t_close
#define read t_read
#define readlink t_readlink
#define fstat t_stat
#define dup_cloexec t_dup
#define fchown t_chown
#define fchmod t_chmod
#define unshare t_unshare
#define mount t_mount
#define mkdirat t_mkdirat
#define dup2 t_dup2
#define execve t_execve
'''
MAIN=r'''
static int run_main(void) {
 int global=10,work=11,runtime=12,source=-1;
 struct stat work_identity,runtime_identity;struct lease_set rt={1,12};
 int source_created=0,fixture_errno=-1,work_owned=0;char*argv[]={"test","--work",(char*)workpath,NULL};
 /* exact main snapshot -> namespace transition -> handoff -> source open */
 @REGION@
 finish:
 printf("{\"primary\":\"%s\",\"cleanup_error\":%s,\"work\":%d,\"runtime\":%d,\"work_ns\":%d,\"runtime_ns\":%d,\"leases_fd\":%d,\"source_creates\":%d,\"source_opens\":%d,\"source_ns\":%d,\"old_closed\":[%d,%d,%d],\"invalid_close\":%d,\"adapter_invocations\":0}\n",primary,cleanup?"true":"false",work,runtime,fds[work].ns,fds[runtime].ns,rt.fd,source_creates,source_opens,source_ns,closes[10],closes[11],closes[12],invalid_close);
 return 0;
}
int main(int argc,char**argv) {
 if(argc!=3)return 98;scenario=argv[2];populate(10,1,0);populate(11,2,0);populate(12,3,0);fds[11].st.st_uid=fds[11].st.st_gid=1000;
 if(!strcmp(argv[1],"main"))return run_main();
 current_ns=1;
 if(!strcmp(argv[1],"member")){int fd=is("old")?11:alloc(2,1);int r=nh_member(fd,workpath,&cleanup);printf("{\"rc\":%d,\"cleanup_error\":%s}\n",r,cleanup?"true":"false");return 0;}
 if(!strcmp(argv[1],"old_model")){
  int work=11,source=-1;@OLD@
  char path[64];snprintf(path,sizeof path,"/proc/self/fd/%d",source);int result=mount(path,SOURCE_ROOT,NULL,MS_BIND,NULL);
  printf("{\"source_creates\":%d,\"source_ns\":%d,\"current_ns\":%d,\"bind_rc\":%d,\"errno\":%d}\n",source_creates,source_ns,current_ns,result,errno);return 0;
 }
 if(!strcmp(argv[1],"fingerprint")){
  populate(13,6,0);struct mount_tuple outer={0};struct probe_args a={.runtime="/tmp/clb91-native-PRIVATE_CANARY_91/runtime",.runtimefd=12,.sourcefd=13,.outer=&outer,.out=1,.err=2};
  a.runtime_identity=fds[12].st;a.source_identity=fds[13].st;
  if(!setjmp(done))probe_start(&a);
  printf("{\"primary\":\"%s\",\"views\":%d,\"execs\":%d,\"runtime_ns\":%d,\"source_ns\":%d,\"observed_fd_ids\":[%d,%d],\"old_closed\":[%d,%d]}\n",primary,views,execs,observed_runtime_ns,observed_source_ns,observed_runtime_fd,observed_source_fd,closes[12],closes[13]);return 0;
 }return 97;
}
'''
def function(text,name):
 start=text.index('static int '+name+'(');end=text.index('\n}',start)+2;return text[start:end]
def build(root,dest,old_root=None):
 helper=(root/'tests/native-fixture-runner.c').read_text();adapter=(root/'adapter/adapter.c').read_text()
 support='\n'.join(function(adapter,n) for n in ('same_stat','test_work_path','open_beneath'))
 region=helper[helper.index('  if(fchown(work,0,0))'):helper.index('  primary="fixture_source_chown";')]
 # Stop before any ownership operation: models execute the real new handoff,
 # followed by the actual mkdir/open source call sites. F1 suite owns cleanup.
 old=(old_root/'tests/native-fixture-runner.c').read_text() if old_root else helper
 oldregion=old[old.index('  primary="fixture_source_mkdir";'):old.index('  primary="fixture_source_chown";')]
 # Old region is the unchanged stale-dirfd behavior. Its declarations only.
 oldregion='int source_created=0,fixture_errno=-1;\n'+oldregion.replace('goto finish','return 96')
 probe=helper[helper.index('struct probe_args'):helper.index('static int fingerprint')]
 body=PRE+support+'\n#include '+json.dumps(str(root/'tests/fixture-bind-probe.h'))+'\n#include '+json.dumps(str(root/'tests/fixture-namespace-handles.h'))+'\n'+probe+MAIN.replace('@REGION@',region).replace('@OLD@',oldregion)
 src=dest/'boundary.c';src.write_text(body);out=dest/'boundary'
 q=subprocess.run([CC,*SAN,'-std=c11','-O1','-Wall','-Wextra','-Wno-unused-function','-Wno-unused-variable','-Wno-misleading-indentation',str(src),'-o',str(out)],capture_output=True,timeout=30)
 if q.returncode:raise RuntimeError(q.stderr.decode())
 return out
class Handoff(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.tmp=tempfile.TemporaryDirectory(prefix='namespace handoff ');cls.t=P(cls.tmp.name);cls.bin=build(HERE.parent,cls.t)
 @classmethod
 def tearDownClass(cls):cls.tmp.cleanup()
 def run_case(self,case,mode='main'):
  q=subprocess.run([str(self.bin),mode,case],capture_output=True,timeout=5);self.assertEqual(q.returncode,0,q.stderr.decode());self.assertFalse(q.stderr);self.assertLess(len(q.stdout),4096)
  for secret in (b'PRIVATE',b'/tmp/',b'/proc/',b'ext4'):self.assertNotIn(secret,q.stdout)
  return json.loads(q.stdout)
 def refused(self,case,primary):
  r=self.run_case(case);self.assertEqual(r['primary'],primary);self.assertEqual(r['source_creates'],0);self.assertEqual(r['adapter_invocations'],0);self.assertEqual(r['invalid_close'],0);return r
 def test_00_fd_budget_before_namespace_or_source(self):
  r=self.refused('fd_budget','namespace_fd_budget');self.assertEqual(r['work_ns'],0);self.assertEqual(r['old_closed'],[0,0,0])
 def test_01_old_model_fails_new_invariant(self):
  r=self.run_case('ok','old_model');self.assertEqual(r,dict(source_creates=1,source_ns=0,current_ns=1,bind_rc=-1,errno=22))
 def test_02_stale_mount_rejected(self):
  self.assertEqual(self.run_case('old','member')['rc'],-1);self.refused('stale_reopen','namespace_work_mount')
 def test_03_fresh_handles_source_and_leases(self):
  r=self.run_case('ok');self.assertEqual(r['source_creates'],1);self.assertEqual(r['source_opens'],1);self.assertEqual((r['work_ns'],r['runtime_ns'],r['source_ns']),(1,1,1));self.assertEqual(r['leases_fd'],r['runtime']);self.assertEqual(r['old_closed'],[1,1,1]);self.assertFalse(r['cleanup_error'])
 def test_04_work_identity_owner_mode(self):
  for s in ('work_mismatch','work_owner','work_mode','stat_fail'):
   with self.subTest(s=s):self.refused(s,'namespace_work_identity')
 def test_05_symlink_reopen(self):
  self.refused('work_symlink','namespace_work_reopen');self.refused('runtime_symlink','namespace_runtime_reopen')
 def test_06_work_mount_missing_duplicate_malformed(self):
  for s in ('work_mount_missing','duplicate','malformed'):
   with self.subTest(s=s):self.refused(s,'namespace_work_mount')
 def test_07_runtime_identity_contract(self):
  self.refused('runtime_mismatch','namespace_runtime_identity');self.refused('runtime_contract','namespace_runtime_verify')
 def test_08_runtime_mount(self):self.refused('runtime_mount_missing','namespace_runtime_mount')
 def test_09_anchor_close(self):
  r=self.refused('close_anchor','namespace_anchor_close');self.assertTrue(r['cleanup_error']);self.assertEqual(r['old_closed'],[1,1,1]);self.assertEqual(r['work_ns'],1)
 def test_10_lease_close(self):self.assertTrue(self.refused('close_leases','namespace_anchor_close')['cleanup_error'])
 def test_11_recheck_no_handoff(self):
  r=self.refused('recheck','namespace_handoff_recheck');self.assertEqual(r['old_closed'],[0,0,0])
 def test_12_membership_does_not_relax_submount_probe(self):
  self.assertEqual(self.run_case('submounts','member')['rc'],0)
 def test_13_proc_close_failure(self):self.assertTrue(self.refused('close_proc','namespace_work_mount')['cleanup_error'])
 def test_14_fingerprint_clone_reopens(self):
  r=self.run_case('ok','fingerprint');self.assertEqual(r['views'],1);self.assertEqual(r['execs'],1);self.assertEqual((r['runtime_ns'],r['source_ns']),(1,1));self.assertEqual(r['old_closed'],[1,1]);self.assertTrue(all(fd>=40 for fd in r['observed_fd_ids']));self.assertNotEqual(*r['observed_fd_ids'])
 def test_15_fingerprint_refusals(self):
  for s in ('runtime_mismatch','source_mismatch','runtime_contract','runtime_mount_missing','source_mount_missing','close_proc'):
   with self.subTest(s=s):
    r=self.run_case(s,'fingerprint');self.assertEqual(r['primary'],'probe_namespace_handles');self.assertEqual((r['views'],r['execs']),(0,0))
 def test_16_exact_single_bind_and_fingerprint_call(self):
  s=(HERE/'native-fixture-runner.c').read_text();self.assertEqual(s.count('mount(fdpath,SOURCE_ROOT,NULL,MS_BIND,NULL)'),1)
  for bad in ('open_tree','move_mount','mount_setattr','MS_BIND|MS_REC'):self.assertNotIn(bad,s)
  self.assertIn('fingerprint(runtimepath,runtime,source,&outer,hash)',s)
  self.assertLess(s.index('nh_handoff(&global'),s.index('mkdirat(work,"source"'))
  self.assertLess(s.index('nh_fingerprint_handles(a->runtime'),s.index('private_view(a->runtime'))
 def test_17_real_open_beneath_nofollow(self):
  adapter=(HERE.parent/'adapter/adapter.c').read_text()
  text='''#define _GNU_SOURCE 1
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <stdio.h>
#define MAX_PATH 1024
#define dup_cloexec(fd) fcntl(fd,F_DUPFD_CLOEXEC,3)
'''+function(adapter,'open_beneath')+'''
int main(int argc,char**argv){if(argc!=3)return 98;int r=open(argv[1],O_RDONLY|O_DIRECTORY|O_NOFOLLOW);if(r<0)return 97;int f=open_beneath(r,argv[2],O_RDONLY|O_DIRECTORY);if(f>=0)close(f);close(r);printf("%d\\n",f>=0);return 0;}
'''
  src=self.t/'real.c';src.write_text(text);binary=self.t/'real';subprocess.run([CC,*SAN,'-std=c11',str(src),'-o',str(binary)],check=True,capture_output=True)
  area=self.t/'objects';area.mkdir(exist_ok=True);(area/'work').mkdir(exist_ok=True);(area/'work/runtime').mkdir(exist_ok=True);(area/'link').symlink_to(area/'work',target_is_directory=True);(area/'work/alias').symlink_to(area/'work/runtime',target_is_directory=True)
  for path,ok in [('work/runtime',1),('link/runtime',0),('work/alias',0),('../objects/work',0),('work/../work',0)]:
   q=subprocess.run([str(binary),str(area),path],check=True,capture_output=True);self.assertEqual(int(q.stdout),ok,path)
if __name__=='__main__':
 a=argparse.ArgumentParser();a.add_argument('--cc',default='/usr/bin/clang');a.add_argument('--ubsan',action='store_true');o,rest=a.parse_known_args();CC=o.cc;SAN=['-fsanitize=undefined','-fno-sanitize-recover=all'] if o.ubsan else [];unittest.main(argv=['test-namespace-handoff.py',*rest])
