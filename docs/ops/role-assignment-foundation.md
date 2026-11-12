# CLB-role-assignment-foundation — local inactive foundation

ROLE_ASSIGNMENT_FOUNDATION: **READY_FOR_REVIEW**. Architecture ACCEPTED for local
implementation only; this file does not authorize activation, cutover or deployment.
Base/remote main `5550b944990d8c1c93c0a5012d31db209f93ea6d`; registered detached
worktree `/private/tmp/clubs-role-workspaces-proposed-20261109`.
PROJECT_IDENTITY_OK; active Clubs Bot instructions, foreign=0. Initial 19-path
baseline saved in `/private/tmp/clb-role-assignment-foundation-59kjscy6/before/`.
Previous product/shadow materials and other-checkout changes are preserved.

**Follow-up, 2026-11-10:** [CLB-role-platform-revoke-adapter](role-platform-revoke-adapter.md)
implements source-aware platform membership revoke/restore on the existing mutation
path. No evaluator activation or real staff materialization is authorized. The
evidence section below records the original foundation checks, not adapter tests.

## Implemented, inactive

- Paired additive [V077 PostgreSQL](../../core-data/src/main/resources/db/migration/postgresql/V077__client_role_assignment_foundation.sql)
  / [H2](../../core-data/src/main/resources/db/migration/h2/V077__client_role_assignment_foundation.sql):
  `client_club_access_state`, `client_role_assignments`,
  `client_permission_delegations`; no backfill/old migration edits/grant issuance.
  Only existing command result CHECK is widened with `role_foundation`.
  Exact mapping/user/member/source/command/audit FKs, UUIDs, versions, policy pins,
  bounded selector/status/expiry/revocation checks; no GLOBAL/all-clubs scope.
- [Internal repository](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/JdbcRoleAssignmentFoundation.kt):
  admit/re-admit, issue/revoke role, issue/revoke delegation, full disable;
  effective/explain and strictly read-only unknown-result resolution. No DI,
  route, production policy catalog or ownership/Telegram/TARGET-invitation adapter.
  All default providers deny. Only tests instantiate trusted synthetic providers.
- [Evaluator](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentSnapshot.kt):
  active/unarchived client + exact mapping + active membership/version + ACTIVE
  admission/epoch + active source + pinned registered policy + selector/expiry.
  Delegation additionally requires same-club live CM issuer/FLOOR recipient,
  current issuer write + bounded section read + delegation authority and both
  epochs. Permission is a union of valid source contributions with provenance.
  Domain resource ownership/night/table/HOLD/finance guards still apply separately.
- Revoke one source preserves other lawful sources. Dependent delegations become
  terminal; recipient revisions bump. Full disable revokes all new subject sources,
  outgoing dependencies and increments epoch. Explicit re-admission creates no
  permissions; new-ID issue requires exact regrant acknowledgement of revoked
  permissions. Inactive membership never restores. Restore with a changed membership
  version does not revive old sources. Ownership/platform/Guest SELF are separate.
- [Writer authority](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentAuthority.kt)
  follows accepted hierarchy. Role management is distinct from execution;
  delegation requires execution. Self/owner/platform subjects are protected.
  Owner delegation revoke/full disable require separate default-deny authority.
- Actor → same client row → sorted fence/source locks, READ COMMITTED; post-wait
  and final current authority checks; source CAS and epoch; transactional audit
  and existing durable receipt. Audit stores source/dependency IDs, policy pins,
  before/after, reason and verified target invitation ID, without secrets/raw key.
  Cancellation is checked after locks and immediately before commit.
- Replay uses current authority/state, never a saved grant payload. UUIDs remain
  in immutable typed intent; receipt BIGINT points to subject user. `resolve`
  never writes or reserves a key when no receipt exists. Responses contain actor
  and subject fences, connection hash and dependency/actor/identity valid-until.
  An actual UI response merger is not connected.
- Commands detach mutable selectors/regrant sets before dispatcher/DB waits;
  policy sets are immutable. A queued caller edit cannot change persisted scope
  or intent hash; replay of the changed body conflicts.

## Legacy compatibility and cutover blockers

[Legacy writer fence](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/LegacyClientAuthorityWrites.kt)
checks exact client/club/user under the same client lock. TARGET, DISABLED or any
new role/delegation history, including outgoing issuer dependencies, blocks the
old writer before committed effects. Empty tables and ACTIVE/PENDING empty SHADOW
admissions preserve previous behavior. Entirely absent tables are accepted only
with proven successful pre-V077 Flyway history; partial/missing-after-V077 schema
fails closed. Startup migration validation is unchanged.

| Writer family | Current containment |
|---|---|
| Team replace/change/disable | Fence before `ClubStaffAccess.replace`; old package algorithm otherwise unchanged |
| Staff invitation claim/confirm, preparation invite confirmation | Exact claimant fence before status/member/grant effects; old protocol never becomes TARGET |
| Client invitation confirm/accept | Client/user fence before membership/application grant effects |
| Platform membership revoke/restore, staff platform revoke | Source-aware transactional marker terminates V077 sources/fences before legacy cleanup; no-state legacy behavior unchanged; controlled flat restore denied |
| Mapped legacy promoter enable/disable | READ COMMITTED + mapped client lock + exact fence before access/user-role writes; existing NotFound contract |
| Direct/approved club creation | Fresh mapping retains exact 10-grant COMPAT bootstrap, with no new fence/source/neighbor inheritance |
| Preparation task metadata, limits/container lifecycle | No new authority projection; no automatic source creation/restoration |
| Historical migrations, manual demo/privileged SQL | No changes or execution here; administrative writers must be quiesced/fenced/reviewed before TARGET |

**Pending cutover, not implemented:** reviewed compatibility classification/manifest projection, final production
policies/registry/DTOs, signed TARGET admission protocol and ownership provider,
all-writer/reader/seed gates, public endpoints and UI fencing integration.
Internal full disable refuses active opaque legacy grants/roles rather than
partially disabling a mixed user. The follow-up platform adapter now invalidates
the whole new dependency tail atomically with old membership/staff/grant cleanup.
Other incompatible writers still refuse controlled subjects before mutation.
Privileged direct SQL is not claimed to be constrained by application guards.
**Real staff materialization is still prohibited even in SHADOW**: completion of
the platform adapter does not authorize identity/assignment/compatibility cutover.
Current providers are uninstalled;
the read-only inventory creates no database rows. Minimal next implementation is
the reviewed identity/admission integration, not an activation toggle.

## CURRENT / TARGET / DIFFERENCE / RISK

All codes below are test policies, not new production grants. CURRENT is the
persisted synthetic state before an explicit command; TARGET is the isolated
post-command state, not an activated product matrix. Mounted COMPAT authorization
continues to use its old grants throughout. The previous
[38 reference cases](role-assignment-shadow-report.md) and
[46 product inventory cases](role-shadow-synthetic-report.md) remain separate.

| Scenario | CURRENT | TARGET / DIFFERENCE | RISK / expected fail-closed |
|---|---|---|---|
| One FLOOR role | Empty admission | New source: `night.read`, `floor.tables.read` | No projection to mounted client API |
| FLOOR + ENTRANCE, revoke FLOOR | `night.read` from two IDs, floor + entrance operations | Only FLOOR terminal; `night.read` and `entrance.arrival.record` remain from ENTRANCE | Array replacement would lose lawful source |
| FLOOR + calendar delegation | Floor source only | Delegation adds `client.club.calendar.read/write`; revoke removes only its bundle | No broad club read, staff/finance/ownership delegation |
| Revoke CM issuer | FLOOR + delegated config | Delegation terminal, recipient revision changes; FLOOR remains | New CM ID cannot revive old delegation |
| Full disable / re-admit | Several active sources | All terminal + epoch++; later admit still empty, explicit new IDs/ack needed | Old UUID/invitation/epoch/restore cannot regrant |
| Inactive/restored membership | Active sources | Inactive denies; version-changed restore still yields no contributions | No source resurrection by membership restore |
| Promoter 3/5 | No staff source in D/E | Own booking create/read only explicit A/B/C | D/E, foreign client, finance/check-in/staff remain denied |
| Two issuers with same CAS | Same subject revision | One commits; stale second conflicts | No lost permissions or silent partial issuance |
| Grant/revoke or disable race | Shared expected revision/epoch | Client-lock order + fresh checks; stale operation denied | Committed revoke wins future decisions |
| Cancellation/audit completion failure | Pre-command state | Exact source/fence/audit/receipt rollback | No partial package or ghost receipt |
| Legacy writer on mixed source/history | Existing raw grants plus new history | `invalid_transition` / promoter `NotFound`, all data unchanged | No independent source deletion or tombstone reactivation |
| Unknown result resolution | Receipt absent/present/revoked | Absent read does nothing; known receipt returns current revoked state | Resolve cannot execute a delayed command |
| Mutable selector during lock wait | Confirmed SHIFT_SET `[10]` | Persisted scope remains `[10]`, not caller-edited `[10,20]`; changed replay conflicts | No time-of-check/intent mutation |

## Evidence and review

| Check | Result / evidence scope |
|---|---|
| `./gradlew ... test` | PASS, 1,557 tests / 1 pre-existing skip; broad JVM gate before final isolated hardening |
| `./gradlew ... detektGate test -PrunIT=true` | Other modules PASS; first data run found two new fixture-constructor discovery errors, both corrected; module rerun below |
| `:core-data:test -PrunIT=true` without filters | PASS: 746 cases, 0 failures/errors, 10 unrelated native-support opt-in skips |
| Focused H2 + `:core-data:detektMain :core-data:detektTest :core-data:ktlintCheck` | PASS: 31 lifecycle +6 migration +11 legacy fence =48 tests, zero skips |
| Focused real PostgreSQL 16.14 | PASS: 31 lifecycle +6 migration +12 legacy fence +10 concurrency =59 tests, zero skips |
| `ktlintCheck --no-build-cache --rerun-tasks` | PASS, all54 tasks; final changed-module lint rechecked after hardening |
| Offline inventory/reference unittest | PASS, 93 tests; all46+38 original scenario IDs/oracles retained |
| Pinned secret gate | PASS after public-checksum representation; no scanner exemptions/config changes |
| Diff, source pins, links, statuses, scope | PASS for task changes; one unchanged historical CLB83 temporary-artifact link is unavailable, preserved as history |

Gradle uses `--no-configuration-cache` because the existing SARIF recorder is
not configuration-cache compatible. Earlier cached lint outputs carried another
Clubs worktree's paths; the forced fresh54-task check replaced that evidence.
No baseline, task, lint rule or security gate was weakened. The last hardening
changes affect only the isolated foundation/tests; their 48 H2/59 PG checks are
new current-byte evidence, not a claim that the earlier broad command used future bytes.

Raw sanitized logs, XML and snapshots:
`/private/tmp/clb-role-assignment-foundation-59kjscy6/` (`jvm-pass/`,
`full-it-evidence/`, `core-data-it-final-evidence/`, `final-h2-evidence/`, `final-pg-evidence/`,
`foundation-final-h2.log`, `foundation-final-pg.log`, `core-data-it-final.log`,
`full-ktlint-final.log`, `secret-scan-complete/`, `shadow-tests-final.log`).
The whole initial IT run had 2,032 cases, only the two discovery failures and
11 existing skips. Ten skipped cases belong to the unrelated native-support
opt-in harness (`CLB_SUPPORT_CREATION_TEST_PG_BIN` not supplied); one is an
existing disabled GuestFlowStart test. Foundation tests have no skips.
Disposable PostgreSQL: isolated local container PostgreSQL 16.14, loopback only,
`clb_client_test`, per-test owned schemas; H2 in memory. No DATABASE_* environment,
staging, production or real customer database was used.

Meaningful tests cover additive populated upgrade, FK/CHECK parity, all six staff
roles (eight product roles retain separate owner/guest providers), independent
sources, dependencies, epochs/tombstones/regrant, inactive membership, exact
client/club/shift boundaries, promoter 3/5, current authorization isolation,
post-write audit failure rollback, durable replay/resolve and stale versions.
Real PostgreSQL barriers observe `pg_stat_activity` lock waits for two issuers,
grant/revoke in both orders, competing disables, revoke-before-read, post-wait
expiry, simultaneous replay, canceled lock wait and legacy writer interference.

Independent review fixed missing permission-registry checks, separate owner
revoke-delegation authority and JDBC cancellation observation. Self-review
preserved terminal higher-role history without treating it as active authority
when resolving/replaying a completed disable. No test/assertion/gate was removed.
Full product matrix, operational booking/finance semantics and activation remain
outside this implementation.

Public source checksums in prior fixtures are now explicit `{algorithm,digest}`
metadata. The exact evidence set and SHA-256 verification remain mandatory;
wrong algorithm, digest, missing fields and source drift fail closed. No scanner
allowlist/config/ignore was changed. Prior baselines preserve the old representation.

Unverified: real signed identity/ownership/TARGET invitation adapters, actual
assignments/cutover/backfill/rollback rollout, legacy privileged/manual SQL
containment, operational API/UI/frontend response merger and stage/prod runtime.
No commit/push/PR/merge/deploy was performed.

Original foundation next step (completed by the linked follow-up): the isolated
platform adapter. Current next implementation boundary: reviewed signed TARGET
admission/identity integration; keep real staff writes and authorization disabled.


## Changed files in this task

Earlier materials not listed below are byte-preserved against the saved baseline.
All baseline paths remain present; no production route/security configuration or
old migration is changed. The unavailable CLB83 temporary report is an unchanged
historical reference, not current foundation evidence. New data helpers/guards are local, unmounted where stated.

<details>
<summary>Task delta paths (52)</summary>

- [app-bot/src/test/kotlin/com/example/bot/routes/ClientBookingRulesRoutesDataTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientBookingRulesRoutesDataTest.kt)
- [app-bot/src/test/kotlin/com/example/bot/routes/ClientCalendarRoutesDataTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientCalendarRoutesDataTest.kt)
- [app-bot/src/test/kotlin/com/example/bot/routes/ClientNightEventLinksRoutesDataTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientNightEventLinksRoutesDataTest.kt)
- [app-bot/src/test/kotlin/com/example/bot/routes/ClientOperationalNightsRoutesDataTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientOperationalNightsRoutesDataTest.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentAdmissionWriter.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentAdmissionWriter.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentAudit.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentAudit.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentAuthority.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentAuthority.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentIntent.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentIntent.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentLifecycle.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentLifecycle.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentModel.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentModel.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentPolicies.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentPolicies.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentProviders.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentProviders.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentSnapshot.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentSnapshot.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentSourceWriter.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentSourceWriter.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/assignments/JdbcRoleAssignmentFoundation.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/JdbcRoleAssignmentFoundation.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientAccessOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientAccessOperations.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubStaffAccess.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubStaffAccess.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/InvitationOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/InvitationOperations.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/LegacyClientAuthorityWrites.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/LegacyClientAuthorityWrites.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/StaffInvitationOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/StaffInvitationOperations.kt)
- [core-data/src/main/kotlin/com/example/bot/data/promoter/PromoterAdminRepositoryImpl.kt](../../core-data/src/main/kotlin/com/example/bot/data/promoter/PromoterAdminRepositoryImpl.kt)
- [core-data/src/main/resources/db/migration/h2/V077__client_role_assignment_foundation.sql](../../core-data/src/main/resources/db/migration/h2/V077__client_role_assignment_foundation.sql)
- [core-data/src/main/resources/db/migration/postgresql/V077__client_role_assignment_foundation.sql](../../core-data/src/main/resources/db/migration/postgresql/V077__client_role_assignment_foundation.sql)
- [core-data/src/test/kotlin/com/example/bot/data/booking/core/CanonicalBookingH2Test.kt](../../core-data/src/test/kotlin/com/example/bot/data/booking/core/CanonicalBookingH2Test.kt)
- [core-data/src/test/kotlin/com/example/bot/data/client/assignments/AssignmentConcurrencyPostgresIT.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/assignments/AssignmentConcurrencyPostgresIT.kt)
- [core-data/src/test/kotlin/com/example/bot/data/client/assignments/AssignmentContractBase.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/assignments/AssignmentContractBase.kt)
- [core-data/src/test/kotlin/com/example/bot/data/client/assignments/AssignmentLifecycleContract.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/assignments/AssignmentLifecycleContract.kt)
- [core-data/src/test/kotlin/com/example/bot/data/client/assignments/AssignmentLifecycleFixture.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/assignments/AssignmentLifecycleFixture.kt)
- [core-data/src/test/kotlin/com/example/bot/data/client/assignments/AssignmentLifecycleH2Test.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/assignments/AssignmentLifecycleH2Test.kt)
- [core-data/src/test/kotlin/com/example/bot/data/client/assignments/AssignmentLifecyclePostgresIT.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/assignments/AssignmentLifecyclePostgresIT.kt)
- [core-data/src/test/kotlin/com/example/bot/data/client/assignments/AssignmentPermissionContract.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/assignments/AssignmentPermissionContract.kt)
- [core-data/src/test/kotlin/com/example/bot/data/client/assignments/AssignmentTestDatabase.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/assignments/AssignmentTestDatabase.kt)
- [core-data/src/test/kotlin/com/example/bot/data/client/assignments/RoleAssignmentMigrationContract.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/assignments/RoleAssignmentMigrationContract.kt)
- [core-data/src/test/kotlin/com/example/bot/data/client/assignments/RoleAssignmentMigrationH2Test.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/assignments/RoleAssignmentMigrationH2Test.kt)
- [core-data/src/test/kotlin/com/example/bot/data/client/assignments/RoleAssignmentMigrationPostgresIT.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/assignments/RoleAssignmentMigrationPostgresIT.kt)
- [core-data/src/test/kotlin/com/example/bot/data/client/onboarding/LegacyAuthorityWriteFenceTest.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/onboarding/LegacyAuthorityWriteFenceTest.kt)
- [docs/ops/PROJECT_STATUS.md](../../docs/ops/PROJECT_STATUS.md)
- [docs/ops/role-assignment-foundation.md](../../docs/ops/role-assignment-foundation.md)
- [docs/ops/role-assignment-shadow-report.md](../../docs/ops/role-assignment-shadow-report.md)
- [docs/ops/role-shadow-inventory.md](../../docs/ops/role-shadow-inventory.md)
- [docs/ops/role-shadow-synthetic-report.md](../../docs/ops/role-shadow-synthetic-report.md)
- [docs/product/AS_BUILT.md](../../docs/product/AS_BUILT.md)
- [docs/product/OPEN_DECISIONS.md](../../docs/product/OPEN_DECISIONS.md)
- [docs/product/README.md](../../docs/product/README.md)
- [docs/product/ROLE_ASSIGNMENT_CONTRACT_PROPOSED.md](../../docs/product/ROLE_ASSIGNMENT_CONTRACT_PROPOSED.md)
- [docs/product/ROLE_WORKSPACES_PROPOSED.md](../../docs/product/ROLE_WORKSPACES_PROPOSED.md)
- [scripts/role_shadow_inventory.py](../../scripts/role_shadow_inventory.py)
- [scripts/tests/fixtures/role-assignment-contract.json](../../scripts/tests/fixtures/role-assignment-contract.json)
- [scripts/tests/fixtures/role-shadow-inventory.json](../../scripts/tests/fixtures/role-shadow-inventory.json)
- [scripts/tests/role_assignment_reference.py](../../scripts/tests/role_assignment_reference.py)
- [scripts/tests/test_role_assignment_contract.py](../../scripts/tests/test_role_assignment_contract.py)
- [scripts/tests/test_role_shadow_inventory.py](../../scripts/tests/test_role_shadow_inventory.py)

</details>
