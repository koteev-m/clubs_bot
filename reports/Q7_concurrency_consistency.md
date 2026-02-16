# Q7 — Concurrency & Consistency Deep Dive (HOLD/CONFIRM/CHECKIN/SHIFT CLOSE)

## Executive summary

- `HOLD -> CONFIRM` частично защищён идемпотентностью и уникальными индексами (`idempotency_key`, `uq_bookings_active_slot`), но в реализации есть TOCTOU-окна (pre-check `existsActiveFor` до `insert`) и отсутствие DB-ограничения «только один активный HOLD», что закрывается только приложением + ретраями. (`app-bot/src/main/kotlin/com/example/bot/booking/BookingService.kt:77-130`, `core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingRepository.kt:110-167`, `core-data/src/main/resources/db/migration/postgresql/V7__booking_core.sql:24-31`, `core-data/src/main/resources/db/migration/postgresql/V7__booking_core.sql:66-68`)
- В CHECK-IN есть сильная антидубль-модель: `UNIQUE(subject_type, subject_id)` + перехват unique violation и возврат `already used`; для booking-checkin запись checkin и смена статуса брони выполняются в одной транзакции. (`core-data/src/main/resources/db/migration/postgresql/V017__guest_list_invites_checkins.sql:70-83`, `core-data/src/main/kotlin/com/example/bot/data/club/GuestListRepositories.kt:733-787`, `core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt:187-217`)
- Денежные операции по столам сейчас не append-only: `table_deposits` обновляется in-place через `updateDeposit` (перезапись суммы + удаление/вставка allocations), что противоречит инварианту «только операциями». (`core-data/src/main/kotlin/com/example/bot/data/booking/TableSessionDepositRepositories.kt:94-136`, `core-data/src/main/resources/db/migration/postgresql/V036__table_sessions_deposits.sql:25-40`)
- Freeze после закрытия смены реализован для самого shift report (`updateDraft`/`close` под `FOR UPDATE` и проверкой `status=DRAFT`), но не подтверждён глобальный запрет на update deposit после close: route обновления депозита не проверяет `shift_reports.status`. (`core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportRepositories.kt:17-47`, `core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportRepositories.kt:588-633`, `app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt:429-497`)

## 1) Карта критичных write-операций

### 1.1 Booking HOLD

1. HTTP: `/api/clubs/{clubId}/bookings/hold` требует `Idempotency-Key`. (`app-bot/src/main/kotlin/com/example/bot/routes/SecuredBookingRoutes.kt:30-39`)
2. Service: `BookingService.hold(...)`:
   - проверка existing hold по idempotency;
   - проверка active booking;
   - `holdRepository.createHold(...)`.
   (`app-bot/src/main/kotlin/com/example/bot/booking/BookingService.kt:77-161`)
3. Repo: `createHold` в транзакции проверяет active booking + active hold и вставляет hold. (`core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingRepository.kt:470-541`)

### 1.2 Booking CONFIRM

1. HTTP: `/api/clubs/{clubId}/bookings/confirm` требует `Idempotency-Key`. (`app-bot/src/main/kotlin/com/example/bot/routes/SecuredBookingRoutes.kt:72-121`)
2. Service: `BookingService.confirm(...)`:
   - idempotency check по booking;
   - `consumeHold` (удаляет hold);
   - повторная проверка active booking;
   - `createBooked`.
   (`app-bot/src/main/kotlin/com/example/bot/booking/BookingService.kt:164-301`)
3. Repo: `createBooked` ловит unique violation и классифицирует в `DuplicateActiveBooking` / `IdempotencyConflict`. (`core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingRepository.kt:110-167`)

### 1.3 CHECK-IN (booking/guest list)

1. Host flow идёт через `checkinRepo.insertWithBookingUpdate`/`insertWithEntryUpdate`. (`core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt:187-217`, `core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt:307-337`)
2. `insertWithBookingUpdate` в одной транзакции:
   - CAS-like update booking status из разрешённых статусов;
   - insert checkin record.
   (`core-data/src/main/kotlin/com/example/bot/data/club/GuestListRepositories.kt:733-787`)
3. На дублях сервис ловит unique violation и возвращает уже существующий checkin как `already used`. (`core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt:102-107`, `core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt:206-215`)

### 1.4 Денежные операции (депозит/доплаты/охрана)

1. Создание депозита — insert новой записи `table_deposits` + allocations. (`core-data/src/main/kotlin/com/example/bot/data/booking/TableSessionDepositRepositories.kt:46-92`)
2. Обновление депозита — in-place `update table_deposits.amount_minor` + `delete/insert` allocations. (`core-data/src/main/kotlin/com/example/bot/data/booking/TableSessionDepositRepositories.kt:94-136`)
3. Админ-route обновления депозита вызывает `updateDeposit` напрямую, без проверки закрытой смены. (`app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt:429-497`)

### 1.5 Shift close / freeze

1. `updateDraft` блокирует report через `FOR UPDATE`, не обновляет CLOSED report, и пишет только если статус DRAFT. (`core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportRepositories.kt:19-47`)
2. `close` тоже `FOR UPDATE`, валидирует данные и атомарно переводит `DRAFT -> CLOSED`. (`core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportRepositories.kt:588-633`)
3. API слой дополнительно проверяет `status != CLOSED` до close/update. (`app-bot/src/main/kotlin/com/example/bot/routes/AdminFinanceShiftRoutes.kt:245-300`)

## 2) Где и как защищено от дублей

- **Идемпотентность HOLD/CONFIRM**:
  - обязательный `Idempotency-Key` в API;
  - unique `idempotency_key` в `booking_holds`/`bookings`;
  - `findByIdempotencyKey` short-circuit в service.
  (`app-bot/src/main/kotlin/com/example/bot/routes/SecuredBookingRoutes.kt:34-39`, `core-data/src/main/resources/db/migration/postgresql/V1__init.sql:136-145`, `core-data/src/main/resources/db/migration/postgresql/V1__init.sql:167-169`, `app-bot/src/main/kotlin/com/example/bot/booking/BookingService.kt:82-113`, `app-bot/src/main/kotlin/com/example/bot/booking/BookingService.kt:174-184`)

- **Одна активная бронь на слот**:
  - partial unique index `uq_bookings_active_slot` (`BOOKED`,`SEATED`).
  (`core-data/src/main/resources/db/migration/postgresql/V7__booking_core.sql:66-68`)

- **Один check-in на subject**:
  - `UNIQUE(subject_type, subject_id)`;
  - duplicate scan/map в `already used`.
  (`core-data/src/main/resources/db/migration/postgresql/V017__guest_list_invites_checkins.sql:70-83`, `core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt:102-107`)

- **Закрытие смены от гонок close/close**:
  - `FOR UPDATE` + conditional update по `status=DRAFT`; второй close получает `false`.
  (`core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportRepositories.kt:596-606`, `core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportRepositories.kt:623-633`)

- **Retry на deadlock/serialization**:
  - общий `withRetriedTx` + классификатор SQLSTATE `40001/40P01`.
  (`core-data/src/main/kotlin/com/example/bot/data/db/DbTransactions.kt:109-199`, `core-data/src/main/kotlin/com/example/bot/data/db/DbErrorClassifier.kt:8-37`)

## 3) Уязвимые гонки и консистентность (P0/P1/P2)

| Severity | Уязвимость | Impact | Где | Сценарий воспроизведения |
|---|---|---|---|---|
| P0 | Депозит редактируется in-place (не append-only) | Потеря аудируемости и возможность «перезаписать историю денег» | `TableDepositRepository.updateDeposit` | 1) создать депозит; 2) выполнить `PUT /deposits/{id}` с другой суммой; 3) исходная сумма исчезает, остаётся только новое состояние |
| P0 | После закрытия смены можно менять `table_deposits` | Нарушение freeze-инварианта закрытой смены | `AdminTableOpsRoutes.put(/deposits/{depositId})` + отсутствие проверки `shift_reports.status` | 1) закрыть shift report; 2) выполнить update депозита для той же ночи; 3) изменение проходит |
| P1 | Нет DB-constraint «один активный HOLD на слот» | При гонках/ретраях возможны конкурирующие holds до confirm | комментарий в миграции + app-level check | 2 клиента одновременно вызывают HOLD на один стол/слот: уникальность зависит от timing и application checks |
| P1 | TOCTOU между `existsActiveFor` и insert в hold/confirm | Повышенная конфликтность и зависимость от retry/exception path | `BookingService` + `BookingRepository.createBooked/createHold` | Два клиента проходят pre-check почти одновременно; один проигрывает на unique violation (или конфликте) |
| P1 | `updateStatusInternal` у booking без проверки допустимых переходов | Риск нелегальных переходов, если метод будет вызван из нового пути | `BookingRepository.updateStatusInternal` | вызвать `setStatus` в несогласованный target (в текущем коде частично экранируется сервисом) |
| P2 | payment idempotency в cancel/refund неатомарна (find then insert action) | При одновременных запросах часть операций получит 500 на unique race без graceful mapping | `DefaultPaymentsService` + `PaymentsRepositoryImpl.recordAction` | два запроса cancel/refund с одним idem-key одновременно: оба проходят `find==null`, один падает на unique вставке |

## 4) Согласованность статусов (ответы на критичные вопросы)

### 4.1 «Стол свободен, но бронь активна»

- В модели `table_sessions` и `bookings` нет жёсткого FK/constraint, который связывает OPEN/CLOSED стола с booking status (`BOOKED/SEATED/...`).
- Т.е. логически состояние «session closed, но booking BOOKED/SEATED» теоретически возможно при рассинхронизации процессных путей.
  (`core-data/src/main/resources/db/migration/postgresql/V036__table_sessions_deposits.sql:1-24`, `core-data/src/main/resources/db/migration/postgresql/V7__booking_core.sql:56-68`, `app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt:383-427`)

### 4.2 «Смена закрыта, но деньги изменили»

- Для `shift_reports` freeze есть (CLOSED не редактируется).
- Для `table_deposits` на уровне route/repository freeze не найден: update депозита не проверяет состояние shift report.
  (`core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportRepositories.kt:27-29`, `core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportRepositories.kt:588-633`, `app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt:429-497`)

## 5) Рекомендации (без изменения кода в этой задаче)

### P0
1. Перевести депозиты на **append-only ledger** (операции `INITIAL`, `TOP_UP`, `SECURITY`, `CORRECTION`) с immutable rows + derived balance view.
2. Ввести hard-gate: `table_deposits` update/insert запрещены, если shift report для `club_id+night_start_utc` уже `CLOSED` (DB trigger или сервисная транзакционная проверка + audit reason).

### P1
3. Укрепить HOLD-конкурентность: материализовать «active hold lock» (например, отдельная таблица lock-ключей, advisory lock, либо ограничение через deterministic key + upsert).
4. Для booking status transitions централизовать state machine в репозитории (WHERE current_status IN allowed + target validation), чтобы исключить нелегальные переходы при расширении API.
5. Для idempotency в cancel/refund сделать «insert-first» паттерн (`INSERT ... ON CONFLICT DO NOTHING RETURNING`) вместо `find -> insert`.

### P2
6. Добавить инвариантные тесты гонок (двойной HOLD, двойной CONFIRM, двойной CHECK-IN, CLOSE+UPDATE_DEPOSIT) и прогонять в CI как integration concurrency suite.
7. Для cross-entity консистентности «стол/бронь» добавить reconciliation job + алерты на несовместимые состояния.

## Итог

- **Сильные стороны**: check-in dedup, unique active booking, tx retry, row-level locks в shift close.
- **Критичные пробелы**: деньги не append-only и отсутствует гарантированный freeze для депозитов после закрытия смены.
- **Основной риск пятничного пика**: race-path’ы в HOLD/CONFIRM и idempotency windows cancel/refund дают всплеск конфликтов/повторов, даже если часть уже перехвачена уникальными ограничениями.
