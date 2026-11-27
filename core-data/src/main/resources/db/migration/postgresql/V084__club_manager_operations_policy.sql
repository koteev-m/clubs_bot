-- Preserve pinned invitation/assignment policies and explicitly amend proven live V1 club manager sources.
ALTER TABLE client_manager_invitations DROP CONSTRAINT client_manager_invitations_role_code_check;
ALTER TABLE client_manager_invitations ADD CONSTRAINT manager_invitation_role_code_check
    CHECK (role_code IN ('CLUB_MANAGER','NETWORK_MANAGER','FLOOR_MANAGER','ENTRANCE_MANAGER','FINANCE_MANAGER','PROMOTER'));

INSERT INTO permissions(code) VALUES
    ('client.club.booking_rules.read'),
    ('client.club.calendar.read'),
    ('client.club.layout.read'),
    ('client.club.night.create'),
    ('client.club.operations.analytics.read'),
    ('client.club.operations.booking.write'),
    ('client.club.operations.deposit.record'),
    ('client.club.operations.deposit_categories.write'),
    ('client.club.operations.read'),
    ('client.club.operations.release'),
    ('client.club.operations.seat'),
    ('client.club.operations.table_money.read'),
    ('client.club.operations.team.manage'),
    ('client.club.operations.top_up.record'),
    ('client.club.profile.read'),
    ('client.staff.delegate.booking_rules'),
    ('client.staff.delegate.calendar'),
    ('client.staff.delegate.layout'),
    ('client.staff.delegate.night_formation'),
    ('client.staff.delegate.profile');

ALTER TABLE client_role_assignments ADD COLUMN club_manager_operations_policy_hash VARCHAR(64)
    CHECK (club_manager_operations_policy_hash IS NULL OR
        (role_code='CLUB_MANAGER' AND policy_version=1
         AND policy_hash='79402dc78454fcaac9118535ed01bcf93644c985c71448b16865782c1e08695f'
         AND club_manager_operations_policy_hash='057f0c090d8b5d3f4d85b7707c0a1421364a3bc9722d47abb5ff3d0902a29e1a'));

UPDATE client_role_assignments a SET club_manager_operations_policy_hash='057f0c090d8b5d3f4d85b7707c0a1421364a3bc9722d47abb5ff3d0902a29e1a'
WHERE a.role_code='CLUB_MANAGER' AND a.policy_version=1
    AND a.policy_hash='79402dc78454fcaac9118535ed01bcf93644c985c71448b16865782c1e08695f'
    AND a.selector_kind='CLUB' AND a.selector_json='[]' AND a.status='ACTIVE'
    AND (a.expires_at IS NULL OR a.expires_at>CURRENT_TIMESTAMP)
    AND EXISTS (
        SELECT 1 FROM client_manager_invitations i
        JOIN client_club_access_state s ON s.client_id=i.client_id AND s.club_id=i.club_id AND s.user_id=i.claimant_user_id
        JOIN client_memberships m ON m.client_id=s.client_id AND m.user_id=s.user_id
        JOIN clients c ON c.id=m.client_id
        WHERE i.status='accepted' AND i.source_id=a.id AND i.claimant_user_id=a.user_id
            AND i.client_id=a.client_id AND i.club_id=a.club_id AND i.role_code=a.role_code
            AND i.policy_version=a.policy_version AND i.policy_hash=a.policy_hash
            AND s.mode='TARGET' AND s.status='ACTIVE' AND s.admission_epoch=a.admission_epoch
            AND m.is_active=TRUE AND m.version=s.membership_version AND c.is_active=TRUE AND c.archived_at IS NULL
    );

INSERT INTO audit_log(created_at,subject_user_id,club_id,entity_type,entity_id,action,fingerprint,metadata_json)
SELECT CURRENT_TIMESTAMP,user_id,club_id,'CLIENT_ONBOARDING',club_id,'client.club_manager.policy.v2',
    'club-manager-policy-v2-' || CAST(id AS VARCHAR(36)),
    '{"sourceId":"' || CAST(id AS VARCHAR(36)) || '","migration":"V084","fromPolicyVersion":1,"toPolicyVersion":2}'
FROM client_role_assignments WHERE club_manager_operations_policy_hash='057f0c090d8b5d3f4d85b7707c0a1421364a3bc9722d47abb5ff3d0902a29e1a';
