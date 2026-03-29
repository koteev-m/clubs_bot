CREATE INDEX IF NOT EXISTS idx_table_deposits_club_session_created_id_desc
    ON table_deposits (club_id, table_session_id, created_at DESC, id DESC);
