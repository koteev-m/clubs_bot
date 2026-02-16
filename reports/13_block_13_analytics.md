# Блок 13 (Полная статистика) — аудит соответствия

## 1) Какие метрики реально собираются

## 1.1 Основной analytics API (night-card)

- В проекте есть единый admin endpoint `GET /api/admin/clubs/{clubId}/analytics`.
- Он собирает и возвращает агрегаты по ночи:
  - `attendance` (по каналам direct/promoter/guest-list),
  - `visits` (уникальные, early, table nights),
  - `deposits` (total + allocation summary),
  - `shift` (люди + total revenue из смены),
  - `segments` (NEW/FREQUENT/SLEEPING).
- Также есть история generated карточек: `GET /stories` и `GET /stories/{nightStartUtc}`.
  Источник: `AdminAnalyticsRoutes`.【app-bot/src/main/kotlin/com/example/bot/routes/AdminAnalyticsRoutes.kt:42-355】

## 1.2 Источники/метрики по доменам

- **Брони/столы:** occupancy и seat-load считаются из `BookingState` + `LayoutRepository` в owner health, плюс депозитные агрегаты в `TableDepositRepository`.
  Источник: `OwnerHealthServiceImpl`, `AdminAnalyticsRoutes`.【app-bot/src/main/kotlin/com/example/bot/owner/OwnerHealthServiceImpl.kt:25-229】【app-bot/src/main/kotlin/com/example/bot/routes/AdminAnalyticsRoutes.kt:176-190】

- **Вход:** `VisitRepository` хранит и агрегирует `club_visits` (unique visitors, early flags, has_table).
  Источник: `VisitRepositories.kt`.【core-data/src/main/kotlin/com/example/bot/data/visits/VisitRepositories.kt:110-347】

- **Промо:** есть отдельная аналитика промоутеров (`/api/promoter/scorecard`, `/api/admin/promoters/rating`) и сервис расчёта conversion/no-show.
  Источник: `PromoterRatingRoutes`, `PromoterRatingService`.【app-bot/src/main/kotlin/com/example/bot/routes/PromoterRatingRoutes.kt:59-173】【core-domain/src/main/kotlin/com/example/bot/promoter/rating/PromoterRatingService.kt:11-208】

- **Финансы:** shift report хранит людей/браслеты/выручку и вычисляет total revenue; `AdminAnalyticsRoutes` подтягивает shift summary по ночи.
  Источник: `ShiftReportRepositories`, `AdminAnalyticsRoutes`.【core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportRepositories.kt:48-475】【app-bot/src/main/kotlin/com/example/bot/routes/AdminAnalyticsRoutes.kt:192-207】

- **Геймификация:** доступны агрегаты через visits и `guest_segments` (NEW/FREQUENT/SLEEPING), а также отдельные gamification repos/settings.
  Источник: `GuestSegmentsRepository`, gamification tables/repos. 【core-data/src/main/kotlin/com/example/bot/data/stories/PostEventStoryRepositories.kt:202-293】【core-data/src/main/kotlin/com/example/bot/data/gamification/GamificationRepositories.kt:1-385】

- **Музыка:** есть лайки/голоса баттлов и fan ranking (`/api/music/fans/ranking`), но это отдельный API-контур, не встроенный в night analytics payload.
  Источник: `MusicBattleRoutes`, `MusicBattleService`.【app-bot/src/main/kotlin/com/example/bot/routes/MusicBattleRoutes.kt:162-196】【app-bot/src/main/kotlin/com/example/bot/music/MusicBattleService.kt:49-81】

- **Коммуникации:** есть outbox/campaign/segment инфраструктура и rate-limit telemetry, но в admin analytics payload для ночи эти показатели не агрегируются.
  Источник: notifications tables/routes + metrics binder. 【core-data/src/main/kotlin/com/example/bot/data/notifications/Tables.kt:9-77】【app-bot/src/main/kotlin/com/example/bot/routes/NotifyRoutes.kt:147-244】【app-bot/src/main/kotlin/com/example/bot/metrics/AppMetricsBinder.kt:11-43】

- **Саппорт:** есть тикеты/статусы/сообщения, но отдельного блока support KPI внутри night 360 payload сейчас нет.
  Источник: `SupportRoutes`, `SupportRepository`.【app-bot/src/main/kotlin/com/example/bot/routes/SupportRoutes.kt:127-499】【core-data/src/main/kotlin/com/example/bot/data/support/SupportRepository.kt:25-412】

## 1.3 Технические telemetry-метрики

- Через Micrometer реально публикуются UI и rate-limit счётчики/таймеры:
  - booking/check-in UX counters + durations,
  - rate-limit gauges.
  Источник: `UiBookingMetrics`, `UiCheckinMetrics`, `AppMetricsBinder`.【app-bot/src/main/kotlin/com/example/bot/metrics/UiBookingMetrics.kt:8-148】【app-bot/src/main/kotlin/com/example/bot/metrics/UiCheckinMetrics.kt:13-219】【app-bot/src/main/kotlin/com/example/bot/metrics/AppMetricsBinder.kt:11-43】

---

## 2) Где события и агрегации

## 2.1 Night card 360

- `AdminAnalyticsRoutes` выполняет runtime-агрегацию из нескольких репозиториев/сервисов (owner health, visits, deposits, shift report, segments), формирует `AnalyticsResponse`, и сохраняет snapshot как `post_event_stories` (schema versioned).
  【app-bot/src/main/kotlin/com/example/bot/routes/AdminAnalyticsRoutes.kt:136-257】【core-data/src/main/kotlin/com/example/bot/data/stories/PostEventStoryRepositories.kt:89-200】

- В payload есть `meta.caveats` и `hasIncompleteData` для деградирующих источников.
  Это снижает риск «тихой» потери данных.
  【app-bot/src/main/kotlin/com/example/bot/routes/AdminAnalyticsRoutes.kt:147-248】

## 2.2 Агрегирующие сервисы/репозитории

- Owner health агрегирует events + layouts + bookings + guest lists в tables/attendance/promoter/trend/alerts/breakdowns.
  【app-bot/src/main/kotlin/com/example/bot/owner/OwnerHealthServiceImpl.kt:33-380】【core-domain/src/main/kotlin/com/example/bot/owner/OwnerHealthModels.kt:7-295】

- Visits/segments/shift/deposits считаются в выделенных репозиториях и подмешиваются в night-card.
  【core-data/src/main/kotlin/com/example/bot/data/visits/VisitRepositories.kt:110-347】【core-data/src/main/kotlin/com/example/bot/data/stories/PostEventStoryRepositories.kt:202-293】【core-data/src/main/kotlin/com/example/bot/data/finance/ShiftReportRepositories.kt:48-475】【core-data/src/main/kotlin/com/example/bot/data/booking/TableSessionDepositRepositories.kt:155-335】

---

## 3) Что отсутствует / частично

## 3.1 Дашборды по всем требуемым направлениям

### Реализовано частично
- Есть сильная база для блоков: брони/столы/вход/промо/финансы + сегменты.
- Есть отдельные контуры музыки, коммуникаций и саппорта.

### Отсутствует как единый “полный дашборд”
- Нет единого API/DTO, который в одном месте покрывает все обязательные домены Блока 13:
  `брони/столы/вход/промо/финансы/геймификация/музыка/коммуникации/саппорт`.
- `AdminAnalyticsRoutes` сейчас night-centric и не включает полноценные KPI музыки/коммуникаций/саппорта.

## 3.2 Карточка ночи 360°

- **Есть:** `GET /api/admin/clubs/{clubId}/analytics` + story snapshots (`post_event_stories`).
- **Ограничение:** часть доменов 360 покрыта косвенно/внешними API, а не в единой карточке.

## 3.3 Экспорт

- В контуре аналитики (`/api/admin/clubs/{clubId}/analytics`, `/stories`) экспорт (CSV/XLSX/PDF) не найден.
- В проекте есть CSV export для guest list, но это отдельный операционный контур, не экспорт BI/analytics.
  【app-bot/src/main/kotlin/com/example/bot/routes/GuestListRoutes.kt:92-111】

## 3.4 Авто-отчёты

- Есть планировщик кампаний (`CampaignScheduler`), но он относится к коммуникациям и требует `SchedulerApi` реализацию.
- Специализированного scheduler/job для периодической генерации и рассылки analytics reports не найдено.
  【app-bot/src/main/kotlin/com/example/bot/workers/CampaignScheduler.kt:35-190】【core-domain/src/main/kotlin/com/example/bot/notifications/SchedulerApi.kt:6-40】

---

## 4) Итоговая оценка соответствия Блоку 13

1. **Карточка ночи 360°:** `PARTIAL` (ядро реализовано, но не все домены в одном payload).
2. **Полные дашборды по всем направлениям:** `PARTIAL` (функции распределены по разным API/модулям).
3. **Экспорт аналитики:** `NONE` (в analytics-контуре не найден).
4. **Авто-отчёты:** `NONE/PARTIAL` (есть scheduler-инфраструктура для кампаний, но не для analytics отчётов).

## Риски

- **P1:** отсутствие единой cross-domain витрины KPI затрудняет управленческие решения “в одной панели”.
- **P1:** отсутствие export в analytics-контуре блокирует регламентную отчётность без ручных шагов.
- **P2:** отсутствие авто-отчётов повышает вероятность пропуска критичных сигналов смены/ночи.
