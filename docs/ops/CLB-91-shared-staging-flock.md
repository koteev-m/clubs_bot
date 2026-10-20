# CLB-91 — simple shared staging flock (locally verified)

Verdict: **CLB_SHARED_STAGING_FLOCK_LOCAL_VERIFIED** (2026-10-20).
This verdict covers the local implementation and disposable Linux/Docker tests.

Current model: GitHub CI verifies an immutable image, then SSH, Docker Compose,
health/smoke. HDC/1, HDC/2, external Authority/CAS/custody are not prerequisites
for this staging model. Historical experiments, incidents and their statuses are
retained; none is promoted to acceptance by this change.

Local source: branch `codex/clb91-staging-shared-flock`, worktree
`/private/tmp/clb91-staging-shared-flock`, base and freshly observed remote main
`b35409bfbee5a6eca8af38253d2da2f504bea940`. Identity preflight:
`PROJECT_IDENTITY_OK`; active foreign instructions=0. Six unpublished files in
`clb-miniapp-main-smoke/clubs_bot` are outside this change and preserved by hash.
No commit, push, PR, Actions dispatch, server access, restore or deployment.

## Candidate file inventory

- Deployment: new [shared_staging_flock.py](../../scripts/deploy/shared_staging_flock.py)
  owns the lock/session; [quiesced-release.sh](../../scripts/deploy/quiesced-release.sh)
  uses its persistent transport; [remote-compose-release.sh](../../scripts/deploy/remote-compose-release.sh)
  enforces inherited ownership; [first-install.py](../../scripts/deploy/aeza-initial/first-install.py)
  locks individual phases and avoids automatic mutation after UNKNOWN;
  [corrected-stage-release.py](../../scripts/deploy/corrected-stage-release.py)
  changes only three current-helper byte pins.
- Tests: new [process/session suite](../../scripts/tests/test_shared_staging_flock.py)
  and [PG16 neighbor rehearsal](../../scripts/tests/rehearse_shared_staging_flock.py);
  affected [first-install tests](../../scripts/tests/test_aeza_first_install.py),
  [release FSM/runner fixtures](../../scripts/tests/test_quiesced_release_state.py),
  and [historical corrected transport fixtures](../../scripts/tests/test_corrected_stage_release.py).
- Gates: [quiesced structure](../../scripts/validate-quiesced-deployment.sh),
  [credential/capability policy](../../scripts/validate-workflow-capabilities.rb),
  and affected fixture closure in [selfcheck](../../scripts/selfcheck-quality-gates.sh).
  Previous security/credential assertions remain mandatory on Linux.
- Documentation: this runbook, [initial-install procedure](CLB-91-aeza-first-install.md),
  [release runbook](release-rollback.md), and [current checkpoint](PROJECT_STATUS.md).

## Operator prerequisites (not provisioned here)

Every cooperating participant uses exactly
`/run/lock/shared-staging-docker.lock` on the **same Linux server**. The operator
must provision a regular, root-owned, single-link file with mode `0660`, no
extended ACL, accessible to both deployment principals through an approved
group. Extended attributes are refused by this candidate. The file must already exist; scripts never create, chown or chmod it.
All parent directories must be root-owned non-symlink directories and must not
be group/world writable unless sticky (the usual root-owned sticky `/run/lock`
is supported). Never unlink, rotate or replace the file while participants may
be active. No lock pathname, unlocked mode or permission override is exposed by
the production CLI or environment. Test code alone substitutes isolated paths.

The Python gate opens with `O_NOFOLLOW`, checks inode/device, type, owner, mode,
link count and xattrs, then uses `LOCK_EX | LOCK_NB`. It verifies the held lock
on that open file description through Linux `/proc/self/fdinfo`, and repeats
identity checks during execution and before/after each operation. File existence
and PID text are not lock evidence. The shell helper verifies inherited FD 19;
it does not reacquire flock. FD 19 is separate from the existing project locks.
Ordering is **shared host flock → existing Clubs application/operation locks**.
The same mutating helper serves stage/prod and requires this gate in either mode;
operator provisioning is required on any host using that entrypoint. The regular
`status` and historical `corrected-start ... inspect|reconcile` paths remain read-only.

Trusted reviewed code, Docker context/Engine, helper upload and operator access
are still prerequisites. This is ordinary operator code, not a privilege or
malicious-code confinement mechanism. No new accounts, privileges, ACLs, daemon
configuration or Caddy/DNS/TLS changes are supplied.

## Ordinary release lifetime

Previously each of upload, preflight, prepare, publish, quiesce, migrate, start,
cleanup and helper-cleanup used independent transports. Now the CI runner starts
one bounded SSH process running the reviewed stdlib-only Python session. That
server process acquires the host lock **before helper upload**, acknowledges
readiness, and retains one open file description until the final helper-cleanup
reply and session exit. SCP still uploads the existing self-contained helper
under that held lock. The Python session code is passed as a quoted SSH command;
it is not installed as a daemon or retained coordinator.

The existing runner sends one request, consumes its result and only then sends
the next request. The server accepts only the existing ordinary order once:
preflight → prepare → publish → quiesce → migrate → start → cleanup → helper-cleanup.
It invokes the unchanged FSM operations as child processes with inherited FD 19.
There is no unlock between quiesce and migrate, nor between subsequent phases.
There is no nested acquisition, independent operation host lock, request replay,
new release state machine or automatic recovery command. Bash 4+ and Linux
procfs are required on the CI client; Python 3 and Linux procfs are required on
the server. The current Ubuntu workflows satisfy the client platform contract.

Limits: nonblocking lock admission, 20-second helper-upload limit (inside the
30-second idle/request-read budget),
300 seconds per child operation, 900 seconds total session, five seconds for
child reap, at most 1 MiB per output stream. SSH keepalive is 10 seconds with
three missed responses. Requests and replies are bounded JSON over the same
SSH stdin/stdout. Credentials appear only in preflight stdin; raw child output
continues through the existing private capture/redaction checks.

Direct mutating helper calls without the **actual held** inherited descriptor
refuse before protocol-root creation, intent, project locks or Docker activity.
Old incident recovery/abort/resume transports are not upgraded or authorized;
they require separately reviewed authorization and lock ownership before use.
Only their current-helper byte identity constants are refreshed to keep the
existing structural consistency check truthful. No run authorization, external
approval, native policy, historical binding or incident state is changed.

## Initial install and restore boundary

Keep the existing `check`, `database`, `application`, `verify` commands. Distribute
`aeza-initial/` with its reviewed sibling `shared_staging_flock.py` in the same
bundle layout. Each CLI invocation acquires its own host lock before constructing
the phase and keeps it through final observations/markers. Commands inherit the
open file description; the parent checks it before/after execution. `check` and
`verify` remain read-only for project state, but are coordinated too, including
health-probe `docker exec`.

After a confirmed successful `database` phase the process exits and releases its
lock. Manual database preparation/restore waiting holds **no lock**. A separately
authorized procedure must acquire this same host flock for every Docker-mutating
part (including Docker exec that writes/restores PostgreSQL); it must also
coordinate DB writers and schema access. No restore, pg_restore, repair,
migration, retry, rollback or external restore workflow is added here.

`application` independently reacquires the lock, retains the exact image,
PostgreSQL identity/ownership, validate-only Flyway and baseline checks, and
releases after confirmed phase completion. Failed attempt markers remain.
A positively observed unhealthy baseline retains the prior targeted-stop
behavior. A nonzero Docker command alone cannot establish daemon completion. Interruption, timeout, nonzero Docker exit, output overflow, unconfirmed process cleanup or
lock identity loss is UNKNOWN: no automatic Docker stop/repair is attempted.
No old release state is adopted and no previous app is fabricated.

## Failure and reconciliation

| Boundary | Behavior |
| --- | --- |
| Missing/untrusted lock or unsupported platform | Refuse before Docker or project mutation; no fallback. |
| BUSY | Nonblocking refusal before upload, quiescence, intent or migration. No retry. |
| Disconnect after admission, before a request | End session without project effects. Establish state read-only before a new attempt. |
| EOF/signal/timeout during a child operation | Stop/reap its owned CLI process group, retain durable FSM/attempt evidence, report ambiguity. No next request. |
| Lost ACK | Existing one-shot read-only status classification remains. Success evidence means ACK lost, not permission to continue. |
| Incomplete migration | Existing durable `incomplete_unknown`/migration ledger gates remain; Flyway is not rerun. |
| SIGKILL/host or Docker daemon loss | No claim of daemon cancellation. Inherited descriptors can retain flock while descendants live. After their exit, lock release still proves no Docker outcome. |

Unknown state requires separately authorized read-only reconciliation of exact
owned containers, migration ledger/history, baseline and daemon activity before
any new host mutation. The runner never automatically retries, aborts, resumes,
rolls back, cleans up or resets an ambiguous release. Availability of an explicit
recovery option in the old status protocol is not authorization to execute it.

[Linux flock semantics](https://man7.org/linux/man-pages/man2/flock.2.html) bind the
lock to an open file description; inherited descriptors share ownership until
all copies close. Advisory flock cannot fence noncooperating actors or cancel an
already submitted Docker daemon operation. SIGKILL cannot run Python cleanup,
and an escaped process group is outside this mechanism. Both projects' operators
must pause mutations after ambiguity until reconciliation. This change does not
claim crash-proof cross-project fencing or server readiness.
Forced process-group termination can also leave existing private helper or
temporary credential files because shell traps cannot run after SIGKILL.
Normal success/failure credential cleanup remains tested; interrupted private
artifacts require separate reconciliation, with no automatic remote cleanup.

## Independence and local evidence

Clubs stays `clubs-bot-stage`, application `127.0.0.1:8081`, PostgreSQL 16 with its
own volume/networks. The neighboring application contract remains a separate
project/database and `127.0.0.1:8080`. Host Caddy belongs to the system operator.
No foreign compose down, container/volume/database mutation, global prune or
common daemon configuration is allowed. The Hookah repository and actual runtime
were not read or exercised.

New tests: [Linux flock/session tests](../../scripts/tests/test_shared_staging_flock.py)
and [opt-in synthetic PG16 rehearsal](../../scripts/tests/rehearse_shared_staging_flock.py).
The latter uses two unique disposable Compose projects, project and invocation
labels, no published ports or host data mounts, internal networks, independent
PG16 volumes and persisted sentinels. It verifies exact before/after container,
network and volume identities and removes only positively identified owned
resources. Passing this test does not establish actual Hookah compatibility or
real first-install/application readiness.

Linux verification uses disposable Ubuntu 24.04 arm64 containers, Python 3.12.3,
Bash 5.2.21, and the already available local Docker Engine. No images were
published, no daemon was started/reconfigured, and no actual neighboring
application or user database was touched. Tests requiring root-owned fixtures
run as root **inside the disposable container**; strict FSM/status and historical
corrected-stage regressions run as existing unprivileged UID 1000.
Validators requiring tracked-file inventories use a disposable copy with a
synthetic Git index containing the candidate files; the real worktree index is
unchanged. Historical blobs are read from this same Clubs repository through a
read-only object mount, never from another project.

| Local check | Command (inside the Linux fixture unless specified) | Result |
| --- | --- | --- |
| Actual flock, full session, interruption and phase boundaries | `python3 -I -S -B scripts/tests/test_shared_staging_flock.py -v` | 23/23, exit 0 |
| Initial-install invariants/process boundaries | `python3 -I -S -B scripts/tests/test_aeza_first_install.py -v` | 34/34, exit 0 |
| Strict release FSM, migration ledger, UNKNOWN/no replay | `python3 -I -S -B scripts/tests/test_quiesced_release_state.py --strict-ci` | 73/73, no skips, exit 0 |
| SSH runner/ACK/redaction subset (included in FSM total) | `python3 -I -S -B scripts/tests/test_quiesced_release_state.py RunnerClassificationTest` | 12/12, exit 0 |
| Read-only status strict contract | `python3 -I -S -B scripts/tests/test_read_only_release_status.py --strict` | 80 methods / 322 subtests, no skips, exit 0 |
| Historical corrected-stage regressions | `python3 -I -S -B scripts/run-corrected-stage-shard.py <authority\|executor\|import-root\|support>` (each separately) | 10 + 24 + 16 + 72 = 122/122, no skips, each exit 0 after affected recheck |
| Packaged validate-only Flyway launcher | `python3 -I -S -B scripts/tests/test_bootstrap_schema_launcher.py --image sha256:810731421097b8cc8d91a0a2b5d4139a1ea10c8cea3b6081c70c6d70ac53e1a1` (Mac driver, Linux image) | 14/14, exit 0 |
| Real shared-flock/Docker/PG16 neighbor rehearsal | `CLB_FLOCK_DISPOSABLE_DOCKER=1 CLB_FLOCK_RUNNER_TOKEN=<owned-runner-label> python3 -I -S -B scripts/tests/rehearse_shared_staging_flock.py` | 4/4 integration assertions, exit 0 |
| Existing real PG16/validate-only rehearsal | `python3 -I -S -B scripts/tests/rehearse_aeza_first_install.py` (Mac driver, Linux containers) | 9 PASS checks, exit 0; healthy app/FSM baseline NOT_RUN |
| Ordinary release structure | `bash scripts/validate-quiesced-deployment.sh` | PASS, exit 0 |
| Historical helper byte pins/structure | `ruby scripts/validate-corrected-stage-workflow.rb` | PASS, exit 0 |
| Payment structural gate | `python3 -I -S -B scripts/validate-payment-hardening.py --structural .` | PASS, exit 0 |
| Capability policy | `ruby scripts/validate-workflow-capabilities.rb .` | 32 visible workflows, exit 0 |
| Credential runtime and negative policy fixtures | Extracted `validate_temporary_remote_docker_credentials` from `scripts/selfcheck-quality-gates.sh`; isolated credential-policy mutations | 3 runtime cases; baseline + 6 expected refusals; gate exit 0 |
| Syntax | `bash -n` separately for four changed shell files; `ruby -c scripts/validate-workflow-capabilities.rb`; Python `compile` on eight changed Python files | PASS, exit 0 |
| Diff, documentation and preservation | `git diff --check`; candidate whitespace/local-link check; original SHA-256 comparison | PASS, exit 0; 102 local links checked, one unchanged historical missing temporary report; all 6 original files unchanged |

Final host-lock/neighbor report: `postgres-final.json`, invocation
`8d5f59b3ac094089887060e928e785c7`, projects
`clb91-flock-8d5f59b3ac094089` and `clb91-neighbor-8d5f59b3ac094089`.
PG16 image:
`sha256:16bc17c64a573ef34162af9298258d1aec548232985b33ed7b1eac33ba35c229`.
Before/after resource IDs, creation identities and labels match exactly;
independent persisted sentinel values match after restarting only the owned DB.
All resources bearing this invocation's labels are absent after owned cleanup.
The separate existing Flyway rehearsal (`flyway-pg-final.log`,
`clb91-install-8136033f9a72`) used PostgreSQL 16.14 and 60 synthetic history rows:
validation and rejection cases left full normalized dumps unchanged. It created
no ordinary application, ran no migration/restore and removed only its fixtures.
The Docker daemon had no running containers before or after these rehearsals.

Final strict FSM results are in `fsm-current.log` (73/73); its source identities
are in `fsm-current-inputs.txt`. Historical shards are in
`corrected-{authority,import-root,support}-final.log` and the affected
`corrected-executor-recheck.log` (24/24). Earlier executor failure is retained:
outer test-lock serialization broke a project-lock barrier; only that fixture
schedule changed, its original concurrency assertions stayed intact, and the
complete affected shard was rerun. Other shards retain unchanged behavior.

Historical corrected-stage tests keep original project-lock/claim assertions.
Their synthetic transport supplies an actual fixture-held descriptor after the
original source/hash verification. For tests intentionally racing project locks,
each transport uses a separate test flock so outer host serialization cannot hide
the original project concurrency invariants. These fixtures do **not** demonstrate
shared-host exclusion; the new process/session suite does. No production recovery
transport was upgraded to acquire or pass the host lock.

Full Kotlin/Gradle, detekt and payment-runtime suites are NOT_RUN for this
shell/Python/test/documentation change: no Kotlin, payment logic, schema or
migration bytes changed. Full repository selfcheck is not claimed; the affected
structural and credential gates above were run. Darwin Bash 3.2 cannot parse the
new Linux/Bash 4+ runner; its syntax result is not production evidence. Linux
syntax and runtime tests are the applicable checks.

Detailed local logs and source hashes live in
`/private/tmp/clb91-flock-evidence/`. Earlier failing attempts remain there as
history. Corrected verification-environment issues included missing historical
Git blobs in a disposable checkout (fixed by a read-only Clubs object mount),
unsupported Darwin shell semantics, unsuitable fixture UID/filesystem/temp-root
choices, and an old synthetic SSH adapter missing the new held descriptor.
No production gate or old concurrency/credential assertion was removed.
The implementation's initial coprocess-descriptor handoff failed a real runner
test because Bash retained CLOEXEC; reopening the owned pipes through procfs
fixed it. Read-only reconcile admission and the old structural byte/sequence
expectations were also aligned with the new transport. Final process, FSM,
status and structural results above supersede those development failures.

Focused self-review (not independent): no unresolved P0/P1/P2 defect in this
local diff. Checked admission before effects, host-before-project ordering,
continuous ordinary-release ownership, direct-entrypoint refusal, bounded
transport/process cleanup, durable UNKNOWN/no replay, credential redaction and
owned-only fixture cleanup. Advisory/crash limitations and interrupted private
artifacts remain explicit operational risks, not fencing guarantees.

Server/native host acceptance, real restored schema, qualifying immutable app,
real application baseline, ingress and a first ordinary update remain separate
acceptance boundaries. `AEZA_REINSTALL=NO_GO`; `STAGE_TRUST=UNVERIFIED`;
`TRANSACTION_READINESS=NO`.

One next step: review the local CLB-91 diff. Publication and server operations
require separate authorization.
