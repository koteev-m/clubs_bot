# Security invariants (P0.4)

Документ фиксирует инварианты безопасности для admin miniapp и связанных API. Текст ориентирован на прод‑релиз P0.4.

## 1) RBAC

### Platform cabinet identity (DEC-039, CLB-197 local implementation)

`GET /api/platform/access` is a read-only capability check for the two platform-owner
screens. It requires the existing validated Telegram initData header contract and an
exact match of signed `user.id` to positive `AppConfig.bot.ownerId` (protected
`OWNER_TELEGRAM_ID`). It returns only `{ "platformOwner": true }` on success, with
`Cache-Control: no-store` and the existing Mini App Vary header. Invalid/missing/stale
initData is 401; another identity or nonpositive/unavailable configured ID is 403.
Missing/malformed raw owner configuration retains the existing safe startup failure.
The existing validator age/future-skew rules are reused; no new token/session is issued.

This endpoint rejects initData in query strings in every profile, even when a valid
header is also present, and does not read credentials from a body. URL userId/role/scope,
unverified Telegram headers, browser storage and legacy `Role.OWNER` cannot authorize it.
Errors use the existing auth/API error contract without identity values. Startup safe
config output reports only `ownerConfigured`, not the configured ID.

This is not a new global role system: it creates no DB identity/assignment and changes
no existing OWNER/GLOBAL or support permissions. Future invitation/assignment/data APIs
must independently enforce platform identity on the server; a previous successful check
or visible frontend controls provide no mutation authority. The separately accepted
onboarding A contract and local source delta are described below; club creation/profile
and club-only staff assignment remain outside this stage.

`/app?mode=platform-owner` selects the guarded React presentation only; ordinary `/app`
still serves the guest catalogue and the support entries are preserved. The explicit
development preview remains loopback-only and cannot issue access or invitations.

### Client onboarding A (accepted 2026-10-28; local candidate)

Authority is [PRODUCT_SPEC §29](product/PRODUCT_SPEC.md#client-onboarding-ab-technical-contract),
refining DEC-039–042. Implementation evidence is bounded to
[AS_BUILT §4.16](product/AS_BUILT.md#client-onboarding-a-local); final checks/review and
unverified runtime are recorded in [PROJECT_STATUS](ops/PROJECT_STATUS.md).

- `/api/client/*` and `/api/platform/onboarding/*` use the fixed server platform
  connection, fresh verified Telegram identity and one canonical
  `X-Telegram-Init-Data` header. Duplicate/coalesced headers, the alias, body-only
  credentials and credential query are rejected; other new API query parameters are
  rejected after authentication. Legacy request identity/RBAC does not authorize
  these endpoints. Every platform action requires the exact positive configured owner.
- Invitation secret has at least 256 cryptographically random bits, is stored only
  as a hash, and is returned only on initial issue/reissue. Seven-day expiry is checked
  on claim/confirmation. Preview and GET never provision a user or grant authority.
  Explicit claim provisions only minimal identity in its transaction; membership and
  exact `client.application.manage` require a later owner confirmation.
- The candidate's short one-time verification code is a **human comparison aid**:
  the recipient reports it in the original private chat. It cannot authorize access,
  replace the configured owner, switch the candidate or grant club access.
- Every client mutation/replay rechecks client, active membership, exact action and
  object/author. Membership alone does not authorize all clubs. Revoke closes only
  that user's client scopes and preserves data; restore lists prior scopes explicitly.
  Submitted fields are immutable, rejected/cancelled terminal, and approval does not
  create a club in A. Internal platform notes are excluded from client responses.
- Business changes, command record and audit insert use one transaction. Audit
  failure rolls everything back. Raw invitation secrets, initData and raw idempotency
  keys are forbidden in persistence metadata, logs, telemetry and optional browser
  storage. HTTP logs use method/path/status without query, headers or request bodies.
- `startapp` can appear in launch URL and in Telegram initData. The client scrubs
  invitation-bearing address state and prevents optional SDK sessionStorage caching
  of launch credentials; signed initData remains available in memory for auth.
  This application guarantee does **not** assert that Telegram infrastructure cannot
  store or process the parameter. An integration/deployment must also avoid raw
  launch query logging at its own ingress. The repository Caddy configuration also
  removes request URI and headers from reverse-proxy error metadata; the local
  ingress check exercises an actual upstream failure with synthetic canaries.
- Caddy permits PATCH only for the exact positive-ID client application route.
  Authentication, author scope and draft/version checks remain server-side; the
  proxy matcher grants no authority. Mini App documents use no-store/no-referrer.
- New API successes/errors keep `ApiError`, no-store and the Mini App Vary header.
  Account switch/foreground invalidates private UI state and rejects late responses;
  an old response or selected client ID never restores a lost grant.

No Managed Bots, multiple production credentials, staff management, payment
verification, private-club creation/profile or complete legacy tenant containment is
claimed here. Real invitations, Telegram setup, live DB changes and rollout remain
separately authorized actions.

### Existing admin/promoter RBAC

- Все `/api/admin/*` требуют miniapp авторизации (initData) и выставляют `Cache-Control: no-store`.
- Все `/api/promoter/*` требуют miniapp авторизации (initData) и выставляют `Cache-Control: no-store`.
- Доступные роли: `OWNER`, `GLOBAL_ADMIN`, `CLUB_ADMIN`.
- Promoter доступен для `PROMOTER` (опционально также `OWNER`/`GLOBAL_ADMIN`/`CLUB_ADMIN`).
- Инварианты:
  - Создание клубов (`POST /api/admin/clubs`) доступно только `OWNER`/`GLOBAL_ADMIN`.
  - `CLUB_ADMIN` ограничен только клубами из RBAC-контекста — `isAdminClubAllowed(clubId)`.
  - Любые действия по залам/столам проверяют принадлежность клуба перед изменением.
  - Админские endpoints промоутеров/квот (`GET /api/admin/promoters`, `POST /api/admin/promoters/{id}/access`,
    `GET|POST|PUT /api/admin/quotas`) доступны `OWNER`/`GLOBAL_ADMIN`/`HEAD_MANAGER`/`CLUB_ADMIN` и проверяют club scope.
  - Promoter ограничен своими guest lists (owner/promoter) и `clubIds` из RBAC.
  - Для не‑глобальных ролей пустой `clubIds` означает отсутствие доступа к клубам (deny‑by‑default).

### 1.2) Admin: промоутеры и квоты

- `GET /api/admin/promoters?clubId={clubId}`: список промоутеров клуба, их доступ и квоты.
  - Ответ: `{ promoters: [{ promoterId, telegramUserId, username, displayName, accessEnabled, quotas: [{ tableId, quota, held, expiresAt }] }] }`.
- `POST /api/admin/promoters/{promoterUserId}/access`
  - Payload: `{ clubId: number, enabled: boolean }`.
  - Ответ: `{ enabled: boolean }`.
- `POST /api/admin/quotas` (upsert, сброс held)
  - Payload: `{ clubId, promoterId, tableId, quota, expiresAt }`.
  - Ответ: `{ quota: { clubId, promoterId, tableId, quota, held, expiresAt } }`.
- `PUT /api/admin/quotas` (обновление, сохраняет held)
  - Payload: `{ clubId, promoterId, tableId, quota, expiresAt }`.
  - Ответ: `{ quota: { clubId, promoterId, tableId, quota, held, expiresAt } }`.

### 1.3) Admin: ops chats

- Назначение: конфиг маршрутизации ops-уведомлений на клубный чат и треды.
- Доступ: `OWNER` / `GLOBAL_ADMIN` / `HEAD_MANAGER` / `CLUB_ADMIN`.
- Обязательные инварианты: `withMiniAppAuth(initData)`, `Cache-Control: no-store`, `Vary: X-Telegram-Init-Data`, club scope.
- `GET /api/admin/ops-chats?clubId={clubId}`
  - Возвращает `{ config }`, где `config` — объект или `null`, если настройки ещё не заданы.
  - Ответ 200:
    ```json
    {
      "config": {
        "clubId": 1,
        "chatId": -1001234567890,
        "bookingsThreadId": 10,
        "checkinThreadId": 11,
        "guestListsThreadId": 12,
        "supportThreadId": null,
        "alertsThreadId": 99,
        "updatedAt": "2024-06-01T12:00:00Z"
      }
    }
    ```
- `PUT /api/admin/ops-chats`
  - Payload:
    ```json
    {
      "clubId": 1,
      "chatId": -1001234567890,
      "bookingsThreadId": 10,
      "checkinThreadId": 11,
      "guestListsThreadId": 12,
      "supportThreadId": null,
      "alertsThreadId": 99
    }
    ```
  - Валидация:
    - `clubId > 0`
    - `chatId != 0` (допускаются отрицательные chatId)
    - `bookingsThreadId`, `checkinThreadId`, `guestListsThreadId`, `supportThreadId`, `alertsThreadId` — `null` или `> 0`
  - Ответ: `{ config: { ... } }` (включая `updatedAt`).
  - Ошибки: `validation_error`, `invalid_json`, `forbidden` (scope mismatch).

### 1.4) Ops-события и треды

- Маршрутизация происходит по категории:
  - `BOOKINGS` → `bookingsThreadId`
  - `GUEST_LISTS` → `guestListsThreadId`
  - `SUPPORT` → `supportThreadId`
  - `ALERTS` → `alertsThreadId`
- Минимальный набор событий:
  - `NEW_BOOKING`, `BOOKING_UPDATED`, `BOOKING_CANCELLED` → `BOOKINGS`
  - `GUEST_LIST_CREATED` → `GUEST_LISTS`
  - `SUPPORT_QUESTION_CREATED` → `SUPPORT`
  - `SYSTEM_ALERT` → `ALERTS`

## 1.1) Promoter bookings

- Привязка guest list entry ↔ booking хранится персистентно в БД (не в памяти).

## 2) No‑leak logs

Запрещено логировать (включая warn/error/debug):

- bytes / `ByteArray` / base64
- multipart payload и file contents
- geometryJson / контент плана зала
- initData / bot tokens / auth headers
- bulk текст гостевых списков
- ссылки/QR с токенами приглашений

Разрешено логировать:

- идентификаторы (clubId/hallId/tableId)
- counts, sizeBytes
- sha256 (или префикс), contentType
- requestId
- ops-уведомления: только технические идентификаторы (id сущностей), без текста обращений и персональных данных

### Audit (grep‑чеклист)

Перед релизом прогнать:

```bash
rg -n --hidden --glob '!.git' --glob '!**/node_modules/**' --glob '!**/dist/**' --glob '!**/build/**' "logger\.(debug|info|warn|error)\(" app-bot core-*
rg -n --hidden --glob '!.git' --glob '!**/node_modules/**' --glob '!**/dist/**' --glob '!**/build/**' "console\.(log|warn|error)\(" miniapp/src
rg -n -i --hidden --glob '!.git' --glob '!**/node_modules/**' --glob '!**/dist/**' --glob '!**/build/**' "(base64|multipart|formdata|partdata|tobytearray\(|bytearray|bytes=|geometryjson|initdata|x-telegram-init-?data|authorization)" app-bot core-* miniapp/src
```

Результат аудита P0.4: проверены `app-bot`, `core-*`, `miniapp/src` (без `node_modules`, `dist`, `build`), чувствительные
payload‑данные в логах не обнаружены. Логи ограничены id/size/sha256 и техническими метками.

## 3) Upload constraints

- Загрузка плана зала: `PUT /api/admin/halls/{hallId}/plan`.
- Допустимые content‑type: `image/png`, `image/jpeg`.
- Максимальный размер: 5MB (server‑side guard).
- Контент файла не логируется и не отражается в ошибках.
- Проверки на клиенте — не безопасность; обязательны server‑side guards.

## 4) Cache headers

- Все admin endpoints обязаны возвращать `Cache-Control: no-store` и `Vary: X-Telegram-Init-Data`.
- `GET /api/clubs/{clubId}/halls/{hallId}/plan`:
  - `ETag` основан на sha256 контента.
  - `Cache-Control: private, max-age=3600, must-revalidate`.
  - `304 Not Modified` возвращает те же cache headers.

## 5) Error handling

- Ошибки возвращаются в формате `{ code, message, requestId, status, details }`.
- Поля `message` и `details` не должны содержать чувствительные данные.
- `requestId` используется для корреляции и трассировки проблем.
