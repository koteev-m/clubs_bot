CREATE INDEX IF NOT EXISTS idx_booking_outbox_topic_claim
    ON booking_outbox(status, topic, next_attempt_at, lease_until);
