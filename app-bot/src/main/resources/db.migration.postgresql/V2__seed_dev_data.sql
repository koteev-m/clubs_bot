-- Немного данных для DEV
INSERT INTO clubs (id, name, description, timezone,
                   admin_chat_id, general_topic_id, bookings_topic_id, lists_topic_id,
                   qa_topic_id, media_topic_id, system_topic_id)
VALUES
 (1, 'Mix',       null, 'Europe/Moscow', -1003032666045, 1,2,3,4,5,6),
 (2, 'Osobnyak',  null, 'Europe/Moscow', -1003015873542, 1,2,3,4,5,6),
 (3, 'Internal3', null, 'Europe/Moscow', -1003082862964, 1,2,3,4,5,6),
 (4, 'NN',        null, 'Europe/Moscow', -1002988144234, 1,2,3,4,5,6)
ON CONFLICT (id) DO UPDATE
SET name              = EXCLUDED.name,
    description       = EXCLUDED.description,
    timezone          = EXCLUDED.timezone,
    admin_chat_id     = EXCLUDED.admin_chat_id,
    general_topic_id  = EXCLUDED.general_topic_id,
    bookings_topic_id = EXCLUDED.bookings_topic_id,
    lists_topic_id    = EXCLUDED.lists_topic_id,
    qa_topic_id       = EXCLUDED.qa_topic_id,
    media_topic_id    = EXCLUDED.media_topic_id,
    system_topic_id   = EXCLUDED.system_topic_id;