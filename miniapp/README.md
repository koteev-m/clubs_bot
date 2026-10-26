# Telegram Mini App

## Development

```bash
pnpm install
pnpm dev
```

Open `http://localhost:5173?mode=guest` in Telegram Web App environment.

## Build

```bash
pnpm build
```

Static files are written to `dist/` with asset base `/app/react/`. This command does not
publish them. Canonical `/app` still serves the legacy static catalogue; the separately
wired support modes are not a cutover to the broader React role shells.

## Modes

Start the Mini App with payload `mode=guest` or `mode=entry` to switch modes.

## Local platform owner preview (CLB-196)

From this worktree's `miniapp/` directory, with dependencies installed from the lockfile:

```bash
node node_modules/vite/bin/vite.js --host localhost --port 5173 --strictPort
```

- Overview: [http://localhost:5173/__preview/platform](http://localhost:5173/__preview/platform).
- Invitation: [http://localhost:5173/__preview/platform/invite](http://localhost:5173/__preview/platform/invite).

The overview action opens the invitation screen. The in-page Back control, browser
Back/Forward and Telegram BackButton share browser history. A direct invitation entry
creates an overview history entry; reload retains it. Check visually at 320, 390 and
430 px widths, including scrolling to the navigation and the invitation status.
CSS accommodates iPhone safe areas and Telegram inset variables.

This preview requires both Vite development mode and a loopback hostname. It uses no
backend data, sends no API requests and cannot issue invitations or assign roles.
Unavailable counts are dashes, the club list is explicitly unavailable, and unfinished
navigation entries are disabled. The invitation states «Создание приглашений ещё не
подключено». The unguarded preview entry is excluded from production builds; shared
screens are now also used behind the CLB-197 server access gate. The aliases `mode=owner`
and `mode=platform` are refused; `mode=platform-owner` selects the server check only.

The existing `OWNER`/GLOBAL mapping (DEC-005) does not establish a separately verified
platform owner versus an invited independent club owner. CLB-197/DEC-039 now provides
the narrow server identity check described below. Invitation creation/acceptance and
real platform data remain unconnected. Existing guest
and support entries and canonical `/app` are preserved. The preview itself does not change
DEC-003 pilot scope, DEC-007 navigation acceptance, RBAC, existing API contracts or server ingress.

Focused checks (also work directly when a local pnpm wrapper cannot execute scripts):

```bash
node node_modules/vitest/vitest.mjs run src/modules/platform-owner src/app/App.test.tsx
node node_modules/typescript/bin/tsc --noEmit
node node_modules/eslint/bin/eslint.js . --ext .js,.jsx,.ts,.tsx,.cjs,.mjs --max-warnings 0
node node_modules/vite/bin/vite.js build
node node_modules/@playwright/test/cli.js test platform-owner.spec.ts
```

The browser spec requires the dev server at localhost:5173 and an installed Playwright
browser. It emits `platform-overview.png` and `platform-invite.png` in its test output.
For an installed system Chrome, a temporary Playwright config may set
`use: { channel: 'chrome', baseURL: 'http://localhost:5173' }` without changing test gates.
Physical iPhone/Telegram runtime acceptance and staging smoke remain unverified.

To inspect a production build locally, match its asset base explicitly:

```bash
node node_modules/vite/bin/vite.js preview --base /app/react/ --host localhost --port 4173 --strictPort
```

At `/app/react/__preview/platform` and `/app/react/__preview/platform/invite` the build
shows an unavailable-access message, without the preview screens or API requests.

## Platform owner Telegram identity (CLB-197)

Open [the guarded cabinet](http://localhost:5173/app?mode=platform-owner) or
[the guarded invitation screen](http://localhost:5173/app?mode=platform-owner&screen=invite).
The same bounded entry is packaged for Ktor at `/app?mode=platform-owner`; default
guest `/app`, staff support and guest support retain their entries. This adds no public
owner tab to guest navigation. The compiled `dist/` is refreshed for Ktor packaging.

Vite proxies **only** `/api/platform/access` to the existing local backend at
`http://127.0.0.1:8080`. To test one's own cabinet, that backend must already run with
its private bot/configuration and the matching positive `OWNER_TELEGRAM_ID`. Open the
Mini App through Telegram with fresh initData issued for that same bot; the SDK passes
it via the existing request header. An ordinary desktop browser without initData should
show «Нужна авторизация в Telegram». Do not paste credentials or identity into query
parameters, commit them, or generate a signature from client-provided identity.
This task did not start a real configured bot/backend, read actual IDs, or change Telegram
launch configuration. A real Telegram device launch additionally needs an explicitly
authorized HTTPS launch URL serving this version and a runtime smoke; none is claimed here.

The server's `GET /api/platform/access` validates the existing Telegram initData contract
and compares signed `user.id` with configured `AppConfig.bot.ownerId`. Success exposes
only `{ "platformOwner": true }`; missing/invalid/stale credentials return 401, another
identity/nonpositive configured ID returns 403. Missing/malformed raw owner configuration
keeps the existing startup refusal. Header-only transport and no-store apply in all profiles.
No role lookup or database write grants access to this cabinet; a legacy GLOBAL OWNER
with another Telegram ID remains denied. The previous role's existing endpoint semantics
are unchanged. Safe startup config output now reports owner presence without its ID.

Frontend controls mount only after an authoritative success; malformed success, errors,
401/403, credential changes and page restoration clear access. No role, userId, URL or
browser storage flag can substitute for this request. A manipulated frontend is never
server authority. Future protected APIs must recheck identity independently.

Automated checks use synthetic identities/tokens only:

```bash
./gradlew :app-bot:test --tests com.example.bot.routes.PlatformOwnerRoutesTest --tests com.example.bot.routes.WebAppRoutesTest
./gradlew :core-domain:test --tests com.example.bot.config.AppConfigFromEnvTest
./gradlew test detekt ktlintCheck --no-configuration-cache
# From miniapp/:
node node_modules/vitest/vitest.mjs run
node node_modules/@playwright/test/cli.js test platform-owner-auth.spec.ts platform-owner.spec.ts guest-support.spec.ts
```

The Ktor tests sign initData with a synthetic bot token and exercise exact identity,
legacy OWNER separation, invalid/missing configuration, signatures, expiry, transport
and refusal headers. Browser auth tests mock API outcomes; they do not claim a real
Telegram signature/device or deployment test. Both original CLB-196 preview screens
remain available. Club-owner role representation, club-scoped personnel permissions,
invitation issuance/acceptance and real club data are still future work; assigning legacy
GLOBAL OWNER as a club owner is not an implementation shortcut.

## Assets

Hall images (`src/assets/halls/club_<id>.png`) are not tracked in Git and must be provided manually for each club.

## Testing

Unit tests:
```bash
pnpm test
```
E2E tests:
```bash
pnpm e2e
```
