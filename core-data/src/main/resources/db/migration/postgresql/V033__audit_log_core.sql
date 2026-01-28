-- Core audit log schema alignment
ALTER TABLE audit_log
    ADD COLUMN IF NOT EXISTS night_id BIGINT NULL,
    ADD COLUMN IF NOT EXISTS actor_user_id BIGINT NULL,
    ADD COLUMN IF NOT EXISTS actor_role TEXT NULL,
    ADD COLUMN IF NOT EXISTS subject_user_id BIGINT NULL,
    ADD COLUMN IF NOT EXISTS entity_type TEXT NULL,
    ADD COLUMN IF NOT EXISTS entity_id BIGINT NULL,
    ADD COLUMN IF NOT EXISTS fingerprint TEXT NULL,
    ADD COLUMN IF NOT EXISTS metadata_json TEXT NOT NULL DEFAULT '{}';

UPDATE audit_log
SET actor_user_id = user_id
WHERE actor_user_id IS NULL;

UPDATE audit_log
SET entity_type = 'LEGACY'
WHERE entity_type IS NULL;

UPDATE audit_log
SET metadata_json =
    (
        jsonb_build_object(
            'legacy_resource', resource,
            'legacy_resource_id', resource_id,
            'legacy_ip', ip,
            'legacy_result', result
        ) || COALESCE(meta, '{}'::jsonb)
    )::text
WHERE metadata_json = '{}'::text;

UPDATE audit_log
SET fingerprint = gen_random_uuid()::text
WHERE fingerprint IS NULL;

ALTER TABLE audit_log
    ALTER COLUMN entity_type SET NOT NULL,
    ALTER COLUMN fingerprint SET NOT NULL,
    ALTER COLUMN metadata_json SET NOT NULL;

ALTER TABLE audit_log
    DROP COLUMN IF EXISTS user_id,
    DROP COLUMN IF EXISTS resource,
    DROP COLUMN IF EXISTS resource_id,
    DROP COLUMN IF EXISTS ip,
    DROP COLUMN IF EXISTS result,
    DROP COLUMN IF EXISTS meta;

CREATE INDEX IF NOT EXISTS idx_audit_log_club_created_at
    ON audit_log (club_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor_created_at
    ON audit_log (actor_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_subject_created_at
    ON audit_log (subject_user_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS ux_audit_log_fingerprint
    ON audit_log (fingerprint);
