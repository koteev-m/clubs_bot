# Security invariants (P0.4)

Документ фиксирует инварианты безопасности для admin miniapp и связанных API. Текст ориентирован на прод‑релиз P0.4.

## 1) RBAC

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
