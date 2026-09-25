/* Verification only. No command/executable argument, no runtime test bypass. */
#define _GNU_SOURCE 1
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/resource.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <limits.h>
#include "limit-core.h"
#include "sha256.h"
#include "binding.h" /* Generated in separate build directory, exact run inputs. */
#ifdef __linux__
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/utsname.h>
#endif
static int refuse(const char *reason) {
    /* Callers supply only literals. No paths, strerror or untrusted text. */
    fprintf(stderr, "{\"clb95_launcher\":1,\"status\":\"%s\"}\n", reason);
    return 120;
}
static int work_path(const char *p) {
    const char *prefix="/tmp/clb91-native-"; size_t i,n=strlen(prefix),z=strlen(p);
    if(z<n+6 || z>n+64 || strncmp(p,prefix,n)) return 0;
    for(i=n;i<z;i++) if(!((p[i]>='a'&&p[i]<='z')||(p[i]>='A'&&p[i]<='Z')||
        (p[i]>='0'&&p[i]<='9')||p[i]=='_'||p[i]=='-')) return 0;
    return 1;
}
static int invocation(int argc, char **argv) {
    return argc==3 && !strcmp(argv[1],"--work") && work_path(argv[2]);
}
#ifdef __linux__
/* Every component is opened no-follow. Fixed build-time paths cannot contain .. */
static int open_fixed(const char *path, int directory) {
    char copy[PATH_MAX],*part,*next; int fd,n;
    if(path[0]!='/'||strlen(path)>=sizeof copy||path[1]==0) return -1;
    strcpy(copy,path+1); fd=open("/",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    if(fd<0) return -1;
    part=copy;
    for(;;) {
        next=strchr(part,'/'); if(next)*next=0;
        if(!*part||!strcmp(part,".")||!strcmp(part,"..")){close(fd);return -1;}
        n=openat(fd,part,O_RDONLY|O_NOFOLLOW|O_CLOEXEC|O_NONBLOCK|
            ((next||directory)?O_DIRECTORY:0));
        if(close(fd)){if(n>=0)close(n);return -1;}
        if(n<0)return -1;
        fd=n; if(!next)return fd; part=next+1;
    }
}
static int same(const struct stat *a,const struct stat *b) {
    return a->st_dev==b->st_dev&&a->st_ino==b->st_ino&&a->st_mode==b->st_mode&&
      a->st_uid==b->st_uid&&a->st_gid==b->st_gid&&a->st_nlink==b->st_nlink&&
      a->st_size==b->st_size&&a->st_mtim.tv_sec==b->st_mtim.tv_sec&&
      a->st_mtim.tv_nsec==b->st_mtim.tv_nsec&&a->st_ctim.tv_sec==b->st_ctim.tv_sec&&
      a->st_ctim.tv_nsec==b->st_ctim.tv_nsec;
}
static int exact_binary(const char *path,const char *expect,int seal) {
    int fd=open_fixed(path,0),mem=-1; struct stat a,b; unsigned char *raw=0;
    char hash[65];size_t pos=0;ssize_t n;
    if(fd<0)return -1;
    if(fstat(fd,&a)||!S_ISREG(a.st_mode)||a.st_nlink!=1||a.st_size<=0||a.st_size>16777216||
        (a.st_mode&07777)!=0755||a.st_uid!=BUILD_UID||a.st_gid!=BUILD_GID)goto end;
    raw=malloc((size_t)a.st_size);if(!raw)goto end;
    while(pos<(size_t)a.st_size){n=pread(fd,raw+pos,(size_t)a.st_size-pos,(off_t)pos);
        if(n<0&&errno==EINTR)continue;if(n<=0)goto end;pos+=(size_t)n;}
    digest(raw,pos,hash);if(strcmp(hash,expect)||fstat(fd,&b)||!same(&a,&b))goto end;
    /* Driver has already checked static ELF. Repeat fixed architecture signature. */
    if(pos<64||memcmp(raw,"\177ELF\2\1\1",7)||raw[18]!=62||raw[19]!=0)goto end;
    if(!seal){mem=dup(fd);goto end;}
    mem=memfd_create("clb95-exact-helper",MFD_CLOEXEC|MFD_ALLOW_SEALING);
    if(mem<0)goto end;
    pos=0;while(pos<(size_t)a.st_size){n=write(mem,raw+pos,(size_t)a.st_size-pos);
        if(n<0&&errno==EINTR)continue;if(n<=0)goto bad;pos+=(size_t)n;}
    if(fcntl(mem,F_ADD_SEALS,F_SEAL_WRITE|F_SEAL_GROW|F_SEAL_SHRINK|F_SEAL_SEAL)<0)goto bad;
    goto end;
bad: close(mem);mem=-1;
end: free(raw);if(close(fd)){if(mem>=0)close(mem);mem=-1;}return mem;
}
static void number(rlim_t n) {
    if(n==RLIM_INFINITY)fputs("null",stderr);else fprintf(stderr,"%llu",(unsigned long long)n);
}
#endif
int main(int argc,char **argv) {
    if(!invocation(argc,argv))return refuse("invocation");
    if(!root_identity(getuid(),geteuid()))return refuse("root_required");
#if !defined(__linux__) || !defined(__x86_64__)
    return refuse("linux_x64_required");
#else
    int helper=-1,adapter=-1,work=-1,q;struct stat st;struct utsname u;
    struct force_record record;const char *failure;
    if(uname(&u)||strcmp(u.machine,"x86_64"))return refuse("linux_x64_required");
    work=open_fixed(argv[2],1);
    if(work<0||fstat(work,&st)||!S_ISDIR(st.st_mode)||(st.st_mode&07777)!=0700||
        st.st_uid!=BUILD_UID||st.st_gid!=BUILD_GID){if(work>=0)close(work);return refuse("work_identity");}
    if(close(work))return refuse("close");
    helper=exact_binary(HELPER_PATH,HELPER_SHA256,1);
    adapter=exact_binary(ADAPTER_PATH,ADAPTER_SHA256,0);
    if(helper<0||adapter<0){if(helper>=0)close(helper);if(adapter>=0)close(adapter);return refuse("binary_identity");}
    if(close(adapter)){close(helper);return refuse("close");}
    /* Fixed helper seals/rechecks adapter again before it executes it. */
    if(helper!=3){q=dup3(helper,3,O_CLOEXEC);if(q<0||close(helper)){if(q>=0)close(q);return refuse("close");}}
    if(syscall(SYS_close_range,4U,~0U,0)){close(3);return refuse("close");}
    failure=force_soft(&record);if(failure){close(3);return refuse(failure);}
    /* One bounded line on separate stderr; future coordinator must parse it.
       Existing driver does not yet retain this line as numeric evidence. */
    fprintf(stderr,"{\"clb95_launcher\":1,\"status\":\"forced\",\"launcher_source_sha256\":\"%s\",\"launcher_uid\":0,\"launcher_euid\":0,\"nofile_entry_soft\":",LAUNCHER_SOURCE_SHA256);
    number(record.before.rlim_cur);fputs(",\"nofile_entry_hard\":",stderr);number(record.before.rlim_max);
    fputs(",\"nofile_forced_soft\":1024,\"nofile_post_force_soft\":",stderr);number(record.after.rlim_cur);
    fputs(",\"nofile_post_force_hard\":",stderr);number(record.after.rlim_max);fputs("}\n",stderr);
    if(fflush(stderr)||ferror(stderr)){close(3);return refuse("evidence_write");}
    {char *const args[]={"native-fixture-runner","--work",argv[2],"--adapter",ADAPTER_PATH,NULL};
     char *const env[]={"PATH=/usr/bin:/bin","LC_ALL=C","HOME=/nonexistent",NULL};
     fexecve(3,args,env);}
    close(3);return refuse("exec_failed");
#endif
}
