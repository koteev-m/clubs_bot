-- Keep existing ledger amounts and allocation snapshots immutable.
DO $$
DECLARE old_check text;
BEGIN
    SELECT conname INTO STRICT old_check
    FROM pg_constraint
    WHERE conrelid = 'table_deposit_operations'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%received_method%';
    EXECUTE format('ALTER TABLE table_deposit_operations DROP CONSTRAINT %I', old_check);
END $$;

ALTER TABLE table_deposit_operations ALTER COLUMN received_method TYPE VARCHAR(5);

ALTER TABLE table_deposit_operations ADD COLUMN received_cash_minor BIGINT;
ALTER TABLE table_deposit_operations ADD COLUMN received_card_minor BIGINT;
ALTER TABLE table_deposit_operations ADD CONSTRAINT table_deposit_received_tender_check CHECK (
    (received_method IS NULL AND received_cash_minor IS NULL AND received_card_minor IS NULL)
    OR (received_method IS NOT NULL AND amount_minor > 0 AND type IN ('INITIAL', 'TOPUP') AND (
        (received_method IN ('CASH', 'CARD') AND received_cash_minor IS NULL AND received_card_minor IS NULL)
        OR (received_method = 'MIXED'
            AND received_cash_minor IS NOT NULL AND received_card_minor IS NOT NULL
            AND received_cash_minor > 0 AND received_card_minor > 0
            AND received_cash_minor < amount_minor AND received_card_minor < amount_minor
            AND CAST(received_cash_minor AS DECIMAL(20, 0)) + received_card_minor = amount_minor)
    ))
);
