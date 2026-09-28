#!/usr/bin/env python3
"""Exact production parsers/collector/formatter; syscall-only virtual procfs boundary.
No mounts, namespaces, privilege transitions or native helper main are executed.
The same boundary is used by the exact fixture source + finish regression suite.
"""
import argparse,json,pathlib,re,subprocess,tempfile,unittest
P=pathlib.Path;HERE=P(__file__).resolve().parent
# Macro substitution is only around the production header, never shipped into C.
BOUNDARY=r'''
static const char *bp_scenario="ok";
static unsigned bp_opens,bp_closes,bp_live,bp_mi_reads,bp_source_reads,bp_target_reads,bp_stat_reads,bp_link_reads;
static const char *bp_info="10 1 8:1 / / rw - ext4 PRIVATE-DEVICE-CANARY rw\n20 1 0:8 / /opt rw - tmpfs PRIVATE-SOURCE-CANARY rw\n";
static const char *bp_map_data="0 0 4294967295\n";
static char bp_large[262146];
static struct {int live;size_t at,n;const char*data;} bp_files[16];
static int bt_open(const char*path,int flags) {
 if(!strcmp(path,"/opt")) {
  if(flags!=(O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC))abort();
  if(!strcmp(bp_scenario,"target_open")){errno=EACCES;return -1;}
  bp_opens++;bp_live++;return 8;
 }
 if(flags!=(O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK))abort();
 const char*data=NULL;
 if(!strcmp(path,"/proc/self/fdinfo/7")) {bp_source_reads++;data=!strcmp(bp_scenario,"fd_drift")&&bp_source_reads>1?"mnt_id:\t11\n":"mnt_id:\t10\n";}
 else if(!strcmp(path,"/proc/self/fdinfo/8")){bp_target_reads++;data="mnt_id:\t20\n";}
 else if(!strcmp(path,"/proc/self/mountinfo")) {
  bp_mi_reads++;data=bp_info;
  if(!strcmp(bp_scenario,"missing"))data="20 1 0:8 / /opt rw - tmpfs PRIVATE-SOURCE-CANARY rw\n";
  if(!strcmp(bp_scenario,"mount_drift")&&bp_mi_reads>1)data="10 1 8:1 / / rw shared:2 - ext4 PRIVATE-DEVICE-CANARY rw\n20 1 0:8 / /opt rw - tmpfs PRIVATE-SOURCE-CANARY rw\n";
  if(!strcmp(bp_scenario,"oversize")){memset(bp_large,'x',sizeof bp_large-1);bp_large[sizeof bp_large-1]=0;data=bp_large;}
 }
 else if(!strcmp(path,"/proc/self/uid_map")||!strcmp(path,"/proc/self/gid_map"))data=!strcmp(bp_scenario,"bad_map")?"PRIVATE-MAP-CANARY\n":bp_map_data;
 else abort();
 if(!strcmp(bp_scenario,"proc_open")){errno=EACCES;return -1;}
 for(int i=0;i<16;i++)if(!bp_files[i].live){bp_files[i].live=1;bp_files[i].at=0;bp_files[i].data=data;bp_files[i].n=strlen(data);bp_opens++;bp_live++;return 100+i;}
 abort();
}
static ssize_t bt_read(int fd,void*out,size_t n) {
 if(fd<100||fd>=116||!bp_files[fd-100].live)abort();
 if(!strcmp(bp_scenario,"read_error")){errno=EIO;return -1;}
 size_t left=bp_files[fd-100].n-bp_files[fd-100].at;if(n>left)n=left;
 /* Deliberately short reads: collection must not assume one read per file. */
 if(n>13)n=13;memcpy(out,bp_files[fd-100].data+bp_files[fd-100].at,n);bp_files[fd-100].at+=n;return (ssize_t)n;
}
static int bt_fstat(int fd,struct stat*s) {
 memset(s,0,sizeof *s);s->st_dev=1;s->st_ino=2;
 if(fd==7){bp_stat_reads++;s->st_mode=S_IFDIR;if(!strcmp(bp_scenario,"stat_drift")&&bp_stat_reads>1)s->st_ino=3;return 0;}
 if(fd<100||fd>=116||!bp_files[fd-100].live)abort();s->st_mode=S_IFREG;
 if(!strcmp(bp_scenario,"proc_type"))s->st_mode=S_IFDIR;return 0;
}
static ssize_t bt_readlink(const char*path,char*out,size_t n) {
 if(strcmp(path,"/proc/self/fd/7"))abort();bp_link_reads++;
 const char*v=!strcmp(bp_scenario,"path_mismatch")||(!strcmp(bp_scenario,"path_drift")&&bp_link_reads>1)?"/synthetic/OTHER-PRIVATE-CANARY/source":"/synthetic/PRIVATE-PATH-CANARY-91/source";
 size_t z=strlen(v);if(z>n)z=n;memcpy(out,v,z);return (ssize_t)z;
}
static int bt_close(int fd) {
 if(fd==8){bp_closes++;bp_live--;if(!strcmp(bp_scenario,"close_error")){errno=EIO;return -1;}return 0;}
 if(fd<100||fd>=116||!bp_files[fd-100].live)abort();bp_files[fd-100].live=0;bp_closes++;bp_live--;return 0;
}
#define open bt_open
#define read bt_read
#define fstat bt_fstat
#define readlink bt_readlink
#define close bt_close
'''
UNDEFINE='\n#undef open\n#undef read\n#undef fstat\n#undef readlink\n#undef close\n'
PRELUDE=r'''
#define _GNU_SOURCE 1
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <errno.h>
#include <limits.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
static volatile int interrupted;
'''
MAIN=r'''
int main(int argc,char**argv) {
 if(argc<2)return 98;
 char *b=malloc(300001);size_t n=fread(b,1,300000,stdin);b[n]=0;
 struct bind_probe p=bp_empty();int rc=-1;unsigned id=0;p.attempted=1;
 if(!strcmp(argv[1],"fd")){rc=bp_fdinfo(b,n,&id);printf("{\"rc\":%d,\"id\":%u}\n",rc,id);}
 else if(!strcmp(argv[1],"mount")){p.source_id=10;p.target_id=20;rc=bp_mountinfo(b,n,10,20,"/synthetic/PRIVATE-PATH-CANARY-91/source",&p);p.complete=rc==0;printf("{\"rc\":%d",rc);print_bind_probe(&p);puts("}");}
 else if(!strcmp(argv[1],"map")){char*sep=memchr(b,'|',n);if(!sep)return 97;*sep=0;p.userns=bp_userns(b,(size_t)(sep-b),sep+1,n-(size_t)(sep-b)-1);printf("{\"class\":\"%s\"}\n",p.userns);}
 else if(!strcmp(argv[1],"collect")){
  if(argc!=3)return 96;bp_scenario=argv[2];if(!strcmp(bp_scenario,"interrupted"))interrupted=1;
  struct stat st={0};st.st_dev=1;st.st_ino=2;
  collect_bind_probe(7,&st,"/synthetic/PRIVATE-PATH-CANARY-91","/proc/self/fd/7",&p);
  printf("{\"live\":%u,\"opens\":%u,\"closes\":%u",bp_live,bp_opens,bp_closes);print_bind_probe(&p);puts("}");
 }else return 95;
 free(b);return 0;
}
'''
def boundary_include(root):
 return BOUNDARY+'\n#include '+json.dumps(str(root/'tests/fixture-bind-probe.h'))+'\n'+UNDEFINE

def compile_probe(root,dest,cc,sanitizers):
 src=dest/'probe-tests.c';src.write_text(PRELUDE+boundary_include(root)+MAIN)
 binary=dest/'probe-tests'
 cmd=[cc]+sanitizers+['-std=c11','-O1','-Wall','-Wextra','-Werror','-Wno-unused-function','-Wno-misleading-indentation',str(src),'-o',str(binary)]
 q=subprocess.run(cmd,capture_output=True,timeout=30)
 if q.returncode:raise RuntimeError(q.stderr.decode())
 return binary

def line(i=10,path='/',optional='',source='PRIVATE-DEVICE-CANARY'):
 return f'{i} 1 8:1 / {path} rw'+(' '+optional if optional else '')+f' - ext4 {source} rw\n'
def mounts(opt=''):
 return line(optional=opt)+line(20,'/opt')

class Tests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.tmp=tempfile.TemporaryDirectory(prefix='bind probe ');cls.binary=compile_probe(ROOT,P(cls.tmp.name),CC,SANITIZERS)
 @classmethod
 def tearDownClass(cls):cls.tmp.cleanup()
 def run_case(self,cmd,data='',arg=None):
  q=subprocess.run([str(self.binary),cmd]+([] if arg is None else [arg]),input=data.encode(),capture_output=True,timeout=3)
  self.assertEqual(q.returncode,0);self.assertEqual(q.stderr,b'');self.assertLess(len(q.stdout),2048)
  for secret in (b'PRIVATE',b'/synthetic',b'/opt',b'/proc/',b'ext4',b'tmpfs'):
   self.assertNotIn(secret,q.stdout)
  return json.loads(q.stdout)
 def test_propagation_classes(self):
  for opt,expected in [('', 'private'),('shared:42','shared'),('master:9','slave'),('master:9 propagate_from:2','slave'),('shared:42 master:9','shared_slave'),('unbindable','unbindable')]:
   with self.subTest(opt=opt):
    r=self.run_case('mount',mounts(opt));self.assertEqual(r['rc'],0);self.assertEqual(r['source_mount_class'],expected);self.assertEqual(r['target_mount_class'],'private');self.assertEqual(r['source_submount_count'],0)
 def test_duplicate_missing_mount_id(self):
  for data,status in [(mounts()+line(),'duplicate'),(line(20,'/opt'),'missing')]:
   r=self.run_case('mount',data);self.assertEqual(r['rc'],-1);self.assertEqual(r['source_mount_status'],status);self.assertEqual(r['source_mount_class'],'unknown');self.assertIsNone(r['source_submount_count'])
 def test_malformed_optional_fields(self):
  for opt in ('shared:', 'shared:0','shared:-1','shared:4294967296','shared:1 shared:2','master:a','master:1 master:2','propagate_from:1','unbindable shared:1','unbindable unbindable','new_tag:1'):
   with self.subTest(opt=opt):
    r=self.run_case('mount',mounts(opt));self.assertEqual(r['rc'],-1);self.assertEqual(r['source_mount_class'],'unknown')
 def test_malformed_mount_records(self):
  for data in (mounts().replace('8:1','8:x',1),mounts().replace(' rw -','  rw -',1),mounts().replace(' / rw',' relative rw',1),mounts()[:-1],mounts()+'\x00',mounts().replace(' - ext4',' - ext4 extra',1)):
   self.assertEqual(self.run_case('mount',data)['rc'],-1)
 def test_fdinfo_exact_integer(self):
  self.assertEqual(self.run_case('fd','pos:\t0\nflags:\t0100000\nmnt_id:\t10\n'),{'rc':0,'id':10})
  for data in ('mnt_id:\t0\n','mnt_id:\t-1\n','mnt_id:\t4294967296\n','mnt_id:\t10 x\n','mnt_id:\t10\nmnt_id:\t10\n','pos:0\n','mnt_id:\t10','mnt_id:\t10\n\x00'):
   self.assertEqual(self.run_case('fd',data)['rc'],-1)
 def test_bounds(self):
  self.assertEqual(self.run_case('fd','x'*4096+'\n')['rc'],-1)
  for data in ('x'*262144+'\n',line(source='x'*8200)+line(20,'/opt'),''.join(line(i+1) for i in range(1025))):
   r=self.run_case('mount',data);self.assertEqual(r['rc'],-1);self.assertEqual(r['source_mount_status'],'bound')
 def test_map_classes(self):
  for data,want in [('0 0 4294967295\n','initial_identity'),('1000 1000 1\n','identity_single_range'),('0 1000 1\n','remapped'),('0 1000 1\n1 2000 2\n','remapped'),('0 0 1\n2 2 1\n','unexpected')]:
   self.assertEqual(self.run_case('map',data+'|'+data)['class'],want)
 def test_map_malformed(self):
  for data in ('','0 0 0\n','0 -1 1\n','0 0 4294967296\n','1 1 4294967295\n','0 0 2\n1 5 1\n','0 1 1\n2 1 1\n','0 0 1 extra\n','0 0 1','PRIVATE-CANARY\n','x'*4096+'\n',''.join(f'{i} {i} 1\n' for i in range(17))):
   self.assertEqual(self.run_case('map',data+'|0 0 4294967295\n')['class'],'unexpected')
 def test_submount_zero_positive_and_overflow(self):
  prefix='/synthetic/PRIVATE-PATH-CANARY-91/source'
  for count in (0,1,32,33):
   data=mounts()+line(30,prefix)+line(31,prefix+'-other/x')+''.join(line(40+i,prefix+f'/sub{i}') for i in range(count))
   r=self.run_case('mount',data);self.assertEqual(r['source_submount_count'],min(32,count));self.assertEqual(r['source_submount_overflow'],count>32);self.assertEqual(r['rc'],-1 if count>32 else 0)
 def test_escaped_paths(self):
  r=self.run_case('mount',mounts()+line(30,'/synthetic/PRIVATE-PATH-CANARY-91/source/a\\040b'))
  self.assertEqual(r['source_submount_count'],1)
  self.assertEqual(self.run_case('mount',mounts()+line(30,'/bad\\777'))['rc'],-1)
 def test_exact_collector_complete_and_held_fds_closed(self):
  r=self.run_case('collect',arg='ok');self.assertEqual(r['probe_status'],'complete');self.assertEqual(r['source_mount_id'],10);self.assertEqual(r['target_mount_id'],20);self.assertEqual(r['source_submount_count'],0);self.assertEqual(r['userns_class'],'initial_identity');self.assertEqual(r['live'],0);self.assertEqual(r['opens'],r['closes']);self.assertIsNone(r['mountns_owner_current_userns'])
 def test_collector_fail_closed_and_cleanup(self):
  for fault in ('target_open','proc_open','read_error','oversize','proc_type','path_mismatch','fd_drift','mount_drift','stat_drift','path_drift','bad_map','interrupted','close_error','missing'):
   with self.subTest(fault=fault):
    r=self.run_case('collect',arg=fault);self.assertEqual(r['probe_status'],'incomplete');self.assertEqual(r['live'],0);self.assertEqual(r['opens'],r['closes']);self.assertEqual(r['probe_cleanup_error'],fault=='close_error')
 def test_readset_and_original_mount_semantics(self):
  helper=(ROOT/'tests/native-fixture-runner.c').read_text()
  self.assertEqual(helper.count('mount(fdpath,SOURCE_ROOT,NULL,MS_BIND,NULL)'),1)
  start=helper.index('collect_bind_probe(source,');end=helper.index('primary="fixture_source_mountinfo";',start)
  region=helper[start:end];self.assertIn('errno=0;',region);self.assertNotIn('MS_REC',region)
  header=(ROOT/'tests/fixture-bind-probe.h').read_text()
  for forbidden in ('mount(', 'unshare(', 'setns(', 'system(', 'unlink(', 'ioctl('):self.assertIsNone(re.search(r'\b'+re.escape(forbidden),header))

if __name__=='__main__':
 ap=argparse.ArgumentParser();ap.add_argument('--root',type=P,default=HERE.parent);ap.add_argument('--cc',default='cc');ap.add_argument('--ubsan',action='store_true');a=ap.parse_args();ROOT=a.root.resolve();CC=a.cc;SANITIZERS=['-fsanitize=undefined','-fno-sanitize-recover=all'] if a.ubsan else []
 result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Tests));print(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skips':len(result.skipped),'ubsan':bool(SANITIZERS),'verdict':'PASS' if result.wasSuccessful() else 'FAIL','scope':'exact production parser/collector/formatter, per-syscall synthetic procfs boundary; no root/native namespace/helper main'}));raise SystemExit(not result.wasSuccessful())
