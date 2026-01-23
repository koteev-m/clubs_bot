CREATE TABLE IF NOT EXISTS promoter_club_access (
    club_id BIGINT NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    promoter_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    access_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (club_id, promoter_user_id)
);

CREATE INDEX IF NOT EXISTS idx_promoter_club_access_promoter
    ON promoter_club_access (promoter_user_id);
