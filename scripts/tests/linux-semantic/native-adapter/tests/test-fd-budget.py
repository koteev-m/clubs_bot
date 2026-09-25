#!/usr/bin/env python3
"""Real admission/header + exact caller guards; narrow synthetic syscall boundary.

No limit changes, root, namespace, mount or worker execution. The procfs model
enumerates numeric descriptor names only. Actual generated entry kinds and
source tables supply the formula; the OS boundary supplies availability/errors.
"""
import argparse
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
static rlim_t observed_soft,observed_hard;
static int names_n,read_at,live,opens,closes,limits_reads;
static char names[8200][32];
static const int own_fd=10000;
static struct dirent item;
static DIR *const sentinel=(DIR*)(uintptr_t)1;
static int is(const char*s){return !strcmp(scenario,s);}
static void add_name(const char*s){if(names_n>=8200)abort();snprintf(names[names_n++],32,"%s",s);}
static void add_id(int n){char text[32];snprintf(text,sizeof text,"%d",n);add_name(text);}
static int tb_getrlimit(int resource,struct rlimit*r){
 if(resource!=RLIMIT_NOFILE)abort();limits_reads++;
 if(is("getrlimit_error")){errno=EIO;return -1;}
 r->rlim_cur=observed_soft;r->rlim_max=observed_hard;return 0;
}
static int tb_open(const char*p,int flags){
 if(strcmp(p,"/proc/self/fd")||flags!=(O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC))abort();
 if(is("open_error")){errno=EMFILE;return -1;}
 if(live)abort();live=1;opens++;return own_fd;
}
static DIR*tb_fdopendir(int fd){
 if(fd!=own_fd||!live)abort();
 if(is("fdopendir_error")){errno=ENOMEM;return NULL;}return sentinel;
}
static struct dirent*tb_readdir(DIR*d){
 if(d!=sentinel||!live)abort();
 if(is("readdir_error")&&read_at==2){errno=EIO;return NULL;}
 if(read_at==names_n)return NULL;
 memset(&item,0,sizeof item);strcpy(item.d_name,names[read_at++]);return &item;
}
static int tb_close(int fd){
 if(fd!=own_fd||!live)abort();live=0;closes++;
 if(is("close_error")||is("fdopendir_close_error")){errno=EIO;return -1;}return 0;
}
static DIR*tb_fdopendir_dispatch(int fd){
 if(is("fdopendir_close_error")){if(fd!=own_fd||!live)abort();errno=ENOMEM;return NULL;}
 return tb_fdopendir(fd);
}
static int tb_closedir(DIR*d){if(d!=sentinel)abort();return tb_close(own_fd);}
static void*tb_malloc(size_t size){if(is("malloc_error"))return NULL;return malloc(size);}
#define getrlimit tb_getrlimit
#define open tb_open
#define fdopendir tb_fdopendir_dispatch
#define readdir tb_readdir
#define closedir tb_closedir
#define close tb_close
#define malloc tb_malloc
'''

POST = r'''
#undef getrlimit
#undef open
#undef fdopendir
#undef readdir
#undef closedir
#undef close
#undef malloc
static void setup_names(void){
 add_name(".");add_name("..");
 if(is("many")){for(int i=0;i<1000;i++)add_id(i);}
 else if(is("overflow")){for(int i=0;i<8192;i++)add_id(i);}
 else if(is("exact_bound")){for(int i=0;i<8189;i++)add_id(i);}
 else if(is("missing_stdio")){add_id(1);add_id(2);add_id(8);}
 else if(is("missing_all_stdio")){add_id(8);add_id(9);}
 else {add_id(0);add_id(1);add_id(2);}
 if(is("sparse")){add_id(100);add_id(1000000);}
 if(is("duplicate"))add_id(2);
 if(is("bad_zero"))add_name("01");
 if(is("bad_sign"))add_name("-1");
 if(is("bad_decimal"))add_name("3.5");
 if(is("bad_overflow"))add_name("2147483648");
 if(is("bad_long"))add_name("9999999999999999999999999999999");
 if(is("bad_canary"))add_name("PRIVATE_CANARY_91");
 if(!is("own_absent"))add_id(own_fd);
 if(is("own_duplicate"))add_id(own_fd);
 if(is("interrupted"))interrupted=1;
}
static unsigned clone_calls,lease_calls,source_calls;
static const char*primary="none";
static int cleanup;
'''

MAIN = r'''
int main(int argc,char**argv){
 if(argc!=5)return 98;scenario=argv[2];
 observed_soft=!strcmp(argv[3],"inf")?RLIM_INFINITY:(rlim_t)strtoull(argv[3],NULL,10);
 observed_hard=is("hard_finite")?4096:RLIM_INFINITY;
 setup_names();model_count=sizeof exact_kinds/sizeof *exact_kinds;
 for(size_t i=0;i<model_count;i++)model_entries[i].kind=exact_kinds[i];
 if(is("bad_kind"))model_entries[0].kind=77;
 if(is("too_many_entries"))model_count=MAX_LEASES+1;
 uint64_t held=0,additional=strtoull(argv[4],NULL,10);int rc=-1,fr=fd_budget_runtime(&held);
 if(!strcmp(argv[1],"adapter"))rc=guard_adapter();
 else if(!strcmp(argv[1],"helper"))rc=guard_helper();
 else if(!strcmp(argv[1],"fingerprint"))rc=guard_fingerprint();
 else if(!strcmp(argv[1],"formula"))rc=fr;
 else if(!strcmp(argv[1],"number")){int value=-1;rc=fd_budget_number(argv[2],&value);held=value<0?0:(uint64_t)value;}
 else if(!strcmp(argv[1],"admit"))rc=fd_budget_admit(additional,&namespace_budget);
 else return 97;
 printf("{\"rc\":%d,\"formula_rc\":%d,\"runtime_held\":%llu,\"source_held\":%zu,\"clone_calls\":%u,\"lease_calls\":%u,\"source_calls\":%u,\"primary\":\"%s\",\"cleanup\":%d,\"live\":%d,\"opens\":%d,\"closes\":%d,\"limits_reads\":%d,\"close_error\":%d,\"budget\":",rc,fr,(unsigned long long)held,2*(sizeof srcdirs/sizeof *srcdirs+sizeof srcfiles/sizeof *srcfiles),clone_calls,lease_calls,source_calls,primary,cleanup,live,opens,closes,limits_reads,namespace_budget.close_error);
 print_fd_budget(&namespace_budget);puts("}");return 0;
}
'''


def production_parts(root):
    adapter = (root / 'adapter/adapter.c').read_text()
    helper = (root / 'tests/native-fixture-runner.c').read_text()
    generated = (root / 'adapter/generated_contract.h').read_text()
    kinds = [int(x) for x in re.findall(r'^\{"[^"\n]+",([123]),', generated, re.M)]
    if len(kinds) != 267 or {k: kinds.count(k) for k in (1, 2, 3)} != {1: 196, 2: 50, 3: 21}:
        raise AssertionError('exact generated contract changed; recompute reviewed budget')
    tables = adapter[adapter.index('static const char*srcdirs[]='):adapter.index('static int source_optional(')]
    adapter_guard = adapter[adapter.index('  primary="namespace_fd_budget";'):adapter.index('  primary="namespace";', adapter.index('  primary="namespace_fd_budget";'))]
    helper_guard = helper[helper.index('  primary="namespace_fd_budget";'):helper.index('  primary="outer_namespace";', helper.index('  primary="namespace_fd_budget";'))]
    fingerprint_guard = re.search(r'  \{uint64_t held;if\(fd_budget_runtime\(&held\)\|\|fd_budget_admit\(6\+3\+held\+2,&namespace_budget\)\)return -1;\}', helper)
    if fingerprint_guard is None:
        raise AssertionError('exact fingerprint admission guard missing')
    # The unchanged guard snippets execute, not a boolean/source-name imitation.
    # The boundary after admission counts where the actual clone/leases begin.
    guard_code = 'static int guard_adapter(void){\n' + adapter_guard + r'''
 clone_calls++;lease_calls++;return 0;
finish:return -1;
}
static int guard_helper(void){
''' + helper_guard + r'''
 clone_calls++;lease_calls++;source_calls++;return 0;
finish:return -1;
}
static int guard_fingerprint(void){
''' + fingerprint_guard.group(0) + r'''
 clone_calls++;lease_calls++;return 0;
}
'''
    return 'static const int exact_kinds[]={' + ','.join(map(str, kinds)) + '};\n', tables, guard_code


def compile_tests(root, destination, cc, sanitizers):
    kinds, tables, guards = production_parts(root)
    source = destination / 'fd-budget-tests.c'
    source.write_text(PRE + '\n#include ' + json.dumps(str(root / 'adapter/fd-budget.h')) + '\n' + POST + kinds + tables + guards + MAIN)
    binary = destination / 'fd-budget-tests'
    command = [cc] + sanitizers + ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror', '-Wno-unused-function', '-Wno-misleading-indentation', str(source), '-o', str(binary)]
    result = subprocess.run(command, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError(result.stderr.decode())
    return binary


class Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='fd budget ')
        cls.binary = compile_tests(ROOT, P(cls.tmp.name), CC, SANITIZERS)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_case(self, mode='admit', scenario='ok', soft=10000, additional=543):
        result = subprocess.run([str(self.binary), mode, scenario, str(soft), str(additional)], capture_output=True, timeout=3)
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertEqual(result.stderr, b'')
        self.assertLess(len(result.stdout), 1024)
        for secret in (b'PRIVATE', b'/proc', b'/tmp', b'/opt', b'/Users', b'strerror'):
            self.assertNotIn(secret, result.stdout)
        value = json.loads(result.stdout)
        self.assertEqual(value['live'], 0)
        self.assertEqual(value['opens'], value['closes'])
        self.assertEqual(set(value['budget']), {'nofile_soft', 'nofile_hard', 'open_fd_count', 'required_additional_peak', 'required_total_peak', 'fd_budget_sufficient'})
        self.assertTrue(all(x is None or type(x) in (int, bool) for x in value['budget'].values()))
        return value

    def test_exact_contract_formula_and_all_three_guard_peaks(self):
        result = self.run_case('formula')
        self.assertEqual((result['runtime_held'], result['source_held']), (513, 24))
        for mode, peak in [('adapter', 543), ('helper', 518), ('fingerprint', 524)]:
            with self.subTest(mode=mode):
                result = self.run_case(mode)
                self.assertEqual(result['rc'], 0)
                self.assertEqual(result['budget']['required_additional_peak'], peak)
                self.assertEqual(result['clone_calls'], 1)

    def test_one_slot_short_refuses_all_actual_guards(self):
        for mode, peak in [('adapter', 543), ('helper', 518), ('fingerprint', 524)]:
            with self.subTest(mode=mode):
                result = self.run_case(mode, soft=3 + peak - 1)
                self.assertEqual(result['rc'], -1)
                self.assertFalse(result['budget']['fd_budget_sufficient'])
                self.assertEqual((result['clone_calls'], result['lease_calls'], result['source_calls']), (0, 0, 0))
                if mode != 'fingerprint':
                    self.assertEqual(result['primary'], 'namespace_fd_budget')

    def test_exact_threshold_accepts(self):
        for mode, peak in [('adapter', 543), ('helper', 518), ('fingerprint', 524)]:
            result = self.run_case(mode, soft=3 + peak)
            self.assertEqual(result['rc'], 0)
            self.assertTrue(result['budget']['fd_budget_sufficient'])

    def test_limits_zero_low_and_large(self):
        for soft in (0, 1, 3, 1024):
            result = self.run_case(scenario='many', soft=soft)
            self.assertEqual(result['rc'], -1)
        self.assertEqual(self.run_case(scenario='many', soft=1543)['rc'], 0)

    def test_infinity_is_null_not_platform_integer(self):
        result = self.run_case(soft='inf')
        self.assertEqual(result['rc'], 0)
        self.assertIsNone(result['budget']['nofile_soft'])
        self.assertIsNone(result['budget']['nofile_hard'])
        self.assertEqual(result['budget']['required_total_peak'], 546)
        self.assertEqual(self.run_case(scenario='hard_finite', soft='inf')['budget']['nofile_hard'], 4096)

    def test_many_current_descriptors_reduce_available_budget(self):
        result = self.run_case(scenario='many', soft=1542)
        self.assertEqual(result['budget']['open_fd_count'], 1000)
        self.assertEqual(result['budget']['required_total_peak'], 1543)
        self.assertEqual(result['rc'], -1)

    def test_sparse_high_descriptor_numbers_count_conservatively(self):
        # Includes descriptor 1000000 even when soft=548. It occupies no low
        # slot, but including it cannot under-budget low-slot availability.
        result = self.run_case(scenario='sparse', soft=548)
        self.assertEqual(result['budget']['open_fd_count'], 5)
        self.assertEqual(result['rc'], 0)
        self.assertEqual(self.run_case(scenario='sparse', soft=547)['rc'], -1)

    def test_missing_stdio_slots_reserved_for_dup_minimum_three(self):
        for scenario, count, reserve in [('missing_stdio', 3, 1), ('missing_all_stdio', 2, 3)]:
            result = self.run_case(scenario=scenario)
            self.assertEqual(result['budget']['open_fd_count'], count)
            self.assertEqual(result['budget']['required_additional_peak'], 543 + reserve)
            self.assertEqual(result['budget']['required_total_peak'], count + 543 + reserve)

    def test_own_enumeration_descriptor_excluded_exactly_once(self):
        result = self.run_case()
        self.assertEqual(result['budget']['open_fd_count'], 3)
        self.assertEqual((result['opens'], result['closes']), (1, 1))
        for scenario in ('own_absent', 'own_duplicate'):
            self.assertEqual(self.run_case(scenario=scenario)['rc'], -1)

    def test_malformed_names_and_duplicates_refuse_without_leak(self):
        for scenario in ('duplicate', 'bad_zero', 'bad_sign', 'bad_decimal', 'bad_overflow', 'bad_long', 'bad_canary'):
            with self.subTest(scenario=scenario):
                result = self.run_case(scenario=scenario)
                self.assertEqual(result['rc'], -1)
                self.assertIsNone(result['budget']['open_fd_count'])
                self.assertIsNone(result['budget']['fd_budget_sufficient'])

    def test_bounded_enumeration_overflow_and_exact_boundary(self):
        result = self.run_case(scenario='overflow', soft='inf')
        self.assertEqual(result['rc'], -1)
        self.assertIsNone(result['budget']['open_fd_count'])
        result = self.run_case(scenario='exact_bound', soft='inf')
        self.assertEqual(result['rc'], 0)
        self.assertEqual(result['budget']['open_fd_count'], 8189)

    def test_getrlimit_failure_has_no_proc_open_or_partial_state(self):
        result = self.run_case(scenario='getrlimit_error')
        self.assertEqual(result['rc'], -1)
        self.assertEqual(result['opens'], 0)
        self.assertEqual(result['limits_reads'], 1)
        self.assertTrue(all(x is None for x in result['budget'].values()))

    def test_proc_open_dir_stream_read_allocation_and_cancel_fail_closed(self):
        for scenario in ('open_error', 'fdopendir_error', 'readdir_error', 'malloc_error', 'interrupted'):
            with self.subTest(scenario=scenario):
                result = self.run_case(scenario=scenario)
                self.assertEqual(result['rc'], -1)
                self.assertIsNone(result['budget']['fd_budget_sufficient'])

    def test_close_failure_is_secondary_and_not_sufficient(self):
        for scenario in ('close_error', 'fdopendir_close_error'):
            result = self.run_case('adapter', scenario=scenario)
            self.assertEqual(result['rc'], -1)
            self.assertEqual(result['close_error'], 1)
            self.assertEqual(result['cleanup'], 1)
            self.assertEqual(result['primary'], 'namespace_fd_budget')
            self.assertEqual(result['lease_calls'], 0)

    def test_unsigned_arithmetic_overflow_never_wraps_to_pass(self):
        for scenario in ('ok', 'missing_stdio'):
            result = self.run_case(scenario=scenario, soft='inf', additional=(1 << 64) - 1)
            self.assertEqual(result['rc'], -1)
            self.assertIsNone(result['budget']['required_total_peak'])
            self.assertIsNone(result['budget']['fd_budget_sufficient'])

    def test_unknown_contract_kind_and_excessive_entries_refuse_before_proc(self):
        for scenario in ('bad_kind', 'too_many_entries'):
            result = self.run_case('adapter', scenario=scenario)
            self.assertEqual(result['rc'], -1)
            self.assertEqual(result['formula_rc'], -1)
            self.assertEqual(result['opens'], 0)
            self.assertEqual(result['lease_calls'], 0)

    def test_integer_parser_boundaries(self):
        for text, expected in [('0', 0), ('2147483647', 2147483647), ('2147483648', None), ('', None), ('00', None), ('+1', None), (' 1', None), ('1 ', None), ('1\n', None)]:
            result = self.run_case('number', scenario=text)
            self.assertEqual(result['rc'], 0 if expected is not None else -1)
            if expected is not None:
                self.assertEqual(result['runtime_held'], expected)

    def test_no_mutation_in_admission_and_worker_close_order(self):
        header = (ROOT / 'adapter/fd-budget.h').read_text()
        for forbidden in ('setrlimit', 'prlimit', 'sysctl', 'system', 'popen', 'dup', 'dup2', 'dup3'):
            self.assertIsNone(re.search(r'\b' + forbidden + r'\s*\(', header))
        source = (ROOT / 'adapter/adapter.c').read_text()
        child = source[source.index('static void namespace_child('):source.index('static int namespace_start(')]
        self.assertEqual(child.count('setrlimit(RLIMIT_NOFILE,&r)'), 1)
        self.assertLess(child.index('private_view('), child.index('child_handles_close(h)'))
        self.assertLess(child.index('child_handles_close(h)'), child.index('setrlimit(RLIMIT_NOFILE,&r)'))
        after = child[child.index('if(setrlimit(RLIMIT_NOFILE,&r))'):]
        before_close = after[:after.index('close_extra_fds();')]
        self.assertNotRegex(before_close, r'\b(open|openat|pipe|pipe2|dup|dup2|dup3|fcntl)\s*\(')
        self.assertEqual(after.count('close_extra_fds();'), 2)
        self.assertLess(after.index('close_extra_fds();'), after.index('execve('))
        pid1 = after[after.index('/* PID1 holds no original capabilities'):]
        self.assertLess(pid1.index('close_extra_fds();'), pid1.index('waitpid('))
        self.assertNotRegex(pid1, r'\b(open|openat|pipe|pipe2|dup|dup2|dup3|fcntl)\s*\(')
        self.assertIn('r.rlim_cur=r.rlim_max=1024;', child)
        self.assertIn('if(syscall(SYS_close_range,3U,~0U,0))', source)

    def test_worker_fixed_held_sets_do_not_inherit_parent_leases(self):
        # This is a source-contract check of explicit application FD owners,
        # not a dynamic loader/Compose peak measurement or native proof.
        runtime = json.loads((ROOT / 'reference/candidate-runtime.json').read_text())
        diagnostic = (ROOT / 'reference/stage-compose-diagnostic-operation.py').read_text()
        planner = (ROOT / 'reference/stage-compose-env-file-plan.py').read_text()
        self.assertEqual(len(runtime['files']), 196)
        self.assertRegex(diagnostic, r'(?m)^FD_LIMIT = 48$')
        self.assertIn('require(len(self.fds) <= FD_LIMIT', diagnostic)
        self.assertIn('len(self.fds) < 8', planner)
        # Existing steady explicit owners: manifest196 + capture48 + sealed8
        # + canonical-root1 + standard3. No native total-peak claim here.
        self.assertEqual(len(runtime['files']) + 48 + 8 + 1 + 3, 256)


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
    print(json.dumps({'tests': result.testsRun, 'failures': len(result.failures), 'errors': len(result.errors), 'skips': len(result.skipped), 'ubsan': bool(SANITIZERS), 'verdict': 'PASS' if result.wasSuccessful() else 'FAIL', 'scope': 'exact admission primitive and caller guards; syscall procfs/resource model, no native/root/limit mutation'}))
    raise SystemExit(not result.wasSuccessful())
