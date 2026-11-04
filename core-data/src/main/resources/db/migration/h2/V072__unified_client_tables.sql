-- Existing coordinates preserve the placement previously presented by the editor.
ALTER TABLE hall_tables ADD COLUMN is_placed BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE hall_tables ADD COLUMN booking_table_id BIGINT;
ALTER TABLE hall_tables ADD COLUMN booking_club_id BIGINT;

ALTER TABLE halls ADD CONSTRAINT uq_halls_id_club UNIQUE (id, club_id);
ALTER TABLE tables ADD CONSTRAINT uq_tables_client_projection UNIQUE (id, club_id, table_number, capacity);
ALTER TABLE hall_tables ADD CONSTRAINT uq_hall_tables_booking_table UNIQUE (booking_table_id);
ALTER TABLE hall_tables ADD CONSTRAINT ck_hall_tables_booking_link
    CHECK ((booking_table_id IS NULL AND booking_club_id IS NULL)
        OR (booking_table_id IS NOT NULL AND booking_club_id IS NOT NULL AND min_deposit = 0));
ALTER TABLE hall_tables ADD CONSTRAINT fk_hall_tables_booking_hall
    FOREIGN KEY (hall_id, booking_club_id) REFERENCES halls(id, club_id) ON DELETE RESTRICT;
ALTER TABLE hall_tables ADD CONSTRAINT fk_hall_tables_booking_table
    FOREIGN KEY (booking_table_id, booking_club_id, table_number, capacity)
    REFERENCES tables(id, club_id, table_number, capacity) ON UPDATE CASCADE ON DELETE RESTRICT;
