package com.example.bot.routes

import com.example.bot.admin.AdminHall
import com.example.bot.admin.AdminHallsRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.http.ErrorCodes
import com.example.bot.layout.ClubLayout
import com.example.bot.layout.HallPlan
import com.example.bot.layout.HallPlansRepository
import com.example.bot.layout.LayoutAssets
import com.example.bot.layout.LayoutAssetsRepository
import com.example.bot.layout.LayoutRepository
import com.example.bot.layout.Table
import com.example.bot.layout.Zone
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HallPlanRoutesTest {
    private val telegramId = 123L
    private val clock: Clock = Clock.fixed(Instant.parse("2024-06-02T10:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = telegramId) }
    }

    @After
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `upload rejects svg content type`() = withApp { _, _ ->
        val response =
            client.put("/api/admin/halls/1/plan") {
                header("X-Telegram-Init-Data", "init")
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                "<svg></svg>".encodeToByteArray(),
                                Headers.build {
                                    append(HttpHeaders.ContentType, "image/svg+xml")
                                    append(HttpHeaders.ContentDisposition, "filename=\"plan.svg\"")
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
    fun `upload rejects too large payload`() = withApp { _, _ ->
        val oversized = ByteArray(5 * 1024 * 1024 + 1) { 1 }
        val response =
            client.put("/api/admin/halls/1/plan") {
                header("X-Telegram-Init-Data", "init")
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                oversized,
                                Headers.build {
                                    append(HttpHeaders.ContentType, ContentType.Image.PNG.toString())
                                    append(HttpHeaders.ContentDisposition, "filename=\"plan.png\"")
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
    fun `guest receives plan bytes and etag`() = withApp { layoutRepo, plansRepo ->
        val initialLayout = client.get("/api/clubs/1/layout") { header("X-Telegram-Init-Data", "init") }
        val initialEtag = initialLayout.headers[HttpHeaders.ETag]

        val bytes = "plan".encodeToByteArray()
        val upload =
            client.put("/api/admin/halls/1/plan") {
                header("X-Telegram-Init-Data", "init")
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                bytes,
                                Headers.build {
                                    append(HttpHeaders.ContentType, ContentType.Image.PNG.toString())
                                    append(HttpHeaders.ContentDisposition, "filename=\"plan.png\"")
                                },
                            )
                        },
                    ),
                )
            }
        assertEquals(HttpStatusCode.OK, upload.status)

        val response =
            client.get("/api/clubs/1/halls/1/plan") {
                header("X-Telegram-Init-Data", "init")
            }
        val etag = response.headers[HttpHeaders.ETag]

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Image.PNG.toString(), response.headers[HttpHeaders.ContentType])
        assertEquals(bytes.toList(), response.bodyAsBytes().toList())
        assertTrue(!etag.isNullOrBlank())

        val cached =
            client.get("/api/clubs/1/halls/1/plan") {
                header("X-Telegram-Init-Data", "init")
                header(HttpHeaders.IfNoneMatch, etag!!)
            }
        assertEquals(HttpStatusCode.NotModified, cached.status)

        val afterLayout = client.get("/api/clubs/1/layout") { header("X-Telegram-Init-Data", "init") }
        val afterEtag = afterLayout.headers[HttpHeaders.ETag]

        assertNotEquals(initialEtag, afterEtag)
        assertTrue(plansRepo.getPlanForClub(1, 1) != null)
        assertTrue(layoutRepo.lastUpdatedAt(1, null) != null)
    }

    private fun withApp(
        block: suspend ApplicationTestBuilder.(TestLayoutRepository, InMemoryHallPlansRepository) -> Unit,
    ) {
        val hallsRepo = InMemoryAdminHallsRepository(clock)
        val layoutRepo = TestLayoutRepository(clock)
        val plansRepo = InMemoryHallPlansRepository(layoutRepo, hallsRepo, clock)
        val assetsRepo =
            object : LayoutAssetsRepository {
                override suspend fun loadGeometry(clubId: Long, fingerprint: String): String? = null
            }

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(setOf(Role.GLOBAL_ADMIN), setOf(1))
                    auditLogRepository = io.mockk.mockk(relaxed = true)
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }
                adminHallPlanRoutes(hallsRepo, plansRepo, botTokenProvider = { "test" })
                hallPlanRoutes(hallsRepo, plansRepo)
                layoutRoutes(layoutRepo, assetsRepo)
            }

            block(this, layoutRepo, plansRepo)
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

    private class InMemoryAdminHallsRepository(private val clock: Clock) : AdminHallsRepository {
        private val idSequence = AtomicLong(1)
        private val halls =
            mutableMapOf(
                1L to
                    AdminHall(
                        id = 1,
                        clubId = 1,
                        name = "Main",
                        isActive = true,
                        layoutRevision = 1,
                        geometryFingerprint = "fp-1",
                        createdAt = clock.instant(),
                        updatedAt = clock.instant(),
                    ),
            )

        override suspend fun listForClub(clubId: Long): List<AdminHall> =
            halls.values.filter { it.clubId == clubId }

        override suspend fun getById(id: Long): AdminHall? = halls[id]

        override suspend fun findActiveForClub(clubId: Long): AdminHall? =
            halls.values.firstOrNull { it.clubId == clubId && it.isActive }

        override suspend fun create(clubId: Long, request: com.example.bot.admin.AdminHallCreate): AdminHall {
            val id = idSequence.incrementAndGet()
            val hall =
                AdminHall(
                    id = id,
                    clubId = clubId,
                    name = request.name,
                    isActive = request.isActive,
                    layoutRevision = 1,
                    geometryFingerprint = "fp-$id",
                    createdAt = clock.instant(),
                    updatedAt = clock.instant(),
                )
            halls[id] = hall
            return hall
        }

        override suspend fun update(id: Long, request: com.example.bot.admin.AdminHallUpdate): AdminHall? {
            val existing = halls[id] ?: return null
            val updated =
                existing.copy(
                    name = request.name ?: existing.name,
                    layoutRevision = existing.layoutRevision + 1,
                    updatedAt = clock.instant(),
                )
            halls[id] = updated
            return updated
        }

        override suspend fun delete(id: Long): Boolean = halls.remove(id) != null

        override suspend fun makeActive(id: Long): AdminHall? {
            val existing = halls[id] ?: return null
            halls.replaceAll { _, hall ->
                if (hall.id == id) hall.copy(isActive = true, layoutRevision = hall.layoutRevision + 1) else hall.copy(isActive = false)
            }
            return existing.copy(isActive = true, layoutRevision = existing.layoutRevision + 1)
        }

        override suspend fun isHallNameTaken(clubId: Long, name: String, excludeHallId: Long?): Boolean =
            halls.values.any { it.clubId == clubId && it.name == name && it.id != excludeHallId }
    }

    private class TestLayoutRepository(private val clock: Clock) : LayoutRepository {
        private var updatedAt: Instant = clock.instant()

        override suspend fun getLayout(clubId: Long, eventId: Long?): ClubLayout? {
            if (clubId != 1L) return null
            return ClubLayout(
                clubId = clubId,
                eventId = eventId,
                zones = listOf(Zone(id = "vip", name = "VIP", tags = emptyList(), order = 1)),
                tables = listOf(Table(id = 1, zoneId = "vip", label = "T1", capacity = 4, minimumTier = "standard", status = com.example.bot.layout.TableStatus.FREE)),
                assets = LayoutAssets(geometryUrl = "/assets/layouts/1/fp-1.json", fingerprint = "fp-1"),
            )
        }

        override suspend fun lastUpdatedAt(clubId: Long, eventId: Long?): Instant? = if (clubId == 1L) updatedAt else null

        fun bump() {
            updatedAt = updatedAt.plusSeconds(1)
        }
    }

    private class InMemoryHallPlansRepository(
        private val layoutRepository: TestLayoutRepository,
        private val hallsRepository: InMemoryAdminHallsRepository,
        private val clock: Clock,
    ) : HallPlansRepository {
        private val plans = mutableMapOf<Long, HallPlan>()

        override suspend fun upsertPlan(
            hallId: Long,
            contentType: String,
            bytes: ByteArray,
            sha256: String,
            sizeBytes: Long,
        ): HallPlan {
            val now = clock.instant()
            val createdAt = plans[hallId]?.createdAt ?: now
            val plan =
                HallPlan(
                    hallId = hallId,
                    bytes = bytes,
                    contentType = contentType,
                    sha256 = sha256,
                    sizeBytes = sizeBytes,
                    createdAt = createdAt,
                    updatedAt = now,
                )
            plans[hallId] = plan
            layoutRepository.bump()
            return plan
        }

        override suspend fun getPlanForClub(clubId: Long, hallId: Long): HallPlan? {
            val hall = hallsRepository.getById(hallId) ?: return null
            if (hall.clubId != clubId) return null
            return plans[hallId]
        }
    }
}
