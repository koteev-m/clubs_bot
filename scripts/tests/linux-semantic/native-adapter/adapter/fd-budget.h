/* Read-only admission for namespace duplicate leases. No limit mutation.
 * The caller is single-threaded; signal handlers set a flag, never open FDs.
 * Proc entries and all arithmetic are bounded; no target/path is exported. */
#ifndef CLB91_FD_BUDGET_H
#define CLB91_FD_BUDGET_H
#define FD_BUDGET_ENTRIES 8192U
struct fd_budget {
  uint64_t soft,hard,open_count,additional,total;
  int limits_known,soft_infinite,hard_infinite,count_known,required_known;
  int sufficient,close_error;
};
static struct fd_budget namespace_budget;
static int fd_budget_runtime(uint64_t *held) {
  size_t i;uint64_t n=0;
  if(CONTRACT_ENTRY_COUNT>MAX_LEASES)return -1;
  for(i=0;i<CONTRACT_ENTRY_COUNT;i++) {
    int kind=CONTRACT_ENTRIES[i].kind;
    if(kind==1||kind==2)n+=2; /* hold: object plus parent */
    else if(kind==3)n++;     /* hold_symlink: parent only */
    else return -1;
  }
  *held=n;return 0;
}
static int fd_budget_number(const char *s,int *out) {
  size_t n=0;unsigned v=0;
  if(!s[0]||(s[0]=='0'&&s[1]))return -1;
  while(s[n]) {
    unsigned d=(unsigned char)s[n]-'0';
    if(n++>=10||d>9||v>((unsigned)INT_MAX-d)/10)return -1;
    v=v*10+d;
  }
  *out=(int)v;return 0;
}
static int fd_budget_compare(const void *a,const void *b) {
  int x=*(const int*)a,y=*(const int*)b;return (x>y)-(x<y);
}
static int fd_budget_count(uint64_t *count,unsigned *stdio_missing,int *close_error) {
  int q=-1,result=-1,own=0;DIR *dir=NULL;struct dirent *e;
  int *ids=NULL;size_t n=0,entries=0,i;unsigned stdio_seen=0;
  q=open("/proc/self/fd",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
  if(q<0)goto finish;
  dir=fdopendir(q);if(!dir)goto finish;
  ids=malloc(FD_BUDGET_ENTRIES*sizeof *ids);if(!ids)goto finish;
  for(;;) {
    int id;errno=0;e=readdir(dir);
    if(!e){if(errno)goto finish;break;}
    if(++entries>FD_BUDGET_ENTRIES||interrupted)goto finish;
    if(!strcmp(e->d_name,".")||!strcmp(e->d_name,".."))continue;
    if(fd_budget_number(e->d_name,&id)||n>=FD_BUDGET_ENTRIES)goto finish;
    ids[n++]=id;
  }
  qsort(ids,n,sizeof *ids,fd_budget_compare);
  for(i=0;i<n;i++) {
    if(i&&ids[i]==ids[i-1])goto finish;
    if(ids[i]==q){own++;continue;}
    if(ids[i]<3)stdio_seen|=1U<<ids[i];
  }
  if(own!=1)goto finish;
  *count=n-1; /* exclude our live enumeration FD exactly once */
  *stdio_missing=3U-((stdio_seen&1)!=0)-((stdio_seen&2)!=0)-((stdio_seen&4)!=0);
  result=0;
finish:
  free(ids);
  if(dir){if(closedir(dir)){*close_error=1;result=-1;}}
  else if(q>=0&&close(q)){*close_error=1;result=-1;}
  return result;
}
/* Count ALL existing descriptors, even those above a lowered soft limit. This
 * intentionally overcounts occupied slots below soft. Reserve absent stdio
 * slots as well because dup_cloexec allocates at >=3; never assume dense IDs. */
static int fd_budget_admit(uint64_t additional,struct fd_budget *b) {
  struct rlimit r;unsigned missing=0;
  memset(b,0,sizeof *b);
  if(getrlimit(RLIMIT_NOFILE,&r))return -1;
  b->limits_known=1;b->soft_infinite=r.rlim_cur==RLIM_INFINITY;
  b->hard_infinite=r.rlim_max==RLIM_INFINITY;
  b->soft=b->soft_infinite?0:(uint64_t)r.rlim_cur;
  b->hard=b->hard_infinite?0:(uint64_t)r.rlim_max;
  if((!b->soft_infinite&&(rlim_t)b->soft!=r.rlim_cur)||
     (!b->hard_infinite&&(rlim_t)b->hard!=r.rlim_max))return -1;
  if(fd_budget_count(&b->open_count,&missing,&b->close_error))return -1;
  b->count_known=1;
  if(additional>UINT64_MAX-missing)return -1;
  b->additional=additional+missing;
  if(b->additional>UINT64_MAX-b->open_count)return -1;
  b->total=b->open_count+b->additional;b->required_known=1;
  b->sufficient=b->soft_infinite||b->total<=b->soft;
  return b->sufficient?0:-1;
}
static void fd_budget_value(int known,uint64_t v) {
  if(known)printf("%llu",(unsigned long long)v);else fputs("null",stdout);
}
static void print_fd_budget(const struct fd_budget *b) {
  fputs("{\"nofile_soft\":",stdout);fd_budget_value(b->limits_known&&!b->soft_infinite,b->soft);
  fputs(",\"nofile_hard\":",stdout);fd_budget_value(b->limits_known&&!b->hard_infinite,b->hard);
  fputs(",\"open_fd_count\":",stdout);fd_budget_value(b->count_known,b->open_count);
  fputs(",\"required_additional_peak\":",stdout);fd_budget_value(b->required_known,b->additional);
  fputs(",\"required_total_peak\":",stdout);fd_budget_value(b->required_known,b->total);
  fputs(",\"fd_budget_sufficient\":",stdout);
  fputs(b->required_known?(b->sufficient?"true":"false"):"null",stdout);fputs("}",stdout);
}
#endif
