# HTTP заголовки и кэширование

## Обязательные заголовки

| Тип эндпоинта | Пример пути | Обязательные заголовки | Комментарий |
| --- | --- | --- | --- |
| API (Mini App/JSON) | `/api/clubs/{id}/layout`, `/api/bookings/{id}/qr`, `/api/.well-known/errors` | `Content-Type: application/json; charset=utf-8`; `Cache-Control: no-store` для персонифицированных ответов; `Cache-Control: max-age=60, must-revalidate` + `Vary: X-Telegram-Init-Data` + `ETag` там, где включены условные ответы; `ETag` + `Cache-Control: public, max-age=300, stale-while-revalidate=30, stale-if-error=86400` для `/api/.well-known/errors`; `X-Request-Id` всегда возвращается (пересылаем входящий или генерируем). | Mini App API по умолчанию без кэша (no-store) с `Vary: X-Telegram-Init-Data`. Исключения: ответы с ETag/If-None-Match (кластерные кеши поддерживаются через `RouteCacheMetrics`). Без `ETag`/`If-None-Match` не полагаться на кэш. |
| Health/ready | `/health`, `/ready` | `Content-Type: text/plain; charset=utf-8`; кэш не выставляется (ожидается no-store на балансере); `X-Content-Type-Options: nosniff`. | `/health` делает `SELECT 1` в БД с таймаутом (`HEALTH_DB_TIMEOUT_MS`), `/ready` проверяет `MigrationState`. |
| Метрики | `/metrics` | `Content-Type: text/plain; charset=utf-8`; обычно без `Cache-Control` (прокси должны пробрасывать как no-store). | Экспорт Micrometer/Prometheus 0.0.4, включает Hikari (`db_pool_*`), БД (`db.tx.*`, `db.breaker.opened`), миграции (`db.migrations.*`), UI чек-ин (`ui_checkin_*`) и др. |
| Статика (Mini App, layouts) | `/webapp/entry/...`, `/assets/layouts/{clubId}/{fingerprint}.json` | Фингерпринтованные файлы: `Cache-Control: public, max-age=<WEBAPP_ENTRY_CACHE_SECONDS>, immutable` + `Vary: Accept-Encoding` (добавляется, если ещё нет); HTML: `Cache-Control: max-age=60, must-revalidate`; нефингерпринтованные ассеты: `Cache-Control: max-age=300, must-revalidate`; `Content-Type` по расширению; `X-Content-Type-Options: nosniff`. | Кэш настраивается плагином `installWebAppImmutableCacheFromEnv` (`WEBAPP_ENTRY_CACHE_SECONDS` по умолчанию 31_536_000). Layout JSON (`/assets/layouts/...`) возвращает `ETag: {fingerprint}` и длинный публичный кэш. |

> Security: `installHttpSecurityFromEnv` всегда ставит `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy: camera=(), microphone=(), geolocation=()`. HSTS (`Strict-Transport-Security`) включается в профилях `STAGE/PROD`. В проекте нет CSP/X-Frame-Options — можно добавить на уровне ingress при необходимости.

## Кэширование и прокси

- API:
  - Mini App ответы персонализированы → `Cache-Control: no-store` + `Vary: X-Telegram-Init-Data` через `ensureMiniAppNoStoreHeaders()`.
  - Часть GET умеет условные запросы (`ETag`/`If-None-Match`) и короткий кэш: например `/api/bookings/{id}/qr`, `/api/bookings/{id}/ics`, `/api/me/mixtape`, `/api/.well-known/errors`. Метрики попаданий/промахов: `miniapp.cache.hit304` / `miniapp.cache.miss304` с тегом `route`.
- Статика:
  - `/webapp/entry/...` обслуживается с длинным публичным кэшем для версионированных файлов, HTML — короткий revalidate, нефингерпринтованные ассеты — 5 минут.
  - `/assets/layouts/...` используют fingerprint в пути → можно кэшировать годами (`public, max-age=31536000, immutable` в ingress примерах) до смены fingerprint.
- Прокси/балансер ожидания:
  - `X-Request-Id` или `X-Request-ID` принимаются и эхоятся; если нет входящего, генерируется UUID. Полезно прокидывать из ingress для трассировки.
  - Дополнительные `X-Forwarded-*` заголовки не используются в коде напрямую; логика авторизации/маршрутизации от них не зависит.

## Примеры

```bash
# API: Error Registry с ETag и коротким кэшем
curl -i https://service.example.com/api/.well-known/errors
# -> Content-Type: application/json; charset=utf-8
# -> Cache-Control: public, max-age=300, stale-while-revalidate=30, stale-if-error=86400
# -> ETag: "error-codes-v2"

# Статика: долговечный ассет Mini App (fingerprinted)
curl -I https://service.example.com/webapp/entry/app.abcd1234.js
# -> Cache-Control: public, max-age=31536000, immutable
# -> Vary: Accept-Encoding
```
