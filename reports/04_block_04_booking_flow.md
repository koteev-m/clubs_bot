# Аудит Блока 4 — Бронирование столов

## 1) Фактический поток бронирования (команды / эндпоинты)

### 1.1 Основной защищённый API (RBAC + `BookingService`)

```mermaid
flowchart TD
    A[POST /api/clubs/{clubId}/bookings/hold] --> B[BookingService.hold]
    B --> C{Есть активная бронь/холд?}
    C -- Да --> D[409 duplicate_active_booking]
    C -- Нет --> E[Создание hold в booking_holds]

    F[POST /api/clubs/{clubId}/bookings/confirm] --> G[BookingService.confirm]
    G --> H[consumeHold(holdId): удаление hold]
    H --> I{Hold протух?}
    I -- Да --> J[410 hold_expired]
    I -- Нет --> K{Есть активная BOOKED/SEATED бронь?}
    K -- Да --> L[409 duplicate_active_booking]
    K -- Нет --> M[createBooked -> status=BOOKED]

    N[POST /api/clubs/{clubId}/bookings/{bookingId}/seat] --> O[BOOKED -> SEATED]
    P[POST /api/clubs/{clubId}/bookings/{bookingId}/no-show] --> Q[BOOKED -> NO_SHOW]
```

Опорные места в коде:
- Маршруты `hold/confirm/seat/no-show`: `app-bot/src/main/kotlin/com/example/bot/routes/SecuredBookingRoutes.kt:26-154`.
- Бизнес-логика hold/confirm/finalize/seat/no-show: `app-bot/src/main/kotlin/com/example/bot/booking/BookingService.kt:77-358`.
- Репозиторий бронирований/холдов/статусов: `core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingRepository.kt:88-260,442-695`.

### 1.2 Telegram guest flow (UI callback’и)

В Telegram callback-потоке выполняется последовательность:
1. `bookingService.hold(...)` с **фиксированным TTL=7 минут**.
2. `bookingService.confirm(...)`.
3. `bookingService.finalize(...)` (публикация `booking.confirmed` в outbox).

Опорные места:
- Hold/confirm/finalize вызовы и обработка ответов: `app-bot/src/main/kotlin/com/example/bot/telegram/MenuCallbacksHandler.kt:526-656,909-957`.
- Константа `HOLD_TTL = 7m`: `app-bot/src/main/kotlin/com/example/bot/telegram/MenuCallbacksHandler.kt:1110`.

### 1.3 Параллельный legacy web-flow

Есть отдельный miniapp/web-роут `/api/bookings`, который создаёт бронь **напрямую**, без HOLD-этапа (статус `CONFIRMED`), с отдельной логикой и таблицами-алиасами в файле.

Опорные места:
- `installBookingWebApp` и `POST /api/bookings`: `app-bot/src/main/kotlin/com/example/bot/web/BookingWebAppRoutes.kt:345,424-527`.
- Проверка занятости в legacy потоке по `CONFIRMED/SEATED`: `.../BookingWebAppRoutes.kt:462-470`.

---

## 2) Где создаётся HOLD, где превращается в BOOKED, где снимается

### HOLD создаётся
- В `BookingService.hold(...)` через `BookingHoldRepository.createHold(...)`.
- `expiresAt = now + ttl` (ttl приходит из запроса или из telegram-слоя).  
  Ссылки: `BookingService.kt:77-130`, `BookingRepository.kt:446-519,573-624`.

### HOLD превращается в BOOKED
- В `BookingService.confirm(...)`:
  - сначала `consumeHold(holdId)` (чтение+удаление hold),
  - затем `createBooked(... status=BOOKED ...)`.
- Если подтверждение идемпотентно повторяется — возвращается `AlreadyBooked`.  
  Ссылки: `BookingService.kt:164-301`, `BookingRepository.kt:110-176,678-693,262-322`.

### HOLD снимается
- При `confirm` — всегда удаляется запись hold (`consumeHoldInternal` удаляет row до проверки expired).
- Фоновая очистка просроченных hold через `cleanupExpired(now)` есть на уровне репозитория.
- Явного сервиса/воркера, который гарантированно регулярно вызывает cleanup, в контуре Блока 4 не найдено.  
  Ссылки: `BookingRepository.kt:564-570,678-693`.

---

## 3) Гонки и конкурентность: какие защиты есть

## Есть
1. **Идемпотентность hold/confirm** через `idempotency_key` + проверка совпадения payload/existing booking.  
   `BookingService.kt:82-113,174-184`.
2. **Проверка активной брони** до создания hold/booking (`existsActiveFor`).  
   `BookingService.kt:115-120,215-228`, `BookingRepository.kt:88-108`.
3. **DB-уникальность активных BOOKED/SEATED** на `(table_id, slot_start, slot_end)` (partial unique index).  
   `V7__booking_core.sql:64-68`.
4. **Интеграционный тест на гонку confirm**: параллельный confirm создаёт только одну бронь.  
   `BookingServiceIT.kt:79-111`.

## Частично
1. **Уникальность активного HOLD только на уровне приложения** (в БД нет partial unique по `expires_at > now()`).  
   Это явно задокументировано в миграции.  
   `V7__booking_core.sql:24-31`, `BookingRepository.kt:484-496`.
2. При конфликте в hold репозиторий на unique violation всегда мапит в `ActiveHoldExists`, без детального различения idempotency-конфликта.  
   `BookingRepository.kt:512-516`.

## Нет
1. Явного распределённого lock/serializable-изоляции на этап hold->confirm между разными runtime-процессами/инстансами (ставка на индексы+retry).
2. Централизованного сценария «продлить hold только менеджером» (см. раздел Missing).

---

## 4) Проверка критичных инвариантов Блока 4

### 4.1 HOLD TTL и запрет двух HOLD
- **TTL реализован** (`expiresAt = now + ttl`) и управляется входным параметром (`ttlSeconds` в API, 7 минут в Telegram flow).  
  `Requests.kt:10-30`, `MenuCallbacksHandler.kt:1110`, `BookingRepository.kt:599-610`.
- **Запрет двух HOLD** реализован **частично**: есть app-level проверка активного hold по столу/слоту, но без DB-гарантии уникальности активного hold.  
  `BookingRepository.kt:484-496`, `V7__booking_core.sql:24-31`.

### 4.2 Удержание брони 30 минут после времени прихода
- Поле `arrival_by` в модели есть, но при создании через core booking заполняется `null`; отдельной логики «держать 30 минут после времени прихода» не найдено.  
  `BookingTables.kt:71`, `BookingRepository.kt:308`.
- Итого: **не реализовано**.

### 4.3 Продление удержания только менеджером
- В репозитории есть `prolongHold(id, ttl)`, но публичного защищённого маршрута/сервиса с role-check для менеджера не найдено.  
  `BookingRepository.kt:521-536`.
- Итого: **частично (технический примитив есть, бизнес-потока и RBAC-ограничения нет)**.

### 4.4 Отмена до последнего момента
- Есть cancel-маршрут в payments контуре (`/cancel`) и Telegram `MyBookingsService.cancel`.
- Явного deadline-правила «до последнего момента» (или блокировки по времени) в найденных реализациях нет.
- Более того, в `MyBookingsService.performCancellation` UPDATE не фиксирует исходный статус в WHERE — возможно изменение записи вне ожидаемого состояния при гонке.  
  `MyBookingsService.kt:117-124`.
- Итого: **частично**.

### 4.5 Напоминание гостю перед слётом
- Отдельного механизма reminder перед истечением hold (scheduler/outbox topic/handler) не найдено.
- Есть лишь реакция на уже истёкший hold (`texts.holdExpired`).  
  `MenuCallbacksHandler.kt:597-600`.
- Итого: **не реализовано**.

### 4.6 Корректные статусы BOOKED/SEATED/NO_SHOW/CANCELLED
- В основной схеме статусы и check constraint приведены к требуемому набору.  
  `BookingTables.kt:9-14`, `V7__booking_core.sql:49-58`.
- Есть операции переходов `BOOKED -> SEATED/NO_SHOW`, а также cancel в `CANCELLED`.
- Итого: **реализовано в core потоке**.

### 4.7 Отсутствие двойных броней на один стол/ночь
- Есть app-level check + DB unique partial index для активных статусов.  
  `BookingService.kt:115-120,215-228`, `V7__booking_core.sql:66-68`.
- Покрыто интеграционным тестом параллельного confirm.  
  `BookingServiceIT.kt:79-111`.
- Итого: **реализовано (для core booking потока)**.

---

## 5) Missing / Partial / None

## Missing (нет)
1. Правило «удержание 30 минут после времени прихода».  
2. Напоминание гостю перед слётом hold.  
3. End-to-end manager-only продление hold (route + RBAC + audit).

## Partial (частично)
1. Запрет двойного HOLD: есть только app-level проверка (без DB-гарантии active hold uniqueness).
2. Отмена «до последнего момента»: есть cancel-флоу, но нет формализованного deadline-полиси в коде.
3. Система бронирования фрагментирована между core (`BOOKED`) и legacy web-flow (`CONFIRMED`), что повышает риск рассинхронизации инвариантов.

## None (не подтверждено в текущем коде)
1. Единый централизованный booking policy-движок, где явным образом заданы: hold ttl policy, grace period после arrival, cancel deadline, reminder windows.

---

## 6) Тесты: есть / нет и покрытие

## Есть
- `BookingServiceIT`:
  - гонка на confirm (single booking),
  - idempotent confirm,
  - finalize/outbox happy-path.  
  `app-bot/src/test/kotlin/com/example/bot/booking/BookingServiceIT.kt:79-193`.
- `BookingHoldRepositoryIT`:
  - TTL,
  - prolong hold,
  - consume hold,
  - cleanup expired.  
  `core-data/src/test/kotlin/com/example/bot/data/booking/core/BookingHoldRepositoryIT.kt:26-127`.

## Нет / пробелы
1. Нет тестов на бизнес-инвариант «30 минут после времени прихода».
2. Нет тестов на reminder перед истечением hold.
3. Нет интеграционного сценария manager-only prolong hold (потому что нет публичного API).
4. Нет e2e-тестов консистентности между legacy `/api/bookings` и core `/api/clubs/{clubId}/bookings/*`.

---

## 7) Краткий вывод

По Блоку 4 базовая core-механика `HOLD -> BOOKED -> (SEATED|NO_SHOW|CANCELLED)` реализована и частично защищена от гонок/дублей (идемпотентность + DB индекс на активные брони). Одновременно критичные продуктовые инварианты из спецификации (30-минутное удержание после времени прихода, reminder перед слётом, менеджерское продление hold как обязательный бизнес-поток) в текущей реализации отсутствуют или реализованы только на уровне низкоуровневых примитивов.
