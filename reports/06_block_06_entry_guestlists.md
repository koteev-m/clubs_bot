# Аудит Блока 6 — Вход и гостевые списки

## 1) Где хранятся guestlists, invites, check-in events

## 1.1 Схема/таблицы

Основные сущности Блока 6 находятся в таблицах:
- `guest_lists` — список гостей (club/event/owner/arrival window/status).
- `guest_list_entries` — записи гостей (ФИО, @username, телефон, telegram_user_id, статусы).
- `invitations` — инвайты с `token_hash`, каналом, `expires_at`, `revoked_at`, `used_at`.
- `checkins` — события прохода (`subject_type`, `subject_id`, `result_status`, `deny_reason`, `method`).

Ссылки:
- `core-data/src/main/kotlin/com/example/bot/data/club/GuestListTables.kt:7-71`
- `core-data/src/main/resources/db/migration/postgresql/V017__guest_list_invites_checkins.sql:54-83`
- `core-data/src/main/resources/db/migration/postgresql/V018__guest_list_schema_polish.sql:29-59`

## 1.2 Репозитории/сервисы

- Guest list CRUD/поиск/импорт: `GuestListRepositoryImpl`, `GuestListServiceImpl`.
- Инвайты: `InvitationServiceImpl` + `InvitationDbRepository`.
- Check-in (QR/manual/host scan): `CheckinServiceImpl` + `CheckinDbRepository`.

Ссылки:
- `core-data/src/main/kotlin/com/example/bot/data/club/GuestListRepositoryImpl.kt:36-421`
- `core-data/src/main/kotlin/com/example/bot/data/club/GuestListServiceImpl.kt:26-220`
- `core-data/src/main/kotlin/com/example/bot/data/club/InvitationServiceImpl.kt:31-232`
- `core-data/src/main/kotlin/com/example/bot/data/club/GuestListRepositories.kt:424-828`
- `core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt:51-1190`

---

## 2) Критичные требования Блока 6

## 2.1 Промо создаёт GuestList (paste list + add one by one)

### Реализовано
- Создание списка: `POST /api/promoter/guest-lists`.
- Массовое добавление (paste bulk text): `POST /api/promoter/guest-lists/{id}/entries/bulk`.
- По одному: `POST /api/promoter/guest-lists/{id}/entries`.
- На сервисном уровне bulk-парсинг с дедупликацией по нормализованному имени и проверкой capacity.

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/routes/PromoterGuestListRoutes.kt:628-759`
- `core-data/src/main/kotlin/com/example/bot/data/club/GuestListServiceImpl.kt:69-129`

### Вывод
- Требование “paste list + add one by one” **реализовано**.

## 2.2 Приглашения: внутренние и внешние

### Что есть
- Каналы инвайта в домене: `TELEGRAM` и `EXTERNAL`.
- При создании инвайта возвращаются и deeplink (`https://t.me/<bot>?start=inv_<token>`), и QR payload (`inv:<token>`).
- Telegram-поток принятия `/start inv_<token>` и callbacks confirm/decline реализован.

Ссылки:
- `core-domain/src/main/kotlin/com/example/bot/club/GuestListRepository.kt:178-181`
- `core-data/src/main/kotlin/com/example/bot/data/club/InvitationServiceImpl.kt:40-99,174-176`
- `app-bot/src/main/kotlin/com/example/bot/telegram/InvitationTelegramHandler.kt:50-74,156-213`

### Ограничения/пробелы
- В promoter API список инвайтов генерируется принудительно как `EXTERNAL` (канал не выбирается, Telegram-инвайт отдельно не оркестрирован).
  - `PromoterGuestListRoutes` внутри `GET /guest-lists/{id}/invitations` вызывает `createInvitation(..., channel = EXTERNAL, ...)`.
- Для `POST /guest-lists/{id}/entries/{entryId}/invitation` канал передаётся, но нет отдельного бизнес-сценария “внутреннее приглашение только если пользователь уже запускал бота” (нет явной проверки по `telegram_user_id` перед выдачей TELEGRAM-channel).

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/routes/PromoterGuestListRoutes.kt:413-439`
- `app-bot/src/main/kotlin/com/example/bot/routes/PromoterGuestListRoutes.kt:761-795`
- `core-data/src/main/kotlin/com/example/bot/data/club/InvitationServiceImpl.kt:118-121`

### Вывод
- Внешние приглашения (deeplink+QR) — **реализованы**.
- Внутренние приглашения “только для уже известных боту гостей” — **частично** (тип канала есть, но обязательное правило явно не зафиксировано в бизнес-валидации).

## 2.3 Check-in по одному Night Pass (QR), защита от дублей/повторов/пересылки

### Что реализовано
1. **Один check-in на subject** обеспечен БД и сервисом:
   - `UNIQUE (subject_type, subject_id)` в `checkins`;
   - до вставки выполняется `findBySubject`, при гонке unique violation трактуется как already used.
2. **Инвайт-token одноразовый**:
   - `insertWithEntryUpdate` сначала делает `Invitations.used_at = now` при `used_at is null and revoked_at is null`;
   - если обновления 0, чек-ин не проводится (token уже использован/отозван).
3. **Проверка срока и подписи QR/токена**:
   - `InvitationService.resolveInvitation` проверяет revoked/used/expired;
   - booking QR проверяется через `QrBookingCodec.verify` + TTL + секрет/oldSecret.
4. **Scope проверка** (club/event mismatch -> deny) в host check-in.

Ссылки:
- `core-data/src/main/resources/db/migration/postgresql/V017__guest_list_invites_checkins.sql:70-83`
- `core-data/src/main/kotlin/com/example/bot/data/club/GuestListRepositories.kt:672-693,789-804`
- `core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt:218-220,252-256,396-410,421-445,747-797`
- `core-data/src/main/kotlin/com/example/bot/data/club/InvitationServiceImpl.kt:145-158`

### Ограничения/пробелы
- Специфический инвариант “один Night Pass QR на ночь для пользователя” в guest-list контуре явно не централизован как отдельная гарантия. Есть сильная защита от повторного использования конкретного subject/token, но не видно общего агрегатного правила “нельзя иметь 2 валидных QR на ту же ночь для одного гостя” на уровне модели/индекса.
- Защита от **пересылки** опирается на одноразовость токена и факт первого использования (последующие попытки -> already used), но нет привязки токена к устройству/чату.

### Вывод
- Защита от дублей/повторов — **реализована хорошо**.
- Анти-пересылка в строгом виде (device-bound) — **частично**.

## 2.4 Статусы ARRIVED/DENIED(/LATE/SEATED)

### Что есть
- Для check-in доменный `CheckinResultStatus`: `ARRIVED`, `LATE`, `DENIED`.
- Для guest list entries: есть `ARRIVED/LATE/DENIED` (+ `CHECKED_IN/NO_SHOW/...`).
- Для booking при check-in происходит маппинг:
  - ARRIVED/LATE -> booking `SEATED`
  - DENIED -> booking `NO_SHOW`

Ссылки:
- `core-domain/src/main/kotlin/com/example/bot/club/GuestListRepository.kt:85-106,193-197`
- `core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt:976-993`

### Вывод
- Требование по статусам — **частично**:
  - ARRIVED/DENIED/LATE — есть напрямую;
  - SEATED присутствует как booking-status (результат check-in брони), но не как checkin-result-status.

## 2.5 Причины отказов + аналитика

### Реализовано
- Для DENY обязательна причина в host/manual flow (`CHECKIN_DENY_REASON_REQUIRED`).
- `checkins` хранит `deny_reason` и constraint консистентности (`DENIED -> reason required`).
- В `HostCheckinOutcome` и HTTP-ответе возвращается `denyReason`.
- Есть аналитический срез входа (HostEntrance) и метрики check-in (`UiCheckinMetrics`).

Ссылки:
- `core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt:103-105,301-303,356-358`
- `core-data/src/main/resources/db/migration/postgresql/V018__guest_list_schema_polish.sql:40-55`
- `app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt:67-74,228-236`
- `app-bot/src/main/kotlin/com/example/bot/routes/HostEntranceRoutes.kt:25-60`
- `app-bot/src/main/kotlin/com/example/bot/metrics/UiCheckinMetrics.kt:70-174`

### Вывод
- “Причины отказов + аналитика” — **реализовано** (с оговоркой: часть аналитики агрегатная и не 360°).

## 2.6 Поиск по ФИО/@username/тел

### Что есть
- Поиск host endpoint: `/api/host/checkin/search`.
- Guest list search по имени и телефону поддерживается в `GuestListRepositoryImpl`.
- Booking search в HostSearchService ищет только по `guestName` (LIKE).

Ссылки:
- `app-bot/src/main/kotlin/com/example/bot/routes/HostCheckinRoutes.kt:182-202`
- `core-data/src/main/kotlin/com/example/bot/data/club/GuestListRepositoryImpl.kt:341-350`
- `app-bot/src/main/kotlin/com/example/bot/host/HostSearchService.kt:55-68`
- `core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingRepository.kt:398-433`

### Пробелы
- Поиск по `@username` фактически не реализован в HostSearchService (в запросах не используется `tgUsername`).
- Booking часть не ищет по телефону.

### Вывод
- “ФИО/тел” — **частично реализовано**.
- “@username” — **не реализовано явно**.

---

## 3) Где именно хранятся события/факты для Блока 6

- Guest lists / entries: `guest_lists`, `guest_list_entries` (+ соответствующие репозитории).
- Invites: `invitations` (token_hash, channel, expires/revoked/used).
- Check-ins: `checkins` (метод, статус, deny_reason, subject).
- Дополнительно в audit пишутся события check-in (`visitCheckedIn`).

Ссылки:
- `core-data/src/main/kotlin/com/example/bot/data/club/GuestListTables.kt:7-71`
- `core-data/src/main/kotlin/com/example/bot/data/club/GuestListRepositories.kt:424-828`
- `core-data/src/main/kotlin/com/example/bot/data/checkin/CheckinServiceImpl.kt:567-577,716-727,861-871,947-957`

---

## 4) Что не реализовано / реализовано частично

## Missing
1. Явное правило “внутренний invite только если гость уже запускал бота” как строгая серверная валидация при создании инвайта.
2. Явный unified-инвариант “один Night Pass QR на ночь на пользователя” на уровне guest-list token lifecycle (вместо гарантий на subject/token).
3. Поиск по `@username` в host search.

## Partial
1. Анти-пересылка QR: защищено одноразовостью/уникальностью, но нет device-binding/recipient-binding.
2. Поиск по телефону реализован для guest-list entries, но booking search по телефону нет.
3. Статус SEATED покрыт в booking-домене как эффект check-in, а не как статус checkin-result.

## Implemented
1. Создание guest-list промоутером + bulk paste + add one by one.
2. Deeplink + QR инвайты.
3. Антидубль check-in (pre-check + DB unique + idempotent handling on race).
4. Обязательные deny reasons и их хранение.

---

## 5) Краткий итог по Блоку 6

Блок 6 покрыт **в значительной степени**: гостевые списки, инвайты, check-in, базовые антидубли и deny-reason присутствуют и поддерживаются БД-ограничениями и сервисной логикой. Главные пробелы относительно спецификации — строгая бизнес-валидация “internal invite only for known bot users”, полнота поиска по `@username`, и более строгая трактовка инварианта “один Night Pass QR на ночь” именно как пользовательского правила, а не только через uniqueness по subject/token.
