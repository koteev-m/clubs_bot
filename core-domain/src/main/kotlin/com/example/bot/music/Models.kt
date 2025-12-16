package com.example.bot.music

import java.time.Instant

/** Source type of a music item. */
enum class MusicSource { YOUTUBE, SOUNDCLOUD, SPOTIFY, FILE, LINK }

/** Request to create a new music item. */
data class MusicItemCreate(
    val clubId: Long?,
    val title: String,
    val dj: String?,
    val source: MusicSource,
    val sourceUrl: String?,
    val durationSec: Int?,
    val coverUrl: String?,
    val tags: List<String>?,
    val publishedAt: Instant?,
)

/** View of a music item. */
data class MusicItemView(
    val id: Long,
    val clubId: Long?,
    val title: String,
    val dj: String?,
    val source: MusicSource,
    val sourceUrl: String?,
    val telegramFileId: String?,
    val durationSec: Int?,
    val coverUrl: String?,
    val tags: List<String>?,
    val publishedAt: Instant?,
    val isTrackOfNight: Boolean = false,
)

/** Request to create playlist. */
data class PlaylistCreate(
    val clubId: Long?,
    val title: String,
    val description: String?,
    val coverUrl: String?,
    val itemIds: List<Long> = emptyList(),
)

/** View of playlist without items. */
data class PlaylistView(
    val id: Long,
    val clubId: Long?,
    val title: String,
    val description: String?,
    val coverUrl: String?,
)

/** View of playlist with items. */
data class PlaylistFullView(
    val id: Long,
    val clubId: Long?,
    val title: String,
    val description: String?,
    val coverUrl: String?,
    val items: List<MusicItemView>,
)

/** Identifier of the user. */
typealias UserId = Long

/**
 * Music like placed by a user for a specific item.
 */
data class Like(
    val userId: Long,
    val itemId: Long,
    val createdAt: Instant,
)

/**
 * Weekly mixtape assembled for a user.
 */
data class Mixtape(
    val userId: Long,
    val items: List<Long>,
    val weekStart: Instant,
)

data class TrackOfNight(
    val setId: Long,
    val trackId: Long,
    val markedBy: Long,
    val markedAt: Instant,
)
