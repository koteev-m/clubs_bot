CREATE TABLE IF NOT EXISTS club_gamification_settings (
    club_id BIGINT PRIMARY KEY REFERENCES clubs(id),
    stamps_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    early_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    badges_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    prizes_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    contests_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    tables_loyalty_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    early_window_minutes INTEGER NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT club_gamification_settings_early_window_minutes_check
        CHECK (early_window_minutes IS NULL OR early_window_minutes >= 0)
);

CREATE TABLE IF NOT EXISTS badges (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NOT NULL REFERENCES clubs(id),
    code TEXT NOT NULL,
    name_ru TEXT NOT NULL,
    icon TEXT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    condition_type TEXT NOT NULL,
    threshold INTEGER NOT NULL,
    window_days INTEGER NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_badges_club_code UNIQUE (club_id, code),
    CONSTRAINT badges_threshold_check CHECK (threshold >= 1)
);

CREATE TABLE IF NOT EXISTS user_badges (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NOT NULL REFERENCES clubs(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    badge_id BIGINT NOT NULL REFERENCES badges(id),
    earned_at TIMESTAMPTZ NOT NULL,
    fingerprint TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_badges_fingerprint UNIQUE (fingerprint),
    CONSTRAINT uq_user_badges_club_user_badge UNIQUE (club_id, user_id, badge_id)
);

CREATE INDEX IF NOT EXISTS idx_user_badges_club_user
    ON user_badges (club_id, user_id);

CREATE TABLE IF NOT EXISTS prizes (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NOT NULL REFERENCES clubs(id),
    code TEXT NOT NULL,
    title_ru TEXT NOT NULL,
    description TEXT NULL,
    terms TEXT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    limit_total INTEGER NULL,
    expires_in_days INTEGER NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_prizes_club_code UNIQUE (club_id, code),
    CONSTRAINT prizes_limit_total_check CHECK (limit_total IS NULL OR limit_total >= 1),
    CONSTRAINT prizes_expires_in_days_check CHECK (expires_in_days IS NULL OR expires_in_days >= 1)
);

CREATE TABLE IF NOT EXISTS reward_ladder_levels (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NOT NULL REFERENCES clubs(id),
    metric_type TEXT NOT NULL,
    threshold INTEGER NOT NULL,
    window_days INTEGER NOT NULL DEFAULT 0,
    prize_id BIGINT NOT NULL REFERENCES prizes(id),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    order_index INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_reward_ladder_levels UNIQUE (club_id, metric_type, threshold, window_days),
    CONSTRAINT reward_ladder_levels_threshold_check CHECK (threshold >= 1),
    CONSTRAINT reward_ladder_levels_order_index_check CHECK (order_index >= 0),
    CONSTRAINT reward_ladder_levels_window_days_check CHECK (window_days >= 0)
);

CREATE TABLE IF NOT EXISTS reward_coupons (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NOT NULL REFERENCES clubs(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    prize_id BIGINT NOT NULL REFERENCES prizes(id),
    status TEXT NOT NULL,
    reason_code TEXT NULL,
    fingerprint TEXT NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NULL,
    redeemed_at TIMESTAMPTZ NULL,
    issued_by BIGINT NULL REFERENCES users(id),
    redeemed_by BIGINT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_reward_coupons_fingerprint UNIQUE (fingerprint),
    CONSTRAINT reward_coupons_status_check
        CHECK (status IN ('AVAILABLE', 'REDEEMED', 'EXPIRED', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_reward_coupons_club_user_status
    ON reward_coupons (club_id, user_id, status);

CREATE INDEX IF NOT EXISTS idx_reward_coupons_club_status_issued
    ON reward_coupons (club_id, status, issued_at DESC);
