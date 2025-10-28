CREATE TABLE IF NOT EXISTS payment_actions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id UUID NOT NULL,
    idempotency_key TEXT NOT NULL,
    action TEXT NOT NULL,
    status TEXT NOT NULL,
    reason TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_actions_booking FOREIGN KEY (booking_id) REFERENCES bookings(id)
);

CREATE UNIQUE INDEX IF NOT EXISTS payment_actions_idempotency_key_idx
    ON payment_actions (idempotency_key);

CREATE INDEX IF NOT EXISTS payment_actions_booking_idx
    ON payment_actions (booking_id);
