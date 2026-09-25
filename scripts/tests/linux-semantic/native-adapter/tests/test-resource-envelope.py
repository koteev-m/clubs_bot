#!/usr/bin/env python3
"""Execute exact helper envelope/caller code against narrow syscall boundaries.

No real resource-limit mutation, root, namespace, mount or worker execution.
The C model implements only getrlimit/setrlimit, numeric proc-FD enumeration,
and exact pre-exec descriptor operations. Actual contract kinds/source arrays,
read-only budget and helper envelope functions are compiled unchanged.
"""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import tempfile
import unittest

P = pathlib.Path
HERE = P(__file__).resolve().parent

PRE = r'''
#define _GNU_SOURCE 1
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <errno.h>
#include <limits.h>
#include <dirent.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#define MAX_LEASES 384
struct entry {int kind;};
static struct entry model_entries[MAX_LEASES];
static size_t model_count;
#define CONTRACT_ENTRIES model_entries
#define CONTRACT_ENTRY_COUNT model_count
static volatile int interrupted;
static const char *scenario;
static struct rlimit limits;
static rlim_t initial_hard;
static unsigned reads,sets,proc_opens,proc_closes,proc_live,walk,phase;
static unsigned lease_calls,namespace_calls,stat_calls,hard_changes,owned_closes;
static unsigned execution_reached,remaining_after_exec;
static uint64_t inherited_soft;
static rlim_t set_soft[4],set_hard[4];
static int population,missing_stdio,malformed;
static struct dirent item;
static DIR *const sentinel=(DIR*)(uintptr_t)1;
static const int own_fd=20000;
static int is(const char*s){return !strcmp(scenario,s);}
static int model_getrlimit(int resource,struct rlimit*r){
 if(resource!=RLIMIT_NOFILE)abort();reads++;
 if(is("first_get_failure")&&reads==1){errno=EIO;return -1;}
 if(is("post_set_get_failure")&&sets==1&&phase==0&&reads==3){errno=EIO;return -1;}
 *r=limits;
 if(is("pre_set_drift")&&reads==2)r->rlim_cur++;
 if(is("post_set_mismatch")&&sets==1&&phase==0&&reads==3)r->rlim_cur--;
 if(is("post_set_hard_mismatch")&&sets==1&&phase==0&&reads==3)r->rlim_max--;
 if(is("restore_hard_drift")&&phase==1)r->rlim_max--;
 if(is("restore_readback_mismatch")&&sets==2&&phase==1)r->rlim_cur++;
 return 0;
}
static int model_setrlimit(int resource,const struct rlimit*r){
 if(resource!=RLIMIT_NOFILE||sets>=4)abort();
 set_soft[sets]=r->rlim_cur;set_hard[sets]=r->rlim_max;sets++;
 if(r->rlim_max!=initial_hard)hard_changes++;
 if((is("set_failure")&&phase==0)||(is("restore_set_failure")&&phase==1)){
  errno=EPERM;return -1;
 }
 if(phase==1&&owned_closes!=5)abort();
 limits=*r;return 0;
}
static int model_open(const char*p,int flags){
 if(strcmp(p,"/proc/self/fd")||flags!=(O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC))abort();
 if(is("proc_open_failure")){errno=EMFILE;return -1;}
 if(proc_live)abort();proc_live=1;proc_opens++;walk=0;return own_fd;
}
static DIR*model_fdopendir(int fd){if(fd!=own_fd||!proc_live)abort();return sentinel;}
static struct dirent*model_readdir(DIR*d){
 if(d!=sentinel||!proc_live)abort();
 if(is("proc_read_failure")&&walk==3){errno=EIO;return NULL;}
 const char *special=NULL;char number[32];
 if(walk==0)special=".";
 else if(walk==1)special="..";
 else if(walk<(unsigned)population+2) {
  unsigned id=walk-2;if(missing_stdio)id+=3;
  snprintf(number,sizeof number,"%u",id);special=number;
 } else if(walk==(unsigned)population+2) {
  snprintf(number,sizeof number,"%d",own_fd);special=number;
 } else if(walk==(unsigned)population+3&&malformed)special="PRIVATE_CANARY/path";
 else return NULL;
 walk++;memset(&item,0,sizeof item);snprintf(item.d_name,sizeof item.d_name,"%s",special);return &item;
}
static int model_close(int fd){
 if(fd==own_fd){if(!proc_live)abort();proc_live=0;proc_closes++;if(is("proc_close_failure")){errno=EIO;return -1;}return 0;}
 if(fd>=10001&&fd<=10005){owned_closes++;return 0;}
 abort();
}
static int model_closedir(DIR*d){if(d!=sentinel)abort();return model_close(own_fd);}
static int model_fstat(int fd,struct stat*st){
 if(fd!=10003&&fd!=10002)abort();stat_calls++;memset(st,0,sizeof *st);
 if(is("anchor_failure")){errno=EIO;return -1;}return 0;
}
#define getrlimit model_getrlimit
#define setrlimit model_setrlimit
#define open model_open
#define fdopendir model_fdopendir
#define readdir model_readdir
#define closedir model_closedir
#define close model_close
#define fstat model_fstat
'''

POST = r'''
static unsigned char model_fds[2000],cloexecs[2000];
static int exec_dup2(int old,int next){
 if(old<0||old>=2000||next<0||next>=2000||!model_fds[old])abort();
 model_fds[next]=1;cloexecs[next]=0;return next;
}
static int exec_dup3(int old,int next,int flags){
 if(flags!=O_CLOEXEC)abort();exec_dup2(old,next);cloexecs[next]=1;return next;
}
static long exec_syscall(long call,unsigned first,unsigned last,unsigned flags){
 if(call!=91||first!=4U||last!=~0U||flags)abort();
 for(unsigned i=first;i<2000;i++)model_fds[i]=0;return 0;
}
static int exec_fexecve(int fd,char*const*args,char*const*env){
 (void)args;(void)env;if(fd!=3||!model_fds[fd]||!cloexecs[fd])abort();
 for(unsigned i=0;i<2000;i++)if(cloexecs[i])model_fds[i]=0;
 for(unsigned i=0;i<2000;i++)remaining_after_exec+=model_fds[i]!=0;
 if(remaining_after_exec!=3||!model_fds[0]||!model_fds[1]||!model_fds[2])abort();
 execution_reached++;inherited_soft=(uint64_t)limits.rlim_cur;return 0;
}
static void model_exec(void){
 int a[2]={610,611},b[2]={612,613},c[2]={614,615},adapter=500;
 char*const args[]={NULL};char*const env[]={NULL};
 for(unsigned i=0;i<1600;i++)model_fds[i]=1;
 #define dup2 exec_dup2
 #define dup3 exec_dup3
 #define syscall exec_syscall
 #define SYS_close_range 91
 #define fexecve exec_fexecve
'''
POST_END = r'''
 #undef dup2
 #undef dup3
 #undef syscall
 #undef SYS_close_range
 #undef fexecve
}
'''

MAIN = r'''
int main(int argc,char**argv){
 if(argc!=6)return 98;scenario=argv[1];
 limits.rlim_cur=!strcmp(argv[2],"inf")?RLIM_INFINITY:(rlim_t)strtoull(argv[2],NULL,10);
 limits.rlim_max=!strcmp(argv[3],"inf")?RLIM_INFINITY:(rlim_t)strtoull(argv[3],NULL,10);
 initial_hard=limits.rlim_max;population=atoi(argv[4]);missing_stdio=is("missing_stdio");
 malformed=is("proc_malformed");if(is("proc_overflow"))population=8192;
 model_count=sizeof exact_kinds/sizeof *exact_kinds;
 for(size_t i=0;i<model_count;i++)model_entries[i].kind=exact_kinds[i];
 if(is("contract_bad_kind"))model_entries[0].kind=88;
 int work=10003,runtime=10002,source=10001,adapter=10004,global=10005,cleanup=0;
 struct stat work_identity,runtime_identity;
 const char *primary="none";
 int adapter_admission=-2,fingerprint_admission=-2;
'''
MAIN_ACCEPT = r'''
 namespace_calls++;lease_calls++;
 primary=is("cleanup_primary")||is("restore_set_failure")||is("restore_readback_mismatch")||is("restore_hard_drift")?"fixture_source_bind":"native_adapter_cases_passed";
 if(!strcmp(argv[5],"full")){
  unsigned old_population=(unsigned)population;
  population++;
  uint64_t held=0;
  fingerprint_admission=fd_budget_runtime(&held)||fd_budget_admit(6+3+held+2,&namespace_budget);
  model_exec();
  population=555;missing_stdio=0;
  adapter_admission=adapter_fd_budget(&namespace_budget);
  population=(int)old_population;
 }
finish:
 phase=1;
'''
MAIN_END = r'''
 printf("{\"primary\":\"%s\",\"cleanup\":%d,\"sets\":%u,\"reads\":%u,\"hard_changes\":%u,\"namespaces\":%u,\"leases\":%u,\"stats\":%u,\"proc_live\":%u,\"proc_opens\":%u,\"proc_closes\":%u,\"owned_closes\":%u,\"execs\":%u,\"exec_fds\":%u,\"inherited_soft\":%llu,\"adapter_admission\":%d,\"fingerprint_admission\":%d,\"set_values\":[",primary,cleanup,sets,reads,hard_changes,namespace_calls,lease_calls,stat_calls,proc_live,proc_opens,proc_closes,owned_closes,execution_reached,remaining_after_exec,(unsigned long long)inherited_soft,adapter_admission,fingerprint_admission);
 for(unsigned i=0;i<sets;i++){if(i)putchar(',');printf("[%llu,%llu]",(unsigned long long)set_soft[i],(unsigned long long)set_hard[i]);}
 printf("],\"final_soft\":");fd_budget_value(limits.rlim_cur!=RLIM_INFINITY,(uint64_t)limits.rlim_cur);
 printf(",\"envelope\":");print_fixture_envelope();puts("}");return 0;
}
'''


def parts(root):
    adapter = (root/'adapter/adapter.c').read_text()
    helper = (root/'tests/native-fixture-runner.c').read_text()
    generated = (root/'adapter/generated_contract.h').read_text()
    kinds = [int(x) for x in re.findall(r'^\{"[^"\n]+",([123]),', generated, re.M)]
    if {k: kinds.count(k) for k in (1, 2, 3)} != {1: 196, 2: 50, 3: 21}:
        raise AssertionError('exact generated contract changed')
    tables = adapter[adapter.index('static const char*srcdirs[]='):adapter.index('static int source_optional(')]
    guard_start = helper.index('  primary="namespace_anchor_identity";')
    guard = helper[guard_start:helper.index('  primary="outer_namespace";', guard_start)]
    cleanup_start = helper.index('  if(source>=0&&close(source))cleanup=1;')
    cleanup = helper[cleanup_start:helper.index('  alarm(0);', cleanup_start)]
    exec_start = helper.index('    if(dup2(a[0],0)')
    exec_end = helper.index('    _exit(120);', helper.index('    fexecve(3,args,env);', exec_start))
    execution = helper[exec_start:exec_end]
    return adapter, helper, 'static const int exact_kinds[]={' + ','.join(map(str, kinds)) + '};\n', tables, guard, cleanup, execution


def compile_model(root, temp, cc, sanitizers):
    _, _, kinds, tables, guard, cleanup, execution = parts(root)
    text = (PRE + '\n#include ' + json.dumps(str(root/'adapter/fd-budget.h')) + '\n'
            + kinds + tables + '\n#include ' + json.dumps(str(root/'tests/fixture-resource-envelope.h'))
            + '\n' + POST + execution + POST_END + MAIN + guard + MAIN_ACCEPT + cleanup + MAIN_END)
    source = temp/'resource-envelope-model.c'
    source.write_text(text)
    binary = temp/'resource-envelope-model'
    command = [cc] + sanitizers + ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror',
               '-Wno-unused-function', '-Wno-misleading-indentation', str(source), '-o', str(binary)]
    result = subprocess.run(command, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError(result.stderr.decode())
    return binary


class Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='resource envelope ')
        cls.binary = compile_model(ROOT, P(cls.tmp.name), CC, SANITIZERS)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_case(self, scenario='ok', soft=1024, hard=1048576, count=520, full=False):
        p = subprocess.run([str(self.binary), scenario, str(soft), str(hard), str(count), 'full' if full else 'admit'], capture_output=True, timeout=3)
        self.assertEqual(p.returncode, 0, p.stderr.decode())
        self.assertEqual(p.stderr, b'')
        self.assertLess(len(p.stdout), 1600)
        for canary in (b'PRIVATE', b'/proc', b'/tmp', b'/Users', b'strerror'):
            self.assertNotIn(canary, p.stdout)
        r = json.loads(p.stdout)
        self.assertEqual(r['hard_changes'], 0)
        self.assertEqual(r['proc_live'], 0)
        self.assertEqual(r['proc_opens'], r['proc_closes'])
        self.assertEqual(r['owned_closes'], 5)
        self.assertTrue(all(v is None or type(v) in (int, bool) for v in r['envelope'].values()))
        return r

    def refused(self, scenario, primary, **kwargs):
        r = self.run_case(scenario, **kwargs)
        self.assertEqual(r['primary'], primary)
        self.assertEqual((r['namespaces'], r['leases'], r['execs']), (0, 0, 0))
        return r

    def test_sufficient_soft_no_mutation(self):
        for soft in (1098, 1200, 1048576):
            r = self.run_case(soft=soft)
            self.assertEqual((r['sets'], r['namespaces'], r['final_soft']), (0, 1, soft))
            self.assertFalse(r['envelope']['nofile_raise_attempted'])
            self.assertEqual(r['envelope']['required_total_peak'], 1098)

    def test_exact_bounded_raise_and_restoration(self):
        r = self.run_case()
        self.assertEqual(r['set_values'], [[1098, 1048576], [1024, 1048576]])
        self.assertEqual(r['envelope']['nofile_original_soft'], 1024)
        self.assertEqual(r['envelope']['nofile_requested_soft'], 1098)
        self.assertEqual(r['envelope']['nofile_effective_soft'], 1098)
        self.assertTrue(r['envelope']['nofile_raise_succeeded'])
        self.assertTrue(r['envelope']['nofile_restore_succeeded'])
        self.assertEqual(r['final_soft'], 1024)
        self.assertEqual(r['cleanup'], 0)

    def test_hard_one_slot_short_refuses(self):
        for hard in (1024, 1038, 1045, 1097):
            r = self.refused('ok', 'namespace_fd_budget', hard=hard)
            self.assertEqual(r['sets'], 0)
            self.assertEqual(r['envelope']['required_total_peak'], 1098)

    def test_hard_exact_required_is_accepted_and_unchanged(self):
        r = self.run_case(hard=1098)
        self.assertEqual(r['set_values'], [[1098, 1098], [1024, 1098]])

    def test_soft_one_slot_short_raises_exactly_one(self):
        r = self.run_case(soft=1097)
        self.assertEqual(r['set_values'], [[1098, 1048576], [1097, 1048576]])

    def test_set_failure_refuses_without_false_restore(self):
        r = self.refused('set_failure', 'resource_envelope_set')
        self.assertEqual(r['sets'], 1)
        self.assertTrue(r['envelope']['nofile_raise_attempted'])
        self.assertFalse(r['envelope']['nofile_raise_succeeded'])
        self.assertFalse(r['envelope']['nofile_restore_attempted'])
        self.assertEqual(r['final_soft'], 1024)

    def test_readback_failures_restore_after_successful_mutation(self):
        for scenario in ('post_set_mismatch', 'post_set_hard_mismatch', 'post_set_get_failure'):
            r = self.refused(scenario, 'resource_envelope_readback')
            self.assertIsNone(r['envelope']['nofile_effective_soft'])
            self.assertFalse(r['envelope']['nofile_raise_succeeded'])
            self.assertTrue(r['envelope']['nofile_restore_succeeded'])
            self.assertEqual(r['sets'], 2)
            self.assertEqual(r['final_soft'], 1024)

    def test_pre_mutation_drift_refuses_without_set(self):
        r = self.refused('pre_set_drift', 'resource_envelope_readback')
        self.assertEqual(r['sets'], 0)

    def test_restoration_failure_preserves_primary_and_is_secondary(self):
        for scenario in ('restore_set_failure', 'restore_readback_mismatch', 'restore_hard_drift'):
            r = self.run_case(scenario)
            self.assertEqual(r['primary'], 'fixture_source_bind')
            self.assertEqual(r['cleanup'], 1)
            self.assertTrue(r['envelope']['nofile_restore_error'])
            self.assertFalse(r['envelope']['nofile_restore_succeeded'])
            self.assertIsNone(r['envelope']['nofile_restored_soft'])

    def test_infinity_representation_and_no_mutation(self):
        r = self.run_case(soft='inf', hard='inf')
        self.assertEqual(r['sets'], 0)
        self.assertIsNone(r['envelope']['nofile_original_soft'])
        self.assertIsNone(r['envelope']['nofile_effective_soft'])
        self.assertIsNone(r['envelope']['nofile_hard'])
        r = self.run_case(hard='inf')
        self.assertTrue(r['envelope']['nofile_raise_succeeded'])
        self.assertIsNone(r['envelope']['nofile_hard'])

    def test_extra_fd_projection_ceiling_and_one_slot(self):
        r = self.run_case(count=573)
        self.assertEqual(r['envelope']['required_total_peak'], 1098)
        self.assertEqual(r['namespaces'], 1)
        r = self.refused('ok', 'namespace_fd_budget', count=574)
        self.assertEqual(r['envelope']['required_total_peak'], 1099)
        self.assertEqual(r['sets'], 0)
        # Sufficient inherited limit needs no raise or arbitrary lowering.
        r = self.run_case(count=574, soft=1100)
        self.assertEqual((r['sets'], r['namespaces']), (0, 1))

    def test_missing_stdio_reserved(self):
        r = self.run_case('missing_stdio', count=570)
        self.assertEqual(r['envelope']['required_total_peak'], 1098)
        r = self.refused('missing_stdio', 'namespace_fd_budget', count=571)
        self.assertEqual(r['envelope']['required_total_peak'], 1099)

    def test_incomplete_proc_observation_never_mutates(self):
        for scenario in ('first_get_failure', 'proc_open_failure', 'proc_read_failure', 'proc_close_failure', 'proc_malformed', 'proc_overflow', 'contract_bad_kind'):
            r = self.refused(scenario, 'namespace_fd_budget')
            self.assertEqual(r['sets'], 0)
            self.assertIsNone(r['envelope']['required_total_peak'])

    def test_anchor_failure_precedes_mutation(self):
        r = self.refused('anchor_failure', 'namespace_anchor_identity')
        self.assertEqual((r['sets'], r['reads'], r['proc_opens']), (0, 0, 0))

    def test_complete_path_inherits_limit_and_exec_closes_anchors(self):
        r = self.run_case(full=True)
        self.assertEqual((r['execs'], r['exec_fds'], r['inherited_soft']), (1, 3, 1098))
        self.assertEqual((r['adapter_admission'], r['fingerprint_admission']), (0, 0))
        self.assertEqual(r['final_soft'], 1024)

    def test_security_and_restore_order_from_actual_caller(self):
        adapter, helper, _, _, _, _, _ = parts(ROOT)
        main = helper[helper.index('int main('):]
        admit = main.index('fixture_envelope_admit()')
        for checked in ('geteuid()', 'hash_fd(adapter,', 'seal_adapter(adapter)', 'verify_runtime(runtime,&rt)', 'fchown(work,0,0)', 'fchmod(work,0700)', 'fstat(runtime,&runtime_identity)'):
            self.assertLess(main.index(checked), admit)
        self.assertLess(admit, main.index('fd_budget_admit(3+held+2'))
        self.assertLess(admit, main.index('unshare(CLONE_NEWNS|CLONE_NEWNET)'))
        restore = main.index('fixture_envelope_restore()')
        for finished in ('cleanup_owned(source)', 'recheck(&rt)', 'close_leases(&rt)', 'remove_exact_runtime(runtime)', 'fchown(work,workst.st_uid,workst.st_gid)', 'close(global)'):
            self.assertLess(main.index(finished, main.index('finish:alarm')), restore)
        self.assertLess(restore, main.index('printf("{\\"fixture'))
        self.assertNotIn('fixture-resource-envelope.h', adapter)
        self.assertNotIn('fixture_envelope_admit', adapter)

    def test_worker_1024_hardening_and_fd_close_invariant(self):
        adapter = (ROOT/'adapter/adapter.c').read_text()
        child = adapter[adapter.index('static void namespace_child('):adapter.index('static int namespace_start(')]
        self.assertEqual(child.count('setrlimit(RLIMIT_NOFILE,&r)'), 1)
        self.assertIn('r.rlim_cur=r.rlim_max=1024;', child)
        self.assertLess(child.index('private_view('), child.index('child_handles_close(h)'))
        self.assertLess(child.index('child_handles_close(h)'), child.index('setrlimit(RLIMIT_NOFILE,&r)'))
        tail = child[child.index('if(setrlimit(RLIMIT_NOFILE,&r))'):]
        self.assertNotRegex(tail[:tail.index('close_extra_fds();')], r'\b(open|openat|pipe|pipe2|dup|dup2|dup3|fcntl)\s*\(')
        self.assertEqual(tail.count('close_extra_fds();'), 2)
        self.assertLess(tail.index('close_extra_fds();'), tail.index('execve('))
        self.assertIn('if(syscall(SYS_close_range,3U,~0U,0))', adapter)

    def test_no_system_wide_or_foreign_process_limit_api(self):
        header = (ROOT/'tests/fixture-resource-envelope.h').read_text()
        self.assertNotRegex(header, r'\b(prlimit|sysctl|system|popen)\s*\(')
        self.assertEqual(header.count('setrlimit(RLIMIT_NOFILE,&next)'), 2)
        self.assertIn('next=current;next.rlim_cur=(rlim_t)e->required;', header)
        self.assertIn('next=current;next.rlim_cur=e->original.rlim_cur;', header)
        self.assertNotRegex(header, r'next\.rlim_max\s*=')

    def test_r3_portable_blocks_exact_unchanged(self):
        raw = (ROOT/'tests/test-namespace-handoff.py').read_bytes()
        # The sole test-isolation glue delegates the new admission to this
        # suite. Removing exactly that glue must recover the published R3
        # file, preserving observations, all assertions and longjmp boundaries.
        glue = (b'/* Resource admission is exercised by test-resource-envelope.py. */\n'
                b'static const char *fixture_envelope_admit(void){return NULL;}\n')
        self.assertEqual(raw.count(glue), 1)
        self.assertEqual(hashlib.sha256(raw.replace(glue, b'', 1)).hexdigest(), 'd4cf481570a111c290e88f8e21220fc8d7f8b328e3e16fd49b6ef9f447aba12e')
        self.assertIn(b'observed_runtime_ns', raw)
        self.assertIn(b'observed_source_ns', raw)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=P, default=HERE.parent)
    parser.add_argument('--cc', default='cc')
    parser.add_argument('--ubsan', action='store_true')
    args = parser.parse_args()
    ROOT = args.root.resolve()
    CC = args.cc
    SANITIZERS = ['-fsanitize=undefined', '-fno-sanitize-recover=all'] if args.ubsan else []
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Tests))
    print(json.dumps({'tests': result.testsRun, 'failures': len(result.failures), 'errors': len(result.errors),
                      'skips': len(result.skipped), 'ubsan': bool(SANITIZERS),
                      'verdict': 'PASS' if result.wasSuccessful() else 'FAIL',
                      'scope': 'exact helper envelope/caller and pre-exec code; modeled resource/proc syscalls, no actual limit mutation or native execution'}))
    raise SystemExit(not result.wasSuccessful())
