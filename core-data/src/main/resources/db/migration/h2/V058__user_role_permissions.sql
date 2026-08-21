CREATE TABLE permissions (
    code TEXT PRIMARY KEY
);

CREATE TABLE user_role_permissions (
    user_role_id BIGINT NOT NULL REFERENCES user_roles(id) ON DELETE CASCADE,
    permission_code TEXT NOT NULL REFERENCES permissions(code),
    PRIMARY KEY (user_role_id, permission_code)
);

INSERT INTO permissions (code) VALUES
    ('support.view'),
    ('support.reply'),
    ('support.status.manage');
