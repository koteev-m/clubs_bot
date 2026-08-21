package com.example.bot.routes

import com.example.bot.data.audit.AuditLogRepositoryImpl
import com.example.bot.data.clubs.ClubsDbRepository
import com.example.bot.data.security.ExposedUserRepository
import com.example.bot.data.security.ExposedUserRolePermissionRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.data.support.SupportRepository
import com.example.bot.data.support.SupportServiceImpl
import com.example.bot.data.support.TicketMessagesTable
import com.example.bot.data.support.TicketsTable
import com.example.bot.opschat.NoopOpsNotificationPublisher
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.support.SupportService
import com.example.bot.support.SupportServiceError
import com.example.bot.support.SupportServiceResult
import com.example.bot.support.TicketSenderType
import com.example.bot.support.TicketStatus
import com.example.bot.support.TicketTopic
import com.example.bot.support.TicketWithMessage
import com.example.bot.testing.createInitData
import com.example.bot.testing.withInitData
import com.example.bot.webapp.TEST_BOT_TOKEN
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respondText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

private const val CANCELLATION_PROPAGATED_HEADER = "X-Cancellation-Propagated"

class SupportGuestOwnershipRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `list uses authenticated owner and excludes foreign metadata previews and spoofed owner inputs`() =
        withSupportApp { context ->
            val userATelegramId = 810_001L
            val userBTelegramId = 810_002L
            val userAId = insertUser(context.database, userATelegramId, "owner-a")
            val userBId = insertUser(context.database, userBTelegramId, "owner-b")
            val userAClubId = insertClub(context.database, "Owner A Club")
            val userBClubId = insertClub(context.database, "FOREIGN CLUB SENTINEL")

            val older =
                createTicket(
                    service = context.realSupportService,
                    clubId = userAClubId,
                    userId = userAId,
                    topic = TicketTopic.ADDRESS,
                    text = "OWNER_A_OLDER_PREVIEW",
                )
            val newer =
                createTicket(
                    service = context.realSupportService,
                    clubId = userAClubId,
                    userId = userAId,
                    topic = TicketTopic.BOOKING,
                    text = "OWNER_A_NEWER_PREVIEW",
                )
            val foreign =
                createTicket(
                    service = context.realSupportService,
                    clubId = userBClubId,
                    userId = userBId,
                    topic = TicketTopic.LOST_FOUND,
                    text = "FOREIGN_INITIAL_SENTINEL",
                    attachments = "[\"FOREIGN_ATTACHMENT_SENTINEL\"]",
                )
            insertMessage(
                database = context.database,
                ticketId = foreign.ticket.id,
                senderType = TicketSenderType.SYSTEM,
                text = "FOREIGN_LAST_PREVIEW_SENTINEL",
                attachments = "[\"FOREIGN_LAST_ATTACHMENT_SENTINEL\"]",
                createdAt = Instant.parse("2040-08-21T09:00:00Z"),
            )
            seedTicketMetadata(
                database = context.database,
                ticketId = older.ticket.id,
                status = TicketStatus.NEW,
                createdAt = Instant.parse("2026-08-21T08:00:00Z"),
                updatedAt = Instant.parse("2026-08-21T08:01:00Z"),
            )
            seedTicketMetadata(
                database = context.database,
                ticketId = newer.ticket.id,
                status = TicketStatus.NEW,
                createdAt = Instant.parse("2026-08-21T08:02:00Z"),
                updatedAt = Instant.parse("2026-08-21T08:03:00Z"),
            )
            seedTicketMetadata(
                database = context.database,
                ticketId = foreign.ticket.id,
                status = TicketStatus.CLOSED,
                createdAt = Instant.parse("2040-08-21T08:00:00Z"),
                updatedAt = Instant.parse("2040-08-21T09:00:00Z"),
            )

            val response =
                client.get(
                    "/api/support/tickets/my" +
                        "?userId=$userBId&telegramId=$userBTelegramId&clubId=$userBClubId" +
                        "&role=OWNER&scope=GLOBAL",
                ) {
                    withInitData(createInitData(userId = userATelegramId))
                    contentType(ContentType.Application.Json)
                    header("X-User-Id", userBId)
                    header("X-Telegram-User-Id", userBTelegramId)
                    header("X-Club-Id", userBClubId)
                    header("X-Role", "OWNER")
                    header("X-Scope", "GLOBAL")
                    setBody(
                        """{"userId":$userBId,"telegramId":$userBTelegramId,"clubId":$userBClubId,"role":"OWNER"}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            response.assertNoStoreHeaders()
            val body = response.bodyAsText()
            val items = json.parseToJsonElement(body).jsonArray
            assertEquals(listOf(newer.ticket.id, older.ticket.id), items.map { it.jsonObject.requiredLong("id") })
            assertEquals(setOf(userAClubId), items.map { it.jsonObject.requiredLong("clubId") }.toSet())
            assertEquals(
                listOf("OWNER_A_NEWER_PREVIEW", "OWNER_A_OLDER_PREVIEW"),
                items.map { it.jsonObject.requiredString("lastMessagePreview") },
            )
            assertFalse(items.any { it.jsonObject.requiredLong("id") == foreign.ticket.id })
            assertFalse(items.any { it.jsonObject.requiredLong("clubId") == userBClubId })
            assertFalse(body.contains("lost_found"))
            assertFalse(body.contains("closed"))
            assertFalse(body.contains("2040-08-21"))
            assertFalse(body.contains("FOREIGN_"))
            assertFalse(body.contains("system"))
        }

    @Test
    fun `owner opens complete ordered thread with exact minimal response fields`() =
        withSupportApp { context ->
            val telegramId = 820_001L
            val userId = insertUser(context.database, telegramId, "thread-owner")
            val clubId = insertClub(context.database, "Thread Club")
            val created =
                createTicket(
                    service = context.realSupportService,
                    clubId = clubId,
                    userId = userId,
                    topic = TicketTopic.COMPLAINT,
                    text = "OWNER_INITIAL_MESSAGE",
                    attachments = "[\"initial-photo.jpg\"]",
                )
            val initialAt = Instant.parse("2026-08-21T10:00:00Z")
            val tiedAt = Instant.parse("2026-08-21T10:01:00Z")
            seedMessageCreatedAt(context.database, created.initialMessage.id, initialAt)
            seedTicketMetadata(
                database = context.database,
                ticketId = created.ticket.id,
                status = TicketStatus.NEW,
                createdAt = initialAt,
                updatedAt = tiedAt,
            )
            val agentMessageId =
                insertMessage(
                    database = context.database,
                    ticketId = created.ticket.id,
                    senderType = TicketSenderType.AGENT,
                    text = "OWNER_AGENT_MESSAGE",
                    attachments = "[\"agent-file.pdf\"]",
                    createdAt = tiedAt,
                )
            val guestMessageId =
                insertMessage(
                    database = context.database,
                    ticketId = created.ticket.id,
                    senderType = TicketSenderType.GUEST,
                    text = "OWNER_GUEST_FOLLOW_UP",
                    attachments = null,
                    createdAt = tiedAt,
                )

            val response = ownTicketRequest(telegramId, created.ticket.id)

            assertEquals(HttpStatusCode.OK, response.status)
            response.assertNoStoreHeaders()
            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(setOf("ticket", "messages"), payload.keys)
            val ticket = payload.getValue("ticket").jsonObject
            assertEquals(setOf("id", "clubId", "topic", "status", "createdAt", "updatedAt"), ticket.keys)
            assertEquals(created.ticket.id, ticket.requiredLong("id"))
            assertEquals(clubId, ticket.requiredLong("clubId"))
            assertEquals("complaint", ticket.requiredString("topic"))
            assertEquals("new", ticket.requiredString("status"))
            assertEquals(initialAt, Instant.parse(ticket.requiredString("createdAt")))
            assertEquals(tiedAt, Instant.parse(ticket.requiredString("updatedAt")))

            val messages = payload.getValue("messages").jsonArray.map { it.jsonObject }
            assertEquals(3, messages.size)
            messages.forEach { message ->
                assertEquals(setOf("id", "senderType", "text", "attachments", "createdAt"), message.keys)
            }
            assertEquals(
                listOf(created.initialMessage.id, agentMessageId, guestMessageId),
                messages.map { it.requiredLong("id") },
            )
            assertEquals(listOf("guest", "agent", "guest"), messages.map { it.requiredString("senderType") })
            assertEquals(
                listOf("OWNER_INITIAL_MESSAGE", "OWNER_AGENT_MESSAGE", "OWNER_GUEST_FOLLOW_UP"),
                messages.map { it.requiredString("text") },
            )
            assertEquals("[\"initial-photo.jpg\"]", messages[0].requiredString("attachments"))
            assertEquals("[\"agent-file.pdf\"]", messages[1].requiredString("attachments"))
            assertIs<JsonNull>(messages[2].getValue("attachments"))
            assertEquals(
                listOf(initialAt, tiedAt, tiedAt),
                messages.map {
                    Instant.parse(it.requiredString("createdAt"))
                },
            )
        }

    @Test
    fun `foreign and missing ticket reads are byte equivalent and contain no owner data`() =
        withSupportApp { context ->
            val ownerTelegramId = 830_001L
            val requesterTelegramId = 830_002L
            val ownerUserId = insertUser(context.database, ownerTelegramId, "private-owner")
            insertUser(context.database, requesterTelegramId, "other-guest")
            val clubId = insertClub(context.database, "PRIVATE CLUB SENTINEL")
            val ownerTicket =
                createTicket(
                    service = context.realSupportService,
                    clubId = clubId,
                    userId = ownerUserId,
                    topic = TicketTopic.DRESSCODE,
                    text = "PRIVATE_MESSAGE_SENTINEL",
                    attachments = "[\"PRIVATE_ATTACHMENT_SENTINEL\"]",
                )

            val foreignResponse = ownTicketRequest(requesterTelegramId, ownerTicket.ticket.id)
            val missingResponse = ownTicketRequest(requesterTelegramId, Long.MAX_VALUE)

            val foreignBody = foreignResponse.bodyAsText()
            val missingBody = missingResponse.bodyAsText()
            assertEquals(HttpStatusCode.NotFound, foreignResponse.status)
            assertEquals(HttpStatusCode.NotFound, missingResponse.status)
            foreignResponse.assertNoStoreHeaders()
            missingResponse.assertNoStoreHeaders()
            assertEquals("support_ticket_not_found", errorCode(foreignBody))
            assertEquals("support_ticket_not_found", errorCode(missingBody))
            assertEquals(missingBody, foreignBody)
            assertSamePublicErrorHeaders(foreignResponse, missingResponse)
            assertFalse(foreignBody.contains("PRIVATE_"))
            assertFalse(foreignBody.contains("dresscode"))
            assertFalse(foreignBody.contains(clubId.toString()))
            assertFalse(foreignBody.contains(ownerTicket.ticket.id.toString()))
        }

    @Test
    fun `unknown application identity is forbidden`() =
        withSupportApp {
            val response = ownTicketRequest(telegramId = 840_001L, ticketId = 1L)

            assertEquals(HttpStatusCode.Forbidden, response.status)
            response.assertNoStoreHeaders()
            assertEquals("forbidden", errorCode(response.bodyAsText()))
        }

    @Test
    fun `detail rejects non canonical positive decimal ticket ids`() =
        withSupportApp { context ->
            val telegramId = 850_001L
            insertUser(context.database, telegramId, "id-parser-owner")
            val invalidIds = listOf("+1", "01", "0", "-1", "%20", "1x", "9223372036854775808")

            invalidIds.forEach { rawId ->
                val response =
                    client.get("/api/support/tickets/my/$rawId") {
                        withInitData(createInitData(userId = telegramId))
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status, rawId)
                response.assertNoStoreHeaders()
                assertEquals("validation_error", errorCode(response.bodyAsText()), rawId)
            }
        }

    @Test
    fun `detail persistence failure returns generic internal error without database details`() {
        val rawDetail = "SENTINEL ticket_messages_ticket_id_fkey SELECT FROM ticket_messages"
        val failingService = mockk<SupportService>()
        coEvery { failingService.getMyTicket(any(), any()) } returns
            SupportServiceResult.Failure(SupportServiceError.PersistenceFailure)

        withSupportApp(supportServiceFactory = { failingService }) { context ->
            val telegramId = 860_001L
            insertUser(context.database, telegramId, "failure-owner")

            val response = ownTicketRequest(telegramId, 1L)

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            response.assertNoStoreHeaders()
            val body = response.bodyAsText()
            assertEquals("internal_error", errorCode(body))
            assertFalse(body.contains(rawDetail))
            assertFalse(body.contains("SENTINEL"))
            assertFalse(body.contains("ticket_messages_ticket_id_fkey"))
            assertFalse(body.contains("SELECT FROM", ignoreCase = true))
            assertFalse(body.contains("constraint", ignoreCase = true))
        }
    }

    @Test
    fun `detail service cancellation propagates to the application boundary`() {
        val cancellation = CancellationException("cancelled owned support thread read")
        val cancellingService = mockk<SupportService>()
        coEvery { cancellingService.getMyTicket(any(), any()) } throws cancellation

        withSupportApp(
            supportServiceFactory = { cancellingService },
            installCancellationMarker = true,
        ) { context ->
            val telegramId = 870_001L
            insertUser(context.database, telegramId, "cancellation-owner")

            val response = ownTicketRequest(telegramId = telegramId, ticketId = 1L)

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("true", response.headers[CANCELLATION_PROPAGATED_HEADER])
            response.assertNoStoreHeaders()
        }
    }

    private data class TestContext(
        val database: Database,
        val realSupportService: SupportService,
    )

    private fun withSupportApp(
        supportServiceFactory: ((Database) -> SupportService)? = null,
        installCancellationMarker: Boolean = false,
        block: suspend ApplicationTestBuilder.(TestContext) -> Unit,
    ) = testApplication {
        val database = prepareDatabase()
        val realSupportService = SupportServiceImpl(SupportRepository(database))
        val routedSupportService = supportServiceFactory?.invoke(database) ?: realSupportService
        val userRepository = ExposedUserRepository(database)
        application {
            install(ContentNegotiation) { json() }
            if (installCancellationMarker) {
                install(StatusPages) {
                    exception<CancellationException> { call, _ ->
                        call.response.headers.append(CANCELLATION_PROPAGATED_HEADER, "true")
                        call.respondText("cancellation_propagated", status = HttpStatusCode.ServiceUnavailable)
                    }
                }
            } else {
                installJsonErrorPages()
            }
            install(RbacPlugin) {
                this.userRepository = userRepository
                this.userRoleRepository = StubUserRoleRepository()
                this.auditLogRepository = AuditLogRepositoryImpl(database)
                principalExtractor = { call ->
                    if (call.attributes.contains(MiniAppUserKey)) {
                        val principal = call.attributes[MiniAppUserKey]
                        TelegramPrincipal(principal.id, principal.username)
                    } else {
                        null
                    }
                }
            }
            supportRoutes(
                supportService = routedSupportService,
                staffSupportReadService = realSupportService,
                userRepository = userRepository,
                userRolePermissionRepository = ExposedUserRolePermissionRepository(database),
                clubsRepository = ClubsDbRepository(database),
                sendTelegram = { mockk() },
                opsPublisher = NoopOpsNotificationPublisher,
                botTokenProvider = { TEST_BOT_TOKEN },
            )
        }
        block(TestContext(database = database, realSupportService = realSupportService))
    }

    private suspend fun ApplicationTestBuilder.ownTicketRequest(
        telegramId: Long,
        ticketId: Long,
    ): HttpResponse =
        client.get("/api/support/tickets/my/$ticketId") {
            withInitData(createInitData(userId = telegramId))
        }

    private suspend fun createTicket(
        service: SupportService,
        clubId: Long,
        userId: Long,
        topic: TicketTopic,
        text: String,
        attachments: String? = null,
    ): TicketWithMessage {
        val result =
            service.createTicket(
                clubId = clubId,
                userId = userId,
                bookingId = null,
                listEntryId = null,
                topic = topic,
                text = text,
                attachments = attachments,
            )
        return assertIs<SupportServiceResult.Success<TicketWithMessage>>(result).value
    }

    private fun prepareDatabase(): Database {
        val dbName = "support_guest_ownership_routes_${UUID.randomUUID()}"
        val dataSource =
            JdbcDataSource().apply {
                setURL("jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1")
                user = "sa"
                password = ""
            }
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/common", "classpath:db/migration/h2")
            .load()
            .migrate()
        return Database.connect(dataSource)
    }

    private fun insertUser(
        database: Database,
        telegramUserId: Long,
        username: String,
    ): Long =
        transaction(database) {
            UsersTable
                .insert {
                    it[UsersTable.telegramUserId] = telegramUserId
                    it[UsersTable.username] = username
                    it[UsersTable.displayName] = username
                    it[UsersTable.phoneE164] = null
                }.resultedValues!!
                .single()[UsersTable.id]
        }

    private fun insertClub(
        database: Database,
        name: String,
    ): Long =
        transaction(database) {
            ClubsTable
                .insert {
                    it[ClubsTable.name] = name
                    it[ClubsTable.description] = null
                    it[ClubsTable.timezone] = "Europe/Moscow"
                    it[ClubsTable.adminChannelId] = null
                    it[ClubsTable.bookingsTopicId] = null
                    it[ClubsTable.checkinTopicId] = null
                    it[ClubsTable.qaTopicId] = null
                }.resultedValues!!
                .single()[ClubsTable.id]
        }

    private fun seedTicketMetadata(
        database: Database,
        ticketId: Long,
        status: TicketStatus,
        createdAt: Instant,
        updatedAt: Instant,
    ) {
        transaction(database) {
            assertEquals(
                1,
                TicketsTable.update({ TicketsTable.id eq ticketId }) {
                    it[TicketsTable.status] = status.wire
                    it[TicketsTable.createdAt] = createdAt.atOffset(ZoneOffset.UTC)
                    it[TicketsTable.updatedAt] = updatedAt.atOffset(ZoneOffset.UTC)
                },
            )
        }
    }

    private fun seedMessageCreatedAt(
        database: Database,
        messageId: Long,
        createdAt: Instant,
    ) {
        transaction(database) {
            assertEquals(
                1,
                TicketMessagesTable.update({ TicketMessagesTable.id eq messageId }) {
                    it[TicketMessagesTable.createdAt] = createdAt.atOffset(ZoneOffset.UTC)
                },
            )
        }
    }

    private fun insertMessage(
        database: Database,
        ticketId: Long,
        senderType: TicketSenderType,
        text: String,
        attachments: String?,
        createdAt: Instant,
    ): Long =
        transaction(database) {
            TicketMessagesTable.insert {
                it[TicketMessagesTable.ticketId] = ticketId
                it[TicketMessagesTable.senderType] = senderType.wire
                it[TicketMessagesTable.text] = text
                it[TicketMessagesTable.attachments] = attachments
                it[TicketMessagesTable.createdAt] = createdAt.atOffset(ZoneOffset.UTC)
            }[TicketMessagesTable.id]
        }

    private fun JsonObject.requiredLong(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.requiredString(key: String): String = getValue(key).jsonPrimitive.content

    private fun errorCode(body: String): String = json.parseToJsonElement(body).jsonObject.requiredString("code")

    private fun HttpResponse.assertNoStoreHeaders() {
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        assertEquals("X-Telegram-Init-Data", headers[HttpHeaders.Vary])
    }

    private fun assertSamePublicErrorHeaders(
        first: HttpResponse,
        second: HttpResponse,
    ) {
        listOf(HttpHeaders.CacheControl, HttpHeaders.Vary, HttpHeaders.ContentType).forEach { name ->
            assertEquals(first.headers[name], second.headers[name], name)
        }
    }

    private class StubUserRoleRepository : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = emptySet()

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = emptySet()
    }

    private object UsersTable : Table("users") {
        val id = long("id").autoIncrement()
        val telegramUserId = long("telegram_user_id").nullable()
        val username = text("username").nullable()
        val displayName = text("display_name").nullable()
        val phoneE164 = text("phone_e164").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    private object ClubsTable : Table("clubs") {
        val id = long("id").autoIncrement()
        val name = text("name")
        val description = text("description").nullable()
        val timezone = text("timezone")
        val adminChannelId = long("admin_channel_id").nullable()
        val bookingsTopicId = integer("bookings_topic_id").nullable()
        val checkinTopicId = integer("checkin_topic_id").nullable()
        val qaTopicId = integer("qa_topic_id").nullable()
        override val primaryKey = PrimaryKey(id)
    }
}
