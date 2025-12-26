-- Guest list core schema refresh for invitations and check-ins
ALTER TABLE guest_lists
    ADD COLUMN IF NOT EXISTS promoter_id BIGINT NULL REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS "limit" INT NULL;

UPDATE guest_lists
SET "limit" = capacity
WHERE "limit" IS NULL;

ALTER TABLE guest_lists
    DROP CONSTRAINT IF EXISTS guest_lists_limit_check;
ALTER TABLE guest_lists
    ADD CONSTRAINT guest_lists_limit_check CHECK ("limit" IS NULL OR "limit" > 0);

ALTER TABLE guest_lists
    DROP CONSTRAINT IF EXISTS guest_lists_status_check;
ALTER TABLE guest_lists
    ADD CONSTRAINT guest_lists_status_check CHECK (status IN ('ACTIVE', 'CLOSED', 'CANCELLED'));

CREATE INDEX IF NOT EXISTS idx_guest_lists_club_event ON guest_lists(club_id, event_id);
CREATE INDEX IF NOT EXISTS idx_guest_lists_promoter ON guest_lists(promoter_id);
CREATE INDEX IF NOT EXISTS idx_guest_lists_status ON guest_lists(status);

ALTER TABLE guest_list_entries
    ADD COLUMN IF NOT EXISTS display_name TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS telegram_user_id BIGINT NULL,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE guest_list_entries
SET display_name = full_name
WHERE display_name = '';

ALTER TABLE guest_list_entries
    DROP CONSTRAINT IF EXISTS guest_list_entries_status_check;
ALTER TABLE guest_list_entries
    ADD CONSTRAINT guest_list_entries_status_check CHECK (
        status IN (
            'ADDED', 'INVITED', 'CONFIRMED', 'DECLINED', 'ARRIVED', 'LATE', 'DENIED',
            'PLANNED', 'CHECKED_IN', 'NO_SHOW', 'WAITLISTED', 'CALLED', 'EXPIRED'
        )
    );
ALTER TABLE guest_list_entries
    ALTER COLUMN display_name DROP DEFAULT;
ALTER TABLE guest_list_entries
    ALTER COLUMN status SET DEFAULT 'ADDED';

CREATE INDEX IF NOT EXISTS idx_guest_list_entries_guest_list_id ON guest_list_entries(guest_list_id);
CREATE INDEX IF NOT EXISTS idx_guest_list_entries_list_status ON guest_list_entries(guest_list_id, status);
CREATE INDEX IF NOT EXISTS idx_guest_list_entries_telegram_user ON guest_list_entries(telegram_user_id);

CREATE TABLE IF NOT EXISTS invitations (
    id BIGSERIAL PRIMARY KEY,
    guest_list_entry_id BIGINT NOT NULL REFERENCES guest_list_entries(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL,
    channel TEXT NOT NULL CHECK (channel IN ('TELEGRAM', 'EXTERNAL')),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NULL,
    used_at TIMESTAMPTZ NULL,
    created_by BIGINT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_invitations_token_hash ON invitations(token_hash);
CREATE INDEX IF NOT EXISTS idx_invitations_entry ON invitations(guest_list_entry_id);
CREATE INDEX IF NOT EXISTS idx_invitations_expires_at ON invitations(expires_at);

CREATE TABLE IF NOT EXISTS checkins (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NULL REFERENCES clubs(id) ON DELETE SET NULL,
    event_id BIGINT NULL REFERENCES events(id) ON DELETE SET NULL,
    subject_type TEXT NOT NULL CHECK (subject_type IN ('BOOKING', 'GUEST_LIST_ENTRY')),
    subject_id BIGINT NOT NULL,
    checked_by BIGINT NULL REFERENCES users(id),
    method TEXT NOT NULL CHECK (method IN ('QR', 'NAME')),
    result_status TEXT NOT NULL CHECK (result_status IN ('ARRIVED', 'LATE', 'DENIED')),
    deny_reason TEXT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (subject_type, subject_id)
);
