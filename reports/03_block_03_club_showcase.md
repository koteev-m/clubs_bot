# Блок 3 (Витрина клуба) — аудит соответствия

## 1) Экран/команды и callback’и в боте, mini app маршруты

### Telegram-бот (меню/кнопки/callback’и)

- В основном guest-меню есть кнопки:
  - `Выбрать клуб` (`menu:clubs`),
  - `Мои бронирования` (`menu:bookings`),
  - `Задать вопрос` (`menu:ask`),
  - `Музыка` (`menu:music`).
  Источник: `Keyboards.startMenu(...)` и `BotTexts.menu(...)`. 【app-bot/src/main/kotlin/com/example/bot/telegram/Keyboards.kt:22-37】【app-bot/src/main/kotlin/com/example/bot/text/BotTexts.kt:16-28】

- В `MenuCallbacksHandler` реально обрабатываются только:
  - `menu:clubs` (показ списка клубов),
  - `menu:bookings`,
  - дальше ветки `club:*` -> выбор ночи, `night:*` -> выбор стола и т.д.
  При этом в handler-константах отсутствуют `menu:ask`/`menu:music`. 【app-bot/src/main/kotlin/com/example/bot/telegram/MenuCallbacksHandler.kt:73-100】【app-bot/src/main/kotlin/com/example/bot/telegram/MenuCallbacksHandler.kt:1095-1098】

- Список клубов в callback-боте строится как `name + shortDescription` (если есть), без адреса/афиш/фото/музыки на карточке клуба. 【app-bot/src/main/kotlin/com/example/bot/telegram/MenuCallbacksHandler.kt:1041-1060】

- CTA “Задать вопрос” работает через fallback-команду `/ask` и callback `ask:club:{id}` в `TelegramGuestFallbackHandler` (создание тикета в support). 【app-bot/src/main/kotlin/com/example/bot/telegram/TelegramGuestFallbackHandler.kt:73-75】【app-bot/src/main/kotlin/com/example/bot/telegram/TelegramGuestFallbackHandler.kt:131-155】【app-bot/src/main/kotlin/com/example/bot/telegram/TelegramGuestFallbackHandler.kt:157-191】

- В `Application` в `TelegramCallbackRouter` подключены support/invitation/guestFallback хэндлеры; `MenuCallbacksHandler` в wiring не используется (не найдено подключения в runtime-маршрутизации callback’ов). 【app-bot/src/main/kotlin/com/example/bot/Application.kt:312-317】

### Mini App / HTTP API

- Публичная mini-app ветка витрины:
  - `GET /api/clubs` — список клубов,
  - `GET /api/events` — события/ночи по фильтрам.
  Маршруты защищены `withMiniAppAuth`. 【app-bot/src/main/kotlin/com/example/bot/routes/ClubsRoutes.kt:88-91】【app-bot/src/main/kotlin/com/example/bot/routes/ClubsRoutes.kt:91-192】【app-bot/src/main/kotlin/com/example/bot/routes/ClubsRoutes.kt:193-243】

- Дополнительно есть guest-flow endpoint `GET /clubs/{clubId}/nights` (список ночей). 【app-bot/src/main/kotlin/com/example/bot/routes/GuestFlowRoutes.kt:14-20】

- Для “Задать вопрос” в mini app реализован support API (`/api/support/tickets`, сообщения, админ-ветка тикетов). 【app-bot/src/main/kotlin/com/example/bot/routes/SupportRoutes.kt:127-200】【app-bot/src/main/kotlin/com/example/bot/routes/SupportRoutes.kt:248-275】

- Для “Музыка” есть публичные маршруты `/api/music/*` (items/sets/playlists/mixtape). 【app-bot/src/main/kotlin/com/example/bot/routes/MusicRoutes.kt:114-203】【app-bot/src/main/kotlin/com/example/bot/routes/MusicRoutes.kt:206-237】

---

## 2) Контентные сущности витрины (events, posters, photo reports)

### Clubs / профиль клуба

- В доменной модели `Club` для guest API есть: `city`, `name`, `genres`, `tags`, `logoUrl`.
- В `ClubDto` публичного `/api/clubs` нет `description` и нет `address`. 【core-domain/src/main/kotlin/com/example/bot/clubs/ClubsModels.kt:5-12】【app-bot/src/main/kotlin/com/example/bot/routes/ClubsRoutes.kt:41-49】

- В БД таблица `clubs` содержит `description`, `timezone`, но отдельного поля адреса (`address`) нет. 【core-data/src/main/resources/db/migration/postgresql/V1__init.sql:6-18】
- Exposed-модель `Clubs` также не содержит `address`. 【core-data/src/main/kotlin/com/example/bot/data/db/Tables.kt:8-19】

### Events / афиши

- Таблица `events` содержит `title`, `start_at/end_at`, `is_special`, `poster_url` (афиша). 【core-data/src/main/resources/db/migration/postgresql/V1__init.sql:62-70】
- Но в доменной `Event` и API `EventDto` поля `posterUrl` нет, т.е. афиша из БД в витрину не выводится этим путём. 【core-domain/src/main/kotlin/com/example/bot/clubs/ClubsModels.kt:14-21】【app-bot/src/main/kotlin/com/example/bot/routes/ClubsRoutes.kt:52-59】

### Фото-репорты

- Явной сущности “photo reports” для витрины клуба не найдено.
- Есть `post_event_stories` (JSON payload аналитической карточки ночи), но это не витринные фото-отчёты в явной модели Блока 3. 【core-data/src/main/resources/db/migration/postgresql/V041__post_event_stories_guest_segments.sql:1-13】

### Музыка

- Контент музыки реализован отдельным модулем `/api/music/*` и админ-редактированием `/api/admin/music/items*`.
- В `MusicSetDto`/выдаче есть `coverUrl` и `audioUrl`, что подходит для витринного музыкального блока. 【app-bot/src/main/kotlin/com/example/bot/routes/MusicRoutes.kt:136-159】【app-bot/src/main/kotlin/com/example/bot/routes/MusicRoutes.kt:222-233】【app-bot/src/main/kotlin/com/example/bot/routes/AdminMusicRoutes.kt:128-159】

---

## 3) Где хранятся и как редактируются данные клуба (конфиг/БД/админка)

- Основное хранение — БД `clubs`/`events` и связанные таблицы. 【core-data/src/main/resources/db/migration/postgresql/V1__init.sql:6-76】
- Guest read-модель клубов/событий:
  - `ClubsDbRepository`, `EventsDbRepository`. 【core-data/src/main/kotlin/com/example/bot/data/clubs/GuestClubsRepository.kt:18-86】【core-data/src/main/kotlin/com/example/bot/data/clubs/GuestClubsRepository.kt:90-170】
- В админке редактирование клуба сейчас ограничено полями `name/city/isActive` (без description/logo/tags/genres/timezone/address).
  - REST: `POST/PATCH/DELETE /api/admin/clubs`.
  - DB repo: create/update только перечисленных полей. 【app-bot/src/main/kotlin/com/example/bot/routes/AdminClubsRoutes.kt:70-161】【core-data/src/main/kotlin/com/example/bot/data/admin/AdminClubsDbRepository.kt:45-55】【core-data/src/main/kotlin/com/example/bot/data/admin/AdminClubsDbRepository.kt:80-90】

---

## 4) Что отсутствует / не доделано относительно Блока 3

1. **Карточка клуба в API/боте неполная**:
   - нет поля адреса;
   - нет явного поля описания в основном `/api/clubs` DTO;
   - нет агрегации “описание + адрес + афиши + фото + музыка” в одном club card endpoint.

2. **Афиши частично не доведены до витрины**:
   - `poster_url` есть в БД `events`, но не проходит в `EventDto` публичного API витрины.

3. **Фото-репорты как отдельная витринная сущность не обнаружены**:
   - есть post-event stories (аналитика), но нет явного photo reports контента/эндпоинтов для гостя.

4. **CTA в Telegram-меню частично несвязаны с callback handler**:
   - в `startMenu` есть `menu:ask` и `menu:music`, но в `MenuCallbacksHandler` эти ветки не обрабатываются.

5. **Редактирование контента клуба в админке ограничено**:
   - нет редактирования address/description/logo/tags/genres/timezone через `AdminClubsRoutes`.

6. **Список клубов в `/api/clubs` имеет неочевидную семантику**:
   - без фильтров (`city/tag/genre/q/date`) возвращается пустой список (из-за `hasFilters`), что не соответствует типичному ожиданию “список клубов витрины”. 【app-bot/src/main/kotlin/com/example/bot/routes/ClubsRoutes.kt:105-109】

