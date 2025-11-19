# HTTP smoke для check-in

Файлы `.http` можно открыть в IDE (IntelliJ HTTP client, VS Code REST Client, Bruno и т.п.). Перед запуском подставьте переменные вверху `checkin.http`:

- `HOST` — базовый URL сервиса (пример `http://localhost:8080` или staging-хост).
- `CLUB_ID` — идентификатор клуба, для которого доступна Mini App авторизация.
- `INIT_DATA` — валидное `X-Telegram-Init-Data`. Проще всего сгенерировать его через `WebAppInitDataTestHelper` (см. `app-bot/src/test/...`) или мини-скрипт, который подписывает payload HMAC по `TELEGRAM_BOT_TOKEN`. Важно, чтобы `auth_date` был свежим (<24 ч) и соответствовал пользователю с нужными ролями.
- `QR_TOKEN` — QR-токен формата `GL:<listId>:<entryId>:<ts>:<hmac>`, подписанный тем же `QR_SECRET`, который настроен на сервере.

Сценарии внутри файла:

1. `GET /health` — проверка базовой живости.
2. `POST /api/clubs/{clubId}/checkin/scan` — happy-path чек-ина (требует валидных INIT_DATA и QR).
3. `OPTIONS /api/clubs/{clubId}/checkin/scan` — CORS preflight для sanity-проверки заголовков.
