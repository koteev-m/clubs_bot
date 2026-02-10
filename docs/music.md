# Музыкальный модуль: инварианты и контракты

## Модели
- `music_items` хранит и треки, и DJ-сеты; тип фиксируется в `item_type` (`TRACK` или `SET`).
- Публикация управляется через `published_at`. Опубликованные элементы видны в гостевой ленте и микстейпах, черновики — только в админке.
- `music_assets` хранит бинарные файлы (аудио/обложки) с контрольной суммой SHA-256.
- `MusicItemType`: `TRACK`, `SET`.
- `MusicSource`: `YOUTUBE`, `SOUNDCLOUD`, `SPOTIFY`, `FILE`, `LINK`.

## Инварианты
- Miniapp API (`/api/music/*`, `/api/me/*`, `/api/admin/*`) всегда отвечает с заголовками:
  - `Cache-Control: no-store`;
  - `Vary: X-Telegram-Init-Data`;
  - применяется и для `304/401/413/...` ответов (включая ошибки и not-modified).
- Публичная раздача ассетов `/api/music/items/{id}/audio|cover` использует другие заголовки:
  - `Cache-Control: private, max-age=3600, must-revalidate`;
  - `ETag` = SHA-256 контента; при совпадении `If-None-Match` возвращается `304` с теми же cache-заголовками.
- Гостевой API (miniapp роли, включая GUEST) возвращает только `item_type = SET` и `published_at != null`:
  - `/api/music/sets` — список сетов;
  - `/api/music/mixtape/week` и `/api/me/mixtape` — только опубликованные сеты;
  - лайки (`/api/music/items/{id}/like`) допустимы только для опубликованных сетов.
- Аудиофайл и обложка доступны публично только для опубликованных элементов. ETag основан на SHA-256, ответы кешируются приватно.
- Загрузка файлов идёт потоково с ограничением размера и allowlist Content-Type:
  - аудио: до 50 MB; `audio/mpeg`, `audio/ogg`, `audio/mp4`, `audio/aac`;
  - обложка: до 5 MB; `image/png`, `image/jpeg`, `image/webp`.
- При загрузке не логируем содержимое файлов/байты (только метаданные вроде размера/sha256/идентификаторов).
- Для клубных элементов требуется доступ администратора к клубу; глобальные элементы разрешены только для OWNER/GLOBAL_ADMIN.

- Музыкальные баттлы (`music_battles`) клубного scope (`club_id`, nullable для совместимости с глобальным каталогом), статусы: `DRAFT`, `ACTIVE`, `CLOSED`.
- Голосование (`music_battle_votes`) ограничено до одного голоса пользователя на баттл (`PRIMARY KEY (battle_id, user_id)`), смена выбора разрешена только пока баттл активен и не завершился (`ACTIVE` и `now < ends_at`).
- Stem-пакеты хранятся отдельной link-таблицей `music_item_stems_assets` (ровно один актуальный stem-asset на item, upsert по `item_id`).

- API баттлов и голосования (miniapp):
  - `GET /api/music/battles/current?clubId=...` — текущий `ACTIVE` баттл по клубу, `404 not_found` если нет активного;
  - `GET /api/music/battles?clubId=...&limit=...&offset=...` — список баттлов;
  - `GET /api/music/battles/{battleId}` — детали баттла;
  - `POST /api/music/battles/{battleId}/vote` — идемпотентный upsert голоса (`{ "chosenItemId": ... }`), доступно miniapp-пользователям (роли guest+), при закрытом/неактивном баттле `409 invalid_state`, при некорректном выборе `400 validation_error`.
- В баттлах возвращаются агрегаты `countA/countB/percentA/percentB` (проценты считаются целочисленно) и `myVote` для авторизованного пользователя.
- `GET /api/music/items/{itemId}/stems`:
  - доступ только для `OWNER`, `HEAD_MANAGER`, `GLOBAL_ADMIN`;
  - раздача через тот же контракт кеширования, что и для `/audio`/`/cover` (`ETag` + `Cache-Control: private, max-age=3600, must-revalidate`);
  - при отсутствии stem-пакета: `404 not_found`.
- `GET /api/music/fans/ranking?clubId=...&windowDays=...`:
  - безопасный MVP без PII других пользователей;
  - возвращает только `myStats` (votesCast/likesGiven/points/rank) и обезличенное распределение (`topPoints`, `p50/p90/p99`, `totalFans`).

## Рекомендации для клиентов
- `initData` передавать только в заголовке `X-Telegram-Init-Data`, не в query string. Query-параметр `initData` поддерживается только как legacy fallback.
- Для публичных лент использовать `ETag` и `If-None-Match`.
- Для отображения аудио использовать `audioUrl` (внутренние ссылки), для внешних источников — `sourceUrl` (если поле заполнено).

## Эндпоинты (admin/guest) с примерами
### Admin (`/api/admin/*`, miniapp auth + RBAC)
- `GET /api/admin/music/items?type=SET`
  - `curl -H "X-Telegram-Init-Data: <initData>" "https://<host>/api/admin/music/items?type=SET"`
- `POST /api/admin/music/items`
  - Тело запроса: `title` (обязательно), `itemType` (обязательно), `source` (обязательно), `clubId`, `dj`, `description`, `sourceUrl`, `durationSec`, `coverUrl`, `tags`, `published` (по умолчанию `false`).
  - `curl -X POST -H "Content-Type: application/json" -H "X-Telegram-Init-Data: <initData>" \`
    `-d '{"title":"Night Set","itemType":"SET","source":"FILE"}' https://<host>/api/admin/music/items`
- `POST /api/admin/music/items/{id}/publish`
  - `curl -X POST -H "X-Telegram-Init-Data: <initData>" https://<host>/api/admin/music/items/10/publish`
- `GET /api/admin/music/items/{id}`
- `PUT /api/admin/music/items/{id}`
- `POST /api/admin/music/items/{id}/unpublish`
- `PUT /api/admin/music/items/{id}/audio` (multipart, поле `file`)
  - `curl -X PUT -H "X-Telegram-Init-Data: <initData>" -F "file=@set.mp3" https://<host>/api/admin/music/items/10/audio`
- `PUT /api/admin/music/items/{id}/cover` (multipart, поле `file`)
  - `curl -X PUT -H "X-Telegram-Init-Data: <initData>" -F "file=@cover.jpg" https://<host>/api/admin/music/items/10/cover`

### Guest/Mini App (`/api/music/*`, `/api/me/*`)
- `GET /api/music/sets?limit=20&offset=0&tag=...&q=...`
  - `curl -H "X-Telegram-Init-Data: <initData>" "https://<host>/api/music/sets?limit=20&offset=0"`
- `GET /api/music/playlists`
- `GET /api/music/playlists/{id}`
- `GET /api/music/mixtape/week`
- `GET /api/me/mixtape`
- `POST /api/music/items/{id}/like`
  - `curl -X POST -H "X-Telegram-Init-Data: <initData>" https://<host>/api/music/items/10/like`
- `DELETE /api/music/items/{id}/like`
- `GET /api/music/items/{id}/audio` (публично только published)
- `GET /api/music/items/{id}/cover` (публично только published)
