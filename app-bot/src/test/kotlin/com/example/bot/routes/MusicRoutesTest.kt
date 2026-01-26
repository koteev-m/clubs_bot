package com.example.bot.routes

import com.example.bot.music.MusicAsset
import com.example.bot.music.MusicAssetKind
import com.example.bot.music.MusicAssetMeta
import com.example.bot.music.MusicItemCreate
import com.example.bot.music.MusicItemRepository
import com.example.bot.music.MusicItemType
import com.example.bot.music.MusicItemUpdate
import com.example.bot.music.MusicItemView
import com.example.bot.music.MusicLikesRepository
import com.example.bot.music.MusicPlaylistRepository
import com.example.bot.music.MusicService
import com.example.bot.music.MusicSource
import com.example.bot.music.PlaylistCreate
import com.example.bot.music.PlaylistFullView
import com.example.bot.music.PlaylistView
import com.example.bot.music.TrackOfNight
import com.example.bot.music.TrackOfNightRepository
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
import kotlinx.serialization.json.long
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
                description = null,
                itemType = MusicItemType.TRACK,
                source = MusicSource.SPOTIFY,
                sourceUrl = null,
                audioAssetId = null,
                telegramFileId = null,
                durationSec = 210,
                coverUrl = "https://example.com/a.jpg",
                coverAssetId = null,
                tags = emptyList(),
                publishedAt = updatedAt,
            ),
            MusicItemView(
                id = 2,
                clubId = null,
                title = "Track B",
                dj = "Artist 2",
                description = null,
                itemType = MusicItemType.TRACK,
                source = MusicSource.SPOTIFY,
                sourceUrl = null,
                audioAssetId = null,
                telegramFileId = null,
                durationSec = 180,
                coverUrl = "https://example.com/b.jpg",
                coverAssetId = null,
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

    private val itemsRepository = FakeMusicItemRepository(items, updatedAt)
    private val likesRepository = FakeMusicLikesRepository()
    private val assetsRepository = FakeMusicAssetsRepository()
    private val service =
        MusicService(
            itemsRepo = itemsRepository,
            playlistsRepo = FakeMusicPlaylistRepository(playlists, playlistItems, updatedAt),
            likesRepository = likesRepository,
            clock = fixedClock,
            trackOfNightRepository = EmptyTrackOfNightRepository(),
        )
    private val mixtapeService = com.example.bot.music.MixtapeService(likesRepository, service, fixedClock)

    @Test
    fun `items endpoint returns list and respects etag`() =
        testApplication {
            // TELEGRAM_BOT_TOKEN отдаём через Gradle Test.environment(...)
            applicationDev {
                install(ContentNegotiation) { json() }
                musicRoutes(service, itemsRepository, likesRepository, assetsRepository, mixtapeService)
            }

            val initData = createInitData()

            val firstResponse =
                client.get("/api/music/items") {
                    withInitData(initData)
                }
            println("DBG music items-first: status=${firstResponse.status} body=${firstResponse.bodyAsText()}")
            assertEquals(HttpStatusCode.OK, firstResponse.status)
            assertEquals("no-store", firstResponse.headers[HttpHeaders.CacheControl])
            assertEquals("X-Telegram-Init-Data", firstResponse.headers[HttpHeaders.Vary])

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
    fun `sets endpoint supports pagination and headers`() =
        testApplication {
            val sets =
                listOf(
                    MusicItemView(
                        id = 300,
                        clubId = null,
                        title = "Set 1",
                        dj = "DJ 1",
                        description = null,
                        itemType = MusicItemType.SET,
                        source = MusicSource.SPOTIFY,
                        sourceUrl = null,
                        audioAssetId = null,
                        telegramFileId = null,
                        durationSec = 200,
                        coverUrl = "https://example.com/set1.jpg",
                        coverAssetId = null,
                        tags = listOf("house"),
                        publishedAt = updatedAt,
                    ),
                    MusicItemView(
                        id = 301,
                        clubId = null,
                        title = "Set 2",
                        dj = "DJ 2",
                        description = null,
                        itemType = MusicItemType.SET,
                        source = MusicSource.SPOTIFY,
                        sourceUrl = null,
                        audioAssetId = null,
                        telegramFileId = null,
                        durationSec = 240,
                        coverUrl = "https://example.com/set2.jpg",
                        coverAssetId = null,
                        tags = listOf("techno"),
                        publishedAt = updatedAt,
                    ),
                    MusicItemView(
                        id = 302,
                        clubId = null,
                        title = "Set 3",
                        dj = "DJ 3",
                        description = null,
                        itemType = MusicItemType.SET,
                        source = MusicSource.SPOTIFY,
                        sourceUrl = null,
                        audioAssetId = null,
                        telegramFileId = null,
                        durationSec = 260,
                        coverUrl = "https://example.com/set3.jpg",
                        coverAssetId = null,
                        tags = listOf("house"),
                        publishedAt = updatedAt,
                    ),
                )
            val repo = FakeMusicItemRepository(sets, updatedAt)
            val localService =
                MusicService(
                    itemsRepo = repo,
                    playlistsRepo = FakeMusicPlaylistRepository(playlists, playlistItems, updatedAt),
                    likesRepository = likesRepository,
                    clock = fixedClock,
                    trackOfNightRepository = EmptyTrackOfNightRepository(),
                )
            val localMixtapeService = com.example.bot.music.MixtapeService(likesRepository, localService, fixedClock)

            applicationDev {
                install(ContentNegotiation) { json() }
                musicRoutes(localService, repo, likesRepository, assetsRepository, localMixtapeService)
            }

            val response =
                client.get("/api/music/sets?limit=1&offset=1") {
                    withInitData(createInitData())
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals("X-Telegram-Init-Data", response.headers[HttpHeaders.Vary])
            val body = json.parseToJsonElement(response.bodyAsText())
            assertEquals(1, body.jsonArray.size)
            assertEquals(301L, body.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.long)
        }

    @Test
    fun `playlists endpoint returns list`() =
        testApplication {
            applicationDev {
                install(ContentNegotiation) { json() }
                musicRoutes(service, itemsRepository, likesRepository, assetsRepository, mixtapeService)
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
                musicRoutes(service, itemsRepository, likesRepository, assetsRepository, mixtapeService)
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

    @Test
    fun `asset endpoints available without init data only for published items`() =
        testApplication {
            val publishedItem =
                MusicItemView(
                    id = 100,
                    clubId = null,
                    title = "Published Track",
                    dj = "DJ",
                    description = null,
                    itemType = MusicItemType.TRACK,
                    source = MusicSource.SPOTIFY,
                    sourceUrl = null,
                    audioAssetId = 10,
                    telegramFileId = null,
                    durationSec = 180,
                    coverUrl = null,
                    coverAssetId = 11,
                    tags = emptyList(),
                    publishedAt = updatedAt,
                )
            val unpublishedItem = publishedItem.copy(id = 101, publishedAt = null)
            val repo = FakeMusicItemRepository(listOf(publishedItem, unpublishedItem), updatedAt)
            val assetMetaAudio =
                MusicAssetMeta(
                    id = 10,
                    kind = MusicAssetKind.AUDIO,
                    contentType = "audio/mpeg",
                    sha256 = "audio-sha",
                    sizeBytes = 4,
                    updatedAt = updatedAt,
                )
            val assetMetaCover =
                MusicAssetMeta(
                    id = 11,
                    kind = MusicAssetKind.COVER,
                    contentType = "image/jpeg",
                    sha256 = "cover-sha",
                    sizeBytes = 3,
                    updatedAt = updatedAt,
                )
            val assetsRepo =
                FakeMusicAssetsRepository(
                    assets =
                        mapOf(
                            10L to
                                MusicAsset(
                                    id = 10,
                                    kind = MusicAssetKind.AUDIO,
                                    bytes = byteArrayOf(1, 2, 3, 4),
                                    contentType = assetMetaAudio.contentType,
                                    sha256 = assetMetaAudio.sha256,
                                    sizeBytes = 4,
                                    createdAt = updatedAt,
                                    updatedAt = updatedAt,
                                ),
                            11L to
                                MusicAsset(
                                    id = 11,
                                    kind = MusicAssetKind.COVER,
                                    bytes = byteArrayOf(5, 6, 7),
                                    contentType = assetMetaCover.contentType,
                                    sha256 = assetMetaCover.sha256,
                                    sizeBytes = 3,
                                    createdAt = updatedAt,
                                    updatedAt = updatedAt,
                                ),
                        ),
                    metas = mapOf(10L to assetMetaAudio, 11L to assetMetaCover),
                )
            val localService =
                MusicService(
                    itemsRepo = repo,
                    playlistsRepo = FakeMusicPlaylistRepository(playlists, playlistItems, updatedAt),
                    likesRepository = likesRepository,
                    clock = fixedClock,
                    trackOfNightRepository = EmptyTrackOfNightRepository(),
                )
            val localMixtapeService = com.example.bot.music.MixtapeService(likesRepository, localService, fixedClock)

            applicationDev {
                install(ContentNegotiation) { json() }
                musicRoutes(localService, repo, likesRepository, assetsRepo, localMixtapeService)
            }

            val audioOk = client.get("/api/music/items/100/audio")
            assertEquals(HttpStatusCode.OK, audioOk.status)
            val coverOk = client.get("/api/music/items/100/cover")
            assertEquals(HttpStatusCode.OK, coverOk.status)

            val audioHidden = client.get("/api/music/items/101/audio")
            assertEquals(HttpStatusCode.NotFound, audioHidden.status)
            val coverHidden = client.get("/api/music/items/101/cover")
            assertEquals(HttpStatusCode.NotFound, coverHidden.status)
        }

    @Test
    fun `asset endpoints return 304 with quoted etag without reading bytes`() =
        testApplication {
            val publishedItem =
                MusicItemView(
                    id = 200,
                    clubId = null,
                    title = "Published Track",
                    dj = "DJ",
                    description = null,
                    itemType = MusicItemType.TRACK,
                    source = MusicSource.SPOTIFY,
                    sourceUrl = null,
                    audioAssetId = 20,
                    telegramFileId = null,
                    durationSec = 180,
                    coverUrl = null,
                    coverAssetId = null,
                    tags = emptyList(),
                    publishedAt = updatedAt,
                )
            val repo = FakeMusicItemRepository(listOf(publishedItem), updatedAt)
            val assetMeta =
                MusicAssetMeta(
                    id = 20,
                    kind = MusicAssetKind.AUDIO,
                    contentType = "audio/mpeg",
                    sha256 = "abc",
                    sizeBytes = 4,
                    updatedAt = updatedAt,
                )
            var getAssetCalled = false
            val assetsRepo =
                FakeMusicAssetsRepository(
                    metas = mapOf(20L to assetMeta),
                    onGetAsset = {
                        getAssetCalled = true
                        error("getAsset should not be called when If-None-Match matches")
                    },
                )
            val localService =
                MusicService(
                    itemsRepo = repo,
                    playlistsRepo = FakeMusicPlaylistRepository(playlists, playlistItems, updatedAt),
                    likesRepository = likesRepository,
                    clock = fixedClock,
                    trackOfNightRepository = EmptyTrackOfNightRepository(),
                )
            val localMixtapeService = com.example.bot.music.MixtapeService(likesRepository, localService, fixedClock)

            applicationDev {
                install(ContentNegotiation) { json() }
                musicRoutes(localService, repo, likesRepository, assetsRepo, localMixtapeService)
            }

            val response =
                client.get("/api/music/items/200/audio") {
                    header(HttpHeaders.IfNoneMatch, "\"abc\"")
                }
            assertEquals(HttpStatusCode.NotModified, response.status)
            assertEquals("abc", response.headers[HttpHeaders.ETag])
            assertEquals("private, max-age=3600, must-revalidate", response.headers[HttpHeaders.CacheControl])
            assertEquals(false, getAssetCalled)
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

        override suspend fun update(
            id: Long,
            req: MusicItemUpdate,
            actor: UserId,
        ): MusicItemView? = throw UnsupportedOperationException("Not implemented")

        override suspend fun setPublished(id: Long, publishedAt: Instant?, actor: UserId): MusicItemView? =
            throw UnsupportedOperationException("Not implemented")

        override suspend fun attachAudioAsset(id: Long, assetId: Long, actor: UserId): MusicItemView? =
            throw UnsupportedOperationException("Not implemented")

        override suspend fun attachCoverAsset(id: Long, assetId: Long, actor: UserId): MusicItemView? =
            throw UnsupportedOperationException("Not implemented")

        override suspend fun getById(id: Long): MusicItemView? = items.firstOrNull { it.id == id }

        override suspend fun findByIds(ids: List<Long>): List<MusicItemView> = items.filter { it.id in ids }

        override suspend fun listActive(
            clubId: Long?,
            limit: Int,
            offset: Int,
            tag: String?,
            q: String?,
            type: MusicItemType?,
        ): List<MusicItemView> {
            var filtered = items
            if (type != null) {
                filtered = filtered.filter { it.itemType == type }
            }
            if (!tag.isNullOrBlank()) {
                filtered = filtered.filter { it.tags?.contains(tag) == true }
            }
            if (!q.isNullOrBlank()) {
                filtered =
                    filtered.filter {
                        it.title.contains(q, ignoreCase = true) || (it.dj?.contains(q, ignoreCase = true) ?: false)
                    }
            }
            return filtered.drop(offset).take(limit)
        }

        override suspend fun listAll(clubId: Long?, limit: Int, offset: Int, type: MusicItemType?): List<MusicItemView> =
            items.drop(offset).take(limit)

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

    private class FakeMusicLikesRepository : MusicLikesRepository {
        override suspend fun like(userId: Long, itemId: Long, now: Instant): Boolean = true

        override suspend fun unlike(userId: Long, itemId: Long): Boolean = true

        override suspend fun findUserLikesSince(userId: Long, since: Instant): List<com.example.bot.music.Like> = emptyList()

        override suspend fun findAllLikesSince(since: Instant): List<com.example.bot.music.Like> = emptyList()

        override suspend fun find(userId: Long, itemId: Long): com.example.bot.music.Like? = null

        override suspend fun countsForItems(itemIds: Collection<Long>): Map<Long, Int> = emptyMap()

        override suspend fun likedItemsForUser(userId: Long, itemIds: Collection<Long>): Set<Long> = emptySet()
    }

    private class FakeMusicAssetsRepository(
        private val assets: Map<Long, MusicAsset> = emptyMap(),
        private val metas: Map<Long, MusicAssetMeta> = emptyMap(),
        private val onGetAsset: ((Long) -> Unit)? = null,
    ) : com.example.bot.music.MusicAssetRepository {
        override suspend fun createAsset(
            kind: com.example.bot.music.MusicAssetKind,
            bytes: ByteArray,
            contentType: String,
            sha256: String,
            sizeBytes: Long,
        ): com.example.bot.music.MusicAsset {
            throw UnsupportedOperationException("Not implemented")
        }

        override suspend fun getAsset(id: Long): com.example.bot.music.MusicAsset? {
            onGetAsset?.invoke(id)
            return assets[id]
        }

        override suspend fun getAssetMeta(id: Long): com.example.bot.music.MusicAssetMeta? = metas[id]
    }


    private class EmptyTrackOfNightRepository : TrackOfNightRepository {
        override suspend fun setTrackOfNight(
            setId: Long,
            trackId: Long,
            actorId: Long,
            markedAt: Instant,
        ): TrackOfNight = throw UnsupportedOperationException("Not implemented")

        override suspend fun currentForSet(setId: Long): TrackOfNight? = null

        override suspend fun currentTracksForSets(setIds: Collection<Long>): Map<Long, Long> = emptyMap()

        override suspend fun lastUpdatedAt(): Instant? = null

        override suspend fun currentGlobal(): TrackOfNight? = null
    }
}
