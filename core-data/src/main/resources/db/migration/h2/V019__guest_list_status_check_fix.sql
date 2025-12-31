-- Normalize guest list status check after dropping legacy constraints
ALTER TABLE guest_lists ADD COLUMN status_tmp TEXT NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE guest_lists ADD CONSTRAINT guest_lists_status_check_tmp CHECK (status_tmp IN ('ACTIVE', 'CLOSED', 'CANCELLED'));
UPDATE guest_lists SET status_tmp = status;
DROP INDEX IF EXISTS idx_guest_lists_status;
ALTER TABLE guest_lists DROP COLUMN status;
ALTER TABLE guest_lists ALTER COLUMN status_tmp RENAME TO status;
CREATE INDEX IF NOT EXISTS idx_guest_lists_status ON guest_lists(status);
