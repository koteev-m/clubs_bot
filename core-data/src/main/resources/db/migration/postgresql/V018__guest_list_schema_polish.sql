-- Polish guest list and checkin schema
-- 1) Remove reserved "limit" column and rely on capacity
-- 2) Tighten checkins invariants and add operational indexes

-- Drop legacy "limit" column to avoid reserved keyword usage
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'guest_lists' AND column_name = 'limit'
    ) THEN
        ALTER TABLE guest_lists DROP CONSTRAINT IF EXISTS guest_lists_limit_check;
        -- Preserve any data by migrating into capacity if needed
        UPDATE guest_lists
        SET capacity = "limit"
        WHERE (capacity IS NULL OR capacity <= 0) AND "limit" IS NOT NULL;
        ALTER TABLE guest_lists DROP COLUMN "limit";
    END IF;
END $$;

-- Reaffirm positive capacity
ALTER TABLE guest_lists
    DROP CONSTRAINT IF EXISTS guest_lists_capacity_check;
ALTER TABLE guest_lists
    ALTER COLUMN capacity SET NOT NULL,
    ADD CONSTRAINT guest_lists_capacity_check CHECK (capacity > 0);

-- Ensure subject_id handles UUID bookings as text
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'checkins' AND column_name = 'subject_id' AND data_type <> 'text'
    ) THEN
        ALTER TABLE checkins ALTER COLUMN subject_id TYPE TEXT USING subject_id::text;
    END IF;
END $$;

-- Enforce deny_reason consistency with result_status
UPDATE checkins
SET deny_reason = 'unspecified'
WHERE result_status = 'DENIED' AND deny_reason IS NULL;

UPDATE checkins
SET deny_reason = NULL
WHERE result_status <> 'DENIED' AND deny_reason IS NOT NULL;

ALTER TABLE checkins
    DROP CONSTRAINT IF EXISTS checkins_deny_reason_consistency;
ALTER TABLE checkins
    ADD CONSTRAINT checkins_deny_reason_consistency CHECK (
        (result_status = 'DENIED' AND deny_reason IS NOT NULL) OR
        (result_status <> 'DENIED' AND deny_reason IS NULL)
    );

-- Operational indexes for checkin queries
CREATE INDEX IF NOT EXISTS idx_checkins_club_event ON checkins(club_id, event_id);
CREATE INDEX IF NOT EXISTS idx_checkins_event_occurred_at ON checkins(event_id, occurred_at);
