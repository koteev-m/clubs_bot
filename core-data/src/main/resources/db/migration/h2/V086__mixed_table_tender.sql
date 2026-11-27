-- Locate the previous inline check without relying on H2-generated names.
EXECUTE IMMEDIATE 'ALTER TABLE table_deposit_operations DROP CONSTRAINT "' || COALESCE(
    (SELECT cc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc
     JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc ON tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME
         AND tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA
     WHERE tc.TABLE_SCHEMA=CURRENT_SCHEMA AND LOWER(tc.TABLE_NAME)='table_deposit_operations'
         AND LOWER(cc.CHECK_CLAUSE) LIKE '%received_method%'),
    'missing_v085_tender_check') || '"';

ALTER TABLE table_deposit_operations ALTER COLUMN received_method VARCHAR(5);

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
