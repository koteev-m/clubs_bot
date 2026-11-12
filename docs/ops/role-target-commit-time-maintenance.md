# ROLE_TARGET_COMMIT_TIME_MAINTENANCE — local evidence

## Design follow-up: production lease/intent — 2026-11-11

**ROLE_TARGET_LEASE_INTENT_CONTRACT: READY_FOR_APPROVAL / PROPOSED.**
The [single extended contract §10](../product/ROLE_TARGET_TRUSTED_BINDING_CONTRACT_PROPOSED.md#production-lease-intent)
compares existing DB locks, a minimal control head and external leases. It recommends
a maintenance-purpose non-expiring DB head on the same mutation Connection;
protected context publishers take its exclusive lock. Finite server intents reuse
the existing command/receipt path. No provider, head migration or runtime is added.
App `current()` needs an explicit Connection bind in a future slice; an independently
pooled/JVM-only lease would not satisfy this contract.

Preflight: PROJECT_IDENTITY_OK, registered detached worktree, HEAD/base/merge-base/
fresh remote main `5550b944990d8c1c93c0a5012d31db209f93ea6d`. All175 reviewed
paths matched manifest SHA-256
`46cf606da5e806be25f1d7dbfae497b997351fef591da441fdb03b242e473b46`.
Backups/final logs, source hashes, full/delta manifests:
`/private/tmp/clb-lease-intent-contract-o47mab22/`.

New bounded evidence: 23 inventory groups / 88 exact sources, SQL/Exposed discovery
alarm, acyclic intended blocking graph with explicit NOWAIT reverse edges;
9 reference tests including 24 literal admission cases. PostgreSQL probes use
18 tests with independent OS psql/backend processes: both command orders,
shared/exclusive head publication, actor remap, real V078 child guards/mapping ABA,
actual 40001/40P01/55P03/57014, audit/receipt rollback, backend crash/restart, stale
generation/bundle and replay. The head/command are disposable **test models**;
V078 is applied unchanged on minimal prerequisite tables. No Flyway/JVM/provider
integration or production configuration participation is claimed by these probes.
Final command outcomes and preservation checks accompany the manifest.

Production is still blocked on the real control publisher, same-transaction provider
ports and verified participation across all replicas/config updaters; bare V078
evidence writers remain unwired. Existing H2/PostgreSQL/Ktor evidence below is
historical and reusable only with its preserved source hashes. No Kotlin, SQL
migration, auth route, V077/V078, CONCEPT_SOURCE, grants or real data change here;
no broad JVM rerun for this design/test-only slice. Five new technical decisions
await owner approval; historical acceptance is not extended automatically.

Next: after approval, implement the default-deny, unregistered DB control-head and
transaction-bound lease/finite-intent pair on synthetic data, with two-process
publisher/command proofs; keep live adoption and activation disabled.

## Previous implementation evidence (preserved)

**ROLE_TARGET_COMMIT_TIME_MAINTENANCE: READY_FOR_REVIEW.**
CLB-role-target-commit-time-maintenance-adapter, 2026-11-11. Registered detached
worktree `/private/tmp/clubs-role-workspaces-proposed-20261109`; HEAD/merge-base/
fresh remote main `5550b944990d8c1c93c0a5012d31db209f93ea6d`.
PROJECT_IDENTITY_OK; active Clubs Bot instructions, foreign=0. All162 hashes
matched reviewed manifest `9cc059b0877e53e2b90d8659ec43727963208a81a534bd350efd43c01173502f`
before edits. No unrelated changes, rebase/ref update or publication.

Backups, logs, XML/input hashes, full/delta manifests:
`/private/tmp/clb-commit-maintenance-mo27st3r/`.

## Implemented local adapter; no runtime activation

[TargetCommitTimeMaintenanceAdapter](../../app-bot/src/main/kotlin/com/example/bot/plugins/target/TargetCommitTimeMaintenanceAdapter.kt)
links an expected read-only pin and trusted current maintenance intent to
[JdbcCommitTimeBindingMaintenance](../../core-data/src/main/kotlin/com/example/bot/data/client/bindings/JdbcCommitTimeBindingMaintenance.kt).
The latter delegates to the same
[JdbcInactiveBindingFoundation](../../core-data/src/main/kotlin/com/example/bot/data/client/bindings/JdbcInactiveBindingFoundation.kt)
transaction, BindingValidation, BindingWriter, BindingAudit and durable receipt path.
No second SQL mutation implementation/API is introduced. Existing legacy entry
methods keep their original evidence providers and semantics; guarded hooks have
no fallback to those providers. New authorization defaults absent → forbidden.

Supported operations are **PLATFORM only**: existing Disable, terminal Revoke and
retirement of the **currently selected AVAILABLE** material. No real token is
resolved, invalidated or rotated; retirement remains V078 metadata. Revoke retains
its existing all-available-material retirement behavior for this exact root.
No create/replacement/credential issuance/scope change or ACTIVE operation is added.

Unsupported/blockers are explicit: Create needs first-root bot/provenance attestation;
Replace needs a separate terminal-incarnation/re-admission intent and provenance;
AttachReference needs trusted material/bot attestation and credential boundary.
The existing writer itself is platform-only. All BRANDED writes remain forbidden,
even with a synthetic business provider/read pin: no current business provider,
client-locked branded writer or approved operation contract exists. Mapping/foreign
scope negatives for that branch demonstrate **denial**, not implemented branded
canonical authorization. No GLOBAL, creator or membership shortcut is used.

## Required authority lease, not a free-running callback

[BindingMaintenanceAuthorization/Lease](../../core-data/src/main/kotlin/com/example/bot/data/client/bindings/CommitMaintenanceModel.kt)
requires a current shared authority/context fence for the whole transaction.
The app `MaintenanceMutationLeaseSource` has **no production implementation** and
defaults absent. A plain repeated current() callback cannot guarantee revocation
serialization and is insufficient. The synthetic implementation is test-only.

The lease is acquired before DB locks and released **after commit or rollback**
and connection cleanup. Every relevant configuration/authentication/selection
updater must take the matching exclusive fence; it cannot commit a revoke while
a valid mutation is still using the shared lease. Conversely, a committed revoke
must precede every subsequent shared lease/current-identity check. Tests use a
fair ReentrantReadWriteLock; the future production provider must prove participation
of all updaters, cancellation/timeout behavior and stable identity provenance.
This slice does not pretend those live providers already exist.

Inside the supplied mutation transaction the app adapter repeatedly calls the
existing `TargetIdentityVerifier.verifyMaintenance` on fresh signed headers. It
checks exact trusted connection object, current enabled authentication context,
positive exact configured platform owner ID, equality to the pin's actor, current
server-selected root/bot/incarnation/fence, context revision, credential revision
and unbound PLATFORM scope. The authority connection UUID and authority reference
come from the trusted lease, never username/connectionKey/body/query/pin alone.
No credential proof v2/MAC/nonce is minted. Raw initData/headers/key are not audited,
serialized, logged or returned; they exist only in the transient request adapter.

Stable authority connection identity is required for the durable receipt namespace;
it is not a new random request ID or token revision. No production connection is
adopted to supply that identity. Missing authority/context/lease or wrong signed
actor/bot denies before business writes, including receipt replay.

## DB transaction, canonical state and lock order

PLATFORM order is:

1. shared server maintenance authority/context lease;
2. DB transaction / trusted schema resolution;
3. actor `users` row (`FOR NO KEY UPDATE` PostgreSQL, `FOR UPDATE` H2);
4. exact binding aggregate root `FOR UPDATE`;
5. child DML under that root; V078 BEFORE guards use exact-root NOWAIT;
6. audit completion, durable receipt, post-state/authority/cancellation checks;
7. commit/rollback, connection cleanup, shared lease release.

The actor's Telegram→DB-user mapping is rechecked after acquiring the actor lock
for the guarded path. This prevents using a user row remapped between initial
lookup and lock for audit/receipt identity. The old legacy path is unchanged.
Root/child version/generation, current incarnation, kind/scope, credential reference/
revision/material state and expected status are reread **on the same Connection**.
`JdbcDurableBindingSource.readInTransaction` reuses the existing single-SELECT
projection/decoder without another connection/transaction/cache. The expected
snapshot must match in full before new mutation. Post-write validation uses the
legitimate new fence, not the old expected pin, and verifies terminal/retirement
postconditions. Composite child commands retain their multiple aggregate ticks.

PLATFORM has no client binding/mapping epoch: both must be null. No client lock is
invented or acquired after the root. A future BRANDED path would require its exact
client lock **before** root and mapping updates; that path is intentionally blocked.
Existing V077 platform membership revoke uses its client/admission locks; neither
it nor legacy client writers acquire these binding roots. The new platform path
never requests client/admission locks, so it adds no root→client dependency. The
shared actor command lock remains compatible with FK KEY SHARE checks.

Direct child DML that reaches a trigger with a free child but occupied root fails
NOWAIT (`55P03`). If the exact child row is already locked, PostgreSQL can wait for
that row before firing the trigger; this is not promised as immediate rejection.
After it runs, all ordinary child changes still advance the aggregate fence or
fail terminal constraints. Reversed root/child ownership cannot silently preserve
old receipt freshness. Both mutation orders and raw-child-first ordering are tested.

PostgreSQL guard/transactional reader resolve operators through pg_catalog and
relations through the exact trusted deployment schema, with pg_temp last. No
session path/public assumption, SECURITY DEFINER, privilege grant or V078 edit.
A schema-qualified zero-row SELECT binds all required canonical relations before
unqualified writer/receipt lookup. Missing canonical objects cannot fall through
to a TEMP replica, including a cloned previously committed receipt. Those relation
locks remain in the transaction. H2 uses the exact configured schema. TEMP/hostile-relation/operator probes cover
new writer queries as well as the old V078 functions/guards in regression tests.
Privileged DDL, disabled guards, TRUNCATE bypass/backup recovery remain separate
operational boundaries; this slice does not defend against a DB administrator.

## CAS, receipt, retry and failure semantics

The read pin is **only expected state**, never bearer authorization. The current
actor and server intent are reverified under locks and again after writes. Old
pins with a new key fail CAS; child/credential changes fence them. The guarded
request hash has a separate `commit-maintenance-v1` domain and includes operation,
material and all relevant expected metadata/fence fields. It cannot alias a legacy
receipt or an altered expected-state request. Raw idempotency keys stay hashed.

The existing actor/root serialization produces one atomic mutation + audit +
receipt, whose fence is the final aggregate fence. Replay never executes a command:
REPLAY_CURRENT if the committed receipt still matches the root; REPLAY_STALE if
later legitimate state superseded it. Returned data is only status/current fence,
no saved permission payload. Replay still requires current signed authority and a
current authorized server intent for the original target. If that authority/intent
is gone, denial takes precedence over disclosing an old outcome. No unknown key
reserves a row before success. No separate resolve route is introduced.

Audit/receipt failure or precommit authority/identity/cancellation failure rolls
all child changes and trigger fence increments back. `40001` and `40P01` retry the
whole transaction at most twice (three attempts); each attempt reacquires the
shared lease and rechecks identity/authority/state with the **original** expected
pin. There is no auto-refresh/regrant or infinite retry. `55P03` is a fail-closed
conflict without retry. Actual PostgreSQL40001 raised after child writes proves
rollback/retry and rejection after authority withdrawal between attempts.

A lost response after commit can be recovered by a currently authorized replay
of the same intent/key; no receipt resurrects revoked authority. A later mutation
requires new expected state and fresh authority. Cancellation after an actual
commit cannot undo it; the durable receipt remains the recovery boundary.

## Evidence and focused review

Tests separately prove signed authentication, mutation purpose/lease, same-transaction
canonical checks, CAS and absence of TARGET permission issuance. Only disposable
H2/PostgreSQL and synthetic identities/references/authority providers are used.
New test-host routes exist only in tests; there is no production route/DI/job.

Negative/security cases: absent lease, other signed user/token, stale identity,
revoked authority after read selection, changed context, stale/root/child fences,
retired material, wrong selected material, unsupported branded/foreign scope and
mapping ABA, old pin/new key, incompatible replay, concurrent disable/revoke,
raw DML/NOWAIT, real SQL retry with authority loss, cancellation and audit/receipt
rollback. Tests check both risky orderings and lease release only after visible commit.
Self-review corrections have actual RED→GREEN evidence:

- `actor-race-red.log/xml`: remapping Telegram→DB-user between initial lookup and
  actor lock previously allowed the command under the wrong DB actor. The guarded
  post-lock mapping check now rejects, without changing the legacy path.
- `missing-schema-red.log/xml`: absent configured schema + TEMP clones of a real
  root/receipt previously yielded a false cached current replay. The qualified
  canonical-relation bind now rejects before lookup. Valid custom-schema/TEMP
  reads/writes still work; no privileges/migrations changed.

The first concurrent raw-DML probe incorrectly expected NOWAIT on an already locked
child row; it was corrected to a free child insert, preserving the actual55P03
assertion. This changes a probe, not the guards or acceptance contract. Test fixture
counters use atomic visibility across concurrent workers. No test/baseline was weakened.

No V077/V078 or historical migration changes, no actual token operations, no
TARGET evaluator/assignments/invitations/grants, no new network authority or
ownership changes. NightConcierge and old platform-owner/client/team/preparation
wiring remain unchanged. Real clients/clubs/stage/prod were not accessed.

## Verification commands and changed scope

| Final check | Result |
|---|---|
| New adapter acceptance | **44/44**, core29 + app15, no failures/errors/skips |
| Final affected suite | **281/281**, core-data162 + app-bot119, including real PG/P1/P2/migration/legacy routing, no skips |
| Required full JVM | **1,716 tests**, zero failures/errors; one unchanged GuestFlowStartTest skip |
| Kotlin/static | compilation, repository detektGate and affected ktlint PASS |
| Offline reference | **131/131**, no oracle/scenario changes |
| Flyway | H2/PG migrate/validate and populated V077→V078 upgrade PASS in affected regressions; V077/V078 bytes unchanged |
| Secret/source/history | final sanitized secret-scan-final/summary.json; fail-closed PASS required for handoff |
| Scope | candidate175/delta20, previous155/162 byte-preserved; links/decisions/immutable/history preserved |

Only actor mapping and missing-canonical-schema corrections changed production
bytes after initial green checks; both have RED→GREEN proof followed by the final
44-case,281-case and full JVM/static gates. No source/test edit follows these gates.
Production shared-lease participation across replicas and all configuration updaters
is unimplemented/unverified; a process-local test lock is not claimed sufficient
for a multi-instance deployment. Existing DB user provisioning is required for the
audit/receipt FK; no user is auto-created by this adapter.


Final XML/static/input hashes are stored with the manifests. New adapter cases:
core-data12 H2 +12 PostgreSQL contracts +5 PostgreSQL concurrency/schema cases;
app7 H2 +7 PostgreSQL signed compositions +1 Ktor test-host case = **44 cases**.
They distinguish supported platform mutation, rejected unsupported cases and
future production integrations; they do not claim branded mutation support.

Commands use the registered worktree and the explicit owned loopback
`CLB_CLIENT_TEST_PG_URL` for tagged integration tests:

- `h2-second.log`: narrow H2 lifecycle/CAS/rollback/retry gates after initial
  compile/style fixes;11 cases PASS at that stage.
- `integration-second.log`:41 pre-self-review cases PASS + repository Detekt/ktlint.
- `actor-race-red.log`, `missing-schema-red.log`: one expected regression failure
  each before the corresponding guarded fix; XML and tested source copies retained.
- `adapter-final-green.log`:44 final new cases PASS on H2/PostgreSQL/Ktor and
  repository static gates after both fixes, before broader unchanged regressions.
- `affected-final-locked.log`: `./gradlew --no-configuration-cache --console=plain
  --no-parallel --continue :core-data:test -PrunIT=true` with bindings package,
  CanonicalAudience and ClientContextPostgresIT filters; `:app-bot:test` with target
  package, PlatformOwnerRoutesTest/MiniAppAuthTest/ClientContextAuthTest and existing
  mapping/team/preparation routes; `detektGate :core-data:ktlintCheck :app-bot:ktlintCheck`.
- Full JVM: `./gradlew --no-configuration-cache --console=plain --continue test
  detektGate :core-data:ktlintCheck :app-bot:ktlintCheck`.
- Offline reference: `python3 -I -S -B -m unittest discover -s scripts/tests -t .
  -p 'test_role*.py'`.
- Final source/history secret gate: `python3 -I -S -B scripts/secret-scan.py
  --output <evidence>/secret-scan-final`; tracked/untracked diff, new links,
  decision-status/fixture/hash preservation and runtime call-path checks.

The real PostgreSQL test uses pinned16 image digest
`sha256:16bc17c64a573ef34162af9298258d1aec548232985b33ed7b1eac33ba35c229`.
Actual40001 and55P03 are observed on PostgreSQL;40P01 handling is fault-injected,
not a claim to have forced a new real deadlock. Full PostgreSQL IT is not repeated:
all affected transaction, V077/V078 migration/guard/client and routing paths run
explicitly. No staging/prod/default application database is used.

Scope: existing BindingModel only exports MaintenanceIdentity; existing
JdbcInactiveBindingFoundation adds the guarded hook/common retry path; existing
JdbcDurableBindingSource adds the same-transaction reader. All other prior
production Kotlin, SQL/migrations, guards, legacy writers and tests are preserved.
New core main files: CommitMaintenanceModel.kt, JdbcCommitTimeBindingMaintenance.kt;
new app main: TargetCommitTimeMaintenanceAdapter.kt. New core tests:
CommitMaintenanceFixture/Contract/H2Test/PostgresIT/ConcurrencyPostgresIT;
new app tests: SyntheticMaintenanceMutationLease, TargetCommitMaintenanceDatabaseTest,
TargetCommitMaintenancePostgresIT and TargetCommitMaintenanceRouteTest.
Docs: this report, trusted-binding contract, AS_BUILT and PROJECT_STATUS; only the
trusted-binding fixture doc hash is refreshed, with all scenarios/oracles preserved.
Full manifests enumerate exact paths and SHA-256 bytes outside the repository.

Full untracked diff checks record two unchanged prior EOF warnings in
role-assignment-shadow-report.md:3395 and role-shadow-synthetic-report.md:1590;
changed paths must be clean. Immutable CONCEPT_SOURCE and AGENTS remain unchanged.
Production lease/updater/configuration/credential behavior is not proven by these
synthetic implementations and remains blocked until separately reviewed.

## Remaining prerequisites and next step

Production use remains blocked until a real shared authority/context lease provider
and authorized server-intent source exist, including all relevant updaters and
stable connection/audit provenance. No default synthetic provider can activate it.
Business ownership/branded maintenance, real credential attestation/resolver/rotation,
ACTIVE/activation contract and TARGET invitation/admission are still separate future
work. Tests do not prove live secret-store/configuration/recovery behavior or arbitrary
external writers ignoring the required fence.

One next step: independent focused review of the exact final adapter candidate,
especially lease participation/lifetime, same-transaction checks, replay and retries;
keep production activation and further functional slices disabled.
