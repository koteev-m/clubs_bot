# ROLE_TARGET_CANONICAL_AUDIENCE_PROVIDER — local read-only slice

**ROLE_TARGET_AUDIENCE_PROVIDER: READY_FOR_REVIEW.** Unpublished, unwired;
not an authorization, registry or staff activation. Registered detached worktree
`/private/tmp/clubs-role-workspaces-proposed-20261109`, HEAD/fresh remote main
`5550b944990d8c1c93c0a5012d31db209f93ea6d`. PROJECT_IDENTITY_OK, Clubs Bot
instructions, foreign=0. HEAD/origin-main merge-base is the same SHA; the local
`main` ref is stale (merge-base `f2a3bde00681859ecd39ba73ce7c8cfe32e2bc97`),
so no rebase/reset/ref update was performed. Both reviewed inventories matched all
84 hashes before edits; manifest SHA-256
`c13a4d5cf1bf2e5e12ca2b137903c75babd308e00a3d0fdc6d5ec57def156eb7`.
Baseline, commands, XML, preservation checks and full/delta manifests:
`/private/tmp/clb-role-target-audience-provider-191yjiqh/`.

## Implemented sources and binding

[JdbcCanonicalClientAudienceRepository](../../core-data/src/main/kotlin/com/example/bot/data/client/JdbcCanonicalClientAudienceRepository.kt)
reads only `clients`, `client_clubs` and `clubs`. Canonical
[V061](../../core-data/src/main/resources/db/migration/postgresql/V061__client_context.sql)
mapping has a club primary key, unique `(client_id,club_id)` and FKs to clients/clubs.
[V066](../../core-data/src/main/resources/db/migration/postgresql/V066__client_archive.sql) adds
`clients.archived_at` and positive `lifecycle_version`. An entire explicitly
configured finite club set must exist under the exact active, unarchived client.
Partial/foreign/missing sets and empty scopes deny; no club is discovered or added
to an audience automatically. A new club in the same client leaves an existing
finite audience unchanged. No membership/user/grant/legacy-role join exists.

`clubs.is_active` is the existing guest-publication state, not staff admission.
The onboarding/direct creation flows create private clubs with FALSE and an exact
client mapping. The reader deliberately allows these canonical private resources;
it grants no right to prepare/manage them. There is no separate mapping
active/revoked/status/version column to check. Club profile/calendar/team/limit
versions concern other domains and are not treated as bot-binding generations.

[CanonicalTargetAudienceProvider](../../app-bot/src/main/kotlin/com/example/bot/plugins/target/CanonicalTargetAudienceProvider.kt)
requires a pinned explicit **server** `TargetAudienceBinding` and a current
`CanonicalTargetBindingSource`. That interface supplies read-only configuration,
not a bot registry, credential store or lifecycle writer. Its default returns null.
The production platform adapter still constructs only `ClientAudience.Platform`;
it does not install a finite TARGET binding or this provider.

Only the exact pinned canonical `TrustedBotConnection` object with a Platform
root can be narrowed into an explicit `ClientAudience.Client` finite set. Platform
alone, same connectionKey/token/name, client-scoped branded objects and unknown
connections deny. Current server configuration must match the pin's exact object,
client/club set, active flag and positive version both before and after the DB read.
Cancellation/revision changes invalidate the pin; adopting a new version requires
a new explicitly selected server pin, never request-body data.

The implementation cannot manufacture a live authoritative configuration source.
Without one it fails closed, even when DB mappings and Telegram HMAC are valid.
Tests inject synthetic configuration only. No environment variables/configuration
files or actual runtime bindings were introduced by this task. Branded connections
remain denied pending reviewed durable bot/client/club binding, lifecycle/revocation,
credential-reference, identity/generation and rotation contracts; see
[Managed Bots direction](../product/MANAGED_BOTS_DIRECTION.md).

## Read consistency and future mutation boundary

Each repository call owns one read-only READ COMMITTED transaction and executes
one parameterized SELECT over the full finite set, then rolls back on success or
failure. A concurrent commit cannot mix rows from different statement snapshots.
Rollback/close preserve the primary cancellation even if cleanup also fails.
DB/configuration errors become null at the provider without exception details or
credential logs. This API is blocking, matching the existing synchronous TARGET
verifier; future callers must execute it on an IO dispatcher. The test-only Ktor
adapter does so. No production request path calls it.

The immutable canonical snapshot includes exact mapping IDs/created_at metadata
and client lifecycle_version. `TargetAudienceBinding.sameSnapshot` compares these
across the verifier's existing three fresh provider reads, alongside the unchanged
bot HMAC/version/status checks. There is no cross-request authority cache. A late
result cannot match a different context/connection or silently replace a changed
snapshot within that verification. It remains a timestamped authentication result,
not an authorization ticket or ordering token for arbitrary HTTP responses.

Mapping created_at is **not** a monotonic generation, tombstone or durable epoch.
An arbitrary delete/reinsert restoring identical metadata, a config source that
lies about its current revision, or a change after the final read cannot be ruled
out by this reader. No such durable registry is claimed. Future issue/confirm must
revalidate exact config, canonical predicates and issuer/recipient authority after
lock waits, on the mutation-owned transaction/connection, with current policy,
membership, invitation/admission epochs, audit and receipt checks. Calling this
standalone reader before a mutation does not solve TOCTOU or supply those fences.

## Authority and legacy isolation

Audience is independent of user authority: a future invite recipient need not yet
have any users/membership/staff row. Signed identity can succeed while all staff
tables are empty. The result supplies no ownership, role, invitation permission,
finance capability, membership grant or admission. Platform-owner identity remains
the earlier configured authority. No legacy principal fallback, role inheritance,
provisioning, production DI/route, background job, grant writer or DB migration was
added. V077, existing readers/writers and the previous HMAC verifier are preserved.

## Verification

The final full candidate has 97 paths, with 18 changed in this slice. Of the reviewed
84 paths, 79 remain byte-identical. The five explicit updates are the audience
snapshot comparison, contract note, AS_BUILT, checkpoint and one reference-source
hash. All prior writers, V077, existing authorization/HMAC implementation/tests,
historical generated reports and CONCEPT_SOURCE are preserved.

Checks use `--no-configuration-cache --console=plain` for Gradle:

- Targeted H2 repository: **10/10 PASS**. Provider unit/Ktor/composition H2:
  **16/16 PASS**; previous TARGET identity27 and client-context17 also PASS.
- Real disposable PostgreSQL16: repository **10/10**, complete DB/provider/HMAC
  composition **1/1**, legacy ClientContext regression **16/16 PASS**, no skips.
  Each fixture owns only its synthetic schema; both local tmpfs containers were removed.
- `./gradlew detektGate :core-data:ktlintCheck :app-bot:ktlintCheck test` — PASS:
  **1,628 cases**, zero failures/errors, one pre-existing disabled GuestFlowStart case.
  All 26 new non-IT cases pass; together with PostgreSQL there are 37 new cases.
- Existing Flyway migrate/validate exercised on both vendors with unchanged migrations.
- `python3 -B -m unittest scripts.tests.test_role_target_identity_contract` —
  **23/23 PASS**; 68 scenario oracles preserved, only contract evidence hash refreshed.
- Secret scan, links/decision statuses, scope, prior-file hashes and tracked/new-file
  whitespace evidence are recorded alongside final full/delta manifests. No gate,
  scanner allowlist, Detekt baseline or test expectation was weakened.

Tests cover exact
subsets, private resources, newly added/foreign clubs, missing/moved mappings,
inactive/archive/restore lifecycle, statement snapshots across another connection's
commit, read-only fingerprints, revoked/current config and revision pins, DB failure,
cancellation/cleanup, no membership requirement, wrong trusted identity, snapshot
changes during HMAC verification and a test-only Ktor integration.

Initial local failures (test import/name/Java accessor ambiguity, a seed-user fixture
assumption, JDBC NestedBlockDepth and test InjectDispatcher) were corrected. The
recipient's absence is asserted independently of the historical V2 system-user seed.
The final gate passes all affected tests/static checks. No
stage/prod, real customer DB, invitations/assignments, commit/push/PR/merge/deploy.

## Remaining prerequisites and one next slice

No actual finite canonical configuration source, durable branded registry, ownership
provider, invitation nonce/proof v2, TARGET watermark/compound admission or runtime
authorization activation is delivered. The read-only result cannot restore revoked
sources or make platform membership restore a staff regrant.

One next implementation slice, after separate approval of canonical binding
lifecycle/persistence: a read-only current binding source adapter with exact
identity/version/revoke evidence, keeping invitations and production DI disabled.
Its mutation-transaction revalidation contract must be explicit. No source should
be inferred from legacy ownership, club creator, names or all-clubs membership.
