CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_guest_lists_club_event
    ON guest_lists(club_id, event_id);

CREATE INDEX IF NOT EXISTS idx_guest_list_entries_full_name_trgm
    ON guest_list_entries
    USING gin (LOWER(full_name) gin_trgm_ops);
