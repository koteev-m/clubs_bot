# TL;DR

**Пилот**: Telegram Mini App для команды входа. Сотрудник клуба открывает WebApp из бота, нажимает «Сканировать», считывает QR гостя и подтверждает его приход одним POST-запросом.

- WebApp: `https://<host>/webapp/entry?clubId=<ID>` → кнопка **Scan / «Сканировать»** запускает `showScanQrPopup` Telegram-клиента.
- Сервер: `POST /api/clubs/{clubId}/checkin/scan` (JSON `{ "qr": "GL:..." }`) с заголовком `X-Telegram-Init-Data`.
- Успешный ответ: `200 {"status":"ARRIVED"}`; повторный скан того же QR идемпотентен.
- Требования допуска: роль пользователя `ENTRY_MANAGER`, `CLUB_ADMIN` или `MANAGER`; `clubScoped(Own)` на свой `clubId`; QR-токен выпущен ≤ 12 часов назад.
- Граничные проверки: HMAC-подпись `initData` по `TELEGRAM_BOT_TOKEN`, подпись QR по `QR_SECRET`, rate-limit по пользователю и IP.

# Архитектура

```
Telegram Client
    │  (Mini App button)
    ▼
Telegram WebApp (Entry UI)
    │  fetch POST /api/clubs/{clubId}/checkin/scan
    ▼
Ktor Service (app-bot)
    │  withMiniAppAuth → TelegramPrincipal
    ▼
RBAC & clubScoped (core-security)
    │  authorize roles & clubId
    ▼
Check-in Handler (CheckinRoutes)
    │  QrGuestListCodec.verify → GuestListRepository.markArrived
    ▼
Postgres (guest list entries)
```

# Среда / ENV

| Имя | Назначение | Пример значения |
| --- | --- | --- |
| `TELEGRAM_BOT_TOKEN` | Проверка `X-Telegram-Init-Data` (HMAC Bot API токена). | `123456:bot-token-placeholder` |
| `QR_SECRET` | Секрет для HMAC QR-токенов гостя. | `qr-secret-placeholder` |
| `QR_OLD_SECRET` | Опциональный предыдущий секрет для плавной ротации. | *(можно оставить пустым)* |
| `APP_PROFILE` | Профиль конфигурации (`dev`/`stage`/`prod`). | `stage` |
| `CORS_ALLOWED_ORIGINS` | Разрешённые origin'ы Mini App (через запятую). | `https://t.me, https://web.telegram.org` |
| `CORS_PREFLIGHT_MAX_AGE_SECONDS` | TTL preflight-кэша (секунды, 60–86400). | `600` |
| `DATABASE_URL` | JDBC строка подключения. | `jdbc:postgresql://db.internal:5432/club` |
| `DATABASE_USER` | Пользователь БД. | `bot_app` |
| `DATABASE_PASSWORD` | Пароль БД. | `********` |
| `GLOBAL_RPS` | Общий лимит запросов (см. `BotLimits`). | `200` |
| `CHAT_RPS` | Лимит на чат/пользователя. | `30` |

## CSP (для статического WebApp)
- Управляется ENV: `CSP_ENABLED`, `CSP_REPORT_ONLY`, `CSP_VALUE`, `WEBAPP_CSP_PATH_PREFIX` (по умолчанию `/webapp/entry`).
- Рекомендуемый дефолт:
  ```
  default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self' https://t.me https://telegram.org;
  ```
- Стартуйте с `CSP_ENABLED=true` и `CSP_REPORT_ONLY=true`, соберите репорты/логи, затем переведите в enforce (`CSP_REPORT_ONLY=false`).
- Не используйте `frame-ancestors` из-за Telegram WebView.

## Кэширование статики WebApp
- Для `/webapp/entry/*` сервер ставит `Cache-Control` только на успешные (2xx/304) ответы.
- HTML (`/webapp/entry` или `*.html`) — короткий кэш с `must-revalidate`.
- Ассеты с fingerprint в имени — `public, max-age=<TTL>, immutable` (TTL задаёт `WEBAPP_ENTRY_CACHE_SECONDS`, 60–31536000, по умолчанию 31536000); выдаём слабый ETag `W/"<fingerprint>"` → честные 304 по `If-None-Match`.
- Не‑фингерпринтнутые ассеты получают короткий кэш (`max-age=300, must-revalidate`).

## HTTP заголовки безопасности
- `X-Content-Type-Options: nosniff`
- `Referrer-Policy: no-referrer`
- `Permissions-Policy: camera=(), microphone=(), geolocation=()`
- `Strict-Transport-Security: max-age=31536000; includeSubDomains` — только в профилях `STAGE/PROD`.

> **Хранение секретов:** Все чувствительные значения (бот-токен, QR-секрет, пароли) грузим из Vault/Secret Manager или CI/CD переменных. Не логируем и не коммитим.

### CORS / Telegram WebApp

- `CORS_ALLOWED_ORIGINS` обязателен в `STAGE/PROD`; сервис не стартует без whitelist. Пример значения: `https://t.me, https://web.telegram.org` (пробелы допустимы — триммируются).
- Dev-профиль по умолчанию включает `anyHost()` (без `allowCredentials`).
- Preflight ответы возвращают `Access-Control-Max-Age: 600` (можно переопределить через `CORS_PREFLIGHT_MAX_AGE_SECONDS`, диапазон 60–86400 секунд), `Access-Control-Allow-Headers: X-Telegram-Init-Data, X-Telegram-InitData, Content-Type, Authorization` и `Vary: Origin`.
- Если origin отсутствует в whitelist, сервер отвечает `403` на `OPTIONS` и не выставляет `Access-Control-Allow-Origin`, поэтому браузер заблокирует кросс-доменные запросы.

### Ротация QR-секретов

- При смене `QR_SECRET` задайте старое значение в `QR_OLD_SECRET` (ENV, docker-compose, Kubernetes secret). Сервер сначала проверяет QR новым ключом и, если он не подошёл, пробует старый.
- Держите оверлап минимум `TTL QR` (12 часов) + запас на час-другой, чтобы все уже выданные коды успели погаснуть. После окна ротации очистите `QR_OLD_SECRET`.

# Сборка и запуск

1. Локальная проверка качества:
   ```bash
   ./gradlew clean build detekt ktlintCheck --console=plain
   ```
2. Сборка рантайма:
   ```bash
   ./gradlew :app-bot:installDist      # дистрибутив в app-bot/build/install/app-bot
   ```
3. Локальный запуск (dev-профиль, порт 8080):
   ```bash
   TELEGRAM_BOT_TOKEN=fake QR_SECRET=dev-secret \
   DATABASE_URL=jdbc:h2:mem:bot;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false \
   DATABASE_USER=sa DATABASE_PASSWORD= \
   ./gradlew :app-bot:run
   ```
4. Docker Compose (если нужен интеграционный стенд):
   ```bash
   cp .env.example .env   # заполнить секреты
   docker compose up -d app postgres
   docker compose logs -f app
   ```
5. Проверки готовности:
   ```bash
   curl -f http://localhost:8080/ready
   curl -f http://localhost:8080/health
   curl -f http://localhost:8080/metrics | head
   ```

# WebApp (Mini App) — доступ и поведение

- URL: `https://<host>/webapp/entry?clubId=<ID>`; обычно открывается через кнопку бота (`KeyboardFactory` → `WebAppInfo`).
- UI кнопка **«Сканировать»** вызывает `Telegram.WebApp.showScanQrPopup` и подписывается на событие `qrTextReceived`.
- После скана клиент делает `fetch` на `/api/clubs/{clubId}/checkin/scan`, добавляя `X-Telegram-Init-Data` и JSON `{ "qr": "<token>" }`.
- Статусный тост отображает ответ сервера (`ARRIVED`, текст ошибки, тайм-аут).

# Безопасность

- **InitData HMAC:** `withMiniAppAuth` пересчитывает подпись по `TELEGRAM_BOT_TOKEN`, отклоняет `401` при неверном HMAC, `auth_date` старше 24 ч или сдвинутый вперёд более чем на 2 минуты.
- **QR токен:** `QrGuestListCodec` ожидает формат `GL:<listId>:<entryId>:<ts>:<hmacHex>`; TTL 12 ч, допускается ≤2 мин сдвига времени, сравнение подписи constant-time.
- **RBAC + clubScope:** `authorize(Role.CLUB_ADMIN | MANAGER | ENTRY_MANAGER)` и `clubScoped(ClubScope.Own)` разрешают доступ только своему `clubId` (глобальные роли проходят вне зависимости от списка).
- **PII:** В логах используем только числовые `clubId`, `listId`, `entryId`, `reason`; `logback.xml` маскирует номера телефонов. Запрещено выводить initData, имя гостя или QR.
- **Rate limiting:** `RateLimitPlugin` считает ключ по `MiniAppUserKey` (Telegram user id); для путей `/api/clubs/` применяются subject-лимиты и IP-лимиты. При превышении — `429` с `Retry-After`.

## Валидация и защита от перегрузки
- Лимит тела чек-ина: по умолчанию 4 KB, настраивается через `CHECKIN_MAX_BODY_BYTES` (диапазон 512–32768 байт).
- Таймаут обработки HTTP-запросов: `HTTP_REQUEST_TIMEOUT_MS` (диапазон 100–30000 мс, по умолчанию 3000 мс), при превышении возвращается `408 Request Timeout`.
- Сервис принимает `X-Request-Id` и отражает его в ответе; RequestId прокидывается в MDC/логи (корреляция ошибок/метрик).
- QR валидируется ранним чекером: длина **12..512** символов, формат `GL:<listId>:<entryId>:<ts>:<hmacHex>` (HMAC — минимум 16 hex‑символов); при нарушении — быстрый 400 с кодом `"invalid_qr_length"`/`"invalid_qr_format"`/`"empty_qr"`.
- Контент‑тип: `Content-Type: application/json` обязателен; иначе — **415 `unsupported_media_type`**.

# API контракты

`POST /api/clubs/{clubId}/checkin/scan`

- Заголовки: `Content-Type: application/json`, `X-Telegram-Init-Data: <telegram-init-data>`.
- Тело запроса:
  ```json
  { "qr": "GL:12345:67890:1732390400:0a1b2c..." }
  ```
  - `qr` должен иметь формат `GL:<listId>:<entryId>:<ts>:<hmacHex>`.
  - Пример валидного QR: `GL:123:456:1732390400:deadbeefdeadbeef`.
  - Пример невалидного QR: `GL:abc:1:2:zz` → ответ `400 invalid_qr_format`.
  - Ответы:
    - `200 {"status":"ARRIVED"}` — успех или повтор.
    - `400` — `"invalid_or_expired_qr"`, `"invalid_json"`, `"entry_list_mismatch"`, `"empty_qr"`, `"invalid_qr_length"`, `"invalid_qr_format"`, `"invalid_club_id"`.
    - `409` — `"outside_arrival_window"`, `"unable_to_mark"` — в едином JSON-формате (см. пример ниже), message опционален.
    - `415` — `"unsupported_media_type"` — заголовок `Content-Type` должен быть `application/json`.
    - Прочие ошибки в `/api/*` также возвращаются в едином JSON-формате: `404 not_found`, `401 unauthorized` (с сохранением `WWW-Authenticate`, если задан), `403 forbidden`, `429 rate_limited (с Retry-After)`, `408 request_timeout`, `413 payload_too_large`, `500 internal_error`.
    - Единый формат ошибок (JSON):
      ```json
      {
        "code": "invalid_qr_format",
        "requestId": "abcd-1234-...",
        "status": 400
      }
      ```
      message — опционален; клиенты должны полагаться на поле code.
    - `401` — неверная подпись initData.
    - `403 "club_scope_mismatch"` — чужой клуб.
    - `404` — `"list_not_found"` или `"entry_not_found"`.

Примеры:
```bash
curl -X POST http://localhost:8080/api/clubs/42/checkin/scan \
  -H 'Content-Type: application/json' \
  -H 'X-Telegram-Init-Data: <encoded-init-data>' \
  -d '{"qr":"GL:42:1001:1732390400:deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"}'

http POST http://localhost:8080/api/clubs/42/checkin/scan \
  'X-Telegram-Init-Data:<encoded-init-data>' \
  qr='GL:42:1001:1732390400:deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef'
```

# QR токены

- Формат: `GL:<listId>:<entryId>:<issuedEpochSeconds>:<HMAC_HEX>`.
- TTL: 12 часов от времени генерации; допускается 2 минуты положительного рассинхрона.
- Генерация: `QrGuestListCodec.encode(listId, entryId, issuedAt, QR_SECRET)` (см. `app-bot/src/main/kotlin/com/example/bot/guestlists/QrGuestListCodec.kt`).
- Распространение: QR печатаем/отправляем гостю, **не** логируем токен в полном виде; при дебаге используем маску `GL:***`.

# Операционный раздел (Runbook)

- **SLO пилота:** медиана `scan→ARRIVED` ≤ 500 мс; ошибка чек-ина ≤ 1%.
- **Метрики (Prometheus):**
  - `ui_checkin_scan_total` — общее количество попыток.
  - `ui_checkin_scan_error_total` — количество ошибок.
  - `ui_checkin_scan_duration_ms_seconds_bucket|sum|count` — гистограмма Micrometer (p50/p95/p99).
  - `ui_checkin_old_secret_total` — сколько успешных чек-инов прошло через fallback по старому QR-секрету.
  - ⚠️ Micrometer экспортирует метрики с точками как имена с подчёркиваниями (`ui.checkin.old_secret_total` → `ui_checkin_old_secret_total`). В алёртах и запросах используем underscore-варианты, как в `/metrics`.
- **Алерты (пример):** (готовый набор правил лежит в [`observability/prometheus/alerts/checkin.yml`](../observability/prometheus/alerts/checkin.yml))
  - `p95(ui_checkin_scan_duration_ms_seconds) > 1.5s` в течение 5 мин.
  - `rate(ui_checkin_scan_error_total[5m]) / clamp_min(rate(ui_checkin_scan_total[5m]), 1e-9) > 0.02`.
  - `rate(ui_checkin_old_secret_total[5m]) > 0` — всплеск использования fallback-секрета.
  - `rate(ui_checkin_old_secret_total[5m]) / clamp_min(rate(ui_checkin_scan_total[5m]), 1e-9) > 0.01` — более 1% чек-инов идут по старому секрету.
  - `(hour() >= 18 && hour() <= 23) && sum(rate(ui_checkin_scan_total[15m])) < 0.001` — нет чек-инов в рабочее окно (30 минут подряд).
- **Логи:**
  - INFO: `checkin.arrived clubId=<id> listId=<id> entryId=<id>`.
  - WARN: `checkin.error reason=<code> clubId=<id> [listId=<id> entryId=<id>]` где `<code>` ∈ {`invalid_or_expired_qr`, `list_not_found`, `entry_not_found`, `club_scope_mismatch`, `invalid_json`, `entry_list_mismatch`, `empty_qr`, `invalid_qr_length`, `invalid_qr_format`, `invalid_club_id`}.
- **Троттлинг:** IP и subject-лимиты управляются переменными `RL_SUBJECT_RPS`, `RL_SUBJECT_BURST`, `RL_SUBJECT_TTL_SECONDS`, `RL_RETRY_AFTER_SECONDS`, `RL_IP_RPS`, `RL_IP_BURST`. При всплесках ошибок проверяем частоту 429 на `/metrics` и записи `Too Many Requests` в логах; временное повышение лимитов согласовываем с Security.
- **Резервные сценарии:**
  - Камера не работает — использовать поиск в админке по имени/телефону (`/app` → список гостей) и вручную поставить `ARRIVED`.
  - Нет сети — вести бумажный лист прихода, позже внести в систему (сохранить время входа).
  - Ошибка `401` (initData) — попросить сотрудника закрыть и заново открыть Mini App через бота.
  - Ошибка `403` (scope) — проверить, что пользователь авторизован как сотрудник нужного клуба.

# Тесты / Smoke перед открытием двери

- `GET /ready` → `200 OK`, `GET /health` → `200 OK`, `/metrics` доступен.
- WebApp открывается, `Сканировать` вызывает нативное окно Telegram.
- QR happy-path: валидный токен → `200 ARRIVED`; испорченный токен → `400 invalid_or_expired_qr`; токен другого клуба → `403`; повторное сканирование → `200 ARRIVED`.
- После каждой операции счётчики `ui_checkin_scan_total`/`ui_checkin_scan_error_total` увеличиваются на `/metrics`.

# Траблшутинг

| Симптом | Что проверить | Команда / действие | Ожидаемый результат |
| --- | --- | --- | --- |
| `401 Unauthorized` | Свежесть `initData`, верность `TELEGRAM_BOT_TOKEN` | Открыть Mini App заново; `kubectl exec`/`docker compose exec app` и проверить переменную | Новый запуск выдаёт `200` |
| `400 invalid_or_expired_qr` | Время выпуска QR и корректность токена | `docker compose logs app | grep invalid_or_expired_qr`; сверить `issuedAt` с `docker compose exec app date` | Обнаруживаем просрочку или опечатку и перевыпускаем QR |
| `403 club scope mismatch` | Роли пользователя, соответствие `clubId` | Проверить RBAC в админке; `./gradlew :core-data:run --args='user-roles <userId>'` (если доступно) | Пользователь видит только свой клуб |
| `404 List/Entry not found` | Гость удалён или список закрыт | Проверить статус в панели гостевых списков | Решить через ручной чек-ин или восстановить запись |
| `5xx` на API | Состояние БД/серверов | `kubectl logs` или `docker compose logs app` → искать stacktrace; проверить `/ready` | После восстановления сервис отвечает 200 |
| Таймаут в WebApp | Сеть, rate-limit | `curl -w '%{http_code}\n' -H 'X-Telegram-Init-Data:…' http://localhost:8080/api/clubs/<id>/checkin/scan` | Ответ приходит ≤1 с без 429 |
| 429 Too Many Requests | Subject/IP лимиты | `docker compose logs app | grep "Too Many Requests"`; при необходимости временно повысить `RL_SUBJECT_RPS` | Кол-во блокировок падает после корректировки |

# Вопросы приватности и хранения данных

- **Не сохраняем:** содержимое `initData`, QR-токены и исходные подписи. В БД храним только идентификаторы списков и отметки о приходе.
- **Логи:** структурированные JSON через Logback, телефонные номера маскируются. Логи отправляются в централизованный стек мониторинга с ротацией по политике платформы (30 дней). Доступ ограничен SRE и Security.
- **Экспорт:** при выгрузке списков обезличиваем имена/телефоны или используем внутренний идентификатор гостя.

# Ссылки по коду

- MiniApp авторизация: `withMiniAppAuth` (см. `app-bot/src/main/kotlin/com/example/bot/plugins/InitDataAuth.kt`)
- Check-in маршруты: `app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt`
- Метрики UI: `app-bot/src/main/kotlin/com/example/bot/metrics/UiCheckinMetrics.kt`
- QR кодек: `app-bot/src/main/kotlin/com/example/bot/guestlists/QrGuestListCodec.kt`
- WebApp статика: `app-bot/src/main/resources/webapp/entry/app.js`

# Версии и совместимость

- Проект собран и протестирован с Kotlin JVM 2.2.10, Ktor Server Netty 2.x, Coroutines 1.8.x, Java 21.
- Конкретные версии библиотек заданы в Gradle каталоге; на стенде допускаются минорные отличия (совместимые API) при условии повторного smoke-теста.
- Фактическая инфраструктурная конфигурация пилотной площадки (Ingress, CDN, reverse-proxy) может отличаться; синхронизируйтесь с DevOps перед деплоем.
