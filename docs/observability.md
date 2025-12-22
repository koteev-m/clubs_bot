# Observability

## Overview
Мы экспонируем метрики через Micrometer + Prometheus. Эндпоинт `/metrics` отдаёт текстовый формат Prometheus 0.0.4 и подключён к глобальному `PrometheusMeterRegistry`.

## Метрики БД (Hikari pool)
После инициализации пула Hikari в метриках появляются gauges с тегом `pool` (например, `pool="primary"`):

- `db_pool_active_connections` — активные соединения, занятые запросами.
- `db_pool_idle_connections` — свободные (idle) соединения.
- `db_pool_pending_connections` — потоки, ожидающие свободного соединения.
- `db_pool_max_connections` — максимально доступные соединения пула.
- `db_pool_min_idle_connections` — минимальное количество idle-коннектов.

Как посмотреть:
- Локально: `curl http://localhost:8080/metrics | grep db_pool_active_connections`.
- Пример PromQL: `db_pool_active_connections{pool="primary"}` для текущей загрузки или `rate(db_pool_active_connections{pool="primary"}[5m])` для динамики.

## HTTP-метрики и алерты
Основные алерты описаны в `devops/alerts/prometheus/app-bot-rules.yaml` и используют HTTP метрики Micrometer (`http_server_requests_seconds_count`):

### ApiHigh5xxRate
- Мониторит долю ответов 5xx > 5% за 5 минут (`rate(http_server_requests_seconds_count{status=~"5.."}[5m])`).
- Runbook: см. этот раздел — `#apihigh5xxrate`.
- При срабатывании: проверить логи и внешние зависимости (БД, сторонние API).

### ApiAuthErrorsSpike
- Следит за всплесками 401/403 (порог ~0.5 rps за 10 минут).
- Runbook: `#apiautherrorsspike`.
- При срабатывании: проверить токены/логин, возможные атаки или устаревшие ключи.

### ApiRateLimitSpike
- Срабатывает на рост 429 (порог ~0.5 rps за 10 минут).
- Runbook: `#apiratelimitspike`.
- При срабатывании: убедиться в корректности лимитов и поведении клиентов.

### ApiNoTraffic
- Фиксирует отсутствие входящего трафика 15 минут (`sum(rate(http_server_requests_seconds_count[5m])) == 0`).
- Runbook: `#apinotraffic`.
- При срабатывании: проверить балансер/ingress, DNS, запуск приложения.

### DbPoolExhausted
- Слежение за загрузкой пула: активные соединения > 90% от максимума 5 минут (`db_pool_active_connections / db_pool_max_connections`).
- Runbook: `#dbpoolexhausted`.
- При срабатывании: проверить долгие запросы, утечки коннектов, повысить пул или оптимизировать запросы.

## Как проверить /metrics локально
- Запустить сервис: `./gradlew :app-bot:run` (или использовать существующий способ в окружении).
- Открыть метрики: `curl http://localhost:8080/metrics | head`.
- Проверить Hikari метрики: `curl http://localhost:8080/metrics | grep db_pool_active_connections`.
- Метрики пула появляются только если инициализирован DataSource/Hikari (dev-профиль без БД их не покажет).

## See also
- Prometheus правила: `devops/alerts/prometheus/app-bot-rules.yaml`.
- Другие документы по инфраструктуре и безопасности: `docs/WEBHOOK_SECURITY.md`, `docs/RBAC.md`.
