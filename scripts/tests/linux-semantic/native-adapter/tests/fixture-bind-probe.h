/* Read-only, fixed-scope pre-bind observations. Never authorizes a mount or fallback.
 * No strings from procfs or the fixture path are emitted. Linux-only collection;
 * pure parsers and exact call-boundary tests also compile on macOS. */
#ifndef CLB91_BIND_PROBE_H
#define CLB91_BIND_PROBE_H
#define BP_FD_LIMIT 4096
#define BP_MAP_LIMIT 4096
#define BP_MI_LIMIT 262144
#define BP_LINE_LIMIT 8192
#define BP_LINES 1024
#define BP_SUBMOUNTS 32
#define BP_MAP_RANGES 16
struct bind_probe {
  unsigned source_id,target_id;
  const char *source_class,*target_class,*source_state,*target_state,*userns;
  int submounts,overflow,complete,attempted,close_error;
};
static struct bind_probe bp_empty(void) {
  return (struct bind_probe){0,0,"unknown","unknown","not_probed","not_probed","unknown",-1,0,0,0,0};
}
static int bp_uint(const char*s,unsigned*out) {
  unsigned long long n=0;size_t i;
  if(!*s)return -1;
  for(i=0;s[i];i++){if(s[i]<'0'||s[i]>'9')return -1;n=n*10+(unsigned)(s[i]-'0');if(n>4294967295ULL)return -1;}
  *out=(unsigned)n;return 0;
}
static int bp_text(const char*s,size_t n,size_t bound) {
  return !n||n>bound||s[n-1]!='\n'||memchr(s,0,n)?-1:0;
}
static int bp_fdinfo(const char*s,size_t n,unsigned*out) {
  size_t at=0;unsigned found=0,id=0;
  if(bp_text(s,n,BP_FD_LIMIT))return -1;
  while(at<n){const char*end=memchr(s+at,'\n',n-at);size_t len=(size_t)(end-s-at);
    if(len>=7&&!memcmp(s+at,"mnt_id:",7)) {
      char num[16];size_t start=at+7,z=(size_t)(end-s),k;
      while(start<z&&(s[start]==' '||s[start]=='\t'))start++;
      k=z-start;if(!k||k>=sizeof num||found++)return -1;
      memcpy(num,s+start,k);num[k]=0;if(bp_uint(num,&id)||!id)return -1;
    }
    at=(size_t)(end-s)+1;
  }
  if(found!=1)return -1;*out=id;return 0;
}
/* Mountinfo paths use these four documented octal escapes. */
static int bp_path(const char*s,char*out,size_t cap) {
  size_t i=0,k=0;
  if(*s!='/')return -1;
  while(s[i]) {
    unsigned char c=(unsigned char)s[i++];
    if(c=='\\') {
      if(!strncmp(s+i,"040",3))c=' ';else if(!strncmp(s+i,"011",3))c='\t';
      else if(!strncmp(s+i,"012",3))c='\n';else if(!strncmp(s+i,"134",3))c='\\';else return -1;
      i+=3;
    } else if(c<33||c==127)return -1;
    if(k+1>=cap)return -1;out[k++]=(char)c;
  }
  out[k]=0;return 0;
}
static int bp_inside(const char*path,const char*source) {
  size_t n=strlen(source);return !strncmp(path,source,n)&&path[n]=='/';
}
static int bp_mountinfo(const char*s,size_t n,unsigned source,unsigned target,const char*canonical,struct bind_probe*p) {
  unsigned ids[BP_LINES],count=0,found_s=0,found_t=0,sub=0;size_t at=0;
  const char *sc="unknown",*tc="unknown";int overflow=0;
  p->source_class=p->target_class="unknown";p->submounts=-1;p->overflow=0;
  p->source_state=p->target_state="malformed";
  if(n>BP_MI_LIMIT){p->source_state=p->target_state="bound";return -1;}
  if(bp_text(s,n,BP_MI_LIMIT)||!source||!target||!canonical||canonical[0]!='/'||!canonical[1]||canonical[strlen(canonical)-1]=='/')return -1;
  while(at<n) {
    const char*end=memchr(s+at,'\n',n-at);size_t len=(size_t)(end-s-at),i,nt=0,sep=0;
    char line[BP_LINE_LIMIT+1],*tok[32],*save=NULL,*x,decoded[BP_LINE_LIMIT+1];unsigned id,parent,major,minor;int shared=0,slave=0,unbound=0,from=0;
    if(len>BP_LINE_LIMIT||count>=BP_LINES){p->source_state=p->target_state="bound";return -1;}
    memcpy(line,s+at,len);line[len]=0;at=(size_t)(end-s)+1;
    /* Kernel records have single separators, no raw control bytes. */
    if(!len||line[0]==' '||line[len-1]==' '||strstr(line,"  "))return -1;
    for(i=0;i<len;i++)if((unsigned char)line[i]<32||line[i]==127)return -1;
    for(x=strtok_r(line," ",&save);x;x=strtok_r(NULL," ",&save)){if(nt>=32)return -1;tok[nt++]=x;}
    if(nt<10||bp_uint(tok[0],&id)||!id||bp_uint(tok[1],&parent))return -1;
    for(i=0;i<count;i++)if(ids[i]==id){p->source_state=p->target_state="duplicate";return -1;}
    ids[count++]=id;x=strchr(tok[2],':');if(!x)return -1;*x++=0;if(bp_uint(tok[2],&major)||bp_uint(x,&minor))return -1;
    if(bp_path(tok[3],decoded,sizeof decoded)||bp_path(tok[4],decoded,sizeof decoded))return -1;
    if(bp_inside(decoded,canonical)){if(sub<BP_SUBMOUNTS)sub++;else overflow=1;}
    for(i=6;i<nt;i++)if(!strcmp(tok[i],"-")){sep=i;break;}
    if(!sep||nt!=sep+4)return -1;
    for(i=6;i<sep;i++) {
      unsigned tag=0;
      if(!strncmp(tok[i],"shared:",7)){if(shared++||bp_uint(tok[i]+7,&tag)||!tag)return -1;}
      else if(!strncmp(tok[i],"master:",7)){if(slave++||bp_uint(tok[i]+7,&tag)||!tag)return -1;}
      else if(!strncmp(tok[i],"propagate_from:",15)){if(from++||bp_uint(tok[i]+15,&tag)||!tag)return -1;}
      else if(!strcmp(tok[i],"unbindable")){if(unbound++)return -1;}
      else return -1; /* Conservative unknown for new optional semantics. */
    }
    if((from&&!slave)||(unbound&&(shared||slave||from)))return -1;
    x=unbound?"unbindable":shared?(slave?"shared_slave":"shared"):(slave?"slave":"private");
    if(id==source){found_s++;sc=x;}if(id==target){found_t++;tc=x;}
  }
  p->source_state=found_s==1?"ok":"missing";p->target_state=found_t==1?"ok":"missing";
  p->source_class=sc;p->target_class=tc;
  /* Missing source mount cannot prove a submount count for the held object. */
  if(found_s==1){p->submounts=(int)sub;p->overflow=overflow;}
  return found_s==1&&found_t==1&&!overflow?0:-1;
}
struct bp_range {unsigned inner,outer,length;};
static int bp_map(const char*s,size_t n,int*single,int*full,int*remapped) {
  struct bp_range rows[BP_MAP_RANGES];size_t at=0;unsigned count=0;int off=0;
  if(bp_text(s,n,BP_MAP_LIMIT))return -1;
  while(at<n){const char*end=memchr(s+at,'\n',n-at);size_t len=(size_t)(end-s-at),i;char line[128],*tok[4],*save=NULL,*x;unsigned nt=0;struct bp_range row;
    if(count>=BP_MAP_RANGES||!len||len>=sizeof line)return -1;
    memcpy(line,s+at,len);line[len]=0;at=(size_t)(end-s)+1;
    for(i=0;i<len;i++)if(((unsigned char)line[i]<32&&line[i]!='\t')||line[i]==127)return -1;
    for(x=strtok_r(line," \t",&save);x;x=strtok_r(NULL," \t",&save)){if(nt>=4)return -1;tok[nt++]=x;}
    if(nt!=3||bp_uint(tok[0],&row.inner)||bp_uint(tok[1],&row.outer)||bp_uint(tok[2],&row.length)||!row.length)return -1;
    if((unsigned long long)row.inner+row.length>4294967295ULL||(unsigned long long)row.outer+row.length>4294967295ULL)return -1;
    for(i=0;i<count;i++)if(((unsigned long long)row.inner< (unsigned long long)rows[i].inner+rows[i].length&&(unsigned long long)rows[i].inner<(unsigned long long)row.inner+row.length)||((unsigned long long)row.outer<(unsigned long long)rows[i].outer+rows[i].length&&(unsigned long long)rows[i].outer<(unsigned long long)row.outer+row.length))return -1;
    rows[count++]=row;if(row.inner!=row.outer)off=1;
  }
  if(!count)return -1;*single=count==1;*full=count==1&&rows[0].inner==0&&rows[0].outer==0&&rows[0].length==4294967295U;*remapped=off;return 0;
}
static const char*bp_userns(const char*u,size_t un,const char*g,size_t gn) {
  int us,uf,ur,gs,gf,gr;
  if(bp_map(u,un,&us,&uf,&ur)||bp_map(g,gn,&gs,&gf,&gr))return "unexpected";
  if(uf&&gf)return "initial_identity"; /* Map shape only, not namespace ancestry. */
  if(ur||gr)return "remapped";
  return us&&gs?"identity_single_range":"unexpected";
}
static int bp_close(int fd,struct bind_probe*p) {
  if(fd>=0&&close(fd)){p->close_error=1;return -1;}return 0;
}
static int bp_read(const char*path,char*b,size_t cap,size_t*n,struct bind_probe*p) {
  int fd=open(path,O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK),bad=0;size_t used=0;struct stat st;
  if(fd<0)return -1;
  if(fstat(fd,&st)||!S_ISREG(st.st_mode))bad=1;
  while(!bad&&used<=cap){ssize_t z;
    if(interrupted){bad=1;break;}z=read(fd,b+used,cap+1-used);
    if(z<0&&errno==EINTR)continue;if(z<0){bad=1;break;}if(!z)break;used+=(size_t)z;
    if(used>cap){bad=1;break;}
  }
  if(bp_close(fd,p))bad=1;if(bad)return -1;b[used]=0;*n=used;return 0;
}
static int bp_fd_mount(int fd,unsigned*id,struct bind_probe*p) {
  char path[64],b[BP_FD_LIMIT+1];size_t n;
  if(fd<0||snprintf(path,sizeof path,"/proc/self/fdinfo/%d",fd)>=(int)sizeof path||bp_read(path,b,BP_FD_LIMIT,&n,p))return -1;
  return bp_fdinfo(b,n,id);
}
static void collect_bind_probe(int source,const struct stat*snapshot,const char*workpath,const char*fdpath,struct bind_probe*p) {
  int target=-1,ok=1;unsigned sid=0,tid=0;char canonical[PATH_MAX],expected[PATH_MAX],u[BP_MAP_LIMIT+1],g[BP_MAP_LIMIT+1];
  char *info=NULL,*again=NULL;size_t n=0,n2=0,un=0,gn=0;ssize_t z;struct stat before,after;
  *p=bp_empty();p->attempted=1;p->source_state=p->target_state="unavailable";
  info=malloc(BP_MI_LIMIT+1);again=malloc(BP_MI_LIMIT+1);if(!info||!again)goto finish;
  target=open("/opt",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
  if(target<0||bp_fd_mount(source,&p->source_id,p)||bp_fd_mount(target,&p->target_id,p))goto finish;
  if(fstat(source,&before)||before.st_dev!=snapshot->st_dev||before.st_ino!=snapshot->st_ino)goto finish;
  z=readlink(fdpath,canonical,sizeof canonical-1);if(z<=0||z>=(ssize_t)sizeof canonical-1)goto finish;canonical[z]=0;
  if(snprintf(expected,sizeof expected,"%s/source",workpath)>=(int)sizeof expected||strcmp(canonical,expected))goto finish;
  if(bp_read("/proc/self/mountinfo",info,BP_MI_LIMIT,&n,p))goto finish;
  if(bp_mountinfo(info,n,p->source_id,p->target_id,canonical,p))ok=0;
  if(bp_read("/proc/self/uid_map",u,BP_MAP_LIMIT,&un,p)||bp_read("/proc/self/gid_map",g,BP_MAP_LIMIT,&gn,p))ok=0;
  else {p->userns=bp_userns(u,un,g,gn);if(!strcmp(p->userns,"unexpected"))ok=0;}
  /* A bounded observation, not an atomic namespace snapshot. Refuse observed drift. */
  if(bp_fd_mount(source,&sid,p)||bp_fd_mount(target,&tid,p)||sid!=p->source_id||tid!=p->target_id||bp_read("/proc/self/mountinfo",again,BP_MI_LIMIT,&n2,p)||n!=n2||memcmp(info,again,n)||fstat(source,&after)||before.st_dev!=after.st_dev||before.st_ino!=after.st_ino){
    p->source_class=p->target_class="unknown";p->source_state=p->target_state="drift";p->submounts=-1;ok=0;
  }
  z=readlink(fdpath,expected,sizeof expected-1);
  if(z<=0||z>=(ssize_t)sizeof expected-1||((expected[z]=0),strcmp(canonical,expected))){
    p->source_class=p->target_class="unknown";p->source_state=p->target_state="drift";p->submounts=-1;ok=0;
  }
  p->complete=ok;
finish:
  if(bp_close(target,p))p->complete=0;
  if(p->close_error)p->complete=0;
  free(info);free(again);
}
static void print_bind_probe(const struct bind_probe*p) {
  printf(",\"probe_status\":\"%s\",\"source_mount_class\":\"%s\",\"target_mount_class\":\"%s\",\"source_mount_status\":\"%s\",\"target_mount_status\":\"%s\",\"source_mount_id\":",p->attempted?(p->complete?"complete":"incomplete"):"not_probed",p->source_class,p->target_class,p->source_state,p->target_state);
  if(p->source_id)printf("%u",p->source_id);else fputs("null",stdout);
  fputs(",\"target_mount_id\":",stdout);if(p->target_id)printf("%u",p->target_id);else fputs("null",stdout);
  fputs(",\"source_submount_count\":",stdout);if(p->submounts>=0)printf("%d",p->submounts);else fputs("null",stdout);
  printf(",\"source_submount_overflow\":%s,\"userns_class\":\"%s\",\"mountns_owner_current_userns\":null,\"probe_cleanup_error\":%s",p->overflow?"true":"false",p->userns,p->close_error?"true":"false");
}
#endif
