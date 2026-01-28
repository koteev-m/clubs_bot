# P2.5 discovery: audit-log + invariants hooks

Ниже — короткая фиксирующая заметка по текущим точкам действий/инвариантам, чтобы дальнейшая интеграция audit-log и enforcement не опиралась на догадки.

## 1) Где живут ключевые действия (эндпоинты/сервисы)

### Бронирования (create/update/cancel)
- **Новый miniapp поток (A3):** `/api/clubs/{clubId}/bookings/hold`, `/confirm`, `/api/bookings/{id}/plus-one` → `BookingA3Routes`. Реальные операции выполняет `BookingState` (in-memory orchestration), а аудит сейчас пишется через лог `booking.audit ...` в самом роуте. `Idempotency-Key` обязателен, идёт rate-limit + базовые валидации. 【F:app-bot/src/main/kotlin/com/example/bot/routes/BookingA3Routes.kt†L1-L330】
- **RBAC/legacy HTTP API:** `/api/clubs/{clubId}/bookings/hold|confirm|{bookingId}/seat|{bookingId}/no-show` → `SecuredBookingRoutes` → `BookingService` (core data). Тут есть статусные переходы, outbox, Ops-уведомления и audit-log через `AuditLogRepository`. 【F:app-bot/src/main/kotlin/com/example/bot/routes/SecuredBookingRoutes.kt†L1-L170】【F:app-bot/src/main/kotlin/com/example/bot/booking/BookingService.kt†L1-L318】
- **Deprecated legacy API:** `/bookings/hold|confirm|{id}/cancel|seat/qr` → `BookingRoutes` → `booking.legacy.BookingService` (удержания/подтверждение/отмена/посадка). 【F:app-bot/src/main/kotlin/com/example/bot/routes/BookingRoutes.kt†L1-L86】【F:core-domain/src/main/kotlin/com/example/bot/booking/legacy/BookingService.kt†L1-L196】
- **Отмена гостем через Telegram:** `MyBookingsService.cancel` обновляет статус, пишет outbox `booking.cancelled`, логирует факт отмены. 【F:app-bot/src/main/kotlin/com/example/bot/telegram/bookings/MyBookingsService.kt†L70-L198】
- **Cancel/refund в платежах:** `/api/clubs/{clubId}/bookings/{bookingId}/cancel` и `/refund` → `PaymentsCancelRefundRoutes` (через `PaymentsService`). 【F:app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt†L1-L200】

### Check-in / arrival / entry scan
- **Host check-in (miniapp):** `/api/host/checkin` + `/scan` + `/search` → `HostCheckinRoutes` → `CheckinService`. RBAC: ENTRY_MANAGER / MANAGER / CLUB_ADMIN / HEAD_MANAGER / OWNER / GLOBAL_ADMIN. 【F:app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt†L1-L220】
- **Guest-list QR scan (miniapp):** `/api/clubs/{clubId}/checkin/scan` → `CheckinRoutes`. Проверки QR/TTL/arrival window, запись ARRIVED, метрики. RBAC: CLUB_ADMIN / MANAGER / ENTRY_MANAGER. 【F:app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt†L1-L320】
- **Core check-in обработка:** `CheckinServiceImpl` (booking QR + invitations + entry updates). Именно здесь лежит важная логика по доступу, статусам и вставке чек-инов. 【F:core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt†L1-L240】
- **Legacy compat заглушка:** `/api/checkin/qr` → всегда forbidden. 【F:app-bot/src/main/kotlin/com/example/bot/routes/CheckinCompatRoutes.kt†L1-L17】

### Гостевые списки / роли / промоутеры
- **Guest lists:** `/api/guest-lists` (list/search/export/import/arriveByName) → `GuestListRoutes` (RBAC: OWNER/GLOBAL_ADMIN/HEAD_MANAGER/CLUB_ADMIN/MANAGER/ENTRY_MANAGER/PROMOTER). 【F:app-bot/src/main/kotlin/com/example/bot/routes/GuestListRoutes.kt†L1-L190】
- **Invites (QR/start params):** `/api/guest-lists/{listId}/entries/{entryId}/invite` → `GuestListInviteRoutes`. 【F:app-bot/src/main/kotlin/com/example/bot/routes/GuestListInviteRoutes.kt†L1-L160】
- **Promoter guest lists / booking assign:** `/api/promoter/...` → `PromoterGuestListRoutes` (создание списков, bulk invite, assignment к booking). 【F:app-bot/src/main/kotlin/com/example/bot/routes/PromoterGuestListRoutes.kt†L1-L220】
- **Promoter admin / quotas / rating:** отдельные admin и promoter маршруты (`PromoterAdminRoutes`, `PromoterQuotasAdminRoutes`, `PromoterRatingRoutes`). Они дают управление квотами/доступом. 【F:app-bot/src/main/kotlin/com/example/bot/routes/PromoterAdminRoutes.kt†L1-L120】【F:app-bot/src/main/kotlin/com/example/bot/routes/PromoterQuotasAdminRoutes.kt†L1-L120】【F:app-bot/src/main/kotlin/com/example/bot/routes/PromoterRatingRoutes.kt†L1-L120】

### Столы / депозиты
- **Admin tables/halls/layout:** `AdminTablesRoutes`, `AdminHallsRoutes`, `LayoutRoutes`, `HallPlanRoutes` — CRUD столов/зал/плана. 【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminTablesRoutes.kt†L1-L220】【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminHallsRoutes.kt†L1-L120】【F:app-bot/src/main/kotlin/com/example/bot/routes/LayoutRoutes.kt†L1-L120】【F:app-bot/src/main/kotlin/com/example/bot/routes/HallPlanRoutes.kt†L1-L140】
- **Минимальные депозиты:** используются в booking flow (`BookingService`, legacy `BookingService`) и в payments. 【F:app-bot/src/main/kotlin/com/example/bot/booking/BookingService.kt†L1-L190】【F:core-domain/src/main/kotlin/com/example/bot/booking/legacy/BookingService.kt†L40-L150】

### Финансы / закрытие смены
- **Payments:** cancel/refund (см. выше) и core-domain `PaymentsService` (confirm/invoice). 【F:app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt†L1-L200】【F:core-domain/src/main/kotlin/com/example/bot/payments/PaymentsService.kt†L1-L120】
- **Shift close:** явного «закрытия смены» нет, но есть `ShiftChecklistService` для host checklist (in-memory). 【F:app-bot/src/main/kotlin/com/example/bot/host/ShiftChecklistService.kt†L1-L120】

## 2) Паттерны

### ensureMiniAppNoStoreHeaders
- Базовая функция — `ApplicationCall.ensureMiniAppNoStoreHeaders()` (Cache-Control: no-store + Vary: X-Telegram-Init-Data). 【F:app-bot/src/main/kotlin/com/example/bot/http/NoStoreHeaders.kt†L1-L24】
- Применяется в miniapp маршрутах: `/api/admin/*` (разные Admin* routes via `intercept(ApplicationCallPipeline.Setup)`), `/api/me/*` (MusicLikesRoutes), `/api/music/*` (MusicRoutes/MusicLikesRoutes). 【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminMusicRoutes.kt†L1-L60】【F:app-bot/src/main/kotlin/com/example/bot/routes/MusicLikesRoutes.kt†L36-L140】【F:app-bot/src/main/kotlin/com/example/bot/routes/MusicRoutes.kt†L1-L120】
- В `JsonErrorPages` headers добавляются для /api/admin (при ошибках). 【F:app-bot/src/main/kotlin/com/example/bot/plugins/JsonErrorPages.kt†L1-L94】

### ErrorCodes / respondError / JsonErrorPages
- `ErrorCodes` и registry: единый список + `/api/.well-known/errors`. 【F:app-bot/src/main/kotlin/com/example/bot/http/ErrorCodes.kt†L1-L120】【F:app-bot/src/main/kotlin/com/example/bot/http/ErrorRegistry.kt†L1-L120】
- `respondError` централизует JSON-ошибки, устанавливает `requestId` и маркирует обработку. 【F:app-bot/src/main/kotlin/com/example/bot/http/ApiError.kt†L1-L41】
- `JsonErrorPages` конвертирует типовые статусы/исключения в JSON-ошибки для `/api/*`. 【F:app-bot/src/main/kotlin/com/example/bot/plugins/JsonErrorPages.kt†L1-L94】

### Flyway миграции (пути и нумерация)
- Основные миграции лежат в `core-data/src/main/resources/db/migration/postgresql` и `.../h2`, общий слой — `.../common`. Имена формата `V{n}__description.sql`, с примерами `V0__...`, `V020__...`, `V032__...`. 【F:core-data/src/main/resources/db/migration/postgresql/V0__enable_uuid_extensions.sql†L1-L1】【F:core-data/src/main/resources/db/migration/postgresql/V020__guest_list_status_check_name_fix.sql†L1-L1】【F:core-data/src/main/resources/db/migration/h2/V032__music_assets.sql†L1-L1】【F:core-data/src/main/resources/db/migration/common/V6__telegram_notify_endpoints.sql†L1-L1】
- Есть отдельная dev миграция в app-bot: `app-bot/src/main/resources/db.migration.postgresql/V2__seed_dev_data.sql`. 【F:app-bot/src/main/resources/db.migration.postgresql/V2__seed_dev_data.sql†L1-L1】
- Запуск/конфиг Flyway централизован в `MigrationsPlugin` + `MigrationRunner/FlywayConfig`. 【F:app-bot/src/main/kotlin/com/example/bot/plugins/MigrationsPlugin.kt†L1-L120】【F:core-data/src/main/kotlin/com/example/bot/data/db/MigrationRunner.kt†L1-L120】

### Exposed: newSuspendedTransaction(Dispatchers.IO, db)
- Базовый паттерн в core-data: `DbTransactions` (экзекутор через `newSuspendedTransaction(context = Dispatchers.IO, db = database)`). 【F:core-data/src/main/kotlin/com/example/bot/data/db/DbTransactions.kt†L55-L80】
- Прямые вызовы в репозиториях (например `GuestListRepositories`, `BookingRepository`, `Music` репо и др.) и в `MyBookingsService`. 【F:core-data/src/main/kotlin/com/example/bot/data/club/GuestListRepositories.kt†L120-L180】【F:core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingRepository.kt†L60-L140】【F:app-bot/src/main/kotlin/com/example/bot/telegram/bookings/MyBookingsService.kt†L112-L188】

## 3) RBAC (ключевые роли + check-in)
- Полный перечень ролей: `Role` в core-data (`OWNER`, `GLOBAL_ADMIN`, `HEAD_MANAGER`, `CLUB_ADMIN`, `MANAGER`, `ENTRY_MANAGER`, `PROMOTER`, `GUEST`). 【F:core-data/src/main/kotlin/com/example/bot/data/security/Role.kt†L1-L14】
- **Право check-in:**
  - `/api/host/checkin` — роли ENTRY_MANAGER/MANAGER/CLUB_ADMIN/HEAD_MANAGER/OWNER/GLOBAL_ADMIN. 【F:app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt†L71-L96】
  - `/api/clubs/{clubId}/checkin/scan` — роли CLUB_ADMIN/MANAGER/ENTRY_MANAGER. 【F:app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt†L55-L88】
  - Core enforcement: `CheckinServiceImpl.hasEntryManagerRole` (проверка ролей внутри сервиса). 【F:core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt†L1-L110】

## 4) Логирование: запреты и grep‑проверки
- Явные запреты на логирование initData/секретов/PII + allowed данные (идентификаторы/sha256/size) задокументированы в `docs/security.md` и `docs/README_pilot_QR.md`. 【F:docs/security.md†L39-L118】【F:docs/README_pilot_QR.md†L70-L120】
- Audit grep‑чеклист: `docs/security.md` (rg-паттерны). 【F:docs/security.md†L102-L118】

## 5) Куда вставлять audit‑log (конкретные точки интеграции)
- **Booking (A3):** `BookingA3Routes` — на hold/confirm/plus-one (сейчас логит `booking.audit` через logger). Лог‑хук можно заменить/дополнить записью в audit repository. 【F:app-bot/src/main/kotlin/com/example/bot/routes/BookingA3Routes.kt†L90-L320】
- **Booking (core + legacy):** `BookingService` (hold/confirm/finalize/seat/no-show) уже пишет `auditLogRepository.log(...)`, доп. инварианты можно фиксировать здесь. Legacy `booking.legacy.BookingService.cancel/seatByQr`. 【F:app-bot/src/main/kotlin/com/example/bot/booking/BookingService.kt†L170-L318】【F:core-domain/src/main/kotlin/com/example/bot/booking/legacy/BookingService.kt†L120-L190】
- **Telegram cancellation:** `MyBookingsService.cancel` (и outbox `booking.cancelled`). 【F:app-bot/src/main/kotlin/com/example/bot/telegram/bookings/MyBookingsService.kt†L70-L210】
- **Payments cancel/refund:** `PaymentsCancelRefundRoutes` → `PaymentsService`. 【F:app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt†L1-L200】
- **Check-in:** `HostCheckinRoutes` → `CheckinServiceImpl` (booking QR / invite / entry transitions). 【F:app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt†L71-L200】【F:core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt†L70-L240】
- **Guest list actions:** `GuestListRoutes` (arriveByName, import/export), `GuestListInviteRoutes` (invite issuance), `PromoterGuestListRoutes` (create list/entry/invite/assign booking). 【F:app-bot/src/main/kotlin/com/example/bot/routes/GuestListRoutes.kt†L130-L190】【F:app-bot/src/main/kotlin/com/example/bot/routes/GuestListInviteRoutes.kt†L40-L140】【F:app-bot/src/main/kotlin/com/example/bot/routes/PromoterGuestListRoutes.kt†L1-L220】

## 6) Инварианты, реально enforced кодом (не только docs)
- **Idempotency-Key обязателен** для booking hold/confirm/plus-one (A3) + legacy routes, с валидацией формата. 【F:app-bot/src/main/kotlin/com/example/bot/routes/BookingA3Routes.kt†L64-L188】【F:app-bot/src/main/kotlin/com/example/bot/routes/SecuredBookingRoutes.kt†L20-L102】
- **Booking статусные переходы**: `BookingService.updateStatus` разрешает seat/no-show только из `BOOKED`; иначе конфликт. 【F:app-bot/src/main/kotlin/com/example/bot/booking/BookingService.kt†L218-L318】
- **Guest-list check-in window**: `CheckinRoutes`/`GuestListRoutes` enforce arrivalWindow (late override только для CALLED). 【F:app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt†L200-L320】【F:app-bot/src/main/kotlin/com/example/bot/routes/GuestListRoutes.kt†L150-L188】
- **QR validity**: `CheckinRoutes`/`QrGuestListCodec` и booking QR verify — invalid/expired/format checks enforced. 【F:app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt†L140-L260】【F:core-domain/src/main/kotlin/com/example/bot/booking/a3/QrBookingCodec.kt†L1-L120】
- **RBAC + club scope**: `authorize` + `clubScoped` в booking/checkin/guest list routes. 【F:app-bot/src/main/kotlin/com/example/bot/routes/SecuredBookingRoutes.kt†L17-L112】【F:app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt†L73-L156】【F:app-bot/src/main/kotlin/com/example/bot/routes/GuestListRoutes.kt†L35-L120】

## 7) Какие данные безопасно логировать
- **Можно:** идентификаторы (clubId/listId/entryId/bookingId/userId), статусы, counts/size/sha256, requestId. 【F:docs/security.md†L39-L78】
- **Нельзя:** initData, QR токены/секреты, auth headers, имена гостей/PII, raw payloads. 【F:docs/security.md†L39-L70】【F:docs/README_pilot_QR.md†L78-L110】

## 8) Файлы-кандидаты для P2.5-1..P2.5-4

Планируемые точки вмешательства для audit-log/invariants:
- `app-bot/src/main/kotlin/com/example/bot/routes/BookingA3Routes.kt`
- `app-bot/src/main/kotlin/com/example/bot/booking/BookingService.kt`
- `app-bot/src/main/kotlin/com/example/bot/routes/SecuredBookingRoutes.kt`
- `app-bot/src/main/kotlin/com/example/bot/routes/BookingRoutes.kt` (legacy)
- `app-bot/src/main/kotlin/com/example/bot/telegram/bookings/MyBookingsService.kt`
- `app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt`
- `app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt`
- `app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt`
- `core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt`
- `app-bot/src/main/kotlin/com/example/bot/routes/GuestListRoutes.kt`
- `app-bot/src/main/kotlin/com/example/bot/routes/GuestListInviteRoutes.kt`
- `app-bot/src/main/kotlin/com/example/bot/routes/PromoterGuestListRoutes.kt`
- `core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingRepository.kt`
- `core-data/src/main/kotlin/com/example/bot/data/club/GuestListRepositories.kt`

