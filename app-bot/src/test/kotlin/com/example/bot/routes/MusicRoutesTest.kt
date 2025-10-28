package com.example.bot.routes

import com.example.bot.music.MusicItemCreate
import com.example.bot.music.MusicItemRepository
import com.example.bot.music.MusicItemView
import com.example.bot.music.MusicPlaylistRepository
import com.example.bot.music.MusicService
import com.example.bot.music.MusicSource
import com.example.bot.music.PlaylistCreate
import com.example.bot.music.PlaylistFullView
import com.example.bot.music.PlaylistView
import com.example.bot.music.UserId
import com.example.bot.testing.applicationDev
import com.example.bot.testing.withInitData
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MusicRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2024-05-01T10:15:30Z"), ZoneOffset.UTC)
    private val updatedAt: Instant = Instant.parse("2024-05-01T09:00:00Z")

    private val items =
        listOf(
            MusicItemView(
                id = 1,
                clubId = null,
                title = "Track A",
                dj = "Artist 1",
                source = MusicSource.SPOTIFY,
                sourceUrl = null,
                telegramFileId = null,
                durationSec = 210,
                coverUrl = "https://example.com/a.jpg",
                tags = emptyList(),
                publishedAt = updatedAt,
            ),
            MusicItemView(
                id = 2,
                clubId = null,
                title = "Track B",
                dj = "Artist 2",
                source = MusicSource.SPOTIFY,
                sourceUrl = null,
                telegramFileId = null,
                durationSec = 180,
                coverUrl = "https://example.com/b.jpg",
                tags = emptyList(),
                publishedAt = updatedAt,
            ),
        )

    private val playlists =
        listOf(
            PlaylistView(
                id = 10,
                clubId = null,
                title = "Top Hits",
                description = "Best of",
                coverUrl = "https://example.com/pl.jpg",
            ),
        )

    private val playlistItems = mapOf(10L to items)

    private val service =
        MusicService(
            itemsRepo = FakeMusicItemRepository(items, updatedAt),
            playlistsRepo = FakeMusicPlaylistRepository(playlists, playlistItems, updatedAt),
            clock = fixedClock,
        )

    @Test
    fun `items endpoint returns list and respects etag`() =
        testApplication {
            // TELEGRAM_BOT_TOKEN отдаём через Gradle Test.environment(...)
            applicationDev {
                install(ContentNegotiation) { json() }
                musicRoutes(service)
            }

            val initData = createInitData()

            val firstResponse =
                client.get("/api/music/items") {
                    withInitData(initData)
                }
            println("DBG music items-first: status=${firstResponse.status} body=${firstResponse.bodyAsText()}")
            assertEquals(HttpStatusCode.OK, firstResponse.status)

            val etag = firstResponse.headers[HttpHeaders.ETag]
            assertNotNull(etag)
            val body = json.parseToJsonElement(firstResponse.bodyAsText())
            assertEquals(2, body.jsonArray.size)

            val cachedResponse =
                client.get("/api/music/items") {
                    withInitData(initData)
                    header(HttpHeaders.IfNoneMatch, etag)
                }
            println("DBG music items-cached: status=${cachedResponse.status} body=${cachedResponse.bodyAsText()}")
            assertEquals(HttpStatusCode.NotModified, cachedResponse.status)
        }

    @Test
    fun `playlists endpoint returns list`() =
        testApplication {
            applicationDev {
                install(ContentNegotiation) { json() }
                musicRoutes(service)
            }

            val response =
                client.get("/api/music/playlists") {
                    withInitData(createInitData())
                }
            println("DBG music playlists: status=${response.status} body=${response.bodyAsText()}")
            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.parseToJsonElement(response.bodyAsText())
            assertEquals(1, payload.jsonArray.size)
        }

    @Test
    fun `playlist details return 200 and 404`() =
        testApplication {
            applicationDev {
                install(ContentNegotiation) { json() }
                musicRoutes(service)
            }

            val okResponse =
                client.get("/api/music/playlists/10") {
                    withInitData(createInitData())
                }
            println("DBG music playlist-ok: status=${okResponse.status} body=${okResponse.bodyAsText()}")
            assertEquals(HttpStatusCode.OK, okResponse.status)
            val details = json.parseToJsonElement(okResponse.bodyAsText())
            assertEquals("Top Hits", details.jsonObject["name"]?.jsonPrimitive?.content)

            val notFound =
                client.get("/api/music/playlists/999") {
                    withInitData(createInitData())
                }
            println("DBG music playlist-404: status=${notFound.status} body=${notFound.bodyAsText()}")
            assertEquals(HttpStatusCode.NotFound, notFound.status)
        }

    private fun createInitData(): String {
        val params =
            linkedMapOf(
                "user" to WebAppInitDataTestHelper.encodeUser(id = 777, username = "tester"),
                // важно: используем "свежее" время
                "auth_date" to Instant.now().epochSecond.toString(),
            )
        return WebAppInitDataTestHelper.createInitData(TEST_BOT_TOKEN, params)
    }

    private class FakeMusicItemRepository(
        private val items: List<MusicItemView>,
        private val updatedAt: Instant?,
    ) : MusicItemRepository {
        override suspend fun create(
            req: MusicItemCreate,
            actor: UserId,
        ): MusicItemView = throw UnsupportedOperationException("Not implemented")

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
        private val playlists: List<PlaylistView>,
        private val itemsByPlaylist: Map<Long, List<MusicItemView>>,
        private val updatedAt: Instant?,
    ) : MusicPlaylistRepository {
        override suspend fun create(
            req: PlaylistCreate,
            actor: UserId,
        ): PlaylistView = throw UnsupportedOperationException("Not implemented")

        override suspend fun setItems(
            playlistId: Long,
            itemIds: List<Long>,
        ) = throw UnsupportedOperationException("Not implemented")

        override suspend fun listActive(
            limit: Int,
            offset: Int,
        ): List<PlaylistView> = playlists.drop(offset).take(limit)

        override suspend fun itemsCount(playlistIds: Collection<Long>): Map<Long, Int> =
            playlistIds.associateWith { id -> itemsByPlaylist[id]?.size ?: 0 }

        override suspend fun getFull(id: Long): PlaylistFullView? {
            val view = playlists.firstOrNull { it.id == id }
            val items = itemsByPlaylist[id]
            return if (view != null && items != null) {
                PlaylistFullView(
                    view.id,
                    view.clubId,
                    view.title,
                    view.description,
                    view.coverUrl,
                    items,
                )
            } else {
                null
            }
        }

        override suspend fun lastUpdatedAt(): Instant? = updatedAt
    }
}
