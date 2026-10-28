-- Stage B. Legacy clubs remain unmapped; no role or automatic publication is introduced.
INSERT INTO permissions(code) VALUES ('client.club.profile.write');
ALTER TABLE client_club_grants DROP CONSTRAINT client_club_grants_permission_code_check;
ALTER TABLE client_club_grants ADD CONSTRAINT client_club_grants_permission_check
    CHECK (permission_code IN ('client.club.read','client.club.profile.write'));
ALTER TABLE client_commands DROP CONSTRAINT client_commands_result_type_check;
ALTER TABLE client_commands ADD CONSTRAINT client_commands_result_type_check
    CHECK (result_type IN ('invitation','application','member','club_create','club_profile'));

ALTER TABLE client_club_applications ADD CONSTRAINT client_application_approved_identity
    UNIQUE(id,client_id,author_user_id,status,approved_snapshot_hash,approved_snapshot_version);

CREATE TABLE client_club_onboarding (
    application_id BIGINT PRIMARY KEY CHECK (application_id > 0),
    client_id BIGINT NOT NULL CHECK (client_id > 0),
    owner_user_id BIGINT NOT NULL CHECK (owner_user_id > 0),
    club_id BIGINT NOT NULL UNIQUE CHECK (club_id > 0),
    application_status VARCHAR(16) NOT NULL DEFAULT 'approved' CHECK (application_status = 'approved'),
    approved_snapshot_hash VARCHAR(64) NOT NULL CHECK (char_length(approved_snapshot_hash) = 64),
    approved_snapshot_version BIGINT NOT NULL CHECK (approved_snapshot_version > 0),
    create_application_version BIGINT NOT NULL CHECK (create_application_version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    profile_version BIGINT NOT NULL DEFAULT 1 CHECK (profile_version > 0),
    profile_saved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(application_id,client_id,owner_user_id,application_status,approved_snapshot_hash,approved_snapshot_version)
        REFERENCES client_club_applications(id,client_id,author_user_id,status,approved_snapshot_hash,approved_snapshot_version)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY(client_id,club_id) REFERENCES client_clubs(client_id,club_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY(client_id,owner_user_id) REFERENCES client_memberships(client_id,user_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
);

-- Consumption is permanent. Only the profile concurrency marker and successful save time can change.
CREATE FUNCTION guard_client_club_onboarding() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'client_club_onboarding_immutable' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.application_id,NEW.client_id,NEW.owner_user_id,NEW.club_id,NEW.application_status,
           NEW.approved_snapshot_hash,NEW.approved_snapshot_version,NEW.create_application_version,NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.application_id,OLD.client_id,OLD.owner_user_id,OLD.club_id,OLD.application_status,
           OLD.approved_snapshot_hash,OLD.approved_snapshot_version,OLD.create_application_version,OLD.created_at) THEN
        RAISE EXCEPTION 'client_club_onboarding_immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER client_club_onboarding_immutable BEFORE UPDATE OR DELETE ON client_club_onboarding
    FOR EACH ROW EXECUTE FUNCTION guard_client_club_onboarding();
