-- Inactive additive role foundation: no grants, adoption, backfill or runtime cutover.
-- Immutable/terminal transitions are enforced by the new transactional CAS writer;
-- these constraints retain exact scope, references and revocation metadata at rest.
ALTER TABLE client_commands DROP CONSTRAINT client_commands_result_type_check;
ALTER TABLE client_commands ADD CONSTRAINT client_commands_result_type_check
    CHECK (result_type IN ('invitation','application','member','club_create','club_profile','client_limit',
        'club_layout','client_lifecycle','club_calendar','club_booking_rules','club_night_event_link',
        'club_finance','club_module_settings','club_team','staff_invitation','club_preparation',
        'prep_invite','prep_submit','role_foundation'));

CREATE TABLE client_club_access_state (
    client_id BIGINT NOT NULL CHECK (client_id > 0),
    club_id BIGINT NOT NULL CHECK (club_id > 0),
    user_id BIGINT NOT NULL CHECK (user_id > 0),
    status VARCHAR(8) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACTIVE','DISABLED')),
    mode VARCHAR(8) NOT NULL DEFAULT 'SHADOW' CHECK (mode IN ('COMPAT','SHADOW','TARGET')),
    admission_epoch BIGINT NOT NULL DEFAULT 1 CHECK (admission_epoch > 0),
    membership_version BIGINT NULL CHECK (membership_version IS NULL OR membership_version > 0),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    revoked_through_invitation_id BIGINT NOT NULL DEFAULT 0 CHECK (revoked_through_invitation_id >= 0),
    compatibility_manifest TEXT NULL,
    compatibility_hash VARCHAR(64) NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP WITH TIME ZONE NULL,
    revoked_by BIGINT NULL,
    revoke_reason VARCHAR(500) NULL,
    PRIMARY KEY (client_id,club_id,user_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY (client_id,club_id) REFERENCES client_clubs(client_id,club_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY (revoked_by) REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CHECK (status<>'ACTIVE' OR membership_version IS NOT NULL),
    CHECK ((compatibility_manifest IS NULL AND compatibility_hash IS NULL) OR
        (compatibility_manifest IS NOT NULL AND CHAR_LENGTH(compatibility_manifest) BETWEEN 2 AND 65536
            AND compatibility_hash IS NOT NULL AND REGEXP_LIKE(compatibility_hash,'^[0-9a-f]{64}$'))),
    CHECK ((status IN ('PENDING','ACTIVE') AND revoked_at IS NULL AND revoked_by IS NULL AND revoke_reason IS NULL) OR
        (status='DISABLED' AND revoked_at IS NOT NULL AND revoked_by IS NOT NULL
            AND revoke_reason IS NOT NULL AND CHAR_LENGTH(TRIM(revoke_reason)) BETWEEN 1 AND 500))
);

CREATE TABLE client_role_assignments (
    id UUID PRIMARY KEY,
    client_id BIGINT NOT NULL CHECK (client_id > 0),
    club_id BIGINT NOT NULL CHECK (club_id > 0),
    user_id BIGINT NOT NULL CHECK (user_id > 0),
    role_code VARCHAR(24) NOT NULL CHECK (role_code IN
        ('NETWORK_MANAGER','CLUB_MANAGER','FLOOR_MANAGER','ENTRANCE_MANAGER','FINANCE_MANAGER','PROMOTER')),
    policy_version BIGINT NOT NULL CHECK (policy_version > 0),
    policy_hash VARCHAR(64) NOT NULL CHECK (REGEXP_LIKE(policy_hash,'^[0-9a-f]{64}$')),
    selector_kind VARCHAR(32) NOT NULL CHECK (selector_kind IN ('CLUB','SHIFT_SET','ALL_SHIFTS_OF_THIS_CLUB')),
    selector_json TEXT NOT NULL CHECK (CHAR_LENGTH(selector_json) BETWEEN 2 AND 8192),
    admission_epoch BIGINT NOT NULL CHECK (admission_epoch > 0),
    status VARCHAR(8) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    issuer_authority_ref TEXT NOT NULL CHECK (CHAR_LENGTH(TRIM(issuer_authority_ref)) BETWEEN 1 AND 1024),
    created_by BIGINT NOT NULL,
    command_connection_key VARCHAR(64) NOT NULL CHECK (CHAR_LENGTH(command_connection_key) BETWEEN 1 AND 64),
    command_key_hash VARCHAR(64) NOT NULL CHECK (REGEXP_LIKE(command_key_hash,'^[0-9a-f]{64}$')),
    created_audit_id BIGINT NOT NULL,
    last_audit_id BIGINT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP WITH TIME ZONE NULL,
    revoked_by BIGINT NULL,
    revoke_reason VARCHAR(500) NULL,
    UNIQUE (client_id,club_id,user_id,id),
    FOREIGN KEY (client_id,user_id) REFERENCES client_memberships(client_id,user_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY (client_id,club_id) REFERENCES client_clubs(client_id,club_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY (client_id,club_id,user_id) REFERENCES client_club_access_state(client_id,club_id,user_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY (created_by,command_connection_key,command_key_hash)
        REFERENCES client_commands(actor_user_id,connection_key,key_hash) ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY (created_audit_id) REFERENCES audit_log(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY (last_audit_id) REFERENCES audit_log(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY (revoked_by) REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CHECK (expires_at IS NULL OR expires_at>created_at),
    CHECK ((selector_kind IN ('CLUB','ALL_SHIFTS_OF_THIS_CLUB') AND selector_json='[]') OR
        (selector_kind='SHIFT_SET' AND selector_json<>'[]')),
    CHECK ((status='ACTIVE' AND revoked_at IS NULL AND revoked_by IS NULL AND revoke_reason IS NULL) OR
        (status='REVOKED' AND revoked_at IS NOT NULL AND revoked_by IS NOT NULL
            AND revoke_reason IS NOT NULL AND CHAR_LENGTH(TRIM(revoke_reason)) BETWEEN 1 AND 500))
);
CREATE INDEX idx_role_assignments_subject ON client_role_assignments(client_id,club_id,user_id,status);

CREATE TABLE client_permission_delegations (
    id UUID PRIMARY KEY,
    client_id BIGINT NOT NULL CHECK (client_id > 0),
    club_id BIGINT NOT NULL CHECK (club_id > 0),
    user_id BIGINT NOT NULL CHECK (user_id > 0),
    permission_code TEXT NOT NULL,
    recipient_assignment_id UUID NOT NULL,
    issuer_assignment_id UUID NOT NULL,
    issuer_user_id BIGINT NOT NULL CHECK (issuer_user_id > 0),
    policy_version BIGINT NOT NULL CHECK (policy_version > 0),
    policy_hash VARCHAR(64) NOT NULL CHECK (REGEXP_LIKE(policy_hash,'^[0-9a-f]{64}$')),
    admission_epoch BIGINT NOT NULL CHECK (admission_epoch > 0),
    issuer_admission_epoch BIGINT NOT NULL CHECK (issuer_admission_epoch > 0),
    status VARCHAR(8) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    issuer_authority_ref TEXT NOT NULL CHECK (CHAR_LENGTH(TRIM(issuer_authority_ref)) BETWEEN 1 AND 1024),
    created_by BIGINT NOT NULL,
    command_connection_key VARCHAR(64) NOT NULL CHECK (CHAR_LENGTH(command_connection_key) BETWEEN 1 AND 64),
    command_key_hash VARCHAR(64) NOT NULL CHECK (REGEXP_LIKE(command_key_hash,'^[0-9a-f]{64}$')),
    created_audit_id BIGINT NOT NULL,
    last_audit_id BIGINT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP WITH TIME ZONE NULL,
    revoked_by BIGINT NULL,
    revoke_reason VARCHAR(500) NULL,
    FOREIGN KEY (permission_code) REFERENCES permissions(code) ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY (client_id,club_id,user_id,recipient_assignment_id)
        REFERENCES client_role_assignments(client_id,club_id,user_id,id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY (client_id,club_id,issuer_user_id,issuer_assignment_id)
        REFERENCES client_role_assignments(client_id,club_id,user_id,id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY (created_by,command_connection_key,command_key_hash)
        REFERENCES client_commands(actor_user_id,connection_key,key_hash) ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY (created_audit_id) REFERENCES audit_log(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY (last_audit_id) REFERENCES audit_log(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY (revoked_by) REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CHECK (issuer_user_id<>user_id AND issuer_assignment_id<>recipient_assignment_id),
    CHECK (expires_at>created_at),
    CHECK ((status='ACTIVE' AND revoked_at IS NULL AND revoked_by IS NULL AND revoke_reason IS NULL) OR
        (status='REVOKED' AND revoked_at IS NOT NULL AND revoked_by IS NOT NULL
            AND revoke_reason IS NOT NULL AND CHAR_LENGTH(TRIM(revoke_reason)) BETWEEN 1 AND 500))
);
CREATE INDEX idx_permission_delegations_recipient ON client_permission_delegations(client_id,club_id,user_id,status);
CREATE INDEX idx_permission_delegations_issuer ON client_permission_delegations(issuer_assignment_id,status);
