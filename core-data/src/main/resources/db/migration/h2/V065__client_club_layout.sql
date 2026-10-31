-- Exact client layout capability; legacy OWNER/GLOBAL and unmapped clubs are not adopted.
INSERT INTO permissions(code) VALUES ('client.club.layout.write');
ALTER TABLE client_club_grants DROP CONSTRAINT client_club_grants_permission_check;
ALTER TABLE client_club_grants ADD CONSTRAINT client_club_grants_permission_check
    CHECK (permission_code IN ('client.club.read','client.club.profile.write','client.club.layout.write'));
ALTER TABLE client_commands DROP CONSTRAINT client_commands_result_type_check;
ALTER TABLE client_commands ADD CONSTRAINT client_commands_result_type_check
    CHECK (result_type IN ('invitation','application','member','club_create','club_profile','client_limit','club_layout'));

-- A bounded, one-time upgrade for immutable creator provenance only. An existing inactive
-- layout row is a tombstone: never reactivate it, including after membership restoration.
INSERT INTO audit_log(created_at,actor_user_id,subject_user_id,club_id,entity_type,entity_id,action,fingerprint,metadata_json)
SELECT CURRENT_TIMESTAMP, NULL, o.owner_user_id, o.club_id, 'CLIENT_ONBOARDING', o.club_id,
       'client.club.layout.bootstrap', 'layout-bootstrap-' || CAST(o.club_id AS VARCHAR),
       '{"migration":"V065","source":"confirmed_owner"}'
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
JOIN clients c ON c.id=o.client_id AND c.is_active=TRUE
JOIN clubs club ON club.id=o.club_id AND club.is_active=FALSE
JOIN client_clubs mapping ON mapping.client_id=o.client_id AND mapping.club_id=o.club_id
JOIN client_memberships m ON m.client_id=o.client_id AND m.user_id=o.owner_user_id AND m.is_active=TRUE
JOIN client_club_grants r ON r.client_id=o.client_id AND r.club_id=o.club_id AND r.user_id=o.owner_user_id
    AND r.permission_code='client.club.read' AND r.is_active=TRUE
JOIN client_club_grants p ON p.client_id=o.client_id AND p.club_id=o.club_id AND p.user_id=o.owner_user_id
    AND p.permission_code='client.club.profile.write' AND p.is_active=TRUE
WHERE NOT EXISTS (SELECT 1 FROM client_club_grants g WHERE g.client_id=o.client_id
    AND g.club_id=o.club_id AND g.user_id=o.owner_user_id AND g.permission_code='client.club.layout.write');

INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active)
SELECT o.client_id,o.owner_user_id,o.club_id,'client.club.layout.write',TRUE
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
JOIN clients c ON c.id=o.client_id AND c.is_active=TRUE
JOIN clubs club ON club.id=o.club_id AND club.is_active=FALSE
JOIN client_clubs mapping ON mapping.client_id=o.client_id AND mapping.club_id=o.club_id
JOIN client_memberships m ON m.client_id=o.client_id AND m.user_id=o.owner_user_id AND m.is_active=TRUE
JOIN client_club_grants r ON r.client_id=o.client_id AND r.club_id=o.club_id AND r.user_id=o.owner_user_id
    AND r.permission_code='client.club.read' AND r.is_active=TRUE
JOIN client_club_grants p ON p.client_id=o.client_id AND p.club_id=o.club_id AND p.user_id=o.owner_user_id
    AND p.permission_code='client.club.profile.write' AND p.is_active=TRUE
WHERE NOT EXISTS (SELECT 1 FROM client_club_grants g WHERE g.client_id=o.client_id
    AND g.club_id=o.club_id AND g.user_id=o.owner_user_id AND g.permission_code='client.club.layout.write');
