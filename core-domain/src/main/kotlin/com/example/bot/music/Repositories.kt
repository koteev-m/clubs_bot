package com.example.bot.music

import java.time.Instant

/** Repository for music items. */
interface MusicItemRepository {
    suspend fun create(
        req: MusicItemCreate,
        actor: UserId,
    ): MusicItemView

    suspend fun update(
        id: Long,
        req: MusicItemUpdate,
        actor: UserId,
    ): MusicItemView?

    suspend fun setPublished(
        id: Long,
        publishedAt: Instant?,
        actor: UserId,
    ): MusicItemView?

    suspend fun attachAudioAsset(
        id: Long,
        assetId: Long,
        actor: UserId,
    ): MusicItemView?

    suspend fun attachCoverAsset(
        id: Long,
        assetId: Long,
        actor: UserId,
    ): MusicItemView?

    suspend fun getById(id: Long): MusicItemView?

    suspend fun findByIds(ids: List<Long>): List<MusicItemView>

    suspend fun listActive(
        clubId: Long?,
        limit: Int,
        offset: Int,
        tag: String?,
        q: String?,
        type: MusicItemType? = null,
    ): List<MusicItemView>

    suspend fun listAll(
        clubId: Long?,
        limit: Int,
        offset: Int,
        type: MusicItemType? = null,
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

    /**
     * Returns like counts for given items.
     */
    suspend fun countsForItems(itemIds: Collection<Long>): Map<Long, Int>

    /**
     * Returns ids of items liked by the user from the provided set.
     */
    suspend fun likedItemsForUser(userId: Long, itemIds: Collection<Long>): Set<Long>
}

interface TrackOfNightRepository {
    /**
     * Sets track of night for [setId]. The operation is idempotent for the same track within the set.
     * Returns the actual state after the operation.
     */
    suspend fun setTrackOfNight(
        setId: Long,
        trackId: Long,
        actorId: Long,
        markedAt: Instant,
    ): TrackOfNight

    /** Current track of night for the given [setId] if present. */
    suspend fun currentForSet(setId: Long): TrackOfNight?

    /** Current track of night ids for provided [setIds]. */
    suspend fun currentTracksForSets(setIds: Collection<Long>): Map<Long, Long>

    /** Latest update timestamp for any track of night. */
    suspend fun lastUpdatedAt(): Instant?

    /** Latest track of night across all sets if available. */
    suspend fun currentGlobal(): TrackOfNight?
}

interface MusicAssetRepository {
    suspend fun createAsset(
        kind: MusicAssetKind,
        bytes: ByteArray,
        contentType: String,
        sha256: String,
        sizeBytes: Long,
    ): MusicAsset

    suspend fun getAsset(id: Long): MusicAsset?

    suspend fun getAssetMeta(id: Long): MusicAssetMeta?
}

interface MusicBattleRepository {
    suspend fun create(
        clubId: Long?,
        itemAId: Long,
        itemBId: Long,
        status: MusicBattleStatus,
        startsAt: Instant,
        endsAt: Instant,
    ): MusicBattle

    suspend fun getById(id: Long): MusicBattle?

    suspend fun findCurrentActive(clubId: Long?, now: Instant): MusicBattle?

    suspend fun listRecent(
        clubId: Long?,
        limit: Int,
        offset: Int,
    ): List<MusicBattle>

    suspend fun setStatus(id: Long, status: MusicBattleStatus, updatedAt: Instant): Boolean
}

interface MusicBattleVoteRepository {
    suspend fun upsertVote(
        battleId: Long,
        userId: Long,
        chosenItemId: Long,
        now: Instant,
    ): MusicVoteUpsertResult

    suspend fun findUserVote(battleId: Long, userId: Long): MusicBattleVote?

    suspend fun aggregateVotes(battleId: Long): MusicBattleVoteAggregate?
}

interface MusicStemsRepository {
    suspend fun linkStemAsset(itemId: Long, assetId: Long, now: Instant): MusicStemsPackage

    suspend fun unlinkStemAsset(itemId: Long): Boolean

    suspend fun getStemAsset(itemId: Long): MusicStemsPackage?
}
