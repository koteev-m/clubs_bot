# Музыкальный модуль: инварианты и контракты

## Модели
- `music_items` хранит и треки, и DJ-сеты; тип фиксируется в `item_type` (`TRACK` или `SET`).
- Публикация управляется через `published_at`. Опубликованные элементы видны в гостевой ленте и микстейпах, черновики — только в админке.
- `music_assets` хранит бинарные файлы (аудио/обложки) с контрольной суммой SHA-256.

## Инварианты
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

## Рекомендации для клиентов
- Для публичных лент использовать `ETag` и `If-None-Match`.
- Для отображения аудио использовать `audioUrl` (внутренние ссылки), для внешних источников — `sourceUrl` (если поле заполнено).

## Эндпоинты (admin/guest) с примерами
### Admin (`/api/admin/*`, miniapp auth + RBAC)
- `GET /api/admin/music/items?type=SET`
  - `curl -H "X-Telegram-Init-Data: <initData>" "https://<host>/api/admin/music/items?type=SET"`
- `POST /api/admin/music/items`
  - `curl -X POST -H "Content-Type: application/json" -H "X-Telegram-Init-Data: <initData>" \`
    `-d '{"title":"Night Set","itemType":"SET","source":"INTERNAL","published":false}' https://<host>/api/admin/music/items`
- `GET /api/admin/music/items/{id}`
- `PUT /api/admin/music/items/{id}`
- `POST /api/admin/music/items/{id}/publish`
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
