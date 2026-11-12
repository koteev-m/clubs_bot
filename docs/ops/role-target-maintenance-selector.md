# ROLE_TARGET_MAINTENANCE_SELECTOR — local evidence

**ROLE_TARGET_MAINTENANCE_SELECTOR: READY_FOR_REVIEW.**
CLB-role-target-maintenance-selector, 2026-11-11. Registered detached worktree
`/private/tmp/clubs-role-workspaces-proposed-20261109`; HEAD/merge-base/fresh remote
main `5550b944990d8c1c93c0a5012d31db209f93ea6d`. PROJECT_IDENTITY_OK; active
Clubs Bot instructions, foreign=0. All152 reviewed paths matched manifest
`f68b1d733cecafb91929b31990730ef800c86069ab49cdfb5264c04f3234ef04` before edits.
No unrelated changes, ref update/rebase or publication. Previous independent PASS
is supplied by the owner; earlier evidence is preserved.

Baseline copies, logs, XML/input hashes, full/delta manifests:
`/private/tmp/clb-role-maintenance-selector-bq549iqa/`.

## Authentication, selection and authority are separate

[TargetIdentityVerifier](../../app-bot/src/main/kotlin/com/example/bot/plugins/target/TargetIdentityVerifier.kt)
adds only `verifyMaintenance`: same strict credential parser, same existing
`verifySignature`/TrustedBotConnection/InitDataValidator HMAC implementation,
positive Telegram ID, 0≤age<300s, zero future skew and double signature validation.
No second HMAC algorithm, MAC token, nonce or proof v2. Existing client-context
`verify` remains unchanged; normal Mini App24h auth and runtime routes are unchanged.

Maintenance authentication is through an independently trusted **live PLATFORM
channel**, not the credential of the inspected INACTIVE binding. It requires an
explicit current immutable
[MaintenanceAuthenticationBinding/source](../../app-bot/src/main/kotlin/com/example/bot/plugins/target/TargetMaintenanceIdentity.kt).
The exact connection object and positive context revision must remain enabled/
current across verification and selection. Missing source denies. Replacing or
withdrawing the context invalidates identities made under the old context, even
with the same connectionKey/token/user. The verifier returns a private-implementation
sealed TargetMaintenanceIdentity; it contains no raw initData/token and is neither
TargetVerifiedIdentity for a client nor maintenance/write authority.

The authentication source is a trusted server contract, **not a persisted bot
registry or implemented live rotation provider**. It must replace/fence the
immutable context before rotation/revoke and never reuse an old object/revision.
No production provider is registered. Actual secret invalidation/rotation and
recovery remain separate work. A read-only result cannot prove unobserved server
configuration ABA or fence later mutations; no such guarantee is claimed here.

[TargetMaintenanceSelector](../../app-bot/src/main/kotlin/com/example/bot/plugins/target/TargetMaintenanceSelector.kt)
accepts only this verified identity. It rechecks freshness, exact current channel,
server selection and authority around two independent current durable reads. Both
reads must match the exact pinned reference and each other, including status,
credential material state/revision, lifecycle and mapping epoch. Final freshness
and cancellation checks occur after provider/DB waits. No cross-request cache.

| Source | What is actually authoritative / limit |
|---|---|
| AppConfig OWNER_TELEGRAM_ID / existing platform-owner contract | Positive exact configured signed user ID, separate from legacy OWNER/GLOBAL. Selector rereads the supplied current protected configuration source; no membership/role fallback |
| MaintenanceServerSelectionSource | Explicit immutable server intent binds current authentication context to exact target root/incarnation/bot ID/fence/scope/revisions. Default absent; no production selector/provisioning or first-record fallback |
| BusinessMaintenanceAuthorityProvider | Default absent. Future independent provider must prove current exact actor/client and finite owned maintenance clubs plus source ID/version/state. Synthetic adapter only in tests |
| V078 DurableBindingSource/JDBC | Actual consistent root/incarnation/credential/canonical mapping snapshot, not user authority or a live credential proof |
| Legacy club creator, client membership, team grants, AssignmentOwnershipProvider | Creator/team semantics remain historical; no verified new business-ownership provider exists. These sources cannot be substituted for the missing provider |

## Selection sequence and exact boundaries

1. Require fresh verified identity from the exact independently configured platform
   authentication connection; no body/query/userId/role is accepted by `select`.
2. Read the current server selection and current authentication context; verify
   enabled status, positive revision and exact context identity. Caller root IDs
   are not selection parameters; a future lookup hint must first be resolved by
   this trusted context source. No source → Unavailable, no DB metadata read.
3. For PLATFORM target metadata require the exact configured platform owner. The
   target still has no client/club binding or ownership. The channel remains
   multi-client; it never selects a business client implicitly.
4. For BRANDED target metadata require separate current business authority for
   the exact actor/client and **every** selected club. Being platform owner alone
   is insufficient. The target's finite set must exactly match V078, so a partial,
   neighboring, foreign or automatically expanded set cannot pass.
5. Read V078; recheck current identity/context/authority; read V078 again; compare
   exact snapshot values; recheck current identity/context/authority and final
   time/cancellation. Return a diagnostic pin only if all checks still pass.

Private/unpublished clubs are canonical resources. Staff membership is not required
for authenticating a future recipient, and is never inferred from channel identity.
Current business ownership could legitimately cover a larger finite set; the
selected branded binding remains its exact explicit subset, never all client clubs.

Outcomes: Selected(read-only pin), Denied, Stale, Revoked, Unavailable, Error.
Missing references/provider state do not select alternatives. SQL/inconsistent/
provider failures return sanitized Error; cancellation is rethrown. Failed outcomes
contain no private binding snapshot or provider/driver diagnostic. Retired material
can be inspected by an authorized reader, explicitly marked RETIRED, never resolved
as a credential. PENDING/DISABLED are readable metadata; ACTIVE is still SQL/service
forbidden, and a terminal REVOKED result cannot yield Selected.

## Immutable pin and TOCTOU

[MaintenanceSnapshotPin/model](../../app-bot/src/main/kotlin/com/example/bot/plugins/target/MaintenanceSelectionModel.kt)
contains the exact durable snapshot: root UUID, incarnation UUID, aggregate root
version/generation, bot ID/kind, status, credential UUID/revision/material state,
canonical finite scope, mapping epoch/client lifecycle version. It also records
actor ID, authentication time/window, selection time, context revision and business
source ID/version when applicable. V078 has **one aggregate version/generation**;
no independently writable child counters or parallel persistence are invented.
All collections are immutable. Pin/credential toString is redacted; no raw token,
initData, resolver handle or new bearer proof is returned/stored/logged.

Authentication may be reused within its strict window with all checks repeated;
it is not a one-time invitation credential. Neither the identity nor the pin is
accepted by existing mutation writers or converted to BindingEvidenceProvider,
roles, staff grants, admission or TARGET authorization. There is no write adapter.

Each SELECT has the existing one-statement read-only DB contract and schema/search_path
protection. Two reads plus fresh providers reduce observed stale-context races;
they are **not** a shared transactional authorization fence. A commit after the
last observation can make a returned diagnostic snapshot old. A pin is never a
permanent permission and cannot authorize a later operation. Future maintenance/
Issue/Confirm must freshly verify signed identity and current authority/context,
then revalidate root/incarnation/generation/status, credential revision/retirement,
client mapping epoch/exact scope and applicable admission/policy under the existing
shared locks/CAS within its own audit/receipt mutation transaction. No promise of
revoking previously disclosed metadata or finished operations is made.

## Tests, self-review and compatibility

Synthetic Kotlin tests separately exercise authentication, metadata selection and
absence of future mutation authority. Disposable H2/PostgreSQL tests compose the
actual verifier → selector → JdbcDurableBindingSource, not a reference replacement.
They insert only synthetic PENDING metadata, never ACTIVE or real bot credentials.

Coverage: exact platform owner/other actor; stale300/valid299/future auth_date;
wrong token, duplicate credentials, nonpositive IDs and wrong channel audience;
context withdrawal/replacement; expiry during provider waits; branded default
absence and synthetic independent ownership; wrong actor/client/club, partial finite
set and inactive/revoked authority; wrong root/incarnation/generation; raw guarded
child mutation, terminal revoke, retirement, mapping DELETE/reinsert ABA; concurrent
revoke between actual durable reads; repeated reads, SQL/provider failure,
cancellation, no writes/no permissions/private metadata leaks. A test-only Ktor
route checks 401/403/metadata-read separation; no production route is added.

Focused review: no identity from names/body/query/legacy roles; current channel and
actor rechecked; typed pin never used as a writer credential; exact finite scope;
no stale-snapshot cache; no mutation/audit/receipt write; protected DB schema unchanged.
Existing source value comparisons use explicit finite-scope equality rather than
ClientAudience object identity. Existing V077/V078/P1/P2 guards and staff writers
are preserved. Normal NightConcierge/platform-owner/owner-team/preparation/client
flows remain wired as before. No real data, stage/prod, token operation or publication.

To compose backend modules, only existing read-only binding types/JDBC adapter and
BindingFence/Kind/State visibility are exported from core-data. Their fields/SQL/
algorithms are unchanged; writers remain internal. This is a Kotlin module boundary,
not an HTTP API or production DI registration. No migration/dependency is added.

Initial local compile/static defects (cross-module smart cast and long fixture
line) were corrected; tests/gates were not weakened. Final commands, XML counts,
source/manifest hashes and preservation evidence are recorded externally.

## Final verification and changed files

| Check | Result |
|---|---|
| New maintenance acceptance | **32/32**:15 authentication/selector unit cases,8 H2 +8 real PostgreSQL composition cases,1 Ktor test-host case; zero skips |
| Expanded affected backend tests | **237/237**: core-data133 + app-bot104, zero failures/errors/skips; includes prior P1/P2 source/guard/mapping tests and populated H2/PG V077→V078 migrate/validate |
| Required full JVM gate | **1,696 tests**, zero failures/errors; one unchanged `GuestFlowStartTest` skip |
| Static/compile | repository `detektGate`, core-data/app-bot `ktlintCheck`, Kotlin compilation PASS |
| Offline reference | **131/131**; fixture scenarios/oracles unchanged, only exact doc/verifier source pins refreshed |
| Secret/source/history gate | final sanitized `secret-scan-final/summary.json`; fail-closed PASS required before handoff |
| Preservation | prior142/152 byte-identical; all old SQL/migrations/writers/tests and immutable CONCEPT_SOURCE/AGENTS unchanged |

Existing unrelated deprecation warnings and the one pre-existing skip were not
changed. Full PostgreSQL IT is not repeated: this slice changes no schema/writer,
and all affected PostgreSQL read/fence/migration/concurrency paths ran explicitly.
The full JVM gate is required because this is a cross-module authentication change.
Final input manifests bind XML/static evidence to the final Kotlin/SQL bytes; no
production/test source edits follow these gates.

Commands (all from the registered worktree, no Git mutation):

- Identity guard: `python3 -I -S -B scripts/project-identity-guard.py
  --task-id CLB-role-target-maintenance-selector --expected-head 5550b944990d8c1c93c0a5012d31db209f93ea6d`
  with app-bot/core-data/docs/scripts scopes.
- `unit-gates.log`: targeted `:app-bot:test --tests '*TargetMaintenanceSelectorTest'`
  plus affected Detekt/ktlint; initial14/14 PASS.
- `integration-gates.log`: `:app-bot:test -PrunIT=true --tests '*TargetMaintenance*'`
  plus app static;31 cases PASS, command initially failed only one fixture line-length
  violation, corrected. The final added provider-wait test brings the total to32.
- `affected-final.log`: `./gradlew --no-configuration-cache --console=plain --no-parallel
  --continue :app-bot:test -PrunIT=true` with target package, PlatformOwnerRoutesTest,
  MiniAppAuthTest, ClientContextAuthTest, mapping/team/preparation route filters;
  `:core-data:test` with bindings package, CanonicalAudience and ClientContextPostgresIT;
  `detektGate :app-bot:ktlintCheck :core-data:ktlintCheck` — PASS237.
- `jvm-final.log`: `./gradlew --no-configuration-cache --console=plain --continue
  test detektGate :app-bot:ktlintCheck :core-data:ktlintCheck` — PASS,1,696/one old skip.
- `python3 -I -S -B -m unittest discover -s scripts/tests -t . -p 'test_role*.py'` — PASS131.
- `python3 -I -S -B scripts/secret-scan.py --output <evidence>/secret-scan-final`;
  final tracked/untracked diff, links, decision-status/hash preservation and runtime
  call-path checks are saved beside manifests.

The PG16 database is task-owned, loopback-only, disposable, pinned image digest
`sha256:16bc17c64a573ef34162af9298258d1aec548232985b33ed7b1eac33ba35c229`.
No external DB or real identity/credential is used. Two unchanged untracked historical
reports retain existing EOF warnings (`role-assignment-shadow-report.md:3395`,
`role-shadow-synthetic-report.md:1590`); the changed20 paths have clean diff checks.

Full candidate162 / task delta20 paths:

- New internal app types/selector: `plugins/target/TargetMaintenanceIdentity.kt`,
  `MaintenanceSelectionModel.kt`, `TargetMaintenanceSelector.kt`; existing
  `TargetIdentityVerifier.kt` adds the opt-in maintenance purpose only.
- Existing core-data read-only visibility: bindings `BindingModel.kt`,
  `DurableBindingSource.kt`, `JdbcDurableBindingSource.kt`. SQL/algorithms unchanged.
- New app test files in `plugins/target/`: `MaintenanceSelectorFixture.kt`,
  `TargetMaintenanceSelectorTest.kt`, `MaintenanceDatabaseFixture.kt`,
  `TargetMaintenanceDatabaseTest.kt`, `TargetMaintenancePostgresIT.kt`,
  `TargetMaintenanceRouteTest.kt`.
- This report, `docs/ops/PROJECT_STATUS.md`, `docs/product/AS_BUILT.md`,
  `ROLE_TARGET_IDENTITY_CONTRACT_PROPOSED.md`, `ROLE_TARGET_TRUSTED_BINDING_CONTRACT_PROPOSED.md`.
- Only hash pins in `scripts/tests/fixtures/role-target-identity-contract.json`
  and `role-target-trusted-binding.json`.

Full and delta manifests and all source hashes live outside the repository in the
evidence directory; no circular manifest checksum or publication is introduced.

## Unavailable prerequisites and next slice

Runtime business maintenance is explicitly **blocked** until a real verified
business-ownership authority provider exists. No creator/membership/GLOBAL shortcut.
Runtime selection also needs a reviewed current authentication-context lifecycle
provider and an authorized server-intent source binding exact target metadata;
this task supplies interfaces/default-deny and synthetic adapters, not live adoption.
Credential resolver, live secret retirement/rotation, ACTIVE/activation contract,
write-purpose maintenance proof, proof v2 and transactional TARGET mutations remain
unimplemented and outside this read-only slice. Privileged DDL/disabled guards/
backup recovery are separate operational boundaries, not protections added here.

One next implementation slice: separately approved **commit-time maintenance
authorization adapter for existing inactive lifecycle operations**, preserving the
ACTIVE prohibition and requiring current signed actor/authority/context under the
existing locks/CAS/audit/receipt transaction; no production activation or issuance.
