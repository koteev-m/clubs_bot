CREATE TABLE IF NOT EXISTS club_ops_chat_config (
    club_id BIGINT NOT NULL,
    chat_id BIGINT NOT NULL,
    bookings_thread_id INTEGER,
    checkin_thread_id INTEGER,
    support_thread_id INTEGER,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP(),
    PRIMARY KEY (club_id),
    FOREIGN KEY (club_id) REFERENCES clubs(id) ON DELETE CASCADE,
    CONSTRAINT chk_club_ops_chat_config_club_id_positive CHECK (club_id > 0),
    CONSTRAINT chk_club_ops_chat_config_chat_id_non_zero CHECK (chat_id <> 0),
    CONSTRAINT chk_club_ops_chat_config_bookings_thread_id_positive CHECK (bookings_thread_id IS NULL OR bookings_thread_id > 0),
    CONSTRAINT chk_club_ops_chat_config_checkin_thread_id_positive CHECK (checkin_thread_id IS NULL OR checkin_thread_id > 0),
    CONSTRAINT chk_club_ops_chat_config_support_thread_id_positive CHECK (support_thread_id IS NULL OR support_thread_id > 0)
);
