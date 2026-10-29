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

CREATE TRIGGER client_direct_club_profile_immutable BEFORE UPDATE, DELETE ON client_direct_club_profiles
    FOR EACH ROW CALL "com.example.bot.data.client.onboarding.DirectClubProfileImmutableTrigger";
