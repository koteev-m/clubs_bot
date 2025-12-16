CREATE TABLE IF NOT EXISTS music_track_of_night (
    set_id BIGINT PRIMARY KEY,
    track_id BIGINT NOT NULL,
    marked_by BIGINT,
    marked_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_music_track_of_night_set FOREIGN KEY (set_id) REFERENCES music_playlists(id),
    CONSTRAINT fk_music_track_of_night_track FOREIGN KEY (track_id) REFERENCES music_items(id)
);

CREATE INDEX IF NOT EXISTS idx_music_track_of_night_marked_at ON music_track_of_night(marked_at);
