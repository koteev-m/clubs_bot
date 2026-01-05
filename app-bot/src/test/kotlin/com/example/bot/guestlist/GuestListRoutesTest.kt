package com.example.bot.guestlist

import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListRepository
import com.example.bot.club.GuestListStatus
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.club.GuestListCsvParser
import com.example.bot.data.club.GuestListRepositoryImpl
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.plugins.DataSourceHolder
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.routes.guestListRoutes
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.testing.applicationDev
import com.example.bot.testing.defaultRequest
import com.example.bot.testing.header
import com.example.bot.testing.withInitData
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// --- HMAC-подписанный initData для этого файла (file-private) ---
private fun buildSignedInitData(
    userId: Long,
    username: String?,
): String {
    val params =
        linkedMapOf(
            "user" to WebAppInitDataTestHelper.encodeUser(id = userId, username = username),
            "auth_date" to Instant.now().epochSecond.toString(),
        )
    return WebAppInitDataTestHelper.createInitData(TEST_BOT_TOKEN, params)
}

@Suppress("unused")
class GuestListRoutesTest :
    StringSpec(
        {
            lateinit var dataSource: JdbcDataSource
            lateinit var database: Database
            lateinit var repository: GuestListRepository
            val parser = GuestListCsvParser()

            beforeTest {
                val setup = prepareDatabase()
                dataSource = setup.dataSource
                database = setup.database
                repository = GuestListRepositoryImpl(database)
            }

            afterTest {
                DataSourceHolder.dataSource = null
            }

            fun Application.testModule() {
                DataSourceHolder.dataSource = dataSource
                install(ContentNegotiation) { json() }

                // Локальный модуль только для теста: User/UserRole/Audit + уже созданный GuestListRepository
                val testModule: Module =
                    module {
                        single<GuestListRepository> { repository }
                        single<UserRepository> { GLUserRepositoryStub(database) }
                        single<UserRoleRepository> { GLUserRoleRepositoryStub(database) }
                        single<AuditLogRepository> { relaxedAuditRepository() }
                    }

                install(Koin) { modules(testModule) }

                install(RbacPlugin) {
                    userRepository = get()
                    userRoleRepository = get()
                    auditLogRepository = get()
                    principalExtractor = { call ->
                        val initDataPrincipal =
                            if (call.attributes.contains(MiniAppUserKey)) {
                                call.attributes[MiniAppUserKey]
                            } else {
                                null
                            }
                        if (initDataPrincipal != null) {
                            TelegramPrincipal(initDataPrincipal.id, initDataPrincipal.username)
                        } else {
                            call.request.header("X-Telegram-Id")?.toLongOrNull()?.let { id ->
                                TelegramPrincipal(id, call.request.header("X-Telegram-Username"))
                            }
                        }
                    }
                }

                // Сами маршруты списка гостей (+ withMiniAppAuth внутри)
                guestListRoutes(
                    repository = get(),
                    parser = parser,
                    botTokenProvider = { TEST_BOT_TOKEN },
                )
            }

            fun ApplicationTestBuilder.authenticatedClient(
                telegramId: Long,
                username: String = "user$telegramId",
            ): HttpClient =
                defaultRequest {
                    val initData = buildSignedInitData(telegramId, username)
                    withInitData(initData)
                    header("X-Telegram-Id", telegramId.toString())
                    if (username.isNotBlank()) {
                        header("X-Telegram-Username", username)
                    }
                }

            fun createClub(name: String): Long =
                transaction(database) {
                    GLClubsTable.insert {
                        it[GLClubsTable.name] = name
                        it[timezone] = "Europe/Moscow"
                        it[description] = null
                        it[adminChannelId] = null
                        it[bookingsTopicId] = null
                        it[checkinTopicId] = null
                        it[qaTopicId] = null
                    } get GLClubsTable.id
                }

            fun createEvent(
                clubId: Long,
                title: String,
            ): Long {
                val start = Instant.parse("2024-07-01T18:00:00Z")
                val end = Instant.parse("2024-07-02T02:00:00Z")
                return transaction(database) {
                    EventsTable.insert {
                        it[EventsTable.clubId] = clubId
                        it[EventsTable.title] = title
                        it[startAt] = start.atOffset(ZoneOffset.UTC)
                        it[endAt] = end.atOffset(ZoneOffset.UTC)
                    } get EventsTable.id
                }
            }

            fun createDomainUser(username: String): Long =
                transaction(database) {
                    GLDomainUsersTable.insert {
                        it[telegramUserId] = null
                        it[GLDomainUsersTable.username] = username
                        it[displayName] = username
                        it[phone] = null
                    } get GLDomainUsersTable.id
                }

            fun registerRbacUser(
                telegramId: Long,
                roles: Set<Role>,
                clubs: Set<Long>,
            ): Long =
                transaction(database) {
                    roles.forEach { role ->
                        val exists =
                            GLRolesTable
                                .selectAll()
                                .where { GLRolesTable.code eq role.name }
                                .limit(1)
                                .any()
                        if (!exists) {
                            GLRolesTable.insert { it[code] = role.name }
                        }
                    }
                    val userId =
                        GLDomainUsersTable.insert {
                            it[telegramUserId] = telegramId
                            it[username] = "user$telegramId"
                            it[displayName] = "user$telegramId"
                            it[phone] = null
                        } get GLDomainUsersTable.id
                    roles.forEach { role ->
                        if (clubs.isEmpty()) {
                            GLUserRolesTable.insert {
                                it[GLUserRolesTable.userId] = userId
                                it[roleCode] = role.name
                                it[scopeType] = "GLOBAL"
                                it[scopeClubId] = null
                            }
                        } else {
                            clubs.forEach { clubId ->
                                GLUserRolesTable.insert {
                                    it[GLUserRolesTable.userId] = userId
                                    it[roleCode] = role.name
                                    it[scopeType] = "CLUB"
                                    it[scopeClubId] = clubId
                                }
                            }
                        }
                    }
                    userId
                }

            data class ImportSetup(
                val listId: Long,
                val telegramId: Long,
            )

            suspend fun prepareActiveListWithManager(
                clubName: String,
                eventTitle: String,
                ownerUsername: String,
                telegramId: Long,
                capacity: Int = 25,
                listTitle: String = eventTitle,
            ): ImportSetup {
                val clubId = createClub(clubName)
                val eventId = createEvent(clubId, eventTitle)
                val ownerId = createDomainUser(ownerUsername)
                val list =
                    repository.createList(
                        clubId = clubId,
                        eventId = eventId,
                        ownerType = GuestListOwnerType.MANAGER,
                        ownerUserId = ownerId,
                        title = listTitle,
                        capacity = capacity,
                        arrivalWindowStart = null,
                        arrivalWindowEnd = null,
                        status = GuestListStatus.ACTIVE,
                    )
                registerRbacUser(telegramId = telegramId, roles = setOf(Role.MANAGER), clubs = setOf(clubId))
                return ImportSetup(list.id, telegramId)
            }

            suspend fun assertJsonImport(
                response: HttpResponse,
                expectedAccepted: Int,
            ): JsonObject {
                response.status shouldBe HttpStatusCode.OK
                val contentType =
                    response.headers[HttpHeaders.ContentType]?.let { header -> ContentType.parse(header).withoutParameters() }
                contentType shouldBe ContentType.Application.Json
                val jsonBody = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                jsonBody["accepted"]!!.jsonPrimitive.int shouldBe expectedAccepted
                return jsonBody
            }

            suspend fun assertCsvImport(
                response: HttpResponse,
                expectedDataLine: String = "1,0",
            ) {
                response.status shouldBe HttpStatusCode.OK
                val contentType =
                    response.headers[HttpHeaders.ContentType]?.let { header -> ContentType.parse(header).withoutParameters() }
                contentType shouldBe ContentType.Text.CSV
                val lines = response.bodyAsText().trimEnd().lines()
                lines shouldHaveSize 2
                lines.first().removePrefix("\uFEFF") shouldBe "accepted_count,rejected_count"
                lines[1] shouldBe expectedDataLine
            }

            "import dry run returns json report" {
                val setup =
                    prepareActiveListWithManager(
                        clubName = "Nebula",
                        eventTitle = "Launch",
                        ownerUsername = "owner1",
                        telegramId = 100L,
                        capacity = 50,
                        listTitle = "VIP",
                    )

                testApplication {
                    applicationDev { testModule() }
                    val authedClient = authenticatedClient(telegramId = setup.telegramId)
                    val response =
                        authedClient.post("/api/guest-lists/${setup.listId}/import?dry_run=true") {
                            contentType(ContentType.Text.CSV)
                            setBody("name,phone,guests_count,notes\nAlice,+123456789,2,VIP\n")
                        }
                    val jsonBody = assertJsonImport(response, expectedAccepted = 1)
                    jsonBody["rejected"]!!.jsonArray.size shouldBe 0
                    repository.listEntries(setup.listId, page = 0, size = 10) shouldHaveSize 0
                }
            }

            "commit import persists and returns csv" {
                val setup =
                    prepareActiveListWithManager(
                        clubName = "Orion",
                        eventTitle = "Opening",
                        ownerUsername = "owner2",
                        telegramId = 200L,
                        capacity = 30,
                        listTitle = "Friends",
                    )

                testApplication {
                    applicationDev { testModule() }
                    val authedClient = authenticatedClient(telegramId = setup.telegramId)
                    val response =
                        authedClient.post("/api/guest-lists/${setup.listId}/import") {
                            header(HttpHeaders.Accept, ContentType.Text.CSV.toString())
                            contentType(ContentType.Text.CSV)
                            setBody("name,phone,guests_count,notes\nBob,+123456700,3,\n")
                        }
                    assertCsvImport(response)
                    repository.listEntries(setup.listId, page = 0, size = 10) shouldHaveSize 1
                }
            }

            "Accept text/csv;q=0 НЕ включает CSV" {
                val setup =
                    prepareActiveListWithManager(
                        clubName = "Andromeda",
                        eventTitle = "Preview",
                        ownerUsername = "owner3",
                        telegramId = 210L,
                        capacity = 15,
                    )

                testApplication {
                    applicationDev { testModule() }
                    val authedClient = authenticatedClient(telegramId = setup.telegramId)
                    val response =
                        authedClient.post("/api/guest-lists/${setup.listId}/import") {
                            header(HttpHeaders.Accept, "text/csv;q=0, application/json;q=1")
                            contentType(ContentType.Text.CSV)
                            setBody("name,phone,guests_count,notes\nCharlie,+123450000,1,\n")
                        }

                    assertJsonImport(response, expectedAccepted = 1)
                    repository.listEntries(setup.listId, page = 0, size = 10) shouldHaveSize 1
                }
            }

            "Accept text/csv с параметрами включает CSV" {
                val setup =
                    prepareActiveListWithManager(
                        clubName = "Lyra",
                        eventTitle = "Evening",
                        ownerUsername = "owner4",
                        telegramId = 220L,
                        capacity = 25,
                    )

                testApplication {
                    applicationDev { testModule() }
                    val authedClient = authenticatedClient(telegramId = setup.telegramId)
                    val response =
                        authedClient.post("/api/guest-lists/${setup.listId}/import") {
                            header(HttpHeaders.Accept, "text/csv; charset=utf-8; q=1")
                            contentType(ContentType.Text.CSV)
                            setBody("name,phone,guests_count,notes\nDiana,+123450001,2,VIP\n")
                        }

                    assertCsvImport(response)
                    repository.listEntries(setup.listId, page = 0, size = 10) shouldHaveSize 1
                }
            }

            "Wildcard with lower csv quality falls back to json" {
                val setup =
                    prepareActiveListWithManager(
                        clubName = "Deneb",
                        eventTitle = "WildcardLow",
                        ownerUsername = "owner8",
                        telegramId = 245L,
                        capacity = 20,
                    )

                testApplication {
                    applicationDev { testModule() }
                    val authedClient = authenticatedClient(telegramId = setup.telegramId)
                    val response =
                        authedClient.post("/api/guest-lists/${setup.listId}/import") {
                            header(HttpHeaders.Accept, "*/*;q=1, text/csv;q=0.1")
                            contentType(ContentType.Text.CSV)
                            setBody("name,phone,guests_count,notes\nHank,+123450005,1,\n")
                        }

                    assertJsonImport(response, expectedAccepted = 1)
                    repository.listEntries(setup.listId, page = 0, size = 10) shouldHaveSize 1
                }
            }

            "Type wildcard preferred over lower csv quality" {
                val setup =
                    prepareActiveListWithManager(
                        clubName = "Rigel",
                        eventTitle = "TypeWildcard",
                        ownerUsername = "owner9",
                        telegramId = 250L,
                        capacity = 20,
                    )

                testApplication {
                    applicationDev { testModule() }
                    val authedClient = authenticatedClient(telegramId = setup.telegramId)
                    val response =
                        authedClient.post("/api/guest-lists/${setup.listId}/import") {
                            header(HttpHeaders.Accept, "application/*;q=1, text/csv;q=0.5")
                            contentType(ContentType.Text.CSV)
                            setBody("name,phone,guests_count,notes\nIvy,+123450006,1,\n")
                        }

                    assertJsonImport(response, expectedAccepted = 1)
                    repository.listEntries(setup.listId, page = 0, size = 10) shouldHaveSize 1
                }
            }

            "Wildcard allows csv when json forbidden" {
                val setup =
                    prepareActiveListWithManager(
                        clubName = "Spica",
                        eventTitle = "WildcardCsv",
                        ownerUsername = "owner10",
                        telegramId = 255L,
                        capacity = 20,
                    )

                testApplication {
                    applicationDev { testModule() }
                    val authedClient = authenticatedClient(telegramId = setup.telegramId)
                    val response =
                        authedClient.post("/api/guest-lists/${setup.listId}/import") {
                            header(HttpHeaders.Accept, "application/json;q=0, */*;q=1")
                            contentType(ContentType.Text.CSV)
                            setBody("name,phone,guests_count,notes\nJules,+123450007,1,\n")
                        }

                    assertCsvImport(response)
                    repository.listEntries(setup.listId, page = 0, size = 10) shouldHaveSize 1
                }
            }

            "JSON preferred when csv has lower q" {
                val setup =
                    prepareActiveListWithManager(
                        clubName = "Altair",
                        eventTitle = "Preference",
                        ownerUsername = "owner6",
                        telegramId = 235L,
                        capacity = 25,
                    )

                testApplication {
                    applicationDev { testModule() }
                    val authedClient = authenticatedClient(telegramId = setup.telegramId)
                    val response =
                        authedClient.post("/api/guest-lists/${setup.listId}/import") {
                            header(HttpHeaders.Accept, "text/csv;q=0.1, application/json;q=1")
                            contentType(ContentType.Text.CSV)
                            setBody("name,phone,guests_count,notes\nFinn,+123450003,1,\n")
                        }

                    assertJsonImport(response, expectedAccepted = 1)
                    repository.listEntries(setup.listId, page = 0, size = 10) shouldHaveSize 1
                }
            }

            "CSV preferred when quality above json" {
                val setup =
                    prepareActiveListWithManager(
                        clubName = "Vega",
                        eventTitle = "PreferenceCsv",
                        ownerUsername = "owner7",
                        telegramId = 240L,
                        capacity = 25,
                    )

                testApplication {
                    applicationDev { testModule() }
                    val authedClient = authenticatedClient(telegramId = setup.telegramId)
                    val response =
                        authedClient.post("/api/guest-lists/${setup.listId}/import") {
                            header(HttpHeaders.Accept, "application/json;q=0.1, text/csv;q=1")
                            contentType(ContentType.Text.CSV)
                            setBody("name,phone,guests_count,notes\nGina,+123450004,1,\n")
                        }

                    assertCsvImport(response)
                    repository.listEntries(setup.listId, page = 0, size = 10) shouldHaveSize 1
                }
            }

            "Invalid Accept header falls back to default (JSON)" {
                val setup =
                    prepareActiveListWithManager(
                        clubName = "Draco",
                        eventTitle = "InvalidAccept",
                        ownerUsername = "owner11",
                        telegramId = 260L,
                    )

                testApplication {
                    applicationDev { testModule() }
                    val authedClient = authenticatedClient(telegramId = setup.telegramId)
                    val response =
                        authedClient.post("/api/guest-lists/${setup.listId}/import") {
                            header(HttpHeaders.Accept, "not-a-media-type")
                            contentType(ContentType.Text.CSV)
                            setBody("name,phone,guests_count,notes\nKira,+123450008,1,\n")
                        }

                    assertJsonImport(response, expectedAccepted = 1)
                    repository.listEntries(setup.listId, page = 0, size = 10) shouldHaveSize 1
                }
            }

            "Tie CSV and JSON defaults to JSON" {
                val setup =
                    prepareActiveListWithManager(
                        clubName = "Lacerta",
                        eventTitle = "TiePreference",
                        ownerUsername = "owner12",
                        telegramId = 265L,
                    )

                testApplication {
                    applicationDev { testModule() }
                    val authedClient = authenticatedClient(telegramId = setup.telegramId)
                    val response =
                        authedClient.post("/api/guest-lists/${setup.listId}/import") {
                            header(HttpHeaders.Accept, "text/csv, application/json")
                            contentType(ContentType.Text.CSV)
                            setBody("name,phone,guests_count,notes\nLena,+123450009,1,\n")
                        }

                    assertJsonImport(response, expectedAccepted = 1)
                    repository.listEntries(setup.listId, page = 0, size = 10) shouldHaveSize 1
                }
            }

            "format=csv overrides q=0 Accept" {
                val setup =
                    prepareActiveListWithManager(
                        clubName = "Cassiopeia",
                        eventTitle = "Priority",
                        ownerUsername = "owner5",
                        telegramId = 230L,
                        capacity = 25,
                    )

                testApplication {
                    applicationDev { testModule() }
                    val authedClient = authenticatedClient(telegramId = setup.telegramId)
                    val response =
                        authedClient.post("/api/guest-lists/${setup.listId}/import?format=csv") {
                            header(HttpHeaders.Accept, "text/csv;q=0, application/json;q=1")
                            contentType(ContentType.Text.CSV)
                            setBody("name,phone,guests_count,notes\nEli,+123450002,1,\n")
                        }

                    assertCsvImport(response)
                    repository.listEntries(setup.listId, page = 0, size = 10) shouldHaveSize 1
                }
            }

            "manager sees only own club" {
                val clubA = createClub("Nova")
                val clubB = createClub("Pulse")
                val eventA = createEvent(clubA, "Night A")
                val eventB = createEvent(clubB, "Night B")
                val ownerA = createDomainUser("managerA")
                val ownerB = createDomainUser("managerB")
                val listA =
                    repository.createList(
                        clubId = clubA,
                        eventId = eventA,
                        ownerType = GuestListOwnerType.MANAGER,
                        ownerUserId = ownerA,
                        title = "List A",
                        capacity = 20,
                        arrivalWindowStart = null,
                        arrivalWindowEnd = null,
                        status = GuestListStatus.ACTIVE,
                    )
                val listB =
                    repository.createList(
                        clubId = clubB,
                        eventId = eventB,
                        ownerType = GuestListOwnerType.MANAGER,
                        ownerUserId = ownerB,
                        title = "List B",
                        capacity = 20,
                        arrivalWindowStart = null,
                        arrivalWindowEnd = null,
                        status = GuestListStatus.ACTIVE,
                    )
                repository.addEntry(listA.id, "Alice", "+100", 2, null)
                repository.addEntry(listB.id, "Clare", "+200", 1, null)
                registerRbacUser(telegramId = 300L, roles = setOf(Role.MANAGER), clubs = setOf(clubA))

                testApplication {
                    applicationDev { testModule() }
                    val authedClient = authenticatedClient(telegramId = 300L)
                    val response = authedClient.get("/api/guest-lists")
                    // Диагностика
                    println(
                        "DBG guestlists manager: status=${response.status} " +
                            "body=${response.bodyAsText()}",
                    )
                    response.status shouldBe HttpStatusCode.OK
                    val items = Json.parseToJsonElement(response.bodyAsText()).jsonObject["items"]!!.jsonArray
                    items.shouldHaveSize(1)
                    items.first().jsonObject["clubId"].toString() shouldBe clubA.toString()

                    val forbidden = authedClient.get("/api/guest-lists?club=$clubB")
                    println(
                        "DBG guestlists manager forbidden: " +
                            "status=${forbidden.status} body=${forbidden.bodyAsText()}",
                    )
                    forbidden.status shouldBe HttpStatusCode.Forbidden
                }
            }

            "promoter filtered by owner" {
                val club = createClub("Pulse")
                val event = createEvent(club, "Promo")
                val promoterUserId =
                    registerRbacUser(telegramId = 400L, roles = setOf(Role.PROMOTER), clubs = setOf(club))
                val managerOwner = createDomainUser("manager")
                val promoterList =
                    repository.createList(
                        clubId = club,
                        eventId = event,
                        ownerType = GuestListOwnerType.PROMOTER,
                        ownerUserId = promoterUserId,
                        title = "Promo",
                        capacity = 15,
                        arrivalWindowStart = null,
                        arrivalWindowEnd = null,
                        status = GuestListStatus.ACTIVE,
                    )
                val managerList =
                    repository.createList(
                        clubId = club,
                        eventId = event,
                        ownerType = GuestListOwnerType.MANAGER,
                        ownerUserId = managerOwner,
                        title = "Manager",
                        capacity = 15,
                        arrivalWindowStart = null,
                        arrivalWindowEnd = null,
                        status = GuestListStatus.ACTIVE,
                    )
                repository.addEntry(promoterList.id, "Promo Guest", "+111", 1, null)
                repository.addEntry(managerList.id, "Club Guest", "+222", 1, null)

                testApplication {
                    applicationDev { testModule() }
                    val authedClient = authenticatedClient(telegramId = 400L)
                    val response = authedClient.get("/api/guest-lists")
                    response.status shouldBe HttpStatusCode.OK
                    val items = Json.parseToJsonElement(response.bodyAsText()).jsonObject["items"]!!.jsonArray
                    items.shouldHaveSize(1)
                    items.first().jsonObject["listId"].toString() shouldBe promoterList.id.toString()
                }
            }

            "head manager export returns csv" {
                val club = createClub("Zenith")
                val event = createEvent(club, "Gala")
                val owner = createDomainUser("head")
                val list =
                    repository.createList(
                        clubId = club,
                        eventId = event,
                        ownerType = GuestListOwnerType.MANAGER,
                        ownerUserId = owner,
                        title = "Gala",
                        capacity = 40,
                        arrivalWindowStart = null,
                        arrivalWindowEnd = null,
                        status = GuestListStatus.ACTIVE,
                    )
                repository.addEntry(list.id, "Guest One", "+500", 2, "VIP")
                registerRbacUser(telegramId = 500L, roles = setOf(Role.HEAD_MANAGER), clubs = emptySet())

                testApplication {
                    applicationDev { testModule() }
                    val authedClient = authenticatedClient(telegramId = 500L)
                    val response = authedClient.get("/api/guest-lists/export")
                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.ContentType]!!.startsWith("text/csv") shouldBe true
                    response.bodyAsText().contains("Guest One") shouldBe true
                }
            }
        },
    )

// ===== Локальная БД/таблицы для этого файла =====

private data class GLDbSetup(
    val dataSource: JdbcDataSource,
    val database: Database,
)

private fun prepareDatabase(): GLDbSetup {
    val dbName = "guestlists_${UUID.randomUUID()}"
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
    return GLDbSetup(dataSource, database)
}

private object GLClubsTable : Table("clubs") {
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

private object GLDomainUsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val telegramUserId = long("telegram_user_id").nullable()
    val username = text("username").nullable()
    val displayName = text("display_name").nullable()
    val phone = text("phone_e164").nullable()
    override val primaryKey = PrimaryKey(id)
}

private object GLRolesTable : Table("roles") {
    val code = text("code")
    override val primaryKey = PrimaryKey(code)
}

private object GLUserRolesTable : Table("user_roles") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val roleCode = text("role_code")
    val scopeType = text("scope_type")
    val scopeClubId = long("scope_club_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

// ===== Тестовые реализации RBAC на таблицах этого файла =====

private class GLUserRepositoryStub(
    private val db: Database,
) : UserRepository {
    override suspend fun getByTelegramId(id: Long): User? =
        transaction(db) {
            GLDomainUsersTable
                .selectAll()
                .where { GLDomainUsersTable.telegramUserId eq id }
                .limit(1)
                .firstOrNull()
                ?.let { row ->
                    val userId = row[GLDomainUsersTable.id]
                    val username = row[GLDomainUsersTable.username]
                    User(
                        id = userId,
                        telegramId = id,
                        username = username,
                    )
                }
        }
}

private class GLUserRoleRepositoryStub(
    private val db: Database,
) : UserRoleRepository {
    override suspend fun listRoles(userId: Long): Set<Role> =
        transaction(db) {
            GLUserRolesTable
                .selectAll()
                .where { GLUserRolesTable.userId eq userId }
                .map { row -> Role.valueOf(row[GLUserRolesTable.roleCode]) }
                .toSet()
        }

    override suspend fun listClubIdsFor(userId: Long): Set<Long> =
        transaction(db) {
            GLUserRolesTable
                .selectAll()
                .where { GLUserRolesTable.userId eq userId }
                .mapNotNull { row -> row[GLUserRolesTable.scopeClubId] }
                .toSet()
        }
}

private fun relaxedAuditRepository(): AuditLogRepository = mockk(relaxed = true)
