# Q2 — Senior-оценка качества кода и архитектуры (Clean Architecture / Clean Code)

## 1) Оценка по шкалам (0–5)

- **Architecture:** 2.8/5
- **Reliability:** 3.4/5
- **Security hygiene:** 3.1/5
- **Testability:** 3.7/5
- **Maintainability:** 2.6/5
- **Performance:** 3.0/5
- **UX consistency:** 2.4/5

Кратко: у проекта сильный базис (модули, миграции, тесты, retry/tx), но заметны архитектурные перекосы: «толстый» composition root, смешение слоёв (web+SQL+HTML+Telegram в одном файле), и частичные fail-open паттерны в security/routing.

---

## 2) Что проверено

- Архитектурные слои и границы модулей (`settings.gradle.kts`, Gradle dependencies модулей).
- Composition root, DI wiring, маршрутизация и middleware в Ktor.
- Coroutines/timeout/cancellation паттерны.
- DB/Exposed/Flyway: транзакционность, индексы/ограничения, миграционный контур.
- Код-стиль/читаемость, ошибки обработки исключений.
- Тестируемость и покрытие типами тестов (unit/IT/smoke/concurrency).
- Hot paths и N+1/сканирующие запросы.

---

## 3) Топ-20 проблем (по убыванию критичности)

> Формат: **[C#] Проблема** → impact → где (path:lines)

1. **[C1] Монолитный composition root в `Application.module()` (594 строки, десятки `inject`, ручное связывание всего графа).**
   - Impact: рост стоимости изменений, высокий риск регресса при правке и плохая локализуемость дефектов.
   - Где: `app-bot/src/main/kotlin/com/example/bot/Application.kt:151-380`, `app-bot/src/main/kotlin/com/example/bot/Application.kt:430-499`.

2. **[C2] Reflection-based автопоиск Koin-модулей (`loadKoinModulesReflectively`) вместо явного composition.**
   - Impact: недетерминированный runtime wiring, сложность дебага, хрупкость при shading/classloader отличиях.
   - Где: `app-bot/src/main/kotlin/com/example/bot/Application.kt:181-188`, `app-bot/src/main/kotlin/com/example/bot/Application.kt:520-594`.

3. **[C3] Сильное смешение слоёв в `BookingWebAppRoutes`: HTTP/HTML/JS + Exposed table mappings + Telegram side-effects + крипто-утилиты.**
   - Impact: нарушение SRP/Clean Architecture, крайне сложная тестируемость и сопровождение.
   - Где: `app-bot/src/main/kotlin/com/example/bot/web/BookingWebAppRoutes.kt:345`, `app-bot/src/main/kotlin/com/example/bot/web/BookingWebAppRoutes.kt:576-677`, `app-bot/src/main/kotlin/com/example/bot/web/BookingWebAppRoutes.kt:679-704`, `app-bot/src/main/kotlin/com/example/bot/web/BookingWebAppRoutes.kt:708-715`.

4. **[C4] Legacy endpoint использует trust-by-header (`X-TG-User-Id`) и не опирается на Mini App HMAC guard.**
   - Impact: риск spoofing в обход централизованной authn модели.
   - Где: `app-bot/src/main/kotlin/com/example/bot/web/BookingWebAppRoutes.kt:432-437`.

5. **[C5] Core-security слой зависит от data-слоя (`com.example.bot.data.*`) и Ktor одновременно.**
   - Impact: размытые границы модулей, ухудшение переиспользования и тестируемости security-ядра.
   - Где: `core-security/build.gradle.kts:21-24`, `core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt:7-10`, `core-security/src/main/kotlin/com/example/bot/security/webhook/WebhookSecurityPlugin.kt:4-7`.

6. **[C6] Fail-open регистрация критичных маршрутов при неактивном RBAC.**
   - Impact: архитектурно небезопасный default-путь (coupling на env-конфиг вместо fail-closed policy).
   - Где: `app-bot/src/main/kotlin/com/example/bot/routes/OutboxAdminRoutes.kt:114-117`, `app-bot/src/main/kotlin/com/example/bot/routes/OutboxAdminRoutes.kt:314-318`, `app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt:76-77`, `app-bot/src/main/kotlin/com/example/bot/routes/PaymentsCancelRefundRoutes.kt:103-114`.

7. **[C7] Runtime webhook path использует облегчённый route, а hardened plugin остаётся отдельным контуром.**
   - Impact: дублирование ingress-логики, разные security guarantees на похожих путях.
   - Где: `app-bot/src/main/kotlin/com/example/bot/Application.kt:473-476`, `app-bot/src/main/kotlin/com/example/bot/routes/TelegramWebhookRoutes.kt:17-46`, `app-bot/src/main/kotlin/com/example/bot/webhook/WebhookRoutes.kt:28-39`.

8. **[C8] Глухие `catch (_: Throwable)` в бизнес-потоках и интеграциях.**
   - Impact: потеря причинно-следственной диагностики, скрытие ошибок контрактов.
   - Где: `app-bot/src/main/kotlin/com/example/bot/web/BookingWebAppRoutes.kt:503-505`, `app-bot/src/main/kotlin/com/example/bot/web/BookingWebAppRoutes.kt:692-692`, `app-bot/src/main/kotlin/com/example/bot/telegram/InvitationTelegramHandler.kt:31-31`, `app-bot/src/main/kotlin/com/example/bot/telegram/SupportTelegramHandler.kt:66-66`.

9. **[C9] В `StatusPages` ловится `Throwable` для `/api/*` без явного rethrow `CancellationException`.**
   - Impact: риск некорректного handling отмены корутин и ложных 500 в edge-сценариях.
   - Где: `app-bot/src/main/kotlin/com/example/bot/plugins/JsonErrorPages.kt:88-93`.

10. **[C10] Доменные/дата-модели держат Telegram-специфичные поля (`telegramUserId`, `telegramFileId`) как first-class атрибуты.**
    - Impact: частичная протечка adapter-контекста в core модели.
    - Где: `core-domain/src/main/kotlin/com/example/bot/club/GuestListServiceModels.kt:67`, `core-domain/src/main/kotlin/com/example/bot/club/InvitationServiceModels.kt:89`, `core-domain/src/main/kotlin/com/example/bot/music/Models.kt:37`, `core-domain/src/main/kotlin/com/example/bot/promoter/admin/PromoterAdminModels.kt:7`.

11. **[C11] N+1 паттерн в `BookingHoldRepository.toBookingHold()` (дочитывание `TablesTable` на каждый hold).**
    - Impact: деградация при списках hold и лишняя DB нагрузка.
    - Где: `core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingRepository.kt:627-640`.

12. **[C12] Host search сканирует все guest lists клуба и фильтрует по event в памяти.**
    - Impact: неэффективность hot-path поиска на входе при росте данных.
    - Где: `app-bot/src/main/kotlin/com/example/bot/host/HostSearchService.kt:47-50`.

13. **[C13] `DefaultAvailabilityService` игнорирует TZ клуба и фиксирует `UTC`, `arrivalByUtc=start`.**
    - Impact: рассинхрон доменной логики календаря/доступности и API выдачи.
    - Где: `core-data/src/main/kotlin/com/example/bot/availability/DefaultAvailabilityService.kt:54-66`.

14. **[C14] Разный стиль API-ошибок: часть маршрутов отдаёт plain string вместо унифицированного `ErrorCodes` JSON.**
    - Impact: UX/API консистентность ниже, сложнее клиентская обработка.
    - Где: `app-bot/src/main/kotlin/com/example/bot/routes/AvailabilityApiRoutes.kt:44`, `app-bot/src/main/kotlin/com/example/bot/routes/AvailabilityApiRoutes.kt:52`, `app-bot/src/main/kotlin/com/example/bot/routes/AvailabilityApiRoutes.kt:61`, `app-bot/src/main/kotlin/com/example/bot/routes/AvailabilityApiRoutes.kt:70`.

15. **[C15] Кастомный plugin расположен в `io.ktor.*` namespace.**
    - Impact: namespace shadowing, путаница с upstream Ktor APIs и IDE navigation.
    - Где: `app-bot/src/main/kotlin/io/ktor/server/plugins/requesttimeout/RequestTimeout.kt:1-2`.

16. **[C16] Сетевой вызов Telegram через `openStream()` внутри route-файла (`notifyHq`), без явного управляемого HTTP-клиента/политик retry.**
    - Impact: операционный риск, сложность observability и тестирования.
    - Где: `app-bot/src/main/kotlin/com/example/bot/web/BookingWebAppRoutes.kt:679-692`.

17. **[C17] Дублирование data-access сущностей: часть таблиц описана в core-data, часть прямо в app-bot legacy route.**
    - Impact: риск schema drift и двойной точки правды.
    - Где: `core-data/src/main/kotlin/com/example/bot/data/booking/BookingTables.kt:16-78`, `app-bot/src/main/kotlin/com/example/bot/web/BookingWebAppRoutes.kt:576-615`.

18. **[C18] `runBlocking` в startup path для seed-операций.**
    - Impact: блокирующий старт и потенциальный startup latency spike.
    - Где: `app-bot/src/main/kotlin/com/example/bot/Application.kt:196-200`.

19. **[C19] В репозитории есть fallback/демо токен в лимитах (`000000:DEV`).**
    - Impact: повышенная вероятность случайного использования fallback в непредусмотренной среде.
    - Где: `core-domain/src/main/kotlin/com/example/bot/config/BotLimits.kt:23-29`.

20. **[C20] Архитектурные качества не закреплены автоматическими guardrails (dependency rules/architecture tests).**
    - Impact: медленная эрозия модульных границ по мере роста кода.
    - Где (косвенно): `settings.gradle.kts:37-45`, `app-bot/build.gradle.kts:75-80`, `core-security/build.gradle.kts:21-24`.

---

## 4) Kotlin/Coroutines/Ktor/DB — итоги senior review

### Kotlin / Coroutines

Плюсы:
- В data-слое системно используется `newSuspendedTransaction(..., Dispatchers.IO)` и retry-обёртка (`withRetriedTx`).
- Есть timeout-guard для HTTP запросов.

Риски:
- Часть `catch(Throwable)` не дифференцирует `CancellationException`.
- Много `runCatching { ... }.getOrNull()` в критичных местах скрывают причины ошибок.

Ссылки: `core-data/src/main/kotlin/com/example/bot/data/db/DbTransactions.kt:167-196`, `app-bot/src/main/kotlin/com/example/bot/plugins/RequestGuardsPlugin.kt:20-28`, `app-bot/src/main/kotlin/com/example/bot/plugins/JsonErrorPages.kt:88-93`.

### Ktor

Плюсы:
- Наличие централизованных плагинов (`cors`, `security headers`, `request guards`, `status pages`).
- Хорошая базовая инфраструктура no-store/error mapping для `/api/*`.

Риски:
- Перегруженный composition root.
- Несогласованный формат ошибок между route-группами.

Ссылки: `app-bot/src/main/kotlin/com/example/bot/Application.kt:151-207`, `app-bot/src/main/kotlin/com/example/bot/plugins/JsonErrorPages.kt:21-93`, `app-bot/src/main/kotlin/com/example/bot/routes/AvailabilityApiRoutes.kt:39-74`.

### DB / Exposed / Flyway

Плюсы:
- Миграции централизованы (`MigrationRunner`, `MigrationsPlugin`), validate/migrate режимы и fail-fast логика присутствуют.
- Ключевые уникальные ограничения для booking/checkin/payment actions реализованы.

Риски:
- Hot-path performance debt (N+1, in-memory filtering по клубным спискам).
- Нюансы консистентности HOLD при высокой конкуренции остаются app-level.

Ссылки: `app-bot/src/main/kotlin/com/example/bot/plugins/MigrationsPlugin.kt:25-83`, `core-data/src/main/kotlin/com/example/bot/data/db/MigrationRunner.kt:29-137`, `core-data/src/main/resources/db/migration/postgresql/V7__booking_core.sql:24-31`.

### Тестируемость

Плюсы:
- Широкий тестовый набор (183 тестовых файла), есть IT/Testcontainers, есть конкуррентные smoke/E2E кейсы.

Риски:
- Для архитектурных инвариантов нет отдельного автоматического слоя тестов/линтов.

Ссылки: `app-bot/src/test/kotlin/com/example/bot/routes/BookingA3RoutesTest.kt:254`, `app-bot/src/test/kotlin/com/example/bot/routes/EntryManagerCheckInSmokeTest.kt:126`, `core-data/src/test/kotlin/com/example/bot/data/booking/core/BookingRepositoryIT.kt:24`.

---

## 5) Топ-10 “быстрых побед”

1. Явно разделить `Application.module()` на feature-bootstrap функции (routing/security/ops/payments/music) по 30–60 строк.
2. Убрать reflection discovery Koin-модулей, перейти на явный `modules(listOf(...))`.
3. Закрыть legacy `BookingWebAppRoutes` в отдельный deprecated adapter package с feature-flag и планом снятия.
4. Унифицировать error responses: на всех `/api/*` только `ErrorCodes` JSON.
5. В `JsonErrorPages` сделать явный `if (cause is CancellationException) throw cause`.
6. Вынести `notifyHq` на общий Telegram client + retry/timeout policy.
7. Исправить N+1 в `toBookingHold()` (join/батч-подгрузка table metadata).
8. Для host search добавить repository-метод с фильтром `clubId + eventId` на SQL стороне.
9. Переименовать custom timeout plugin в `com.example.bot.plugins.RequestTimeoutPlugin` (без `io.ktor.*`).
10. Добавить lightweight architecture checks (dependency guard task): запрет `app-bot -> core-data internals` вне адаптеров, запрет `core-*` импорта transport-specific DTO.

---

## 6) Рекомендации по рефакторингу (без внедрения)

### 6.1 Целевая структура слоёв

- **core-domain**: entity/use-case/policy/ports only (без Telegram/HTTP/storage типов).
- **core-data**: реализации портов + Exposed/Flyway + tx/persistence concerns.
- **core-security**: authn/authz policy + interfaces на user/audit repositories через порты domain/security, а не `data.*` imports.
- **app-bot**: transport adapters (Ktor/Telegram), composition root, wiring.

### 6.2 План декомпозиции `Application.module()`

1. `bootstrapPlatformPlugins()`
2. `bootstrapPersistence()`
3. `bootstrapSecurity()`
4. `bootstrapFeatureRoutes()`
5. `bootstrapBackgroundAndLifecycle()`

С обязательным контрактом: в каждом bootstrap максимум 5–8 прямых `inject`.

### 6.3 Coroutines/ошибки

- Стандарт policy: `catch (CancellationException) { throw }` перед `catch(Throwable)`.
- Убрать «глухие» catch и `getOrNull()` там, где нужна диагностика.
- Для интеграций ввести typed result (`sealed class`) + structured error context.

### 6.4 Performance

- Пройти hot paths с query plan:
  - `HostSearchService.search`;
  - `BookingHoldRepository.toBookingHold`;
  - `DefaultAvailabilityService.listOpenNights`.
- Ввести perf-regression smoke (p95/p99) в `tools:perf` для поиска/доступности.

### 6.5 UX consistency

- Один стиль API ошибок + единый словарь `ErrorCodes` на всех route-группах.
- Убрать остаточные plain-text ответы и legacy string errors.

---

## 7) Итог senior review

Проект production-ориентированный и зрелый по инфраструктурным практикам (миграции, retry, тесты, модульность), но требует **архитектурного выпрямления**: уменьшить связность composition root, убрать legacy-смешение слоёв, унифицировать error/adapter-подходы и закрепить границы модулей автоматическими guardrails.
