# Client club layout: local implementation

Accepted scope: [PRODUCT_SPEC §29.12](../product/PRODUCT_SPEC.md#client-club-layout),
bounded `ONB-004`. Base: `dbdbd2987298178ea22c06d8fe26302d1668ddec`.
Worktree: `/private/tmp/clubs-layout-worktree`, branch `codex/client-club-layout`.
`CLB-client-club-layout` is a descriptive identity-guard label, not a new card ID.
The original implementation stage authorized only local code, paired migrations,
synthetic tests and documentation. It performed no publication, real data,
Telegram configuration, credential or Stage/prod actions.

## Draft PR publication continuation

The owner subsequently authorized one implementation commit, one ordinary push and
one Draft PR into `main`. Merge, workflow rerun/dispatch, deployment, real data,
Telegram, secrets and GitHub Environment changes remain outside this authorization.
Live `origin/main` still equals the base above. Before publication, all 50 candidate
paths and hashes, and the whole 1,547-file candidate digest, matched the retained
`LOCAL_LAYOUT_READY` evidence exactly. Other worktrees are preserved.

Publication review covers paired V065, creator provenance, revoke tombstones,
exact grants, private containment, image isolation and unchanged test gates.
Implementation/test/dist bytes remain unchanged; only this report and the scoped
checkpoint record the publication continuation. Prior successful tests are reused
for those identical bytes. Publication checks and resulting commit/remote/PR/initial
CI identities are retained in `/private/tmp/clubs-layout-publication/` and the final
handoff. A Draft PR does not establish CI completion or runtime acceptance.

## API and storage

All endpoints use the existing fresh platform-bot signature guard. Legacy roles,
headers and URL modes never supply client authority. Missing and foreign resources
return the common `not_found` envelope; successful responses are no-store.

| Method and path, relative to `/api/client/clubs/{clubId}` | Input / result |
|---|---|
| `GET /layout` | Current permissions and halls, tables, plan metadata; IDs and revisions are decimal strings |
| `POST /halls` | `{name}`; creates a hall and `main` base zone |
| `PATCH /halls/{hallId}` | `{expectedVersion,name}` |
| `POST /halls/{hallId}/tables` | `{expectedVersion,tableNumber,label,capacity,x,y}` |
| `PATCH /halls/{hallId}/tables/{tableId}` | `expectedVersion` plus changed table fields |
| `POST /halls/{hallId}/plan` | Raw PNG/JPEG; `X-Layout-Version` and `Idempotency-Key` |
| `GET /halls/{hallId}/plan` | Authenticated image bytes; no ETag/304 path; `nosniff` |

Every mutation requires an idempotency key and returns the complete current layout.
All non-create operations verify the hall revision. Coordinates use `[0,1]`;
number is positive, capacity is 1–100, hall names are 1–255 characters and table
labels are 1–100 characters.
The client lock serializes layout commands with membership revocation. Replay
rechecks current read and write grants before returning current state. A hall revision,
domain change, durable command and audit event commit atomically.

The existing `halls`, `hall_zones`, `hall_tables` and `hall_plans` are reused.
Administrative repositories and `PrivateClubContainment` retain their legacy
boundaries. The client UI reuses `ClubHallsScreen`, `HallEditorScreen`,
`HallPlanStage` and contain-coordinate helpers, through a scoped API adapter.

## Migration and image boundary

Paired `V065__client_club_layout.sql` extends only the exact permission and command
result allowlists. Direct and historical approved club creation include the creator's
new grant in their existing transaction. Upgrade selects only saved creator provenance
from direct profiles or historical onboarding, a matching private club/client mapping,
active client/membership and active exact read/profile.write. It inserts no row when
any layout grant already exists, including an inactive tombstone. Bootstrap is audited.
There is no staff, GLOBAL/OWNER, unmapped-club or username-based adoption.
Existing explicit revoke/restore remains authoritative; restore can select only
previously existing exact grants and cannot invent a new grant.

Uploads accept PNG/JPEG up to 5 MiB. Server-side ImageIO checks the actual format
and dimensions before decoding (at most 25 million pixels), then re-encodes the
image, removing supplied metadata and trailing content. The normalized file must
also fit 5 MiB. No image body, initData or idempotency key is logged or included
in audit. Image reads repeat current grant and hall/club checks before returning bytes.

## Verification record

The final command record below is local evidence only. Real Telegram and hosted
CI/Stage/prod are outside this task and are not established by local passing tests.

## Focused review and corrections

Independent bounded reviews covered client/resource joins, fresh exact authority,
replay, migration prerequisites, image handling, editor reuse and legacy containment.
The following findings were fixed before final acceptance:

- Ambiguous creator provenance now fails closed in V065; migration audit uses a
  system actor and records the beneficiary, with exact club context.
- Image decode/encode uses local memory streams, with no global ImageIO setting
  or temporary uploaded-image cache. Metadata and trailing content are removed.
- A terminal access-loss fence and ordered reads prevent late responses from
  restoring private data or stale write permissions. Deferred image-body completion
  after denial, external cancellation or unmount cannot create a retained blob URL.
- Native Back after revoke exits the denied editor. Unsaved metadata survives a
  cancelled drag; the shared stage respects a rejected selection before starting drag.
  Denial during a mutation clears `saving` before its terminal notification, so
  browser history is not trapped by a stale pending flag.
- The first hall alone becomes active; existing legacy selection semantics are
  preserved. Table-create audit includes the new table ID.

Initial failures were corrected rather than waived: lint formatting/depth, and a
test identity assumption for coroutine stacktrace recovery. The cancellation test
now requires the exact original exception in a bounded cause chain, its message,
and unchanged rows in halls, zones, tables, plans, command ledger and audit after
rollback. Sequence counters are outside that snapshot; PostgreSQL may consume an
ID on rollback. Test-only changes
do not relax production cancellation propagation.

Review evidence and final source hashes: `/private/tmp/clubs-layout-evidence/review-api-final.txt`.
Formatter-only changes to unrelated legacy files were restored to exact HEAD bytes;
normal lint/static checks run without the local formatting scope.

## Executed commands

Commands run from the worktree root, except frontend commands from `miniapp`.
Gradle uses the repository's bounded supervisor, normal cache access, one test fork
and a 1024 MiB test heap. PostgreSQL is task-owned PostgreSQL 16 on
`127.0.0.1:54965/clb_client_test`, synthetic user `clb_client_test`, tmpfs data.
`ORG_GRADLE_PROJECT_runIT=true` selects the existing `runIT` project property.

| Command | Result |
|---|---|
| `python3 -I -S -B scripts/project-identity-guard.py --task-id CLB-client-club-layout --expected-head dbdbd2987298178ea22c06d8fe26302d1668ddec` | `PROJECT_IDENTITY_OK`; separate active-chain attestation: clubs_bot only, foreign instructions = 0 |
| `node node_modules/vitest/vitest.mjs run` | Final 257/257 PASS, 36 files |
| `node node_modules/typescript/bin/tsc --noEmit` | PASS, exit 0 |
| `node node_modules/eslint/bin/eslint.js . --ext .js,.jsx,.ts,.tsx,.cjs,.mjs --max-warnings 0` | PASS, exit 0 |
| `node node_modules/vite/bin/vite.js build` | PASS, exit 0; existing Browserslist/chunk-size warnings retained |
| `bash scripts/validate-miniapp-dist.sh miniapp/dist` | PASS on final generated assets |

Frontend dependencies came from the unchanged lockfile. Offline install initially
lacked a cached package; the ordinary frozen install populated dependencies but
pnpm 11 reported an ignored esbuild build script. The pinned optional esbuild binary
was present and ran successfully; direct Node entrypoints above verified all checks
and the build. No package, lockfile, build approval setting or security gate changed.

Packaged JDBC browser acceptance used the test-only
`ClientOnboardingBrowserFixture` on loopback with H2 memory storage and synthetic
signed Telegram identities, not production Application/Telegram wiring.

| Browser command / cohort | Result |
|---|---|
| Playwright with `/private/tmp/clubs-layout-evidence/playwright-layout.config.cjs` | Existing Chromium onboarding/replay scenarios 2/2 PASS; the new layout test initially exposed an immediate `.count()` synchronization error |
| Same config, `--grep='cabinet layout'`, after the test-only picker correction | Chromium 1/1 + WebKit 1/1 PASS on final packaged source; actual image decode, table metadata, pointer drag with persisted coordinate comparison, native Back/unsaved guard, reopen, 390/320 px and revoke cleanup |
| Fresh memory fixture, `playwright-webkit.config.cjs --project=webkit --grep-invert='cabinet layout'` | Existing WebKit onboarding/replay scenarios 2/2 PASS |

The first attempt incorrectly shared one mutable fixture across browser projects;
the older onboarding scenarios assume one context, so subsequent WebKit assertions
failed on accumulated clients. Fresh fixture verification confirmed the cause.
The layout test now waits for fresh context and selects its exact organization;
it does not depend on timing or another test's state. No product behavior or
assertions were relaxed. Final browser CLI logs and JSON reports are under
`/private/tmp/clubs-layout-evidence/`; the first run's CLI log is retained, while
its overwritten JSON/context artifacts are not claimed as retained evidence.
Owned browser fixtures are stopped after verification; cleanup checks use loopback
port 54863, with no real Telegram or runtime database involved.

Server evidence completed before the final aggregate gate:

- Full `:core-data:test` with `runIT`, native disposable PostgreSQL 16 and existing
  Testcontainers: **474 tests, 0 failures/errors, 10 existing conditional skips**.
  New onboarding contract: **H2 69/69 and PostgreSQL 76/76 PASS**, no new skips.
  The ten skipped tests are the existing two native-support suites gated by
  `NativeSupportPostgres#requested`; their separate opt-in adapters were not enabled.
- Full `:app-bot:test` with `runIT` in the initial aggregate invocation: **1025 tests,
  0 failures/errors/skips**. That aggregate invocation later timed out; its overall
  result is not claimed as PASS. Completed module XML is retained separately.
- Final core IT invocation: `ORG_GRADLE_PROJECT_runIT=true
  CLB_CLIENT_TEST_PG_URL=jdbc:postgresql://127.0.0.1:54965/clb_client_test
  DOCKER_HOST=unix:///Users/maksimmartynov/.docker/run/docker.sock
  python3 -B /private/tmp/clubs-layout-evidence/run-core-it.py`.
  The local driver calls the unchanged repository supervisor with execution 280 s,
  cleanup 20 s, the same CPU/RSS/heap/ownership guards and task 250 s; test completion
  events provide progress without logging payloads. **Exit 0, 246.966 s, cleanup
  verified**, evidence `core-full-it-progress/result.json`. Repository gates and
  assertions remain unchanged.

Earlier local verification failures are retained as failures: the 180 s task budget
was too short for the full data suite; a silent-progress attempt hit the idle watchdog.
An interrupted run left extensions in a fixture schema, producing `gin_trgm_ops`
errors and exhausted test connections on reuse. The failed own run was stopped via
its supervisor and only the labelled tmpfs PostgreSQL container was recreated.
No runtime database or credentials were accessed or repaired. The final clean run
above completed its schema cleanup. Raw stdout has the supervisor's 2 MiB cap;
complete final module counts and test outcomes use retained JUnit XML, not inferred
log totals (`completed-module-it-results.json`, `core-data-it-xml`, `app-bot-it-xml`).

The full ordinary JVM run produced **1367 tests, 0 failures/errors, 1 existing
disabled `GuestFlowStartTest`**, retained in `full-unit-results.json` and
`full-unit-xml`. Its combined quality invocation correctly failed on detekt's new
test-only `LargeClass` finding. The migration scenario and two helpers moved
verbatim to `ClubLayoutMigrationContract`; all twelve new test definitions and
assertions were preserved (`core-test-refactor-manifest.json`). Affected H2 69/69,
PostgreSQL 76/76, detektTest and ktlintTestSourceSetCheck then passed, exit 0 in
167.429 s (`core-test-refactor-final`). No baseline/suppression changed.

Unsliced `ktlintCheck --offline --rerun-tasks --no-build-cache` passed all 54 tasks
in 34.314 s, cleanup verified (`ktlint-final-fresh`). Fresh reports avoid the stale
absolute paths retained by old cross-worktree cache entries. Final remaining quality
gate evidence is recorded below.

Final `detektGate coverageGate verifyResolvedProductionDependencyGraph
:app-bot:installDist :app-bot:checkLogsPolicy --offline --no-build-cache --continue`
through the normal bounded supervisor: **PASS, exit 0, 100.968 s, cleanup verified**
(`quality-final/result.json`). Ktlint is the separately completed fresh 54-task gate
above, with unchanged Kotlin bytes. The current test-results directories include
later filtered rechecks; the complete unit set is the retained `full-unit-xml`
plus the verbatim test-refactor manifest and affected vendor rechecks, not a sum
of the last contents of every working directory.

After the final terminal-saving fix: full frontend **257/257 PASS**, final affected
API/cabinet tests and TypeScript/ESLint PASS. Rebuilt assets passed the actual JDBC
layout scenario again: **Chromium 1/1 + WebKit 1/1**, 7.5 s
(`browser-final-build.log`, `browser-final-build-results.json`). Final dist,
resources and installed `app-bot.jar` contain identical five files; both V065
resources in installed `core-data.jar` match source (`packaging-final.json`).

Final source/history secret gate uses the unchanged repository wrapper:
`DOCKER_HOST=unix:///Users/maksimmartynov/.docker/run/docker.sock
python3 -B scripts/secret-scan.py --output /private/tmp/clubs-layout-evidence/secret-scan-final`.
Its actual verdict and candidate digest are retained in that directory; the final
handoff reports this generated result. Scanner configuration and historical
fingerprints are unchanged. Whitespace, new local links, immutable concept source,
review hash bindings and final candidate bytes are also recorded in the evidence root.

## Handoff boundary

Open through `/app?mode=cabinet`, select the permitted venue, then **«Залы и столы»**.
On an upgraded database V065 grants layout.write only to the eligible recorded
creator; revoked or ineligible grants remain closed. At `LOCAL_LAYOUT_READY`, all
work was uncommitted in the worktree above and no publication or real data had
been touched. The publication continuation above supersedes that local-only boundary.

Unverified: hosted CI, real Telegram/iPhone/Android behavior, Stage/prod deployment
and the ten pre-existing opt-in native-support adapter tests. Those limits do not
represent additional feature acceptance or a waiver of future CI/release gates.
The candidate is prepared for the separately authorized Draft PR publication.

## Changed files

50 paths; migrations are the two V065 files listed below. `D` assets are obsolete hashed build outputs.

- `A` [app-bot/src/main/kotlin/com/example/bot/routes/ClientLayoutRoutes.kt](../../app-bot/src/main/kotlin/com/example/bot/routes/ClientLayoutRoutes.kt)
- `M` [app-bot/src/main/kotlin/com/example/bot/routes/ClientOnboardingRoutes.kt](../../app-bot/src/main/kotlin/com/example/bot/routes/ClientOnboardingRoutes.kt)
- `A` [app-bot/src/test/kotlin/com/example/bot/routes/ClientLayoutRoutesDataTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientLayoutRoutesDataTest.kt)
- `M` [app-bot/src/test/kotlin/com/example/bot/routes/ClientOnboardingAuthTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientOnboardingAuthTest.kt)
- `M` [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientAccessOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientAccessOperations.kt)
- `M` [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientOnboardingRepository.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientOnboardingRepository.kt)
- `A` [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubLayoutOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubLayoutOperations.kt)
- `A` [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubLayoutPlans.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubLayoutPlans.kt)
- `A` [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubLayoutTables.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubLayoutTables.kt)
- `A` [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubLayoutViews.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubLayoutViews.kt)
- `M` [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubOnboardingOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubOnboardingOperations.kt)
- `M` [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/DirectClubOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/DirectClubOperations.kt)
- `M` [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/JdbcClientOnboardingRepository.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/JdbcClientOnboardingRepository.kt)
- `M` [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/OnboardingValues.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/OnboardingValues.kt)
- `A` [core-data/src/main/resources/db/migration/h2/V065__client_club_layout.sql](../../core-data/src/main/resources/db/migration/h2/V065__client_club_layout.sql)
- `A` [core-data/src/main/resources/db/migration/postgresql/V065__client_club_layout.sql](../../core-data/src/main/resources/db/migration/postgresql/V065__client_club_layout.sql)
- `M` [core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClientOnboardingPostgresIT.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClientOnboardingPostgresIT.kt)
- `A` [core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClubLayoutContract.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClubLayoutContract.kt)
- `A` [core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClubLayoutMigrationContract.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClubLayoutMigrationContract.kt)
- `M` [core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClubOnboardingContract.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClubOnboardingContract.kt)
- `M` [core-data/src/test/kotlin/com/example/bot/data/client/onboarding/OnboardingContract.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/onboarding/OnboardingContract.kt)
- `M` [core-data/src/test/kotlin/com/example/bot/data/client/onboarding/OnboardingFixture.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/onboarding/OnboardingFixture.kt)
- `M` [docs/ops/PROJECT_STATUS.md](../../docs/ops/PROJECT_STATUS.md)
- `A` [docs/ops/client-club-layout.md](../../docs/ops/client-club-layout.md)
- `M` [docs/product/AS_BUILT.md](../../docs/product/AS_BUILT.md)
- `M` [docs/product/CONCEPT_CODE_GAP.md](../../docs/product/CONCEPT_CODE_GAP.md)
- `M` [docs/product/PRODUCT_SPEC.md](../../docs/product/PRODUCT_SPEC.md)
- `M` [docs/security.md](../../docs/security.md)
- `A` [miniapp/dist/assets/PlatformOwnerScreens-C4VCJhbn.js](../../miniapp/dist/assets/PlatformOwnerScreens-C4VCJhbn.js)
- `D` `miniapp/dist/assets/PlatformOwnerScreens-Czwbuy26.js`
- `D` `miniapp/dist/assets/index-7saVrHJ9.js`
- `D` `miniapp/dist/assets/index-BYV9Hj-z.css`
- `A` [miniapp/dist/assets/index-DYXi0nkw.css](../../miniapp/dist/assets/index-DYXi0nkw.css)
- `A` [miniapp/dist/assets/index-rpvY16o6.js](../../miniapp/dist/assets/index-rpvY16o6.js)
- `M` [miniapp/dist/index.html](../../miniapp/dist/index.html)
- `M` [miniapp/src/modules/admin/pages/ClubHallsScreen.tsx](../../miniapp/src/modules/admin/pages/ClubHallsScreen.tsx)
- `M` [miniapp/src/modules/admin/pages/HallEditorScreen.tsx](../../miniapp/src/modules/admin/pages/HallEditorScreen.tsx)
- `M` [miniapp/src/modules/admin/pages/HallPlanStage.tsx](../../miniapp/src/modules/admin/pages/HallPlanStage.tsx)
- `M` [miniapp/src/modules/admin/pages/__tests__/hallEditor.test.tsx](../../miniapp/src/modules/admin/pages/__tests__/hallEditor.test.tsx)
- `A` [miniapp/src/modules/admin/pages/layoutEditor.types.ts](../../miniapp/src/modules/admin/pages/layoutEditor.types.ts)
- `M` [miniapp/src/modules/admin/utils/adminErrors.ts](../../miniapp/src/modules/admin/utils/adminErrors.ts)
- `M` [miniapp/src/modules/client/ClientAccess.tsx](../../miniapp/src/modules/client/ClientAccess.tsx)
- `A` [miniapp/src/modules/client/ClubLayout.tsx](../../miniapp/src/modules/client/ClubLayout.tsx)
- `M` [miniapp/src/modules/client/ClubProfile.tsx](../../miniapp/src/modules/client/ClubProfile.tsx)
- `A` [miniapp/src/modules/client/layout.api.test.ts](../../miniapp/src/modules/client/layout.api.test.ts)
- `A` [miniapp/src/modules/client/layout.api.ts](../../miniapp/src/modules/client/layout.api.ts)
- `A` [miniapp/src/modules/client/layout.test.tsx](../../miniapp/src/modules/client/layout.test.tsx)
- `M` [miniapp/src/modules/client/onboarding.css](../../miniapp/src/modules/client/onboarding.css)
- `M` [miniapp/tests/onboarding-integrated.spec.ts](../../miniapp/tests/onboarding-integrated.spec.ts)
- `M` [miniapp/tests/serve-onboarding-dist.mjs](../../miniapp/tests/serve-onboarding-dist.mjs)
