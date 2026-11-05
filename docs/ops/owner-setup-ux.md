# Owner setup UX — local handoff, 2026-11-05

Task: `CLB-owner-setup-ux` (user-authorized technical label; no new card).
Repository: `koteev-m/clubs_bot`. Base and fetched `origin/main`:
`46db7930f951ff115426d66576731089d75ffc75`.
Worktree: `/Users/maksimmartynov/.codex/worktrees/owner-setup-ux/clubs_bot`,
detached HEAD, uncommitted local candidate. `PROJECT_IDENTITY_OK` passed with
this exact expected HEAD; active foreign instructions = 0.

Verdict: **PARTIAL_BLOCKED**. The requested local UX changes and regression
protection are implemented. The original staging duplicate-image incident has
not been reproduced or attributed to a saved-data or rendering cause. No stage
data, API, browser session or database was accessed. Local tests cannot prove
the contents of production `hall_plans` or which bytes a real iPhone displayed.

## Duplicate-image investigation

- `ClubLayoutOperations.plan`, `ClubLayoutPlans.upload` and `ClubLayoutViews.hall`
  read/update `hall_plans` by exact `hall_id`; the parent club and client grants
  are verified first. Table lists and coordinates are selected by the hall ID.
  There is no club-wide fallback image in these paths.
- `layout.api.ts` requests `/api/client/clubs/{club}/halls/{hall}/plan` with
  `cache: no-store`, after checking layout metadata. Missing metadata produces
  an empty scene; refused private image access clears the cabinet. The private
  route has no 304 path. These contracts were preserved.
- Both the base source and the published base `miniapp/dist` already key the
  owner editor by hall ID. Therefore the report cannot honestly identify
  unkeyed owner navigation as the cause of the staging incident.
- The reusable `HallEditorScreen` itself previously retained URL/ETag, drafts,
  selected table and requests if its caller reused it with another hall ID.
  It now creates a session per club/hall. Aborted/unmounted table reads and
  late failures are ignored. Each replacement image also creates a fresh
  `HallPlanStage` for its dimensions and pointer state. Tests cover late success
  and late 403 without a parent key. This is a confirmed local lifecycle gap,
  not proof that this path caused the staging observation.
- The cabinet now compares saved plan SHA-256 metadata and explicitly names
  other halls whose saved image matches. That notice means equal saved image
  metadata, rather than an inferred rendering error. It never copies, deletes
  or repairs an image. Replacing A changes only A, including its revision;
  B's image/tables/coordinates and an empty hall stay unchanged.

## UX delivered

- The selected hall is named explicitly. Its list, count, placed/unplaced
  tables, plan URL, loading state and drafts have separate lifetimes.
- `ClubTableCreationForm` has explicit foreground/background pairs for inputs,
  textarea, select and native options; placeholder, errors, disabled buttons
  and focus are readable. The form and shared editor adopt the cabinet's
  existing dark palette only inside `.client-layout`; the standalone form has
  an explicit light palette. Both Telegram/browser theme preferences were
  exercised. Other screens' global palette was not changed.
- Single, range and arbitrary-number creation remain. Conflicts explain the
  occupied number and, when present in the authorized layout, the other hall.
  Duplicate input numbers are distinguished from occupied numbers. A raced
  server conflict identifies the attempted numbers and requests refreshed
  data; no number is renumbered automatically. Club-wide uniqueness remains.
- Upload controls explicitly advertise **PNG/JPEG, up to 5 MB**. PDF support
  and conversion were not added. Replacing an image preserves saved markers.
- Calendar labels use the day/date of the party, opening relative to that
  date, and closing relative to the actual opening. Before save each rule
  shows calculated weekdays and wall dates. Weekly dates are clearly labeled
  as examples; saved server calculations remain authoritative for exceptions,
  holidays, DST and actual instants.
- Empty-calendar guidance names the required action. The old reconcile button
  is now **«Сформировать ближайшие ночи»**. It still sends the same explicit
  `reconcile: true` intent, version and idempotency key. Saving still performs
  the existing server reconciliation; the UI does not pretend it only saves
  a template. The separate button applies the saved schedule to the next
  56 calendar days and future dated rules without requiring rule edits.
- Booking fields distinguish online cutoff before closing, latest arrival
  after opening, HOLD during checkout, and waiting after the stated arrival.
  Examples: close 12:00 / cutoff 120 → 10:00; open 00:00 / arrival 180 → 03:00;
  checkout 21:00 / HOLD 10 → 21:10; stated arrival 01:00 / wait 30 → 01:30.
  HOLD 10 is advice only. Null, zero where allowed, and saved nondefault values
  remain distinct. Reads, refresh and unrelated saves never apply the advice.
  Contradictory arrival/cutoff values warn before save without rewriting them.
- Preview HOLD reads **«Оформляют бронь (HOLD)»**, with an explanation of
  temporary checkout retention. UNKNOWN reads **«Требуется настройка»**, with
  a known reason or an explicit missing-night/snapshot/table-state explanation.
  Unknown future reason codes also have a safe fallback. UNAVAILABLE explains
  cancellation/inactive configuration or requests a settings check. UNKNOWN
  never becomes FREE. The existing HOLD button remains disabled; no confirm
  or payment action was introduced.

## Required Friday example

For party date **06.11.2026 (Friday)**, set opening to the next day, `00:00`,
closing `12:00`, and leave “closing on the next day after opening” unchecked.
The payload remains `startNextDay=true`, `nextDay=false`.

> Вечеринка пятницы. Открытие: суббота, 00:00. Закрытие: суббота, 12:00.

The date preview shows party **06.11.2026**, opening **07.11.2026**, closing
**07.11.2026**. Server-returned `00:00:00` is displayed as `00:00`; nonzero
seconds are not discarded. The night keeps Friday's canonical business date.

## Verification and evidence

- TypeScript and full ESLint with zero warnings: PASS.
- Full Vitest: 289/289 PASS; focused-review follow-up adds a late-error case
  and rechecks the affected hall/calendar tests (9/9 PASS, 290 distinct tests
  in the resulting suite). Unchanged successful tests are reused.
- Packaged owner UX suite: 20/20 mobile Chromium/WebKit cases PASS. The final
  hall/error/calendar follow-up rechecks the affected 10/10 cases. Browser
  tests supply synthetic Telegram launch data on each document, mock API
  responses and measure actual rendered image pixels and text contrast.
  Native Telegram/iPhone execution is not claimed. WebKit screenshots were
  visually inspected; text contrast checks require at least 4.5:1.
- Repository H2/PostgreSQL run: 575 cases, 565 PASS, 10 existing skips, no
  failures; includes 82 H2 and 89 PostgreSQL onboarding cases. New independent
  hall scenario was then rechecked on both databases: 2/2 PASS.
- Fresh Ktor H2/PostgreSQL run: 63/63 PASS: layout 5, calendar 12, booking rules
  10, operational nights 16, preview 10, private containment 10. The new image
  case roundtrips independent PNG/JPEG and a missing plan after app restart.
- Domain calendar/DST cases: 7/7 PASS. Backend production source is unchanged.
- Full `detektGate` and fresh baseline-aware `ktlintCheck`: PASS. The added
  Ktor test's affected Detekt/ktlint gates also PASS after formatting correction.
  No baseline or test rule was relaxed. The initial cached-path ktlint failure
  was resolved by rerunning the same gate without build/configuration cache.
- Vite, `validate-miniapp-dist.sh` and `:app-bot:installDist`: PASS; final
  installed JAR assets are compared byte for byte with `miniapp/dist`.
- Full ordinary `./gradlew test coverageGate`: PASS, 1,447 cases (1,446 PASS,
  one existing skip). JaCoCo still reports execution-data mismatch warnings
  for unchanged refund/cancel DTO classes; the coverage gate passes, and this
  handoff does not claim new coverage for those classes.
- The initial secret scan correctly refused a result while the candidate was
  still changing. The final frozen-candidate result is in
  `/private/tmp/owner-setup-secret-scan-final.log`; no scan rule was disabled.
- V067–V073: all 14 H2/PostgreSQL migration files byte-identical to the base.
  No legacy events, V070 links, live holds or bookings were auto-created.

Execution logs are local `/private/tmp/owner-setup-*` files. Important runs:
`owner-setup-jvm.log`, `owner-setup-jvm-final.log`,
`owner-setup-jvm-gates.log`, `owner-setup-ktlint-fresh.log`,
`owner-setup-detekt.log`, `owner-setup-packaging-static.log`,
`owner-setup-install-final.log`, `owner-setup-secret-scan-final.log`,
`owner-setup-vitest-complete.log`, `owner-setup-vitest-freeze.log`,
`owner-setup-browser-complete.log`, `owner-setup-browser-freeze.log`.
The exact uncommitted source/artifact digest inventory is in
[owner-setup-ux-evidence.json](owner-setup-ux-evidence.json).

Focused review covered hall ownership/lifetimes, stale success/failure,
object-URL cleanup, club-wide number uniqueness, scoped styles, wall-date vs
DST instants, server reconciliation effects, null preservation, safe unknown
states and the absence of new booking commands. The late-failure finding was
fixed and tested. No independent reviewer is claimed.

## Boundaries and remaining decision

No commit, push, PR, dispatch or deploy. No real staging data was read or
changed. No club publication, public guest-route/rule wiring, staff operations,
payments or migration changes. Unrelated worktrees and pre-existing edits were
preserved. Only formatter side effects created by this task were restored,
after saving their exact backup and reviewing their diffs.

No new product decision is needed for the requested UX wording and isolation
protection. Guest/staff/payment cutover and automatic legacy links remain
separately authorized work under DEC-008/009/018. If two existing halls really
contain the same saved image, choosing which image belongs to which hall is
an owner data decision; automatic deduplication/repair is outside this task.

The one next step is a read-only reproduction of the original incident with
the affected hall IDs and saved `plan.sha256` metadata, under separately
authorized staging access or owner-provided sanitized evidence. Do not include
initData, authorization headers, binary image bodies or secrets in that evidence.

## Short iPhone checklist

Run on a disposable test club after a separately authorized installation;
do not replace the real staging club's images for this task.

1. In Telegram, open Main, Welcome and a hall with no image. Check hall title,
   table count, distinct images and markers. Switch away/back, use refresh and
   reopen the Mini App. No previous image/table should appear as current.
2. Use different PNG/JPEG in the test halls. Replace only Main's image and
   verify Welcome and the empty hall. If the saved-image equality notice
   appears, record its named halls without attempting automatic repair.
3. In both Telegram themes, open “Добавить столы”; check labels, select/options,
   list placeholder, keyboard focus, invalid/occupied number and disabled save.
   Try one table, a range and arbitrary numbers. A number used in Main must
   be refused in Welcome with its number and reason shown.
4. Set Friday, next-day opening 00:00, closing 12:00, closing-next-day unchecked.
   Read the Friday/Saturday sentence and dates before save, save/reopen, then
   use “Сформировать ближайшие ночи”. Verify the night retains Friday's date.
5. Read all four booking examples. Confirm saved HOLD is unchanged and an
   unset value stays blank. Try conflicting arrival/cutoff values in the test
   club and check the warning. In guest preview select HOLD/UNKNOWN/unavailable
   fixtures, read the reason and verify that booking controls remain disabled.
