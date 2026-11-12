# ROLE_TARGET_DB_CONTROL_HEAD_FOUNDATION — local implementation evidence

## ROLE_TARGET_CONTROL_OPERATOR_P1_FIX — local correction, 2026-11-12

**ROLE_TARGET_CONTROL_OPERATOR_P1_FIX: READY_FOR_FOCUSED_REVIEW.** Independent foundation review
reported P1 in standalone lease/publisher queries: qualified relations did not pin
operator resolution. A non-superuser-owned UUID equality operator could accept a
nonexistent control UUID or redirect `fence(A)` to B. The read helper additionally
labelled a persisted row with the requested UUID; publisher only checked row count.
The earlier foundation evidence below is historical and does not supersede this finding.

Minimal correction (no schema, lifecycle, authority or runtime activation changes):

- `configureControlTransaction(connection, trustedSchema)` requires non-autocommit
  and sets **transaction-local `search_path=pg_catalog, trusted-schema, pg_temp`**
  before standalone publisher authentication/audit/SQL or lease head reads. Trusted
  identifiers come exclusively from `BindingStorageSchema`; all relations remain
  schema-qualified. No session search path is authority; no `SECURITY DEFINER` or
  privilege expansion. Existing limits and bounded retries remain.
- Lease verifies on the original Connection/native transaction and reapplies the
  context on every check, including after savepoint rollback. `readControlHead`
  explicitly uses `OPERATOR(pg_catalog.=)` on PostgreSQL, including standalone
  autocommit metadata reads. It selects persisted `id`, compares it independently
  in Kotlin, constructs the result from that ID and rejects multiple returned rows.
- Publisher retains exact CAS. PostgreSQL `UPDATE … RETURNING id,version,generation`
  must return exactly one row with the expected actual UUID and incremented fence.
  H2 retains affected-row count=1. Both dialects reread and compare the complete
  expected poststate. Create also checks affected count and complete initial state.
  Any mismatch rolls back mutation, audit and immutable publication history.

`SET LOCAL` ends at commit/rollback; it does not sanitize arbitrary external SQL or
permanently change a pooled session. Every new lease/publisher invocation establishes
its own safe context before preparing its queries. Prepared-statement invalidation
on path change, savepoint restoration/reapplication and physical pooled Connection
reuse are tested. A read pin or ended transaction handle never grants authority.
Lock order remains **head → actor → root → child**; SHARE/UPDATE modes, adoption fence,
receipt domain and native transaction checks are unchanged. This fix does not add
an authority cache, new transaction/Connection or publication/activation mechanism.

Evidence: `/private/tmp/clb-control-operator-p1-kc1gzqfw/`. Original review files are
untouched. `red/` preserves the exact original probe (2 failures) and baseline input
hashes; `original-green/` preserves the unchanged probe (2 passes) after correction.
The false lease changed from accepted to denied; wrong-target fence changed from
A=ENABLED/g2, B=DISABLED/g3 to A=DISABLED/g3, B=ENABLED/g2. Permanent ordinary-role
regressions additionally cover actual-ID/RETURNING mismatch, TEMP, non-public schema,
prepared statements, pooled reuse and savepoints. Final inputs/results/manifests are
recorded alongside the final delivery logs; no old hash is reused for changed bytes.

Final verification: **218 unique cases PASS, zero failures/errors/skips** — core
binding/control168 (66 H2,102 PostgreSQL), including8 final permanent regressions,
and app/Ktor/identity50 (including12 PostgreSQL). Two unchanged original review
probes also pass on final production bytes. Both permanent attack cases were
separately RED against the saved baseline, followed by exact fixed-byte restoration.
Final8 replace their earlier8 in the merged XML; other160 inputs/production bytes
are unchanged. Source manifests record the test-only helper extraction/pool cleanup.
Ten independent JVM workers exercise shared/exclusive locks, raw non-key UPDATE,
crash/restart and adoption. Native injected40001/40P01 retries reauthorize; actual
55P03/cancellation/rollback cases pass. Populated H2/PG Flyway migrate/validate,
compile, Detekt gate and affected ktlint pass; reference140 and final secret/diff/
source-preservation checks are recorded in delivery evidence.

Verification corrections: new test setup/cleanup nesting and implicit lambda names
were refactored for Detekt; PostgreSQL SHOW normalization is compared with the
observed initial session value. No existing tests, guards or baselines were weakened.
No full JVM/all-module IT rerun: only unwired standalone helpers changed; complete
affected binding suites plus app composition/identity/Ktor/static gates were run.
Production providers/config delivery, real replica failover, privileged recovery and
live Telegram were not tested or activated.

V077/V078/V079, current Mini App authorization, NightConcierge, staff grants and
production registrations are byte-preserved. Production providers remain absent;
ACTIVE, TARGET, invitations, assignments and real credentials remain disabled.
Privileged DDL/TRUNCATE, disabled guards, hostile changes to trusted schema objects,
backup/failover recovery and administrator bypass remain separate recovery gates.
This change protects ordinary permitted DML from untrusted schema/operator masking;
it does not claim to protect against a DB administrator.

Next: independent focused review of operator resolution, exact-target checks and
transaction/rollback evidence. No further functional slice or publication.

**ROLE_TARGET_DB_CONTROL_HEAD_FOUNDATION: READY_FOR_REVIEW.** CLB-role-target-db-control-head-foundation,
2026-11-12. PROJECT_IDENTITY_OK; registered detached worktree
`/private/tmp/clubs-role-workspaces-proposed-20261109`; HEAD/base/merge-base/fresh
remote main `5550b944990d8c1c93c0a5012d31db209f93ea6d`.
All179 paths matched the supplied manifest SHA-256
`1303d73bc14045651053de8a49d51b602d693a387ce8fc0bc51407ad798566bf` before edits.
Backups, logs, XML/input hashes, final full/delta manifests:
`/private/tmp/clb-db-control-head-pfnzjejy/`.

## Accepted scope and implemented inactive model

The owner approved the five directions in
[binding contract §10](../product/ROLE_TARGET_TRUSTED_BINDING_CONTRACT_PROPOSED.md#production-lease-intent)
for local implementation. No runtime/config/credential/permission activation was
approved. V077/V078, current Mini App authorization, NightConcierge connection and
existing client grants remain unchanged.

Paired additive **V079__maintenance_control_heads.sql** creates no records:

- `target_maintenance_control_heads`: immutable UUID, exact immutable PLATFORM
  root and stable authority-connection UUID (both unique), provenance, positive
  version/generation, DISABLED/ENABLED publication gate, manifest UUID/digest and audit FK.
- `target_maintenance_publications`: append-only exact snapshots keyed by
  control/generation, unique non-null manifest UUID, immutable audit reference.
  An AFTER head trigger inserts the snapshot in the same transaction; ordinary
  direct history INSERT must match the current head and cannot duplicate history.

Initial head is DISABLED v/g=1; ordinary DML cannot delete/rewind/rebind it.
Fence clears the manifest and increments v/g. Publish requires DISABLED and a
fresh manifest identity, increments v/g again; ENABLED→ENABLED is rejected. Old
manifest UUIDs remain reserved by history. A failed publication rolls back to the
committed disabled fence; it cannot re-enable the old generation. ENABLED refers
only to maintenance configuration coherence, **never V078 ACTIVE or user authority**.
This minimum supports one exact managed PLATFORM root per authority realm; no
wildcard, client/club inheritance, real bot adoption or backfill is implemented.

All PostgreSQL functions are invoker functions with qualified deployment-schema
relations and `pg_catalog, trusted-schema, pg_temp` resolution. No public-schema
assumption, SECURITY DEFINER, SQL grants or secret storage is introduced. H2 has
paired metadata/history triggers. H2 TRUNCATE/disabled referential integrity and
privileged DDL/backup recovery remain outside the ordinary-DML guarantee.

## Transaction-bound lease and lock graph

[JdbcControlHeadLease](../../core-data/src/main/kotlin/com/example/bot/data/client/bindings/JdbcControlHeadLease.kt)
never acquires a Connection or commits. On the caller's existing non-autocommit
transaction it locks the exact head `FOR SHARE` on PostgreSQL (`FOR UPDATE` local
H2 fallback), verifies complete expected scope/version/generation/status/manifest,
and pins Connection object identity plus native transaction ID. Foreign connection,
commit/rollback reuse, closed handle, stale/missing head or wrong schema deny.
There is no cross-request authority cache or session-level lock. Closing a handle
cannot release a DB lock early; only transaction completion does that.

Normal maintenance order is **head SHARE → actor NKU → root UPDATE → child →
audit/receipt → commit**. Publisher updates use head UPDATE then audit/history;
they do not lock actor/client/binding roots. Initial DISABLED bootstrap is the
bounded exception: its INSERT guard takes root UPDATE before publishing any head.
This serializes adoption with the bare writer's root lock; no usable head/lease
exists yet. A duplicate bootstrap aborts and cannot replace an existing scope.
V078 child→root NOWAIT and the calendar resolver's client→actor NOWAIT remain
nonblocking reverse edges. Arbitrary raw SQL/FK sequences can still deadlock and
must abort; there is no universal deadlock-free claim.

PostgreSQL transaction-local limits: lock_timeout 2s, statement_timeout 5s,
idle-in-transaction timeout 10s; final lease/publisher checks also bound an attempt
to 10 monotonic seconds. These abort work, not expire/steal authority. Core retry
remains two complete retries for 40001/40P01, fresh lease/identity/intent each time;
55P03 is not retried. Cancellation propagates through rollback/connection cleanup.
Crash closes the backend transaction; reconnect/restart requires a new handle and
current expected manifest. No old holder can resume its ended transaction.

## Synthetic publication and trusted ephemeral intent

[SyntheticControlHeadPublisher](../../core-data/src/main/kotlin/com/example/bot/data/client/bindings/SyntheticControlHeadPublisher.kt)
has a default-absent internal authority provider. It only creates/fences/publishes
metadata on disposable/internal explicit inputs, with current authority rechecks,
CAS and atomic audit/history. It neither delivers external config nor rotates a token.
Lost response requires reading current head/history and explicit expected CAS;
there is no new HTTP publication or durable invitation/intent protocol.

[MaintenanceControlBundle](../../app-bot/src/main/kotlin/com/example/bot/plugins/target/MaintenanceControlBundle.kt)
and [DbControlMaintenanceLeaseSource](../../app-bot/src/main/kotlin/com/example/bot/plugins/target/DbControlMaintenanceLeaseSource.kt)
compose the real signed verifier with a **default-absent protected bundle source**.
The finite manifest binds exact root/bot/incarnation and allowed subset of the
three existing actions, configured owner, immutable auth context/revision, stable
realm/control identity, publication generation and manifest identity. Its digest
does not include token bytes or process-object identity. The runtime local
authentication object must nevertheless match the signed verifier exactly.

The ephemeral request binds actor, context revision, operation, full immutable
expected binding/credential state and SHA-256 idempotency-key digest. Scope comes
from the protected manifest, never body/query/username/legacy roles. Expected root
fence is data, not authority; canonical pre/post-state remains checked on the
same locked Connection. The payload does not refresh itself after the writer's
own legitimate root increments. BRANDED and mapping/client scope remain denied.

The approved app ports now take an explicit request and Connection. The existing
writer/receipt transaction is reused. The new path has a separate
`commit-maintenance-control-v1` request-hash domain bound to control generation,
manifest and key digest. Stable receipt namespace is preserved; an old key after
publication cannot alias a new admitted command. The prior isolated v1 evidence
path retains its previous hash domain. No receipt is a bearer permission.
The core guard independently requires the typed JDBC control lease for every
adopted root, checks it before actor/root locks and ties its realm/publication to
the returned identity. Old guarded/JVM-only providers and string-only markers
cannot bypass a registered control head; their unmanaged-root behavior is retained.

Fence-first delivery is synthetic only: commit DISABLED/new generation, then
explicitly publish a fresh pinned manifest, then deliver the matching bundle.
Old processes keep their old bundle and deny against the changed head even before
new config arrives. Delivery failure leaves them denied; no automatic rollback
or old-bundle generation rebasing exists. An ENABLED row without a valid trusted
bundle and freshly signed exact owner still denies before lifecycle mutation.

## All 23 updater groups

Exact paths/current source hashes and per-group participation fields are in the
[inventory](../../scripts/tests/fixtures/role-target-lease-intent-inventory.json).
Historical §10 inventory is retained; this is the new local overlay.

| Groups | Current maintenance effect / fence | Participation or remaining gate |
|---|---|---|
| W01 identity provision | Unique TG mapping; actor NKU + post-lock TG relookup | Existing DB fence; no remap authority inferred |
| W02 privacy | Phone/privacy fields outside predicate | No new head needed |
| W03 onboarding dispatch | Actor/client locks, legacy commands | No maintenance authority consumed |
| W04 client invitation | Client membership; V078 client epoch | Legacy flow retained |
| W05 staff invitation/preparation | Exact client/staff/watermark guards | No TARGET conversion or maintenance grant |
| W06 staff replacement/disable | Existing mixed-source guard | Never used for control maintenance |
| W07 platform member revoke/restore | Client/admission/source terminal fences | Not configured-platform-owner revocation |
| W08 V077 assignments/delegations | Actor→client→sorted source locks | Inactive; no platform maintenance authority |
| W09 promoter access | Existing mapped-client compatibility fence | No head participation required |
| W10 club create/bootstrap | Client→mapping/high-water fence | No new club/binding inheritance |
| W11 client lifecycle/quota | V078 audience epoch; no quota-only bump | PLATFORM has no client/mapping predicate |
| W12 club profile/publication/seed | Existing club/index locks | Publication is not staff admission |
| W13 bare binding evidence writer | Explicit trusted storage pin, root UPDATE, controlled-root rejection | Blocked for adopted root before receipt/audit/mutation; pre-V079 absence requires proven Flyway history |
| W14 guarded maintenance | Actual transaction-bound SHARE→actor→root→child, current signed proof/CAS | Core requires typed JDBC head lease for adopted roots; old guarded/JVM/string-only paths deny. No production registration |
| W15 ordinary binding/control DML | V078 unchanged; V079 head/history locks, monotonicity/terminal constraints | SQL consistency only, not Telegram authentication; hostile TEMP/non-key UPDATE tested |
| W16 mappings/cascades | V078 schema-pinned durable high-water triggers | Unchanged; future BRANDED still blocked |
| W17 auth/selection/bundle ports | Same-Connection and intent-bound interfaces, default absent | Synthetic composition only; real protected provider absent |
| W18 live AppConfig/connection | Existing process config has no control-head publisher | **Production blocked**; not adapted or switched here |
| W19 config/deploy/secret publishers | New synthetic metadata publisher only | **Production blocked** pending protected publisher/all-replica participation |
| W20 migrations/recovery | Empty additive V079; historical migrations immutable | Privileged DDL/disabled guards/backup/failover require separate recovery gate |
| W21 raw remap/staff SQL | Actor/root/head storage fences; no staff role in this predicate | Raw staff cutover remains blocked; DB-admin bypass is outside guarantees |
| W22 readers/pins | Snapshot/expected state only | Never authority; current checks required for each mutation |
| W23 other advisory/flock | Different resources | Cannot substitute for control-head lease |

## Verification boundary and next step

Disposable H2/PostgreSQL tests cover populated V078→V079 Flyway migrate/validate,
empty upgrade/no adoption, initial/published/disabled states, history and manifest
ABA, stale CAS, wrong/missing scope/schema, lease connection/transaction lifetime,
publisher rollback/audit failure/cancellation and controlled-root compatibility.
Signed composition covers wrong actor/bot/stale initData, missing/changed bundle,
finite operation/root substitution, old receipt/config domain, selected material
retirement, stale child/root and terminal binding, owner loss/cancellation rollback.

Real PostgreSQL independent JVMs run the actual lease and publisher implementations:
two shared holders, exclusive wait, raw non-key UPDATE wait, exclusive-first denial,
crash/restart and bootstrap/old-writer ordering. Ordinary-role hostile TEMP/path
probe checks invoker/schema behavior. Native injected 40001/40P01 retry cases
revalidate authority; actual lock_timeout yields 55P03 without retry. They complement
the earlier real deadlock/serialization probes; they do not claim a new spontaneous
deadlock for every current writer pair.

Final verification: full JVM **1,739 cases, zero failures/errors, one unchanged
GuestFlowStart skip**; affected PostgreSQL **348 unique cases, zero failures/errors/
skips** (346-case PG-only suite plus final 12-case app suite replacing its earlier
10-case version). Compile, Detekt gate, affected ktlint, populated H2/PostgreSQL
Flyway migrate/validate and reference140 pass. Per-run XML is copied before the
next Gradle invocation; source-input manifests distinguish backend and reference
inputs. Final command logs, preservation/link/diff/secret checks and hashes accompany
the full/delta candidate manifests.

Verification corrections are retained in evidence: initial worker stdout mixed
SLF4J warnings with its protocol (stderr separated, assertions retained); five
upgrade fixtures expected V078 as latest (now explicit V078 and V079 steps, all
domain assertions retained); function-resolution probe now requires all eight
V078/V079 functions. Overlapping early Gradle runs collided in XML output; those
intermediate artifacts are not final evidence. Delivery gates run sequentially.
Static findings were fixed in new code/tests without baseline or gate changes.

Security self-review fixed two controlled-root interference paths: bare evidence
writes and the old guarded/JVM/string-marker path. Both now require the intended
control participation or fail before mutation. Counter/adoption/canonical pre/post
checks and positive fresh-pin follow-up operations are covered by real DB tests.

No real tokens/config, client/staff materialization, jobs/routes/DI or stage/prod
were used. Production lease admission is unavailable by default; ACTIVE remains
rejected by unchanged V078. The real protected owner/auth/manifest publisher,
operational all-writer/replica/failover proof, credential resolver and runtime
maintenance intent source are still absent. No proof v2/invitation/admission/RBAC
activation is supplied.

Next: independent focused review of V079 guards, transaction lifetime, bare-writer
adoption fence, receipt-domain compatibility and separate-process lock evidence.
Keep runtime adoption and further functional activation disabled.
