#ifndef CLB95_LIMIT_CORE_H
#define CLB95_LIMIT_CORE_H
#include <sys/resource.h>
#include <sys/types.h>
#include <stdint.h>
#include <string.h>
#define FORCE_SOFT 1024U
#define REQUIRED_SOFT 1098U
struct force_record { struct rlimit before, after; int before_known, after_known, attempted; };
static int root_identity(uid_t uid, uid_t euid) { return uid == 0 && euid == 0; }
/* Shared actual syscall core. Tests replace calls only at compile time. */
static const char *force_soft(struct force_record *r) {
    struct rlimit next;
    memset(r, 0, sizeof *r);
    if (getrlimit(RLIMIT_NOFILE, &r->before)) return "limit_read";
    r->before_known = 1;
    if (r->before.rlim_max != RLIM_INFINITY && r->before.rlim_max < REQUIRED_SOFT)
        return "hard_insufficient";
    if (r->before.rlim_cur != RLIM_INFINITY && r->before.rlim_cur < FORCE_SOFT)
        return "entry_soft_low";
    next = r->before;
    next.rlim_cur = FORCE_SOFT; /* Never assign a different rlim_max. */
    r->attempted = 1;
    if (setrlimit(RLIMIT_NOFILE, &next)) return "limit_set";
    if (getrlimit(RLIMIT_NOFILE, &r->after)) return "limit_readback";
    r->after_known = 1;
    if (r->after.rlim_cur != FORCE_SOFT || r->after.rlim_max != r->before.rlim_max)
        return "limit_readback";
    return 0;
}
#endif
