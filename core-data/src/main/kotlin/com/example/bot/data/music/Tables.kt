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
    val description = text("description").nullable()
    val itemType = text("item_type").default("TRACK")
    val sourceType = text("source_type")
    val sourceUrl = text("source_url").nullable()
    val audioAssetId = long("audio_asset_id").nullable()
    val telegramFileId = text("telegram_file_id").nullable()
    val durationSec = integer("duration_sec").nullable()
    val coverUrl = text("cover_url").nullable()
    val coverAssetId = long("cover_asset_id").nullable()
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
    val userId = long("user_id")
    val itemId = long("item_id") references MusicItemsTable.id
    val likedAt = timestampWithTimeZone("liked_at").defaultExpression(CurrentTimestamp())
    override val primaryKey = PrimaryKey(userId, itemId)

    init {
        uniqueIndex("ux_music_likes_user_item", userId, itemId)
    }
}

object MusicTrackOfNightTable : Table("music_track_of_night") {
    val setId = long("set_id") references MusicPlaylistsTable.id
    val trackId = long("track_id") references MusicItemsTable.id
    val markedBy = long("marked_by").nullable()
    val markedAt = timestampWithTimeZone("marked_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(setId)

    init {
        index("idx_music_track_of_night_marked_at", false, markedAt)
    }
}


object MusicBattlesTable : Table("music_battles") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id").nullable()
    val itemAId = long("item_a_id") references MusicItemsTable.id
    val itemBId = long("item_b_id") references MusicItemsTable.id
    val status = text("status")
    val startsAt = timestampWithTimeZone("starts_at")
    val endsAt = timestampWithTimeZone("ends_at")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(id)
}

object MusicBattleVotesTable : Table("music_battle_votes") {
    val battleId = long("battle_id") references MusicBattlesTable.id
    val userId = long("user_id")
    val chosenItemId = long("chosen_item_id") references MusicItemsTable.id
    val votedAt = timestampWithTimeZone("voted_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(battleId, userId)

    init {
        index("idx_music_battle_votes_battle_item", false, battleId, chosenItemId)
    }
}

object MusicItemStemsAssetsTable : Table("music_item_stems_assets") {
    val itemId = long("item_id") references MusicItemsTable.id
    val assetId = long("asset_id") references MusicAssetsTable.id
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(itemId)
}

object MusicAssetsTable : Table("music_assets") {
    val id = long("id").autoIncrement()
    val kind = text("kind")
    val bytes = binary("bytes")
    val contentType = text("content_type")
    val sha256 = text("sha256")
    val sizeBytes = long("size_bytes")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(id)
}
