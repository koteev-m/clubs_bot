-- =====================
-- Core data for guest clubs and layouts
-- =====================
ALTER TABLE clubs
    ADD COLUMN IF NOT EXISTS city TEXT NOT NULL DEFAULT 'Unknown',
    ADD COLUMN IF NOT EXISTS genres TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS tags TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS logo_url TEXT NULL,
    ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_clubs_city_active ON clubs(city, is_active);

CREATE TABLE IF NOT EXISTS halls (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    layout_revision BIGINT NOT NULL DEFAULT 0,
    geometry_json TEXT NOT NULL,
    geometry_fingerprint VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (club_id, name)
);
CREATE INDEX IF NOT EXISTS idx_halls_club_active ON halls(club_id, is_active);

CREATE TABLE IF NOT EXISTS hall_zones (
    id BIGSERIAL PRIMARY KEY,
    hall_id BIGINT NOT NULL REFERENCES halls(id) ON DELETE CASCADE,
    zone_id VARCHAR(64) NOT NULL,
    name TEXT NOT NULL,
    tags TEXT NOT NULL DEFAULT '[]',
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (hall_id, zone_id)
);
CREATE INDEX IF NOT EXISTS idx_hall_zones_hall ON hall_zones(hall_id, sort_order);

CREATE TABLE IF NOT EXISTS hall_tables (
    id BIGSERIAL PRIMARY KEY,
    hall_id BIGINT NOT NULL REFERENCES halls(id) ON DELETE CASCADE,
    table_number INT NOT NULL,
    label TEXT NOT NULL,
    capacity INT NOT NULL CHECK (capacity > 0),
    minimum_tier VARCHAR(64) NOT NULL DEFAULT 'standard',
    min_deposit BIGINT NOT NULL DEFAULT 0 CHECK (min_deposit >= 0),
    zone_id VARCHAR(64) NOT NULL,
    zone VARCHAR(64) NULL,
    arrival_window VARCHAR(32) NULL,
    mystery_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    x DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    y DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (hall_id, table_number),
    CHECK (x >= 0 AND x <= 1),
    CHECK (y >= 0 AND y <= 1)
);
CREATE INDEX IF NOT EXISTS idx_hall_tables_hall_active ON hall_tables(hall_id, is_active);
