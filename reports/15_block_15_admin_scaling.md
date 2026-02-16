# Блок 15 (Админ‑настройки и масштабирование) — аудит соответствия

## 1) Можно ли реально добавить новый клуб через админку без изменения кода?

Короткий ответ: **частично**.

## Что реально можно сделать уже сейчас через админ‑контур

1. Создать/обновить/деактивировать клуб через `/api/admin/clubs`.
   - Доступные поля в API: `name`, `city`, `isActive`.
   - В репозитории при создании клуба автоматически выставляются дефолты (`timezone=Europe/Moscow`, пустые `genres/tags`, `description=null`, topic/chat поля `null`).

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/routes/AdminClubsRoutes.kt:34-45`
- `app-bot/src/main/kotlin/com/example/bot/routes/AdminClubsRoutes.kt:81-104`
- `core-data/src/main/kotlin/com/example/bot/data/admin/AdminClubsDbRepository.kt:41-63`

2. Добавить зал(ы) клуба, загрузить геометрию, переключать active hall.
   - Создание зала с `geometryJson`, валидацией и защитой от конфликта имени.
   - Есть явный `make-active`.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/routes/AdminHallsRoutes.kt:35-39`
- `app-bot/src/main/kotlin/com/example/bot/routes/AdminHallsRoutes.kt:114-140`
- `app-bot/src/main/kotlin/com/example/bot/routes/AdminHallsRoutes.kt:226-246`

3. Добавить столы, привязать их к залу/зоне, управлять координатами и номером стола.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/routes/AdminTablesRoutes.kt:114-127`
- `app-bot/src/main/kotlin/com/example/bot/routes/AdminTablesRoutes.kt:129-191`
- `core-data/src/main/kotlin/com/example/bot/data/layout/LayoutDbRepository.kt:352-389`

## Почему это только «частично безкодовое»

- Модель/контракт `AdminClub` ограничен (нет полного профиля клуба из требования Блока 15: адрес, описание, модули, коммуникационные настройки, роли, правила и т.п. в едином клубном конфиге).
- `isActive` фактически работает как простый флаг активности, но нет отдельного workflow «черновик → публикация» с валидацией готовности.
- Не найден единый мастер‑флоу «создай клуб и пошагово заполни все подсистемы».

Ссылки:
- `core-domain/src/main/kotlin/com/example/bot/admin/AdminRepositories.kt:5-24`
- `app-bot/src/main/kotlin/com/example/bot/routes/AdminClubsRoutes.kt:47-55`
- `core-data/src/main/kotlin/com/example/bot/data/admin/AdminClubsDbRepository.kt:94-101`

---

## 2) Как загружается схема и как она вяжется к столам?

## 2.1 Модель данных

- Залы/зоны/столы:
  - `halls` (геометрия, fingerprint, ревизия layout),
  - `hall_zones` (зоны зала),
  - `hall_tables` (столы: `hall_id`, `zone_id`, `table_number`, `x/y`, и т.д.).
- Отдельно хранится план‑изображение зала в `hall_plans` (`bytes`, `content_type`, `sha256`).

Ссылки:
- `core-data/src/main/resources/db/migration/postgresql/V024__core_data_layout.sql:15-63`
- `core-data/src/main/resources/db/migration/postgresql/V026__hall_plans.sql:1-9`
- `core-data/src/main/resources/db/migration/postgresql/V025__hall_tables_active_number_unique.sql:1-3`

## 2.2 Поток загрузки

1. Админ создаёт/редактирует зал с `geometryJson`.
   - Из `geometryJson` парсятся `zones` (`id`, `name`), дубли `zone.id` запрещены.
   - При изменении геометрии пересчитывается `geometryFingerprint`, растёт `layoutRevision`, зоны upsert’ятся.

Ссылки:
- `core-data/src/main/kotlin/com/example/bot/data/admin/AdminHallsDbRepository.kt:69-99`
- `core-data/src/main/kotlin/com/example/bot/data/admin/AdminHallsDbRepository.kt:115-133`
- `core-data/src/main/kotlin/com/example/bot/data/admin/AdminHallsDbRepository.kt:219-235`
- `core-data/src/main/kotlin/com/example/bot/data/admin/AdminHallsDbRepository.kt:245-264`

2. Админ загружает картинку плана зала (`PUT /api/admin/halls/{hallId}/plan`, multipart).
   - Ограничения: только `image/png` и `image/jpeg`, лимит 5 MB, хэш SHA‑256.
   - План сохраняется через `HallPlansRepository.upsertPlan(...)`.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/routes/HallPlanRoutes.kt:38-42`
- `app-bot/src/main/kotlin/com/example/bot/routes/HallPlanRoutes.kt:65-97`
- `app-bot/src/main/kotlin/com/example/bot/routes/HallPlanRoutes.kt:197-251`

3. Админ создаёт столы для конкретного `hallId`, указывая `zone`, `x/y`, `tableNumber`.
   - Столы физически лежат в `hall_tables` и связаны с залом/зоной.
   - При изменениях bump’ается `layoutRevision`.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/routes/AdminTablesRoutes.kt:129-191`
- `core-data/src/main/kotlin/com/example/bot/data/layout/LayoutDbRepository.kt:364-377`
- `core-data/src/main/kotlin/com/example/bot/data/layout/LayoutDbRepository.kt:459-468`

4. Клиент получает план по `GET /api/clubs/{clubId}/halls/{hallId}/plan`.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/routes/HallPlanRoutes.kt:125-137`
- `app-bot/src/main/kotlin/com/example/bot/routes/HallPlanRoutes.kt:166-179`

Итого: связка «геометрия/зоны/столы/план» реализована и рабочая, но это только часть требований Блока 15.

---

## 3) Что надо доделать для полноценной «безкодовости»

## Missing (нет как целостной подсистемы)

1. **Полный no-code профиль клуба в одном контуре**:
   - профиль (address/description/media/branding),
   - календарные правила (`club_hours/holidays/exceptions`) как админ‑CRUD,
   - правила/модули/коммуникации/роли в едином onboarding.

Факт: в `admin/clubs` сейчас только `name/city/isActive`.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/routes/AdminClubsRoutes.kt:34-45`
- `core-domain/src/main/kotlin/com/example/bot/admin/AdminRepositories.kt:14-24`

2. **Шаблоны/клонирование клуба целиком** (layout + rules + finance + comms + roles).

Факт: есть шаблоны в отдельных доменах (например, финансы), но не найден сценарий clone/bootstrap клуба «одной кнопкой».

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceTemplateRoutes.kt:83-90`
- `app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceTemplateRoutes.kt:113-158`

3. **Системный аудит изменений клубной конфигурации**.

Факт: `AuditLogger` применяется в table-ops/finance, но в `adminClubs/adminHalls/adminTables/adminHallPlan/adminOpsChats` не передаётся и не используется как единый аудит-конвейер (только обычные `logger.info`).

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/Application.kt:380-397`
- `app-bot/src/main/kotlin/com/example/bot/Application.kt:375-379`
- `core-domain/src/main/kotlin/com/example/bot/audit/AuditLogger.kt:12-15`
- `app-bot/src/main/kotlin/com/example/bot/routes/AdminClubsRoutes.kt:102-103`

## Partial (частично есть)

1. **Публикация/черновик клуба** — только через `isActive`.
   - Есть включение/выключение клуба.
   - Нет расширенного статуса жизненного цикла (`DRAFT/READY/PUBLISHED/ARCHIVED`) и pre-publish проверок полноты конфигурации.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/routes/AdminClubsRoutes.kt:37-45`
- `core-data/src/main/kotlin/com/example/bot/data/admin/AdminClubsDbRepository.kt:97-100`

2. **Коммуникационные настройки** — есть отдельный admin route для ops chat config, но это фрагмент, а не единый масштабируемый “club setup”.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/routes/AdminOpsChatsRoutes.kt:65-80`
- `app-bot/src/main/kotlin/com/example/bot/routes/AdminOpsChatsRoutes.kt:82-107`

## Риски

- **P1**: при быстром масштабировании сетью клубов высок риск ручных ошибок из-за фрагментированной настройки (несколько несвязанных admin‑контуров вместо единого мастера).
- **P1**: неполный audit trail по изменению конфигурации клуба усложняет расследование инцидентов/регрессий.
- **P2**: отсутствие полноценного шаблонизатора/клонирования повышает time-to-launch каждого нового клуба.

---

## 4) Краткий итог соответствия Блоку 15

- Добавление клуба **без правки кода возможно только в базовом виде** (минимум полей + ручная настройка залов/столов/части смежных модулей).
- Связка «схема зала ↔ зоны ↔ столы ↔ план-изображение» реализована технически корректно.
- Для полного соответствия требованию «безкодовости и масштабирования» не хватает:
  1) единого конфигурационного мастера,
  2) clone/template на уровне клуба,
  3) обязательного структурированного аудита конфиг-изменений,
  4) расширенной модели publish/draft жизненного цикла.
