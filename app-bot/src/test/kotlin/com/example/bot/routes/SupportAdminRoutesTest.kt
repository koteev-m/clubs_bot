package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.audit.AuditLogRepository
import com.example.bot.data.audit.AuditLogRepositoryImpl
import com.example.bot.data.support.SupportRepository
import com.example.bot.data.support.SupportServiceImpl
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.support.SupportService
import com.example.bot.support.SupportServiceResult
import com.example.bot.support.TicketTopic
import com.example.bot.testing.createInitData
import com.example.bot.testing.withInitData
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.response.BaseResponse
import io.mockk.mockk
import io.ktor.client.request.get
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
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SupportAdminRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `admin list tickets ok`() = withSupportAdminApp { context ->
        val adminTelegramId = 201L
        val adminUserId = insertUser(context.database, context.userRepository, adminTelegramId, "admin")
        val clubId = insertClub(context.database, "Admin Club")
        context.userRoleRepository.setRoles(adminUserId, setOf(Role.CLUB_ADMIN), clubIds = setOf(clubId))

        val ownerUserId = insertUser(context.database, context.userRepository, 202L, "guest")
        val ticketId = createTicket(context, clubId, ownerUserId)

        val response =
            client.get("/api/support/tickets?clubId=$clubId") {
                withInitData(createInitData(userId = adminTelegramId))
            }

        assertEquals(HttpStatusCode.OK, response.status)
        response.assertNoStoreHeaders()
        val items = json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertTrue(items.any { it.jsonObject["id"]!!.jsonPrimitive.long == ticketId })
    }

    @Test
    fun `guest without role cannot list tickets`() = withSupportAdminApp { context ->
        val telegramId = 301L
        insertUser(context.database, context.userRepository, telegramId, "guest")
        val clubId = insertClub(context.database, "Guest Club")

        val response =
            client.get("/api/support/tickets?clubId=$clubId") {
                withInitData(createInitData(userId = telegramId))
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        response.assertNoStoreHeaders()
        assertEquals("support_ticket_forbidden", response.errorCode())
    }

    @Test
    fun `assign status and reply ok for admin`() = withSupportAdminApp { context ->
        val adminTelegramId = 401L
        val adminUserId = insertUser(context.database, context.userRepository, adminTelegramId, "admin")
        val clubId = insertClub(context.database, "Support Club")
        context.userRoleRepository.setRoles(adminUserId, setOf(Role.OWNER), clubIds = emptySet())

        val ownerUserId = insertUser(context.database, context.userRepository, 402L, "guest")
        val ticketId = createTicket(context, clubId, ownerUserId)

        val assignResponse =
            client.post("/api/support/tickets/$ticketId/assign") {
                withInitData(createInitData(userId = adminTelegramId))
            }

        assertEquals(HttpStatusCode.OK, assignResponse.status)
        assignResponse.assertNoStoreHeaders()
        val assignPayload = json.parseToJsonElement(assignResponse.bodyAsText()).jsonObject
        assertEquals(ticketId, assignPayload["id"]!!.jsonPrimitive.long)
        assertEquals(clubId, assignPayload["clubId"]!!.jsonPrimitive.long)

        val statusResponse =
            client.post("/api/support/tickets/$ticketId/status") {
                withInitData(createInitData(userId = adminTelegramId))
                contentType(ContentType.Application.Json)
                setBody("""{"status":"answered"}""")
            }

        assertEquals(HttpStatusCode.OK, statusResponse.status)
        statusResponse.assertNoStoreHeaders()
        val statusPayload = json.parseToJsonElement(statusResponse.bodyAsText()).jsonObject
        assertEquals("answered", statusPayload["status"]!!.jsonPrimitive.content)

        val replyResponse =
            client.post("/api/support/tickets/$ticketId/reply") {
                withInitData(createInitData(userId = adminTelegramId))
                contentType(ContentType.Application.Json)
                setBody("""{"text":"Reply","attachments":"[]"}""")
            }

        assertEquals(HttpStatusCode.OK, replyResponse.status)
        replyResponse.assertNoStoreHeaders()
        val replyPayload = json.parseToJsonElement(replyResponse.bodyAsText()).jsonObject
        assertEquals(ticketId, replyPayload["ticketId"]!!.jsonPrimitive.long)
        assertEquals(clubId, replyPayload["clubId"]!!.jsonPrimitive.long)
        assertEquals(ownerUserId, replyPayload["ownerUserId"]!!.jsonPrimitive.long)
        assertEquals("answered", replyPayload["ticketStatus"]!!.jsonPrimitive.content)
        assertNotNull(replyPayload["replyMessageId"]?.jsonPrimitive?.long)
        assertTrue(replyPayload["replyCreatedAt"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `admin status invalid json returns 400 and no-store headers`() = withSupportAdminApp { context ->
        val adminTelegramId = 451L
        val adminUserId = insertUser(context.database, context.userRepository, adminTelegramId, "admin")
        val clubId = insertClub(context.database, "Invalid Json Club")
        context.userRoleRepository.setRoles(adminUserId, setOf(Role.OWNER), clubIds = emptySet())

        val ownerUserId = insertUser(context.database, context.userRepository, 452L, "guest")
        val ticketId = createTicket(context, clubId, ownerUserId)

        val response =
            client.post("/api/support/tickets/$ticketId/status") {
                withInitData(createInitData(userId = adminTelegramId))
                contentType(ContentType.Application.Json)
                setBody("{")
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        response.assertNoStoreHeaders()
        assertEquals("invalid_json", response.errorCode())
    }

    @Test
    fun `admin endpoints without init data return 401 and no-store headers`() = withSupportAdminApp {
        val listResponse = client.get("/api/support/tickets?clubId=1")
        assertEquals(HttpStatusCode.Unauthorized, listResponse.status)
        listResponse.assertNoStoreHeaders()
        assertEquals("unauthorized", listResponse.errorCode())

        val assignResponse = client.post("/api/support/tickets/1/assign")
        assertEquals(HttpStatusCode.Unauthorized, assignResponse.status)
        assignResponse.assertNoStoreHeaders()
        assertEquals("unauthorized", assignResponse.errorCode())

        val statusResponse =
            client.post("/api/support/tickets/1/status") {
                contentType(ContentType.Application.Json)
                setBody("""{"status":"answered"}""")
            }
        assertEquals(HttpStatusCode.Unauthorized, statusResponse.status)
        statusResponse.assertNoStoreHeaders()
        assertEquals("unauthorized", statusResponse.errorCode())

        val replyResponse =
            client.post("/api/support/tickets/1/reply") {
                contentType(ContentType.Application.Json)
                setBody("""{"text":"Reply"}""")
            }
        assertEquals(HttpStatusCode.Unauthorized, replyResponse.status)
        replyResponse.assertNoStoreHeaders()
        assertEquals("unauthorized", replyResponse.errorCode())
    }

    @Test
    fun `admin without club access cannot manage tickets`() = withSupportAdminApp { context ->
        val adminTelegramId = 501L
        val adminUserId = insertUser(context.database, context.userRepository, adminTelegramId, "admin")
        val clubId = insertClub(context.database, "Scoped Club")
        context.userRoleRepository.setRoles(adminUserId, setOf(Role.CLUB_ADMIN), clubIds = emptySet())

        val ownerUserId = insertUser(context.database, context.userRepository, 502L, "guest")
        val ticketId = createTicket(context, clubId, ownerUserId)

        val listResponse =
            client.get("/api/support/tickets?clubId=$clubId") {
                withInitData(createInitData(userId = adminTelegramId))
            }

        assertEquals(HttpStatusCode.Forbidden, listResponse.status)
        listResponse.assertNoStoreHeaders()
        assertEquals("support_ticket_forbidden", listResponse.errorCode())

        val assignResponse =
            client.post("/api/support/tickets/$ticketId/assign") {
                withInitData(createInitData(userId = adminTelegramId))
            }

        assertEquals(HttpStatusCode.Forbidden, assignResponse.status)
        assignResponse.assertNoStoreHeaders()
        assertEquals("support_ticket_forbidden", assignResponse.errorCode())

        val statusResponse =
            client.post("/api/support/tickets/$ticketId/status") {
                withInitData(createInitData(userId = adminTelegramId))
                contentType(ContentType.Application.Json)
                setBody("""{"status":"closed"}""")
            }

        assertEquals(HttpStatusCode.Forbidden, statusResponse.status)
        statusResponse.assertNoStoreHeaders()
        assertEquals("support_ticket_forbidden", statusResponse.errorCode())

        val replyResponse =
            client.post("/api/support/tickets/$ticketId/reply") {
                withInitData(createInitData(userId = adminTelegramId))
                contentType(ContentType.Application.Json)
                setBody("""{"text":"Reply"}""")
            }

        assertEquals(HttpStatusCode.Forbidden, replyResponse.status)
        replyResponse.assertNoStoreHeaders()
        assertEquals("support_ticket_forbidden", replyResponse.errorCode())
    }

    @Test
    fun `invalid status filter returns 400`() = withSupportAdminApp { context ->
        val adminTelegramId = 601L
        val adminUserId = insertUser(context.database, context.userRepository, adminTelegramId, "admin")
        val clubId = insertClub(context.database, "Filter Club")
        context.userRoleRepository.setRoles(adminUserId, setOf(Role.OWNER), clubIds = emptySet())

        val response =
            client.get("/api/support/tickets?clubId=$clubId&status=bad") {
                withInitData(createInitData(userId = adminTelegramId))
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        response.assertNoStoreHeaders()
        assertEquals("validation_error", response.errorCode())
    }

    @Test
    fun `admin without internal user returns forbidden for support tickets`() = withSupportAdminApp { context ->
        val clubId = insertClub(context.database, "Missing User Club")

        val response =
            client.get("/api/support/tickets?clubId=$clubId") {
                withInitData(createInitData(userId = 700L))
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        response.assertNoStoreHeaders()
        assertEquals("forbidden", response.errorCode())
    }

    private data class DbSetup(
        val dataSource: JdbcDataSource,
        val database: Database,
    )

    private data class TestContext(
        val database: Database,
        val supportService: SupportService,
        val userRepository: TestUserRepository,
        val userRoleRepository: TestUserRoleRepository,
    )

    private fun withSupportAdminApp(block: suspend ApplicationTestBuilder.(TestContext) -> Unit) =
        testApplication {
            val setup = prepareDatabase()
            val supportRepository = SupportRepository(setup.database)
            val supportService = SupportServiceImpl(supportRepository)
            val userRepository = TestUserRepository()
            val userRoleRepository = TestUserRoleRepository()
            val telegramSender = RecordingTelegramSender()
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    this.userRepository = userRepository
                    this.userRoleRepository = userRoleRepository
                    this.auditLogRepository = AuditLogRepositoryImpl(setup.database)
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
                    sendTelegram = telegramSender::send,
                    botTokenProvider = { TEST_BOT_TOKEN },
                )
            }
            block(TestContext(setup.database, supportService, userRepository, userRoleRepository))
        }

    private fun prepareDatabase(): DbSetup {
        val dbName = "support_admin_routes_${UUID.randomUUID()}"
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
        val database = Database.connect(dataSource)
        return DbSetup(dataSource = dataSource, database = database)
    }

    private suspend fun createTicket(
        context: TestContext,
        clubId: Long,
        userId: Long,
    ): Long {
        val result =
            context.supportService.createTicket(
                clubId = clubId,
                userId = userId,
                bookingId = null,
                listEntryId = null,
                topic = TicketTopic.OTHER,
                text = "Need help",
                attachments = null,
            )
        assertTrue(result is SupportServiceResult.Success)
        return result.value.ticket.id
    }

    private fun insertUser(
        database: Database,
        userRepository: TestUserRepository,
        telegramUserId: Long,
        username: String,
    ): Long {
        val id =
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
        userRepository.register(id, telegramUserId, username)
        return id
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

    private object UsersTable : Table("users") {
        val id = long("id").autoIncrement()
        val telegramUserId = long("telegram_user_id")
        val username = text("username").nullable()
        val displayName = text("display_name")
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

    private suspend fun HttpResponse.errorCode(): String {
        val raw = bodyAsText()
        val parsed = runCatching { Json.parseToJsonElement(raw).jsonObject.errorCodeOrNull() }.getOrNull()
        val extracted = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.getOrNull(1)
        return parsed ?: extracted ?: raw
    }

    private fun JsonObject.errorCodeOrNull(): String? {
        val code = this["code"] as? JsonPrimitive
        if (code != null) {
            return code.content
        }
        val error = this["error"]
        val nestedCode = ((error as? JsonObject)?.get("code") as? JsonPrimitive)?.content
        val legacyCode = (error as? JsonPrimitive)?.content
        return nestedCode ?: legacyCode
    }

    private fun HttpResponse.assertNoStoreHeaders() {
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        assertEquals("X-Telegram-Init-Data", headers[HttpHeaders.Vary])
    }

    private class TestUserRepository : UserRepository {
        private val usersByTelegramId = mutableMapOf<Long, User>()
        private val usersById = mutableMapOf<Long, User>()

        fun register(
            id: Long,
            telegramUserId: Long,
            username: String,
        ) {
            val user = User(id = id, telegramId = telegramUserId, username = username)
            usersByTelegramId[telegramUserId] = user
            usersById[id] = user
        }

        override suspend fun getByTelegramId(id: Long): User? = usersByTelegramId[id]

        override suspend fun getById(id: Long): User? = usersById[id]
    }

    private class TestUserRoleRepository : UserRoleRepository {
        private val rolesByUser = mutableMapOf<Long, Set<Role>>()
        private val clubsByUser = mutableMapOf<Long, Set<Long>>()

        fun setRoles(
            userId: Long,
            roles: Set<Role>,
            clubIds: Set<Long>,
        ) {
            rolesByUser[userId] = roles
            clubsByUser[userId] = clubIds
        }

        override suspend fun listRoles(userId: Long): Set<Role> = rolesByUser[userId].orEmpty()

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = clubsByUser[userId].orEmpty()
    }

    private class RecordingTelegramSender {
        val requests = mutableListOf<BaseRequest<*, *>>()

        suspend fun send(request: BaseRequest<*, *>): BaseResponse {
            requests += request
            return mockk()
        }
    }
}
