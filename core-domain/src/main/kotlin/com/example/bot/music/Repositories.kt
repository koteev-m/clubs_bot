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
