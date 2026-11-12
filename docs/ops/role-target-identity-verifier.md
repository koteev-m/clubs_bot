# ROLE_TARGET_IDENTITY_VERIFIER — local read-only foundation

**ROLE_TARGET_IDENTITY_VERIFIER: READY_FOR_REVIEW.** Unpublished candidate;
not production RBAC activation. Project identity PASS, active Clubs Bot instructions,
foreign=0. Registered detached worktree `/private/tmp/clubs-role-workspaces-proposed-20261109`,
HEAD/fresh main `5550b944990d8c1c93c0a5012d31db209f93ea6d`.
The previous 74-path manifest matched before edits, SHA-256
`1163091b494f3a17d92e0d161b2f1780857c98d3a7ca02d7b8ed17d1f4440549`.
Baseline/logs/XML/final full+delta manifests:
`/private/tmp/clb-role-target-identity-verifier-u3rl7ryx/`.

## Implemented guarantees

[Internal verifier](../../app-bot/src/main/kotlin/com/example/bot/plugins/target/TargetIdentityVerifier.kt)
returns a minimal sealed `TargetVerifiedIdentity`: positive Telegram user ID,
exact client/club context, connection key, audience revision, auth timestamp and
valid-until. Private implementation has no public constructor/copy, no serializer,
Ktor Principal, role/permission/ownership/admission interface or raw credential.
`matchesContext` is only structural matching, never freshness or authorization.
A platform-owner-shaped Telegram ID is still only authenticated identity here.

HMAC uses the existing [InitDataValidator](../../core-security/src/main/kotlin/com/example/bot/security/auth/InitDataValidator.kt)
via the small internal `TrustedBotConnection.verifyWithPolicy` method in
[ClientContextAuth](../../app-bot/src/main/kotlin/com/example/bot/plugins/ClientContextAuth.kt).
No second HMAC algorithm/key access or dependency was introduced. The old
`verify(raw)` and all shared validator defaults remain unchanged (24h max age,
2min future tolerance, existing duplicate-field semantics). The new wrapper alone
requires **0 ≤ age <300 seconds**, with **zero future skew** and nanosecond boundary
checks. It checks clock/audience after credential work; expiry or a backward step
during verification rejects the result. A trustworthy server UTC clock is required;
this stateless component does not detect clock rollback between different requests.

The [strict TARGET parser](../../app-bot/src/main/kotlin/com/example/bot/plugins/target/TargetInitDataCredentials.kt)
accepts one canonical `X-Telegram-Init-Data` header, case-insensitive header name;
rejects duplicate/combined values, alias/Authorization credentials, any query string,
raw whitespace/control/non-ASCII bytes and input over8192 bytes. No body extraction,
URL/body bot/user/client identity, or legacy principal fallback. Unknown role/tenant
hint headers cannot change the server context or signed user.

Form decoding rejects missing/empty segments, malformed percent escapes, invalid
UTF-8 instead of replacement decoding, encoded key names, duplicate fields and
case variants of user/hash/auth_date/signature. Values are decoded once from raw
in each parser path; decoded values are never reparsed as form credentials.
[JSON key guard](../../app-bot/src/main/kotlin/com/example/bot/plugins/target/TargetJsonKeys.kt)
rejects duplicate object keys, including escaped spellings and nested objects/arrays;
then kotlinx JSON validates syntax. Depth is bounded32. User ID must be an unquoted
positive signed64-bit integer; exponent/decimal/bool/overflow forms are rejected.
Only hash is excluded by the existing HMAC algorithm; signature remains covered.

## Trusted audience boundary

[TargetAudienceProvider](../../app-bot/src/main/kotlin/com/example/bot/plugins/target/TargetIdentityAudience.kt)
is an internal read-only interface for current trusted server snapshots. Default
provider returns null. No real provider, DI, registry installation or database
reader is connected by this task. Tests use synthetic finite client/club bindings.
The provider must prove the server mapping and connection status independently of
request data, and advance version/replace connection on rotation/rebinding.
Connection key is non-secret metadata, never a credential value.

A binding must be active, version>0, the **same trusted connection instance**, and
an explicit `ClientAudience.Client` containing the exact client/club. It may not
widen the underlying client connection's finite audience. Empty sets mean deny.
`ClientAudience.Platform` alone never permits a TARGET client. A canonical connection
can authenticate in an explicitly supplied finite client binding, with no automatic
network/platform authority. The same signed identity cannot reuse a result for a
different client, club or connection object.

The verifier reads snapshots before, between and after two calls to the existing
HMAC validator. This rejects changes to version/status/scope during verification;
the second key check also rejects a rotated credential even if the opaque provider
failed to advance its metadata version. It does not claim to lock future config
changes: results are authentication snapshots, not reusable authorization tickets.
Future mutation adapters must call the verifier again after lock waits and verify
current membership, authority, source/fence/policy and audience in their own transaction.

Failures return null without parser/provider exception detail or logging credentials;
CancellationException is rethrown. No raw initData, bot token, signature, envelope or
idempotency key is stored or returned by the verifier. Test-only Ktor probes use the
project's common error envelope; no new production route/error schema exists.

## Replay and inactive boundaries

Signed initData is deliberately reusable authentication within its validity window.
No nonce consumption, invitation intent MAC, proof v2, durable command, membership,
role assignment, delegation, grant or admission is produced. Ownership providers
and the V077 admission verifier still default deny. Existing authorization does not
call this verifier. Operational/legacy routes and AdminShell wiring are unchanged.
The [TARGET invitation contract](../product/ROLE_TARGET_IDENTITY_CONTRACT_PROPOSED.md)
remains PROPOSED outside this specifically authorized read-only identity slice.
V077 and platform revoke/restore implementation are byte-preserved.

## Verification and evidence

The final full candidate contains 84 paths; this slice changes 15 paths. Of the
previous 74 paths, 69 remain byte-identical. The five intentional changes are
README, AS_BUILT, checkpoint, the TARGET contract's implementation note, and the
reference fixture's two evidence hashes. Its 68 scenario oracles remain unchanged.
All previous production RBAC/writer/V077 files and CONCEPT_SOURCE are byte-preserved.
Per-path SHA-256, full/delta manifests, preservation/link/status checks, command
results and XML are recorded in the evidence directory.
New tests cover HMAC, wrong token/signature, unknown/inactive connection, exact
client/club, platform vs explicit canonical binding, 299.999999999/300-second
boundaries, future auth_date, clock changes, malformed/duplicate form and JSON,
invalid UTF-8 with a valid legacy replacement-decoded HMAC, invalid IDs, stale
metadata/key rotation, cancellation, repeated authentication, safe result type,
no legacy fallback, no credential logs and unchanged12h legacy acceptance.
Ktor probes exist only in `src/test`; no browser behavior changed.

Commands use `--no-configuration-cache --console=plain`:

- `:app-bot:compileKotlin` — PASS.
- Initial targeted new + legacy auth tests: unit/legacy checks PASS; three new Ktor
  fixtures initially returned406 because JSON content negotiation was missing.
  Fixture corrected; expectations kept401 using the shared error envelope.
- `:app-bot:test --tests '*TargetIdentity*Test' :app-bot:detektMain :app-bot:detektTest :app-bot:ktlintCheck`
  — PASS before final two boundary additions; final broad gate includes them.
- `detektGate :app-bot:ktlintCheck test` — PASS: 1,602 JVM cases, zero failures/errors,
  one previously disabled `GuestFlowStartTest`. All 27 new tests pass without skips,
  including three Ktor test-host cases. XML totals are in `full-jvm-evidence/summary.json`.
  Initial ReturnCount/ComplexCondition findings corrected without suppressions/rule changes.
- `python3 -B -m unittest scripts.tests.test_role_target_identity_contract` — PASS,
  23 tests; only the two changed evidence-source hashes were refreshed. The generated
  68-scenario report is byte-identical to its prior version.
- `python3 -I -S -B scripts/secret-scan.py --output <evidence>/secret-scan` — PASS,
  zero current-source/history findings. No scanner configuration/allowlist changed.
- Project identity, links/statuses/scope, previous-candidate preservation and
  `git diff --check` checks are recorded alongside the final manifests.

No PostgreSQL/IT rerun is required: no DB/transaction/writer/migration changes in
this delta. Earlier DB evidence is retained, not presented as a test of this verifier.
No real customer identity/database, stage/prod, commit/push/PR/merge/deploy.

## Remaining risks and one next slice

Production audiences/managed-bot registry, business ownership proof, invitation
nonce/proof v2, issuer revalidation, TARGET cutoffs/admission/compound issuance and
UI response fencing remain unimplemented/unwired. The parser guarantees do not
prove an actual client membership or authorize a booking/finance/staff operation.
The strict JSON guard is isolated to TARGET; accepted future Telegram payloads
must still satisfy this bounded parser policy, and unknown metadata fails closed.

One next implementation slice: a read-only canonical audience provider backed by
reviewed current server client/club mappings and version/status semantics, tested
without grants, invitations, staff materialization or authorization activation.
Any new persistence or invitation architecture still requires separate approval.
