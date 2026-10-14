# CLB-91 same-user mount-view candidate — test only

Local source candidate on main `34460528411d377528dd963f0e482ee06a861afe`,
tree `3101ddd39e3258f144ca586b1b95841d4d987a0f`. This is a synthetic kernel
experiment candidate, not a stage launcher, installer or release authority.
Native Linux and stage acceptance are **NOT_RUN**. No Engine reader is included.

## Local checks

From the registered worktree, as an ordinary user:

```sh
python3 -I -S -B scripts/tests/linux-semantic/b-launch/test-prototype.py
python3 -I -S -B scripts/tests/linux-semantic/b-launch/test-native-harness.py
python3 -I -S -B scripts/tests/linux-semantic/b-launch/test-workflow.py
ruby scripts/validate-workflow-yaml.rb
ruby scripts/validate-workflow-capabilities.rb
git diff --check
```

The suite uses adversarial injected observations for Linux-only operations and
real unprivileged file descriptors, no-follow opens, inode replacement, flock,
fork, signals, bounded waits and child reaping on the current OS. It never calls
unshare, mount, credential setters or prctl. Mocked credential setters and libc
are explicitly synthetic. `SyntheticRootHold` executes real acquisition and
partial cleanup against its own disposable FD tree, injecting only the root
path and expected root ownership. It creates no user namespace or UID mapping.
Several non-1000 principals are modeled; no actual
stage principal is attested. Darwin flock/fork results are not Linux acceptance.

`prototype.py` has no native CLI entrypoint: executing it returns a fixed
NOT_RUN message and exit 1. `native_trial` is a dormant function for a future
reviewed, separately authorized disposable native harness. It returns only
`NATIVE_TRIAL_COMPLETED_NOT_ACCEPTED`, never compatibility or native acceptance.
It is not safe to run merely because the caller is root. The future harness must
independently establish the disposable host, exact source, Python/libc/bootstrap
and fixture identities before any root execution. This task grants none of that
future execution authority. A dedicated unpublished manual workflow candidate now
invokes the fixed harness; no published workflow has run it.

## Fixed topology and source boundary

One forked child unshares **only CLONE_NEWNS** and makes all propagation private
before a bind. It keeps the original user, PID and network namespaces and `/`.
There is no chroot, UID mapping, setns, shell, external exec, network or Docker connection.
Only `/opt/clb91-b-launch-fixture/view` may be overmounted. Its source is a held
read-only descriptor to the sibling `source` directory, not a caller pathname.
The mount destination is reopened and checked inside the child mount namespace.
Application, locks and all canonical ancestors remain outside this projection.
Submounts in the runtime source/target are refused. The bind is read-only,
nosuid/nodev; source file identities, root ownership and modes are held/rechecked.

The future harness must create this entire **synthetic**, exclusively owned
fixture (refuse pre-existing objects; no automatic cleanup of unknown paths):

| Object under fixed fixture | Owner/group | Mode | Contents |
| --- | --- | --- | --- |
| fixture directory; canonical `/opt` | root/root | 0755 | No substituted ancestors |
| `source`, `view` | root/root | 0555 | Source has only `runtime`; view initially empty |
| `source/runtime` | root/root | 0444 | Exactly `CLB91-SYNTHETIC-RUNTIME` plus newline |
| `application` | captured synthetic principal UID/GID | 0700 | Two empty locks |
| `application/application-lock`, `application/operation-lock` | same principal UID/GID | 0600 | Empty, regular, nlink=1 |

The source is deliberately one small synthetic closure. The executing Python and
libc remain the independently checked test-host runtime. This does not install,
project, or verify the production 196-leaf Runtime; does not prove its interpreter
maps, executable startup or full filesystem view; and cannot establish B
compatibility. Extending to that actual closure requires separate acceptance.

The native harness tests two explicit **synthetic** principals: UID/GID/groups
24001/25001/(25001,26001) and 24002/25002/(25002,26002). It requires those IDs
to be unused by host accounts and processes before setup. Each worker receives
exactly its selected UID/GID/groups; no NSS account, user mapping, fixed UID1000
or sudo environment supplies them. This tests the credential mechanism, not the
real deployment principal. Stage principal preservation remains unverified.
No sudoers/setuid/capability installation is provided.

The root supervisor requires **CAP_KILL** to terminate its different-UID child.
Setup requires **CAP_SYS_ADMIN, CAP_SETUID, CAP_SETGID, CAP_SETPCAP** in the
original user namespace. Before worker handoff, the child clears ambient and
bounding sets, disables KEEPCAPS, sets all real/effective/saved GIDs and UIDs,
zeros effective/permitted/inheritable sets, sets irreversible NO_NEW_PRIVS, and
verifies all five sets are zero. It rearms PDEATHSIG after credential changes.
All three standard streams are checked `/dev/null`, including stdin. Only
checked read-only directory/source anchors and same-principal lock FDs remain;
unrelated descriptors are closed. Same-UID proc FD access is restored
explicitly with dumpability=1 after privilege drop. This is part of the test
contract, not proof of acceptable live privacy/LSM policy.

The worker is a fixed in-process function with no fork/exec. The supervisor
owns/reaps exactly that child, never signals a PID after reap, handles recorded
signals/cancellation, and bounds execution plus cleanup waits. It is not a
general descendant sandbox. A kill/reap failure is refusal with cleanup
unconfirmed: the future harness must quarantine and tear down the disposable
environment, never emit native PASS. Namespace mounts are destroyed by process
exit only if no surviving process or namespace handle retains them; native
acceptance must verify this independently. No host unmount fallback exists.

## Fixed entry, source identity and public protocol

`native-entry.py` is the sole entrypoint. `native-sources.json` binds exactly the
four executable sources (entry, protocol, harness, prototype) to the base/tree
above. Before a future run, an independent reviewer must verify the **outer**
manifest digest and published source revision. There is no self-approval mode.
The entrypoint itself and Python bootstrap must be verified before invocation;
checking its own bytes after startup cannot establish that initial trust.

Source bytes are captured through no-follow bounded FDs, with owner/mode/nlink,
short-read, content and object rechecks, then compiled from those captured
buffers. The native source directory is fixed to `/opt/clb91-b-native-sources`,
root-owned with protected ancestors and no group/world writes. No source path,
program, mount target, principal or control selector is accepted from argv.
A separate read-only invocation without the final execution flag reports
`NOT_RUN`, exit 2. This flag and GitHub environment variables **are not authority**.

Only the following invocation shape is prepared, for a separately authorized
future disposable Linux run (do not execute during local preparation):

```text
<independently verified Python> -I -S -B /opt/clb91-b-native-sources/native-entry.py
  --source-contract <independently approved public manifest SHA256>
  --revision <exact published Git SHA> --execute-disposable-native
```

`native-protocol.py` accepts one JSON record <=4096 bytes, with exact fields,
repository/workflow/revision/public-source identity, run ID/attempt, prerequisite
verdicts, individual control verdicts, cleanup and aggregate PASS/FAIL/NOT_RUN.
Exit codes are 0/1/2 respectively. Consumers must use `parse` and check exit
consistency; duplicate/missing/unknown fields, controls, identities or statuses
are refusal. A fixed entry-level failure is intentionally not valid complete
native evidence. Missing prerequisites leave dependent controls `NOT_RUN`.
The ordered ledger cannot replace FAIL, duplicate a result or finalize early.
No observation digest, raw paths, credentials, environment, mount tables, FD
identities, exception text or child stderr belongs in public output/artifacts.
Only public **source** hashes are allowed, never hashes of observations.

`native-harness.py` retains synthetic observations in bounded anonymous shared
memory, nonce/sequence-bound and acknowledged; no socket or filesystem evidence
store exists. A worker has null stdin/stdout/stderr. After confirmed reap the
mapping is zeroed/closed and only exact owned fixture objects are removed;
unknown or substituted objects are never recursively removed. Unconfirmed child
cleanup leaves the fixture quarantined and blocks PASS. An early parent-death
failure before its worker pidfd is acquired can remain UNCONFIRMED: discard the
VM rather than reporting cleanup/native success. The subreaper setting is
process-local and restored; no new PID namespace is used. Exact pidfds avoid
signaling a reused orphan PID.

## Read-only prerequisites before privileged setup

The future harness refuses unless all of the following are established:

- Native Linux x86_64/ELF64, isolated Python without site/bytecode writes; no
  known container or QEMU marker, initial UID map, self and PID1 user/PID/network
  namespace identities, single thread and no initial child processes.
- Exact approved repository, Tests workflow, manual event, revision, run ID and
  attempt 1. Independent provider/run/source verification is still necessary:
  inherited environment and ELF checks cannot prove absence of emulation or
  authenticate a VM/runtime supplier. Docker Desktop, QEMU and Rosetta are not
  acceptance environments.
- Already available CAP_KILL, SETGID, SETUID, SETPCAP, SYS_ADMIN plus CHOWN,
  DAC_OVERRIDE and FOWNER for exclusively owned synthetic fixture creation and
  removal. No capability, sudo, setuid or sysctl grant is installed by the harness.
- Protected root-owned Python/modules/cache/loaded mappings and ancestors,
  independently approved bootstrap/library provenance, usable procfs/fdinfo,
  mountinfo, pidfds, permitted `/opt` filesystem and unused fixed fixture/IDs.
- Accepted known LSM inventory including Landlock, unconfined process context,
  seccomp mode 0 and Landlock ABI >=1. Unknown/unavailable policy fails closed;
  no securityfs mount, policy edit or fallback is attempted. Fixed per-child
  Landlock read denial and seccomp-unshare denial are negative tests, not host
  policy changes. Piped core collectors are refused; core limits are set to zero
  before fixture setup. An unsuitable hosted runner may therefore refuse.

The synthetic mount is still only one leaf, not an installed 196-leaf runtime.
No Docker socket, Engine request, release record or application data is used.

## Prepared native harness — execution NOT_RUN

Use disposable native Linux x86_64, preferably ubuntu-24.04, with independently
checked source/bootstrap/libc and trusted procfs. Require fork, unshare(NEWNS),
mount(private-recursive/bind/remount), prctl including ambient/bounding/NNP and
PDEATHSIG, capset v3, setgroups/setresgid/setresuid, flock and proc FD/mountinfo
semantics. LSM/seccomp denial is a failed acceptance, not permission to relax
AppArmor/seccomp, widen grants, change user namespaces or use a fallback.

| Control | Prepared real-kernel measurements (none executed locally) |
| --- | --- |
| A/H: parent unchanged | Retain parent app FD, root identity and mount table before fork; compare them with child held anchors during projection and after reap. No parent mount additions or propagation. |
| B/C: isolated root-owned closure | Child mount namespace differs; user/PID/network namespaces match. Only fixed runtime target is added; root UID/GID and original `/` match. Test writes fail even with setup root before drop. |
| D/E: principal and capabilities | Two explicit synthetic principals, with supplementary groups. Observe real/effective/saved/fs credentials, all capability sets, NNP, KEEPCAPS and PDEATHSIG after handoff. Actual attempts to regain root/capabilities fail in disposable test child. |
| F/G/L: backing, roots, locks | Compare fresh child path opens with retained anchors and current mount-id membership, filesystem/source/root/target tuples and devices/inodes. Independent peer sees actual lock contention/unlock. Same pathname with different object/backing refuses. No application/root mount is added. |
| I: FD/proc semantics | A same-principal peer dereferences worker `/proc/PID/fd` and inherited FDs; no setup/writable foreign FD is present. Check actual procfs/ptrace/LSM policy and dumpability. Root supervisor FD access is not substituted for worker access. |
| J: lifecycle | Exercise seccomp-denied setup, partial acquisition, worker failure, armed-worker cancellation/timeout, real EBADF cleanup failure and supervisor death after credential drop. Reap worker/peer, prove lock release after positive controls and unchanged parent mounts; unconfirmed cleanup cannot pass. Busy-lock and pre-spawn cancellation also have portable controls; these do not add native results. |
| K: unsupported host | Drop each required setup/supervisor capability in isolated tests; exercise syscall/LSM refusal without changing host policy. No worker runs after refusal. |
| Privacy/source | Source substitution, symlinks, wrong owner/group/mode, hardlinks, oversized inputs and drift refuse. Only bounded status/control categories and public Git/source identities leave the harness; no raw tables, identities, paths, stdout/stderr, hashes of observations or private data. |

Native PASS requires all 24 controls, zero skips, exact captured source buffers,
confirmed cleanup, and exact commit/run/attempt/host/source provenance. A completed trial
alone is insufficient. Failures remain failures; unsupported/unknown input and
missing evidence cannot be promoted to PASS.

## Dedicated manual workflow candidate — NOT_DISPATCHED

[CLB-91 B Native Acceptance](../../../../.github/workflows/clb91-b-native-acceptance.yml)
has exactly one `workflow_dispatch` job on a GitHub-hosted `ubuntu-24.04` x64 VM,
`contents: read`, no Environment, Secrets, OIDC, service/container or credentials.
Only repository `koteev-m/clubs_bot`, `main`, owner `koteev-m`, initial attempt 1
and the explicitly approved exact revision can proceed. Checkout is SHA-bound,
with persisted credentials disabled. Existing Tests and deployment workflows
are unchanged; their extra jobs/downloads/image builds do not run here.

**Future independent source approval is not configured.** Before any future
dispatch, separately authorize provision of public repository variable
`CLB91_B_NATIVE_APPROVAL`, containing exactly:

```json
{
  "format": 1,
  "repository": "koteev-m/clubs_bot",
  "workflow": ".github/workflows/clb91-b-native-acceptance.yml",
  "revision": "<future independently reviewed published 40-hex commit>",
  "closure_sha256": "<public workflow-sources.json SHA256>",
  "native_sha256": "<public native-sources.json SHA256>",
  "run_number": "<one separately authorized next workflow run number>",
  "expires_at": 0
}
```

This is a schema illustration, **not executable approval**. Expiry must be an
integer UTC epoch in the next hour, with trusted clock checks before native
handoff. No actual approval record, future revision, variable or secret was
provisioned. The base/tree in `native-sources.json` records the historical
construction baseline, not authority for these unpublished bytes. A new main
commit, missing/expired record, changed source, wrong run number or attempt
refuses; no auto-adoption, retry or unsafe fallback exists. Run numbers belong to
the workflow, not global repository numbering. No release FSM/claim is involved.

`workflow-sources.json` pins the seven executable/manifest/workflow inputs.
`workflow-driver.py` verifies that closure before and after execution. The
manifest cannot approve itself: its exact digest and future commit require
independent review and separately controlled public configuration. Keep this
variable limited to public source/run metadata; never place credentials or
private evidence in it. Repository settings write authority remains an external
trust assumption, and requires separate authorization.

The driver calls sudo once, with a **fixed reviewed Python bootstrap**, exact
approved driver hash and bounded public context. No caller program, source
path, shell fragment, mount target or private bytes reach sudo. The bootstrap
captures the one fixed driver through no-follow FD, rechecks identity and hash,
then executes that captured approved buffer. The root driver revalidates the
entire source closure and read-only native prerequisites before creating the
exclusive protected source copy and synthetic fixture. It removes only its
owned source objects and does not release native PASS before that cleanup.
The native operation still performs the same 24 controls. Partial/unknown
cleanup blocks acceptance and requires discarding the disposable VM.

**This is a source-restricted workflow, not an OS-enforced sudo mediation
boundary.** Hosted-runner passwordless sudo, initial Python/sudo/loader supplier
and protected host image remain separately accepted prerequisites. No new sudo
rule, grant, setuid binary, capability, package or host policy is installed.
ELF/ownership/environment checks cannot independently authenticate that initial
bootstrap or prove absence of emulation. An unsuitable capability/LSM/seccomp,
procfs/backing context must refuse, with no repair/fallback. Root's synthetic
worker receives only validated public execution context, never inherited tokens
or Secrets. The real stage helper environment is not changed or simulated.

Operation budget remains 120 seconds, capture deadline 150 seconds with bounded
termination/reap, and job timeout 10 minutes including checkout/evidence. One
VM, one experiment, no retries. A lost root descendant cannot be assumed reaped
by the unprivileged wrapper: no artifact/PASS is accepted, and external VM
teardown is mandatory. Privileged setup/actual sudo signal behavior remains
NOT_RUN, to be checked in the separately authorized native execution.

Unique concurrency group `clb91-b-native-acceptance-single-v1` uses
`cancel-in-progress: false`; it does not interact with Tests/release/stage.
GitHub's default allows one running and one pending run, and a newer queued run
can replace an older pending run even with this setting. Before dispatch,
independently check for duplicate/pending/conflicting native experiments; do not
treat concurrency as one-shot authorization. See [official concurrency rules](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency).

Only strict protocol-validated JSON <=4096 bytes is eligible for the fixed
artifact, retention 3 days. Upload uses a validated output marker and is disabled
on cancellation; no wildcard paths/raw stderr/environment/fixture are uploaded.
Failure remains job failure even if a valid FAIL diagnostic is uploaded. A
skipped/green job without the exact validated native PASS record proves nothing.
Cancellation during final validation/publication refuses and rolls back owned
partial evidence. File-command layout is checked against [Actions runner
v2.328.0](https://github.com/actions/runner/blob/v2.328.0/src/Runner.Worker/FileCommandManager.cs#L29);
unknown layouts refuse. Publication, public approval provisioning, runner/image
and root test acceptance, exactly one future dispatch plus Git checkout/artifact
network traffic and Actions cost all require separate permissions.

References only: [existing Tests workflow](../../../../.github/workflows/tests.yml),
[native adapter](../native-adapter/README.md),
[namespace bootstrap](../namespace-bootstrap/README.md). Their different
chroot/PID/network/principal contracts grant no authority for this candidate.

Linux syscall references: [unshare](https://man7.org/linux/man-pages/man2/unshare.2.html),
[mount namespaces](https://man7.org/linux/man-pages/man7/mount_namespaces.7.html),
[capabilities](https://man7.org/linux/man-pages/man7/capabilities.7.html),
[dumpability](https://man7.org/linux/man-pages/man2/PR_SET_DUMPABLE.2const.html),
[proc FD access](https://man7.org/linux/man-pages/man5/proc_pid_fd.5.html),
[subreaper](https://man7.org/linux/man-pages/man2/PR_SET_CHILD_SUBREAPER.2const.html),
[Landlock](https://docs.kernel.org/userspace-api/landlock.html),
[seccomp](https://man7.org/linux/man-pages/man2/seccomp.2.html).

No stage privileges, launch authority, deployment, paired Engine evidence or
recovery authorization is created. STAGE_TRUST=UNVERIFIED;
TRANSACTION_READINESS=NO.
