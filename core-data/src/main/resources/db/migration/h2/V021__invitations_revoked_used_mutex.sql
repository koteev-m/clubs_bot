ALTER TABLE invitations
    DROP CONSTRAINT IF EXISTS invitations_revoked_and_used_mutual_exclusion;

-- Normalize legacy inconsistent rows produced before mutex rule:
UPDATE invitations
SET revoked_at = NULL
WHERE revoked_at IS NOT NULL AND used_at IS NOT NULL;

ALTER TABLE invitations
    ADD CONSTRAINT invitations_revoked_and_used_mutual_exclusion
        CHECK (NOT (revoked_at IS NOT NULL AND used_at IS NOT NULL));
