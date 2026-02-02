package com.example.bot.routes

import com.example.bot.audit.AuditLogger
import com.example.bot.data.booking.TableDeposit
import com.example.bot.data.booking.TableDepositAllocation
import com.example.bot.data.booking.TableDepositRepository
import com.example.bot.data.booking.TableSession
import com.example.bot.data.booking.TableSessionRepository
import com.example.bot.data.booking.TableSessionStatus
import com.example.bot.data.gamification.GamificationSettingsRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.data.visits.NightOverrideRepository
import com.example.bot.data.visits.VisitCheckInResult
import com.example.bot.data.visits.VisitRepository
import com.example.bot.http.ErrorCodes
import com.example.bot.layout.AdminTablesRepository
import com.example.bot.layout.Table
import com.example.bot.layout.TableStatus
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.tables.GuestQrResolveResult
import com.example.bot.tables.GuestQrResolver
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
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Before
import org.junit.Test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.assertEquals

class AdminTableOpsRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val telegramId = 777L

    @Before
    fun setUp() {
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = telegramId) }
    }

    @After
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `seat table with qr creates session and deposit and marks hasTable`() = withApp { deps ->
        coEvery { deps.guestQrResolver.resolveGuest(1, any(), any()) } returns
            GuestQrResolveResult.Success(guestUserId = 42, eventId = 999, listId = 1, entryId = 2)
        coEvery { deps.tableSessionRepository.findActiveSession(any(), any(), any()) } returns null
        coEvery { deps.tableSessionRepository.openSession(any(), any(), any(), any(), any(), any()) } returns
            tableSession(id = 55, tableId = 10)
        coEvery {
            deps.tableDepositRepository.createDeposit(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns
            tableDeposit(id = 77, sessionId = 55, tableId = 10, guestUserId = 42)
        coEvery { deps.visitRepository.tryCheckIn(any()) } returns visitResult()
        coEvery { deps.visitRepository.markHasTable(any(), any(), any(), any()) } returns true

        val response =
            client.post("/api/admin/clubs/1/nights/2024-06-01T20:00:00Z/tables/10/seat") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "mode":"WITH_QR",
                    "guestPassQr":"GL:1:2:10:deadbeef",
                    "depositAmount":1000,
                    "allocations":[{"categoryCode":"BAR","amount":1000}]
                }""",
                )
            }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(55, body["sessionId"]!!.jsonPrimitive.long)
        assertEquals(77, body["depositId"]!!.jsonPrimitive.long)
        response.assertNoStoreHeaders()
        coVerify(exactly = 1) { deps.visitRepository.tryCheckIn(any()) }
        coVerify(exactly = 1) { deps.visitRepository.markHasTable(1, any(), 42, true) }
    }

    @Test
    fun `seat table without qr skips visit updates`() = withApp { deps ->
        coEvery { deps.tableSessionRepository.findActiveSession(any(), any(), any()) } returns null
        coEvery { deps.tableSessionRepository.openSession(any(), any(), any(), any(), any(), any()) } returns
            tableSession(id = 100, tableId = 10)
        coEvery {
            deps.tableDepositRepository.createDeposit(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns
            tableDeposit(id = 200, sessionId = 100, tableId = 10, guestUserId = null)

        val response =
            client.post("/api/admin/clubs/1/nights/2024-06-01T20:00:00Z/tables/10/seat") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "mode":"NO_QR",
                    "depositAmount":0,
                    "allocations":[]
                }""",
                )
            }

        assertEquals(HttpStatusCode.Created, response.status)
        response.assertNoStoreHeaders()
        coVerify(exactly = 0) { deps.visitRepository.tryCheckIn(any()) }
        coVerify(exactly = 0) { deps.visitRepository.markHasTable(any(), any(), any(), any()) }
    }

    @Test
    fun `seat table forbidden for non admin role`() = withApp(roles = setOf(Role.PROMOTER)) { _ ->
        val response =
            client.post("/api/admin/clubs/1/nights/2024-06-01T20:00:00Z/tables/10/seat") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "mode":"NO_QR",
                    "depositAmount":0,
                    "allocations":[]
                }""",
                )
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        response.assertNoStoreHeaders()
    }

    @Test
    fun `seat table fails when allocations mismatch`() = withApp { _ ->
        val response =
            client.post("/api/admin/clubs/1/nights/2024-06-01T20:00:00Z/tables/10/seat") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "mode":"NO_QR",
                    "depositAmount":1000,
                    "allocations":[{"categoryCode":"BAR","amount":500}]
                }""",
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCodes.validation_error, response.errorCode())
        response.assertNoStoreHeaders()
    }

    @Test
    fun `seat when already occupied returns existing and does not create new deposit or visits`() = withApp { deps ->
        val session = tableSession(id = 55, tableId = 10)
        val deposit = tableDeposit(id = 77, sessionId = 55, tableId = 10, guestUserId = 42)
        coEvery { deps.guestQrResolver.resolveGuest(1, any(), any()) } returns
            GuestQrResolveResult.Success(guestUserId = 42, eventId = 999, listId = 1, entryId = 2)
        coEvery { deps.tableSessionRepository.findActiveSession(1, any(), 10) } returns session
        coEvery { deps.tableDepositRepository.listDepositsForSession(1, 55) } returns listOf(deposit)

        val response =
            client.post("/api/admin/clubs/1/nights/2024-06-01T20:00:00Z/tables/10/seat") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "mode":"WITH_QR",
                    "guestPassQr":"GL:1:2:10:deadbeef",
                    "depositAmount":1000,
                    "allocations":[{"categoryCode":"BAR","amount":1000}]
                }""",
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(55, body["sessionId"]!!.jsonPrimitive.long)
        assertEquals(77, body["depositId"]!!.jsonPrimitive.long)
        response.assertNoStoreHeaders()
        coVerify(exactly = 0) { deps.tableSessionRepository.openSession(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            deps.tableDepositRepository.createDeposit(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
        coVerify(exactly = 0) { deps.visitRepository.tryCheckIn(any()) }
        coVerify(exactly = 0) { deps.visitRepository.markHasTable(any(), any(), any(), any()) }
    }

    @Test
    fun `seat with qr rejects guest user mismatch`() = withApp { deps ->
        coEvery { deps.guestQrResolver.resolveGuest(1, any(), any()) } returns
            GuestQrResolveResult.Success(guestUserId = 42, eventId = 999, listId = 1, entryId = 2)

        val response =
            client.post("/api/admin/clubs/1/nights/2024-06-01T20:00:00Z/tables/10/seat") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "mode":"WITH_QR",
                    "guestPassQr":"GL:1:2:10:deadbeef",
                    "guestUserId":777,
                    "depositAmount":1000,
                    "allocations":[{"categoryCode":"BAR","amount":1000}]
                }""",
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCodes.validation_error, response.errorCode())
        response.assertNoStoreHeaders()
    }

    @Test
    fun `update deposit rejects too long reason`() = withApp { _ ->
        val response =
            client.put("/api/admin/clubs/1/nights/2024-06-01T20:00:00Z/deposits/10") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "amount":1000,
                    "allocations":[{"categoryCode":"BAR","amount":1000}],
                    "reason":"${"a".repeat(501)}"
                }""",
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCodes.validation_error, response.errorCode())
        response.assertNoStoreHeaders()
    }

    private fun withApp(
        roles: Set<Role> = setOf(Role.MANAGER),
        clubIds: Set<Long> = setOf(1),
        block: suspend ApplicationTestBuilder.(Deps) -> Unit,
    ) {
        val deps = buildDeps()
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(roles, clubIds)
                    auditLogRepository = mockk(relaxed = true)
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }
                adminTableOpsRoutes(
                    adminTablesRepository = deps.adminTablesRepository,
                    tableSessionRepository = deps.tableSessionRepository,
                    tableDepositRepository = deps.tableDepositRepository,
                    visitRepository = deps.visitRepository,
                    nightOverrideRepository = deps.nightOverrideRepository,
                    gamificationSettingsRepository = deps.gamificationSettingsRepository,
                    auditLogger = deps.auditLogger,
                    guestQrResolver = deps.guestQrResolver,
                    clock = java.time.Clock.fixed(Instant.parse("2024-06-01T20:00:00Z"), ZoneOffset.UTC),
                    botTokenProvider = { "test" },
                )
            }

            block(this, deps)
        }
    }

    private fun buildDeps(): Deps {
        val table =
            Table(
                id = 10,
                zoneId = "main",
                label = "Table 10",
                capacity = 4,
                minimumTier = "standard",
                status = TableStatus.FREE,
                tableNumber = 10,
            )
        val adminTablesRepository =
            mockk<AdminTablesRepository>().apply {
                coEvery { listForClub(1) } returns listOf(table)
                coEvery { findById(1, 10) } returns table
            }
        return Deps(
            adminTablesRepository = adminTablesRepository,
            tableSessionRepository = mockk(),
            tableDepositRepository = mockk(),
            visitRepository = mockk(relaxed = true),
            nightOverrideRepository = mockk(relaxed = true),
            gamificationSettingsRepository = mockk(relaxed = true),
            auditLogger = mockk(relaxed = true),
            guestQrResolver = mockk(),
        )
    }

    private fun tableSession(
        id: Long,
        tableId: Long,
    ): TableSession =
        TableSession(
            id = id,
            clubId = 1,
            nightStartUtc = Instant.parse("2024-06-01T20:00:00Z"),
            tableId = tableId,
            status = TableSessionStatus.OPEN,
            openedAt = Instant.parse("2024-06-01T20:00:00Z"),
            closedAt = null,
            openedBy = 1,
            closedBy = null,
            note = null,
        )

    private fun tableDeposit(
        id: Long,
        sessionId: Long,
        tableId: Long,
        guestUserId: Long?,
    ): TableDeposit =
        TableDeposit(
            id = id,
            clubId = 1,
            nightStartUtc = Instant.parse("2024-06-01T20:00:00Z"),
            tableId = tableId,
            tableSessionId = sessionId,
            paymentId = UUID.randomUUID(),
            bookingId = null,
            guestUserId = guestUserId,
            amountMinor = 1000,
            createdAt = Instant.parse("2024-06-01T20:00:00Z"),
            createdBy = 1,
            updatedAt = Instant.parse("2024-06-01T20:00:00Z"),
            updatedBy = 1,
            updateReason = null,
            allocations = listOf(TableDepositAllocation(depositId = id, categoryCode = "BAR", amountMinor = 1000)),
        )

    private fun visitResult(): VisitCheckInResult =
        VisitCheckInResult(
            created = true,
            visit =
                com.example.bot.data.visits.ClubVisit(
                    id = 1,
                    clubId = 1,
                    nightStartUtc = Instant.parse("2024-06-01T20:00:00Z"),
                    eventId = null,
                    userId = 42,
                    firstCheckinAt = Instant.parse("2024-06-01T20:00:00Z"),
                    actorUserId = 1,
                    actorRole = Role.MANAGER,
                    entryType = "TABLE_DEPOSIT",
                    isEarly = false,
                    hasTable = false,
                    createdAt = Instant.parse("2024-06-01T20:00:00Z"),
                    updatedAt = Instant.parse("2024-06-01T20:00:00Z"),
                ),
        )

    private suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String =
        runCatching {
            val node = json.parseToJsonElement(bodyAsText()).jsonObject
            node["code"]?.jsonPrimitive?.content
                ?: node["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
                ?: node["error"]?.jsonPrimitive?.content
        }.getOrNull().orEmpty()

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

    private data class Deps(
        val adminTablesRepository: AdminTablesRepository,
        val tableSessionRepository: TableSessionRepository,
        val tableDepositRepository: TableDepositRepository,
        val visitRepository: VisitRepository,
        val nightOverrideRepository: NightOverrideRepository,
        val gamificationSettingsRepository: GamificationSettingsRepository,
        val auditLogger: AuditLogger,
        val guestQrResolver: GuestQrResolver,
    )
}
