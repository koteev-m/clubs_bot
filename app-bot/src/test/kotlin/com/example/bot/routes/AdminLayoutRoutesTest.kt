package com.example.bot.routes

import com.example.bot.admin.AdminClub
import com.example.bot.admin.AdminClubCreate
import com.example.bot.admin.AdminClubUpdate
import com.example.bot.admin.AdminClubsRepository
import com.example.bot.admin.AdminHall
import com.example.bot.admin.AdminHallCreate
import com.example.bot.admin.AdminHallUpdate
import com.example.bot.admin.AdminHallsRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.http.ErrorCodes
import com.example.bot.layout.InMemoryLayoutRepository
import com.example.bot.layout.LayoutAssetsRepository
import com.example.bot.layout.LayoutRepository
import com.example.bot.layout.Zone
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import io.ktor.client.request.get
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
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AdminLayoutRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
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
    fun `rbac matrix for clubs`() {
        withApp(roles = setOf(Role.OWNER)) { clubsRepo, _, _ ->
            val response = client.get("/api/admin/clubs") { header("X-Telegram-Init-Data", "init") }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(2, json.parseToJsonElement(response.bodyAsText()).jsonArray.size)
        }

        withApp(roles = setOf(Role.CLUB_ADMIN), clubIds = setOf(1)) { _, _, _ ->
            val response = client.get("/api/admin/clubs") { header("X-Telegram-Init-Data", "init") }
            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(1, payload.size)
            assertEquals(1L, payload.first().jsonObject["id"]!!.jsonPrimitive.long)
        }

        withApp(roles = emptySet()) { _, _, _ ->
            val response = client.get("/api/admin/clubs") { header("X-Telegram-Init-Data", "init") }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `make-active keeps single hall`() = withApp(roles = setOf(Role.GLOBAL_ADMIN)) { _, hallsRepo, _ ->
        val initial = hallsRepo.listForClub(1)
        assertEquals(1, initial.count { it.isActive })

        val response = client.post("/api/admin/halls/2/make-active") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.OK, response.status)

        val after = hallsRepo.listForClub(1)
        assertEquals(1, after.count { it.isActive })
        assertTrue(after.first { it.isActive }.id == 2L)
    }

    @Test
    fun `table coords validation`() = withApp(roles = setOf(Role.CLUB_ADMIN), clubIds = setOf(1)) { _, _, _ ->
        val response =
            client.post("/api/admin/halls/1/tables") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "label":"T1",
                    "capacity":4,
                    "x":1.5,
                    "y":0.5
                }""",
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains(ErrorCodes.invalid_table_coords), "expected invalid_table_coords: $body")
    }

    @Test
    fun `layout etag updates after hall table change`() = withApp(roles = setOf(Role.GLOBAL_ADMIN)) { _, _, layoutRepo ->
        val initial = client.get("/api/clubs/1/layout") { header("X-Telegram-Init-Data", "init") }
        val initialEtag = initial.headers[HttpHeaders.ETag]

        val create =
            client.post("/api/admin/halls/1/tables") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "label":"T1",
                    "capacity":4,
                    "x":0.5,
                    "y":0.5
                }""",
                )
            }
        assertEquals(HttpStatusCode.Created, create.status)

        val after = client.get("/api/clubs/1/layout") { header("X-Telegram-Init-Data", "init") }
        val afterEtag = after.headers[HttpHeaders.ETag]

        assertNotEquals(initialEtag, afterEtag)
        assertEquals(1, json.parseToJsonElement(after.bodyAsText()).jsonObject["tables"]!!.jsonArray.size)
        assertTrue(layoutRepo.lastUpdatedAt(1, null) != null)
    }

    private fun withApp(
        roles: Set<Role> = setOf(Role.CLUB_ADMIN),
        clubIds: Set<Long> = setOf(1),
        block: suspend ApplicationTestBuilder.(InMemoryAdminClubsRepository, InMemoryAdminHallsRepository, LayoutRepository) -> Unit,
    ) {
        val clubsRepo = InMemoryAdminClubsRepository(clock)
        val hallsRepo = InMemoryAdminHallsRepository(clock)
        val layoutRepo =
            InMemoryLayoutRepository(
                layouts =
                    listOf(
                        InMemoryLayoutRepository.LayoutSeed(
                            clubId = 1,
                            zones = listOf(Zone(id = "vip", name = "VIP", tags = emptyList(), order = 1)),
                            tables = emptyList(),
                            geometryJson = InMemoryLayoutRepository.DEFAULT_GEOMETRY_JSON,
                        ),
                        InMemoryLayoutRepository.LayoutSeed(
                            clubId = 2,
                            zones = listOf(Zone(id = "vip", name = "VIP", tags = emptyList(), order = 1)),
                            tables = emptyList(),
                            geometryJson = InMemoryLayoutRepository.DEFAULT_GEOMETRY_JSON,
                        ),
                    ),
                clock = clock,
            )
        val assets =
            object : LayoutAssetsRepository {
                override suspend fun loadGeometry(clubId: Long, fingerprint: String): String? = null
            }

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(roles, clubIds)
                    auditLogRepository = io.mockk.mockk(relaxed = true)
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }
                adminClubsRoutes(clubsRepo, botTokenProvider = { "test" })
                adminHallsRoutes(hallsRepo, clubsRepo, botTokenProvider = { "test" })
                adminTablesRoutes(layoutRepo, hallsRepo, botTokenProvider = { "test" })
                layoutRoutes(layoutRepo, assets)
            }

            block(this, clubsRepo, hallsRepo, layoutRepo)
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

    private class InMemoryAdminClubsRepository(private val clock: Clock) : AdminClubsRepository {
        private val idSequence = AtomicLong(2)
        private val clubs: MutableList<AdminClub> =
            mutableListOf(
                AdminClub(1, "Club One", "Moscow", true, clock.instant(), clock.instant()),
                AdminClub(2, "Club Two", "Berlin", true, clock.instant(), clock.instant()),
            )

        override suspend fun list(): List<AdminClub> = clubs.toList()

        override suspend fun getById(id: Long): AdminClub? = clubs.firstOrNull { it.id == id }

        override suspend fun create(request: AdminClubCreate): AdminClub {
            val club =
                AdminClub(
                    id = idSequence.incrementAndGet(),
                    name = request.name,
                    city = request.city,
                    isActive = request.isActive,
                    createdAt = clock.instant(),
                    updatedAt = clock.instant(),
                )
            clubs += club
            return club
        }

        override suspend fun update(id: Long, request: AdminClubUpdate): AdminClub? {
            val existing = clubs.firstOrNull { it.id == id } ?: return null
            val updated =
                existing.copy(
                    name = request.name ?: existing.name,
                    city = request.city ?: existing.city,
                    isActive = request.isActive ?: existing.isActive,
                    updatedAt = clock.instant(),
                )
            clubs.replaceAll { if (it.id == id) updated else it }
            return updated
        }

        override suspend fun delete(id: Long): Boolean {
            val existing = clubs.firstOrNull { it.id == id } ?: return false
            clubs.replaceAll { if (it.id == id) it.copy(isActive = false, updatedAt = clock.instant()) else it }
            return existing.isActive
        }
    }

    private class InMemoryAdminHallsRepository(private val clock: Clock) : AdminHallsRepository {
        private val halls: MutableList<AdminHall> =
            mutableListOf(
                AdminHall(1, 1, "Main", true, 1, "fp-1", clock.instant(), clock.instant()),
                AdminHall(2, 1, "Second", false, 1, "fp-2", clock.instant(), clock.instant()),
                AdminHall(3, 2, "Main", true, 1, "fp-3", clock.instant(), clock.instant()),
            )

        override suspend fun listForClub(clubId: Long): List<AdminHall> = halls.filter { it.clubId == clubId }

        override suspend fun getById(id: Long): AdminHall? = halls.firstOrNull { it.id == id }

        override suspend fun findActiveForClub(clubId: Long): AdminHall? = halls.firstOrNull { it.clubId == clubId && it.isActive }

        override suspend fun create(clubId: Long, request: AdminHallCreate): AdminHall {
            val nextId = (halls.maxOfOrNull { it.id } ?: 0L) + 1
            val hall =
                AdminHall(
                    id = nextId,
                    clubId = clubId,
                    name = request.name,
                    isActive = request.isActive,
                    layoutRevision = 1,
                    geometryFingerprint = "fp-$nextId",
                    createdAt = clock.instant(),
                    updatedAt = clock.instant(),
                )
            halls += hall
            return hall
        }

        override suspend fun update(id: Long, request: AdminHallUpdate): AdminHall? {
            val existing = halls.firstOrNull { it.id == id } ?: return null
            val updated =
                existing.copy(
                    name = request.name ?: existing.name,
                    layoutRevision = existing.layoutRevision + 1,
                    updatedAt = clock.instant(),
                )
            halls.replaceAll { if (it.id == id) updated else it }
            return updated
        }

        override suspend fun delete(id: Long): Boolean {
            val existing = halls.firstOrNull { it.id == id } ?: return false
            halls.replaceAll { if (it.id == id) it.copy(isActive = false, layoutRevision = it.layoutRevision + 1) else it }
            if (existing.isActive) {
                halls.firstOrNull { it.clubId == existing.clubId && it.id != id }?.let { other ->
                    halls.replaceAll { if (it.id == other.id) it.copy(isActive = true) else it }
                }
            }
            return true
        }

        override suspend fun makeActive(id: Long): AdminHall? {
            val existing = halls.firstOrNull { it.id == id } ?: return null
            halls.replaceAll {
                if (it.clubId == existing.clubId) {
                    it.copy(isActive = it.id == id, layoutRevision = if (it.id == id) it.layoutRevision + 1 else it.layoutRevision)
                } else {
                    it
                }
            }
            return existing.copy(isActive = true, layoutRevision = existing.layoutRevision + 1)
        }

        override suspend fun isHallNameTaken(clubId: Long, name: String, excludeHallId: Long?): Boolean =
            halls.any { it.clubId == clubId && it.name.equals(name, ignoreCase = true) && it.id != excludeHallId }
    }
}
