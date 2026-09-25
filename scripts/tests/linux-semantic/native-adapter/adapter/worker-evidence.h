/* CLB-97 verification derivative only. No mutable limits, allocation or new FD. */
#ifndef CLB97_WORKER_EVIDENCE_H
#define CLB97_WORKER_EVIDENCE_H
#define CLB97_WORKER_CAPTURE 2
static const unsigned char CLB97_WORKER_RECORD[] =
  "clb97:v1 role=worker stage=pre_exec soft=1024 hard=1024\n";
#define CLB97_WORKER_RECORD_BYTES (sizeof CLB97_WORKER_RECORD - 1)
_Static_assert(CLB97_WORKER_RECORD_BYTES == 56, "fixed worker protocol bound");
struct worker_record {
  size_t matched;
  int complete, rejected;
};
/* Called ONLY by the future Python worker, after drop and close_extra_fds.
 * The literal numbers are emitted only after comparing both actual values.
 * No stdio, formatting, heap, locale, locks, descriptors or resource mutation.
 * A partial/zero write is terminal: never finish a partly delivered record.
 */
static const char *worker_measure_emit(void) {
  struct rlimit observed;
  unsigned attempt;
  if(getrlimit(RLIMIT_NOFILE,&observed))return "worker_nofile_read";
  if(observed.rlim_cur!=1024||observed.rlim_max!=1024)return "worker_nofile_mismatch";
  for(attempt=0;attempt<8;attempt++) {
    ssize_t n=write(2,CLB97_WORKER_RECORD,CLB97_WORKER_RECORD_BYTES);
    if(n==(ssize_t)CLB97_WORKER_RECORD_BYTES)return NULL;
    if(n<0&&errno==EINTR)continue;
    return "worker_record_write";
  }
  return "worker_record_write";
}
#endif
