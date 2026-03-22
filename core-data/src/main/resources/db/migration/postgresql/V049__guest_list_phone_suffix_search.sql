ALTER TABLE guest_list_entries ADD COLUMN IF NOT EXISTS phone_last_four VARCHAR(4) NULL;
CREATE INDEX IF NOT EXISTS idx_guest_list_entries_phone_last_four ON guest_list_entries(phone_last_four);

UPDATE guest_list_entries
SET phone_last_four = RIGHT(REGEXP_REPLACE(phone_e164, '[^0-9]', '', 'g'), 4)
WHERE phone_e164 IS NOT NULL
  AND phone_last_four IS NULL
  AND LENGTH(REGEXP_REPLACE(phone_e164, '[^0-9]', '', 'g')) >= 4;
