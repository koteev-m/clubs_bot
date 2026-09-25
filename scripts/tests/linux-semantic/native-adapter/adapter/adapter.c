/* CLB-91 candidate: fixed semantic adapter. No installer or arbitrary exec API.
* Linux namespace branch requires a future separately authorized root test VM.
* Mac builds exercise common primitives only; they cannot produce native PASS.
*/
#define _GNU_SOURCE 1
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/resource.h>
#include <sys/file.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <poll.h>
#include <time.h>
#include <dirent.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>
#ifdef __linux__
#include <sched.h>
#include <sys/mount.h>
#include <sys/statfs.h>
#include <sys/prctl.h>
#include <sys/random.h>
#include <sys/utsname.h>
#include <sys/sysmacros.h>
#include <sys/syscall.h>
#include <sys/mman.h>
#include <grp.h>
#endif
#define MAX_LEASES 384
#define MAX_RUNTIME (128ULL * 1024 * 1024)
#define OUTPUT_LIMIT 4096
#define MAX_PATH 1024
#define RUN_SECONDS 110
#define SOURCE_ROOT "/opt/clubs-bot-stage"
struct contract_entry  {
  const char *path;
  int kind;
  unsigned mode;
  unsigned long long size;
  const char *sha256;
  const char *target;
  long long mtime;
};
#include "generated_contract.h"
static int dup_cloexec(int fd) {
  return fcntl(fd,F_DUPFD_CLOEXEC,3);
}
/* FIPS 180-4 SHA-256, used only for exact content identity and HMAC framing. */
typedef struct  {
  uint32_t h[8];
  uint64_t n;
  unsigned used;
  unsigned char b[64];
}
sha_ctx;
static const uint32_t K[64]=  {
  0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,
  0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,
  0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,
  0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,
  0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,
  0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};
static uint32_t rr(uint32_t v,unsigned n)  {
  return(v>>n)|(v<<(32-n));
}
static void sha_block(sha_ctx*c,const unsigned char*b)  {
  uint32_t w[64],a,d,e,f,g,h,x,y,bb,cc;
  unsigned i;
  for(i=0;i<16;i++)w[i]=(uint32_t)b[4*i]<<24|(uint32_t)b[4*i+1]<<16|(uint32_t)b[4*i+2]<<8|b[4*i+3];
  for(i=16;i<64;i++)w[i]=w[i-16]+(rr(w[i-15],7)^rr(w[i-15],18)^(w[i-15]>>3))+w[i-7]+(rr(w[i-2],17)^rr(w[i-2],19)^(w[i-2]>>10));
  a=c->h[0];
  bb=c->h[1];
  cc=c->h[2];
  d=c->h[3];
  e=c->h[4];
  f=c->h[5];
  g=c->h[6];
  h=c->h[7];
  for(i=0;i<64;i++)  {
    x=h+(rr(e,6)^rr(e,11)^rr(e,25))+((e&f)^(~e&g))+K[i]+w[i];
    y=(rr(a,2)^rr(a,13)^rr(a,22))+((a&bb)^(a&cc)^(bb&cc));
    h=g;
    g=f;
    f=e;
    e=d+x;
    d=cc;
    cc=bb;
    bb=a;
    a=x+y;
  }
  c->h[0]+=a;
  c->h[1]+=bb;
  c->h[2]+=cc;
  c->h[3]+=d;
  c->h[4]+=e;
  c->h[5]+=f;
  c->h[6]+=g;
  c->h[7]+=h;
}
static void sha_init(sha_ctx*c)  {
  static const uint32_t init[8]=  {
    0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19
  };
  memcpy(c->h,init,32);
  c->n=0;
  c->used=0;
}
static void sha_update(sha_ctx*c,const void*p,size_t n)  {
  const unsigned char*b=p;
  c->n+=n;
  while(n)  {
    size_t take=64-c->used;
    if(take>n)take=n;
    memcpy(c->b+c->used,b,take);
    c->used+=(unsigned)take;
    b+=take;
    n-=take;
    if(c->used==64)  {
      sha_block(c,c->b);
      c->used=0;
    }
  }
}
static void sha_final(sha_ctx*c,unsigned char*out)  {
  uint64_t bits=c->n*8;
  unsigned i;
  c->b[c->used++]=128;
  if(c->used>56)  {
    memset(c->b+c->used,0,64-c->used);
    sha_block(c,c->b);
    c->used=0;
  }
  memset(c->b+c->used,0,56-c->used);
  for(i=0;i<8;i++)c->b[63-i]=(unsigned char)(bits>>(8*i));
  sha_block(c,c->b);
  for(i=0;i<32;i++)out[i]=(unsigned char)(c->h[i/4]>>(24-8*(i%4)));
}
static void hex(const unsigned char*b,size_t n,char*out)  {
  static const char t[]="0123456789abcdef";
  size_t i;
  for(i=0;i<n;i++)  {
    out[2*i]=t[b[i]>>4];
    out[2*i+1]=t[b[i]&15];
  }
  out[2*n]=0;
}
static void digest(const void*b,size_t n,char out[65])  {
  sha_ctx c;
  unsigned char v[32];
  sha_init(&c);
  sha_update(&c,b,n);
  sha_final(&c,v);
  hex(v,32,out);
}
static void hmac32(const unsigned char key[32],const void*b,size_t n,char out[65])  {
  unsigned char p[64],v[32];
  sha_ctx c;
  size_t i;
  for(i=0;i<64;i++)p[i]=(i<32?key[i]:0)^0x36;
  sha_init(&c);
  sha_update(&c,p,64);
  sha_update(&c,b,n);
  sha_final(&c,v);
  for(i=0;i<64;i++)p[i]=(i<32?key[i]:0)^0x5c;
  sha_init(&c);
  sha_update(&c,p,64);
  sha_update(&c,v,32);
  sha_final(&c,v);
  hex(v,32,out);
}
static volatile sig_atomic_t interrupted;
static void on_signal(int sig)  {
  (void)sig;
  interrupted=1;
}
static double now(void)  {
  struct timespec t;
  if(clock_gettime(CLOCK_MONOTONIC,&t))return 0;
  return(double)t.tv_sec+t.tv_nsec/1e9;
}
static int same_stat(const struct stat*a,const struct stat*b)  {
#ifdef __APPLE__
#define MT st_mtimespec
#define CT st_ctimespec
#else
#define MT st_mtim
#define CT st_ctim
#endif
  return a->st_dev==b->st_dev&&a->st_ino==b->st_ino&&a->st_mode==b->st_mode&&a->st_uid==b->st_uid&&a->st_gid==b->st_gid&&a->st_nlink==b->st_nlink&&a->st_size==b->st_size&&a->MT.tv_sec==b->MT.tv_sec&&a->MT.tv_nsec==b->MT.tv_nsec&&a->CT.tv_sec==b->CT.tv_sec&&a->CT.tv_nsec==b->CT.tv_nsec;
}
static int hash_fd(int fd,unsigned long long limit,char out[65])  {
  sha_ctx c;
  unsigned char v[32],b[32768];
  off_t off=0;
  ssize_t n;
  sha_init(&c);
  for(;;)  {
    n=pread(fd,b,sizeof b,off);
    if(interrupted)return -1;
    if(n<0&&errno==EINTR)continue;
    if(n<0)return -1;
    if(!n)break;
    if((unsigned long long)off+(unsigned long long)n>limit)return -1;
    sha_update(&c,b,(size_t)n);
    off+=n;
  }
  sha_final(&c,v);
  hex(v,32,out);
  return 0;
}
/* Component-by-component O_NOFOLLOW: no symlink parent is accepted. */
static int open_beneath(int root,const char*path,int flags)  {
  int fd=dup_cloexec(root),next;
  char copy[MAX_PATH],*save=NULL,*p;
  if(fd<0||strlen(path)>=sizeof copy)  {
    if(fd>=0)close(fd);
    return -1;
  }
  strcpy(copy,path);
  for(p=strtok_r(copy,"/",&save);p;p=strtok_r(NULL,"/",&save))  {
    if(!strcmp(p,".")||!strcmp(p,"..")||!*p)  {
      close(fd);
      return -1;
    }
    next=openat(fd,p,(save&&*save?O_RDONLY|O_DIRECTORY:flags)|O_NOFOLLOW|O_CLOEXEC|O_NONBLOCK);
    close(fd);
    if(next<0)return -1;
    fd=next;
  }
  return fd;
}
static int test_work_path(const char*p)  {
  const char*prefix="/tmp/clb91-native-";
  size_t i,n=strlen(prefix),z=strlen(p);
  if(z<n+6||z>n+64||strncmp(p,prefix,n))return 0;
  for(i=n;i<z;i++)if(!((p[i]>='a'&&p[i]<='z')||(p[i]>='A'&&p[i]<='Z')||(p[i]>='0'&&p[i]<='9')||p[i]=='_'||p[i]=='-'))return 0;
  return 1;
}
static int safe_runtime_path(int root,const char*p)  {
  char work[MAX_PATH];
  struct stat st;
  int tmp,w;
  size_t n=strlen(p);
  if(n<9||n>=sizeof work||strcmp(p+n-8,"/runtime"))return -1;
  memcpy(work,p,n-8);
  work[n-8]=0;
  if(!test_work_path(work))return -1;
  tmp=openat(root,"tmp",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
  if(tmp<0)return -1;
  if(fstat(tmp,&st)||st.st_uid||st.st_gid||(st.st_mode&07777)!=01777)  {
    close(tmp);
    return -1;
  }
  w=openat(tmp,work+5,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
  close(tmp);
  if(w<0)return -1;
  if(fstat(w,&st)||st.st_uid||st.st_gid||(st.st_mode&07777)!=0700)  {
    close(w);
    return -1;
  }
  close(w);
  return 0;
}
struct lease  {
  int fd,parent;
  char path[MAX_PATH],name[256],hash[65];
  struct stat st;
  unsigned long long limit;
  int hashed;
};
struct lease_set  {
  struct lease a[MAX_LEASES];
  size_t n;
};
static int hold(struct lease_set*s,int root,const char*path,uid_t uid,gid_t gid,unsigned mode,int kind,unsigned long long limit,int content)  {
  struct lease*l;
  char tmp[MAX_PATH],*last;
  int fd,parent;
  struct stat st;
  if(s->n>=MAX_LEASES||strlen(path)>=sizeof tmp)return -1;
  strcpy(tmp,path);
  last=strrchr(tmp,'/');
  if(last)  {
    *last++=0;
    parent=open_beneath(root,*tmp?tmp:"/",O_RDONLY|O_DIRECTORY);
  }
  else  {
    parent=dup_cloexec(root);
    last=tmp;
  }
  if(parent<0||strlen(last)>255)  {
    if(parent>=0)close(parent);
    return -1;
  }
  fd=openat(parent,last,O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC|(kind==2?O_DIRECTORY:0));
  if(fd<0)  {
    close(parent);
    return -1;
  }
  if(fstat(fd,&st)||st.st_uid!=uid||st.st_gid!=gid||(st.st_mode&07777)!=mode||(kind==1&&!S_ISREG(st.st_mode))||(kind==2&&!S_ISDIR(st.st_mode))||(kind==1&&(st.st_nlink!=1||(unsigned long long)st.st_size>limit)))  {
    close(fd);
    close(parent);
    return -1;
  }
  l=&s->a[s->n];
  memset(l,0,sizeof*l);
  l->fd=fd;
  l->parent=parent;
  strcpy(l->path,path);
  strcpy(l->name,last);
  l->st=st;
  l->limit=limit;
  l->hashed=content;
  if(content&&hash_fd(fd,limit,l->hash))  {
    close(fd);
    close(parent);
    return -1;
  }
  s->n++;
  return 0;
}
static int hold_absence(struct lease_set*s,int root,const char*path)  {
  struct lease*l;
  char tmp[MAX_PATH],*b;
  struct stat st;
  int parent;
  if(s->n>=MAX_LEASES||strlen(path)>=sizeof tmp)return -1;
  strcpy(tmp,path);
  b=strrchr(tmp,'/');
  if(b)  {
    *b++=0;
    parent=open_beneath(root,tmp,O_RDONLY|O_DIRECTORY);
  }
  else  {
    b=tmp;
    parent=dup_cloexec(root);
  }
  if(parent<0||strlen(b)>255)return -1;
  if(!fstatat(parent,b,&st,AT_SYMLINK_NOFOLLOW)||errno!=ENOENT)  {
    close(parent);
    return -1;
  }
  l=&s->a[s->n++];
  memset(l,0,sizeof*l);
  l->fd=-1;
  l->parent=parent;
  strcpy(l->path,path);
  strcpy(l->name,b);
  return 0;
}
static int hold_symlink(struct lease_set*s,int root,const char*path,uid_t uid,gid_t gid,const char*target)  {
  struct lease*l;
  char tmp[MAX_PATH],*b,t[MAX_PATH];
  struct stat st;
  ssize_t n;
  int parent;
  if(s->n>=MAX_LEASES||strlen(path)>=sizeof tmp)return -1;
  strcpy(tmp,path);
  b=strrchr(tmp,'/');
  if(b)  {
    *b++=0;
    parent=open_beneath(root,*tmp?tmp:"/",O_RDONLY|O_DIRECTORY);
  }
  else  {
    b=tmp;
    parent=dup_cloexec(root);
  }
  if(parent<0||strlen(b)>255)return -1;
  n=readlinkat(parent,b,t,sizeof t-1);
  if(n<0||fstatat(parent,b,&st,AT_SYMLINK_NOFOLLOW)||!S_ISLNK(st.st_mode)||st.st_uid!=uid||st.st_gid!=gid)  {
    close(parent);
    return -1;
  }
  t[n]=0;
  if(strcmp(t,target))  {
    close(parent);
    return -1;
  }
  l=&s->a[s->n++];
  memset(l,0,sizeof*l);
  l->fd=-2;
  l->parent=parent;
  l->st=st;
  strcpy(l->path,path);
  strcpy(l->name,b);
  digest(t,(size_t)n,l->hash);
  return 0;
}
static int recheck(const struct lease_set*s)  {
  size_t i;
  struct stat a,b;
  char h[65];
  for(i=0;i<s->n;i++)  {
    const struct lease*l=&s->a[i];
    if(l->fd==-1)  {
      if(!fstatat(l->parent,l->name,&b,AT_SYMLINK_NOFOLLOW)||errno!=ENOENT)return -1;
      continue;
    }
    if(l->fd==-2)  {
      char t[MAX_PATH];
      ssize_t n=readlinkat(l->parent,l->name,t,sizeof t);
      if(n<0||fstatat(l->parent,l->name,&b,AT_SYMLINK_NOFOLLOW)||!same_stat(&l->st,&b))return -1;
      digest(t,(size_t)n,h);
      if(strcmp(h,l->hash))return -1;
      continue;
    }
    if(fstat(l->fd,&a)||fstatat(l->parent,l->name,&b,AT_SYMLINK_NOFOLLOW)||!same_stat(&l->st,&a)||!same_stat(&l->st,&b))return -1;
    if(l->hashed&&(hash_fd(l->fd,l->limit,h)||strcmp(h,l->hash)))return -1;
  }
  return 0;
}
static int close_leases(struct lease_set*s)  {
  int bad=0;
  while(s->n)  {
    struct lease*l=&s->a[--s->n];
    if(l->fd>=0&&close(l->fd))bad=1;
    if(close(l->parent))bad=1;
  }
  return bad?-1:0;
}
static int supported_backing(unsigned long t)  {
  return t==0xef53UL||t==0x58465342UL||t==0x9123683eUL||t==0x2fc12fc1UL||t==0xf2f52010UL;
}
struct captured  {
  unsigned char out[OUTPUT_LIMIT+1];
  size_t used,err_bytes,err_used;
  unsigned char err[64];
  int code,signal_no;
  const char*primary;
  int cleanup;
};
/* Own exact child PID, never kill by name. Linux caller uses PID namespace PID1:
* killing that exact PID destroys all namespace descendants. Common tests use
* a fixed isolated process group, explicitly not proof of namespace cleanup. */
static void capture_child(pid_t pid,int infd,const unsigned char*input,size_t input_len,int outfd,int errfd,double seconds,int ns,struct captured*r)  {
  double end=now()+seconds;
  size_t sent=0;
  int status=0,done=0,outopen=1,erropen=1;
  memset(r,0,sizeof*r);
  r->code=-1;
  r->primary="none";
  fcntl(infd,F_SETFL,O_NONBLOCK);
  fcntl(outfd,F_SETFL,O_NONBLOCK);
  fcntl(errfd,F_SETFL,O_NONBLOCK);
  while(!done||outopen||erropen)  {
    unsigned char b[1024];
    ssize_t n;
    struct pollfd f[3]=  {
      {
        infd,POLLOUT,0
      },  {
        outfd,POLLIN|POLLHUP,0
      },  {
        errfd,POLLIN|POLLHUP,0
      }
    };
    if(interrupted)  {
      r->primary="cancelled";
      break;
    }
    if(now()>end)  {
      r->primary="timeout";
      break;
    }
    if(infd>=0&&sent==input_len)  {
      close(infd);
      infd=-1;
    }
    if(poll(f,3,20)<0&&errno!=EINTR)  {
      r->primary="capture_io";
      break;
    }
    if(infd>=0&&f[0].revents&POLLOUT)  {
      n=write(infd,input+sent,input_len-sent);
      if(n>0)sent+=(size_t)n;
      else if(n<0&&errno!=EINTR&&errno!=EAGAIN)  {
        r->primary="stdin_io";
        break;
      }
    }
    if(outopen&&(f[1].revents&(POLLIN|POLLHUP)))  {
      n=read(outfd,b,sizeof b);
      if(n>0)  {
        if(r->used+(size_t)n>OUTPUT_LIMIT)  {
          r->primary="output_bounds";
          break;
        }
        memcpy(r->out+r->used,b,(size_t)n);
        r->used+=(size_t)n;
      }
      else if(!n)  {
        outopen=0;
        close(outfd);
        outfd=-1;
      }
      else if(errno!=EINTR&&errno!=EAGAIN)  {
        r->primary="stdout_io";
        break;
      }
    }
    if(erropen&&(f[2].revents&(POLLIN|POLLHUP)))  {
      n=read(errfd,b,sizeof b);
      if(n>0)  {
        {
          size_t keep=sizeof r->err-r->err_used;
          if(keep>(size_t)n)keep=(size_t)n;
          memcpy(r->err+r->err_used,b,keep);
          r->err_used+=keep;
        }
        r->err_bytes+=(size_t)n;
        if(r->err_bytes>OUTPUT_LIMIT)  {
          r->primary="stderr_bounds";
          break;
        }
      }
      else if(!n)  {
        erropen=0;
        close(errfd);
        errfd=-1;
      }
      else if(errno!=EINTR&&errno!=EAGAIN)  {
        r->primary="stderr_io";
        break;
      }
    }
    if(!done)  {
      pid_t q=waitpid(pid,&status,WNOHANG);
      if(q==pid)done=1;
      else if(q<0)  {
        r->primary="wait_error";
        break;
      }
    }
  }
  if(strcmp(r->primary,"none"))  {
    if(kill(ns?pid:-pid,SIGKILL)&&errno!=ESRCH)r->cleanup=1;
  }
  if(!done)  {
    pid_t q;
    do  {
      q=waitpid(pid,&status,0);
    }
    while(q<0&&errno==EINTR);
    if(q==pid)done=1;
    else r->cleanup=1;
  }
  if(infd>=0&&close(infd))r->cleanup=1;
  if(outfd>=0&&close(outfd))r->cleanup=1;
  if(errfd>=0&&close(errfd))r->cleanup=1;
  if(done&&WIFEXITED(status))r->code=WEXITSTATUS(status);
  else if(done&&WIFSIGNALED(status))  {
    r->signal_no=WTERMSIG(status);
    r->code=128+r->signal_no;
  }
  r->out[r->used]=0;
}
/* Only authenticated exact bootstrap reports are eligible for forwarding.
* MAC is verified before any semantic fields; raw stderr is never emitted. */
static int word_in(const char*w,const char*const*set,size_t n)  {
  size_t i;
  for(i=0;i<n;i++)if(!strcmp(w,set[i]))return 1;
  return 0;
}
static const char*child_error(const struct captured*r) {
  static const char*const fixed[]= {
    "pipes","source_recheck","mount_private","runtime_mount","path","source_mount","null_device","dev_mount","dev_path","null_target",
    "null_bind","null_close","proc_mount","tmp_mount","private_mount","chroot","view_identity","as","core","fsize","nofile",
    "fork","principal","exec","wait","descriptor_close","parent_death_guard",
    "child_allocation","child_root","child_runtime_path","child_runtime_open","child_runtime_identity","child_runtime_contract",
    "child_runtime_mount","child_source_open","child_source_identity","child_source_backing","child_source_leases",
    "child_source_mount","child_outer_tuple","child_anchor_recheck","child_view_recheck","child_cleanup"
  };
  size_t i;
  static char combined[64];
  if(r->code!=120||r->err_used!=r->err_bytes)return "none_or_unrecognized";
  for(i=0;i<sizeof fixed/sizeof*fixed;i++) {
    size_t n=strlen(fixed[i]);
    if(n==r->err_used&&!memcmp(r->err,fixed[i],n))return fixed[i];
    if(n+14==r->err_used&&n+14<sizeof combined&&!memcmp(r->err,fixed[i],n)&&
       !memcmp(r->err+n,"+child_cleanup",14)) {
      memcpy(combined,fixed[i],n);memcpy(combined+n,"+child_cleanup",15);return combined;
    }
  }
  return "none_or_unrecognized";
}
static int semantic_body(const char*body,int code)  {
  static const char*const reasons[]=  {
    "runtime","request","principal","layout","identity","busy","backing","bounds","io","interrupted","cleanup","transport",
    "protocol","input","unsupported","version","parser","model","different"
  };
  static const char*const phases[]=  {
    "initial","pre_capture","post_open","capture","prepare","post_prepare","finalize"
  };
  static const char*const guards[]=  {
    "platform","manifest","availability","path_safety","integrity","interpreter","modules","aliases","maps","descriptors",
    "private_root","ruby"
  };
  static const char*const statuses[]=  {
    "pass","fail","not_evaluated"
  };
  char tmp[513],*token,*save=NULL,*values[17];
  const char*keys[]=  {
    "result","reason","phase","guard","private_capture","platform","manifest","availability","path_safety","integrity","interpreter",
    "modules","aliases","maps","descriptors","private_root","ruby"
  };
  size_t i,n=0,guard=99;
  int failures=0;
  if(!strcmp(body,"compose-env-semantic:v=1 result=equivalent strategy=remove scope=snapshot future=requires_recheck application=not_authorized")||!strcmp(body,"compose-env-semantic:v=1 result=equivalent strategy=explicit scope=snapshot future=requires_recheck application=not_authorized"))return code==0?0:-1;
  if(code!=1||strlen(body)>511)return -1;
  strcpy(tmp,body);
  token=strtok_r(tmp," ",&save);
  if(!token||strcmp(token,"compose-env-semantic:v=1"))return -1;
  while((token=strtok_r(NULL," ",&save)))  {
    size_t k;
    if(n>=17)return -1;
    k=strlen(keys[n]);
    if(strncmp(token,keys[n],k)||token[k]!='='||!token[k+1])return -1;
    values[n++]=token+k+1;
  }
  if(n!=2&&n!=17)return -1;
  if(strcmp(values[0],"unavailable")||!word_in(values[1],reasons,sizeof reasons/sizeof*reasons))return -1;
  if(n==2)return 0;
  if(!word_in(values[2],phases,sizeof phases/sizeof*phases))return -1;
  for(i=0;i<12;i++)if(!strcmp(values[3],guards[i]))guard=i;
  if(guard==99&&strcmp(values[3],"none"))return -1;
  if(strcmp(values[4],"attempted")&&strcmp(values[4],"not_started"))return -1;
  for(i=0;i<12;i++)  {
    if(!word_in(values[i+5],statuses,3))return -1;
    if(!strcmp(values[i+5],"fail"))failures++;
  }
  if((guard!=99&&strcmp(values[guard+5],"fail"))||(guard==99&&failures)||(!strcmp(values[1],"runtime")&&guard==99))return -1;
  if((!strcmp(values[2],"initial")||!strcmp(values[2],"pre_capture"))&&strcmp(values[4],"not_started"))return -1;
  if((!strcmp(values[2],"post_open")||!strcmp(values[2],"capture")||!strcmp(values[2],"prepare")||!strcmp(values[2],"post_prepare"))&&strcmp(values[4],"attempted"))return -1;
  return 0;
}
static int authenticated(const struct captured*r,const unsigned char nonce[32],const char**json,size_t*len)  {
  const char*p=(const char*)r->out;
  const char*prefix="clb91-isolated-auth:v=1 tag=";
  char tag[65],body[513];
  size_t n=strlen(prefix),i,z;
  unsigned diff=0;
  const char*end,*tail;
  if(strcmp(r->primary,"none")||r->err_bytes||r->used<n+64+4||memcmp(p,prefix,n)||p[n+64]!=' '||p[r->used-1]!='\n')return -1;
  *json=p+n+65;
  *len=r->used-n-66;
  if(**json!='{'||(*json)[*len-1]!='}')return -1;
  for(i=0;i<*len;i++)if((unsigned char)(*json)[i]<32||(unsigned char)(*json)[i]>126)return -1;
  hmac32(nonce,*json,*len,tag);
  for(i=0;i<64;i++)diff|=(unsigned char)tag[i]^(unsigned char)p[n+i];
  if(diff)return -1;
  if(strncmp(*json,"{\"body\":\"",9))return -1;
  end=strstr(*json+9,"\\n\",\"cleanup_errors\":[");
  if(!end)return -1;
  z=(size_t)(end-(*json+9));
  if(z>511)return -1;
  for(i=0;i<z;i++)if((*json)[9+i]=='\\'||(*json)[9+i]=='\"')return -1;
  memcpy(body,*json+9,z);
  body[z]=0;
  if(semantic_body(body,r->code))return -1;
  tail=end+strlen("\\n\",\"cleanup_errors\":[");
  n=(size_t)((*json+*len)-tail);
  if(n==2&&!memcmp(tail,"]}",2))return 0;
  if(r->code==0)return -1;
  if((n==17&&!memcmp(tail,"\"capture_close\"]}",17))||(n==17&&!memcmp(tail,"\"runtime_close\"]}",17))||(n==33&&!memcmp(tail,"\"capture_close\",\"runtime_close\"]}",33)))return 0;
  return -1;
}
static int verify_runtime_owned(int fd,struct lease_set*ls,uid_t owner,gid_t group)  {
  size_t i,j;
  struct stat st;
  if(fstat(fd,&st)||st.st_uid!=owner||st.st_gid!=group||(st.st_mode&07777)!=0555)return -1;
  for(i=0;i<CONTRACT_ENTRY_COUNT;i++)  {
    const struct contract_entry*c=&CONTRACT_ENTRIES[i];
    if(c->kind==3)  {
      if(hold_symlink(ls,fd,c->path+1,owner,group,c->target))return -1;
      continue;
    }
    if(hold(ls,fd,c->path+1,owner,group,c->mode,c->kind,c->size,c->kind==1))return -1;
    if(ls->a[ls->n-1].st.MT.tv_sec!=c->mtime||ls->a[ls->n-1].st.MT.tv_nsec)return -1;
    if(c->kind==1&&(ls->a[ls->n-1].st.st_size!=(off_t)c->size||strcmp(ls->a[ls->n-1].hash,c->sha256)))return -1;
  }
  /* Exhaustive directory set; unlisted runtime modules cannot hide in the view. */
  for(i=0;i<=CONTRACT_ENTRY_COUNT;i++)  {
    const char*p=i==CONTRACT_ENTRY_COUNT?"/":CONTRACT_ENTRIES[i].path;
    DIR*d;
    struct dirent*e;
    int q;
    if(i<CONTRACT_ENTRY_COUNT&&CONTRACT_ENTRIES[i].kind!=2)continue;
    q=open_beneath(fd,p,O_RDONLY|O_DIRECTORY);
    if(q<0)return -1;
    d=fdopendir(q);
    if(!d)  {
      close(q);
      return -1;
    }
    errno=0;
    while((e=readdir(d)))  {
      char full[MAX_PATH];
      int known=0;
      if(!strcmp(e->d_name,".")||!strcmp(e->d_name,".."))continue;
      if(snprintf(full,sizeof full,"%s%s%s",p,!strcmp(p,"/")?"":"/",e->d_name)>=(int)sizeof full)  {
        closedir(d);
        return -1;
      }
      for(j=0;j<CONTRACT_ENTRY_COUNT;j++)if(!strcmp(full,CONTRACT_ENTRIES[j].path))  {
        known=1;
        break;
      }
      if(!known)  {
        closedir(d);
        return -1;
      }
    }
    if(errno||closedir(d))return -1;
  }
  return recheck(ls);
}
static int verify_runtime(int fd,struct lease_set*ls)  {
  return verify_runtime_owned(fd,ls,0,0);
}
#if defined(__linux__) && !defined(ADAPTER_TEST)
#include "../tests/fixture-bind-probe.h"
#include "fd-budget.h"
struct mount_tuple  {
  char root[MAX_PATH],target[MAX_PATH],type[32],source[MAX_PATH];
};
static int read_mount_tuple(struct mount_tuple*out)  {
  FILE*f=fopen("/proc/self/mountinfo","re");
  char line[8192];
  int found=0;
  size_t total=0,lines=0;
  if(!f)return -1;
  while(fgets(line,sizeof line,f))  {
    if(interrupted||++lines>4096||(total+=strlen(line))>1024*1024) {
      fclose(f);
      return -1;
    }
    char root[MAX_PATH],target[MAX_PATH],type[32],source[MAX_PATH],*sep;
    if(!strchr(line,'\n'))  {
      fclose(f);
      return -1;
    }
    sep=strstr(line," - ");
    if(!sep)continue;
    if(sscanf(line,"%*u %*u %*s %1023s %1023s",root,target)!=2||strcmp(target,SOURCE_ROOT))continue;
    if(found++||strchr(root,'\\')||sscanf(sep+3,"%31s %1023s",type,source)!=2||strchr(source,'\\'))  {
      fclose(f);
      return -1;
    }
    strcpy(out->root,root);
    strcpy(out->target,target);
    strcpy(out->type,type);
    strcpy(out->source,source);
  }
  if(ferror(f)||fclose(f)||found!=1)return -1;
  return 0;
}
static int tuple_equal(const struct mount_tuple*a,const struct mount_tuple*b)  {
  return !strcmp(a->root,b->root)&&!strcmp(a->target,b->target)&&!strcmp(a->type,b->type)&&!strcmp(a->source,b->source);
}
static int child_path(const char*root,const char*path,char out[PATH_MAX])  {
  return snprintf(out,PATH_MAX,"%s%s",root,path)>=PATH_MAX?-1:0;
}
static const char*srcdirs[]=  {
  ".clubs-bot-release-state",".clubs-bot-release-state/stage",".clubs-bot-release-state/stage/clubs-bot-schema-stage.lock",
  ".clubs-bot-release-state/stage/clubs-bot-schema-stage.results",".clubs-bot-release-state/stage/clubs-bot-schema-stage.migration-ledgers"
};
struct source_item  {
  const char*path;
  unsigned limit;
  int required,content;
};
static const struct source_item srcfiles[]=  {
  {
    "docker-compose.yml",65536,1,1
  },  {
    "docker-compose.override.yml",4096,1,1
  },  {
    ".env",65536,1,0
  },  {
    ".clubs-bot-release-state/application.binding",2048,1,1
  },  {
    ".clubs-bot-release-state/application.lock",4096,1,0
  },  {
    ".clubs-bot-release-state/stage/clubs-bot-schema-stage.results/operation.lock",4096,1,0
  },  {
    ".clubs-bot-release-state/stage/clubs-bot-schema-stage.lock/docker-compose.release.yml",4096,1,1
  }
};
/* Remaining child peak: three current roots + full runtime/source leases +
 * three walker FDs (view root + two open_beneath FDs). Existing anchors/pipes
 * are counted live; no credit is taken for child pipe-end closures. */
static int adapter_fd_budget(struct fd_budget *b) {
  uint64_t runtime,source=2*(sizeof srcdirs/sizeof *srcdirs+sizeof srcfiles/sizeof *srcfiles);
  if(source/2>MAX_LEASES||fd_budget_runtime(&runtime)){memset(b,0,sizeof *b);return -1;}
  return fd_budget_admit(3+runtime+source+3,b);
}
static int source_optional(struct lease_set*s,int fd,const char*p,unsigned limit,int required,int content)  {
  struct stat st;
  int q=open_beneath(fd,p,O_RDONLY);
  if(q<0)return !required&&errno==ENOENT?hold_absence(s,fd,p):-1;
  if(fstat(q,&st))  {
    close(q);
    return -1;
  }
  close(q);
  if(hold(s,fd,p,1000,1000,!strcmp(p,"docker-compose.yml")&&(st.st_mode&07777)==0644?0644:0600,1,limit,content))return -1;
  return 0;
}
static int lease_source(int fd,struct lease_set*ls)  {
  size_t i;
  struct stat st;
  if(fstat(fd,&st)||st.st_uid!=1000||st.st_gid!=1000||(st.st_mode&07777)!=0700)return -1;
  for(i=0;i<sizeof srcdirs/sizeof*srcdirs;i++)if(hold(ls,fd,srcdirs[i],1000,1000,0700,2,0,0))return -1;
  for(i=0;i<sizeof srcfiles/sizeof*srcfiles;i++)if(source_optional(ls,fd,srcfiles[i].path,srcfiles[i].limit,srcfiles[i].required,srcfiles[i].content))return -1;
  for(i=0;i<ls->n;i++)if(!strcmp(ls->a[i].path,".clubs-bot-release-state/application.lock")||!strcmp(ls->a[i].path,".clubs-bot-release-state/stage/clubs-bot-schema-stage.results/operation.lock"))if(flock(ls->a[i].fd,LOCK_SH|LOCK_NB))return -1;
  return recheck(ls);
}
static int verify_view_source(int srcfd,const struct lease_set*s)  {
  struct stat a,b;
  size_t i;
  int root=open(SOURCE_ROOT,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
  if(root<0)return -1;
  if(fstat(srcfd,&a)||fstat(root,&b)||!same_stat(&a,&b))  {
    close(root);
    return -1;
  }
  for(i=0;i<s->n;i++)  {
    if(s->a[i].fd<0)  {
      int missing=open_beneath(root,s->a[i].path,O_RDONLY);
      if(missing>=0)  {
        close(missing);
        close(root);
        return -1;
      }
      if(errno!=ENOENT)  {
        close(root);
        return -1;
      }
      continue;
    }
    int q=open_beneath(root,s->a[i].path,O_RDONLY|(S_ISDIR(s->a[i].st.st_mode)?O_DIRECTORY:0));
    if(q<0||fstat(q,&b)||!same_stat(&s->a[i].st,&b))  {
      if(q>=0)close(q);
      close(root);
      return -1;
    }
    close(q);
  }
  close(root);
  return recheck(s);
}
/* Parent FDs remain trust anchors. They are never the child mount handles. */
struct namespace_args  {
  const char*runtime;
  int runtimefd,srcfd;
  const struct lease_set *boundary,*runtime_leases,*source;
  const struct mount_tuple*outer;
  struct stat runtime_identity,source_identity;
  int in,out,err,close_in,close_out,close_err;
};
struct child_handles {
  int global,runtime,source,close_error;
  struct lease_set runtime_leases,source_leases;
  struct mount_tuple outer;
};
static struct child_handles *active_child_handles;
static int child_handles_close(struct child_handles *h) {
  int bad=0;
  if(!h)return 0;
  bad=h->close_error;
  if(close_leases(&h->source_leases))bad=1;
  if(close_leases(&h->runtime_leases))bad=1;
  if(h->source>=0&&close(h->source))bad=1;h->source=-1;
  if(h->runtime>=0&&close(h->runtime))bad=1;h->runtime=-1;
  if(h->global>=0&&close(h->global))bad=1;h->global=-1;
  free(h);return bad;
}
static int child_mount_member(int fd,const char *canonical,int *close_error) {
  struct bind_probe p=bp_empty();unsigned id=0,again=0;size_t n=0;int result=-1;
  char *info=malloc(BP_MI_LIMIT+1);
  if(!info)goto finish;
  if(bp_fd_mount(fd,&id,&p)||bp_read("/proc/self/mountinfo",info,BP_MI_LIMIT,&n,&p))goto finish;
  (void)bp_mountinfo(info,n,id,id,canonical,&p);
  /* Parser sets ok only after the whole bounded table. Submount overflow does
   * not negate membership; the existing diagnostic probe keeps its own rule. */
  if(strcmp(p.source_state,"ok")||strcmp(p.target_state,"ok")||
     bp_fd_mount(fd,&again,&p)||again!=id||p.close_error)goto finish;
  result=0;
finish:if(p.close_error)*close_error=1;free(info);return result;
}
static int child_same_object(int fd,int anchor,const struct stat *trusted) {
  struct stat current,original;
  return fstat(fd,&current)||fstat(anchor,&original)||!S_ISDIR(current.st_mode)||
    !same_stat(trusted,&original)||!same_stat(trusted,&current)?-1:0;
}
static const char *child_reopen(const struct namespace_args *a,struct child_handles *h) {
  struct statfs fs;
  h->global=open("/",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
  if(h->global<0)return "child_root";
  if(safe_runtime_path(h->global,a->runtime))return "child_runtime_path";
  h->runtime=open_beneath(h->global,a->runtime,O_RDONLY|O_DIRECTORY);
  if(h->runtime<0)return "child_runtime_open";
  if(child_same_object(h->runtime,a->runtimefd,&a->runtime_identity))return "child_runtime_identity";
  if(verify_runtime(h->runtime,&h->runtime_leases))return "child_runtime_contract";
  if(child_mount_member(h->runtime,a->runtime,&h->close_error))return "child_runtime_mount";
  h->source=open_beneath(h->global,SOURCE_ROOT,O_RDONLY|O_DIRECTORY);
  if(h->source<0)return "child_source_open";
  if(child_same_object(h->source,a->srcfd,&a->source_identity))return "child_source_identity";
  if(fstatfs(h->source,&fs)||!supported_backing((unsigned long)fs.f_type))return "child_source_backing";
  if(lease_source(h->source,&h->source_leases))return "child_source_leases";
  if(child_mount_member(h->source,SOURCE_ROOT,&h->close_error))return "child_source_mount";
  if(read_mount_tuple(&h->outer)||!tuple_equal(a->outer,&h->outer))return "child_outer_tuple";
  /* Parent-held leases are comparison/recheck evidence, never current handles. */
  if(recheck(a->boundary)||recheck(a->runtime_leases)||recheck(a->source)||
     recheck(&h->runtime_leases)||recheck(&h->source_leases)||
     child_same_object(h->runtime,a->runtimefd,&a->runtime_identity)||
     child_same_object(h->source,a->srcfd,&a->source_identity))return "child_anchor_recheck";
  return NULL;
}
static void child_fail(const char*reason)  {
  /* Fixed primary plus optional fixed secondary; never raw stderr or paths. */
  struct child_handles *h=active_child_handles;active_child_handles=NULL;
  int cleanup=child_handles_close(h);
  write(2,reason,strlen(reason));
  if(cleanup)write(2,"+child_cleanup",14);
  _exit(120);
}
static void close_extra_fds(void)  {
  /* Fail closed if the native kernel cannot close the complete descriptor set;
  * RLIMIT_NOFILE is not evidence that no higher descriptor was inherited. */
  if(syscall(SYS_close_range,3U,~0U,0))child_fail("descriptor_close");
}
static void mount_null_device(const char*runtime)  {
  int nullfd=open("/dev/null",O_RDWR|O_NOFOLLOW|O_CLOEXEC);
  struct stat st;
  char dst[PATH_MAX],from[64];
  int target;
  if(nullfd<0||fstat(nullfd,&st)||!S_ISCHR(st.st_mode)||major(st.st_rdev)!=1||minor(st.st_rdev)!=3||st.st_uid||st.st_gid||(st.st_mode&07777)!=0666)child_fail("null_device");
  if(child_path(runtime,"/dev",dst)||mount("tmpfs",dst,"tmpfs",MS_NOSUID|MS_NOEXEC,"size=65536,mode=0555"))child_fail("dev_mount");
  if(child_path(runtime,"/dev/null",dst))child_fail("dev_path");
  target=open(dst,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
  if(target<0||close(target))child_fail("null_target");
  snprintf(from,sizeof from,"/proc/self/fd/%d",nullfd);
  if(mount(from,dst,NULL,MS_BIND,NULL))child_fail("null_bind");
  if(close(nullfd))child_fail("null_close");
}
static void private_view(const char*runtime,int runtimefd,int srcfd,const struct lease_set*source,const struct mount_tuple*outer)  {
  char dst[PATH_MAX],orig[64];
  struct mount_tuple inner;
  struct statfs fs;
  if(recheck(source))child_fail("source_recheck");
  if(mount(NULL,"/",NULL,MS_REC|MS_PRIVATE,NULL))child_fail("mount_private");
  snprintf(orig,sizeof orig,"/proc/self/fd/%d",runtimefd);
  if(mount(orig,runtime,NULL,MS_BIND,NULL)||mount(NULL,runtime,NULL,MS_BIND|MS_REMOUNT|MS_RDONLY|MS_NOSUID|MS_NODEV,NULL))child_fail("runtime_mount");
  if(child_path(runtime,SOURCE_ROOT,dst))child_fail("path");
  snprintf(orig,sizeof orig,"/proc/self/fd/%d",srcfd);
  if(mount(orig,dst,NULL,MS_BIND,NULL)||mount(NULL,dst,NULL,MS_BIND|MS_REMOUNT|MS_RDONLY|MS_NOSUID|MS_NODEV|MS_NOEXEC,NULL))child_fail("source_mount");
  mount_null_device(runtime);
  if(child_path(runtime,"/proc",dst)||mount("proc",dst,"proc",MS_NOSUID|MS_NODEV|MS_NOEXEC,NULL))child_fail("proc_mount");
  if(child_path(runtime,"/tmp",dst)||mount("tmpfs",dst,"tmpfs",MS_NOSUID|MS_NODEV|MS_NOEXEC,"size=16777216,mode=1777"))child_fail("tmp_mount");
  if(child_path(runtime,"/run/user/1000",dst)||mount("tmpfs",dst,"tmpfs",MS_NOSUID|MS_NODEV|MS_NOEXEC,"size=8388608,mode=0700,uid=1000,gid=1000"))child_fail("private_mount");
  if(chdir(runtime)||chroot(".")||chdir("/"))child_fail("chroot");
  if(read_mount_tuple(&inner)||!tuple_equal(outer,&inner)||fstatfs(srcfd,&fs)||!supported_backing((unsigned long)fs.f_type)||verify_view_source(srcfd,source))child_fail("view_identity");
}
static void namespace_child(const struct namespace_args *a)  {
  struct rlimit r;
  pid_t worker;
  int status;
  const char *reason;
  if(dup2(a->in,0)<0||dup2(a->out,1)<0||dup2(a->err,2)<0)child_fail("pipes");
  active_child_handles=calloc(1,sizeof *active_child_handles);
  if(!active_child_handles)child_fail("child_allocation");
  active_child_handles->global=active_child_handles->runtime=active_child_handles->source=-1;
  reason=child_reopen(a,active_child_handles);
  if(reason)child_fail(reason);
  private_view(a->runtime,active_child_handles->runtime,active_child_handles->source,
               &active_child_handles->source_leases,&active_child_handles->outer);
  if(recheck(&active_child_handles->runtime_leases)||recheck(&active_child_handles->source_leases))child_fail("child_view_recheck");
  /* Release child-local resources before any semantic result exists. Parent
   * retains the original leases/locks through capture and final rechecks. */
  {struct child_handles *h=active_child_handles;active_child_handles=NULL;
    if(child_handles_close(h))child_fail("child_cleanup");
  }
  r.rlim_cur=r.rlim_max=768ULL*1024*1024;
  if(setrlimit(RLIMIT_AS,&r))child_fail("as");
  r.rlim_cur=r.rlim_max=0;
  if(setrlimit(RLIMIT_CORE,&r))child_fail("core");
  r.rlim_cur=r.rlim_max=1024*1024;
  if(setrlimit(RLIMIT_FSIZE,&r))child_fail("fsize");
  r.rlim_cur=r.rlim_max=1024;
  if(setrlimit(RLIMIT_NOFILE,&r))child_fail("nofile");
  worker=fork();
  if(worker<0)child_fail("fork");
  if(worker==0)  {
    char*const argv[]=  {
      "/usr/bin/python3.12","-I","-S","-B","-c",(char*)BOOTSTRAP,NULL
    };
    char*const env[]=  {
      "PATH=/usr/bin:/bin","HOME=/run/user/1000","LC_ALL=C",NULL
    };
    if(setgroups(0,NULL)||setresgid(1000,1000,1000)||setresuid(1000,1000,1000)||prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0))child_fail("principal");
    close_extra_fds();
    execve(argv[0],argv,env);
    child_fail("exec");
  }
  /* PID1 holds no original capabilities after worker fork; reaps every child. */
  close_extra_fds();
  if(waitpid(worker,&status,0)!=worker)child_fail("wait");
  kill(-1,SIGKILL);
  while(waitpid(-1,NULL,0)>0||errno==EINTR)  {
  }
  if(WIFEXITED(status))_exit(WEXITSTATUS(status));
  _exit(128+(WIFSIGNALED(status)?WTERMSIG(status):0));
}static int namespace_start(void*opaque)  {
  struct namespace_args*a=opaque;
  if(prctl(PR_SET_PDEATHSIG,SIGKILL))child_fail("parent_death_guard");
  close(a->close_in);
  close(a->close_out);
  close(a->close_err);
  namespace_child(a);
  return 120;
}
static int native_main(int argc,char**argv)  {
  int root=-1,src=-1,global=-1,pin[2]=  {
    -1,-1
  },pout[2]=  {
    -1,-1
  },perr[2]=  {
    -1,-1
  },code=1,launched=0;
  struct lease_set runtime=  {
    0
  },source=  {
    0
  },boundary=  {
    0
  };
  struct mount_tuple outer;
  struct statfs fs;
  struct stat original,after;
  struct captured result=  {
    .code=-1
  };
  unsigned char nonce[32],*request=NULL;
  const char*primary="request",*json=NULL;
  size_t json_len=0;
  int cleanup=0,rechecks=0,have_original=0;
  pid_t pid=-1;
  struct utsname u;
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
  if(BOOTSTRAP_LEN>16384||REQUEST_TAIL_LEN>525316)goto finish;
  if(argc!=5||strcmp(argv[1],"--runtime-root")||strcmp(argv[3],"--source-root")||strcmp(argv[4],SOURCE_ROOT)||argv[2][0]!='/')goto finish;
  if(geteuid()!=0||getuid()!=0||uname(&u)||strcmp(u.machine,"x86_64"))  {
    primary="native_root_required";
    goto finish;
  }
  signal(SIGINT,on_signal);
  signal(SIGTERM,on_signal);
  signal(SIGHUP,on_signal);
  signal(SIGPIPE,SIG_IGN);
  signal(SIGALRM,on_signal);
  alarm(160);
  umask(077);
  root=open("/",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
  if(root<0)goto finish;
  global=dup_cloexec(root);
  if(safe_runtime_path(root,argv[2]))  {
    primary="runtime_safe_parent";
    goto finish;
  }
  {
    struct stat st;
    if(fstat(root,&st)||st.st_uid||st.st_gid||(st.st_mode&0022))  {
      primary="safe_parent";
      goto finish;
    }
  }
  if(hold(&boundary,root,"opt",0,0,0755,2,0,0)||hold(&boundary,root,SOURCE_ROOT+1,1000,1000,0700,2,0,0))  {
    primary="safe_parent";
    goto finish;
  }
  src=open_beneath(root,SOURCE_ROOT,O_RDONLY|O_DIRECTORY);
  {
    int q=open_beneath(root,argv[2],O_RDONLY|O_DIRECTORY);
    close(root);
    root=q;
  }
  primary="runtime_integrity";
  if(root<0||hold(&boundary,global,argv[2]+1,0,0,0555,2,0,0)||verify_runtime(root,&runtime))goto finish;
  primary="source_backing";
  if(src<0||fstatfs(src,&fs)||!supported_backing((unsigned long)fs.f_type)||read_mount_tuple(&outer))goto finish;
  primary="source_identity";
  if(fstat(src,&original))goto finish;
  have_original=1;
  if(lease_source(src,&source))goto finish;
  primary="random";
  if(getrandom(nonce,sizeof nonce,0)!=(ssize_t)sizeof nonce)goto finish;
  request=malloc(32+REQUEST_TAIL_LEN);
  if(!request)goto finish;
  memcpy(request,nonce,32);
  memcpy(request+32,REQUEST_TAIL,REQUEST_TAIL_LEN);
  primary="pipes";
  if(pipe2(pin,O_CLOEXEC)||pipe2(pout,O_CLOEXEC)||pipe2(perr,O_CLOEXEC))goto finish;
  primary="namespace_fd_budget";
  if(adapter_fd_budget(&namespace_budget)){if(namespace_budget.close_error)cleanup=1;goto finish;}
  primary="namespace";
  /* clone returns the exact namespace PID1 to the original parent. */
  {
    struct namespace_args args=  {
      .runtime=argv[2],.runtimefd=root,.srcfd=src,.boundary=&boundary,
      .runtime_leases=&runtime,.source=&source,.outer=&outer,.source_identity=original,
      .in=pin[0],.out=pout[1],.err=perr[1],.close_in=pin[1],.close_out=pout[0],.close_err=perr[0]
    };
    if(fstat(root,&args.runtime_identity))goto finish;
    void*stack=malloc(1024*1024);
    if(!stack)goto finish;
    pid=clone(namespace_start,(char*)stack+1024*1024,CLONE_NEWNS|CLONE_NEWNET|CLONE_NEWPID|SIGCHLD,&args);
    if(pid<0)  {
      free(stack);
      goto finish;
    }
    free(stack);
  }
  launched=1;
  close(pin[0]);
  close(pout[1]);
  close(perr[1]);
  capture_child(pid,pin[1],request,32+REQUEST_TAIL_LEN,pout[0],perr[0],RUN_SECONDS,1,&result);
  primary=result.primary;
  cleanup=result.cleanup;
  if(!strcmp(primary,"none"))  {
    primary="worker_response";
    if(!authenticated(&result,nonce,&json,&json_len))  {
      primary=result.code==0?"semantic_complete":"semantic_refused";
      code=result.code==0?0:1;
    }
  }
  rechecks=!(recheck(&boundary)||recheck(&runtime)||recheck(&source)||fstat(src,&after)||!same_stat(&original,&after));
  if(!rechecks&&code==0)  {
    primary="final_recheck";
    code=1;
  }
  finish:alarm(0);
  if(close_leases(&source))cleanup=1;
  if(close_leases(&runtime))cleanup=1;
  if(close_leases(&boundary))cleanup=1;
  if(!launched)  {
    int i;
    for(i=0;i<2;i++)  {
      if(pin[i]>=0)close(pin[i]);
      if(pout[i]>=0)close(pout[i]);
      if(perr[i]>=0)close(perr[i]);
    }
  }
  if(global>=0&&close(global))cleanup=1;
  if(src>=0&&close(src))cleanup=1;
  if(root>=0&&close(root))cleanup=1;
  free(request);
  if(cleanup)code=1;
  printf("{\"adapter\":1,\"manifest_sha256\":\"%s\",\"operation_sha256\":\"%s\",\"primary\":\"%s\",\"exit\":%d,\"signal\":%d,\"stdout_bytes\":%zu,\"stderr_bytes\":%zu,\"child_error\":\"%s\",\"view_held_fd_identity\":%s,\"original_recheck\":%s,\"cleanup_error\":%s,\"worker_report\":",CONTRACT_MANIFEST_SHA256,CONTRACT_OPERATION_SHA256,primary,result.code,result.signal_no,result.used,result.err_bytes,child_error(&result),json?"true":"false",rechecks?"true":"false",cleanup?"true":"false");
  if(json)fwrite(json,1,json_len,stdout);
  else fputs("null",stdout);
  fputs(",\"original_identity\":",stdout);
  if(have_original)printf("{\"device\":%llu,\"inode\":%llu,\"uid\":%lu,\"gid\":%lu}",(unsigned long long)original.st_dev,(unsigned long long)original.st_ino,(unsigned long)original.st_uid,(unsigned long)original.st_gid);
  else fputs("null",stdout);
  fputs(",\"fd_budget\":",stdout);print_fd_budget(&namespace_budget);
  puts("}");
  return code;
}
#endif
#if !defined(ADAPTER_TEST) && !defined(ADAPTER_NO_MAIN)
int main(int argc,char**argv)  {
#ifdef __linux__
  return native_main(argc,argv);
#else
  (void)argc;
  (void)argv;
  puts("{\"adapter\":1,\"primary\":\"linux_native_required\",\"executed\":false}");
  return 1;
#endif
}
#endif
