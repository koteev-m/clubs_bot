package com.example.bot.music

import com.example.bot.music.MusicItemCreate
import com.example.bot.music.MusicItemRepository
import com.example.bot.music.MusicItemView
import com.example.bot.music.MusicPlaylistRepository
import com.example.bot.music.MusicSource
import com.example.bot.music.PlaylistCreate
import com.example.bot.music.PlaylistFullView
import com.example.bot.music.PlaylistView
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

    private fun mixtapeService(
        likesRepository: MusicLikesRepository,
        items: List<MusicItemView>,
    ): MixtapeService {
        val musicService =
            MusicService(
                itemsRepo = FakeMusicItemRepository(items, updatedAt = now),
                playlistsRepo = FakeMusicPlaylistRepository(updatedAt = now),
                clock = clock,
            )
        return MixtapeService(likesRepository, musicService, clock)
    }

    private fun musicItem(id: Long, title: String): MusicItemView =
        MusicItemView(
            id = id,
            clubId = null,
            title = title,
            dj = null,
            source = MusicSource.SPOTIFY,
            sourceUrl = null,
            telegramFileId = null,
            durationSec = 120,
            coverUrl = null,
            tags = emptyList(),
            publishedAt = now,
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

        override suspend fun find(userId: Long, itemId: Long): Like? = likes.firstOrNull { it.userId == userId && it.itemId == itemId }

        fun addLike(like: Like) {
            likes += like
        }
    }

    private class FakeMusicItemRepository(
        private val items: List<MusicItemView>,
        private val updatedAt: Instant?,
    ) : MusicItemRepository {
        override suspend fun create(req: MusicItemCreate, actor: UserId): MusicItemView = throw UnsupportedOperationException()

        override suspend fun listActive(
            clubId: Long?,
            limit: Int,
            offset: Int,
            tag: String?,
            q: String?,
        ): List<MusicItemView> = items.drop(offset).take(limit)

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
}
