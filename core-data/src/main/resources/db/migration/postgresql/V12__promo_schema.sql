CREATE TABLE IF NOT EXISTS promo_links (
    id BIGSERIAL PRIMARY KEY,
    promoter_user_id BIGINT NOT NULL REFERENCES users(id),
    club_id BIGINT REFERENCES clubs(id),
    utm_source TEXT NOT NULL,
    utm_medium TEXT NOT NULL,
    utm_campaign TEXT NOT NULL,
    utm_content TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_promo_links_promoter_club ON promo_links (promoter_user_id, club_id);

CREATE TABLE IF NOT EXISTS promo_attribution (
    id BIGSERIAL PRIMARY KEY,
    booking_id UUID NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    promo_link_id BIGINT NOT NULL REFERENCES promo_links(id) ON DELETE CASCADE,
    promoter_user_id BIGINT NOT NULL REFERENCES users(id),
    utm_source TEXT NOT NULL,
    utm_medium TEXT NOT NULL,
    utm_campaign TEXT NOT NULL,
    utm_content TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_promo_attribution_booking UNIQUE (booking_id)
);

CREATE INDEX IF NOT EXISTS idx_promo_attribution_booking ON promo_attribution (booking_id);

CREATE TABLE IF NOT EXISTS booking_templates (
    id BIGSERIAL PRIMARY KEY,
    promoter_user_id BIGINT NOT NULL REFERENCES users(id),
    club_id BIGINT NOT NULL REFERENCES clubs(id),
    table_capacity_min INTEGER NOT NULL,
    notes TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_booking_templates_owner ON booking_templates (promoter_user_id, club_id, is_active);