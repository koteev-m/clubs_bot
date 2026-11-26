-- Existing manager rows retain the two-party protocol. New issuances opt in explicitly.
ALTER TABLE client_manager_invitations ADD COLUMN acceptance_policy VARCHAR(24) NOT NULL DEFAULT 'owner_confirmation'
    CHECK (acceptance_policy IN ('owner_confirmation','recipient_acceptance'));
ALTER TABLE client_manager_invitations ADD COLUMN parent_invitation_id BIGINT NULL;
ALTER TABLE client_manager_invitations ADD CONSTRAINT manager_invitation_parent_fk
    FOREIGN KEY (client_id,parent_invitation_id) REFERENCES client_manager_invitations(client_id,id);
ALTER TABLE client_manager_invitations ADD CONSTRAINT manager_invitation_child_scope
    CHECK (parent_invitation_id IS NULL OR
        (parent_invitation_id < id AND role_code='NETWORK_MANAGER' AND acceptance_policy='recipient_acceptance'));
ALTER TABLE client_manager_invitations ADD CONSTRAINT manager_invitation_group_club UNIQUE(parent_invitation_id,club_id);
ALTER TABLE client_manager_invitations ADD CONSTRAINT manager_invitation_direct_state
    CHECK (acceptance_policy <> 'recipient_acceptance' OR status <> 'claimed');

-- Replacing a link claimed by a specific person preserves its recipient binding.
ALTER TABLE client_invitations ADD COLUMN bound_user_id BIGINT REFERENCES users(id);
ALTER TABLE client_invitations ADD CONSTRAINT client_invitation_bound_recipient
    CHECK (bound_user_id IS NULL OR
        (purpose='client' AND (claimant_user_id IS NULL OR bound_user_id=claimant_user_id)));
ALTER TABLE client_invitations DROP CONSTRAINT staff_invitation_scope_check;
ALTER TABLE client_invitations ADD CONSTRAINT staff_invitation_scope_check CHECK (
    (purpose='client' AND club_id IS NULL AND staff_role IS NULL AND invitation_scope='client')
    OR (purpose='staff' AND club_id IS NOT NULL AND club_id>0 AND inviter_user_id IS NOT NULL AND inviter_user_id>0
        AND staff_role IS NOT NULL AND staff_role IN ('ENTRY_MANAGER','MANAGER','CLUB_ADMIN')
        AND invitation_scope=token_hash));
ALTER TABLE client_invitations ADD CONSTRAINT direct_invitation_state
    CHECK (acceptance_policy <> 'recipient_acceptance' OR status <> 'claimed');
