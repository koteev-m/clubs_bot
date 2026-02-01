ALTER TABLE table_sessions
    ADD CONSTRAINT IF NOT EXISTS chk_table_sessions_open_marker_status
        CHECK (COALESCE(open_marker, 0) = CASE WHEN status = 'OPEN' THEN 1 ELSE 0 END);

ALTER TABLE table_sessions
    ADD CONSTRAINT IF NOT EXISTS uq_table_sessions_id_club_night_table
        UNIQUE (id, club_id, night_start_utc, table_id);

ALTER TABLE table_deposits
    ADD CONSTRAINT IF NOT EXISTS fk_table_deposits_table_session_consistency
        FOREIGN KEY (table_session_id, club_id, night_start_utc, table_id)
            REFERENCES table_sessions(id, club_id, night_start_utc, table_id);
