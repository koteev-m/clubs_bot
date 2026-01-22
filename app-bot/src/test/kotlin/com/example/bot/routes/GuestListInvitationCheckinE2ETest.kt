package com.example.bot.routes

import com.example.bot.clubs.Event
import com.example.bot.clubs.InMemoryEventsRepository
import com.example.bot.clubs.ClubsRepository
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.club.GuestListEntriesTable
import com.example.bot.data.club.GuestListDbRepository
import com.example.bot.data.security.Role
import com.example.bot.host.BookingProvider
import com.example.bot.host.HostEntranceService
import com.example.bot.admin.AdminHallsRepository
import com.example.bot.layout.AdminTablesRepository
import com.example.bot.booking.a3.BookingState
import com.example.bot.data.promoter.PromoterBookingAssignmentsRepository
import com.example.bot.plugins.DataSourceHolder
import com.example.bot.plugins.configureSecurity
import com.example.bot.http.ErrorCodes
import com.example.bot.testing.createInitData
import com.example.bot.testing.defaultRequest
import com.example.bot.testing.withInitData
import com.example.bot.club.WaitlistEntry
import com.example.bot.club.WaitlistRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.ktor.ext.get

class GuestListInvitationCheckinE2ETest {
    private lateinit var dataSource: JdbcDataSource
    private lateinit var database: Database
    private lateinit var event: Event
    private var clubId: Long = 0L
    private var eventId: Long = 0L
    private var promoterUserId: Long = 0L
    private var entryManagerUserId: Long = 0L

    private val promoterTelegramId = 7101L
    private val entryManagerTelegramId = 7201L
    private val guestTelegramId = 7301L

    @BeforeEach
    fun setUp() {
        val setup = prepareDatabase()
        dataSource = setup.dataSource
        database = setup.database

        clubId = insertClub(name = "Aurora")
        eventId =
            insertEvent(
                clubId = clubId,
                title = "Showcase",
                startAt = Instant.parse("2100-01-01T19:00:00Z"),
                endAt = Instant.parse("2100-01-02T03:00:00Z"),
            )
        promoterUserId = insertUser(telegramUserId = promoterTelegramId, username = "promoter", displayName = "Promoter")
        entryManagerUserId = insertUser(telegramUserId = entryManagerTelegramId, username = "entry", displayName = "Entry")
        insertUserRole(promoterUserId, Role.PROMOTER, scopeType = "CLUB", scopeClubId = clubId)
        insertUserRole(entryManagerUserId, Role.ENTRY_MANAGER, scopeType = "CLUB", scopeClubId = clubId)

        event =
            Event(
                id = eventId,
                clubId = clubId,
                startUtc = Instant.parse("2100-01-01T19:00:00Z"),
                endUtc = Instant.parse("2100-01-02T03:00:00Z"),
                title = "Showcase",
                isSpecial = false,
            )
    }

    @AfterEach
    fun tearDown() {
        DataSourceHolder.dataSource = null
    }

    @Test
    fun `happy path guest list invitation and checkin`() = testApplication {
        application { testModule() }

        val promoterClient = authenticatedClient(promoterTelegramId)
        val guestClient = authenticatedClient(guestTelegramId)
        val entryClient = authenticatedClient(entryManagerTelegramId)

        val createListResponse =
            promoterClient.post("/api/promoter/guest-lists") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "clubId": $clubId,
                      "eventId": $eventId,
                      "arrivalWindowStart": "2100-01-01T19:00:00Z",
                      "arrivalWindowEnd": "2100-01-02T03:00:00Z",
                      "limit": 10,
                      "name": "VIP"
                    }
                    """.trimIndent(),
                )
            }

        assertEquals(HttpStatusCode.Created, createListResponse.status)
        val listId =
            createListResponse.bodyAsJson()["guestList"]!!.jsonObject["id"]!!.jsonPrimitive.long

        val bulkResponse =
            promoterClient.post("/api/promoter/guest-lists/$listId/entries/bulk") {
                contentType(ContentType.Application.Json)
                setBody("""{"rawText":"Иван Иванов / Пётр Петров\nСаша"}""")
            }
        assertEquals(HttpStatusCode.OK, bulkResponse.status)

        val entryId =
            transaction(database) {
                GuestListEntriesTable.selectAll()
                    .where { GuestListEntriesTable.guestListId eq listId }
                    .orderBy(GuestListEntriesTable.id)
                    .limit(1)
                    .single()[GuestListEntriesTable.id]
            }

        val invitationResponse =
            promoterClient.post("/api/promoter/guest-lists/$listId/entries/$entryId/invitation") {
                contentType(ContentType.Application.Json)
                setBody("""{"channel":"TELEGRAM"}""")
            }

        assertEquals(HttpStatusCode.Created, invitationResponse.status)
        val token = invitationResponse.bodyAsJson()["token"]!!.jsonPrimitive.content

        val confirmResponse =
            guestClient.post("/api/invitations/respond") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"$token","response":"CONFIRM"}""")
            }
        assertEquals(HttpStatusCode.OK, confirmResponse.status)
        assertEquals("CONFIRMED", confirmResponse.bodyAsJson()["entryStatus"]!!.jsonPrimitive.content)

        val scanResponse =
            entryClient.post("/api/host/checkin/scan") {
                contentType(ContentType.Application.Json)
                setBody("""{"clubId":$clubId,"eventId":$eventId,"qrPayload":"inv:$token"}""")
            }
        assertEquals(HttpStatusCode.OK, scanResponse.status)
        val scanBody = scanResponse.bodyAsJson()
        assertEquals("ARRIVED", scanBody["outcomeStatus"]!!.jsonPrimitive.content)

        val secondScan =
            entryClient.post("/api/host/checkin/scan") {
                contentType(ContentType.Application.Json)
                setBody("""{"clubId":$clubId,"eventId":$eventId,"qrPayload":"inv:$token"}""")
            }
        assertEquals(HttpStatusCode.OK, secondScan.status)
        val secondBody = secondScan.bodyAsJson()
        assertEquals("DENIED", secondBody["outcomeStatus"]!!.jsonPrimitive.content)
        assertEquals("ALREADY_USED", secondBody["denyReason"]!!.jsonPrimitive.content)

        val entranceResponse =
            entryClient.get("/api/host/entrance?clubId=$clubId&eventId=$eventId")
        assertEquals(HttpStatusCode.OK, entranceResponse.status)
        val arrived =
            entranceResponse.bodyAsJson()["status"]!!.jsonObject["guestList"]!!.jsonObject["arrivedGuests"]!!.jsonPrimitive.int
        assertEquals(1, arrived)
    }

    @Test
    fun `invalid scan payload returns invalid response`() = testApplication {
        application { testModule() }

        val entryClient = authenticatedClient(entryManagerTelegramId)
        val response =
            entryClient.post("/api/host/checkin/scan") {
                contentType(ContentType.Application.Json)
                setBody("""{"clubId":$clubId,"eventId":$eventId,"qrPayload":"garbage"}""")
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCodes.checkin_invalid_payload, response.errorCode())
    }

    @Test
    fun `host checkin forbidden without entry role`() = testApplication {
        application { testModule() }

        val promoterClient = authenticatedClient(promoterTelegramId)
        val response =
            promoterClient.post("/api/host/checkin/scan") {
                contentType(ContentType.Application.Json)
                setBody("""{"clubId":$clubId,"eventId":$eventId,"qrPayload":"inv_token"}""")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("forbidden", response.errorCode())
    }

    @Test
    fun `respond confirm without initData is forbidden`() = testApplication {
        application { testModule() }

        val rawToken = "missing-init"
        val response =
            client.post("/api/invitations/respond") {
                contentType(ContentType.Application.Json)
                setBody("""{"token":"$rawToken","response":"CONFIRM"}""")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = response.bodyAsJson()
        assertEquals("invitation_forbidden", body["code"]!!.jsonPrimitive.content)
        assertFalse(response.bodyAsText().contains(rawToken))
    }

    private fun Application.testModule() {
        DataSourceHolder.dataSource = dataSource
        install(ContentNegotiation) { json() }

        val eventsRepository = InMemoryEventsRepository(listOf(event))
        val bookingProvider =
            object : BookingProvider {
                override fun findBookingsForEvent(
                    clubId: Long,
                    eventId: Long,
                ): List<com.example.bot.booking.a3.Booking> = emptyList()
            }
        val waitlistRepository =
            object : WaitlistRepository {
                override suspend fun enqueue(
                    clubId: Long,
                    eventId: Long,
                    userId: Long,
                    partySize: Int,
                ): WaitlistEntry = error("waitlist enqueue is not used in this test")

                override suspend fun listQueue(
                    clubId: Long,
                    eventId: Long,
                ): List<WaitlistEntry> = emptyList()

                override suspend fun callEntry(
                    clubId: Long,
                    id: Long,
                    reserveMinutes: Int,
                ): WaitlistEntry? = null

                override suspend fun expireEntry(
                    clubId: Long,
                    id: Long,
                    close: Boolean,
                ): WaitlistEntry? = null

                override suspend fun get(id: Long): WaitlistEntry? = null
            }
        val testModule: Module =
            module {
                single { eventsRepository }
                single { bookingProvider }
            }

        install(Koin) { modules(com.example.bot.di.bookingModule, testModule) }

        configureSecurity()

        val hostEntranceService =
            HostEntranceService(
                guestListRepository = get(),
                waitlistRepository = waitlistRepository,
                bookingProvider = bookingProvider,
                eventsRepository = eventsRepository,
            )

        promoterGuestListRoutes(
            guestListRepository = get(),
            guestListService = get(),
            guestListEntryRepository = get(),
            invitationService = get(),
            guestListDbRepository = mockk<GuestListDbRepository>(relaxed = true),
            clubsRepository = mockk<ClubsRepository>(relaxed = true),
            eventsRepository = eventsRepository,
            adminHallsRepository = mockk<AdminHallsRepository>(relaxed = true),
            adminTablesRepository = mockk<AdminTablesRepository>(relaxed = true),
            bookingState = mockk<BookingState>(relaxed = true),
            promoterAssignments = mockk<PromoterBookingAssignmentsRepository>(relaxed = true),
            botTokenProvider = { com.example.bot.webapp.TEST_BOT_TOKEN },
        )
        invitationRoutes(
            invitationService = get(),
            botTokenProvider = { com.example.bot.webapp.TEST_BOT_TOKEN },
        )
        val hostSearchService = mockk<com.example.bot.host.HostSearchService>(relaxed = true)
        hostCheckinRoutes(
            checkinService = get(),
            hostSearchService = hostSearchService,
            botTokenProvider = { com.example.bot.webapp.TEST_BOT_TOKEN },
        )
        hostEntranceRoutes(
            service = hostEntranceService,
            botTokenProvider = { com.example.bot.webapp.TEST_BOT_TOKEN },
        )
    }

    private fun ApplicationTestBuilder.authenticatedClient(telegramId: Long): HttpClient =
        defaultRequest {
            withInitData(createInitData(userId = telegramId))
        }

    private suspend fun io.ktor.client.statement.HttpResponse.bodyAsJson(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String {
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

    private data class DbSetup(
        val dataSource: JdbcDataSource,
        val database: Database,
    )

    private fun prepareDatabase(): DbSetup {
        val dbName = "guestlist_e2e_${UUID.randomUUID()}"
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
        return DbSetup(dataSource, database)
    }

    private fun insertClub(
        name: String,
        timezone: String = "Europe/Moscow",
    ): Long =
        transaction(database) {
            ClubsTable
                .insert {
                    it[ClubsTable.name] = name
                    it[ClubsTable.description] = null
                    it[ClubsTable.timezone] = timezone
                    it[ClubsTable.adminChannelId] = null
                    it[ClubsTable.bookingsTopicId] = null
                    it[ClubsTable.checkinTopicId] = null
                    it[ClubsTable.qaTopicId] = null
                }.resultedValues!!
                .single()[ClubsTable.id]
        }

    private fun insertEvent(
        clubId: Long,
        title: String,
        startAt: Instant,
        endAt: Instant,
    ): Long =
        transaction(database) {
            EventsTable
                .insert {
                    it[EventsTable.clubId] = clubId
                    it[EventsTable.title] = title
                    it[EventsTable.startAt] = startAt.atOffset(ZoneOffset.UTC)
                    it[EventsTable.endAt] = endAt.atOffset(ZoneOffset.UTC)
                    it[EventsTable.isSpecial] = false
                    it[EventsTable.posterUrl] = null
                }.resultedValues!!
                .single()[EventsTable.id]
        }

    private fun insertUser(
        telegramUserId: Long,
        username: String,
        displayName: String,
    ): Long =
        transaction(database) {
            UsersTable
                .insert {
                    it[UsersTable.telegramUserId] = telegramUserId
                    it[UsersTable.username] = username
                    it[UsersTable.displayName] = displayName
                    it[UsersTable.phoneE164] = null
                }.resultedValues!!
                .single()[UsersTable.id]
        }

    private fun insertUserRole(
        userId: Long,
        role: Role,
        scopeType: String,
        scopeClubId: Long?,
    ) {
        transaction(database) {
            UserRolesTable.insert {
                it[UserRolesTable.userId] = userId
                it[UserRolesTable.roleCode] = role.name
                it[UserRolesTable.scopeType] = scopeType
                it[UserRolesTable.scopeClubId] = scopeClubId
            }
        }
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

    private object UsersTable : Table("users") {
        val id = long("id").autoIncrement()
        val telegramUserId = long("telegram_user_id")
        val username = text("username").nullable()
        val displayName = text("display_name")
        val phoneE164 = text("phone_e164").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    private object UserRolesTable : Table("user_roles") {
        val id = long("id").autoIncrement()
        val userId = long("user_id")
        val roleCode = text("role_code")
        val scopeType = text("scope_type")
        val scopeClubId = long("scope_club_id").nullable()
        override val primaryKey = PrimaryKey(id)
    }
}
