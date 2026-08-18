# Private Support Loop

## Status

- `ACCEPTED_NOT_IMPLEMENTED`
- Accepted by: user
- Accepted at: 2026-08-18

Acceptance фиксирует target product boundary, но не является `AS_BUILT`. Production code, tests и deployment этим документом не изменяются.

## Authority

- [DEC-003 — pilot launch model](../OPEN_DECISIONS.md#dec-003--один-клуб-или-сеть-в-mvp)
- [DEC-004 — role set первого usable release](../OPEN_DECISIONS.md#dec-004--mvp-role-set)
- [DEC-005 — role mapping and RBAC boundaries](../OPEN_DECISIONS.md#dec-005--различия-host-entrance-manager-floor-manager-club-manager-и-admin)
- [DEC-007 — canonical guest home/navigation](../OPEN_DECISIONS.md#dec-007--canonical-guest-homenavigation)
- [DEC-017 — canonical Mini App implementation strategy](../OPEN_DECISIONS.md#dec-017--retainreplace-current-mini-app-implementations)
- [DEC-025 — support taxonomy, RBAC and lifecycle](../OPEN_DECISIONS.md#dec-025--support-taxonomy-and-lifecycle)
- [DEC-036 — first product slice: Private Support Loop](../OPEN_DECISIONS.md#dec-036--first-product-slice-private-support-loop)

## Purpose

Fresh Telegram guest без предварительной регистрации выбирает реальный клуб и категорию, отправляет private question, а разрешённый staff этого клуба видит durable thread, отвечает и доводит ticket до явного `RESOLVED`/`CLOSED` с наблюдаемой Telegram delivery и audit trail.

## Pilot boundary

- Slice запускается операционно для одного реального pilot club.
- Pilot club выбирается configuration/production data.
- Multi-club data model, `clubId` и `CLUB/GLOBAL scope` сохраняются.
- Pilot club не hardcode-ится в architecture, code или flow.
- Network analytics, полноценный multi-club onboarding и межклубный UX не входят в slice.

## Actors and scopes

| Actor | System role | Scope | Allowed slice capabilities | Explicitly denied |
|---|---|---|---|---|
| Гость | `GUEST` | Own identity; выбранный club context | Создать ticket; видеть и дополнять только собственные tickets/messages | Чужие tickets/messages; staff inbox; staff reply/status actions |
| Host / Менеджер входа | `ENTRY_MANAGER` | CLUB | Ни одна staff support capability | Inbox, view, reply и status manage |
| Менеджер зала / Менеджер клуба | `MANAGER` | Matching CLUB only | Support view, support reply и support status manage — каждая capability только при отдельном explicit permission | Foreign-club data/actions; любая capability без соответствующего permission |
| Админ клуба | `CLUB_ADMIN` | Matching CLUB only | Support view, support reply и support status manage — каждая capability только при отдельном explicit permission | Foreign-club data/actions; любая capability без соответствующего permission |
| Владелец | `OWNER` | GLOBAL в architecture; один configured club в pilot release | Только отдельно предоставленный owner oversight, если он будет принят и выражен explicit permission | Operational reply/status по одному названию роли; automatic support grant |

Названия concrete permission constants здесь не принимаются. Семантические permissions разделены на `support view`, `support reply` и `support status manage`; роль или scope сами по себе не заменяют permission.

## Visible flow

`/start`
→ idempotent provisioning минимальной application identity
→ private `/ask`
→ club selection из production data
→ support category selection
→ persisted ticket
→ persisted initial message
→ minimal staff inbox
→ ticket detail
→ staff reply
→ Telegram delivery гостю
→ `RESOLVED / CLOSED` по `DEC-025`.

Первый slice не зависит от redesign canonical Mini App; minimal staff inbox может быть отдельным bounded surface.

## Minimal identity provisioning

Current production fact: bare `/start` отвечает без application user lookup, но `/ask` вызывает read-only lookup и отклоняет неизвестный Telegram user. Production `UserRepository` writer отсутствует, а найденный legacy `ensureUser` находится за disabled-by-default legacy bootstrap. Bounded evidence: [AS_BUILT §4.1](../AS_BUILT.md#41-identity-boundary-for-the-first-support-slice) и [`SUP-005` gap](../CONCEPT_CODE_GAP.md#18-loyalty-and-support).

Target contract:

- Fresh Telegram user проходит slice без предварительной регистрации.
- Provisioning минимален и не является full registration/profile flow.
- Logical identity keyed by `telegram_user_id`.
- Private bare `/start` является canonical provisioning trigger; `/ask` не заменяет эту accepted boundary.
- Первый `/start` создаёт или обеспечивает одну application identity.
- Последовательный повтор `/start` возвращает ту же logical identity и не создаёт вторую row.
- Конкурентная/retry обработка `/start` для одного `telegram_user_id` сходится к той же logical identity.
- В БД остаётся ровно одна user row для одного `telegram_user_id`.
- Caller не получает необработанный unique-constraint или SQL error.
- Username/display name используются только когда они переданы и нужны.
- Phone, contact enrichment и rich profile не являются prerequisite.
- Concrete concurrency mechanism этим решением не выбран.
- Provisioning failure возвращает bounded non-success outcome без raw unique/SQL/internal details; exact UI presentation, copy, button и retry affordance остаются implementation design.
- `/ask` может fail closed или defensively verify application identity, но не является alternative primary provisioning trigger.

## Support categories

Гость явно выбирает одну из семи категорий:

1. Адрес / как добраться
2. Правила / дресс-код
3. Списки / вход
4. Брони / депозит
5. Потерял вещь
6. Жалоба / сервис
7. Другое

Target code identifiers этим решением не принимаются. Current code vocabulary частично отличается, а Telegram `/ask` создаёт `OTHER` без category-selection step (`core-domain/src/main/kotlin/com/example/bot/support/SupportModels.kt:6-18`; `app-bot/src/main/kotlin/com/example/bot/telegram/TelegramGuestFallbackHandler.kt:219-249`), поэтому mapping и migration остаются implementation work. AI classification отсутствует в slice.

## Lifecycle

Active slice states:

- `NEW`
- `IN_PROGRESS`
- `RESOLVED`
- `CLOSED`

`WAITING` сохраняется только как deferred target state и не входит в slice.

| From | Trigger | To | Actor | Confirmation | Audit |
|---|---|---|---|---|---|
| — | Guest создаёт persisted ticket с initial message | `NEW` | Guest | Явная отправка выбранной category и message | Создание ticket не добавлено `DEC-025` в обязательный audit set |
| `NEW` | Explicit «Взять в работу» | `IN_PROGRESS` | `MANAGER` или `CLUB_ADMIN` с matching CLUB и support status manage | Explicit action | Status change: actor, club, ticket, old/new status |
| `NEW` | Первый staff reply | `IN_PROGRESS` | `MANAGER` или `CLUB_ADMIN` с matching CLUB и support reply | Explicit reply action | Staff reply и status change; message body только в thread |
| `IN_PROGRESS` | Staff reply | `IN_PROGRESS` | `MANAGER` или `CLUB_ADMIN` с matching CLUB и support reply | Explicit reply action | Staff reply; message body только в thread |
| `IN_PROGRESS` | Explicit resolve после ответа | `RESOLVED` | `MANAGER` или `CLUB_ADMIN` с matching CLUB и support status manage | Separate explicit confirmed action | Status change: actor, club, ticket, old/new status |
| `RESOLVED` | Новое guest message | `IN_PROGRESS` | Guest — владелец ticket | Automatic state transition from accepted guest action | Status change: actor, club, ticket, old/new status |
| `RESOLVED` | Explicit close | `CLOSED` | `MANAGER` или `CLUB_ADMIN` с matching CLUB и support status manage | Не требуется accepted product contract; UI confirmation возможен только как отдельное implementation proposal | Close/status change: actor, club, ticket, old/new status |
| `CLOSED` | Message, status change или manual reopen на том же ticket | — (denied) | Любой actor | Не применяется | Denial audit policy этим решением не расширяется |

Staff reply сам по себе никогда не устанавливает `RESOLVED`. `CLOSED` terminal в первом release; manual reopen отсутствует. Для нового вопроса после `CLOSED` создаётся новый ticket. Других переходов этот contract не разрешает.

## Staff bounded surface

Required minimum:

- ticket list;
- club-scoped filtering;
- ticket detail;
- thread;
- reply action;
- take-in-work action;
- resolve action;
- close action;
- permission denial.

Surface не является полным Mini App shell и не открывает excluded capabilities. Backend primitive без served list/detail/thread/actions не считается complete product surface.

## Persistence contract

### Accepted product contract

- Ticket сохраняется в DB.
- Initial guest message сохраняется в DB.
- Последующие guest messages и staff replies сохраняются в thread.
- Ticket/thread/replies переживают process restart.

### Engineering correctness / validation gate

`ENGINEERING_VALIDATION`, а не дополнительное product decision:

- DB write/transaction failure не возвращает success.
- Transaction failure не оставляет partial identity/ticket/thread, category или question state.
- Raw SQL, unique-constraint и internal details не выходят caller.
- Проверка не выбирает transaction library, retry count, queue/outbox или worker design.

Current DB primitives являются reusable, но не доказывают весь slice: `core-data/src/main/resources/db/migration/postgresql/V022__support_tickets.sql:1-26` и `core-data/src/main/kotlin/com/example/bot/data/support/SupportRepository.kt:34-89,131-279`.

## Telegram delivery contract

- Persisted staff reply доставляется owner guest через Telegram private delivery.
- Delivery result наблюдаем и аудируется.
- Delivery failure не возвращает и не показывает ложный success.
- Failure получает bounded non-success state/outcome, пригодный для staff observation.
- Exact queue/outbox architecture, retry policy и idempotency mechanism этим решением не выбраны.

Current reply path сохраняет reply и возвращает HTTP success до launched Telegram send, а send failure только логируется (`app-bot/src/main/kotlin/com/example/bot/routes/SupportRoutes.kt:358-389,459-493`); поэтому current primitive не соответствует accepted truthful-delivery contract.

## Audit contract

Обязательные auditable events:

- staff reply;
- status change;
- close;
- Telegram delivery result.

Каждая audit record содержит:

- actor;
- club;
- ticket;
- old status;
- new status, если применимо.

Message body остаётся в ticket thread и не дублируется в audit payload. Retry/delivery attempts не должны стирать или подменять наблюдаемый result; exact retry cardinality определяется implementation design при сохранении одного различимого audit outcome для каждого фактически выполненного auditable event/result.

## Security and privacy

- Staff access fail-closed в `prod`/`stage` по role + matching CLUB scope + соответствующему explicit permission.
- Guest может читать/дополнять только собственный ticket thread.
- Unauthorized и foreign-club principals не получают ticket metadata, thread или mutation capability.
- `ENTRY_MANAGER` denied; `OWNER` не получает operational reply/status автоматически.
- Provisioning не расширяет PII contract: phone/rich profile не требуются, optional username/display name минимизируются.
- Message body не дублируется в audit.
- `initData`, secrets, `qrSecret` и `Idempotency-Key` не логируются.
- Failure/denial не обходится fallback-ролью, client mode или подставленным `clubId`.

## Explicit exclusions

Product-slice exclusions:

- calendar truth;
- operational-night UI;
- rich club detail;
- booking;
- HOLD;
- payments;
- deposits;
- Night Pass;
- check-in;
- loyalty;
- music;
- broadcasts;
- channel posts;
- exports;
- iBota;
- Guest Mode;
- all AI functions;
- complete guest-home redesign;
- extended registration/profile flow.

Support-workflow exclusions из `DEC-025`:

- SLA;
- priority model;
- platform/network escalation;
- automatic assignment;
- automatic close timer;
- manual reopen `CLOSED`;
- AI classification;
- AI draft;
- AI auto-answer.

## Acceptance matrix

### Operational/runtime acceptance

| # | Acceptance ID | Check | Verification | Authority / Basis |
|---:|---|---|---|---|
| 1 | `PSL-AC-01` | Never-seen Telegram ID sends private bare `/start` | Flow accepts the user without prerequisite registration/profile and enters the canonical provisioning boundary | `DEC-036` |
| 2 | `PSL-AC-02` | First private bare `/start` is processed | One minimal application identity keyed by `telegram_user_id` is created or ensured and returned | `DEC-036` |
| 3 | `PSL-AC-03` | The same Telegram ID sends sequential repeated `/start` | Every call returns the same logical identity and does not create a second user row | `DEC-036` |
| 4 | `PSL-AC-04` | Controlled concurrent/retry `/start` processing for one Telegram ID | All calls converge to one logical identity and a safe idempotent outcome | `DEC-036` |
| 5 | `PSL-AC-05` | DB inspected after sequential/concurrent `/start` processing | Exactly one user row exists for the `telegram_user_id` | `DEC-036` |
| 6 | `PSL-AC-06` | Unique race or DB constraint path occurs during `/start` provisioning | Caller receives bounded non-success without raw unique/SQL/internal detail; exact presentation or retry affordance is not asserted | `DEC-036` |
| 7 | `PSL-AC-07` | Minimal identity payload is inspected | Optional supplied/needed username/display name may persist; phone and rich profile are not required | `DEC-036` |
| 8 | `PSL-AC-08` | Provisioned guest sends private `/ask` | `/ask` defensively verifies identity and reaches club selection without becoming the primary provisioning trigger or requiring full Mini App redesign | `DEC-007`; `DEC-036` |
| 9 | `PSL-AC-09` | Guest selects a club | List comes from production data/configuration; pilot is not hardcoded and multi-club model plus `clubId` remain intact | `DEC-003`; `DEC-007`; `DEC-036` |
| 10 | `PSL-AC-10` | Guest selects a support category | Exactly one of the seven accepted categories is selected explicitly; no AI classification | `DEC-025`; `DEC-036` |
| 11 | `PSL-AC-11` | Guest submits category and question | Ticket is persisted in `NEW` and initial message is persisted in the durable flow | `DEC-025`; `DEC-036` |
| 12 | `PSL-AC-12` | Guest lists or opens tickets | Only that guest's tickets/messages are visible; another guest's data is denied | `DEC-025` |
| 13 | `PSL-AC-13` | `MANAGER` has support view and matching CLUB | Ticket list/filter/detail/thread are visible for that club only | `DEC-025`; `DEC-036` |
| 14 | `PSL-AC-14` | `MANAGER` has support reply and matching CLUB | Reply is allowed and persists; missing reply permission is denied | `DEC-025`; `DEC-036` |
| 15 | `PSL-AC-15` | `MANAGER` has support status manage and matching CLUB | Take/resolve/close are allowed only through accepted transitions; missing status permission is denied | `DEC-025`; `DEC-036` |
| 16 | `PSL-AC-16` | `CLUB_ADMIN` has the same explicit permissions and matching CLUB | View/reply/status obey the same permission and scope boundaries as `MANAGER` | `DEC-025`; `DEC-036` |
| 17 | `PSL-AC-17` | `ENTRY_MANAGER` accesses support | Inbox, ticket, reply and status actions are denied | `DEC-025`; `DEC-036` |
| 18 | `PSL-AC-18` | `OWNER` has role only, without explicit support permission | Operational reply/status are denied; role name produces no automatic grant | `DEC-025`; `DEC-036` |
| 19 | `PSL-AC-19` | Staff supplies a foreign `clubId` or ticket ID | List/detail/thread/reply/status are denied without leaking foreign ticket data | `DEC-025`; `DEC-036` |
| 20 | `PSL-AC-20` | Explicit «Взять в работу» on `NEW` | Ticket becomes `IN_PROGRESS`; status change is audited with actor/club/ticket/old/new | `DEC-025` |
| 21 | `PSL-AC-21` | First staff reply on `NEW` | Reply persists; ticket becomes `IN_PROGRESS`; reply and state change are audited without body duplication | `DEC-025` |
| 22 | `PSL-AC-22` | Staff reply on `IN_PROGRESS` | Reply persists and status stays `IN_PROGRESS`; reply does not imply `RESOLVED` | `DEC-025` |
| 23 | `PSL-AC-23` | Telegram delivery succeeds | Guest receives the persisted reply and success result is observable/audited | `DEC-036` |
| 24 | `PSL-AC-24` | Telegram delivery fails | Surface does not show successful delivery; bounded failure is observable and delivery result is audited | `DEC-036` |
| 25 | `PSL-AC-25` | Staff explicitly resolves after replying | Separate confirmed action moves `IN_PROGRESS → RESOLVED` and is audited | `DEC-005`; `DEC-025` |
| 26 | `PSL-AC-26` | Ticket-owning guest writes to a `RESOLVED` ticket | Message persists and ticket moves `RESOLVED → IN_PROGRESS`; transition is audited | `DEC-025`; `DEC-036` |
| 27 | `PSL-AC-27` | Staff explicitly closes a `RESOLVED` ticket | Only `RESOLVED → CLOSED` succeeds and close/status are audited; additional confirmation is not required by accepted product contract | `DEC-025`; `DEC-036` |
| 28 | `PSL-AC-28` | Message/reopen is attempted on `CLOSED` | Existing ticket remains terminal; manual reopen is unavailable; a new question creates a new ticket | `DEC-025`; `DEC-036` |
| 29 | `PSL-AC-29` | Process restarts after identity, ticket, initial message and reply | Identity, ticket, complete persisted thread and statuses remain available from DB | `DEC-036` |
| 30 | `PSL-AC-30` | Audit records are inspected | Every staff reply/status change/close/delivery result has required fields; message body exists only in thread | `DEC-025`; `DEC-036` |
| 31 | `PSL-AC-31` | `WAITING` or unsupported transition is requested | `WAITING` and every transition outside the accepted table are unavailable | `DEC-025` |
| 32 | `PSL-AC-32` | Staff opens the minimal bounded surface | List, club filter, detail, thread, reply, take, resolve, close and denial states are present; no full shell is implied | `DEC-017`; `DEC-036` |
| 33 | `PSL-AC-33` | First-slice scope is inspected | Every accepted product/support exclusion remains absent; no `DEC-028`–`DEC-035` capability enters the slice | `DEC-003`; `DEC-025`; `DEC-036` |
| 34 | `PSL-AC-34` | Logs and audit are inspected after success/failure | No `initData`, secrets, `qrSecret`, `Idempotency-Key` or duplicate message body is logged/audited | `AGENTS.md` §D Security guardrails |
| 35 | `PSL-AC-35` | Pilot configuration changes to another valid club record | Flow follows selected production data without code/architecture hardcode and retains CLUB isolation | `DEC-003`; `DEC-007`; `DEC-036` |

### Engineering correctness acceptance

| # | Acceptance ID | Check | Verification | Authority / Basis |
|---:|---|---|---|---|
| 36 | `PSL-AC-36` | Provisioning, ticket or message DB write/transaction fails | No success is returned, no partial identity/category/question/ticket/thread state remains, and no raw DB detail escapes; no library, retry count, queue or worker is prescribed | `ENGINEERING_VALIDATION` |

### Contract/architecture preservation acceptance

These are pass/fail documentary/static guards. They preserve accepted authorities without claiming that deferred roles, the canonical guest shell or future navigation are implemented by this slice.

| # | Acceptance ID | Check | Verification | Authority / Basis |
|---:|---|---|---|---|
| 37 | `PSL-AC-37` | Pilot and multi-club architecture contract is parsed | Exactly one data/config-selected pilot is operational; multi-club model, `clubId`, CLUB/GLOBAL scopes and no-hardcode boundary remain stated | `DEC-003` |
| 38 | `PSL-AC-38` | First-release network exclusions are parsed | Network analytics, full multi-club onboarding and cross-club UX remain excluded from first release | `DEC-003` |
| 39 | `PSL-AC-39` | First-release product-role contract is parsed | Exact set remains Guest, Host/Entrance Manager, Club Manager, Club Admin and Owner; Floor Manager, Promoter, Finance Manager, DJ, Head Admin and Network Head Manager remain future/deferred, deferral is not deletion, and `DEC-004` does not define permissions | `DEC-004` |
| 40 | `PSL-AC-40` | Role mapping and scope contract is parsed | Guest → `GUEST`, Host/Entrance Manager → `ENTRY_MANAGER`, Floor/Club Manager → `MANAGER`, Club Admin → `CLUB_ADMIN`, Owner → `OWNER`; staff CLUB scopes, OWNER GLOBAL scope and future Floor Manager distinction remain intact | `DEC-005` |
| 41 | `PSL-AC-41` | Role authority and retained-model contract is parsed | Role + scope + permission, no adjacent grants, confirmation only where a specific accepted authority requires it, required audit, retained `GLOBAL_ADMIN`/`HEAD_MANAGER`/`PROMOTER` without new onboarding/navigation, and `DEC-025` support authority remain intact | `DEC-005`; `DEC-025` |
| 42 | `PSL-AC-42` | Canonical guest-home contract is parsed | Target remains a new minimal role-aware shell with exact IA: Clubs, Calendar, Scheme/Tables, My bookings, Pass, My nights, Music and Questions | `DEC-007` |
| 43 | `PSL-AC-43` | Guest-navigation gates are parsed | Sections require accepted decisions, production wiring, RBAC and smoke; unresolved/unwired/placeholders are not shown as working | `DEC-007` |
| 44 | `PSL-AC-44` | Private-slice boundaries against guest-shell decisions are parsed | Private `/start → /ask`, redesign independence, configured pilot, `DEC-001` labels, `DEC-017` cutover and AI-gated iBota remain intact | `DEC-007` |
| 45 | `PSL-AC-45` | Canonical shell and reuse strategy are parsed | Target is a new role-aware shell; React reuse is selective and requires requirements/API/RBAC/packaging verification | `DEC-017` |
| 46 | `PSL-AC-46` | Fallback and final packaging contract is parsed | Static `/app` remains temporary/non-target, React is not automatically canonical, and final state remains one `/app`/pipeline/asset source | `DEC-017` |
| 47 | `PSL-AC-47` | Navigation, cutover and removal order is parsed | Navigation remains gated; cutover follows E2E smoke; legacy/duplicate assets are removed only after cutover | `DEC-017` |
| 48 | `PSL-AC-48` | Support independence from the canonical shell is parsed | Private support remains independent and staff inbox may remain a separate bounded surface | `DEC-017` |
| 49 | `PSL-AC-49` | Owner oversight boundary is parsed | Owner oversight remains a future explicit permission and is not silently granted | `DEC-025` |
| 50 | `PSL-AC-50` | Slice status and implementation boundary are parsed | Status remains `ACCEPTED_NOT_IMPLEMENTED`; production code requires a separate implementation task and review | `DEC-036` |
| 51 | `PSL-AC-51` | DEC-036 exclusion set is parsed | Every explicitly accepted slice exclusion remains present and absent from required implementation scope | `DEC-036` |
| 52 | `PSL-AC-52` | Unselected technical choices are parsed | Concurrency mechanism, failure presentation, permission constants, UI technology, queue/outbox and retry policy remain implementation choices | `DEC-036` |
| 53 | `PSL-AC-53` | Authority traceability integrity is parsed | Exactly 118 stable clause IDs exist, every clause maps to acceptance, and no accepted decision/requirement ID is invented | `DEC-003`; `DEC-004`; `DEC-005`; `DEC-007`; `DEC-017`; `DEC-025`; `DEC-036` |

Acceptance inventory: `PSL-AC-01`–`PSL-AC-53`; operational/runtime: 35; engineering-only: 1; contract/architecture preservation: 17; acceptance items without an accepted, repository-security or `ENGINEERING_VALIDATION` basis: 0; unsupported product additions: 0.

## Authority clause inventory and acceptance traceability

Inventory method: one semantic clause for each accepted bullet or indivisible accepted set. These `*-Cnn` values are local traceability IDs, not product decision IDs or requirement IDs.

| Decision | Clauses | Mapped | Missing |
|---|---:|---:|---:|
| `DEC-003` | 9 | 9 | 0 |
| `DEC-004` | 6 | 6 | 0 |
| `DEC-005` | 16 | 16 | 0 |
| `DEC-007` | 10 | 10 | 0 |
| `DEC-017` | 12 | 12 | 0 |
| `DEC-025` | 29 | 29 | 0 |
| `DEC-036` | 36 | 36 | 0 |
| **Total** | **118** | **118** | **0** |

| Clause ID | Decision | Accepted clause | Acceptance ID(s) | Verification class |
|---|---|---|---|---|
| `DEC-003-C01` | `DEC-003` | One real pilot club is operational in the first usable release | `PSL-AC-35`, `PSL-AC-37` | `STATIC_ARCHITECTURE` |
| `DEC-003-C02` | `DEC-003` | Multi-club data model is retained | `PSL-AC-09`, `PSL-AC-37` | `STATIC_ARCHITECTURE` |
| `DEC-003-C03` | `DEC-003` | `clubId` is retained | `PSL-AC-09`, `PSL-AC-37` | `STATIC_ARCHITECTURE` |
| `DEC-003-C04` | `DEC-003` | CLUB/GLOBAL scope architecture is retained | `PSL-AC-37` | `STATIC_ARCHITECTURE` |
| `DEC-003-C05` | `DEC-003` | Network analytics is excluded from first release | `PSL-AC-33`, `PSL-AC-38` | `EXCLUSION_GUARD` |
| `DEC-003-C06` | `DEC-003` | Full multi-club onboarding is excluded from first release | `PSL-AC-33`, `PSL-AC-38` | `EXCLUSION_GUARD` |
| `DEC-003-C07` | `DEC-003` | Cross-club UX is excluded from first release | `PSL-AC-33`, `PSL-AC-38` | `EXCLUSION_GUARD` |
| `DEC-003-C08` | `DEC-003` | Pilot club is selected through configuration/data | `PSL-AC-09`, `PSL-AC-35`, `PSL-AC-37` | `STATIC_ARCHITECTURE` |
| `DEC-003-C09` | `DEC-003` | Pilot club is not hardcoded | `PSL-AC-09`, `PSL-AC-35`, `PSL-AC-37` | `STATIC_ARCHITECTURE` |
| `DEC-004-C01` | `DEC-004` | Exact first-release product-role set is Guest, Host/Entrance Manager, Club Manager, Club Admin and Owner | `PSL-AC-39` | `DOCUMENTARY_CONTRACT` |
| `DEC-004-C02` | `DEC-004` | Floor Manager remains a future target distinction governed by `DEC-005` mapping | `PSL-AC-39` | `DOCUMENTARY_CONTRACT` |
| `DEC-004-C03` | `DEC-004` | Promoter, Finance Manager and DJ remain target roles but are deferred | `PSL-AC-39` | `DOCUMENTARY_CONTRACT` |
| `DEC-004-C04` | `DEC-004` | Head Admin and Network Head Manager remain target roles but are deferred | `PSL-AC-39` | `DOCUMENTARY_CONTRACT` |
| `DEC-004-C05` | `DEC-004` | Deferral does not mean role deletion | `PSL-AC-39` | `DOCUMENTARY_CONTRACT` |
| `DEC-004-C06` | `DEC-004` | `DEC-004` does not itself define the permission matrix | `PSL-AC-39` | `DOCUMENTARY_CONTRACT` |
| `DEC-005-C01` | `DEC-005` | Guest maps to `GUEST` | `PSL-AC-40` | `STATIC_ARCHITECTURE` |
| `DEC-005-C02` | `DEC-005` | Host/Entrance Manager maps to `ENTRY_MANAGER` | `PSL-AC-40` | `STATIC_ARCHITECTURE` |
| `DEC-005-C03` | `DEC-005` | Floor Manager and Club Manager temporarily map to `MANAGER` | `PSL-AC-40` | `STATIC_ARCHITECTURE` |
| `DEC-005-C04` | `DEC-005` | Club Admin maps to `CLUB_ADMIN` | `PSL-AC-40` | `STATIC_ARCHITECTURE` |
| `DEC-005-C05` | `DEC-005` | Owner maps to `OWNER` | `PSL-AC-40` | `STATIC_ARCHITECTURE` |
| `DEC-005-C06` | `DEC-005` | `ENTRY_MANAGER`, `MANAGER` and `CLUB_ADMIN` are CLUB-scoped | `PSL-AC-40` | `SECURITY_RBAC` |
| `DEC-005-C07` | `DEC-005` | `OWNER` remains GLOBAL-scoped | `PSL-AC-40` | `SECURITY_RBAC` |
| `DEC-005-C08` | `DEC-005` | One-club pilot does not remove OWNER GLOBAL architecture | `PSL-AC-40` | `STATIC_ARCHITECTURE` |
| `DEC-005-C09` | `DEC-005` | No separate Floor Manager enum is created in first release | `PSL-AC-40` | `STATIC_ARCHITECTURE` |
| `DEC-005-C10` | `DEC-005` | Future Floor Manager target distinction is retained | `PSL-AC-40` | `DOCUMENTARY_CONTRACT` |
| `DEC-005-C11` | `DEC-005` | Authority requires role + scope + explicit permission | `PSL-AC-41` | `SECURITY_RBAC` |
| `DEC-005-C12` | `DEC-005` | Role name alone grants no adjacent or senior authority | `PSL-AC-41` | `SECURITY_RBAC` |
| `DEC-005-C13` | `DEC-005` | Critical actions require confirmation and audit | `PSL-AC-25`, `PSL-AC-41` | `SECURITY_RBAC` |
| `DEC-005-C14` | `DEC-005` | `GLOBAL_ADMIN`, `HEAD_MANAGER` and `PROMOTER` remain in the existing model | `PSL-AC-41` | `STATIC_ARCHITECTURE` |
| `DEC-005-C15` | `DEC-005` | Retained legacy roles do not enter new first-release onboarding/navigation | `PSL-AC-41` | `EXCLUSION_GUARD` |
| `DEC-005-C16` | `DEC-005` | Support inbox/reply authority is governed by `DEC-025` | `PSL-AC-41`, `PSL-AC-53` | `DOCUMENTARY_CONTRACT` |
| `DEC-007-C01` | `DEC-007` | Future canonical guest home is a new minimal role-aware shell | `PSL-AC-42` | `STATIC_ARCHITECTURE` |
| `DEC-007-C02` | `DEC-007` | Exact eight-section target IA is retained | `PSL-AC-42` | `DOCUMENTARY_CONTRACT` |
| `DEC-007-C03` | `DEC-007` | Active navigation requires accepted decisions, production wiring, RBAC verification and staging smoke | `PSL-AC-43` | `DOCUMENTARY_CONTRACT` |
| `DEC-007-C04` | `DEC-007` | Unresolved, unwired and placeholder capabilities are not presented as working | `PSL-AC-43` | `EXCLUSION_GUARD` |
| `DEC-007-C05` | `DEC-007` | First support slice remains private `/start → /ask` | `PSL-AC-01`, `PSL-AC-08`, `PSL-AC-44` | `RUNTIME_BEHAVIOR` |
| `DEC-007-C06` | `DEC-007` | Support slice is independent from complete Mini App redesign | `PSL-AC-08`, `PSL-AC-32`, `PSL-AC-44` | `DOCUMENTARY_CONTRACT` |
| `DEC-007-C07` | `DEC-007` | Pilot club is selected through data/configuration | `PSL-AC-09`, `PSL-AC-35`, `PSL-AC-44` | `STATIC_ARCHITECTURE` |
| `DEC-007-C08` | `DEC-007` | Public name and final labels remain under `DEC-001` | `PSL-AC-44` | `DOCUMENTARY_CONTRACT` |
| `DEC-007-C09` | `DEC-007` | Reuse and cutover strategy remains under `DEC-017` | `PSL-AC-44` | `DOCUMENTARY_CONTRACT` |
| `DEC-007-C10` | `DEC-007` | iBota active entry is excluded until a separate AI decision | `PSL-AC-33`, `PSL-AC-44` | `EXCLUSION_GUARD` |
| `DEC-017-C01` | `DEC-017` | Canonical Mini App target is a new role-aware shell | `PSL-AC-45` | `STATIC_ARCHITECTURE` |
| `DEC-017-C02` | `DEC-017` | Current React components, API clients, stores and styles may be reused selectively | `PSL-AC-45` | `STATIC_ARCHITECTURE` |
| `DEC-017-C03` | `DEC-017` | Reuse requires accepted-requirement, production-API, RBAC and packaging/build verification | `PSL-AC-45` | `DOCUMENTARY_CONTRACT` |
| `DEC-017-C04` | `DEC-017` | Current static `/app` remains a temporary fallback | `PSL-AC-46` | `STATIC_ARCHITECTURE` |
| `DEC-017-C05` | `DEC-017` | Static `/app` is not the target product | `PSL-AC-46` | `DOCUMENTARY_CONTRACT` |
| `DEC-017-C06` | `DEC-017` | Current React shell is not automatically canonical | `PSL-AC-46` | `DOCUMENTARY_CONTRACT` |
| `DEC-017-C07` | `DEC-017` | Final state has one canonical `/app`, one build/package/serve pipeline and one asset source | `PSL-AC-46` | `STATIC_ARCHITECTURE` |
| `DEC-017-C08` | `DEC-017` | Navigation appears only after complete wiring, RBAC, tests and staging smoke | `PSL-AC-47` | `DOCUMENTARY_CONTRACT` |
| `DEC-017-C09` | `DEC-017` | Cutover is explicit and follows successful E2E smoke | `PSL-AC-47` | `DOCUMENTARY_CONTRACT` |
| `DEC-017-C10` | `DEC-017` | Legacy static implementation and duplicate assets are removed only after cutover | `PSL-AC-47` | `STATIC_ARCHITECTURE` |
| `DEC-017-C11` | `DEC-017` | First support slice remains a private Telegram flow | `PSL-AC-44`, `PSL-AC-48` | `DOCUMENTARY_CONTRACT` |
| `DEC-017-C12` | `DEC-017` | Minimal staff support inbox may be a separate bounded surface | `PSL-AC-32`, `PSL-AC-48` | `DOCUMENTARY_CONTRACT` |
| `DEC-025-C01` | `DEC-025` | Exact seven-category set is retained | `PSL-AC-10` | `RUNTIME_BEHAVIOR` |
| `DEC-025-C02` | `DEC-025` | Guest selects the category | `PSL-AC-10` | `RUNTIME_BEHAVIOR` |
| `DEC-025-C03` | `DEC-025` | AI classification is excluded | `PSL-AC-10`, `PSL-AC-33` | `EXCLUSION_GUARD` |
| `DEC-025-C04` | `DEC-025` | AI draft reply is excluded | `PSL-AC-33` | `EXCLUSION_GUARD` |
| `DEC-025-C05` | `DEC-025` | AI auto-answer is excluded | `PSL-AC-33` | `EXCLUSION_GUARD` |
| `DEC-025-C06` | `DEC-025` | Support inbox is available only to `MANAGER` and `CLUB_ADMIN` | `PSL-AC-13`, `PSL-AC-16` | `SECURITY_RBAC` |
| `DEC-025-C07` | `DEC-025` | Staff support access is limited to matching CLUB scope | `PSL-AC-13`, `PSL-AC-16`, `PSL-AC-19` | `SECURITY_RBAC` |
| `DEC-025-C08` | `DEC-025` | View, reply and status-management permissions are separate | `PSL-AC-13`, `PSL-AC-14`, `PSL-AC-15`, `PSL-AC-16` | `SECURITY_RBAC` |
| `DEC-025-C09` | `DEC-025` | `ENTRY_MANAGER` has no support access | `PSL-AC-17` | `SECURITY_RBAC` |
| `DEC-025-C10` | `DEC-025` | `OWNER` receives no operational reply permission by role alone | `PSL-AC-18` | `SECURITY_RBAC` |
| `DEC-025-C11` | `DEC-025` | Owner oversight remains a future explicit permission | `PSL-AC-49` | `DOCUMENTARY_CONTRACT` |
| `DEC-025-C12` | `DEC-025` | Guest sees only own tickets/messages | `PSL-AC-12` | `SECURITY_RBAC` |
| `DEC-025-C13` | `DEC-025` | Active lifecycle is `NEW → IN_PROGRESS → RESOLVED → CLOSED` | `PSL-AC-20`, `PSL-AC-25`, `PSL-AC-27`, `PSL-AC-28`, `PSL-AC-31` | `RUNTIME_BEHAVIOR` |
| `DEC-025-C14` | `DEC-025` | Ticket creation produces `NEW` | `PSL-AC-11` | `PERSISTENCE` |
| `DEC-025-C15` | `DEC-025` | Explicit take moves `NEW → IN_PROGRESS` | `PSL-AC-20` | `RUNTIME_BEHAVIOR` |
| `DEC-025-C16` | `DEC-025` | First staff reply moves `NEW → IN_PROGRESS` | `PSL-AC-21` | `RUNTIME_BEHAVIOR` |
| `DEC-025-C17` | `DEC-025` | Staff reply does not automatically resolve | `PSL-AC-22` | `RUNTIME_BEHAVIOR` |
| `DEC-025-C18` | `DEC-025` | Resolve after reply is a separate confirmed action | `PSL-AC-25` | `RUNTIME_BEHAVIOR` |
| `DEC-025-C19` | `DEC-025` | Guest message moves `RESOLVED → IN_PROGRESS` | `PSL-AC-26` | `RUNTIME_BEHAVIOR` |
| `DEC-025-C20` | `DEC-025` | Close is allowed only from `RESOLVED` | `PSL-AC-27` | `RUNTIME_BEHAVIOR` |
| `DEC-025-C21` | `DEC-025` | `CLOSED` is terminal in first release | `PSL-AC-28` | `RUNTIME_BEHAVIOR` |
| `DEC-025-C22` | `DEC-025` | Manual reopen of `CLOSED` is absent | `PSL-AC-28` | `RUNTIME_BEHAVIOR` |
| `DEC-025-C23` | `DEC-025` | New question after `CLOSED` creates a new ticket | `PSL-AC-28` | `RUNTIME_BEHAVIOR` |
| `DEC-025-C24` | `DEC-025` | `WAITING` remains deferred outside the slice | `PSL-AC-31` | `EXCLUSION_GUARD` |
| `DEC-025-C25` | `DEC-025` | SLA, priority, escalation, assignment/close automation, manual reopen and AI support remain excluded | `PSL-AC-33` | `EXCLUSION_GUARD` |
| `DEC-025-C26` | `DEC-025` | Staff reply, status change, close and Telegram delivery result are audited | `PSL-AC-30` | `PERSISTENCE` |
| `DEC-025-C27` | `DEC-025` | Audit contains actor, club, ticket and old/new status where applicable | `PSL-AC-30` | `PERSISTENCE` |
| `DEC-025-C28` | `DEC-025` | Message body remains in ticket thread | `PSL-AC-21`, `PSL-AC-30` | `PERSISTENCE` |
| `DEC-025-C29` | `DEC-025` | Message body is not duplicated in audit payload | `PSL-AC-21`, `PSL-AC-30`, `PSL-AC-34` | `SECURITY_RBAC` |
| `DEC-036-C01` | `DEC-036` | First product slice is Private Support Loop for one real pilot club | `PSL-AC-35`, `PSL-AC-37` | `DOCUMENTARY_CONTRACT` |
| `DEC-036-C02` | `DEC-036` | Accepted visible flow runs from `/start` provisioning through `/ask`, durable support, delivery and lifecycle | `PSL-AC-02`, `PSL-AC-08`, `PSL-AC-09`, `PSL-AC-10`, `PSL-AC-11`, `PSL-AC-13`, `PSL-AC-21`, `PSL-AC-23`, `PSL-AC-25`, `PSL-AC-27`, `PSL-AC-32` | `RUNTIME_BEHAVIOR` |
| `DEC-036-C03` | `DEC-036` | Fresh Telegram user needs no prior registration | `PSL-AC-01` | `RUNTIME_BEHAVIOR` |
| `DEC-036-C04` | `DEC-036` | Provisioning is minimal and not full registration/profile | `PSL-AC-01`, `PSL-AC-07` | `RUNTIME_BEHAVIOR` |
| `DEC-036-C05` | `DEC-036` | Provisioning is keyed by `telegram_user_id` | `PSL-AC-02` | `PERSISTENCE` |
| `DEC-036-C06` | `DEC-036` | Sequential `/start` repeats converge to one identity | `PSL-AC-03` | `PERSISTENCE` |
| `DEC-036-C07` | `DEC-036` | Concurrent/retry `/start` processing converges to one identity | `PSL-AC-04` | `PERSISTENCE` |
| `DEC-036-C08` | `DEC-036` | One DB user row remains per `telegram_user_id` | `PSL-AC-05` | `PERSISTENCE` |
| `DEC-036-C09` | `DEC-036` | Raw unique/SQL errors do not escape | `PSL-AC-06` | `PERSISTENCE` |
| `DEC-036-C10` | `DEC-036` | Concrete provisioning concurrency mechanism is not selected | `PSL-AC-52` | `DOCUMENTARY_CONTRACT` |
| `DEC-036-C11` | `DEC-036` | Username/display name are used only when supplied and needed | `PSL-AC-07` | `DOCUMENTARY_CONTRACT` |
| `DEC-036-C12` | `DEC-036` | Phone and rich profile are not prerequisites | `PSL-AC-07` | `EXCLUSION_GUARD` |
| `DEC-036-C13` | `DEC-036` | Inbox/view/reply/status are limited to `MANAGER` and `CLUB_ADMIN` | `PSL-AC-13`, `PSL-AC-16` | `SECURITY_RBAC` |
| `DEC-036-C14` | `DEC-036` | Staff access requires matching CLUB scope | `PSL-AC-13`, `PSL-AC-16`, `PSL-AC-19` | `SECURITY_RBAC` |
| `DEC-036-C15` | `DEC-036` | Staff access requires explicit permissions | `PSL-AC-13`, `PSL-AC-14`, `PSL-AC-15`, `PSL-AC-16` | `SECURITY_RBAC` |
| `DEC-036-C16` | `DEC-036` | `ENTRY_MANAGER` is denied | `PSL-AC-17` | `SECURITY_RBAC` |
| `DEC-036-C17` | `DEC-036` | `OWNER` receives no automatic operational reply permission | `PSL-AC-18` | `SECURITY_RBAC` |
| `DEC-036-C18` | `DEC-036` | Unauthorized staff cannot see or answer a ticket | `PSL-AC-17`, `PSL-AC-18`, `PSL-AC-19` | `SECURITY_RBAC` |
| `DEC-036-C19` | `DEC-036` | Exact `DEC-025` lifecycle is used | `PSL-AC-20`, `PSL-AC-21`, `PSL-AC-22`, `PSL-AC-25`, `PSL-AC-26`, `PSL-AC-27`, `PSL-AC-28`, `PSL-AC-31` | `RUNTIME_BEHAVIOR` |
| `DEC-036-C20` | `DEC-036` | Guest message returns `RESOLVED → IN_PROGRESS` | `PSL-AC-26` | `RUNTIME_BEHAVIOR` |
| `DEC-036-C21` | `DEC-036` | `CLOSED` is terminal in first release | `PSL-AC-28` | `RUNTIME_BEHAVIOR` |
| `DEC-036-C22` | `DEC-036` | Manual reopen is absent | `PSL-AC-28` | `RUNTIME_BEHAVIOR` |
| `DEC-036-C23` | `DEC-036` | Ticket persists in DB | `PSL-AC-11` | `PERSISTENCE` |
| `DEC-036-C24` | `DEC-036` | Initial message persists in DB | `PSL-AC-11` | `PERSISTENCE` |
| `DEC-036-C25` | `DEC-036` | Staff replies persist in DB | `PSL-AC-14`, `PSL-AC-21`, `PSL-AC-22` | `PERSISTENCE` |
| `DEC-036-C26` | `DEC-036` | Slice data survives process restart | `PSL-AC-29` | `PERSISTENCE` |
| `DEC-036-C27` | `DEC-036` | Staff reply is delivered to guest through Telegram | `PSL-AC-23` | `DELIVERY` |
| `DEC-036-C28` | `DEC-036` | Delivery result is observable and audited | `PSL-AC-23`, `PSL-AC-24`, `PSL-AC-30` | `DELIVERY` |
| `DEC-036-C29` | `DEC-036` | Delivery failure is not presented as successful delivery | `PSL-AC-24` | `DELIVERY` |
| `DEC-036-C30` | `DEC-036` | Minimal staff surface may be separate and independent from full redesign | `PSL-AC-08`, `PSL-AC-32`, `PSL-AC-48` | `DOCUMENTARY_CONTRACT` |
| `DEC-036-C31` | `DEC-036` | Staff surface minimum is list/filter/detail/thread/reply/take/resolve/close/denial | `PSL-AC-32` | `RUNTIME_BEHAVIOR` |
| `DEC-036-C32` | `DEC-036` | Staff reply, status change, close and delivery result are audited | `PSL-AC-30` | `PERSISTENCE` |
| `DEC-036-C33` | `DEC-036` | Audit does not duplicate message body | `PSL-AC-30`, `PSL-AC-34` | `SECURITY_RBAC` |
| `DEC-036-C34` | `DEC-036` | Complete explicit slice exclusion set remains enforced | `PSL-AC-33`, `PSL-AC-51` | `EXCLUSION_GUARD` |
| `DEC-036-C35` | `DEC-036` | Canonical slice status is `ACCEPTED_NOT_IMPLEMENTED` | `PSL-AC-50` | `DOCUMENTARY_CONTRACT` |
| `DEC-036-C36` | `DEC-036` | Production code changes only in a separate implementation task/review | `PSL-AC-50` | `DOCUMENTARY_CONTRACT` |

## Staging smoke

Bounded end-to-end smoke starts with a never-seen Telegram ID and verifies in this order:

1. first private bare `/start` provisions one minimal logical identity;
2. sequential repeated `/start` returns the same identity and leaves one user row;
3. controlled concurrent/retry `/start` processing converges to that identity and exposes no raw unique/SQL detail;
4. only then private `/ask`, production-backed club selection and one of the seven categories;
5. persisted `NEW` ticket and initial message;
6. allowed `MANAGER` and `CLUB_ADMIN` paths with separate permissions;
7. `ENTRY_MANAGER`, role-only `OWNER`, missing-permission and foreign-club denials;
8. take, first reply, additional reply, explicit resolve, guest message from `RESOLVED`, explicit close without an accepted extra confirmation requirement and terminal `CLOSED`;
9. Telegram delivery success and observable audited result;
10. process restart followed by ticket/thread/status verification;
11. DB/delivery failure observability without false success, when safely testable in staging.

Smoke does not require manual URL/role/mode substitution and does not depend on the complete canonical Mini App redesign.

## Implementation gaps

| Capability | Current state | Reusable evidence | Missing implementation | Validation required |
|---|---|---|---|---|
| Minimal Telegram user provisioning | `GAP` | Read-only lookup `core-data/src/main/kotlin/com/example/bot/data/security/ExposedUserRepositories.kt:15-37`; DB unique `core-data/src/main/resources/db/migration/postgresql/V1__init.sql:109-115`; current boundary in [AS_BUILT §4.1](../AS_BUILT.md#41-identity-boundary-for-the-first-support-slice) | Production-owned minimal writer; sequential/concurrent convergence; bounded errors; no partial ticket | Ktor/bot tests plus Postgres concurrency/retry integration and staging smoke |
| Guest `/ask` | `PARTIAL` | Wired handler `app-bot/src/main/kotlin/com/example/bot/telegram/TelegramGuestFallbackHandler.kt:156-254` | Provisioning prerequisite, category step and complete failure contract | Fresh/known guest Telegram handler tests and end-to-end smoke |
| Production club selection | `PARTIAL` | Handler/callback calls repository at `app-bot/src/main/kotlin/com/example/bot/telegram/TelegramGuestFallbackHandler.kt:168-215`; production DI binds `ClubsRepository` to `ClubsDbRepository` at `app-bot/src/main/kotlin/com/example/bot/di/ClubsModule.kt:13-15`; DB query is implemented at `core-data/src/main/kotlin/com/example/bot/data/clubs/GuestClubsRepository.kt:21-63` | Verify data/config-selected pilot boundary and bounded unavailable/stale selection; production-backed primitive does not make the complete slice implemented | Ktor/bot tests, DB fixtures and staging data smoke |
| Category mapping | `PARTIAL` | Current topics `core-domain/src/main/kotlin/com/example/bot/support/SupportModels.kt:6-18`; current `/ask` hardcodes `OTHER` at `app-bot/src/main/kotlin/com/example/bot/telegram/TelegramGuestFallbackHandler.kt:240-249` | Exact guest-selected seven-category mapping and migration without invented identifiers | Mapping/round-trip tests and seven-category UI/Telegram smoke |
| Ticket/message persistence | `PARTIAL` | DB schema `core-data/src/main/resources/db/migration/postgresql/V022__support_tickets.sql:1-26`; transactional create `core-data/src/main/kotlin/com/example/bot/data/support/SupportRepository.kt:34-89` | Align `NEW`, category and failure semantics with accepted contract | Postgres transaction/failure/restart integration tests |
| Staff inbox | `PARTIAL` | Registered list API `app-bot/src/main/kotlin/com/example/bot/routes/SupportRoutes.kt:246-270` | Served list/filter surface and accepted role/scope/permission enforcement | Ktor authorization tests and served-surface smoke |
| Ticket detail/thread | `GAP` | Ticket/message repositories are partial primitives; [current `SUP-004` evidence](../CONCEPT_CODE_GAP.md#18-loyalty-and-support) | Served detail plus ordered complete thread read contract | Ktor/API/UI tests, ownership/scope denial and paging/ordering checks |
| Staff reply | `PARTIAL` | Persisted reply/API `app-bot/src/main/kotlin/com/example/bot/routes/SupportRoutes.kt:337-395`; `core-data/src/main/kotlin/com/example/bot/data/support/SupportRepository.kt:237-279` | Accepted `MANAGER`/`CLUB_ADMIN` permissions, lifecycle effect, atomic/truthful delivery outcome | Ktor test host plus Postgres reply/delivery integration tests |
| Status lifecycle | `PARTIAL` | Current statuses `core-domain/src/main/kotlin/com/example/bot/support/SupportModels.kt:20-29`; unchecked mutations `core-data/src/main/kotlin/com/example/bot/data/support/SupportRepository.kt:131-279` | Exact accepted states/transitions, resolve confirmation and terminal enforcement | Transition-matrix Ktor/Postgres tests including every denial |
| Explicit permissions | `PARTIAL` | Generic RBAC/club primitives `core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt:94-169`; current support roles `app-bot/src/main/kotlin/com/example/bot/routes/SupportRoutes.kt:115-116,536-549` | Separate semantic view/reply/status permissions; remove automatic global/role grant from slice | Role × scope × permission Ktor matrix and foreign-club tests |
| Audit | `GAP` for support contract | Generic foundation `core-domain/src/main/kotlin/com/example/bot/audit/AuditLogger.kt:1-180`; current support routes have no support audit writer | Required events/fields, body exclusion and delivery-result audit | Postgres audit cardinality/content/redaction tests |
| Telegram delivery | `PARTIAL` | Current send primitive `app-bot/src/main/kotlin/com/example/bot/routes/SupportRoutes.kt:459-493` | Observable result, no false success, bounded failure; later design chooses queue/retry | Success/failure/cancellation/idempotency tests and safe staging failure smoke |
| Restart durability | `PARTIAL` | DB ticket/message schema and repository above | Full slice restart proof including provisioning identity, accepted statuses and reply/delivery correlation | Postgres restart integration and staging restart smoke |
| Staging smoke | `GAP` | Existing unit/API tests cover isolated primitives; [AS_BUILT Support flow](../AS_BUILT.md#4-key-flow-audit) | Never-seen-user, permissions, lifecycle, restart and delivery E2E procedure | Successful bounded staging run with recorded outcomes |

## Explicit technical choices not accepted yet

The later implementation design may choose these mechanisms only if they preserve this contract:

- concurrency mechanism for provisioning;
- exact provisioning-failure presentation, copy, button and retry affordance;
- exact permission constant names;
- staff UI technology;
- queue/outbox mechanism;
- exact delivery retry policy.

These are technical design choices, not unresolved product blockers and not accepted implementation promises.

## Implementation handoff

Production code remains unchanged until a separate implementation task and review. That task must update code and tests, satisfy repository engineering/security gates, run the required Gradle checks and provide a successful bounded staging smoke before the slice can move from `ACCEPTED_NOT_IMPLEMENTED`.
