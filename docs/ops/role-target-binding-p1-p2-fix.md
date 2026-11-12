# ROLE_TARGET_BINDING_P1_P2_FIX — focused local correction

**ROLE_TARGET_BINDING_P1_P2_FIX: READY_FOR_FOCUSED_REVIEW.**
Only the two independent findings are addressed; no next binding slice or activation.

## Baseline and authority

Task `CLB-role-target-binding-p1-p2-fix`, PROJECT_IDENTITY_OK; active Clubs Bot
instructions, foreign=0. Registered detached worktree
`/private/tmp/clubs-role-workspaces-proposed-20261109`; HEAD/fresh remote main and
HEAD/origin-main merge-base `5550b944990d8c1c93c0a5012d31db209f93ea6d`.
No ref update/rebase. All 138 supplied candidate paths/hashes matched before edits,
with no additional changes. Reviewed manifest SHA-256
`442eee32287910f8fa8ef38619cf157c69125f6d159c5fcec34411b528e75828`.
Byte copies, commands, RED/GREEN XML, tested-input hashes and full/delta manifests:
`/private/tmp/clb-binding-p1-p2-iopfhqmp/`.

Independent evidence `/private/tmp/clb-binding-security-review-h8us_k6s/` is
unchanged. The original SQL probe was executed unchanged against an owned
synthetic PostgreSQL, then adapted in the task evidence directory to substitute
only this fixed V078, an independent schema and explicit assertions. No reviewer
file was edited. V077/historical migrations, existing authorization/routes/Mini
App/staff permissions/ownership and CONCEPT_SOURCE remain untouched.

## P1 — trusted schema resolution

**Root cause:** V078 function bodies resolved unqualified relations and helper
functions from the invoking session. TEMP clients intercepted the mapping
trigger's UPDATE; permanent projection/head did not advance despite real mapping
DELETE/reinsert. This is ordinary TEMP/name masking, not privileged trigger disable.

**Fix:** unpublished PostgreSQL V078 follows the existing V056 Flyway convention:
`"${flyway:defaultSchema}"` qualifies all created/referenced relations, own
functions and trigger targets. No assumption of public. Every function pins
`search_path = pg_catalog, <trusted migration schema>, pg_temp`; pg_temp is
explicitly last. Builtin unnest/regexp_like are pg_catalog-qualified. Migration
DDL/check binding also uses a transaction-local protected path. No session path,
club name or request field supplies the authoritative schema. No SECURITY DEFINER,
new GRANT or changed SQL privilege is introduced by the migration.

Tests attack TEMP and separate hostile schemas with matching clients, high-water,
root/binding/material relations, fake helper functions, exact unnest overload and
BIGINT addition operator. All six V078 functions are checked for the exact pinned
configuration and invoker security. A populated V077 upgrade is tested with a
synthetic NOSUPERUSER/NOCREATEDB/NOCREATEROLE invoker using TEMP and only explicit
fixture DML grants; these test grants do not exist in migration/runtime code.
Permanent counters advance; temporary/hostile copies do not. Material retirement
and old-incarnation checks ignore TEMP status lies.

## P2 — every child write participates in its aggregate fence

**Root cause:** child semantic checks rejected ACTIVE/terminal changes but did not
advance the parent fence. Direct DISABLED UPDATE could commit with root1/1 and
leave the original create receipt COMPLETED_CURRENT on both vendors.

**Fix: variant B, DB-enforced atomic fencing.** Before child DML, the guard locks
its exact root with FOR UPDATE NOWAIT. Each successful row INSERT/UPDATE or allowed
scope DELETE advances both parent counters in the same transaction. Binding,
material, credential revision and scope children are covered. Immutable reference,
root identity, terminal material/binding and ACTIVE guards remain enforced.
Rejected/rolled-back writes leave no fence or state change.

Only the initial unselected Create bootstrap binding is exempt: root.current is
NULL, no readable/current result exists, and initialization remains one atomic
transaction. Other child operations on an uninitialized root deny. A selected root
can never be reset to NULL; this exemption cannot apply to an issued create receipt.
Receipts themselves remain immutable records and do not advance their own fence.

**Counter semantics:** each child row write is a revision, not one command number.
The service retains its actor→root lock and validates the caller's exact input CAS
before any mutation. After guarded children, it reads that same locked aggregate
and CASes the exact post-child fence for its final audit/receipt update. Receipt
pins use the final returned revision. For example Create1 → Disable3 → Revoke5 →
fresh incarnation7; a fresh reference stages material/revision/selection and reaches
5, then one-material Revoke8 and new incarnation10. Exact numeric assertions were
updated to these stronger storage semantics; no status/replay/rollback/scope
assertion or test was removed. Callers must use the returned fence, never predict +1.

**Concurrency:** ordinary row DML may already hold a child tuple lock before its
BEFORE trigger. Waiting there for root would invert service order and deadlock.
NOWAIT removes that backward wait edge: a contending ordinary statement fails
closed (PostgreSQL55P03 / H2HYT00), with no partial child/root change. The service
already owns the root and does not falsely conflict. If direct DML commits first,
a waiting service sees its new fence and rejects stale input before audit/source
mutation. No caller-set session flag, bypass token or proof of authority is used.

Raw DML consistency is not lifecycle authorization: a DB principal still needs its
existing table permissions, including parent mutation. Missing privileges fail
closed; no rights are granted. Authorized service operations still supply fresh
maintenance attestations, CAS, idempotency, audit and receipt atomically.

## RED → GREEN and validation

- Three original failing cases captured RED on the unchanged implementation:
  H2 child update, PostgreSQL child update and TEMP epoch masking. XML/logs in
  `red-xml/`, `red-regressions.log`; original probe in `original-probe-red.log`
  retains the review result2/2 and temporary1002.
- The same three regressions GREEN. Expanded **73 cases** (H2 repository31,
  PostgreSQL42) PASS, zero failures/skips; `foundation-final-xml/` and exact
  `tested-inputs.json`. Existing Create/Disable/Revoke, terminal history, references,
  client/club isolation, both identifier modes, migrations, replay, stale versions,
  audit/receipt rollback, cancellation and SQL retry cases remain.
- Direct status/ref/material/revision/scope writes, invalid FK rollback, immutable
  rejection and uninitialized-root behavior are exercised. NOWAIT is checked on both
  vendors. Real PostgreSQL tests cover both raw-child/service CAS orders and the
  exact current/stale receipt results. Existing shared readers/mapping writers still
  serialize correctly; no lock was added to mapping writers by P1.
- Adapted independent SQL probe PASS: permanent projection/head2→4, TEMP1000
  unchanged; explicit mapping rollback preserves both. Review-equivalent populated
  fixture3→5 also passes. Actual independent SERIALIZABLE probe PASS: SQLSTATE40001,
  before4→final6, failed transaction fully rolled back, retry preserved both bumps.
- `./gradlew --no-configuration-cache --console=plain --continue test detektGate
  :core-data:ktlintCheck :app-bot:ktlintCheck` — **PASS**, **1,661 cases**, zero
  failures/errors; one pre-existing disabled GuestFlowStart skip. `jvm-final-xml/`.
- Closing targeted `test -PrunIT=true` on core-data/app-bot — **104/104 PASS**,
  no skips: foundation73 + legacy PostgreSQL client context16 + existing Ktor15
  (PostgreSQL owner7/preparation2/first-second-club2; H2 staff2/club2).
  `targeted-final-xml/` includes fresh hashes for every actual final binding input,
  including test-only bootstrap deny assertions added after the broad JVM run.
  Kotlin/SQL production bytes did not change after that broad run. Affected
  Detekt/ktlint also PASS. No old tests, tags or assertions were removed.
- Reference tests: `python3 -I -S -B -m unittest discover -s scripts/tests -t .
  -p 'test_role*.py'` — **131/131 PASS**, literal scenario/oracle JSON preserved;
  only the trusted-binding document digest changed. Unrelated full IT/native-support
  opt-in suites were not repeated; all affected H2/real PostgreSQL paths were.
- Source/history secret gate uses the pinned project `scripts/secret-scan.py`;
  sanitized result in `secret-scan-final/`. Scope/preservation/introduced links,
  unchanged decisions, exact tested hashes and tracked/delta-untracked whitespace:
  `scope-preservation-links.json`. Two older unchanged shadow reports retain their
  already-reviewed blank line at EOF; the full untracked check records those
  baseline diagnostics separately. No new whitespace error.
- Final inventory: **143 paths**, **16-file delta**; prior **127/138 byte-preserved**.
  Only three prior production/schema paths changed: PG V078, H2 metadata guard,
  binding writer. Three existing tests retain exact assertions with new revisions;
  four regression test files are new. Four existing docs plus this report and one
  source digest complete the delta. Full `candidate-manifest.json` and
  `candidate-inventory.tsv`, `task-delta-manifest.json`, `manifest-sha256.txt`,
  and complete `final-source-input-manifest.json` live beside logs/XML.

Initial correction iterations caught a schema-qualified trigger *name* syntax
error (trigger target/function are qualified; the trigger name is not), and lint/
Detekt helper-depth/formatting findings. Corrected without suppressions, baseline
changes, schema guard relaxation or reduced test selections. After the last Kotlin/
SQL change all affected H2/PostgreSQL/static checks are repeated; manifests use
actual final bytes, not earlier task input hashes.

## Isolation and remaining boundaries

No new TARGET authority/provider, production DI, route, job, provisioning, actual
bot token, invitation, assignment or grant. NightConcierge continues its existing
protected Platform connection; new registry/evaluator are unwired. Legacy owner/
team/preparation and Mini App authentication are checked through their existing
paths. No server/stage/prod, real DB/staff data, publication or employee materialization.

Trusted deployment schema must remain protected from untrusted permanent DDL.
Administrative DROP/ALTER, trigger disable/session_replication_role, destructive
TRUNCATE and old-backup recovery require separately authorized reconciliation and
fencing. Ordinary TEMP/search_path and child row DML are explicitly inside these
fixes; they are not excused as administrator actions. No infrastructure was changed
for DB-administrator resistance. Existing guards continue to reject ordinary
mapping/history TRUNCATE; this is not a backup-recovery guarantee.

Real credential resolver/material attestation, signed maintenance adapter, durable
binding consumer/proof v2, TARGET Issue/Confirm and branded onboarding remain absent.
They are not started here. Disposable tests prove storage/locking behavior only. The task-owned tmpfs,
loopback-only PostgreSQL16 container is removed after these checks; no actual
client database is used.

**One next step:** independent focused review of this P1/P2 correction and its final
input manifests. Stop before the next functional binding slice.
