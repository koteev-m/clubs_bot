-- Accepted CLB-owner-team contract. Grants remain the sole authority store.
ALTER TABLE clubs ADD COLUMN team_version BIGINT NOT NULL DEFAULT 1 CHECK (team_version > 0);
INSERT INTO permissions(code) VALUES ('client.club.team.manage');
ALTER TABLE client_club_grants DROP CONSTRAINT client_club_grants_permission_check;
ALTER TABLE client_club_grants ADD CONSTRAINT client_club_grants_permission_check
    CHECK (permission_code IN ('client.club.read','client.club.profile.write','client.club.layout.write',
        'client.club.calendar.write','client.club.booking_rules.write','client.club.finance.read',
        'client.club.finance.write','client.club.modules.read','client.club.modules.write','client.club.team.manage'));
ALTER TABLE client_commands DROP CONSTRAINT client_commands_result_type_check;
ALTER TABLE client_commands ADD CONSTRAINT client_commands_result_type_check
    CHECK (result_type IN ('invitation','application','member','club_create','club_profile','client_limit',
        'club_layout','client_lifecycle','club_calendar','club_booking_rules','club_night_event_link',
        'club_finance','club_module_settings','club_team','staff_invitation'));
ALTER TABLE client_invitations ADD COLUMN purpose VARCHAR(8) NOT NULL DEFAULT 'client'
    CHECK (purpose IN ('client','staff'));
ALTER TABLE client_invitations ADD COLUMN club_id BIGINT NULL;
ALTER TABLE client_invitations ADD COLUMN staff_role VARCHAR(16) NULL;
ALTER TABLE client_invitations ADD COLUMN inviter_user_id BIGINT NULL;
ALTER TABLE client_invitations ADD COLUMN invitation_scope VARCHAR(64) NOT NULL DEFAULT 'client';
ALTER TABLE client_invitations ADD CONSTRAINT staff_invitation_scope_check CHECK (
    (purpose='client' AND club_id IS NULL AND staff_role IS NULL AND inviter_user_id IS NULL AND invitation_scope='client')
    OR (purpose='staff' AND club_id IS NOT NULL AND club_id>0 AND inviter_user_id IS NOT NULL AND inviter_user_id>0
        AND staff_role IS NOT NULL AND staff_role IN ('ENTRY_MANAGER','MANAGER','CLUB_ADMIN')
        AND invitation_scope=token_hash AND acceptance_policy='owner_confirmation'));
ALTER TABLE client_invitations ADD CONSTRAINT staff_invitation_club_fk FOREIGN KEY(client_id,club_id)
    REFERENCES client_clubs(client_id,club_id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE client_invitations ADD CONSTRAINT staff_invitation_inviter_fk FOREIGN KEY(inviter_user_id)
    REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE client_invitations ADD CONSTRAINT staff_invitation_identity UNIQUE(client_id,id,club_id);
-- Preserve the single open client-onboarding invitation; staff invitations have separate scopes.
ALTER TABLE client_invitations DROP CONSTRAINT client_invitations_client_id_open_marker_key;
ALTER TABLE client_invitations ADD CONSTRAINT invitation_open_scope_unique UNIQUE(client_id,invitation_scope,open_marker);
CREATE INDEX idx_staff_invitations_club ON client_invitations(club_id,id);
CREATE TABLE client_club_staff (
    client_id BIGINT NOT NULL CHECK (client_id>0),
    club_id BIGINT NOT NULL CHECK (club_id>0),
    user_id BIGINT NOT NULL CHECK (user_id>0),
    role_code VARCHAR(16) NOT NULL CHECK (role_code IN ('ENTRY_MANAGER','MANAGER','CLUB_ADMIN')),
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version>0),
    invitation_id BIGINT NOT NULL,
    revoked_through_invitation_id BIGINT NOT NULL DEFAULT 0 CHECK (revoked_through_invitation_id>=0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(client_id,club_id,user_id),
    FOREIGN KEY(client_id,user_id) REFERENCES client_memberships(client_id,user_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY(client_id,club_id) REFERENCES client_clubs(client_id,club_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY(client_id,invitation_id,club_id) REFERENCES client_invitations(client_id,id,club_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
);
-- Bounded upgrade for a unique immutable creator with all prior configuration grants active.
-- An existing row for this exact permission is a tombstone even when inactive: never recreate it.
INSERT INTO audit_log(created_at,actor_user_id,subject_user_id,club_id,entity_type,entity_id,action,fingerprint,metadata_json)
SELECT CURRENT_TIMESTAMP, NULL, o.owner_user_id, o.club_id, 'CLIENT_ONBOARDING', o.club_id,
       'client.club.team.manage.bootstrap', 'team-bootstrap-' || CAST(o.club_id AS VARCHAR),
       '{"migration":"V075","source":"confirmed_owner"}'
FROM (
    SELECT provenance.client_id,provenance.club_id,MIN(provenance.owner_user_id) AS owner_user_id
    FROM (
        SELECT client_id,club_id,owner_user_id FROM client_direct_club_profiles
        UNION
        SELECT client_id,club_id,owner_user_id FROM client_club_onboarding
    ) provenance
    GROUP BY provenance.client_id,provenance.club_id
    HAVING COUNT(DISTINCT provenance.owner_user_id)=1
) o
JOIN clients c ON c.id=o.client_id AND c.is_active=TRUE AND c.archived_at IS NULL
JOIN clubs club ON club.id=o.club_id AND club.is_active=FALSE
JOIN client_clubs mapping ON mapping.client_id=o.client_id AND mapping.club_id=o.club_id
JOIN client_memberships m ON m.client_id=o.client_id AND m.user_id=o.owner_user_id AND m.is_active=TRUE
JOIN client_club_grants r ON r.client_id=o.client_id AND r.club_id=o.club_id AND r.user_id=o.owner_user_id
    AND r.permission_code='client.club.read' AND r.is_active=TRUE
WHERE NOT EXISTS (SELECT 1 FROM client_club_grants g WHERE g.client_id=o.client_id
    AND g.club_id=o.club_id AND g.user_id=o.owner_user_id AND g.permission_code='client.club.team.manage');

INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active)
SELECT o.client_id,o.owner_user_id,o.club_id,'client.club.team.manage',TRUE
FROM (
    SELECT provenance.client_id,provenance.club_id,MIN(provenance.owner_user_id) AS owner_user_id
    FROM (
        SELECT client_id,club_id,owner_user_id FROM client_direct_club_profiles
        UNION
        SELECT client_id,club_id,owner_user_id FROM client_club_onboarding
    ) provenance
    GROUP BY provenance.client_id,provenance.club_id
    HAVING COUNT(DISTINCT provenance.owner_user_id)=1
) o
JOIN clients c ON c.id=o.client_id AND c.is_active=TRUE AND c.archived_at IS NULL
JOIN clubs club ON club.id=o.club_id AND club.is_active=FALSE
JOIN client_clubs mapping ON mapping.client_id=o.client_id AND mapping.club_id=o.club_id
JOIN client_memberships m ON m.client_id=o.client_id AND m.user_id=o.owner_user_id AND m.is_active=TRUE
JOIN client_club_grants r ON r.client_id=o.client_id AND r.club_id=o.club_id AND r.user_id=o.owner_user_id
    AND r.permission_code='client.club.read' AND r.is_active=TRUE
WHERE NOT EXISTS (SELECT 1 FROM client_club_grants g WHERE g.client_id=o.client_id
    AND g.club_id=o.club_id AND g.user_id=o.owner_user_id AND g.permission_code='client.club.team.manage');
