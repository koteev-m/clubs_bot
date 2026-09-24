#define ADAPTER_TEST 1
#include "adapter.c"
static unsigned passed,failed;
static void check(int yes,const char*name) {
  printf("{\"test\":\"%s\",\"pass\":%s}\n",name,yes?"true":"false");
  if(yes)passed++;
  else failed++;
}
static int write_file(int root,const char*n,const char*b) {
  int f=openat(root,n,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW,0600);
  if(f<0)return -1;
  if(write(f,b,strlen(b))!=(ssize_t)strlen(b)) {
    close(f);
    return -1;
  }
  return close(f);
}
static struct captured child_test(int kind) {
  int a[2],b[2],c[2];
  pid_t p;
  struct captured r;
  pipe(a);
  pipe(b);
  pipe(c);
  p=fork();
  if(p==0) {
    char data[5000];
    setpgid(0,0);
    dup2(a[0],0);
    dup2(b[1],1);
    dup2(c[1],2);
    close(a[1]);
    close(b[0]);
    close(c[0]);
    if(kind==0)write(1,"partial",7);
    else if(kind==1) {
      memset(data,'x',sizeof data);
      write(1,data,sizeof data);
      sleep(10);
    }
    else if(kind==2||kind==3) {
      write(1,"partial",7);
      sleep(10);
    }
    else if(kind==4) {
      write(2,"PRIVATE-CANARY-DO-NOT-PUBLISH",27);
    }
    _exit(0);
  }
  setpgid(p,p);
  close(a[0]);
  close(b[1]);
  close(c[1]);
  interrupted=0;
  if(kind==3) {
    signal(SIGALRM,on_signal);
    ualarm(50000,0);
  }
  capture_child(p,a[1],(const unsigned char*)"",0,b[0],c[0],kind==2?.05:1,0,&r);
  ualarm(0,0);
  interrupted=0;
  return r;
}
int main(void) {
  char tmp[]="/tmp/clb91-adapter-core-XXXXXX",h[65];
  unsigned char key[32]= {
    0
  };
  int d;
  gid_t fixture_gid;
  struct stat fixture_stat;
  struct lease_set s= {
    0
  };
  struct captured r;
  const char*j;
  size_t len;
  char public[1024];
  signal(SIGPIPE,SIG_IGN);
  digest("abc",3,h);
  check(!strcmp(h,"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),"sha256_known_vector");
  hmac32(key,"abc",3,h);
  check(!strcmp(h,"fd7adb152c05ef80dccf50a1fa4c05d5a3ec6da95575fc312ae7c5d091836351"),"hmac_known_vector");
  if(!mkdtemp(tmp))return 2;
  d=open(tmp,O_RDONLY|O_DIRECTORY);
  if(d<0)return 2;
  if(fstat(d,&fixture_stat))return 2;
  fixture_gid=fixture_stat.st_gid;
  write_file(d,"input","synthetic-private-canary");
  check(!hold(&s,d,"input",getuid(),fixture_gid,0600,1,64,1)&&!recheck(&s),"real_identity_held_fd");
  renameat(d,"input",d,"old");
  write_file(d,"input","synthetic-private-canary");
  check(recheck(&s)!=0,"same_bytes_copy_is_not_original");
  close_leases(&s);
  unlinkat(d,"input",0);
  renameat(d,"old",d,"input");
  check(hold(&s,d,"input",getuid()+1,fixture_gid,0600,1,64,1)!=0,"wrong_principal_refused");
  symlinkat("input",d,"link");
  check(hold(&s,d,"link",getuid(),fixture_gid,0600,1,64,1)!=0,"leaf_symlink_refused");
  check(open_beneath(d,"../input",O_RDONLY)<0,"parent_traversal_refused");
  check(!hold_symlink(&s,d,"link",getuid(),fixture_gid,"input")&&!recheck(&s),"approved_alias_held");
  unlinkat(d,"link",0);
  symlinkat("old",d,"link");
  check(recheck(&s)!=0,"alias_target_replaced");
  close_leases(&s);
  unlinkat(d,"link",0);
  hold(&s,d,"input",getuid(),fixture_gid,0600,1,64,1);
  {
    int f=openat(d,"input",O_WRONLY|O_NOFOLLOW);
    pwrite(f,"X",1,0);
    close(f);
  }
  check(recheck(&s)!=0,"held_file_content_drift");
  close_leases(&s);
  check(!hold_absence(&s,d,"absent")&&!recheck(&s),"absence_lease");
  write_file(d,"absent","new");
  check(recheck(&s)!=0,"absence_to_presence_refused");
  close_leases(&s);
  unlinkat(d,"absent",0);
  check(hold(&s,d,"input",getuid(),fixture_gid,0600,1,2,1)!=0,"input_size_bound");
  check(supported_backing(0xef53)&&!supported_backing(0x01021994)&&!supported_backing(0x794c7630),"backing_allowlist_not_forged_observation");
  r=child_test(0);
  check(r.code==0&&r.used==7&&!strcmp(r.primary,"none")&&!r.cleanup&&authenticated(&r,key,&j,&len)!=0,"actual_partial_output_refused");
  r=child_test(1);
  check(!strcmp(r.primary,"output_bounds")&&r.signal_no==SIGKILL&&!r.cleanup,"actual_output_bounds_kill_reap");
  r=child_test(2);
  check(!strcmp(r.primary,"timeout")&&r.signal_no==SIGKILL&&!r.cleanup,"actual_timeout_kill_reap");
  r=child_test(3);
  check(!strcmp(r.primary,"cancelled")&&r.signal_no==SIGKILL&&!r.cleanup,"actual_cancellation_kill_reap");
  r=child_test(4);
  check(r.err_bytes==27&&!strstr((char*)r.out,"CANARY")&&authenticated(&r,key,&j,&len)!=0,"stderr_count_only_no_secret_export");
  strcpy(public,"{\"body\":\"compose-env-semantic:v=1 result=equivalent strategy=remove scope=snapshot future=requires_recheck application=not_authorized\\n\",\"cleanup_errors\":[]}");
  memset(&r,0,sizeof r);
  r.primary="none";
  r.code=0;
  hmac32(key,public,strlen(public),h);
  r.used=(size_t)snprintf((char*)r.out,sizeof r.out,"clb91-isolated-auth:v=1 tag=%s %s\n",h,public);
  check(!authenticated(&r,key,&j,&len),"exact_authenticated_semantic_schema");
  r.out[40]^=1;
  check(authenticated(&r,key,&j,&len)!=0,"authenticated_tamper_refused");
  r.out[40]^=1;
  r.code=1;
  check(authenticated(&r,key,&j,&len)!=0,"exit_semantic_mismatch_refused");
  strcpy(public,"{\"body\":\"compose-env-semantic:v=1 result=unavailable reason=io\\n\",\"cleanup_errors\":[\"capture_close\"]}");
  r.code=1;
  hmac32(key,public,strlen(public),h);
  r.used=(size_t)snprintf((char*)r.out,sizeof r.out,"clb91-isolated-auth:v=1 tag=%s %s\n",h,public);
  check(!authenticated(&r,key,&j,&len),"primary_and_cleanup_both_retained");
  hold(&s,d,"input",getuid(),fixture_gid,0600,1,64,1);
  close(s.a[0].fd);
  check(close_leases(&s)!=0,"real_close_error_independent");
  unlinkat(d,"input",0);
  close(d);
  check(!rmdir(tmp),"owned_fixture_cleanup");
  printf("{\"summary\":{\"passed\":%u,\"failed\":%u,\"native_namespace\":\"NOT_RUN\",\"architecture\":\"host common C core only\"}}\n",passed,failed);
  return failed?1:0;
}
