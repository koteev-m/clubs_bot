# Инварианты и аудит (P2.5)

Документ фиксирует текущие инварианты чек-ина и контракт audit-log, согласованный с реальной реализацией.

## Инварианты чек-ина

### Роли и доступ

- **Miniapp guest-list QR** (`/api/clubs/{clubId}/checkin/scan`) доступен только для ролей `CLUB_ADMIN`, `MANAGER`, `ENTRY_MANAGER` и проходит `clubScoped(ClubScope.Own)`.【F:app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt†L55-L89】
- **Host check-in** (`/api/host/checkin`, `/api/host/checkin/scan`) также ограничен теми же ролями и проверкой доступа к клубу (`canAccessClub`).【F:app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt†L87-L155】
- Внутри сервисного слоя контроль ролей дублируется: **разрешены только** `ENTRY_MANAGER`, `MANAGER`, `CLUB_ADMIN`. Для `hostCheckin`/`hostScan` и `handleInvitationCheckin` при нарушении ролей возвращается `Failure(CHECKIN_FORBIDDEN)`, а для `scanQr`/`manualCheckin` — `Success(CheckinResult.Forbidden)` (это не ошибка сервиса, а результат запрета).【F:core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt†L58-L296】【F:core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt†L727-L750】

### «Штампы» (checkins) и неизменяемость

- В таблице `checkins` один «штамп» на субъект: `UNIQUE (subject_type, subject_id)` — повторный чек-ин возвращает `AlreadyUsed`/`ALREADY_USED` и не создаёт новую запись.【F:core-data/src/main/resources/db/migration/postgresql/V017__guest_list_invites_checkins.sql†L70-L90】【F:core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt†L147-L171】【F:core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt†L343-L385】
- Поля «штампа» фиксированы на момент записи: `occurred_at` (момент чек-ина) и `created_at` (момент вставки). Для чек-ина через сервис используется `Instant.now(clock).truncatedTo(SECONDS)` как `occurred_at`.【F:core-data/src/main/resources/db/migration/postgresql/V017__guest_list_invites_checkins.sql†L70-L90】【F:core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt†L168-L176】
- Внутренняя консистентность `deny_reason`: **обязателен только при `DENIED`**, иначе должен быть `NULL`. Это проверяется как в сервисе, так и на уровне БД (CHECK constraint).【F:core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt†L239-L255】【F:core-data/src/main/resources/db/migration/postgresql/V018__guest_list_schema_polish.sql†L41-L56】

### Статусы и переходы

- **Бронирования (BOOKING):** чек-ин допускается только из статуса `BOOKED`. Любые другие статусы дают отказ (`INVALID_STATUS`).【F:core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt†L356-L395】
- **Guest list entries:** статусы `ARRIVED/LATE/CHECKED_IN` считаются уже отмеченными, а `DENIED/NO_SHOW/EXPIRED` — терминальными, и повторный чек-ин запрещён.【F:core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt†L58-L68】【F:core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt†L470-L535】
- Метод чек-ина ограничен `QR`/`NAME`, а результат — `ARRIVED/LATE/DENIED` (constraint в БД).【F:core-data/src/main/resources/db/migration/postgresql/V017__guest_list_invites_checkins.sql†L70-L90】

### Ранний/поздний приход

- **Miniapp guest-list scan:** чек-ин возможен только внутри окна `arrivalWindowStart..arrivalWindowEnd`. Если запись помечена `CALLED`, то допустим ранний приход (выход за окно разрешён). Вне окна возвращается `outside_arrival_window`.【F:app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt†L275-L313】
- **CheckinServiceImpl:** результат `ARRIVED`/`LATE` определяется по `arrivalWindowEnd + lateGraceMinutes` (по умолчанию 15 минут). Отдельной блокировки раннего прихода здесь нет — это проверяется на уровне miniapp-роутов, а не сервиса.【F:core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt†L846-L858】【F:core-data/src/main/kotlin/com/example/bot/checkin/CheckinModels.kt†L12-L27】

## Столы и депозиты (table_sessions, table_deposits)

### Термины и сущности

- **Операционный стол** — сущность, с которой работают админ-операции «посадить/освободить стол». Source of truth — таблицы layout-холла (`hall_tables`) через `AdminTablesRepository`/`LayoutDbRepository` (используются для `GET /api/admin/clubs/{clubId}/nights/{nightStartUtc}/tables`).【F:core-data/src/main/kotlin/com/example/bot/data/layout/LayoutTables.kt†L21-L43】【F:core-data/src/main/kotlin/com/example/bot/data/layout/LayoutDbRepository.kt†L63-L112】【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt†L129-L184】
- **Session** (`table_sessions`) — факт занятости стола в конкретную ночь (`night_start_utc`). Статусы: `OPEN`/`CLOSED`. Уникальность гарантируется связкой `club_id + night_start_utc + table_id + open_marker`, где `open_marker=1` только для `OPEN`, поэтому **одна открытая сессия на стол в ночь** — инвариант уровня БД.【F:core-data/src/main/resources/db/migration/postgresql/V036__table_sessions_deposits.sql†L1-L14】【F:core-data/src/main/resources/db/migration/postgresql/V037__table_sessions_deposits_constraints.sql†L1-L7】【F:core-data/src/main/kotlin/com/example/bot/data/booking/TableSessionDepositRepositories.kt†L66-L148】
- **Deposit** (`table_deposits`) — факт внесения депозита для сессии стола и конкретной ночи (FK в `table_sessions` + дублирование `club_id`, `night_start_utc`, `table_id` под консистентность; FK с `ON DELETE CASCADE`, чтобы удаление сессии предсказуемо удаляло связанные депозиты). Поля:
  - `guest_user_id` может быть `NULL` для NO_QR сценариев или когда гость не резолвится по QR (без геймификации/visit-метрик).
  - `booking_id` может быть `NULL` для ручных посадок без привязки к бронированию.
  - `payment_id` nullable и указывает на запись в `payments` при наличии платежного чека; FK определён в миграции `V036__table_sessions_deposits.sql` и настроен `ON DELETE SET NULL`, используется для reconciliation с платежами при наличии значения.【F:core-data/src/main/resources/db/migration/postgresql/V036__table_sessions_deposits.sql†L25-L49】【F:core-data/src/main/kotlin/com/example/bot/data/booking/BookingTables.kt†L80-L112】
- **Allocations** (`table_deposit_allocations`) — разбиение депозита по категориям. Инварианты:
  - суммы `allocations.amount_minor` должны строго равняться `deposit.amount_minor`;
  - категории нормализуются в UPPERCASE и должны быть уникальными в рамках депозита;
  - суммы и депозит не могут быть отрицательными.【F:core-data/src/main/kotlin/com/example/bot/data/booking/TableSessionDepositRepositories.kt†L304-L409】

### Сценарии

#### A) WITH_QR

1. **Резолв гостя**: `GuestQrResolver` валидирует формат/TTL QR, проверяет соответствие клубу, грузит guest-list entry и пытается найти пользователя по `telegramUserId`. При успехе возвращает `guestUserId` и `eventId` списка.【F:app-bot/src/main/kotlin/com/example/bot/tables/GuestQrResolver.kt†L36-L105】
2. **Создание session + deposit**:
   - `/seat` использует `openSessionIdempotent` (возвращает `sessionCreated`) и `ensureSeatDepositIdempotent` (возвращает `depositCreated`). В `ensureSeatDepositIdempotent` берётся блокировка строки `table_sessions` через `SELECT ... FOR UPDATE` перед проверкой/вставкой депозита — это механизм дедупликации при конкурентных `POST /seat`.【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt†L171-L352】【F:core-data/src/main/kotlin/com/example/bot/data/booking/TableSessionDepositRepositories.kt†L66-L243】
   - Если депозит уже существует — возвращаются существующие `sessionId`/`depositId`, HTTP `200 OK`, **без побочных эффектов** и без изменения суммы/allocations. Изменение депозита выполняется только через `PUT /deposits/{depositId}`.【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt†L171-L362】【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt†L429-L497】
   - `201 Created` возвращается **только** при `depositCreated = true`, иначе — `200 OK`.【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt†L197-L352】
   - Побочные эффекты выполняются только при `created = true`: `sessionCreated -> TABLE_SESSION:CREATE`, `depositCreated -> TABLE_DEPOSIT:CREATE` и `visit/hasTable` при наличии `guestUserId`.【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt†L197-L352】【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt†L640-L684】
3. **Отметка «ночь со столом» (hasTable)**:
   - при наличии `guestUserId` создаётся/обновляется визит с `entryType = "TABLE_DEPOSIT"` и `hasTable = true` через `VisitRepository.tryCheckIn` и `markHasTable`.【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt†L640-L684】【F:core-data/src/main/kotlin/com/example/bot/data/visits/VisitRepositories.kt†L86-L186】

#### B) NO_QR

1. **Создание session + deposit** без `guest_user_id` (guest остаётся `NULL`): `mode = NO_QR`, `guestPassQr` не требуется; депозит создаётся с `guestUserId = null`.【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt†L69-L114】【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt†L197-L352】
2. **Ограничения**:
   - отсутствует привязка к гостю → **нет** геймификации/метрик по визитам (`hasTable` не отмечается).
   - любые downstream процессы, завязанные на `guest_user_id`, недоступны для этого депозита.【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt†L265-L357】【F:core-data/src/main/kotlin/com/example/bot/data/booking/TableSessionDepositRepositories.kt†L32-L55】

### Редактирование и аудит

- `updateDeposit` **требует reason**: проверка на пустую строку есть в API и `TableDepositRepository`, а ограничение длины (`MAX_NOTE_LENGTH`) выполняется только на уровне API. Запись обновляет `amount_minor`, `allocations`, `updated_at/by`, `update_reason`.【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt†L429-L587】【F:core-data/src/main/kotlin/com/example/bot/data/booking/TableSessionDepositRepositories.kt†L245-L303】
- **Audit log** при операциях со столами и депозитами:
  - `TABLE_SESSION:CREATE/CLOSE` пишет `nightStartUtc`, `tableId`, `sessionId`, `actor`, (опц.) `guestUserId` и `note`.【F:core-domain/src/main/kotlin/com/example/bot/audit/AuditLogger.kt†L96-L150】
  - `TABLE_DEPOSIT:CREATE/UPDATE` пишет `nightStartUtc`, `tableId`, `sessionId`, `amountMinor`, `allocations`, `reason` (для update).【F:core-domain/src/main/kotlin/com/example/bot/audit/AuditLogger.kt†L152-L218】
  - **Запрещено логирование QR/секретов**: `AuditLogRepositoryImpl` редактирует/удаляет поля `initdata`, `qr`, `token`, `phone` и скрывает похожие на телефон значения.【F:core-data/src/main/kotlin/com/example/bot/data/audit/AuditLogRepositoryImpl.kt†L64-L140】

### Финансы и reconciliation

- **Сумма депозитов за ночь**: `TableDepositRepository.sumDepositsForNight(clubId, nightStartUtc)` возвращает сумму `amount_minor` для `table_deposits` по ночи.【F:core-data/src/main/kotlin/com/example/bot/data/booking/TableSessionDepositRepositories.kt†L314-L341】
- **Распределение по категориям**: `TableDepositRepository.allocationSummaryForNight(clubId, nightStartUtc)` агрегирует `table_deposit_allocations` по `category_code` с суммами по ночи.【F:core-data/src/main/kotlin/com/example/bot/data/booking/TableSessionDepositRepositories.kt†L343-L381】
- **Связь с платежами**: `table_deposits.payment_id` — nullable FK на `payments(id)` с `ON DELETE SET NULL` (см. `V036__table_sessions_deposits.sql`). При наличии значения reconciliation выполняется join по `payment_id` ↔ `payments.id`, иначе депозит связывается только по ночи/столу/сессии.【F:core-data/src/main/resources/db/migration/postgresql/V036__table_sessions_deposits.sql†L25-L49】

### Финансы / отчёт смены (shift_reports)

- Для **article-based** revenue entry (`articleId != null`) `groupId` всегда берётся из статьи и не переопределяется payload-ом. Если в payload передан `groupId` и он отличается от `groupId` статьи — entry отклоняется с ошибкой `revenue_article_group_mismatch` (group mismatch).【F:core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportRepositories.kt†L677-L695】【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceShiftRoutes.kt†L529-L545】
- Для **article-based** entry `name` может быть переопределён только непустым значением после `trim`; иначе используется имя статьи (`article.name`).【F:core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportRepositories.kt†L685-L695】
- Для **article-based** entry поля `include_in_total`/`show_separately`/`order_index` по умолчанию берутся из статьи, если не заданы в payload (`includeInTotal ?: article.includeInTotal`, `showSeparately ?: article.showSeparately`, `orderIndex ?: article.orderIndex`).【F:core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportRepositories.kt†L696-L698】
- Семантика `show_separately`: в non-total indicators участвуют только строки с `include_in_total=false`; `show_separately` — это подмножество именно этих строк, а при `include_in_total=true` флаг не попадает в индикаторы.【F:core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportCalculations.kt†L32-L37】
- `depositIndicatorsSum` считается только по subset `show_separately=true` среди `include_in_total=false`, поэтому строки с `include_in_total=true` в этот расчёт не входят независимо от `show_separately`.【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceShiftRoutes.kt†L510-L514】

### API: основные эндпоинты

- `GET /api/admin/clubs/{clubId}/nights/{nightStartUtc}/tables` — список столов с активными сессиями/депозитами (`activeSessionId`, `activeDeposit`).【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt†L129-L187】
- `POST /api/admin/clubs/{clubId}/nights/{nightStartUtc}/tables/{tableId}/seat` — посадка стола.
  - Request: `mode` (`WITH_QR`/`NO_QR`), `guestPassQr?`, `guestUserId?`, `depositAmount`, `allocations[]`, `note?`.【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt†L69-L114】
  - Response: `sessionId`, `depositId`, `table` с `activeSessionId`/`activeDeposit`.【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt†L90-L114】
- `POST /api/admin/clubs/{clubId}/nights/{nightStartUtc}/tables/{tableId}/free` — закрытие сессии стола.【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt†L374-L427】
- `PUT /api/admin/clubs/{clubId}/nights/{nightStartUtc}/deposits/{depositId}` — обновление суммы и allocations, **reason обязателен**; ответ возвращает обновлённый депозит.【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt†L429-L497】

## Audit log: контракт и ограничения

### Что пишем в `audit_log`

Audit log содержит унифицированные записи событий с полями:
`club_id`, `night_id`, `actor_user_id`, `actor_role`, `subject_user_id`, `entity_type`, `entity_id`, `action`, `fingerprint`, `metadata_json` и `created_at` (авто).【F:core-data/src/main/kotlin/com/example/bot/data/audit/AuditLogTable.kt†L8-L20】

События формируются через `AuditLogger` и RBAC:
- чек-ины (`VISIT:CHECKIN`), создание/отмена брони, депозиты, закрытие смены, выдача ролей;【F:core-domain/src/main/kotlin/com/example/bot/audit/AuditLogger.kt†L12-L163】
- HTTP-доступы `ACCESS_GRANTED/ACCESS_DENIED` от RBAC плагина, с метаданными запроса.【F:core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt†L360-L421】

### Что **нельзя** логировать (и как фильтруется)

`AuditLogRepositoryImpl` очищает `metadata_json`:
- удаляет поля с ключами, содержащими `initdata`, `init_data`, `qr`, `token`, `phone`;
- заменяет на `[REDACTED]` строковые значения, похожие на телефон; 
- `null` → `{}`.

Следствие: **никаких QR-пейлоадов, Telegram init data, токенов и телефонных номеров** в audit-log быть не должно — они отфильтруются или редактируются на записи.【F:core-data/src/main/kotlin/com/example/bot/data/audit/AuditLogRepositoryImpl.kt†L64-L140】

### Fingerprint: уникальность и идемпотентность

- В БД есть уникальный индекс по `fingerprint`, а запись вставляется через `insertIgnore` — повтор по fingerprint вернёт существующий id (идемпотентная запись).【F:core-data/src/main/resources/db/migration/postgresql/V033__audit_log_core.sql†L49-L56】【F:core-data/src/main/kotlin/com/example/bot/data/audit/AuditLogRepositoryImpl.kt†L28-L57】
- Формат fingerprint по умолчанию: `<ENTITY>:<ACTION>:<ID>:v1` (см. `AuditLogger`).【F:core-domain/src/main/kotlin/com/example/bot/audit/AuditLogger.kt†L192-L210】
- Для RBAC, если есть `Idempotency-Key`, fingerprint = base64url(sha256("rbac|<key>|<method>|<path>|<result>")); иначе — случайный UUID.【F:core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt†L432-L459】

## Примеры fingerprint

```text
# Booking created
BOOKING:CREATE:7ce2b1a0-7a8a-4b26-9a2f-6db8c9f6cc99:v1

# Visit check-in
VISIT:CHECKIN:club:42:night:777:checkin:12345:v1

# Role granted (global scope)
ROLE_ASSIGNMENT:CREATE:club:GLOBAL:user:9001:role:MANAGER:scope:GLOBAL:v1

# RBAC decision with idempotency key
<base64url-sha256(rbac|<key>|POST|/api/clubs/42/checkin/scan|access_granted)>
```

Форматы соответствуют генерации в `AuditLogger` и `RbacPlugin`.【F:core-domain/src/main/kotlin/com/example/bot/audit/AuditLogger.kt†L12-L210】【F:core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt†L360-L459】

## Примеры ошибок API (code / ErrorCodes)

Большинство API ошибок возвращаются в формате `ApiError` (через `respondError`/`StatusPages`), но **miniapp initData auth** (плагин `InitDataAuth`) при отказе возвращает другой JSON без `requestId`/`status`/`details`.【F:app-bot/src/main/kotlin/com/example/bot/http/ApiError.kt†L1-L44】【F:app-bot/src/main/kotlin/com/example/bot/plugins/JsonErrorPages.kt†L1-L92】【F:app-bot/src/main/kotlin/com/example/bot/plugins/InitDataAuth.kt†L68-L130】

```json
{
  "error": "initData missing",
  "message": "initData missing",
  "code": "unauthorized"
}
```

В 401 из initData auth клиенты должны опираться на поле `code`, а не на наличие `requestId` или стандартного shape ответа ошибки.【F:app-bot/src/main/kotlin/com/example/bot/plugins/InitDataAuth.kt†L68-L130】

Формат ошибки (`ApiError`):

```json
{
  "code": "checkin_invalid_payload",
  "message": null,
  "requestId": "req-123",
  "status": 400,
  "details": null
}
```

`code` — строка из `ErrorCodes`, клиенты должны опираться именно на него.【F:app-bot/src/main/kotlin/com/example/bot/http/ApiError.kt†L11-L36】【F:app-bot/src/main/kotlin/com/example/bot/http/ErrorCodes.kt†L1-L52】

Ниже — реальные коды и статусы, которые выдаются чек-ин маршрутами:

- **400** `invalid_json` — невалидный JSON (host / miniapp).【F:app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt†L104-L116】【F:app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt†L117-L147】
- **400** `checkin_invalid_payload` — неправильный payload (host).【F:app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt†L118-L133】【F:app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt†L166-L179】
- **400** `checkin_deny_reason_required` — запрет без `denyReason` (host).【F:app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt†L217-L235】
- **403** `checkin_forbidden` / `forbidden` — нет роли или нет доступа к клубу (host).【F:app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt†L133-L150】【F:app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt†L217-L235】
- **401** `unauthorized` — miniapp initData отсутствует/некорректна (schema отличается от `ApiError`, см. выше).【F:app-bot/src/main/kotlin/com/example/bot/plugins/InitDataAuth.kt†L68-L130】
- **404** `checkin_subject_not_found` — субъект не найден (host).【F:app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt†L217-L235】
- **409** `already_checked_in` — запись уже отмечена (miniapp).【F:app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt†L265-L291】
- **409** `outside_arrival_window` — слишком рано (miniapp, без статуса `CALLED`).【F:app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt†L294-L313】
- **400** `invalid_or_expired_qr` — QR неверен/просрочен (miniapp).【F:app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt†L211-L252】
- **403** `club_scope_mismatch` — QR относится к другому клубу (miniapp).【F:app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt†L321-L343】

Полный список кодов хранится в `ErrorCodes` и реестре ошибок (`/api/.well-known/errors`).【F:app-bot/src/main/kotlin/com/example/bot/http/ErrorCodes.kt†L1-L87】

## Финансы / отчёт смены (shift_reports)

### Шаблон клуба (club_report_templates + справочники)

- Шаблон отчёта состоит из:
  - `bracelets types` (`club_bracelet_types`),
  - `revenue groups` (`club_revenue_groups`),
  - `revenue articles` (`club_revenue_articles`).
  Все сущности шаблона отдаются в `template` внутри `ShiftReportDetailsResponse`.【F:core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportTables.kt†L6-L66】【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceShiftRoutes.kt†L111-L155】
- Для элементов шаблона используются флаги/порядок:
  - `enabled` (по умолчанию `true`) — soft-disable в админ API шаблона;
  - `order_index` (по умолчанию `0`) — сортировка в выдаче и при рендеринге формы;
  - для статей выручки также `include_in_total` (по умолчанию `false`) и `show_separately` (по умолчанию `false`).【F:core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportTables.kt†L14-L66】【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceTemplateRoutes.kt†L262-L273】

### Структура отчёта смены (shift_reports + entries)

- `shift_reports` хранит «шапку» отчёта: клуб, ночь, статус `DRAFT|CLOSED`, people counters, comment, audit-поля закрытия (`closed_at`, `closed_by`) и timestamps.【F:core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportTables.kt†L68-L88】【F:core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportModels.kt†L51-L69】
- Дочерние блоки отчёта:
  - `shift_report_bracelets` — счётчики по типам браслетов (`report_id + bracelet_type_id`);
  - `shift_report_revenue_entries` — строки выручки (`article_id` optional для ad-hoc строк, `group_id`, `amount_minor`, `include_in_total`, `show_separately`, `order_index`).【F:core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportTables.kt†L90-L119】【F:core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportModels.kt†L71-L93】

### Формулы и агрегаты

- **Totals per group**: сумма `amount_minor` только по строкам, где `include_in_total=true`, затем группировка по `group_id`.【F:core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportCalculations.kt†L14-L27】
- **Total revenue**: сумма `amount_minor` только по `include_in_total=true`.【F:core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportCalculations.kt†L29-L30】
- **Non-total indicators**: все строки с `include_in_total=false`; отдельно выделяется подмножество `show_separately=true` для отдельного отображения и сверок.【F:core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportCalculations.kt†L32-L37】【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceShiftRoutes.kt†L448-L452】

### Anti-double-count для депозитов

- В выручке смены депозиты столов обычно уже представлены в `depositHints.sumDepositsForNight` (из `table_deposits`). Если ту же сумму включить в «общую выручку», получится двойной учёт. Поэтому для индикаторов типа «депозитные карты/депозиты столов» рекомендуется держать `include_in_total=false` и (если нужны в интерфейсе) `show_separately=true`. Технически это согласуется с дефолтом для новых статей (`include_in_total=false`).【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceShiftRoutes.kt†L283-L287】【F:core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportTables.kt†L54-L56】【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceTemplateRoutes.kt†L262-L264】
- Сверка при закрытии смены считается как:
  - `tableDepositsSum = depositHints.sumDepositsForNight`,
  - `depositIndicatorsSum = Σ amount_minor` по строкам `show_separately && !include_in_total`,
  - `mismatch = abs(tableDepositsSum - depositIndicatorsSum)`.【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceShiftRoutes.kt†L505-L515】

### Правила закрытия смены

- Переход статуса только `DRAFT -> CLOSED`; повторное закрытие возвращает `invalid_state`/`409` и не меняет запись.【F:core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportRepositories.kt†L585-L613】【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceShiftRoutes.kt†L279-L300】
- Если `mismatch > SHIFT_REPORT_DEPOSIT_MISMATCH_THRESHOLD_MINOR`, то `comment` обязателен (`required_for_mismatch`).
  - Конфиг: env `SHIFT_REPORT_DEPOSIT_MISMATCH_THRESHOLD_MINOR`.
  - Дефолт в коде: `10_000` minor units.
  - Значение в `.env.example`: `10000`.【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceShiftRoutes.kt†L48-L49】【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceShiftRoutes.kt†L197-L197】【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceShiftRoutes.kt†L285-L287】【F:.env.example†L228-L231】

### API endpoints (финансовая смена + шаблон)

- Смена:
  - `GET /api/admin/clubs/{clubId}/nights/{nightStartUtc}/finance/shift` — получить/создать draft отчёт на ночь;
  - `PUT /api/admin/clubs/{clubId}/finance/shift/{reportId}` — обновить draft отчёт;
  - `POST /api/admin/clubs/{clubId}/finance/shift/{reportId}/close` — закрыть смену.【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceShiftRoutes.kt†L206-L267】
- Шаблон:
  - `GET /api/admin/clubs/{clubId}/finance/template`;
  - `POST|PUT|POST disable|POST reorder` для `bracelets`, `revenue-groups`, `revenue-articles` в `/api/admin/clubs/{clubId}/finance/template/...`.【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceTemplateRoutes.kt†L83-L147】【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceTemplateRoutes.kt†L172-L239】【F:app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceTemplateRoutes.kt†L239-L343】
