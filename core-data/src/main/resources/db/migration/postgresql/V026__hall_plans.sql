CREATE TABLE hall_plans (
    hall_id BIGINT PRIMARY KEY REFERENCES halls(id) ON DELETE CASCADE,
    bytes BYTEA NOT NULL,
    content_type TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
