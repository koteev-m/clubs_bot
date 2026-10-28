-- Stage B, including the same permanent consumption boundary as PostgreSQL.
INSERT INTO permissions(code) VALUES ('client.club.profile.write');
EXECUTE IMMEDIATE 'ALTER TABLE client_club_grants DROP CONSTRAINT "' ||
    (SELECT cc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc
     JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc ON tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME
         AND tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA
     WHERE tc.TABLE_SCHEMA=current_schema() AND LOWER(tc.TABLE_NAME)='client_club_grants'
         AND LOWER(cc.CHECK_CLAUSE) LIKE '%permission_code%') || '"';
ALTER TABLE client_club_grants ADD CONSTRAINT client_club_grants_permission_check
    CHECK (permission_code IN ('client.club.read','client.club.profile.write'));
EXECUTE IMMEDIATE 'ALTER TABLE client_commands DROP CONSTRAINT "' ||
    (SELECT cc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc
     JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc ON tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME
         AND tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA
     WHERE tc.TABLE_SCHEMA=current_schema() AND LOWER(tc.TABLE_NAME)='client_commands'
         AND LOWER(cc.CHECK_CLAUSE) LIKE '%result_type%') || '"';
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
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    profile_version BIGINT NOT NULL DEFAULT 1 CHECK (profile_version > 0),
    profile_saved_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(application_id,client_id,owner_user_id,application_status,approved_snapshot_hash,approved_snapshot_version)
        REFERENCES client_club_applications(id,client_id,author_user_id,status,approved_snapshot_hash,approved_snapshot_version)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY(client_id,club_id) REFERENCES client_clubs(client_id,club_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY(client_id,owner_user_id) REFERENCES client_memberships(client_id,user_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TRIGGER client_club_onboarding_immutable BEFORE UPDATE, DELETE ON client_club_onboarding
    FOR EACH ROW CALL "com.example.bot.data.client.onboarding.ClientClubOnboardingImmutableTrigger";
