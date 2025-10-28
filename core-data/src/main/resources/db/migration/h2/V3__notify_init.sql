CREATE TABLE notify_segments (
    id BIGSERIAL PRIMARY KEY,
    title TEXT NOT NULL,
    definition JSON NOT NULL,
    created_by BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
COMMENT ON TABLE notify_segments IS 'User targeting segments';
COMMENT ON COLUMN notify_segments.definition IS 'Segment definition as JSON';
COMMENT ON COLUMN notify_segments.created_by IS 'Author user id';

CREATE TABLE notify_campaigns (
    id BIGSERIAL PRIMARY KEY,
    title TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('DRAFT','SCHEDULED','SENDING','PAUSED','DONE','FAILED')),
    kind TEXT NOT NULL,
    club_id BIGINT NULL REFERENCES clubs(id),
    message_thread_id INT NULL,
    segment_id BIGINT NULL REFERENCES notify_segments(id),
    schedule_cron TEXT NULL,
    starts_at TIMESTAMP WITH TIME ZONE NULL,
    created_by BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
COMMENT ON TABLE notify_campaigns IS 'Notification campaigns';
COMMENT ON COLUMN notify_campaigns.status IS 'Campaign status';
COMMENT ON COLUMN notify_campaigns.schedule_cron IS 'Cron schedule expression';
COMMENT ON COLUMN notify_campaigns.starts_at IS 'Scheduled start in UTC';
COMMENT ON COLUMN notify_campaigns.segment_id IS 'Target segment';

CREATE TABLE user_subscriptions (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    club_id BIGINT NULL REFERENCES clubs(id),
    topic TEXT NOT NULL,
    opt_in BOOLEAN NOT NULL DEFAULT TRUE,
    lang TEXT NOT NULL DEFAULT 'ru',
    PRIMARY KEY (user_id, club_id, topic)
);
COMMENT ON TABLE user_subscriptions IS 'User notification preferences';
COMMENT ON COLUMN user_subscriptions.opt_in IS 'Whether user opted in';
COMMENT ON COLUMN user_subscriptions.lang IS 'Preferred language';

ALTER TABLE notifications_outbox ADD COLUMN recipient_type TEXT NOT NULL;
ALTER TABLE notifications_outbox ADD COLUMN recipient_id BIGINT NOT NULL;
ALTER TABLE notifications_outbox ADD COLUMN dedup_key TEXT UNIQUE;
ALTER TABLE notifications_outbox ADD COLUMN priority INT NOT NULL DEFAULT 100;
ALTER TABLE notifications_outbox ADD COLUMN campaign_id BIGINT NULL REFERENCES notify_campaigns(id);
ALTER TABLE notifications_outbox ADD COLUMN method TEXT NOT NULL;
ALTER TABLE notifications_outbox ADD COLUMN parse_mode TEXT NULL;
ALTER TABLE notifications_outbox ADD COLUMN attachments JSON NULL;
ALTER TABLE notifications_outbox ADD COLUMN language TEXT NULL;
ALTER TABLE notifications_outbox ADD COLUMN created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();

COMMENT ON COLUMN notifications_outbox.recipient_type IS 'Target entity type';
COMMENT ON COLUMN notifications_outbox.recipient_id IS 'Target entity id';
COMMENT ON COLUMN notifications_outbox.dedup_key IS 'Unique key to deduplicate notifications';
COMMENT ON COLUMN notifications_outbox.priority IS 'Lower values are processed first';
COMMENT ON COLUMN notifications_outbox.campaign_id IS 'Related notify_campaigns.id';
COMMENT ON COLUMN notifications_outbox.method IS 'Delivery method';
COMMENT ON COLUMN notifications_outbox.parse_mode IS 'Message parse mode';
COMMENT ON COLUMN notifications_outbox.attachments IS 'Optional attachments data';
COMMENT ON COLUMN notifications_outbox.language IS 'Preferred language';
COMMENT ON COLUMN notifications_outbox.created_at IS 'Record creation time';

CREATE INDEX IF NOT EXISTS idx_notifications_outbox_status_retry ON notifications_outbox(status, next_retry_at);
CREATE INDEX idx_notifications_outbox_campaign_id ON notifications_outbox(campaign_id);
CREATE INDEX idx_notifications_outbox_priority_created_at ON notifications_outbox(priority, created_at);
