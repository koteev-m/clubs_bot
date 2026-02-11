package com.example.bot.music

import com.example.bot.music.MusicItemCreate
import com.example.bot.music.MusicItemRepository
import com.example.bot.music.MusicItemType
import com.example.bot.music.MusicItemUpdate
import com.example.bot.music.MusicItemView
import com.example.bot.music.MusicPlaylistRepository
import com.example.bot.music.MusicSource
import com.example.bot.music.PlaylistCreate
import com.example.bot.music.PlaylistFullView
import com.example.bot.music.PlaylistView
import com.example.bot.music.TrackOfNight
import com.example.bot.music.TrackOfNightRepository
import com.example.bot.music.UserId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Test

class MixtapeServiceTest {
    private val now = Instant.parse("2024-06-12T15:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `uses likes from last seven days only`() = runBlocking {
        val likesRepo = StubMusicLikesRepository()
        likesRepo.addLike(Like(userId = 1, itemId = 11, createdAt = now.minusSeconds(3600)))
        likesRepo.addLike(Like(userId = 1, itemId = 22, createdAt = now.minusSeconds(6 * 24 * 3600)))
        likesRepo.addLike(Like(userId = 1, itemId = 33, createdAt = now.minusSeconds(10 * 24 * 3600)))

        val service = mixtapeService(likesRepo, emptyList())
        val mixtape = service.buildWeeklyMixtape(1)

        assertEquals(listOf(11L, 22L), mixtape.items)
    }

    @Test
    fun `orders likes by recency and keeps unique`() = runBlocking {
        val likesRepo = StubMusicLikesRepository()
        likesRepo.addLike(Like(userId = 1, itemId = 5, createdAt = now.minusSeconds(100)))
        likesRepo.addLike(Like(userId = 1, itemId = 3, createdAt = now.minusSeconds(50)))
        likesRepo.addLike(Like(userId = 1, itemId = 5, createdAt = now.minusSeconds(10)))

        val service = mixtapeService(likesRepo, emptyList())
        val mixtape = service.buildWeeklyMixtape(1)

        assertEquals(listOf(5L, 3L), mixtape.items)
    }

    @Test
    fun `appends recommendations after likes`() = runBlocking {
        val likesRepo = StubMusicLikesRepository()
        likesRepo.addLike(Like(userId = 1, itemId = 2, createdAt = now.minusSeconds(100)))

        val items =
            listOf(
                musicItem(id = 1, title = "A"),
                musicItem(id = 2, title = "B"),
                musicItem(id = 3, title = "C"),
            )

        val service = mixtapeService(likesRepo, items)
        val mixtape = service.buildWeeklyMixtape(1)

        assertEquals(listOf(2L, 1L, 3L), mixtape.items)
    }

    @Test
    fun `computes week start for current week`() = runBlocking {
        val likesRepo = StubMusicLikesRepository()
        val service = mixtapeService(likesRepo, emptyList())

        val mixtape = service.buildWeeklyMixtape(1)

        assertEquals(Instant.parse("2024-06-10T00:00:00Z"), mixtape.weekStart)
    }

    @Test
    fun `builds deterministic mixtape with fixed clock`() = runBlocking {
        val likesRepo = StubMusicLikesRepository()
        likesRepo.addLike(Like(userId = 1, itemId = 2, createdAt = now.minusSeconds(100)))
        likesRepo.addLike(Like(userId = 1, itemId = 4, createdAt = now.minusSeconds(200)))

        val items =
            listOf(
                musicItem(id = 1, title = "A"),
                musicItem(id = 3, title = "C"),
                musicItem(id = 5, title = "E"),
            )

        val service = mixtapeService(likesRepo, items)
        val first = service.buildWeeklyMixtape(1)
        val second = service.buildWeeklyMixtape(1)

        assertEquals(first, second)
        assertEquals(listOf(2L, 4L), first.items.take(2))
    }

    @Test
    fun `recommendations only include published sets`() = runBlocking {
        val likesRepo = StubMusicLikesRepository()
        val items =
            listOf(
                musicItem(id = 10, title = "Unpublished", publishedAt = null),
                musicItem(id = 20, title = "Published"),
            )

        val service = mixtapeService(likesRepo, items)
        val mixtape = service.buildWeeklyMixtape(1)

        assertEquals(listOf(20L), mixtape.items)
    }

    @Test
    fun `recommendations never include tracks`() = runBlocking {
        val likesRepo = StubMusicLikesRepository()
        val items =
            listOf(
                musicItem(id = 5, title = "Track", type = MusicItemType.TRACK),
                musicItem(id = 6, title = "Set", type = MusicItemType.SET),
            )

        val service = mixtapeService(likesRepo, items)
        val mixtape = service.buildWeeklyMixtape(1)

        assertEquals(listOf(6L), mixtape.items)
    }

    @Test
    fun `global mixtape order is deterministic for equal data`() = runBlocking {
        val likesRepo = StubMusicLikesRepository()
        likesRepo.addLike(Like(userId = 1, itemId = 1, createdAt = now.minusSeconds(200)))
        likesRepo.addLike(Like(userId = 2, itemId = 1, createdAt = now.minusSeconds(100)))
        likesRepo.addLike(Like(userId = 3, itemId = 2, createdAt = now.minusSeconds(300)))
        likesRepo.addLike(Like(userId = 4, itemId = 2, createdAt = now.minusSeconds(50)))
        likesRepo.addLike(Like(userId = 5, itemId = 3, createdAt = now.minusSeconds(400)))
        likesRepo.addLike(Like(userId = 6, itemId = 4, createdAt = now.minusSeconds(250)))
        likesRepo.addLike(Like(userId = 7, itemId = 4, createdAt = now.minusSeconds(100)))

        val service = mixtapeService(likesRepo, emptyList())
        val first = service.buildWeeklyGlobalMixtape()
        val second = service.buildWeeklyGlobalMixtape()

        assertEquals(listOf(2L, 1L, 4L, 3L), first.items)
        assertEquals(first, second)
    }

    private fun mixtapeService(
        likesRepository: MusicLikesRepository,
        items: List<MusicItemView>,
    ): MixtapeService {
        val musicService =
            MusicService(
                itemsRepo = FakeMusicItemRepository(items, updatedAt = now),
                playlistsRepo = FakeMusicPlaylistRepository(updatedAt = now),
                likesRepository = likesRepository,
                clock = clock,
                trackOfNightRepository = EmptyTrackOfNightRepository(),
            )
        return MixtapeService(likesRepository, musicService, clock)
    }

    private fun musicItem(
        id: Long,
        title: String,
        type: MusicItemType = MusicItemType.SET,
        publishedAt: Instant? = now,
    ): MusicItemView =
        MusicItemView(
            id = id,
            clubId = null,
            title = title,
            dj = null,
            description = null,
            itemType = type,
            source = MusicSource.SPOTIFY,
            sourceUrl = null,
            audioAssetId = null,
            telegramFileId = null,
            durationSec = 120,
            coverUrl = null,
            coverAssetId = null,
            tags = emptyList(),
            publishedAt = publishedAt,
        )

    private class StubMusicLikesRepository : MusicLikesRepository {
        private val likes = mutableListOf<Like>()

        override suspend fun like(userId: Long, itemId: Long, now: Instant): Boolean {
            if (likes.any { it.userId == userId && it.itemId == itemId }) return false
            likes += Like(userId, itemId, now)
            return true
        }

        override suspend fun unlike(userId: Long, itemId: Long): Boolean {
            val removed = likes.removeIf { it.userId == userId && it.itemId == itemId }
            return removed
        }

        override suspend fun findUserLikesSince(userId: Long, since: Instant): List<Like> =
            likes.filter { it.userId == userId && !it.createdAt.isBefore(since) }

        override suspend fun findAllLikesSince(since: Instant): List<Like> = likes.filter { !it.createdAt.isBefore(since) }
        override suspend fun aggregateUserLikesSince(clubId: Long, since: Instant): Map<Long, Int> =
            likes.filter { !it.createdAt.isBefore(since) }.groupingBy { it.userId }.eachCount()

        override suspend fun find(userId: Long, itemId: Long): Like? = likes.firstOrNull { it.userId == userId && it.itemId == itemId }

        override suspend fun countsForItems(itemIds: Collection<Long>): Map<Long, Int> =
            itemIds.associateWith { id -> likes.count { it.itemId == id } }

        override suspend fun likedItemsForUser(userId: Long, itemIds: Collection<Long>): Set<Long> =
            likes.filter { it.userId == userId && it.itemId in itemIds }.mapTo(mutableSetOf()) { it.itemId }

        fun addLike(like: Like) {
            likes += like
        }
    }

    private class FakeMusicItemRepository(
        private val items: List<MusicItemView>,
        private val updatedAt: Instant?,
    ) : MusicItemRepository {
        override suspend fun create(req: MusicItemCreate, actor: UserId): MusicItemView = throw UnsupportedOperationException()
        override suspend fun update(id: Long, req: MusicItemUpdate, actor: UserId): MusicItemView? = throw UnsupportedOperationException()
        override suspend fun setPublished(id: Long, publishedAt: Instant?, actor: UserId): MusicItemView? = throw UnsupportedOperationException()
        override suspend fun attachAudioAsset(id: Long, assetId: Long, actor: UserId): MusicItemView? = throw UnsupportedOperationException()
        override suspend fun attachCoverAsset(id: Long, assetId: Long, actor: UserId): MusicItemView? = throw UnsupportedOperationException()
        override suspend fun getById(id: Long): MusicItemView? = items.firstOrNull { it.id == id }
        override suspend fun findByIds(ids: List<Long>): List<MusicItemView> = items.filter { it.id in ids }

        override suspend fun listActive(
            clubId: Long?,
            limit: Int,
            offset: Int,
            tag: String?,
            q: String?,
            type: MusicItemType?,
        ): List<MusicItemView> =
            items
                .asSequence()
                .filter { it.publishedAt != null }
                .filter { type == null || it.itemType == type }
                .drop(offset)
                .take(limit)
                .toList()

        override suspend fun listAll(clubId: Long?, limit: Int, offset: Int, type: MusicItemType?): List<MusicItemView> =
            items.drop(offset).take(limit)

        override suspend fun lastUpdatedAt(): Instant? = updatedAt
    }

    private class FakeMusicPlaylistRepository(
        private val updatedAt: Instant?,
    ) : MusicPlaylistRepository {
        override suspend fun create(req: PlaylistCreate, actor: UserId): PlaylistView = throw UnsupportedOperationException()

        override suspend fun setItems(playlistId: Long, itemIds: List<Long>) = throw UnsupportedOperationException()

        override suspend fun listActive(limit: Int, offset: Int): List<PlaylistView> = emptyList()

        override suspend fun itemsCount(playlistIds: Collection<Long>): Map<Long, Int> = emptyMap()

        override suspend fun getFull(id: Long): PlaylistFullView? = null

        override suspend fun lastUpdatedAt(): Instant? = updatedAt
    }

    private class EmptyTrackOfNightRepository : TrackOfNightRepository {
        override suspend fun setTrackOfNight(
            setId: Long,
            trackId: Long,
            actorId: Long,
            markedAt: Instant,
        ): TrackOfNight = throw UnsupportedOperationException()

        override suspend fun currentForSet(setId: Long): TrackOfNight? = null

        override suspend fun currentTracksForSets(setIds: Collection<Long>): Map<Long, Long> = emptyMap()

        override suspend fun lastUpdatedAt(): Instant? = null

        override suspend fun currentGlobal(): TrackOfNight? = null
    }
}
