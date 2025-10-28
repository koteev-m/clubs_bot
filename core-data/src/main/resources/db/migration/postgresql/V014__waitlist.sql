-- Waitlist table for per-club per-event queue
CREATE TABLE IF NOT EXISTS waitlist (
    id           BIGSERIAL PRIMARY KEY,
    club_id      BIGINT      NOT NULL,
    event_id     BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    party_size   INTEGER     NOT NULL CHECK (party_size > 0),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    called_at    TIMESTAMPTZ NULL,
    expires_at   TIMESTAMPTZ NULL,
    status       TEXT        NOT NULL
);

-- Индексы для выборок очереди и очистки истёкших
CREATE INDEX IF NOT EXISTS idx_waitlist_club_event_status_created
    ON waitlist (club_id, event_id, status, created_at);

CREATE INDEX IF NOT EXISTS idx_waitlist_expires_at
    ON waitlist (expires_at);