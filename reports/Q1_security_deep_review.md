# Q1 — Глубокий Security Code Review (SAST-style)

## Executive summary

1. В проекте есть сильная база security-контуров: HMAC-проверка Mini App `initData`, scoped RBAC-плагин, idempotency ключи в бронировании/платежах и уникальность check-in на уровне БД.  
   Ссылки: `core-security/src/main/kotlin/com/example/bot/security/auth/InitDataValidator.kt:27-93`, `core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt:229-406`, `core-data/src/main/resources/db/migration/postgresql/V1__init.sql:136-176`, `core-data/src/main/resources/db/migration/postgresql/V017__guest_list_invites_checkins.sql:70-83`, `core-data/src/main/resources/db/migration/postgresql/V13__payments_actions.sql:1-12`.
2. Основной webhook runtime-контур проще, чем hardened plugin: в `Application.module()` подключён `telegramWebhookRoutes`, а не `webhookRoute + WebhookSecurity`; в результате в основном пути отсутствуют dedup/replay guard и content/body hardening.  
   Ссылки: `app-bot/src/main/kotlin/com/example/bot/Application.kt:473-476`, `app-bot/src/main/kotlin/com/example/bot/routes/TelegramWebhookRoutes.kt:17-46`, `app-bot/src/main/kotlin/com/example/bot/webhook/WebhookRoutes.kt:28-39`.
3. RBAC на части критичных admin/write-контуров работает в режиме fail-open при `RBAC_ENABLED=false` (или если плагин не установлен): есть условные ветки, которые регистрируют handlers без `authorize`.  
   Ссылки: `app-bot/src/main/kotlin/com/example/bot/routes/OutboxAdminRoutes.kt:114-117`, `app-bot/src/main/kotlin/com/example/bot/routes/OutboxAdminRoutes.kt:314-318`, `app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt:76-77`, `app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt:103-114`.
4. Анти-спам плагины присутствуют (`RateLimitPlugin`, `HotPathLimiter`), но по коду runtime нет их явной установки в `Application.module()`: риск «ложного чувства защищённости» и неограниченного трафика на hot-paths.  
   Ссылки: `app-bot/src/main/kotlin/com/example/bot/plugins/RateLimitPlugin.kt:81-199`, `app-bot/src/main/kotlin/com/example/bot/Application.kt:430-499`.
5. В payment Telegram-адаптере отсутствуют жёсткие pre-checkout проверки бизнес-инвариантов (payload/amount/owner) — pre-checkout подтверждается без валидаций. Это logic flaw с потенциальной финансовой рассинхронизацией.  
   Ссылки: `app-bot/src/main/kotlin/com/example/bot/telegram/PaymentsHandlers.kt:50-53`, `app-bot/src/main/kotlin/com/example/bot/telegram/PaymentsHandlers.kt:56-61`.
6. Для HOLD есть зазор гонки: миграция прямо фиксирует, что уникальность активного hold обеспечивается только на уровне приложения (без DB partial unique для «активных»), значит при высоком конкурирующем потоке возможны коллизии до resolve на app-уровне.  
   Ссылки: `core-data/src/main/resources/db/migration/postgresql/V7__booking_core.sql:24-31`, `core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingRepository.kt:484-507`.
7. PII-минимизация и маскирование реализованы частично и осознанно (mask QR, sanitize audit metadata), но сохраняется поверхность через fallback `initData` из query/body и хранение телефонов в явном виде (без field-level encryption).  
   Ссылки: `app-bot/src/main/kotlin/com/example/bot/plugins/InitDataAuth.kt:151-199`, `app-bot/src/main/kotlin/com/example/bot/logging/Masking.kt:3-15`, `core-data/src/main/kotlin/com/example/bot/data/audit/AuditLogRepositoryImpl.kt:97-139`, `core-data/src/main/resources/db/migration/postgresql/V1__init.sql:107-113`.
8. Зависимости и supply-chain в целом в хорошем состоянии (pinned SHA в workflows, Trivy, dependency drift), но SCA не выглядит как «жёсткий gate» по JVM CVE в PR (отдельно нужен непрерывный OSS/CVE policy с fail criteria и SLA).  
   Ссылки: `.github/workflows/security-scan.yml:1-49`, `.github/workflows/dependency-drift.yml:1-46`, `gradle/libs.versions.toml:1-123`.

---

## Таблица уязвимостей (P0/P1/P2)

| Priority | Уязвимость | Impact | Где (path:lines) | Как воспроизвести | Как исправить |
|---|---|---|---|---|---|
| **P0** | Основной webhook не использует hardened security plugin (`WebhookSecurity`) | Replay/flood duplicate update, рост нагрузки, эксплуатация malformed payload-path без централизованной anti-abuse телеметрии | `app-bot/src/main/kotlin/com/example/bot/Application.kt:473-476`; `app-bot/src/main/kotlin/com/example/bot/routes/TelegramWebhookRoutes.kt:17-46`; `app-bot/src/main/kotlin/com/example/bot/webhook/WebhookRoutes.kt:28-39`; `core-security/src/main/kotlin/com/example/bot/security/webhook/WebhookSecurityPlugin.kt:54-166` | Отправлять повторяющиеся POST на `/telegram/webhook` с тем же `update_id`: в базовом роуте нет dedup state/409-ответов, update обрабатывается повторно | Перевести runtime на `webhookRoute { install(WebhookSecurity) }`, либо встроить в `telegramWebhookRoutes` эквивалентные проверки: secret+method+content-type+body limit+dedup+suspicious IP |
| **P1** | RBAC fail-open в admin/write маршрутах при `RBAC_ENABLED=false` | Потенциальный несанкционированный доступ к критичным операциям в misconfig-среде | `app-bot/src/main/kotlin/com/example/bot/routes/OutboxAdminRoutes.kt:114-117,314-318`; `app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt:76-77,103-114` | Запустить сервис с `RBAC_ENABLED=false`; запросить `/api/admin/outbox` и cancel/refund роуты с валидным Mini App контекстом | Ввести fail-closed policy для PROD/STAGE: не регистрировать критичные маршруты без активного RBAC-плагина; добавить startup guard |
| **P1** | Legacy fallback `initData` из query/body увеличивает риск утечек auth-артефактов | `initData` может попасть в reverse-proxy/access logs, APM payload logging, browser history/query traces | `app-bot/src/main/kotlin/com/example/bot/plugins/InitDataAuth.kt:151-159,176-199` | Передать `initData` в query `?initData=...`; проверить ingress/access logs | В PROD/STAGE отключить `allowInitDataFromBody` и query fallback; принимать только `X-Telegram-Init-Data` |
| **P1** | Pre-checkout платежа подтверждается без бизнес-валидаций | Риск capture невалидного/устаревшего счета, рассинхрон статусов и финпотерь при edge-cases | `app-bot/src/main/kotlin/com/example/bot/telegram/PaymentsHandlers.kt:50-53,56-61` | Сформировать pre-checkout на booking в пограничном статусе; обработчик всегда отправит `AnswerPreCheckoutQuery(ok=true)` | Перед подтверждением pre-checkout валидировать booking ownership/status/amount/currency/ttl и отвечать `errorMessage` при нарушениях |
| **P1** | Anti-spam/rate-limit плагины не видны в runtime wiring | При burst-трафике hot endpoints не ограничиваются, риск деградации/DoS | `app-bot/src/main/kotlin/com/example/bot/plugins/RateLimitPlugin.kt:81-199`; `app-bot/src/main/kotlin/com/example/bot/Application.kt:430-499` | Проверить startup logs и нагрузить hot-path; отсутствие 429-ответов от rate limiter | Явно устанавливать rate-limit/hot-path plugins в `Application.module()` c fail-fast при неконсистентной конфигурации |
| **P2** | Уникальность «активного HOLD» обеспечивается в приложении, не БД | При высокой конкуренции возможны race/коллизии и нестабильные UX-результаты (очереди/двойные обещания) | `core-data/src/main/resources/db/migration/postgresql/V7__booking_core.sql:24-31`; `core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingRepository.kt:484-507` | Параллельно создать HOLD на один стол/слот из нескольких потоков | Добавить DB-safe стратегию сериализации: advisory lock по `(table_id, slot_start, slot_end)` или serialized section per table-slot |
| **P2** | Audit для части admin-config операций только через `logger.info` | Снижение расследуемости и non-repudiation (кто/когда изменил критичный конфиг) | `app-bot/src/main/kotlin/com/example/bot/routes/AdminClubsRoutes.kt:102-103,137-139,159-160`; `core-data/src/main/kotlin/com/example/bot/data/audit/AuditLogRepositoryImpl.kt:97-139` | Выполнить admin create/update/delete клуба; проверить отсутствие унифицированного audit события в централизованном формате | Обязательный `AuditLogger` для admin config write-операций (+ reason, actor role, diff payload) |
| **P2** | PII хранится в открытом виде в ряде таблиц | Повышенный blast radius при компрометации БД/дампов | `core-data/src/main/resources/db/migration/postgresql/V1__init.sql:107-113,159-161,239-240`; `core-data/src/main/resources/db/migration/postgresql/V1__init.sql:253` | Просмотр дампа БД: `phone_e164` доступен как plaintext | Ввести field-level encryption/tokenization для телефона + строгий retention policy + masked replicas |

---

## Проверка по requested-чеклисту

### 1) Secrets management

**Что хорошо**
- В репозитории не выявлены «явные» закоммиченные ключи/токены типовых форматов (по статическому regex-скану).  
  Команда: `rg -n "(AIza|AKIA...|BEGIN PRIVATE KEY|ghp_)" .`.
- Конфиги имеют placeholders (`.env.example`), а безопасные `safe()`-репрезентации маскируют секреты.  
  Ссылки: `.env.example:63-93`, `core-domain/src/main/kotlin/com/example/bot/config/AppConfig.kt:14-30`.

**Риски**
- Двойной источник bot token (`BOT_TOKEN` и `TELEGRAM_BOT_TOKEN`) создаёт misconfig-риск и рассинхрон между подписью initData и runtime bot token.  
  Ссылки: `.env.example:63-65`, `app-bot/src/main/kotlin/com/example/bot/plugins/Env.kt:27-39`.

### 2) AuthN/AuthZ

- Базовый контур `withMiniAppAuth + authorize + clubScoped` реализован.  
  Ссылки: `app-bot/src/main/kotlin/com/example/bot/routes/AdminClubsRoutes.kt:66-69`, `app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt:120-147`.
- Ключевой риск: fail-open ветки при выключенном RBAC.  
  Ссылки: см. таблицу уязвимостей (P1).

### 3) Telegram surfaces security

- Secret header check есть в основном webhook route.  
  Ссылка: `app-bot/src/main/kotlin/com/example/bot/routes/TelegramWebhookRoutes.kt:23-31`.
- Dedup/replay handling реализован в `WebhookSecurityPlugin`, но не в основном wiring.  
  Ссылки: `core-security/src/main/kotlin/com/example/bot/security/webhook/WebhookSecurityPlugin.kt:133-166`, `app-bot/src/main/kotlin/com/example/bot/Application.kt:473-476`.
- Mini App initData HMAC реализована корректно.  
  Ссылка: `core-security/src/main/kotlin/com/example/bot/security/auth/InitDataValidator.kt:75-93`.

### 4) Инъекции и валидация входа

- SQL в кастомных местах использует prepared statements и enum/whitelist для sort fields (низкий риск SQLi).  
  Ссылки: `core-data/src/main/kotlin/com/example/bot/data/repo/OutboxAdminRepository.kt:68-75`, `core-data/src/main/kotlin/com/example/bot/data/repo/OutboxAdminRepository.kt:548-626`.
- Upload endpoints имеют size/content-type validation, path traversal по filename не обнаружен (filename не используется как путь записи).  
  Ссылки: `app-bot/src/main/kotlin/com/example/bot/routes/AdminMusicRoutes.kt:492-576`, `app-bot/src/main/kotlin/com/example/bot/routes/HallPlanRoutes.kt:197-260`.
- Regex для JSON body extraction (`INIT_DATA_REGEX`) минимальный и может быть обходным/хрупким, но не выглядит как catastrophic-backtracking риск.  
  Ссылка: `app-bot/src/main/kotlin/com/example/bot/plugins/InitDataAuth.kt:206`.

### 5) Concurrency/consistency

- Идемпотентность и уникальность для booking/checkin/payment actions реализована на DB уровне.  
  Ссылки: `core-data/src/main/resources/db/migration/postgresql/V1__init.sql:144,168,174-176`; `core-data/src/main/resources/db/migration/postgresql/V017__guest_list_invites_checkins.sql:82`; `core-data/src/main/resources/db/migration/postgresql/V13__payments_actions.sql:11-12`.
- HOLD race — основной consistency debt (см. P2).  
  Ссылки: `core-data/src/main/resources/db/migration/postgresql/V7__booking_core.sql:24-31`, `core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingRepository.kt:484-507`.

### 6) PII

- Хранение телефона и username в доменных таблицах присутствует.  
  Ссылки: `core-data/src/main/resources/db/migration/postgresql/V1__init.sql:107-113,239-240`.
- Есть частичная маскировка/редакция в logging/audit.  
  Ссылки: `app-bot/src/main/kotlin/com/example/bot/logging/Masking.kt:3-15`, `core-data/src/main/kotlin/com/example/bot/data/audit/AuditLogRepositoryImpl.kt:97-139`.

### 7) Audit

- Централизованный audit pipeline есть, но покрытие admin-config write-операций неполное.  
  Ссылки: `core-data/src/main/kotlin/com/example/bot/data/audit/AuditLogRepositoryImpl.kt:97-139`; `app-bot/src/main/kotlin/com/example/bot/routes/AdminClubsRoutes.kt:102-103,137-139,159-160`.

### 8) Dependencies / CVE risk

- Версии библиотек централизованы (`libs.versions.toml`), workflows pinned по SHA и есть Trivy + drift report.  
  Ссылки: `gradle/libs.versions.toml:1-123`, `.github/workflows/security-scan.yml:1-49`, `.github/workflows/dependency-drift.yml:1-46`.
- Не обнаружен PR-blocking JVM SCA policy с SLA/исключениями (процессный долг, не конкретная CVE в этом отчёте).

### 9) Payments

- HTTP cancel/refund требует `Idempotency-Key` и ведёт action tracking.  
  Ссылки: `app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt:146-150` (cancel flow), `core-data/src/main/resources/db/migration/postgresql/V13__payments_actions.sql:1-12`.
- Telegram pre-checkout бизнес-валидации отсутствуют (см. P1).

---

## Logic flaws (денежные/операционные риски)

1. **Pre-checkout auto-approve без state/amount проверки**  
   Сценарий: booking устарел/изменён, но pre-checkout всё равно `ok=true`; далее payment callback может маркировать capture с некорректной привязкой.  
   Ссылки: `app-bot/src/main/kotlin/com/example/bot/telegram/PaymentsHandlers.kt:50-61`.
2. **HOLD consistency under contention**  
   Сценарий: параллельные попытки hold на тот же слот; логика rely-on-app-check, что при высокой конкуренции может давать «ложные подтверждения» до финального отката.  
   Ссылки: `core-data/src/main/resources/db/migration/postgresql/V7__booking_core.sql:24-31`, `core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingRepository.kt:484-507`.
3. **RBAC fail-open при env misconfig**  
   Сценарий: в staging/prod accidentally `RBAC_ENABLED=false`, критичные admin/write роуты поднимаются без role checks.  
   Ссылки: `app-bot/src/main/kotlin/com/example/bot/routes/OutboxAdminRoutes.kt:314-318`, `app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt:103-114`.

---

## Долги безопасности (что добавить)

1. **Fail-closed bootstrap policy**: если профиль `PROD/STAGE`, не запускать критичные маршруты без RBAC/anti-abuse plugins.
2. **Unified webhook hardening**: единый защищённый webhook ingress + dedup state + suspicious IP throttling в основном runtime.
3. **Strict Mini App auth transport**: только заголовок, запрет query/body fallback в prod/stage.
4. **Security backfill policy**: централизованный аудит всех admin config writes (reason required + diff snapshot + actor scope).
5. **PII hardening roadmap**: encryption-at-rest для `phone_e164`, retention windows, masking по умолчанию в репортах/экспортах.
6. **Payment safety gates**: pre-checkout валидатор бизнес-правил + детерминированная сверка payload↔booking↔amount↔currency.
7. **SCA governance**: еженедельный CVE triage + PR gate для критичных JVM CVE (с допустимым waiver-процессом).

---

## Команды и покрытие анализа

```bash
# Поиск потенциальных секретов/ключей
rg -n "(BOT_TOKEN|SECRET|PASSWORD|API_KEY|BEGIN PRIVATE|ghp_)" .env.example docs app-bot core-* gradle/libs.versions.toml .github/workflows
rg -n "(AIza|AKIA[0-9A-Z]{16}|ghp_|BEGIN PRIVATE KEY)" . -g '!.git' -g '!**/build/**'

# Проверка authn/authz/webhook/initData/PII/payment контуров
rg -n "telegramWebhookRoutes|WebhookSecurity|withMiniAppAuth|authorize\(|RBAC_ENABLED|Idempotency-Key|handlePreCheckout" app-bot core-security core-data core-domain -g '*.kt'

# Проверка зависимостей и security workflows
rg -n "^\s*uses:\s*" .github/workflows/*.yml
nl -ba gradle/libs.versions.toml | sed -n '1,220p'
```
