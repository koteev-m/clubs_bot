# CLB-91 same-user mount-view candidate — test only

Historical construction base: `34460528411d377528dd963f0e482ee06a861afe`,
tree `3101ddd39e3258f144ca586b1b95841d4d987a0f`. The first workflow candidate
was merged at `95b1fbdb50e1fbd5ca84206ebcd096d49664a44c`; the approval-v2
changes below are local, unpublished bytes and need a new independent source
approval after publication. This is a synthetic kernel
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
future execution authority. The dedicated manual workflow invokes the fixed harness only after its external
protection/approval contract. Local tests never dispatch it or establish that
those GitHub protections exist.

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
`contents: read`, no Secrets, OIDC, service/container, stage credentials or new
release authority. Only repository `koteev-m/clubs_bot`, `main`, actor and
triggering actor `koteev-m`, initial attempt 1 and an explicitly approved exact
revision can proceed. Checkout is SHA-bound, with persisted credentials disabled.
Existing Tests and deployment workflows remain unchanged.

### Protected Environment and external acceptance — NOT_PROVISIONED

The **whole native job**, including checkout and driver startup, references only
`clb91-b-native-test`, with `deployment: false`. This uses Environment protection
without creating a GitHub deployment object. Required reviewers and branch rules
still apply; custom deployment protection apps are incompatible with this mode.
See [GitHub's deployment-false contract](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/control-deployments#using-environments-without-deployments).

The workflow declaration does **not** prove that the Environment exists or is
protected. GitHub can implicitly create an unprotected Environment. Before any
future dispatch, a separately authorized operator must independently verify and
retain non-secret configuration evidence for this exact repository:

- the exact existing Environment identity/name, not `stage` or `prod`;
- required reviewers enabled, with an explicitly chosen eligible reviewer other
  than the dispatch actor; `prevent_self_review: true` and admin bypass disabled;
- custom branch policy permitting only branch `main`, with no tag or additional
  branch policy; no custom deployment-protection apps;
- no Environment Secrets/credentials for this synthetic test;
- the v2 approval exists only as an **Environment-scoped public variable**;
  no same-name repository/organization variable or workflow/job-level binding;
- no old pending/in-progress native run, and no competing coordinator/dispatch.

Reviewer identity/eligibility, plan support, protection rules, variable scope and
post-dispatch variable delivery need independent live GitHub configuration
acceptance. This task has not inspected or configured them. If any is absent,
unknown, changed or unsupported, the operator **must not dispatch or approve**.
An Environment variable is not cryptographic evidence of its origin; the driver
cannot authenticate settings, reviewer or variable scope from process environment.
Those are explicit external trust prerequisites, not synthetic test PASS.

The approval is bound at the **driver step**, after Environment release, using
`${{ vars.CLB91_B_NATIVE_APPROVAL_V2 }}`. It is not evaluated as a workflow/job
`env` value or job `if` condition. GitHub documents environment configuration
variables as available after the environment is declared by the runner; verify
that an update made while this run is Waiting is delivered after review. Stale,
missing or wrong-run data causes refusal before sudo, never a fallback or rerun.
See [variable timing](https://docs.github.com/en/actions/reference/workflows-and-actions/variables#configuration-variable-precedence)
and [vars context](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#vars-context).

Future configuration permissions are separate from job permissions. Using
existing human GitHub access (no new PAT/App/service), Environment/protection
creation or updates require repository admin authority (REST `Administration:
write`); public Environment variable creation/update/deletion uses `Environments:
write`. Dispatch/cancellation uses operator `Actions: write`. A chosen eligible
required reviewer releases only the selected job; the corresponding REST review
API requires `Deployments: write`. None of these write permissions is added to
the workflow token, which remains `contents: read`, and none is granted here.
See [Environment configuration API](https://docs.github.com/en/rest/deployments/environments#create-or-update-an-environment),
[Environment variable API](https://docs.github.com/en/rest/actions/variables#create-an-environment-variable)
and [review API](https://docs.github.com/en/rest/actions/workflow-runs#review-pending-deployments-for-a-workflow-run).

### Post-dispatch approval v2 — no v1 fallback

The separate public Environment variable `CLB91_B_NATIVE_APPROVAL_V2` must contain
exactly the following schema (illustration only, **not executable approval**):

```json
{
  "format": 2,
  "repository": "koteev-m/clubs_bot",
  "workflow": ".github/workflows/clb91-b-native-acceptance.yml",
  "revision": "<independently approved published 40-hex commit>",
  "closure_sha256": "<approved workflow-sources.json SHA256>",
  "native_sha256": "<approved native-sources.json SHA256>",
  "run_id": "<actual selected workflow run ID after dispatch>",
  "run_number": "<actual selected workflow run number after dispatch>",
  "attempt": 1,
  "expires_at": 0
}
```

Both run identities are canonical nonzero decimal **strings**, at most 20 digits;
`format`, `attempt` and UTC epoch `expires_at` are integers, never booleans.
Expiry must satisfy `now < expires_at <= now+3600` at each authorization check.
It is an admission deadline, not a kill timer for an already admitted worker;
the separate native operation budget still applies.
All fields are mandatory; duplicate/extra fields, v1, wrong types, changed
source/revision/run/attempt, missing or expired approval refuse. The old
`CLB91_B_NATIVE_APPROVAL` repository variable is never read. A new main or a
replacement run requires new independent approval; there is no auto-adoption.

`workflow-sources.json` pins the seven executable/manifest/workflow inputs.
`workflow-driver.py` verifies that closure before and after execution. The native
manifest's historical base/tree do not approve the current sources. The manifest
cannot approve itself: both exact digests and the future published commit need
independent review. No sensitive data belongs in the public approval. Settings
write authority, GitHub run identity/attempt uniqueness and protection enforcement
remain external trust assumptions. This adds no release FSM or durable claim.

### Future one-shot coordinator contract — NOT_AUTHORIZED

1. Independently approve the exact **published** revision and complete source
   closure, both manifest digests, bootstrap/runner trust and bounded root test.
   The current base is not authority for these unpublished v2 changes.
2. Verify the Environment configuration above, no approval from an older run,
   no pending/running duplicate, and an exclusive coordinator window. Obtain
   separate permission for configuration, one dispatch and one VM root test.
3. Submit **one** dispatch of this exact workflow on `main`, with only
   `approved_revision`; obtain the created run details. A lost/ambiguous response
   permits read-only reconciliation only, never automatic redispatch. Do not
   guess a run number or approve any run until identity is established.
4. Independently retrieve that run and verify repository, numeric workflow ID
   mapped to the exact path, run ID/number, SHA, event, branch, actor,
   triggering actor, attempt 1 and the sole native job's **Waiting** state for
   the exact Environment. Main must still match the approved revision. Any
   duplicate, cancellation, replacement, drift or ambiguity stops this attempt.
5. Provision only the selected run's v2 record as the exact Environment variable
   **after dispatch**, with fresh bounded expiry, and verify its value/scope.
   Do not provision repository/org fallback, Secret, token, PAT or GitHub App.
6. Immediately before release, repeat the run/job, pending/duplicate, source,
   variable and Environment checks. The chosen required reviewer reviews the
   full source/run record and explicitly approves **only that run's** waiting
   job. Approval by itself is not source review; never bypass protection rules.
7. Driver validates the exact v2 record before sudo and at the final pre-sudo
   gate. Root validates it on entry and again before native handoff. One run's
   record cannot authorize a different run, even at the same revision/number.
   Reruns (attempt >1) refuse. A cancellation signal observed before spawn
   prevents sudo; cancellation racing spawn follows bounded supervision/refusal.
8. Verify the selected run's exact source/run-bound native protocol, all 24
   controls and cleanup. Job success, skipped work or synthetic tests do not
   substitute for native PASS. Unknown cleanup requires VM disposal, no retry.
9. Revoke the Environment approval after any terminal/canceled/refused outcome,
   or immediately on uncertainty (plus separately authorized cancellation if
   needed); verify revocation. Revocation is not a kill switch for a worker
   already handed off: it cannot retroactively revoke a captured record.

No different run inherits approval after cancellation or pending replacement:
its run ID fails even if revision matches. Concurrency is serialization, not
reservation or approval. Group `clb91-b-native-acceptance-single-v1` retains
`cancel-in-progress: false`; a new queued run can replace an existing pending
run. If this occurs, abort/revoke rather than approving the replacement. See
[GitHub concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency).
The exact run ID removes the old pre-dispatch next-number race; an exclusive
coordinator window remains necessary for operational coordination and settings.

### Root, supervision and public evidence boundary

The driver calls sudo once with a fixed reviewed Python bootstrap, approved
driver hash and bounded public context. No caller program, source path, shell
fragment, mount target or private bytes reach sudo. The bootstrap captures the
fixed driver through no-follow FD, rechecks identity/hash and executes only the
captured buffer. Root revalidates the source closure and read-only prerequisites
before creating the exclusive source copy and synthetic fixture. It removes
only owned objects and releases no native PASS before source-copy cleanup.

This is a **source-restricted workflow**, not OS-enforced sudo confinement.
Hosted-runner passwordless sudo, initial Python/sudo/loader supplier and host
image remain separately accepted prerequisites. No sudo rule, grant, setuid,
capability, package or host policy is installed. Structural checks cannot
independently authenticate bootstrap provenance or absence of emulation.
Unsupported capability/LSM/seccomp, procfs/backing must refuse without repair.
Only validated public execution context reaches the synthetic worker.

Budgets remain 120 seconds for the native operation, 150 seconds capture with
bounded termination/reap, and 10 minutes job execution after Environment release.
Waiting for review is controlled by GitHub and approval expiry must be set close
to review, not at dispatch. There is one VM/experiment, no retries. An unreaped
root descendant cannot be assumed cleaned by the unprivileged wrapper: no
artifact/PASS is accepted and disposable VM teardown is mandatory.

Only strict protocol-validated JSON <=4096 bytes is eligible for the fixed
artifact with retention 3 days. Upload requires the validated marker and is
disabled on cancellation. No raw stdout/stderr, credentials, environment,
filesystem identities or fixture paths are uploaded/logged. A valid FAIL
artifact does not make the job succeed. Cleanup uncertainty cannot yield PASS.
File-command layout remains checked against
[Actions runner v2.328.0](https://github.com/actions/runner/blob/v2.328.0/src/Runner.Worker/FileCommandManager.cs#L29).

Local tests verify schema/source/workflow/refusal behavior only. Actual GitHub
Environment protection, variable delivery, native kernel acceptance and stage
compatibility remain **NOT_RUN**. Publication, exact Environment configuration,
public approval provisioning/revocation, reviewer release, hosted-runner/root
acceptance, one future dispatch, checkout/artifact network and Actions cost all
require separate authorization. Native PASS would still not prove the actual
196-leaf Runtime, paired A-to-B stage resources or release/recovery readiness.

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
