CREATE TABLE IF NOT EXISTS payment_actions (
    id BIGSERIAL PRIMARY KEY,
    booking_id UUID NOT NULL REFERENCES bookings(id),
    idempotency_key TEXT NOT NULL,
    action TEXT NOT NULL,
    status TEXT NOT NULL,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS payment_actions_idempotency_key_idx
    ON payment_actions (idempotency_key);

CREATE INDEX IF NOT EXISTS payment_actions_booking_idx
    ON payment_actions (booking_id);
