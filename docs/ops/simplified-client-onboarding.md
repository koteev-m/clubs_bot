# Local simplified client onboarding — 2026-10-29

## Release-cycle preparation — 2026-10-29

The owner authorized this bounded release cycle: local corrections, ordinary release
branch pushes, one PR/merge, normal GHCR publication and one existing workflow dispatch
to stage. The branch is `codex/simplified-onboarding-release-20261029`, based on fresh
`origin/main c0df96b520e4e1f6bfe97a27ce7b17546d694505`; no additional worktree was created.
Production, direct SSH/server operations, credential changes, force/history rewriting
and unknown-state recovery remain excluded. The separate Stage improvements are excluded.

Three independent read-only reviews covered authority/quota/V064, Gradle/Gitleaks and
multipart/frontend. One P2 was reproduced and fixed: direct-to-legacy duplicate checking
must use immutable creation fingerprints and city ID, rather than editable `clubs.timezone`.
The existing shared regression now edits timezone before legacy creation and requires
`duplicate_application` without another mapping. Independent re-review closed the finding;
V064 and existing migrations did not change.

Focused commands through `scripts/run-bounded-gradle.py` (300-second outer budget,
150-second idle limit, 1024 MiB test heap) ran from this worktree. H2 onboarding 57/57,
PostgreSQL 16 onboarding 62/62 and affected core-data detekt/ktlint PASS. The initial
class filter selected no tests and was corrected; the next pre-fix run failed as expected.
The runIT CLI property was refused before process launch; the supported Gradle project
property environment was used for the real PostgreSQL run. Both final runs verified
owned cleanup (74.851 s / 62.347 s). The disposable PostgreSQL was local loopback/tmpfs,
not a project or Stage database. Evidence: `/private/tmp/clb-release-evidence/`.

The reuse audit binds all unchanged implementation bytes to the retained final candidate
digest `ed52ab8247141eb4509656b489e247cc02b910edd946586297a972de7408b8ee` after accounting
for these two release fixes. Prior broad checks are retained; full Linux CI remains required.
Gradle's original GC retaining cause remains unconfirmed. Secret scanning must additionally
cover the final committed tree/history before the ordinary push.

Owner confirmation received directly: 2026-10-29 around 18:30 MSK, @NightConcierge_Bot
(7696193761) sent a new `/start` reply and opened Mini App after old-token revocations.
No tokens were accessed/changed. This confirms current bot operation, not real onboarding,
invitation or second-account acceptance. No Stage deployment occurred during preparation.
Live GitHub rules require PR plus `lint` and `release-state`; all applicable CI gates
are still required by this release. Stage retains owner approval and a five-minute timer.

**Implementation continuation, 2026-10-29:** the three outcomes remain independent:
onboarding **`ONBOARDING_PRESERVED`** (earlier local functional acceptance retained);
Gradle **`GRADLE_PROTECTION_VERIFIED_CAUSE_UNCONFIRMED`**, cleanup
**`OWNED_EXPERIMENTS_CLEAN`**; Gitleaks **`SECURITY_GATE_READY`**, historical credential
**`OWNER_ATTESTED_REVOKED`**. These are local results, not deployment approval.
The earlier diagnostic and failed executions below are retained as history, not current
claims that no implementation was applied.

**Earlier implementation verdict: BLOCKED — repository history secret-scan gate.** The requested functional
path is implemented and passes local acceptance. The correctly mounted full-history
gitleaks scan exits nonzero with four findings in pre-existing commits. Current source
scan passes. No suppression, history rewrite, credential change or publication was
performed; this is not `LOCAL_SIMPLIFIED_ONBOARDING_READY` with a silently waived gate.

## Identity, scope and permissions

- Repository `koteev-m/clubs_bot`; fresh fetched `origin/main` and isolated worktree
  HEAD/base `c0df96b520e4e1f6bfe97a27ce7b17546d694505`, also the last deployed revision
  supplied by the user. This does not independently verify current Stage runtime.
- Worktree `/Users/maksimmartynov/.codex/worktrees/simplified-onboarding/clubs_bot`,
  detached HEAD, all edits uncommitted. Identity guard passes; no active foreign
  instructions. `CLB-simplified-onboarding` is a descriptive guard label, not a new card.
- The primary checkout and the existing `client-invitation-fix` worktree were not
  edited. Its twelve dirty Stage deployment/template/preflight/test/doc files remain
  there and are absent from this task's implementation diff.
- No commit, push, PR, merge, dispatch, deploy, SSH, Stage/prod data, real Telegram API,
  BotFather, Caddy or global configuration change. Managed Bots remain out of scope.
  Tests use only synthetic H2, task-owned loopback PostgreSQL 16 and disposable
  Testcontainers resources. Browser fixture does not run production Application wiring.

## User path and accepted change

The owner issues a link with **«Разрешено заведений» = 1** by default. The recipient
opens the Mini App and presses **«Принять приглашение»** once. The new empty client
cabinet opens without an account checkbox, comparison code or owner reconfirmation.
One client context opens directly; multiple contexts retain explicit selection.

The client enters venue name, country by ordinary name, selected city/region and one
full address, then presses **«Создать заведение»**. Success opens the saved private
venue card. Loading and errors are beside the action; failed submissions retain input.
Back/Forward and same-credential foreground revalidation retain the pending command
in memory. A changed account or lost access clears private state; stale/aborted
responses cannot restore it. Main Mini App checks server authority before choosing
platform/client/staff/guest. URL role/mode does not authorize anything.

The first venue consumes the single place. A second is rejected until the exact
platform owner changes the client's total allowance to 2. Owner cards show total,
created and remaining. Client/staff API calls cannot increase the allowance.
The venue remains private: no bot connection, booking activation or publication.

`DEC-041` retains its history with an explicit owner-authorized AMEND dated 2026-10-29.
[PRODUCT_SPEC §29.10](../product/PRODUCT_SPEC.md#simplified-client-onboarding) overrides
the old mandatory application/approval and code exchange. CLNT-003–006/010, traceability,
roadmap, security and local AS_BUILT evidence agree. CONCEPT_SOURCE is unchanged.

## Persistence, quota and replay

New paired PostgreSQL/H2 **V064** adds nullable `clients.allowed_clubs`, optimistic
`limit_version`, per-invitation acceptance policy and durable direct venue profiles.
V001–V063 are unchanged. Existing clients get **no automatic allowance**: null blocks
new creation only, while authorized existing venues remain readable/usable.

The client row lock serializes every direct/legacy creation and owner limit change.
Usage counts every `client_clubs` mapping, including private/inactive venues. Lowering
below usage fails without disabling/deleting existing venues; archiving does not free
places. Club, ownership mapping, exact grants, command result and audit commit together.
Limit changes require exact configured owner plus version and transactional audit.

Idempotency keys and payload hashes recover committed-but-lost replies under current
authority. A normalized venue/address/location fingerprint prevents exact duplicates
even with a new key; stable GeoNames ID and timezone distinguish same-named cities.
Linked historical applications can reopen the already created venue. An unlinked
ambiguous legacy match returns `duplicate_application` rather than guessing identity.
FK/immutable-profile triggers protect the durable direct mapping. The old approved
application create endpoint uses the same limit; a client with a legacy GLOBAL role
cannot bypass it through admin club creation. No GLOBAL/platform-owner grant is issued.

Invitation binding is one recipient under the client lock, signed initData, explicit
POST, seven-day expiry, revoke/reissue and hash-only secret storage. Automatic activation
is restricted to a new empty cabinet without prior members, clubs or applications.
**The actual recipient of a forwarded link may accept it. Revocation cannot undo prior
actions or disclosure.** No invitation secret/initData is put in persistent browser
storage or logs.

Existing clients, clubs, applications, approvals, unit/address/comments and audit remain.
Old pending invitations keep owner-confirmation policy, including on reissue; no migration
accepts them. Saved applications remain history and can prefill the new form, combining
address/unit without deleting the source fields. Direct creation does not fabricate approval.

## Location source and Telegram entry

No existing city-to-zone resolver was found; the previous source used manual IANA input
and legacy Moscow defaults. The new offline snapshot derives from public
[GeoNames cities15000/admin1CodesASCII](https://download.geonames.org/export/dump/),
downloaded 2026-10-29, CC BY 4.0. It contains 34,193 records with pinned source/output
hashes and a deterministic importer. Server labels countries through JDK Russian locale,
searches aliases and resolves selected city ID to region/timezone. It rejects overrides,
unknown IDs and mismatched countries. No device timezone, default guess, paid service,
runtime external geocoder or address transfer is introduced.

Coverage is limited to cities above 15,000 inhabitants and capitals; names/regions can
be Latin-script. Ambiguous results show region choices; absent cities require refinement
and cannot be silently created with a guessed timezone. See the packaged
[source README](../../app-bot/src/main/resources/locations/README.md).

Installed pengrad SDK 9.2.0 supports `SetChatMenuButton`/`MenuButtonWebApp`. Code prepares
the default **«Открыть кабинет»** menu at startup and per-chat menu on `/start`; only
fake request transport was exercised. Clients/owner get one cabinet button on `/start`,
other guest entry remains. Direct `startapp=ci_<secret>` wins over ordinary entry.
Main Mini App configuration in BotFather is untouched.

## Actual verification

Evidence directory: `/private/tmp/clb-simplified-evidence/`. Commands ran from the
isolated worktree (frontend commands from `miniapp`). No checks/baselines were disabled.
The local-only Gradle init script sets Test heap to 1536 MiB and one fork; default
512 MiB exhausted memory during an earlier full run. Production heap/policies are unchanged.

| Command / check | Actual result |
|---|---|
| `python3 -I -S -B scripts/project-identity-guard.py --task-id CLB-simplified-onboarding --expected-head c0df96b520e4e1f6bfe97a27ce7b17546d694505` | PASS |
| `./gradlew -I /private/tmp/clb-simplified-evidence/local-test-memory.gradle test --no-configuration-cache --max-workers=2 --console=plain` (full run evidence) | 1,331 PASS, 1 existing disabled; intermediate detekt test-size finding fixed separately |
| `CLB_CLIENT_TEST_PG_URL=jdbc:postgresql://127.0.0.1:54964/clb_client_test DOCKER_HOST=unix:///Users/maksimmartynov/.docker/run/docker.sock ./gradlew -I /private/tmp/clb-simplified-evidence/local-test-memory.gradle test -PrunIT=true detektGate coverageGate verifyResolvedProductionDependencyGraph :app-bot:installDist --continue --no-configuration-cache --max-workers=2 --console=plain` | BUILD SUCCESSFUL; 1,563 PASS, 11 skips: 10 opt-in native PostgreSQL adapters, 1 existing disabled GuestFlowStartTest; required Testcontainers/PostgreSQL ran |
| Onboarding shared contract inside that run | H2 57/57, PostgreSQL 62/62 PASS; includes direct acceptance, two recipients, expiry/revoke/reissue, upgrade preservation, owner limit authorization, common quota, races/replays, rollback audit and legacy bypass |
| `./gradlew ktlintCheck --rerun-tasks --continue --no-build-cache --no-configuration-cache --max-workers=2 --console=plain` | PASS; forced rerun avoids stale absolute paths from build cache |
| `CLB_CLIENT_TEST_PG_URL=jdbc:postgresql://127.0.0.1:54964/clb_client_test DOCKER_HOST=unix:///Users/maksimmartynov/.docker/run/docker.sock ./gradlew -I /private/tmp/clb-simplified-evidence/local-test-memory.gradle :app-bot:checkLogsPolicy -PrunIT=true --no-configuration-cache --max-workers=2 --console=plain` | PASS, no sensitive logging matches; dependent app tests pass |
| `node node_modules/vitest/vitest.mjs run` | Full 222/222 PASS; after final revoked-main fix affected CabinetEntry suite 10/10 PASS (one added regression) |
| `node node_modules/typescript/bin/tsc --noEmit`; ESLint full project, then final changed files | PASS |
| `node node_modules/vite/bin/vite.js build`; `./gradlew :app-bot:installDist --no-configuration-cache --max-workers=2 --console=plain` | PASS on final assets; Vite retains nonblocking bundle-size/Browserslist warnings |
| `node node_modules/@playwright/test/cli.js test --config=playwright.onboarding.config.ts` | Final packaged mobile Chromium 9/9 PASS |
| Same command with `CLB_ONBOARDING_BASE_URL=http://127.0.0.1:54863` | Final real Ktor/JDBC packaged browser 2/2 PASS on clean synthetic H2 |
| `bash scripts/validate-miniapp-dist.sh miniapp/dist`; `python3 -m unittest scripts.tests.test_validate_miniapp_dist` | PASS; validator tests 37/37 |
| GeoNames importer rerun from pinned inputs | Exact output hash reproduced, 34,193 rows |

The JDBC browser journey covers one-action acceptance, city/address selection, lost
committed creation response, Back/Forward/foreground, same-key retry, saved private card,
reentry, second-create refusal, owner increase, second create, foreign isolation and
revocation clearing. The other journey covers lost invitation response, explicit reissue,
old-link refusal and acceptance. No Stage HTTP status was inferred.

The old submit flicker had two local candidates: error rendered below a long form and
component-local pending command lost on refresh/remount. The removed main flow was not
expanded into a separate repair. The new path is verified independently.

Independent frontend/backend/entry reviews found and closed city ambiguity, pending
state isolation, stale-account response, navigation and cancellation-test issues.
The final revoked-main regression also received independent read-only review, no
remaining actionable findings. All production changes have their affected checks.

## Gradle incident: facts, uncertainty and proposed correction

### Evidence and attribution

New correct repository-only identity preflight passed at unchanged detached HEAD
`c0df96b520e4e1f6bfe97a27ce7b17546d694505`; active CLB instructions, foreign = 0.
The earlier STOP resulted from incorrectly passing external data as instruction scope.
No guard override/repair was made. External evidence is read as data, not instructions
or executable scripts. All 68 candidate paths matched `final-files.json` at entry.
The base SHA alone does not identify the uncommitted candidate used in the incident.

Owner's dump `/Users/maksimmartynov/Desktop/gradle-worker-84187.txt` is 18,928 bytes;
SHA-256 **163b4476e089ad0ddaf19a1a07dafcac182a10ebc79335f0662dfa78c97bcfea** matches
exactly. It records Corretto/OpenJDK 21.0.2+13-LTS at **12:57:14 MSK**, after the owner's
SIGTERM. No new signal or attach command was sent to PID 84187 or 83051.

| Evidence | Supported conclusion / boundary |
|---|---|
| Dump lines 13–34 | Test worker is TIMED_WAITING in runBlocking → runTest → Ktor testApplication → AdminMusicRoutesTest.withApp:467 → oversized-cover test:161. This does not locate the suspended coroutine's internal instruction. |
| Ten GC threads, dump lines 237–255 | 27,118.08194 CPU-seconds accumulated over JVM elapsed 3,542.79 s (average 7.65 CPU equivalents); G1 thread names confirm G1. High GC CPU is evidenced, not the retaining object graph or a proven leak. |
| JVM elapsed + dump wall time | Approximate JVM start **11:58:11.21**, not start/duration of this individual test. |
| `daemon-83051.out.log:2538`, related worktree cwd | Build `90cfb542-350d-4b9b-a4bb-aad79e5b6260` began **11:57:39.004**. Reported parent/cwd, approximate JVM start, test stack and matching OOM stream strongly associate this worker with the early `full-jvm-static.log` run. No retained direct PID→exec argv record proves that association alone. |
| Worker classpath argfile `gradle-worker-classpath7237446413001414087txt` | Timestamp **11:58:11.355**, app-bot test/main output and this worktree's module JARs. Consistent with the worker start; filename is not a PID mapping. It contains classpath, not complete JVM flags. |
| `full-jvm-static.log:16612–16642`; daemon lines 18899–18929 | OutOfMemoryError from uncaught handlers on multiple DefaultDispatcher threads and DefaultExecutor. Last preceding timestamped application lines are around **11:59:20**, followed by music tests. Exact OOM allocation site/time is absent. |
| Daemon lines 18930, 18999, 19027–19044 | Client disconnection, cancellation of app-bot:test, BUILD FAILED in 3m30s; daemon marks build canceled/idle at **12:01:09.399**, then cannot write the result to the disconnected client. Client PID/termination mechanism and exact worker termination attempt are not recorded. |
| Later daemon build entries | New work began **12:01:38**, **12:01:47**, **12:03:22**, **12:08:17** and later, before the owner's worker dump/kill. Later checks therefore overlapped the lifetime of the leftover worker, even though they had their own successful results. |

The temporary `local-test-memory.gradle` sets **1536m / maxParallelForks=1**; its saved
mtime is **12:01:38.089**, after this worker's inferred start. Together with the prior
execution record this supports attribution to the early pre-memory-override run, not
to the later successful 1536m run. It does **not** reconstruct the worker argv.
512 MiB is the recorded/default Test heap assumption, not independently measured Xmx
for PID 84187. **Actual Xmx, Xms and complete GC flags remain unknown.** Daemon log
`-Xmx2g` belongs to the daemon, not the Test worker. G1 is directly evidenced; no GC log,
heap occupancy history, VM.flags or heap-retention snapshot for that worker was retained.

Current source inputs and the near-time resolved classpath agree on **Ktor 3.3.1,
coroutines 1.10.2, JUnit 4.13.2, Vintage/Jupiter 5.13.4, Platform 1.13.4, MockK 1.13.10**;
wrapper/daemon are Gradle 9.2.0. AdminMusicRoutesTest, AdminMusicRoutes and JsonErrorPages
are unchanged from HEAD. However, compiled worktree outputs/JARs were subsequently
rebuilt, and onboarding/location code was still evolving during the early run. There
is no immutable complete manifest of all **loaded** bytes at 11:58. Final candidate
hashes and later test XML cannot reconstruct that earlier in-memory application.

Bounded process metadata at **13:19 MSK** showed Java PID 83051 (start 11:53:51, PPID 1)
and PID 83229 (start 11:54:25), both near 0% CPU. This is observation, not authority to
stop them or a declaration that every Java process is owned by this task. PPID 1 for
a Gradle daemon alone is normal lifecycle evidence, not proof of a malfunction.

### Three separate conclusions

**A — intensive GC:** confirmed GC CPU expenditure plus OOM in the correlated early
run narrows the immediate failure to memory exhaustion/GC pressure. Whether caused
by accumulated suite state, retained mocks/application objects, transient allocations,
or the effective heap budget is **not established**. A 5 MiB+1 array alone is not an
explanation. This test creates fresh fake repositories and a relaxed MockK audit
repository; fake asset storage can materialize bytes on successful uploads. No retained
heap or allocation profile establishes any of these as the memory owner.

**B — test not finishing:** the dump confirms a test still awaiting completion. Ktor's
explicit real-time coroutine timeout is not a process deadline. OOM on dispatcher and
DefaultExecutor can prevent timely cancellation/cleanup, but this snapshot cannot
prove that mechanism, a deadlock, or a particular upstream defect. No DefaultDispatcher
threads are shown in the post-TERM dump; that must not be treated as a pre-TERM state.

**C — leftover process:** the build/client ended while a worker consistent with that
run remained alive. The earlier workflow lacked an independent owner-aware process
deadline and verified worker teardown. Exact Gradle/OS cancellation mechanics and why
TERM did not finish within the owner's five-second observation are unknown. Gradle's
`TestWorker.stop()` stack is ordinary JUnit Platform execution dispatch, **not** proof
that this frame is a JVM shutdown hook. The post-TERM dump cannot prove TERM was ignored.

Prior **PASS** refers to completed later executions: the same oversized test passed in
`full-jvm-verified.log:16531`, `integration-gates.log:16952`, and
`logs-policy-verified.log:17298`. The full-jvm-verified build itself still had a detekt
failure subsequently fixed; integration-gates and logs-policy-verified finished successfully.
None of those results proved the absence of leftover JVMs. The earlier handoff's cleanup
statement covered its disposable browser/Postgres resources, not all Test workers.

### Multipart, cancellation and timeout review

The cover limit is 5 MiB; whole-request allowance includes another 256 KiB for multipart
overhead. Ktor's ByteArray form supplies a known part length, and MultiPartFormDataContent
computes total Content-Length. Thus the 5 MiB+1 case should reach the **file** limit;
the larger request-size sibling covers the header-level rejection. Unknown-length bodies
need streamed request enforcement; Content-Length is not a substitute for actual reads.

`readLimitedWithShaToTempFile:605–635` reads 8192-byte chunks, throws before writing
a chunk that exceeds the file limit, closes the output and deletes the temp on failure.
It rethrows cancellation and other Throwable. `receiveUpload:525–595` maps over-limit
to 413, disposes the part and continues consuming subsequent parts. In Ktor 3.3.1
FileItem disposal releases/drains remaining part data; it is not equivalent to canceling
the whole producer/request. There is no explicit request-channel cancellation here.
An already accepted temp file can also require cleanup if a later multipart operation
fails before the outer upload result transfers ownership. These lifecycle edges warrant
targeted regressions; their responsibility for this incident is unproven.

Two concrete risks in unchanged source are distinct from an established root cause:

- `receiveUpload` uses `runCatching` and maps every non-cancellation/non-limit Throwable,
  including OutOfMemoryError, to ordinary 500. The helper rethrows it, but the caller
  masks it. JsonErrorPages also handles Throwable except cancellation. Fatal VM errors
  should not enter normal logging/HTTP recovery; no captured OOM stack proves this branch ran.
- `withApp` uses JUnit4 `@Test` without timeout; teardown resets the auth validator.
  Ktor 3.3.1 `runTestApplication` calls block → start → stop without a finally around
  that sequence. The test's exceptional path may not reach normal stop. Its
  `runTestWithRealTime` explicitly supplies 60 seconds; changing coroutines'
  `default_timeout` does not override that explicit argument. Coroutine cancellation
  needs a runnable scheduler and cooperation; Jupiter-only timeout settings do not
  govern this Vintage test. No repository Test task timeout was found.

Version-specific official source references (read, not executed):
[Ktor TestApplication](https://github.com/ktorio/ktor/blob/3.3.1/ktor-server/ktor-server-test-host/common/src/io/ktor/server/testing/TestApplication.kt),
[Ktor TestCommon](https://github.com/ktorio/ktor/blob/3.3.1/ktor-test-dispatcher/common/src/TestCommon.kt),
[coroutines TestBuilders](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-test/common/src/TestBuilders.kt),
[Gradle TestWorker](https://github.com/gradle/gradle/blob/v9.2.0/platforms/software/testing-base-infrastructure/src/main/java/org/gradle/api/internal/tasks/testing/worker/TestWorker.java).
Multipart source:
[formDsl](https://github.com/ktorio/ktor/blob/3.3.1/ktor-client/ktor-client-core/common/src/io/ktor/client/request/forms/formDsl.kt),
[FormDataContent](https://github.com/ktorio/ktor/blob/3.3.1/ktor-client/ktor-client-core/common/src/io/ktor/client/request/forms/FormDataContent.kt),
[RequestBodyLimit](https://github.com/ktorio/ktor/blob/3.3.1/ktor-server/ktor-server-plugins/ktor-server-body-limit/common/src/io/ktor/server/plugins/bodylimit/RequestBodyLimit.kt),
[CIOMultipartDataBase](https://github.com/ktorio/ktor/blob/3.3.1/ktor-http/ktor-http-cio/common/src/io/ktor/http/cio/CIOMultipartDataBase.kt),
[Multipart](https://github.com/ktorio/ktor/blob/3.3.1/ktor-http/ktor-http-cio/common/src/io/ktor/http/cio/Multipart.kt).
No dependency upgrade is proposed on an unverified bug resemblance.

### Minimal next implementation scope — proposal only

1. Keep memory root cause open. On a separately approved bounded investigation, record
   exact candidate/classpath hashes, VM.flags/heap info and capped GC logs before testing.
   A single passing test cannot clear a cumulative suite defect; 512/1536 MiB are
   experimental factors, not an accepted permanent heap increase.
2. Make upload temp/channel ownership explicit across success, size rejection, later-part
   failure and cancellation. Preserve 413/envelope and rethrow CancellationException;
   avoid converting fatal VM errors into normal 500. Verify Ktor-supported cancellation
   versus bounded draining before choosing the producer/consumer termination change.
3. Add focused regressions for known-length file-limit and whole-request-limit 413,
   unknown-length streaming overflow, interrupted producer/client cancellation and a
   malformed later part. Assert completed client/testApplication, deleted owned temps,
   zero asset writes and unchanged item references. The existing oversized test checks
   status/text only; the existing cancellation test throws from channelProvider before
   actual streaming. Neither proves these lifecycle properties.
4. Add an explicit Vintage-compatible test deadline plus bounded application cleanup;
   JUnit4 timeout interrupts are only an inner guard, not a process-kill guarantee.
   A Gradle Test task deadline is another layer, not the final cleanup authority.
5. Use a small task-local external supervisor for this invocation, not a general process
   platform. Before any reproduction, validate its teardown with a lightweight own
   process. Use an isolated single-use daemon/registry in temporary storage without
   changing shared Gradle configuration, one Test fork, one worker, no parallel and an
   absolute invocation deadline ≤300 s. `--no-daemon` alone is insufficient.
6. Register each created JVM by PID **and start identity**, retained ancestry/session
   ownership plus a per-run marker before parent exit; do not rediscover ownership from
   current PPID alone. On stalled progress/memory pressure/deadline/client exit, collect
   bounded thread/VM flags/heap info (every subprocess also timed), TERM owned JVMs,
   wait a bounded interval, then KILL only still-matching owned identities. Reconcile
   workers at success/cancel/failure and return failure if cleanup is uncertain.
   No heap dump, heavy histogram, killall, shared-daemon stop or cache clearing.

On macOS the supervisor needs verified process start metadata and explicit descendant
tracking; process-group membership alone is insufficient after daemonization. On CI
Linux, a dedicated job/container/cgroup can additionally bound descendants/resources,
with job timeout as an outer layer. ActiveProcessorCount/GC-thread controls may reduce
parallelism during an experiment but are not hard CPU caps. Neither approach guarantees
cleanup after SIGKILL of the entire controller chain or OS shutdown; failure leaves an
explicit unconfirmed-cleanup result and requires identity-safe recovery.

**Experiments: NOT_RUN, 0/2 targeted invocations used.** No Gradle/Java/Docker/browser
test or diagnostic attach was launched; no PID was signaled. Existing evidence is
sufficient for `CAUSE_NARROWED`, not `CAUSE_CONFIRMED`. Recreating the cumulative OOM
would add load without identifying the missing retention graph. Evidence hashes and
attribution limits are in `/private/tmp/clb-simplified-evidence/gradle-incident-evidence.json`.

## Gradle remediation implementation and bounded verification

The implementation closes demonstrated error-handling/lifecycle defects and adds
independent worker protection. It does **not** establish or eliminate the retaining
object graph that caused the original intensive GC/OOM. No permanent heap/dependency
change or full-suite rerun is used as an alleged fix.

`MusicUpload.kt` now owns the current multipart part/channel and accepted temporary
file until the entire body completes. Rejection/cancellation cancels the request and
part producer before disposal; late multipart failures delete an accepted temp. Parts
are disposed once. Cleanup retains primary fatal/cancellation errors and avoids
self-suppression; fatal cleanup errors are not downgraded. Both asset-write callers
use the same cleanup policy. Streaming whole-request `RequestTooLargeException`
reaches the existing 413 handler instead of becoming 500. JsonErrorPages handles
ordinary `Exception`, rethrows cancellation and does not mask JVM `Error`.

Regression coverage includes below/above the 5 MiB file limit, known/unknown-length
413, unknown-length aggregate multipart limit, zero asset writes/references on refusal,
temporary-file cleanup, cancellation during streaming, a live producer, late part
failure, fatal provider/dispose/HTTP-handler failures and cleanup failure precedence.
The test application's body/stop are explicitly bracketed: 20-second body and 5-second
cleanup inside the 30-second JUnit4/Vintage rule; smaller helper/error tests have their
own bounds. These cooperative/interrupt deadlines are not JVM termination guarantees.

`scripts/run-bounded-gradle.py` is a foreground, per-invocation supervisor for macOS
and Linux, with no service/global Gradle configuration. It uses a private daemon
registry, unique JVM marker, single-use daemon, one worker/fork, in-process Kotlin
compiler and temporary controlled Test heap. A launcher handshake establishes birth
identity before it can fork. Test task timeout is 180 seconds; the independent overall
budget is at most 300 seconds including 20 seconds reserved for reconciliation.
No-output, aggregate owned RSS and sustained CPU pressure also stop the invocation.
ActiveProcessorCount=2 is a safety heuristic, **not** a hard CPU quota.

Ownership uses a live verified parent/group or exact invocation marker. Historical
PGIDs alone never authorize adoption. PID reuse, missing identity and inspection
failure fail closed. Birth records survive reparenting; vanished/refused identities
cannot be revived. macOS uses `proc_pidinfo(PROC_PIDTBSDINFO)` seconds+microseconds,
binding private argv between two matching birth reads; Linux binds `/proc` cmdline
between start-tick reads and uses pidfd for signaling when supported. Raw process
arguments/environments are not reported. Existing JVMs are excluded before launch.

On failure/cancellation/timeout, the supervisor captures bounded numeric heap info
only from confirmed own JVMs, then TERM, bounded wait, KILL and final reconciliation
for those identities. Each diagnostic subprocess has a timeout. Logs are capped at
2 MiB plus 128 KiB diagnostic output. Timeout/pressure returns 124, cancellation 130,
uncertain cleanup 125, ordinary failure/leftovers nonzero; none is converted to PASS.
A nominally successful command with leftover live children is a failure even if cleanup
then succeeds. No global kill, shared-daemon stop or cache clearing occurs.

**Protection boundaries:** macOS has a narrow last-recheck-to-kill race without a
pidfd equivalent; this is not a kernel-atomic Linux guarantee. Arbitrary unmarked
children that detach and reparent before ever being observed are unsupported. Marked
Gradle/Test JVMs and observed descendants are the supported scope. The controller must
remain alive through reconciliation: SIGKILL of the control chain or OS failure cannot
guarantee cleanup. CI Linux should additionally retain its job/container timeout.
This does not replace CI's broad required test/static/security gates.

Independent review found and closed permanent-PGID trust, coarse macOS start identity,
fast-exit argv races, CLI isolation-option bypass, cleanup error precedence and streamed
aggregate-limit handling. Lightweight controller regressions: **23/23 PASS**, exit 0,
31.537 seconds (`supervisor-regressions-final.log`), covering success, command failure,
timeout, TERM→KILL, cancellation with an unrelated process kept alive, reparented marked
and observed unmarked children, capped logs, PID/PGID reuse and fail-closed snapshots.
No test signals any pre-existing Java process. Previous failing lightweight iterations
are retained as evidence and do not count as PASS.

Before the first protected Gradle run, only existing Java PID 83051 was observed, idle
at 0% CPU/~58 MiB RSS; no ambient JVM option overrides. macOS memory_pressure reported
54% system-wide free memory (swap use alone was not treated as active pressure).
The existing daemon is outside the invocation ownership set and is never stopped.
Focused Gradle execution results and final source preservation are recorded below.

### Actual remediation checks and limits

Every invocation below was sequential and supervised: no parallel Gradle/scanner tests,
one worker/fork, temporary 512 MiB Test heap, daemon heap 1024 MiB, private daemon registry,
`--no-daemon --no-parallel --max-workers=1 --no-configuration-cache --no-build-cache`,
ActiveProcessorCount=2 and total wall budget ≤300 seconds. The formatter JVM alone used
256 MiB. Full command arrays/flags and outcomes remain in the named evidence directories.

| Invocation / command | Actual result; cleanup |
|---|---|
| `python3 -I -B scripts/tests/test_run_bounded_gradle.py` | 23 PASS, exit 0; real lightweight own-process lifecycle plus synthetic PID/PGID snapshots. |
| `python3 -B scripts/run-bounded-gradle.py --timeout 300 --idle-timeout 120 --heap-mib 512 --output /private/tmp/clb-simplified-evidence/gradle-protection-run1 -- :app-bot:test --tests com.example.bot.routes.AdminMusicRoutesTest --tests com.example.bot.routes.MusicUploadLifecycleTest --tests com.example.bot.plugins.JsonErrorPagesTest --tests com.example.bot.http.JsonErrorFormatTest :app-bot:detektMain :app-bot:detektTest :app-bot:ktlintCheck --no-build-cache --continue` | Exit 1, 85.857 s, **0 tests executed**: test compilation and static issues; own JVM cleanup verified. Fixed typed multipart append and detekt structure without suppressions. |
| Temporary six-file formatter via the same `supervise()` controller; `:app-bot:ktlintFormatCli :app-bot:compileTestKotlin :app-bot:detektMain :app-bot:detektTest :app-bot:ktlintCheck --no-build-cache --continue` | Initial helper init failed in included buildSrc, exit 1 / 5.042 s, cleanup verified. Corrected init scopes only app-bot. Second run exit 1 / 119.198 s because formatter reported an initial line-length error; **subsequent compileTestKotlin, detektMain, detektTest and ktlintCheck passed on the saved formatted files**. No test tasks executed; cleanup verified. `gradle-format-static-2/invocation.json` names the exact six files. |
| Same protected four-class test command, without static tasks; output `gradle-protection-run2` | Exit 1, 15.635 s, **36 executed / 35 PASS / 1 failed**. AdminMusicRoutesTest 10/10, MusicUploadLifecycleTest 9/9, JsonErrorFormatTest 15/15 PASS. New JsonErrorPages fatal test expected a client-side throw, but Ktor's engine fallback returns a response. Cleanup verified. |
| Protected `:app-bot:test --tests com.example.bot.plugins.JsonErrorPagesTest :app-bot:detektTest :app-bot:ktlintCheck --no-build-cache --continue`; output `gradle-error-handler-final` | Exit 1, 86.779 s, 2 executed / 1 failed; outer observer saw the fatal Error, but coroutine recovery copied it. Cleanup verified. No upload scenario rerun. |
| Same **error-handler-only** command after correcting the assertion; output `gradle-error-handler-verified` | **Exit 0**, 81.184 s, **2/2 PASS**, detektTest and changed-source ktlint execution PASS; cleanup verified, no refused identities. Original Error identity is required at the cause-chain root, its type must remain OutOfMemoryError, and exact observer response is asserted. |

Final coverage is **36 distinct passing cases across the focused runs**, not a claim
that the failed 36-case execution passed. The two invocations targeting the incident
upload were one compilation failure and one actual successful upload/lifecycle execution.
Subsequent reruns only corrected the new error-handler regression, as authorized by the
owner's instruction to complete both implementations. UP-TO-DATE dependencies/static
aggregators are not counted as executed tests. Raw logs and safe XML counts/hashes are
in `/private/tmp/clb-simplified-evidence/`; no raw logs are added to Git.

The fatal test now observes the application boundary before Ktor's engine fallback:
[Ktor 3.3.1 BaseApplicationResponse](https://github.com/ktorio/ktor/blob/3.3.1/ktor-server/ktor-server-core/common/src/io/ktor/server/engine/BaseApplicationResponse.kt).
It does not weaken the requirement to an ordinary HTTP500 assertion. Independent
read-only review closed the final supervisor and Kotlin findings. Final process metadata
shows only the original idle daemon PID 83051 with the same precise birth identity;
all invocation-owned workers are absent. No signals targeted that daemon.

Full JVM/IT/coverage and final installDist were **not rerun**: cumulative OOM retention
is still unknown, the Mac has 16 GiB, and repeated full suites exceed this bounded local
verification scope. Targeted real Ktor tests, main/test compilation, app-bot JAR and
affected static gates provide the local alternative. Required broad gates remain
mandatory for a separately controlled CI run before publication. Linux implementation
was statically reviewed; these process regressions were executed on macOS, not Linux.

## Historical credentials: owner attestation and local gate remediation

**Historical credential: `OWNER_ATTESTED_REVOKED`, 2026-10-29.** This supersedes the
earlier `IDENTITY_UNCONFIRMED / CREDENTIAL_REMEDIATION_REQUIRED` diagnostic status for
this one credential. The three nonsecret classifications are reused. Exact metadata
and safe provenance remain in the existing evidence directory.

| Full historical commit | Path / line / rule | Classification, evidence and minimal action |
|---|---|---|
| `6ec7be88e320990506553c8a5660b5668d41e35b` | `docs/product/CONCEPT_CODE_GAP.md:424`, generic-api-key | FALSE_POSITIVE. Architecture prose, corrected by `3426f53b171ecd8b225539b1df1c1a9fa5f3363a` in PR #554. Exact historical fingerprint applied locally. |
| `119181e921c19f177dad97120a42c3bc3af66d35` | `core-data/src/test/kotlin/com/example/bot/data/client/onboarding/OnboardingFoundationContract.kt:63`, generic-api-key | SYNTHETIC_TEST_VALUE. Key passed to fixture.issue; assertions exclude raw key/secret from persisted commands/audit. `synthetic-purpose-evidence.json`. Exact historical fingerprint applied locally. |
| `f06af8dfcbb301d0cb037a9960347ef3e5430435` | `core-data/src/test/kotlin/com/example/bot/data/repo/PaymentsRepositoryH2Test.kt:80`, generic-api-key | SYNTHETIC_TEST_VALUE. H2 createInitiated idempotencyKey in duplicate-charge regression. Exact historical fingerprint applied locally. |
| `f88a089df8b0b78874bc069592a40d472cfe4108` | `scripts/dev-env.sh:5`, telegram-bot-api-token | EXPOSED_CREDENTIAL / OWNER_ATTESTED_REVOKED. Private comparison confirms bot ID **8295829972**. The owner directly reported BotFather Revoke for this exact bot on **2026-10-29**. Exact historical fingerprint applied under the owner's explicit authorization. Removal `223cbc5…` alone was never treated as revocation evidence. |

Exact fingerprints, supported by the pinned Gitleaks **8.28.0**:

```text
6ec7be88e320990506553c8a5660b5668d41e35b:docs/product/CONCEPT_CODE_GAP.md:generic-api-key:424
119181e921c19f177dad97120a42c3bc3af66d35:core-data/src/test/kotlin/com/example/bot/data/client/onboarding/OnboardingFoundationContract.kt:generic-api-key:63
f06af8dfcbb301d0cb037a9960347ef3e5430435:core-data/src/test/kotlin/com/example/bot/data/repo/PaymentsRepositoryH2Test.kt:generic-api-key:80
f88a089df8b0b78874bc069592a40d472cfe4108:scripts/dev-env.sh:telegram-bot-api-token:5
```

Exactly these **four** entries are now in `.gitleaksignore`, with reviewed reasons.
Four-component commit:file:rule:line matching
preserves detection at the same file/line in another commit; do not use global
three-component fingerprints, commit/file/rule exclusions or a blanket baseline.
Real pinned-scanner regressions detect new synthetic values at all four file/line
shapes in another commit and in current uncommitted files. A synthetic Telegram token
for the same synthetic bot ID remains detected after rotation; the real token is never
used as test data. No rule/config allowlist was widened. See version-pinned
[matching implementation](https://github.com/gitleaks/gitleaks/blob/v8.28.0/detect/detect.go#L635-L657).

The revocation source is the **direct owner message in this chat**, not an independent
Telegram API/BotFather inspection. The private historical-value comparison matched
8295829972 and differed from the owner-confirmed primary bot **@NightConcierge_Bot,
ID 7696193761**. Safe evidence: `historical-token-owner-attestation.json`. No token,
fragment, Match/Secret, credential-bearing URL or protected env is copied into evidence.

The owner also reports Revoke for display names **Night Concierge Test Bot**,
**Dev Night Concierge Bot**, and **Night Concierge Bot**. Their IDs/usernames and
environment connections are unconfirmed; names alone cannot establish identity.
One could be primary bot 7696193761. If so, an environment using its previous token
may now fail authentication and needs separately authorized recovery. No credentials
were changed here. Existing repository evidence mentions @NightConcierge_Bot and an
October 28 getMe result in [client-invitation-fix.md](client-invitation-fix.md), but
contains **no successful post-revocation smoke test**. Primary bot availability remains
unverified. The owner's next identity action is to compare those three bots' public
IDs with 7696193761, without sending tokens.

### Scanner scopes and wrapper correction

| Scope | Retained evidence / result |
|---|---|
| Complete candidate, including untracked | Matching incoming 68-path manifest and full source snapshot (1519 existing files); `gitleaks-current-final` PASS, 16.69 MB scanned. Documentation added by this diagnostic is separately reviewed; no claim that an old scan covers newly edited prose. |
| Unchanged base tree `c0df96b…` | Tree `995f7519db624dfd1c9ee3d043a70cbbcab50bb8`, `gitleaks-base-c0df96b` PASS, 16.53 MB. Not a zero-byte merge diff. |
| Full locally available ref history | `history-classification-scan.log`: four findings, 668 processed commits / 26.02 MB; 1216 reachable commits in metadata. Default full-history/all scope; no history narrowed for green. |
| Hosted Secret Scan PR #554 | Run 40768160883, job 123097852839, actual depth 1 / merge `1b0e777d633a94c5f16e75d793a8002cc5d16a5f`; one commit / 17.20 MB PASS. It does not establish full-history cleanliness. |

Image digest remains `sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854`;
config SHA-256 `06d824a905552bfdf069435fe5096b09cca999899e3ee8feaae5f39d9bf53dc7`.
The table above preserves earlier executions. Input hashes, fingerprints and hosted log
extracts are in `history-classification-inputs.json`, `history-findings-metadata.json`
and `hosted-secret-scan-scope.json` under the existing evidence directory.

The old wrapper mounted only the worktree at `/repo`; its absolute Git common-dir was
unavailable. `secret-scan.log` retains Git fatal/128 followed by zero commits/bytes and
false success. `verify.sh secret-scan` now calls `scripts/secret-scan.py`:

- Resolve root, git-dir, common-dir and HEAD; refuse shallow, missing/unreadable metadata,
  external object alternates, partial clone objects and unsupported submodules.
- Mount checkout/common-dir and a complete current candidate snapshot **read-only**,
  preserving linked-worktree absolute paths. Same-layout container preflight checks
  version, HEAD, readable commit, reachable count and actual RO mount flags. Reports
  use a separate writable directory; Docker is networkless, read-only, unprivileged,
  limited to two CPUs / 512 MiB. Paths with mountinfo-escaped whitespace currently
  fail closed; they cannot produce false PASS.
- Scan current tracked plus nonignored untracked regular files, including packaged
  assets; retain deletions and refuse symlinks/unsafe inputs. This scope intentionally
  excludes ignored local secrets, dependencies and generated build outputs. Scan full
  available ref history with `--full-history --all --diff-filter=tuxdb`, matching
  Gitleaks 8.28.0's default history scope. No history reduction for a green result.
- Require fresh valid reports, consistent exit/finding counts, positive bytes for both
  phases and positive history commit count; reject errors, unknown warnings, missing
  summaries and repository mutation during scanning. Reachable and scanned commits
  are separate: merges/no-addition commits need not yield scanner fragments.
- Use explicit frozen config/fingerprints and info-only diagnostics. Raw scanner
  stdout/stderr and source snippets never reach output. Only safe metadata is converted
  to SARIF; 180-second container timeout/cancellation removes that invocation's random
  container name, never other containers. Uncertain cleanup/failure cannot return PASS.

The existing Secret Scan workflow now uses this same wrapper and `fetch-depth: 0`.
CI fetches/scans the available history instead of one shallow merge snapshot, increasing
checkout/scan time with repository size; the existing 20-minute job limit remains.
No unrelated workflow or `.gitleaks.toml` detection rule changed.

`CLB_SECRET_SCAN_DOCKER_TESTS=1 python3 -B -m unittest scripts.tests.test_secret_scan`:
**14/14 PASS**, exit 0, 13.513 s. Ten unit/failure-contract tests and four real Docker
cases cover checkout/worktree, inaccessible common-dir, zero/missing coverage,
partial/error diagnostics, untracked/current and side-ref findings, shallow refusal,
existing exact checksum rules, safe reports and all four historical fingerprint shapes.
The first runs caught an overly broad environment override refusal, exact version-prefix
mismatch and an incorrectly constructed synthetic canary; these were fixed and the
complete focused set rerun. `GIT_PAGER` is harmless; Git identity/config overrides still
refuse. `security-wrapper-tests-5.log` contains the passing result. Independent static
review's relative-path/config-path and cancellation findings were fixed and re-reviewed.
Final diff review also corrected the literal `fatal: ` diagnostic check (a word boundary
after the colon was inappropriate). All **10 unit tests** then passed, followed by the
affected method with a positive-byte/positive-commit failure fixture. The four successful
Docker cases are reused for unchanged scanner/config/scan behavior; the full 14-case run
preceded this stricter diagnostic guard. Final actual-source scanning uses the corrected
guard. Independent review accepted this delta.

Final actual-candidate/history scan and preservation results are recorded with the
implementation verification below; previous scans are not presented as coverage of
new Gradle/security bytes.

`SECRET_SCAN_OUTPUT_DIR=/private/tmp/clb-simplified-evidence/security-gate-verified bash scripts/verify.sh secret-scan`
completed with **exit 0**: current candidate **1,526 files**, **16,806,872 scanned bytes**,
**0 findings**; full available history **668 scanned commits / 26,024,672 bytes**,
**0 unaccounted findings**, with **1,216 reachable commits** recorded separately.
This is real nonzero scanning, not the old wrapper's exit-zero failure.
`security-gate-verified/summary.json` binds image, version, config, ignore file, candidate
and history hashes. The unchanged base-tree PASS remains reused, not rescanned.

After final report/checkpoint edits, the complete current-source snapshot is checked
again by the same pinned scanner functions, with sanitized evidence in
`security-current-final/`. The successful history result is reused **only with matching
HEAD/ref-history, scanner image/version, config and fingerprint hashes**. No needless
second history scan or narrowing is used. Workflow syntax validator, shell syntax,
documentation links and `git diff --check` are the final mechanical checks.

### Preservation and local handoff

The original 68-path onboarding manifest still differs only in the two authorized
ops documents: **66 code/test/migration/location/assets paths are byte-for-byte
preserved in contents/deletion state**, including the two intended obsolete-asset deletions. Both V064 migrations,
GeoNames archive and packaged Mini App are retained. The new Gradle/security work is
additional; the other worktree's Stage improvements remain untouched. One Python bytecode
file generated by this task was removed, not source or user data. HEAD, refs and commit
graph remain unchanged; the index is empty. The authorized historical credential was
compared privately for bot identity, never disclosed or used for authentication. No real
credentials were changed; no Telegram API, BotFather, Stage/prod, other projects,
commit/push/PR/merge/deploy or SSH were accessed or performed.

**One next step before publication:** authorize a controlled CI run of the unchanged
mandatory full test/IT/coverage/build gates on this final candidate. Real Telegram
acceptance and reconciliation of the three additional revoked display names remain
separate operational checks; no post-revocation availability claim is made.

### Earlier secret-scan blocker evidence (retained)

This paragraph preserves the original verification history. Its former blockers are
superseded by the owner attestation and locally verified corrections above.

The unmodified `scripts/verify.sh secret-scan` wrapper could not resolve a managed
worktree's absolute Git common-dir inside Docker: exit 0 with zero scanned bytes is
**not accepted as PASS**. With correct read-only mounts, pinned gitleaks scans 668
commits/26.02 MB and returns four pre-existing findings (`gitleaks-history.sarif`):

| Rule | Historical file | Commit prefix |
|---|---|---|
| generic-api-key | `docs/product/CONCEPT_CODE_GAP.md` | `6ec7be88` |
| generic-api-key | `core-data/src/test/kotlin/com/example/bot/data/client/onboarding/OnboardingFoundationContract.kt` | `119181e9` |
| generic-api-key | `core-data/src/test/kotlin/com/example/bot/data/repo/PaymentsRepositoryH2Test.kt` | `f06af8df` |
| telegram-bot-api-token | `scripts/dev-env.sh` | `f88a089d` |

These are scanner findings, not a claim that each is an active credential. Values are
redacted and not copied here. A separate complete current-source snapshot scan with
the same pinned scanner and repository config passes, but does not waive history gate.
At that earlier stage no allowlist/rules/security gate had been changed; adjudication
was outside the original onboarding implementation and was subsequently authorized.

## Changed files

All paths are repository-relative. Deleted hashes are replaced build outputs.

Additional files changed by the two remediation tasks (the earlier onboarding list
follows unchanged):

- [.gitleaksignore](../../.gitleaksignore)
- [.github/workflows/secret-scan.yml](../../.github/workflows/secret-scan.yml)
- [scripts/verify.sh](../../scripts/verify.sh)
- [scripts/secret-scan.py](../../scripts/secret-scan.py)
- [scripts/tests/test_secret_scan.py](../../scripts/tests/test_secret_scan.py)
- [scripts/run-bounded-gradle.py](../../scripts/run-bounded-gradle.py)
- [scripts/tests/test_run_bounded_gradle.py](../../scripts/tests/test_run_bounded_gradle.py)
- [app-bot/src/main/kotlin/com/example/bot/routes/AdminMusicRoutes.kt](../../app-bot/src/main/kotlin/com/example/bot/routes/AdminMusicRoutes.kt)
- [app-bot/src/main/kotlin/com/example/bot/routes/MusicUpload.kt](../../app-bot/src/main/kotlin/com/example/bot/routes/MusicUpload.kt)
- [app-bot/src/main/kotlin/com/example/bot/plugins/JsonErrorPages.kt](../../app-bot/src/main/kotlin/com/example/bot/plugins/JsonErrorPages.kt)
- [app-bot/src/test/kotlin/com/example/bot/routes/AdminMusicRoutesTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/AdminMusicRoutesTest.kt)
- [app-bot/src/test/kotlin/com/example/bot/routes/MusicUploadLifecycleTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/MusicUploadLifecycleTest.kt)
- [app-bot/src/test/kotlin/com/example/bot/plugins/JsonErrorPagesTest.kt](../../app-bot/src/test/kotlin/com/example/bot/plugins/JsonErrorPagesTest.kt)
- Both existing ops documents: this report and [PROJECT_STATUS.md](PROJECT_STATUS.md).

- [app-bot/src/main/kotlin/com/example/bot/Application.kt](../../app-bot/src/main/kotlin/com/example/bot/Application.kt)
- [app-bot/src/main/kotlin/com/example/bot/routes/AdminClubsRoutes.kt](../../app-bot/src/main/kotlin/com/example/bot/routes/AdminClubsRoutes.kt)
- [app-bot/src/main/kotlin/com/example/bot/routes/ClientOnboardingRoutes.kt](../../app-bot/src/main/kotlin/com/example/bot/routes/ClientOnboardingRoutes.kt)
- [app-bot/src/main/kotlin/com/example/bot/routes/OnboardingLocations.kt](../../app-bot/src/main/kotlin/com/example/bot/routes/OnboardingLocations.kt)
- [app-bot/src/main/kotlin/com/example/bot/routes/WebAppRoutes.kt](../../app-bot/src/main/kotlin/com/example/bot/routes/WebAppRoutes.kt)
- [app-bot/src/main/kotlin/com/example/bot/telegram/TelegramCabinetMenu.kt](../../app-bot/src/main/kotlin/com/example/bot/telegram/TelegramCabinetMenu.kt)
- [app-bot/src/main/kotlin/com/example/bot/telegram/TelegramGuestFallbackHandler.kt](../../app-bot/src/main/kotlin/com/example/bot/telegram/TelegramGuestFallbackHandler.kt)
- [app-bot/src/main/resources/locations/README.md](../../app-bot/src/main/resources/locations/README.md)
- [app-bot/src/main/resources/locations/cities.tsv.gz](../../app-bot/src/main/resources/locations/cities.tsv.gz)
- [app-bot/src/main/resources/locations/manifest.json](../../app-bot/src/main/resources/locations/manifest.json)
- [app-bot/src/test/kotlin/com/example/bot/routes/ClientContextRoutesDataTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientContextRoutesDataTest.kt)
- [app-bot/src/test/kotlin/com/example/bot/routes/ClientOnboardingRoutesDataTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientOnboardingRoutesDataTest.kt)
- [app-bot/src/test/kotlin/com/example/bot/routes/OnboardingLocationsTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/OnboardingLocationsTest.kt)
- [app-bot/src/test/kotlin/com/example/bot/routes/WebAppRoutesTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/WebAppRoutesTest.kt)
- [app-bot/src/test/kotlin/com/example/bot/telegram/TelegramCabinetMenuTest.kt](../../app-bot/src/test/kotlin/com/example/bot/telegram/TelegramCabinetMenuTest.kt)
- [app-bot/src/test/kotlin/com/example/bot/telegram/TelegramGuestFallbackHandlerTest.kt](../../app-bot/src/test/kotlin/com/example/bot/telegram/TelegramGuestFallbackHandlerTest.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientAccessOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientAccessOperations.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientLimitOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientLimitOperations.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientOnboardingRepository.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientOnboardingRepository.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubOnboardingOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubOnboardingOperations.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/DirectClubOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/DirectClubOperations.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/DirectClubProfileImmutableTrigger.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/DirectClubProfileImmutableTrigger.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/InvitationOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/InvitationOperations.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/JdbcClientOnboardingRepository.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/JdbcClientOnboardingRepository.kt)
- [core-data/src/main/kotlin/com/example/bot/data/client/onboarding/OnboardingSql.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/OnboardingSql.kt)
- [core-data/src/main/resources/db/migration/h2/V064__simplified_client_onboarding.sql](../../core-data/src/main/resources/db/migration/h2/V064__simplified_client_onboarding.sql)
- [core-data/src/main/resources/db/migration/postgresql/V064__simplified_client_onboarding.sql](../../core-data/src/main/resources/db/migration/postgresql/V064__simplified_client_onboarding.sql)
- [core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClubProfileContract.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClubProfileContract.kt)
- [core-data/src/test/kotlin/com/example/bot/data/client/onboarding/OnboardingContract.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/onboarding/OnboardingContract.kt)
- [core-data/src/test/kotlin/com/example/bot/data/client/onboarding/OnboardingFixture.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/onboarding/OnboardingFixture.kt)
- [core-data/src/test/kotlin/com/example/bot/data/client/onboarding/SimplifiedOnboardingContract.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/onboarding/SimplifiedOnboardingContract.kt)
- [docs/ops/PROJECT_STATUS.md](../../docs/ops/PROJECT_STATUS.md)
- [docs/ops/simplified-client-onboarding.md](../../docs/ops/simplified-client-onboarding.md)
- [docs/product/AS_BUILT.md](../../docs/product/AS_BUILT.md)
- [docs/product/CONCEPT_CODE_GAP.md](../../docs/product/CONCEPT_CODE_GAP.md)
- [docs/product/OPEN_DECISIONS.md](../../docs/product/OPEN_DECISIONS.md)
- [docs/product/PRODUCT_ROADMAP.md](../../docs/product/PRODUCT_ROADMAP.md)
- [docs/product/PRODUCT_SPEC.md](../../docs/product/PRODUCT_SPEC.md)
- [docs/security.md](../../docs/security.md)
- [miniapp/dist/assets/PlatformOwnerScreens-CBEELu8s.js](../../miniapp/dist/assets/PlatformOwnerScreens-CBEELu8s.js)
- `miniapp/dist/assets/PlatformOwnerScreens-DGnvUznx.js` (deleted obsolete asset)
- `miniapp/dist/assets/index-Ck11E10x.js` (deleted obsolete asset)
- [miniapp/dist/assets/index-KMk0ccg6.js](../../miniapp/dist/assets/index-KMk0ccg6.js)
- [miniapp/dist/index.html](../../miniapp/dist/index.html)
- [miniapp/src/app/App.tsx](../../miniapp/src/app/App.tsx)
- [miniapp/src/app/CabinetEntry.test.tsx](../../miniapp/src/app/CabinetEntry.test.tsx)
- [miniapp/src/app/CabinetEntry.tsx](../../miniapp/src/app/CabinetEntry.tsx)
- [miniapp/src/app/client-entry.test.ts](../../miniapp/src/app/client-entry.test.ts)
- [miniapp/src/main/resources/miniapp/assets/js/client-entry.js](../../miniapp/src/main/resources/miniapp/assets/js/client-entry.js)
- [miniapp/src/modules/auth/hooks/useInitData.test.tsx](../../miniapp/src/modules/auth/hooks/useInitData.test.tsx)
- [miniapp/src/modules/client/ClientAccess.tsx](../../miniapp/src/modules/client/ClientAccess.tsx)
- [miniapp/src/modules/client/ClubProfile.tsx](../../miniapp/src/modules/client/ClubProfile.tsx)
- [miniapp/src/modules/client/NewClub.tsx](../../miniapp/src/modules/client/NewClub.tsx)
- [miniapp/src/modules/client/PlatformOnboarding.tsx](../../miniapp/src/modules/client/PlatformOnboarding.tsx)
- [miniapp/src/modules/client/invitation.test.tsx](../../miniapp/src/modules/client/invitation.test.tsx)
- [miniapp/src/modules/client/onboarding-b.test.tsx](../../miniapp/src/modules/client/onboarding-b.test.tsx)
- [miniapp/src/modules/client/onboarding.api.ts](../../miniapp/src/modules/client/onboarding.api.ts)
- [miniapp/src/modules/client/onboarding.hooks.ts](../../miniapp/src/modules/client/onboarding.hooks.ts)
- [miniapp/src/modules/client/onboarding.test.tsx](../../miniapp/src/modules/client/onboarding.test.tsx)
- [miniapp/src/modules/client/onboarding.types.ts](../../miniapp/src/modules/client/onboarding.types.ts)
- [miniapp/src/modules/client/simplified-onboarding.test.tsx](../../miniapp/src/modules/client/simplified-onboarding.test.tsx)
- [miniapp/src/modules/client/useInvitationDraft.ts](../../miniapp/src/modules/client/useInvitationDraft.ts)
- [miniapp/src/modules/platform-owner/PlatformOwnerAccess.test.tsx](../../miniapp/src/modules/platform-owner/PlatformOwnerAccess.test.tsx)
- [miniapp/src/modules/platform-owner/PlatformOwnerAccess.tsx](../../miniapp/src/modules/platform-owner/PlatformOwnerAccess.tsx)
- [miniapp/tests/onboarding-integrated.spec.ts](../../miniapp/tests/onboarding-integrated.spec.ts)
- [miniapp/tests/onboarding-production.spec.ts](../../miniapp/tests/onboarding-production.spec.ts)
- [miniapp/tests/serve-onboarding-dist.mjs](../../miniapp/tests/serve-onboarding-dist.mjs)
- [scripts/build-onboarding-locations.py](../../scripts/build-onboarding-locations.py)

## Remaining verification and next step

Unverified: real Telegram iOS/Android/desktop Main Mini App and permanent menu appearance,
actual SDK registration response, real deep-link delivery/account switching, hosted CI,
Stage migration/runtime and GeoNames coverage for a real customer's chosen settlement.
These require a separately authorized operational step; local evidence is not deployment.

The historical bot 8295829972 is now identified and owner-attested revoked; the
authorized local Gradle/security implementations above are complete. Remaining operational
uncertainty concerns the three additional revoked display names and primary bot availability,
not the identity of this historical finding. No token should be shared.

**One next step before publication:** authorize a controlled CI run of the mandatory
full test/IT/coverage/build gates on the final candidate. Publication and runtime changes
remain unauthorized. The original GC retention cause and the documented supervisor
failure-model limits remain open; neither is described as eliminated.
