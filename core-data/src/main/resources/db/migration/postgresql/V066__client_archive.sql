-- An archive is independent of onboarding activation and preserves every historical row.
ALTER TABLE clients ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE NULL;
ALTER TABLE clients ADD COLUMN lifecycle_version BIGINT NOT NULL DEFAULT 1 CHECK (lifecycle_version > 0);
CREATE INDEX idx_clients_archive_id ON clients(archived_at,id);
ALTER TABLE client_commands DROP CONSTRAINT client_commands_result_type_check;
ALTER TABLE client_commands ADD CONSTRAINT client_commands_result_type_check
    CHECK (result_type IN ('invitation','application','member','club_create','club_profile','client_limit','club_layout','client_lifecycle'));
