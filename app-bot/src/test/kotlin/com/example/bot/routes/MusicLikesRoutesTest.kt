package com.example.bot.routes

import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.music.Like
import com.example.bot.music.Mixtape
import com.example.bot.music.MixtapeService
import com.example.bot.music.MusicItemCreate
import com.example.bot.music.MusicItemRepository
import com.example.bot.music.MusicItemView
import com.example.bot.music.MusicLikesRepository
import com.example.bot.music.MusicPlaylistRepository
import com.example.bot.music.MusicService
import com.example.bot.music.PlaylistCreate
import com.example.bot.music.PlaylistFullView
import com.example.bot.music.PlaylistView
import com.example.bot.music.TrackOfNight
import com.example.bot.music.TrackOfNightRepository
import com.example.bot.music.UserId
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Before
import org.junit.Test
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MusicLikesRoutesTest {
    private val now = Instant.parse("2024-06-08T18:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val telegramId = 42L
    private val json = Json

    @Before
    fun setUp() {
        System.setProperty("TELEGRAM_BOT_TOKEN", "test")
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = telegramId) }
    }

    @After
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `post like creates record`() = withLikesApp { repo ->
        val response = client.post("/api/music/items/10/like") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.OK, response.status)
        response.assertNoStoreHeaders()
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(10L, body["itemId"]!!.jsonPrimitive.long)
        assertTrue(body["liked"]!!.jsonPrimitive.boolean)
        assertNotNull(body["likedAt"]!!.jsonPrimitive.content)
        val stored = repo.find(telegramId, 10)
        assertNotNull(stored)
        assertEquals(stored.createdAt.toString(), body["likedAt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `post like is idempotent`() = withLikesApp { repo ->
        val first = client.post("/api/music/items/11/like") { header("X-Telegram-Init-Data", "init") }
        val second = client.post("/api/music/items/11/like") { header("X-Telegram-Init-Data", "init") }

        val firstAt = json.parseToJsonElement(first.bodyAsText()).jsonObject["likedAt"]!!.jsonPrimitive.content
        val secondAt = json.parseToJsonElement(second.bodyAsText()).jsonObject["likedAt"]!!.jsonPrimitive.content

        assertEquals(firstAt, secondAt)
        assertEquals(1, repo.likesCount())
    }

    @Test
    fun `delete like removes record`() = withLikesApp { repo ->
        client.post("/api/music/items/12/like") { header("X-Telegram-Init-Data", "init") }

        val response = client.delete("/api/music/items/12/like") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.OK, response.status)
        response.assertNoStoreHeaders()
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(false, body["liked"]!!.jsonPrimitive.boolean)
        assertTrue(body["likedAt"] == null || body["likedAt"] is JsonNull)
        assertEquals(0, repo.likesCount())
    }

    @Test
    fun `delete like is idempotent`() = withLikesApp { repo ->
        client.post("/api/music/items/13/like") { header("X-Telegram-Init-Data", "init") }
        client.delete("/api/music/items/13/like") { header("X-Telegram-Init-Data", "init") }

        val response = client.delete("/api/music/items/13/like") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, repo.likesCount())
    }

    @Test
    fun `validation keeps headers`() = withLikesApp { _ ->
        val response = client.post("/api/music/items/foo/like") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        response.assertNoStoreHeaders()
        val error = json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        assertEquals("invalid_item_id", error)
    }

    @Test
    fun `rbac forbids when role missing`() = withLikesApp(roles = emptySet()) { _ ->
        val response = client.post("/api/music/items/14/like") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        response.assertNoStoreHeaders()
    }

    @Test
    fun `get mixtape returns etag and payload`() =
        withLikesApp(mixtapeService = fakeMixtapeService()) { _ ->
            val response = client.get("/api/me/mixtape") { header("X-Telegram-Init-Data", "init") }

            assertEquals(HttpStatusCode.OK, response.status)
            response.assertNoStoreHeaders()
            val etag = response.headers[HttpHeaders.ETag]
            assertTrue(!etag.isNullOrBlank())
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(telegramId, body["userId"]!!.jsonPrimitive.long)
            assertEquals("2024-06-10T00:00:00Z", body["weekStart"]!!.jsonPrimitive.content)
            assertEquals(listOf(10L, 20L), body["items"]!!.jsonArray.map { it.jsonPrimitive.long })
        }

    @Test
    fun `get mixtape returns 304 when if-none-match matches`() =
        withLikesApp(mixtapeService = fakeMixtapeService()) { _ ->
            val first = client.get("/api/me/mixtape") { header("X-Telegram-Init-Data", "init") }
            val etag = first.headers[HttpHeaders.ETag]
            assertTrue(!etag.isNullOrBlank())

            val second =
                client.get("/api/me/mixtape") {
                    header("X-Telegram-Init-Data", "init")
                    header(HttpHeaders.IfNoneMatch, etag!!)
                }

            assertEquals(HttpStatusCode.NotModified, second.status)
            second.assertNoStoreHeaders()
            assertEquals(etag, second.headers[HttpHeaders.ETag])
        }

    
    private fun withLikesApp(
        roles: Set<Role> = setOf(Role.GUEST),
        mixtapeService: MixtapeService? = null,
        block: suspend ApplicationTestBuilder.(StubMusicLikesRepository) -> Unit,
    ) = testApplication {
        val likesRepo = StubMusicLikesRepository()
        application {
            install(ContentNegotiation) { json() }
            install(RbacPlugin) {
                userRepository = StubUserRepository()
                userRoleRepository = StubUserRoleRepository(roles)
                auditLogRepository = relaxedAuditRepository()
                principalExtractor = { TelegramPrincipal(telegramId, "tester") }
            }

            val emptyLikesRepository = EmptyMusicLikesRepository()
            val mixtapeServiceInstance =
                mixtapeService
                    ?: MixtapeService(
                        likesRepository = emptyLikesRepository,
                        musicService =
                            MusicService(
                                itemsRepo = FakeItemRepo(),
                                playlistsRepo = FakePlaylistRepo(),
                                clock = clock,
                                trackOfNightRepository = EmptyTrackOfNightRepository(),
                            ),
                        clock = clock,
                    )
            musicLikesRoutes(
                likesRepository = likesRepo,
                mixtapeService = mixtapeServiceInstance,
                clock = clock,
                botTokenProvider = { "test" },
            )
        }
        block(likesRepo)
    }

    private fun fakeMixtapeService(): MixtapeService {
        val service = mockk<MixtapeService>()
        val mixtape =
            Mixtape(
                userId = telegramId,
                items = listOf(10L, 20L),
                weekStart = Instant.parse("2024-06-10T00:00:00Z"),
            )
        coEvery { service.buildWeeklyMixtape(any()) } returns mixtape
        return service
    }

    private class StubMusicLikesRepository : MusicLikesRepository {
        private val storage = mutableListOf<Like>()

        override suspend fun like(userId: Long, itemId: Long, now: Instant): Boolean {
            if (storage.any { it.userId == userId && it.itemId == itemId }) return false
            storage += Like(userId, itemId, now)
            return true
        }

        override suspend fun unlike(userId: Long, itemId: Long): Boolean = storage.removeIf { it.userId == userId && it.itemId == itemId }

        override suspend fun findUserLikesSince(userId: Long, since: Instant): List<Like> =
            storage.filter { it.userId == userId && !it.createdAt.isBefore(since) }

        override suspend fun findAllLikesSince(since: Instant): List<Like> = storage.filter { !it.createdAt.isBefore(since) }

        override suspend fun find(userId: Long, itemId: Long): Like? = storage.firstOrNull { it.userId == userId && it.itemId == itemId }

        fun likesCount(): Int = storage.size
    }

    private class StubUserRepository : UserRepository {
        override suspend fun getByTelegramId(id: Long): User? = User(id = id, telegramId = id, username = "tester")
    }

    private class StubUserRoleRepository(private val roles: Set<Role>) : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = roles

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = emptySet()
    }

    private class EmptyMusicLikesRepository : MusicLikesRepository {
        override suspend fun like(userId: Long, itemId: Long, now: Instant): Boolean = false
        override suspend fun unlike(userId: Long, itemId: Long): Boolean = false
        override suspend fun findUserLikesSince(userId: Long, since: Instant): List<Like> = emptyList()
        override suspend fun findAllLikesSince(since: Instant): List<Like> = emptyList()
        override suspend fun find(userId: Long, itemId: Long): Like? = null
    }

    private class FakeItemRepo : MusicItemRepository {
        override suspend fun create(req: MusicItemCreate, actor: UserId): MusicItemView = throw UnsupportedOperationException()
        override suspend fun listActive(clubId: Long?, limit: Int, offset: Int, tag: String?, q: String?): List<MusicItemView> = emptyList()
        override suspend fun lastUpdatedAt(): Instant? = null
    }

    private class FakePlaylistRepo : MusicPlaylistRepository {
        override suspend fun create(req: PlaylistCreate, actor: UserId): PlaylistView = throw UnsupportedOperationException()
        override suspend fun setItems(playlistId: Long, itemIds: List<Long>) = Unit
        override suspend fun listActive(limit: Int, offset: Int): List<PlaylistView> = emptyList()
        override suspend fun itemsCount(playlistIds: Collection<Long>): Map<Long, Int> = emptyMap()
        override suspend fun getFull(id: Long): PlaylistFullView? = null
        override suspend fun lastUpdatedAt(): Instant? = null
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

    private fun relaxedAuditRepository(): AuditLogRepository = io.mockk.mockk(relaxed = true)

    private fun io.ktor.client.statement.HttpResponse.assertNoStoreHeaders() {
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        assertEquals("X-Telegram-Init-Data", headers[HttpHeaders.Vary])
    }
}
