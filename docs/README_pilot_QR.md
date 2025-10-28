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
    │  InitDataAuthPlugin → TelegramPrincipal
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
| `APP_PROFILE` | Профиль конфигурации (`dev`/`stage`/`prod`). | `stage` |
| `DATABASE_URL` | JDBC строка подключения. | `jdbc:postgresql://db.internal:5432/club` |
| `DATABASE_USER` | Пользователь БД. | `bot_app` |
| `DATABASE_PASSWORD` | Пароль БД. | `********` |
| `GLOBAL_RPS` | Общий лимит запросов (см. `BotLimits`). | `200` |
| `CHAT_RPS` | Лимит на чат/пользователя. | `30` |

> **Хранение секретов:** Все чувствительные значения (бот-токен, QR-секрет, пароли) грузим из Vault/Secret Manager или CI/CD переменных. Не логируем и не коммитим.

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

- **InitData HMAC:** `InitDataAuthPlugin` пересчитывает подпись по `TELEGRAM_BOT_TOKEN`, отклоняет `401` при неверном HMAC или `auth_date` старше 24 ч.
- **QR токен:** `QrGuestListCodec` ожидает формат `GL:<listId>:<entryId>:<ts>:<hmacHex>`; TTL 12 ч, допускается ≤2 мин сдвига времени, сравнение подписи constant-time.
- **RBAC + clubScope:** `authorize(Role.CLUB_ADMIN | MANAGER | ENTRY_MANAGER)` и `clubScoped(ClubScope.Own)` разрешают доступ только своему `clubId` (глобальные роли проходят вне зависимости от списка).
- **PII:** В логах используем только числовые `clubId`, `listId`, `entryId`, `reason`; `logback.xml` маскирует номера телефонов. Запрещено выводить initData, имя гостя или QR.
- **Rate limiting:** `RateLimitPlugin` считает ключ по `InitDataPrincipal.userId`; для путей `/api/clubs/` применяются subject-лимиты и IP-лимиты. При превышении — `429` с `Retry-After`.

# API контракты

`POST /api/clubs/{clubId}/checkin/scan`

- Заголовки: `Content-Type: application/json`, `X-Telegram-Init-Data: <telegram-init-data>`.
- Тело запроса:
  ```json
  { "qr": "GL:12345:67890:1732390400:0a1b2c..." }
  ```
- Ответы:
  - `200 {"status":"ARRIVED"}` — успех или повтор.
  - `400` — `"Invalid or expired QR"`, `"Invalid JSON"`, `"Entry-list mismatch"`, `"Empty QR"`.
  - `401` — неверная подпись initData.
  - `403 "club scope mismatch"` — чужой клуб.
  - `404` — `"List not found"` или `"Entry not found"`.

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
- **Алерты (пример):**
  - `p95(ui_checkin_scan_duration_ms_seconds) > 1.5s` в течение 5 мин.
  - `rate(ui_checkin_scan_error_total[5m]) / rate(ui_checkin_scan_total[5m]) > 0.02`.
- **Логи:**
  - INFO: `checkin.arrived clubId=<id> listId=<id> entryId=<id>`.
  - WARN: `checkin.error reason=<code> clubId=<id> [listId=<id> entryId=<id>]` где `<code>` ∈ {`invalid_or_expired_qr`, `list_not_found`, `entry_not_found`, `scope_mismatch`, `malformed_json`, `missing_qr`}.
- **Троттлинг:** IP и subject-лимиты управляются переменными `RL_SUBJECT_RPS`, `RL_SUBJECT_BURST`, `RL_SUBJECT_TTL_SECONDS`, `RL_RETRY_AFTER_SECONDS`, `RL_IP_RPS`, `RL_IP_BURST`. При всплесках ошибок проверяем частоту 429 на `/metrics` и записи `Too Many Requests` в логах; временное повышение лимитов согласовываем с Security.
- **Резервные сценарии:**
  - Камера не работает — использовать поиск в админке по имени/телефону (`/app` → список гостей) и вручную поставить `ARRIVED`.
  - Нет сети — вести бумажный лист прихода, позже внести в систему (сохранить время входа).
  - Ошибка `401` (initData) — попросить сотрудника закрыть и заново открыть Mini App через бота.
  - Ошибка `403` (scope) — проверить, что пользователь авторизован как сотрудник нужного клуба.

# Тесты / Smoke перед открытием двери

- `GET /ready` → `200 OK`, `GET /health` → `200 OK`, `/metrics` доступен.
- WebApp открывается, `Сканировать` вызывает нативное окно Telegram.
- QR happy-path: валидный токен → `200 ARRIVED`; испорченный токен → `400 Invalid or expired QR`; токен другого клуба → `403`; повторное сканирование → `200 ARRIVED`.
- После каждой операции счётчики `ui_checkin_scan_total`/`ui_checkin_scan_error_total` увеличиваются на `/metrics`.

# Траблшутинг

| Симптом | Что проверить | Команда / действие | Ожидаемый результат |
| --- | --- | --- | --- |
| `401 Unauthorized` | Свежесть `initData`, верность `TELEGRAM_BOT_TOKEN` | Открыть Mini App заново; `kubectl exec`/`docker compose exec app` и проверить переменную | Новый запуск выдаёт `200` |
| `400 Invalid or expired QR` | Время выпуска QR и корректность токена | `docker compose logs app | grep invalid_or_expired_qr`; сверить `issuedAt` с `docker compose exec app date` | Обнаруживаем просрочку или опечатку и перевыпускаем QR |
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

- InitData плагин: `app-bot/src/main/kotlin/com/example/bot/webapp/InitDataAuthPlugin.kt`
- Check-in маршруты: `app-bot/src/main/kotlin/com/example/bot/routes/CheckinRoutes.kt`
- Метрики UI: `app-bot/src/main/kotlin/com/example/bot/metrics/UiCheckinMetrics.kt`
- QR кодек: `app-bot/src/main/kotlin/com/example/bot/guestlists/QrGuestListCodec.kt`
- WebApp статика: `app-bot/src/main/resources/webapp/entry/app.js`

# Версии и совместимость

- Проект собран и протестирован с Kotlin JVM 2.2.10, Ktor Server Netty 2.x, Coroutines 1.8.x, Java 21.
- Конкретные версии библиотек заданы в Gradle каталоге; на стенде допускаются минорные отличия (совместимые API) при условии повторного smoke-теста.
- Фактическая инфраструктурная конфигурация пилотной площадки (Ingress, CDN, reverse-proxy) может отличаться; синхронизируйтесь с DevOps перед деплоем.
