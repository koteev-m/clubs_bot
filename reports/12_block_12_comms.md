# Блок 12 (Коммуникации и рассылки) — аудит соответствия

## 1) Где реализованы рассылки и сегменты

## 1.1 Модель данных для рассылок

- В БД есть полноценные сущности коммуникаций:
  - `notify_segments` (JSON DSL сегмента),
  - `notify_campaigns` (status/kind/club/thread/schedule/segment),
  - расширенный `notifications_outbox` (recipient, dedup, priority, campaign, parse_mode, attachments).
  Источник: `V3__notify_init.sql`.【core-data/src/main/resources/db/migration/postgresql/V3__notify_init.sql:1-75】

- В коде есть Exposed-таблицы и репозитории для этих сущностей (`NotifySegments`, `NotifyCampaigns`, `UserSubscriptions`, `NotificationsOutboxTable` + соответствующие `*Repository`).【core-data/src/main/kotlin/com/example/bot/data/notifications/Tables.kt:9-77】【core-data/src/main/kotlin/com/example/bot/data/notifications/Repositories.kt:11-240】

- Есть DSL сегментации (`SegmentNode`) и отдельный `SegmentationRepository`, умеющий резолвить сегмент в набор `userId` по полям (`club_id`, `opt_in`, `lang`, `last_visit_days`, `is_vip`, `no_shows_ge`, bookings interval).【core-domain/src/main/kotlin/com/example/bot/notifications/Segmentation.kt:6-43】【core-data/src/main/kotlin/com/example/bot/data/repo/SegmentationRepository.kt:27-162】

## 1.2 API и runtime-контур рассылок

- Есть `NotifyRoutes` c `/api/notify/tx` и `/api/campaigns/*`, но реализация кампаний in-memory (`ConcurrentHashMap`) без персистентности и без привязки к `notify_campaigns`/`notify_segments` таблицам.【app-bot/src/main/kotlin/com/example/bot/routes/NotifyRoutes.kt:34-244】

- `CampaignScheduler` реализован (cron/startsAt/batch enqueue/progress/done), но работает через абстракцию `SchedulerApi`; реальной реализации `SchedulerApi` в проекте не найдено.
  Источник: worker + поиск реализаций.【app-bot/src/main/kotlin/com/example/bot/workers/CampaignScheduler.kt:35-190】【core-domain/src/main/kotlin/com/example/bot/notifications/SchedulerApi.kt:6-40】

- В `Application.module()` `notifyRoutes(...)` не подключён, т.е. `/api/campaigns` из `NotifyRoutes` не экспонируется в основном runtime-контуре приложения.
  Источник: wiring маршрутов в `Application.kt`.【app-bot/src/main/kotlin/com/example/bot/Application.kt:333-496】

## 1.3 Посты в канал / Telegram-контур

- Есть конфигурирование ops-чатов/тредов клуба через `/api/admin/ops-chats` (`chatId` + thread ids), и operational notifications publisher отправляет сообщения в эти чаты best-effort.
  Это контур операционных уведомлений, а не отдельный продуктовый контур “посты в канал” для маркетинговых публикаций.
  【app-bot/src/main/kotlin/com/example/bot/routes/AdminOpsChatsRoutes.kt:56-153】【app-bot/src/main/kotlin/com/example/bot/notifications/TelegramOperationalNotificationService.kt:165-333】

- В migration есть `hq_notify` и telegram endpoint-поля в `clubs`, но это инфраструктурная маршрутизация Telegram endpoint’ов, не редактор/планировщик постов в канал.
  【core-data/src/main/resources/db/migration/common/V6__telegram_notify_endpoints.sql:1-85】

## 1.4 Уведомления при изменении календаря

- Специализированного потока “calendar changed → notify guests/subscribers” не найдено:
  - в основном runtime нет CRUD-контура календаря с генерацией notify-событий;
  - нет отдельного доменного события/шаблона уведомления о смене расписания клуба.
- Наличие общих notification/outbox-абстракций подтверждено, но привязка именно к изменениям календаря в коде не обнаружена.

---

## 2) Лимиты и анти-спам

## 2.1 Лимиты отправки уведомлений (Telegram dispatch)

- В `NotifySender` есть rate-control и устойчивость к флуду:
  - глобальный и per-chat token bucket через `RatePolicy`;
  - реакция на `429` (`retry_after`) с блокировкой bucket’ов;
  - bounded retries/backoff/jitter, идемпотентность отправки через dedup store.
  【app-bot/src/main/kotlin/com/example/bot/telegram/NotifySender.kt:56-302】【core-domain/src/main/kotlin/com/example/bot/notifications/RatePolicy.kt:20-165】

- На HTTP-входе есть `RateLimitPlugin` (IP + subject limits, `429`, configurable path prefixes), что добавляет anti-spam на уровне API.
  【app-bot/src/main/kotlin/com/example/bot/plugins/RateLimitPlugin.kt:27-207】

## 2.2 Outbox/ретраи

- Для outbox worker реализован retry с экспоненциальным backoff+jitter и переносом `nextAttemptAt`.
  Это защищает от burst/fail-storm, но не заменяет бизнес-правил “тихих часов”.【app-bot/src/main/kotlin/com/example/bot/workers/OutboxWorker.kt:36-195】

## 2.3 Тихие часы

- Явной модели quiet hours (по клубу/по пользователю), политики отложенной отправки в “ночное окно” и их применения в notification pipeline не найдено.
- В коде есть только `Tags.QUIET_DAY`, но не обнаружено использования этого тега как механизма доставки коммуникаций.
  【core-domain/src/main/kotlin/com/example/bot/clubs/Tags.kt:1-6】

---

## 3) Контент-процессы (афиши/фото/сеты)

- Аффиши/контентные поля есть в данных (`events.poster_url`), а сеты/музыкальный контент загружаются и публикуются в music-модуле (`music_assets`, upload routes).
  【core-data/src/main/resources/db/migration/postgresql/V1__init.sql:62-70】【app-bot/src/main/kotlin/com/example/bot/routes/AdminMusicRoutes.kt:44-480】

- Есть `post_event_stories` + `guest_segments` для аналитических post-event артефактов.
  【core-data/src/main/resources/db/migration/postgresql/V041__post_event_stories_guest_segments.sql:1-25】【core-data/src/main/kotlin/com/example/bot/data/stories/PostEventStoryRepositories.kt:56-307】

- Но не найден единый контент-процесс “афиша/фото/сеты -> коммуникационная рассылка/постинг” (workflow, moderation queue, массовая публикация в канал/сегменты) как сквозной pipeline.

---

## 4) Матрица соответствия Блоку 12

## Реализовано

1. Технический фундамент для рассылок: outbox/campaign/segment/subscription таблицы.
2. Anti-spam/лимиты на уровне отправки в Telegram (`RatePolicy`, retry/backoff, dedup) и API rate-limit.
3. Ops-уведомления в Telegram чаты/треды (операционный контур).

## Частично

1. Ручные рассылки с сегментацией: есть модели/DSL/сервисные заготовки, но runtime-контур разрознен (in-memory campaign routes, отсутствует связка с persisted campaign/segment pipeline).
2. Контент-процессы: контентные сущности есть, но автоматизированной коммуникационной оркестрации вокруг них не найдено.

## Отсутствует

1. Уведомления при изменениях календаря как явный сценарий.
2. Продуктовый контур “посты в канал” (с API/планированием/аудиторией), отличный от ops chat notifications.
3. Тихие часы в notification delivery.

---

## 5) Риски

- **P1:** календарные изменения могут не доходить до гостей, что ведёт к no-show/конфликтам на входе.
- **P1:** отсутствие строгого рабочего контура сегментированных рассылок снижает управляемость коммуникаций и воспроизводимость кампаний.
- **P2:** без quiet-hours возможны отправки в неподходящее время и деградация UX/рост отписок.
