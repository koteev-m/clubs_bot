package com.example.bot.routes

import com.example.bot.analytics.AdminAnalyticsRefreshWorker
import com.example.bot.analytics.AdminAnalyticsSnapshotService
import com.example.bot.analytics.SnapshotState
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.data.stories.AnalyticsSnapshot
import com.example.bot.data.stories.PostEventStory
import com.example.bot.data.stories.PostEventStoryRepository
import com.example.bot.data.stories.PostEventStoryStatus
import com.example.bot.http.ErrorCodes
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
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        coVerify(exactly = 0) { deps.snapshotService.fetchLatest(any(), any(), any()) }
    }

    @Test
    fun `analytics returns snapshot and schedules refresh when stale`() = withApp { deps ->
        coEvery { deps.snapshotService.fetchLatest(1, any(), 30) } returns snapshotView(state = SnapshotState.STALE_ALLOWED)

        val response =
            client.get("/api/admin/clubs/1/analytics?nightStartUtc=2024-06-01T20:00:00Z&windowDays=30") {
                header("X-Telegram-Init-Data", "init")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, body["schemaVersion"]!!.jsonPrimitive.int)
        coVerify(exactly = 1) { deps.refreshWorker.schedule(1, Instant.parse("2024-06-01T20:00:00Z"), 30) }
    }

    @Test
    fun `analytics returns accepted and schedules refresh when snapshot missing`() = withApp { deps ->
        coEvery { deps.snapshotService.fetchLatest(1, any(), 30) } returns null

        val response =
            client.get("/api/admin/clubs/1/analytics?nightStartUtc=2024-06-01T20:00:00Z&windowDays=30") {
                header("X-Telegram-Init-Data", "init")
            }

        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("recompute_scheduled", body["status"]!!.jsonPrimitive.content)
        coVerify(exactly = 1) { deps.refreshWorker.schedule(1, Instant.parse("2024-06-01T20:00:00Z"), 30) }
    }

    @Test
    fun `analytics returns accepted and schedules refresh when snapshot is stale too old`() = withApp { deps ->
        coEvery { deps.snapshotService.fetchLatest(1, any(), 30) } returns snapshotView(state = SnapshotState.STALE_TOO_OLD)

        val response =
            client.get("/api/admin/clubs/1/analytics?nightStartUtc=2024-06-01T20:00:00Z&windowDays=30") {
                header("X-Telegram-Init-Data", "init")
            }

        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("recompute_scheduled", body["status"]!!.jsonPrimitive.content)
        coVerify(exactly = 1) { deps.refreshWorker.schedule(1, Instant.parse("2024-06-01T20:00:00Z"), 30) }
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

    @Test
    fun `story details returns payload`() = withApp { deps ->
        coEvery { deps.storyRepository.getByClubAndNight(1, any(), any()) } returns story()

        val response =
            client.get("/api/admin/clubs/1/stories/2024-06-01T20:00:00Z") {
                header("X-Telegram-Init-Data", "init")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, body["schemaVersion"]!!.jsonPrimitive.int)
        assertEquals("READY", body["status"]!!.jsonPrimitive.content)
        assertEquals(1, body["id"]!!.jsonPrimitive.long)
    }

    @Test
    fun `story details returns not found when missing`() = withApp { deps ->
        coEvery { deps.storyRepository.getByClubAndNight(1, any(), any()) } returns null

        val response =
            client.get("/api/admin/clubs/1/stories/2024-06-01T20:00:00Z") {
                header("X-Telegram-Init-Data", "init")
            }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(ErrorCodes.not_found, body["code"]!!.jsonPrimitive.content)
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
                    snapshotService = deps.snapshotService,
                    refreshWorker = deps.refreshWorker,
                    storyRepository = deps.storyRepository,
                    botTokenProvider = { "test" },
                )
            }

            block(this, deps)
        }
    }

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

    private fun snapshotView(state: SnapshotState): com.example.bot.analytics.AnalyticsSnapshotView {
        val response =
            AnalyticsResponse(
                schemaVersion = 1,
                clubId = 1,
                nightStartUtc = "2024-06-01T20:00:00Z",
                generatedAt = "2024-06-02T00:00:00Z",
                meta = AnalyticsMeta(hasIncompleteData = false, caveats = emptyList()),
                attendance = null,
                visits = VisitSummary(uniqueVisitors = 10, earlyVisits = 3, tableNights = 2),
                deposits = DepositSummary(totalMinor = 12000, allocationSummary = mapOf("bar" to 12000)),
                shift = null,
                segments = SegmentSummary(counts = mapOf("new" to 2, "frequent" to 1, "sleeping" to 0)),
            )
        val snapshot =
            AnalyticsSnapshot(
                id = 1,
                clubId = 1,
                nightStartUtc = Instant.parse("2024-06-01T20:00:00Z"),
                windowDays = 30,
                schemaVersion = 1,
                status = PostEventStoryStatus.READY,
                payloadJson = "{}",
                errorCode = null,
                generatedAt = Instant.parse("2024-06-02T00:00:00Z"),
                updatedAt = Instant.parse("2024-06-02T00:00:00Z"),
            )
        return com.example.bot.analytics.AnalyticsSnapshotView(response = response, snapshot = snapshot, state = state)
    }

    private fun buildDeps(): Deps {
        val snapshotService = mockk<AdminAnalyticsSnapshotService>()
        val refreshWorker = mockk<AdminAnalyticsRefreshWorker>()
        every { refreshWorker.schedule(any(), any(), any()) } returns Unit
        return Deps(
            snapshotService = snapshotService,
            refreshWorker = refreshWorker,
            storyRepository = mockk(),
        )
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
        val snapshotService: AdminAnalyticsSnapshotService,
        val refreshWorker: AdminAnalyticsRefreshWorker,
        val storyRepository: PostEventStoryRepository,
    )
}
