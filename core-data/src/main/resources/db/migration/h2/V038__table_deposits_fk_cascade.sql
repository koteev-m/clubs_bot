ALTER TABLE table_deposits
    DROP CONSTRAINT IF EXISTS fk_table_deposits_table_session_consistency;

ALTER TABLE table_deposits
    ADD CONSTRAINT fk_table_deposits_table_session_consistency
        FOREIGN KEY (table_session_id, club_id, night_start_utc, table_id)
            REFERENCES table_sessions(id, club_id, night_start_utc, table_id)
            ON DELETE CASCADE;
