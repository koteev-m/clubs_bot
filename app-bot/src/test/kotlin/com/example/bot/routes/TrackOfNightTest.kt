package com.example.bot.routes

import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.music.MusicItemCreate
import com.example.bot.music.MusicItemRepository
import com.example.bot.music.MusicItemView
import com.example.bot.music.MusicPlaylistRepository
import com.example.bot.music.MusicService
import com.example.bot.music.MusicSource
import com.example.bot.music.PlaylistCreate
import com.example.bot.music.PlaylistFullView
import com.example.bot.music.PlaylistView
import com.example.bot.music.TrackOfNight
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.long
import com.example.bot.music.TrackOfNightRepository
import com.example.bot.music.UserId
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin

class TrackOfNightTest {
    private val now = Instant.parse("2024-06-15T10:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val telegramId = 99L
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = telegramId) }
    }

    @AfterTest
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `dj can mark track of night`() =
        withTrackOfNightApp() { repo, service ->
            val response =
                client.post("/api/music/sets/1/track-of-night") {
                    header("X-Telegram-Init-Data", "init")
                    contentType(ContentType.Application.Json)
                    setBody("{\"trackId\":10}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            response.assertNoStoreHeaders()
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(1L, body["setId"]!!.jsonPrimitive.long)
            assertEquals(10L, body["trackId"]!!.jsonPrimitive.long)
            assertTrue(body["isTrackOfNight"]!!.jsonPrimitive.boolean)
            assertFalse(body["markedAt"]!!.jsonPrimitive.content.isBlank())

            val stored = repo.currentForSet(1)
            assertNotNull(stored)
            assertEquals(10L, stored.trackId)

            val feed = service.listItems(limit = 10).second
            val flagged = feed.first { it.id == 10L }
            assertTrue(flagged.isTrackOfNight)
            assertTrue(feed.filter { it.id != 10L }.all { !it.isTrackOfNight })
        }

    @Test
    fun `second track overrides first`() =
        withTrackOfNightApp() { repo, service ->
            client.post("/api/music/sets/1/track-of-night") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("{\"trackId\":10}")
            }

            val second =
                client.post("/api/music/sets/1/track-of-night") {
                    header("X-Telegram-Init-Data", "init")
                    contentType(ContentType.Application.Json)
                    setBody("{\"trackId\":11}")
                }

            assertEquals(HttpStatusCode.OK, second.status)
            val stored = repo.currentForSet(1)
            assertNotNull(stored)
            assertEquals(11L, stored.trackId)

            val feed = service.listItems(limit = 10).second
            assertTrue(feed.first { it.id == 11L }.isTrackOfNight)
            assertTrue(feed.first { it.id == 10L }.isTrackOfNight.not())
        }

    @Test
    fun `etag changes after track of night update`() =
        withTrackOfNightApp { repo, musicService ->
            val (etag1, feed1) = musicService.listItems()
            assertTrue(feed1.none { it.isTrackOfNight })

            client.post("/api/music/sets/1/track-of-night") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"trackId":10}""")
            }

            val (etag2, feed2) = musicService.listItems()

            assertNotNull(repo.lastUpdatedAt())
            assertNotNull(etag1)
            assertNotNull(etag2)
            assertNotEquals(etag1, etag2)
            assertTrue(feed2.first { it.id == 10L }.isTrackOfNight)
        }

    @Test
    fun `etag remains the same for idempotent track updates`() =
        withTrackOfNightApp { repo, musicService ->
            client.post("/api/music/sets/1/track-of-night") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"trackId":10}""")
            }

            val (etag1, _) = musicService.listItems()
            val updatedAt1 = repo.lastUpdatedAt()

            client.post("/api/music/sets/1/track-of-night") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"trackId":10}""")
            }

            val (etag2, feed2) = musicService.listItems()
            val updatedAt2 = repo.lastUpdatedAt()

            assertEquals(etag1, etag2)
            assertEquals(updatedAt1, updatedAt2)
            assertTrue(feed2.first { it.id == 10L }.isTrackOfNight)
        }

    @Test
    fun `rejects when user has no role`() =
        withTrackOfNightApp(roles = emptySet()) { repo, _ ->
            val response =
                client.post("/api/music/sets/1/track-of-night") {
                    header("X-Telegram-Init-Data", "init")
                    contentType(ContentType.Application.Json)
                    setBody("{\"trackId\":10}")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            response.assertNoStoreHeaders()
            assertNull(repo.currentForSet(1))
        }

    @Test
    fun `forbids when set belongs to another club`() =
        withTrackOfNightApp(clubIds = setOf(1), playlists = mapOf(2L to playlist(clubId = 2))) { repo, _ ->
            val response =
                client.post("/api/music/sets/2/track-of-night") {
                    header("X-Telegram-Init-Data", "init")
                    contentType(ContentType.Application.Json)
                    setBody("{\"trackId\":10}")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            response.assertNoStoreHeaders()
            assertNull(repo.currentForSet(2))
        }

    @Test
    fun `global admin can mark track of night for foreign club`() =
        withTrackOfNightApp(
            roles = setOf(Role.GLOBAL_ADMIN),
            clubIds = emptySet(),
            playlists = mapOf(2L to playlist(clubId = 2)),
        ) { repo, service ->
            val response =
                client.post("/api/music/sets/2/track-of-night") {
                    header("X-Telegram-Init-Data", "init")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trackId":10}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            response.assertNoStoreHeaders()

            val stored = repo.currentForSet(2)
            assertNotNull(stored)
            assertEquals(10L, stored.trackId)

            val feed = service.listItems(limit = 10).second
            assertTrue(feed.first { it.id == 10L }.isTrackOfNight)
        }

    @Test
    fun `invalid set id`() =
        withTrackOfNightApp() { _, _ ->
            val response = client.post("/api/music/sets/foo/track-of-night") { header("X-Telegram-Init-Data", "init") }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            response.assertNoStoreHeaders()
            val error = json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
            assertEquals("invalid_set_id", error)

            val zeroResponse =
                client.post("/api/music/sets/0/track-of-night") { header("X-Telegram-Init-Data", "init") }
            assertEquals(HttpStatusCode.BadRequest, zeroResponse.status)
            zeroResponse.assertNoStoreHeaders()
        }

    @Test
    fun `invalid body`() =
        withTrackOfNightApp() { _, _ ->
            val invalidJson =
                client.post("/api/music/sets/1/track-of-night") {
                    header("X-Telegram-Init-Data", "init")
                    contentType(ContentType.Application.Json)
                    setBody("oops")
                }
            assertEquals(HttpStatusCode.BadRequest, invalidJson.status)
            invalidJson.assertNoStoreHeaders()
            val error = json.parseToJsonElement(invalidJson.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
            assertEquals("invalid_json", error)

            val missingTrack =
                client.post("/api/music/sets/1/track-of-night") {
                    header("X-Telegram-Init-Data", "init")
                    contentType(ContentType.Application.Json)
                    setBody("{\"trackId\":0}")
                }
            assertEquals(HttpStatusCode.BadRequest, missingTrack.status)
            val missingError = json.parseToJsonElement(missingTrack.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
            assertEquals("invalid_track_id", missingError)
        }

    @Test
    fun `track must exist in set`() =
        withTrackOfNightApp() { repo, _ ->
            val response =
                client.post("/api/music/sets/1/track-of-night") {
                    header("X-Telegram-Init-Data", "init")
                    contentType(ContentType.Application.Json)
                    setBody("{\"trackId\":999}")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            response.assertNoStoreHeaders()
            val error = json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
            assertEquals("track_not_in_set", error)
            assertNull(repo.currentForSet(1))
        }

    private fun withTrackOfNightApp(
        roles: Set<Role> = setOf(Role.MANAGER),
        clubIds: Set<Long> = setOf(1),
        playlists: Map<Long, PlaylistFullView> = mapOf(1L to playlist()),
        block: suspend ApplicationTestBuilder.(StubTrackOfNightRepository, MusicService) -> Unit,
    ) =
        testApplication {
            val trackRepository = StubTrackOfNightRepository()
            val playlistRepository = StubMusicPlaylistRepository(playlists)
            val items = playlists.values.flatMap { it.items }
            val itemRepository = StubMusicItemRepository(items)
            val musicService =
                MusicService(
                    itemsRepo = itemRepository,
                    playlistsRepo = playlistRepository,
                    clock = clock,
                    trackOfNightRepository = trackRepository,
                )
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(roles, clubIds)
                    auditLogRepository = relaxedAuditRepository()
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }

                trackOfNightRoutes(
                    trackOfNightRepository = trackRepository,
                    playlistsRepository = playlistRepository,
                    clock = clock,
                    botTokenProvider = { "test" },
                )

                environment.monitor.subscribe(ApplicationStopped) {
                    resetMiniAppValidator()
                }
            }

            block(trackRepository, musicService)
        }

    private fun playlist(clubId: Long = 1): PlaylistFullView =
        PlaylistFullView(
            id = clubId.toLong(),
            clubId = clubId,
            title = "Set $clubId",
            description = null,
            coverUrl = null,
            items =
                listOf(
                    musicItem(10),
                    musicItem(11),
                ),
        )

    private fun musicItem(id: Long): MusicItemView =
        MusicItemView(
            id = id,
            clubId = 1,
            title = "Track $id",
            dj = "DJ",
            source = MusicSource.SPOTIFY,
            sourceUrl = null,
            telegramFileId = null,
            durationSec = 120,
            coverUrl = null,
            tags = emptyList(),
            publishedAt = now,
        )

    private class StubTrackOfNightRepository : TrackOfNightRepository {
        private val storage = mutableMapOf<Long, TrackOfNight>()

        override suspend fun setTrackOfNight(setId: Long, trackId: Long, actorId: Long, markedAt: Instant): TrackOfNight {
            val existing = storage[setId]
            if (existing != null && existing.trackId == trackId) {
                return existing
            }

            val record = TrackOfNight(setId, trackId, actorId, markedAt)
            storage[setId] = record
            return record
        }

        override suspend fun currentForSet(setId: Long): TrackOfNight? = storage[setId]

        override suspend fun currentTracksForSets(setIds: Collection<Long>): Map<Long, Long> =
            storage.filterKeys { it in setIds }.mapValues { it.value.trackId }

        override suspend fun lastUpdatedAt(): Instant? = storage.values.maxOfOrNull { it.markedAt }

        override suspend fun currentGlobal(): TrackOfNight? = storage.values.maxByOrNull { it.markedAt }
    }

    private class StubMusicPlaylistRepository(
        private val playlists: Map<Long, PlaylistFullView>,
    ) : MusicPlaylistRepository {
        override suspend fun create(req: PlaylistCreate, actor: UserId): PlaylistView =
            throw UnsupportedOperationException()

        override suspend fun setItems(playlistId: Long, itemIds: List<Long>) = throw UnsupportedOperationException()

        override suspend fun listActive(limit: Int, offset: Int): List<PlaylistView> = playlists.values.map { toView(it) }

        override suspend fun itemsCount(playlistIds: Collection<Long>): Map<Long, Int> =
            playlistIds.associateWith { playlists[it]?.items?.size ?: 0 }

        override suspend fun getFull(id: Long): PlaylistFullView? = playlists[id]

        override suspend fun lastUpdatedAt(): Instant? = null

        private fun toView(full: PlaylistFullView): PlaylistView =
            PlaylistView(full.id, full.clubId, full.title, full.description, full.coverUrl)
    }

    private class StubMusicItemRepository(
        private val items: List<MusicItemView>,
    ) : MusicItemRepository {
        override suspend fun create(req: MusicItemCreate, actor: UserId): MusicItemView = throw UnsupportedOperationException()

        override suspend fun listActive(
            clubId: Long?,
            limit: Int,
            offset: Int,
            tag: String?,
            q: String?,
        ): List<MusicItemView> = items.drop(offset).take(limit)

        override suspend fun lastUpdatedAt(): Instant? = null
    }

    private class StubUserRepository : UserRepository {
        override suspend fun getByTelegramId(id: Long): User? = User(id = id, telegramId = id, username = "tester")

        override suspend fun getById(id: Long): User? = User(id = id, telegramId = id, username = "tester")
    }

    private class StubUserRoleRepository(
        private val roles: Set<Role>,
        private val clubIds: Set<Long>,
    ) : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = roles

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = clubIds
    }

    private fun relaxedAuditRepository(): AuditLogRepository = io.mockk.mockk(relaxed = true)

    private fun io.ktor.client.statement.HttpResponse.assertNoStoreHeaders() {
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        assertEquals("X-Telegram-Init-Data", headers[HttpHeaders.Vary])
    }
}
