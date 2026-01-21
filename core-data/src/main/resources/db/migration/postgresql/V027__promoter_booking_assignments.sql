CREATE TABLE promoter_booking_assignments (
    entry_id BIGINT PRIMARY KEY REFERENCES guest_list_entries(id) ON DELETE CASCADE,
    booking_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX promoter_booking_assignments_booking_id_idx
    ON promoter_booking_assignments (booking_id);
