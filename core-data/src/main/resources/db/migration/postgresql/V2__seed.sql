-- =====================
-- Seed roles (idempotent)
-- =====================
INSERT INTO roles(code) VALUES
 ('OWNER'),('GLOBAL_ADMIN'),('HEAD_MANAGER'),('CLUB_ADMIN'),
 ('MANAGER'),('ENTRY_MANAGER'),('PROMOTER'),('GUEST')
ON CONFLICT (code) DO NOTHING;

-- =====================
-- Seed clubs (идемпотентно по name)
-- Примечание: в схеме нет UNIQUE(name), поэтому используем WHERE NOT EXISTS
-- =====================
INSERT INTO clubs(name, description, timezone, admin_channel_id)
SELECT 'Aurora','Moscow main club','Europe/Moscow',NULL
WHERE NOT EXISTS (SELECT 1 FROM clubs WHERE name='Aurora');

INSERT INTO clubs(name, description, timezone, admin_channel_id)
SELECT 'Nebula','Berlin vibes','Europe/Berlin',NULL
WHERE NOT EXISTS (SELECT 1 FROM clubs WHERE name='Nebula');

INSERT INTO clubs(name, description, timezone, admin_channel_id)
SELECT 'Eclipse','NYC nights','America/New_York',NULL
WHERE NOT EXISTS (SELECT 1 FROM clubs WHERE name='Eclipse');

INSERT INTO clubs(name, description, timezone, admin_channel_id)
SELECT 'Mirage','Tokyo lounge','Asia/Tokyo',NULL
WHERE NOT EXISTS (SELECT 1 FROM clubs WHERE name='Mirage');

-- =====================
-- Seed zones "Main Hall" для каждого клуба
-- =====================
INSERT INTO zones(club_id, name, priority)
SELECT c.id, 'Main Hall', 1
FROM clubs c
WHERE c.name IN ('Aurora','Nebula','Eclipse','Mirage')
  AND NOT EXISTS (
    SELECT 1 FROM zones z WHERE z.club_id = c.id AND z.name = 'Main Hall'
  );

-- =====================
-- Seed tables (по клубам, в зону "Main Hall")
-- Используем VALUES + JOIN, уникальность (club_id, table_number) защищает от дублей
-- =====================
WITH t(club_name, table_number, capacity, min_dep) AS (
    VALUES
      ('Aurora', 1, 4, 100.00), ('Aurora', 2, 4, 100.00), ('Aurora', 3, 4, 100.00),
      ('Nebula', 1, 4,  80.00), ('Nebula', 2, 4,  80.00), ('Nebula', 3, 4,  80.00),
      ('Eclipse',1, 4, 120.00), ('Eclipse',2, 4, 120.00), ('Eclipse',3, 4, 120.00),
      ('Mirage', 1, 4,  90.00), ('Mirage', 2, 4,  90.00), ('Mirage', 3, 4,  90.00)
)
INSERT INTO tables(club_id, zone_id, table_number, capacity, min_deposit)
SELECT c.id,
       z.id,
       t.table_number,
       t.capacity,
       t.min_dep
FROM t
JOIN clubs c ON c.name = t.club_name
JOIN zones z ON z.club_id = c.id AND z.name = 'Main Hall'
ON CONFLICT (club_id, table_number) DO NOTHING;

-- =====================
-- Seed promoter user и роль (идемпотентно)
-- =====================
WITH u AS (
  INSERT INTO users(telegram_user_id, username, display_name)
  VALUES (1000001, 'promoter1', 'Promoter One')
  ON CONFLICT (telegram_user_id)
  DO UPDATE SET username = EXCLUDED.username
  RETURNING id
)
INSERT INTO user_roles(user_id, role_code, scope_type, scope_club_id)
SELECT u.id, 'PROMOTER', 'CLUB', c.id
FROM u
JOIN clubs c ON c.name = 'Aurora'
ON CONFLICT (user_id, role_code, scope_type, scope_club_id) DO NOTHING;

-- =====================
-- Seed events: ближайшая суббота 22:00–06:00 ЛОКАЛЬНО, сохранить как timestamptz
-- ВАЖНО: конвертируем в timestamptz ОДИН раз через timezone(c.timezone, local_ts)
-- =====================
WITH c AS (
  SELECT id, timezone FROM clubs
)
INSERT INTO events(club_id, title, start_at, end_at, is_special)
SELECT
  c.id,
  'Regular Night',
  -- локальный "понедельник 00:00" текущей недели:
  timezone(c.timezone,
           date_trunc('week', (now() AT TIME ZONE c.timezone))         -- ts without tz (локально)
           + interval '5 days'                                         -- суббота
           + time '22:00'                                              -- 22:00 локально
  )                                             AS start_at_utc,       -- timestamptz
  timezone(c.timezone,
           date_trunc('week', (now() AT TIME ZONE c.timezone))
           + interval '6 days'                                         -- воскресенье
           + time '06:00'                                              -- 06:00 локально (переваливает сутки)
  )                                             AS end_at_utc,         -- timestamptz
  FALSE
FROM c
ON CONFLICT (club_id, start_at) DO NOTHING;