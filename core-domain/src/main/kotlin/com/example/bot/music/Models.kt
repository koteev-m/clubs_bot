package com.example.bot.music

import java.time.Instant

/** Source type of a music item. */
enum class MusicSource { YOUTUBE, SOUNDCLOUD, SPOTIFY, FILE, LINK }

/** Type of music item in the catalog. */
enum class MusicItemType { TRACK, SET }

/** Request to create a new music item. */
data class MusicItemCreate(
    val clubId: Long?,
    val title: String,
    val dj: String?,
    val description: String? = null,
    val itemType: MusicItemType = MusicItemType.TRACK,
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
    val description: String?,
    val itemType: MusicItemType,
    val source: MusicSource,
    val sourceUrl: String?,
    val audioAssetId: Long?,
    val telegramFileId: String?,
    val durationSec: Int?,
    val coverUrl: String?,
    val coverAssetId: Long?,
    val tags: List<String>?,
    val publishedAt: Instant?,
)

/** Update payload for a music item (null values mean "no change"). */
data class MusicItemUpdate(
    val clubId: Long? = null,
    val title: String? = null,
    val dj: String? = null,
    val description: String? = null,
    val itemType: MusicItemType? = null,
    val source: MusicSource? = null,
    val sourceUrl: String? = null,
    val durationSec: Int? = null,
    val coverUrl: String? = null,
    val tags: List<String>? = null,
)

/** Stored media asset (audio or cover). */
data class MusicAsset(
    val id: Long,
    val kind: MusicAssetKind,
    val bytes: ByteArray,
    val contentType: String,
    val sha256: String,
    val sizeBytes: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Media asset metadata without bytes. */
data class MusicAssetMeta(
    val id: Long,
    val kind: MusicAssetKind,
    val contentType: String,
    val sha256: String,
    val sizeBytes: Long,
    val updatedAt: Instant,
)

/** Asset kind (audio or cover). */
enum class MusicAssetKind { AUDIO, COVER }

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

enum class MusicBattleStatus { DRAFT, ACTIVE, CLOSED }

data class MusicBattle(
    val id: Long,
    val clubId: Long?,
    val itemAId: Long,
    val itemBId: Long,
    val status: MusicBattleStatus,
    val startsAt: Instant,
    val endsAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class MusicBattleVote(
    val battleId: Long,
    val userId: Long,
    val chosenItemId: Long,
    val votedAt: Instant,
)

data class MusicBattleVoteAggregate(
    val battleId: Long,
    val itemAId: Long,
    val itemBId: Long,
    val itemAVotes: Int,
    val itemBVotes: Int,
)

enum class MusicVoteUpsertResult {
    CREATED,
    UPDATED,
    UNCHANGED,
}

data class MusicStemsPackage(
    val itemId: Long,
    val assetId: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)
