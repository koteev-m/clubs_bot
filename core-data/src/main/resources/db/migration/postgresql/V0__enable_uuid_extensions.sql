-- Ensure gen_random_uuid() exists in any environment.
-- Preferred: pgcrypto (provides gen_random_uuid()).
CREATE EXTENSION IF NOT EXISTS pgcrypto;

DO $do$
BEGIN
    -- If gen_random_uuid() still doesn't exist (e.g., pgcrypto unavailable on some hosts),
    -- fallback to uuid-ossp + a compat wrapper.
    IF to_regprocedure('gen_random_uuid()') IS NULL THEN
        CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

        CREATE OR REPLACE FUNCTION gen_random_uuid()
        RETURNS uuid
        LANGUAGE SQL
        VOLATILE
        AS $f$
            SELECT uuid_generate_v4();
        $f$;
    END IF;
END
$do$;