package com.example.bot.routes

import com.example.bot.data.security.ExposedUserRepository
import com.example.bot.data.support.SupportRepository
import com.example.bot.data.support.SupportServiceImpl
import com.example.bot.support.SupportService
import com.example.bot.support.TicketStatus
import com.example.bot.testing.createInitData
import com.example.bot.testing.withInitData
import com.example.bot.webapp.TEST_BOT_TOKEN
import io.ktor.client.request.post
import io.ktor.client.request.get
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

class SupportGuestRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `create ticket returns 201 and no-store headers`() = withSupportApp { context ->
        val telegramId = 101L
        insertUser(context.database, telegramId, "guest")
        val clubId = insertClub(context.database, "Test Club")

        val response =
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

        assertEquals(HttpStatusCode.Created, response.status)
        response.assertNoStoreHeaders()
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(payload["id"]?.jsonPrimitive?.long)
        assertEquals(clubId, payload["clubId"]!!.jsonPrimitive.long)
        assertEquals("booking", payload["topic"]!!.jsonPrimitive.content)
        assertEquals("opened", payload["status"]!!.jsonPrimitive.content)
        assertTrue(payload["updatedAt"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `list my tickets returns ticket and no-store headers`() = withSupportApp { context ->
        val telegramId = 202L
        insertUser(context.database, telegramId, "guest")
        val clubId = insertClub(context.database, "Test Club")

        val create =
            client.post("/api/support/tickets") {
                withInitData(createInitData(userId = telegramId))
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "clubId":$clubId,
                    "topic":"complaint",
                    "text":"Bad service"
                }""",
                )
            }
        val ticketId = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.long

        val response =
            client.get("/api/support/tickets/my") {
                withInitData(createInitData(userId = telegramId))
            }

        assertEquals(HttpStatusCode.OK, response.status)
        response.assertNoStoreHeaders()
        val items = json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertTrue(items.any { it.jsonObject["id"]!!.jsonPrimitive.long == ticketId })
    }

    @Test
    fun `add message returns 200 and no-store headers`() = withSupportApp { context ->
        val telegramId = 303L
        insertUser(context.database, telegramId, "guest")
        val clubId = insertClub(context.database, "Test Club")

        val create =
            client.post("/api/support/tickets") {
                withInitData(createInitData(userId = telegramId))
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "clubId":$clubId,
                    "topic":"other",
                    "text":"Initial"
                }""",
                )
            }
        val ticketId = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.long

        val response =
            client.post("/api/support/tickets/$ticketId/messages") {
                withInitData(createInitData(userId = telegramId))
                contentType(ContentType.Application.Json)
                setBody("""{"text":"More details","attachments":"[]"}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        response.assertNoStoreHeaders()
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(ticketId, payload["ticketId"]!!.jsonPrimitive.long)
        assertEquals("guest", payload["senderType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `other user cannot add message`() = withSupportApp { context ->
        val ownerTelegramId = 404L
        val intruderTelegramId = 405L
        insertUser(context.database, ownerTelegramId, "owner")
        insertUser(context.database, intruderTelegramId, "intruder")
        val clubId = insertClub(context.database, "Test Club")

        val create =
            client.post("/api/support/tickets") {
                withInitData(createInitData(userId = ownerTelegramId))
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "clubId":$clubId,
                    "topic":"invite",
                    "text":"Issue"
                }""",
                )
            }
        val ticketId = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.long

        val response =
            client.post("/api/support/tickets/$ticketId/messages") {
                withInitData(createInitData(userId = intruderTelegramId))
                contentType(ContentType.Application.Json)
                setBody("""{"text":"Hacked"}""")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        response.assertNoStoreHeaders()
        assertEquals("support_ticket_forbidden", response.errorCode())
    }

    @Test
    fun `closed ticket blocks guest message`() = withSupportApp { context ->
        val telegramId = 505L
        val agentTelegramId = 506L
        insertUser(context.database, telegramId, "guest")
        val agentId = insertUser(context.database, agentTelegramId, "agent")
        val clubId = insertClub(context.database, "Test Club")

        val create =
            client.post("/api/support/tickets") {
                withInitData(createInitData(userId = telegramId))
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "clubId":$clubId,
                    "topic":"dresscode",
                    "text":"Question"
                }""",
                )
            }
        val ticketId = json.parseToJsonElement(create.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.long

        val closed = context.supportService.setStatus(ticketId, agentId, TicketStatus.CLOSED)
        assertTrue(closed is com.example.bot.support.SupportServiceResult.Success)

        val response =
            client.post("/api/support/tickets/$ticketId/messages") {
                withInitData(createInitData(userId = telegramId))
                contentType(ContentType.Application.Json)
                setBody("""{"text":"Ping"}""")
            }

        assertEquals(HttpStatusCode.Conflict, response.status)
        response.assertNoStoreHeaders()
        assertEquals("support_ticket_closed", response.errorCode())
    }

    private data class DbSetup(
        val dataSource: JdbcDataSource,
        val database: Database,
    )

    private data class TestContext(
        val database: Database,
        val supportService: SupportService,
    )

    private fun withSupportApp(block: suspend ApplicationTestBuilder.(TestContext) -> Unit) =
        testApplication {
            val setup = prepareDatabase()
            val supportRepository = SupportRepository(setup.database)
            val supportService = SupportServiceImpl(supportRepository)
            val userRepository = ExposedUserRepository(setup.database)
            application {
                install(ContentNegotiation) { json() }
                supportRoutes(
                    supportService = supportService,
                    userRepository = userRepository,
                    botTokenProvider = { TEST_BOT_TOKEN },
                )
            }
            block(TestContext(setup.database, supportService))
        }

    private fun prepareDatabase(): DbSetup {
        val dbName = "support_routes_${UUID.randomUUID()}"
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
        transaction(database) {
            listOf("action", "result").forEach { column ->
                exec("""ALTER TABLE audit_log ALTER COLUMN $column RENAME TO "$column"""")
            }
            exec("ALTER TABLE audit_log ALTER COLUMN resource_id DROP NOT NULL")
        }
        return DbSetup(dataSource = dataSource, database = database)
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
}
