/* Helper-only FD handoff. An object identity is not mount-namespace membership.
 * Reuse the bounded procfs parsers; no mount, retry, policy change or raw output. */
#ifndef CLB91_FIXTURE_NAMESPACE_HANDLES_H
#define CLB91_FIXTURE_NAMESPACE_HANDLES_H
static int nh_close(int *fd,int *cleanup) {
  int old=*fd;*fd=-1; /* Never retry close: the descriptor may already be released. */
  if(old>=0&&close(old)){*cleanup=1;return -1;}return 0;
}
static int nh_identity(int fd,int anchor,const struct stat *trusted) {
  struct stat current,original;
  return fstat(fd,&current)||fstat(anchor,&original)||!S_ISDIR(current.st_mode)||
    !same_stat(trusted,&original)||!same_stat(trusted,&current)?-1:0;
}
static int nh_member(int fd,const char *canonical,int *cleanup) {
  struct bind_probe p=bp_empty();unsigned id=0,again=0;size_t n=0;int result=-1;
  char *info=malloc(BP_MI_LIMIT+1);
  if(!info)goto finish;
  if(bp_fd_mount(fd,&id,&p)||bp_read("/proc/self/mountinfo",info,BP_MI_LIMIT,&n,&p))goto finish;
  /* Membership alone is required here. A fully parsed table may have a bounded
   * submount overflow; that is not an absent/duplicate/invalid mount ID.
   * bp_mountinfo sets both states to ok only after parsing the entire table. */
  (void)bp_mountinfo(info,n,id,id,canonical,&p);
  if(strcmp(p.source_state,"ok")||strcmp(p.target_state,"ok")||
     bp_fd_mount(fd,&again,&p)||again!=id||p.close_error)goto finish;
  result=0;
finish:
  if(p.close_error)*cleanup=1;
  free(info);return result;
}
static int nh_handoff(int *global,int *work,int *runtime,const char *workpath,
                      const struct stat *work_identity,const struct stat *runtime_identity,
                      struct lease_set *leases,const char **primary,int *cleanup) {
  int fresh_global=-1,fresh_work=-1,fresh_runtime=-1,result=-1;
  struct lease_set *fresh_leases=NULL;char runtimepath[PATH_MAX];
  *primary="namespace_current_root";
  fresh_global=open("/",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
  if(fresh_global<0)goto finish;
  *primary="namespace_work_reopen";
  if(!test_work_path(workpath))goto finish;
  fresh_work=open_beneath(fresh_global,workpath,O_RDONLY|O_DIRECTORY);
  if(fresh_work<0)goto finish;
  *primary="namespace_work_identity";
  if(nh_identity(fresh_work,*work,work_identity)||work_identity->st_uid||
     work_identity->st_gid||(work_identity->st_mode&07777)!=0700)goto finish;
  *primary="namespace_work_mount";
  if(nh_member(fresh_work,workpath,cleanup))goto finish;
  *primary="namespace_runtime_reopen";
  fresh_runtime=openat(fresh_work,"runtime",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
  if(fresh_runtime<0)goto finish;
  *primary="namespace_runtime_identity";
  if(nh_identity(fresh_runtime,*runtime,runtime_identity))goto finish;
  *primary="namespace_runtime_verify";
  fresh_leases=calloc(1,sizeof *fresh_leases);
  if(!fresh_leases||verify_runtime(fresh_runtime,fresh_leases))goto finish;
  *primary="namespace_runtime_mount";
  if(snprintf(runtimepath,sizeof runtimepath,"%s/runtime",workpath)>=(int)sizeof runtimepath||
     nh_member(fresh_runtime,runtimepath,cleanup))goto finish;
  *primary="namespace_handoff_recheck";
  if(nh_identity(fresh_work,*work,work_identity)||nh_identity(fresh_runtime,*runtime,runtime_identity)||
     recheck(leases)||recheck(fresh_leases))goto finish;
  /* Commit the verified current handles together. Even a close failure leaves
   * cleanup using these handles; old descriptors are never mount sources. */
  *primary="namespace_anchor_close";
  if(close_leases(leases))*cleanup=1;
  *leases=*fresh_leases;fresh_leases->n=0;
  {int old_global=*global,old_work=*work,old_runtime=*runtime;
    *global=fresh_global;fresh_global=-1;*work=fresh_work;fresh_work=-1;
    *runtime=fresh_runtime;fresh_runtime=-1;
    nh_close(&old_runtime,cleanup);nh_close(&old_work,cleanup);nh_close(&old_global,cleanup);
  }
  if(!*cleanup)result=0;
finish:
  if(fresh_leases){if(close_leases(fresh_leases))*cleanup=1;free(fresh_leases);}
  nh_close(&fresh_runtime,cleanup);nh_close(&fresh_work,cleanup);nh_close(&fresh_global,cleanup);
  return result;
}
/* fingerprint() has its own CLONE_NEWNS boundary. Reopen its runtime and source
 * inside that child as well. Inherited anchors are only compared and closed. */
static int nh_fingerprint_handles(const char *runtimepath,int *runtime,int *source,
                                  const struct stat *runtime_identity,const struct stat *source_identity) {
  int root=-1,r=-1,s=-1,cleanup=0,result=-1;struct lease_set *leases=NULL;
  root=open("/",O_RDONLY|O_DIRECTORY|O_CLOEXEC);if(root<0)goto finish;
  r=open_beneath(root,runtimepath,O_RDONLY|O_DIRECTORY);
  s=open_beneath(root,SOURCE_ROOT,O_RDONLY|O_DIRECTORY);
  if(r<0||s<0||nh_identity(r,*runtime,runtime_identity)||nh_identity(s,*source,source_identity))goto finish;
  leases=calloc(1,sizeof *leases);
  if(!leases||verify_runtime(r,leases)||nh_member(r,runtimepath,&cleanup)||
     nh_member(s,SOURCE_ROOT,&cleanup)||nh_identity(r,*runtime,runtime_identity)||
     nh_identity(s,*source,source_identity)||recheck(leases))goto finish;
  nh_close(runtime,&cleanup);nh_close(source,&cleanup);
  *runtime=r;r=-1;*source=s;s=-1;
  result=0;
finish:
  if(leases){if(close_leases(leases))cleanup=1;free(leases);}
  nh_close(&r,&cleanup);nh_close(&s,&cleanup);nh_close(&root,&cleanup);
  return result||cleanup?-1:0;
}
#endif
