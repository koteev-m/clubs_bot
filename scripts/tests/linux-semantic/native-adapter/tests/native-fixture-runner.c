/* FUTURE native-only synthetic provisioning, separate binary and authority.
* No host Python executes while privileged. It never accepts a shell command.
* Run only on a separately authorized disposable Linux x86_64 test VM.
*/
#define ADAPTER_NO_MAIN 1
#include "../adapter/adapter.c"
#ifndef ADAPTER_EXEC_SHA256
#error Compile with the exact just-built static adapter SHA-256 as ADAPTER_EXEC_SHA256.
#endif
#if !defined(__linux__)
int main(void)  {
  puts("{\"native_fixture\":\"NOT_RUN_linux_required\"}");
  return 1;
}
#else
#include "fixture-bind-probe.h"
#include "fixture-namespace-handles.h"
#include "fixture-resource-envelope.h"
struct owned  {
  char path[MAX_PATH];
  struct stat st;
  int dir;
  char hash[65];
};
struct owned owned[20];
size_t owned_n;
static int untracked_created;
static int made(int source,const char*p,int dir,const void*b,size_t n)  {
  struct owned*o;
  int f=-1,parent=-1,bad=0;
  char x[MAX_PATH],*tail;
  if(owned_n>=20||strlen(p)>=sizeof x||n>65536)return -1;
  strcpy(x,p);
  tail=strrchr(x,'/');
  if(tail) {
    *tail++=0;
    parent=open_beneath(source,x,O_RDONLY|O_DIRECTORY);
  }
  else {
    parent=dup_cloexec(source);
    tail=x;
  }
  if(parent<0)return -1;
  if(dir) {
    if(mkdirat(parent,tail,0700)) {
      close(parent);
      return -1;
    }
    untracked_created=1;
    f=openat(parent,tail,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
  }
  else {
    f=openat(parent,tail,O_RDWR|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if(f>=0)untracked_created=1;
  }
  close(parent);
  if(f<0)return -1;
  o=&owned[owned_n];
  if(fstat(f,&o->st)) {
    close(f);
    return -1;
  }
  strcpy(o->path,p);
  o->dir=dir;
  digest("",0,o->hash);
  owned_n++;
  untracked_created=0;
  if(!dir) {
    ssize_t written=write(f,b,n);
    if(written!=(ssize_t)n)bad=1;
    if(hash_fd(f,65536,o->hash))bad=1;
  }
  if(!bad&&fchown(f,1000,1000))bad=1;
  if(fstat(f,&o->st))bad=1;
  if(close(f))bad=1;
  return bad?-1:0;
}
static int cleanup_owned(int source)  {
  int failure=0;
  while(owned_n)  {
    struct owned*o=&owned[--owned_n];
    struct stat st;
    int f=open_beneath(source,o->path,O_RDONLY|(o->dir?O_DIRECTORY:0));
    char h[65];
    if(f<0)  {
      failure=1;
      continue;
    }
    if(fstat(f,&st)||st.st_dev!=o->st.st_dev||st.st_ino!=o->st.st_ino||st.st_uid!=o->st.st_uid||st.st_gid!=o->st.st_gid||(st.st_mode&07777)!=(o->st.st_mode&07777)||(!o->dir&&(hash_fd(f,65536,h)||strcmp(h,o->hash))))  {
      close(f);
      failure=1;
      continue;
    }
    close(f);
    if(unlinkat(source,o->path,o->dir?AT_REMOVEDIR:0))failure=1;
  }
  return failure?-1:0;
}
struct probe_args  {
  const char*runtime;
  int runtimefd,sourcefd;
  const struct mount_tuple*outer;
  int out,err;
  struct stat runtime_identity,source_identity;
};
static int probe_start(void*v)  {
  struct probe_args*a=v;
  struct lease_set empty=  {
    0
  };
  char*const args[]=  {
    "/usr/bin/findmnt","--noheadings","--pairs","--output","FSTYPE,SOURCE,FSROOT,TARGET","--target",SOURCE_ROOT,NULL
  };
  char*const env[]=  {
    "PATH=/usr/bin:/bin","LC_ALL=C",NULL
  };
  if(dup2(a->out,1)<0||dup2(a->err,2)<0)child_fail("probe_pipe");
  if(nh_fingerprint_handles(a->runtime,&a->runtimefd,&a->sourcefd,
      &a->runtime_identity,&a->source_identity))child_fail("probe_namespace_handles");
  private_view(a->runtime,a->runtimefd,a->sourcefd,&empty,a->outer);
  if(setgroups(0,NULL)||setresgid(1000,1000,1000)||setresuid(1000,1000,1000)||prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0))child_fail("probe_principal");
  close_extra_fds();
  execve(args[0],args,env);
  child_fail("probe_exec");
  return 120;
}
static int fingerprint(const char*runtime,int runtimefd,int sourcefd,const struct mount_tuple*outer,char out[65])  {
  int in[2]= {
    -1,-1
  },stdoutp[2]= {
    -1,-1
  },stderrp[2]= {
    -1,-1
  };
  int i;
  void*stack;
  pid_t child;
  struct captured r;
  struct probe_args a;
  char fs[32],src[MAX_PATH],root[MAX_PATH],target[MAX_PATH],expected[4096],h[4][65];
  /* Six future pipe ends + three current handles + full runtime leases +
   * two walker FDs. Admission happens in parent, before clone/partial leases. */
  {uint64_t held;if(fd_budget_runtime(&held)||fd_budget_admit(6+3+held+2,&namespace_budget))return -1;}
  if(pipe2(in,O_CLOEXEC)||pipe2(stdoutp,O_CLOEXEC)||pipe2(stderrp,O_CLOEXEC))goto resource_failure;
  stack=malloc(1024*1024);
  if(!stack)goto resource_failure;
  a=(struct probe_args)  {
    runtime,runtimefd,sourcefd,outer,stdoutp[1],stderrp[1]
  };
  if(fstat(runtimefd,&a.runtime_identity)||fstat(sourcefd,&a.source_identity)){free(stack);goto resource_failure;}
  child=clone(probe_start,(char*)stack+1024*1024,CLONE_NEWNS|CLONE_NEWNET|CLONE_NEWPID|SIGCHLD,&a);
  free(stack);
  if(child<0)goto resource_failure;
  close(in[0]);
  close(stdoutp[1]);
  close(stderrp[1]);
  capture_child(child,in[1],(const unsigned char*)"",0,stdoutp[0],stderrp[0],10,1,&r);
  if(strcmp(r.primary,"none")||r.code||r.cleanup||r.err_bytes||sscanf((char*)r.out,"FSTYPE=\"%31[^\"]\" SOURCE=\"%1023[^\"]\" FSROOT=\"%1023[^\"]\" TARGET=\"%1023[^\"]\"",fs,src,root,target)!=4)return -1;
  snprintf(expected,sizeof expected,"FSTYPE=\"%s\" SOURCE=\"%s\" FSROOT=\"%s\" TARGET=\"%s\"\n",fs,src,root,target);
  if(strcmp(expected,(char*)r.out)||strchr(src,'\\')||strchr(root,'\\')||strcmp(target,SOURCE_ROOT)||strcmp(fs,outer->type)||strcmp(root,outer->root))return -1;
  digest(fs,strlen(fs),h[0]);
  digest(src,strlen(src),h[1]);
  digest(root,strlen(root),h[2]);
  digest(target,strlen(target),h[3]);
  snprintf(expected,sizeof expected,"clubs-bot-mount-fingerprint-version=2\nFSTYPE_SHA256=%s\nSOURCE_SHA256=%s\nFSROOT_SHA256=%s\nTARGET_SHA256=%s",h[0],h[1],h[2],h[3]);
  digest(expected,strlen(expected),out);
  return 0;
  resource_failure: for(i=0;i<2;i++) {
    if(in[i]>=0)close(in[i]);
    if(stdoutp[i]>=0)close(stdoutp[i]);
    if(stderrp[i]>=0)close(stderrp[i]);
  }
  return -1;
}
static int promote_exact_runtime(int runtime,uid_t owner,gid_t group,int*changed)  {
  struct lease_set s=  {
    0
  };
  size_t i;
  int failure=0;
  if(verify_runtime_owned(runtime,&s,owner,group))  {
    close_leases(&s);
    return -1;
  }
  *changed=1;
  for(i=0;i<s.n;i++)  {
    struct lease*l=&s.a[i];
    if(l->fd>=0)  {
      if(fchown(l->fd,0,0))failure=1;
    }
    else if(l->fd==-2&&fchownat(l->parent,l->name,0,0,AT_SYMLINK_NOFOLLOW))failure=1;
  }
  if(fchown(runtime,0,0))failure=1;
  if(close_leases(&s))failure=1;
  return failure?-1:0;
}
static int seal_adapter(int original) {
  struct stat before,after;
  unsigned char*b;
  size_t size,used=0;
  char h[65];
  int fd=-1;
  if(fstat(original,&before)||!S_ISREG(before.st_mode)||before.st_size<=0||before.st_size>16*1024*1024)return -1;
  size=(size_t)before.st_size;
  b=malloc(size);
  if(!b)return -1;
  while(used<size) {
    ssize_t n=pread(original,b+used,size-used,(off_t)used);
    if(n<0&&errno==EINTR)continue;
    if(n<=0)goto finish;
    used+=(size_t)n;
  }
  digest(b,size,h);
  if(strcmp(h,ADAPTER_EXEC_SHA256)||fstat(original,&after)||!same_stat(&before,&after))goto finish;
  fd=memfd_create("clb91-adapter-exact",MFD_CLOEXEC|MFD_ALLOW_SEALING);
  if(fd<0)goto finish;
  used=0;
  while(used<size) {
    ssize_t n=write(fd,b+used,size-used);
    if(n<0&&errno==EINTR)continue;
    if(n<=0) {
      close(fd);
      fd=-1;
      goto finish;
    }
    used+=(size_t)n;
  }
  if(fcntl(fd,F_ADD_SEALS,F_SEAL_WRITE|F_SEAL_GROW|F_SEAL_SHRINK|F_SEAL_SEAL)<0) {
    close(fd);
    fd=-1;
  }
  finish:free(b);
  return fd;
}
static unsigned adapter_calls;
static int invoke_adapter(int adapter,const char*runtime,struct captured*r)  {
  int a[2]=  {
    -1,-1
  },b[2]=  {
    -1,-1
  },c[2]=  {
    -1,-1
  };
  pid_t child;
  int i;
  if(pipe2(a,O_CLOEXEC)||pipe2(b,O_CLOEXEC)||pipe2(c,O_CLOEXEC))goto fail;
  child=fork();
  if(child<0)goto fail;
  if(child==0)  {
    char*const args[]=  {
      "clb91-native-adapter","--runtime-root",(char*)runtime,"--source-root",SOURCE_ROOT,NULL
    };
    char*const env[]=  {
      "PATH=/usr/bin:/bin","LC_ALL=C",NULL
    };
    pid_t parent=getppid();
    setpgid(0,0);
    if(prctl(PR_SET_PDEATHSIG,SIGKILL)||getppid()!=parent)_exit(120);
    if(dup2(a[0],0)<0||dup2(b[1],1)<0||dup2(c[1],2)<0)_exit(120);
    if(adapter!=3&&dup3(adapter,3,O_CLOEXEC)<0)_exit(120);
    if(syscall(SYS_close_range,4U,~0U,0))_exit(120);
    fexecve(3,args,env);
    _exit(120);
  }
  adapter_calls++;
  setpgid(child,child);
  close(a[0]);
  close(b[1]);
  close(c[1]);
  capture_child(child,a[1],(const unsigned char*)"",0,b[0],c[0],180,0,r);
  return strcmp(r->primary,"none")||r->cleanup||r->err_bytes?-1:0;
  fail:for(i=0;i<2;i++)  {
    if(a[i]>=0)close(a[i]);
    if(b[i]>=0)close(b[i]);
    if(c[i]>=0)close(c[i]);
  }
  return -1;
}
static int positive_report(const struct captured*r)  {
  return r->code==0&&strstr((const char*)r->out,"\"primary\":\"semantic_complete\"")&&strstr((const char*)r->out,"\"original_recheck\":true")&&strstr((const char*)r->out,"\"cleanup_error\":false");
}
static int negative_report(const struct captured*r,const char*expected)  {
  char key[128];
  snprintf(key,sizeof key,"\"primary\":\"%s\"",expected);
  return r->code==1&&strstr((const char*)r->out,key)&&strstr((const char*)r->out,"\"cleanup_error\":false");
}
static int actual_tmpfs_control(int adapter,const char*runtime) {
  int a[2]= {
    -1,-1
  },b[2]= {
    -1,-1
  },c[2]= {
    -1,-1
  };
  pid_t child;
  struct captured r;
  int i;
  if(pipe2(a,O_CLOEXEC)||pipe2(b,O_CLOEXEC)||pipe2(c,O_CLOEXEC))goto fail;
  child=fork();
  if(child<0)goto fail;
  if(child==0) {
    struct captured negative;
    pid_t parent=getppid();
    setpgid(0,0);
    if(prctl(PR_SET_PDEATHSIG,SIGKILL)||getppid()!=parent)_exit(120);
    if(dup2(a[0],0)<0||dup2(b[1],1)<0||dup2(c[1],2)<0)_exit(120);
    close(a[1]);
    close(b[0]);
    close(c[0]);
    if(unshare(CLONE_NEWNS)||mount(NULL,"/",NULL,MS_REC|MS_PRIVATE,NULL)||mount("tmpfs",SOURCE_ROOT,"tmpfs",MS_NOSUID|MS_NODEV|MS_NOEXEC,"size=1048576,mode=0700,uid=1000,gid=1000"))_exit(120);
    if(invoke_adapter(adapter,runtime,&negative)||!negative_report(&negative,"source_backing"))_exit(121);
    if(write(1,"backing_refused\n",16)!=16)_exit(120);
    _exit(0);
  }
  setpgid(child,child);
  close(a[0]);
  close(b[1]);
  close(c[1]);
  capture_child(child,a[1],(const unsigned char*)"",0,b[0],c[0],30,0,&r);
  if(strcmp(r.primary,"none")||r.code||r.cleanup||r.err_bytes||r.used!=16||memcmp(r.out,"backing_refused\n",16))return -1;
  adapter_calls++;
  return 0;
  fail:for(i=0;i<2;i++) {
    if(a[i]>=0)close(a[i]);
    if(b[i]>=0)close(b[i]);
    if(c[i]>=0)close(c[i]);
  }
  return -1;
}
static int remove_exact_runtime(int fd)  {
  struct lease_set verify=  {
    0
  };
  size_t i;
  if(verify_runtime(fd,&verify))  {
    close_leases(&verify);
    return -1;
  }
  if(close_leases(&verify))return -1;
  for(i=CONTRACT_ENTRY_COUNT;i>0;i--)  {
    const struct contract_entry*c=&CONTRACT_ENTRIES[i-1];
    if(unlinkat(fd,c->path+1,c->kind==2?AT_REMOVEDIR:0))return -1;
  }
  return 0;
}
int main(int argc,char**argv)  {
  int work=-1,runtime=-1,source=-1,adapter=-1,global=-1,status=1,cleanup=0;
  char runtimepath[PATH_MAX],sourcepath[PATH_MAX],fdpath[64],hash[65],binding[1024],pathhash[65],canary[65],dotenv[128];
  unsigned char random[32];
  struct stat workst,ast,sourcest,work_identity,runtime_identity;
  struct statfs fs;
  struct lease_set rt=  {
    0
  };
  struct mount_tuple outer;
  struct captured result=  {
    .code=-1
  };
  const char*primary="request";
  int source_created=0,source_identity_captured=0,runtime_owned=0,work_owned=0,uid_control=0,symlink_control=0,runtime_control=0,backing_control=0,backing_context_started=0;
  /* Diagnostics only: capture direct-call errno before cleanup can change it.
   * Composite helpers have no errno contract; report null, never stale errno. */
  int fixture_errno=-1,fixture_entry=-1,fixture_has_magic=0;
  unsigned long fixture_magic=0;
  struct bind_probe bind_observation=bp_empty();
  size_t i;
  struct utsname un;
  {
    struct rlimit limit= {
      0,0
    };
    if(setrlimit(RLIMIT_CORE,&limit)) {
      primary="core_limit";
      goto finish;
    }
    limit.rlim_cur=limit.rlim_max=768ULL*1024*1024;
    if(setrlimit(RLIMIT_AS,&limit)) {
      primary="memory_limit";
      goto finish;
    }
  }
  if(argc!=5||strcmp(argv[1],"--work")||strcmp(argv[3],"--adapter")||!test_work_path(argv[2])||argv[4][0]!='/')goto finish;
  if(geteuid()||getuid()||uname(&un)||strcmp(un.machine,"x86_64"))  {
    primary="native_root_required";
    goto finish;
  }
  signal(SIGINT,on_signal);
  signal(SIGTERM,on_signal);
  signal(SIGHUP,on_signal);
  signal(SIGALRM,on_signal);
  signal(SIGPIPE,SIG_IGN);
  alarm(240);
  umask(077);
  global=open("/",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
  work=open_beneath(global,argv[2],O_RDONLY|O_DIRECTORY);
  adapter=open_beneath(global,argv[4],O_RDONLY);
  primary="input_identity";
  if(work<0||adapter<0||fstat(work,&workst)||!S_ISDIR(workst.st_mode)||(workst.st_mode&07777)!=0700||fstat(adapter,&ast)||!S_ISREG(ast.st_mode)||ast.st_nlink!=1||ast.st_size>16*1024*1024||hash_fd(adapter,16*1024*1024,hash)||strcmp(hash,ADAPTER_EXEC_SHA256))goto finish;
  {
    int sealed=seal_adapter(adapter);
    if(sealed<0)goto finish;
    close(adapter);
    adapter=sealed;
  }
  runtime=openat(work,"runtime",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
  primary="runtime_input";
  if(runtime<0||promote_exact_runtime(runtime,workst.st_uid,workst.st_gid,&runtime_owned)||verify_runtime(runtime,&rt))goto finish;
  if(fchown(work,0,0))goto finish;
  work_owned=1;
  if(fchmod(work,0700))goto finish;
  /* No original /opt mount is touched: first isolate all propagation, then
  * cover /opt only inside this disposable test namespace. */
  /* workst retains only the original owner for restoration. Trust the state
   * after our ownership/mode changes, never the pre-change snapshot. */
  primary="namespace_anchor_identity";
  if(fstat(work,&work_identity)||fstat(runtime,&runtime_identity))goto finish;
  {const char *failure=fixture_envelope_admit();
    if(failure){primary=failure;goto finish;}
  }
  primary="namespace_fd_budget";
  {uint64_t held;if(fd_budget_runtime(&held)||fd_budget_admit(3+held+2,&namespace_budget))goto finish;}
  primary="outer_namespace";
  if(unshare(CLONE_NEWNS|CLONE_NEWNET)||mount(NULL,"/",NULL,MS_REC|MS_PRIVATE,NULL)||mount("tmpfs","/opt","tmpfs",MS_NOSUID|MS_NODEV,"size=1048576,mode=0755"))goto finish;
  if(nh_handoff(&global,&work,&runtime,argv[2],&work_identity,&runtime_identity,&rt,&primary,&cleanup))goto finish;
  primary="fixture_source_mkdir";
  errno=0;
  if(mkdirat(work,"source",0700)) { fixture_errno=errno?errno:-1;goto finish; }
  source_created=1;
  primary="fixture_source_open";
  errno=0;
  source=openat(work,"source",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
  if(source<0) { fixture_errno=errno?errno:-1;goto finish; }
  primary="fixture_source_chown";
  errno=0;
  if(fchown(source,1000,1000)) { fixture_errno=errno?errno:-1;goto finish; }
  primary="fixture_source_stat";
  errno=0;
  if(fstat(source,&sourcest)) { fixture_errno=errno?errno:-1;goto finish; }
  source_identity_captured=1;
  primary="fixture_source_statfs";
  errno=0;
  if(fstatfs(source,&fs)) { fixture_errno=errno?errno:-1;goto finish; }
  fixture_has_magic=1;fixture_magic=(unsigned long)fs.f_type;
  primary="fixture_source_backing_policy";
  if(!supported_backing((unsigned long)fs.f_type)) {
    goto finish;
  }
  primary="fixture_source_mountpoint";
  errno=0;
  if(mkdir(SOURCE_ROOT,0700)) { fixture_errno=errno?errno:-1;goto finish; }
  primary="fixture_source_fdpath";
  if(snprintf(fdpath,sizeof fdpath,"/proc/self/fd/%d",source)>=(int)sizeof fdpath)goto finish;
  collect_bind_probe(source,&sourcest,argv[2],fdpath,&bind_observation);
  primary="fixture_source_bind";
  errno=0;
  if(mount(fdpath,SOURCE_ROOT,NULL,MS_BIND,NULL)) { fixture_errno=errno?errno:-1;goto finish; }
  primary="fixture_source_mountinfo";
  errno=0;
  if(read_mount_tuple(&outer))goto finish;
  primary="fixture_source_paths";
  if(snprintf(runtimepath,sizeof runtimepath,"%s/runtime",argv[2])>=(int)sizeof runtimepath||snprintf(sourcepath,sizeof sourcepath,"%s/source",argv[2])>=(int)sizeof sourcepath)goto finish;
  primary="fixture_source_directory";
  for(i=0;i<sizeof srcdirs/sizeof*srcdirs;i++) {
    fixture_entry=(int)i;errno=0;
    if(made(source,srcdirs[i],1,NULL,0))goto finish;
  }
  primary="fixture_source_lock";
  fixture_entry=0;errno=0;
  if(made(source,".clubs-bot-release-state/application.lock",0,"",0))goto finish;
  fixture_entry=1;errno=0;
  if(made(source,".clubs-bot-release-state/stage/clubs-bot-schema-stage.results/operation.lock",0,"",0))goto finish;
  fixture_entry=-1;
  primary="fixture_source_random";
  errno=0;
  {
    ssize_t n=getrandom(random,32,0);
    if(n!=32) { if(n<0)fixture_errno=errno?errno:-1;goto finish; }
  }
  hex(random,32,canary);
  snprintf(dotenv,sizeof dotenv,"A=%s\n",canary);
  primary="fixture_source_dotenv";
  errno=0;
  if(made(source,".env",0,dotenv,strlen(dotenv)))goto finish;
  memset(canary,0,sizeof canary);
  memset(dotenv,0,sizeof dotenv);
  memset(random,0,sizeof random);
  {
    const char*main="services:\n  app:\n    image: fixture:local\n    env_file: [.env]\n    environment:\n      A: ${A}\n  caddy:\n    image: fixture:local\n    volumes:\n      - ./Caddyfile:/public:ro\n";
    const char*override="# clubs-bot-managed-quiesced-release\n# revision: 44497dcd28139cef865c3f98ac3f2c4a5afac636\nservices:\n  app:\n    image: ghcr.io/koteev-m/clubs_bot/app-bot@sha256:ddf5486e02835855178cc3b30bd2f22899335131e6dc388def20feac328016fe\n";
    primary="fixture_source_compose";
    fixture_entry=0;errno=0;
    if(made(source,"docker-compose.yml",0,main,strlen(main)))goto finish;
    fixture_entry=1;errno=0;
    if(made(source,"docker-compose.override.yml",0,override,strlen(override)))goto finish;
    fixture_entry=2;errno=0;
    if(made(source,".clubs-bot-release-state/stage/clubs-bot-schema-stage.lock/docker-compose.release.yml",0,override,strlen(override)))goto finish;
    fixture_entry=-1;
  }
  primary="native_backing_binding";
  if(fingerprint(runtimepath,runtime,source,&outer,hash)) {
    if(!namespace_budget.sufficient)primary="namespace_fd_budget";
    goto finish;
  }
  digest(SOURCE_ROOT,strlen(SOURCE_ROOT),pathhash);
  snprintf(binding,sizeof binding,"binding_version=3\nenvironment=stage\ncompose_path_hash=%s\nmount_fingerprint_version=2\nmount_fingerprint=mount-v2:%s\ncompose_project=clb91-prototype\ncompose_service=app",pathhash,hash);
  if(made(source,".clubs-bot-release-state/application.binding",0,binding,strlen(binding)))goto finish;
  primary="adapter_capture";
  if(invoke_adapter(adapter,runtimepath,&result)||!positive_report(&result))goto finish;
  /* Negative fixtures mutate only this helper's already-owned synthetic tree.
  * The production adapter has no bypass/test command or alternate executable. */
  {
    struct captured negative;
    int f=open_beneath(source,".env",O_RDONLY);
    if(f<0)goto finish;
    primary="uid_control";
    if(fchown(f,1001,1000))  {
      close(f);
      goto finish;
    }
    uid_control=!invoke_adapter(adapter,runtimepath,&negative)&&negative_report(&negative,"source_identity");
    if(fchown(f,1000,1000)||close(f)||!uid_control)goto finish;
  }
  {
    struct captured negative;
    primary="symlink_control";
    if(renameat(source,"docker-compose.yml",source,".owned-main-hold"))goto finish;
    if(symlinkat(".env",source,"docker-compose.yml"))  {
      renameat(source,".owned-main-hold",source,"docker-compose.yml");
      goto finish;
    }
    symlink_control=!invoke_adapter(adapter,runtimepath,&negative)&&negative_report(&negative,"source_identity");
    {
      char t[16];
      ssize_t n=readlinkat(source,"docker-compose.yml",t,sizeof t);
      if(n!=4||memcmp(t,".env",4)||unlinkat(source,"docker-compose.yml",0)||renameat(source,".owned-main-hold",source,"docker-compose.yml")||!symlink_control)goto finish;
    }
  }
  {
    struct captured negative;
    const char*path="usr/lib/python3.12/keyword.py";
    int f=open_beneath(runtime,path,O_RDONLY);
    struct stat st;
    unsigned char original_byte,changed;
    struct timespec ts[2];
    primary="runtime_control";
    if(f<0||fstat(f,&st)||pread(f,&original_byte,1,0)!=1)  {
      if(f>=0)close(f);
      goto finish;
    }
    close(f);
    /* Rechecks across a declared negative mutation use a new verified baseline. */
    if(close_leases(&rt))goto finish;
    f=open_beneath(runtime,path,O_RDWR);
    if(f<0)goto finish;
    changed=original_byte^1;
    ts[0]=st.st_atim;
    ts[1]=st.st_mtim;
    if(pwrite(f,&changed,1,0)!=1)  {
      close(f);
      goto finish;
    }
    runtime_control=!invoke_adapter(adapter,runtimepath,&negative)&&negative_report(&negative,"runtime_integrity");
    if(pwrite(f,&original_byte,1,0)!=1||futimens(f,ts)||close(f)||verify_runtime(runtime,&rt)||!runtime_control)goto finish;
  }
  primary="backing_control";
  backing_context_started=1;
  if(actual_tmpfs_control(adapter,runtimepath))goto finish;
  backing_control=1;
  status=0;
  primary="native_adapter_cases_passed";
  finish:alarm(30);
  if(untracked_created||bind_observation.close_error||namespace_budget.close_error||fixture_envelope.close_error)cleanup=1;
  interrupted=0;
  if(source>=0&&cleanup_owned(source))cleanup=1;
  if(source_created)  {
    struct stat st;
    if(source<0||!source_identity_captured||fstat(source,&st)||st.st_dev!=sourcest.st_dev||st.st_ino!=sourcest.st_ino||unlinkat(work,"source",AT_REMOVEDIR))cleanup=1;
  }
  if(recheck(&rt))cleanup=1;
  if(close_leases(&rt))cleanup=1;
  if(runtime_owned&&(remove_exact_runtime(runtime)||unlinkat(work,"runtime",AT_REMOVEDIR)))cleanup=1;
  if(work_owned&&fchown(work,workst.st_uid,workst.st_gid))cleanup=1;
  if(source>=0&&close(source))cleanup=1;
  if(runtime>=0&&close(runtime))cleanup=1;
  if(work>=0&&close(work))cleanup=1;
  if(adapter>=0&&close(adapter))cleanup=1;
  if(global>=0&&close(global))cleanup=1;
  if(fixture_envelope_restore())cleanup=1;
  alarm(0);
  if(cleanup)status=1;
  printf("{\"fixture\":1,\"verdict\":\"%s\",\"cleanup\":\"%s\",\"primary\":\"%s\",\"adapter_started\":%s,\"adapter_invocations\":%u,\"adapter_invocations_relation\":\"%s\",\"tmpfs_adapter_attempt\":\"%s\",\"adapter_exit\":%d,\"cleanup_error\":%s,\"negative_controls\":{\"wrong_uid\":%s,\"symlink\":%s,\"runtime_hash\":%s,\"actual_tmpfs_backing\":%s},\"adapter_result\":",status?"BLOCKED":"PASS",cleanup?"UNKNOWN":"confirmed",primary,adapter_calls?"true":"false",adapter_calls,backing_context_started&&!backing_control?"confirmed_lower_bound":"exact",backing_control?"confirmed":backing_context_started?"UNKNOWN":"NOT_RUN",result.code,cleanup?"true":"false",uid_control?"true":"false",symlink_control?"true":"false",runtime_control?"true":"false",backing_control?"true":"false");
  if(result.used&&result.used<=OUTPUT_LIMIT&&result.out[0]=='{'&&result.out[result.used-1]=='\n')fwrite(result.out,1,result.used-1,stdout);
  else fputs("null",stdout);
  fputs(",\"fd_budget\":",stdout);print_fd_budget(&namespace_budget);
  fputs(",\"resource_envelope\":",stdout);print_fixture_envelope();
  fputs(",\"fixture_diagnostic\":{\"errno\":",stdout);
  if(fixture_errno>=0)printf("%d",fixture_errno);else fputs("null",stdout);
  fputs(",\"fs_magic\":",stdout);
  if(fixture_has_magic)printf("%lu",fixture_magic);else fputs("null",stdout);
  fputs(",\"entry\":",stdout);
  if(fixture_entry>=0)printf("%d",fixture_entry);else fputs("null",stdout);
  print_bind_probe(&bind_observation);
  puts("}}");
  return status;
}
#endif
