package com.example.bot.routes

import com.example.bot.data.audit.AuditLogRepositoryImpl
import com.example.bot.data.security.ExposedUserRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.data.support.SupportRepository
import com.example.bot.data.support.SupportServiceImpl
import com.example.bot.opschat.OpsDomainNotification
import com.example.bot.opschat.OpsNotificationPublisher
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.support.SupportService
import com.example.bot.support.SupportServiceError
import com.example.bot.support.SupportServiceResult
import com.example.bot.support.TicketTopic
import com.example.bot.testing.createInitData
import com.example.bot.testing.withInitData
import com.example.bot.webapp.TEST_BOT_TOKEN
import io.ktor.client.request.post
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SupportGuestTicketCreationRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `create ticket persistence failure returns generic 500 without details or notification`() {
        val rawDetail = "SENTINEL tickets_status_check INSERT INTO tickets"
        val failingSupportService = mockk<SupportService>()
        coEvery {
            failingSupportService.createTicket(
                clubId = any(),
                userId = any(),
                bookingId = null,
                listEntryId = null,
                topic = any(),
                text = any(),
                attachments = null,
            )
        } returns SupportServiceResult.Failure(SupportServiceError.PersistenceFailure)

        withSupportApp(supportServiceFactory = { failingSupportService }) { context ->
            val telegramId = 102L
            insertUser(context.database, telegramId, "guest")
            val clubId = insertClub(context.database, "Test Club")

            val response = createTicketRequest(telegramId = telegramId, clubId = clubId)

            response.assertGenericInternalError(rawDetail)
            assertTrue(context.opsPublisher.notifications.isEmpty())
        }
    }

    @Test
    fun `create ticket unexpected sql exception uses generic error boundary`() {
        val rawDetail = "SENTINEL ticket_messages_text_check INSERT INTO ticket_messages"
        val failingSupportService = mockk<SupportService>()
        coEvery {
            failingSupportService.createTicket(
                clubId = any(),
                userId = any(),
                bookingId = null,
                listEntryId = null,
                topic = any(),
                text = any(),
                attachments = null,
            )
        } throws SQLException(rawDetail, "23514")

        withSupportApp(supportServiceFactory = { failingSupportService }) { context ->
            val telegramId = 103L
            insertUser(context.database, telegramId, "guest")
            val clubId = insertClub(context.database, "Test Club")

            val response = createTicketRequest(telegramId = telegramId, clubId = clubId)

            response.assertGenericInternalError(rawDetail)
            assertTrue(context.opsPublisher.notifications.isEmpty())
        }
    }

    @Test
    fun `create ticket request deserialization cancellation is rethrown without side effects`() {
        val cancellation = CancellationException("cancelled during create-ticket deserialization")
        val supportService = mockk<SupportService>(relaxed = true)
        val opsPublisher = mockk<OpsNotificationPublisher>(relaxed = true)
        var invalidJsonResponseCreated = false
        var successResponseCreated = false

        val thrown =
            assertFailsWith<CancellationException> {
                runBlocking {
                    val request = receiveCreateTicketRequestOrNull { throw cancellation }
                    if (request == null) {
                        invalidJsonResponseCreated = true
                        return@runBlocking
                    }
                    supportService.createTicket(
                        clubId = 1L,
                        userId = 2L,
                        bookingId = null,
                        listEntryId = null,
                        topic = TicketTopic.BOOKING,
                        text = "Need help",
                        attachments = null,
                    )
                    opsPublisher.enqueue(mockk())
                    successResponseCreated = true
                }
            }

        assertSame(cancellation, thrown)
        coVerify(exactly = 0) {
            supportService.createTicket(
                clubId = any(),
                userId = any(),
                bookingId = null,
                listEntryId = null,
                topic = any(),
                text = any(),
                attachments = null,
            )
        }
        verify(exactly = 0) { opsPublisher.enqueue(any()) }
        assertFalse(invalidJsonResponseCreated)
        assertFalse(successResponseCreated)
    }

    private data class TestContext(
        val database: Database,
        val opsPublisher: RecordingOpsPublisher,
    )

    private fun withSupportApp(
        supportServiceFactory: (Database) -> SupportService = { database ->
            SupportServiceImpl(SupportRepository(database))
        },
        block: suspend ApplicationTestBuilder.(TestContext) -> Unit,
    ) = testApplication {
        val database = prepareDatabase()
        val supportService = supportServiceFactory(database)
        val userRepository = ExposedUserRepository(database)
        val opsPublisher = RecordingOpsPublisher()
        application {
            install(ContentNegotiation) { json() }
            installJsonErrorPages()
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
                supportService = supportService,
                userRepository = userRepository,
                sendTelegram = { mockk() },
                opsPublisher = opsPublisher,
                botTokenProvider = { TEST_BOT_TOKEN },
            )
        }
        block(TestContext(database, opsPublisher))
    }

    private suspend fun ApplicationTestBuilder.createTicketRequest(
        telegramId: Long,
        clubId: Long,
    ): HttpResponse =
        client.post("/api/support/tickets") {
            withInitData(createInitData(userId = telegramId))
            contentType(ContentType.Application.Json)
            setBody(
                """{
                "clubId":$clubId,
                "topic":"booking",
                "text":"Need help"
            }""",
            )
        }

    private suspend fun HttpResponse.assertGenericInternalError(rawDetail: String) {
        assertEquals(HttpStatusCode.InternalServerError, status)
        assertNoStoreHeaders()
        val body = bodyAsText()
        val payload = json.parseToJsonElement(body).jsonObject
        assertEquals("internal_error", payload["code"]!!.jsonPrimitive.content)
        assertEquals(500L, payload["status"]!!.jsonPrimitive.long)
        assertFalse(body.contains(rawDetail))
        assertFalse(body.contains("SENTINEL"))
        assertFalse(body.contains("INSERT INTO", ignoreCase = true))
        assertFalse(body.contains("constraint", ignoreCase = true))
    }

    private fun prepareDatabase(): Database {
        val dbName = "support_ticket_creation_routes_${UUID.randomUUID()}"
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

    private fun HttpResponse.assertNoStoreHeaders() {
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        assertEquals("X-Telegram-Init-Data", headers[HttpHeaders.Vary])
    }

    private class StubUserRoleRepository : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = emptySet()

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = emptySet()
    }

    private class RecordingOpsPublisher : OpsNotificationPublisher {
        val notifications = mutableListOf<OpsDomainNotification>()

        override fun enqueue(notification: OpsDomainNotification) {
            notifications += notification
        }
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
