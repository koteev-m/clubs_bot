CREATE INDEX IF NOT EXISTS idx_tickets_club_updated_at ON tickets(club_id, updated_at DESC);
