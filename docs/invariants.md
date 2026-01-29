# Инварианты и аудит (P2.5)

Документ фиксирует текущие инварианты чек-ина и контракт audit-log, согласованный с реальной реализацией.

## Инварианты чек-ина

### Роли и доступ

- **Miniapp guest-list QR** (`/api/clubs/{clubId}/checkin/scan`) доступен только для ролей `CLUB_ADMIN`, `MANAGER`, `ENTRY_MANAGER` и проходит `clubScoped(ClubScope.Own)`.【F:app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt†L55-L89】
- **Host check-in** (`/api/host/checkin`, `/api/host/checkin/scan`) также ограничен теми же ролями и проверкой доступа к клубу (`canAccessClub`).【F:app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt†L87-L155】
- Внутри сервисного слоя контроль ролей дублируется: **разрешены только** `ENTRY_MANAGER`, `MANAGER`, `CLUB_ADMIN` — иначе возвращается `CHECKIN_FORBIDDEN`.【F:core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt†L58-L166】【F:core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt†L285-L296】

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
- **404** `checkin_subject_not_found` — субъект не найден (host).【F:app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt†L217-L235】
- **409** `already_checked_in` — запись уже отмечена (miniapp).【F:app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt†L265-L291】
- **409** `outside_arrival_window` — слишком рано (miniapp, без статуса `CALLED`).【F:app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt†L294-L313】
- **400** `invalid_or_expired_qr` — QR неверен/просрочен (miniapp).【F:app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt†L211-L252】
- **403** `club_scope_mismatch` — QR относится к другому клубу (miniapp).【F:app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt†L321-L343】

Полный список кодов хранится в `ErrorCodes` и реестре ошибок (`/api/.well-known/errors`).【F:app-bot/src/main/kotlin/com/example/bot/http/ErrorCodes.kt†L1-L87】
