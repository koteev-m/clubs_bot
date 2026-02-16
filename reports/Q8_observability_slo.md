# Q8 — Observability & SLO аудит (логи, метрики, алерты, PII masking)

## Executive summary

- В проекте есть зрелая база observability: JSON-логирование через logstash encoder, request/call correlation (`X-Request-Id`, `callId`, trace/span в MDC), Prometheus `/metrics`, health/readiness endpoints и набор Prometheus-алертов по HTTP/DB/check-in/payments. (`app-bot/src/main/resources/logback.xml:14-25`, `app-bot/src/main/kotlin/com/example/bot/plugins/LoggingAndRequestId.kt:18-33`, `app-bot/src/main/kotlin/com/example/bot/plugins/TracingPlugin.kt:24-45`, `app-bot/src/main/kotlin/com/example/bot/plugins/MetricsPlugin.kt:55-63`, `devops/alerts/prometheus/app-bot-rules.yaml:6-79`, `observability/prometheus/alerts/checkin.yml:6-67`, `tools/observability/alerts/payments.yml:4-73`)
- PII masking частично реализован (phone/name/token masking в логах + deny filter чувствительных ключей), а в ops-логах используется hash subject id; но нет явной централизованной политики masking для всех исходящих админ-чат сообщений и некоторых custom логов. (`app-bot/src/main/kotlin/com/example/bot/logging/MessageMaskingConverter.kt:13-40`, `app-bot/src/main/kotlin/com/example/bot/logging/DenySensitiveTurboFilter.kt:26-35`, `app-bot/src/main/kotlin/com/example/bot/notifications/TelegramOperationalNotificationService.kt:242-246`, `app-bot/src/main/kotlin/com/example/bot/notifications/OpsNotificationRenderer.kt:12-21`)
- Единый JSON error-format для `/api/*` есть (`ApiError` + `JsonErrorPages`), но в кодовой базе присутствуют и альтернативные обработчики `StatusPages`, что создаёт риск несогласованного формата ошибок на части маршрутов. (`app-bot/src/main/kotlin/com/example/bot/http/ApiError.kt:13-40`, `app-bot/src/main/kotlin/com/example/bot/plugins/JsonErrorPages.kt:21-93`, `app-bot/src/main/kotlin/com/example/bot/plugins/SecurityPlugins.kt:49-60`)
- Метрики latency/error для check-in, booking UI, DB transactions, Hikari, payments и rate-limit присутствуют, но нет явного SLI по webhook processing time/queue lag как first-class метрик, и часть alert rules остаётся шаблонной с TODO-порогами. (`app-bot/src/main/kotlin/com/example/bot/metrics/UiCheckinMetrics.kt:90-122`, `app-bot/src/main/kotlin/com/example/bot/metrics/UiBookingMetrics.kt:75-93`, `app-bot/src/main/kotlin/com/example/bot/metrics/MicrometerDbMetrics.kt:23-61`, `app-bot/src/main/kotlin/com/example/bot/telemetry/PaymentsMetrics.kt:53-95`, `tools/observability/alerts/payments.yml:4-51`, `app-bot/src/main/kotlin/com/example/bot/routes/TelegramWebhookRoutes.kt:42-49`)
- SLO/SLI формально не зафиксированы как контракт (документировано много метрик и алертов, но нет единой таблицы целей p95/error budget и бизнес-SLI вроде no-show/denied spikes, financial mismatch drift). (`docs/observability.md:19-55`, `docs/alerts.md:21-33`)

## Что есть сейчас (path:lines)

### 1) Логирование, формат, корреляция

- JSON structured logging в logback (`LoggingEventCompositeJsonEncoder`, `timestamp/logLevel/loggerName/threadName/message/mdc`). (`app-bot/src/main/resources/logback.xml:14-25`)
- Разделение appender по окружению (dev console / prod file), root level INFO + отдельные debug логгеры. (`app-bot/src/main/resources/logback.xml:8-13`, `app-bot/src/main/resources/logback.xml:28-60`)
- Correlation:
  - `CallId` plugin: retrieve/generate/verify `X-Request-Id`, возврат в response header;
  - `CallLogging` кладёт `requestId` в MDC;
  - tracing plugin добавляет `traceId`, `spanId`, `callId`, `requestId` и теги span.
  (`app-bot/src/main/kotlin/com/example/bot/plugins/LoggingAndRequestId.kt:18-33`, `app-bot/src/main/kotlin/com/example/bot/plugins/TracingPlugin.kt:24-57`)

### 2) PII masking / секреты в логах и сообщениях

- Message masking converter:
  - маскирует телефоны;
  - маскирует пары ФИО/имени;
  - редактирует длинные токены-паттерны.
  (`app-bot/src/main/kotlin/com/example/bot/logging/MessageMaskingConverter.kt:13-31`, `app-bot/src/main/kotlin/com/example/bot/logging/MessageMaskingConverter.kt:33-98`)
- Turbo filter отбрасывает сообщения с чувствительными ключами (`qr`, `start_param`, `idempotencyKey`). (`app-bot/src/main/kotlin/com/example/bot/logging/DenySensitiveTurboFilter.kt:26-35`)
- Для ops notifications в приложенческих логах subject id хешируется (`subject_id_hash`). (`app-bot/src/main/kotlin/com/example/bot/notifications/TelegramOperationalNotificationService.kt:242-246`, `app-bot/src/main/kotlin/com/example/bot/notifications/TelegramOperationalNotificationService.kt:316-323`)

### 3) Метрики

- Общая платформа: Prometheus registry + `/metrics` endpoint + Micrometer plugin. (`app-bot/src/main/kotlin/com/example/bot/plugins/MetricsPlugin.kt:28-63`)
- DB/Hikari:
  - tx retries/failures/duration/breaker;
  - Hikari pool gauges (`db_pool_*`).
  (`app-bot/src/main/kotlin/com/example/bot/metrics/MicrometerDbMetrics.kt:23-61`, `docs/observability.md:6-17`)
- Product metrics:
  - check-in latency/errors (`ui.checkin.scan.*`, by-name, QR rotation);
  - booking flow timings;
  - payments duration/errors/idempotent hits/outbox counters.
  (`app-bot/src/main/kotlin/com/example/bot/metrics/UiCheckinMetrics.kt:69-127`, `app-bot/src/main/kotlin/com/example/bot/metrics/UiBookingMetrics.kt:75-93`, `app-bot/src/main/kotlin/com/example/bot/telemetry/PaymentsMetrics.kt:53-95`)

### 4) Ошибки и единый формат API

- `ApiError` + `respondError(...)` с `code/message/requestId/status/details` для `/api`. (`app-bot/src/main/kotlin/com/example/bot/http/ApiError.kt:13-40`)
- `JsonErrorPages` централизует 400/401/403/404/415/429/500 для `/api/*`. (`app-bot/src/main/kotlin/com/example/bot/plugins/JsonErrorPages.kt:21-93`)

### 5) Алерты и operational docs

- Продукционные HTTP/DB алерты: 5xx, auth spikes, 429 spikes, no traffic, db pool exhausted. (`devops/alerts/prometheus/app-bot-rules.yaml:6-79`)
- Check-in алерты: p95 latency, error rate, old-secret usage, no traffic during open hours. (`observability/prometheus/alerts/checkin.yml:6-67`)
- Payments alerting шаблон с заготовками (есть TODO thresholds). (`tools/observability/alerts/payments.yml:4-51`)
- Документация по observability/alerts присутствует. (`docs/observability.md:1-55`, `docs/alerts.md:21-33`)

## Чего не хватает / gaps

1. **SLO/SLI как контракта**: нет единой таблицы целей (например p95/p99, availability, error budget по сервисам и бизнес-флоу). (`docs/observability.md:19-55`)
2. **Webhook processing SLI/alerts**: нет явной метрики времени обработки webhook update и alert на деградацию webhook handler latency (при том что handler синхронный). (`app-bot/src/main/kotlin/com/example/bot/routes/TelegramWebhookRoutes.kt:42-49`)
3. **Единообразие error-handling**: присутствуют параллельные `StatusPages`-обработчики помимо `JsonErrorPages`; есть риск drift формата ответов между роутами. (`app-bot/src/main/kotlin/com/example/bot/plugins/JsonErrorPages.kt:21-93`, `app-bot/src/main/kotlin/com/example/bot/plugins/SecurityPlugins.kt:49-60`)
4. **Бизнес-сигналы алертов**: нет готовых правил на всплеск `NO_SHOW`/`DENIED` и автоматический контроль финансового расхождения shift-vs-deposit (в docs упоминается, но не видно конкретных Prometheus rules). (`docs/alerts.md:31-33`)
5. **PII policy для админ-чатов**: есть правило «без токенов» и hashing в runtime-логах, но нет централизованного enforce-механизма masking для всех текстов в ops/support Telegram сообщениях. (`app-bot/src/main/kotlin/com/example/bot/notifications/OpsNotificationRenderer.kt:7-21`)
6. **Alert maturity**: в payments alerts есть TODO thresholds/runbook links, т.е. policy не production-ready из коробки. (`tools/observability/alerts/payments.yml:4-51`)

## Рекомендованный набор

### Минимальный набор для пилота (обязательный)

1. **SLI/SLO v1 документ**:
   - API availability (2xx+4xx non-auth)/total;
   - API p95 latency;
   - webhook p95 processing latency;
   - check-in success rate.
2. **Единый формат ошибок**: один authoritative `StatusPages` policy для `/api/*`, запрет дублирующих exception handlers.
3. **Алерты v1 (без TODO)**:
   - `ApiHigh5xxRate`, `DbPoolExhausted`, `CheckinErrorRateHigh`;
   - webhook latency high;
   - critical no-traffic в рабочие часы.
4. **PII guardrails v1**:
   - обязательный masking middleware для outgoing ops/support сообщений;
   - тест-кейсы на phone/name/token redaction в логах.

### Оптимальный набор для прод

1. **SLO/Error budget framework** по сервисам (API, webhook, check-in, payments, outbox).
2. **Полный tracing rollout** (100% propagation requestId/traceId в async workers + span naming conventions).
3. **Queue/backlog observability**:
   - outbox queue depth, oldest message age, retry/failure rates;
   - alerting на backlog lag.
4. **Бизнес-аномалии**:
   - spikes `DENIED/NO_SHOW`;
   - shift revenue vs deposits mismatch drift;
   - promoter/check-in abuse heuristics.
5. **Runbook completeness**:
   - каждый alert с runbook_url, owner, escalation policy, MTTA/MTTR цели.

## Итог

- Текущий стек observability достаточен для пилота по инфраструктурным метрикам и базовым API/check-in алертам.
- Для production-grade SLO нужен следующий шаг: формализация SLI/SLO, webhook-specific latency/alerts, унификация error-handling и закрытие PII/business-alert пробелов.
