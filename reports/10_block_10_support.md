# Блок 10 — “Задать вопрос / саппорт” (аудит соответствия)

## 1) Где реализованы тикеты

### 1.1 Доменные модели и статусы

1. **Категории тикетов**
   - `TicketTopic`: `address`, `dresscode`, `booking`, `invite`, `lost_found`, `complaint`, `other`.
   - См. `core-domain/src/main/kotlin/com/example/bot/support/SupportModels.kt:6-18`.

2. **Статусы тикетов (фактические)**
   - `TicketStatus`: `opened`, `in_progress`, `answered`, `closed`.
   - См. `core-domain/src/main/kotlin/com/example/bot/support/SupportModels.kt:20-29`.

3. **Сущности тикетов**
   - `Ticket` содержит `clubId`, `userId`, опционально `bookingId`, `listEntryId`, а также `lastAgentId`, `resolutionRating`.
   - См. `SupportModels.kt:41-53`.

### 1.2 БД и таблицы

1. **`tickets`**
   - Поля: `club_id`, `user_id`, `booking_id`, `list_entry_id`, `topic`, `status`, `last_agent_id`, `resolution_rating`, timestamps.
   - См. `core-data/src/main/resources/db/migration/postgresql/V022__support_tickets.sql:1-13`.

2. **`ticket_messages`**
   - Поля: `ticket_id`, `sender_type (guest/agent/system)`, `text`, `attachments`, `created_at`.
   - См. `V022__support_tickets.sql:15-22`.

3. **Индексы для операционки**
   - По пользователю/клубу/статусу и сообщениям.
   - См. `V022__support_tickets.sql:24-26`, `V023__support_tickets_indexes.sql:1`.

### 1.3 Сервис и репозиторий

1. **Репозиторный слой**
   - `SupportRepository`: create/list/add message/assign/status/reply/rating.
   - См. `core-data/src/main/kotlin/com/example/bot/data/support/SupportRepository.kt:30-333`.

2. **Сервисный слой**
   - `SupportServiceImpl` маппит репозиторные ошибки в доменные (`TicketNotFound`, `TicketForbidden`, `TicketClosed`, etc.).
   - См. `core-data/src/main/kotlin/com/example/bot/data/support/SupportServiceImpl.kt:15-149`.

### 1.4 HTTP/API (панель + guest)

1. **Guest endpoints**
   - `POST /api/support/tickets` — создать вопрос;
   - `GET /api/support/tickets/my` — мои тикеты;
   - `POST /api/support/tickets/{id}/messages` — дописать сообщение.
   - См. `app-bot/src/main/kotlin/com/example/bot/routes/SupportRoutes.kt:131-244`.

2. **Admin endpoints (через панель/miniapp API)**
   - `GET /api/support/tickets?clubId=...&status=...`;
   - `POST /api/support/tickets/{id}/assign`;
   - `POST /api/support/tickets/{id}/status`;
   - `POST /api/support/tickets/{id}/reply`.
   - См. `SupportRoutes.kt:246-397`.

3. **Telegram ответ пользователю**
   - После admin `reply` отправляется DM пользователю через `sendSupportReplyNotification(...)` + inline rating keyboard.
   - См. `SupportRoutes.kt:381-389,459-508`.

---

## 2) Как маршрутизируется в нужный клуб

1. **На уровне данных тикета**
   - Каждый тикет создаётся с явным `clubId` и хранит его в `tickets.club_id`.
   - См. `SupportRepository.kt:34-57`, `V022__support_tickets.sql:1-4`.

2. **На уровне доступа для админов**
   - Проверка `hasSupportClubAccess(clubId)`: global роли (`OWNER/GLOBAL_ADMIN/HEAD_MANAGER`) либо клуб в `rbacContext.clubIds`.
   - Любое admin-действие (`list/assign/status/reply`) сначала проверяет клуб тикета.
   - См. `SupportRoutes.kt:115-117,263-265,281-344,536-549`.

3. **Фолбэк в Telegram `/ask`**
   - Выбор клуба в callback `ask:club:{id}` и создание тикета в выбранный `clubId`.
   - См. `app-bot/src/main/kotlin/com/example/bot/telegram/TelegramGuestFallbackHandler.kt:160-229`.

4. **Опциональное дублирование в админ-чат**
   - При создании тикета публикуется `OpsDomainNotification(event=SUPPORT_QUESTION_CREATED, clubId=...)`.
   - Роутинг по support-треду: `OpsNotificationCategory.SUPPORT -> config.supportThreadId`.
   - См. `SupportRoutes.kt:164-173`, `core-domain/src/main/kotlin/com/example/bot/opschat/OpsChatModels.kt:8-31,52-60`, `app-bot/src/main/kotlin/com/example/bot/notifications/TelegramOperationalNotificationService.kt:266-275`.

---

## 3) Проверка критичных требований блока 10

| Требование | Статус | Комментарий |
|---|---|---|
| Категории | **Implemented** | Категории зафиксированы enum + DB check constraint. |
| Тикеты со статусами | **Partial** | Статусы есть, но схема `opened/in_progress/answered/closed` не совпадает со spec `NEW/IN_PROGRESS/WAITING/RESOLVED/CLOSED`. |
| Связь с клубом/ночью/бронью/списком | **Partial** | Есть `club_id`, `booking_id`, `list_entry_id`; **night** поля нет. В текущих API-сценариях `bookingId/listEntryId` при создании ставятся `null`. |
| Ответы админов через бот/панель | **Implemented** | Admin reply endpoint + отправка ответа пользователю в Telegram. |
| (Опц.) дублирование в админ-чат | **Implemented (optional)** | Публикуется ops notification support-события, роутится в support thread при наличии конфига. |

---

## 4) Missing / Partial / None

### Missing / None

1. **Нет статуса-пайплайна как в спецификации**
   - Отсутствуют `NEW`, `WAITING`, `RESOLVED` как отдельные wire-статусы.

2. **Нет привязки тикета к “ночи” (nightStart/event night) как first-class полю**
   - В схеме тикета нет `night_id`/`night_start_utc`.

### Partial

1. **Связь с booking/list есть в схеме, но не используется публичным create-flow**
   - `POST /api/support/tickets` и Telegram fallback создают тикет с `bookingId = null`, `listEntryId = null`.

2. **Смена статуса и reply не ограничены строгим workflow**
   - `setStatus` в репозитории допускает прямую установку переданного статуса без явного state-machine контроля переходов.

3. **Маршрутизация по клубу есть, но без проверки клуба на create**
   - В create endpoint используется переданный `clubId`; явной валидации «клуб существует/доступен пользователю» в route нет.

---

## 5) Краткий вывод

Саппорт-контур “Задать вопрос” в проекте реализован: есть категории, тикеты и сообщения, guest/admin API, RBAC-контроль доступа по клубу для админских действий, ответ админа с доставкой пользователю в Telegram и опциональное зеркалирование в ops-chat support thread. Основные расхождения со спецификацией Блока 10 — это статусная модель (другая терминология и набор состояний), отсутствие явной привязки к ночи и частичное использование связей с booking/list в публичном создании тикетов.
