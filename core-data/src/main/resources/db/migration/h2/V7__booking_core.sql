-- Align booking, hold, outbox, and audit tables with new specification

-- Ensure booking holds capture slot window and enforce uniqueness per active slot
ALTER TABLE booking_holds ADD COLUMN IF NOT EXISTS slot_start TIMESTAMP WITH TIME ZONE;
ALTER TABLE booking_holds ADD COLUMN IF NOT EXISTS slot_end TIMESTAMP WITH TIME ZONE;

UPDATE booking_holds AS h
SET slot_start = e.start_at,
    slot_end = e.end_at
FROM events AS e
WHERE h.event_id = e.id
  AND (h.slot_start IS NULL OR h.slot_end IS NULL);

ALTER TABLE booking_holds ALTER COLUMN slot_start SET NOT NULL;
ALTER TABLE booking_holds ALTER COLUMN slot_end SET NOT NULL;

ALTER TABLE booking_holds
    DROP CONSTRAINT IF EXISTS booking_holds_slot_window_check;
ALTER TABLE booking_holds
    ADD CONSTRAINT booking_holds_slot_window_check CHECK (slot_end > slot_start);

DROP INDEX IF EXISTS uq_active_hold;
CREATE INDEX idx_booking_holds_active_slot_h2
    ON booking_holds(table_id, slot_start, slot_end);

-- Extend bookings with slot window tracking and new status set
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS slot_start TIMESTAMP WITH TIME ZONE;
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS slot_end TIMESTAMP WITH TIME ZONE;

UPDATE bookings AS b
SET slot_start = e.start_at,
    slot_end = e.end_at
FROM events AS e
WHERE b.event_id = e.id
  AND (b.slot_start IS NULL OR b.slot_end IS NULL);

ALTER TABLE bookings ALTER COLUMN slot_start SET NOT NULL;
ALTER TABLE bookings ALTER COLUMN slot_end SET NOT NULL;

ALTER TABLE bookings
    DROP CONSTRAINT IF EXISTS bookings_status_check;

UPDATE bookings SET status = 'BOOKED' WHERE status = 'CONFIRMED';
UPDATE bookings SET status = 'CANCELLED' WHERE status = 'EXPIRED';

ALTER TABLE bookings
    ADD CONSTRAINT bookings_status_check CHECK (status IN ('BOOKED','SEATED','NO_SHOW','CANCELLED'));

ALTER TABLE bookings
    DROP CONSTRAINT IF EXISTS bookings_slot_window_check;
ALTER TABLE bookings
    ADD CONSTRAINT bookings_slot_window_check CHECK (slot_end > slot_start);

DROP INDEX IF EXISTS uq_active_booking;
CREATE INDEX idx_bookings_active_slot_h2
    ON bookings(table_id, slot_start, slot_end);

-- Align notifications outbox naming and statuses
ALTER TABLE notifications_outbox
    RENAME COLUMN next_retry_at TO next_attempt_at;

UPDATE notifications_outbox SET status = 'NEW' WHERE status = 'PENDING';

ALTER TABLE notifications_outbox
    DROP CONSTRAINT IF EXISTS notifications_outbox_status_check;
ALTER TABLE notifications_outbox
    ADD CONSTRAINT notifications_outbox_status_check CHECK (status IN ('NEW','SENT','FAILED'));
ALTER TABLE notifications_outbox
    ALTER COLUMN status SET DEFAULT 'NEW';

DROP INDEX IF EXISTS idx_notifications_outbox_status_retry;
CREATE INDEX idx_notifications_outbox_status_attempt
    ON notifications_outbox(status, next_attempt_at);

-- Bring audit log columns to the new naming scheme
ALTER TABLE audit_log
    RENAME COLUMN ts TO created_at;
ALTER TABLE audit_log
    RENAME COLUMN actor_user_id TO user_id;
ALTER TABLE audit_log
    RENAME COLUMN entity TO resource;
ALTER TABLE audit_log
    RENAME COLUMN entity_id TO resource_id;
ALTER TABLE audit_log
    RENAME COLUMN delta TO meta;

ALTER TABLE audit_log
    ALTER COLUMN meta TYPE JSON USING COALESCE(meta, CAST('{}' AS JSON));
ALTER TABLE audit_log
    ALTER COLUMN meta SET DEFAULT CAST('{}' AS JSON);

ALTER TABLE audit_log
    ADD COLUMN IF NOT EXISTS club_id BIGINT NULL REFERENCES clubs(id) ON DELETE SET NULL;
ALTER TABLE audit_log
    ADD COLUMN IF NOT EXISTS ip VARCHAR(64) NULL;
