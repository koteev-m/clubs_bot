# Решения пользователя

Здесь только выборы, которые нельзя вывести из source или принять по рекомендации аудитора. Decision record может иметь статус `DECISION_REQUIRED` или `ACCEPTED_DECISION`. `ACCEPTED_DECISION` возможен только после прямого решения пользователя; рекомендация агента не считается принятием. Рекомендованный default — самый простой способ снять зависимость, а не автоматически выбранный вариант.

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
- **Selected option:** A.
- **Accepted by:** user.
- **Accepted at:** 2026-08-18.
- **Accepted contract:**
  - Первый usable release запускается операционно для одного реального пилотного клуба.
  - Multi-club data model сохраняется.
  - `clubId` сохраняется.
  - `CLUB/GLOBAL scope` сохраняется в архитектуре.
  - Network analytics не входят в первый release.
  - Полноценный multi-club onboarding не входит в первый release.
  - Межклубный UX не входит в первый release.
  - Конкретный пилотный клуб выбирается configuration/data.
  - Пилотный клуб не зашивается в product architecture или code.
- **Consequences:** первый release ограничен одним configuration/data-selected клубом, при этом multi-club model, `clubId` и `CLUB/GLOBAL scope` остаются target architecture; network analytics, полноценный multi-club onboarding и межклубный UX отложены за границу первого release.
- **Depends on:** none.
- **Blocks:** `DEC-004`, `DEC-006`, `DEC-007`, `DEC-008`, `DEC-024`.
- **Status:** `ACCEPTED_DECISION`.

## `DEC-004` — MVP role set

- **Context:** source role catalogue шире текущего enum.
- **Source tension:** отдельные Host, floor manager, club manager, admin, finance manager, DJ, global promoter, Owner.
- **Code tension:** `Role` содержит OWNER, GLOBAL_ADMIN, HEAD_MANAGER, CLUB_ADMIN, MANAGER, ENTRY_MANAGER, PROMOTER, GUEST.
- **Options:** (A) Guest + Host + club manager/admin + Owner; (B) все source roles; (C) Guest-only first slice с staff API вне slice.
- **Recommendation:** для первого operational slice Guest, ENTRY_MANAGER, MANAGER/CLUB_ADMIN и OWNER; finance/DJ/promoter additions — следующими capability slices.
- **Selected option:** A.
- **Accepted by:** user.
- **Accepted at:** 2026-08-18.
- **Accepted contract:**
  - Роли первого usable release:
    - Гость;
    - Host / Менеджер входа;
    - Менеджер клуба;
    - Админ клуба;
    - Владелец / Owner.
  - Менеджер зала сохраняется как target role distinction, но его системное соответствие определяется `DEC-005`.
  - Промоутер, Финансовый менеджер и DJ остаются в target role model, но не входят в первый release.
  - Главный админ и Главный менеджер сети остаются в target role model, но не входят в первый release.
  - Исключение роли из первого release не означает удаление из продукта.
  - Это решение не определяет permission matrix самостоятельно.
- **Consequences:** первый release использует bounded role set; остальные source/target roles сохраняются для последующих capability slices, а permission matrix и временное системное соответствие Менеджера зала определяются отдельно.
- **Depends on:** `DEC-003`.
- **Blocks:** `DEC-005`, `DEC-006`, `DEC-012`, `DEC-014`, `DEC-021`, `DEC-023`, `DEC-025`.
- **Status:** `ACCEPTED_DECISION`.

## `DEC-005` — различия Host, entrance manager, floor manager, club manager и admin

- **Context:** source различает роли, current enum частично объединяет.
- **Source tension:** у входа, зала, клуба и admin разные operational responsibilities.
- **Code tension:** ENTRY_MANAGER и MANAGER есть; floor/club manager distinction не выражен, CLUB_ADMIN смешивает operation/config.
- **Options:** (A) пять отдельных ролей; (B) Host=ENTRY_MANAGER, floor+club=MANAGER, admin отдельно; (C) capability permissions без жёстких role names.
- **Recommendation:** B для MVP, с явной permission matrix и миграционным путём к C.
- **Selected option:** B.
- **Accepted by:** user.
- **Accepted at:** 2026-08-18.
- **Accepted contract:**
  - Гость → `GUEST`.
  - Host / Менеджер входа → `ENTRY_MANAGER`.
  - Менеджер зала и Менеджер клуба временно объединяются в `MANAGER`.
  - Админ клуба → `CLUB_ADMIN`.
  - Владелец → `OWNER`.
  - `ENTRY_MANAGER`, `MANAGER` и `CLUB_ADMIN` работают только в CLUB scope.
  - `OWNER` сохраняет GLOBAL scope.
  - В pilot release `OWNER` фактически видит один настроенный клуб, но архитектурный GLOBAL scope не удаляется.
  - Отдельная системная роль Менеджера зала в первом release не создаётся.
  - Target distinction Менеджера зала сохраняется для будущего capability-based RBAC.
  - Полномочия определяются сочетанием role + scope + explicit permission.
  - Название роли само по себе не предоставляет все операции соседних или старших ролей.
  - Критические действия требуют confirmation и audit.
  - `GLOBAL_ADMIN`, `HEAD_MANAGER` и `PROMOTER` не удаляются из existing model.
  - `GLOBAL_ADMIN`, `HEAD_MANAGER` и `PROMOTER` не входят в новые onboarding/navigation flows первого release.
  - Право на support inbox/reply определяется `DEC-025`.
- **Consequences:** первый release переиспользует существующие system roles с явными scope и permission boundaries; отдельный floor-manager system role и capability-based RBAC остаются будущей эволюцией, а legacy roles не получают новые onboarding/navigation flows автоматически.
- **Depends on:** `DEC-004`.
- **Blocks:** `DEC-012`, `DEC-021`, `DEC-023`, `DEC-025`.
- **Status:** `ACCEPTED_DECISION`.

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
- **Selected option:** C.
- **Accepted by:** user.
- **Accepted at:** 2026-08-18.
- **Accepted contract:**
  - Будущий canonical guest home создаётся как новый minimal role-aware shell.
  - Target information architecture сохраняет:
    - Клубы;
    - Календарь;
    - Схема / Столы;
    - Мои брони;
    - Пропуск;
    - Мои ночи;
    - Музыка;
    - Вопросы.
  - Раздел появляется в active target navigation только после:
    - принятия связанных решений;
    - полного production wiring;
    - RBAC verification;
    - successful staging smoke.
  - Unresolved, unwired и placeholder capabilities не показываются как рабочие.
  - Первый support slice остаётся private Telegram flow `/start → /ask`.
  - Первый support slice не зависит от redesign Mini App.
  - Pilot club выбирается data/configuration, не hardcode.
  - Public name и final labels остаются зависимостью `DEC-001`.
  - Reuse/cutover strategy принадлежит `DEC-017`.
  - iBota не появляется как active entry до отдельного принятия AI contract.
- **Consequences:** новый role-aware shell остаётся future canonical guest home, но private support slice может быть реализован независимо; navigation раскрывает только fully wired, RBAC-verified и smoke-tested capabilities, а final labels и cutover сохраняют отдельные authorities.
- **Depends on:** `DEC-001`, `DEC-003`.
- **Blocks:** `DEC-016`, `DEC-017`, `DEC-025`.
- **Status:** `ACCEPTED_DECISION`.

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
- **Selected option:** C.
- **Accepted by:** user.
- **Accepted at:** 2026-08-18.
- **Accepted contract:**
  - Canonical Mini App создаётся как новый role-aware shell.
  - Разрешено выборочно переиспользовать current React:
    - components;
    - API clients;
    - stores;
    - styles.
  - Reuse допускается только после проверки соответствия:
    - accepted requirements;
    - production API;
    - RBAC;
    - packaging/build contract.
  - Current static `/app` остаётся temporary fallback.
  - Static `/app` не развивается как target product.
  - Existing React shell не является автоматически принятой canonical implementation.
  - В результате должны остаться:
    - один canonical `/app`;
    - один build/package/serve pipeline;
    - один source of assets.
  - Раздел появляется в navigation только после полного wiring, RBAC, tests и staging smoke.
  - Cutover является explicit operation после successful end-to-end smoke.
  - Legacy static implementation и duplicate asset inputs удаляются только после cutover.
  - Первый support slice остаётся private Telegram flow.
  - Minimal staff support inbox может быть отдельным bounded surface.
- **Consequences:** отдельная implementation-задача должна построить новый shell, доказать допустимый reuse и провести explicit cutover; static `/app` остаётся временным fallback, а первый support slice и bounded staff inbox не блокируются полным redesign.
- **Depends on:** `DEC-007`.
- **Blocks:** `DEC-025`.
- **Status:** `ACCEPTED_DECISION`.

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
- **Selected option:** A.
- **Accepted by:** user.
- **Accepted at:** 2026-08-18.
- **Accepted contract:**
  - Категории первого usable release:
    1. Адрес / как добраться.
    2. Правила / дресс-код.
    3. Списки / вход.
    4. Брони / депозит.
    5. Потерял вещь.
    6. Жалоба / сервис.
    7. Другое.
  - Категорию выбирает гость.
  - AI classification не входит в первый slice.
  - AI draft reply не входит в первый slice.
  - AI auto-answer не входит в первый slice.
  - Support inbox доступен `MANAGER` и `CLUB_ADMIN`.
  - Staff access действует только в CLUB scope соответствующего клуба.
  - Требуются явные permissions отдельно для:
    - просмотра;
    - staff reply;
    - изменения статуса.
  - `ENTRY_MANAGER` доступа к support inbox не получает.
  - `OWNER` не получает operational reply permission только из-за названия роли.
  - Возможный owner oversight требует отдельного explicit permission.
  - Гость видит только собственные tickets/messages.
  - Lifecycle первого slice: `NEW → IN_PROGRESS → RESOLVED → CLOSED`.
  - Новый ticket создаётся в `NEW`.
  - Explicit «Взять в работу» переводит `NEW → IN_PROGRESS`.
  - Первый staff reply также переводит `NEW → IN_PROGRESS`.
  - Staff reply сам по себе не устанавливает `RESOLVED`.
  - `RESOLVED` устанавливается отдельным explicit confirmed action после ответа.
  - Новое guest message в `RESOLVED` автоматически переводит ticket обратно в `IN_PROGRESS`.
  - `CLOSED` разрешён только из `RESOLVED`.
  - `CLOSED` является terminal в первом release.
  - Manual reopen `CLOSED` отсутствует.
  - Для нового вопроса после `CLOSED` создаётся новый ticket.
  - `WAITING` сохраняется в target model, но не входит в первый slice.
  - Явно исключены:
    - SLA;
    - priority model;
    - platform/network escalation;
    - automatic assignment;
    - automatic close timer;
    - manual reopen `CLOSED`;
    - AI classification;
    - AI draft;
    - AI auto-answer.
  - Аудируются:
    - staff reply;
    - status change;
    - close;
    - Telegram delivery result.
  - Audit содержит:
    - actor;
    - club;
    - ticket;
    - old status;
    - new status, если применимо.
  - Message body остаётся в ticket thread.
  - Message body не дублируется в audit payload.
- **Consequences:** первый slice получает guest-selected taxonomy, bounded four-state lifecycle, explicit support permissions и audit contract; `WAITING`, escalation/automation/priority/SLA и все AI support functions остаются вне slice, а current code требует отдельной migration и end-to-end implementation.
- **Depends on:** `DEC-004`, `DEC-005`, `DEC-007`, `DEC-017`.
- **Blocks:** none.
- **Status:** `ACCEPTED_DECISION`.

## `DEC-026` — precedence: `CONCEPT_SOURCE` vs repository product requirements

- **Context:** прежний snapshot `AGENTS.md` добавлял или конкретизировал reminder «скоро слетит», spontaneous tables, mystery-upgrade, richer allocation categories, playlists/favourites, channel posts, exports/auto-reports and cloning/templates beyond or differently from `CONCEPT_SOURCE`.
- **Source tension:** immutable source does not contain every repository promise or describes a narrower capability; silently merging either direction would change product scope.
- **Code tension:** some repository additions already have wired or partial implementations, while others are absent or unwired; code existence cannot settle product precedence.
- **Options:** (A) concept overrides all repository additions; (B) repository additions remain mandatory; (C) merge both into a separately accepted superseding product spec; (D) classify every conflict individually as keep, amend, defer or reject.
- **Recommendation:** D; эта рекомендация сама по себе не являлась принятием.
- **Selected option:** D.
- **Accepted by:** user.
- **Accepted at:** 2026-08-17.
- **Accepted policy:**
  1. `docs/product/CONCEPT_SOURCE.md` остаётся неизменяемым источником исходного замысла.
  2. `docs/product/PRODUCT_SPEC.md` становится целевой продуктовой спецификацией после внесения явно принятых решений.
  3. Каждое расхождение между концепцией, `AGENTS.md`, существующей документацией и кодом классифицируется отдельно:
     - `KEEP`
     - `AMEND`
     - `DEFER`
     - `REJECT`
  4. Наличие требования в старом `AGENTS.md` или наличие реализации в коде само по себе не делает capability обязательной частью продукта.
  5. До отдельного решения спорные capabilities нельзя:
     - удалять;
     - объявлять обязательными;
     - считать принятыми;
     - выводить в пользовательскую навигацию.
  6. `AGENTS.md` должен стать короткой картой repository, product docs, engineering rules и обязательных проверок без дублирования полной продуктовой спецификации.
- **Consequences:**
  - conflicts классифицируются item-by-item;
  - наличие code не определяет product priority;
  - прежний `AGENTS.md` snapshot не является target product spec;
  - disputed capabilities остаются unresolved до отдельных item-level решений;
  - `AGENTS.md` превращается в repository map.
- **Depends on:** none.
- **Blocks:** none at governance-method level; `DEC-019`, `DEC-023` и конкретные conflict items сохраняют собственные unresolved decisions.
- **Status:** `ACCEPTED_DECISION`.

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

## `DEC-028` — reminder «скоро слетит»

- **Context:** прежний repository requirement выделял отдельное уведомление «скоро слетит» рядом с booking/HOLD flow.
- **Source tension:** source допускает общее напоминание о существующей брони и описывает HOLD, post-arrival retention, no-show и release, но не определяет отдельный expiry-reminder trigger.
- **Code tension:** booking/HOLD lifecycle остаётся частичным, а наличие operational notification primitives не доказывает canonical trigger, timing, audience или anti-spam contract для такого reminder.
- **Available classifications:** `KEEP`, `AMEND`, `DEFER`, `REJECT`.
- **Selected classification:** `AMEND`.
- **Accepted by:** user.
- **Accepted at:** 2026-08-18.
- **Accepted contract:**
  - Сохраняется идея уведомления перед canonical истечением брони.
  - Capability привязывается к будущему canonical booking lifecycle.
  - Пока не принято, относится ли reminder:
    - к истечению HOLD;
    - к завершению arrival retention;
    - к обоим событиям.
  - Trigger, timing, recipients, channel, repeat policy и anti-spam определяются вместе с booking/HOLD lifecycle.
  - Capability не входит в первый product slice.
  - До принятия canonical lifecycle не реализуется и не выводится в navigation.
  - Конкретный trigger этим решением не выбран.
- **Consequences:** reminder является принятой amended extension, но не implementation-ready requirement; lifecycle event и delivery policy остаются unresolved implementation/product parameters.
- **Depends on:** `DEC-008`, `DEC-018`.
- **Blocks:** реализацию reminder и его появление в navigation до принятия canonical booking lifecycle.
- **Status:** `ACCEPTED_DECISION`.

## `DEC-029` — spontaneous tables: staff-only walk-in seating

- **Context:** repository называл spontaneous tables; пользователь принял более точную staff-only capability для посадки без предварительной брони.
- **Source tension:** source требует staff table seating и режимы «депозит / по счёту / от клуба», но не определяет spontaneous-table creation как отдельный product contract.
- **Code tension:** current table routes содержат spontaneous opening/session fragments, technical `WITH_QR/NO_QR` modes и путь, способный создать visit при посадке; это не соответствует автоматически canonical walk-in permissions, commercial modes, ledger, audit и entrance ordering.
- **Available classifications:** `KEEP`, `AMEND`, `DEFER`, `REJECT`.
- **Selected classification:** `AMEND`.
- **Accepted by:** user.
- **Accepted at:** 2026-08-18.
- **Accepted contract:**
  - Canonical name: staff-only walk-in seating.
  - Посадка за свободный стол без предварительной брони.
  - Создаётся table session, а не фиктивная booking.
  - Действие доступно только авторизованному staff согласно будущей принятой permission matrix.
  - Стол должен быть свободен в текущую operational night.
  - Stop-sales и другие блокировки обязательны.
  - Staff явно выбирает режим:
    - депозит;
    - по счёту;
    - от клуба.
  - Требуются confirmation и audit.
  - Денежные изменения проходят через financial ledger.
  - Night Pass может быть привязан, но не является обязательным условием самой посадки.
  - Walk-in seating не создаёт check-in, stamp или loyalty progress в обход принятого entrance/check-in contract.
  - Capability не показывается гостю как самостоятельный раздел.
  - Не входит в первый product slice.
- **Consequences:** current spontaneous-table code не считается уже соответствующим принятому контракту; implementation должен быть проверен заново против permission, operational-night, stop-sales, ledger, confirmation/audit и entrance boundaries.
- **Depends on:** `DEC-004`, `DEC-005`, `DEC-008`, `DEC-012`, `DEC-021`.
- **Blocks:** canonical walk-in implementation и staff surface до принятия permission matrix и зависимых lifecycle contracts.
- **Status:** `ACCEPTED_DECISION`.

## `DEC-030` — mystery-upgrade

- **Context:** repository requirement и отдельные code fragments упоминали mystery eligibility/upgrade вне source loyalty contract.
- **Source tension:** source включает stamps, early arrival, badges, raffles и table loyalty, но не определяет mystery-upgrade.
- **Code tension:** отдельный `mysteryEligible`-подобный flag или fragment не образует end-to-end mechanic, reward contract, anti-fraud или audit journey и не является product acceptance.
- **Available classifications:** `KEEP`, `AMEND`, `DEFER`, `REJECT`.
- **Selected classification:** `DEFER`.
- **Accepted by:** user.
- **Accepted at:** 2026-08-18.
- **Accepted contract:**
  - Сохраняется только как будущая product hypothesis.
  - Исключается из MVP.
  - Исключается из первого product slice.
  - Не появляется в target navigation.
  - Существующие или похожие code-фрагменты не считаются принятым контрактом.
  - Возврат к механике возможен только после:
    - canonical Night Pass/check-in;
    - loyalty ledger;
    - catalogue of rewards;
    - anti-fraud;
    - audit.
  - До возврата должны быть отдельно определены:
    - предмет upgrade;
    - eligibility;
    - probability/selection;
    - budget owner;
    - validity;
    - staff confirmation;
    - abuse prevention.
  - Текущего target requirement реализации mystery-upgrade нет.
- **Consequences:** `DEFER` сохраняет hypothesis для возможного будущего revisit, но не означает ни удаление, ни rejection, ни current target promise.
- **Depends on:** `DEC-011`, `DEC-013`, `DEC-021`.
- **Blocks:** никакой текущий slice; реализация остаётся заблокированной до отдельного revisit после выполнения accepted prerequisites.
- **Status:** `ACCEPTED_DECISION`.

## `DEC-031` — richer allocation categories

- **Context:** repository конкретизировал более богатое распределение депозитов и доплат сверх source examples bar/balls/50-50/other configured categories.
- **Source tension:** source требует configurable allocation, но не фиксирует canonical category catalogue, snapshot semantics или correction workflow.
- **Code tension:** current append-only deposit operations и arbitrary allocation rows дают только foundation; canonical club-level category directory, immutable historical identity and explicit correcting operation contract не доказаны end-to-end.
- **Available classifications:** `KEEP`, `AMEND`, `DEFER`, `REJECT`.
- **Selected classification:** `AMEND`.
- **Accepted by:** user.
- **Accepted at:** 2026-08-18.
- **Accepted contract:**
  - Категории распределения настраиваются на уровне клуба.
  - Каждая отдельная операция депозита или доплаты может распределяться по нескольким активным категориям.
  - Сумма allocations обязана точно совпадать с суммой операции.
  - Free-text вместо canonical category не используется.
  - Историческая операция сохраняет snapshot имени/identity категории.
  - Изменение справочника не переписывает историю.
  - Финансовая история append-only.
  - Исправление выполняется отдельной correcting operation с reason, confirmation и audit.
  - Личная охрана остаётся отдельной услугой.
  - Allocation categories не смешиваются с financial-shift revenue articles.
  - Внутреннее распределение не показывается гостю.
  - Не входит в первый product slice.
  - Initial catalogue определяется позже вместе с table/deposit configuration.
  - Стартовый набор категорий этим решением не выбран.
- **Consequences:** принято направление canonical club configuration и historical safety; initial catalogue и implementation slice остаются unresolved.
- **Depends on:** `DEC-004`, `DEC-005`, `DEC-008`, `DEC-012`.
- **Blocks:** implementation category directory, allocations и correcting flow до согласования table/deposit configuration.
- **Status:** `ACCEPTED_DECISION`.

## `DEC-032` — playlists/favourites

- **Context:** repository объединял playlists, likes/favourites, battles и stems вокруг более узкого source music catalogue/interactions contract.
- **Source tension:** source принимает DJ files, moderation, track-of-night, votes/reactions, donations and statistics, но не public or private playlist/favourite semantics.
- **Code tension:** wired playlists/likes/battles/stems APIs и tests не доказывают canonical served UI, DJ role, privacy, moderation/content-rights boundary или принятие всех этих mechanics единым контрактом.
- **Available classifications:** `KEEP`, `AMEND`, `DEFER`, `REJECT`.
- **Selected classification:** `AMEND`.
- **Accepted by:** user.
- **Accepted at:** 2026-08-18.
- **Accepted contract:**
  - Контракт разделяется на две capability.
  - **Private favourites:**
    - Личные приватные bookmarks гостя.
    - Можно сохранять только опубликованные tracks/sets.
    - Favourites не обходят moderation, hiding или access policy.
    - Aggregated statistics допустима.
    - Список конкретных пользователей не раскрывается DJ или другим гостям.
    - Рассматривается после read-only music catalogue.
  - **Curated playlists:**
    - Создаются только авторизованным DJ или administrator.
    - Curated playlists связаны с club, event или operational night.
    - Подчиняются moderation и content rights.
    - Рассматриваются после принятия DJ role и authoring contract.
  - Не входят в текущий scope:
    - public user-created playlists;
    - collaborative editing;
    - social music catalogue.
  - Обе capability:
    - не входят в первый product slice;
    - не появляются в target navigation до music phase.
  - Существующие playlists/likes/battles/stems не считаются единым принятым контрактом.
  - Battles/stems этим решением не принимаются.
- **Consequences:** music phase получает две bounded extensions; privacy, DJ authorization, moderation and rights обязательны, а public/collaborative/social scope и battles/stems остаются вне решения.
- **Depends on:** `DEC-004`, `DEC-005`, `DEC-014`.
- **Blocks:** favourites и curated-playlist implementation/navigation до соответствующих catalogue и DJ-authoring prerequisites.
- **Status:** `ACCEPTED_DECISION`.

## `DEC-033` — channel posts

- **Context:** repository requirement предлагал channel posts рядом с source draft/preview and broadcast capabilities.
- **Source tension:** source разрешает iBota готовить announcement draft и требует preview/confirmation для communications, но не задаёт authority для generic Telegram channel publishing.
- **Code tension:** operational notifications wired, а campaign/outbox/scheduler fragments present but unwired; ни один fragment не доказывает allowlisted channel authority, separate permission, confirmed-content audit или accepted channel-post workflow.
- **Available classifications:** `KEEP`, `AMEND`, `DEFER`, `REJECT`.
- **Selected classification:** `AMEND`.
- **Accepted by:** user.
- **Accepted at:** 2026-08-18.
- **Accepted contract:**
  - Публикация разрешена только в заранее подключённые Telegram channels клуба или сети.
  - Требуется отдельное communications permission.
  - iBota может подготовить draft, но не публикует самостоятельно.
  - Обязательны preview и explicit confirmation.
  - Произвольный channel/chat ID из пользовательского текста не является authority.
  - Запрещена публикация PII и внутренних staff-only данных.
  - Audit фиксирует:
    - actor;
    - target channel;
    - подтверждённую версию content;
    - send result.
  - Edit/delete опубликованного сообщения является отдельным подтверждаемым действием.
  - Channel posts отделены от:
    - personal notifications;
    - segmented broadcasts.
  - Automatic scheduling этим решением не принимается.
  - Не входит в первый product slice.
  - Появляется только в communications phase.
- **Consequences:** принято только human-confirmed allowlisted publishing; automatic scheduling и authority из свободного текста исключены из accepted scope.
- **Depends on:** `DEC-004`, `DEC-005`, `DEC-006`.
- **Blocks:** channel-post implementation до communications permission, allowlist, preview/confirmation и audit contract.
- **Status:** `ACCEPTED_DECISION`.

## `DEC-034` — exports/auto-reports

- **Context:** repository requirement объединял export formats и automatic reports поверх source role analytics.
- **Source tension:** source задаёт role-scoped reports and analytic queries, но не определяет export artifact metadata, frozen/non-final semantics или scheduled delivery.
- **Code tension:** deterministic analytics snapshots и finance freeze foundations существуют частично, но on-demand export, sensitive-artifact access audit, scheduled delivery и AI narrative grounding contract не доказаны.
- **Available classifications:** `KEEP`, `AMEND`, `DEFER`, `REJECT`.
- **Selected classification:** `AMEND`.
- **Accepted by:** user.
- **Accepted at:** 2026-08-18.
- **Accepted contract:**
  - Контракт разделяется на две capability.
  - **On-demand exports:**
    - Запускаются только авторизованным role/scope principal.
    - Формируются из canonical report.
    - Используют зафиксированные:
      - filters;
      - period;
      - club/network scope;
      - timezone.
    - Содержат metadata:
      - generated-at;
      - report version;
      - filters;
      - scope;
      - timezone.
    - Financial export после shift close использует frozen data.
    - Незакрытый operational report явно помечается non-final.
    - PII включается только при отдельном permission и реальной operational need; иначе маскируется или исключается.
    - Создание и доступ к sensitive export аудируются.
    - CSV/XLSX/PDF и другие форматы выбираются в implementation slice, не здесь.
  - **Scheduled auto-reports:**
    - Разрешаются только после появления canonical metrics и принятого communications delivery contract.
    - Schedule настраивается уполномоченной ролью.
    - Получатели и channels заранее allowlisted.
    - Требуются:
      - test delivery;
      - explicit enablement;
      - result log;
      - bounded retry;
      - deduplication.
    - AI narrative не подменяет source numbers.
    - AI narrative требует отдельного принятого AI grounding contract.
  - Обе capability:
    - не входят в первый product slice;
    - не появляются в target navigation до analytics/communications phase.
- **Consequences:** on-demand export предшествует scheduled delivery; formats остаются implementation choice, sensitive data получает separate permission/audit boundary, а AI narrative остаётся blocked до принятия grounding contract.
- **Depends on:** `DEC-004`, `DEC-005`, `DEC-006`, `DEC-012`; scheduled delivery additionally depends on an accepted communications delivery contract, and AI narrative additionally depends on `DEC-027` becoming accepted.
- **Blocks:** export/report delivery implementation до canonical reports/metrics, communications delivery и соответствующих authorization/privacy contracts.
- **Status:** `ACCEPTED_DECISION`.

## `DEC-035` — cloning/templates

- **Context:** repository requirement предлагал templates/cloning как ускорение source Add Club master.
- **Source tension:** source требует no-code onboarding нового клуба, но не задаёт reusable template, cloning, snapshot selection или prohibited-data boundary.
- **Code tension:** current club/hall/table/finance configuration CRUD fragments не образуют versioned template or create-from-existing workflow и не доказывают secret/PII/runtime exclusion.
- **Available classifications:** `KEEP`, `AMEND`, `DEFER`, `REJECT`.
- **Selected classification:** `AMEND`.
- **Accepted by:** user.
- **Accepted at:** 2026-08-18.
- **Accepted contract:**
  - Контракт состоит из двух безопасных механизмов.
  - **Versioned configuration templates:**
    - Шаблон содержит только allowlisted club configuration.
    - Создаётся и публикуется Owner или уполномоченной GLOBAL role.
    - Может содержать только явно разрешённые categories.
    - Изменение template не меняет уже созданные clubs.
    - Применение создаёт draft.
    - Перед применением обязательны:
      - validation;
      - preview;
      - explicit confirmation;
      - audit.
  - **Create from existing club:**
    - Создаёт новый club draft из snapshot выбранного клуба.
    - Не создаёт live-link или дальнейшую синхронизацию.
    - Каждая категория copying выбирается явно.
    - Новый club получает новые IDs и независимую configuration.
  - Запрещено копировать:
    - Telegram tokens;
    - webhook secrets;
    - credentials;
    - signing/QR keys;
    - staff assignments;
    - user roles;
    - guests и PII;
    - bookings;
    - HOLD;
    - GuestList;
    - Night Pass;
    - visits;
    - table sessions;
    - financial operations;
    - financial shifts;
    - audit logs;
    - idempotency records;
    - runtime incidents;
    - delivery history.
  - Content/media копируется только отдельной явной опцией и после проверки rights.
  - Capability относится к onboarding/network-scaling phase.
  - Не входит в первый product slice.
  - Не появляется в guest navigation.
- **Consequences:** accepted scope ограничен draft-producing allowlisted snapshot mechanisms; live synchronization и перенос secrets, runtime, staff, user, financial, audit and delivery state запрещены.
- **Depends on:** `DEC-003`, `DEC-004`, `DEC-005`, `DEC-006`, `DEC-008`.
- **Blocks:** template/cloning implementation до canonical configuration models, category allowlist, authorization, validation, preview, confirmation and audit.
- **Status:** `ACCEPTED_DECISION`.

## `DEC-036` — first product slice: Private Support Loop

- **Context:** product baseline содержал только recommended first slice; пользователь явно принимает bounded Private Support Loop как первый product slice, не объявляя его реализованным.
- **Source tension:** source задаёт guest question categories, internal inbox, status catalogue и staff reply, но не определяет first-slice boundary, fresh-user provisioning, exact transition graph, staff permission boundary или delivery/audit acceptance.
- **Code tension:** production содержит `/start`, `/ask`, club selection, ticket/message persistence, staff list/reply API и Telegram delivery primitives, но `/ask` требует существующего application user; served staff surface, accepted lifecycle, semantic permissions, support audit и truthful delivery result отсутствуют end-to-end.
- **Options:** (A) accept bounded Private Support Loop; (B) revise the boundary; (C) defer implementation.
- **Selected option:** A.
- **Accepted by:** user.
- **Accepted at:** 2026-08-18.
- **Accepted contract:**
  - **Product boundary:** первый product slice — Private Support Loop для одного реального пилотного клуба.
  - **Visible flow:** `/start → idempotent provisioning минимальной application identity → private /ask → club selection из production data → support category selection → persisted ticket → persisted initial message → minimal staff inbox → ticket detail → staff reply → Telegram delivery гостю → RESOLVED / CLOSED по DEC-025`.
  - **Fresh-user provisioning:**
    - Fresh Telegram user проходит flow без предварительной регистрации.
    - Provisioning минимален и не является full registration/profile flow.
    - Provisioning keyed by `telegram_user_id`.
    - Последовательные повторы сходятся к одной logical identity.
    - Конкурентные/retry вызовы сходятся к одной logical identity.
    - В БД остаётся одна user row для одного `telegram_user_id`.
    - Необработанные unique-constraint/SQL errors наружу не выходят.
    - Concrete concurrency mechanism этим решением не выбирается.
    - Username/display name могут использоваться только если переданы и нужны.
    - Phone и rich profile не являются prerequisite slice.
  - **Staff authority:**
    - Inbox/view/reply/status доступны только `MANAGER` и `CLUB_ADMIN`.
    - Доступ действует только в CLUB scope соответствующего клуба.
    - Доступ действует только через explicit permissions.
    - `ENTRY_MANAGER` доступа не получает.
    - `OWNER` не получает operational reply permission автоматически.
    - Unauthorized staff не видит ticket и не может ответить.
  - **Lifecycle:** используется exact `DEC-025` lifecycle `NEW → IN_PROGRESS → RESOLVED → CLOSED`; new guest message from `RESOLVED` returns ticket to `IN_PROGRESS`; `CLOSED` terminal в первом release; manual reopen отсутствует.
  - **Persistence and delivery:**
    - Ticket сохраняется в БД.
    - Initial message сохраняется в БД.
    - Staff replies сохраняются в БД.
    - Данные переживают process restart.
    - Reply доставляется гостю через Telegram.
    - Delivery result наблюдаем и аудируется.
    - Delivery failure не должен выдавать ложный success.
  - **Staff surface:** minimal staff inbox может быть отдельным bounded surface и не зависит от полного canonical Mini App redesign; required minimum:
    - ticket list;
    - club-scoped filtering;
    - ticket detail;
    - thread;
    - reply action;
    - take-in-work action;
    - resolve action;
    - close action;
    - permission denial.
  - **Audit:** staff reply, status change, close и Telegram delivery result аудируются; audit не дублирует message body.
  - **Explicitly excluded:**
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
  - **Product status:** slice accepted by product boundary but not implemented; canonical status `ACCEPTED_NOT_IMPLEMENTED`.
  - **Implementation boundary:** до отдельной implementation-задачи production code не меняется.
- **Consequences:** Private Support Loop становится первым accepted product slice и получает canonical slice specification; acceptance не меняет current production status и оставляет весь code/tests delivery отдельной implementation-задаче.
- **Depends on:** `DEC-003`, `DEC-004`, `DEC-005`, `DEC-007`, `DEC-017`, `DEC-025`.
- **Blocks:** implementation первого slice до отдельной design/code/test/review задачи.
- **Status:** `ACCEPTED_DECISION`.
