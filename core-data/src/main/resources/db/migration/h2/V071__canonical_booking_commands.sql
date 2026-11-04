-- V7 added the current status constraint but could not drop the anonymous V1
-- check in H2. Keep V7's named constraint and remove only the obsolete check.
EXECUTE IMMEDIATE 'ALTER TABLE bookings DROP CONSTRAINT IF EXISTS "' || COALESCE(
    (SELECT cc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc
     JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc ON tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME
         AND tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA
     WHERE tc.TABLE_SCHEMA=current_schema() AND LOWER(tc.TABLE_NAME)='bookings'
         AND LOWER(cc.CHECK_CLAUSE) LIKE '%status%'
         AND LOWER(cc.CHECK_CLAUSE) LIKE '%confirmed%'
         AND LOWER(cc.CHECK_CLAUSE) LIKE '%expired%'),
    'bookings_retired_v1_status_check') || '"';

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
