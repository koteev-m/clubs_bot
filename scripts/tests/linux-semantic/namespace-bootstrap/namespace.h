/* Fixed replacement for the test adapter's namespace_child only.
 * Existing parent retains original leases/locks, Runtime verifies before capture.
 * No caller-selected program, Docker API or host-global mount operation. */
static void ns195_write(int fd,const unsigned char *p,size_t n) {
  while(n) {ssize_t k=write(fd,p,n);if(k<0&&errno==EINTR)continue;
    if(k<=0)child_fail("pipes");p+=k;n-=(size_t)k;}
}
static void ns195_read(int fd,unsigned char *p,size_t n) {
  while(n) {ssize_t k=read(fd,p,n);if(k<0&&errno==EINTR)continue;
    if(k<=0)child_fail("pipes");p+=k;n-=(size_t)k;}
}
/* Namespace PID1 ignores default terminating signals; use an explicit handler.
 * Exiting PID1 tears down every remaining descendant and its private mounts. */
static void ns195_stop(int sig) {_exit(128+sig);}
static void ns195_exec(const char *role,int code,int input,int output,int result) {
  /* Duplicate above all destinations before remapping; never inherit source FDs. */
  int f[4]={fcntl(code,F_DUPFD_CLOEXEC,10),fcntl(input,F_DUPFD_CLOEXEC,10),
            fcntl(output,F_DUPFD_CLOEXEC,10),result<0?-1:fcntl(result,F_DUPFD_CLOEXEC,10)};
  struct rlimit r;
  if(f[0]<0||f[1]<0||f[2]<0||(result>=0&&f[3]<0))child_fail("pipes");
  if(dup2(f[0],0)<0||dup2(f[1],3)<0||dup2(f[2],1)<0)child_fail("pipes");
  if(result>=0) {if(dup2(f[3],4)<0)child_fail("pipes");} else close(4);
  if(syscall(SYS_close_range,5U,~0U,0))child_fail("descriptor_close");
  r.rlim_cur=r.rlim_max=768ULL*1024*1024;if(setrlimit(RLIMIT_AS,&r))child_fail("as");
  r.rlim_cur=r.rlim_max=0;if(setrlimit(RLIMIT_CORE,&r))child_fail("core");
  r.rlim_cur=r.rlim_max=1024*1024;if(setrlimit(RLIMIT_FSIZE,&r))child_fail("fsize");
  r.rlim_cur=r.rlim_max=1024;if(setrlimit(RLIMIT_NOFILE,&r))child_fail("nofile");
  if(setgroups(0,NULL)||setresgid(1000,1000,1000)||setresuid(1000,1000,1000)||
     prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0)||prctl(PR_SET_PDEATHSIG,SIGKILL))child_fail("principal");
  char *const argv[]={"/usr/bin/python3.12","-I","-S","-B","-c",(char*)BOOTSTRAP,(char*)role,NULL};
  char *const env[]={"PATH=/usr/bin:/bin","HOME=/run/user/1000","LC_ALL=C",NULL};
  execve(argv[0],argv,env);child_fail("exec");
}
struct ns195_worker {int code,input,output;};
static int ns195_worker_start(void *opaque) {
  struct ns195_worker *w=opaque;
  if(prctl(PR_SET_PDEATHSIG,SIGKILL))child_fail("parent_death_guard");
  /* This namespace has no producer PID and no original source mount view. */
  if(mount(NULL,"/",NULL,MS_REC|MS_PRIVATE,NULL))child_fail("mount_private");
  if(mount("tmpfs",SOURCE_ROOT,"tmpfs",MS_RDONLY|MS_NOSUID|MS_NODEV|MS_NOEXEC,
           "size=4096,mode=0700,uid=1000,gid=1000"))child_fail("source_mount");
  if(mount("proc","/proc","proc",MS_NOSUID|MS_NODEV|MS_NOEXEC,NULL))child_fail("proc_mount");
  if(mount("tmpfs","/run/user/1000","tmpfs",MS_NOSUID|MS_NODEV|MS_NOEXEC,
           "size=8388608,mode=0700,uid=1000,gid=1000"))child_fail("private_mount");
  ns195_exec("worker",w->code,w->input,w->output,-1);return 120;
}
static void namespace_child(const struct namespace_args *a) {
  const char *reason;
  int pc[2],wc[2],snapshot[2],answer[2],status,producer_status=-1,worker_status=-1;
  pid_t producer,worker,p;
  if(dup2(a->in,0)<0||dup2(a->out,1)<0||dup2(a->err,2)<0)child_fail("pipes");
  /* Namespace PID1 owns descendants; its death destroys both child trees. */
  if(signal(SIGALRM,ns195_stop)==SIG_ERR||signal(SIGTERM,ns195_stop)==SIG_ERR||
     signal(SIGINT,ns195_stop)==SIG_ERR||signal(SIGHUP,ns195_stop)==SIG_ERR)child_fail("parent_death_guard");
  alarm(100);
  active_child_handles=calloc(1,sizeof *active_child_handles);
  if(!active_child_handles)child_fail("child_allocation");
  active_child_handles->global=active_child_handles->runtime=active_child_handles->source=-1;
  reason=child_reopen(a,active_child_handles);if(reason)child_fail(reason);
  private_view(a->runtime,active_child_handles->runtime,active_child_handles->source,
               &active_child_handles->source_leases,&active_child_handles->outer);
  if(recheck(&active_child_handles->runtime_leases)||recheck(&active_child_handles->source_leases))child_fail("child_view_recheck");
  {struct child_handles *h=active_child_handles;active_child_handles=NULL;
   if(child_handles_close(h))child_fail("child_cleanup");}
  unsigned char *request=malloc(32+REQUEST_TAIL_LEN);
  if(!request)child_fail("child_allocation");
  ns195_read(0,request,32+REQUEST_TAIL_LEN);
  if(memcmp(request+32,REQUEST_TAIL,REQUEST_TAIL_LEN))child_fail("exec");
  if(pipe2(pc,O_CLOEXEC)||pipe2(wc,O_CLOEXEC)||pipe2(snapshot,O_CLOEXEC)||pipe2(answer,O_CLOEXEC))child_fail("pipes");
  struct ns195_worker w={wc[0],snapshot[0],answer[1]};
  void *stack=malloc(1024*1024);if(!stack)child_fail("child_allocation");
  worker=clone(ns195_worker_start,(char*)stack+1024*1024,CLONE_NEWNS|CLONE_NEWPID|CLONE_NEWNET|SIGCHLD,&w);
  if(worker<0)child_fail("fork");free(stack);
  producer=fork();if(producer<0)child_fail("fork");
  if(!producer)ns195_exec("producer",pc[0],snapshot[1],1,answer[0]);
  close(pc[0]);close(wc[0]);close(snapshot[0]);close(snapshot[1]);close(answer[0]);close(answer[1]);
  ns195_write(wc[1],request,32+REQUEST_TAIL_LEN);close(wc[1]);
  ns195_write(pc[1],request,32+REQUEST_TAIL_LEN);close(pc[1]);free(request);
  close_extra_fds();
  if(setgroups(0,NULL)||setresgid(1000,1000,1000)||setresuid(1000,1000,1000)||
     prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0))child_fail("principal");
  while((p=waitpid(-1,&status,0))>0||errno==EINTR) {
    if(p<0)continue;
    if(p==producer)producer_status=status;
    if(p==worker)worker_status=status;
    if((p==producer||p==worker)&&(!WIFEXITED(status)||WEXITSTATUS(status)!=0))kill(-1,SIGKILL);
  }
  alarm(0);
  if(producer_status<0||worker_status<0||!WIFEXITED(producer_status)||!WIFEXITED(worker_status)||WEXITSTATUS(worker_status))_exit(1);
  _exit(WEXITSTATUS(producer_status));
}
