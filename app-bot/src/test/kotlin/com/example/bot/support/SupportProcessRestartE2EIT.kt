package com.example.bot.support

import com.example.bot.webapp.WebAppInitDataTestHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import testing.RequiresDocker
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

private const val POSTGRES_IMAGE = "postgres:16-alpine"
private const val DATABASE_NAME = "psl_ac_29_restart"
private const val DATABASE_USERNAME = "psl_ac_29_user"
private const val DATABASE_PASSWORD = "psl_ac_29_database_password_secret"
private const val BOT_TOKEN = "111111:PSL_AC_29_RESTART_BOT_TOKEN_SECRET"
private const val WEBHOOK_SECRET = "psl-ac-29-webhook-secret-do-not-log"
private const val OWNER_TELEGRAM_ID = 8_210_000_000L
private const val GUEST_TELEGRAM_ID = 8_210_000_101L
private const val STAFF_TELEGRAM_ID = 8_210_000_202L
private const val GUEST_USERNAME = "restart_guest"
private const val STAFF_USERNAME = "restart_staff"
private const val GUEST_START_UPDATE_ID = 921_001_001L
private const val STAFF_START_UPDATE_ID = 921_001_002L
private const val REPEATED_GUEST_START_UPDATE_ID = 921_001_003L
private const val TICKET_TOPIC = "other"
private const val INITIAL_QUESTION = "PSL-AC-29 initial guest question sentinel"
private const val STAFF_REPLY = "PSL-AC-29 persisted staff reply sentinel"
private const val WELCOME_TEXT = "Добро пожаловать в Night Concierge!"

@RequiresDocker
@Tag("it")
internal class SupportProcessRestartE2EIT : SupportProcessRestartAssertions() {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val json = Json
    private val initDataSequence = AtomicLong()
    private val generatedInitData = mutableListOf<String>()

    @Test
    fun `production support state and delivery audit survive complete JVM restart`() {
        val postgres =
            PostgreSQLContainer<Nothing>(POSTGRES_IMAGE).apply {
                withDatabaseName(DATABASE_NAME)
                withUsername(DATABASE_USERNAME)
                withPassword(DATABASE_PASSWORD)
            }
        val fakeTelegram = FakeTelegramBotApi()
        var processA: SupportChildProcess? = null
        var processB: SupportChildProcess? = null
        var primaryFailure: Throwable? = null
        var deferredFailure: Throwable? = null
        var proofSummary: ProofSummary? = null

        try {
            postgres.start()
            val databaseConfig =
                RestartDatabaseConfig(
                    jdbcUrl = postgres.jdbcUrl,
                    username = postgres.username,
                    password = postgres.password,
                )
            val containerId = postgres.containerId
            val retainedJdbcUrl = postgres.jdbcUrl
            val retainedDatabaseName = postgres.databaseName
            val initialDatabaseIdentity = SupportRestartDatabase.databaseIdentity(databaseConfig)
            val childEnvironment = childEnvironment(postgres, fakeTelegram)

            val portA = findFreeLoopbackPort()
            processA =
                SupportChildProcess.start(
                    label = "process-a",
                    port = portA,
                    temporaryDirectory = temporaryDirectory,
                    environment = childEnvironment,
                )
            val processAStartedAt = processA.startedAt
            processA.awaitReadiness()
            processA.checkStillAlive("after readiness")

            assertWebhookAccepted(processA, GUEST_START_UPDATE_ID, GUEST_TELEGRAM_ID, GUEST_USERNAME)
            assertWebhookAccepted(processA, STAFF_START_UPDATE_ID, STAFF_TELEGRAM_ID, STAFF_USERNAME)
            val (guestUser, staffUser) =
                SupportRestartDatabase.awaitProvisionedUsers(
                    config = databaseConfig,
                    guestTelegramId = GUEST_TELEGRAM_ID,
                    staffTelegramId = STAFF_TELEGRAM_ID,
                    updateIds = listOf(GUEST_START_UPDATE_ID, STAFF_START_UPDATE_ID),
                )
            assertEquals(GUEST_TELEGRAM_ID, guestUser.telegramUserId)
            assertEquals(STAFF_TELEGRAM_ID, staffUser.telegramUserId)
            assertEquals(null, guestUser.username)
            assertEquals(null, guestUser.displayName)
            assertEquals(null, staffUser.username)
            assertEquals(null, staffUser.displayName)
            assertWelcomeRequests(fakeTelegram.requests, expectedCount = 2)

            val staffFixture = SupportRestartDatabase.installExactStaffFixture(databaseConfig, staffUser.id)
            assertExactStaffFixture(staffFixture, staffUser.id)

            val createResponse =
                postJson(
                    process = processA,
                    path = "/api/support/tickets",
                    initData = freshInitData(GUEST_TELEGRAM_ID, GUEST_USERNAME, "a-create"),
                    body =
                        buildJsonObject {
                            put("clubId", staffFixture.club.id)
                            put("topic", TICKET_TOPIC)
                            put("text", INITIAL_QUESTION)
                        }.toString(),
                )
            assertEquals(201, createResponse.status, createResponse.body)
            val createPayload = parseObject(createResponse)
            assertEquals(setOf("id", "clubId", "topic", "status", "updatedAt"), createPayload.keys)
            val ticketId = createPayload.requiredLong("id")
            assertEquals(staffFixture.club.id, createPayload.requiredLong("clubId"))
            assertEquals(TICKET_TOPIC, createPayload.requiredString("topic"))
            assertEquals("new", createPayload.requiredString("status"))

            val (createdTicket, createdMessages) =
                SupportRestartDatabase.inspectTicketAndMessages(databaseConfig, ticketId)
            assertEquals(ticketId, createdTicket.id)
            assertEquals(staffFixture.club.id, createdTicket.clubId)
            assertEquals(guestUser.id, createdTicket.userId)
            assertEquals(TICKET_TOPIC, createdTicket.topic)
            assertEquals("new", createdTicket.status)
            assertEquals(1, createdMessages.size)
            val initialMessageId = createdMessages.single().id
            assertEquals("guest", createdMessages.single().senderType)
            assertEquals(INITIAL_QUESTION, createdMessages.single().text)
            assertEquals(null, createdMessages.single().attachments)

            val replyResponse =
                postJson(
                    process = processA,
                    path = "/api/support/tickets/$ticketId/reply",
                    initData = freshInitData(STAFF_TELEGRAM_ID, STAFF_USERNAME, "a-reply"),
                    body = buildJsonObject { put("text", STAFF_REPLY) }.toString(),
                )
            assertEquals(200, replyResponse.status, replyResponse.body)
            val replyPayload = parseObject(replyResponse)
            assertEquals(
                setOf(
                    "ticketId",
                    "clubId",
                    "replyMessageId",
                    "replyCreatedAt",
                    "ticketStatus",
                    "deliveryStatus",
                ),
                replyPayload.keys,
            )
            assertEquals(ticketId, replyPayload.requiredLong("ticketId"))
            assertEquals(staffFixture.club.id, replyPayload.requiredLong("clubId"))
            val replyMessageId = replyPayload.requiredLong("replyMessageId")
            assertEquals("in_progress", replyPayload.requiredString("ticketStatus"))
            assertEquals("delivered", replyPayload.requiredString("deliveryStatus"))

            val guestListA = getJson(processA, "/api/support/tickets/my", freshInitDataForGuest("a-guest-list"))
            assertEquals(200, guestListA.status, guestListA.body)
            assertSingleTicketList(parseArray(guestListA), ticketId, staffFixture.club.id)
            val guestThreadA =
                getJson(
                    processA,
                    "/api/support/tickets/my/$ticketId",
                    freshInitDataForGuest("a-guest-detail"),
                )
            assertEquals(200, guestThreadA.status, guestThreadA.body)
            val guestThreadSnapshotA = parseObject(guestThreadA)
            assertGuestThread(
                payload = guestThreadSnapshotA,
                ticketId = ticketId,
                clubId = staffFixture.club.id,
                initialMessageId = initialMessageId,
                replyMessageId = replyMessageId,
            )

            val staffClubsA =
                getJson(processA, "/api/support/staff/clubs", freshInitDataForStaff("a-staff-clubs"))
            assertEquals(200, staffClubsA.status, staffClubsA.body)
            assertExactStaffClubs(parseArray(staffClubsA), staffFixture.club)
            val staffListA =
                getJson(
                    processA,
                    "/api/support/tickets?clubId=${staffFixture.club.id}",
                    freshInitDataForStaff("a-staff-list"),
                )
            assertEquals(200, staffListA.status, staffListA.body)
            assertSingleTicketList(parseArray(staffListA), ticketId, staffFixture.club.id)
            val staffThreadA =
                getJson(
                    processA,
                    "/api/support/tickets/$ticketId",
                    freshInitDataForStaff("a-staff-detail"),
                )
            assertEquals(200, staffThreadA.status, staffThreadA.body)
            val staffThreadSnapshotA = parseObject(staffThreadA)
            assertStaffThread(
                payload = staffThreadSnapshotA,
                ticketId = ticketId,
                clubId = staffFixture.club.id,
                initialMessageId = initialMessageId,
                replyMessageId = replyMessageId,
            )

            val baseline =
                SupportRestartDatabase.inspectDurableState(
                    config = databaseConfig,
                    ticketId = ticketId,
                    guestTelegramId = GUEST_TELEGRAM_ID,
                    staffTelegramId = STAFF_TELEGRAM_ID,
                    assignmentId = staffFixture.assignment.id,
                )
            assertDurableContract(
                state = baseline,
                guestUserId = guestUser.id,
                staffUserId = staffUser.id,
                clubId = staffFixture.club.id,
                ticketId = ticketId,
                replyMessageId = replyMessageId,
            )
            assertEquals(initialDatabaseIdentity, baseline.databaseIdentity)
            assertSupportTelegramAttempt(fakeTelegram, staffFixture.club.name, expectedCount = 1)

            processA.checkStillAlive("before durable restart boundary")
            val processAExit = processA.stopGracefully()
            val processAStoppedAt = Instant.now()
            assertFalse(processA.isAlive())
            assertFalse(processHandleAlive(processA.pid))
            assertTrue(postgres.isRunning)
            assertEquals(containerId, postgres.containerId)
            assertEquals(retainedJdbcUrl, postgres.jdbcUrl)
            assertEquals(retainedDatabaseName, postgres.databaseName)
            assertEquals(initialDatabaseIdentity, SupportRestartDatabase.databaseIdentity(databaseConfig))

            val processADeathObservedAt = Instant.now()
            val portB = findFreeLoopbackPort(excludedPorts = setOf(portA))
            assertNotEquals(portA, portB)
            processB =
                SupportChildProcess.start(
                    label = "process-b",
                    port = portB,
                    temporaryDirectory = temporaryDirectory,
                    environment = childEnvironment,
                )
            assertNotEquals(processA.pid, processB.pid)
            assertFalse(processHandleAlive(processA.pid))
            val processBReadyAt = processB.awaitReadiness()
            assertTrue(processBReadyAt.isAfter(processADeathObservedAt))
            assertFalse(processHandleAlive(processA.pid))
            processB.checkStillAlive("after readiness")
            assertTrue(postgres.isRunning)
            assertEquals(containerId, postgres.containerId)
            assertEquals(initialDatabaseIdentity, SupportRestartDatabase.databaseIdentity(databaseConfig))

            assertWebhookAccepted(processB, REPEATED_GUEST_START_UPDATE_ID, GUEST_TELEGRAM_ID, GUEST_USERNAME)
            val repeatedGuest =
                SupportRestartDatabase.awaitRepeatedStart(
                    config = databaseConfig,
                    telegramUserId = GUEST_TELEGRAM_ID,
                    updateId = REPEATED_GUEST_START_UPDATE_ID,
                    expectedUserId = guestUser.id,
                )
            assertEquals(guestUser, repeatedGuest)
            assertWelcomeRequests(fakeTelegram.requests, expectedCount = 3)
            assertSupportTelegramAttempt(fakeTelegram, staffFixture.club.name, expectedCount = 1)

            val guestListB = getJson(processB, "/api/support/tickets/my", freshInitDataForGuest("b-guest-list"))
            assertEquals(200, guestListB.status, guestListB.body)
            assertSingleTicketList(parseArray(guestListB), ticketId, staffFixture.club.id)
            val guestThreadB =
                getJson(
                    processB,
                    "/api/support/tickets/my/$ticketId",
                    freshInitDataForGuest("b-guest-detail"),
                )
            assertEquals(200, guestThreadB.status, guestThreadB.body)
            val guestThreadSnapshotB = parseObject(guestThreadB)
            assertEquals(guestThreadSnapshotA, guestThreadSnapshotB)
            assertGuestThread(
                payload = guestThreadSnapshotB,
                ticketId = ticketId,
                clubId = staffFixture.club.id,
                initialMessageId = initialMessageId,
                replyMessageId = replyMessageId,
            )

            val staffAsGuestList =
                getJson(processB, "/api/support/tickets/my", freshInitDataForStaff("b-owner-negative-list"))
            assertEquals(200, staffAsGuestList.status, staffAsGuestList.body)
            assertTrue(parseArray(staffAsGuestList).isEmpty())
            val staffAsGuestDetail =
                getJson(
                    processB,
                    "/api/support/tickets/my/$ticketId",
                    freshInitDataForStaff("b-owner-negative-detail"),
                )
            assertEquals(404, staffAsGuestDetail.status, staffAsGuestDetail.body)
            assertEquals("support_ticket_not_found", errorCode(staffAsGuestDetail))

            val staffClubsB =
                getJson(processB, "/api/support/staff/clubs", freshInitDataForStaff("b-staff-clubs"))
            assertEquals(200, staffClubsB.status, staffClubsB.body)
            assertEquals(parseArray(staffClubsA), parseArray(staffClubsB))
            assertExactStaffClubs(parseArray(staffClubsB), staffFixture.club)
            val staffListB =
                getJson(
                    processB,
                    "/api/support/tickets?clubId=${staffFixture.club.id}",
                    freshInitDataForStaff("b-staff-list"),
                )
            assertEquals(200, staffListB.status, staffListB.body)
            assertEquals(parseArray(staffListA), parseArray(staffListB))
            assertSingleTicketList(parseArray(staffListB), ticketId, staffFixture.club.id)
            val staffThreadB =
                getJson(
                    processB,
                    "/api/support/tickets/$ticketId",
                    freshInitDataForStaff("b-staff-detail"),
                )
            assertEquals(200, staffThreadB.status, staffThreadB.body)
            val staffThreadSnapshotB = parseObject(staffThreadB)
            assertEquals(staffThreadSnapshotA, staffThreadSnapshotB)
            assertStaffThread(
                payload = staffThreadSnapshotB,
                ticketId = ticketId,
                clubId = staffFixture.club.id,
                initialMessageId = initialMessageId,
                replyMessageId = replyMessageId,
            )

            val guestStaffClubs =
                getJson(processB, "/api/support/staff/clubs", freshInitDataForGuest("b-staff-negative-clubs"))
            assertEquals(200, guestStaffClubs.status, guestStaffClubs.body)
            assertTrue(parseArray(guestStaffClubs).isEmpty())
            val guestStaffList =
                getJson(
                    processB,
                    "/api/support/tickets?clubId=${staffFixture.club.id}",
                    freshInitDataForGuest("b-staff-negative-list"),
                )
            assertEquals(403, guestStaffList.status, guestStaffList.body)
            assertEquals("support_ticket_forbidden", errorCode(guestStaffList))
            val foreignClubList =
                getJson(
                    processB,
                    "/api/support/tickets?clubId=${staffFixture.foreignClub.id}",
                    freshInitDataForStaff("b-foreign-club-negative"),
                )
            assertEquals(403, foreignClubList.status, foreignClubList.body)
            assertEquals("support_ticket_forbidden", errorCode(foreignClubList))

            val afterRestart =
                SupportRestartDatabase.inspectDurableState(
                    config = databaseConfig,
                    ticketId = ticketId,
                    guestTelegramId = GUEST_TELEGRAM_ID,
                    staffTelegramId = STAFF_TELEGRAM_ID,
                    assignmentId = staffFixture.assignment.id,
                )
            assertEquals(baseline, afterRestart)
            assertDurableContract(
                state = afterRestart,
                guestUserId = guestUser.id,
                staffUserId = staffUser.id,
                clubId = staffFixture.club.id,
                ticketId = ticketId,
                replyMessageId = replyMessageId,
            )
            assertEquals(containerId, postgres.containerId)
            assertEquals(retainedJdbcUrl, postgres.jdbcUrl)
            assertEquals(initialDatabaseIdentity, afterRestart.databaseIdentity)
            assertSupportTelegramAttempt(fakeTelegram, staffFixture.club.name, expectedCount = 1)
            assertEquals(generatedInitData.size, generatedInitData.toSet().size)

            processB.checkStillAlive("before requested cleanup")
            val processBExit = processB.stopGracefully()
            val processBStoppedAt = Instant.now()
            assertFalse(processB.isAlive())
            assertFalse(processHandleAlive(processB.pid))
            assertFalse(processHandleAlive(processA.pid))
            assertTrue(postgres.isRunning)

            assertSafeProcessLogs(processA, processB, generatedInitData)
            proofSummary =
                ProofSummary(
                    processAPid = processA.pid,
                    processAStartedAt = processAStartedAt,
                    processAStoppedAt = processAStoppedAt,
                    processAExit = processAExit,
                    processBPid = processB.pid,
                    processBStartedAt = processB.startedAt,
                    processBReadyAt = processBReadyAt,
                    processBStoppedAt = processBStoppedAt,
                    processBExit = processBExit,
                    containerId = containerId,
                    databaseIdentity = initialDatabaseIdentity,
                    fakeTelegramPort = fakeTelegram.port,
                    guestUserId = guestUser.id,
                    staffUserId = staffUser.id,
                    clubId = staffFixture.club.id,
                    ticketId = ticketId,
                    messageIds = afterRestart.messages.map(DurableMessageRow::id),
                    deliveryId = afterRestart.delivery.id,
                    deliveryStatus = afterRestart.delivery.status,
                    auditRows = afterRestart.audits,
                    supportTelegramAttemptCount = supportTelegramRequests(fakeTelegram.requests).size,
                )
        } catch (failure: Throwable) {
            primaryFailure = failure
            val diagnostics = safeProcessDiagnostics(processA, processB, generatedInitData)
            deferredFailure =
                AssertionError(
                    "PSL-AC-29 process proof failed; bounded child diagnostics follow\n$diagnostics",
                    failure,
                )
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            runCatching { processB?.forceStop() }.onFailure(cleanupFailures::add)
            runCatching { processA?.forceStop() }.onFailure(cleanupFailures::add)
            runCatching { fakeTelegram.close() }.onFailure(cleanupFailures::add)
            runCatching {
                if (postgres.isRunning) {
                    postgres.stop()
                }
            }.onFailure(cleanupFailures::add)
            if (cleanupFailures.isNotEmpty()) {
                if (primaryFailure == null) {
                    deferredFailure = AssertionError("Process/container cleanup failed", cleanupFailures.first())
                } else {
                    cleanupFailures.forEach(primaryFailure::addSuppressed)
                }
            }
        }

        deferredFailure?.let { throw it }
        val summary = requireNotNull(proofSummary)
        println(summary.render())
    }

    private fun childEnvironment(
        postgres: PostgreSQLContainer<Nothing>,
        fakeTelegram: FakeTelegramBotApi,
    ): Map<String, String> =
        mapOf(
            "APP_PROFILE" to "DEV",
            "APP_ENV" to "dev",
            "DATABASE_URL" to postgres.jdbcUrl,
            "DATABASE_USER" to postgres.username,
            "DATABASE_PASSWORD" to postgres.password,
            "FLYWAY_ENABLED" to "true",
            "FLYWAY_MODE" to "migrate-and-validate",
            "FLYWAY_LOCATIONS" to
                "classpath:db/migration/common,classpath:db/migration/postgresql",
            "FLYWAY_OUT_OF_ORDER" to "false",
            "FLYWAY_BASELINE_ON_MIGRATE" to "true",
            "TELEGRAM_BOT_TOKEN" to BOT_TOKEN,
            "BOT_USERNAME" to "restart_proof_bot",
            "OWNER_TELEGRAM_ID" to OWNER_TELEGRAM_ID.toString(),
            "TELEGRAM_USE_POLLING" to "false",
            "WEBHOOK_SECRET_TOKEN" to WEBHOOK_SECRET,
            "LOCAL_BOT_API_ENABLED" to "true",
            "LOCAL_BOT_API_URL" to fakeTelegram.apiUrl,
            "HQ_CHAT_ID" to "-1009000000001",
            "CLUB1_CHAT_ID" to "-1009000000002",
            "CLUB2_CHAT_ID" to "-1009000000003",
            "CLUB3_CHAT_ID" to "-1009000000004",
            "CLUB4_CHAT_ID" to "-1009000000005",
            "RBAC_ENABLED" to "true",
            "ALLOW_INSECURE_DEV" to "false",
            "NOTIFICATIONS_ENABLED" to "false",
            "OPS_NOTIFY_ENABLED" to "false",
            "REFUND_WORKER_ENABLED" to "false",
            "LEGACY_BOOKING_WEBAPP_ENABLED" to "false",
            "RATE_LIMIT_ENABLED" to "false",
            "HOT_PATH_ENABLED" to "false",
            "TRACING_ENABLED" to "false",
            "METRICS_PROMETHEUS_ENABLED" to "false",
            "LOG_JSON" to "true",
            "LOG_LEVEL" to "INFO",
            "TZ" to "UTC",
        )

    private fun assertWebhookAccepted(
        process: SupportChildProcess,
        updateId: Long,
        telegramUserId: Long,
        username: String,
    ) {
        process.checkStillAlive("before webhook update $updateId")
        val response =
            RestartHttpClient.request(
                method = "POST",
                url = "${process.baseUrl}/telegram/webhook",
                body = startUpdate(updateId, telegramUserId, username),
                headers =
                    mapOf(
                        "Content-Type" to "application/json",
                        "X-Telegram-Bot-Api-Secret-Token" to WEBHOOK_SECRET,
                    ),
            )
        assertEquals(200, response.status, response.body)
        assertEquals("OK", response.body)
    }

    private fun startUpdate(
        updateId: Long,
        telegramUserId: Long,
        username: String,
    ): String =
        buildJsonObject {
            put("update_id", updateId)
            putJsonObject("message") {
                put("message_id", updateId)
                put("date", Instant.now().epochSecond)
                put("text", "/start")
                putJsonObject("from") {
                    put("id", telegramUserId)
                    put("is_bot", false)
                    put("first_name", "Restart")
                    put("username", username)
                }
                putJsonObject("chat") {
                    put("id", telegramUserId)
                    put("type", "private")
                    put("first_name", "Restart")
                    put("username", username)
                }
            }
        }.toString()

    private fun freshInitDataForGuest(phase: String): String = freshInitData(GUEST_TELEGRAM_ID, GUEST_USERNAME, phase)

    private fun freshInitDataForStaff(phase: String): String = freshInitData(STAFF_TELEGRAM_ID, STAFF_USERNAME, phase)

    private fun freshInitData(
        telegramUserId: Long,
        username: String,
        phase: String,
    ): String {
        val nonce = initDataSequence.incrementAndGet()
        val initData =
            WebAppInitDataTestHelper.createInitData(
                botToken = BOT_TOKEN,
                rawParams =
                    linkedMapOf(
                        "query_id" to "psl-ac-29-$phase-$nonce",
                        "user" to WebAppInitDataTestHelper.encodeUser(telegramUserId, username),
                        "auth_date" to Instant.now().epochSecond.toString(),
                    ),
            )
        generatedInitData += initData
        return initData
    }

    private fun postJson(
        process: SupportChildProcess,
        path: String,
        initData: String,
        body: String,
    ): RestartHttpResponse =
        RestartHttpClient.request(
            method = "POST",
            url = process.baseUrl + path,
            body = body,
            headers = miniAppHeaders(initData) + ("Content-Type" to "application/json"),
        )

    private fun getJson(
        process: SupportChildProcess,
        path: String,
        initData: String,
    ): RestartHttpResponse =
        RestartHttpClient.request(
            method = "GET",
            url = process.baseUrl + path,
            headers = miniAppHeaders(initData),
        )

    private fun miniAppHeaders(initData: String): Map<String, String> = mapOf("X-Telegram-Init-Data" to initData)

    private fun parseObject(response: RestartHttpResponse): JsonObject =
        json.parseToJsonElement(response.body).jsonObject

    private fun parseArray(response: RestartHttpResponse): JsonArray = json.parseToJsonElement(response.body).jsonArray

    private fun errorCode(response: RestartHttpResponse): String = parseObject(response).requiredString("code")
}

internal open class SupportProcessRestartAssertions {
    private val json = Json

    protected fun assertExactStaffFixture(
        fixture: InstalledStaffFixture,
        staffUserId: Long,
    ) {
        assertEquals(staffUserId, fixture.assignment.userId)
        assertEquals("MANAGER", fixture.assignment.roleCode)
        assertEquals("CLUB", fixture.assignment.scopeType)
        assertEquals(fixture.club.id, fixture.assignment.scopeClubId)
        assertEquals(listOf("support.reply", "support.view"), fixture.assignment.permissions)
        assertNotEquals(fixture.club.id, fixture.foreignClub.id)
    }

    protected fun assertWelcomeRequests(
        requests: List<FakeTelegramBotApi.RecordedRequest>,
        expectedCount: Int,
    ) {
        val welcomes = requests.filter { it.endpoint == "sendMessage" && it.text == WELCOME_TEXT }
        assertEquals(expectedCount, welcomes.size)
        val expectedTargets =
            if (expectedCount == 2) {
                listOf(GUEST_TELEGRAM_ID, STAFF_TELEGRAM_ID)
            } else {
                listOf(GUEST_TELEGRAM_ID, STAFF_TELEGRAM_ID, GUEST_TELEGRAM_ID)
            }
        assertEquals(expectedTargets, welcomes.map { it.chatId })
    }

    protected fun assertSupportTelegramAttempt(
        fakeTelegram: FakeTelegramBotApi,
        clubName: String,
        expectedCount: Int,
    ) {
        val requests = supportTelegramRequests(fakeTelegram.requests)
        assertEquals(expectedCount, requests.size)
        val request = requests.single()
        assertEquals(GUEST_TELEGRAM_ID, request.chatId)
        assertEquals("Ответ от клуба «$clubName»\n\n$STAFF_REPLY", request.text)
        assertTrue(request.replyMarkupPresent)
    }

    protected fun supportTelegramRequests(
        requests: List<FakeTelegramBotApi.RecordedRequest>,
    ): List<FakeTelegramBotApi.RecordedRequest> =
        requests.filter { request ->
            request.endpoint == "sendMessage" && request.text?.contains(STAFF_REPLY) == true
        }

    protected fun assertSingleTicketList(
        payload: JsonArray,
        ticketId: Long,
        clubId: Long,
    ) {
        assertEquals(1, payload.size)
        val ticket = payload.single().jsonObject
        assertEquals(
            setOf("id", "clubId", "topic", "status", "updatedAt", "lastMessagePreview", "lastSenderType"),
            ticket.keys,
        )
        assertEquals(ticketId, ticket.requiredLong("id"))
        assertEquals(clubId, ticket.requiredLong("clubId"))
        assertEquals(TICKET_TOPIC, ticket.requiredString("topic"))
        assertEquals("in_progress", ticket.requiredString("status"))
        assertEquals(STAFF_REPLY, ticket.requiredString("lastMessagePreview"))
        assertEquals("agent", ticket.requiredString("lastSenderType"))
    }

    protected fun assertExactStaffClubs(
        payload: JsonArray,
        club: ClubRow,
    ) {
        assertEquals(1, payload.size)
        val item = payload.single().jsonObject
        assertEquals(setOf("id", "name", "canReply", "canTakeInWork", "canManageStatus"), item.keys)
        assertEquals(club.id, item.requiredLong("id"))
        assertEquals(club.name, item.requiredString("name"))
        assertTrue(
            item
                .getValue("canReply")
                .jsonPrimitive.content
                .toBooleanStrict(),
        )
        assertFalse(
            item
                .getValue("canTakeInWork")
                .jsonPrimitive.content
                .toBooleanStrict(),
        )
        assertFalse(
            item
                .getValue("canManageStatus")
                .jsonPrimitive.content
                .toBooleanStrict(),
        )
    }

    protected fun assertGuestThread(
        payload: JsonObject,
        ticketId: Long,
        clubId: Long,
        initialMessageId: Long,
        replyMessageId: Long,
    ) {
        assertEquals(setOf("ticket", "messages"), payload.keys)
        assertTicket(payload.getValue("ticket").jsonObject, ticketId, clubId)
        val messages = payload.getValue("messages").jsonArray
        assertEquals(2, messages.size)
        assertEquals(
            listOf(initialMessageId, replyMessageId),
            messages.map { message -> message.jsonObject.requiredLong("id") },
        )
        val guest = messages[0].jsonObject
        val agent = messages[1].jsonObject
        val guestKeys = setOf("id", "senderType", "text", "attachments", "createdAt")
        assertEquals(guestKeys, guest.keys)
        assertEquals(guestKeys, agent.keys)
        assertEquals("guest", guest.requiredString("senderType"))
        assertEquals(INITIAL_QUESTION, guest.requiredString("text"))
        assertEquals(JsonNull, guest["attachments"])
        assertEquals("agent", agent.requiredString("senderType"))
        assertEquals(STAFF_REPLY, agent.requiredString("text"))
        assertEquals(JsonNull, agent["attachments"])
        assertTrue(guest.requiredLong("id") < agent.requiredLong("id"))
    }

    protected fun assertStaffThread(
        payload: JsonObject,
        ticketId: Long,
        clubId: Long,
        initialMessageId: Long,
        replyMessageId: Long,
    ) {
        assertEquals(setOf("ticket", "messages"), payload.keys)
        assertTicket(payload.getValue("ticket").jsonObject, ticketId, clubId)
        val messages = payload.getValue("messages").jsonArray
        assertEquals(2, messages.size)
        assertEquals(
            listOf(initialMessageId, replyMessageId),
            messages.map { message -> message.jsonObject.requiredLong("id") },
        )
        val guest = messages[0].jsonObject
        val agent = messages[1].jsonObject
        val keys = setOf("id", "senderType", "text", "attachments", "createdAt", "deliveryStatus")
        assertEquals(keys, guest.keys)
        assertEquals(keys, agent.keys)
        assertEquals("guest", guest.requiredString("senderType"))
        assertEquals(INITIAL_QUESTION, guest.requiredString("text"))
        assertEquals(JsonNull, guest["attachments"])
        assertEquals(JsonNull, guest["deliveryStatus"])
        assertEquals("agent", agent.requiredString("senderType"))
        assertEquals(STAFF_REPLY, agent.requiredString("text"))
        assertEquals(JsonNull, agent["attachments"])
        assertEquals("delivered", agent.requiredString("deliveryStatus"))
        assertTrue(guest.requiredLong("id") < agent.requiredLong("id"))
    }

    private fun assertTicket(
        ticket: JsonObject,
        ticketId: Long,
        clubId: Long,
    ) {
        assertEquals(setOf("id", "clubId", "topic", "status", "createdAt", "updatedAt"), ticket.keys)
        assertEquals(ticketId, ticket.requiredLong("id"))
        assertEquals(clubId, ticket.requiredLong("clubId"))
        assertEquals(TICKET_TOPIC, ticket.requiredString("topic"))
        assertEquals("in_progress", ticket.requiredString("status"))
    }

    protected fun assertDurableContract(
        state: DurableSupportState,
        guestUserId: Long,
        staffUserId: Long,
        clubId: Long,
        ticketId: Long,
        replyMessageId: Long,
    ) {
        assertEquals(guestUserId, state.guestUser.id)
        assertEquals(staffUserId, state.staffUser.id)
        assertEquals(1L, state.guestUserRowCount)
        assertEquals(1L, state.staffUserRowCount)
        assertEquals(listOf("support.reply", "support.view"), state.assignment.permissions)
        assertEquals("MANAGER", state.assignment.roleCode)
        assertEquals("CLUB", state.assignment.scopeType)
        assertEquals(clubId, state.assignment.scopeClubId)
        assertEquals(ticketId, state.ticket.id)
        assertEquals(clubId, state.ticket.clubId)
        assertEquals(guestUserId, state.ticket.userId)
        assertEquals("in_progress", state.ticket.status)
        assertEquals(staffUserId, state.ticket.lastAgentId)
        assertEquals(1L, state.allTicketCount)
        assertEquals(1L, state.scopedTicketCount)
        assertEquals(2L, state.scopedMessageCount)
        assertEquals(1L, state.scopedDeliveryCount)
        assertEquals(2, state.messages.size)
        assertEquals("guest", state.messages[0].senderType)
        assertEquals(INITIAL_QUESTION, state.messages[0].text)
        assertEquals(null, state.messages[0].attachments)
        assertEquals("agent", state.messages[1].senderType)
        assertEquals(STAFF_REPLY, state.messages[1].text)
        assertEquals(null, state.messages[1].attachments)
        assertEquals(replyMessageId, state.messages[1].id)
        assertTrue(state.messages[0].id < state.messages[1].id)
        assertEquals(ticketId, state.delivery.ticketId)
        assertEquals(replyMessageId, state.delivery.replyMessageId)
        assertEquals(guestUserId, state.delivery.recipientUserId)
        assertEquals(staffUserId, state.delivery.actingStaffUserId)
        assertEquals("MANAGER", state.delivery.actingRole)
        assertEquals("delivered", state.delivery.status)
        assertEquals(null, state.delivery.failureCode)
        assertNotNull(state.delivery.completedAt)

        assertEquals(
            mapOf(
                "SUPPORT_REPLY" to 1,
                "SUPPORT_STATUS_CHANGE" to 1,
                "SUPPORT_DELIVERY_RESULT" to 1,
            ),
            state.audits.groupingBy(DurableAuditRow::action).eachCount(),
        )
        assertEquals(
            state.audits.size,
            state.audits
                .map(DurableAuditRow::id)
                .distinct()
                .size,
        )
        assertEquals(
            state.audits.size,
            state.audits
                .map(DurableAuditRow::fingerprint)
                .distinct()
                .size,
        )
        state.audits.forEach { audit ->
            assertEquals(clubId, audit.clubId)
            assertEquals(staffUserId, audit.actorUserId)
            assertEquals("MANAGER", audit.actorRole)
            assertEquals("SUPPORT_TICKET", audit.entityType)
            assertEquals(ticketId, audit.entityId)
            assertFalse(audit.metadataJson.contains(INITIAL_QUESTION))
            assertFalse(audit.metadataJson.contains(STAFF_REPLY))
            assertTrue(audit.fingerprint.isNotBlank())
        }
        val replyAudit = state.audits.single { it.action == "SUPPORT_REPLY" }
        val replyMetadata = json.parseToJsonElement(replyAudit.metadataJson).jsonObject
        assertEquals(setOf("message_id"), replyMetadata.keys)
        assertEquals(replyMessageId, replyMetadata.requiredLong("message_id"))
        val statusAudit = state.audits.single { it.action == "SUPPORT_STATUS_CHANGE" }
        val statusMetadata = json.parseToJsonElement(statusAudit.metadataJson).jsonObject
        assertEquals(setOf("old_status", "new_status"), statusMetadata.keys)
        assertEquals("new", statusMetadata.requiredString("old_status"))
        assertEquals("in_progress", statusMetadata.requiredString("new_status"))
        val deliveryAudit = state.audits.single { it.action == "SUPPORT_DELIVERY_RESULT" }
        val deliveryMetadata = json.parseToJsonElement(deliveryAudit.metadataJson).jsonObject
        assertEquals(setOf("result", "reply_message_id"), deliveryMetadata.keys)
        assertEquals("delivered", deliveryMetadata.requiredString("result"))
        assertEquals(replyMessageId, deliveryMetadata.requiredLong("reply_message_id"))
        assertEquals(
            "SUPPORT_TICKET:SUPPORT_DELIVERY_RESULT:${state.delivery.id}",
            deliveryAudit.fingerprint,
        )
    }

    protected fun assertSafeProcessLogs(
        processA: SupportChildProcess,
        processB: SupportChildProcess,
        generatedInitData: List<String>,
    ) {
        val captured =
            listOf(
                Files.readString(processA.stdoutPath),
                Files.readString(processA.stderrPath),
                Files.readString(processB.stdoutPath),
                Files.readString(processB.stderrPath),
            ).joinToString("\n")
        sensitiveCanaries(generatedInitData).forEach { (label, value) ->
            assertFalse(captured.contains(value), "$label was present in child logs")
        }
        assertFalse(captured.contains("PSQLException"), "PostgreSQL exception details were present")
        assertFalse(captured.contains("SQLException"), "SQL exception details were present")
        assertFalse(captured.contains("Migrations failed"), "migration failure appeared during successful proof")
    }

    protected fun safeProcessDiagnostics(
        processA: SupportChildProcess?,
        processB: SupportChildProcess?,
        generatedInitData: List<String>,
    ): String {
        val raw = listOfNotNull(processA?.boundedLogTail(), processB?.boundedLogTail()).joinToString("\n")
        val matchedLabels =
            sensitiveCanaries(generatedInitData)
                .filter { (_, value) -> raw.contains(value) }
                .map { (label, _) -> label }
        val redacted =
            sensitiveCanaries(generatedInitData).fold(raw) { diagnostics, (label, value) ->
                diagnostics.replace(value, "<redacted:$label>")
            }
        return buildString {
            if (matchedLabels.isNotEmpty()) {
                appendLine("Sensitive canaries redacted: ${matchedLabels.joinToString(",")}")
            }
            append(redacted)
        }
    }

    private fun sensitiveCanaries(generatedInitData: List<String>): List<Pair<String, String>> =
        buildList {
            add("raw-bot-token" to BOT_TOKEN)
            add("raw-webhook-secret" to WEBHOOK_SECRET)
            add("raw-database-password" to DATABASE_PASSWORD)
            add("initial-support-body" to INITIAL_QUESTION)
            add("staff-reply-body" to STAFF_REPLY)
            generatedInitData.forEachIndexed { index, initData ->
                add("raw-init-data-$index" to initData)
            }
        }

    protected fun processHandleAlive(pid: Long): Boolean =
        ProcessHandle
            .of(pid)
            .map(ProcessHandle::isAlive)
            .orElse(false)

    protected data class ProofSummary(
        val processAPid: Long,
        val processAStartedAt: Instant,
        val processAStoppedAt: Instant,
        val processAExit: Int,
        val processBPid: Long,
        val processBStartedAt: Instant,
        val processBReadyAt: Instant,
        val processBStoppedAt: Instant,
        val processBExit: Int,
        val containerId: String,
        val databaseIdentity: DatabaseIdentity,
        val fakeTelegramPort: Int,
        val guestUserId: Long,
        val staffUserId: Long,
        val clubId: Long,
        val ticketId: Long,
        val messageIds: List<Long>,
        val deliveryId: Long,
        val deliveryStatus: String,
        val auditRows: List<DurableAuditRow>,
        val supportTelegramAttemptCount: Int,
    ) {
        fun render(): String =
            buildString {
                append("PSL_AC_29_PROCESS_PROOF ")
                append("processA.pid=$processAPid processA.started=$processAStartedAt ")
                append("processA.stopped=$processAStoppedAt processA.exit=$processAExit ")
                append("processB.pid=$processBPid processB.started=$processBStartedAt ")
                append("processB.ready=$processBReadyAt processB.stopped=$processBStoppedAt ")
                append("processB.exit=$processBExit container.id=$containerId ")
                append("database.name=${databaseIdentity.databaseName} database.oid=${databaseIdentity.databaseOid} ")
                append("postgres.started=${databaseIdentity.postmasterStartedAt} fakeTelegram.port=$fakeTelegramPort ")
                append("guestUser.id=$guestUserId staffUser.id=$staffUserId club.id=$clubId ticket.id=$ticketId ")
                append("message.ids=${messageIds.joinToString(",")} delivery.id=$deliveryId ")
                append("delivery.status=$deliveryStatus supportTelegram.attempts=$supportTelegramAttemptCount ")
                append(
                    "audits=" +
                        auditRows.joinToString(",") { audit ->
                            "${audit.id}:${audit.action}:${audit.fingerprint}"
                        },
                )
            }
    }
}

private fun JsonObject.requiredLong(key: String): Long = getValue(key).jsonPrimitive.long

private fun JsonObject.requiredString(key: String): String =
    requireNotNull(getValue(key).jsonPrimitive.contentOrNull) { "JSON field $key was null" }
