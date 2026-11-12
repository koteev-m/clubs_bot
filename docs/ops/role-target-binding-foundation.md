# ROLE_TARGET_INACTIVE_BINDING_FOUNDATION — local evidence

**ROLE_TARGET_BINDING_FOUNDATION: READY_FOR_REVIEW.** Local unpublished
candidate only. No active TARGET connection, authorization or staff provisioning.

## Focused review correction

The initial READY_FOR_REVIEW snapshot below received independent P1/P2 findings:
TEMP/search_path could divert the mapping epoch; direct child DML could retain a
current receipt fence. It does not prove those properties before correction.
[Current focused fix and fresh inputs](role-target-binding-p1-p2-fix.md) supersede
that part of the evidence. V078 is still unpublished and inactive. Child writes
now fence exact roots atomically, so revisions may advance multiple times per
service command. No activation or next binding slice is included.

## Identity, scope and preservation

Task `CLB-role-target-inactive-binding-foundation`, PROJECT_IDENTITY_OK; active
Clubs Bot instructions, foreign=0. Registered detached worktree
`/private/tmp/clubs-role-workspaces-proposed-20261109`; HEAD/origin-main merge-base
and fresh remote main `5550b944990d8c1c93c0a5012d31db209f93ea6d`.
Local `main` ref is stale and was preserved; no rebase/reset/ref update.
Before edits all 101 reviewed paths matched the supplied manifest, SHA-256
`460424b353f0bdd5a669daeaaaf1dd55e4bef8b833b95feeaa3c7daa1a4c6c49`.
Baseline byte copies, commands, test XML, focused review and final full/delta
manifests: `/private/tmp/clb-role-inactive-binding-pcz8b7vo/`.

The owner accepted the five architecture directions in the
[binding contract](../product/ROLE_TARGET_TRUSTED_BINDING_CONTRACT_PROPOSED.md)
for this limited foundation. Product permissions and future activation remain
outside this approval. CONCEPT_SOURCE, previous migrations including V077,
previous production Kotlin, grants and identity/audience readers are preserved.

## Implemented inactive persistence

New paired additive [PostgreSQL V078](../../core-data/src/main/resources/db/migration/postgresql/V078__inactive_target_bindings.sql)
and [H2 V078](../../core-data/src/main/resources/db/migration/h2/V078__inactive_target_bindings.sql):

| Structure | Enforced boundary |
|---|---|
| `target_bot_connections` | Stable UUID, unique positive verified bot ID, immutable PLATFORM/BRANDED and branded client; root owns current incarnation, positive version/generation, provenance/audit |
| `target_bot_bindings` | Independent UUID per incarnation, exact root FK, PENDING/DISABLED/terminal REVOKED. ACTIVE is reserved but rejected by SQL guard and internal operations |
| `target_binding_clubs` | Explicit normalized subset; composite FKs enforce same branded client and canonical mapping. PLATFORM has NULL client and cannot acquire these rows |
| `target_credential_materials` | Stable opaque material UUID, AVAILABLE→terminal RETIRED; history is retained |
| `target_credential_revisions` | Immutable opaque revision UUID/positive revision, same root/material/verified bot; retired material cannot be issued under a new reference |
| `target_binding_commands` | Actor + independent maintenance connection + hashed key; exact intent/root/incarnation/result pins, audit; immutable receipt |
| `clients.target_mapping_epoch` + `target_client_mapping_epochs` | Transactional current epoch projection + durable high-water mark surviving physical client deletion/reuse; not a second client catalogue |

No roots, bindings, credentials, clients, assignments or grants are backfilled.
Branded scope history uses RESTRICT canonical FKs; future branded deletion/rebind
needs its own reviewed lifecycle, and is not an enabled legacy path.
Existing clients receive only default metadata epoch=1; high-water rows are lazily
created on the first relevant write. An absent head before that first write means
the initial epoch=1 for that existing client, not a guessed bot binding. New
clients create their own head atomically. An eventual security reader must use
the durable counter/current client snapshot consistently, reject incoherent or
missing required state, and revalidate under locks; the current audience reader
has **not** been upgraded or activated by this slice.

`client_commands` requires a real client and numeric result ID, so a platform root
cannot reuse it without fabricating a tenant. The small binding-specific receipt
uses the existing audit_log and actor locks, not a second client or grant model.

## Internal lifecycle and transaction

[JdbcInactiveBindingFoundation](../../core-data/src/main/kotlin/com/example/bot/data/client/bindings/JdbcInactiveBindingFoundation.kt)
is internal, has no DI/route caller, and all evidence providers default deny.
Tests supply only synthetic maintenance identity, bot attestation and opaque
credential provenance. Configured exact platform-owner Telegram ID alone is
insufficient; a fresh independent maintenance attestation must match it. No
legacy OWNER/GLOBAL, membership or TARGET business authority is used.

Operations: create root + PENDING incarnation; read; resolve; disable; terminal
revoke; replace revoked incarnation with a new PENDING UUID; attach an immutable
attested reference; retire selected material. Branded lifecycle/activation is
not implemented. Every security mutation increases root version and generation;
initial atomic bootstrap is 1/1; read and current replay leave them unchanged.
Replace requires terminal current state, fresh UUID/provenance and an empty
credential selection. Old binding IDs, materials and receipts are retained.
Revoke retires all available materials of that root. Retirement of the selected
material clears selection and disables the incarnation. Neither path affects
legacy AppConfig tokens, memberships, V077 or other users/clients.

Order: fresh identity → existing actor row lock → root FOR UPDATE → recheck
identity/time/CAS → audit + source metadata + durable receipt → recheck all
attestations and cancellation → commit. Read-only metadata takes PostgreSQL
FOR SHARE through transaction end; independent readers coexist. H2 takes stronger
locks. Platform lifecycle never waits for client locks after root. Future branded
or TARGET operations must use actor→client→root→children order. No operation in
this slice performs network/secret-store effects inside a DB transaction.

Retry of PostgreSQL serialization/deadlock SQLSTATE 40001/40P01 restarts the whole
transaction with current evidence (bounded two retries). Exceptions/cancellation
roll back; cleanup preserves the primary failure. Audit or receipt failure leaves
no partial root, incarnation, credential state or mutation. Exact intent hashes
contain UUID/enum/numeric fields only, no raw maintenance evidence/key/token.
Unknown resolution never reserves a key. Same key/different intent conflicts;
replay only succeeds if receipt pins still equal current state. Old receipts may
resolve as COMPLETED_STALE with current authorized metadata, never replay old
ACTIVE content or repeat issuance.

## Mapping-writer coverage and ABA

Audited all runtime SQL/Exposed references and repository SQL bootstrap paths.
No business writer needs a code change: database triggers cover row-level DML,
including direct SQL and FK cascades, within the original transaction.

| Real path / predicate | Epoch behavior and evidence |
|---|---|
| `InvitationOperations` creates inactive client; claim/confirmation activates | New client head; actual is_active change advances. Existing idempotent retries do not repeat the change |
| `ClubOnboardingOperations.createClub` historical approved application | Already locks client; mapping INSERT advances once in the same transaction; H2 + PostgreSQL mounted-route tests |
| `DirectClubOperations.createClub` first/second club | Same client lock; each new exact mapping advances once. Replayed receipt leaves epoch unchanged; H2 + PostgreSQL mounted-route tests |
| `ClientLifecycleOperations.archive/restore` | archived_at/lifecycle_version change advances once with original audit/receipt/rollback |
| `ClientLimitOperations`, display names and updated_at | No audience-predicate change; no epoch bump |
| `AdminClubsDbRepository`, publication/profile/calendar | No mapping mutation; club is_active is publication, not staff admission. Private clubs remain eligible resources |
| `ClientClubMappings` Exposed object | Read-only containment; no hidden writer |
| `ClientAccessOperations`, memberships, staff, grants, preparation | User authority, not canonical bot audience; no epoch bump or permission change |
| Raw client_clubs INSERT/DELETE/key UPDATE | Sorted old/new client IDs; affected client UPDATE atomically advances counter. Unchanged keys/created_at-only update does not |
| Club DELETE cascade under V061 | Mapping DELETE trigger advances owning client. RESTRICT descendants still reject with full rollback |
| Client DELETE/reinsert, even same ID/created_at/status | Durable head survives deletion and advances on recreation; old projection cannot recur. No new restriction on deleting a legacy unreferenced client |
| Unreferenced client primary-key change | Old identity is fenced and destination uses its durable history; existing FK restrictions remain |
| Ordinary mapping/head TRUNCATE; history deletes/rewinds | Rejected by PostgreSQL trigger or H2 constraints; no timestamp-based generation |

A column-only prototype reproduced client-delete/reinsert ABA. It was corrected
before completion with the small non-cascading high-water table; pre-fix evidence
is retained separately. It contains no user data or bot credentials. Mapping
ABA, client-ID reuse and club-delete cascade are tested with identical original
mapping timestamps. Concurrent mapping DML shares the same client row lock as
current writers; two inserts retain both bumps. Durable head also serializes
concurrent client deletion/recreation. Business results, grants and existing
runtime authorizers remain unchanged.

**Boundary:** ordinary transactional DML/cascades and all identified repository
writers are covered. Privileged DDL, disabling triggers/referential integrity,
superuser repair, restoring an old database and unknown future writers are not
made safe by this migration. Any such operation requires separately approved
reconciliation/fencing before TARGET activation. DB errors and counter overflow
fail atomically. An INSERT…ON CONFLICT no-op may conservatively advance the
high-water mark in a BEFORE INSERT trigger without changing canonical data; no
current client writer uses that upsert. A future authoritative reader must compare
the durable head/current projection or fail closed, never assume timestamps or
projection equality without validation. Locks prevent lost updates; deadlocks may abort direct out-of-order
SQL, which must retry, never ignore failure. No claim of disaster-recovery safety.

## Credential guarantees and prerequisites

Only opaque UUID references/revision/material retirement are implemented. No
resolver, external secret manager, token bytes, Telegram GetMe verification or
actual revocation of Telegram secrets exists here. Real attestation adapters must
prove public bot identity and stable material identity across aliases; absent
proof fails closed. Logical retirement rejects reuse of an attested retired
material UUID, not arbitrary token bytes falsely labeled with a different UUID.
Credential revision does not replace root generation. Real rotation, activation,
maintenance signed-identity wiring and branded onboarding require later review.

## Production isolation

Runtime `ClientContextRoutes` still constructs the existing Platform connection
from its protected token provider. Mini App24h, exact platform-owner signed access,
client context readers, grants, owner/team/preparation and old invitations retain
their code paths. New main Kotlin is confined to the bindings package; H2 invokes
only the metadata trigger classes during migration/DML. PostgreSQL uses SQL
triggers. No DI registration, request-path evaluator/provider integration, public
route, background job or provisioning is added. V077 and TARGET identity/audience
implementations remain byte-identical. New tables alone cannot activate a bot or
staff authority; ACTIVE inserts/updates are deliberately denied even in tests.

## Verification and focused review

- `./gradlew --no-configuration-cache --console=plain --continue test detektGate
  :core-data:ktlintCheck :app-bot:ktlintCheck` — **PASS**, 1,654 cases,
  zero failures/errors; one pre-existing disabled GuestFlowStart case in core-testing.
  That broad gate preceded the final H2 identifier-mode fix; its closing repeat
  and the two added cases are recorded below.
- Narrow real PostgreSQL repeat plus legacy parity/check-in/promo and affected
  Detekt/lint — **PASS**. New repository cases: H2 26/26 and PostgreSQL 31/31;
  prior mounted-onboarding tests also passed twice on H2 and PostgreSQL (two each).
  PostgreSQL concurrency cases use independent connections and pg_stat_activity
  lock evidence, not sleeps: two SHARE holders, both disable/revoke CAS orders,
  authority loss while waiting, concurrent mapping inserts, client recreation and
  cancellation. Upgrade tests start from populated V077, preserve legacy rows and
  validate latest/repeated migration. Audit/receipt failures roll back exact snapshots.
- `python3 -I -S -B -m unittest discover -s scripts/tests -t . -p 'test_role*.py'`
  — **131/131 PASS**. Previous literal/reference oracles unchanged; only the
  trusted-binding document digest was refreshed after the accepted-scope overlay.
- Full `test -PrunIT=true --no-parallel --max-workers=2` — **PASS**, 2,211 cases,
  zero failures/errors, 11 existing skips (ten native-support opt-in cases and the
  disabled GuestFlowStart). Core-data851/app-bot1,226; full XML in `full-it-xml/`.
  It finished in 24m12s before the final isolated H2 identifier-mode correction.
- Focused self-review then reproduced the H2 uppercase-identifier lookup defect
  with a standalone Java probe. Fixed schema/table quoting using JDBC metadata
  and normalized row labels locally in the **new binding package only**. Existing
  OnboardingSql and previous production Kotlin were preserved. Both H2 modes now
  exercise Flyway, epoch/ABA, create/reference/revoke/new incarnation/read. All
  **57** repository cases rechecked: H2 26 + PostgreSQL31, zero failures/skips;
  one Detekt SQL-line formatting issue was subsequently corrected without changing
  the SQL string. PostgreSQL migration/queries/lifecycle semantics are unchanged.
- Closing full `test detektGate :core-data:ktlintCheck :app-bot:ktlintCheck`
  after the H2 identifier fix — **PASS**, **1,656 cases**, zero failures/errors,
  one existing GuestFlowStart skip. All **61** new cases are covered across
  final rechecks: H2 repository26 + Ktor2; PostgreSQL repository31 + Ktor2.
  Full IT was not needlessly rerun over unchanged unrelated paths after this
  isolated normalization: the changed foundation got all 57 repository rechecks
  and the complete closing JVM/static gate. SQL migrations are unchanged.
- Final scope/preservation/introduced-link/decision-status/whitespace checks and
  project secret gate: see `scope-preservation-links.json`, `manifest-sha256.txt`
  and `secret-scan-final/` beside the full/delta manifests. Current-source and full
  available-history scanner inputs are pinned; scanner allowlists are unchanged.
- Final candidate **138 paths / delta47**. Reviewed previous101: **91 byte-identical**;
  ten explicit docs/source-pin/migration-test edits. All **31** prior production/
  schema paths are byte-identical. Nine additionally affected original PostgreSQL
  test fixtures were copied before edits; one test-only reset helper was added.
  Delta groups: 9 main Kotlin files, 2 migrations, 30 test/fixture files, 5 docs,
  1 source-pin fixture. All paths/hashes are in `candidate-manifest.json` /
  `candidate-inventory.tsv`; the task-only subset is `task-delta-manifest.json`.

The initial full JVM run found five migration fixtures expecting one migration
after V076; each now explicitly validates V077 before V078, preserving original
legacy-data/behavior assertions. Kotlin lint/Detekt findings were fixed without
suppressions or baseline changes. The first full IT found obsolete test cleanup
via TRUNCATE CASCADE conflicting with deliberately protected mappings/history.
Only test-owned container fixtures now clean/recreate their schema, apply/validate
all migrations, then remove known V2 demonstration rows and reset their sequences.
Original case oracles and production guards remain intact. Three redundant subclass
TRUNCATE calls were removed because their base now provides a fresh empty schema.
This reset is test-only; it is not an operational DB cleanup recipe. The first IT
was stopped after saved failures and a JDBC/Flyway wait stack, and a fresh owned
container was used for recheck. The final full IT uses serialized Gradle tasks
(max workers=2); the explicit PostgreSQL concurrency tests retain their real locks.

Focused self-review covers ABA, stale generation/CAS, cross-client/subset FKs,
terminal bindings/material alias retirement, audit/receipt rollback, replay,
current maintenance proof after waits/retry, cancellation and production call
paths. Re-selecting an old revoked incarnation is denied even through direct SQL.
The H2 case-folding finding is also fixed and rechecked; no unresolved finding
in the tested inactive surface. External independent review
is still the next gate.

Local Docker PostgreSQL16 image
`sha256:16bc17c64a573ef34162af9298258d1aec548232985b33ed7b1eac33ba35c229`,
owned containers `clb-inactive-binding-pcz8b7vo` / `clb-inactive-binding-pcz8b7vo-r2`, loopback-only ephemeral ports and
tmpfs storage; only synthetic identities and per-test disposable schemas. No
stage/prod/server/real data. Both task-owned tmpfs PostgreSQL containers and the
diagnostic JShell probe were removed/stopped after checks. No browser tests
needed for unchanged UI/runtime.
No full Managed Bots onboarding, credentials resolver, signed maintenance adapter,
proof v2, TARGET Issue/Claim/Confirm or actual branded connection is verified.

Next slice: independent review of V078, inactive lifecycle and complete mapping
DML/cascade evidence before implementing the read-only durable binding source
adapter; keep all activation and TARGET issuance disabled.
