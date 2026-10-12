# CLB-195 synthetic namespace bootstrap

Executable local candidate; **NATIVE_PENDING**. Not a stage command, installer,
writer or release operation. CLB-192 native run 37757252333 / attempt 1 /
9234bacd421c6ac87dfd92b5c0c73a838f0660ca remains valid for its unchanged sources.
It does not establish acceptance of this new integration.

`build.py` verifies the existing native-adapter source/reference pins and makes a
temporary test variant. Its `namespace_child` body, embedded bootstrap/request
arrays, `child_fail` diagnostic writes and copied common C test's fixture syscall
checks change. The input core test remains bound to its original hash;
shape-checked substitutions retain every
original assertion and expose the generated core source hash. No original
adapter, Runtime, planner, CLB-192 source or
production manifest is edited. The existing native fixture's project name becomes
`clubs-bot-stage` when that fixture is created, before its first capture; no saved
stage binding is rewritten. Generated source and header hashes are reviewable.

The static fixture helper verifies/seals the exact compiled adapter. The adapter
verifies the pinned rootfs before source leases and creates its original-object
private view using the existing ownership, mount tuple, shared locks and FD
rechecks. This inherits the native experiment's CI-provider/kernel/compiler trust
boundary; it is not an attestation of an arbitrary supervisor or stage host.

The new fixed namespace body starts two children, then drops its own privilege.
Both children drop to the fixture's unchanged UID/GID 1000 and pinned `prototype`
account before Python. Producer loads an exact hashed source bundle and completes
the full Runtime gate before copying its interpolation environment or reading
snapshot contents. It uses the existing CLB-192 snapshot function and original
ReadOnlyCapture. The parent retains original leases throughout. All processes use
bounded deadlines; namespace PID1 death destroys its descendant processes.
PID1 installs explicit termination handlers (default signal dispositions are not
treated as evidence of a working namespace-init deadline).

Worker has an additional mount/PID/network namespace, newly mounted procfs and an
empty read-only canonical directory. Original FDs are closed before exec. Worker
requires PID 1, empty canonical directory and absent Docker socket. Only a bounded
anonymous pipe carries the snapshot; a second pipe carries the nonce-bound public
response. The unchanged CLB-192 worker completes its Runtime gate before reading,
and calls the real planner. No original-file mount, producer PID, arbitrary
command, daemon socket, network access or private argv/environment transport is
provided to the worker. Runtime is the existing native-adapter's complete pinned
196-file/23-alias profile, not a reduced or learned allowlist.

Synthetic interpolation is complete by construction: C exec supplies exactly
PATH, HOME and LC_ALL, and producer verifies that complete environment after the
Runtime gate. Missing or extra variables refuse. This does not implement capture
of a live deployment principal's environment. Both capture identity/mount and
Runtime are rechecked; cleanup failure cannot return a positive report. The
existing outer C HMAC parser authenticates the final result against its fresh
request. Private exceptions and child stderr are not published.

Portable verification:

```sh
python3 -I -S -B scripts/tests/linux-semantic/namespace-bootstrap/test-bootstrap.py
python3 -B scripts/tests/test_amd64_ci_harness.py
python3 -I -S -B scripts/tests/linux-semantic/native-adapter/ci/test-recipe.py
ruby scripts/validate-workflow-yaml.rb
ruby scripts/validate-workflow-capabilities.rb
git diff --check
```

Python boundary fault injection proves ordering/refusal/cleanup orchestration,
not a real namespace. Pipe timeout and generated common C lifecycle tests execute
actual OS operations without root. Native namespace execution and Linux static
linking remain pending; no privileged test runs on Darwin/Rosetta.

The user-supplied Lint diagnostic located an unchecked `symlinkat` at core source
line 98; `warning=other` does not prove the exact GCC warning or fortify setting.
The generated CLB-195 core checks both symlink operations and the analogous
pipe/write/pwrite results. Pipe/fork setup failure closes acquired descriptors;
short child writes exit unsuccessfully. Fault injections for initial/replacement
symlinks, pipe/fork failure, short write and failed pwrite must produce a failed
summary and confirmed owned-fixture cleanup. Compiler flags stay unchanged.
Portable compilation also marks these calls `warn_unused_result`: the original
core must refuse for unused results and the checked variant must compile. This
tests the result-use contract without proving the exact Ubuntu diagnostic.

Run 37949150044 stopped in `portable` before static build or namespace execution.
Its published evidence did not identify the failing test because the coordinator
withheld child stderr. The follow-up candidate emits only a checked suite verdict
and fixed test method names to stdout. The coordinator accepts PASS only with the
complete 14-test report and exit zero; on failure it prints a bounded method name
or `UNKNOWN`. It still withholds assertion text, child stderr and private values.
Tests run 37965271312 identified
`test_generated_common_c_core_real_lifecycle` as the failing Ubuntu test, but
its bounded output cannot distinguish materialization, compilation, execution,
stderr, timeout or summary failure. The follow-up local candidate marks the
first failing stage with a fixed `c_stage` category in the portable V2 report.
The coordinator validates that category and the exit code before publishing it;
it never publishes raw compiler output, process stderr or assertion text. This is
diagnosis only: the same C assertions and native PASS gates remain required.
Linux GCC behavior and the exact defect remain unverified until the automatic
PR Lint runs these changed bytes.

The automatic PR Lint runs the existing portable suite through its CI harness.
On a C compile failure, that harness now publishes only an allowlisted C source
name, bounded line number and fixed GCC warning category. The test still fails;
raw compiler output, source paths and assertion text stay private. This diagnoses
the Linux compiler refusal without running the native namespace step.

## Native build failure attribution

Terminal main run 38215257414 / attempt 1 / commit
ecdc977e0eb74ce5b216c007f24b13ff97c1dd49 passed prior CLB-191/192 checks and
the complete portable suite, then stopped at `phase=build`, with `build={}`,
core/capture/cleanup and native controls NOT_RUN. Its five source hashes match
the local candidate before this diagnostic change. The log contains only
`CLB195_NAMESPACE_FAIL`; it does not establish a compiler, namespace or privilege
failure. The first build interval covers materialization, adapter and core C
compilation, bounded binary reads and static ELF validation. Fixture compilation
comes after successful core execution and cannot explain this observed record.

On a build refusal the coordinator now appends only fixed `build_stage` and
`build_reason` categories to that console marker. Stages identify `materialize`,
adapter/core/fixture `compile`, `read` or `elf`. Reasons distinguish a recorded command
nonzero exit, timeout, cancellation, output bound or unconfirmed process from a
generic `operation_failed`; unavailable attribution yields `UNKNOWN`. Only the
new capture record for that operation may supply a transport reason. No exception
text, argv, compiler output or private path is published. `command_nonzero` would
prove that the compiler/linker command failed, not its precise diagnostic.

The bounded artifact schema and PASS predicates are unchanged. Every exception
still propagates to FAIL, static flags and deadlines are unchanged, and no new
artifact is uploaded. Portable fault injection checks actual orchestration order,
every instrumented build operation, transport attribution, cancellation propagation and output
allowlisting; it does not execute a native namespace. Local Apple Clang cannot
establish Ubuntu GCC static acceptance. New native behavior remains NATIVE_PENDING.

## One future manual Tests run

Run 38239985347 / attempt 1 / commit
06b8d55ebeff4d8fa64ee4584a5728b238beacdd passed adapter compilation and core,
then refused at `fixture_compile` / `command_nonzero` before capture/cleanup.
Artifact 11742932203 matches the local source hashes. Exact GCC/linker cause is
UNKNOWN; this follow-up extends the existing bounded compiler observer to fixture
compilation only. It additionally permits `native-fixture-runner.c` and the three
fixed fixture headers as source basenames. Line/warning validation, transport
refusal/timeout/cleanup, strict flags and artifact/PASS contracts are unchanged.
Unsupported linker or compiler output remains UNKNOWN. Portable routing/parser
and real synthetic transport regressions do not establish Ubuntu compilation or
native namespace acceptance. No fixture source/pin is changed.

User-confirmed run 38236374063 / attempt 1 / commit
60d19d4c187ed4d8196710ab033bc4b89eefd6d1 reported `adapter_compile` /
`command_nonzero`, `adapter.c:931`, `unused-result`. The CLB-195 generator now
shape-checks and replaces only the two unchecked diagnostic writes in `child_fail`.
Cleanup still happens once before output, the fixed reason/optional
`+child_cleanup` messages remain, and failed or short writes exit 120, as does the
completed failure path. No warning suppression or historical pin update is used.
Portable regression compiles the exact extracted old/generated function with an
explicit `warn_unused_result` attribute: old refuses, generated compiles. Synthetic
write errors/short results test both calls, cleanup ordering and exit 120 without
privilege or namespaces. Local Apple Clang evidence is not Ubuntu GCC 13 static
acceptance; the full adapter build and native namespace remain NATIVE_PENDING.

Run 38230093134 / attempt 1 / commit
14fee9a9eba70ecdf768d311b7836b7c7aae2077 confirmed `adapter_compile` /
`command_nonzero`, after preparation, prior checks and portable PASS. Exact GCC
warning, source error or linker failure remains UNKNOWN. The separate unit job's
wrapper-validation network failure is unrelated; no wrapper or gate is changed.
The coordinator's adapter-only compiler capture now adds an observer to a
shape-checked in-memory copy of the pinned capture function. Transport deadlines,
bounds, cancellation, process cleanup and nonzero refusal remain intact; historical
driver bytes/pins are unchanged. The observer retains only an allowlisted source
basename, bounded line and fixed warning category. Console-only fields are
`gcc_source`, `gcc_line`, `gcc_warning`; raw compiler output and exception text
remain private. Linker-only, colored or unsupported diagnostics give UNKNOWN.
Artifact schema/limits and native PASS predicates are unchanged. Real synthetic
unprivileged processes test nonzero refusal, successful transport and timeout;
these checks are not Ubuntu GCC compilation or native namespace acceptance.

After separately authorized publication and dispatch, the existing
`amd64-runtime-prototype` job on ubuntu-24.04 runs unchanged preparation, original
native adapter and CLB-191/192 checks, then:

```sh
python3 -I -S -B scripts/tests/linux-semantic/namespace-bootstrap/native-ci.py \
  --prepared "$RUNNER_TEMP/clb91-adapter-inputs" \
  --output "$RUNNER_TEMP/clb195-namespace"
```

The new step requires native x86_64, initial manual attempt, matching exact
commit/run/preparation/CLB-192 evidence and pinned inputs. It reuses the prepared
rootfs without downloading/installing anything. It compiles a fixed static adapter
and the existing synthetic fixture helper as the ordinary runner user. Only that
fixed helper receives `sudo -n`, over its own disposable fixture. Existing outer
namespace setup precedes `/opt` projection: no host-global mount, sysctl/AppArmor
change, privileged Docker or stage credentials. Missing capabilities refuse.

PASS requires portable controls, actual C core controls (including drift, HMAC,
real timeout/signal/reaping), a successful real capture-to-isolated-worker result,
real wrong-UID/symlink/runtime-tamper/unsupported-backing refusals, source identity
rechecks and confirmed native plus coordinator cleanup. Any skipped check, early
failure or unknown cleanup is FAIL, never native acceptance. The new artifact
`clb195-namespace/result.json` is at most 4096 bytes and contains fixed statuses,
commit/run/source/build identities, bounded execution phase and the exact five
native control statuses only. Missing controls, numeric truthy substitutes and
incomplete identity/cleanup checks refuse. Existing artifact total 1 MiB and
retention three days remain. No raw snapshots, model, trace or runtime archive is
uploaded. Artifacts are checked against prior CLB-192 evidence and current sources.

New step timeout: 15 minutes, inside the unchanged 75-minute prototype job.
Unchanged unit and integration jobs also run on workflow_dispatch (45 minutes
each): configured total remains 165 job-minutes, not elapsed time or a price quote.
The prototype's 75-minute limit wins if earlier steps consume its budget; this is
not a guaranteed completion time. Same-ref Tests concurrency can cancel an
existing run. One future approval must cover the full workflow, existing frozen
input preparation/builds and fixed synthetic namespace privilege. No dispatch,
publication or new runtime resource is authorized by this README.

Stage applicability still requires a separate bounded decision: this fixture's
principal, dedicated mount and fixed interpolation are not observed stage facts.
No migration/release authority is created. STAGE_TRUST=UNVERIFIED;
TRANSACTION_READINESS=NO.
