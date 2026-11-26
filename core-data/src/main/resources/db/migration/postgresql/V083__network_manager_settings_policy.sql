-- Amend only live network sources issued and accepted under the original V081 policy.
-- Keep the invitation, its creation policy, the assignment version and issuer fences intact.
-- An invitation pending during upgrade retains its original policy even if confirmed later.
ALTER TABLE client_role_assignments ADD COLUMN network_settings_policy_hash VARCHAR(64)
    CHECK (network_settings_policy_hash IS NULL OR
        (role_code='NETWORK_MANAGER' AND policy_version=1
         AND policy_hash='8696dee290220fc8c929ad4d9ca4645628628aa550adfb6ca1e53a9f06b307d1'
         AND network_settings_policy_hash='c801582561b0e003198f4f8d2e9673a9e48198a3253e66dba747222db3019d57'));

UPDATE client_role_assignments a SET network_settings_policy_hash='c801582561b0e003198f4f8d2e9673a9e48198a3253e66dba747222db3019d57'
WHERE a.role_code='NETWORK_MANAGER' AND a.policy_version=1
    AND a.policy_hash='8696dee290220fc8c929ad4d9ca4645628628aa550adfb6ca1e53a9f06b307d1'
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
SELECT CURRENT_TIMESTAMP,user_id,club_id,'CLIENT_ONBOARDING',club_id,'client.manager.policy.v2',
    'manager-policy-v2-' || CAST(id AS VARCHAR(36)),
    '{"sourceId":"' || CAST(id AS VARCHAR(36)) || '","migration":"V083","fromPolicyVersion":1,"toPolicyVersion":2}'
FROM client_role_assignments WHERE network_settings_policy_hash='c801582561b0e003198f4f8d2e9673a9e48198a3253e66dba747222db3019d57';
