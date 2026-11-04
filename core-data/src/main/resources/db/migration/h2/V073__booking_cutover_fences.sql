-- Empty by default. Ownership is anchored to both verified link identities.
ALTER TABLE canonical_night_event_links ADD CONSTRAINT uq_night_event_link_club
    UNIQUE (club_id, canonical_night_id);
ALTER TABLE hall_tables ADD CONSTRAINT uq_hall_table_link_club
    UNIQUE (booking_club_id, booking_table_id);

CREATE TABLE booking_cutover_fences (
    club_id BIGINT NOT NULL,
    canonical_night_id BIGINT NOT NULL,
    booking_table_id BIGINT NOT NULL,
    PRIMARY KEY (club_id, canonical_night_id, booking_table_id),
    FOREIGN KEY (club_id, canonical_night_id)
        REFERENCES canonical_night_event_links(club_id, canonical_night_id) ON DELETE RESTRICT,
    FOREIGN KEY (club_id, booking_table_id)
        REFERENCES hall_tables(booking_club_id, booking_table_id) ON DELETE RESTRICT
);

CREATE INDEX idx_booking_cutover_fences_table ON booking_cutover_fences(club_id, booking_table_id);
