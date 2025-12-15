package com.example.bot.music

import java.time.Instant

/** Repository for music items. */
interface MusicItemRepository {
    suspend fun create(
        req: MusicItemCreate,
        actor: UserId,
    ): MusicItemView

    suspend fun listActive(
        clubId: Long?,
        limit: Int,
        offset: Int,
        tag: String?,
        q: String?,
    ): List<MusicItemView>

    suspend fun lastUpdatedAt(): Instant?
}

/** Repository for music playlists. */
interface MusicPlaylistRepository {
    suspend fun create(
        req: PlaylistCreate,
        actor: UserId,
    ): PlaylistView

    suspend fun setItems(
        playlistId: Long,
        itemIds: List<Long>,
    )

    suspend fun listActive(
        limit: Int,
        offset: Int = 0,
    ): List<PlaylistView>

    suspend fun itemsCount(playlistIds: Collection<Long>): Map<Long, Int>

    suspend fun getFull(id: Long): PlaylistFullView?

    suspend fun lastUpdatedAt(): Instant?
}

/** Repository for music likes. */
interface MusicLikesRepository {
    /**
     * Places like [userId] → [itemId]. Returns true when the like is created, false when it already exists.
     */
    suspend fun like(userId: Long, itemId: Long, now: Instant): Boolean

    /**
     * Removes like [userId] → [itemId]. Returns true if a like was deleted, false if nothing changed.
     */
    suspend fun unlike(userId: Long, itemId: Long): Boolean

    /**
     * Returns likes of the given user in the inclusive range [since; now].
     */
    suspend fun findUserLikesSince(userId: Long, since: Instant): List<Like>

    /**
     * Returns likes of all users in the inclusive range [since; now].
     */
    suspend fun findAllLikesSince(since: Instant): List<Like>

    /**
     * Returns a specific like if present.
     */
    suspend fun find(userId: Long, itemId: Long): Like?
}
