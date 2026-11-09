-- Additive task state only. Existing staff grants and invitation contracts remain unchanged.
ALTER TABLE client_commands DROP CONSTRAINT client_commands_result_type_check;
ALTER TABLE client_commands ADD CONSTRAINT client_commands_result_type_check
    CHECK (result_type IN ('invitation','application','member','club_create','club_profile','client_limit',
        'club_layout','client_lifecycle','club_calendar','club_booking_rules','club_night_event_link',
        'club_finance','club_module_settings','club_team','staff_invitation','club_preparation','prep_invite','prep_submit'));
CREATE TABLE client_club_preparation (
    client_id BIGINT NOT NULL CHECK (client_id>0),
    club_id BIGINT PRIMARY KEY CHECK (club_id>0),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version>0),
    status VARCHAR(24) NOT NULL DEFAULT 'unassigned'
        CHECK (status IN ('unassigned','invitation_pending','in_progress','submitted','accepted','returned')),
    assignee_user_id BIGINT NULL,
    invitation_id BIGINT NULL,
    reason VARCHAR(500) NULL,
    configuration_hash VARCHAR(64) NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(client_id,club_id) REFERENCES client_clubs(client_id,club_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY(client_id,assignee_user_id) REFERENCES client_memberships(client_id,user_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY(client_id,invitation_id,club_id) REFERENCES client_invitations(client_id,id,club_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CHECK ((status='unassigned' AND assignee_user_id IS NULL AND invitation_id IS NULL)
        OR (status='invitation_pending' AND assignee_user_id IS NULL AND invitation_id IS NOT NULL)
        OR (status IN ('in_progress','submitted','accepted','returned') AND assignee_user_id IS NOT NULL)),
    CHECK (status<>'returned' OR (reason IS NOT NULL AND LENGTH(TRIM(reason))>0)),
    CHECK (status NOT IN ('submitted','accepted') OR configuration_hash IS NOT NULL)
);
