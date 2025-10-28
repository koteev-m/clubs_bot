CREATE TABLE IF NOT EXISTS suspicious_ips (
    id BIGSERIAL PRIMARY KEY,
    ip TEXT NOT NULL,
    user_agent TEXT,
    reason TEXT NOT NULL,
    details TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_suspicious_ips_created_at ON suspicious_ips (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_suspicious_ips_ip         ON suspicious_ips (ip);

CREATE TABLE IF NOT EXISTS webhook_update_dedup (
    update_id BIGINT PRIMARY KEY,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    duplicate_count INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_webhook_update_dedup_first_seen ON webhook_update_dedup (first_seen_at);