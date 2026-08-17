# Первичный dependency-aware roadmap

Roadmap задаёт последовательность проверяемых outcomes, а не календарные сроки, capacity или story points. Он не означает, что рекомендации приняты. Любая production реализация выполняется отдельными задачами с code/tests/Gradle gates.

## 1. Foundation already reusable

Следующие части имеют доказанный production/test foundation и не требуют переписывания «с нуля»: Ktor/DB bootstrap, webhook ingress queue, initData HMAC validation, RBAC/club-scope primitives, audit/redaction primitives, club/event persistence, layout/tables repositories, GuestList/invitation/check-in services, user/night visit uniqueness, deposit operation ledger, finance templates/shift freeze, support persistence/list/reply/delivery primitives, music backend и deterministic analytics snapshot.

Reuse не означает keep-as-is: the secured DB HOLD branch is selected but its A3/React client contract is broken; query-string initData, incomplete role coverage, table-seating visit bypass, unwired availability/campaigns и canonical UI packaging должны быть разрешены до соответствующих slices. Private `/ask` code is reusable only after an application user exists; fresh Telegram user provisioning is not part of the current production foundation.

## 2. Recommended first product slice

Статус: `RECOMMENDED_NOT_ACCEPTED`.

### Private support loop

`/start → private /ask → club selection → persisted support ticket → minimal staff list/detail/reply inbox → Telegram delivery гостю`

#### Reusable foundation

- bare private Telegram `/start` response primitive, which does not look up or create an application user;
- `/ask` command, Telegram club-selection callback and support ticket creation path for an already provisioned application user;
- support ticket/message persistence;
- RBAC list/reply API and service primitives;
- guest Telegram delivery primitive.

#### New implementation required

- idempotent minimal Telegram user provisioning before `/ask` can succeed for a fresh guest: ensure/create keyed by unique `telegram_user_id`, producing the database-generated `users.id` required by support, with duplicate-safe concurrent/repeated `/start` behavior;
- provisioning data boundary: `username`/`display_name` are optional and may be retained only when supplied and justified; phone, contact and rich profile fields are not required. A provisioning failure must stop before club/question state, return a bounded retry message and leave no partial ticket;
- provisioning must use a production-owned path and must not depend on the disabled legacy booking WebApp writer. Whether ensure runs inside `/start` or in an idempotent pre-`/ask` guard is a slice design dependency; this roadmap does not accept a broad registration/profile flow;
- минимальный served staff inbox и ticket detail;
- staff reply UI/action поверх существующего API;
- verified RBAC/club scope для staff support surface;
- выбранная minimal category/status/transition policy по `DEC-025`;
- end-to-end delivery smoke с persistence и authorization.

#### Explicitly excluded from first slice

- calendar truth и current/next operational night;
- rich club detail и canonical guest home redesign;
- booking/HOLD, payment/deposit и Night Pass;
- loyalty, music, broadcasts;
- registration/profile enrichment beyond the minimal support identity record;
- AI auto-answer, iBota и Guest Mode.

Acceptance boundary:

1. Fresh Telegram guest вызывает `/start`.
2. Первый `/start` создаёт или обеспечивает ровно одну минимальную application user identity, нужную support flow.
3. Повторный последовательный `/start` не создаёт вторую user row и возвращает ту же логическую identity.
4. Два или более конкурентных/retry ensure-вызова для одного `telegram_user_id` сходятся к одной application user identity: в БД остаётся ровно одна строка, caller не получает необработанный unique-constraint/SQL error, а оба пути получают один логический результат либо безопасный идемпотентный outcome; конкретный concurrency mechanism остаётся implementation choice slice.
5. Гость вызывает `/ask`.
6. Гость выбирает клуб из production-backed list.
7. Вопрос создаёт persisted ticket и persisted initial message.
8. Разрешённый staff видит ticket в минимальном served inbox.
9. Staff открывает ticket detail и отвечает.
10. Ответ сохраняется и доставляется гостю в Telegram.
11. Unauthorized staff не видит ticket и не может ответить.
12. Ticket, initial message и reply переживают process restart через DB persistence.
13. Staging smoke начинается с ранее неизвестного Telegram user, контролируемо проверяет sequential и concurrent/retry provisioning и затем проходит end-to-end без ручной подмены URL/role/mode.

Decision prerequisites: `DEC-003`, `DEC-004`, `DEC-005`, `DEC-017`, `DEC-025`. Slice остаётся `RECOMMENDED_NOT_ACCEPTED`; решение о запуске не принято.

## 3. Phases

### Phase 0 — Product contract and architecture choices

- **Category:** Product rework / governance.
- **User outcome:** команда знает, какой продукт и какой runtime path строится; silent parallel implementations не получают новых функций.
- **Included IDs:** `PROD-003`, `RBAC-001`, `RBAC-002`, `UX-001`, `CAL-001`, `PASS-001`, `FIN-002`, `SUP-002`.
- **Dependencies:** review [OPEN_DECISIONS.md](OPEN_DECISIONS.md).
- **Acceptance boundary:** приняты только выбранные пользователем public names, launch model, role mapping, operational-night model, canonical UI, support workflow, repository/source precedence and booking implementation direction; decisions записаны явно.
- **Excluded scope:** production changes.
- **Staging smoke:** не применяется; review проверяет непротиворечивую decision log и updated traceability.
- **Decision prerequisites:** `DEC-001`–`DEC-012`, `DEC-017`, `DEC-018`, `DEC-025`, `DEC-026` в той части, которая блокирует соответствующий slice.

### Phase 1 — Private support loop

- **Category:** Reuse with minimal new staff surface.
- **User outcome:** гость задаёт вопрос выбранному клубу в private Telegram flow, разрешённый staff отвечает из minimal served inbox, и ответ приходит гостю.
- **Included IDs:** `PROD-001`, `NET-001`, `RBAC-006`, `SUP-001`, `SUP-002`, `SUP-004`, `SUP-005`, `COM-007`, `SEC-002`.
- **Dependencies:** idempotent minimal Telegram user provisioning before `/ask`; existing `/ask`/club-selection code for a provisioned user; support persistence/list/reply/delivery primitives; minimal served staff inbox/detail/reply; staff role mapping; `DEC-025`; secure staff auth transport.
- **Acceptance boundary:** полностью соответствует тринадцати пунктам first-slice boundary выше.
- **Excluded scope:** calendar/operational-night truth, rich club detail, canonical guest shell redesign, booking/HOLD, payment/deposit, Night Pass, loyalty and AI.
- **Staging smoke:** previously unknown Telegram user; first `/start` ensure/create; sequential repeated `/start` returns the same logical identity without a second row; two or more controlled concurrent/retry ensure calls for the same `telegram_user_id` leave exactly one user row, expose no uncaught unique-constraint/SQL error and return the same logical user or another safe idempotent outcome; then `/ask`; club selection; persisted ticket/message; authorized list/detail/reply; unauthorized list/reply denial; Telegram guest delivery; process restart; delivery failure observability/retry boundary.
- **Decision prerequisites:** `DEC-003`, `DEC-004`, `DEC-005`, `DEC-017`, `DEC-025`.

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
- **Dependencies:** unified entrance-first check-in with table-seating bypass closed; canonical guest navigation; DJ/moderation/payment decisions; `DEC-026` for repository-only mystery/playlists/favourites scope.
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
- **Decision prerequisites:** `DEC-003`–`DEC-006`, `DEC-008`, `DEC-023`, `DEC-026` for disputed templates/cloning scope.

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

## 4. Sequencing guardrails

- Не начинать AI mutation до deterministic service acceptance и common confirmation contract.
- Не строить loyalty, finance или analytics поверх нескольких definitions of operational night.
- Не добавлять новые features одновременно в static и React Mini App; сначала выбрать canonical packaging.
- Не трактовать duplicate HOLD routes как недетерминированную runtime ambiguity: secured DB branch уже выбирается детерминированно; перед расширением согласовать DTO/owner implementation и удалить либо намеренно адаптировать shadowed A3 path.
- Не включать disputed `AGENTS.md` additions как accepted scope до `DEC-026`.
- Не включать campaigns, Guest Mode, paid broadcasts, bot-to-bot или business updates только потому, что код/platform primitive существует.
- Каждая phase заканчивается end-to-end staging smoke с persistence и authorization, а не только render/UI demo.
