-- Staff-agreed total. Existing reservations have no explicit staff agreement.
-- Financial ledgers and guest checkout continue using their existing fields.
ALTER TABLE bookings ADD COLUMN staff_booking_deposit_minor BIGINT
    CONSTRAINT ck_staff_booking_deposit_minor CHECK (staff_booking_deposit_minor BETWEEN 0 AND 999999999999);
