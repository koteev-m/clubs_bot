-- Eligibility is written only with a new, confirmed owner-created club. No legacy backfill.
CREATE TABLE client_working_clubs (
    club_id BIGINT PRIMARY KEY,
    client_id BIGINT NOT NULL,
    scheduled_through DATE,
    FOREIGN KEY (client_id,club_id) REFERENCES client_clubs(client_id,club_id) ON DELETE RESTRICT
);

CREATE TABLE client_working_tables (
    table_id BIGINT PRIMARY KEY REFERENCES tables(id) ON DELETE RESTRICT,
    club_id BIGINT NOT NULL REFERENCES client_working_clubs(club_id) ON DELETE RESTRICT
);

CREATE TABLE client_working_night_events (
    club_id BIGINT NOT NULL REFERENCES client_working_clubs(club_id) ON DELETE RESTRICT,
    night_id BIGINT NOT NULL PRIMARY KEY,
    event_id BIGINT NOT NULL UNIQUE,
    FOREIGN KEY (club_id,night_id) REFERENCES club_operational_nights(club_id,id) ON DELETE RESTRICT,
    FOREIGN KEY (club_id,event_id) REFERENCES events(club_id,id) ON DELETE RESTRICT
);
CREATE INDEX idx_client_working_night_events_club ON client_working_night_events(club_id,night_id);
