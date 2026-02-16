# Блок 8 — Финансовая смена (аудит соответствия)

## 1) Экран/команды закрытия смены (фактический flow)

### Реализованный API-контур

1. **Открытие/получение смены (авто-create DRAFT)**
   - `GET /api/admin/clubs/{clubId}/nights/{nightStartUtc}/finance/shift`
   - Внутри вызывается `shiftReportRepository.getOrCreateDraft(...)`, т.е. черновик смены создаётся лениво автоматически при первом запросе. 
   - См. `app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceShiftRoutes.kt:206-221`, `core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportRepositories.kt:424-476`.

2. **Обновление черновика смены**
   - `PUT /api/admin/clubs/{clubId}/finance/shift/{reportId}`.
   - Обновление возможно только если статус `DRAFT`; при `CLOSED` возвращается `invalid_state`.
   - См. `app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceShiftRoutes.kt:224-265`, `core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportRepositories.kt:478-559`.

3. **Закрытие смены**
   - `POST /api/admin/clubs/{clubId}/finance/shift/{reportId}/close`.
   - Перед закрытием: проверка состояния, расчёт расхождения с table deposits, требование комментария при большом mismatch, затем `shiftReportRepository.close(...)` и аудит `auditLogger.shiftReportClosed(...)`.
   - См. `app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceShiftRoutes.kt:267-324`.

4. **Админ-шаблон финансовых сущностей (браслеты/группы/статьи)**
   - Отдельный контур `.../finance/template` для настройки типов браслетов и статей выручки.
   - См. `app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceTemplateRoutes.kt:73-351`.

---

## 2) Какие поля/таблицы/валидации есть

### Таблицы и поля

1. **Смена** (`shift_reports`):
   - `status (DRAFT/CLOSED)`, `people_women`, `people_men`, `people_rejected`, `comment`, `closed_at`, `closed_by`, `club_id`, `night_start_utc`.
   - См. `core-data/src/main/resources/db/migration/postgresql/V039__shift_reports.sql:58-72`, `core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportTables.kt:68-88`.

2. **Браслеты**:
   - справочник типов: `club_bracelet_types`;
   - значения в отчёте: `shift_report_bracelets(report_id, bracelet_type_id, count)`.
   - См. `V039__shift_reports.sql:7-21,80-88`, `ShiftReportTables.kt:14-29,90-100`.

3. **Выручка**:
   - группы: `club_revenue_groups`;
   - статьи: `club_revenue_articles` с флагами `include_in_total`, `show_separately`;
   - значения отчёта: `shift_report_revenue_entries`.
   - См. `V039__shift_reports.sql:23-56,90-104`, `ShiftReportTables.kt:31-66,102-119`.

4. **Сверка со столами**:
   - `DepositHints(sumDepositsForNight, allocationSummaryForNight)` берутся из `TableDepositRepository`.
   - См. `core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportRepositories.kt:636-645`.

### Валидации

1. **Негативные значения запрещены**:
   - в API-валидации (`people`, `bracelets.count`, `revenue.amountMinor`);
   - в репозитории (`validatePeople`, проверки по браслетам/выручке);
   - на уровне БД (`CHECK >= 0` в V040).
   - См. `AdminFinanceShiftRoutes.kt:475-503`, `ShiftReportRepositories.kt:482-493,607-621,647-649`, `V040__shift_reports_constraints.sql:3-16`.

2. **Консистентность справочников**:
   - проверяется существование bracelet type, revenue group/article, запрет дублей article/bracelet в payload.
   - См. `ShiftReportRepositories.kt:483-491,651-661,667-676,734-744`.

3. **Требование комментария при расхождении с депозитами столов**:
   - если `|sumDepositsForNight - sum(non-total showSeparately indicators)| > threshold` и комментарий пуст, закрытие запрещается.
   - См. `AdminFinanceShiftRoutes.kt:283-287,505-515`.

---

## 3) Возможны ли правки после закрытия (и кем)

### Что есть

1. **Через публичный API правки после `CLOSED` запрещены**:
   - route-блокирует `PUT /finance/shift/{reportId}` для закрытой смены;
   - repository `updateDraft` дополнительно блокирует апдейт закрытой записи (double-check).
   - См. `AdminFinanceShiftRoutes.kt:245-247`, `ShiftReportRepositories.kt:504-506`.

2. **Повторное закрытие запрещено**:
   - `close()` возвращает `false`, если уже `CLOSED`.
   - См. `ShiftReportRepositories.kt:604-606`.

### Что отсутствует относительно спеки

- В коде нет механизма «супер-роль + причина + аудит» для легального пост-фактум исправления закрытой смены (в спецтребовании это нужно).
- Текущее поведение: после `CLOSED` — фактически immutable в этом контуре для всех ролей.

---

## 4) Сопоставление с критичными требованиями Блока 8

| Требование | Статус | Наблюдение |
|---|---|---|
| Смена открывается автоматически | **Implemented** | `getOrCreateDraft` на `GET .../nights/{nightStartUtc}/finance/shift`. |
| Закрывает только финансовый менеджер клуба | **Partial/None** | Отдельной роли `FINANCE_MANAGER` нет в enum ролей; закрытие доступно `OWNER/GLOBAL_ADMIN/HEAD_MANAGER/CLUB_ADMIN`. |
| Ввод людей (Ж/М/отказы) | **Implemented** | Поля `peopleWomen/peopleMen/peopleRejected` есть в API/БД/валидации. |
| Браслеты: типы настраиваемые | **Implemented** | `club_bracelet_types` + template endpoints create/update/disable/reorder. |
| Выручка: статьи настраиваемые + флаг “в общую/не в общую” | **Implemented** | `club_revenue_articles.include_in_total` (+ `show_separately`), configurable через template routes. |
| Депозитные карты отдельной метрикой “не в общую” | **Partial** | Есть общий механизм non-total indicators и сверка с table deposits, но нет явной доменной сущности/правила именно для «депозитных карт». |
| Сверка со столами | **Implemented (partial policy)** | Есть `DepositHints` и mismatch-check перед close; но это общий контроль, без полной регламентной политики корректировок. |
| После закрытия смены данные заморожены | **Implemented** | Обновления закрытой смены блокируются route + repository. |

Подтверждающие ссылки:
- роли/доступ: `AdminFinanceShiftRoutes.kt:204-205`, `core-data/.../Role.kt:6-15`;
- автосоздание/закрытие: `AdminFinanceShiftRoutes.kt:206-221,267-324`;
- freeze: `AdminFinanceShiftRoutes.kt:245-247`, `ShiftReportRepositories.kt:504-506,604-606`.

---

## 5) Missing / Partial / None

### Missing / None

1. **Нет роли “Финансовый менеджер клуба” в RBAC-модели**:
   - enum `Role` не содержит отдельного финансового менеджера.
   - Это прямой разрыв с формулировкой Блока 8.

2. **Нет канала «исправление закрытой смены супер-ролью с причиной и аудитом»**:
   - по факту исправления после `CLOSED` не предусмотрены вообще.

### Partial

1. **Контроль депозитных карт “не в общую”**:
   - есть универсальные поля `include_in_total`/`show_separately` + mismatch-check,
   - но нет отдельного жёстко типизированного индикатора/статьи/валидационного правила именно для депозитных карт.

2. **Политика закрытия/причин для расхождений**:
   - комментарий обязателен только при превышении threshold,
   - но нет отдельного reason-кода, классификатора причин и специализированного процесса ревизии/аппрува.

---

## 6) Тесты (что покрыто)

### Есть

1. `AdminFinanceShiftRoutesTest`:
   - lazy create draft;
   - закрытие требует комментарий при большом mismatch;
   - запрет update/close для закрытой смены;
   - запрет close для роли `MANAGER`.
   - См. `app-bot/src/test/kotlin/com/example/bot/routes/AdminFinanceShiftRoutesTest.kt:65-163`.

2. `ShiftReportRepositoryTest`:
   - `getOrCreateDraft` идемпотентность;
   - `close` переводит в `CLOSED`, фиксирует `closed_at/closed_by`, повторное закрытие блокируется;
   - проверки валидности payload (unknown article, дубли и т.д.).
   - См. `core-data/src/test/kotlin/com/example/bot/data/finance/ShiftReportRepositoryTest.kt:40-120,227-340`.

### Пробелы

- Нет тестов на роль `FINANCE_MANAGER` (роли нет в коде).
- Нет тестов сценария «супер-исправление после закрытия с причиной» (функционал отсутствует).
- Нет тестов, гарантирующих выделение именно «депозитных карт» как отдельной не-total метрики по доменной политике.
