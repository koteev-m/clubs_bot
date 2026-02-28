ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS telegram_payment_charge_id text;

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS provider_payment_charge_id text;

CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_telegram_payment_charge_id
    ON payments (telegram_payment_charge_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_provider_payment_charge_id
    ON payments (provider_payment_charge_id);
