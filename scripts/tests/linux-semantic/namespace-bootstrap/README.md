# CLB-195 synthetic namespace bootstrap

Executable local candidate; **NATIVE_PENDING**. Not a stage command, installer,
writer or release operation. CLB-192 native run 37757252333 / attempt 1 /
9234bacd421c6ac87dfd92b5c0c73a838f0660ca remains valid for its unchanged sources.
It does not establish acceptance of this new integration.

`build.py` verifies the existing native-adapter source/reference pins and makes a
temporary test variant. Only its `namespace_child` body and embedded bootstrap/
request arrays change. No original adapter, Runtime, planner, CLB-192 source or
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

## One future manual Tests run

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
