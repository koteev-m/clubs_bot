CREATE TABLE IF NOT EXISTS operational_night_overrides (
    club_id BIGINT NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    night_start_utc TIMESTAMPTZ NOT NULL,
    early_cutoff_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (club_id, night_start_utc)
);

CREATE TABLE IF NOT EXISTS club_visits (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    night_start_utc TIMESTAMPTZ NOT NULL,
    event_id BIGINT NULL REFERENCES events(id) ON DELETE SET NULL,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    first_checkin_at TIMESTAMPTZ NOT NULL,
    actor_user_id BIGINT NOT NULL REFERENCES users(id),
    actor_role TEXT NULL,
    entry_type TEXT NOT NULL,
    is_early BOOLEAN NOT NULL,
    has_table BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (club_id, night_start_utc, user_id)
);

CREATE INDEX IF NOT EXISTS idx_club_visits_club_night_start
    ON club_visits (club_id, night_start_utc);

CREATE INDEX IF NOT EXISTS idx_club_visits_user_club_first_checkin
    ON club_visits (user_id, club_id, first_checkin_at DESC);

CREATE INDEX IF NOT EXISTS idx_club_visits_club_event
    ON club_visits (club_id, event_id)
    WHERE event_id IS NOT NULL;
