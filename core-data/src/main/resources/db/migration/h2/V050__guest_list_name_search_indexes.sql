CREATE INDEX IF NOT EXISTS idx_guest_lists_club_event ON guest_lists(club_id, event_id);
CREATE INDEX IF NOT EXISTS idx_guest_list_entries_full_name ON guest_list_entries(full_name);
