create table if not exists music_items (
    id bigserial primary key,
    club_id bigint null references clubs(id) on delete set null,
    title text not null,
    dj text null,
    source_type text not null check (source_type in ('YOUTUBE','SOUNDCLOUD','SPOTIFY','FILE','LINK')),
    source_url text null,
    telegram_file_id text null,
    duration_sec int null check (duration_sec >= 0),
    cover_url text null,
    tags text null,
    published_at TIMESTAMP WITH TIME ZONE null,
    is_active boolean not null default true,
    created_by bigint null,
    created_at TIMESTAMP WITH TIME ZONE not null default now(),
    updated_at TIMESTAMP WITH TIME ZONE not null default now()
);

comment on table music_items is 'Tracks and DJ sets';
comment on column music_items.club_id is 'Owning club id';
comment on column music_items.source_type is 'Origin of media';
comment on column music_items.telegram_file_id is 'Cached Telegram file id';

create index if not exists music_items_club_active_idx on music_items(club_id, is_active, published_at desc);
create index if not exists music_items_tags_idx on music_items(tags);

create table if not exists music_playlists (
    id bigserial primary key,
    club_id bigint null references clubs(id) on delete set null,
    title text not null,
    description text null,
    cover_url text null,
    is_active boolean not null default true,
    created_by bigint null,
    created_at TIMESTAMP WITH TIME ZONE not null default now(),
    updated_at TIMESTAMP WITH TIME ZONE not null default now()
);

comment on table music_playlists is 'Music playlists';

create table if not exists music_playlist_items (
    playlist_id bigint not null references music_playlists(id) on delete cascade,
    item_id bigint not null references music_items(id) on delete cascade,
    position int not null default 0,
    primary key (playlist_id, item_id)
);
create index if not exists music_playlist_items_pos_idx on music_playlist_items(playlist_id, position);

create table if not exists music_likes (
    item_id bigint not null references music_items(id) on delete cascade,
    user_id bigint not null references users(id) on delete cascade,
    liked_at TIMESTAMP WITH TIME ZONE not null default now(),
    primary key (item_id, user_id)
);

comment on table music_likes is 'User likes for music items';
