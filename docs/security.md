# Security invariants (P0.4)

Документ фиксирует инварианты безопасности для admin miniapp и связанных API. Текст ориентирован на прод‑релиз P0.4.

## 1) RBAC

- Все `/api/admin/*` требуют miniapp авторизации (initData) и выставляют `Cache-Control: no-store`.
- Все `/api/promoter/*` требуют miniapp авторизации (initData) и выставляют `Cache-Control: no-store`.
- Доступные роли: `OWNER`, `GLOBAL_ADMIN`, `CLUB_ADMIN`.
- Promoter доступен для `PROMOTER` (опционально также `OWNER`/`GLOBAL_ADMIN`/`HEAD_MANAGER`).
- Инварианты:
  - Создание клубов (`POST /api/admin/clubs`) доступно только `OWNER`/`GLOBAL_ADMIN`.
  - `CLUB_ADMIN` ограничен только клубами из RBAC-контекста — `isAdminClubAllowed(clubId)`.
  - Любые действия по залам/столам проверяют принадлежность клуба перед изменением.
  - Promoter ограничен своими guest lists (owner/promoter) и/или `clubIds` из RBAC.

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
