# Protected stage read-only snapshot

Task: `CLB_STAGE_PROTECTED_READONLY_SNAPSHOT`, 2026-11-12.
Final local correction: **PARTIAL_BLOCKED** (2026-11-13); disposable PostgreSQL,
Linux and pinned secret-scan gates cannot run with the current Docker socket restriction.
Stage release admission remains **BLOCKED**; this is not live evidence.
Base: `aed1e5831ca807f1c8ab2d58dbc952efc899207b` (historical main observation;
the final local correction does not refresh remote state).
Implementation lives in a separate registered detached worktree; the earlier
RBAC worktree is unchanged. No dispatch, server connection or publication occurred.

## Channel choice

New [stage-only workflow](../../.github/workflows/stage-readonly-snapshot.yml),
[runner](../../scripts/deploy/stage-readonly-snapshot.py) and
[collector](../../scripts/deploy/stage-readonly-snapshot-collector.py).
The existing channels cannot supply this snapshot:

| Existing channel | Actual contract | Gap |
| --- | --- | --- |
| `release-status.yml` | Exact incident tag/helper hash/owner/revision/digest; retained FSM status | Not a general runtime/DB/neighbor/backup inventory |
| `stage-runtime-inventory.yml` | Fixed historical CLB-91 confirmation, system artifacts/packages | No app/DB/release admission |
| `stage-compose-diagnostic.yml` | Fixed historical incident and static Compose subset | No current runtime or DB status |
| `deploy-ssh.yml` | Quiesced mutating release | Prohibited for snapshot |

Historical incident workflows and their confirmations remain unchanged.

## Coverage and evidence limits

Only bounded machine metadata is returned. No raw Docker inspect, environment,
Compose configuration, logs, DB URLs, user rows or backup contents are exported.

| Area | Can observe | Does not prove |
| --- | --- | --- |
| Clubs | Exactly two `clubs-bot-stage` app/db services at the fixed application root; hashed container identities; app state/health; local image OCI revision and exact GHCR RepoDigest; loopback 8081 `/health` and `/ready` codes | Compose config equivalence, registry signature/provenance, owner Telegram smoke, full legacy authorization |
| PostgreSQL | Existing `clubs` non-superuser role over local socket; `public.flyway_schema_history` ranks/versions/checksums/success; latest successful numeric version; pending 77/78/79; enabled flags of seven relevant V078/V079 triggers | Full migration validation/rehearsal, migration-in-flight exclusion, other deployment schema, correctness of trigger function bytes |
| Release | Existing canonical shared host flock acquired nonblocking and retained across observations; bounded no-follow FSM artifact/checkpoint and ledger/outcome counts | Authoritative FSM/backing/root binding validation, ledger content/integrity, absence of unfinished operations or uncooperative external writer |
| Neighbor | Bounded inventory of container metadata; count of other containers; hashed candidate with exact loopback 8080 binding; its HTTP health code; named volume/network intersection with Clubs | Hookah identity from a port/label alone, full resource isolation, bind-mount/host resource overlap |
| Recovery | Always `unknown` | Current backup existence, freshness, usability or tested recovery; no authoritative provider exists in this channel |

`stage_ready` is always **false**. `observed_with_unknowns` means metadata capture,
not release admission. A successful workflow never overrides an unknown gate.
Unavailable/unsafe evidence becomes `unknown` or a generic `unavailable` refusal;
there is no SSH/DB identity fallback, repair or automatic retry.

DB schema, project/root and app/db role names follow the existing
[initial-install contract](CLB-91-aeza-first-install.md). A different live layout
is refused; the collector does not discover a convenient DB/user/schema or read
password files. Local socket authentication may fail; no new grants are requested.
The query uses one READ COMMITTED, READ ONLY transaction. A catalog-only
precheck verifies the exact `public` namespace/relation, USAGE/SELECT and exact
non-superuser `clubs` context. Only an ordinary persistent heap table with the
ten canonical Flyway column names/types/typmods is supported. Partitions,
inheritance, views/materialized/foreign tables, generated columns, RLS/policies,
rewrite rules, noninternal triggers and expression/partial/nonbuiltin index
contracts refuse before data access. Heap sequential access is selected, with
index/bitmap/TID/parallel scans disabled. No user table is read.

`LOCK TABLE ONLY public.flyway_schema_history IN ACCESS SHARE MODE` is obtained
only after the safe precheck; metadata and the exact relation/namespace OIDs are then checked again
under that lock. READ COMMITTED avoids retaining a stale catalog snapshot after
waiting for a concurrent DDL commit. The lock is held through the bounded
explicit-column direct `COPY TO STDOUT` and rollback. Replacement/rename/column/rule/RLS enable
DDL is serialized; a changed OID refuses even for a new structurally valid table.
Schema rename is a separate boundary: PostgreSQL 16.11/16.14 do not serialize it
with that relation lock. Before and after locking, the collector refuses a
namespace if any non-superuser role can exercise its owner's privileges and has
CREATE on the current database. This includes inherited ownership, PUBLIC grants
and database ownership, not just the current role's NOCREATEDB attribute.
Such unsupported contexts return UNKNOWN without COPY. This follows the focused
review's conservative fail-closed direction; no stronger table lock is presented
as a namespace lock. Trusted administrative schema/privilege/role changes during
the read remain outside the supported contract; live privilege stability has
not been established by this local candidate.
View-lock name resolution during a replacement race does not evaluate its query,
which is independently tested with a victim session. The contract relies on
trusted PostgreSQL catalogs/builtin access methods; privileged catalog or
extension tampering is outside this diagnostic gate. No SECURITY DEFINER,
new DB privileges, advisory writer protocol or schema mutation is introduced.
[PostgreSQL LOCK semantics](https://www.postgresql.org/docs/16/sql-lock.html)
explain lock lifetime and why REPEATABLE READ before lock would be insufficient.
The version-specific rename implementation is recorded in
[PG16.11](https://github.com/postgres/postgres/blob/REL_16_11/src/backend/commands/schemacmds.c)
and [PG16.14](https://github.com/postgres/postgres/blob/REL_16_14/src/backend/commands/schemacmds.c).

`READ ONLY` alone is **not** side-effect protection: executable SELECT hooks can
call session-control functions. The catalog/type/hook checks, lock and planner-free table read are the
safety boundary. A catalog-confirmed absent canonical relation with a valid
namespace/context yields `not_found`/`canonical_absence`; permission failures,
unsupported types, missing context, timeouts, errors or malformed results yield
`unknown`. `observed` requires the locked canonical heap-table path. No raw SQL
exception, OID, owner identity or private catalog value is exported.

The four data columns use **direct relation** `COPY ... TO STDOUT (FORMAT binary)`,
not a query-backed COPY, data SELECT, file copy, PROGRAM or COPY FROM. SELECT
privileges suffice; READ ONLY remains on. A direct table COPY bypasses query
planner predicate/expression evaluation and reads only that heap (not children).
This matters because ACCESS SHARE does not block CREATE INDEX. A concurrent
immutable index predicate can execute during ordinary SELECT planning, even
with index scans disabled. The PG regression creates that race after the locked
catalog check: COPY preserves the control session; the SELECT negative control
terminates it. A private bounded binary parser checks header/flags/lengths/types,
NULLs, 160-row cap and exact end-of-frame before normal JSON summarization.
No raw COPY bytes reach workflow output.
[PostgreSQL COPY contract](https://www.postgresql.org/docs/16/sql-copy.html)
distinguishes direct table/stdout reads from query or file/program forms.


The initial target being compared remains:
`aed1e5831ca807f1c8ab2d58dbc952efc899207b`, image tag `sha-aed1e58`,
`ghcr.io/koteev-m/clubs_bot/app-bot`, digest
`sha256:72cac72d8c9ffefd927910d8704d0f5b668694e462a2bd7b66b0b0eaeaaf7af3`.
A new workflow commit will be a descendant of that target; target mismatch is
reported rather than silently changing this expected baseline.

## Security and consistency

- Manual only; exact repository, main branch/default branch/workflow identity,
  first attempt and exact confirmation are required. Literal Environment `stage`;
  `contents: read`; no prod/host/path/command input. Exact workflow capability is
  registered in the existing validator; generic SSH capabilities remain forbidden.
- Existing protected `SSH_PRIVATE_KEY`, `SSH_USER`, `SSH_HOST`, `SSH_PORT`,
  `SSH_KNOWN_HOSTS`, `COMPOSE_PATH` only. Existing
  [pin validator](../../scripts/deploy/release_ssh_pin.py) validates fixed stage
  endpoint and approved Ed25519 fingerprint; no keyscan/accept-new/trust mutation.
- SSH identity files/certificates are explicitly `none`; password, keyboard
  interactive, GSSAPI and hostbased authentication are disabled. Publickey via
  the existing protected Environment agent remains eligible (`IdentitiesOnly`
  stays `no`). No standard home key/config, certificate or alternate auth source
  is used. Effective `ssh -G` is tested with synthetic home key/config fixtures.
  SSH config/proxy/local-command/agent forwarding are disabled; strict host
  checking uses the validated pin in an anonymous sealed runner FD. No pin is
  installed on the server. No secret environment is forwarded.
- Exact Git source and working bytes are compared before SSH. Collector,
  existing host-lock module and the new narrow process helper are captured once.
  The SSH capture and remote process runner use the captured helper bytes;
  no post-capture helper pathname reread is used. Local Git capture bootstraps
  from trusted checked-out entrypoint/helper source. Captured modules go through stdin
  and executed in memory by fixed `/usr/bin/python3 -I -S -B -`. No upload,
  retained helper execution, arbitrary command selection or server temp file.
- Existing [shared flock](CLB-91-shared-staging-flock.md) predicate/implementation
  is reused unchanged: existing root-owned inode, mode/link/ancestor/xattr checks,
  actual kernel lock verification, `LOCK_EX|LOCK_NB`, no creation or chmod.
  Snapshot uses its own `clb-stage-readonly-snapshot` Actions concurrency group,
  disjoint from deployment/migration `payments-schema-*`. It cannot displace a
  pending deploy by sharing its group. Existing deploy concurrency is unchanged;
  [GitHub concurrency rules](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)
  still allow one snapshot to replace another pending snapshot. Busy/missing/untrusted lock
  refuses before Docker/DB/filesystem observations. No FSM owner is claimed.
- Fixed `/usr/bin/docker`, local Unix Engine socket, isolated CLI environment;
  only ps/inspect and one fixed read-only psql exec. No Docker Compose plugin or
  user-defined healthcheck command is invoked. Fixed curl returns HTTP codes only,
  without redirect/proxy, response bodies or public network requests.
- Maximum 32 containers, 160 history records, 128 entries per metadata directory,
  4 KiB metadata record, 48 KiB command/result output. Per-command 7 seconds,
  collector deadline 55 seconds/alarm 65 seconds, SSH capture 90 seconds, workflow
  5 minutes. Limit failure refuses; no truncation presented as complete evidence.
- [Owned group helper](../../scripts/deploy/snapshot_process.py) fixes PGID at
  `start_new_session`; `waitid(WNOWAIT)` keeps the direct leader unreaped until
  TERM then KILL have targeted the complete owned group, including after early
  leader exit. Direct leader reap, pipe drain/close and waits are bounded; repeated
  successful cleanup does not signal again. Completion is recorded only after
  termination, reap, drain and close. An interruption leaves cleanup incomplete
  with a failure flag; a bounded retry resumes without destructive signals after
  the signalling phase, even if the leader has already been reaped. Pipes remain
  available for the retry to drain. Cancellation is deferred during ownership
  establishment; cleanup masks the owning thread's signals but does not assume
  that this prevents Python handlers arriving through another thread. Exceptions
  still propagate. Cleanup failure is an explicit
  refusal (`reason=cleanup`), never partial success. Darwin's zombie-only EPERM
  is accepted only after reap plus a non-destructive proof that the group is absent.
  No destructive signal is sent after reap. Descendants in the group are killed;
  non-child zombies are reaped by system init (tests use a disposable init).
  Deliberate session escape, a foreign SIGCHLD reaper, uninterruptible kernel tasks
  or hostile trusted binaries are unsupported: failure is not reported as success.
  Runner cancellation
  closes SSH; remote termination/alarm releases lock. A disconnected remote may
  retain the advisory lock until its bounded exit; no immediate disconnect or
  cross-host global atomicity guarantee is claimed. DB statement timeout bounds
  the already read-only backend if docker-exec transport is lost. An additional
  three-second idle-in-transaction timeout prevents a partially delivered script
  from keeping its relation lock indefinitely. Docker-daemon/backend work is not
  claimed to be a member of the local CLI process group.
- The complete diagnostic schema is checked on the runner: required nested
  fields, exact types/enums/counts, HTTP 100–599, hashes, history uniqueness,
  calculated pending/latest/failed values, expected target flags, trigger-to-table
  pairs and section provenance. Empty sections, extra privileged keys, bool/int
  substitutions, invalid status ranges and inconsistent results refuse. All
  backup/FSM/ledger/unfinished-release/neighbor authority boundaries stay exactly
  `unknown`; successful delivery cannot promote them. `stage_ready=false` always.
- Docker metadata is checked again before accepting earlier results. Reads are
  still observations over an interval, not one atomic host/DB/filesystem snapshot.
  No cache or future mutation authorization is created.
- Release files are read only as unverified artifacts. Checkpoint vocabulary is
  closed; owner IDs, paths and ledger contents are not exported. No backup path
  is inspected. Container/resource identities are hashed; only public image
  revision/digest and fixed service/project names leave the host.
- Read operations may affect atime, daemon/DB statistics and access/audit logs.
  They do not alter container lifecycle, business rows, schema, release state,
  lock-file bytes, SSH trust or credentials. Advisory kernel lock acquisition is
  deliberate coordination, not a persisted release mutation.

## Verification

Permanent [security tests](../../scripts/tests/test_stage_readonly_snapshot.py)
are included in [selfcheck](../../scripts/selfcheck-quality-gates.sh).
Tests distinguish authentication/pin admission, observation and release admission.
Synthetic collector/transport/protocol tests run on the host; root-owned flock,
sealed FD and cancellation checks additionally run in a disposable Linux container
with no network, Docker socket or credentials and read-only sources. Ruby workflow
checks run on the host. An initial Linux test failed because the minimal image
had no Git; the test was corrected to exercise missing Git blob refusal with a
synthetic Git response. No security predicate was weakened.

### Historical P1/P2 correction evidence

Task `CLB_STAGE_READONLY_SNAPSHOT_P1_P2_FIX` started from the exact independently
reviewed nine-path manifest, SHA-256
`c42c57ea3a30ebf445a21f96c1a744b9b13a1d487abf873a3740dc108ba420e6`.
Original review RED evidence is retained unchanged: malicious VIEW terminated
another clubs session on PG16.14 despite READ ONLY; child processes survived
leader exit/timeout/cancel; SSH -G admitted default identity files; shared
concurrency could displace pending deploy; missing DB and errors were conflated;
empty sections/negative HTTP/status promotions were accepted.

Permanent suites now include all original 31 snapshot cases plus 13 focused
regressions (44 cases total), and
[17 opt-in PostgreSQL cases](../../scripts/tests/test_stage_snapshot_postgres.py).
Host and disposable Linux together execute all 44 cases. PG probes pass on the
repository-pinned PG16.11 and immutable cached PG16.14: real ordinary Flyway
history, missing/denied/timeout, malicious view with surviving control session,
RLS/policies/rules, materialized/foreign objects, hostile OID operator/TEMP view,
wrong columns, exact-OID replacement, post-lock DDL/RLS/rule exclusion,
concurrent index planning hooks and partial-script idle-lock expiry.
The tests create only no-network tmpfs fixture containers and synthetic sessions.

```sh
python3 -B scripts/tests/test_stage_readonly_snapshot.py
CLB_SNAPSHOT_DISPOSABLE_PG=1 python3 -B scripts/tests/test_stage_snapshot_postgres.py
CLB_SNAPSHOT_DISPOSABLE_PG=1 CLB_SNAPSHOT_PG_FIXTURE=review16.14 \
  python3 -B scripts/tests/test_stage_snapshot_postgres.py
```

The mandatory full quality selfcheck registers both suites; missing opted-in
Docker/PG prerequisites fail rather than silently skipping. The default fixture
uses the existing repository's immutable PG16 image; it may pull that exact pin
if absent. No credential, external DB endpoint or arbitrary image is accepted.
Affected SSH pin/runtime inventory tests, workflow YAML/capability validation,
Python compilation, shell syntax, secret scan, links and full diff/scope checks
are recorded against final source hashes in external evidence. Full Gradle/JVM/
browser suites and full quality selfcheck are not rerun for this Python/Actions
correction. Live DB/socket policy, protected SSH authentication, GitHub queue
execution and stage runtime have not been tested or admitted here.

### Final two-finding correction — 2026-11-13

`CLB_STAGE_SNAPSHOT_FINAL_FIX` starts from the focused review's exact 11-path
manifest, SHA-256 `2df107d3b5a91c8a7604c4baefcf2878d1421ee11c9440b6808e5954483f08c9`.
The earlier claims above describe the previous candidate, not verification of
these new bytes. F1 is addressed by conservative namespace-mutability refusal
and post-lock namespace identity checking; F2 by pessimistic cleanup state and
resumable bounded cleanup phases. No workflow, runner, trust or deploy changes.

The snapshot suite has 48 cases: host **43 PASS / 5 Linux-only SKIP**. Two new
interruption cases first failed on the original helper (`cleaned=true` after
TERM or reap interruption), then passed on the corrected helper. The additional
wait/drain-error test verifies explicit failure and safe completion on retry;
the real cross-thread SIGTERM test requires Linux. Existing early-leader-exit,
descendant, timeout/cancellation and protocol regressions pass on this host.

The disposable PostgreSQL suite now contains 23 cases, including six new cases:
post-validation namespace swap to four bytea columns; database privilege granted
between precheck and lock; same heap OID moved to a replacement namespace;
PUBLIC/inherited-owner privileges; database-owner posture; denied rename in the
supported posture. The race test uses a fixture-only advisory pause and requires
successful SQL completion, so a timeout cannot count as a safe UNKNOWN result.
The collector itself adds no advisory lock or writer cooperation protocol.

Current environment: Docker's local Unix socket is denied. PostgreSQL setup
failed before any case ran; the Linux fixture could not start; pinned source/
history secret scan refused during preflight (exit 2). PG16.11/16.14 behavior,
Linux signal delivery and final-source secret scanning are **UNVERIFIED**, not
PASS. No socket workaround, alternative daemon or permission escalation was used.
Host SSH pin (8 PASS/5 SKIP), runtime inventory (13 PASS/3 SKIP), 33-workflow
YAML/capability validators, Python compilation and scoped diff/link checks pass.
Evidence and the final manifest are retained outside the worktree in
`/private/tmp/clb-snapshot-final-fix-vltrti7_/`.

Verdict: **PARTIAL_BLOCKED**. Next: run the blocked PostgreSQL, Linux and pinned
secret-scan gates against the exact final manifest in an authorized Docker environment.

## Future single-run procedure — not authorized or executed here

1. Review/publish this separate change through a separately authorized PR; merge
   must not be treated as snapshot or deployment authorization. Recheck final main
   source identity and pins. Existing RBAC candidate is not modified.
2. Obtain explicit authorization for **one read-only snapshot dispatch**, then use:

   ```sh
   gh workflow run stage-readonly-snapshot.yml \
     --repo koteev-m/clubs_bot --ref main \
     -f confirmation='CLB_STAGE_PROTECTED_READONLY_SNAPSHOT:read-only'
   ```

   No environment/image/SSH/DB/command inputs exist. Re-runs are rejected. An
   uncertain dispatch is reconciled read-only, never retried automatically.
3. A human authorized Environment reviewer approves `stage`; preserve the existing
   five-minute wait and main-only branch policy. Do not self-approve automatically.
   Read-only Environment API observation in this task confirmed these gates; no
   secret value, principal authentication or live target state was checked.
4. Read one bounded run/result snapshot. No polling/rerun/release follows from this
   procedure. Unknown backup/FSM/Hookah authority requires separate current
   evidence before any release admission. A later quiesced V077/V078/V079 release
   still needs independent rehearsal, recovery proof and deployment permission.

Publication remains unauthorized. No workflow dispatch, migration, deployment, stage/prod/Hookah change,
Git add/commit/push/PR or GitHub Environment change has occurred.
