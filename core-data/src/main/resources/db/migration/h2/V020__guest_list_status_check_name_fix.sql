ALTER TABLE guest_lists DROP CONSTRAINT IF EXISTS guest_lists_status_check_tmp;
ALTER TABLE guest_lists DROP CONSTRAINT IF EXISTS guest_lists_status_check;
ALTER TABLE guest_lists ADD CONSTRAINT guest_lists_status_check
    CHECK (status IN ('ACTIVE', 'CLOSED', 'CANCELLED'));
