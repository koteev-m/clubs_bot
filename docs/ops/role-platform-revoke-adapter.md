# CLB-role-platform-revoke-adapter — local candidate

ROLE_PLATFORM_REVOKE_ADAPTER: **READY_FOR_REVIEW**. Local candidate only;
not a release or permission to materialize staff.
Registered detached worktree `/private/tmp/clubs-role-workspaces-proposed-20261109`,
HEAD and fresh remote main `5550b944990d8c1c93c0a5012d31db209f93ea6d`.
PROJECT_IDENTITY_OK; active Clubs Bot instructions, foreign=0.

## Candidate preservation and boundary

The independent foundation review manifest SHA-256 is
`0350dbfeae719b55073b8947b3614a393887b32a058a8e26694e4380aff610e1`.
All57 reviewed path hashes and the expanded working-tree path set matched before
adapter edits. Baseline, copied bytes, logs and final full/delta manifest are in
`/private/tmp/clb-role-platform-revoke-adapter-drylmbog/`. No prior path was deleted;
paired V077 migrations, immutable CONCEPT_SOURCE, instruction files and the other
checkout's changes are preserved. No new migration, dependency or public route.

ACCEPTED scope is the local platform revoke/restore adapter. The foundation remains
inactive for production authorization. No actual assignments/admissions, import,
real SHADOW materialization, policy installation, seeds or automatic grants run.
No stage/prod, commit/push/PR/merge/deploy. Old permission codes/product duties and
historical decision records are unchanged.

## Existing transaction and adapter

[ClientAccessOperations.change](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientAccessOperations.kt)
is still the only platform membership mutation path. Existing signed Telegram
route checks and configured platform owner authority remain. The repository's
transaction uses actor lock → exact client row lock → current member version and
active transition checks. The additive adapter then locks that client's admission,
role and delegation rows in deterministic order, matching foundation writers.

[Transaction-local marker](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/PlatformMembershipSourceRevocation.kt)
can only be privately constructed after platform/current-member checks. It is bound
to the exact session/client/user/member-version/transition and consumed once by
`ClubStaffAccess.platformRevoke`. Direct callers without a marker retain the old
mixed-subject fail-closed guard. This is not a caller-provided skip-guard flag.

[Source invalidator](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/PlatformMembershipSourceInvalidator.kt)
performs **revoke** as follows:

1. Revoke every ACTIVE role source of the subject in this client, regardless of
   expired/missing policy metadata. Revoke all ACTIVE incoming and outgoing
   delegations and role dependencies. Rows become terminal; source IDs are retained.
2. Every existing subject admission, including PENDING/empty SHADOW/already DISABLED,
   becomes DISABLED with epoch/version +1. Clear compatibility manifest/hash; retain
   or advance legacy invitation watermark using the existing club invitation fence.
3. Bump only affected delegation recipients' admission revisions. Their independent
   role sources, raw grants and memberships stay intact. Another client is untouched.
4. Continue old staff deactivation/watermark/team-version handling and old grant
   cleanup. CAS membership active→inactive, then legacy audit and existing receipt.

[Source audit](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/PlatformMembershipSourceAudit.kt)
records action `client.staff.platform_membership_revoke`, exact subject/client,
platform authority, correlation hash, safe before/after source and fence IDs/status/
versions/pins. It excludes unrelated subjects, secrets, raw keys and invitation tokens.
Source audit, sources, fences, old grants/staff, membership, audit and durable receipt
commit or roll back together on the same connection. No source/provider/evaluator
needs to authorize the act of terminating authority.

**Restore** retains the existing explicit legacy restore contract: requested
`client.application.manage` or existing whitelisted club grants, existing row only,
creator/staff protections, active/unarchived client. Restoring an old club grant in
any controlled admission/source-history scope is rejected before commit, including
an empty SHADOW admission that has now been disabled. Otherwise a flat grant could
bypass the disabled fence through the mounted legacy reader. No new permission
code or whitelist expansion; for example `client.club.booking_rules.write` is not added.

Membership-only restore is allowed for a mixed subject. Already revoked sources
and disabled admissions remain byte-identical: no epoch rewind, source creation,
reactivation or new receipt for an old assignment. If member-inactive data has a
dangling ACTIVE source/admission, restore first quarantines it and audits
`client.staff.platform_restore_fence`. New staff authority later needs separate
fresh authorized re-admission, new IDs and explicit regrant acknowledgement.

No V077 records for this client/user (including outgoing issuer sources) means no
new source audit or foundation writes: old revoke/restore results remain intact.
Schema absence is accepted only by the existing proven pre-V077 Flyway check;
partial/missing-after-V077 schema fails closed. Historical invitations keep the
old protocol; restore neither accepts/reissues them nor converts them into TARGET
invitations. Controlled tuples still deny old confirmation/claim. Uncontrolled old
pending invitations retain their existing behavior; this adapter creates no fence
or membership for their unknown claimants. The future TARGET invitation protocol
and authoritative pending-invitation watermark provider are still absent.

## Concurrency, replay and failure semantics

Shared client locking serializes platform, foundation and guarded legacy writers.
CAS rechecks member/source/fence versions and epochs under that lock. SQLState
40001/40P01 retries open a fresh transaction and recheck current authority/state;
replayed durable commands return current state, never rerun the old grant payload.
A stale distinct command fails version_conflict; a revoked source stays terminal.
Cancellation observed before platform commit rolls back the whole transaction,
even when the cancelled JDBC command was waiting on the client lock.

Real PostgreSQL barriers observe `pg_stat_activity.wait_event_type='Lock'`, rather
than guessing with sleeps. Both orders are covered for revoke vs role issue,
delegation issue (recipient and issuer), two revokes, full disable and legacy staff
mutation; restore vs old staff replay and explicit re-admission. The earlier
admission may commit before revoke, but revoke then terminates it. If revoke wins,
queued stale issue/re-admission fails. Restore itself never revives authority.
Full disable/revoke revisions cannot overwrite one another. Legacy mutation on a
controlled tuple is denied; legal legacy mutation before revoke is then cleaned up.

## CURRENT / TARGET / DIFFERENCE / RISK

These are disposable synthetic records; TARGET is isolated evaluation, not runtime
permission activation. The preserved46 inventory and38 reference scenarios remain
[separate evidence](role-assignment-shadow-report.md).

| Scenario | CURRENT | TARGET / DIFFERENCE | RISK / expected result |
|---|---|---|---|
| No V077 state | Old member/grants/staff | Old revoke and explicit restore result | No new source audit or interruption of legacy users |
| One role | Live exact-club contribution | Same ID REVOKED; admission disabled/epoch +1 | No historical source can authorize after commit |
| Two roles + delegation, several clubs | Several independent staff sources | All subject scopes in this client terminate | No source/club omitted; no GLOBAL inheritance |
| Revoked issuer | Outgoing DID plus recipient's own FLOOR | DID terminal; FLOOR still live; recipient revision +1 | Prevent both stale delegation and loss of independent authority |
| Other client | Same user with another independent membership/source | Other client byte-identical | No cross-client revoke/leak |
| Membership restore | Inactive member, terminal staff history | Member active; staff history unchanged | Membership is not admission; no accidental regrant |
| Explicit legacy controlled club restore | Disabled admission/history plus raw grant | Whole restore rejected/rolled back | Mounted raw reader cannot bypass the fence |
| Old invitations/receipts | Historical protocol/result pointer | No issue/accept/reexecute; current response | No indirect resurrection through stale replay |
| Fresh later admission | Disabled epoch + revoked source | Separate authorized fresh proof/new IDs/ack required | Old UUID/epoch/receipt is insufficient |
| Late audit/receipt failure | Existing live or inactive state | Full snapshot unchanged | No partial source/member cleanup or ghost receipt |
| SQL retry after another revoke | Rolled-back first source update | Fresh attempt sees new member version and denies | No retry using stale authority/saved grant payload |
| Concurrent mutation/cancellation | Commands waiting on shared lock | Serializable safe outcomes or complete rollback | No lost tombstone, partial permission or stale committed grant |

Concrete test-policy example: FLOOR contributes `night.read` and
`floor.tables.read`; a calendar delegation adds `client.club.calendar.read` and
`client.club.calendar.write`. Platform revoke changes that exact client's union
from those four permissions to empty; membership restore leaves it empty. An
independent recipient FLOOR source keeps its two permissions when only its
issuer-dependent calendar delegation is revoked. These codes are synthetic policy
fixtures, not newly accepted or issued production permissions.

## Verification

All commands ran from the registered worktree. Gradle used
`--no-configuration-cache --console=plain`; no task/baseline/rule was weakened.
The earlier MaxLineLength and three test NoNameShadowing findings were corrected
and the affected gates rerun. No remaining security/concurrency finding was found
by targeted self-review or the independent production-code review. A separate docs
review corrected two abbreviated permission names to the exact existing codes.

| Command / check | Result |
|---|---|
| Project identity, fresh GitHub main, original review manifest | PASS; main unchanged5550; all57 original hashes matched before edits |
| `:core-data:compileKotlin :core-data:compileTestKotlin :app-bot:compileTestKotlin` | PASS |
| Initial focused H2 + legacy fence + mounted Ktor tests | PASS 25/25; later retry case added and included in broad gates |
| Initial focused PostgreSQL adapter + concurrency | PASS 18/18; both orders16 + cancellation revoke/restore; later retry included below |
| `detektGate :core-data:detektTest test` | PASS 1,575 cases, 0 failures/errors; one pre-existing disabled Guest test |
| `test -PrunIT=true` | PASS 2,088 cases, 0 failures/errors, 11 existing skips; core-data776, app-bot1,178 |
| New adapter suites within final full IT | H2 **11/11**, PostgreSQL **11/11**, PG concurrency **8/8**, Ktor **4/4**, zero skips |
| `:core-data:ktlintCheck :app-bot:ktlintTestSourceSetCheck` | PASS after final test fixture correction |
| `python3 -B -m unittest scripts.tests.test_role_shadow_inventory scripts.tests.test_role_assignment_contract` | PASS 93/93; all46 +38 scenario IDs/oracles retained; source pins26 +34 |
| Pinned `scripts/secret-scan.py` current tree + available history | PASS; no findings or scanner exceptions |
| `git diff --check`, affected links/fragments/statuses/scope | PASS; one unchanged historical CLB83 temporary link remains unavailable |
| V077/CONCEPT_SOURCE/AGENTS and prior/other-checkout preservation | PASS; code hashes45 paths identical across final IT |

The 11 skips are ten unrelated native-support opt-in cases
(`CLB_SUPPORT_CREATION_TEST_PG_BIN` not supplied) and one existing disabled
GuestFlowStart test. New adapter/foundation cases are not skipped. The PostgreSQL
suite proves actual locks/transactions, while the preserved offline reference
interleavings remain only a value model. The full suite also reruns owner/team/
preparation/invitation/bootstrap, current authorization and migration regressions.

Late failure injection verifies, on the same actual connection, that membership
and role source have already changed before audit/receipt failure; the complete
snapshot then rolls back. The 40001 test verifies rollback and connection close,
commits a separate legitimate revoke before retry, and proves the retry sees fresh
membership/CAS and adds no extra effect. Cancellation tests wait on a real PG client
lock for both revoke and restore and preserve the complete before snapshot.

Disposable environment: owned loopback PostgreSQL16.14 `clb_client_test`, per-test
owned schemas, H2 in memory and existing local Testcontainers regression fixtures.
No actual client databases or production environment variables. The owned adapter
container is removed after the checks. The long full IT run completed successfully
in 16m37s; a bounded worker stack confirmed migration validation progress, not an
RBAC deadlock. Other users' processes/containers were not stopped.

Sanitized raw logs/XML/snapshots: `/private/tmp/clb-role-platform-revoke-adapter-drylmbog/`:
`compile.log`, `target-h2-routes.log`, `target-postgres.log`, `full-jvm.log`,
`full-postgres.log`, `lint-final.log`, `shadow-tests-final.log`, `secret-scan-complete/`,
`full-jvm-evidence/summary.json`, `full-it-evidence/summary.json`,
`identity-final.log`, `docs-checks.json`. The first static failures and successful
reruns are retained, rather than hidden.

Full **69-path** candidate manifest: `candidate-manifest.json` in that directory;
SHA-256 metadata, Git status and before hashes distinguish the **23-path task delta**
from 46 byte-preserved prior paths. `task-delta-manifest.json` contains only this
adapter's changes. The original57-path review manifest is retained separately.
No prior candidate file was deleted. This is local evidence, not a committed SHA.

The 23 changed paths comprise seven production Kotlin files (three new adapter
classes, four existing integration/helper files), eight test files (seven new and
one updated guard test), five documentation files and three shadow source-pin/helper
files. No API route, schema migration, invitation writer, permission registry,
legacy package algorithm, build file, dependency, frontend or immutable concept
changed in this adapter delta.

## Remaining boundary and one next step

No new evaluator call, DI binding, policy catalog, operational endpoint or UI merger
was added. Identity/ownership/TARGET admission providers still default deny. Direct
privileged SQL is outside application guards; no runtime rollout/rollback is proven.
The platform adapter removes one compatibility blocker; it does not approve real
SHADOW writes, staff import, assignment activation or production authorization.

One next step: review a bounded signed TARGET admission/identity adapter contract
and its fresh-invitation/epoch/regrant gates before any real staff materialization.
