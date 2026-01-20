# Error Registry и /api/.well-known/errors

## Назначение

Error Registry предоставляет машиночитаемый список кодов ошибок API, чтобы фронт/интеграции могли подтягивать HTTP-статусы, стабильность и депрекейт-флаги без жёсткого захардкоживания. Реестр версионирован и отдаётся через общий эндпоинт.

## Эндпоинт /api/.well-known/errors

- Методы: `GET`, `HEAD`.
- Заголовки ответа: `Content-Type: application/json; charset=utf-8`, `Cache-Control: public, max-age=300, stale-while-revalidate=30, stale-if-error=86400`, `ETag: "error-codes-v<version>"` (текущая версия — 8). Это версия реестра (ErrorRegistry.version) для кеширования и контроля изменений набора кодов.
- Кэширование: поддерживаются слабые/джокерные `If-None-Match`; при совпадении ETag — `304 Not Modified` с теми же заголовками. `HEAD` возвращает только заголовки/статус.
- Контракт реализован в `ErrorRegistry` + `errorCodesRoutes()`; фактический маршрут — `/api/.well-known/errors`.
- Общая политика HTTP-заголовков и кэширования описана в `docs/headers-and-cache.md`.

## Структура JSON

Тело соответствует модели `ErrorCodesPayload`:

```json
{
  "version": 2,
  "codes": [
    {
      "code": "invalid_qr_format",
      "http": 400,
      "stable": true,
      "deprecated": false
    }
  ]
}
```

- `version` в JSON — версия схемы payload (ErrorCodesPayload.version), она может быть меньше/больше версии реестра и меняется только при изменении формата JSON (поэтому при ETag `v8` в примерах допустимо видеть `version: 2`).
- Поле `code` — строковый идентификатор (например, `invalid_or_expired_qr`, `idempotency_conflict`).
- `http` — числовой HTTP статус, с которым код обычно возвращается.
- `stable` — флаг стабильности контракта (по умолчанию `true`).
- `deprecated` — флаг снятия с поддержки (по умолчанию `false`).
- Поля выдаются отсортированными по `code` внутри групп `common`, `checkin`, `booking`, `guestLists`, `invitations`, `support`.

## Кэширование и версионирование Error Registry

- ETag обновляется вместе с версией реестра (`"error-codes-v8"` на текущий момент). Клиенты могут кэшировать ответ до смены ETag или до истечения `max-age`/`stale-*` директив.
- При получении `304 Not Modified` клиент может продолжать использовать кэшированное тело; `RouteCacheMetrics` фиксирует hit/miss для анализа.
- При смене версии/наборов кодов ETag изменится автоматически, клиенты получат новое тело.

## Примеры использования

```bash
# Получить реестр ошибок
curl -i https://service.example.com/api/.well-known/errors

# HEAD-запрос (только заголовки/статус)
curl -I https://service.example.com/api/.well-known/errors

# Кэширование с If-None-Match
curl -i -H 'If-None-Match: "error-codes-v8"' https://service.example.com/api/.well-known/errors
# -> 304 Not Modified при актуальном ETag
```
