-- Preserve the legacy identities and their child references.
ALTER TABLE events ADD CONSTRAINT uq_events_club_id UNIQUE (club_id, id);
ALTER TABLE club_operational_nights ADD CONSTRAINT uq_operational_nights_club_id UNIQUE (club_id, id);

ALTER TABLE client_commands DROP CONSTRAINT client_commands_result_type_check;
ALTER TABLE client_commands ADD CONSTRAINT client_commands_result_type_check
    CHECK (result_type IN ('invitation','application','member','club_create','club_profile','client_limit',
        'club_layout','client_lifecycle','club_calendar','club_booking_rules','club_night_event_link'));

CREATE TABLE canonical_night_event_links (
    canonical_night_id BIGINT PRIMARY KEY,
    event_id BIGINT NOT NULL UNIQUE,
    club_id BIGINT NOT NULL,
    legacy_start_at TIMESTAMP WITH TIME ZONE NOT NULL,
    legacy_end_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_canonical_night_event_window CHECK (legacy_end_at > legacy_start_at),
    CONSTRAINT fk_canonical_night_event_night
        FOREIGN KEY (club_id, canonical_night_id) REFERENCES club_operational_nights(club_id, id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_canonical_night_event_event
        FOREIGN KEY (club_id, event_id) REFERENCES events(club_id, id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
);
