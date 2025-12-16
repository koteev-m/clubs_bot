CREATE TABLE IF NOT EXISTS music_track_of_night (
    set_id BIGINT PRIMARY KEY REFERENCES music_playlists(id),
    track_id BIGINT NOT NULL REFERENCES music_items(id),
    marked_by BIGINT,
    marked_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_music_track_of_night_marked_at ON music_track_of_night(marked_at);
