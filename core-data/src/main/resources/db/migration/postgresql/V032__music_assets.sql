ALTER TABLE music_items
    ADD COLUMN IF NOT EXISTS description TEXT NULL,
    ADD COLUMN IF NOT EXISTS item_type TEXT NOT NULL DEFAULT 'TRACK',
    ADD COLUMN IF NOT EXISTS audio_asset_id BIGINT NULL,
    ADD COLUMN IF NOT EXISTS cover_asset_id BIGINT NULL;

CREATE TABLE IF NOT EXISTS music_assets (
    id BIGSERIAL PRIMARY KEY,
    kind TEXT NOT NULL,
    bytes BYTEA NOT NULL,
    content_type TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_music_items_audio_asset'
          AND conrelid = 'music_items'::regclass
    ) THEN
        ALTER TABLE music_items
            ADD CONSTRAINT fk_music_items_audio_asset
                FOREIGN KEY (audio_asset_id) REFERENCES music_assets(id) ON DELETE SET NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_music_items_cover_asset'
          AND conrelid = 'music_items'::regclass
    ) THEN
        ALTER TABLE music_items
            ADD CONSTRAINT fk_music_items_cover_asset
                FOREIGN KEY (cover_asset_id) REFERENCES music_assets(id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_music_items_type_published
    ON music_items(item_type, published_at DESC);
