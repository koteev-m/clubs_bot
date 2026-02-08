package com.example.bot.routes

import com.example.bot.data.booking.TableDepositRepository
import com.example.bot.data.finance.ShiftReport
import com.example.bot.data.finance.ShiftReportDetails
import com.example.bot.data.finance.ShiftReportRepository
import com.example.bot.data.finance.ShiftReportRevenueEntry
import com.example.bot.data.finance.ShiftReportStatus
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.data.stories.GuestSegmentsRepository
import com.example.bot.data.stories.PostEventStory
import com.example.bot.data.stories.PostEventStoryRepository
import com.example.bot.data.stories.PostEventStoryStatus
import com.example.bot.data.stories.SegmentComputationResult
import com.example.bot.data.stories.SegmentType
import com.example.bot.data.visits.VisitRepository
import com.example.bot.http.ErrorCodes
import com.example.bot.owner.AttendanceChannel
import com.example.bot.owner.AttendanceChannels
import com.example.bot.owner.AttendanceHealth
import com.example.bot.owner.OwnerHealthService
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long

class AdminAnalyticsRoutesTest {
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
    fun `analytics returns forbidden for manager role`() = withApp(roles = setOf(Role.MANAGER)) { deps ->
        val response =
            client.get("/api/admin/clubs/1/analytics?nightStartUtc=2024-06-01T20:00:00Z&windowDays=30") {
                header("X-Telegram-Init-Data", "init")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        coVerify(exactly = 0) { deps.visitRepository.countNightUniqueVisitors(any(), any()) }
    }

    @Test
    fun `analytics validates nightStartUtc`() = withApp { _ ->
        val response =
            client.get("/api/admin/clubs/1/analytics?nightStartUtc=bad&windowDays=30") {
                header("X-Telegram-Init-Data", "init")
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(ErrorCodes.validation_error, body["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `analytics returns stable payload and upserts story`() = withApp { deps ->
        coEvery { deps.ownerHealthService.attendanceForNight(1, any()) } returns attendanceHealth()
        coEvery { deps.visitRepository.countNightUniqueVisitors(1, any()) } returns 10
        coEvery { deps.visitRepository.countNightEarlyVisits(1, any()) } returns 3
        coEvery { deps.visitRepository.countNightTableNights(1, any()) } returns 2
        coEvery { deps.tableDepositRepository.sumDepositsForNight(1, any()) } returns 15000
        coEvery { deps.tableDepositRepository.allocationSummaryForNight(1, any()) } returns mapOf("BAR" to 5000)
        coEvery { deps.shiftReportRepository.getByClubAndNight(1, any()) } returns shiftReport()
        coEvery { deps.shiftReportRepository.getDetails(10) } returns shiftDetails()
        coEvery { deps.guestSegmentsRepository.computeSegments(1, 30, any()) } returns SegmentComputationResult(
            counts = mapOf(SegmentType.NEW to 5, SegmentType.FREQUENT to 2, SegmentType.SLEEPING to 1),
        )
        coEvery { deps.storyRepository.upsert(any(), any(), any(), any(), any(), any(), any(), any()) } returns story()

        val response =
            client.get("/api/admin/clubs/1/analytics?nightStartUtc=2024-06-01T20:00:00Z&windowDays=30") {
                header("X-Telegram-Init-Data", "init")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("1", body["schemaVersion"]!!.jsonPrimitive.content)
        assertEquals("2024-06-01T20:00:00Z", body["nightStartUtc"]!!.jsonPrimitive.content)
        val meta = body["meta"]!!.jsonObject
        assertEquals(false, meta["hasIncompleteData"]!!.jsonPrimitive.boolean)
        assertEquals(15000, body["deposits"]!!.jsonObject["totalMinor"]!!.jsonPrimitive.long)
        assertEquals(10, body["visits"]!!.jsonObject["uniqueVisitors"]!!.jsonPrimitive.long)
        val segments = body["segments"]!!.jsonObject["counts"]!!.jsonObject
        assertEquals(5, segments["new"]!!.jsonPrimitive.int)

        coVerify(exactly = 1) {
            deps.storyRepository.upsert(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `stories list supports pagination`() = withApp { deps ->
        coEvery { deps.storyRepository.listByClub(1, 2, 0) } returns listOf(story(2), story(1))

        val response =
            client.get("/api/admin/clubs/1/stories?limit=2&offset=0") {
                header("X-Telegram-Init-Data", "init")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val stories = body["stories"]!!.jsonArray
        assertEquals(2, stories.size)
        assertEquals("2024-06-02T20:00:00Z", stories[0].jsonObject["nightStartUtc"]!!.jsonPrimitive.content)
        assertEquals("2024-06-01T20:00:00Z", stories[1].jsonObject["nightStartUtc"]!!.jsonPrimitive.content)
    }

    private fun withApp(
        roles: Set<Role> = setOf(Role.HEAD_MANAGER),
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
                adminAnalyticsRoutes(
                    ownerHealthService = deps.ownerHealthService,
                    visitRepository = deps.visitRepository,
                    tableDepositRepository = deps.tableDepositRepository,
                    shiftReportRepository = deps.shiftReportRepository,
                    storyRepository = deps.storyRepository,
                    guestSegmentsRepository = deps.guestSegmentsRepository,
                    clock = java.time.Clock.fixed(Instant.parse("2024-06-02T00:00:00Z"), ZoneOffset.UTC),
                    botTokenProvider = { "test" },
                )
            }

            block(this, deps)
        }
    }

    private fun attendanceHealth(): AttendanceHealth {
        val bookings = AttendanceChannel(plannedGuests = 10, arrivedGuests = 5, noShowGuests = 5, noShowRate = 0.5)
        val guestLists = AttendanceChannel(plannedGuests = 4, arrivedGuests = 3, noShowGuests = 1, noShowRate = 0.25)
        return AttendanceHealth(
            bookings = bookings,
            guestLists = guestLists,
            channels =
                AttendanceChannels(
                    directBookings = bookings,
                    promoterBookings = AttendanceChannel(plannedGuests = 2, arrivedGuests = 1, noShowGuests = 1, noShowRate = 0.5),
                    guestLists = guestLists,
                ),
        )
    }

    private fun shiftReport(): ShiftReport =
        ShiftReport(
            id = 10,
            clubId = 1,
            nightStartUtc = Instant.parse("2024-06-01T20:00:00Z"),
            status = ShiftReportStatus.CLOSED,
            peopleWomen = 10,
            peopleMen = 12,
            peopleRejected = 1,
            comment = null,
            closedAt = null,
            closedBy = null,
            createdAt = Instant.parse("2024-06-01T20:00:00Z"),
            updatedAt = Instant.parse("2024-06-01T20:00:00Z"),
        )

    private fun shiftDetails(): ShiftReportDetails =
        ShiftReportDetails(
            report = shiftReport(),
            bracelets = emptyList(),
            revenueEntries =
                listOf(
                    ShiftReportRevenueEntry(
                        id = 1,
                        reportId = 10,
                        articleId = null,
                        name = "Total",
                        groupId = 1,
                        amountMinor = 5000,
                        includeInTotal = true,
                        showSeparately = false,
                        orderIndex = 0,
                    ),
                ),
        )

    private fun story(id: Long = 1): PostEventStory =
        PostEventStory(
            id = id,
            clubId = 1,
            nightStartUtc = Instant.parse("2024-06-0${id}T20:00:00Z"),
            schemaVersion = 1,
            status = PostEventStoryStatus.READY,
            payloadJson = """{"schemaVersion":1}""",
            errorCode = null,
            generatedAt = Instant.parse("2024-06-02T00:00:00Z"),
            updatedAt = Instant.parse("2024-06-02T00:00:00Z"),
        )

    private fun buildDeps(): Deps =
        Deps(
            ownerHealthService = mockk(),
            visitRepository = mockk(),
            tableDepositRepository = mockk(),
            shiftReportRepository = mockk(),
            storyRepository = mockk(),
            guestSegmentsRepository = mockk(),
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
        val ownerHealthService: OwnerHealthService,
        val visitRepository: VisitRepository,
        val tableDepositRepository: TableDepositRepository,
        val shiftReportRepository: ShiftReportRepository,
        val storyRepository: PostEventStoryRepository,
        val guestSegmentsRepository: GuestSegmentsRepository,
    )
}
