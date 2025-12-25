# Алерты и SLO/SLA

## SLO / SLA

- Доступность API: целимся в ≥99.9% за 30 дней. `/health` и `/ready` должны отвечать без деградации, `ApiNoTraffic` (см. rules) фиксирует отсутствие запросов.
- Время ответа API: p95 ≤ 500 мс, p99 ≤ 1.5 с для основных Mini App маршрутов (`http_server_requests_seconds`).
- БД: circuit breaker не должен открываться в штатной работе; `db.breaker.opened` используется как симптом деградации.
- Фоновые процессы: отдельные воркеры не выделены, SLA завязаны на HTTP и миграции. Миграции должны валидироваться/применяться до поднятия трафика (см. `dr.md`).

## Источники сигналов

- Метрики Micrometer/Prometheus:
  - БД: `db.tx.retries{reason}`, `db.tx.failures{reason}`, `db.tx.duration{readOnly}`, `db.breaker.opened`.
  - Миграции: `db.migrations.validate.success|failure`, `db.migrations.migrate.success|failure`, `db.migrations.pending` (c тегами `env`, `mode`).
  - Пул соединений: `db_pool_active_connections`, `db_pool_idle_connections`, `db_pool_pending_connections`, `db_pool_max_connections`, `db_pool_min_idle_connections` (tag `pool="primary"`).
  - HTTP: `http_server_requests_seconds_count` и percentiles/duration; алерты описаны в `devops/alerts/prometheus/app-bot-rules.yaml`.
  - QR / Mini App: `ui_checkin_rotation_active`, `ui_checkin_rotation_deadline_seconds`, `ui_checkin_old_secret_fallback_total`, `ui_checkin_qr_invalid_total`, `ui_checkin_qr_expired_total`, `ui_checkin_qr_scope_mismatch_total`, `miniapp.cache.hit304/miss304`.
- Логи: ERROR/FATAL приложения, сообщения о проваленных миграциях/невалидных схемах, `checkin.scan`/`booking.qr` WARN для QR-проблем.
- Health/ready пробы: `GET /health`, `GET /ready` (доступность БД, применённые миграции).

## Правила алертов

| Имя/группа алерта | Метрика/источник | Условие срабатывания | Канал/ответственный | Комментарий/Runbook |
| --- | --- | --- | --- | --- |
| ApiHigh5xxRate / ApiAuthErrorsSpike / ApiRateLimitSpike | `http_server_requests_seconds_count` (см. `devops/alerts/prometheus/app-bot-rules.yaml`) | Соответствуют правилам в Prometheus: рост 5xx >5% за 5м; 401/403 >0.5 rps 10м; 429 >0.5 rps 10м. | Slack #alerts-backend (дежурный разработчик) | Проверить логи, внешние зависимости, rate limits. |
| ApiNoTraffic | `http_server_requests_seconds_count` | Ноль запросов 15 минут. | Slack #alerts-backend | Проверить ingress/DNS/деплой. |
| DbBreakerOpened | `db.breaker.opened` или `/health`=DB_UNAVAILABLE | Любое ненулевое значение за окно 5–10 минут, либо `/health` 503. | Slack #alerts-database / on-call | См. `docs/runtime-db-resiliency.md` (breaker/open). |
| DbTxFailures | `db.tx.failures{reason}` | Рост `reason=serialization|deadlock|connection` > baseline (например >5 в 5м). | Slack #alerts-database | Анализировать SQL/нагрузку; возможно, включить ретраи/увеличить таймауты. |
| DbMigrationsPending | `db.migrations.pending` | `> 0` в prod/stage после деплоя; либо `db.migrations.validate.failure` >0. | Slack #alerts-platform | См. `docs/dr.md` (раздел миграций) и CI `db-migrate` логи. |
| HikariPoolExhausted | `db_pool_active_connections / db_pool_max_connections` | >90% 5 минут (см. правило `DbPoolExhausted`). | Slack #alerts-database | Проверить долгие запросы/утечки; при необходимости поднять пул в пределах лимитов. |
| QrRotationDeadline | `ui_checkin_rotation_active`, `ui_checkin_rotation_deadline_seconds` | `ui_checkin_rotation_active=1` и дедлайн в прошлом/ближайшие N минут. | Slack #alerts-backend | См. `docs/qr-rotation.md` (закрыть окно, перевыпустить QR). |
| QrInvalidSpike | `ui_checkin_qr_invalid_total`, `ui_checkin_qr_expired_total`, `ui_checkin_qr_scope_mismatch_total` | Резкий рост ошибок сканирования (порог устанавливается операторами мониторинга). | Slack #alerts-backend | Проверить QR секреты/TTL, логи `checkin.scan` и связанный клуб/ивент. |

## Каналы уведомлений

- Slack: `#alerts-backend` (API/QR), `#alerts-database` (БД/пул), `#alerts-platform` (миграции/инфра).
- E-mail/on-call: rota команды backend; продублировать критические инциденты.
- Telegram-бот: допустим для дублирования уведомлений (без секретов в ссылках).

## Runbook для алертов

- **DB недоступна / breaker открыт** → `docs/runtime-db-resiliency.md` (ретраи, breaker окна), `docs/dr.md` (фейловер/restore). Проверить `/health`, Hikari метрики и `db.tx.*`.
- **Проблемы с миграциями** → `db.migrations.*`, CI workflow `db-migrate`, раздел Flyway в `docs/dr.md`.
- **Проблемы QR** → `docs/qr-rotation.md` (окно ротации, дедлайн), логи `checkin.scan`, метрики `ui_checkin_qr_*`.
- **HTTP пики/5xx** → заглянуть в `docs/observability.md` (описание существующих правил) и сервисные логи; проверить внешние зависимости и балансер.
