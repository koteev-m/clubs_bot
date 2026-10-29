-- Explicit limits: old clients receive no new creation allowance.
ALTER TABLE clients ADD COLUMN allowed_clubs INTEGER NULL CHECK (allowed_clubs >= 0);
ALTER TABLE clients ADD COLUMN limit_version BIGINT NOT NULL DEFAULT 1 CHECK (limit_version > 0);
-- Old pending invitations keep the historical owner confirmation protocol.
ALTER TABLE client_invitations ADD COLUMN acceptance_policy VARCHAR(24) NOT NULL DEFAULT 'owner_confirmation'
    CHECK (acceptance_policy IN ('owner_confirmation','recipient_acceptance'));
ALTER TABLE client_commands DROP CONSTRAINT client_commands_result_type_check;
ALTER TABLE client_commands ADD CONSTRAINT client_commands_result_type_check
    CHECK (result_type IN ('invitation','application','member','club_create','club_profile','client_limit'));

-- Direct creations never fabricate an approved application or overwrite old saved fields.
CREATE TABLE client_direct_club_profiles (
    club_id BIGINT PRIMARY KEY CHECK (club_id > 0),
    client_id BIGINT NOT NULL CHECK (client_id > 0),
    owner_user_id BIGINT NOT NULL CHECK (owner_user_id > 0),
    application_id BIGINT NULL UNIQUE,
    venue_fingerprint VARCHAR(64) NOT NULL CHECK (char_length(venue_fingerprint) = 64),
    country_code VARCHAR(2) NOT NULL,
    city VARCHAR(128) NOT NULL,
    address VARCHAR(512) NOT NULL,
    unit VARCHAR(128) NOT NULL DEFAULT '',
    city_id VARCHAR(32) NOT NULL DEFAULT '',
    region VARCHAR(255) NOT NULL DEFAULT '',
    legacy_fingerprint VARCHAR(64) NOT NULL,
    profile_version BIGINT NOT NULL DEFAULT 1 CHECK (profile_version > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    profile_saved_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(client_id,venue_fingerprint),
    FOREIGN KEY(client_id,club_id) REFERENCES client_clubs(client_id,club_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY(client_id,owner_user_id) REFERENCES client_memberships(client_id,user_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY(client_id,application_id) REFERENCES client_club_applications(client_id,id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE FUNCTION guard_client_direct_club_profile() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'client_direct_club_profile_immutable' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.club_id,NEW.client_id,NEW.owner_user_id,NEW.application_id,NEW.venue_fingerprint,
           NEW.country_code,NEW.city,NEW.address,NEW.unit,NEW.city_id,NEW.region,NEW.legacy_fingerprint,NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.club_id,OLD.client_id,OLD.owner_user_id,OLD.application_id,OLD.venue_fingerprint,
           OLD.country_code,OLD.city,OLD.address,OLD.unit,OLD.city_id,OLD.region,OLD.legacy_fingerprint,OLD.created_at) THEN
        RAISE EXCEPTION 'client_direct_club_profile_immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER client_direct_club_profile_immutable BEFORE UPDATE OR DELETE ON client_direct_club_profiles
    FOR EACH ROW EXECUTE FUNCTION guard_client_direct_club_profile();
