ALTER TABLE tickets
    DROP CONSTRAINT tickets_status_check;

ALTER TABLE tickets
    ADD CONSTRAINT tickets_status_check
    CHECK (status IN ('new', 'opened', 'in_progress', 'answered', 'closed'));
