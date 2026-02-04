package com.example.bot.routes

import com.example.bot.audit.AuditLogger
import com.example.bot.data.finance.ClubReportTemplate
import com.example.bot.data.finance.ClubReportTemplateData
import com.example.bot.data.finance.DepositHints
import com.example.bot.data.finance.ShiftReport
import com.example.bot.data.finance.ShiftReportDetails
import com.example.bot.data.finance.ShiftReportRepository
import com.example.bot.data.finance.ShiftReportStatus
import com.example.bot.data.finance.ShiftReportTemplateRepository
import com.example.bot.data.finance.ShiftReportUpdatePayload
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.http.ErrorCodes
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AdminFinanceShiftRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val telegramId = 777L

    @BeforeTest
    fun setUp() {
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = telegramId) }
    }

    @AfterTest
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `get creates draft lazily and returns it`() = withApp { deps ->
        val report = shiftReport(status = ShiftReportStatus.DRAFT)
        val details = ShiftReportDetails(report, bracelets = emptyList(), revenueEntries = emptyList())
        val template = template()
        val depositHints = DepositHints(sumDepositsForNight = 0, allocationSummaryForNight = emptyMap())

        coEvery { deps.shiftReportRepository.getOrCreateDraft(1, any()) } returns report
        coEvery { deps.shiftReportRepository.getDetails(report.id) } returns details
        coEvery { deps.shiftReportRepository.getDepositHints(1, any()) } returns depositHints
        coEvery { deps.templateRepository.getTemplateData(1) } returns template

        val response =
            client.get("/api/admin/clubs/1/nights/2024-06-01T20:00:00Z/finance/shift") {
                header("X-Telegram-Init-Data", "init")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(report.id.toString(), body["report"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("DRAFT", body["report"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        coVerify(exactly = 1) { deps.shiftReportRepository.getOrCreateDraft(1, any()) }
    }

    @Test
    fun `close requires comment when mismatch exceeds threshold`() = withApp(thresholdMinor = 100) { deps ->
        val report = shiftReport(status = ShiftReportStatus.DRAFT, comment = null)
        val details = ShiftReportDetails(report, bracelets = emptyList(), revenueEntries = emptyList())
        val depositHints = DepositHints(sumDepositsForNight = 1000, allocationSummaryForNight = emptyMap())

        coEvery { deps.shiftReportRepository.getDetails(report.id) } returns details
        coEvery { deps.shiftReportRepository.getDepositHints(1, any()) } returns depositHints

        val response =
            client.post("/api/admin/clubs/1/finance/shift/${report.id}/close") {
                header("X-Telegram-Init-Data", "init")
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(ErrorCodes.validation_error, body["code"]!!.jsonPrimitive.content)
        coVerify(exactly = 0) { deps.shiftReportRepository.close(any(), any(), any()) }
    }

    @Test
    fun `update draft returns invalid_state for closed report`() = withApp { deps ->
        val report = shiftReport(status = ShiftReportStatus.CLOSED)
        val details = ShiftReportDetails(report, bracelets = emptyList(), revenueEntries = emptyList())
        coEvery { deps.shiftReportRepository.getDetails(report.id) } returns details

        val response =
            client.put("/api/admin/clubs/1/finance/shift/${report.id}") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "peopleWomen":0,
                    "peopleMen":0,
                    "peopleRejected":0,
                    "comment":null,
                    "bracelets":[],
                    "revenueEntries":[]
                }""",
                )
            }

        assertEquals(HttpStatusCode.Conflict, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(ErrorCodes.invalid_state, body["code"]!!.jsonPrimitive.content)
        coVerify(exactly = 0) { deps.shiftReportRepository.updateDraft(any(), any<ShiftReportUpdatePayload>()) }
    }

    @Test
    fun `close returns invalid_state for closed report`() = withApp { deps ->
        val report = shiftReport(status = ShiftReportStatus.CLOSED)
        val details = ShiftReportDetails(report, bracelets = emptyList(), revenueEntries = emptyList())
        coEvery { deps.shiftReportRepository.getDetails(report.id) } returns details

        val response =
            client.post("/api/admin/clubs/1/finance/shift/${report.id}/close") {
                header("X-Telegram-Init-Data", "init")
            }

        assertEquals(HttpStatusCode.Conflict, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(ErrorCodes.invalid_state, body["code"]!!.jsonPrimitive.content)
        coVerify(exactly = 0) { deps.shiftReportRepository.close(any(), any(), any()) }
    }

    private fun withApp(
        roles: Set<Role> = setOf(Role.MANAGER),
        clubIds: Set<Long> = setOf(1),
        thresholdMinor: Long? = null,
        block: suspend ApplicationTestBuilder.(Deps) -> Unit,
    ) {
        val deps = buildDeps()
        testApplication {
            if (thresholdMinor != null) {
                environment {
                    config = MapApplicationConfig(
                        "app.SHIFT_REPORT_DEPOSIT_MISMATCH_THRESHOLD_MINOR" to thresholdMinor.toString(),
                    )
                }
            }
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(roles, clubIds)
                    auditLogRepository = mockk(relaxed = true)
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }
                adminFinanceShiftRoutes(
                    shiftReportRepository = deps.shiftReportRepository,
                    templateRepository = deps.templateRepository,
                    auditLogger = deps.auditLogger,
                    clock = java.time.Clock.fixed(Instant.parse("2024-06-01T20:00:00Z"), ZoneOffset.UTC),
                    botTokenProvider = { "test" },
                )
            }

            block(this, deps)
        }
    }

    private fun buildDeps(): Deps =
        Deps(
            shiftReportRepository = mockk(),
            templateRepository = mockk(),
            auditLogger = mockk(relaxed = true),
        )

    private fun shiftReport(
        status: ShiftReportStatus,
        comment: String? = "ok",
    ): ShiftReport =
        ShiftReport(
            id = 10,
            clubId = 1,
            nightStartUtc = Instant.parse("2024-06-01T20:00:00Z"),
            status = status,
            peopleWomen = 0,
            peopleMen = 0,
            peopleRejected = 0,
            comment = comment,
            closedAt = null,
            closedBy = null,
            createdAt = Instant.parse("2024-06-01T20:00:00Z"),
            updatedAt = Instant.parse("2024-06-01T20:00:00Z"),
        )

    private fun template(): ClubReportTemplateData =
        ClubReportTemplateData(
            template =
                ClubReportTemplate(
                    clubId = 1,
                    createdAt = Instant.parse("2024-06-01T20:00:00Z"),
                    updatedAt = Instant.parse("2024-06-01T20:00:00Z"),
                ),
            bracelets = emptyList(),
            revenueGroups = emptyList(),
            revenueArticles = emptyList(),
        )

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
        val shiftReportRepository: ShiftReportRepository,
        val templateRepository: ShiftReportTemplateRepository,
        val auditLogger: AuditLogger,
    )
}
