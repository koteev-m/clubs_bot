package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.http.ErrorCodes
import com.example.bot.admin.AdminHall
import com.example.bot.admin.AdminHallCreate
import com.example.bot.admin.AdminHallUpdate
import com.example.bot.admin.AdminHallsRepository
import com.example.bot.layout.AdminTableCreate
import com.example.bot.layout.AdminTableUpdate
import com.example.bot.layout.AdminTablesRepository
import com.example.bot.layout.ArrivalWindow
import com.example.bot.layout.ClubLayout
import com.example.bot.layout.LayoutAssets
import com.example.bot.layout.LayoutAssetsRepository
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
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import java.time.LocalTime
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
    private val emptyAssets =
        object : LayoutAssetsRepository {
            override suspend fun loadGeometry(clubId: Long, fingerprint: String): String? = null
        }
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
    fun `get by id returns table`() = withApp() { repo, _ ->
        val created =
            repo.create(
                AdminTableCreate(
                    clubId = 1,
                    label = "Table 1",
                    minDeposit = 1000,
                    capacity = 4,
                    zone = "vip",
                    arrivalWindow = ArrivalWindow(LocalTime.of(22, 0), LocalTime.of(23, 0)),
                    mysteryEligible = true,
                ),
            )

        val response = client.get("/api/admin/tables/${created.id}?clubId=1") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(created.id, payload["id"]!!.jsonPrimitive.long)
        assertEquals(1L, payload["clubId"]!!.jsonPrimitive.long)
        assertEquals("Table 1", payload["label"]!!.jsonPrimitive.content)
        assertEquals(1000, payload["minDeposit"]!!.jsonPrimitive.long)
        assertEquals(4, payload["capacity"]!!.jsonPrimitive.long)
        assertEquals("vip", payload["zone"]!!.jsonPrimitive.content)
        assertEquals("VIP", payload["zoneName"]!!.jsonPrimitive.content)
        assertEquals("22:00-23:00", payload["arrivalWindow"]!!.jsonPrimitive.content)
        assertTrue(payload["mysteryEligible"]!!.jsonPrimitive.boolean)
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
        assertEquals("VIP", createdPayload["zoneName"]!!.jsonPrimitive.content)
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
        assertEquals("VIP", first["zoneName"]!!.jsonPrimitive.content)
        assertTrue(first["mysteryEligible"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `in-memory repo assigns default zone when missing`() = withInMemoryApp { _ ->
        val create =
            client.post("/api/admin/tables?clubId=1") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"label":"Table 1","capacity":4}""")
            }

        assertEquals(HttpStatusCode.Created, create.status)
        val created = json.parseToJsonElement(create.bodyAsText()).jsonObject
        assertEquals("vip", created["zone"]!!.jsonPrimitive.content)
        assertEquals("VIP", created["zoneName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `delete removes table`() = withApp() { repo, _ ->
        val table =
            repo.create(
                AdminTableCreate(
                    clubId = 1,
                    label = "A",
                    minDeposit = 0,
                    capacity = 2,
                    zone = "vip",
                    arrivalWindow = null,
                    mysteryEligible = false,
                ),
            )
        assertEquals(1, repo.listForClub(1).size)

        val response =
            client.delete("/api/admin/tables/${table.id}?clubId=1") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.NoContent, response.status)
        response.assertNoStoreHeaders()
        assertTrue(repo.listForClub(1).isEmpty())
    }

    @Test
    fun `patch returns not found for deleted hall table`() = withApp() { _, _ ->
        val create =
            client.post("/api/admin/halls/1/tables") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "label":"To delete",
                    "capacity":4
                }""",
                )
            }

        assertEquals(HttpStatusCode.Created, create.status)
        val createdPayload = json.parseToJsonElement(create.bodyAsText()).jsonObject
        val tableId = createdPayload["id"]!!.jsonPrimitive.long

        val deleted = client.delete("/api/admin/halls/1/tables/$tableId") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.NoContent, deleted.status)

        val patch =
            client.patch("/api/admin/halls/1/tables/$tableId") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"label":"Updated"}""")
            }

        assertEquals(HttpStatusCode.NotFound, patch.status)
        assertEquals(ErrorCodes.table_not_found, patch.errorCode())
    }

    @Test
    fun `delete invalid id`() = withApp() { _, _ ->
        val nonNumeric = client.delete("/api/admin/tables/foo?clubId=1") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.BadRequest, nonNumeric.status)
        assertEquals("must_be_positive", nonNumeric.bodyAsText().jsonError("id"))

        val zero = client.delete("/api/admin/tables/0?clubId=1") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.BadRequest, zero.status)
        assertEquals("must_be_positive", zero.bodyAsText().jsonError("id"))
    }

    @Test
    fun `delete not found`() = withApp() { _, _ ->
        val response = client.delete("/api/admin/tables/999?clubId=1") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `delete respects rbac`() {
        withApp(roles = emptySet()) { _, _ ->
            val response = client.delete("/api/admin/tables/1?clubId=1") { header("X-Telegram-Init-Data", "init") }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

        withApp(clubIds = setOf(1)) { _, _ ->
            val response = client.delete("/api/admin/tables/1?clubId=2") { header("X-Telegram-Init-Data", "init") }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `get tables supports pagination`() = withApp() { repo, _ ->
        repeat(5) { idx ->
            repo.create(
                AdminTableCreate(
                    clubId = 1,
                    label = "T${idx + 1}",
                    minDeposit = 0,
                    capacity = 2,
                    zone = "vip",
                    arrivalWindow = null,
                    mysteryEligible = false,
                ),
            )
        }

        val page0 = client.get("/api/admin/tables?clubId=1&page=0&size=2") { header("X-Telegram-Init-Data", "init") }
        val page1 = client.get("/api/admin/tables?clubId=1&page=1&size=2") { header("X-Telegram-Init-Data", "init") }

        val items0 = json.parseToJsonElement(page0.bodyAsText()).jsonArray
        val items1 = json.parseToJsonElement(page1.bodyAsText()).jsonArray

        assertEquals(2, items0.size)
        assertEquals(2, items1.size)
        assertNotEquals(items0.first().jsonObject["id"], items1.first().jsonObject["id"])
    }

    @Test
    fun `get tables invalid page and size`() = withApp() { _, _ ->
        val negativePage = client.get("/api/admin/tables?clubId=1&page=-1") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.BadRequest, negativePage.status)
        assertEquals("must_be_non_negative", negativePage.bodyAsText().jsonError("page"))

        val zeroSize = client.get("/api/admin/tables?clubId=1&size=0") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.BadRequest, zeroSize.status)
        assertEquals("must_be_between_1_200", zeroSize.bodyAsText().jsonError("size"))

        val hugeSize = client.get("/api/admin/tables?clubId=1&size=1000") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.BadRequest, hugeSize.status)
        assertEquals("must_be_between_1_200", hugeSize.bodyAsText().jsonError("size"))
    }

    @Test
    fun `get tables with large page returns empty list`() = withApp() { repo, _ ->
        repo.create(
            AdminTableCreate(
                clubId = 1,
                label = "A",
                minDeposit = 0,
                capacity = 2,
                zone = "vip",
                arrivalWindow = null,
                mysteryEligible = false,
            ),
        )

        val response = client.get("/api/admin/tables?clubId=1&page=10&size=5") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(json.parseToJsonElement(response.bodyAsText()).jsonArray.isEmpty())
    }

    @Test
    fun `layout etag changes after delete`() = withInMemoryApp(roles = setOf(Role.GLOBAL_ADMIN)) { repo ->
        val table =
            repo.create(
                AdminTableCreate(
                    clubId = 1,
                    label = "A",
                    minDeposit = 0,
                    capacity = 2,
                    zone = "vip",
                    arrivalWindow = null,
                    mysteryEligible = false,
                ),
            )

        val initialLayout = client.get("/api/clubs/1/layout") { header("X-Telegram-Init-Data", "init") }
        val initialEtag = initialLayout.headers[HttpHeaders.ETag]

        client.delete("/api/admin/tables/${table.id}?clubId=1") { header("X-Telegram-Init-Data", "init") }

        val afterLayout = client.get("/api/clubs/1/layout") { header("X-Telegram-Init-Data", "init") }
        val afterEtag = afterLayout.headers[HttpHeaders.ETag]

        assertNotEquals(initialEtag, afterEtag)
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
    fun `get by id not found`() = withApp() { _, _ ->
        val response = client.get("/api/admin/tables/999?clubId=1") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(ErrorCodes.not_found, response.errorCode())
    }

    @Test
    fun `get by id invalid id`() = withApp() { _, _ ->
        val nonNumeric = client.get("/api/admin/tables/foo?clubId=1") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.BadRequest, nonNumeric.status)
        assertEquals("must_be_positive", nonNumeric.bodyAsText().jsonError("id"))

        val zero = client.get("/api/admin/tables/0?clubId=1") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.BadRequest, zero.status)
        assertEquals("must_be_positive", zero.bodyAsText().jsonError("id"))
    }

    @Test
    fun `get by id respects rbac`() {
        withApp(roles = emptySet()) { _, _ ->
            val response = client.get("/api/admin/tables/1?clubId=1") { header("X-Telegram-Init-Data", "init") }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

        withApp(clubIds = setOf(1)) { _, _ ->
            val response = client.get("/api/admin/tables/1?clubId=2") { header("X-Telegram-Init-Data", "init") }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `get by id includes zone name consistent with list`() = withApp() { repo, _ ->
        val created =
            repo.create(
                AdminTableCreate(
                    clubId = 1,
                    label = "VIP table",
                    minDeposit = 0,
                    capacity = 2,
                    zone = "vip",
                    arrivalWindow = null,
                    mysteryEligible = false,
                ),
            )

        val list = client.get("/api/admin/tables?clubId=1") { header("X-Telegram-Init-Data", "init") }
        val listFirst = json.parseToJsonElement(list.bodyAsText()).jsonArray.first().jsonObject

        val single = client.get("/api/admin/tables/${created.id}?clubId=1") { header("X-Telegram-Init-Data", "init") }
        val payload = json.parseToJsonElement(single.bodyAsText()).jsonObject

        assertEquals(listFirst["zone"]!!.jsonPrimitive.content, payload["zone"]!!.jsonPrimitive.content)
        assertEquals(listFirst["zoneName"]!!.jsonPrimitive.content, payload["zoneName"]!!.jsonPrimitive.content)
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
        val hallsRepository = FakeHallsRepository(clock)
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(roles, clubIds)
                    auditLogRepository = io.mockk.mockk(relaxed = true)
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }
                adminTablesRoutes(repo, hallsRepository, botTokenProvider = { "test" })
                layoutRoutes(repo, emptyAssets)
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
        val hallsRepository = FakeHallsRepository(clock)
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(roles, clubIds)
                    auditLogRepository = io.mockk.mockk(relaxed = true)
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }
                adminTablesRoutes(repo, hallsRepository, botTokenProvider = { "test" })
                layoutRoutes(repo, emptyAssets)
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

        override suspend fun getById(id: Long): User? = User(id = id, telegramId = id, username = "tester")
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

        override suspend fun findById(clubId: Long, id: Long): Table? =
            tablesByClub[clubId]?.firstOrNull { it.id == id }

        override suspend fun create(request: AdminTableCreate): Table {
            val clubId = request.clubId
            val list = tablesByClub.getOrPut(clubId) { mutableListOf() }
            zonesByClub.putIfAbsent(clubId, listOf(Zone(id = "main", name = "Main", tags = emptyList(), order = 1)))
            val nextId = (list.maxOfOrNull { it.id } ?: 0L) + 1
            val nextNumber = request.tableNumber ?: ((list.maxOfOrNull { it.tableNumber } ?: 0) + 1)
            val defaultZoneId = request.zone ?: zonesByClub[clubId]?.firstOrNull()?.id ?: "main"
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
                    tableNumber = nextNumber,
                    x = request.x ?: 0.5,
                    y = request.y ?: 0.5,
                )
            list += table
            touch()
            return table
        }

        override suspend fun update(request: AdminTableUpdate): Table? {
            val clubId = request.clubId
            val list = tablesByClub[clubId] ?: return null
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
                    tableNumber = request.tableNumber ?: existing.tableNumber,
                    x = request.x ?: existing.x,
                    y = request.y ?: existing.y,
                )
            list.replaceAll { if (it.id == request.id) updated else it }
            touch()
            return updated
        }

        override suspend fun delete(clubId: Long, id: Long): Boolean {
            val list = tablesByClub[clubId] ?: return false
            val removed = list.removeIf { it.id == id }
            if (!removed) return false
            touch()
            return true
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

        override suspend fun listForHall(hallId: Long): List<Table> = listForClub(hallId)

        override suspend fun listZonesForHall(hallId: Long): List<Zone> = listZonesForClub(hallId)

        override suspend fun findByIdForHall(hallId: Long, id: Long): Table? = findById(hallId, id)

        override suspend fun createForHall(request: AdminTableCreate): Table = create(request)

        override suspend fun updateForHall(request: AdminTableUpdate): Table? = update(request)

        override suspend fun deleteForHall(hallId: Long, id: Long): Boolean = delete(hallId, id)

        override suspend fun lastUpdatedAtForHall(hallId: Long): Instant? = updatedAt

        override suspend fun isTableNumberTaken(hallId: Long, tableNumber: Int, excludeTableId: Long?): Boolean {
            val tables = listForHall(hallId)
            return tables.any { it.tableNumber == tableNumber && (excludeTableId == null || it.id != excludeTableId) }
        }
    }

    private class FakeHallsRepository(private val clock: Clock) : AdminHallsRepository {
        private val halls: MutableList<AdminHall> =
            mutableListOf(
                AdminHall(
                    id = 1,
                    clubId = 1,
                    name = "Main",
                    isActive = true,
                    layoutRevision = 1,
                    geometryFingerprint = "fp",
                    createdAt = clock.instant(),
                    updatedAt = clock.instant(),
                ),
            )

        override suspend fun listForClub(clubId: Long): List<AdminHall> = halls.filter { it.clubId == clubId }

        override suspend fun getById(id: Long): AdminHall? = halls.firstOrNull { it.id == id }

        override suspend fun findActiveForClub(clubId: Long): AdminHall? =
            halls.firstOrNull { it.clubId == clubId && it.isActive }
                ?: createHall(clubId, AdminHallCreate(name = "Auto $clubId", geometryJson = "{}", isActive = true), clubId)

        override suspend fun create(clubId: Long, request: AdminHallCreate): AdminHall {
            return createHall(clubId, request, null)
        }

        private fun createHall(clubId: Long, request: AdminHallCreate, idOverride: Long?): AdminHall {
            val nextId = idOverride ?: (halls.maxOfOrNull { it.id } ?: 0L) + 1
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
                halls.firstOrNull { it.id != id }?.let { other ->
                    halls.replaceAll { if (it.id == other.id) it.copy(isActive = true) else it }
                }
            }
            return true
        }

        override suspend fun makeActive(id: Long): AdminHall? {
            val existing = halls.firstOrNull { it.id == id } ?: return null
            halls.replaceAll { it.copy(isActive = it.id == id, layoutRevision = if (it.id == id) it.layoutRevision + 1 else it.layoutRevision) }
            return existing.copy(isActive = true, layoutRevision = existing.layoutRevision + 1)
        }

        override suspend fun isHallNameTaken(clubId: Long, name: String, excludeHallId: Long?): Boolean =
            halls.any { it.clubId == clubId && it.name.equals(name, ignoreCase = true) && it.id != excludeHallId }
    }
}
