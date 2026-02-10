-- Battle scope: club-scoped (club_id nullable for global catalog compatibility), lookup APIs always filter by club_id.
CREATE TABLE IF NOT EXISTS music_battles (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NULL REFERENCES clubs(id) ON DELETE SET NULL,
    item_a_id BIGINT NOT NULL REFERENCES music_items(id) ON DELETE CASCADE,
    item_b_id BIGINT NOT NULL REFERENCES music_items(id) ON DELETE CASCADE,
    status TEXT NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'CLOSED')),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_music_battles_items_distinct CHECK (item_a_id <> item_b_id),
    CONSTRAINT chk_music_battles_time_range CHECK (starts_at < ends_at)
);

CREATE INDEX IF NOT EXISTS idx_music_battles_scope_active_lookup
    ON music_battles(club_id, status, starts_at DESC, ends_at, id DESC);

CREATE INDEX IF NOT EXISTS idx_music_battles_scope_recent
    ON music_battles(club_id, starts_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS music_battle_votes (
    battle_id BIGINT NOT NULL REFERENCES music_battles(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    chosen_item_id BIGINT NOT NULL REFERENCES music_items(id) ON DELETE CASCADE,
    voted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (battle_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_music_battle_votes_battle_item
    ON music_battle_votes(battle_id, chosen_item_id);

CREATE TABLE IF NOT EXISTS music_item_stems_assets (
    item_id BIGINT PRIMARY KEY REFERENCES music_items(id) ON DELETE CASCADE,
    asset_id BIGINT NOT NULL REFERENCES music_assets(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
