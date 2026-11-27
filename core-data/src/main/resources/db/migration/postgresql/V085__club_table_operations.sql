ALTER TABLE client_commands DROP CONSTRAINT client_commands_result_type_check;
ALTER TABLE client_commands ADD CONSTRAINT client_commands_result_type_check
    CHECK (result_type IN ('invitation','application','member','club_create','club_profile','client_limit',
        'club_layout','client_lifecycle','club_calendar','club_booking_rules','club_night_event_link',
        'club_finance','club_module_settings','club_team','staff_invitation','club_preparation',
        'prep_invite','prep_submit','role_foundation','manager_roster','manager_claim','manager_issue',
        'club_operations','club_deposit_config','club_report_grant','club_night_formation'));


INSERT INTO permissions(code) VALUES ('client.club.operations.financial_reports.read');

CREATE TABLE club_deposit_configuration (
    club_id BIGINT PRIMARY KEY REFERENCES clubs(id),
    version BIGINT NOT NULL CHECK (version > 1),
    distribution BOOLEAN NOT NULL,
    primary_name VARCHAR(80) NOT NULL
);

CREATE TABLE club_deposit_categories (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NOT NULL REFERENCES club_deposit_configuration(club_id),
    name VARCHAR(80) NOT NULL,
    active BOOLEAN NOT NULL
);
CREATE INDEX idx_club_deposit_categories_club ON club_deposit_categories(club_id, id);

ALTER TABLE table_sessions ADD COLUMN staff_booking_id UUID REFERENCES bookings(id);
ALTER TABLE table_sessions ADD COLUMN seating_mode VARCHAR(7) CHECK (seating_mode IN ('DEPOSIT','BILL','CLUB'));
ALTER TABLE table_sessions ADD COLUMN seated_guests INT CHECK (seated_guests BETWEEN 1 AND 500);
CREATE INDEX idx_table_sessions_staff_booking ON table_sessions(staff_booking_id);

ALTER TABLE table_deposit_operations ADD COLUMN received_method VARCHAR(4)
    CHECK (received_method IS NULL OR (received_method IN ('CASH','CARD') AND amount_minor > 0 AND type IN ('INITIAL','TOPUP')));
ALTER TABLE table_deposit_operation_allocations ADD COLUMN category_name_snapshot VARCHAR(80);

CREATE TABLE client_table_operation_receipts (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES clients(id),
    club_id BIGINT NOT NULL REFERENCES clubs(id),
    night_id BIGINT NOT NULL REFERENCES club_operational_nights(id),
    hall_table_id BIGINT NOT NULL REFERENCES hall_tables(id),
    session_id BIGINT REFERENCES table_sessions(id),
    operation_id BIGINT REFERENCES table_deposit_operations(id),
    booking_id UUID REFERENCES bookings(id),
    action VARCHAR(24) NOT NULL
);

CREATE TABLE client_club_financial_report_access (
    client_id BIGINT NOT NULL REFERENCES clients(id),
    club_id BIGINT NOT NULL REFERENCES clubs(id),
    assignment_id UUID PRIMARY KEY REFERENCES client_role_assignments(id),
    granted_by BIGINT NOT NULL REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL,
    active BOOLEAN NOT NULL,
    version BIGINT NOT NULL CHECK (version > 1)
);

CREATE TABLE client_night_table_sales_stops (
    club_id BIGINT NOT NULL REFERENCES clubs(id),
    night_id BIGINT NOT NULL REFERENCES club_operational_nights(id),
    booking_table_id BIGINT NOT NULL REFERENCES tables(id),
    PRIMARY KEY(club_id, night_id, booking_table_id)
);

ALTER TABLE bookings ADD COLUMN staff_note VARCHAR(500);
ALTER TABLE bookings ADD COLUMN staff_wait_until TIMESTAMPTZ;
ALTER TABLE bookings ADD COLUMN staff_closed_session_id BIGINT REFERENCES table_sessions(id);
ALTER TABLE bookings ADD COLUMN staff_cancellation_reason VARCHAR(500);

DROP INDEX uq_bookings_active_slot;
CREATE UNIQUE INDEX uq_bookings_active_slot ON bookings(table_id, slot_start, slot_end)
    WHERE status IN ('BOOKED','SEATED') AND staff_closed_session_id IS NULL;
ALTER TABLE bookings ADD COLUMN staff_created BOOLEAN NOT NULL DEFAULT FALSE;
