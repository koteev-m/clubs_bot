package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.http.ErrorCodes
import com.example.bot.music.MusicAsset
import com.example.bot.music.MusicAssetKind
import com.example.bot.music.MusicAssetMeta
import com.example.bot.music.MusicAssetRepository
import com.example.bot.music.MusicItemCreate
import com.example.bot.music.MusicItemRepository
import com.example.bot.music.MusicItemType
import com.example.bot.music.MusicItemUpdate
import com.example.bot.music.MusicItemView
import com.example.bot.music.MusicSource
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdminMusicRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val telegramId = 123L
    private val clock: Clock = Clock.fixed(Instant.parse("2024-06-03T10:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = telegramId) }
    }

    @After
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `rbac forbids when role missing`() = withApp(roles = emptySet()) { _, _ ->
        val response = client.get("/api/admin/music/items") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains(ErrorCodes.forbidden))
    }

    @Test
    fun `club admin forbidden for foreign club`() = withApp(clubIds = setOf(1)) { itemsRepo, _ ->
        val foreign =
            itemsRepo.create(
                MusicItemCreate(
                    clubId = 2,
                    title = "Foreign",
                    dj = "DJ",
                    description = null,
                    itemType = MusicItemType.TRACK,
                    source = MusicSource.FILE,
                    sourceUrl = null,
                    durationSec = null,
                    coverUrl = null,
                    tags = null,
                    publishedAt = null,
                ),
                actor = 1,
            )

        val response =
            client.get("/api/admin/music/items/${foreign.id}") {
                header("X-Telegram-Init-Data", "init")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains(ErrorCodes.forbidden))
    }

    @Test
    fun `upload rejects invalid content type`() = withApp { itemsRepo, _ ->
        val item =
            itemsRepo.create(
                MusicItemCreate(
                    clubId = 1,
                    title = "Track",
                    dj = null,
                    description = null,
                    itemType = MusicItemType.TRACK,
                    source = MusicSource.FILE,
                    sourceUrl = null,
                    durationSec = null,
                    coverUrl = null,
                    tags = null,
                    publishedAt = null,
                ),
                actor = 1,
            )

        val response =
            client.put("/api/admin/music/items/${item.id}/audio") {
                header("X-Telegram-Init-Data", "init")
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                "nope".encodeToByteArray(),
                                Headers.build {
                                    append(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                                    append(HttpHeaders.ContentDisposition, "filename=\"track.txt\"")
                                },
                            )
                        },
                    ),
                )
            }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertTrue(response.bodyAsText().contains(ErrorCodes.unsupported_media_type))
    }

    @Test
    fun `upload rejects too large payload`() = withApp { itemsRepo, _ ->
        val item =
            itemsRepo.create(
                MusicItemCreate(
                    clubId = 1,
                    title = "Cover",
                    dj = null,
                    description = null,
                    itemType = MusicItemType.TRACK,
                    source = MusicSource.FILE,
                    sourceUrl = null,
                    durationSec = null,
                    coverUrl = null,
                    tags = null,
                    publishedAt = null,
                ),
                actor = 1,
            )

        val oversized = ByteArray(5 * 1024 * 1024 + 1) { 1 }
        val response =
            client.put("/api/admin/music/items/${item.id}/cover") {
                header("X-Telegram-Init-Data", "init")
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                oversized,
                                Headers.build {
                                    append(HttpHeaders.ContentType, ContentType.Image.PNG.toString())
                                    append(HttpHeaders.ContentDisposition, "filename=\"cover.png\"")
                                },
                            )
                        },
                    ),
                )
            }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertTrue(response.bodyAsText().contains(ErrorCodes.payload_too_large))
    }

    @Test
    fun `happy path create upload publish`() = withApp { itemsRepo, _ ->
        val createResponse =
            client.post("/api/admin/music/items") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "clubId": 1,
                      "title": "New track",
                      "dj": "Tester",
                      "description": "Desc",
                      "itemType": "TRACK",
                      "source": "FILE",
                      "sourceUrl": null,
                      "durationSec": 90,
                      "coverUrl": null,
                      "tags": ["new"]
                    }
                    """.trimIndent(),
                )
            }

        assertEquals(HttpStatusCode.Created, createResponse.status)
        val createdPayload = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val itemId = createdPayload["id"]!!.jsonPrimitive.long

        val uploadResponse =
            client.put("/api/admin/music/items/$itemId/audio") {
                header("X-Telegram-Init-Data", "init")
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                ByteArray(1024) { 7 },
                                Headers.build {
                                    append(HttpHeaders.ContentType, ContentType.Audio.MPEG.toString())
                                    append(HttpHeaders.ContentDisposition, "filename=\"track.mp3\"")
                                },
                            )
                        },
                    ),
                )
            }

        assertEquals(HttpStatusCode.OK, uploadResponse.status)
        assertTrue(uploadResponse.bodyAsText().contains("\"assetId\""))

        val publishResponse =
            client.post("/api/admin/music/items/$itemId/publish") {
                header("X-Telegram-Init-Data", "init")
            }

        assertEquals(HttpStatusCode.OK, publishResponse.status)
        val publishedPayload = json.parseToJsonElement(publishResponse.bodyAsText()).jsonObject
        assertNotNull(publishedPayload["publishedAt"]?.jsonPrimitive?.content)

        val stored = itemsRepo.getById(itemId)
        assertNotNull(stored)
        assertEquals(MusicSource.FILE, stored.source)
    }

    private fun withApp(
        roles: Set<Role> = setOf(Role.CLUB_ADMIN),
        clubIds: Set<Long> = setOf(1),
        block: suspend ApplicationTestBuilder.(FakeMusicItemRepository, FakeMusicAssetRepository) -> Unit,
    ) {
        val itemsRepo = FakeMusicItemRepository(clock)
        val assetsRepo = FakeMusicAssetRepository(clock)
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(roles, clubIds)
                    auditLogRepository = io.mockk.mockk(relaxed = true)
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }
                adminMusicRoutes(itemsRepo, assetsRepo, clock = clock, botTokenProvider = { "test" })
            }

            block(this, itemsRepo, assetsRepo)
        }
    }

    private class StubUserRepository : UserRepository {
        override suspend fun getByTelegramId(id: Long): User? = User(id = 1, telegramId = id, username = "tester")

        override suspend fun getById(id: Long): User? = User(id = id, telegramId = id, username = "tester")
    }

    private class StubUserRoleRepository(
        private val roles: Set<Role>,
        private val clubs: Set<Long>,
    ) : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = roles

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = clubs
    }

    private class FakeMusicItemRepository(private val clock: Clock) : MusicItemRepository {
        private val idSeq = AtomicLong(0)
        private val items = mutableMapOf<Long, MusicItemView>()
        private var updatedAt: Instant? = clock.instant()

        override suspend fun create(req: MusicItemCreate, actor: Long): MusicItemView {
            val id = idSeq.incrementAndGet()
            val view =
                MusicItemView(
                    id = id,
                    clubId = req.clubId,
                    title = req.title,
                    dj = req.dj,
                    description = req.description,
                    itemType = req.itemType,
                    source = req.source,
                    sourceUrl = req.sourceUrl,
                    audioAssetId = null,
                    telegramFileId = null,
                    durationSec = req.durationSec,
                    coverUrl = req.coverUrl,
                    coverAssetId = null,
                    tags = req.tags,
                    publishedAt = req.publishedAt,
                )
            items[id] = view
            updatedAt = clock.instant()
            return view
        }

        override suspend fun update(id: Long, req: MusicItemUpdate, actor: Long): MusicItemView? {
            val existing = items[id] ?: return null
            val updated =
                existing.copy(
                    clubId = req.clubId ?: existing.clubId,
                    title = req.title ?: existing.title,
                    dj = req.dj ?: existing.dj,
                    description = req.description ?: existing.description,
                    itemType = req.itemType ?: existing.itemType,
                    source = req.source ?: existing.source,
                    sourceUrl = req.sourceUrl ?: existing.sourceUrl,
                    durationSec = req.durationSec ?: existing.durationSec,
                    coverUrl = req.coverUrl ?: existing.coverUrl,
                    tags = req.tags ?: existing.tags,
                )
            items[id] = updated
            updatedAt = clock.instant()
            return updated
        }

        override suspend fun setPublished(id: Long, publishedAt: Instant?, actor: Long): MusicItemView? {
            val existing = items[id] ?: return null
            val updated = existing.copy(publishedAt = publishedAt)
            items[id] = updated
            updatedAt = clock.instant()
            return updated
        }

        override suspend fun attachAudioAsset(id: Long, assetId: Long, actor: Long): MusicItemView? {
            val existing = items[id] ?: return null
            val updated = existing.copy(audioAssetId = assetId)
            items[id] = updated
            updatedAt = clock.instant()
            return updated
        }

        override suspend fun attachCoverAsset(id: Long, assetId: Long, actor: Long): MusicItemView? {
            val existing = items[id] ?: return null
            val updated = existing.copy(coverAssetId = assetId)
            items[id] = updated
            updatedAt = clock.instant()
            return updated
        }

        override suspend fun getById(id: Long): MusicItemView? = items[id]

        override suspend fun findByIds(ids: List<Long>): List<MusicItemView> = ids.mapNotNull { items[it] }

        override suspend fun listActive(
            clubId: Long?,
            limit: Int,
            offset: Int,
            tag: String?,
            q: String?,
            type: MusicItemType?,
        ): List<MusicItemView> =
            listAll(clubId, limit, offset, type)
                .filter { it.publishedAt != null }
                .filter { tag == null || (it.tags ?: emptyList()).contains(tag) }
                .filter { q == null || it.title.contains(q, ignoreCase = true) }

        override suspend fun listAll(
            clubId: Long?,
            limit: Int,
            offset: Int,
            type: MusicItemType?,
        ): List<MusicItemView> {
            val filtered =
                items.values
                    .filter { clubId == null || it.clubId == clubId }
                    .filter { type == null || it.itemType == type }
                    .sortedBy { it.id }
            return filtered.drop(offset).take(limit)
        }

        override suspend fun lastUpdatedAt(): Instant? = updatedAt
    }

    private class FakeMusicAssetRepository(private val clock: Clock) : MusicAssetRepository {
        private val idSeq = AtomicLong(0)
        private val assets = mutableMapOf<Long, MusicAsset>()

        override suspend fun createAsset(
            kind: MusicAssetKind,
            bytes: ByteArray,
            contentType: String,
            sha256: String,
            sizeBytes: Long,
        ): MusicAsset {
            val id = idSeq.incrementAndGet()
            val now = clock.instant()
            val asset =
                MusicAsset(
                    id = id,
                    kind = kind,
                    bytes = bytes,
                    contentType = contentType,
                    sha256 = sha256,
                    sizeBytes = sizeBytes,
                    createdAt = now,
                    updatedAt = now,
                )
            assets[id] = asset
            return asset
        }

        override suspend fun getAsset(id: Long): MusicAsset? = assets[id]

        override suspend fun getAssetMeta(id: Long): MusicAssetMeta? =
            assets[id]?.let {
                MusicAssetMeta(
                    id = it.id,
                    kind = it.kind,
                    contentType = it.contentType,
                    sha256 = it.sha256,
                    sizeBytes = it.sizeBytes,
                    updatedAt = it.updatedAt,
                )
            }
    }
}
