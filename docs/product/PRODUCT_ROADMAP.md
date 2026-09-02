# Первичный dependency-aware roadmap

Roadmap задаёт последовательность проверяемых outcomes, а не календарные сроки, capacity или story points. Он не означает, что рекомендации приняты. Любая production реализация выполняется отдельными задачами с code/tests/Gradle gates.

`DEC-026/D` принят и больше не является unresolved governance blocker. Initial eight disputed capabilities из [CONCEPT_CODE_GAP.md](CONCEPT_CODE_GAP.md) теперь имеют item-level classifications: семь `AMEND` и один `DEFER`. Classification не означает `AS_BUILT`, implementation start или автоматическое включение в navigation; accepted boundaries размещены только в будущих phases ниже.

## 1. Foundation already reusable

Следующие части имеют доказанный production/test foundation и не требуют переписывания «с нуля»: Ktor/DB bootstrap, webhook ingress queue, initData HMAC validation, RBAC/club-scope primitives, audit/redaction primitives, club/event persistence, layout/tables repositories, GuestList/invitation/check-in services, user/night visit uniqueness, deposit operation ledger, finance templates/shift freeze, support persistence/list/reply/delivery primitives, private `/start` minimal identity provisioning, music backend и deterministic analytics snapshot.

Reuse не означает keep-as-is: the secured DB HOLD branch is selected but its A3/React client contract is broken; query-string initData, incomplete role coverage, table-seating visit bypass, unwired availability/campaigns и canonical UI packaging должны быть разрешены до соответствующих slices. Private `/start` minimal identity provisioning is production-wired and tested; `/ask` still defensively verifies that identity.

## 2. Accepted first product slice

Статус: [`ACCEPTED_NOT_IMPLEMENTED`](slices/PRIVATE_SUPPORT_LOOP.md).

### [Private Support Loop](slices/PRIVATE_SUPPORT_LOOP.md)

`/start → idempotent minimal application-identity provisioning → private /ask → production-backed club selection → guest-selected support category → persisted ticket + initial message → minimal staff list/detail/thread/reply/take/resolve/close → truthful Telegram delivery гостю → RESOLVED / CLOSED`

#### Reusable foundation

- private bare Telegram `/start` and configured bot mention production path that ensures minimal application identity before welcome;
- `/ask` command, Telegram club-selection callback and support ticket creation path that defensively verifies the provisioned application user;
- support ticket/message persistence;
- generic RBAC/club-scope primitives plus the delivered exact-assignment support permission, bounded staff surface, accepted lifecycle and truthful persisted reply delivery;
- guest Telegram delivery primitive.

#### Completed bounded implementation

- Merged PR #479 at merge commit `20aced05258ab8972b455c59b8038e9a4361f34e` completes bounded `AS_BUILT` acceptance evidence `PSL-AC-01` through `PSL-AC-08`: private bare `/start`/configured mention provision one minimal identity keyed by Telegram user ID; sequential and controlled concurrent calls converge; failures are bounded; and a provisioned user reaches defensive private `/ask` verification.
- Production implementation: `app-bot/src/main/kotlin/com/example/bot/Application.kt:296-320`, `app-bot/src/main/kotlin/com/example/bot/di/BookingModules.kt:240-243`, `app-bot/src/main/kotlin/com/example/bot/telegram/TelegramGuestFallbackHandler.kt:47-128,184-210,306-320`, and `core-data/src/main/kotlin/com/example/bot/data/security/ExposedUserRepositories.kt:42-67`.
- Evidence includes Telegram handler coverage, H2 sequential/minimal persistence and field-preservation tests, controlled PostgreSQL concurrency/failure integration tests, and the CI quality-gate self-check correction in `scripts/selfcheck-quality-gates.sh`/`scripts/validate-payment-hardening.py`.
- Merged PR #481 (feature commit `f625a1a1645edda5d7b6b7ab31972b9fea7af6ba`, merge commit `249dedc0c511ea27308bbe57ebf4612c0a68ed09`) completes bounded `AS_BUILT` acceptance evidence `PSL-AC-09` and `PSL-AC-10`: every private `/ask` path requires explicit production-backed club selection, removing the active-booking bypass; then the guest explicitly chooses one of seven accepted categories using the existing enum/wire mapping, without AI classification or a domain migration.
- PR #481 also establishes the exact-current-bot prompt trust boundary: lazy cached `getMe` identity, rejection of another-bot/forwarded/business/unsupported-origin reply targets, canonical full-prompt reconstruction, and strict callback/context parsing (`app-bot/src/main/kotlin/com/example/bot/Application.kt:296-305`; `TelegramClient.kt:26-70`; `TelegramGuestFallbackHandler.kt:185-342,409-563`). Handler and TelegramClient tests cover literal wires, all seven resulting topics, `Long.MAX_VALUE` callback sizes, exact ticket arguments, provenance, marker-like club names and identity cache/concurrency/retry; the merged implementation also passed its full tests and quality gates.
- Merged PR #483 (production commit `f54426090b88f34963106533349cf6b81ad9bf32`, documentation commit `8d6578046e6c0923ca7f65fba15b1ea9c9658afe`, merge commit `09dfb37b9714dc3e4094278744e0b76baa8942d6`) completes bounded `AS_BUILT` evidence for `PSL-AC-11` and the ticket-creation/initial-message portion of `PSL-AC-36`: selected club, category and normalized non-blank question persist as one `NEW` ticket plus initial `GUEST` message in one transaction; H2/PostgreSQL tests prove creation rollback and V057 safety, including PostgreSQL fail-closed constraint-drift handling. `PersistenceFailure` is detail-free, cancellation rethrows, HTTP/Telegram failures stay bounded, legacy statuses remain readable, legacy staff mutations reject `NEW`, and Mini App vocabulary includes `new` / `Новое`.
- Merged PR #484 (production commit `6c18a4041ee431f0fa5a86db3d516d6b4034a867`, documentation commit `20a3a6f99f67ceb45fb875b7933bdd709af376ce`, merge commit `f96b32de6a5e8d7e371bf0d7d5dde3e176a345f8`) completes bounded `AS_BUILT` evidence for `PSL-AC-12`: authenticated application users list only their own tickets and open only their own complete persisted thread. Ownership comes from validated Mini App initData and server-side user resolution, not a client owner ID; the repository enforces owner-scoped list and `(ticket.id, ticket.user_id)` detail predicates. List order is `updatedAt DESC, ticket ID DESC`; thread order is `createdAt ASC, message ID ASC`; the public detail response is minimal. Foreign and missing reads are the same `404 / support_ticket_not_found`; H2, PostgreSQL Testcontainers and Ktor tests cover owner isolation, foreign preview/thread sentinels, deterministic ties, no-store/error behavior, generic failures and cancellation propagation. At that PR #484 boundary, the Mini App API added only owner-safe detail/thread types and `getMySupportTicket(ticketId)`; the later local pre-PR guest served-surface evidence is recorded below.
- Merged PR #485 (production commit `cfb77b3a9df3291038f0b2792d2185af12bf2aa3`, documentation commit `6b06cdea52a7ced43ddef5fd2cab177ff0e1190a`, merge commit `4146af8817a31d841df4ff51a4d2a4cb0929414c`) completes bounded `AS_BUILT` evidence for `PSL-AC-13` and the read/authorization/denial boundary of later staff acceptance. Paired PostgreSQL/H2 V058 migrations create `permissions` and `user_role_permissions`; a grant is tied to one `user_roles.id`, cascades with that assignment, rejects duplicates and has no default grant. The catalog provides `support.view`, `support.reply` and `support.status.manage`. `GET /api/support/staff/clubs`, permission-gated list/filter and scoped detail/thread provide deterministic minimal reads with non-enumerating foreign/missing detail.
- Merged PR #486 (production commit `6de082b93d6e9ada9a857b3e5d953f8d56581693`, documentation commit `45b435b3d2bf65128a64632c7c5fdb48c2edb11f`, merge commit `4a1bc5aac2f5b491ddc608f3fa02d375f8633eae`) completes bounded `AS_BUILT` evidence for `PSL-AC-14`, the take portion of `PSL-AC-15`, `PSL-AC-20`–`PSL-AC-22`, the mutation/denial portions of `PSL-AC-16`–`PSL-AC-19`, the take/reply portion of `PSL-AC-32`, and bounded mutation/audit rollback evidence for `PSL-AC-36`. The authoritative transaction locks the ticket and verifies exactly one acting-user `MANAGER`/`CLUB_ADMIN` CLUB assignment for the ticket club with the requested permission attached to that same `user_roles.id`; take requires `support.status.manage`, reply requires `support.reply`. Another assignment/user/club, `GLOBAL` scope or route precheck alone cannot grant authority. No accepted assignment with the required permission is `403 / support_ticket_forbidden`; permission elsewhere with foreign/missing ticket is identical `404 / support_ticket_not_found`; invalid lifecycle is `409 / invalid_state`; persistence/audit failure is generic `500 / internal_error`.
- Take moves only `NEW → IN_PROGRESS`, changes `lastAgentId`/`updatedAt` and inserts exactly one `SUPPORT_STATUS_CHANGE` audit; non-`NEW` stored states reject without writes. Reply persists exactly one `AGENT` message: first reply also changes `NEW → IN_PROGRESS` and creates one `SUPPORT_REPLY` plus one transition audit; `IN_PROGRESS` reply creates one reply audit and no transition audit; other stored states reject without writes. Ticket/message/audits share one transaction. Audit metadata is only `old_status`/`new_status` or `message_id`; body, attachments, previews, Telegram/initData/tokens and SQL/exception detail are excluded; forced audit failure rolls back every earlier mutation/audit. PostgreSQL evidence proves two concurrent first replies yield two messages/two reply audits but one transition audit, and concurrent take/first reply serializes with final `IN_PROGRESS` and no partial state.
- Merged PR #487 (production commit `63084ac39224359601f978f7a9bebf4d22f5fc3d`, documentation commit `bde4318b8f5896acd2e61642309dc107e8fd65d7`, merge commit `ce87a5a34fa7a077bcd9fa945901e72c13445822`) completes the remaining accepted lifecycle evidence: V059 PostgreSQL/H2 preserves legacy rows while allowing exactly `new`, `opened`, `in_progress`, `answered`, `resolved`, `closed` and rejecting `waiting`; PostgreSQL migration fails closed on expected-constraint drift. Confirmed `POST /api/support/tickets/{ticketId}/resolve` permits only `IN_PROGRESS → RESOLVED`, owner guest message resumes only `RESOLVED → IN_PROGRESS`, and body-independent `POST /api/support/tickets/{ticketId}/close` permits only `RESOLVED → CLOSED`. `CLOSED` rejects guest message, take, reply, resolve, close and generic status mutation; a guest can still create a separate ticket. Exact `support.status.manage` authority is rechecked under lock for resolve/close. Resolve emits one status audit; close emits one `SUPPORT_CLOSE` and one status audit; guest resume emits one status audit. Status metadata is only old/new value; audit failure rolls back all mutation/message/audit writes. PostgreSQL evidence proves resolve/close races and reply/resolve plus guest/close serialization; H2, Ktor and Mini App evidence covers migration, errors, rollback, confirmation and server-derived controls.
- Merged PR #488 (implementation commit `fa62ef835348768c05b7ddf8ba224a6a8edfa805`, documentation commit `a44d5de9d0c5f86af5e2813245637ed1b0d4e308`, merge commit `9cffa3e8361f42b9a3c469ceebbff4fb0d0f3fe7`) completes bounded `AS_BUILT` evidence for `PSL-AC-23`, `PSL-AC-24`, the delivery-result portion of `PSL-AC-30`, and the exact write/transaction boundary of `PSL-AC-36`. Paired PostgreSQL/H2 V060 migrations add a delivery row for each new persisted `AGENT` reply with `pending`, `sending`, `delivered`, `failed` or `unconfirmed`; legacy agent messages are not backfilled as delivered. The reply transaction atomically persists its lifecycle/message/audits and `PENDING` intent. A conditional post-commit claim permits exactly one synchronous Telegram attempt using persisted server-owned recipient, ticket, actor and reply data. `isOk == true` alone makes `delivered` with HTTP 200; recipient/client/rejection becomes bounded `failed` with `502 / support_delivery_failed`; ambiguous timeout/transport/cancellation becomes `unconfirmed` with `502 / support_delivery_unconfirmed`; terminal persistence/audit failure is `500 / internal_error` and never a delivery success. Terminal result plus one `SUPPORT_DELIVERY_RESULT` audit are atomic, metadata is allowlisted and body/attachment/Telegram/raw error data is excluded. There is no worker, queue/outbox processor, automatic/manual retry, retry endpoint or duplicate-send idempotency policy. H2/PostgreSQL/Ktor and Mini App tests cover migration, rollback, one-claim/one-attempt, result audit/privacy, cancellation and truthful presentation.
- Immutable test-only commit `e331c9991660f5d359aa22c9f73a9a4bcb7bd9af` completes bounded `AS_BUILT` process-restart E2E evidence for `PSL-AC-29`. It adds no production behavior, migration or runtime endpoint and has no PR, merge or documentation commit. One PostgreSQL Testcontainer database and one fake Telegram Bot API server are retained while production `io.ktor.server.netty.EngineMain`/`com.example.bot.ApplicationKt.module` runs in child JVM A, A is completely terminated, then child JVM B starts on a different HTTP port with a different OS/JVM PID; A is confirmed dead before B readiness. Real webhook `/start` provisions guest and staff identities without direct user insert; only the exact staff role/permission fixture uses JDBC. Real authenticated guest/staff APIs create the ticket, initial `GUEST` message and `AGENT` reply; confirmed fake delivery reaches `IN_PROGRESS`/`delivered` with reply/status/delivery-result audits. B's repeated `/start`, guest list/detail and staff clubs/list/detail APIs and fresh JDBC preserve exact identity/ticket/message/delivery/audit IDs, ownership and permission boundaries; public data exposes only `AGENT deliveryStatus=delivered`. The retained recorder sees exactly one authoritative-guest reply attempt with persisted text and no B startup/read replay. Bounded polling/deadlines, child cleanup and separate log redaction checks cover privacy and process/port stability; the focused E2E passed twice.
- In B's public thread the `GUEST` message has no delivery status; recipient, actor, failure code, internal delivery ID and audit fingerprint do not leak. The process-restart proof is not staging smoke and does not claim completion of the whole Private Support Loop.
- `POST /api/support/tickets/{ticketId}/status` remains deliberately disabled: after authentication, strict ID parsing, exact `support.status.manage` authorization and scoped ticket verification, every body returns `409 / invalid_state` without body parsing, `setStatus`, ticket mutation or audit. `/assign`, `/reply`, `/resolve` and `/close` remain permission-specific. The public reply result excludes `ownerUserId`; HTTP 200 now means persisted reply with confirmed Telegram `delivered` status. `/app?mode=support` uses server capabilities for take only on `NEW`, reply only on `NEW`/`IN_PROGRESS`, confirmed Resolve only on `IN_PROGRESS`, and Close only on `RESOLVED`; it prevents duplicate submit, reloads after mutations, says «Ответ доставлен» only for confirmed delivery, and refreshes with bounded non-success wording for failed, unconfirmed or generic delivery persistence outcomes. `403` clears/refreshes and `404` clears selection. There is no reopen, generic status selector, retry control, permission-management UI or canonical-shell redesign.
- This adds no financial identity, payment, ledger or Mini App purchase architecture; it is not the complete Private Support Loop.
- Local implementation commit `b5680a4c992f6f6f25e05e850eac97a0b473f0a4` is completed bounded pre-PR evidence, with no PR, merge or documentation SHA claimed. It serves `/app?mode=guest-support` without changing legacy `/app`/`/app/`, separate staff `/app?mode=support`, direct React-index denial or authority semantics. The served guest surface provides own-ticket list/detail/complete ordered thread, loading/empty/bounded-error/retry/refresh and back states, guest sender labels, a duplicate-protected 2,000-character continuation, authoritative detail/list reload, visible `RESOLVED → IN_PROGRESS` resumption and `CLOSED` read-only behavior. A successful persisted `/ask` may show server-built `Мои обращения`; configured `MINI_APP_URL` is retained only when valid/safe, keeps safe query/fragment content and contains exactly `mode=guest-support`, while an invalid URL suppresses the button without cancelling confirmation. Validated initData, server-side identity, owner predicates, no-store/detail-free errors, `403`/`404` clearing, stale/cross-ticket isolation and guest privacy remain enforced. No staff lifecycle/permission/retry/manual-reopen/SLA/priority/assignment/escalation, booking/payments/loyalty/music/broadcasts/AI or staff/admin/promoter/entry navigation is added. Tracked React dist is in the production Docker context; the shared fail-closed validator runs before `installDist` and in self-check, rejects unsafe/stale assets, and the browser smoke passed through available Chrome (lack of bundled local Chromium is only an environment limitation). Kotlin/frontend/browser/build/validator evidence supports this bounded correction.

#### Remaining first-slice execution work

- Repository release-state resilience is complete as bounded evidence at implementation commit `b86f69dfea8715c9eec24214230fc837cf9a74fb` (`feat(deploy): harden release state resilience`), accepted by independent review as `PASS_TO_COMMIT`. It adds durable migration/release authority and does not itself merge, push or deploy the candidate.
- Deployment-principal read-only status-channel implementation is complete as bounded repository work at local implementation commit `cfe7794df859a135c9825dcd251298ef920577ac` (`feat(deploy): add read-only release status channel`), with independent review `PASS_TO_COMMIT`. The manual-only channel has not been pushed, merged, observed in GitHub Actions or dispatched; it does not assert protected-environment `SSH_KNOWN_HOSTS` provisioning, trusted stage status, incident resolution or recovery authorization.
- The next integration sequence is:

  1. product evidence reconciliation;
  2. push the branch and create a Draft PR;
  3. wait for all checks;
  4. perform independent post-push workflow/security verification;
  5. merge only after required checks;
  6. complete post-merge cleanup;
  7. provision and verify `SSH_KNOWN_HOSTS`;
  8. obtain separate approval for one read-only status-workflow dispatch;
  9. reconcile trusted release status;
  10. resume candidate-start incident diagnosis;
  11. separately authorize the selected recovery or forward-fix path.

- Staging runtime remains behind the candidate and in a fail-closed incident state: it runs `df7685facb52a6e5731a520669dfa2c73f6ccf24` with old digest `sha256:236c11cad4015cd6817c04a9fea0a3155eab77eb72164ab6c554f0c870c55cb6`; public support surfaces serve the legacy bundle, schema permissions/delivery are unconfirmed, the candidate-start root cause is unresolved, no trusted status has been received, and staging smoke is still `NOT_RUN`. Recovery is not authorized.
- The accepted staging smoke remains the only first-slice execution boundary. It must include the already implemented truthful-delivery and guest-surface behavior; it is validation work, not an automatic new feature. A production correction is required only if that smoke exposes a defect, which then becomes a separate bounded task.
- Preserve the completed `DEC-025` `NEW → IN_PROGRESS → RESOLVED → CLOSED` graph, its confirmed resolve and terminal-state enforcement; do not use the disabled generic status route as a bypass.

#### Explicitly excluded from first slice

- calendar truth и current/next operational night;
- operational-night UI;
- rich club detail и canonical guest home redesign;
- booking/HOLD, payments/deposits, Night Pass и check-in;
- loyalty, music, broadcasts, channel posts и exports;
- registration/profile enrichment beyond the minimal support identity record;
- iBota, Guest Mode и все AI functions;
- capabilities accepted by `DEC-028`–`DEC-035`.

Canonical acceptance boundary: полная нумерованная [acceptance matrix](slices/PRIVATE_SUPPORT_LOOP.md#acceptance-matrix) в slice specification. Она расширяет прежние 13 пунктов accepted category, lifecycle, permission, audit и delivery clauses и не меняет их смысл.

### Previous 13-item crosswalk

| Previous item | Preserved meaning | Canonical acceptance IDs | Preservation result |
|---:|---|---|---|
| 1 | Fresh Telegram guest вызывает `/start` | `PSL-AC-01` | `PASS` |
| 2 | Первый private bare `/start` создаёт/обеспечивает минимальную application identity | `PSL-AC-02`, `PSL-AC-07` | `PASS` |
| 3 | Повторный последовательный `/start` не создаёт вторую row и возвращает ту же identity | `PSL-AC-03`, `PSL-AC-05` | `PASS` |
| 4 | Concurrent/retry обработка `/start` сходится к одной identity без raw unique/SQL error | `PSL-AC-04`–`PSL-AC-06` | `PASS` |
| 5 | Гость вызывает private `/ask` | `PSL-AC-08` | `PASS` |
| 6 | Гость выбирает клуб из production-backed list | `PSL-AC-09` | `PASS` |
| 7 | Вопрос создаёт persisted ticket и initial message | `PSL-AC-11` | `PASS` |
| 8 | Разрешённый staff видит ticket в minimal served inbox | `PSL-AC-13`, `PSL-AC-16`, `PSL-AC-32` | `PASS` |
| 9 | Staff открывает detail и отвечает | `PSL-AC-13`, `PSL-AC-14`, `PSL-AC-16`, `PSL-AC-21` | `PASS` |
| 10 | Historical canonical mapping: persisted reply plus separate guest-delivery acceptance | `PSL-AC-21`–`PSL-AC-24` | `PASS` |
| 11 | Unauthorized staff не видит ticket и не отвечает | `PSL-AC-17`–`PSL-AC-19` | `PASS` |
| 12 | Ticket, initial message и reply переживают restart | `PSL-AC-29` | `PASS` |
| 13 | Staging smoke начинается с unknown user и проверяет sequential/concurrent provisioning и E2E без ручной подмены | [Staging smoke](slices/PRIVATE_SUPPORT_LOOP.md#staging-smoke), `PSL-AC-01`–`PSL-AC-35` | `PASS` |

`PASS` in this crosswalk means that the former item keeps its canonical acceptance mapping; it is not an implementation status. `PSL-AC-01`–`PSL-AC-32` and `PSL-AC-36` have bounded `AS_BUILT` evidence; `PSL-AC-33`–`PSL-AC-35` retain their existing documentary/static/product classifications.

`PSL-AC-29` is `AS_BUILT`: the real two-production-JVM proof preserves exact durable identities, ticket/owner/status, initial/reply IDs and ordered complete thread, delivery result/audit correlation, guest ownership and staff permission behavior without a second Telegram send. `PSL-AC-30` is `AS_BUILT`: staff reply/status/close and delivery-result audits have required fields and do not duplicate the message body. `PSL-AC-36` is `AS_BUILT`: provisioning, ticket/initial-message, guest/staff mutation-audit, delivery-intent and delivery-result-audit failure boundaries return bounded non-success, preserve their transactional state rules and do not leak raw DB detail.

Accepted product authorities: `DEC-003`, `DEC-004`, `DEC-005`, `DEC-007`, `DEC-017`, `DEC-025`, `DEC-036`. Они не являются unresolved blockers первого slice.

The only remaining first-slice execution prerequisite is successful accepted staging smoke. Truthful delivery and process-restart proof are complete; deliberate absence of worker, queue/outbox processor and retry architecture is not a gap in this bounded accepted outcome. These choices preserve the accepted contract and do not change the complete-slice status `ACCEPTED_NOT_IMPLEMENTED` by themselves.

## 3. Phases

### Phase 0 — Product contract and architecture choices

- **Category:** Product rework / governance.
- **User outcome:** команда знает, какой продукт и какой runtime path строится; silent parallel implementations не получают новых функций.
- **Included IDs:** `PROD-003`, `RBAC-001`, `RBAC-002`, `UX-001`, `CAL-001`, `PASS-001`, `FIN-002`, `SUP-002`.
- **Dependencies:** review [OPEN_DECISIONS.md](OPEN_DECISIONS.md).
- **Acceptance boundary:** accepted launch model, first-release role mapping, canonical UI strategy, support workflow, repository/source precedence и first product slice записаны явно; public names, operational-night model и booking implementation direction остаются отдельными pending decisions.
- **Excluded scope:** production changes.
- **Staging smoke:** не применяется; review проверяет непротиворечивую decision log и updated traceability.
- **Governance activity:** `classify repository/source conflicts individually`.
- **Activity status:** initial eight item-level decisions accepted; остальные conflicts продолжают требовать собственных решений. Это governance tracking, а не product implementation.
- **Decision status:** `DEC-003`, `DEC-004`, `DEC-005`, `DEC-007`, `DEC-017` и `DEC-025` приняты и больше не являются unresolved blockers Private Support Loop. Pending work этой foundation phase сохраняется для `DEC-001`, `DEC-002`, `DEC-006`, `DEC-008`–`DEC-012`, `DEC-018`; принятый `DEC-026/D` больше не является prerequisite.

### Phase 1 — Private support loop

- **Category:** Reuse with minimal new staff surface.
- **Status:** [`ACCEPTED_NOT_IMPLEMENTED`](slices/PRIVATE_SUPPORT_LOOP.md).
- **User outcome:** fresh guest выбирает клуб и category в private Telegram flow, разрешённый staff отвечает из minimal served inbox, ответ наблюдаемо приходит гостю, а ticket проходит accepted lifecycle.
- **Included IDs:** `PROD-001`, `NET-001`, `RBAC-006`, `SUP-001`, `SUP-002`, `SUP-004`, `SUP-005`, `COM-007`, `SEC-002`.
- **Dependencies:** completed private `/start` minimal identity provisioning, explicit production-backed club/category selection, `PSL-AC-11`/`PSL-AC-36` transaction-failure evidence, owner-safe `PSL-AC-12` guest reads, merged PR #485 exact-permission staff read boundary, merged PR #486 take/reply evidence, merged PR #487 accepted resolve/guest-resume/close lifecycle/status/close audit evidence, merged PR #488 truthful Telegram delivery/delivery-result audit/failure-observability evidence, test-only commit `e331c9991660f5d359aa22c9f73a9a4bcb7bd9af` two-process restart evidence, and local pre-PR `b5680a4c992f6f6f25e05e850eac97a0b473f0a4` served guest list/detail/thread/continuation plus safe Telegram launch and fail-closed packaging evidence.
- **Remaining dependencies:** accepted staging smoke only.
- **Acceptance boundary:** canonical [Private Support Loop acceptance matrix](slices/PRIVATE_SUPPORT_LOOP.md#acceptance-matrix), включая complete previous-item crosswalk выше.
- **Excluded scope:** exact [slice exclusions](slices/PRIVATE_SUPPORT_LOOP.md#explicit-exclusions); `DEC-028`–`DEC-035` capabilities не входят.
- **Staging smoke:** exact bounded [slice smoke](slices/PRIVATE_SUPPORT_LOOP.md#staging-smoke): never-seen Telegram user; first `/start`; sequential repeated `/start`; controlled concurrent/retry `/start`; only then `/ask`; category/ticket/thread; allowed and denied roles/scopes/permissions; lifecycle; truthful delivery; restart; safe failure observability.
- **Accepted authorities:** `DEC-003`, `DEC-004`, `DEC-005`, `DEC-007`, `DEC-017`, `DEC-025`, `DEC-036`; product blockers для этого bounded slice сняты, implementation prerequisites остаются.

### Phase 2 — Deterministic booking core

- **Category:** Product rework.
- **User outcome:** гость выбирает доступный стол и получает одну durable confirmed booking без payment ambiguity.
- **Included IDs:** `CAL-001`–`CAL-005`, `CAT-001`–`CAT-003`, `BKG-001`–`BKG-007`, `HOLD-001`–`HOLD-004`, `TOPS-005`, `RBAC-004`.
- **Dependencies:** first deliver the calendar adapter/API and minimum club/night/table navigation as a bounded prerequisite within this phase; retain or explicitly replace the currently selected DB booking route; reconcile A3/React DTO; one-active-HOLD invariant; lifecycle/cancellation policy; deposit display policy.
- **Acceptance boundary:** one user/one active HOLD; atomic table lock; defined TTL; confirm/cancel/no-show/manager extension; restart durability; one consistent DTO; shadowed A3 registration removed or intentionally reworked after decision.
- **Excluded scope:** online payment unless explicitly selected; iBota; Night Pass beyond a temporary internal booking reference.
- **Staging smoke:** two users race one table; one user attempts two holds; TTL expiry; repeat idempotency key; confirm; cancel; manager extension; restart; cutoff boundary across TZ/overnight.
- **Decision prerequisites:** `DEC-008`, `DEC-009`, `DEC-018`.

### Phase 3 — Unified Night Pass, GuestList and entrance

- **Category:** Product rework + new identity consolidation.
- **User outcome:** promoter adds/invites guests; each guest sees one pass; Host scans/searches once; attendance and stamp are deduplicated.
- **Included IDs:** `GL-001`–`GL-003`, `GL-006`, `PASS-001`–`PASS-004`, `CHK-001`–`CHK-003`, `CHK-005`–`CHK-007`, `LOY-001`, `SEC-004`–`SEC-006`, `UX-002`.
- **Dependencies:** canonical operational night; canonical user identity; pass record/rotation; consolidate check-in routes; staff role mapping; eliminate or gate the `TABLE_DEPOSIT` visit creation path until accepted entrance outcome.
- **Acceptance boundary:** names and invitations resolve to one user/night pass; repeat/alternate-source scans return existing outcome; search and accepted arrival/denied taxonomy work; visit is created once only after accepted entrance check-in; table seating before entrance cannot create stamp/progress; no second QR is displayed.
- **Excluded scope:** AI list import, raffle, full offline sync beyond defined manual journal.
- **Staging smoke:** bulk names; single entry; internal/external invite; booking+list same user; rotating/expired/forwarded token; repeat scans; ARRIVED/LATE/DENIED; table-seat-before-scan produces no visit/stamp; scan-after-table creates one visit; manual search and recovery journal.
- **Decision prerequisites:** `DEC-004`–`DEC-006`, `DEC-010`, `DEC-011`, `DEC-018`, `DEC-020`, `DEC-021`.

### Phase 4 — Table operations and financial close

- **Category:** Product rework.
- **User outcome:** staff seats/frees tables with semantic money operations; finance owner reconciles and freezes the night safely.
- **Included IDs:** `TOPS-001`, `TOPS-002`, `TOPS-004`–`TOPS-006`, `DEP-001`–`DEP-006`, `FIN-001`–`FIN-008`, `UX-003`, `OPS-001`.
- **Dependencies:** canonical night/pass/roles; semantic deposit ledger endpoints; shift lifecycle; audited correction design.
- **Acceptance boundary:** deposit/bill/club modes; INITIAL/TOPUP/SECURITY operations; allocations; stop-sales/Undo; auto-open; finance-only close; reconciliation; freeze; separately authorized correction.
- **Excluded scope:** AI data entry, network analytics, advanced loyalty.
- **Staging smoke:** seat with/without pass; every money operation and retry; allocation totals; free/no-show; stop/Undo; close mismatch; post-close rejection; super correction with reason/audit.
- **Decision prerequisites:** `DEC-004`, `DEC-005`, `DEC-008`, `DEC-012`.

### Phase 5 — Guest retention and content

- **Category:** Reuse with product completion.
- **User outcome:** confirmed attendance produces understandable progress; guest can consume moderated music/content.
- **Included IDs:** `LOY-001`–`LOY-007`, `MUS-001`–`MUS-007`, remaining `CAT-002`, `UX-001` sections.
- **Dependencies:** unified entrance-first check-in with table-seating bypass closed; canonical guest navigation; DJ/moderation/payment decisions; accepted mystery and playlists/favourites boundaries применяются только через отдельные future-phase placements ниже.
- **Acceptance boundary:** first sub-slice may be stamps/early/badges/My Nights; published music may be read-only. Raffles/table loyalty/donations enter only with separate accepted scope.
- **Excluded scope:** AI assistant, paid music support unless payment decision covers it.
- **Staging smoke:** duplicate check-in produces one stamp; early threshold override; badge fingerprint retry; Russian names; published/unpublished file visibility; like/vote idempotency.
- **Decision prerequisites:** `DEC-013`, `DEC-014`, `DEC-022`, payment portion of `DEC-009`.

### Phase 6 — Communications and role analytics

- **Category:** New implementation on reusable repositories.
- **User outcome:** affected users receive controlled notifications; each role sees its permitted operational summary.
- **Included IDs:** `COM-001`–`COM-005`, `COM-007`, `ANL-001`–`ANL-004`, `CAL-006`, `SEC-004`/`SEC-005`.
- **Dependencies:** subscription/consent model; canonical roles; operational outcomes; wire one campaign/scheduler path.
- **Acceptance boundary:** calendar and lifecycle segments; subscription/quiet-hour/frequency enforcement; preview/confirmation; promoter/manager/finance/Owner views with no cross-scope leakage.
- **Excluded scope:** AI copy/analytics, paid broadcast until platform/cost decision.
- **Staging smoke:** segmentation fixtures; unsubscribed/quiet-hours suppression; rate limiting/retry; calendar cancellation audience; role/club access denial; snapshot caveats.
- **Decision prerequisites:** `DEC-004`–`DEC-006`; paid mode owner decision if included.

### Phase 7 — No-code club onboarding and durable procedures

- **Category:** New implementation + consolidation.
- **User outcome:** Owner configures another club без изменения кода; staff follows durable role procedures.
- **Included IDs:** `ONB-001`–`ONB-008`, `OPS-001`, `OPS-002`, `OPS-004`, `DEG-001`–`DEG-003`.
- **Dependencies:** all canonical config models from prior phases; audited config mutations; versioned validation.
- **Acceptance boundary:** one master creates a usable club/profile/calendar/hall/tables/rules/finance/modules/personnel; configuration audit exists; degraded manual journal can reconcile.
- **Excluded scope:** AI-filled wizard/instructions.
- **Staging smoke:** create second club from empty config; overnight holiday; upload/activate plan; book/scan/seat/close; role isolation; rollback invalid config; Mini App/scanner degraded drills.
- **Decision prerequisites:** `DEC-003`–`DEC-006`, `DEC-008`, `DEC-023`; accepted templates/cloning boundary применяется только через onboarding/network-scaling placement ниже.

### Phase 8 — iBota safe read/draft layer

- **Category:** New implementation.
- **User outcome:** selected roles ask questions and receive grounded read-only answers or prefilled drafts; no AI mutation bypasses normal services.
- **Included IDs:** cross-cutting `PROD-002`, `RBAC-003`, `IBCHAT-001`–`IBCHAT-003`, `IBAPP-001`–`IBAPP-003`, `AIFORM-001`–`AIFORM-004`, `AIAN-001`–`AIAN-003`, `AISAFE-001`–`AISAFE-005`; domain assistants `CAL-007`, `CAL-008`, `CAT-004`, `BKG-008`, `BKG-009`, `UX-004`, `UX-005`, `GL-004`, `GL-005`, `GL-007`, `CHK-004`, `TOPS-003`, `TOPS-007`, `DEP-007`, `FIN-009`, `LOY-008`, `SUP-003`, `SUP-006`, `MUS-008`, `COM-006`, `ANL-005`, `ANL-006`, `OPS-003`, `OPS-005`, `ONB-009`, `DEG-004`.
- **Dependencies:** stable deterministic services/forms; identity/RBAC; action registry; grounding/audit/redaction; explicit unresolved `AIAN-003` grounding/explanation policy under `DEC-027`; evaluation set.
- **Acceptance boundary:** read-only assistant first; any draft displays fields/uncertainty; confirmation commits through existing service with idempotency/audit; no PII cross-scope; accuracy is measured, not promised.
- **Excluded scope:** Guest Mode, bot-to-bot, business connection until separate phase.
- **Staging smoke:** role/scope denial; prompt-injection/data-leak fixtures; ambiguous form; amount mismatch; expired proposal; duplicate confirmation; closed shift; broadcast preview; unsupported request.
- **Decision prerequisites:** `DEC-002`, `DEC-016`, `DEC-020`, `DEC-021`, `DEC-024`, `DEC-027` and explicit AI data/provider policy.

### Phase 9 — Deferred advanced Telegram modes

- **Category:** Deferred advanced modules.
- **User outcome:** iBota may be invoked safely outside its private chat; optional agents/business accounts operate within strict boundaries.
- **Included IDs:** `GM-001`–`GM-003`, `BTB-001`–`BTB-003`, `BUS-001`–`BUS-003`.
- **Dependencies:** Phase 8 safety/grounding; current official platform verification; public-context privacy; loop controls; business principal mapping.
- **Acceptance boundary:** Guest Mode begins read-only with private handoff; bot-to-bot has dedup/rate/depth/timeout; business mode has explicit policy/templates and audit.
- **Excluded scope:** any autonomous financial/role/broadcast action.
- **Staging smoke:** one guest response; missing history/participant assumptions; three-bot mention handling; malicious loop; business connect/disconnect/right changes; PII redaction and private handoff.
- **Decision prerequisites:** `DEC-015`, explicit bot-to-bot/business enablement decisions.

## 4. Accepted decision placement in future phases

Все placements ниже являются sequencing constraints для будущей работы. Они не меняют status или содержимое accepted Private Support Loop, не означают начало implementation и не объявляют current code соответствующим accepted contract.

### Booking lifecycle phase

- **Included decision:** `DEC-028` — reminder «скоро слетит» (`AMEND`).
- **Prerequisites:** canonical booking/HOLD lifecycle, включая различие HOLD expiry и arrival-retention completion; согласованные lifecycle notifications и anti-spam policy.
- **Acceptance boundary:** сохраняется reminder перед canonical expiry; точный trigger остаётся blocked и не выбирается, пока lifecycle не определит HOLD, arrival retention или оба события вместе с timing, recipients, channel, repeat policy and anti-spam.
- **Excluded scope:** любой преждевременно выбранный trigger, current-code readiness claim и navigation entry до принятия lifecycle.
- **First-slice inclusion:** none; capability не входит в первый product slice.

### Table operations / finance configuration phase

- **Included decisions:** `DEC-029` — staff-only walk-in seating (`AMEND`); `DEC-031` — richer allocation categories (`AMEND`).
- **Prerequisites:** canonical operational night, staff permission matrix, free-table/stop-sales rules, entrance/check-in ordering, financial ledger, table/deposit configuration and audited correction design.
- **Acceptance boundary:** walk-in creates a table session without fictitious booking, uses an explicit deposit/bill/club mode, confirmation/audit and ledger, and never creates check-in/stamp/loyalty progress; allocations use club-configured canonical categories, exact totals, historical snapshots and append-only correcting operations.
- **Excluded scope:** guest walk-in section, check-in bypass, free-text allocation categories, merging allocation categories with financial-shift revenue articles, exposing internal allocations to guests, or selecting the initial category catalogue here.
- **First-slice inclusion:** none; neither capability входит в первый product slice.

### Loyalty phase

- **Included decision:** `DEC-030` — mystery-upgrade (`DEFER`).
- **Prerequisites:** canonical Night Pass/check-in, loyalty ledger, reward catalogue, anti-fraud and audit; a future revisit must separately define upgrade subject, eligibility, probability/selection, budget owner, validity, staff confirmation and abuse prevention.
- **Acceptance boundary:** future hypothesis is preserved only after all prerequisites and a separate revisit.
- **Excluded scope:** current target promise, MVP, navigation, implementation requirement or inference from existing `mysteryEligible`-like fragments; `DEFER` is not deletion/rejection.
- **First-slice inclusion:** none; capability исключена из первого product slice.

### Music phase

- **Included decision:** `DEC-032` — private favourites and curated playlists (`AMEND`).
- **Prerequisites and ordered sequence:** (1) read-only published music catalogue → (2) private favourites → (3) accepted DJ role and authoring contract, which still requires the corresponding explicit accepted decision → (4) curated playlists. Each step follows the preceding step; moderation, access policy and content-rights enforcement apply throughout.
- **Acceptance boundary:** favourites are private bookmarks for published tracks/sets with only aggregated statistics; curated playlists are authored by authorized DJ/admin and are associated with club, event or operational night.
- **Excluded scope:** public user-created playlists, collaborative editing, social catalogue, disclosure of user lists, and importing battles/stems or all current likes/playlists as one accepted contract.
- **First-slice inclusion:** none; no target navigation before the music phase.

### Communications phase

- **Included decision:** `DEC-033` — channel posts (`AMEND`).
- **Prerequisites:** pre-connected allowlisted club/network channels, separate communications permission, content safety/redaction, preview/explicit confirmation and audit of actor/target/confirmed content/result.
- **Acceptance boundary:** iBota may draft but never self-publish; publishing, edit and delete are distinct confirmed actions, and channel posts remain separate from personal notifications and segmented broadcasts.
- **Excluded scope:** authority from arbitrary text channel/chat IDs, PII or staff-only content, and automatic scheduling.
- **First-slice inclusion:** none; capability appears only in this future communications phase.

### Analytics/communications phase

- **Included decision:** `DEC-034` — on-demand exports and scheduled auto-reports (`AMEND`).
- **Prerequisites:** canonical role/scoped reports and metrics; frozen financial reporting and explicit non-final operational semantics; permissions/privacy/audit; accepted communications delivery before scheduling.
- **Acceptance boundary:** authorized on-demand export with fixed filters/period/scope/timezone and versioned metadata is delivered before scheduled reports; scheduled delivery requires allowlisted recipients/channels, test delivery, explicit enablement, result log, bounded retry and deduplication.
- **Excluded scope:** choosing CSV/XLSX/PDF formats in this roadmap, unpermissioned PII, schedule before canonical metrics/delivery contract, or AI narrative before a separately accepted AI-grounding decision; AI text never replaces source numbers.
- **First-slice inclusion:** none; neither capability входит в первый product slice or target navigation before analytics/communications phase.

### Onboarding/network-scaling phase

- **Included decision:** `DEC-035` — versioned configuration templates and create from existing club (`AMEND`).
- **Prerequisites:** canonical allowlisted club configuration categories; creating and publishing a versioned configuration template is authorized only for Owner or an authorized GLOBAL role; independent-ID draft creation, validation, preview, explicit confirmation, audit and content-rights checks. `DEC-035` does not define authority for create-from-existing; it requires a separate accepted permission policy before implementation.
- **Acceptance boundary:** a versioned template or explicitly selected snapshot categories create an independent new club draft; template changes do not mutate existing clubs and create-from-existing never creates a live link.
- **Excluded scope:** guest navigation; copying tokens, secrets, credentials/keys, staff/roles, guests/PII, bookings/HOLD/GuestList/Night Pass/visits/table sessions, financial operations/shifts, audit/idempotency/runtime incident/delivery history; content/media without explicit rights-checked opt-in.
- **First-slice inclusion:** none; capability не входит в первый product slice.

## 5. Sequencing guardrails

- Не начинать AI mutation до deterministic service acceptance и common confirmation contract.
- Не строить loyalty, finance или analytics поверх нескольких definitions of operational night.
- Не добавлять новые features одновременно в static и React Mini App; сначала выбрать canonical packaging.
- Не трактовать duplicate HOLD routes как недетерминированную runtime ambiguity: secured DB branch уже выбирается детерминированно; перед расширением согласовать DTO/owner implementation и удалить либо намеренно адаптировать shadowed A3 path.
- Не включать disputed additions из прежнего `AGENTS.md` snapshot без собственной item-level classification; accepted initial eight реализуются только в своих future-phase boundaries и не считаются соответствующими current code автоматически.
- Не включать campaigns, Guest Mode, paid broadcasts, bot-to-bot или business updates только потому, что код/platform primitive существует.
- Каждая phase заканчивается end-to-end staging smoke с persistence и authorization, а не только render/UI demo.
