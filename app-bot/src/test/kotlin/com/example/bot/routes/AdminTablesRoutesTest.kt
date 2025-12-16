package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.http.ErrorCodes
import com.example.bot.layout.AdminTableCreate
import com.example.bot.layout.AdminTableUpdate
import com.example.bot.layout.AdminTablesRepository
import com.example.bot.layout.ArrivalWindow
import com.example.bot.layout.ClubLayout
import com.example.bot.layout.LayoutAssets
import com.example.bot.layout.LayoutRepository
import com.example.bot.layout.InMemoryLayoutRepository
import com.example.bot.layout.Table
import com.example.bot.layout.TableStatus
import com.example.bot.layout.toRangeString
import com.example.bot.layout.Zone
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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

class AdminTablesRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val telegramId = 123L
    private val clock: Clock = Clock.fixed(Instant.parse("2024-06-01T10:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = telegramId) }
    }

    @After
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `get tables returns empty list for club`() = withApp() { _, _ ->
        val response = client.get("/api/admin/tables?clubId=1") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, json.parseToJsonElement(response.bodyAsText()).jsonArray.size)
        response.assertNoStoreHeaders()
    }

    @Test
    fun `post creates table and get returns it`() = withApp() { repo, _ ->
        val create =
            client.post("/api/admin/tables?clubId=1") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "label":"Table 1",
                    "capacity":4,
                    "minDeposit":1000,
                    "zone":"vip",
                    "arrivalWindow":"22:00-23:00",
                    "mysteryEligible":true
                }""",
                )
            }

        assertEquals(HttpStatusCode.Created, create.status)
        val createdPayload = json.parseToJsonElement(create.bodyAsText()).jsonObject
        assertEquals(1L, createdPayload["clubId"]!!.jsonPrimitive.long)
        assertEquals(4, createdPayload["capacity"]!!.jsonPrimitive.long)
        assertEquals(1000, createdPayload["minDeposit"]!!.jsonPrimitive.long)
        assertEquals("vip", createdPayload["zone"]!!.jsonPrimitive.content)
        assertEquals("22:00-23:00", createdPayload["arrivalWindow"]!!.jsonPrimitive.content)
        assertTrue(createdPayload["mysteryEligible"]!!.jsonPrimitive.boolean)
        assertTrue(repo.listForClub(1).isNotEmpty())
        create.assertNoStoreHeaders()

        val list = client.get("/api/admin/tables?clubId=1") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.OK, list.status)
        val items = json.parseToJsonElement(list.bodyAsText()).jsonArray
        assertEquals(1, items.size)
        val first = items.first().jsonObject
        assertEquals("22:00-23:00", first["arrivalWindow"]!!.jsonPrimitive.content)
        assertEquals(1000, first["minDeposit"]!!.jsonPrimitive.long)
        assertEquals("vip", first["zone"]!!.jsonPrimitive.content)
        assertTrue(first["mysteryEligible"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `put updates table`() = withApp() { repo, _ ->
        val existing =
            repo.create(
                AdminTableCreate(
                    clubId = 1,
                    label = "T1",
                    minDeposit = 0,
                    capacity = 4,
                    zone = "main",
                    arrivalWindow = null,
                    mysteryEligible = false,
                ),
            )

        val response =
            client.put("/api/admin/tables?clubId=1") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "id":${existing.id},
                    "label":"Updated",
                    "capacity":6,
                    "minDeposit":2000,
                    "arrivalWindow":"20:00-21:00",
                    "mysteryEligible":true
                }""",
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val updated = repo.listForClub(1).first()
        assertEquals("Updated", updated.label)
        assertEquals(6, updated.capacity)
        assertEquals(2000, updated.minDeposit)
        assertEquals("20:00-21:00", updated.arrivalWindow?.toRangeString())
        response.assertNoStoreHeaders()
    }

    @Test
    fun `rbac forbids when role missing`() = withApp(roles = emptySet()) { _, _ ->
        val response = client.get("/api/admin/tables?clubId=1") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains(ErrorCodes.forbidden), "expected forbidden in body: $body")
        response.assertNoStoreHeaders()
    }

    @Test
    fun `forbids when club is foreign for club admin`() = withApp(clubIds = setOf(1)) { _, _ ->
        val response = client.get("/api/admin/tables?clubId=2") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        response.assertNoStoreHeaders()
    }

    @Test
    fun `global admin can manage any club`() = withApp(roles = setOf(Role.GLOBAL_ADMIN)) { _, _ ->
        val response =
            client.post("/api/admin/tables?clubId=5") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"label":"X","capacity":2}""")
            }

        assertEquals(HttpStatusCode.Created, response.status)
        val list = client.get("/api/admin/tables?clubId=5") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.OK, list.status)
        val items = json.parseToJsonElement(list.bodyAsText()).jsonArray
        assertEquals(1, items.size)
    }

    @Test
    fun `validation errors are returned`() = withApp() { _, _ ->
        val invalidJson = client.post("/api/admin/tables?clubId=1") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.BadRequest, invalidJson.status)
        assertEquals(ErrorCodes.invalid_json, invalidJson.errorCode())

        val invalidClub = client.get("/api/admin/tables?clubId=0") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.BadRequest, invalidClub.status)
        assertEquals(ErrorCodes.validation_error, invalidClub.errorCode())

        val invalidArrival =
            client.post("/api/admin/tables?clubId=1") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"label":"","capacity":0,"arrivalWindow":"25:00-24:00"}""")
            }
        assertEquals(HttpStatusCode.BadRequest, invalidArrival.status)
        assertEquals(ErrorCodes.validation_error, invalidArrival.errorCode())
    }

    @Test
    fun `zone must exist in layout`() = withApp() { _, _ ->
        val response =
            client.post("/api/admin/tables?clubId=1") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "label":"Table 1",
                    "capacity":4,
                    "minDeposit":1000,
                    "zone":"unknown",
                    "arrivalWindow":"22:00-23:00",
                    "mysteryEligible":true
                }""",
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("unknown_zone", response.bodyAsText().jsonError("zone"))
    }

    @Test
    fun `zone length error wins over unknown zone`() = withApp() { _, _ ->
        val tooLongZone = "z".repeat(60)
        val response =
            client.post("/api/admin/tables?clubId=1") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "label":"Table 1",
                    "capacity":4,
                    "minDeposit":1000,
                    "zone":"$tooLongZone",
                    "arrivalWindow":"22:00-23:00",
                    "mysteryEligible":true
                }""",
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("length_1_50", response.bodyAsText().jsonError("zone"))
    }

    @Test
    fun `in-memory repo assigns default zone when missing`() = withInMemoryApp { repo ->
        val create =
            client.post("/api/admin/tables?clubId=1") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"label":"NoZone","capacity":2}""")
            }

        assertEquals(HttpStatusCode.Created, create.status)
        val created = json.parseToJsonElement(create.bodyAsText()).jsonObject
        assertEquals("vip", created["zone"]!!.jsonPrimitive.content)

        val layout = client.get("/api/clubs/1/layout") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.OK, layout.status)
        val table = json.parseToJsonElement(layout.bodyAsText()).jsonObject["tables"]!!.jsonArray.first().jsonObject
        assertEquals("vip", table["zone"]!!.jsonPrimitive.content)
    }

    @Test
    fun `layout reflects admin tables and etag updates`() = withApp() { _, _ ->
        val initial = client.get("/api/clubs/1/layout?eventId=100") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.OK, initial.status)
        val etagBefore = initial.headers[HttpHeaders.ETag]
        val initialTables = json.parseToJsonElement(initial.bodyAsText()).jsonObject["tables"]!!.jsonArray
        assertEquals(0, initialTables.size)

        val create =
            client.post("/api/admin/tables?clubId=1") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "label":"New",
                    "capacity":4,
                    "minDeposit":1000,
                    "arrivalWindow":"22:00-23:00",
                    "mysteryEligible":true
                }""",
                )
            }

        assertEquals(HttpStatusCode.Created, create.status)

        val after = client.get("/api/clubs/1/layout?eventId=100") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.OK, after.status)
        val etagAfter = after.headers[HttpHeaders.ETag]
        assertNotEquals(etagBefore, etagAfter)
        val tablesAfter = json.parseToJsonElement(after.bodyAsText()).jsonObject["tables"]!!.jsonArray
        assertEquals(1, tablesAfter.size)
        val table = tablesAfter.first().jsonObject
        assertEquals("New", table["label"]!!.jsonPrimitive.content)
        assertEquals(1000, table["minDeposit"]!!.jsonPrimitive.long)
        assertEquals("22:00-23:00", table["arrivalWindow"]!!.jsonPrimitive.content)
        assertTrue(table["mysteryEligible"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `no-store headers are set`() = withApp() { repo, _ ->
        repo.create(
            AdminTableCreate(
                clubId = 1,
                label = "A",
                minDeposit = 0,
                capacity = 2,
                zone = null,
                arrivalWindow = null,
                mysteryEligible = false,
            ),
        )

        val response = client.get("/api/admin/tables?clubId=1") { header("X-Telegram-Init-Data", "init") }
        response.assertNoStoreHeaders()
    }

    private fun withApp(
        roles: Set<Role> = setOf(Role.CLUB_ADMIN),
        clubIds: Set<Long> = setOf(1),
        block: suspend ApplicationTestBuilder.(FakeTablesRepository, LayoutRepository) -> Unit,
    ) {
        val repo = FakeTablesRepository(clock)
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(roles, clubIds)
                    auditLogRepository = io.mockk.mockk(relaxed = true)
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }
                adminTablesRoutes(repo, botTokenProvider = { "test" })
                layoutRoutes(repo)
            }

            block(this, repo, repo)
        }
    }

    private fun withInMemoryApp(
        roles: Set<Role> = setOf(Role.CLUB_ADMIN),
        clubIds: Set<Long> = setOf(1),
        block: suspend ApplicationTestBuilder.(InMemoryLayoutRepository) -> Unit,
    ) {
        val repo =
            InMemoryLayoutRepository(
                layouts =
                    listOf(
                        InMemoryLayoutRepository.LayoutSeed(
                            clubId = 1,
                            zones = listOf(Zone(id = "vip", name = "VIP", tags = emptyList(), order = 1)),
                            tables = emptyList(),
                            geometryJson = InMemoryLayoutRepository.DEFAULT_GEOMETRY_JSON,
                        ),
                    ),
                clock = clock,
            )
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(roles, clubIds)
                    auditLogRepository = io.mockk.mockk(relaxed = true)
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }
                adminTablesRoutes(repo, botTokenProvider = { "test" })
                layoutRoutes(repo)
            }

            block(this, repo)
        }
    }

    private fun JsonObject.errorCodeOrNull(): String? =
        this["code"]?.jsonPrimitive?.content
            ?: this["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
            ?: this["error"]?.jsonPrimitive?.content

    private fun String.jsonError(field: String): String? =
        runCatching {
            val node = json.parseToJsonElement(this).jsonObject
            val errorNode = node["error"]?.jsonObject ?: node
            errorNode["details"]?.jsonObject?.get(field)?.jsonPrimitive?.content
        }
            .getOrNull()

    private suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String =
        runCatching { Json.parseToJsonElement(bodyAsText()).jsonObject.errorCodeOrNull() }.getOrNull().orEmpty()

    private fun io.ktor.client.statement.HttpResponse.assertNoStoreHeaders() {
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        assertEquals("X-Telegram-Init-Data", headers[HttpHeaders.Vary])
    }

    private class StubUserRepository : UserRepository {
        override suspend fun getByTelegramId(id: Long): User? = User(id = 1, telegramId = id, username = "tester")
    }

    private class StubUserRoleRepository(
        private val roles: Set<Role>,
        private val clubs: Set<Long>,
    ) : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = roles

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = clubs
    }

    private class FakeTablesRepository(private val clock: Clock) : AdminTablesRepository, LayoutRepository {
        private val tablesByClub: MutableMap<Long, MutableList<Table>> = mutableMapOf()
        private val zonesByClub: MutableMap<Long, List<Zone>> =
            mutableMapOf(1L to listOf(Zone(id = "vip", name = "VIP", tags = emptyList(), order = 1)))
        private var updatedAt: Instant? = clock.instant()
        private var touchCounter: Long = 0

        override suspend fun listForClub(clubId: Long): List<Table> = tablesByClub[clubId]?.sortedBy { it.id } ?: emptyList()

        override suspend fun listZonesForClub(clubId: Long): List<Zone> = zonesByClub[clubId] ?: emptyList()

        override suspend fun create(request: AdminTableCreate): Table {
            val list = tablesByClub.getOrPut(request.clubId) { mutableListOf() }
            zonesByClub.putIfAbsent(request.clubId, listOf(Zone(id = "main", name = "Main", tags = emptyList(), order = 1)))
            val nextId = (list.maxOfOrNull { it.id } ?: 0L) + 1
            val defaultZoneId = request.zone ?: zonesByClub[request.clubId]?.firstOrNull()?.id ?: "main"
            val table =
                Table(
                    id = nextId,
                    zoneId = defaultZoneId,
                    label = request.label,
                    capacity = request.capacity,
                    minimumTier = "standard",
                    status = TableStatus.FREE,
                    minDeposit = request.minDeposit,
                    zone = request.zone ?: defaultZoneId,
                    arrivalWindow = request.arrivalWindow,
                    mysteryEligible = request.mysteryEligible,
                )
            list += table
            touch()
            return table
        }

        override suspend fun update(request: AdminTableUpdate): Table? {
            val list = tablesByClub[request.clubId] ?: return null
            val existing = list.firstOrNull { it.id == request.id } ?: return null
            val updated =
                existing.copy(
                    label = request.label ?: existing.label,
                    capacity = request.capacity ?: existing.capacity,
                    minDeposit = request.minDeposit ?: existing.minDeposit,
                    zone = request.zone ?: existing.zone ?: existing.zoneId,
                    zoneId = request.zone ?: existing.zoneId,
                    arrivalWindow = request.arrivalWindow ?: existing.arrivalWindow,
                    mysteryEligible = request.mysteryEligible ?: existing.mysteryEligible,
                )
            list.replaceAll { if (it.id == request.id) updated else it }
            touch()
            return updated
        }

        override suspend fun lastUpdatedAt(clubId: Long): Instant? = updatedAt

        override suspend fun lastUpdatedAt(clubId: Long, eventId: Long?): Instant? = updatedAt

        override suspend fun getLayout(clubId: Long, eventId: Long?): ClubLayout? {
            val tables = listForClub(clubId)
            val zones = zonesByClub[clubId] ?: tables.map { it.zoneId }.distinct().mapIndexed { idx, id -> Zone(id, id, emptyList(), idx + 1) }
            return ClubLayout(
                clubId = clubId,
                eventId = eventId,
                zones = zones,
                tables = tables,
                assets = LayoutAssets(geometryUrl = "/assets/layouts/$clubId/default.json", fingerprint = "fp-$clubId"),
            )
        }

        private fun touch() {
            touchCounter += 1
            updatedAt = clock.instant().plusMillis(touchCounter)
        }
    }
}
