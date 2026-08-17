# Решения пользователя

Здесь только выборы, которые нельзя вывести из source или принять по рекомендации аудитора. У каждого решения статус `DECISION_REQUIRED`. Рекомендованный default — самый простой способ снять зависимость, а не автоматически выбранный вариант.

## `DEC-001` — публичное имя продукта

- **Context:** одно имя нужно для `/start`, `/app`, документации и staff communications.
- **Source tension:** source использует `Telegram Club OS`.
- **Code tension:** `app-bot/src/main/kotlin/com/example/bot/telegram/TelegramGuestFallbackHandler.kt:345-346` использует `Night Concierge`, static Mini App — «Куда пойдём?».
- **Options:** (A) Night Concierge; (B) Telegram Club OS; (C) новое публичное имя + внутреннее platform name.
- **Recommendation:** `Night Concierge` как guest-facing name, `Telegram Club OS` как внутреннее описание платформы.
- **Consequences:** A/C требуют зафиксировать, где показывается platform name; B требует заменить текущие runtime copy в отдельной задаче.
- **Depends on:** none.
- **Blocks:** `DEC-002`, `DEC-007`.
- **Status:** `DECISION_REQUIRED`.

## `DEC-002` — публичное имя AI assistant

- **Context:** source называет помощника iBota, но runtime assistant отсутствует.
- **Source tension:** iBota — сквозной branded entry.
- **Code tension:** нет существующего имени/handler/component, которое можно считать текущим контрактом.
- **Options:** (A) iBota; (B) нейтральное «Помощник»; (C) другое имя.
- **Recommendation:** в первой AI-capable версии UI label «Помощник», iBota оставить working name до brand review.
- **Consequences:** выбор влияет на commands, buttons, analytics events и public communication; не влияет на первый non-AI slice.
- **Depends on:** `DEC-001`.
- **Blocks:** `DEC-016`.
- **Status:** `DECISION_REQUIRED`.

## `DEC-003` — один клуб или сеть в MVP

- **Context:** data/RBAC уже multi-club, но первый end-to-end product flow не собран.
- **Source tension:** source описывает сеть и Owner comparisons.
- **Code tension:** club entity/scope есть; network analytics/onboarding отсутствуют.
- **Options:** (A) один реальный клуб поверх multi-club data model; (B) несколько клубов с первого usable release.
- **Recommendation:** один клуб operationally, не удаляя `clubId`/scope из модели.
- **Consequences:** A уменьшает content/calendar/onboarding scope; B требует сразу решить network roles, comparison и configuration completeness.
- **Depends on:** none.
- **Blocks:** `DEC-004`, `DEC-006`, `DEC-007`, `DEC-008`, `DEC-024`.
- **Status:** `DECISION_REQUIRED`.

## `DEC-004` — MVP role set

- **Context:** source role catalogue шире текущего enum.
- **Source tension:** отдельные Host, floor manager, club manager, admin, finance manager, DJ, global promoter, Owner.
- **Code tension:** `Role` содержит OWNER, GLOBAL_ADMIN, HEAD_MANAGER, CLUB_ADMIN, MANAGER, ENTRY_MANAGER, PROMOTER, GUEST.
- **Options:** (A) Guest + Host + club manager/admin + Owner; (B) все source roles; (C) Guest-only first slice с staff API вне slice.
- **Recommendation:** для первого operational slice Guest, ENTRY_MANAGER, MANAGER/CLUB_ADMIN и OWNER; finance/DJ/promoter additions — следующими capability slices.
- **Consequences:** default требует временно документировать совмещённые обязанности, но не стирать target role distinctions.
- **Depends on:** `DEC-003`.
- **Blocks:** `DEC-005`, `DEC-006`, `DEC-012`, `DEC-014`, `DEC-021`, `DEC-023`, `DEC-025`.
- **Status:** `DECISION_REQUIRED`.

## `DEC-005` — различия Host, entrance manager, floor manager, club manager и admin

- **Context:** source различает роли, current enum частично объединяет.
- **Source tension:** у входа, зала, клуба и admin разные operational responsibilities.
- **Code tension:** ENTRY_MANAGER и MANAGER есть; floor/club manager distinction не выражен, CLUB_ADMIN смешивает operation/config.
- **Options:** (A) пять отдельных ролей; (B) Host=ENTRY_MANAGER, floor+club=MANAGER, admin отдельно; (C) capability permissions без жёстких role names.
- **Recommendation:** B для MVP, с явной permission matrix и миграционным путём к C.
- **Consequences:** A повышает setup complexity; B может дать club manager лишние table permissions; C требует более крупного RBAC redesign.
- **Depends on:** `DEC-004`.
- **Blocks:** `DEC-012`, `DEC-021`, `DEC-023`, `DEC-025`.
- **Status:** `DECISION_REQUIRED`.

## `DEC-006` — GLOBAL promoter model

- **Context:** source прямо называет промоутера глобальным.
- **Source tension:** глобальный promoter может работать с несколькими клубами.
- **Code tension:** global roles — Owner/Global Admin/Head Manager; promoter access обычно club assignment.
- **Options:** (A) PROMOTER всегда GLOBAL; (B) promoter имеет список CLUB assignments; (C) отдельные GLOBAL_PROMOTER и CLUB_PROMOTER.
- **Recommendation:** B — одна роль PROMOTER + явные assignments к одному/нескольким клубам.
- **Consequences:** A расширяет доступ слишком широко; C увеличивает role catalogue; B требует определить cross-club analytics/quotas.
- **Depends on:** `DEC-003`, `DEC-004`.
- **Blocks:** `DEC-024`.
- **Status:** `DECISION_REQUIRED`.

## `DEC-007` — canonical guest home/navigation

- **Context:** served `/app` — catalogue; React source — другой guest shell; source задаёт восемь sections.
- **Source tension:** Clubs/Calendar/Tables/My bookings/Pass/My nights/Music/Questions + iBota.
- **Code tension:** static page имеет filters/list/events; React имеет пять tabs и не является canonical.
- **Options:** (A) static catalogue как home с постепенным deep-link flow; (B) React shell как canonical; (C) новый minimal shell.
- **Recommendation:** C, но не делать canonical shell prerequisite первого support slice: сохранить видимый `/start → private /ask` после добавления idempotent minimal user provisioning, а role-aware guest shell вводить только вместе с выбранным shell-based outcome.
- **Consequences:** A накапливает legacy JS; B требует сначала исправить packaging/unwired APIs; C требует явного cutover. Private `/ask` позволяет проверить support loop без ложной зависимости от calendar/rich detail, но current production path принимает только уже существующего application user; provisioning — технический prerequisite slice, а не выбранный здесь registration contract.
- **Depends on:** `DEC-001`, `DEC-003`.
- **Blocks:** `DEC-016`, `DEC-017`, `DEC-025`.
- **Status:** `DECISION_REQUIRED`.

## `DEC-008` — canonical operational-night definition

- **Context:** source определяет overnight interval в TZ клуба; разные current paths используют event timestamps и hardcoded policy.
- **Source tension:** business date ночи начала, weekly rules + exceptions/holidays, end-relative cutoff.
- **Code tension:** resolver моделирует это, DB adapter rules пуст, finance/table/check-in keys используют timestamps.
- **Options:** (A) materialized Event — единственный canonical night; (B) rules on demand; (C) rules генерируют/materialize canonical Night record.
- **Recommendation:** C — rule engine создаёт/обновляет immutable-identifiable operational nights, а все domains ссылаются на один ID/start.
- **Consequences:** A проще, но ручной calendar; B осложняет устойчивые ссылки/audit; C требует reconciliation/migration design.
- **Depends on:** `DEC-003`.
- **Blocks:** `DEC-009`, `DEC-010`, `DEC-011`, `DEC-012`, `DEC-013`, `DEC-018`.
- **Status:** `DECISION_REQUIRED`.

## `DEC-009` — booking payment/deposit boundary

- **Context:** source показывает депозит до confirmation, но не определяет online payment obligation.
- **Source tension:** booking flow содержит расчёт депозита; table operations проводят фактические deposits.
- **Code tension:** payment/refund infrastructure присутствует, route stack в основном unwired; A3 confirm не платёжный.
- **Options:** (A) reservation без online payment, депозит только на месте; (B) provider deposit до confirm; (C) per-club policy `NONE/PROVIDER_DEPOSIT`; (D) Stars для допустимого digital scope.
- **Recommendation:** C с initial MVP policy `NONE`, пока business/legal/payment requirements не определены.
- **Consequences:** A/C позволяют shipping guest flow быстрее; B требует end-to-end payment, refund, webhook and reconciliation; Stars нельзя выбирать без классификации товара.
- **Depends on:** `DEC-008`.
- **Blocks:** `DEC-014`, `DEC-018`.
- **Status:** `DECISION_REQUIRED`.

## `DEC-010` — GuestList MVP: names, links или оба

- **Context:** backend поддерживает bulk names и invitations, но UI не canonical.
- **Source tension:** оба варианта заявлены как желаемые.
- **Code tension:** оба API-фрагмента есть; единый Night Pass отсутствует.
- **Options:** (A) names/search only; (B) invitation links only; (C) оба, через один list/entry model.
- **Recommendation:** C, но первый UI increment — names/search; invitation включить только вместе с unified pass.
- **Consequences:** A быстрее для входа, но без self-service; B исключает гостей без bot activation; C требует ясной dedup identity.
- **Depends on:** `DEC-008`.
- **Blocks:** `DEC-011`, `DEC-020`.
- **Status:** `DECISION_REQUIRED`.

## `DEC-011` — Night Pass format и rotation

- **Context:** current code выдаёт booking, guest-list и promoter invitation QR разными codecs.
- **Source tension:** один live Night Pass на пользователя/ночь.
- **Code tension:** QR TTL/rotation primitives есть, canonical subject/user-night pass нет.
- **Options:** (A) signed opaque user+night token; (B) server-stored random one-time/rotating token; (C) signed token, разрешаемый server-side к canonical pass record.
- **Recommendation:** C с короткоживущим display token и стабильным server-side pass ID; повторный scan возвращает existing outcome.
- **Consequences:** A проще, но revoke/forward controls слабее; B требует online DB; C требует both DB and key rotation, но объединяет sources.
- **Depends on:** `DEC-008`, `DEC-010`, `DEC-020`.
- **Blocks:** `DEC-013`, `DEC-018`, `DEC-021`.
- **Status:** `DECISION_REQUIRED`.

## `DEC-012` — financial shift MVP

- **Context:** current API уже поддерживает people/bracelets/revenue/reconciliation/freeze, но ownership/lifecycle расходятся.
- **Source tension:** auto-open, finance-only close, freeze, configurable articles.
- **Code tension:** lazy draft create; close разрешён admin/global roles; finance role отсутствует; no post-close correction.
- **Options:** (A) defer finance; (B) ship current admin close; (C) align source lifecycle/role before release.
- **Recommendation:** C, bounded to auto-created shift + people/bracelets/revenue + reconciliation + close/freeze; correction as separate audited command.
- **Consequences:** B быстрее, но закрепляет неправильную ответственность; C зависит от role/night decisions.
- **Depends on:** `DEC-004`, `DEC-005`, `DEC-008`.
- **Blocks:** none.
- **Status:** `DECISION_REQUIRED`.

## `DEC-013` — loyalty timing

- **Context:** visits/badges/coupons exist; raffles/table loyalty incomplete; table seating can create a `TABLE_DEPOSIT` visit before entrance; отдельный current `mysteryEligible` flag отсутствует в source.
- **Source tension:** source включает stamps, early arrival, badges, raffles и table loyalty, но не mystery mechanic.
- **Code tension:** entrance check-in creates visits, but `AdminTableOpsRoutes.markHasTableIfPossible` independently calls `VisitRepository.tryCheckIn`; unique visit storage therefore does not guarantee entrance-first ordering.
- **Options:** (A) ship stamps/early/badges after check-in first; (B) wait for complete gamification suite; (C) defer all loyalty.
- **Recommendation:** A только после unified check-in/Night Pass closes the table-seating bypass; raffles и table loyalty позже; mystery не включать без отдельного решения.
- **Consequences:** A даёт маленький complete loop, но требует запретить stamp/progress до accepted entrance outcome; B/C откладывают guest retention surface.
- **Depends on:** `DEC-008`, `DEC-011`, `DEC-021`.
- **Blocks:** `DEC-022`.
- **Status:** `DECISION_REQUIRED`.

## `DEC-014` — DJ/music timing

- **Context:** backend music implementation unexpectedly broad, UI/role/donations incomplete.
- **Source tension:** music is one of core blocks and guest sections.
- **Code tension:** files/playlists/likes/battles/track-of-night wired; DJ role/canonical UI absent.
- **Options:** (A) include read-only published music early; (B) full DJ module before guest launch; (C) defer music entirely.
- **Recommendation:** A after core guest entry; retain backend, defer DJ authoring/donations.
- **Consequences:** B expands RBAC/storage/moderation; C leaves valuable backend unused but reduces first-slice scope.
- **Depends on:** `DEC-004`, `DEC-009`.
- **Blocks:** none.
- **Status:** `DECISION_REQUIRED`.

## `DEC-015` — Guest Mode timing

- **Context:** official Telegram capability exists on verification date, current router has none.
- **Source tension:** source calls it defining UX.
- **Code tension:** no update types, response method, public-context privacy or handoff.
- **Options:** (A) first product slice; (B) after private bot/Mini App works; (C) long-term experiment.
- **Recommendation:** B.
- **Consequences:** A couples first slice to new platform/privacy work; B reuses stable concierge actions and minimizes public PII risk.
- **Depends on:** `DEC-016`.
- **Blocks:** none.
- **Status:** `DECISION_REQUIRED`.

## `DEC-016` — AI in the first usable slice

- **Context:** source v2.0 centers iBota, but there is no AI implementation or safety contract.
- **Source tension:** every domain includes AI acceleration.
- **Code tension:** deterministic core flows themselves are incomplete/duplicated.
- **Options:** (A) AI from first slice; (B) deterministic slice first, assistant later; (C) read-only FAQ assistant first.
- **Recommendation:** B; optionally C only after identity/privacy policy, without mutations.
- **Consequences:** A risks building orchestration over unstable services; B validates product flow first and preserves AI as cross-cutting next layer. Любой выбранный AI slice отдельно требует решения grounding/explanation policy; этот timing decision её не принимает.
- **Depends on:** `DEC-002`, `DEC-007`.
- **Blocks:** `DEC-015`, `DEC-024`, `DEC-027`.
- **Status:** `DECISION_REQUIRED`.

## `DEC-017` — retain/replace current Mini App implementations

- **Context:** static catalogue is production canonical; React source is richer but unwired/stale.
- **Source tension:** target is one role-aware Mini App.
- **Code tension:** Gradle merges two asset sources; Docker does not build React; test asserts static title.
- **Options:** (A) retain static and extend; (B) make React canonical; (C) new shell reusing selected React components.
- **Recommendation:** C; retain static only as temporary fallback until the first end-to-end shell passes smoke.
- **Consequences:** requires an explicit cutover and removal of duplicate asset inputs in a later code task; avoids silently editing unused React.
- **Depends on:** `DEC-007`.
- **Blocks:** `DEC-025`.
- **Status:** `DECISION_REQUIRED`.

## `DEC-018` — retain/replace booking/check-in/payment implementations

- **Context:** duplicate registered booking routes and multiple QR/check-in/payment stacks create competing ownership; actual HOLD/confirm selector choice is deterministic, not random.
- **Source tension:** source assumes one lifecycle and one pass.
- **Code tension:** in-memory A3 and DB secured hold/confirm share paths, but extra `authorize`/`clubScoped` selectors make the secured DB branch selected; A3 handlers are shadowed for those methods and their React DTO returns `invalid_payload`. Other booking/payment routes are unwired; two check-in families are wired.
- **Options:** (A) retain A3 and remove DB route; (B) retain DB booking service and adapt UI; (C) new orchestrator over selected repositories; payment deferred or included separately.
- **Recommendation:** B for persistence: adapt the client to the selected DB contract, then remove the shadowed A3 route only in an authorized code task; retain existing check-in DB service behind a unified pass adapter; keep payment disabled until `DEC-009`.
- **Consequences:** response DTO/UI changes and combined-routing tests are required; A would intentionally reverse the current selected production path and lose restart durability; C is larger.
- **Depends on:** `DEC-008`, `DEC-009`, `DEC-011`, `DEC-020`.
- **Blocks:** none.
- **Status:** `DECISION_REQUIRED`.

## `DEC-019` — existing capabilities outside `CONCEPT_SOURCE`

- **Context:** waitlist, owner health/stories, detailed music interactions and payment/refund infrastructure exist beyond `CONCEPT_SOURCE`; spontaneous tables, mystery, playlists/favourites and other items are also named by current `AGENTS.md`.
- **Source tension:** `CONCEPT_SOURCE` does not promise or prioritize all of these, while the repository requirement snapshot adds some explicitly.
- **Code tension:** some capabilities are wired, some infrastructure-only, and some repository-only promises are not implemented end-to-end.
- **Options:** (A) retain but exclude from MVP navigation; (B) add to target spec later; (C) remove after usage/evidence audit.
- **Recommendation:** A only after `DEC-026` classifies each repository/source conflict; do not delete or advertise disputed capabilities meanwhile.
- **Consequences:** preserves implementation without silently treating code or `AGENTS.md` additions as accepted product priority; requires item-level ownership decisions.
- **Depends on:** `DEC-026`.
- **Blocks:** none.
- **Status:** `DECISION_REQUIRED`.

## `DEC-020` — meaning and delivery of internal/external invitations

- **Context:** source asks iBota to offer internal/external invitations without defining either channel.
- **Source tension:** both variants are named, but eligibility, consent and fallback are unspecified.
- **Code tension:** current domain stores `TELEGRAM`/`EXTERNAL` and can issue links/tokens, but there is no accepted mapping to source terminology or canonical UI.
- **Options:** (A) internal = direct Telegram delivery only to a known, eligible bot user; external = promoter-shared deeplink/QR; (B) both are shareable artifacts with different analytics labels; (C) one channel in MVP.
- **Recommendation:** A, with explicit opt-in/eligibility and external fallback; this is a proposal, not accepted semantics.
- **Consequences:** A needs user-resolution and delivery-failure handling; B weakens the distinction; C narrows the source flow.
- **Depends on:** `DEC-010`.
- **Blocks:** `DEC-011`, `DEC-018`.
- **Status:** `DECISION_REQUIRED`.

## `DEC-021` — entrance outcome taxonomy and denial reason

- **Context:** source uses “пришёл”, ARRIVED/SEATED quality and denied flags, but does not define a complete state machine or mandatory denial reason.
- **Source tension:** finance and anti-fraud need distinct outcomes; operational status vocabulary remains underspecified.
- **Code tension:** current check-in supports ARRIVED/LATE/DENIED, a nullable denial reason and downstream SEATED/NO_SHOW mappings.
- **Options:** (A) ARRIVED/LATE/DENIED at entrance, mandatory reason for DENIED, SEATED only as table outcome; (B) minimum ARRIVED/DENIED, derive late/no-show elsewhere; (C) configurable taxonomy.
- **Recommendation:** A because it reuses current primitives while separating entrance from table state.
- **Consequences:** A requires validation/migration of nullable reasons; B loses operational detail; C complicates analytics and integrations.
- **Depends on:** `DEC-004`, `DEC-005`, `DEC-011`.
- **Blocks:** `DEC-013`, `DEC-022`.
- **Status:** `DECISION_REQUIRED`.

## `DEC-022` — raffle condition catalogue

- **Context:** source requires enable/disable, period and conditions but does not enumerate supported condition types.
- **Source tension:** guest progress and acceptance cannot be defined without a bounded catalogue.
- **Code tension:** no raffle aggregate, persistence or route exists.
- **Options:** (A) curated conditions based on accepted visit/early/table facts; (B) generic rule builder; (C) defer raffles while shipping stamps/badges.
- **Recommendation:** C for the first loyalty slice, then A after canonical attendance/table facts exist.
- **Consequences:** A is bounded and testable; B creates a rules-engine project; C delays one source module without blocking basic loyalty.
- **Depends on:** `DEC-013`, `DEC-021`.
- **Blocks:** none.
- **Status:** `DECISION_REQUIRED`.

## `DEC-023` — “golden rules” content and ownership

- **Context:** source names “golden rules” in the operations block but supplies no rules, owner or update process.
- **Source tension:** the term suggests normative procedures, yet there is no acceptance content to normalize.
- **Code tension:** only a fixed in-memory Host checklist and separate SOP documents exist.
- **Options:** (A) defer the label until the user supplies rules; (B) versioned global baseline; (C) global baseline with club overrides and acknowledgement.
- **Recommendation:** A now; C only after real rule content and an accountable owner are provided.
- **Consequences:** inventing rules would create unsupported product policy; deferral keeps the unknown explicit but leaves procedure scope incomplete.
- **Depends on:** `DEC-004`, `DEC-005`, `DEC-026`.
- **Blocks:** none.
- **Status:** `DECISION_REQUIRED`.

## `DEC-024` — Owner risk/alert catalogue for iBota

- **Context:** source says Owner receives risk control and alerts, but does not define signals, thresholds, delivery or escalation.
- **Source tension:** an AI summary must not invent risk semantics.
- **Code tension:** deterministic owner-health fragments exist, while iBota and an accepted alert policy do not.
- **Options:** (A) small read-only catalogue grounded in accepted deterministic metrics; (B) configurable per-club thresholds and proactive alerts; (C) defer risk/alerts and keep on-demand factual analytics only.
- **Recommendation:** C for the first AI slice, then A; no proactive or autonomous action.
- **Consequences:** A requires metric ownership and explainable evidence; B adds configuration/notification governance; C narrows the source role until the contract is decidable.
- **Depends on:** `DEC-003`, `DEC-006`, `DEC-016`.
- **Blocks:** none.
- **Status:** `DECISION_REQUIRED`.

## `DEC-025` — support taxonomy and lifecycle

- **Context:** the first recommended slice depends on a minimal support workflow from guest question through persisted ticket, staff reply and Telegram delivery.
- **Source tension:** source enumerates categories and `NEW`, `IN_PROGRESS`, `WAITING`, `RESOLVED`, `CLOSED`, but does not define a transition graph, status ownership, reopen, escalation or audit policy.
- **Code tension:** current code has topics and `OPENED`, `IN_PROGRESS`, `ANSWERED`, `CLOSED`, DB persistence and RBAC list/reply APIs, but no served staff inbox/detail surface and no accepted lifecycle contract.
- **Options:** (A) minimal MVP categories + `NEW/IN_PROGRESS/RESOLVED/CLOSED`, defer `WAITING`; (B) all source statuses with an explicit transition matrix; (C) map current statuses to source names first, then extend; each option must decide allowed transitions, who may change status, close/reopen, staff roles, inbox surface, escalation and audit.
- **Recommendation:** A for the bounded first slice, with one staff role set, explicit close/reopen rule and audited staff mutations; this is a recommendation, not a selected workflow.
- **Consequences:** A minimizes the UI/state-machine implementation but defers a source-listed status; B is complete but broader; C minimizes migration yet risks cementing current semantics. No option is usable until staff access and end-to-end delivery acceptance are explicit.
- **Depends on:** `DEC-004`, `DEC-005`, `DEC-007`, `DEC-017`.
- **Blocks:** none.
- **Status:** `DECISION_REQUIRED`.

## `DEC-026` — precedence: `CONCEPT_SOURCE` vs repository product requirements

- **Context:** `AGENTS.md` is a current repository instruction and adds or specifies reminder «скоро слетит», spontaneous tables, mystery-upgrade, richer allocation categories, playlists/favourites, channel posts, exports/auto-reports and cloning/templates beyond or differently from `CONCEPT_SOURCE`.
- **Source tension:** immutable source does not contain every repository promise or describes a narrower capability; silently merging either direction would change product scope.
- **Code tension:** some repository additions already have wired or partial implementations, while others are absent or unwired; code existence cannot settle product precedence.
- **Options:** (A) concept overrides all repository additions; (B) repository additions remain mandatory; (C) merge both into a separately accepted superseding product spec; (D) classify every conflict individually as keep, amend, defer or reject.
- **Recommendation:** D, recording each item explicitly and leaving both source and repository instruction unchanged until the user decides.
- **Consequences:** A can discard current repository obligations; B can fabricate source scope; C is broad and requires a new accepted baseline; D is slower item-by-item but preserves traceability and avoids silent promises/deletions.
- **Depends on:** none.
- **Blocks:** `DEC-019`, `DEC-023`.
- **Status:** `DECISION_REQUIRED`.

## `DEC-027` — AI grounding and explanation contract

- **Context:** `AIAN-003` must distinguish the explicit non-KPI rule for the `2–4 раза` hypothesis from a proposed presentation contract for AI summaries.
- **Source tension:** source supplies factual analytics examples, role/scope boundaries and hypotheses, but does not require a particular facts-versus-interpretation layout, citation format or explanation vocabulary.
- **Code tension:** no AI/grounding layer, evidence schema or evaluation contract exists; current deterministic snapshots cannot establish an accepted AI presentation policy by themselves.
- **Options:** (A) label factual fields and interpretation separately with source timestamps; (B) one narrative answer with inline evidence references and uncertainty; (C) factual read-only answers only, with interpretation deferred; (D) another explicitly accepted contract with measurable evaluation criteria.
- **Recommendation:** C for the first AI-capable slice, then evaluate A; this is a bounded recommendation, not an accepted source requirement or KPI.
- **Consequences:** A/B require evidence provenance, freshness and evaluation rules; C narrows usefulness but avoids presenting interpretation as fact; D may add product and compliance scope.
- **Depends on:** `DEC-016`.
- **Blocks:** none.
- **Status:** `DECISION_REQUIRED`.
