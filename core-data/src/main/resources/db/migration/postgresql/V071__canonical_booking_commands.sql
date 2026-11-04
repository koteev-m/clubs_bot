-- Durable results survive deletion of a consumed or expired HOLD.
CREATE TABLE canonical_booking_commands (
    club_id BIGINT NOT NULL,
    canonical_night_id BIGINT NOT NULL,
    operation VARCHAR(8) NOT NULL,
    key_hash VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    result_id UUID NOT NULL,
    hold_expires_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (club_id, operation, key_hash),
    UNIQUE (operation, result_id),
    CHECK ((operation = 'HOLD' AND hold_expires_at IS NOT NULL) OR
           (operation = 'CONFIRM' AND hold_expires_at IS NULL)),
    FOREIGN KEY (club_id, canonical_night_id) REFERENCES club_operational_nights(club_id, id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
);
