CREATE TABLE IF NOT EXISTS telegram_webhook_updates (
    id BIGSERIAL PRIMARY KEY,
    update_id BIGINT NOT NULL UNIQUE,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    payload_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error TEXT,
    processed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_telegram_webhook_updates_status_next_attempt
    ON telegram_webhook_updates (status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_telegram_webhook_updates_received_at
    ON telegram_webhook_updates (received_at);
