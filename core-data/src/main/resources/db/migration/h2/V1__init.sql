-- =====================
-- Clubs and schedule
-- =====================
CREATE TABLE clubs (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NULL,
    timezone TEXT NOT NULL,
    admin_channel_id BIGINT NULL,
    bookings_topic_id INT NULL,
    checkin_topic_id INT NULL,
    qa_topic_id INT NULL
);
COMMENT ON TABLE clubs IS 'Night clubs';
COMMENT ON COLUMN clubs.timezone IS 'IANA timezone, e.g. Europe/Moscow';
COMMENT ON COLUMN clubs.admin_channel_id IS 'Telegram chat id of admin supergroup or channel';
COMMENT ON COLUMN clubs.bookings_topic_id IS 'Forum topic id for booking notifications';
COMMENT ON COLUMN clubs.checkin_topic_id IS 'Forum topic id for check-in notifications';
COMMENT ON COLUMN clubs.qa_topic_id IS 'Forum topic id for Q&A';
CREATE INDEX idx_clubs_admin_channel_id ON clubs(admin_channel_id);

CREATE TABLE club_hours (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    dow SMALLINT NOT NULL CHECK (dow BETWEEN 1 AND 7),
    open_local TIME NOT NULL,
    close_local TIME NOT NULL,
    UNIQUE (club_id, dow)
);
COMMENT ON TABLE club_hours IS 'Recurring weekly schedule per club';
COMMENT ON COLUMN club_hours.dow IS 'Day of week 1=Mon .. 7=Sun';

CREATE TABLE club_holidays (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    date DATE NOT NULL,
    is_open BOOLEAN NOT NULL,
    override_open_local TIME NULL,
    override_close_local TIME NULL,
    name TEXT NULL,
    UNIQUE (club_id, date)
);
COMMENT ON TABLE club_holidays IS 'Holidays and special dates';

CREATE TABLE club_exceptions (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    date DATE NOT NULL,
    is_open BOOLEAN NOT NULL,
    reason TEXT NULL,
    override_open_local TIME NULL,
    override_close_local TIME NULL,
    UNIQUE (club_id, date)
);
COMMENT ON TABLE club_exceptions IS 'One-off closures or private events';

-- =====================
-- Events
-- =====================
CREATE TABLE events (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    title TEXT NULL,
    start_at TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at TIMESTAMP WITH TIME ZONE NOT NULL,
    is_special BOOLEAN NOT NULL DEFAULT FALSE,
    poster_url TEXT NULL,
    UNIQUE (club_id, start_at)
);
COMMENT ON TABLE events IS 'Concrete club nights';
COMMENT ON COLUMN events.start_at IS 'Event start in UTC';
COMMENT ON COLUMN events.end_at IS 'Event end in UTC';
CREATE INDEX idx_events_club_start ON events(club_id, start_at);
CREATE INDEX idx_events_start ON events(start_at);

-- =====================
-- Zones and tables
-- =====================
CREATE TABLE zones (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    UNIQUE (club_id, name)
);
COMMENT ON TABLE zones IS 'Zones inside a club';

CREATE TABLE tables (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    zone_id BIGINT NULL REFERENCES zones(id) ON DELETE SET NULL,
    table_number INT NOT NULL,
    capacity INT NOT NULL CHECK (capacity > 0 AND capacity <= 50),
    min_deposit NUMERIC(12,2) NOT NULL CHECK (min_deposit >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (club_id, table_number)
);
COMMENT ON TABLE tables IS 'Tables available for booking';
COMMENT ON COLUMN tables.min_deposit IS 'Minimum deposit required for the table';
CREATE INDEX idx_tables_club_active_number ON tables(club_id, active, table_number);

-- =====================
-- Users and roles
-- =====================
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    telegram_user_id BIGINT UNIQUE,
    username TEXT NULL,
    display_name TEXT NULL,
    phone_e164 TEXT NULL CHECK (phone_e164 ~ '^\+?[1-9]\d{6,14}$')
);
COMMENT ON TABLE users IS 'Telegram users';
CREATE INDEX idx_users_telegram_user_id ON users(telegram_user_id);

CREATE TABLE roles (
    code TEXT PRIMARY KEY CHECK (code IN ('OWNER','GLOBAL_ADMIN','HEAD_MANAGER','CLUB_ADMIN','MANAGER','ENTRY_MANAGER','PROMOTER','GUEST'))
);
COMMENT ON TABLE roles IS 'Fixed role codes';

CREATE TABLE user_roles (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_code TEXT NOT NULL REFERENCES roles(code),
    scope_type TEXT NOT NULL CHECK (scope_type IN ('GLOBAL','CLUB')),
    scope_club_id BIGINT NULL REFERENCES clubs(id),
    CHECK ((scope_type = 'GLOBAL' AND scope_club_id IS NULL) OR (scope_type = 'CLUB' AND scope_club_id IS NOT NULL)),
    UNIQUE (user_id, role_code, scope_type, scope_club_id)
);
COMMENT ON TABLE user_roles IS 'User roles with scope';

-- =====================
-- Booking holds and bookings
-- =====================
CREATE TABLE booking_holds (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    event_id BIGINT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    table_id BIGINT NOT NULL REFERENCES tables(id) ON DELETE CASCADE,
    holder_user_id BIGINT NULL REFERENCES users(id),
    guests_count INT NOT NULL CHECK (guests_count > 0 AND guests_count <= 50),
    min_deposit NUMERIC(12,2) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    idempotency_key TEXT NOT NULL UNIQUE
);
COMMENT ON TABLE booking_holds IS 'Temporary holds for tables';
CREATE INDEX idx_booking_holds_event_expires ON booking_holds(event_id, expires_at);

CREATE TABLE bookings (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    event_id BIGINT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    club_id BIGINT NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    table_id BIGINT NOT NULL REFERENCES tables(id) ON DELETE RESTRICT,
    table_number INT NOT NULL,
    guest_user_id BIGINT NULL REFERENCES users(id),
    guest_name TEXT NULL,
    phone_e164 TEXT NULL,
    promoter_user_id BIGINT NULL REFERENCES users(id),
    guests_count INT NOT NULL CHECK (guests_count > 0 AND guests_count <= 50),
    min_deposit NUMERIC(12,2) NOT NULL,
    total_deposit NUMERIC(12,2) NOT NULL CHECK (total_deposit >= 0),
    arrival_by TIMESTAMP WITH TIME ZONE NULL,
    status TEXT NOT NULL CHECK (status IN ('CONFIRMED','SEATED','NO_SHOW','CANCELLED','EXPIRED')),
    qr_secret VARCHAR(64) NOT NULL UNIQUE,
    idempotency_key TEXT NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
COMMENT ON TABLE bookings IS 'Confirmed bookings';
CREATE INDEX idx_bookings_event_status ON bookings(event_id, status);
CREATE INDEX idx_bookings_club_status ON bookings(club_id, status);
CREATE INDEX idx_bookings_promoter_status ON bookings(promoter_user_id, status);

-- =====================
-- Payments
-- =====================
CREATE TABLE payments (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    booking_id UUID NULL REFERENCES bookings(id) ON DELETE CASCADE,
    provider TEXT NOT NULL,
    currency VARCHAR(8) NOT NULL,
    amount NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    status TEXT NOT NULL CHECK (status IN ('INITIATED','CAPTURED','REFUNDED','DECLINED')),
    external_id TEXT NULL,
    idempotency_key TEXT NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
COMMENT ON TABLE payments IS 'Payments for bookings and digital goods';

-- =====================
-- Guest lists
-- =====================
CREATE TABLE guest_lists (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    event_id BIGINT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    owner_type TEXT NOT NULL CHECK (owner_type IN ('PROMOTER','ADMIN','MANAGER')),
    owner_user_id BIGINT NOT NULL REFERENCES users(id),
    title TEXT NOT NULL,
    capacity INT NOT NULL CHECK (capacity > 0 AND capacity <= 1000),
    arrival_window_start TIMESTAMP WITH TIME ZONE NULL,
    arrival_window_end TIMESTAMP WITH TIME ZONE NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CLOSED')),
    UNIQUE (club_id, event_id, title)
);
COMMENT ON TABLE guest_lists IS 'Guest lists without table reservations';

CREATE TABLE guest_list_entries (
    id BIGSERIAL PRIMARY KEY,
    guest_list_id BIGINT NOT NULL REFERENCES guest_lists(id) ON DELETE CASCADE,
    full_name TEXT NOT NULL,
    tg_username TEXT NULL,
    phone_e164 TEXT NULL,
    plus_ones_allowed INT NOT NULL DEFAULT 0 CHECK (plus_ones_allowed BETWEEN 0 AND 10),
    plus_ones_used INT NOT NULL DEFAULT 0 CHECK (plus_ones_used BETWEEN 0 AND plus_ones_allowed),
    category TEXT NOT NULL DEFAULT 'REGULAR' CHECK (category IN ('REGULAR','VIP','STAFF','PRESS','BLACKLIST')),
    comment TEXT NULL,
    status TEXT NOT NULL DEFAULT 'INVITED' CHECK (status IN ('INVITED','APPROVED','ARRIVED','DENIED','LATE','NO_SHOW')),
    checked_in_at TIMESTAMP WITH TIME ZONE NULL,
    checked_in_by BIGINT NULL REFERENCES users(id)
);
COMMENT ON TABLE guest_list_entries IS 'Entries of guest lists';
CREATE INDEX idx_guest_list_entries_list_status ON guest_list_entries(guest_list_id, status);
CREATE INDEX idx_guest_list_entries_full_name ON guest_list_entries(full_name);
CREATE INDEX idx_guest_list_entries_tg_username ON guest_list_entries(tg_username);
CREATE INDEX idx_guest_list_entries_phone ON guest_list_entries(phone_e164);

-- =====================
-- Notifications outbox and audit log
-- =====================
CREATE TABLE notifications_outbox (
    id BIGSERIAL PRIMARY KEY,
    club_id BIGINT NULL REFERENCES clubs(id),
    target_chat_id BIGINT NOT NULL,
    message_thread_id INT NULL,
    kind TEXT NOT NULL,
    payload JSON NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED')),
    attempts INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP WITH TIME ZONE NULL,
    last_error TEXT NULL
);
COMMENT ON TABLE notifications_outbox IS 'Outbox for reliable delivery to chats/topics';
CREATE INDEX idx_notifications_outbox_status_retry ON notifications_outbox(status, next_retry_at);

CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    ts TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    actor_user_id BIGINT NULL REFERENCES users(id),
    action TEXT NOT NULL,
    entity TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    delta JSON NULL
);
COMMENT ON TABLE audit_log IS 'Audit log of actions';
