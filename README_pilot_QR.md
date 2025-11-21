# Pilot QR & Mini App integration

## Environment variables
- `TELEGRAM_BOT_TOKEN` — bot token used to validate Telegram init data (never log or expose it).
- `QR_SECRET` — HMAC secret for QR signatures generated for guest list entries.

## Mini App entry point
- Static bundle is served from `GET /webapp/entry/*`.
- Open `https://<host>/webapp/entry?clubId=<id>` inside Telegram via a WebApp button to launch the mini application.

## Check-in API
- `POST /api/clubs/{clubId}/checkin/scan` accepts a QR payload and marks the guest as arrived.
- The request **must** include an `X-Telegram-Init-Data` header; it is verified by `withMiniAppAuth` before RBAC is applied.
- Init data older than 24 hours or more than 2 minutes ahead of the server clock is rejected to avoid replays/time skew.

## RBAC & scoping
- Allowed roles: `ENTRY_MANAGER`, `CLUB_ADMIN`, `MANAGER`.
- The handler is wrapped with `authorize(...)` and `clubScoped(Own)` to ensure operators work only within their club.

## Rate limiting
- Check-in requests are covered by the subject rate limiter (see `RateLimitPlugin` configuration) to protect against repeated scans.

## CORS для Mini App
- В проде задайте `CORS_ALLOWED_ORIGINS`, например:
  - https://t.me
  - https://web.telegram.org
  - (если используете desktop/webview — добавьте соответствующие origin'ы)
- В dev `CORS_ALLOWED_ORIGINS` можно оставить пустым, и сервер включит `anyHost()`. В `STAGE/PROD` приложение не стартует без whitelist.
- Preflight ответы кэшируются на 10 минут (`Access-Control-Max-Age: 600`). Таймаут можно переопределить через `CORS_PREFLIGHT_MAX_AGE_SECONDS` (60–86400 секунд).
- Список заголовков включает `X-Telegram-Init-Data`, `X-Telegram-InitData`, `Content-Type` и `Authorization`; убедитесь, что reverse-proxy их не отбрасывает и что переменная окружения не пуста на staging/production.
- Если origin отсутствует в whitelist, CORS отвечает `403` без `Access-Control-Allow-Origin`, поэтому браузер блокирует запросы автоматически.
- Для reverse-proxy/ingress обязательно увеличьте буферы заголовков (см. `docs/ops/ingress.md`), т.к. `X-Telegram-Init-Data` может быть длинным.

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

## Smoke‑проверка заголовков

Быстрая локальная/стендовая проверка основных заголовков (HSTS, Referrer‑Policy, X‑Content‑Type‑Options, Permissions‑Policy, CSP) и кэша статики:

```bash
./tools/smoke_headers.sh https://your-host
```

По умолчанию скрипт бьёт в `http://localhost:8080`:
```bash
./tools/smoke_headers.sh || true
```

> В dev HSTS может быть отключён (ожидаемо); см. пример базового конфига Caddy в `deploy/caddy/Caddyfile`.
