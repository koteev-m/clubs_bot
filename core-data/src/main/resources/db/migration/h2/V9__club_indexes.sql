DROP INDEX IF EXISTS idx_guest_list_entries_list_status;

ALTER TABLE guest_lists
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE guest_list_entries ALTER COLUMN status RENAME TO status_old;

ALTER TABLE guest_list_entries
    ADD COLUMN status TEXT NOT NULL DEFAULT 'PLANNED';

UPDATE guest_list_entries
SET status =
    CASE status_old
        WHEN 'ARRIVED' THEN 'CHECKED_IN'
        WHEN 'NO_SHOW' THEN 'NO_SHOW'
        WHEN 'APPROVED' THEN 'PLANNED'
        WHEN 'INVITED' THEN 'PLANNED'
        WHEN 'DENIED' THEN 'NO_SHOW'
        WHEN 'LATE' THEN 'NO_SHOW'
        ELSE status_old
    END;

ALTER TABLE guest_list_entries DROP COLUMN status_old;

ALTER TABLE guest_list_entries
    ADD CONSTRAINT chk_guest_list_entries_status CHECK (status IN ('PLANNED','CHECKED_IN','NO_SHOW'));

UPDATE guest_list_entries
SET checked_in_at = NULL,
    checked_in_by = NULL
WHERE status <> 'CHECKED_IN';

CREATE INDEX IF NOT EXISTS idx_guest_list_entries_list_status ON guest_list_entries(guest_list_id, status);
CREATE INDEX IF NOT EXISTS idx_guest_lists_club_created_at ON guest_lists(club_id, created_at);
CREATE INDEX IF NOT EXISTS idx_events_club_start ON events(club_id, start_at);
CREATE INDEX IF NOT EXISTS idx_tables_club ON tables(club_id);
