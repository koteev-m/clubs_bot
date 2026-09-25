/* Helper-only disposable process envelope. Never linked into adapter authority.
 * The exact contract gives R=513, S=24, 2R+2S+24=1098. This reviewed compile-time
 * ceiling is deliberately not enlarged if the contract/descriptor load grows.
 * Existing read-only admission remains authoritative at each later boundary. */
#ifndef CLB91_FIXTURE_RESOURCE_ENVELOPE_H
#define CLB91_FIXTURE_RESOURCE_ENVELOPE_H
#define FIXTURE_NOFILE_CEILING 1098U
struct fixture_envelope {
  struct rlimit original;
  uint64_t requested,effective,restored,required;
  int observed,required_known,effective_known,effective_infinite;
  int attempted,succeeded,restore_needed,restore_attempted,restore_succeeded;
  int restore_error,close_error;
};
static struct fixture_envelope fixture_envelope;
/* Called only after principal/input/runtime/ownership/anchor validation. */
static const char *fixture_envelope_admit(void) {
  struct fixture_envelope *e=&fixture_envelope;
  struct fd_budget b;struct rlimit current,next,after;
  uint64_t runtime,source=2*(sizeof srcdirs/sizeof *srcdirs+sizeof srcfiles/sizeof *srcfiles);
  memset(e,0,sizeof *e);
  if(fd_budget_runtime(&runtime)||source/2>MAX_LEASES||runtime>(UINT64_MAX-2*source-24)/2)
    return "namespace_fd_budget";
  /* Actual current helper FDs plus one future source FD and the fingerprint
   * 6 pipes + 3 roots + runtime leases + 2 walker peak. Reserve missing stdio
   * exactly as the original read-only admission does. */
  (void)fd_budget_admit(runtime+12,&b);
  e->close_error=b.close_error;
  if(!b.limits_known||!b.count_known||!b.required_known||b.close_error)
    return "namespace_fd_budget";
  e->original.rlim_cur=b.soft_infinite?RLIM_INFINITY:(rlim_t)b.soft;
  e->original.rlim_max=b.hard_infinite?RLIM_INFINITY:(rlim_t)b.hard;
  e->observed=1;e->effective_known=1;e->effective_infinite=b.soft_infinite;e->effective=b.soft;
  /* invoke_adapter replaces stdio, closes >=4, fexecve closes CLOEXEC fd3:
   * adapter starts with exactly 3 stdio; its whole-path bound is independent
   * of helper extras. Six parent boundary FDs and both lease sets remain. */
  e->required=2*runtime+2*source+24;
  if(b.total>e->required)e->required=b.total;
  e->required_known=1;e->requested=e->required;
  if(b.soft_infinite||b.soft>=e->required)return NULL;
  if(e->required>FIXTURE_NOFILE_CEILING||(!b.hard_infinite&&b.hard<e->required))
    return "namespace_fd_budget";
  if((uint64_t)(rlim_t)e->required!=e->required)return "namespace_fd_budget";
  /* Detect drift before mutation; never restore/raise a changed hard limit. */
  if(getrlimit(RLIMIT_NOFILE,&current)||current.rlim_cur!=e->original.rlim_cur||
     current.rlim_max!=e->original.rlim_max)return "resource_envelope_readback";
  next=current;next.rlim_cur=(rlim_t)e->required;e->attempted=1;
  if(setrlimit(RLIMIT_NOFILE,&next))return "resource_envelope_set";
  e->restore_needed=1;e->effective_known=0;
  if(getrlimit(RLIMIT_NOFILE,&after)||after.rlim_cur!=next.rlim_cur||after.rlim_max!=next.rlim_max)
    return "resource_envelope_readback";
  e->effective_known=1;e->effective_infinite=0;e->effective=e->required;e->succeeded=1;
  return NULL;
}
/* After all fixture rechecks/removal and FD closure. Preserve any primary.
 * One attempt; a failure is secondary cleanup_error, never a guessed restore. */
static int fixture_envelope_restore(void) {
  struct fixture_envelope *e=&fixture_envelope;struct rlimit current,next,after;
  if(!e->restore_needed)return 0;
  e->restore_attempted=1;e->restore_needed=0;
  if(getrlimit(RLIMIT_NOFILE,&current)||current.rlim_max!=e->original.rlim_max)goto fail;
  next=current;next.rlim_cur=e->original.rlim_cur;
  if(setrlimit(RLIMIT_NOFILE,&next)||getrlimit(RLIMIT_NOFILE,&after)||
     after.rlim_cur!=next.rlim_cur||after.rlim_max!=next.rlim_max)goto fail;
  e->restore_succeeded=1;e->restored=(uint64_t)after.rlim_cur;return 0;
fail:e->restore_error=1;return -1;
}
static void print_fixture_envelope(void) {
  const struct fixture_envelope *e=&fixture_envelope;
  fputs("{\"nofile_original_soft\":",stdout);fd_budget_value(e->observed&&e->original.rlim_cur!=RLIM_INFINITY,(uint64_t)e->original.rlim_cur);
  fputs(",\"nofile_hard\":",stdout);fd_budget_value(e->observed&&e->original.rlim_max!=RLIM_INFINITY,(uint64_t)e->original.rlim_max);
  fputs(",\"nofile_requested_soft\":",stdout);fd_budget_value(e->required_known,e->requested);
  fputs(",\"nofile_effective_soft\":",stdout);fd_budget_value(e->effective_known&&!e->effective_infinite,e->effective);
  fputs(",\"nofile_raise_attempted\":",stdout);fputs(e->attempted?"true":"false",stdout);
  fputs(",\"nofile_raise_succeeded\":",stdout);fputs(e->succeeded?"true":"false",stdout);
  fputs(",\"required_total_peak\":",stdout);fd_budget_value(e->required_known,e->required);
  fputs(",\"nofile_restore_attempted\":",stdout);fputs(e->restore_attempted?"true":"false",stdout);
  fputs(",\"nofile_restore_succeeded\":",stdout);fputs(e->restore_succeeded?"true":"false",stdout);
  fputs(",\"nofile_restore_error\":",stdout);fputs(e->restore_error?"true":"false",stdout);
  fputs(",\"nofile_restored_soft\":",stdout);fd_budget_value(e->restore_succeeded,e->restored);fputs("}",stdout);
}
#endif
