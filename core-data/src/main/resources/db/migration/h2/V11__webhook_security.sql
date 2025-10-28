CREATE TABLE IF NOT EXISTS suspicious_ips (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ip VARCHAR(255) NOT NULL,
    user_agent CLOB,
    reason VARCHAR(128) NOT NULL,
    details CLOB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_suspicious_ips_created_at ON suspicious_ips (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_suspicious_ips_ip ON suspicious_ips (ip);

CREATE TABLE IF NOT EXISTS webhook_update_dedup (
    update_id BIGINT PRIMARY KEY,
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    duplicate_count INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_webhook_update_dedup_first_seen ON webhook_update_dedup (first_seen_at);
