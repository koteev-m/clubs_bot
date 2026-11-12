# ROLE_TARGET_DURABLE_BINDING_SOURCE — local evidence

**ROLE_TARGET_DURABLE_BINDING_SOURCE: READY_FOR_REVIEW.**
CLB-role-target-durable-binding-source, 2026-11-11. Registered detached worktree
`/private/tmp/clubs-role-workspaces-proposed-20261109`; HEAD, merge-base and fresh
remote main `5550b944990d8c1c93c0a5012d31db209f93ea6d`. PROJECT_IDENTITY_OK;
active Clubs Bot instructions, foreign=0. All 143 prior paths matched the reviewed
manifest `f3c60fa6b270376c348fd81cf01536769f8285cbe59601338feac1861c8076c6`
before edits; no unrelated changes, ref updates or rebase. Independent P1/P2 PASS
is supplied by the owner; this task preserves that candidate.

Evidence, byte backups, command logs, XML, full/delta manifests:
`/private/tmp/clb-role-durable-binding-6wi27xy_/`.

## Implemented, inactive boundary

[DurableBindingSource](../../core-data/src/main/kotlin/com/example/bot/data/client/bindings/DurableBindingSource.kt)
and [JdbcDurableBindingSource](../../core-data/src/main/kotlin/com/example/bot/data/client/bindings/JdbcDurableBindingSource.kt)
are internal read-only metadata interfaces, with a separate
[decoder](../../core-data/src/main/kotlin/com/example/bot/data/client/bindings/DurableBindingDecoder.kt).
No selector, resolver, app DI/route/job, writer or verifier conversion is supplied.
Existing TargetIdentityVerifier/CanonicalTargetAudienceProvider are unchanged and
remain unwired. Successfully reading INACTIVE metadata is **not authorized**;
it cannot be passed as TargetVerifiedIdentity or TargetAudienceBinding.

The caller must supply a typed **server-selected** reference: exact root UUID,
BindingFence(incarnation UUID, aggregate version, aggregate generation), expected
positive Telegram bot identity, finite ClientAudience restriction, and exact
mapping epoch/selected credential revision when applicable. There is no lookup
by name/username/connectionKey/user membership/OWNER/GLOBAL and no first-row or
first-client fallback. Null selection returns MissingSelection without DB access.
This internal type is an intent pin, not an authentication credential; a future
trusted selector must construct it from authorized server state, never HTTP IDs.

## Sources and snapshot semantics

One parameterized SELECT reads these **existing V078** relations:

| Source | Check/projection |
|---|---|
| target_bot_connections + exact target_bot_bindings | exact parent, current incarnation, bot ID, kind, positive root version/generation, PENDING/DISABLED/REVOKED and consistent terminal metadata |
| target_credential_revisions + target_credential_materials | selected opaque reference, exact same root/bot/material, positive immutable revision, AVAILABLE/terminal RETIRED shape |
| target_binding_clubs + clients/client_clubs/clubs | explicit same-client finite set, canonical exact resource existence; no publication or membership predicate |
| clients.target_mapping_epoch + target_client_mapping_epochs | positive matching projection/high-water and exact expected epoch; client active/unarchived, positive lifecycle version |

V078 has **one aggregate version/generation on the root**, used with the precise
incarnation ID. There are no independent child version/generation columns and no
new parallel persistence model. Guarded child mutations advance this aggregate
fence and make earlier pins/receipts stale. No schema/migration/guard is changed.

The driver uses READ COMMITTED, readOnly=true and an explicit transaction with
rollback on success/failure, plus a **single** SELECT for every returned snapshot.
No data from separate statements/requests is merged; no cross-request cache.
All relations use a quoted exact deployment schema, supplied explicitly by
trusted server configuration (validated ASCII SQL identifier; pg_* and
information_schema are rejected, not inferred from
session search_path; unknown schema fails closed). PostgreSQL additionally sets
transaction-local search_path to pg_catalog,pg_temp before SELECT: built-in
operators resolve before untrusted schemas, and relations never use that path.
Rollback restores the caller's path; no SECURITY DEFINER or privilege change.

A concurrent commit can occur after the SELECT snapshot. A delayed result may
therefore describe an earlier **coherent** version; it never promises current
mutation authority. Subsequent reads with stale pins reject. Every future Issue/
Confirm must serialize and revalidate current root/incarnation/generation/status,
credential revision/material state, mapping epoch/exact mappings, current issuer
and recipient identity, issuer authority, admission epoch and policy within its
own mutation transaction. Read-only inspection does not replace those locks/CAS,
audit/durable receipt or stale-response fencing.

## Outcomes, scope and credentials

- Inactive(snapshot): structurally valid current PENDING/DISABLED metadata with
  matching supplied pins. Missing credential is a legitimate unready draft;
  selected retired material is returned explicitly as RETIRED metadata. Neither
  supplies a usable token, trusted active connection, user identity or authority.
- Revoked(snapshot): exact current terminal incarnation and matching pins; still
  unusable. After replacement, an old incarnation reference rejects as stale.
- NotFound: exact root or requested incarnation absent. Inconsistent: malformed
  parent/child/canonical/terminal/credential shape. Rejected(reason): stale pins,
  wrong identity/scope, inactive client, mapping/head mismatch or revision mismatch.
- ReadFailed: SQL/connection/cleanup error, without driver details or credentials.
  Cancellation propagates, including checks before/after blocking JDBC work;
  this is not a promise to interrupt an already blocked driver call immediately.

PLATFORM has no bound client/clubs/epoch: it stays a multi-client channel and
cannot itself select a business. A later exact authorized per-operation intent
must validate that client's canonical state/epoch and user authority separately.
BRANDED is bound to one client and exactly the selected finite nonempty club set;
empty/incomplete/foreign sets deny. A new club never inherits binding membership.
Private/unpublished clubs remain resources; is_active on clubs is not a staff
admission rule. No user/staff/financial data is read.

Only opaque revision/material UUID metadata is returned, never raw credentials,
provider handles or initData. Snapshot and credential toString are redacted;
SQL exceptions are not returned/logged. No credential resolution or real token
rotation is implemented. AVAILABLE means recorded material state, not proof of a
working Telegram token. Retirement history remains in V078; inspection never
restores it or creates a replacement.

## Verification and focused review

Final results and exact input hashes are recorded with the candidate manifest.

| Check | Result |
|---|---|
| New reader repository tests | **24/24**, H2=11, real PostgreSQL=13; no skips |
| Unchanged foundation/mapping/canonical/client-context regressions | **109/109**; archived expanded run133 includes the earlier reader24 |
| App identity/audience/Ktor/owner-team/preparation compatibility | **52/52**, repeated against final production bytes |
| Distinct JVM/IT cases in this slice | **185/185**, zero failures/errors/skips |
| Offline role reference tests | **131/131** |
| Flyway | populated V077→V078 on both engines, repeated migrate/validate PASS (included above) |
| Kotlin compile / static | core-data/app compile, repository detektGate, affected ktlint PASS |
| Security/schema probes | TEMP + hostile schema/operator, reserved schema deny, root fence, ABA, real read/revoke ordering and rollback PASS |
| Final source/history secret gate | external `secret-scan-final/summary.json`; fail-closed gate required before handoff |
| Scope/links/diff | all introduced links resolve; changed15 paths clean, tracked diff clean |

Two unchanged untracked historical reports retain pre-existing blank-line-at-EOF
warnings (`role-assignment-shadow-report.md:3395`, `role-shadow-synthetic-report.md:1590`).
Full untracked diff output records these separately; this slice does not edit them.
No mandatory tests/static/security gate was disabled. The reserved namespace
check was added during self-review and explicit negative assertions rerun on both
engines with final test inputs. No Kotlin/SQL changed after those checks.

The initial run exposed a Detekt nesting issue; corrected by extracting query/
resolution helpers. A PostgreSQL probe initially failed in its own cleanup
(rollback with autocommit); corrected without changing the reader/SQL guards.
A new negative test initially inferred a non-Unit return and was not discovered
by JUnit; all shared tests now explicitly return Unit and discovery is checked.
No previous tests, oracles, guards or baselines were weakened.

Targeted repository coverage includes both engines, current inactive platform/
branded, null/missing/wrong-root/stale references, absent/retired credentials,
revocation/new incarnation, exact private-club subset, client inactive/archive,
foreign/missing mappings, no new-club inheritance, ABA/head mismatch, child fence/
stale receipt/rollback, errors/cancellation/no writes, and ACTIVE SQL/service deny.
PostgreSQL probes shadow every queried relation in TEMP/untrusted schemas and
supply a hostile UUID operator. A real concurrent revoke commits after SELECT:
old result stays coherent, the next old pin denies and fresh pins read terminal.

Self-review: qualified schema + protected operator path, exact root/incarnation/
credential parentage, immutable finite scope, head/projection equality, sanitized
failure/cancellation, absence of authorization conversions and runtime references.
No privileged DDL/disabled-trigger/TRUNCATE/backup-restore protection is promised:
these remain separate controlled recovery/activation prerequisites. All ordinary
read fencing depends on the reviewed V078 invariants and trusted schema ownership.

## Changed files and command evidence

Only 15 paths differ from the reviewed 143-path candidate; full candidate152:

- New internal Kotlin: `core-data/src/main/kotlin/com/example/bot/data/client/bindings/`
  `DurableBindingSource.kt`, `DurableBindingDecoder.kt`, `JdbcDurableBindingSource.kt`.
- New tests in the corresponding test package: `DurableBindingSourceContract.kt`,
  `DurableBindingSourceH2Test.kt`, `DurableBindingSourcePostgresIT.kt`,
  `DurableBindingSnapshotPostgresIT.kt`, `DurableBindingReadProbe.kt`.
- Documentation: this report, `docs/ops/PROJECT_STATUS.md`,
  `docs/product/AS_BUILT.md`, `docs/product/ROLE_TARGET_IDENTITY_CONTRACT_PROPOSED.md`,
  `docs/product/ROLE_TARGET_TRUSTED_BINDING_CONTRACT_PROPOSED.md`.
- Only documentation hash pins in `scripts/tests/fixtures/role-target-identity-contract.json`
  and `role-target-trusted-binding.json`; scenarios/oracles unchanged.

All137 other prior paths, every previous Kotlin/SQL/writer/test, V077/V078,
AGENTS.md and immutable CONCEPT_SOURCE are byte-preserved. OPEN_DECISIONS/product
permissions/statuses unchanged. Full checksums and delta are external evidence,
without a circular in-repository manifest checksum.

Commands use `./gradlew --no-configuration-cache --console=plain --no-parallel`
and the explicit owned loopback `CLB_CLIENT_TEST_PG_URL` for all PostgreSQL tests:

- `repository-final.log`: `--continue :core-data:test -PrunIT=true`
  with `--tests 'com.example.bot.data.client.bindings.*'`,
  `--tests 'com.example.bot.data.client.CanonicalAudience*'`,
  `--tests '*ClientContextPostgresIT'`;133/133 tests PASS. Overall initial command
  failed only new test InjectDispatcher lint; corrected. Unchanged109 regression
  cases are reused with archived XML/input hashes; changed reader tests rechecked.
- `closing-gates.log`: `--continue :core-data:test -PrunIT=true --tests '*DurableBinding*'`,
  `:app-bot:test` with target package, mapping routes, staff routes and preparation
  PostgreSQL filters; `detektGate :core-data:ktlintCheck :app-bot:compileKotlin`.
- `final-schema-gates.log`: repeat reader24 + `detektGate :core-data:ktlintCheck
  :app-bot:compileKotlin` after the final production schema-namespace restriction.
- `final-reader-tests.log`: reader24 + `:core-data:detektTest :core-data:ktlintCheck`
  after adding explicit reserved-schema negative assertions. No later Kotlin/SQL edits.
- `app-final.log`: targeted `:app-bot:test -PrunIT=true` with the same six
  package/route filters,52/52 PASS on the final core-data artifact.
- `python3 -I -S -B -m unittest discover -s scripts/tests -t . -p 'test_role*.py'`.
- `python3 -I -S -B scripts/secret-scan.py --output <evidence>/secret-scan-final`.
- Tracked `git diff --check`, untracked per-file `git diff --no-index --check`,
  source-hash preservation, link checks and exact runtime call-site inventory.

The PostgreSQL16 image is pinned by digest, all identities/references synthetic,
per-test schemas disposable; no shared/stage/prod DB. Full JVM/IT suites from the
previous reviewed slice are not reasserted for these new bytes. This task runs
targeted DB/compatibility gates and the repository Detekt aggregate instead.

## Compatibility, limits and next slice

NightConcierge still constructs its original in-memory Platform connection.
Platform-owner access, client onboarding, owner/team/preparation, legacy auth/
grants and V077/V078 are byte-preserved. The new read path has no mutation/audit/
receipt writes, real bot IDs/tokens, real staff data or provisioning. TARGET ACTIVE
remains forbidden by SQL/service; no production selector or resolver exists.
No publication, server access, stage/prod operation or real migration occurred.

Unimplemented prerequisites: signed maintenance selector/authority, credential
resolver/material attestation, separately approved activation/rotation, branded
onboarding, proof v2 and transaction-time TARGET Issue/Confirm revalidation.
No full JVM/IT rerun is required solely for this unwired internal reader; targeted
DB/static, unchanged migration validation, identity/audience/Ktor compatibility
and source/history secret gates are the verification boundary for this slice.

One next implementation slice: a separately authorized **signed, read-only
maintenance selector** producing exact server pins for metadata inspection;
keep ACTIVE, credential resolution and TARGET authorization disabled.
