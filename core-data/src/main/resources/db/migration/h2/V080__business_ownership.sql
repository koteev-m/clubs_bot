-- Explicit platform confirmation. Historical membership and club creation alone grant no ownership.
CREATE TABLE client_business_owners (
    client_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    member_version BIGINT NOT NULL CHECK (member_version > 0),
    confirmed_by BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (client_id, user_id),
    FOREIGN KEY (client_id, user_id) REFERENCES client_memberships(client_id, user_id) ON DELETE RESTRICT
);

CREATE TABLE client_business_clubs (
    client_id BIGINT NOT NULL,
    club_id BIGINT NOT NULL PRIMARY KEY,
    manager_roster_version BIGINT NOT NULL DEFAULT 1 CHECK (manager_roster_version > 0),
    profile_version BIGINT NOT NULL DEFAULT 1 CHECK (profile_version > 0),
    profile_saved_at TIMESTAMP WITH TIME ZONE NULL,
    user_id BIGINT NOT NULL,
    evidence VARCHAR(24) NOT NULL CHECK (evidence IN ('OWNER_CREATE', 'VERIFIED_CREATION', 'PLATFORM_CONFIRMATION')),
    confirmed_by BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (client_id, user_id) REFERENCES client_business_owners(client_id, user_id) ON DELETE RESTRICT,
    FOREIGN KEY (client_id, club_id) REFERENCES client_clubs(client_id, club_id) ON DELETE RESTRICT
);
CREATE INDEX idx_business_clubs_owner ON client_business_clubs(client_id, user_id, club_id);
