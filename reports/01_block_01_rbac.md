# Блок 1 (RBAC) — аудит соответствия

## Что реализовано (с ссылками path:lines)

1. **Базовая RBAC-модель ролей и scope в БД**
   - Роли фиксированы в enum: `OWNER`, `GLOBAL_ADMIN`, `HEAD_MANAGER`, `CLUB_ADMIN`, `MANAGER`, `ENTRY_MANAGER`, `PROMOTER`, `GUEST` (`core-data/src/main/kotlin/com/example/bot/data/security/Role.kt:6-15`).
   - Таблица `user_roles` хранит `scope_type` (`GLOBAL`/`CLUB`) и `scope_club_id` с CHECK-ограничением консистентности (`core-data/src/main/resources/db/migration/postgresql/V1__init.sql:122-130`).
   - `UserRoleRepository` и реализация читают роли + список клубов пользователя (`core-data/src/main/kotlin/com/example/bot/data/security/UserRoleRepository.kt:6-11`, `core-data/src/main/kotlin/com/example/bot/data/security/ExposedUserRepositories.kt:45-66`).

2. **HTTP RBAC-плагин и DSL-гварды**
   - RBAC централизованно ставится в `configureSecurity()` через `install(RbacPlugin)` с `UserRepository`, `UserRoleRepository`, `AuditLogRepository` (`app-bot/src/main/kotlin/com/example/bot/plugins/SecurityPlugins.kt:26-47`).
   - DSL `authorize(...)` проверяет наличие роли; `clubScoped(...)` проверяет scope клуба (`core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt:230-251`).
   - `clubScoped(Own)` пропускает глобальные роли (`OWNER/GLOBAL_ADMIN/HEAD_MANAGER`) или совпадение `clubId` в scope (`core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt:143-153`).

3. **Аудит RBAC-решений на уровне HTTP**
   - При `401/403` и успешных проходах фиксируется `HTTP_ACCESS` с причиной и результатом (`access_granted/access_denied`) (`core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt:317-421`).
   - Для идемпотентных запросов формируется детерминированный fingerprint от `Idempotency-Key` (`core-security/src/main/kotlin/com/example/bot/security/rbac/RbacPlugin.kt:447-459`).

4. **RBAC и club-scope на админских маршрутах**
   - Админ-финансы смены: `authorize(OWNER, GLOBAL_ADMIN, HEAD_MANAGER, CLUB_ADMIN)` + проверка доступа к клубу (`app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceShiftRoutes.kt:204-212`, `:267-272`).
   - Админ-операции по столам: `authorize(OWNER, GLOBAL_ADMIN, HEAD_MANAGER, CLUB_ADMIN, MANAGER)` + контроль клуба (`app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt:135-141`).
   - Промо-админка и квоты: role-check + `canAccessClub` (`app-bot/src/main/kotlin/com/example/bot/routes/PromoterAdminRoutes.kt:73-79`, `app-bot/src/main/kotlin/com/example/bot/routes/PromoterQuotasAdminRoutes.kt:70-77`).

5. **Опасные операции частично требуют reason + audit**
   - Коррекция депозита стола требует `reason` (валидация обязательности/длины) и пишет audit-событие с `reason` (`app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt:456-464`, `:579-589`, `:483-495`; `core-domain/src/main/kotlin/com/example/bot/audit/AuditLogger.kt:223-267`).
   - Закрытие финансовой смены логируется в аудит (`app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceShiftRoutes.kt:305-314`; `core-domain/src/main/kotlin/com/example/bot/audit/AuditLogger.kt:355-415`).

---

## Матрица “роль → действия → ограничения → где в коде”

| Роль | Действия (примеры) | Ограничения | Где в коде |
|---|---|---|---|
| OWNER | Полный админ-доступ: финансы, аналитика, столы, клубы, промо, outbox | Глобальный доступ (через `authorize` и `canAccessClub`/`isAdminClubAllowed`) | `AdminFinanceShiftRoutes.kt:204-212`, `AdminTableOpsRoutes.kt:135-141`, `AdminClubsRoutes.kt:68-76`, `OutboxAdminRoutes.kt:315` |
| GLOBAL_ADMIN | Аналогично OWNER | Глобальный доступ | `AdminRbac.kt:7-15`, `RbacHelpers.kt:5-10` |
| HEAD_MANAGER | Аналитика/финансы/часть админки/host/checkin | В ряде мест глобальный (через `canAccessClub`), в ряде — только clubIds (через `isAdminClubAllowed`) | `PromoterQuotasAdminRoutes.kt:70-77`, `AdminFinanceShiftRoutes.kt:204-212` + `AdminRbac.kt:7-10` |
| CLUB_ADMIN | Админка клуба (клубные ресурсы, финансы, halls/tables, промо) | Доступ к своему клубу | `AdminHallsRoutes.kt:71`+, `AdminTablesRoutes.kt:113`+, `PromoterAdminRoutes.kt:73-99` |
| MANAGER | Операции столов, check-in/host, ряд guest list/booking действий | Обычно только клуб из scope | `AdminTableOpsRoutes.kt:135`, `HostCheckinRoutes.kt:114-117` |
| ENTRY_MANAGER (Host/Вход) | Host/check-in/checklist/вход | Клубный scope | `HostEntranceRoutes.kt:36`, `HostChecklistRoutes.kt:52`, `CheckinRoutes.kt:82` |
| PROMOTER | Guest-list/promoter потоки, часть booking/payment действий | Обычно `clubScoped(Own)` или ручной `canAccessClub` | `PromoterGuestListRoutes.kt:292-296`, `SecuredBookingRoutes.kt:28`, `PaymentsCancelRefundRoutes.kt:87-91` |
| GUEST | Гостевые действия booking/payment/music likes и т.д. | Аутентификация mini-app + роль на конкретных ветках | `SecuredRoutes.kt:52-54`, `SecuredBookingRoutes.kt:28`, `MusicLikesRoutes.kt:68` |

> Примечание: в проекте есть отдельный legacy enum `Role { USER, ADMIN }`, который не участвует в основном RBAC (`core-security/src/main/kotlin/com/example/bot/security/Security.kt:11-23`).

---

## Что частично (что не хватает)

1. **Неполное совпадение с требуемым списком ролей Блока 1**
   - В текущем enum/roles-table нет ролей: `Главный админ` (как отдельная от `GLOBAL_ADMIN` трактовка ок, но неявно), `Менеджер зала` (возможный аналог `ENTRY_MANAGER`/`MANAGER` не выделен), `Админ клуба` есть, **`Финансовый менеджер` отсутствует**, **`DJ` отсутствует**.
   - Подтверждение: `Role.kt:6-15`, `V1__init.sql:117-119`.

2. **Опасные операции не везде требуют “причину”**
   - Закрытие финансовой смены аудируется, но reason/justification не запрашивается в API контракте закрытия (`AdminFinanceShiftRoutes.kt:267-314`), и в аудите `shiftReportClosed` причины нет (`AuditLogger.kt:355-415`).

3. **Непоследовательность глобального scope для HEAD_MANAGER**
   - `canAccessClub` считает `HEAD_MANAGER` глобальной ролью (`RbacHelpers.kt:5-10`),
   - но `isAdminClubAllowed` — только `OWNER/GLOBAL_ADMIN` (`AdminRbac.kt:7-10`).
   - В результате однотипные админские действия могут вести себя по-разному в зависимости от helper-а.

4. **Назначение ролей как бизнес-операция не экспонировано**
   - Есть audit-метод `roleGranted(...)` (`AuditLogger.kt:417-443`), но не найдено явного админ-endpoint/сервиса назначения ролей с проверкой “кто/кому/почему”.

---

## Чего нет вообще

1. **Отдельной роли `FINANCIAL_MANAGER` (финансовый менеджер) в RBAC-модели.**
2. **Отдельной роли `DJ` в RBAC-модели.**
3. **Унифицированного требования “опасная операция => обязательная причина + audit” на уровне общего middleware/политики.** Сейчас это реализовано точечно (например, update депозита), но не системно.
4. **Явной административной процедуры назначения/снятия ролей с reason + audit + ограничениями по scope.**

---

## Потенциально неавторизованные места (эндпоинт/хэндлер без проверки роли)

1. **`/api/admin/outbox` может регистрироваться без `authorize(...)`**
   - Если `OUTBOX_ADMIN_ENABLED=true`, но `RBAC_ENABLED=false` (или RBAC-плагин недоступен), ветка регистрируется через `register()` без role-check (`OutboxAdminRoutes.kt:108-116`, `:314-318`).
   - При этом в данной ветке не используется `withMiniAppAuth`.

2. **Публичный compat endpoint checkin без role-check**
   - `/api/checkin/qr` не защищён RBAC, но всегда возвращает `403` (явный “заглушечный deny”) (`CheckinCompatRoutes.kt:11-16`).

---

## Риски безопасности (P0/P1/P2)

- **P0: `/api/admin/outbox` без role-check при определённой конфигурации.**
  - Причина: conditional `if (rbacAvailable) authorize(...) else register()` позволяет открыть админ-функции replay/list без RBAC (`OutboxAdminRoutes.kt:314-318`).
  - Потенциальный эффект: несанкционированный replay outbox, утечка служебной информации.

- **P1: отсутствие обязательной причины для части опасных операций (закрытие смены).**
  - Причина: endpoint закрытия смены не принимает/не валидирует reason (`AdminFinanceShiftRoutes.kt:267-314`), audit не хранит reason (`AuditLogger.kt:355-415`).
  - Эффект: слабая трассируемость и сложность расследований.

- **P1: расхождение семантики глобального доступа `HEAD_MANAGER`.**
  - Причина: разные helper-правила (`RbacHelpers.kt:5-10` vs `AdminRbac.kt:7-10`).
  - Эффект: непредсказуемые deny/allow в разных ручках, риск ошибочной эскалации или блокировки.

- **P2: дрейф моделей ролей (legacy `Role USER/ADMIN`).**
  - Причина: параллельный enum в `core-security/security/Security.kt:11-14`.
  - Эффект: путаница при сопровождении и риск неправильного использования в новом коде.

---

## Рекомендованные правки (без внедрения)

1. **Жёстко закрыть `/api/admin/outbox` независимо от env-флагов.**
   - Убрать fallback `register()` без `authorize`.
   - Дополнительно обернуть ветку `withMiniAppAuth`.

2. **Ввести единую политику для “опасных операций”.**
   - Стандартизировать контракт: `reason` обязателен для закрытия смены, финансовых коррекций, массовых административных действий.
   - Вынести проверку reason в helper/интерсептор; писать reason в audit metadata.

3. **Унифицировать helper-логику club scope.**
   - Привести `isAdminClubAllowed` к общей логике `canAccessClub` (или наоборот), зафиксировать единый список “global roles”.

4. **Доработать ролевую модель до требований Блока 1.**
   - Явно добавить роли `FINANCIAL_MANAGER` и `DJ` (или зафиксировать документированную маппинг-стратегию на существующие роли).
   - Добавить тесты совместимости role matrix ↔ endpoints.

5. **Добавить безопасный контур управления ролями.**
   - Отдельные админ-эндпоинты role assign/revoke c `authorize`, scope validation, mandatory reason и аудитом через `roleGranted`/`roleRevoked`.

