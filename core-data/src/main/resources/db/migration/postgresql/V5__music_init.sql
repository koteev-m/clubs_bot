-- Для GIN-индекса по тексту нужен pg_trgm
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- music_items
CREATE TABLE IF NOT EXISTS music_items (
    id bigserial PRIMARY KEY,
    club_id bigint NULL REFERENCES clubs(id) ON DELETE SET NULL,
    title text NOT NULL,
    dj text NULL,
    source_type text NOT NULL CHECK (source_type IN ('YOUTUBE','SOUNDCLOUD','SPOTIFY','FILE','LINK')),
    source_url text NULL,
    telegram_file_id text NULL,
    duration_sec int NULL CHECK (duration_sec >= 0),
    cover_url text NULL,
    tags text NULL,
    published_at timestamptz NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_by bigint NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE music_items IS 'Tracks and DJ sets';
COMMENT ON COLUMN music_items.club_id IS 'Owning club id';
COMMENT ON COLUMN music_items.source_type IS 'Origin of media';
COMMENT ON COLUMN music_items.telegram_file_id IS 'Cached Telegram file id';

-- Индексы: сортировка по дате публикации, фильтрация по активным
CREATE INDEX IF NOT EXISTS music_items_club_active_idx
    ON music_items(club_id, is_active, published_at DESC);

-- Поиск по тегам (TEXT) через триграммы
-- Требует pg_trgm; на TEXT нужен операторный класс gin_trgm_ops
CREATE INDEX IF NOT EXISTS music_items_tags_trgm_idx
    ON music_items USING gin (tags gin_trgm_ops);

-- music_playlists
CREATE TABLE IF NOT EXISTS music_playlists (
    id bigserial PRIMARY KEY,
    club_id bigint NULL REFERENCES clubs(id) ON DELETE SET NULL,
    title text NOT NULL,
    description text NULL,
    cover_url text NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_by bigint NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE music_playlists IS 'Music playlists';

-- music_playlist_items
CREATE TABLE IF NOT EXISTS music_playlist_items (
    playlist_id bigint NOT NULL REFERENCES music_playlists(id) ON DELETE CASCADE,
    item_id bigint NOT NULL REFERENCES music_items(id) ON DELETE CASCADE,
    position int NOT NULL DEFAULT 0,
    PRIMARY KEY (playlist_id, item_id)
);
CREATE INDEX IF NOT EXISTS music_playlist_items_pos_idx
    ON music_playlist_items(playlist_id, position);

-- music_likes
CREATE TABLE IF NOT EXISTS music_likes (
    item_id bigint NOT NULL REFERENCES music_items(id) ON DELETE CASCADE,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    liked_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (item_id, user_id)
);

COMMENT ON TABLE music_likes IS 'User likes for music items';