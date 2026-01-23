CREATE TABLE IF NOT EXISTS promoter_club_access (
    club_id BIGINT NOT NULL,
    promoter_user_id BIGINT NOT NULL,
    access_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(),
    PRIMARY KEY (club_id, promoter_user_id),
    FOREIGN KEY (club_id) REFERENCES clubs(id) ON DELETE CASCADE,
    FOREIGN KEY (promoter_user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_promoter_club_access_promoter
    ON promoter_club_access (promoter_user_id);
