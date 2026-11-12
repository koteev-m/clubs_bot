# Trusted TARGET bot bindings — PROPOSED contract

## 0. Accepted local foundation — 2026-11-11

**2026-11-12 ACCEPTED for local implementation / implemented inactive foundation:**
the owner approved the five §10 directions. Paired additive V079, a same-Connection
native-transaction-fenced head lease, synthetic default-deny metadata publisher,
finite protected bundle/ephemeral intent composition and control-bound receipt
domain are now local. Bare evidence writes refuse adopted roots. This supports
one exact PLATFORM root/realm; no real config/bot/staff adoption, ACTIVE or runtime
provider registration. [Implementation/participation/evidence](../ops/role-target-db-control-head-foundation.md).
The older design/readiness statements below retain their original date; the real
protected configuration publisher and production updater participation remain absent.

**2026-11-12 local security correction / not activation:** standalone control-head
lease/publisher establish catalog-first transaction-local operator resolution using
the trusted deployment schema. Canonical read IDs and UPDATE RETURNING fences are
independently verified; mismatches roll back all metadata/audit/history writes.
The prior foundation P1 is reproduced by ordinary-role probes; see
[focused correction/evidence](../ops/role-target-db-control-head-foundation.md#role_target_control_operator_p1_fix--local-correction-2026-11-12).
V077/V078/V079, production wiring and the accepted lifecycle are unchanged.

**Next design / not implemented:** [§10 production lease and intent](#production-lease-intent)
defines a maintenance-purpose DB control fence and explicit server intent for the
three existing PLATFORM inactive operations. Separate-process PostgreSQL probes
exercise its primitives; production providers, control-head persistence and config
publication are absent. This is **PROPOSED / READY_FOR_APPROVAL**, not activation.

**Local commit-time follow-up:** [adapter/evidence](../ops/role-target-commit-time-maintenance.md)
connects only platform Disable/Revoke/selected-material retirement to the existing
transactional lifecycle writer. Current signed authority/context requires a shared
lease through commit/rollback; no production lease provider exists, so defaults deny.
The read pin supplies expected state only. Canonical reads use the same locked
transaction, with CAS, bounded full retries, atomic audit/receipt and honest stale
replay. Create/Replace/AttachReference/BRANDED stay unsupported. No V077/V078,
ACTIVE, token operations, production routes/DI or TARGET permission activation.


**Local maintenance read follow-up:** [selector/evidence](../ops/role-target-maintenance-selector.md)
adds purpose-specific authentication in the existing TARGET verifier and read-only
metadata selection. It uses the independent live platform channel, exact current
platform-owner config or separate business proof, and explicit current server pins.
Authentication/server-intent/business providers default absent; business runtime
remains blocked. Pins never authorize writes. No V077/V078, ACTIVE, credential
resolver, production DI/routes or accepted product permissions change.


**Local read-only follow-up:** [durable source/evidence](../ops/role-target-durable-binding-source.md)
reads the exact V078 root/incarnation/credential/mapping aggregate in one statement.
It reports inactive/revoked/stale/inconsistent metadata, never authorization. V078
and the P1/P2 guards are unchanged. No production selector/credential resolver,
verifier/audience conversion, ACTIVE lifecycle or runtime wiring is supplied.


**Local P1/P2 correction:** independent review found session-schema masking and
unfenced child DML in the unpublished foundation. The
[focused correction/evidence](../ops/role-target-binding-p1-p2-fix.md) pins all V078
PostgreSQL resolution to the trusted Flyway schema and atomically fences child
writes on H2/PostgreSQL. A row-level child guard uses root NOWAIT to fail closed
on reversed lock contention; service CAS/receipt pins use the final locked fence.
Counters are monotonic storage revisions and may advance multiple times per
composite command; use returned pins. Historical +1 design/reference rows below
are an abstraction, not an exact AS_BUILT command-count promise. ACTIVE and all
runtime integrations remain prohibited. This is only the two authorized fixes.

**ACCEPTED architecture / limited local implementation:** the owner approved the
five directions in §9 for `CLB-role-target-inactive-binding-foundation`: independent
root/incarnation and platform/branded boundaries; terminal lifecycle/CAS/generation;
opaque credential retirement; complete durable mapping fencing; shared locks,
revalidation/audit/receipt. This does not approve bot activation, changed product
permissions, proof v2, TARGET issuance or branded onboarding.

**AS_BUILT / implemented but inactive:** paired additive V078, an internal
platform-only PENDING/DISABLED/REVOKED lifecycle, opaque reference/material history,
and transactional mapping metadata triggers. ACTIVE is deliberately rejected by
both SQL and operations; default-deny maintenance/bot/reference providers have no
production adapter. No current NightConcierge credential is imported or rotated.
[Implementation, writer inventory and H2/PostgreSQL evidence](../ops/role-target-binding-foundation.md).

**Evidence-driven refinement of §5:** a column-only counter does not survive
physical client deletion/recreation. V078 keeps one durable high-water row per
touched client in `target_client_mapping_epochs`, independent of client FK deletion,
and projects the current epoch into `clients.target_mapping_epoch`. Existing client
rows start at metadata epoch=1 without adopting/backfilling roots, bindings or
staff. Heads are created lazily on mutation; new clients create theirs atomically.
Ordinary mapping/client DML and club-delete cascades advance the same fence;
client-key reuse cannot reset it. This preserves legacy deletion behavior instead
of forbidding all legacy client deletion. Privileged DDL/disabled triggers/old DB
restore remain separate activation/recovery prerequisites. No current audience
provider consumes this epoch as authorization.

**Still PROPOSED / unimplemented:** production maintenance lease/intent providers, credential
resolver and material attestation, activation/scope writers, branded onboarding,
live durable source integration/proof v2, TARGET Issue/Claim/Confirm and cutover.
The design-stage evidence below is retained historically; statements that no
schema/Kotlin existed describe that earlier stage, not this local foundation.

**ROLE_TARGET_BINDING_CONTRACT: PROPOSED / READY_FOR_APPROVAL.**
CLB-role-target-trusted-binding-contract, 2026-11-11. Design and bounded offline
reference only; no registry, schema, credential, Kotlin or runtime change.
Base/fresh remote main `5550b944990d8c1c93c0a5012d31db209f93ea6d`;
registered detached worktree `/private/tmp/clubs-role-workspaces-proposed-20261109`.
PROJECT_IDENTITY_OK; active Clubs Bot instructions, foreign=0. Both reviewed
inventories matched all 97 paths before edits; prior manifest SHA-256
`0147d4ae562d0382aa439a85df89345008d294340e58dc10468626aebf90cf3f`.
Baseline, checks, synthetic results and full/delta manifests:
`/private/tmp/clb-role-target-binding-contract-4ea9qftp/`.

## 1. Authority and current evidence

**ACCEPTED product:** [DEC-039–042](OPEN_DECISIONS.md#dec-039--telegram-identity-владельца-платформы),
[SPEC client boundary](PRODUCT_SPEC.md#client-context-technical-contract),
[eight-role model](ROLE_WORKSPACES_PROPOSED.md#role-model-20261110).
NightConcierge is the shared platform entry; a branded bot belongs to one client
and serves only explicit clubs of that client. Neither channel identity nor
membership/legacy OWNER/GLOBAL supplies TARGET business/staff authority. This
contract does not change product permissions or accept earlier Managed Bots choices.
**All new technical choices below are PROPOSED**, including field names, locks,
persistence and lifecycle authority.

**AS_BUILT / implemented but inactive:**

| Source | What it proves / what is absent |
|---|---|
| [AppConfig](../../core-domain/src/main/kotlin/com/example/bot/config/AppConfig.kt), [platform adapter](../../app-bot/src/main/kotlin/com/example/bot/routes/ClientContextRoutes.kt) | Existing protected platform credential and configured OWNER_TELEGRAM_ID; one Platform connection. No persisted bot→client binding |
| [TrustedBotConnection](../../app-bot/src/main/kotlin/com/example/bot/plugins/ClientContextAuth.kt), [ClientAudience](../../core-data/src/main/kotlin/com/example/bot/data/client/ClientContextRepository.kt) | Opaque credential provider, exact process object, server restriction. connectionKey/finite in-memory set are not durable identity or revocation state |
| [TelegramClient](../../app-bot/src/main/kotlin/com/example/bot/telegram/TelegramClient.kt) | Positive cached bot ID from GetMe for its configured client; not ownership, current credential revision or a lifecycle registry |
| [Canonical reader](../../core-data/src/main/kotlin/com/example/bot/data/client/JdbcCanonicalClientAudienceRepository.kt), V061/V066 | Exact clients/client_clubs/clubs, active/unarchived client, lifecycle_version, mapping created_at. No mapping generation/tombstone or bot binding |
| [TARGET verifier](../../app-bot/src/main/kotlin/com/example/bot/plugins/target/TargetIdentityVerifier.kt), [canonical provider](../../app-bot/src/main/kotlin/com/example/bot/plugins/target/CanonicalTargetAudienceProvider.kt) | Strict HMAC, age<300с/0future, finite explicit server pin and fresh canonical snapshots. Current binding source defaults deny; no real source or production wiring |
| [Assignment foundation](ROLE_ASSIGNMENT_CONTRACT_PROPOSED.md#0-локальная-реализация-и-граница-активации) | V077 and platform membership revoke adapter exist locally/inactive. No binding/admission proof v2 or TARGET invitation writer |

The existing user-authorized ClientContextRepository is not a bot-binding source.
Private `clubs.is_active=FALSE` is publication state, not denial of legitimate
preparation. Clients/client_clubs are reused; no second tenant/membership catalogue.
[Managed Bots direction](MANAGED_BOTS_DIRECTION.md#first-managed-bot-contract)
retains historical proposals; wizard, manager selection, SDK and provisioning stay out of scope.

## 2. Minimal logical model

Three logical records, not a SQL migration or production API:

| Record | Required invariant |
|---|---|
| Connection root | Stable never-reused UUID; unique positive verified Telegram bot ID; immutable kind PLATFORM/BRANDED and immutable branded client_id; monotonic positive aggregate version and security generation; current_binding_id; verified identity/provenance reference. Root survives every revoke and reserves physical bot identity |
| Binding incarnation | New UUID, exact root FK; PENDING/ACTIVE/DISABLED/REVOKED; immutable creation/activation evidence; current immutable credential revision reference; reason and audit references. One current incarnation per root; REVOKED never leaves terminal state |
| Branded club members | Finite nonempty set for ACTIVE branded binding, exact same-client canonical mappings, unique binding/club. May be normalized child rows; never wildcard, inferred all-clubs, duplicate mapping or independent clients |

One aggregate version/generation on the root serializes **all** child changes; a
second independently writable child revision is unnecessary. created_at/updated_at
are audit metadata only. IDs, provenance and root kind/client cannot be patched;
scope/credential/status changes require named operations. A new incarnation after
terminal revoke keeps the root and higher generation, but uses a new binding UUID
and fresh credential revision. No hard-delete/reuse of roots, binding IDs, counters
or client IDs. Cross-client reparenting or PLATFORM↔BRANDED conversion is forbidden,
including replacement chains; business/bot ownership transfer needs a separate contract.

**PLATFORM:** client_id=NULL, no tenant-wide allowed-clubs list. It serves multiple
independent businesses. Each TARGET operation gets one exact client/club from a
server-authorized immutable intent, verifies canonical mapping and user authority,
then derives a finite per-operation audience restriction. Platform audience alone
always denies client mutation. Do not seed one permanent platform/client binding
per business or turn the existing platform bot into a single-client bot.
Issue selectors start as untrusted locators: canonical lookup/current issuer proof
under locks must produce the approved typed intent. Claim/Confirm obtain scope
from the locked invitation. Platform preliminary audience also needs that explicit
server intent; neither URL IDs nor a client selected in the browser supplies it.

**BRANDED:** immutable one client; explicit finite club set. New club creation does
not expand it. Adding/removing a member is a separate authorized scope operation;
it gives no staff/grant/finance authority. A network-manager role does not widen
the bot or the user's clubs. Private clubs may match this resource audience;
guest publication and staff authorization remain separate checks.

## 3. Lifecycle, version and generation

All operations require current lifecycle authority, exact IDs, expected root
version/generation, durable idempotency, one transaction and audit. Recommendation
for the first controlled metadata slice: only configured exact platform-owner
identity (DEC-039), with fresh signed identity <300с, separate maintenance purpose
and explicit intent. No legacy OWNER/GLOBAL or implicit client bot.manage grant.
Client-managed lifecycle policy remains a separate future approval.

Maintenance authentication must not depend on the binding it is re-enabling:
use the separately trusted current platform maintenance credential/identity root.
Missing/unavailable maintenance identity denies; no unauthenticated recovery or
fallback staff principal. This is a proposed future adapter, not a change to the
working platform-owner route. Real credential-loss recovery remains an explicit
protected operational procedure, outside this contract.

| Operation | Transition / fence |
|---|---|
| Create | Verified root + fresh PENDING binding; version=generation=1 for new root; no TARGET use |
| Activate | PENDING or DISABLED→ACTIVE; current credential/bot attestation, status, scope and canonical checks; version+1, generation+1 |
| Change branded set | ACTIVE or DISABLED only; exact same-client nonempty replacement set under client/root locks; version+1, generation+1 even if clubs return to a previous set |
| Disable | ACTIVE/PENDING→DISABLED; version+1, generation+1; no old proof admission. Explicit activation is required to resume |
| Revoke | Any nonterminal→REVOKED; version+1, generation+1; no usable credential selection; preserve history |
| Rotate | First disable/fence (version+1/generation+1), then stage/verify new credential outside DB transaction, then explicit CAS activation with new immutable revision (another +1/+1). Failure leaves DISABLED |
| Replace revoked binding | Fresh PENDING binding UUID, higher root version/generation, fresh credential/provenance; then separate activation. Old row remains REVOKED |

All security changes increment both counters. Audit-only metadata, if a later
writer needs it, increments version only and never rewrites provenance. Identical
idempotent replay/read does not bump either. Empty active branded scope, token/client
change disguised as metadata, reactivation/rotation of REVOKED and counter reset/
overflow are rejected. Rollback before commit has no durable effect; after a
committed revoke no application/config rollback may lower counters or select old IDs.

## 4. Credential boundary and rotation

Telegram bot ID is public identity, not a credential. Root ID is our durable identity;
binding ID is an incarnation; credential reference is an immutable secret revision,
never a token, URL containing token, mutable alias or a hash offered as authentication.
An isolated resolver must check `(root, binding, bot ID, generation, credential revision)`
and return a private handle for HMAC. Missing/retired/ambiguous revisions deny;
never try another token or default platform sender. No cross-request authority cache.

The protected secret backend is separate from ordinary client records/audit/receipts/
DTO/browser storage. Raw token/initData/Idempotency-Key never enter those stores or
logs. Safe audit contains IDs, old/new generations, revision references, actor,
reason and command/intent digest. Existing AppConfig is not this versioned resolver;
a secret-store interface, immutable revision retirement and secure provisioning are
prerequisites. No external secret manager is selected or installed here.

The resolver must also attest a stable opaque **material identity** across aliases
of the same secret and retain terminal material retirement. A new reference pointing
to retired token bytes is not a fresh credential and must deny. How the backend
compares protected material is an implementation choice; do not publish token
hashes or treat an opaque material ID as a credential. Unknown retirement provenance
blocks activation. Commit DISABLED + durable retirement marker first; only then perform
external retirement/staging. Activation requires that marker and a verified fresh
material attestation; a new reference alone is insufficient.
Never perform secret-store side effects inside a DB transaction that could roll back.

Rotation keeps bot ID/kind/client/club set unchanged. Old revision is fenced before
external work and is never selected again, even if Telegram would still accept it.
Candidate verification must prove the same bot with the newly resolved credential;
old cached GetMe ID is insufficient. No dual-token overlap. Audit failure on final
activation leaves the earlier DISABLED fence intact; it does not restore old token.
Concurrent revoke wins against later rotation completion through CAS/terminal state.
For the shared platform bot, actual Telegram token rotation also affects legacy
AppConfig/users; it needs a separately approved coordinated runtime procedure.
TARGET-only metadata must not silently rotate or disable the current production bot.

## 5. Canonical mapping ABA

**Recommendation: one coarse durable `clients.target_mapping_epoch`**, not one new
mapping-history service. Initialize explicitly during a future controlled migration;
unknown/NULL epoch or unverified writer coverage denies TARGET. Every INSERT/UPDATE/
DELETE of client_clubs, including delete/reinsert with identical created_at and
club-delete cascades, advances the affected existing client epoch in the same DB
transaction under its client lock. Moving a mapping, if ever separately authorized,
requires both client locks in sorted order and bumps both. Current product transfer
rules are not expanded by describing the fence. Client root must not be deleted/reused.

All mapping writers/cascades/manual privileged writes must participate; recommend
DB-enforced bump/guard plus writer lock-order checks. A timestamp comparison or
cooperative helper alone is insufficient. Current creation paths are
[ClubOnboardingOperations](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubOnboardingOperations.kt)
and [DirectClubOperations](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/DirectClubOperations.kt);
V061 permits club-delete cascade. Unclassified external/direct writers close the
activation gate. No such field/guard/migration or writer change is made now.

Proof pins `(client ID, target_mapping_epoch, lifecycle_version, exact club)`.
After delete/reinsert old proof differs even if timestamps, IDs and allowed set
look identical. Adding a club invalidates pending proofs conservatively but **does
not add it** to a branded binding or any staff assignment. Fresh proof revalidates
the unchanged finite set. Epoch is resource fencing, not staff revocation/regrant.

## 6. Atomic TARGET revalidation

Future server-internal proof must pin root/binding IDs, verified bot ID, credential
revision, root version/generation, exact client/club, client mapping/lifecycle
epochs, signed actor ID and validity window. Existing minimal TargetVerifiedIdentity
does not contain these fields and cannot be promoted to mutation authority.
Keep its preliminary provider useful, but require a new reviewed proof/transaction
adapter; nonce/MAC proof v2 is still unimplemented.

Order for Issue/Confirm: fresh strict signed identity → transaction → existing
actor lock → exact client lock → sorted connection-root locks → child invitation/
membership/admission/source locks in the existing contract's deterministic order.
Connection readers hold a compatible PostgreSQL SHARE lock through commit;
lifecycle writers use an exclusive UPDATE lock. H2 may serialize more strictly.
Shared client lock already serializes same-client writers; cross-client operations
can share the platform root's read lock. Do not use only a JVM mutex.

Global platform lifecycle takes actor then connection root only and **never waits
for client/child locks after acquiring root**. Branded scope writers take actor→
client→root. Root-only lifecycle must use independently verifiable maintenance
authority, not acquire user/client authority after root. Mapping writers take
client before mapped children. If these orders cannot be enforced, fail closed
before mutation. Deadlock/serialization retry restarts all authority checks.

After lock waits, verify again: ACTIVE current binding and exact version/generation/
credential, fresh Telegram identity, current active/unarchived client and mapping
epoch, issuer authority source/ref, role/policy/scope, claimed recipient identity
and admission/member epochs plus all-invitation cutoff. FIRST/unclaimed handling
follows [TARGET identity contract §5](ROLE_TARGET_IDENTITY_CONTRACT_PROPOSED.md)
and does not demand recipient staff membership merely to authenticate audience.
Recipient claim attestation must be trusted, unexpired and match its current binding
pins; raw stored initData is not an acceptable substitute. Missing proof denies.

Assignment/invitation mutation, audit and durable receipt commit atomically.
Preliminary snapshots/config caches/HTTP responses never authorize. Receipt keys
and intent hashes bind actor, connection/binding, client/club, operation and exact
source intent. Replays recheck current fences/authority before returning current
authorized status; they never reissue or replay a saved ACTIVE payload. Same key
with changed intent conflicts. Late responses carry pins and cannot overwrite a
newer UI revision; server enforcement applies regardless of UI behavior.

If Confirm gets the root lock first it may commit before Revoke; Revoke then fences
subsequent actions. If Revoke commits first, old Confirm/proof/receipt denies with
no partial source. Binding revoke closes a channel; it does not silently revoke
independent lawful staff assignments or other bot channels. No promise to retract
already committed operations, accepted Telegram requests or disclosed data.

## 7. Compatibility, gates and first slice

Legacy 24h Mini App auth, exact platform-owner access, client onboarding, owner/team/
preparation, invitations, grants and routes remain unchanged. PLATFORM remains
multi-client. Existing V077/tombstones and platform membership restore semantics are
preserved. Branded clients and new TARGET readers/writers are not activated by docs.

Before future activation: approved logical model and lifecycle authority; reviewed
paired additive H2/PG migrations; exact root/binding IDs/provenance without inference
or real staff backfill; secret resolver and bot attestation; full mapping-writer/
cascade coverage and durable epoch; proof v2/compound adapter; real PostgreSQL
lock/retry/audit-failure tests and legacy gates. Prior generation-less snapshots
remain preliminary only, never automatically upgraded. Restore from an old DB
backup is a separate recovery problem and must invalidate/reconcile TARGET state
before reopening it; this offline contract does not prove disaster-recovery safety.

**First implementation slice after approval:** local, inactive **platform-only**
durable root + binding lifecycle and client mapping-epoch foundation, with synthetic
immutable credential references/default-deny resolver. Paired disposable H2/PG
tests must prove CAS, revoke/replay, both lock orders and ABA/cascade guards.
No real token operations, branded onboarding, invitation/API/DI or staff activation.
Read-only canonical source adapter follows only after these durable fences exist;
configuration files alone cannot provide this contract.

## 8. Synthetic acceptance and limits

[Offline reference](../../scripts/tests/trusted_binding_reference.py),
[literal synthetic scenarios](../../scripts/tests/fixtures/role-target-trusted-binding.json),
[tests](../../scripts/tests/test_role_target_trusted_binding.py) exercise both
ordered conflict outcomes, exact scopes, generations, ABA, rotation, replay and
atomic rollback. Synthetic client/grant/credential markers are not real data.
Old identity HMAC/freshness/duplicate invariants are reused from the prior reference;
no new production validator or one-time-initData claim is introduced.
Results and per-scenario outcomes are in the external evidence directory above.
Ordered reference steps prove the proposed state machine, **not** PostgreSQL locks,
secret-store atomicity, actual Telegram identity or a working bot registry.
The material-retirement set is a synthetic durable prohibition, not a secret-store
implementation or proof of external token invalidation.

**Verification:** 60 literal scenarios and 15 new reference tests PASS; all 116
previous reference tests PASS (131 total). Cases include both conflict orders,
global platform revoke across independent clients, new branded incarnation with
old receipt, reissued retired material under a new reference, membership/policy/
admission changes, unknown/overflow fences and cancellation. Previous 97-path
production/test evidence is byte-preserved except the four explicit docs/source-pin
updates; final full candidate101 paths / delta8. Link/status/preservation/diff and
secret-scan results are recorded with the manifests. No JVM/PostgreSQL rerun for
this docs/reference-only slice; earlier runtime evidence is not new binding evidence.

## 9. Five decisions for separate owner approval

1. Root identity + terminal binding incarnations; PLATFORM stays multi-client,
   BRANDED is immutable single-client with finite explicit clubs; no cross-client rebind.
2. PENDING/ACTIVE/DISABLED/REVOKED lifecycle, monotonic aggregate generation/CAS,
   and first-slice lifecycle authority limited to exact platform maintenance owner.
3. Immutable credential revision/material-retirement interface and disable-first rotation with no overlap;
   platform runtime token changes require separate operational approval.
4. Coarse per-client mapping epoch with complete DB/writer/cascade fencing for ABA,
   accepting conservative invalidation of pending proofs when a new club appears.
5. Actor→client→connection/children lock contract, root-only global revoke,
   commit-time revalidation/current-only receipts, and the inactive platform-only
   foundation slice in §7. No production activation is included in this approval.

<a id="production-lease-intent"></a>
## 10. Production authority/context lease and server intent — PROPOSED

**ROLE_TARGET_LEASE_INTENT_CONTRACT: READY_FOR_APPROVAL.**
CLB-role-target-production-lease-intent-contract, 2026-11-11. This section extends
the independently reviewed commit-time adapter; it implements neither provider.
ACCEPTED product/architecture and historical decisions above remain unchanged.
AS_BUILT locks below are local code evidence, not proof of runtime deployment.
Evidence, command logs and full/delta manifests:
`/private/tmp/clb-lease-intent-contract-o47mab22/`.

### 10.1 Choice and missing guarantee

| Option | Assessment |
|---|---|
| A: existing users/client/root locks + CAS | Sufficient for those DB rows. Insufficient for process-local OWNER_TELEGRAM_ID, authentication connection/context and server selection; another JVM/config publisher can change them without acquiring any of these rows |
| **B: one DB control head + existing transaction locks** | Recommended minimum. One maintenance-purpose head per stable platform authentication realm; shared row lock on the mutation Connection, exclusive lock for context/config publication. No expiry or second lock service |
| C: external distributed lease system | No demonstrated need. Redis/clock leases would add failure modes without replacing DB CAS or updater participation |

All instances must use the **same authoritative writable PostgreSQL primary and
schema**, including the providers. Read replicas cannot supply this decision.
Single-writer database/failover durability is an infrastructure prerequisite;
independent primaries/split brain and restoring an old DB are not solved here.
H2 remains local parity only. No existing advisory lock protects this realm:
booking HOLD and core-data seed locks have unrelated resource identities.
The existing deployment shared-staging flock and workflow concurrency coordinate
release operations in their own scope; neither is taken by maintenance JDBC
transactions/config readers across replicas and neither supplies this lease.

**PROPOSED control head**, no table/migration created here: never-reused internal
realm UUID; monotonic positive version/generation; DISABLED/ENABLED maintenance
gate; approved immutable protected-bundle fingerprint/reference; publication
provenance. The bundle binds exact positive configured platform owner, public bot
identity, live authentication connection identity/revision and finite server intent
manifest. The head is a coherence/revocation fence, never an ownership grant or
replacement for signed identity/exact protected config. No token/initData, raw
idempotency key, browser input or V078 ACTIVE status belongs in it. Default absent
or DISABLED denies. The live NightConcierge connection is not imported into V078.

Each process must match its immutable local protected bundle to the current head;
a stale replica must deny even when its local signature/owner checks succeed.
The bundle pins its intended durable head generation/publication reference as
well as its content fingerprint; a process must not adopt a newer generation just
because the content hash happens to match. This prevents config ABA without a TTL.
Publishing changed owner/authentication/selection uses **fence first**: exclusive
head lock → DISABLED, new generation, audit/receipt → commit; only then roll out
the new protected bundle; explicitly CAS-publish its matching approved reference
under exclusive head lock. Never lower a counter/reuse a publication reference or
roll back to an old enabled generation. Publisher changes head/config policy only,
not binding roots or user/staff rows in that transaction. A failed rollout stays
disabled. These are future internal administrative operations, not new public APIs
or permission for live config/secret changes. Configuration consumers in W18/W19
do not currently implement this protocol: **production use remains blocked**.

### 10.2 Shared lease and lock ordering

Resource is the exact realm head row, selected from trusted configuration, never
a caller-supplied client/root. A handle acquired before the lifecycle transaction
is inert: it must acquire/check `SELECT … FOR SHARE` **on the mutation Connection**
before actor/root locks. An exclusive publisher uses `FOR UPDATE`; ordinary head
DML must acquire the same conflicting row lock and enforce CAS/monotonic history.
Future head guards must reject delete/reinsert/reset and protect all publication
writers/cascades. No session flag, in-memory mutex or second pooled connection
can substitute for participation.

Required small interface change for the future implementation: app
`MaintenanceMutationStateLease.current()` becomes explicitly
`current(connection: Connection)` (or an equivalent explicit transaction bind).
Core `BindingMaintenanceLease.identity(Connection)` already supplies this context.
The current app `leases.acquire()` also lacks an admitted action/material argument:
the future bridge must pass the normalized trusted intent into acquisition and
revalidate its operation/material/expected-state/key binding, not merely its root.
Do not infer it through ThreadLocal, coroutine thread identity or a separate
connection/session advisory lock. No implementation change is made in this task.
The signed verifier must read the same pinned immutable authentication bundle;
if its callbacks cannot be tied to this head generation, deny.

Blocking order for the supported PLATFORM path:
**head SHARE → actor NO KEY UPDATE → root UPDATE → child → audit/receipt → commit**.
There is no client/admission lock: PLATFORM has no client or mapping epoch.
Publisher: **head UPDATE only**, then audit/receipt; never actor→head or root→head.
Existing staff path stays **actor→client→sorted admissions→assignments→delegations**;
mapping epoch path is **client→durable epoch head**. No staff role/membership is
part of the supported platform maintenance authority predicate.

Direct child DML can own a child before the V078 BEFORE trigger, which acquires
root **NOWAIT**. This reverse edge is not a blocking wait; `55P03` aborts. The
calendar read resolver's client→actor is also NOWAIT/savepoint, not a new blocking
edge. Physical already-locked child rows, FK checks and arbitrary multi-row raw SQL
can still wait/deadlock; the protocol graph does not promise every database
transaction is deadlock-free. Abort/retry rules remain mandatory. Never add a
blocking head lock inside an already child/root-locked trigger.

### 10.3 Complete bounded updater participation inventory

Exact paths, entry points, classifications, 88 source hashes and the lock graph:
[inventory fixture](../../scripts/tests/fixtures/role-target-lease-intent-inventory.json).
The discovery test covers literal SQL and relevant Exposed writers; dynamic writer
dispatch, triggers, migration/ops sources and non-writers were also read manually.
This is the repository inventory at the pinned candidate, **not** a claim to know
external operators, future writers or all infrastructure. NKU = NO KEY UPDATE;
U = UPDATE row lock. Application locks are transactional across JVMs/processes.

| ID / writer and operations | AS_BUILT locks/fence | Required participation / boundary |
|---|---|---|
| W01 ExposedUserRepository.ensureMinimalIdentity, OnboardingSql.provision, legacy BookingWebApp ensure-user | Unique TG ID; insert/index/FK locks | INSERT only. No runtime TG-remap API found; maintenance does not provision users |
| W02 PrivacyService anonymize/backfill | Users row UPDATE | Phone/privacy fields only; does not remap TG or revoke configured owner |
| W03 JdbcClientOnboardingRepository mutations/replays | Actor NKU→client/domain; command+audit TX | Calendar resolver reverse actor probe is NOWAIT. Does not provide maintenance context |
| W04 InvitationOperations client create/confirm/accept/revoke | Actor→client→invitation; compatibility guard | Client activation triggers mapping epoch; legacy invitation stays legacy |
| W05 StaffInvitationOperations claim/confirm; ClubPreparationOperations delegate/confirm | Actor→client→invitation/staff; exact compatibility fence/watermark | No TARGET or maintenance authority |
| W06 ClubTeamOperations change/disable; ClubStaffAccess.replace/platformRevoke | Actor→client→staff/grants; mixed-history guard | Package replacement remains incompatible with mixed TARGET sources |
| W07 ClientAccessOperations revoke/restore + PlatformMembershipSourceRevocation/Invalidator | Actor→client→sorted admission/source rows; member CAS; epoch/tombstones; audit/receipt | Terminates V077 sources, restore does not resurrect. Not configured-platform-owner revocation |
| W08 JdbcRoleAssignmentFoundation + Admission/SourceWriter issue/revoke/delegate/disable/readmit | Actor→client→sorted admission, assignment, delegation rows | Inactive, source epochs/CAS; platform owner excluded. No binding lock |
| W09 PromoterAdminRepositoryImpl.setPromoterAccess/syncUserRole | Mapped client U→legacy access/roles; exact compatibility guard | Unmapped legacy stays legacy; no platform authority |
| W10 DirectClubOperations / ClubOnboardingOperations create | Actor→client→club/mapping; trigger→epoch head | First/second club bootstrap remains legacy; new club never joins binding automatically |
| W11 ClientLifecycleOperations archive/restore, ClientLimitOperations | Actor→client; lifecycle trigger→epoch head | Audience predicates bump; quota/display-only updates do not |
| W12 AdminClubsDbRepository; CoreDataSeeder | Club/index locks; unrelated seed advisory transaction lock | Publication is not admission; deletion cascades fenced by W16 |
| W13 Bare JdbcInactiveBindingFoundation / BindingWriter / BindingValidation / BindingAudit | Actor→root U→child guards; terminal/CAS/final receipt | No production shared lease. Keep unwired; reject/quiesce this alternative entry for any future adopted maintenance root until separately adapted |
| W14 Guarded JdbcCommitTimeBindingMaintenance + TargetCommitTimeMaintenanceAdapter | Required lease→actor→root→child; same-Connection canonical pre/postchecks; audit/receipt | Real lease/intent absent. Only this entry may consume future admitted platform intent; same-Connection port change required |
| W15 V078 PG guards / H2 TargetMetadataGuardTrigger; ordinary root, binding, material, revision, scope, receipt DML | Child→root NOWAIT; root v/g bumps; terminal/immutable records | SQL consistency/freshness, **not signed authority**. Direct DML can stale an intent, cannot masquerade as an authorized receipt replay. Administrative write access remains a distinct trust boundary |
| W16 ClientMappingEpochTrigger / PG functions; client/mapping INSERT/UPDATE/DELETE, club/client FK cascades | Client(s sorted)→durable epoch head; raw DML may already hold mapping/club | P1 qualified schema; projection/head monotonic and ABA-safe. Out-of-order raw SQL can abort. Membership is not an audience predicate |
| W17 Authentication/context/selection/business provider ports | Process callbacks/test JVM RWL; no production lease | Must join new durable head before production. Business provider absent; all BRANDED writes denied |
| W18 AppConfig/Env and platform connection construction | Startup protected config, no shared generation | Exact configured owner still mandatory. Immutable bundle must match head in every process |
| W19 Repo config/deploy consumers and external protected config/secret publishers | No head fence today | Fence-first publisher + operational enforcement/replica proof missing. Unknown publisher → maintenance disabled |
| W20 Historical V2/V061/V062/V065/V067/V068/V074/V075/V077, seed-demo, migration tools | Migration/SQL coordination, not actor authority lease | No rerun/backfill/adoption; quiesce maintenance for incompatible maintenance/migration/recovery |
| W21 Ordinary raw users remap/delete; raw staff/source SQL | Users UPDATE/unique/FK, actor lock and post-lock TG lookup in guarded path | Remap serializes or denies after wait. Raw staff SQL can bypass app client contract: still a staff cutover blocker, never a maintenance authority source |
| W22 Durable/Canonical/ClientContext readers, selector | MVCC snapshot, no authority lock | Not writers; successful read pin is expected state only |
| W23 BookingRepository HOLD advisory transaction lock | Separate namespace/resource | Cannot prove maintenance serialization |

W15/W16 ordinary DML is storage-fenced; it is not required to acquire the config
head because it does not publish owner/authentication/intent policy. It can make
a command fail/stale via the locked root or client epoch. W13/W17–W19 **cannot be
declared production-safe yet**. Privileged DDL, disabled guards, TRUNCATE bypass,
backup recovery and external configuration publication are explicit activation
gates. Do not equate these exceptions with a supported bypass of signed commands.

### 10.4 Authorized server-selected intent

Use an **ephemeral typed admission**, not a new durable intent table/protocol, for
the immediate synchronous operations. Trusted source is an explicitly provisioned,
protected finite maintenance manifest matching the current head bundle. Each
entry binds stable target root/public bot ID, exact incarnation and allowed subset
of **Disable / Revoke / retire selected AVAILABLE material**. No wildcard/first
matching root; unknown source/entry denies. New incarnation requires a new explicit
manifest publication; root version advances do not broaden the manifest.

An HTTP root/action/material may be a lookup hint only. Resolve it against that
trusted manifest, then bind: freshly verified signed actor and live auth context;
exact positive protected owner; stable authority realm/connection UUID and head
publication/generation; exact target root/incarnation; operation; complete immutable
expected binding snapshot/fence; selected credential revision/material if retiring;
idempotency key. Scope is unbound PLATFORM; any client/club or BRANDED selection
denies. Body/URL/connectionKey/username/membership/legacy OWNER/GLOBAL/old pin are
never authority. A valid signature alone supplies no role or invitation right.

Use the existing SHA-256 key/request domain, audit and V078 durable command result.
Stable authority connection UUID is a trusted receipt namespace, never a random
per-request ID or a user-supplied key. The current authority publication is audit
provenance; its equality/generation is checked under the head lock on every attempt.
Any future command-domain adjustment must preserve explicit compatibility: do not
overwrite/alias old guarded receipts or generate a different command silently.
No raw initData/token/key in audit/logs/response/storage. No new MAC/bearer proof.
Cross-request approval/queue/transfer/expiry would require a separate persistence
decision; it is not smuggled into this synchronous contract.

The admitted expected-state payload stays immutable throughout one command.
`current(connection)` refreshes authority/head/policy, not that payload: it must
not replace the expected reference with the writer's own new root fence during
post-write identity checks. Canonical pre-state and legitimate post-state are
checked separately by the existing guard. An old expected fence may be admitted
only as expected data for exact receipt resolution; a new mutation still requires
current CAS. CURRENT/STALE replay is diagnostic, never renewed authority.

### 10.5 Exact transaction, replay and failure protocol

1. Parse strict safe-channel credentials with the existing TARGET verifier; admit
   a request only against the protected finite intent source. Keep auth material
   transient. Read pin supplies expected state, never an authorization decision.
2. Open the existing lifecycle transaction; trusted qualified schema; acquire
   head SHARE on this Connection before actor/root. Re-read current enabled head,
   protected bundle, context, exact owner and server-intent entry under that lock.
   Reverify existing Telegram HMAC, positive user, **0≤age<300s, no future auth_date**;
   no fallback to legacy principal. Check freshness after waits and before commit.
3. Actor NKU, re-read Telegram→DB-user mapping; root U; revalidate current
   root/incarnation/status/version/generation, all credential metadata and immutable
   expected state on this Connection. PLATFORM client/mapping fields must be null.
   Existing BRANDED/mapping/admission predicates are not skipped: that branch is
   unsupported and denied. Future Issue/Confirm still requires client/admission,
   issuer/policy/mapping revalidation in its own transaction.
4. Re-check intent/authority and command expected CAS. Replay is also authorized
   freshly before disclosing a receipt: exact prior command → CURRENT only for
   matching final aggregate fence, otherwise honest STALE; never reexecute or
   restore sources. Altered immutable request conflicts. Lost HTTP response is
   resolved by that same command/key under current authority, not a bearer receipt.
5. Execute the existing inactive writer only. Disable preserves its existing
   disabled transition; Revoke is terminal and retires all AVAILABLE root materials;
   selected retirement requires exact selected AVAILABLE revision/material and
   leaves binding DISABLED with no selected reference. No token is resolved.
6. Validate post-state/final aggregate fence (composite commands may tick several
   times); transactional audit + receipt; repeated context/freshness/cancellation
   checks; commit. Keep all locks through commit/rollback. Unknown key never reserves
   a result on failed authorization. Audit/receipt failure rolls back everything.

`40001`/`40P01`: full rollback and release, bounded **two** retries as implemented;
new handle/transaction, current head/bundle/identity/intent/DB-state checks again.
Never refresh the old expected pin or continue a failed transaction via savepoint.
`55P03`: fail-closed without automatic retry; `57014`/cancellation: propagate after
full rollback. Statement/lock/transaction timeouts must be bounded and configured
without weakening freshness; no unbounded retry or cache. The future provider must
not hide SQLException state or CancellationException from the existing boundary.
Initial PROPOSED limits: lock_timeout 2s, statement_timeout 5s, 10s whole-attempt
deadline and idle-in-transaction timeout; two retries with existing 10/20ms delays.
Timeouts abort the transaction/backend before a lock can be reused; they do not
grant a new holder permission to write while an old transaction remains alive.

No lease TTL/renewal/clock stealing: the row lock exists until the server transaction
ends. Crash/disconnect/backend termination rolls back/releases; an old connection
cannot later resume its aborted transaction. Reconnect/restart is a fresh attempt,
not resurrection of a handle. A network timeout is not proof the commit failed:
use fresh authorized durable resolution, never blind compensation. Cancellation
must cancel/close JDBC work and roll back, not merely abandon a coroutine holding
a pooled transaction. A slow healthy holder delays an exclusive publication; bounded
timeouts/administrative cancellation handle availability, not expiry-based regrant.
No promise to revoke already committed operations or already disclosed data.

### 10.6 Evidence, approval and one next slice

[Reference tests](../../scripts/tests/test_role_target_lease_intent.py): 24 literal
admission cases, pin/authority/bundle negatives, full bounded writer discovery,
88 source hashes, blocking graph with explicit NOWAIT reverse edges. These are
synthetic oracles, not a production shared lease implementation.
[Disposable probes](../../scripts/tests/lease_intent_postgres.py) use independent
OS `psql` processes/backend PIDs on PostgreSQL 16.14. The fixture has minimal
synthetic prerequisite tables, applies **unchanged actual V078**, and adds a
test-only control-head/command model. It is not a populated Flyway upgrade or a
Kotlin/provider integration test. Prior unchanged H2/Flyway/Ktor/runtime evidence
remains in the [adapter report](../ops/role-target-commit-time-maintenance.md).

| Probe | Required committed outcome / fence |
|---|---|
| Two Revoke; Disable↔Revoke; retirement↔Revoke | Same old expected state: one APPLIED, loser stale CAS after actor/root wait; one receipt |
| Two independent shared holders | Exclusive publisher waits until **both** finish; no process mutex |
| Authority publisher first | Waiter reads disabled/new head after wait and denies, zero mutation |
| Maintenance waits on root after acquiring head SHARE | Publisher waits; maintenance may commit **before** publication, no operation using old context starts after committed publication |
| Actor remap before/after maintenance | Earlier remap denies post-lock TG check; later remap waits for command commit |
| Direct child DML vs root-first / child-first | Actual V078 NOWAIT 55P03 or child bump makes command stale; no false receipt freshness |
| Mapping ABA + TEMP/search_path + rollback | Canonical head/projection advance; TEMP unchanged; rollback preserves epoch. PLATFORM itself remains unbound |
| Audit/receipt error; response loss; old receipt | All mutation rolled back, or exact once CURRENT then STALE after later mutation; revoked authority denies replay |
| Actual 40001/40P01; fresh attempt after authority loss | Original transaction aborted; new process denies, no stale-holder continuation |
| Cancellation, backend crash, restart/stale generation | 57014/rollback or backend termination; no receipt/partial write; new stale context denies |
| Old replica bundle; ACTIVE/terminal resurrection | Bundle mismatch denies; real V078 rejects ACTIVE and REVOKED resurrection |

**Five PROPOSED decisions for approval (not yet implemented):**

1. Maintenance-only non-expiring DB control head on the same primary, with durable
   generation and protected bundle matching; no external lock service.
2. Explicit intent-bound same-Connection lease port and head→actor→root lock order; exclusive
   publisher never acquires that head after actor/root locks.
3. Fence-first protected owner/authentication/manifest publication and all-updater
   participation gate; missing/unmatched/old-replica context remains denied.
4. Finite server manifest + transient typed intent for the three operations, using
   existing durable commands instead of a new persistent intent protocol.
5. Keep bare/administrative alternative writers quiesced or separately fenced,
   BRANDED/ACTIVE/credential resolution/TARGET disabled, and privileged recovery
   explicitly outside guarantees until an approved reconciliation procedure exists.

**Next bounded implementation slice after approval:** an unregistered, default-deny
DB control-head persistence/transaction-bound provider pair for these three existing
PLATFORM operations, with synthetic protected manifest and two-process tests of
every head updater, stale replica and retry/crash order. No live head/bundle adoption,
production DI/routes, config/token change or TARGET activation. Real provider enablement
still requires verified protected publisher provenance and operational writer coverage.
