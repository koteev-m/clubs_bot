# Bot Monorepo

Kotlin multi-module project that powers the Telegram guest flow, admin APIs and
observability surfaces for the club booking bot. The service runs on Ktor 3,
Exposed 0.49 and Micrometer, targets JDK 21 and is packaged as a self-contained
`installDist` distribution for Docker deployments.

## Stack and modules

| Module | Description |
| --- | --- |
| `app-bot` | Ktor HTTP application with Telegram integrations, routes and health probes. |
| `core-domain` | Domain logic, availability rules, metrics helpers. |
| `core-data` | Database layer (HikariCP, Flyway migrations, Exposed DAO/DSL). |
| `core-security` | RBAC, webhook signature checks and security plugins. |
| `core-telemetry` | Micrometer registry bootstrap and shared telemetry helpers. |
| `core-testing` | Integration test harness and common fixtures. |
| `miniapp` | Static Mini App assets served via `webAppRoutes()`. |
| `tools` | Auxiliary utilities (perf harness, smoke checks). |

Browse API routes for clubs/events currently rely on in-memory repositories shipped with the app module; a production-grade database module will replace them in future iterations without changing the public API surface. Responses are JSON (UTF-8) with `Cache-Control: max-age=60, must-revalidate`, `Vary: X-Telegram-Init-Data` and stable ETags even for `304 Not Modified` replies.

`GET /api/clubs?city=&q=&tag=&genre=&date=&page=&size=` accepts optional filters:

- `date` — `YYYY-MM-DD`, interpreted as a UTC day window (inclusive).
- `genre` — exact membership in `Club.genres` (case-insensitive).
- `tag=quiet_day` — recommended label to mark a "тихий день".
- Invalid `date` strings are ignored; when no other filters are present this results in an empty array (the endpoint still returns
  cache headers).

Supplying only `date` is treated as a valid filter: the endpoint returns clubs with events on that day when they exist.

ETags rely on repository-level `lastUpdatedAt` watermarks for both clubs and (when `date` is specified) events. These timestamps
must advance for any meaningful change to the underlying data (not just inserts/deletes) so cache validators remain stable. The
ETag seed additionally includes filter parameters (city/tag/genre/q/date/page/size), and responses continue to ship with
`Cache-Control: max-age=60, must-revalidate` and `Vary: X-Telegram-Init-Data`.

`GET /api/clubs/{id}/layout?eventId=` mirrors the same caching contract (JSON UTF-8 content type, `Cache-Control: max-age=60,
must-revalidate`, `Vary: X-Telegram-Init-Data`) and emits stable ETags derived from the layout fingerprint, eventId and
payload sizes. Layout zones are sorted by `order`, tables — by `zoneId` then `id` to keep responses deterministic.
Geometry for the interactive map is served from `/assets/layouts/{clubId}/{fingerprint}.json`, where `fingerprint` is a
SHA-256 base64url hash of the geometry JSON. These immutable assets return `Cache-Control: public, max-age=31536000,
immutable`, `ETag: {fingerprint}` and intentionally omit `Vary` to stay CDN-friendly; new geometry produces a new fingerprint
and URL. Asset paths are validated (numeric `clubId`, base64url `fingerprint`) and invalid inputs respond with 404.

### Booking API (A3/A4)

- `POST /api/clubs/{clubId}/bookings/hold` — body `{ tableId, eventId, guestCount }`, requires `Idempotency-Key` and
  Mini App headers. Returns `{ booking, arrivalWindow: [from, to], latePlusOneAllowedUntil }` with `Cache-Control: no-store`,
  `Vary: X-Telegram-Init-Data` and **no** ETag. Conflicts/errors surface as `{ "error": { "code", "message" } }` using the
  registry codes (e.g. `table_not_available`, `idempotency_conflict`, `validation_error`).
- Promoter HOLDs (Role.PROMOTER/admins) can be limited by per-table `PromoterQuota`; if a quota is exhausted the hold
  responds with `409 promoter_quota_exhausted`, while expired quotas auto-reset and no longer restrict HOLDs. Quotas are
  configured per `(clubId, promoterId, tableId)` via `/api/admin/quotas` (no-store/Vary headers); POST replaces with
  `held=0`, PUT updates `quota`/`expiresAt` preserving the current `held` counter. Quotas apply only to promoter HOLDs — guests
  remain unaffected.
- `POST /api/clubs/{clubId}/bookings/confirm` — body `{ bookingId }`, same idempotency rules and headers; transitions HOLD →
  BOOKED or returns `hold_expired`/`invalid_state`. Booking ownership is enforced — only the creator can confirm within the same
  club scope (403 `forbidden`; `club_scope_mismatch` is used when path `clubId` differs from the booking).
- `POST /api/bookings/{id}/plus-one` — one-off +1 per booking while `latePlusOneAllowedUntil` has not passed; rejects with
`late_plus_one_expired`/`plus_one_already_used` otherwise. Capacity is checked for holds and +1 (409 `capacity_exceeded`) using
the captured `capacityAtHold` when present.
- `GET /api/me/bookings?status=upcoming|past` (A4) — personal list of confirmed bookings for the Mini App user. Defaults to
  `upcoming`, returns `{ bookings: [ { booking: BookingView, arrivalWindow, latePlusOneAllowedUntil, canPlusOne, isPast, arriveBy } ] }`
  with `Cache-Control: no-store`, `Vary: X-Telegram-Init-Data`. Status accepts only `upcoming|past` (`validation_error` on other
  values).
- `GET /api/bookings/{id}/qr` (A4) — QR payload for check-in, only for the booking owner and only when `status=BOOKED`. Responds
  with `{ bookingId, clubId, eventId, qrPayload }`, `Cache-Control: max-age=60, must-revalidate`, `Vary: X-Telegram-Init-Data`,
  strong `ETag` with 304 support; blank `QR_SECRET` yields `internal_error`. Errors are always `no-store` (`not_found`,
  `forbidden`, `invalid_state`).
- `GET /api/bookings/{id}/ics` (A4) — iCalendar export for the booking owner. Returns `text/calendar; charset=utf-8`, `Cache-Control:
  max-age=60, must-revalidate`, `Vary: X-Telegram-Init-Data`, strong `ETag`/304, errors as `no-store`. Body contains a single VEVENT
  with UID `booking-{id}@clubs`, DTSTART/DTEND from the arrival window and SUMMARY from the event/club names.

Idempotency is scoped by user + path + key with a 15‑minute TTL; keys must match `^[A-Za-z0-9._~:-]{1,128}$` and are echoed back
as `Idempotency-Key` (even on conflicts) plus `Idempotency-Replay: true` when the snapshot comes from cache. Sending the same key
with a different payload yields `idempotency_conflict (409)`. Table occupancy (FREE/HOLD/BOOKED) is tracked per event with a
configurable hold TTL and feeds the layout `ETag` watermark to reflect current holds in the `/layout` responses. Booking records
are retained for 48h after completion; event watermarks fall out after 7d, with background cleanup keeping memory bounded.

Key environment knobs (ISO-8601 durations unless noted):

- `BOOKING_HOLD_TTL` (default `PT10M`), `BOOKING_LATE_PLUS_ONE_OFFSET` (`PT30M`), `BOOKING_ARRIVAL_BEFORE` (`PT15M`),
  `BOOKING_ARRIVAL_AFTER` (`PT45M`).
- `BOOKING_IDEMPOTENCY_TTL` (`PT15M`), `BOOKING_RETENTION_TTL` (`PT48H`), `BOOKING_WATERMARK_TTL` (`P7D`).
- Rate limits per route: `BOOKING_RATE_LIMIT_HOLD_CAPACITY`/`BOOKING_RATE_LIMIT_HOLD_REFILL`,
  `BOOKING_RATE_LIMIT_CONFIRM_CAPACITY`/`BOOKING_RATE_LIMIT_CONFIRM_REFILL`,
  `BOOKING_RATE_LIMIT_PLUS_CAPACITY`/`BOOKING_RATE_LIMIT_PLUS_REFILL` (defaults mirror 5 req / 10s for hold/confirm,
  5 req / 30s for +1).

Rate limits are enforced per user and route (e.g., 5 holds / 10s). Every response includes `X-RateLimit-Limit` and
`X-RateLimit-Remaining`; a throttled response adds `Retry-After` with seconds to wait. These headers are returned even for early
validation errors so clients can surface them consistently.

### Host entrance aggregate (C1)

- `GET /api/host/entrance?clubId=&eventId=` — агрегат «Вход сегодня» для ролей ENTRY_MANAGER+. Возвращает ожидаемых гостей по
  каналам (guest list, bookings, прочие), текущие статусы (expected/arrived/notArrived/noShow) по каждому каналу, активный
  waitlist и суммарные counts. Успешные ответы всегда с `Cache-Control: no-store`, `Vary: X-Telegram-Init-Data` и ISO-временем
  `now` (UTC). Ошибки: `validation_error` для невалидных параметров, `forbidden` при недостаточных правах, `not_found` при
  отсутствии события/клуба.
  - `expected` — списки ожидаемых гостей: guest list записи (id, имя, guestCount, статус, hasQr), bookings (bookingId,
    guestCount, статус, опциональные имя/стол) и `other` (резервная секция, сейчас пустая). Статусы на проводе всегда
    в lower-case: guest list — из `GuestListEntryStatus` (`planned|checked_in|no_show|expired|...`), bookings — из
    `BookingStatus` (`hold|booked|canceled`), waitlist — из `WaitlistStatus` (`waiting|called|expired|cancelled|...`).
    `expected.guestList[*].hasQr` сейчас всегда `false` до явной привязки QR к гостевой записи.
  - `status` — агрегаты по каналам: expectedGuests, arrivedGuests, notArrivedGuests, noShowGuests. Guest list expected — все
    не-EXPIRED записи; arrived — CHECKED_IN; noShow — NO_SHOW; notArrived = max(expected - arrived - noShow, 0). Bookings
    expected — все НЕ-CANCELED; arrived/noShow = 0 (пока без чек-ина по booking), notArrived = expected. `counts` — сумма
    expected/arrived/noShow по каналам без дедупликации между ними.
  - `waitlist` — активные записи очереди ожидания (`id`, `clubId`, `eventId`, `userId`, `partySize`, `status`,
    `createdAt`, `calledAt`, `expiresAt`) и `activeCount` (size списка).

### Waitlist API (/api/clubs/{clubId}/waitlist)

- `GET /api/clubs/{clubId}/waitlist?eventId=` — список записей очереди для события.
- `POST /api/clubs/{clubId}/waitlist` — постановка гостя в очередь для указанного события.
- `POST /api/clubs/{clubId}/waitlist/{id}/call` — вызов гостя с резервом на N минут.
- `POST /api/clubs/{clubId}/waitlist/{id}/expire?close=` — истечение резерва: вернуть в очередь или закрыть запись.

Ответы содержат `Cache-Control: no-store`, `Vary: X-Telegram-Init-Data` и объекты очереди в формате:

- базовые поля: `id`, `clubId`, `eventId`, `userId`, `partySize`, `status`, `createdAt`, `calledAt`, `expiresAt` (ISO, UTC);
- SLA-подсказки:
  - `reserveExpiresAt` — ISO-строка (UTC) до какого момента держится резерв после call; `null`, если `expiresAt == null`;
  - `remainingSeconds` — количество секунд до `reserveExpiresAt` на момент ответа: `null`, если резерва нет, `0`, если окно
    уже истекло.

Новые поля (`reserveExpiresAt`, `remainingSeconds`) добавлены поверх существующего контракта без изменений уже
возвращавшихся полей.

### Host shift checklist (C2)

- `GET /api/host/checklist?clubId=&eventId=` — чек-лист смены для ролей ENTRY_MANAGER+. Возвращает
  шаблон задач с текущим состоянием для пары (clubId, eventId). Ответ всегда `Cache-Control: no-store`,
  `Vary: X-Telegram-Init-Data` и содержит:

  - `clubId`, `eventId`, `now` (ISO-строка в UTC).
  - `items` — список `{ id, section, text, done, updatedAt, actorId }`:
    - `id` — стабильный идентификатор задачи (snake_case), используется как ключ состояния.
    - `section` — логическая секция (например, `entrance`, `sound`, `misc`).
    - `text` — текст задачи.
    - `done` — флаг выполнения.
    - `updatedAt` — ISO-строка или `null`, когда флаг меняли в последний раз.
    - `actorId` — идентификатор пользователя (Telegram id), который последним менял задачу, или `null`.
  - Шаблон устойчив к обновлениям: новые задачи автоматически появляются с `done=false`, `updatedAt=null`, `actorId=null`,
    состояние хранится только по `id`.

- `POST /api/host/checklist?clubId=&eventId=` — обновляет флаг `done` для одной задачи:

  - Тело `{ itemId, done }` (оба поля обязательны).
  - Ошибки: `invalid_json` (повреждённое тело), `validation_error` (невалидные параметры, неизвестный `itemId`),
    `forbidden` (недостаточно прав), `not_found` (ивент не существует или не принадлежит клубу).
  - В случае успеха возвращает актуальный снимок чек-листа в том же формате, что и `GET`.

### Music likes and weekly mixtape (D1)

- `POST /api/music/items/{id}/like` — ставит лайк текущего пользователя на трек. Идемпотентен: повторные вызовы сохраняют
  первоначальную отметку и `likedAt`. Ответ `200 OK` с `{ itemId, liked: true, likedAt }` (ISO UTC). Невалидный id (не число,
  ≤ 0) возвращает `400 { "error": "invalid_item_id" }`.
- `DELETE /api/music/items/{id}/like` — снимает лайк; идемпотентен, всегда `200 OK` с `{ itemId, liked: false }`.
- `GET /api/me/mixtape` — персональный «микстейп недели» для текущего пользователя. Ответ содержит `userId`, `weekStart`
  (понедельник 00:00 UTC текущей недели), `items` (лайкнутые за последние 7 дней + рекомендации, без дублей) и `generatedAt`
  (текущее время). Все ответы для этих маршрутов устанавливают `Cache-Control: no-store`, `Vary: X-Telegram-Init-Data` и
  `ETag`. При совпадении `If-None-Match` сервер возвращает `304 Not Modified` без тела, но с теми же заголовками.

### Promoter API (B1)

**Statuses and timeline**

- Current B1 statuses: `ISSUED` (after POST /invites), `ARRIVED` (after check-in scan), `REVOKED` (manual revoke).
- Reserved for future features: `OPENED`, `CONFIRMED`, `NO_SHOW`.
- `PromoterInviteView.status` is always lowercase from the set `issued|opened|confirmed|arrived|no_show|revoked`.
- `timeline` is a chronological list of `{ type, at }` derived from non-null timestamps: `issued` is always present;
  `opened|confirmed|arrived|no_show|revoked` appear only when their `*_at` is set, strictly ordered by time.

**Endpoints**

- `POST /api/promoter/invites` — issue a QR invite for a specific event. Body `{ clubId, eventId, guestName, guestCount }`
  (name 1–100 chars, guestCount 1–10, ids > 0). Responds `201 Created` with
  `{ invite: PromoterInviteView, qr: { payload: "INV:...", format: "text" } }`, `Cache-Control: no-store`,
  `Vary: X-Telegram-Init-Data`. Errors: `invalid_json` (malformed body), `validation_error` (ids/name/count),
  `internal_error` (missing QR secret or other server failure).
- `GET /api/promoter/invites?eventId=` — list invites for the current promoter and event, newest first. Returns
  `{ invites: [PromoterInviteView] }` with `no-store`/`Vary` headers.
- `POST /api/promoter/invites/{id}/revoke` — revoke an invite owned by the promoter. `409 invalid_state` for ARRIVED/REVOKED/NO_SHOW,
  `403 forbidden` for foreign invites, `404 not_found` for missing ids. Success returns the updated `PromoterInviteView`.
- `GET /api/promoter/invites/export.csv?eventId=` — UTF-8 CSV export with header
  `inviteId,promoterId,clubId,eventId,guestName,guestCount,status,issuedAt,openedAt,confirmedAt,arrivedAt,noShowAt,revokedAt`.
  Null timestamps are empty strings; text fields (e.g., `guestName`, timestamps) are CSV-escaped when they contain commas/quotes/newlines; per-user data is `no-store` and `Vary` is always `X-Telegram-Init-Data`.
- Admins can configure promoter HOLD micro-quotas via `/api/admin/quotas` (no-store/Vary headers) to cap simultaneous HOLDs per
  club/promoter/table; POST creates/resets (`held=0`), PUT updates without dropping the current `held`, GET lists quotas by
  `clubId`.

`PromoterInviteView` surfaces all timestamps plus the derived timeline; the QR format is `INV:<inviteId>:<eventId>:<ts>:<hmacHex>` where
`hmacHex = HMAC_SHA256("inviteId:eventId:ts", derivedKey(secret="QrPromoterInvite"))`. The same `QR_SECRET` env var used for
booking QR codes signs promoter invites (rotation via `QR_OLD_SECRET` is honored on scan).

Check-in integration: `/api/clubs/{clubId}/checkin/scan` recognizes `INV:` payloads and marks the invite as
`arrived` (idempotent) and responds with `{ status: "ARRIVED", type: "promoter_invite", inviteId }` on success.
Invalid or revoked invites return `409 invalid_state`; QR decode failures return
`400 invalid_or_expired_qr`. Guest list QR behavior is unchanged.

### Promoter rating (B3)

- Periods are sliding windows in UTC: `week` → `[now - 7d, now)`, `month` → `[now - 30d, now)`.
- Source data: promoter invites and their events; only invites whose `event.startUtc` falls inside the chosen window are
  considered.
- Metrics:
  - `invited` — sum of `guestCount` for invites not in `REVOKED`.
  - `arrivals` — sum of `guestCount` where status is `ARRIVED` (or `arrivedAt` is set).
  - `noShows` — for events with `endUtc < now`, sum `guestCount` for invites that are neither `ARRIVED` nor `REVOKED`.
    Invites for ongoing/future events do not contribute to no-shows.
  - `conversionScore` — `0.0` when `arrivals + noShows == 0`, otherwise `arrivals / (arrivals + noShows)`.
- Endpoints (all responses use `Cache-Control: no-store` and `Vary: X-Telegram-Init-Data`):
  - `GET /api/promoter/scorecard?period=week|month` — personal metrics for the current promoter, returns
    `{ period, from, to, invited, arrivals, noShows, conversionScore }` with `from/to` serialized as ISO instants.
  - `GET /api/admin/promoters/rating?clubId=&period=&page=&size=` — club-level rating for admins/owners/global admins.
    Pagination defaults to `page=1`, `size=20` (range `1..100`). Sorting is deterministic: by `conversionScore` desc,
    then `arrivals` desc, then `promoterId` asc. Response contains `{ clubId, period, from, to, page, size, total, items }`
    where `items` are `{ promoterId, invited, arrivals, noShows, conversionScore }`.
- Analytics are read-only: no invite statuses are mutated and no NO_SHOW flags are persisted.

Example responses (structures match the live API, values are illustrative only):

```json
{
  "period": "week",
  "from": "2024-06-01T00:00:00Z",
  "to": "2024-06-08T00:00:00Z",
  "invited": 42,
  "arrivals": 30,
  "noShows": 12,
  "conversionScore": 0.7142857
}
```

```json
{
  "clubId": 1,
  "period": "week",
  "from": "2024-06-01T00:00:00Z",
  "to": "2024-06-08T00:00:00Z",
  "page": 1,
  "size": 2,
  "total": 5,
  "items": [
    {
      "promoterId": 10,
      "invited": 20,
      "arrivals": 18,
      "noShows": 2,
      "conversionScore": 0.9
    },
    {
      "promoterId": 5,
      "invited": 15,
      "arrivals": 10,
      "noShows": 5,
      "conversionScore": 0.6666667
    }
  ]
}
```

## Mini App UI

The static Mini App lives under `miniapp/src/main/resources/miniapp` and is shipped with the Ktor app through
`webAppRoutes()`. Run the server (`./gradlew :app-bot:run` or your preferred entrypoint) and open:

- `http://localhost:8080/app/index.html` — browse clubs/events with filters (city, **UTC date in YYYY-MM-DD**, genre, quiet day
  toggle → `tag=quiet_day`, debounced search), pagination and a "Показать ещё" loader. Requests reuse server-side caching:
  client-side helpers store `{etag, payload}` in **sessionStorage** keyed by `METHOD URL` and send `If-None-Match`. On
  `304 Not Modified` the cached payload is rendered (⚡ indicator near the heading) without extra drawings; a rare `304` without
  a warm cache falls back to a plain GET. Filter selections are mirrored into the query-string so the URL stays shareable and
  restores the state on reload.
- `http://localhost:8080/app/layout.html?clubId=1&eventId=100` — renders the hall map. Layout JSON is fetched with the same
  ETag-aware helper; the immutable geometry asset (`/assets/layouts/{clubId}/{fingerprint}.json`) is then loaded and drawn as
  an SVG with table coloring by status (FREE/HOLD/BOOKED), viewBox scaling and tooltips. Client-side filters work locally (VIP,
  near_stage, capacity floor, exact minimumTier selector) and switching eventId triggers a cached `/api/clubs/{id}/layout`
  call; a matching ETag yields a 304 and skips re-rendering. The current clubId/eventId and client filters are also reflected
  in the query-string for shareable URLs.

Both pages automatically forward `X-Telegram-Init-Data: window.Telegram.WebApp.initData` on every request, mirroring the API
contract (`Cache-Control: max-age=60, must-revalidate`, `Vary: X-Telegram-Init-Data`, tolerant ETags and 304 flows). Layout
assets remain immutable by fingerprint (`Cache-Control: public, max-age=31536000, immutable`, `ETag: {fingerprint}`, no `Vary`),
so browsers/CDNs can cache them safely. The behavior stays friendly to server validators (no ad-hoc TTLs), leaving freshness
control to the existing headers.

Related documentation:

- [Полная документация пилота QR](docs/README_pilot_QR.md)
- [Чек-лист запуска пилота](docs/CHECKLIST_pilot_QR.md)

## Quality gates

```bash
./gradlew dependencyGuard --console=plain
./gradlew ktlintFormat ktlintCheck detekt --console=plain
./gradlew :core-domain:test --console=plain
./gradlew clean build --console=plain
```

Integration tests are tagged with `@Tag("it")` and excluded unless explicitly
requested.

## Tests

- Unit tests (no Docker required):

  ```bash
  ./gradlew test --console=plain
  ```

- Integration tests (require Docker/Testcontainers):

  ```bash
  ./gradlew test -PrunIT=true --console=plain
  ```

  If Testcontainers cannot access `/var/run/docker.sock`, start Docker Desktop
  (macOS/Windows) or enable the Docker service (Linux) before re-running the
  build.

The dependency guard task validates the resolved dependency graph and fails the
build if it detects legacy Kotlin stdlib artifacts, mismatched Ktor versions or
dynamic/SNAPSHOT coordinates. Run it locally via `./gradlew dependencyGuard
--console=plain` or let CI handle it as part of the standard pipeline.

## SEC-02 Политика логирования и приватность

### Что пишем в логи
- **Технические идентификаторы:** `request_id` (из Ktor CallId; также уходит в заголовок ответа `X-Request-Id`), `actor_id` (идентификатор актёра после успешной аутентификации/авторизации). Оба ключа лежат в **MDC** и попадают во все записи логов в рамках запроса.
- **Бизнес‑ID:** `clubId`, `listId`, `entryId`, `bookingId`, внутренний `userId`.
- **Статусы/исходы**, **тайминги/метрики** и прочую **не‑PII** диагностическую информацию.
- **Никогда** не логируем тела запросов/ответов и произвольные payload’ы.

### Что маскируем (делает `MessageMaskingConverter`, используется как `%maskedMsg` в `logback.xml`)
- **Телефоны** → маскируем всё, кроме 2–3 последних цифр. Пример: `+7 *** *** ** 67`.
- **Имена/ФИО** (`fullName`/`fio`/`name`) → оставляем первые буквы слов, остальное `*`. Пример: `И*** И*****`.
- **«Голые» токены** формата `\d{6,12}:[A-Za-z0-9_-]{30,}` → `***REDACTED***`.

### Что запрещено (TurboFilter = DENY)
Сообщения, содержащие ключи `qr`, `start_param`, `idempotencyKey`, **не доходят до аппендеров**: такие записи отсекаются `DenySensitiveTurboFilter` на ранней стадии.

### Файл‑логирование (prod)
- Консольный вывод включён всегда.
- Файловый аппендер настроен на `logs/app.log` с дневной ротацией.
- Каталог задаётся переменной окружения `LOG_DIR` (по умолчанию — `./logs`). Убедитесь, что каталог существует и доступен на запись.
- Типичный прод‑запуск:  
  ```bash
  export LOG_DIR=/var/log/app
  # дополнительные переменные окружения см. ниже
  # запуск вашего entrypoint (Docker/K8s/systemd)
  ```

Если нужно — ротацию/формат можно настраивать в logback.xml.

### Локальный смоук

1) **Тестовый смоук (без запуска сервера)**

   ```bash
   # Юнит/интеграционные тесты модуля app-bot
   ./gradlew :app-bot:test --no-daemon -S -i
   ```

   Что проверить:
   - Тесты на маскирование и TurboFilter зелёные.
   - Тест `KtorMdcTest` (если включён) подтверждает: X-Request-Id в ответе и request_id в MDC логов.

2) **Ручной смоук (опционально, если есть возможность поднять локально)**

   Запуск с in-memory H2, чтобы не требовать PostgreSQL:

   ```bash
   export TELEGRAM_BOT_TOKEN=111111:TEST_BOT_TOKEN
   export NOTIFICATIONS_ENABLED=false
   export DATABASE_URL='jdbc:h2:mem:clubsb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_UPPER=false'
   export DATABASE_USER=sa
   export DATABASE_PASSWORD=
   export FLYWAY_LOCATIONS=classpath:db/migration/h2
   export LOG_DIR=./logs

   # если настроен application plugin с рабочей точкой входа:
   ./gradlew :app-bot:run --no-daemon -S -i
   ```

   Дальше — 1–2 запроса (подойдёт даже несуществующий путь — CallLogging всё равно сработает):

   ```bash
   # с заданным X-Request-Id
   curl -i -H 'X-Request-Id: smoke-req-1' http://localhost:8080/__smoke__
   # без заголовка — сервер сам сгенерирует request_id
   curl -i http://localhost:8080/__smoke__
   ```

   В логах проверьте:
   - есть request_id=<...> (и actor_id=<...> в авторизованных ветках);
   - нет телефонных номеров/ФИО/«голых» токенов;
   - нет упоминаний qr=/start_param=/idempotencyKey=.

ℹ️ Если в модуле нет исполняемой main, можно ограничиться «тестовым смоуком» (п.1): он покрывает генерацию и прокидывание request_id через MDC, а также маскирование/запрет чувствительных полей.

## Configuration

Copy `.env.example` to `.env` (used by Docker Compose) and provide the required
secrets. The application reads configuration from environment variables:

| Variable | Default | Description |
| --- | --- | --- |
| `APP_ENV` | `dev` | Runtime profile: `dev` enables relaxed limits, `prod` enables hardened settings. |
| `PORT` | `8080` | HTTP port for the Ktor server (also exposed in Docker). |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75 ...` | Extra JVM options consumed by the `installDist` launcher. |
| `DATABASE_URL` | – | JDBC URL for PostgreSQL (required in production). |
| `DATABASE_USER` | – | Database user for Flyway and Exposed. |
| `DATABASE_PASSWORD` | – | Database password. |
| `HEALTH_DB_TIMEOUT_MS` | `150` | Timeout for the `/health` DB probe in milliseconds. |
| `TELEGRAM_BOT_TOKEN` | – | Telegram bot token (required to send messages). |
| `WEBHOOK_SECRET_TOKEN` | – | Shared secret that signs incoming Telegram webhooks. |
| `OWNER_TELEGRAM_ID` | – | Telegram user ID that receives critical alerts. |
| `TELEGRAM_USE_POLLING` | `false` | Switch between webhook mode (`false`) and long polling demo (`true`). |
| `LOCAL_BOT_API_URL` | – | Optional base URL for a self-hosted Telegram Bot API. |
| `RULES_DEBUG` | `false` | Enables detailed DEBUG logs for `OperatingRulesResolver` (fallbacks to INFO otherwise). |
| `TELEGRAM_API_ID`/`TELEGRAM_API_HASH` | – | Required when running the optional `telegram-bot-api` container. |

## Running locally

### Gradle

```bash
export $(grep -v '^#' .env | xargs)  # optional helper when using an .env file
./gradlew :app-bot:run --console=plain
```

The application starts all routes via `Application.module()` and performs
Flyway migrations on startup. After successful migrations, `/ready` returns
`READY` while `/health` returns `OK` once the DB probe succeeds.

### Docker Compose

```bash
cp .env.example .env   # fill in the required secrets
docker compose up --build
```

The compose stack provisions PostgreSQL, builds the runtime image from the
multi-stage `Dockerfile` and runs `app-bot` as a non-root user. Health checks:

- `http://localhost:8080/health` – readiness of the DB connection
- `http://localhost:8080/ready` – application readiness (migrations applied)

The container exposes `PORT` and honours `JAVA_OPTS`. Logs are available via
`docker compose logs -f app`.

## Observability

- `GET /health` — checks database connectivity with a configurable timeout.
- `GET /ready` — returns `READY` only after Flyway migrations succeed.
- `GET /metrics` — Micrometer Prometheus endpoint with counters for availability
  rules (`rules.exception.applied`, `rules.holiday.inherited_open`,
  `rules.holiday.inherited_close`, `rules.day.open`) tagged by day-of-week and
  overnight flags.

Setting `RULES_DEBUG=true` enables verbose DEBUG traces for the
`OperatingRulesResolver`: input base/holiday/exception hours, resolved boundary
sources (`base|exception|holiday|inherited`), final open/close windows and the
`overnight` flag.

## Database migrations

Flyway migrations reside in `core-data`. Run them via Gradle:

```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/postgres \
DATABASE_USER=postgres DATABASE_PASSWORD=postgres \
./gradlew flywayMigrate --console=plain
```

`MigrationState.migrationsApplied` guards the readiness probe and the Docker
entrypoint fails fast when migrations cannot be applied.

## Telegram bot

The Telegram adapter uses the pengrad client. By default the demo long-polling
listener is wired in `Application.module()` for `/demo`. Production deployments
should configure a webhook endpoint and secret.

Invoices (`SendInvoice`) remain compatible with the current pengrad release and
use `.providerToken(...)` for configuration while keeping the deprecation
suppression local to the instantiation site.

## Additional tooling

- `make up` / `make down` — wrappers for Docker Compose.
- `make health` — calls `/health` on the local deployment.
- `./gradlew staticCheck` — aggregate detekt and ktlint CLI checks.
- `./tools/perf/build/install/perf/bin/perf` — performance harness (see
  `README_pilot_QR.md`).
