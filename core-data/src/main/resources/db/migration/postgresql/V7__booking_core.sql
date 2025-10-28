-- Align booking, hold, outbox, and audit tables with new specification

-- Ensure booking holds capture slot window and enforce slot window validity
ALTER TABLE booking_holds
    ADD COLUMN IF NOT EXISTS slot_start TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS slot_end   TIMESTAMPTZ;

UPDATE booking_holds AS h
SET slot_start = e.start_at,
    slot_end   = e.end_at
FROM events AS e
WHERE h.event_id = e.id
  AND (h.slot_start IS NULL OR h.slot_end IS NULL);

ALTER TABLE booking_holds
    ALTER COLUMN slot_start SET NOT NULL,
    ALTER COLUMN slot_end   SET NOT NULL;

ALTER TABLE booking_holds
    DROP CONSTRAINT IF EXISTS booking_holds_slot_window_check;
ALTER TABLE booking_holds
    ADD  CONSTRAINT booking_holds_slot_window_check CHECK (slot_end > slot_start);

-- ❗ НЕЛЬЗЯ делать partial UNIQUE по expires_at > now(): now() не IMMUTABLE.
-- Уникальность «активного холда» обеспечиваем на уровне приложения.
DROP INDEX IF EXISTS uq_active_hold;
DROP INDEX IF EXISTS uq_booking_holds_active_slot;

-- Полезные индексы для быстрых выборок активных холдов
CREATE INDEX IF NOT EXISTS idx_booking_holds_event_expires   ON booking_holds(event_id, expires_at);
CREATE INDEX IF NOT EXISTS idx_booking_holds_slot_active     ON booking_holds(table_id, slot_start, slot_end, expires_at);

-- Extend bookings with slot window tracking and new status set
ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS slot_start TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS slot_end   TIMESTAMPTZ;

UPDATE bookings AS b
SET slot_start = e.start_at,
    slot_end   = e.end_at
FROM events AS e
WHERE b.event_id = e.id
  AND (b.slot_start IS NULL OR b.slot_end IS NULL);

ALTER TABLE bookings
    ALTER COLUMN slot_start SET NOT NULL,
    ALTER COLUMN slot_end   SET NOT NULL;

-- Status migration: CONFIRMED->BOOKED, EXPIRED->CANCELLED, then tighten CHECK
ALTER TABLE bookings
    DROP CONSTRAINT IF EXISTS bookings_status_check;

UPDATE bookings SET status = 'BOOKED'    WHERE status = 'CONFIRMED';
UPDATE bookings SET status = 'CANCELLED' WHERE status = 'EXPIRED';

ALTER TABLE bookings
    ADD CONSTRAINT bookings_status_check CHECK (status IN ('BOOKED','SEATED','NO_SHOW','CANCELLED'));

ALTER TABLE bookings
    DROP CONSTRAINT IF EXISTS bookings_slot_window_check;
ALTER TABLE bookings
    ADD  CONSTRAINT bookings_slot_window_check CHECK (slot_end > slot_start);

-- Уникальность активной брони безопасна: предикат только по константам
DROP INDEX IF EXISTS uq_active_booking;
CREATE UNIQUE INDEX uq_bookings_active_slot
    ON bookings(table_id, slot_start, slot_end)
    WHERE status IN ('BOOKED','SEATED');

-- Align notifications outbox naming and statuses
-- (RENAME без IF EXISTS — допустим, т.к. миграция идёт линейно)
ALTER TABLE notifications_outbox
    RENAME COLUMN next_retry_at TO next_attempt_at;

UPDATE notifications_outbox SET status = 'NEW' WHERE status = 'PENDING';

ALTER TABLE notifications_outbox
    DROP CONSTRAINT IF EXISTS notifications_outbox_status_check;
ALTER TABLE notifications_outbox
    ADD  CONSTRAINT notifications_outbox_status_check CHECK (status IN ('NEW','SENT','FAILED'));
ALTER TABLE notifications_outbox
    ALTER COLUMN status SET DEFAULT 'NEW';

DROP INDEX IF EXISTS idx_notifications_outbox_status_retry;
CREATE INDEX idx_notifications_outbox_status_attempt
    ON notifications_outbox(status, next_attempt_at);

-- Bring audit log columns to the new naming scheme
ALTER TABLE audit_log
    RENAME COLUMN ts            TO created_at;
ALTER TABLE audit_log
    RENAME COLUMN actor_user_id TO user_id;
ALTER TABLE audit_log
    RENAME COLUMN entity        TO resource;
ALTER TABLE audit_log
    RENAME COLUMN entity_id     TO resource_id;
ALTER TABLE audit_log
    RENAME COLUMN delta         TO meta;

ALTER TABLE audit_log
    ALTER COLUMN meta TYPE JSONB USING COALESCE(meta, '{}'::jsonb);
ALTER TABLE audit_log
    ALTER COLUMN meta SET DEFAULT '{}'::jsonb;

ALTER TABLE audit_log
    ADD COLUMN IF NOT EXISTS club_id BIGINT   NULL REFERENCES clubs(id) ON DELETE SET NULL;
ALTER TABLE audit_log
    ADD COLUMN IF NOT EXISTS ip      VARCHAR(64) NULL;