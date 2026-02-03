CREATE TABLE IF NOT EXISTS club_report_templates (
    club_id BIGINT PRIMARY KEY REFERENCES clubs(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS club_bracelet_types (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    order_index INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_club_bracelet_types_club_order
    ON club_bracelet_types (club_id, order_index);

CREATE INDEX IF NOT EXISTS idx_club_bracelet_types_club
    ON club_bracelet_types (club_id);

CREATE TABLE IF NOT EXISTS club_revenue_groups (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    order_index INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_club_revenue_groups_club_order
    ON club_revenue_groups (club_id, order_index);

CREATE INDEX IF NOT EXISTS idx_club_revenue_groups_club
    ON club_revenue_groups (club_id);

CREATE TABLE IF NOT EXISTS club_revenue_articles (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    group_id BIGINT NOT NULL REFERENCES club_revenue_groups(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    include_in_total BOOLEAN NOT NULL DEFAULT FALSE,
    show_separately BOOLEAN NOT NULL DEFAULT FALSE,
    order_index INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_club_revenue_articles_club_group_order
    ON club_revenue_articles (club_id, group_id, order_index);

CREATE INDEX IF NOT EXISTS idx_club_revenue_articles_club
    ON club_revenue_articles (club_id);

CREATE TABLE IF NOT EXISTS shift_reports (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    night_start_utc TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('DRAFT','CLOSED')),
    people_women INT NOT NULL DEFAULT 0,
    people_men INT NOT NULL DEFAULT 0,
    people_rejected INT NOT NULL DEFAULT 0,
    comment TEXT NULL,
    closed_at TIMESTAMPTZ NULL,
    closed_by BIGINT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (club_id, night_start_utc)
);

CREATE INDEX IF NOT EXISTS idx_shift_reports_club_night
    ON shift_reports (club_id, night_start_utc);

CREATE INDEX IF NOT EXISTS idx_shift_reports_club_status
    ON shift_reports (club_id, status);

CREATE TABLE IF NOT EXISTS shift_report_bracelets (
    report_id BIGINT NOT NULL REFERENCES shift_reports(id) ON DELETE CASCADE,
    bracelet_type_id BIGINT NOT NULL REFERENCES club_bracelet_types(id) ON DELETE RESTRICT,
    count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (report_id, bracelet_type_id)
);

CREATE INDEX IF NOT EXISTS idx_shift_report_bracelets_type
    ON shift_report_bracelets (bracelet_type_id);

CREATE TABLE IF NOT EXISTS shift_report_revenue_entries (
    id BIGSERIAL PRIMARY KEY,
    report_id BIGINT NOT NULL REFERENCES shift_reports(id) ON DELETE CASCADE,
    article_id BIGINT NULL REFERENCES club_revenue_articles(id) ON DELETE SET NULL,
    name TEXT NOT NULL,
    group_id BIGINT NOT NULL REFERENCES club_revenue_groups(id) ON DELETE RESTRICT,
    amount_minor BIGINT NOT NULL DEFAULT 0,
    include_in_total BOOLEAN NOT NULL DEFAULT FALSE,
    show_separately BOOLEAN NOT NULL DEFAULT FALSE,
    order_index INT NOT NULL DEFAULT 0,
    UNIQUE (report_id, article_id)
);

CREATE INDEX IF NOT EXISTS idx_shift_report_revenue_entries_report_group_order
    ON shift_report_revenue_entries (report_id, group_id, order_index);
