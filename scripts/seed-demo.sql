-- scripts/seed-demo.sql
-- ИДЕМПОТЕНТНЫЕ СИДЫ ДЛЯ ДЕМО: клубы, зоны, столы, ивенты, роли, ваш Telegram-пользователь.

-- === 0) Роли (если не засеяны миграциями) ===
INSERT INTO roles(code) VALUES
  ('OWNER'),('GLOBAL_ADMIN'),('HEAD_MANAGER'),
  ('CLUB_ADMIN'),('MANAGER'),('ENTRY_MANAGER'),
  ('PROMOTER'),('GUEST')
ON CONFLICT DO NOTHING;

-- === 1) Клубы ===
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM clubs WHERE name = 'Mix') THEN
    INSERT INTO clubs(name, description, timezone, admin_channel_id, bookings_topic_id, checkin_topic_id, qa_topic_id)
    VALUES ('Mix', 'Main club', 'Europe/Moscow', -1003032666045, 2, 2, 4);
  END IF;

  IF NOT EXISTS (SELECT 1 FROM clubs WHERE name = 'Osobnyak') THEN
    INSERT INTO clubs(name, description, timezone, admin_channel_id, bookings_topic_id, checkin_topic_id, qa_topic_id)
    VALUES ('Osobnyak', 'Osobnyak club', 'Europe/Moscow', -1003015873542, 2, 2, 5);
  END IF;

  IF NOT EXISTS (SELECT 1 FROM clubs WHERE name = 'Internal3') THEN
    INSERT INTO clubs(name, description, timezone, admin_channel_id, bookings_topic_id, checkin_topic_id, qa_topic_id)
    VALUES ('Internal3', 'Internal #3', 'Europe/Moscow', -1003082862964, 2, 2, 4);
  END IF;

  IF NOT EXISTS (SELECT 1 FROM clubs WHERE name = 'NN') THEN
    INSERT INTO clubs(name, description, timezone, admin_channel_id, bookings_topic_id, checkin_topic_id, qa_topic_id)
    VALUES ('NN', 'NN club', 'Europe/Moscow', -1002988144234, 2, 2, 4);
  END IF;
END $$;

-- === 2) Зоны и столы (по 2 зоны, столы 1..10 в каждой) ===
WITH c AS (
  SELECT id, name FROM clubs WHERE name IN ('Mix','Osobnyak','Internal3','NN')
)
-- Зоны
INSERT INTO zones(club_id, name, priority)
SELECT c.id, z.name, z.priority
FROM c
JOIN (VALUES ('Main Hall', 0), ('VIP', 10)) AS z(name, priority) ON TRUE
ON CONFLICT (club_id, name) DO NOTHING;

-- Столы
DO $$
DECLARE
  r RECORD;
  zid_main BIGINT;
  zid_vip  BIGINT;
BEGIN
  FOR r IN SELECT id, name FROM clubs WHERE name IN ('Mix','Osobnyak','Internal3','NN') LOOP
    SELECT id INTO zid_main FROM zones WHERE club_id = r.id AND name = 'Main Hall';
    SELECT id INTO zid_vip  FROM zones WHERE club_id = r.id AND name = 'VIP';

    -- столы 1..10: 1..6 в Main Hall, 7..10 в VIP
    INSERT INTO tables(club_id, zone_id, table_number, capacity, min_deposit, active)
    SELECT r.id, zid_main, n, 4, 5000.00, TRUE FROM generate_series(1,6) AS g(n)
    ON CONFLICT (club_id, table_number) DO NOTHING;

    INSERT INTO tables(club_id, zone_id, table_number, capacity, min_deposit, active)
    SELECT r.id, zid_vip, n, 6, 15000.00, TRUE FROM generate_series(7,10) AS g(n)
    ON CONFLICT (club_id, table_number) DO NOTHING;
  END LOOP;
END $$;

-- === 3) События (сегодня +1 час, длительность 5 часов) для каждого клуба ===
DO $$
DECLARE
  r RECORD;
  start_ts TIMESTAMPTZ;
  end_ts   TIMESTAMPTZ;
BEGIN
  FOR r IN SELECT id, timezone, name FROM clubs WHERE name IN ('Mix','Osobnyak','Internal3','NN') LOOP
    -- старт через 1 час от текущего времени
    start_ts := now() + interval '1 hour';
    end_ts   := start_ts + interval '5 hours';

    IF NOT EXISTS (
      SELECT 1 FROM events WHERE club_id = r.id
        AND start_at BETWEEN now() - interval '1 hour' AND now() + interval '24 hours'
    ) THEN
      INSERT INTO events(club_id, title, start_at, end_at, is_special, poster_url)
      VALUES (r.id, CONCAT('Tonight @ ', r.name), start_ts, end_ts, FALSE, NULL);
    END IF;
  END LOOP;
END $$;

-- === 4) Ваш Telegram-пользователь + права (CLUB_ADMIN) для Mix и Osobnyak ===
-- Поменяйте username/display_name при желании.
INSERT INTO users(telegram_user_id, username, display_name)
VALUES (7446417641, 'Maksim_Koteev', 'Maksim')
ON CONFLICT (telegram_user_id) DO UPDATE SET username = EXCLUDED.username, display_name = EXCLUDED.display_name;

-- Дадим роли по клубам (если нужно другие — меняйте список).
WITH u AS (SELECT id FROM users WHERE telegram_user_id = 7446417641),
     c AS (SELECT id FROM clubs WHERE name IN ('Mix','Osobnyak'))
INSERT INTO user_roles(user_id, role_code, scope_type, scope_club_id)
SELECT u.id, 'CLUB_ADMIN', 'CLUB', c.id FROM u, c
ON CONFLICT DO NOTHING;

-- (Опционально) глобальная роль:
-- WITH u AS (SELECT id FROM users WHERE telegram_user_id = 7446417641)
-- INSERT INTO user_roles(user_id, role_code, scope_type, scope_club_id)
-- SELECT u.id, 'HEAD_MANAGER', 'GLOBAL', NULL FROM u
-- ON CONFLICT DO NOTHING;