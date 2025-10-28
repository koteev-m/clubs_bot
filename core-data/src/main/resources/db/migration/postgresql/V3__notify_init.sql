CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Segments
CREATE TABLE IF NOT EXISTS notify_segments (
    id BIGSERIAL PRIMARY KEY,
    title TEXT NOT NULL,
    definition JSONB NOT NULL,
    created_by BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE notify_segments IS 'User targeting segments';
COMMENT ON COLUMN notify_segments.definition IS 'Segment definition as JSON';
COMMENT ON COLUMN notify_segments.created_by IS 'Author user id';

-- Campaigns
CREATE TABLE IF NOT EXISTS notify_campaigns (
    id BIGSERIAL PRIMARY KEY,
    title TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('DRAFT','SCHEDULED','SENDING','PAUSED','DONE','FAILED')),
    kind TEXT NOT NULL,
    club_id BIGINT NULL REFERENCES clubs(id),
    message_thread_id INT NULL,
    segment_id BIGINT NULL REFERENCES notify_segments(id),
    schedule_cron TEXT NULL,
    starts_at TIMESTAMPTZ NULL,
    created_by BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE notify_campaigns IS 'Notification campaigns';
COMMENT ON COLUMN notify_campaigns.status IS 'Campaign status';
COMMENT ON COLUMN notify_campaigns.schedule_cron IS 'Cron schedule expression';
COMMENT ON COLUMN notify_campaigns.starts_at IS 'Scheduled start in UTC';
COMMENT ON COLUMN notify_campaigns.segment_id IS 'Target segment';

-- Расширяем notifications_outbox (создана в V1)
ALTER TABLE notifications_outbox
    ADD COLUMN IF NOT EXISTS recipient_type TEXT NOT NULL DEFAULT 'chat',
    ADD COLUMN IF NOT EXISTS recipient_id BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS dedup_key TEXT,
    ADD COLUMN IF NOT EXISTS priority INT NOT NULL DEFAULT 100,
    ADD COLUMN IF NOT EXISTS campaign_id BIGINT NULL REFERENCES notify_campaigns(id),
    ADD COLUMN IF NOT EXISTS method TEXT NOT NULL DEFAULT 'EVENT',
    ADD COLUMN IF NOT EXISTS parse_mode TEXT NULL,
    ADD COLUMN IF NOT EXISTS attachments JSONB NULL,
    ADD COLUMN IF NOT EXISTS language TEXT NULL,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Уникальность по dedup_key при необходимости
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE schemaname = 'public' AND indexname = 'notifications_outbox_dedup_key_key'
    ) THEN
        BEGIN
            ALTER TABLE notifications_outbox ADD CONSTRAINT notifications_outbox_dedup_key_key UNIQUE (dedup_key);
        EXCEPTION WHEN duplicate_table THEN
            -- уже существует (например, из прошлого прогона)
            NULL;
        END;
    END IF;
END$$;

-- Индексы
CREATE INDEX IF NOT EXISTS idx_notifications_outbox_status_retry
    ON notifications_outbox(status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_notifications_outbox_campaign_id
    ON notifications_outbox(campaign_id);
CREATE INDEX IF NOT EXISTS idx_notifications_outbox_priority_created_at
    ON notifications_outbox(priority, created_at);