ALTER TABLE audit_log
    ADD COLUMN IF NOT EXISTS result TEXT NOT NULL DEFAULT 'UNKNOWN';

CREATE TABLE IF NOT EXISTS booking_outbox (
    id BIGSERIAL PRIMARY KEY,
    topic TEXT NOT NULL,
    payload JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'SENT', 'FAILED')),
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_booking_outbox_status_attempt
    ON booking_outbox(status, next_attempt_at);