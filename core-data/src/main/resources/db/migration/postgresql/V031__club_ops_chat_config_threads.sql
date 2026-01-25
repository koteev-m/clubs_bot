ALTER TABLE club_ops_chat_config
    ADD COLUMN IF NOT EXISTS guest_lists_thread_id INTEGER,
    ADD COLUMN IF NOT EXISTS alerts_thread_id INTEGER;

ALTER TABLE club_ops_chat_config
    ADD CONSTRAINT chk_club_ops_chat_config_guest_lists_thread_id_positive
        CHECK (guest_lists_thread_id IS NULL OR guest_lists_thread_id > 0),
    ADD CONSTRAINT chk_club_ops_chat_config_alerts_thread_id_positive
        CHECK (alerts_thread_id IS NULL OR alerts_thread_id > 0);
