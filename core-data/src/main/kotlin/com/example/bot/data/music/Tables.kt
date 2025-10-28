package com.example.bot.data.music

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/** Exposed table mappings for the music module. */
object MusicItemsTable : Table("music_items") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id").nullable()
    val title = text("title")
    val dj = text("dj").nullable()
    val sourceType = text("source_type")
    val sourceUrl = text("source_url").nullable()
    val telegramFileId = text("telegram_file_id").nullable()
    val durationSec = integer("duration_sec").nullable()
    val coverUrl = text("cover_url").nullable()
    val tags = text("tags").nullable()
    val publishedAt = timestampWithTimeZone("published_at").nullable()
    val isActive = bool("is_active").default(true)
    val createdBy = long("created_by").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestamp())
    override val primaryKey = PrimaryKey(id)
}

object MusicPlaylistsTable : Table("music_playlists") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id").nullable()
    val title = text("title")
    val description = text("description").nullable()
    val coverUrl = text("cover_url").nullable()
    val isActive = bool("is_active").default(true)
    val createdBy = long("created_by").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestamp())
    override val primaryKey = PrimaryKey(id)
}

object MusicPlaylistItemsTable : Table("music_playlist_items") {
    val playlistId = long("playlist_id") references MusicPlaylistsTable.id
    val itemId = long("item_id") references MusicItemsTable.id
    val position = integer("position").default(0)
    override val primaryKey = PrimaryKey(playlistId, itemId)

    init {
        index(false, playlistId, position)
    }
}

object MusicLikesTable : Table("music_likes") {
    val itemId = long("item_id") references MusicItemsTable.id
    val userId = long("user_id")
    val likedAt = timestampWithTimeZone("liked_at").defaultExpression(CurrentTimestamp())
    override val primaryKey = PrimaryKey(itemId, userId)
}
