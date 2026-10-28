# Client invitation: local diagnosis and configuration boundary

Base: `71d0beda4ab2897f668f63f9a67b552d4214d863`, reported by the user as deployed Stage.
This document separates local tests, the earlier bounded Stage observation and the
latest owner report. None of these observations authorizes a Stage mutation.

## Current preparation boundary — 2026-10-28

The preceding authorized read-only diagnostic in this chat identified one running
`clubs-bot-stage` app at revision `71d0beda4ab2897f668f63f9a67b552d4214d863`.
Reading the effective **running JVM environment** found BOT_USERNAME absent.
Exactly one getMe, using that process's configured token without printing it,
returned public username `NightConcierge_Bot` and `has_main_web_app=false`.
Existing app logs contained four POST `/api/platform/onboarding/invitations` HTTP
500 records at 2026-10-28 18:01:44–18:02:59 UTC (21:01:44–21:02:59 Moscow).
These safe summaries come from the previous tool outputs, not a fresh collection;
raw environment/API responses were not retained. Exact attribution to a particular
human click is unavailable. The confirmed Stage configuration matches the locally
reproduced pre-persistence failure path; historical HTTP 500 is now established for
that endpoint, superseding the earlier local-only uncertainty below.

**Latest owner report:** Main Mini App was then enabled for @NightConcierge_Bot in
BotFather. This is owner-provided evidence, not an independent new getMe or launch
verification. The observed server MINI_APP_URL is
`https://stage.nightconcierge.help/app`; it does not prove Telegram's configured URL.
Main Mini App and Bot Management Mode are different settings. Enabling the former
does not implement or activate Managed Bots.

The local candidate remains `LOCAL_INVITATION_FIX_READY`; production code, tests
and dist bytes are preserved against `/private/tmp/client-invitation-fix-evidence/manifest.json`.
For the future separately authorized Stage update, **BOT_USERNAME=NightConcierge_Bot
must reach the effective app process environment**. The local Compose mapping is
preserved, but the separately provisioned Stage Compose/env source must carry the
same value; a repository mapping or a value only in `.env` is not runtime proof.
This docs task does not edit those server inputs or restart the container.

Stage is **not considered fixed** yet. Keep `startapp=ci_<secret>` for Main Mini App;
do not replace it with plain `start`. A real invitation/link/recipient acceptance is
deferred until publication and the agreed Stage update. The new
[Managed Bots direction](../product/MANAGED_BOTS_DIRECTION.md) is independent of this fix.

## Historical local diagnosis before Stage observation

## Confirmed failure path

`Application.kt` passes `AppConfig.bot.username` to `clientOnboardingRoutes`.
`AppConfig.fromEnv()` reads **BOT_USERNAME**, not TELEGRAM_BOT_USERNAME.
At the base revision, `OnboardingHttp.mutate` throws before `repository.mutate` if
issue/reissue has no valid username. `safeOnboarding` maps that exception to 500.
`onboardingRequest` preserves 500, and `useCommand` classifies it as unknown,
keeping the same command for repeated retries. No client/invitation/command is
committed in this configuration-failure branch. Other 500/network failures may
occur after commit and must retain their unknown-outcome semantics.

The repository Compose did not forward BOT_USERNAME, although `.env.example`
declared it. It now forwards the supplied value without a fabricated fallback.
Stage uses separately provisioned configuration: this repository omission alone
does **not** establish the effective Stage value or the cause of the observed incident.
At that earlier local-only step no Stage logs, live environment or Telegram
configuration had been read.

Owner follow-up (2026-10-28): the Stage bot is **@NightConcierge_Bot**; the HTTP
status/error code was not observed. This matches the previously reported bot name
in `CLB-91-aeza-first-install.md`, not an independent runtime identity check.
The expected setting is `BOT_USERNAME=NightConcierge_Bot` (a leading `@` is also
accepted). Neither `.env` nor `.env.stage` exists in this worktree, so there is no
local effective Stage value to compare. The old unknown-outcome message also covers
network failures, timeout and unreadable response bodies, as well as HTTP 5xx.
**HTTP 500 on Stage remains unconfirmed.** The exact-name Ktor regression uses a
synthetic secret and no network call to Telegram; it checks the link format without
substituting this bot into production defaults or modifying server configuration.
Follow-up check: `ClientOnboardingAuthTest` **11/11 PASS**, including exact
`NightConcierge_Bot` and `@NightConcierge_Bot` link generation; app-bot ktlint and
detektTest PASS. Production sources/assets are byte-identical to the earlier
verified manifest, so the existing full-stack results remain applicable. Evidence:
`/private/tmp/client-invitation-fix-evidence/stage-username-check/`.

Local repair returns the common `ApiError` code `invitation_link_unavailable` (503)
before mutation. The UI explains that the platform bot needs configuration; all
other 500 and lost responses retain the original key/payload. If an earlier attempt
was already unknown, even a later configuration refusal cannot discard that key.

## Required operational configuration

An authorized operator must verify, without sharing tokens or initData:

1. The app container receives `BOT_USERNAME` equal to the public username of the
   **same platform bot** as its configured bot token. A username or `@username` is
   accepted; a URL/empty/malformed value is not. Do not substitute a test bot.
2. That bot has its **Main Mini App** configured to the existing canonical Mini App
   entry URL. A working chat menu button is insufficient evidence for this setting.
3. The issued link launches the recipient flow on a real Telegram client.

For the owner-reported bot, the expected format is
`https://t.me/NightConcierge_Bot?startapp=ci_<43-character-secret>`.
Telegram forwards `startapp` as `start_param` / `tgWebAppStartParam`; see
[Telegram Main Mini App documentation](https://core.telegram.org/bots/webapps#launching-the-main-mini-app).
The existing client entry recognizes the `ci_` prefix, scrubs the launch URL, and
resolves the invitation through the authenticated API. Syntactic validation cannot
prove that a username belongs to a live bot or that its Main Mini App is configured.

Client invitation expiry is seven days; `INVITATION_TTL_HOURS` controls a different,
legacy guest invitation feature and must not be used to configure this flow.

## Recovery and privacy

Issue/reissue generates 32 random bytes; persistence contains only the token hash.
The client, invitation, audit and command are committed in one transaction.
Same-key replay returns the existing invitation without the secret. The UI displays
“Ссылка не получена”, opens that invitation, and requires explicit confirmation for
reissue. Reissue invalidates the old invitation and creates a new one for the same
client. A database record alone is never presented as a usable-link success.

The owner form keeps its fields, issued result and pending command in gate-owned
component memory across internal Back/Forward. Same-credential foreground recheck
hides the private UI and discards the raw link but preserves fields and the pending
key/payload. Credentials changing or authorization loss clears private state.
It adds no localStorage/sessionStorage/URL persistence. Opening the mutable invitation
detail discards the old link; inviting another client requires an explicit reset.
The recipient still explicitly claims the account, then supplies the comparison code
in the original personal chat; platform confirmation remains a separate action.

Local verification and remaining boundaries are recorded in
[PROJECT_STATUS.md](PROJECT_STATUS.md). No commit, push, PR, merge, deployment,
shared database change or live Telegram action is part of this repair.

## Local verification, 2026-10-28

Verdict: **LOCAL_INVITATION_FIX_READY**, unpublished worktree
`/Users/maksimmartynov/.codex/worktrees/client-invitation-fix/clubs_bot`, detached
at the stated base. Primary checkout and its existing edits remain untouched.

| Check | Result |
| --- | --- |
| Before-fix regression | Missing/malformed username reproduced generic 500 instead of the expected explicit configuration refusal |
| Ktor auth / JDBC / error registry | 10 / 4 / 7 PASS; configuration refusal has no repository call; link → claim → owner confirm and lost-response replay/reissue covered |
| H2 / disposable PostgreSQL 16.14 onboarding | 43 / 48 PASS; concurrent same-key issue/reissue returns one client and one raw secret per issuance |
| Full `./gradlew test` | 1307 discovered, 1306 PASS, 1 pre-existing disabled GuestFlowStartTest, 0 failures/errors |
| Kotlin | Affected app-bot/core-data detekt + ktlint, logs policy and installDist PASS; no baseline/gate changes |
| Frontend unit tests | Full 203 PASS; subsequent focused 22 PASS includes all 3 added review regressions (206 final test cases covered) |
| TypeScript / ESLint | PASS; affected browser/test additions also linted |
| Mobile production-dist Playwright | 7/7 PASS; updated foreground/Back/Forward/retry test rechecked 1/1 PASS |
| Real Ktor/JDBC + production UI Playwright | 2/2 PASS: issue/copy/recipient claim/owner confirm; lost committed response → same-key replay → explicit reissue → old token refused → new recipient claim |
| Packaging | Dist validator PASS; all 5 dist files match installDist JAR byte-for-byte, no stale assets |
| Source/docs | Compose BOT_USERNAME mapping parsed from YAML without reading/interpolating environment; links and diff whitespace checked |

Mobile form and success/recovery screenshots were inspected. Both browser scenarios
use test-only synthetic identities/bot name and an isolated H2 database; PostgreSQL
contracts use a separate disposable database. This proves the application chain,
not real Telegram network launch or Stage bot ownership/configuration. Test services
were stopped; no Stage/shared database or live Telegram operation was performed.

Independent review found and closed three regressions: command loss on foreground
revalidation, stale copied link after opening mutable detail, and lack of an explicit
next-client reset. Re-review has no open blocking findings.

Evidence: `/private/tmp/invitation-backend-evidence/` contains before/after XML and
backend hashes; `/private/tmp/client-invitation-fix-evidence/` contains the final
file manifest, packaging record and frontend/browser command logs. Initial tool
failures (missing browser binary, validator invoked from the wrong directory,
test-only assertions, legacy line-based lint baseline shift) were corrected;
no dependency lockfile, gate or baseline was weakened.

Current next step: separate review of the combined local candidate before any
explicitly authorized publication. The runtime observation above supersedes the
older recommendation to collect Stage configuration for the first time.

## Changed files

- `.env.example`
- `app-bot/src/main/kotlin/com/example/bot/http/ErrorCodes.kt`
- `app-bot/src/main/kotlin/com/example/bot/http/ErrorRegistry.kt`
- `app-bot/src/main/kotlin/com/example/bot/routes/ClientOnboardingRoutes.kt`
- `app-bot/src/test/kotlin/com/example/bot/routes/ClientOnboardingAuthTest.kt`
- `app-bot/src/test/kotlin/com/example/bot/routes/ClientOnboardingRoutesDataTest.kt`
- `core-data/src/test/kotlin/com/example/bot/data/client/onboarding/OnboardingFoundationContract.kt`
- `docker-compose.yml`
- `docs/error-registry.md`
- `docs/ops/CLB-91-aeza-first-install.md`
- `docs/ops/PROJECT_STATUS.md`
- `docs/ops/client-invitation-fix.md`
- `docs/product/PRODUCT_SPEC.md`
- `miniapp/dist/assets/PlatformOwnerScreens-CAXpUF6Y.js`
- `miniapp/dist/assets/PlatformOwnerScreens-DGnvUznx.js`
- `miniapp/dist/assets/index-BKs7ek27.js`
- `miniapp/dist/assets/index-CQLQPzE_.css`
- `miniapp/dist/assets/index-Ck11E10x.js`
- `miniapp/dist/assets/index-Dv7D4sMz.css`
- `miniapp/dist/index.html`
- `miniapp/src/modules/client/PlatformOnboarding.tsx`
- `miniapp/src/modules/client/invitation.test.tsx`
- `miniapp/src/modules/client/onboarding.api.ts`
- `miniapp/src/modules/client/onboarding.css`
- `miniapp/src/modules/client/onboarding.hooks.ts`
- `miniapp/src/modules/client/onboarding.test.tsx`
- `miniapp/src/modules/client/useInvitationDraft.ts`
- `miniapp/src/modules/platform-owner/PlatformOwnerAccess.test.tsx`
- `miniapp/src/modules/platform-owner/PlatformOwnerAccess.tsx`
- `miniapp/src/modules/platform-owner/PlatformOwnerScreens.tsx`
- `miniapp/tests/onboarding-integrated.spec.ts`
- `miniapp/tests/onboarding-production.spec.ts`
