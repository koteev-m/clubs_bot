# Блок 11 (Музыка и DJ) — аудит соответствия

## 1) Где и как реализованы музыка/DJ (файлы, метаданные, публикация)

## 1.1 Хранение данных и файлов

- Базовая модель музыкального каталога есть в БД:
  - `music_items` (трек/сет, `source_type`, `source_url`, `telegram_file_id`, `published_at`),
  - `music_playlists` + `music_playlist_items`,
  - `music_likes`.
  Источник: `V5__music_init.sql`.【core-data/src/main/resources/db/migration/postgresql/V5__music_init.sql:5-70】

- Хранение бинарных файлов реализовано через `music_assets` (байты + `content_type` + `sha256` + `size_bytes`) и связи в `music_items.audio_asset_id/cover_asset_id`.
  Источник: `V032__music_assets.sql`.【core-data/src/main/resources/db/migration/postgresql/V032__music_assets.sql:1-46】

- В домене явно описаны `MusicAsset`, `MusicAssetMeta`, `MusicSource`, `MusicItemType`, `Playlist*`.
  Источник: `core-domain/.../music/Models.kt`.【core-domain/src/main/kotlin/com/example/bot/music/Models.kt:5-178】

## 1.2 Загрузка треков/сетов файлами

- Загрузка файлов есть в админском API:
  - `PUT /api/admin/music/items/{id}/audio` (multipart),
  - `PUT /api/admin/music/items/{id}/cover` (multipart).
  Источник: `AdminMusicRoutes`.【app-bot/src/main/kotlin/com/example/bot/routes/AdminMusicRoutes.kt:348-480】

- Ограничения и валидация на upload есть:
  - лимиты: аудио 50MB, обложка 5MB,
  - allowlist content-type,
  - чтение потока с hard-limit и расчётом SHA-256,
  - reject пустого файла.
  Источник: `AdminMusicRoutes`.【app-bot/src/main/kotlin/com/example/bot/routes/AdminMusicRoutes.kt:44-60】【app-bot/src/main/kotlin/com/example/bot/routes/AdminMusicRoutes.kt:492-576】

- После upload файл сохраняется в `music_assets`, затем привязывается к item через `attachAudioAsset/attachCoverAsset`.
  Источник: `AdminMusicRoutes`.【app-bot/src/main/kotlin/com/example/bot/routes/AdminMusicRoutes.kt:374-386】【app-bot/src/main/kotlin/com/example/bot/routes/AdminMusicRoutes.kt:437-449】

## 1.3 Публикация/модерация

- Модель модерации сейчас фактически сводится к `draft/published` через `published_at`:
  - publish: `POST /api/admin/music/items/{id}/publish`,
  - unpublish: `POST /api/admin/music/items/{id}/unpublish`.
  Источник: `AdminMusicRoutes`.【app-bot/src/main/kotlin/com/example/bot/routes/AdminMusicRoutes.kt:297-345】

- Публичная выдача ассетов `/api/music/items/{id}/audio|cover` возвращает данные **только** для опубликованных item.
  Источник: `MusicRoutes`.【app-bot/src/main/kotlin/com/example/bot/routes/MusicRoutes.kt:45-55】【app-bot/src/main/kotlin/com/example/bot/routes/MusicRoutes.kt:79-89】

- Отдельных статусов модерации (например, `PENDING/REJECTED`), очередей ревью и reason-полей по отклонению не найдено.

---

## 2) Плейлисты, избранное, “главный трек ночи”, реакции/голосования

## 2.1 Плейлисты

- Плейлисты в модели и read API есть:
  - `GET /api/music/playlists`,
  - `GET /api/music/playlists/{id}`.
  Источник: `MusicRoutes`, `MusicService`, `MusicPlaylistRepository`.
  【app-bot/src/main/kotlin/com/example/bot/routes/MusicRoutes.kt:162-204】
  【app-bot/src/main/kotlin/com/example/bot/music/MusicService.kt:92-147】
  【core-domain/src/main/kotlin/com/example/bot/music/Repositories.kt:56-77】

- В текущем `app-bot` не найден отдельный admin-route для CRUD плейлистов (есть репозиторий, но не найдено HTTP wiring на создание/редактирование).
  Поиск: `rg -n "MusicPlaylistRepository|/playlists" app-bot/src/main/kotlin`.【app-bot/src/main/kotlin/com/example/bot/routes/MusicRoutes.kt:162-204】【app-bot/src/main/kotlin/com/example/bot/routes/TrackOfNightRoutes.kt:27-64】

## 2.2 Избранное / лайки

- “Избранное” реализовано как лайки сетов:
  - `POST /api/music/items/{id}/like`,
  - `DELETE /api/music/items/{id}/like`.
  Источник: `MusicLikesRoutes`.【app-bot/src/main/kotlin/com/example/bot/routes/MusicLikesRoutes.kt:78-109】

- Защита от накрутки дублями есть на уровне БД/репозитория:
  - PK/unique `(item_id, user_id)` в `music_likes`,
  - `insertIgnore` при лайке (идемпотентно).
  Источник: migration + repository impl.【core-data/src/main/resources/db/migration/postgresql/V5__music_init.sql:63-67】【core-data/src/main/kotlin/com/example/bot/data/music/MusicLikesRepositoryImpl.kt:24-34】

## 2.3 “Главный трек ночи”

- Реализован отдельным модулем `track-of-night`:
  - таблица `music_track_of_night`,
  - endpoint `POST /api/music/sets/{setId}/track-of-night` (manager/admin роли).
  Источник: migration + route + repo interface.
  【core-data/src/main/resources/db/migration/postgresql/V015__music_track_of_night.sql:1-8】
  【app-bot/src/main/kotlin/com/example/bot/routes/TrackOfNightRoutes.kt:38-99】
  【core-domain/src/main/kotlin/com/example/bot/music/Repositories.kt:120-139】

- Ограничение целостности есть: один текущий track per set (`set_id PRIMARY KEY`).【core-data/src/main/resources/db/migration/postgresql/V015__music_track_of_night.sql:1-4】

## 2.4 Голосования/реакции

- Голосования реализованы через `music_battles` + `music_battle_votes`:
  - `GET /api/music/battles/current`, `GET /api/music/battles`, `GET /api/music/battles/{id}`,
  - `POST /api/music/battles/{id}/vote`.
  Источник: `MusicBattleRoutes`.【app-bot/src/main/kotlin/com/example/bot/routes/MusicBattleRoutes.kt:100-160】

- Защита от дублей/накрутки в голосовании:
  - один голос на user+battle (`PRIMARY KEY (battle_id, user_id)`),
  - upsert c `insertIgnore` + update,
  - смена голоса разрешена только в окне `ACTIVE && starts_at <= now < ends_at`.
  Источник: migration + repository impl.【core-data/src/main/resources/db/migration/postgresql/V042__music_battles_votes_stems.sql:22-31】【core-data/src/main/kotlin/com/example/bot/data/music/MusicBattleVoteRepositoryImpl.kt:57-77】

---

## 3) Поддержка DJ (донаты/чаевые) и статистика

## 3.1 Поддержка DJ (донаты)

- Специализированной сущности/роутов для донатов DJ в модуле музыки не найдено (нет `dj_tips`/`music_donations` и API-методов в `routes/music*`).
- В `docs/music.md` также отсутствует контракт endpoint’ов донатов; описаны только каталог/лайки/баттлы/stems.
  Источник: `docs/music.md`.【docs/music.md:1-123】

## 3.2 Статистика по прослушиваниям/лайкам

- Лайки и производные метрики есть:
  - count лайков по item,
  - персональные лайки,
  - фан-ранкинг на базе `likes + battle votes` (`/api/music/fans/ranking`).
  Источник: `MusicLikesRepositoryImpl`, `MusicBattleService`, `MusicBattleRoutes`.
  【core-data/src/main/kotlin/com/example/bot/data/music/MusicLikesRepositoryImpl.kt:88-113】
  【app-bot/src/main/kotlin/com/example/bot/music/MusicBattleService.kt:49-81】
  【app-bot/src/main/kotlin/com/example/bot/routes/MusicBattleRoutes.kt:172-196】

- Статистики **прослушиваний** (play events/listen counters, anti-fraud на plays) не найдено: в музыкальных таблицах нет сущности play-логов, в routes нет endpoint’ов записи прослушивания.
  Источник: музыкальные миграции и tables.【core-data/src/main/resources/db/migration/postgresql/V5__music_init.sql:5-70】【core-data/src/main/resources/db/migration/postgresql/V042__music_battles_votes_stems.sql:2-37】【core-data/src/main/kotlin/com/example/bot/data/music/Tables.kt:7-127】

---

## 4) Итог по критериям Блока 11

## Реализовано

1. Загрузка треков/сетов файлами (audio/cover), хранение binary + метаданных, лимиты и allowlist content-type.
2. Базовая модерация через publish/unpublish + скрытие непубликованного контента в публичной выдаче.
3. Плейлисты (read API), “избранное” (лайки), главный трек ночи.
4. Голосования (баттлы) с защитой от дублей и ограничением по окну активности.
5. Базовая статистика по лайкам/голосам и fan ranking.

## Частично

1. Модерация: есть только publish-state, но нет полноценного workflow ревью (pending/rejected/moderation reason/очереди).
2. Плейлисты: есть модель + чтение, но не найден полноценный admin CRUD в `app-bot`.
3. Интерактивы: лайки и battle vote есть, но нет richer-реакций (несколько типов реакций/эмодзи и антифрод на них).

## Отсутствует

1. Поддержка DJ донатами/чаевыми в музыкальном модуле.
2. Метрики прослушиваний (listen/play counters, play analytics).
3. Антифрод для “прослушиваний” (так как самих событий прослушивания нет).

## Риски

- **P1 (продуктовый/доход):** отсутствие DJ-donations не закрывает критичный сценарий монетизации Блока 11.
- **P1 (аналитика):** нет listen-метрик, поэтому “статистика по прослушиваниям” невалидируема и неэкспортируема.
- **P2 (операционный):** упрощённая модерация publish/unpublish без ревью-пайплайна повышает риск публикации некачественного/неподходящего контента.
