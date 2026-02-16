# Q4 — Performance & Scalability review (пятничный пик)

## Executive summary
- Горячий путь availability (`/api/clubs/{clubId}/nights` и `/tables/free`) потенциально перегружается под polling, при этом базовый TTL-кэш в `AvailabilityService` частично нивелирован тем, что `DefaultAvailabilityService` переопределяет методы и ходит в БД напрямую. Ссылки: `core-domain/.../AvailabilityService.kt:63-73,77-95,100-152`, `core-data/.../DefaultAvailabilityService.kt:28-37,71-143`, `app-bot/.../AvailabilityApiRoutes.kt:39-75`.
- В Booking hold/confirm есть и идемпотентность, и уникальные ограничения, но остаются риски гонок/дубликатов в multi-instance из-за in-memory `BookingState` и локальных lock/idempotency map. Ссылки: `app-bot/.../booking/a3/BookingState.kt:44-49,68-95,104-113,206-213`, `core-data/.../BookingRepository.kt:446-519`, `core-data/.../db/migration/postgresql/V1__init.sql:136-150,174-179`.
- В host/table-операциях видны N+1/многократные запросы на путь `/api/admin/.../tables`: по каждой активной сессии отдельно читаются депозиты. На пике это повышает latency и DB CPU. Ссылки: `app-bot/.../AdminTableOpsRoutes.kt:144-154`, `core-data/.../TableSessionDepositRepositories.kt:334-340`.
- Outbox/notify имеет базовый backpressure (батчи, retry/backoff, rate policy), но есть узкие места: последовательная обработка батча одним worker loop, in-memory campaign state, и локальные rate/idempotency store без координации между инстансами. Ссылки: `app-bot/.../workers/OutboxWorker.kt:50-67,111-138,170-207`, `app-bot/.../telegram/NotifySender.kt:165-186,221-230,252-267`, `core-domain/.../notifications/RatePolicy.kt:107-174`, `app-bot/.../routes/NotifyRoutes.kt:67-93,115-124`.
- Для медиа (music) upload/read реализованы лимиты размера, но фактическая модель — `ByteArray` в памяти и в БД (без streaming-пайплайна), что даёт OOM/GC pressure в burst upload/download. Ссылки: `app-bot/.../routes/AdminMusicRoutes.kt:44-47,492-576`, `core-data/.../MusicAssetRepositoryImpl.kt:20-42,62-72`.
- Есть кэш рендера схемы зала с ETag, но это локальный in-process кэш: в multi-instance он не консистентен между pod'ами и не даёт shared warm cache. Ссылки: `app-bot/.../routes/HallRoutes.kt:32-41,51-59`, `app-bot/.../cache/HallRenderCache.kt:22-62,77-128`.

## Hot-paths: что самое тяжёлое и почему

### 1) Availability storm (Block 4)
- Часто дергаемые endpoint’ы:
  - `GET /api/clubs/{clubId}/nights` и `GET /api/clubs/{clubId}/nights/{startUtc}/tables/free`. `app-bot/.../AvailabilityApiRoutes.kt:39-75`.
- Почему тяжело:
  - Путь уходит в БД через `DefaultAvailabilityService` (поиск event + anti-join на bookings/holds) на каждый запрос. `core-data/.../DefaultAvailabilityService.kt:78-126`.
  - Кэш в базовом сервисе (`nightsCache`/`tablesCache`) определён, но в runtime-реализации методы переопределены, т.е. эта оптимизация не работает для основного DB-сервиса. `core-domain/.../AvailabilityService.kt:71-73,81-95,104-106,150-151`, `core-data/.../DefaultAvailabilityService.kt:37-40,71-74`.

### 2) HOLD → CONFIRM race (Block 4)
- Что есть:
  - Идемпотентность по ключу + проверка активных бронирований/hold’ов + retry-транзакции. `core-data/.../BookingRepository.kt:460-469,484-495,538-549`.
  - Уникальные ограничения/индексы в БД для active booking и idempotency. `core-data/.../db/migration/postgresql/V1__init.sql:144,168,174-179`.
- Что нагружает:
  - В in-memory `BookingState` конкурентный cleanup + локальные lock/idempotency map работают только в пределах процесса. `app-bot/.../booking/a3/BookingState.kt:44-57,347-419`.

### 3) Entry/check-in burst (Block 6)
- Что дергается часто:
  - `POST /api/host/checkin`, `POST /api/host/checkin/scan`, `GET /api/host/checkin/search`. `app-bot/.../HostCheckinRoutes.kt:105-205`.
- Риски:
  - Host search делает `listByClub(clubId)` и фильтрует по eventId в памяти перед поиском entries + поиск bookings by guestName с `lower(...) like %...%`. `app-bot/.../HostSearchService.kt:47-57,66-73`, `core-data/.../BookingRepository.kt:409-417`.
  - `%like%` по lower(name) без выделенного функционального индекса может становиться seq scan на крупном объёме.

### 4) Table ops path (Block 7)
- Горячая операция ночи:
  - `/api/admin/clubs/{clubId}/nights/{nightStartUtc}/tables`.
- Риск N+1:
  - На каждую active session вызывается `listDepositsForSession`, затем берётся max депозит. `app-bot/.../AdminTableOpsRoutes.kt:145-154`.

### 5) Broadcast/outbox (Block 12)
- Что есть:
  - Outbox worker c retry/backoff, batch limit, rate policy в sender. `app-bot/.../workers/OutboxWorker.kt:42-57,111-138`, `app-bot/.../telegram/NotifySender.kt:252-267`, `core-domain/.../notifications/RatePolicy.kt:123-174`.
- Узкие места:
  - Обработка батча последовательная (`batch.forEach`), без parallel consumer group и без cross-instance coordination в in-memory кампаниях/дедупе. `app-bot/.../workers/OutboxWorker.kt:55,65`, `app-bot/.../routes/NotifyRoutes.kt:67-93,115-124`, `app-bot/.../telegram/NotifySender.kt:63,165-169`.

### 6) Analytics generation (Block 13)
- Endpoint собирает 5+ агрегаций последовательно в одном request lifecycle и затем upsert story. `app-bot/.../AdminAnalyticsRoutes.kt:156-213,235-246`.
- Под пиком админских запросов это может быть дорогой read-heavy path.

### 7) Music upload/read path (Block 11)
- Upload читает multipart в `ByteArrayOutputStream`, затем кладёт целый `ByteArray` в DB BLOB-поле.
  - `app-bot/.../AdminMusicRoutes.kt:557-576`.
  - `core-data/.../MusicAssetRepositoryImpl.kt:30-37,62-67`.
- Это memory-heavy модель без stream-to-storage.

## Таблица проблем (P0/P1/P2)

| Prio | Симптом | Impact | Где | Рекомендация |
|---|---|---|---|---|
| P0 | Локальный in-memory booking state/locks/idempotency | На multi-instance возможны рассинхроны HOLD/confirm и разный view table occupancy между инстансами | `app-bot/.../booking/a3/BookingState.kt:44-49,86-95,206-213` | Перенести lock/idempotency в shared store (DB/Redis), оставить in-memory только как read-cache |
| P0 | Availability cache фактически не используется в DB-runtime сервисе | Высокий QPS polling => прямой рост DB нагрузки | `core-domain/.../AvailabilityService.kt:71-73`, `core-data/.../DefaultAvailabilityService.kt:37-40,71-78` | Либо использовать базовый кэш-путь, либо добавить явный кэш в `DefaultAvailabilityService` + invalidation hooks |
| P0 | Outbox batch обрабатывается последовательно | При burst рассылках растёт queue lag и tail latency доставки | `app-bot/.../workers/OutboxWorker.kt:52-56,61-66` | Несколько воркеров/партиционирование по topic/campaign + bounded parallelism |
| P1 | N+1 в `/tables` (депозиты по сессиям в цикле) | Рост DB round-trips и latency на вечерних дашбордах хоста | `app-bot/.../AdminTableOpsRoutes.kt:145-154` | Сводный репозиторный запрос “latest deposit per session” одним SQL |
| P1 | Host search `%like%` по lower(name) | На больших данных возможны full scan и деградация check-in поиска | `core-data/.../BookingRepository.kt:409-417` | trigram/GIN индекс или prefix-search/tsvector; отдельные индексы под phone/username |
| P1 | Music upload/read через ByteArray + DB blob | GC/OOM risk на параллельных загрузках/выдаче | `app-bot/.../AdminMusicRoutes.kt:557-576`, `core-data/.../MusicAssetRepositoryImpl.kt:62-67` | Stream upload/download + object storage/CDN, в БД хранить только метаданные |
| P1 | Локальный hall image cache | Низкий hit-rate и несогласованность cache при scale-out | `app-bot/.../cache/HallRenderCache.kt:22-62,85-128` | Shared cache (Redis) или CDN keying по ETag/version |
| P1 | Analytics endpoint делает серию тяжёлых чтений в request path | В часы пика админки может давать spikes p95/p99 | `app-bot/.../AdminAnalyticsRoutes.kt:156-213` | async precompute/materialized snapshots + short TTL cache |
| P2 | In-memory campaign service | Потеря состояния после рестарта, нет горизонтального scale state | `app-bot/.../routes/NotifyRoutes.kt:67-93` | Перенести campaigns в DB + optimistic locking |
| P2 | `forUpdate()` без `SKIP LOCKED` в notifications outbox pick | Потенциальная блокировка между конкурентными воркерами | `core-data/.../OutboxRepository.kt:58-67` | Добавить skip-locked/lease-claim модель |

## “Пятничный пик”: узкие места и деградации

### Что уже помогает
- DB-level uniqueness/индексы для booking/checkin инвариантов снижают риск дублей на записи. `core-data/.../db/migration/postgresql/V1__init.sql:174-179`, `core-data/.../db/migration/postgresql/V017__guest_list_invites_checkins.sql:82`, `core-data/.../db/migration/postgresql/V018__guest_list_schema_polish.sql:58-59`.
- Retry/backoff + rate policy в notify sender и outbox worker. `app-bot/.../workers/OutboxWorker.kt:111-138,170-207`, `app-bot/.../telegram/NotifySender.kt:221-230,252-267`.
- Hall image cache + ETag уменьшает повторную генерацию схемы в рамках одного инстанса. `app-bot/.../routes/HallRoutes.kt:54-59,63-77`.

### Где ожидаем bottleneck под пятницу
- Availability polling на мобильных клиентах (nights/free tables) из-за DB-bound path без эффективного shared cache.
- Host search и check-in фронт, особенно поиск по имени в момент массового входа.
- Outbox lag при массовых рассылках (кампании/уведомления) из-за последовательного consumer loop.
- Табличный view для менеджеров (`/tables`) из-за N+1 чтений депозитов.

### Деградации/fallbacks
- Есть fallback представления списка столов через API (`/tables/free`) независимо от рендера hall image, но нет явного автоматического оркестратора “если map fail → принудительно list mode” на backend-уровне. `app-bot/.../AvailabilityApiRoutes.kt:58-74`, `app-bot/.../routes/HallRoutes.kt:44-79`.

## Рекомендованные оптимизации (без внедрения)
1. **Availability snapshot cache (shared):** precompute free tables per (club,event) + Redis TTL 5–15 сек + watermark invalidation.
2. **Booking consistency hardening:** убрать критичную бизнес-координацию из in-memory `BookingState` в shared transactional layer.
3. **Host search optimization:** добавить денормализованный search index (name/phone/username) + лимит на expensive LIKE.
4. **Table ops anti-N+1:** единый SQL на активные сессии и последние депозиты.
5. **Outbox throughput:** многопоточный worker pool, claim/lease rows, partitioning по campaign/topic.
6. **Campaign persistence:** заменить in-memory `CampaignService` на DB-backed state machine.
7. **Music media pipeline:** stream в объектное хранилище + CDN, в БД только metadata/checksum.
8. **Analytics offload:** precompute “night 360” asynchronously (job) и отдавать cached snapshot.
9. **Multi-instance cache strategy:** shared cache для hall renders и availability.
10. **Performance guardrails:** budget-алерты по p95/p99, queue lag, DB slow query.

## Performance Test Plan (сценарии + метрики + критерии успеха)

### Метрики (минимум)
- API latency: p50/p95/p99 по endpoint’ам:
  - `/api/clubs/{clubId}/nights`
  - `/api/clubs/{clubId}/nights/{startUtc}/tables/free`
  - `/api/host/checkin/*`
  - `/api/admin/clubs/{clubId}/nights/{nightStartUtc}/tables`
- Error rate (4xx/5xx) по endpoint и по причине.
- DB: query count/request, avg/max query time, lock wait, deadlocks.
- Outbox: queue depth, enqueue rate, send rate, retry rate, lag до SENT.
- Telegram send: 429 rate, retryAfter distribution.
- JVM: heap usage, GC pause, allocation rate.

### Сценарии
1. **Availability storm**
   - 2k–5k RPS на nights/free tables при фиксированных 1–2 ночах.
   - Критерий: p95 < 250ms, error < 1%, DB CPU в допустимом бюджете.
2. **HOLD/confirm race**
   - Конкурентные HOLD/confirm на один и тот же стол/ночь.
   - Критерий: не более 1 активной брони, 0 инвариантных нарушений.
3. **Check-in burst (вход в клуб)**
   - 300–1000 req/min на scan/manual/search.
   - Критерий: p95 < 300ms, `CHECKIN_SUBJECT_NOT_FOUND` в ожидаемом коридоре, без дублей.
4. **Broadcast burst**
   - Кампания на N получателей (10k+), замер outbox drain time.
   - Критерий: стабильный лаг очереди, ограниченный % retry/fail, отсутствие runaway 429.
5. **Table ops polling**
   - Частые запросы `/tables` от нескольких менеджеров + операции seat/free/update deposit.
   - Критерий: p95 < 400ms, без деградации write path.
6. **Music upload stress (без destructive load)**
   - Параллельные upload файлов близко к лимиту.
   - Критерий: без OOM, предсказуемые 413/429/5xx, стабильный GC.

### Критерии readiness к пятничному пику
- p99 ключевых API в SLO, queue lag outbox < согласованного порога, и нет инвариантных нарушений по booking/checkin.
- Подтверждён failover сценарий multi-instance (без расхождения booking state).
- Наличие алертов и runbook на деградации availability/checkin/outbox.
