# Q5 — DB Schema & Data Integrity audit (Flyway, constraints, индексы, консистентность)

## Executive summary
- Flyway-конфигурация централизована, с вендорными локациями (`postgresql`/`h2`) и безопасным режимом `VALIDATE` для prod-like сред; pending миграции в validate-режиме блокируют старт приложения. Ссылки: `core-data/src/main/kotlin/com/example/bot/data/db/DbConfig.kt:52-75,81-99,166-194`, `core-data/src/main/kotlin/com/example/bot/data/db/MigrationRunner.kt:140-166`, `app-bot/src/main/kotlin/com/example/bot/plugins/MigrationsPlugin.kt:19-23,35-40`.
- Ключевые инварианты по активным бронированиям в PostgreSQL в основном закрыты: есть partial unique для активной брони на слот/стол и `idempotency_key` для bookings/holds/payments/payment_actions. Ссылки: `core-data/src/main/resources/db/migration/postgresql/V7__booking_core.sql:66-68`, `.../V1__init.sql:144,168`, `.../V4__payments.sql:20,22`, `.../V13__payments_actions.sql:11-12`.
- По HOLD есть важный пробел на уровне БД: уникальность «активного hold» специально оставлена на уровне приложения (в миграции это явно зафиксировано), что повышает риск гонок под высокой конкуренцией. Ссылки: `core-data/src/main/resources/db/migration/postgresql/V7__booking_core.sql:24-31`, `core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingRepository.kt:484-495`.
- Для check-in реализовано `UNIQUE(subject_type, subject_id)` и `club_visits UNIQUE(club_id, night_start_utc, user_id)`, что предотвращает дубль по субъекту и повторный учёт посещения в ночь; но нет явного БД-ограничения «ровно один ARRIVED на user/night» в таблице checkins. Ссылки: `core-data/src/main/resources/db/migration/postgresql/V017__guest_list_invites_checkins.sql:70-83`, `.../V034__operational_night_overrides_club_visits.sql:10-25`.
- Денежный контур смены частично расходится с концепцией append-only: кроме создания депозита есть `updateDeposit` (перезапись amount + allocations), хотя с обязательной причиной и аудитом в API. Ссылки: `core-data/src/main/kotlin/com/example/bot/data/booking/TableSessionDepositRepositories.kt:243-283`, `app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt:454-491`.
- Паритет PostgreSQL/H2 неполный: в PostgreSQL есть `V0__enable_uuid_extensions.sql`, `V014__waitlist.sql`, `V025__hall_tables_active_number_unique.sql`, которых нет в H2; это влияет на тестовую сопоставимость ограничений/индексов. Ссылки: `core-data/src/main/resources/db/migration/postgresql/V0__enable_uuid_extensions.sql:1`, `.../V014__waitlist.sql:1-18`, `.../V025__hall_tables_active_number_unique.sql:1-3`.

---

## A) Снимок Flyway migrations

### Где лежат
- PostgreSQL: `core-data/src/main/resources/db/migration/postgresql/`
- H2: `core-data/src/main/resources/db/migration/h2/`
- Common: `core-data/src/main/resources/db/migration/common/`

### Версии (кратко)
- Базовые: `V1__init`, `V2__seed`, `V3__notify_init`, `V4__payments`, `V5__music_init`, `V6__telegram_notify_endpoints` (common), `V7__booking_core`, `V8__booking_tx_repos`, `V9__club_indexes`, `V10__audit_log_nullable`, `V11__webhook_security`, `V12__promo_schema`, `V13__payments_actions`.
- Расширения: `V014..V042` (waitlist, guest-list/checkin polish, layouts/halls, promoter access, music assets/battles, audit core, visits, gamification, table sessions/deposits, shift reports, stories/segments).
- Дополнительно для PostgreSQL: `V0__enable_uuid_extensions`.

Проверено по дереву миграций: `core-data/src/main/resources/db/migration/...`.

### Runtime-стратегия Flyway
- По умолчанию выбирается вендорная папка (`db/migration/postgresql` или `db/migration/h2`). `core-data/src/main/kotlin/com/example/bot/data/db/DbConfig.kt:77-79,95-99,176-178`.
- `common` миграции выполняются только при явном включении в `FLYWAY_LOCATIONS`/override. `core-data/src/main/kotlin/com/example/bot/data/db/DbConfig.kt:100-103,166-194`.
- В prod-like окружениях эффективный режим принудительно `VALIDATE`. `core-data/src/main/kotlin/com/example/bot/data/db/DbConfig.kt:66-71,149-153`.

---

## B) Модель данных (ERD текстом)

### Ядро клуба и календаря
- `clubs` 1—N `club_hours`, `club_holidays`, `club_exceptions`, `events`, `tables`, `halls`. `core-data/src/main/resources/db/migration/postgresql/V1__init.sql:6-31,35-76,90-103`; `.../V024__core_data_layout.sql:15-27`.
- `events` хранит UTC-времена ночей (`start_at`,`end_at` TIMESTAMPTZ). `core-data/src/main/resources/db/migration/postgresql/V1__init.sql:62-75`.

### Залы/схемы/столы
- `halls` 1—N `hall_zones`, `hall_tables`; `halls` 1—1 `hall_plans`. `core-data/src/main/resources/db/migration/postgresql/V024__core_data_layout.sql:15-63`; `.../V026__hall_plans.sql:1-8`.

### Бронирование
- `booking_holds` (event+table+TTL+idem) → `bookings` (event+table+status+idem+qr).
- `bookings` → `payments` (N), `payment_actions` (N по booking, idem на action).
- `bookings`/`holds` используют slot window (`slot_start`,`slot_end`). `core-data/src/main/resources/db/migration/postgresql/V7__booking_core.sql:4-22,33-68`; `.../V4__payments.sql:13-25`; `.../V13__payments_actions.sql:1-15`.

### Guest list / check-in / визиты
- `guest_lists` 1—N `guest_list_entries` 1—N `invitations`.
- `checkins` фиксирует факт по subject (`BOOKING`/`GUEST_LIST_ENTRY`) с уникальностью subject.
- `club_visits` агрегирует факт посещения на `club+night+user` (unique).
`core-data/src/main/resources/db/migration/postgresql/V017__guest_list_invites_checkins.sql:54-83`; `.../V034__operational_night_overrides_club_visits.sql:10-25`.

### Финансы смены
- Шаблоны: `club_bracelet_types`, `club_revenue_groups`, `club_revenue_articles`.
- Факт смены: `shift_reports` + `shift_report_bracelets` + `shift_report_revenue_entries`.
- Операционка столов: `table_sessions` + `table_deposits` + `table_deposit_allocations`.
`core-data/src/main/resources/db/migration/postgresql/V039__shift_reports.sql:7-104`; `.../V036__table_sessions_deposits.sql:1-63`; `.../V037__table_sessions_deposits_constraints.sql:1-10`.

### Audit
- `audit_log` с fingerprint-уникальностью и индексами по actor/club/subject/time. `core-data/src/main/resources/db/migration/postgresql/V033__audit_log_core.sql:49-56`.

### Missing относительно целевого перечня
- Нет отдельной append-only таблицы **table_operations** (первичный депозит/доплаты/охрана как отдельные immutable операции); вместо этого есть mutable `table_deposits` + allocations. `core-data/src/main/resources/db/migration/postgresql/V036__table_sessions_deposits.sql:25-40`; `core-data/src/main/kotlin/com/example/bot/data/booking/TableSessionDepositRepositories.kt:243-283`.

---

## C) Концепция → таблица/constraint → статус

| Концепция | Реализация | Статус |
|---|---|---|
| Две активные брони на один стол/ночь запрещены | `uq_bookings_active_slot` (table_id+slot_start+slot_end WHERE status IN BOOKED,SEATED) | **OK** (`V7__booking_core.sql:66-68`) |
| Два HOLD одновременно (на слот/стол) | БД unique нет; только app-check + индексы | **PARTIAL** (`V7__booking_core.sql:24-31`, `BookingRepository.kt:484-495`) |
| HOLD idem | `booking_holds.idempotency_key UNIQUE` | **OK** (`V1__init.sql:144`) |
| CONFIRM idem | `bookings.idempotency_key UNIQUE` + проверка в service | **OK** (`V1__init.sql:168`, `BookingService.kt:174-183`) |
| Один check-in на субъект | `UNIQUE(subject_type, subject_id)` | **OK** (`V017__guest_list_invites_checkins.sql:82`) |
| Один check-in на user/night | `club_visits UNIQUE(club_id, night_start_utc, user_id)` | **OK/PARTIAL** (агрегат есть, но не constraint в checkins) (`V034__...sql:24`) |
| Booking status machine | CHECK + миграция статусов | **OK** (`V7__booking_core.sql:49-57`) |
| Check-in status machine | CHECK `ARRIVED/LATE/DENIED` + deny reason consistency | **OK** (`V017__...sql:78`, `V018__...sql:52-55`) |
| Holds TTL cleanup | `expires_at` + explicit cleanup/delete в repo | **PARTIAL** (без DB TTL job) (`V1__init.sql:143,147-150`, `BookingRepository.kt:564-569`) |
| Депозит как append-only операции | Есть `createDeposit`, но есть и `updateDeposit` | **MISSING/PARTIAL** (`TableSessionDepositRepositories.kt:189-241,243-283`) |
| Freeze после закрытия смены | updateDraft блокируется при CLOSED; close переводит DRAFT→CLOSED | **PARTIAL** (guardrails в app/repo, не DB-триггер) (`ShiftReportRepositories.kt:504-506,623-631`) |
| Браслеты/статьи выручки настраиваемы | Таблицы templates + include_in_total/show_separately | **OK** (`V039__shift_reports.sql:7-57,90-99`) |
| Депозитные карты «не в общую» | Модель через `include_in_total=false` есть, но отдельной жёсткой категории нет | **PARTIAL** (`V039__shift_reports.sql:45-47,97`) |
| TZ стратегия | club timezone + TIMESTAMPTZ для событий/ночей/операций | **OK** (`V1__init.sql:10,66-75`; `V036__...sql:4,35-38`) |

---

## D) Индексы и соответствие hot-запросам

### Что покрыто хорошо
- Booking:
  - `idx_bookings_event_status`, `idx_bookings_club_status`, `idx_bookings_promoter_status`. `core-data/src/main/resources/db/migration/postgresql/V1__init.sql:177-179`.
  - Активный слот-уникальный индекс. `.../V7__booking_core.sql:66-68`.
- Holds:
  - `idx_booking_holds_event_expires`, `idx_booking_holds_slot_active`. `.../V7__booking_core.sql:30-31`.
- Check-in/visits:
  - `idx_checkins_club_event`, `idx_checkins_event_occurred_at`; `idx_club_visits_club_night_start`, `idx_club_visits_club_event`. `.../V018__guest_list_schema_polish.sql:58-59`; `.../V034__...sql:27-35`.
- Shift:
  - `idx_shift_reports_club_status`, revenue/bracelet индексы. `.../V039__shift_reports.sql:77-104`.
- Table ops:
  - `idx_table_sessions_club_night_status`, `idx_table_deposits_club_night`, `idx_table_deposits_guest_club_night`. `.../V036__table_sessions_deposits.sql:19-53`.

### Узкие места / недостающие индексы
1. Для промо-аналитики по ночи полезен индекс `bookings(promoter_user_id, event_id, status)`; сейчас есть только `(promoter_user_id, status)`. `V1__init.sql:179`.
2. Для checkins нет индекса `(subject_type, subject_id)` кроме уникального ограничения; это ок для point-lookup, но для user/night аналитики нет явного user link в checkins — reliance на `club_visits`.
3. Для поиска по гостю в bookings (`lower(guest_name) like %...%`) нет функционального/trgm индекса (риски seq scan). `core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingRepository.kt:409-417`.

---

## E) Data integrity risks (P0/P1/P2)

### P0
1. **Нет DB-гарантии активного HOLD (дубликаты под гонками)**
   - Почему: partial unique по `expires_at > now()` невозможен (и удалён), контроль в приложении.
   - Где: `V7__booking_core.sql:24-31`, `BookingRepository.kt:484-495`.
   - Риск: при race/мульти-инстансах можно получить конкурирующие hold до cleanup.

2. **Депозит не append-only (возможна перезапись суммы)**
   - Почему: есть `updateDeposit` с заменой `amountMinor` и полной перезаписью allocations.
   - Где: `TableSessionDepositRepositories.kt:243-283`.
   - Риск: противоречит инварианту «только операции», повышает риск незаметной корректировки денег.

### P1
3. **Freeze смены реализован в коде, а не жёстко в БД**
   - Где: `ShiftReportRepositories.kt:504-506,623-631`.
   - Риск: обход через прямые SQL/update вне сервиса.

4. **Check-in “один ARRIVED на user/night” не выражен прямым constraint в checkins**
   - Где: `V017__...sql:70-83`, `V034__...sql:24`.
   - Риск: при ошибочной интеграции checkins-слоя и club_visits возможны расхождения журнала и агрегата.

5. **Flyway common-migration может быть пропущена при дефолтной vendor-only локации**
   - Где: `DbConfig.kt:176-178` vs `db/migration/common/V6__telegram_notify_endpoints.sql:1-35`.
   - Риск: разные окружения могут иметь разный набор DDL при разных `FLYWAY_LOCATIONS`.

### P2
6. **Паритет H2/PostgreSQL неполный**
   - PostgreSQL-only: `V0__enable_uuid_extensions`, `V014__waitlist`, `V025__hall_tables_active_number_unique`.
   - Риск: интеграционные тесты на H2 не ловят часть продовых ограничений/поведения.

7. **PII retention: телефоны хранятся в users/bookings/guest_list_entries без явной схемы анонимизации**
   - Где: `V1__init.sql:112,160,240`; таблицы Exposed: `UserTables.kt`, `BookingTables.kt`, `GuestListTables.kt`.
   - Риск: регуляторная/операционная нагрузка при запросах на удаление персональных данных.

---

## PostgreSQL vs H2 совместимость (кратко)
- Основные схемы синхронизированы по версиям `V1..V13` и `V015..V042`.
- В H2 отсутствуют PostgreSQL-specific миграции: `V0`, `V014`, `V025`.
- В `V7` для H2 нет partial unique активной брони/hold (используются обычные индексы), тогда как в PostgreSQL есть `uq_bookings_active_slot` (partial unique). Ссылки: `h2/V7__booking_core.sql:54-56`; `postgresql/V7__booking_core.sql:66-68`.

---

## Рекомендации (без внедрения)
1. **HOLD integrity:** в PostgreSQL добавить DB-уровень защиты активного hold через lock/lease pattern (например, отдельная таблица активных lock-ключей `(table_id,slot_start,slot_end)`), чтобы убрать race из app-only логики.
2. **Money append-only:** отделить `table_deposit_operations` (CREATE/TOPUP/SECURITY/ADJUSTMENT) и запретить update существующей операции; итоговую сумму считать агрегированием.
3. **Shift freeze hardening:** DB trigger/policy, запрещающий изменения `shift_report_*` при `shift_reports.status='CLOSED'`, кроме супер-override пути с явным audit fingerprint.
4. **Check-in invariant:** добавить материализованный user-night key для checkins и/или отдельный уникальный guard `(club_id, event_id, subject_user_id, arrived_flag)` там, где это возможно по модели.
5. **H2 parity:** либо добавить эквиваленты `V014/V025`, либо пометить non-parity в test profile и покрыть missing-ограничения integration-тестами на PostgreSQL.
6. **PII lifecycle:** добавить documented/analyzable SQL-процедуры анонимизации (`phone_e164`, display names) + backfill policy и аудит выполнения.

