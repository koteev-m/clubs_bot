-- Add telegram notification endpoints for clubs and HQ

-- Extend clubs with chat and topic identifiers
ALTER TABLE clubs ADD COLUMN IF NOT EXISTS admin_chat_id BIGINT;
ALTER TABLE clubs ADD COLUMN IF NOT EXISTS general_topic_id INT;
ALTER TABLE clubs ADD COLUMN IF NOT EXISTS bookings_topic_id INT;
ALTER TABLE clubs ADD COLUMN IF NOT EXISTS lists_topic_id INT;
ALTER TABLE clubs ADD COLUMN IF NOT EXISTS qa_topic_id INT;
ALTER TABLE clubs ADD COLUMN IF NOT EXISTS media_topic_id INT;
ALTER TABLE clubs ADD COLUMN IF NOT EXISTS system_topic_id INT;

COMMENT ON COLUMN clubs.admin_chat_id IS 'Telegram chat id of admin supergroup';
COMMENT ON COLUMN clubs.general_topic_id IS 'Topic id for general discussions';
COMMENT ON COLUMN clubs.bookings_topic_id IS 'Topic id for booking notifications';
COMMENT ON COLUMN clubs.lists_topic_id IS 'Topic id for lists/check-in';
COMMENT ON COLUMN clubs.qa_topic_id IS 'Topic id for Q&A';
COMMENT ON COLUMN clubs.media_topic_id IS 'Topic id for media';
COMMENT ON COLUMN clubs.system_topic_id IS 'Topic id for system messages';

-- HQ notification table
CREATE TABLE IF NOT EXISTS hq_notify (
    id SMALLINT DEFAULT 1 PRIMARY KEY,
    chat_id BIGINT NOT NULL,
    general_topic_id INT NULL,
    bookings_topic_id INT NULL,
    lists_topic_id INT NULL,
    qa_topic_id INT NULL,
    system_topic_id INT NULL
);

COMMENT ON TABLE hq_notify IS 'Telegram notify endpoints for HQ admin chat';

DELETE FROM hq_notify WHERE id = 1;
INSERT INTO hq_notify (id, chat_id, general_topic_id, bookings_topic_id, lists_topic_id, qa_topic_id, system_topic_id)
VALUES (1, -1002693051031, 19, 2, 3, 6, 4);

-- Ensure club records exist
INSERT INTO clubs (name, timezone)
SELECT 'Микс', 'Europe/Moscow'
WHERE NOT EXISTS (SELECT 1 FROM clubs WHERE name = 'Микс');
INSERT INTO clubs (name, timezone)
SELECT 'Особняк', 'Europe/Moscow'
WHERE NOT EXISTS (SELECT 1 FROM clubs WHERE name = 'Особняк');
INSERT INTO clubs (name, timezone)
SELECT 'Пиздюки', 'Europe/Moscow'
WHERE NOT EXISTS (SELECT 1 FROM clubs WHERE name = 'Пиздюки');
INSERT INTO clubs (name, timezone)
SELECT 'NN', 'Europe/Moscow'
WHERE NOT EXISTS (SELECT 1 FROM clubs WHERE name = 'NN');

-- Update notify endpoints for clubs
UPDATE clubs SET
    admin_chat_id = -1003032666045,
    general_topic_id = 1,
    bookings_topic_id = 2,
    lists_topic_id = 3,
    qa_topic_id = 4,
    media_topic_id = 5,
    system_topic_id = 7
WHERE name = 'Микс';

UPDATE clubs SET
    admin_chat_id = -1003015873542,
    general_topic_id = 1,
    bookings_topic_id = 2,
    lists_topic_id = 4,
    qa_topic_id = 5,
    media_topic_id = 6,
    system_topic_id = 7
WHERE name = 'Особняк';

UPDATE clubs SET
    admin_chat_id = -1003082862964,
    general_topic_id = 1,
    bookings_topic_id = 2,
    lists_topic_id = 3,
    qa_topic_id = 4,
    media_topic_id = 5,
    system_topic_id = 6
WHERE name = 'Пиздюки';

UPDATE clubs SET
    admin_chat_id = -1002988144234,
    general_topic_id = 1,
    bookings_topic_id = 2,
    lists_topic_id = 3,
    qa_topic_id = 4,
    media_topic_id = 5,
    system_topic_id = 6
WHERE name = 'NN';
