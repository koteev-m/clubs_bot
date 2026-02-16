# Блок 2 (Календарь и доступность) — аудит соответствия

## 1) Модель календаря + где формируется список доступных ночей

### Что есть в модели (БД/домен)

- В схеме есть календарные сущности:
  - `clubs.timezone` (IANA TZ клуба).【core-data/src/main/resources/db/migration/postgresql/V1__init.sql:6-18】
  - `club_hours` (правила по дням недели: `dow`, `open_local`, `close_local`).【core-data/src/main/resources/db/migration/postgresql/V1__init.sql:24-34】
  - `club_holidays` (праздники/особые даты с override времени и `is_open`).【core-data/src/main/resources/db/migration/postgresql/V1__init.sql:35-46】
  - `club_exceptions` (исключения/закрытия с override и reason).【core-data/src/main/resources/db/migration/postgresql/V1__init.sql:47-57】
  - `events` (материализованные ночи в UTC).【core-data/src/main/resources/db/migration/postgresql/V1__init.sql:62-76】

- Доменная модель для правил/слотов присутствует:
  - `ClubHour`, `ClubHoliday`, `ClubException`, `Club`, `Event`, `NightSlot`.
  - Алгоритм `OperatingRulesResolver.resolve(...)` умеет объединять base-hours + holiday + exception + materialized events.【core-domain/src/main/kotlin/com/example/bot/time/OperatingRulesResolver.kt:57-100】【core-domain/src/main/kotlin/com/example/bot/time/OperatingRulesResolver.kt:156-321】

### Где реально формируется список доступных ночей (runtime путь)

- В DI зарегистрирован `DefaultAvailabilityService` как основной `AvailabilityService`.【app-bot/src/main/kotlin/com/example/bot/di/AvailabilityModule.kt:8-13】
- Публичные API вызывают `service.listOpenNights(...)`:
  - `/api/clubs/{clubId}/nights`.
  - `/api/clubs/{clubId}/nights/{startUtc}/tables/free`.【app-bot/src/main/kotlin/com/example/bot/routes/AvailabilityApiRoutes.kt:35-71】
- Telegram guest flow тоже получает ночи через `availability.listOpenNights(...)`.【app-bot/src/main/kotlin/com/example/bot/telegram/MenuCallbacksHandler.kt:724-729】

### Ключевой факт по реализации

- `DefaultAvailabilityService.listOpenNights(...)` **не использует** правила `club_hours/holidays/exceptions`; он просто читает `events where end_at > now`, сортирует по `start_at`, делает `limit` и возвращает DTO. 【core-data/src/main/kotlin/com/example/bot/availability/DefaultAvailabilityService.kt:37-68】
- В этом же методе timezone жёстко выставлен в `UTC`, а `arrivalByUtc = start` (без политик cut-off/arrival).【core-data/src/main/kotlin/com/example/bot/availability/DefaultAvailabilityService.kt:54-66】

---

## 2) Проверка edge cases

### 2.1 Ночь пересекает сутки

- В доменной логике пересечение суток поддержано корректно: при `close <= open` конец сдвигается на +1 день. 【core-domain/src/main/kotlin/com/example/bot/time/OperatingRulesResolver.kt:218-219】【core-domain/src/main/kotlin/com/example/bot/time/OperatingRulesResolver.kt:468-476】
- Но runtime список ночей формируется из `events` как есть (не из правил), поэтому корректность зависит от того, как/кем заполнены `events`.【core-data/src/main/kotlin/com/example/bot/availability/DefaultAvailabilityService.kt:43-52】

### 2.2 TZ клуба

- В модели TZ клуба есть (`clubs.timezone`) и в `OperatingRulesResolver` TZ клуба реально применяется (`ZoneId.of(club.timezone)`).【core-data/src/main/resources/db/migration/postgresql/V1__init.sql:10-18】【core-domain/src/main/kotlin/com/example/bot/time/OperatingRulesResolver.kt:161-164】
- В runtime `DefaultAvailabilityService.listOpenNights(...)` TZ клуба **игнорируется**, используется фиксированное `UTC`.【core-data/src/main/kotlin/com/example/bot/availability/DefaultAvailabilityService.kt:54-57】

### 2.3 Правила по дням недели + исключения/праздники

- В БД и домене поддержка есть (`club_hours/holidays/exceptions` + merge-алгоритм приоритета).【core-data/src/main/resources/db/migration/postgresql/V1__init.sql:24-57】【core-domain/src/main/kotlin/com/example/bot/time/OperatingRulesResolver.kt:166-212】【core-domain/src/main/kotlin/com/example/bot/time/OperatingRulesResolver.kt:380-466】
- В runtime-пути выдачи доступных ночей (через `DefaultAvailabilityService`) это **не используется**. 【core-data/src/main/kotlin/com/example/bot/availability/DefaultAvailabilityService.kt:37-68】

### 2.4 Cut-off брони = конец ночи − 2 часа (настраиваемо)

- Текущая `CutoffPolicy` реализует другое правило: online-booking open если `now + onlineCutoffMinutes < eventStartUtc` (по умолчанию 60 минут), то есть отталкивается от **начала**, а не от `eventEnd - 2h`.【core-domain/src/main/kotlin/com/example/bot/policy/CutoffPolicy.kt:10-23】
- Параметр есть в конструкторе (`onlineCutoffMinutes`), но в runtime через DI не видно конфигурации per-club/per-night.
- Дополнительно: в используемом `DefaultAvailabilityService.listOpenNights(...)` cut-off вообще не применяется (метод переопределён).【core-data/src/main/kotlin/com/example/bot/availability/DefaultAvailabilityService.kt:37-68】

### 2.5 Уведомления об изменениях календаря

- В просмотренном коде не найдено маршрутов/сервисов, которые обновляют `club_hours/club_holidays/club_exceptions/events` с последующей отправкой нотификаций о календарных изменениях.
- Поиск по кодовой базе не выявил явных calendar-change уведомлений; есть общая инфраструктура notify/outbox, но без календарного сценария как доменного события в доступных файлах. 【app-bot/src/main/kotlin/com/example/bot/routes/AvailabilityApiRoutes.kt:35-71】【core-domain/src/main/kotlin/com/example/bot/notifications/NotifyModels.kt:1-68】

### 2.6 Кейс “пятница 00:00–12:00 субботы”

- Модель `ClubHour(open: LocalTime, close: LocalTime)` с `toUtcWindow(...)` поддерживает диапазон «внутри суток» или «до +1 дня».【core-domain/src/main/kotlin/com/example/bot/time/OperatingRulesResolver.kt:57-61】【core-domain/src/main/kotlin/com/example/bot/time/OperatingRulesResolver.kt:468-476】
- Диапазон длиной >24 часа (например, 36 часов) напрямую не моделируется через `club_hours` в текущем виде; нужен либо materialized `event`, либо расширение модели.

### 2.7 Отмена ночи

- Теоретически поддерживается через `club_exceptions.is_open=false` в resolver-логике (`DayResolution.Closed(EXCEPTION_CLOSED)`).【core-domain/src/main/kotlin/com/example/bot/time/OperatingRulesResolver.kt:391-394】
- Но в runtime listOpenNights отмена через exceptions не влияет, если событие осталось в `events`, т.к. используется прямой read из `events`.【core-data/src/main/kotlin/com/example/bot/availability/DefaultAvailabilityService.kt:43-52】

---

## 3) Недостающие элементы относительно AGENTS.md (Блок 2)

1. **Единый runtime-контур “правила + исключения + праздники” для выдачи ночей** — сейчас фактически выдача строится из `events` напрямую.
2. **Корректный cut-off по спецификации (`end - 2h`, настраиваемо)** — в коде другая логика (от `start`) и в рабочем сервисе она не применяется.
3. **Использование TZ клуба в runtime выдаче ночей** — сейчас в `DefaultAvailabilityService` TZ захардкожен в `UTC`.
4. **Механизм уведомлений об изменениях календаря** (calendar change notifications) — явной реализации не найдено.
5. **Админ-контур управления календарём** (CRUD для hours/holidays/exceptions/events) в рамках просмотренных routes не обнаружен как целостная подсистема.

---

## 4) Тесты: есть/нет и что покрывают

### Есть

- `OperatingRulesResolverTest` (unit):
  - holiday inheritance,
  - holiday without base,
  - overnight (`22:00 -> 04:00`) перенос на следующий день.
  【core-domain/src/test/kotlin/com/example/bot/time/OperatingRulesResolverTest.kt:19-88】

- `OperatingRulesResolverIT` (IT):
  - комбинированные кейсы holiday/exception, включая закрытие ночи.
  【core-domain/src/test/kotlin/com/example/bot/time/OperatingRulesResolverIT.kt:17-70】

- `AvailabilityCalendarTest` (IT):
  - holiday override + base hours,
  - exception closes night.
  【core-domain/src/test/kotlin/com/example/bot/availability/AvailabilityCalendarTest.kt:45-112】【core-domain/src/test/kotlin/com/example/bot/availability/AvailabilityCalendarTest.kt:114-170】

- `AvailabilityTablesTest` (IT):
  - исключение HOLD/BOOKED столов из free list.
  【core-domain/src/test/kotlin/com/example/bot/availability/AvailabilityTablesTest.kt:45-103】

### Нет / пробелы покрытия

- Нет тестов, подтверждающих соответствие требованию `cut-off = end - 2h`.
- Нет тестов на runtime-путь `DefaultAvailabilityService.listOpenNights(...)` с проверкой TZ клуба и применения правил `club_hours/holidays/exceptions`.
- Нет тестов на календарные уведомления при изменении расписания.
- Нет теста на длинные интервалы типа “пятница 00:00 – суббота 12:00” как отдельный бизнес-кейс.

