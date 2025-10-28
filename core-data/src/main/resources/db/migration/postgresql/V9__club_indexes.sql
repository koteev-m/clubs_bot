-- Ensure guest list tables expose created_at and modern statuses
ALTER TABLE guest_lists
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Align entry statuses with application enums
ALTER TABLE guest_list_entries DROP CONSTRAINT IF EXISTS guest_list_entries_status_check;
ALTER TABLE guest_list_entries ALTER COLUMN status TYPE TEXT;
ALTER TABLE guest_list_entries ALTER COLUMN status SET DEFAULT 'PLANNED';

UPDATE guest_list_entries
SET status =
    CASE status
        WHEN 'ARRIVED'  THEN 'CHECKED_IN'
        WHEN 'NO_SHOW'  THEN 'NO_SHOW'
        WHEN 'APPROVED' THEN 'PLANNED'
        WHEN 'INVITED'  THEN 'PLANNED'
        WHEN 'DENIED'   THEN 'NO_SHOW'
        WHEN 'LATE'     THEN 'NO_SHOW'
        ELSE status
    END;

UPDATE guest_list_entries
SET checked_in_at = NULL,
    checked_in_by = NULL
WHERE status <> 'CHECKED_IN';

ALTER TABLE guest_list_entries
    ADD CONSTRAINT guest_list_entries_status_check CHECK (status IN ('PLANNED', 'CHECKED_IN', 'NO_SHOW'));

-- Recreate helpful indexes (idempotent)
CREATE INDEX IF NOT EXISTS idx_guest_list_entries_list_status ON guest_list_entries(guest_list_id, status);
CREATE INDEX IF NOT EXISTS idx_guest_lists_club_created_at     ON guest_lists(club_id, created_at);
CREATE INDEX IF NOT EXISTS idx_events_club_start               ON events(club_id, start_at);
CREATE INDEX IF NOT EXISTS idx_tables_club                     ON tables(club_id);