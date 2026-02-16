# Блок 17 (Безопасность/аудит/анти‑фрод) — аудит соответствия

## 1) Что реализовано и где

## 1.1 Webhook secret token и базовая защита webhook

### Реализовано

- В runtime используется `telegramWebhookRoutes` с проверкой `X-Telegram-Bot-Api-Secret-Token` (если `expectedSecret` не пустой), иначе `401`.
- Маршрут подключён в `Application.module()` как `telegramWebhookRoutes(expectedSecret = config.webhook.secretToken, ...)`.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/routes/TelegramWebhookRoutes.kt:15-31`
- `app-bot/src/main/kotlin/com/example/bot/Application.kt:473-476`

### Дополнительно (но не в основном wiring)

- Есть более жёсткий `WebhookSecurity` plugin: проверка метода/content-type/body-size, dedup по `update_id`, suspicious IP журнал.
- Однако в основном `Application.module()` подключается `telegramWebhookRoutes`, а не `webhookRoute(...){ install(WebhookSecurity) }`.

Ссылки:
- `core-security/src/main/kotlin/com/example/bot/security/webhook/WebhookSecurityPlugin.kt:86-111`
- `core-security/src/main/kotlin/com/example/bot/security/webhook/WebhookSecurityPlugin.kt:113-166`
- `app-bot/src/main/kotlin/com/example/bot/webhook/WebhookRoutes.kt:34-38`
- `app-bot/src/main/kotlin/com/example/bot/Application.kt:473-476`

## 1.2 Валидация Mini App initData (HMAC)

### Реализовано

- `withMiniAppAuth` извлекает `initData` и валидирует через `InitDataValidator`.
- `InitDataValidator` реализует HMAC-SHA256 по алгоритму Telegram (`WebAppData` key derivation), проверяет `auth_date` (maxAge/future skew), сравнение хэша через `MessageDigest.isEqual`.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/plugins/InitDataAuth.kt:63-99`
- `core-security/src/main/kotlin/com/example/bot/security/auth/InitDataValidator.kt:27-33`
- `core-security/src/main/kotlin/com/example/bot/security/auth/InitDataValidator.kt:75-93`

### Важный нюанс

- Поддерживается legacy fallback чтения `initData` из query/body (не только из заголовка), что увеличивает поверхность утечек через прокси/access logs.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/plugins/InitDataAuth.kt:151-159`
- `app-bot/src/main/kotlin/com/example/bot/plugins/InitDataAuth.kt:176-199`

## 1.3 RBAC на админ‑операции

### Реализовано

- Есть централизованный `RbacPlugin` + DSL `authorize/clubScoped`; решения доступа пишутся в audit log (`HTTP_ACCESS` events).
- Основные admin-маршруты оформлены через `withMiniAppAuth + authorize(...)`.

Ссылки:
- `core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt:229-251`
- `core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt:373-406`
- `app-bot/src/main/kotlin/com/example/bot/routes/AdminClubsRoutes.kt:66-69`
- `app-bot/src/main/kotlin/com/example/bot/routes/AdminHallsRoutes.kt:69-72`
- `app-bot/src/main/kotlin/com/example/bot/routes/AdminTablesRoutes.kt:111-114`

### Частично

- Для части критичных контуров есть conditional fallback без RBAC при `RBAC_ENABLED=false` / plugin недоступен (например, outbox admin, cancel/refund routes).

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/routes/OutboxAdminRoutes.kt:114-117`
- `app-bot/src/main/kotlin/com/example/bot/routes/OutboxAdminRoutes.kt:314-318`
- `app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt:76-77`
- `app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt:103-112`

## 1.4 Идемпотентность write-операций

### Реализовано

- HOLD/BOOKING:
  - idempotency key хранится в `booking_holds`/`bookings` (UNIQUE) и проверяется в репозитории;
  - active booking/hold конфликты отрабатываются через уникальность и проверки.
- CHECKIN:
  - один check-in на subject (`UNIQUE(subject_type, subject_id)`), при гонках unique violation обрабатывается как already used.
- REFUND/PAYMENT actions:
  - для payment actions есть `idempotency_key` + unique index,
  - refund endpoint требует `Idempotency-Key`.

Ссылки:
- `core-data/src/main/resources/db/migration/postgresql/V1__init.sql:136-145`
- `core-data/src/main/resources/db/migration/postgresql/V1__init.sql:152-176`
- `core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingRepository.kt:460-470`
- `core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingRepository.kt:552-561`
- `core-data/src/main/resources/db/migration/postgresql/V017__guest_list_invites_checkins.sql:70-83`
- `core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt:295-299`
- `core-data/src/main/resources/db/migration/postgresql/V13__payments_actions.sql:4-12`
- `app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt:331-336`

## 1.5 PII-минимизация и маскирование

### Реализовано

- Специальная маскировка QR токенов в логировании.
- Audit metadata санитизируется: вычищаются ключи `initdata/init_data/qr/token/phone`, телефоноподобные значения редактируются.
- В ops-notifications используется хэш subjectId вместо raw значения.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/logging/Masking.kt:3-15`
- `core-data/src/main/kotlin/com/example/bot/data/audit/AuditLogRepositoryImpl.kt:97-139`
- `app-bot/src/main/kotlin/com/example/bot/notifications/TelegramOperationalNotificationService.kt:308-323`

## 1.6 Аудит-лог критичных действий

### Реализовано

- Есть централизованный `AuditLogger` и репозиторий audit log.
- Явно логируются RBAC access decisions, check-in события, table ops, закрытие смены и ряд других операций.

Ссылки:
- `core-domain/src/main/kotlin/com/example/bot/audit/AuditLogger.kt:12-15`
- `core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt:392-405`
- `core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt:567-567`
- `app-bot/src/main/kotlin/com/example/bot/Application.kt:380-397`

## 1.7 Анти‑фрод промо и персонала

### Реализовано

- Для промо HOLD есть квоты (`PromoterQuotaService`) и ответ `PROMOTER_QUOTA_EXHAUSTED` при исчерпании.
- Есть rate-limit plugin (IP + subject) для hot paths.
- Webhook anti-abuse: dedup/suspicious-ip реализован в отдельном security plugin.

Ссылки:
- `core-domain/src/main/kotlin/com/example/bot/promoter/quotas/PromoterQuotaService.kt:29-50`
- `app-bot/src/main/kotlin/com/example/bot/booking/a3/BookingState.kt:109-117`
- `app-bot/src/main/kotlin/com/example/bot/routes/BookingA3Routes.kt:571-585`
- `app-bot/src/main/kotlin/com/example/bot/plugins/RateLimitPlugin.kt:31-49`
- `app-bot/src/main/kotlin/com/example/bot/plugins/RateLimitPlugin.kt:125-146`
- `core-security/src/main/kotlin/com/example/bot/security/webhook/WebhookSecurityPlugin.kt:152-166`

---

## 2) Что отсутствует / частично

1. **Hardened webhook контур не в основном runtime-wiring**:
   - security plugin для webhook есть, но основной `Application.module()` использует более простой route.

2. **Legacy fallback initData из query/body** оставляет лишнюю поверхность утечек и replay-практик (даже при HMAC-валидации).

3. **RBAC не является безусловным для всех критичных write-маршрутов**: есть ветки с отключением RBAC по env/plugin availability.

4. **Аудит критичных конфиг-операций неполный**: часть admin-контуров опирается на `logger.info`, но не на структурированный `AuditLogger` pipeline.

5. **Анти‑фрод для промо/персонала в основном rule-based и локальный** (квоты/лимиты), не найдена полноценная fraud-аналитика/флаги риска/автосанкции.

---

## 3) Уязвимости и риски (P0/P1/P2)

## P0

1. **Webhook hardening gap в основном runtime**.
   - Почему P0: webhook — внешний входной периметр; отсутствие unified hardening (dedup/suspicious-IP/body guard в основном маршруте) повышает риск flood/replay/операционных сбоев.

## P1

1. **RBAC optional fallback на отдельных критичных write-ветках** (`outbox admin`, `payments cancel/refund` при `RBAC_ENABLED=false`).
2. **initData из query/body** (legacy) повышает риск утечки auth-параметров в инфраструктурных логах.
3. **Неполное покрытие AuditLogger для части admin config-операций**, снижает расследуемость инцидентов.

## P2

1. **Анти‑фрод без поведенческой аналитики** (скоринги/аномалии/флаги риска) — текущая защита в основном на квотах/лимитах и точечных правилах.

---

## 4) Рекомендованные правки

1. Перевести production webhook на единый hardened route с `WebhookSecurity` (или встроить те же проверки в `telegramWebhookRoutes`).
2. Отключить legacy чтение `initData` из query/body в prod/stage (`allowInitDataFromBody=false`, запрет query fallback), оставить только заголовок.
3. Убрать режимы bypass RBAC для критичных write API в production профилях (fail-closed вместо fail-open).
4. Расширить обязательный `AuditLogger` на admin-конфиг изменения (clubs/halls/tables/ops chats и т.п.).
5. Добавить слой fraud-signals: аномалии по промо/персоналу (скорость действий, deny patterns, mass operations), risk flags, алерты и ручной review workflow.
