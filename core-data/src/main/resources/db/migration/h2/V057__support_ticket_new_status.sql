ALTER TABLE tickets ADD COLUMN status_v057 TEXT AFTER status;

UPDATE tickets SET status_v057 = status;

ALTER TABLE tickets ALTER COLUMN status_v057 SET NOT NULL;

ALTER TABLE tickets
    ADD CONSTRAINT tickets_status_check
    CHECK (status_v057 IN ('new', 'opened', 'in_progress', 'answered', 'closed'));

DROP INDEX IF EXISTS idx_tickets_club_status_updated_at;

ALTER TABLE tickets DROP COLUMN status;
ALTER TABLE tickets ALTER COLUMN status_v057 RENAME TO status;

CREATE INDEX IF NOT EXISTS idx_tickets_club_status_updated_at
    ON tickets(club_id, status, updated_at DESC);
