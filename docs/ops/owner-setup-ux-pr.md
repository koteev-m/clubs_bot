# Owner setup UX — Draft PR publication candidate

Task: `CLB-owner-setup-ux`, 2026-11-05. Repository: `koteev-m/clubs_bot`.
Branch: `codex/owner-setup-ux` in the existing registered `owner-setup-ux`
worktree. Base and freshly fetched `origin/main` are
`46db7930f951ff115426d66576731089d75ffc75`. Identity preflight passed.

The user authorizes exactly one commit, ordinary non-force push, one Draft PR
to `main`, and one bounded hosted CI snapshot for the published SHA. This
does not authorize merge, deployment, workflow dispatch or a new staging
investigation. The resulting commit and PR identity are returned in the
publication response and verified on GitHub.

## Incident qualification

The candidate fixes a **confirmed editor state-isolation defect**: the reusable
editor now owns a separate lifecycle per club/hall, ignores stale success and
failure responses, clears its private state on unmount and recreates image
geometry per plan URL. The base owner wrapper already keyed its editor by hall;
this fix is not evidence that unkeyed owner navigation caused the incident.

The original reason Main and Welcome displayed the same image on staging
remains **EVIDENCE_REQUIRED**. Equal saved image metadata and a stale rendering
are distinct cases. The candidate reports equal saved plan hashes but does not
repair, deduplicate, copy or overwrite existing images. Repeating the Main and
Welcome manual check after a separately authorized stage deployment is mandatory.

No staging request, server connection, database access or real-image operation
was performed during this publication task.

## Included changes and unchanged contracts

- Hall editor lifetimes, URL/ETag/draft/selection and pending read state are
  isolated by club/hall. Late responses cannot restore a previous hall's table
  list or trigger navigation from a stale error.
- Scoped table-form styling sets explicit foreground/background pairs, readable
  native options, placeholder/error/disabled/focus states. Single, range and
  arbitrary-number creation remain; occupied numbers name the known hall and
  remain unique across the club.
- Calendar labels and date previews explain the party date separately from
  actual opening/closing dates. Friday's party opens Saturday 00:00 and closes
  Saturday 12:00 with `startNextDay=true`, `nextDay=false`. Saving and the renamed
  “Сформировать ближайшие ночи” action retain existing version/idempotency,
  reconciliation, DST, exception and dependency protections.
- Four booking-rule explanations distinguish cutoff before closing, latest
  arrival after opening, HOLD during checkout and waiting after stated arrival.
  Advice to use HOLD 10 minutes does not replace saved/null values. Contradictory
  arrival/cutoff values warn before save without changing their semantics.
- Preview HOLD, UNKNOWN and UNAVAILABLE use human labels and concrete reasons.
  UNKNOWN remains fail-closed; no real HOLD, confirm or payment action is enabled.
- Backend production source, APIs, grants and booking runtime are unchanged.
  All 14 H2/PostgreSQL V067–V073 files match the base byte for byte. No migration
  edits, automatic data repair, club publication or guest/staff/payment cutover.
  PNG/JPEG remain the accepted upload formats; PDF support was not added.

## Candidate preservation and verification

The complete original candidate is preserved in
`/private/tmp/owner-setup-ux-publication-backup-20261105` as a binary Git patch,
all existing candidate file bytes and the original evidence manifest. Every
source, test and generated asset matches the original verified digest inventory.
The only changes made during publication preparation are this report, the
checkpoint entry and the new publication inventory.

[The original handoff](owner-setup-ux.md) and
[original frozen inventory](owner-setup-ux-evidence.json) remain historical
records of local preparation. Their original no-publication permission field
does not override the user's later authorization. The old inventory's checkpoint
hash identifies that earlier checkpoint; the new entry is recorded separately in
[the publication inventory](owner-setup-ux-pr-evidence.json).

Reused successful results, tied to unchanged source/test/artifact bytes:

| Check | Saved result |
|---|---|
| TypeScript / full ESLint, zero warnings | PASS |
| Full Vitest and final affected recheck | 289/289 plus 9/9; one added late-error case, 290 distinct cases |
| Packaged mobile Chromium/WebKit | 20/20 plus final affected 10/10 |
| Repository H2/PostgreSQL | 575 cases, 565 PASS and 10 existing skips |
| Final new hall scenario on H2 and PostgreSQL | 2/2 PASS |
| Fresh Ktor H2/PostgreSQL | 63/63 PASS |
| Domain calendar/DST | 7/7 PASS |
| Full ordinary JVM and coverage gate | 1,447 cases, one existing skip, no failures; gate PASS |
| Detekt and fresh baseline-aware ktlint | PASS; no baseline or rule changes |
| Dist validation / installDist | PASS; five installed React files match dist, no stale assets |

JaCoCo reports execution-data mismatch warnings for unchanged refund/cancel DTO
classes. Their coverage is not recharacterized by this PR. Physical Telegram
and iPhone checks remain manual; browser tests use synthetic host/API fixtures.

Focused review in this task checked the complete source/test diff, per-hall
request identity, cleanup and late errors, style scope, canonical-date arithmetic,
unchanged command bodies, null preservation and UNKNOWN fallback behavior.
No new blocking finding was identified. No independent reviewer is claimed.

Publication checks validate digest reuse, allowlisted changed paths, unchanged
backend production/grants/migrations, affected documentation links, staged
whitespace and the final secret scan. The final scan result is preserved in
`/private/tmp/owner-setup-ux-pr-secret-scan.log`; exact current file digests are
recorded before that scan. Hosted CI is read once after publication and is not
polled to completion.

## Manual checklist after separately authorized stage deployment

1. On iPhone, open Main, Welcome and an empty-plan hall. Check names, IDs,
   counts, independent images/markers; switch, refresh and reopen the Mini App.
   Recheck Main/Welcome even if local tests pass. A matching-hash notice is saved
   metadata evidence, not permission to replace real images.
2. In a disposable test club, verify single/range/list creation and a number
   occupied in another hall. Check labels, native options, errors, disabled
   controls and focus in both Telegram themes. Use distinct PNG/JPEG fixtures
   and replace only one test hall's image; verify the others are unaffected.
3. Set Friday → next-day opening 00:00 → closing 12:00 with closing-next-day
   unchecked. Verify the sentence/dates before save, save/reopen and form the
   nearest nights. The canonical night must retain Friday's party date.
4. Verify all four booking examples, unchanged saved/null HOLD values and the
   contradictory-cutoff warning. Inspect HOLD/UNKNOWN/UNAVAILABLE explanations;
   UNKNOWN must not become FREE and real booking/payment controls stay disabled.
