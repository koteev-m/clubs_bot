# Блок 7 — Операционка столов (аудит соответствия)

## 1) Модель «стол / ночь / денежные операции»

### Что реализовано

1. **Сессия стола как операционная сущность на конкретную ночь**:
   - `table_sessions` + доменная модель `TableSession` с `OPEN/CLOSED`, `opened_by/closed_by`, `note`. См.:
     - `core-data/src/main/kotlin/com/example/bot/data/booking/BookingTables.kt:80-93`
     - `core-data/src/main/kotlin/com/example/bot/data/booking/TableSessionDepositRepositories.kt:24-40`
     - `core-data/src/main/resources/db/migration/postgresql/V036__table_sessions_deposits.sql:1-24`

2. **Депозит как набор операций, а не одно поле в сессии**:
   - `table_deposits` + `table_deposit_allocations`, доменные `TableDeposit`, `TableDepositAllocation`.
   - Создание/обновление выполняются репозиторием; при обновлении сохраняется причина (`updateReason`). См.:
     - `core-data/src/main/kotlin/com/example/bot/data/booking/BookingTables.kt:95-119`
     - `core-data/src/main/kotlin/com/example/bot/data/booking/TableSessionDepositRepositories.kt:42-69,195-285`
     - `core-data/src/main/resources/db/migration/postgresql/V036__table_sessions_deposits.sql:25-63`

3. **Публичный admin API по столам/депозитам**:
   - `GET /tables`, `POST /tables/{tableId}/seat`, `POST /tables/{tableId}/free`, `PUT /deposits/{depositId}`.
   - Все под mini app auth + RBAC (`OWNER/GLOBAL_ADMIN/HEAD_MANAGER/CLUB_ADMIN/MANAGER`) и проверкой доступа к клубу. См.:
     - `app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt:130-137,383-435`

4. **Базовая защита от двойной активной сессии**:
   - В БД: `UNIQUE (club_id, night_start_utc, table_id, open_marker)`.
   - В коде: `openSession` обрабатывает unique-conflict и возвращает уже существующую open-сессию (идемпотентность). См.:
     - `core-data/src/main/resources/db/migration/postgresql/V036__table_sessions_deposits.sql:13`
     - `core-data/src/main/kotlin/com/example/bot/data/booking/TableSessionDepositRepositories.kt:84-120`
     - `core-data/src/test/kotlin/com/example/bot/data/booking/TableSessionDepositRepositoryTest.kt:35-82`

---

## 2) Логика статусов и ключевых действий

### 2.1 Посадка (seat)
- В текущем API есть только `SeatMode.WITH_QR` и `SeatMode.NO_QR`; режимы из требования **«депозит / по счёту / от клуба»** явно не представлены отдельной моделью/enum. См.:
  - `app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt:110-114,69-77`

- При `seat`:
  - если активная сессия есть и в ней уже есть депозит → возвращается текущая сессия/депозит;
  - если активная сессия есть, но депозита нет → создаётся депозит;
  - если активной сессии нет → открывается сессия и создаётся депозит.
  См.: `app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt:199-331`.

### 2.2 Освобождение стола («Гости ушли»)
- Реализовано как `POST /tables/{tableId}/free`: закрывает только `OPEN`-сессию, пишет аудит `tableSessionClosed`, возвращает стол как свободный. См.:
  - `app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt:383-427`
  - `core-data/src/main/kotlin/com/example/bot/data/booking/TableSessionDepositRepositories.kt:123-142`

### 2.3 No-show
- Статус `NO_SHOW` есть в общей модели бронирований (`BookingStatus`), и есть отдельный endpoint `POST /api/secure/bookings/{bookingId}/no-show`.
- Но это **контур booking**, а не отдельная операция внутри `AdminTableOpsRoutes` (table-session контура). См.:
  - `core-data/src/main/kotlin/com/example/bot/data/booking/BookingTables.kt:9-14`
  - `app-bot/src/main/kotlin/com/example/bot/routes/SecuredBookingRoutes.kt:125-146`
  - `app-bot/src/main/kotlin/com/example/bot/booking/BookingService.kt:347-358`

### 2.4 Депозит/доплаты/распределение
- Денежные операции идут через `createDeposit/updateDeposit`; есть валидация суммы аллокаций = сумме депозита.
- `updateDeposit` требует `reason` (и API валидирует непустую/ограниченную причину). См.:
  - `core-data/src/main/kotlin/com/example/bot/data/booking/TableSessionDepositRepositories.kt:243-285,375-406`
  - `app-bot/src/main/kotlin/com/example/bot/routes/AdminTableOpsRoutes.kt:429-497,579-590`

- Аллокации категорий универсальные (`categoryCode`), т.е. выделение «охраны» возможно только как соглашение кода категории, без отдельной доменной гарантии. См.:
  - `core-data/src/main/kotlin/com/example/bot/data/booking/BookingTables.kt:113-119`
  - `core-data/src/main/kotlin/com/example/bot/data/booking/TableSessionDepositRepositories.kt:389-406`

---

## 3) Гонки / конкурентность / риск двойных сумм

### Защиты, которые есть
1. **Одна OPEN-сессия на стол/ночь** (DB уникальность + retry/fallback в репозитории). См.:
   - `V036__table_sessions_deposits.sql:13`
   - `TableSessionDepositRepositories.kt:84-120`

2. **Идемпотентное закрытие сессии** (`closeSession` обновляет только OPEN; повторный вызов → `false`). См.:
   - `TableSessionDepositRepositories.kt:123-142`
   - `TableSessionDepositRepositoryTest.kt:84-101`

3. **Сумма аллокаций контролируется** (исключает арифметическое расхождение «детализация != итог»). См.:
   - `TableSessionDepositRepositories.kt:375-387`

### Риски и уязвимые зоны
1. **P1: семантика «доплат» как update существующего депозита**.
   - Спецификация требует «депозит операциями: первичный + доплаты». Текущий `updateDeposit` перезаписывает сумму/аллокации текущей записи, а не создаёт отдельную immutable-операцию «top-up». См.:
     - `TableSessionDepositRepositories.kt:243-273`
   - Это затрудняет восстановление полной истории шагов доплаты и повышает риск спорных интерпретаций в отчётности.

2. **P1: риск разночтений total при множественных депозитах одной сессии**.
   - API при `seat` берёт «последний» депозит (`max(createdAt, id)`), но агрегаты (`sumDepositsForNight`) суммируют все записи за ночь. См.:
     - `AdminTableOpsRoutes.kt:147-159,202-206`
     - `TableSessionDepositRepositories.kt:287-306,334-353`
   - Если в реальном процессе одна сессия получит несколько записей (initial + поправки), возможна неоднозначность «какая сумма считается активной».

3. **P2: нет жёсткой доменной категории для `охрана`**.
   - `categoryCode` — свободный код (нормализация + уникальность внутри депозита), но нет whitelist/enum обязательных категорий. См.:
     - `TableSessionDepositRepositories.kt:389-406`
     - `V036__table_sessions_deposits.sql:54-60`

4. **P2: нет явной бизнес-логики 50/50 («бар/шары»)**.
   - В коде есть только механизм ручных аллокаций, без отдельного флага/правила авто-распределения 50/50.

---

## 4) Сопоставление с требованиями блока 7

| Требование | Статус | Где в коде / комментарий |
|---|---|---|
| Посадка: депозит / по счёту / от клуба (с причиной) | **Partial** | Есть `seat`, но режимы только `WITH_QR/NO_QR`; отдельные режимы «по счёту/от клуба» и причина для `от клуба` не формализованы (`AdminTableOpsRoutes.kt:110-114,164-381`). |
| Депозит операциями: первичный + доплаты | **Partial** | Операции есть (`createDeposit`, `updateDeposit`), но доплата как отдельная immutable операция не выделена; update перезаписывает запись (`TableSessionDepositRepositories.kt:195-285`). |
| Отдельная услуга «охрана» | **Partial** | Технически можно через `categoryCode`, но нет обязательной доменной категории/правила (`TableSessionDepositRepositories.kt:389-406`). |
| Распределение депозита (бар/шары/50-50) | **Partial/None** | Есть только ручные allocations и их валидация суммы (`TableSessionDepositRepositories.kt:375-387`), авто-режима 50/50 не найдено. |
| Освобождение стола «Гости ушли» | **Implemented** | `POST /tables/{tableId}/free` + `closeSession` (`AdminTableOpsRoutes.kt:383-427`, `TableSessionDepositRepositories.kt:123-142`). |
| no-show | **Partial** | Есть в booking-контуре (`SecuredBookingRoutes.kt:140-145`), но не как часть table-ops сценария. |
| Стоп продаж столов + Undo | **None** | В `AdminTableOpsRoutes`/репозиториях стола не найдено endpoint/флагов stop sales + undo (поиск по коду `rg -n "stop|undo|sales" app-bot core-data`). |

---

## 5) Что отсутствует

1. **Явная модель режимов посадки** по бизнес-терминам блока 7:
   - `DEPOSIT`, `BY_BILL`, `FROM_CLUB` (+ обязательная причина для `FROM_CLUB`).
2. **Отдельная команда «доплата»** (append-only), вместо редактирования существующего депозита.
3. **Стоп продаж столов + Undo** на ночь/клуб/зону/стол (в текущем table-ops контуре не найдено).
4. **Формализованная политика распределения 50/50** (включаемый флаг + алгоритм + аудит применения).
5. **Явный доменный флаг/категория «охрана»** (чтобы не зависеть от произвольного `categoryCode`).

---

## 6) Тесты: есть / нет

### Есть
- Репозиторные тесты по сессиям/депозитам:
  - idempotent `openSession`/`closeSession`, валидация сумм, нормализация категорий, обновление с причиной.
  - `core-data/src/test/kotlin/com/example/bot/data/booking/TableSessionDepositRepositoryTest.kt:35-101,103-224,226-280`
- Route-тесты `AdminTableOpsRoutes`:
  - seat с/без QR, forbidden по роли, валидация allocations, «занято — вернуть существующее». См.:
  - `app-bot/src/test/kotlin/com/example/bot/routes/AdminTableOpsRoutesTest.kt:72-247`

### Нет / пробелы
- Нет тестов для стоп-продаж + undo (не найдено и функционала).
- Нет тестов доменной семантики режимов «по счёту/от клуба».
- Нет тестов обязательности/валидности категории «охрана» как отдельной услуги.
- Нет тестов авто-распределения 50/50 (функционал не найден).

---

## 7) Краткий вывод

Текущая реализация закрывает базовую «операционку стола» (занять/освободить, хранить депозит и аллокации, аудит, базовые анти-гонки), но по Блоку 7 остаются значимые разрывы в бизнес-семантике: режимы посадки, stop-sales+undo, «охрана» как отдельная норма, а также чёткая модель доплат и 50/50-распределения.
