ALTER TABLE payments ADD COLUMN amount_minor BIGINT;

UPDATE payments
SET amount_minor = CAST(ROUND(amount * 100) AS BIGINT)
WHERE amount_minor IS NULL;

ALTER TABLE payments ALTER COLUMN amount_minor SET NOT NULL;

ALTER TABLE payments ADD COLUMN payload TEXT;

UPDATE payments
SET payload = 'legacy:' || idempotency_key
WHERE payload IS NULL;

ALTER TABLE payments ALTER COLUMN payload SET NOT NULL;
CREATE UNIQUE INDEX uq_payments_payload ON payments(payload);

ALTER TABLE payments ADD COLUMN status_v055 TEXT;
UPDATE payments SET status_v055 = status;
ALTER TABLE payments ALTER COLUMN status_v055 SET NOT NULL;
ALTER TABLE payments DROP COLUMN status;
ALTER TABLE payments ALTER COLUMN status_v055 RENAME TO status;
ALTER TABLE payments
    ADD CONSTRAINT payments_status_check
    CHECK (status IN ('INITIATED', 'PENDING', 'CAPTURED', 'REFUNDED', 'DECLINED'));

ALTER TABLE payments DROP COLUMN amount;
