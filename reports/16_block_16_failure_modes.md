# Блок 16 (Сбои/аварийные режимы) — аудит соответствия

## 1) Фолбэки (карта → список, QR → поиск/журнал)

## 1.1 QR → поиск/ручной проход

### Что реализовано

- Для входа есть отдельный host-контур с тремя режимами:
  1) `POST /api/host/checkin/scan` — скан QR,
  2) `GET /api/host/checkin/search` — поиск вручную,
  3) `POST /api/host/checkin` — ручной check-in по `bookingId`/`guestListEntryId`/`invitationToken`.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt:120-152`
- `app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt:154-202`

- Поиск в fallback-режиме реально идёт по двум источникам: guest list + bookings.
  - В guest list поддержан фильтр по имени и телефону.
  - Для bookings в текущей реализации поиск по `guestName`.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/host/HostSearchService.kt:37-73`
- `core-data/src/main/kotlin/com/example/bot/data/club/GuestListRepositoryImpl.kt:341-350`
- `core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingRepository.kt:398-434`

### Вывод

- Требование «QR → поиск/журнал» покрыто **частично**:
  - **поиск** есть и рабочий,
  - отдельного API «журнал сканов/check-in событий» для host в routes не найдено (хотя таблица/репозиторий checkins есть).

Ссылки:
- `core-data/src/main/kotlin/com/example/bot/data/club/GuestListRepositories.kt:807-825`
- `core-data/src/main/resources/db/migration/postgresql/V017__guest_list_invites_checkins.sql:70-83`

## 1.2 Карта → список столов

### Что есть

- Контур карты есть: `GET /api/clubs/{id}/layout`.
- Отдельно есть список доступных столов на ночь: `GET /api/clubs/{clubId}/nights/{startUtc}/tables/free`.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/routes/LayoutRoutes.kt:96-106`
- `app-bot/src/main/kotlin/com/example/bot/routes/AvailabilityApiRoutes.kt:58-74`

### Ограничение

- Явного оркестратора «если layout недоступен, автоматически переключиться на list API» в просмотренном runtime-контуре не найдено.
- При этом есть fallback в Telegram (команды `/my`, `/qr`, `/ask`) как деградация при проблемах miniapp, но это другой уровень (чатовый fallback, не map→list внутри miniapp UI).

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/telegram/TelegramGuestFallbackHandler.kt:60-77`
- `docs/p2.4-discovery-bot-fallbacks.md:24-33`

---

## 2) Режимы деградации / инцидентов

## Что реализовано

1. **DB-degradation protection**:
   - централизованный retry/backoff транзакций,
   - circuit breaker с fast-fail при серии connection ошибок.

Ссылки:
- `core-data/src/main/kotlin/com/example/bot/data/db/DbTransactions.kt:109-124`
- `core-data/src/main/kotlin/com/example/bot/data/db/DbTransactions.kt:159-165`
- `docs/runtime-db-resiliency.md:3-33`

2. **Операционный мониторинг деградации** описан в runbook/alerts (DB breaker, QR spikes и т.д.).

Ссылки:
- `docs/alerts.md:7-8`
- `docs/alerts.md:27-33`
- `docs/alerts.md:42-45`

## Что отсутствует

- Не найдена централизованная модель инцидент-режимов уровня продукта (`NORMAL/DEGRADED/INCIDENT`) с переключением поведения ключевых сценариев.
- Не найден единый admin/API контур «включить аварийный режим клуба/системы».

(Поиск по routes/services не показал отдельного incident-mode модуля в runtime.)

---

## 3) Ручные журналы и восстановление

## Что есть

1. **DR/PITR runbook** в документации (snapshot+WAL, fire drill).

Ссылки:
- `docs/dr.md:7-17`
- `docs/dr.md:19-28`

2. **Технический журнал check-ins** в БД (`checkins`) с методами/статусами/deny_reason.

Ссылки:
- `core-data/src/main/resources/db/migration/postgresql/V017__guest_list_invites_checkins.sql:70-83`
- `core-data/src/main/kotlin/com/example/bot/data/club/GuestListRepositories.kt:789-825`

3. **Инструменты ручного восстановления outbox** (list/filter/replay, dry-run) есть в `OutboxAdminRoutes`.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/routes/OutboxAdminRoutes.kt:103-112`
- `app-bot/src/main/kotlin/com/example/bot/routes/OutboxAdminRoutes.kt:124-131`
- `app-bot/src/main/kotlin/com/example/bot/routes/OutboxAdminRoutes.kt:233-251`

## Ограничение

- В основном `Application.module()` не видно регистрации `outboxAdminRoutes(...)`, т.е. контур ручного replay существует в коде, но не подтверждён как включённый в основной runtime этого модуля.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/Application.kt:332-447`

---

## 4) “Пауза маркетинга” при инциденте

## Что есть

- В `NotifyRoutes` есть campaign-status `PAUSED` и endpoint’ы `:pause/:resume`.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/routes/NotifyRoutes.kt:32-33`
- `app-bot/src/main/kotlin/com/example/bot/routes/NotifyRoutes.kt:233-243`

## Критичный пробел

- `notifyRoutes(...)` не подключён в `Application.module()`, поэтому «пауза кампаний» не выглядит как гарантированно доступный production-механизм.
- Дополнительно scheduler кампаний имеет отдельный запуск через env-флаг (`CAMPAIGN_SCHEDULER_ENABLED`), но хук запуска не найден в основном модуле.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/Application.kt:332-447`
- `app-bot/src/main/kotlin/com/example/bot/workers/SchedulerModule.kt:87-97`
- `app-bot/src/main/kotlin/com/example/bot/workers/SchedulerModule.kt:101-135`

---

## 5) Итог соответствия Блоку 16

## Implemented
- QR fallback в операционке входа: scan + manual check-in + search.
- DB resilience (retry/backoff + circuit breaker).
- Документированный DR/PITR runbook.

## Partial
- Карта и список столов существуют как отдельные API, но автопереключение map→list в runtime не обнаружено.
- Технический журнал check-ins есть, но продуктовый журнал для host как отдельный endpoint не найден.
- Контур outbox replay есть в коде, но не подтверждён как подключённый в основном runtime.

## Missing
- Централизованный incident/degraded mode на уровне продукта.
- Гарантированно рабочая “пауза маркетинга при инциденте” в основном runtime-контуре.

## Риски
- **P1**: при сбое QR оператору придётся вручную переключаться между scan/search без единого «журнала инцидента» в host API.
- **P1**: отсутствие централизованного incident mode повышает вероятность непоследовательного поведения разных подсистем при аварии.
- **P2**: пауза маркетинга формально описана в коде кампаний, но без явного runtime-wiring может не сработать в реальном инциденте.
