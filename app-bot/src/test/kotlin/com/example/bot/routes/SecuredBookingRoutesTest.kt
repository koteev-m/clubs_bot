package com.example.bot.routes

import com.example.bot.booking.BookingCmdResult
import com.example.bot.booking.BookingService
import com.example.bot.booking.BookingStatusUpdateResult
import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.booking.core.BookingRecord
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.plugins.DataSourceHolder
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.testing.applicationDev
import com.example.bot.testing.defaultRequest
import com.example.bot.testing.withInitData
import com.example.bot.webapp.InitDataAuthPlugin
import com.example.bot.webapp.InitDataPrincipalKey
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.header
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
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import java.util.UUID
import io.ktor.server.request.header as serverHeader
import java.math.BigDecimal

// ---------- Вспомогательные сущности и таблицы (file-private) ----------

private data class BookingDbSetup(
    val dataSource: JdbcDataSource,
    val database: Database,
)

private object BookingUsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val telegramUserId = long("telegram_user_id").nullable()
    val username = text("username").nullable()
    val displayName = text("display_name").nullable()
    val phone = text("phone_e164").nullable()
    override val primaryKey = PrimaryKey(id)
}

private object BookingUserRolesTable : Table("user_roles") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val roleCode = text("role_code")
    val scopeType = text("scope_type")
    val scopeClubId = long("scope_club_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Подписываем initData тем же токеном, которым защищён роут. */
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

/** Поднимаем H2 + прогоняем миграции (общая схема для теста). */
private fun prepareDatabase(): BookingDbSetup {
    val dbName = "secured_booking_${UUID.randomUUID()}"
    val ds =
        JdbcDataSource().apply {
            setURL(
                "jdbc:h2:mem:$dbName;" +
                    "MODE=PostgreSQL;" +
                    "DATABASE_TO_UPPER=false;" +
                    "DB_CLOSE_DELAY=-1",
            )
            user = "sa"
            password = ""
        }

    Flyway
        .configure()
        .dataSource(ds)
        .locations("classpath:db/migration/common", "classpath:db/migration/h2")
        .target("9")
        .load()
        .migrate()

    val db = Database.connect(ds)

    // локальные правки схемы для совместимости (как в других тестах)
    transaction(db) {
        listOf("action", "result").forEach { column ->
            exec("""ALTER TABLE audit_log ALTER COLUMN $column RENAME TO "$column"""")
        }
        exec("ALTER TABLE audit_log ALTER COLUMN resource_id DROP NOT NULL")
    }

    return BookingDbSetup(ds, db)
}

private fun bookingRecord(
    id: UUID = UUID.randomUUID(),
    clubId: Long = 1L,
    status: BookingStatus = BookingStatus.SEATED,
): BookingRecord =
    BookingRecord(
        id = id,
        clubId = clubId,
        tableId = 42L,
        tableNumber = 7,
        eventId = 100L,
        guests = 3,
        minRate = BigDecimal.TEN,
        totalRate = BigDecimal.TEN,
        slotStart = Instant.parse("2025-04-01T10:00:00Z"),
        slotEnd = Instant.parse("2025-04-01T12:00:00Z"),
        status = status,
        qrSecret = "qr",
        idempotencyKey = "idem",
        createdAt = Instant.parse("2025-03-01T10:00:00Z"),
        updatedAt = Instant.parse("2025-03-01T10:00:00Z"),
    )

/** Ленивая «расслабленная» заглушка аудита. */
private fun relaxedAuditRepository(): AuditLogRepository = mockk(relaxed = true)

/** Koin-стабы RBAC на локальных Exposed-таблицах. */
private class BookingUserRepositoryStub(
    private val db: Database,
) : UserRepository {
    override suspend fun getByTelegramId(id: Long): User? {
        return transaction(db) {
            BookingUsersTable
                .selectAll()
                .where { BookingUsersTable.telegramUserId eq id }
                .limit(1)
                .firstOrNull()
                ?.let { row ->
                    User(
                        id = row[BookingUsersTable.id],
                        telegramId = id,
                        username = row[BookingUsersTable.username],
                    )
                }
        }
    }
}

private class BookingUserRoleRepositoryStub(
    private val db: Database,
) : UserRoleRepository {
    override suspend fun listRoles(userId: Long): Set<Role> {
        return transaction(db) {
            BookingUserRolesTable
                .selectAll()
                .where { BookingUserRolesTable.userId eq userId }
                .map { row -> Role.valueOf(row[BookingUserRolesTable.roleCode]) }
                .toSet()
        }
    }

    override suspend fun listClubIdsFor(userId: Long): Set<Long> {
        return transaction(db) {
            BookingUserRolesTable
                .selectAll()
                .where { BookingUserRolesTable.userId eq userId }
                .mapNotNull { row -> row[BookingUserRolesTable.scopeClubId] }
                .toSet()
        }
    }
}

@Suppress("unused") // тест запускается раннером, прямых ссылок из кода нет
class SecuredBookingRoutesTest : StringSpec({

    val json = Json { ignoreUnknownKeys = true }
    lateinit var setup: BookingDbSetup

    beforeTest {
        setup = prepareDatabase()
    }

    afterTest {
        DataSourceHolder.dataSource = null
    }

    /** Модуль Ktor для теста: JSON, InitData + RBAC, маршруты бронирования. */
    fun Application.testModule(service: BookingService) {
        DataSourceHolder.dataSource = setup.dataSource

        install(ContentNegotiation) { json() }

        // локальные Koin-бины на наши Exposed-таблицы
        val testModule: Module =
            module {
                single<UserRepository> { BookingUserRepositoryStub(setup.database) }
                single<UserRoleRepository> { BookingUserRoleRepositoryStub(setup.database) }
                single<AuditLogRepository> { relaxedAuditRepository() }
            }
        install(Koin) { modules(testModule) }

        // InitData только внутри этого приложения
        install(InitDataAuthPlugin) { botTokenProvider = { TEST_BOT_TOKEN } }

        // RBAC, principal из InitData или из заголовков X-Telegram-*
        install(RbacPlugin) {
            userRepository = get()
            userRoleRepository = get()
            auditLogRepository = get()
            principalExtractor = { call ->
                val p =
                    if (call.attributes.contains(InitDataPrincipalKey)) {
                        call.attributes[InitDataPrincipalKey]
                    } else {
                        null
                    }
                if (p != null) {
                    TelegramPrincipal(p.userId, p.username)
                } else {
                    call.request.serverHeader("X-Telegram-Id")?.toLongOrNull()?.let { uid ->
                        TelegramPrincipal(uid, call.request.serverHeader("X-Telegram-Username"))
                    }
                }
            }
        }

        // сами REST-маршруты
        routing {
            route("") {
                securedBookingRoutes(service)
            }
        }
    }

    /** Хелпер клиента с валидным HMAC initData. */
    fun ApplicationTestBuilder.authenticatedClient(
        telegramId: Long,
        username: String = "user$telegramId",
    ): HttpClient =
        defaultRequest {
            val initData = buildSignedInitData(telegramId, username)
            withInitData(initData)
            header("X-Telegram-Id", telegramId.toString())
            if (username.isNotBlank()) header("X-Telegram-Username", username)
        }

    /** Регистрируем пользователя и его роли в наших тест-таблицах. */
    fun registerUser(
        telegramId: Long,
        roles: Set<Role>,
        clubs: Set<Long>,
    ) {
        transaction(setup.database) {
            val userId =
                BookingUsersTable.insert {
                    it[BookingUsersTable.telegramUserId] = telegramId
                    it[username] = "user$telegramId"
                    it[displayName] = "user$telegramId"
                    it[phone] = null
                } get BookingUsersTable.id

            roles.forEach { role ->
                if (clubs.isEmpty()) {
                    BookingUserRolesTable.insert {
                        it[BookingUserRolesTable.userId] = userId
                        it[roleCode] = role.name
                        it[scopeType] = "GLOBAL"
                        it[scopeClubId] = null
                    }
                } else {
                    clubs.forEach { clubId ->
                        BookingUserRolesTable.insert {
                            it[BookingUserRolesTable.userId] = userId
                            it[roleCode] = role.name
                            it[scopeType] = "CLUB"
                            it[scopeClubId] = clubId
                        }
                    }
                }
            }
        }
    }

    "returns 401 when principal missing" {
        val bookingService = mockk<BookingService>()
        testApplication {
            applicationDev { testModule(bookingService) }
            val response =
                client.post("/api/clubs/1/bookings/hold") {
                    contentType(ContentType.Application.Json)
                    header("Idempotency-Key", "idem-unauth")
                    setBody(
                        """
                        {
                          "tableId": 10,
                          "slotStart": "2025-04-01T10:00:00Z",
                          "slotEnd": "2025-04-01T12:00:00Z",
                          "guestsCount": 2,
                          "ttlSeconds": 900
                        }
                        """.trimIndent(),
                    )
                }
            println("DBG missing-principal: status=${response.status} body=${response.bodyAsText()}")
            response.status shouldBe HttpStatusCode.Unauthorized
        }
        coVerify(exactly = 0) { bookingService.hold(any(), any()) }
    }

    "returns 403 when club scope violated" {
        val bookingService = mockk<BookingService>()
        registerUser(telegramId = 200L, roles = setOf(Role.MANAGER), clubs = setOf(2L))
        testApplication {
            applicationDev { testModule(bookingService) }
            val authed = authenticatedClient(telegramId = 200L)
            val response =
                authed.post("/api/clubs/1/bookings/hold") {
                    header("Idempotency-Key", "idem-scope")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "tableId": 10,
                          "slotStart": "2025-04-01T10:00:00Z",
                          "slotEnd": "2025-04-01T12:00:00Z",
                          "guestsCount": 2,
                          "ttlSeconds": 900
                        }
                        """.trimIndent(),
                    )
                }
            println("DBG scope-violated: status=${response.status} body=${response.bodyAsText()}")
            response.status shouldBe HttpStatusCode.Forbidden
        }
        coVerify(exactly = 0) { bookingService.hold(any(), any()) }
    }

    "returns 400 when Idempotency-Key missing" {
        val bookingService = mockk<BookingService>()
        registerUser(telegramId = 300L, roles = setOf(Role.MANAGER), clubs = setOf(1L))
        testApplication {
            applicationDev { testModule(bookingService) }
            val authed = authenticatedClient(telegramId = 300L)
            val response =
                authed.post("/api/clubs/1/bookings/hold") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "tableId": 10,
                          "slotStart": "2025-04-01T10:00:00Z",
                          "slotEnd": "2025-04-01T12:00:00Z",
                          "guestsCount": 2,
                          "ttlSeconds": 900
                        }
                        """.trimIndent(),
                    )
                }
            println("DBG missing-idem-key: status=${response.status} body=${response.bodyAsText()}")
            response.status shouldBe HttpStatusCode.BadRequest
        }
        coVerify(exactly = 0) { bookingService.hold(any(), any()) }
    }

    "happy path returns 200 for hold and confirm" {
        val bookingService = mockk<BookingService>()
        registerUser(telegramId = 400L, roles = setOf(Role.MANAGER), clubs = setOf(1L))
        val holdId = UUID.randomUUID()
        val bookingId = UUID.randomUUID()
        coEvery { bookingService.hold(any(), "idem-hold") } returns BookingCmdResult.HoldCreated(holdId)
        coEvery { bookingService.confirm(holdId, "idem-confirm") } returns BookingCmdResult.Booked(bookingId)

        testApplication {
            applicationDev { testModule(bookingService) }
            val authed = authenticatedClient(telegramId = 400L)
            val holdResp =
                authed.post("/api/clubs/1/bookings/hold") {
                    header("Idempotency-Key", "idem-hold")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "clubId": 1,
                          "tableId": 25,
                          "slotStart": "2025-04-01T10:00:00Z",
                          "slotEnd": "2025-04-01T12:00:00Z",
                          "guestsCount": 3,
                          "ttlSeconds": 600
                        }
                        """.trimIndent(),
                    )
                }
            println("DBG hold: status=${holdResp.status} body=${holdResp.bodyAsText()}")
            holdResp.status shouldBe HttpStatusCode.OK

            val confirmResp =
                authed.post("/api/clubs/1/bookings/confirm") {
                    header("Idempotency-Key", "idem-confirm")
                    contentType(ContentType.Application.Json)
                    setBody("""{"clubId":1,"holdId":"$holdId"}""")
                }
            println("DBG confirm: status=${confirmResp.status} body=${confirmResp.bodyAsText()}")
            confirmResp.status shouldBe HttpStatusCode.OK
        }

        coVerify(exactly = 1) { bookingService.hold(any(), "idem-hold") }
        coVerify(exactly = 1) { bookingService.confirm(holdId, "idem-confirm") }
    }

    "duplicate active booking returns 409" {
        val bookingService = mockk<BookingService>()
        registerUser(telegramId = 500L, roles = setOf(Role.MANAGER), clubs = setOf(1L))
        coEvery { bookingService.hold(any(), "idem-dup") } returns BookingCmdResult.DuplicateActiveBooking

        testApplication {
            applicationDev { testModule(bookingService) }
            val authed = authenticatedClient(telegramId = 500L)
            val response =
                authed.post("/api/clubs/1/bookings/hold") {
                    header("Idempotency-Key", "idem-dup")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "tableId": 99,
                          "slotStart": "2025-04-01T10:00:00Z",
                          "slotEnd": "2025-04-01T12:00:00Z",
                          "guestsCount": 2,
                          "ttlSeconds": 900
                        }
                        """.trimIndent(),
                    )
                }
            println("DBG duplicate: status=${response.status} body=${response.bodyAsText()}")
            response.status shouldBe HttpStatusCode.Conflict
        }
    }

    "hold expired returns 410" {
        val bookingService = mockk<BookingService>()
        registerUser(telegramId = 600L, roles = setOf(Role.MANAGER), clubs = setOf(1L))
        val holdId = UUID.randomUUID()
        coEvery { bookingService.confirm(holdId, "idem-expire") } returns BookingCmdResult.HoldExpired

        testApplication {
            applicationDev { testModule(bookingService) }
            val authed = authenticatedClient(telegramId = 600L)
            val response =
                authed.post("/api/clubs/1/bookings/confirm") {
                    header("Idempotency-Key", "idem-expire")
                    contentType(ContentType.Application.Json)
                    setBody("""{"holdId":"$holdId"}""")
                }
            println("DBG expire: status=${response.status} body=${response.bodyAsText()}")
            response.status shouldBe HttpStatusCode.Gone
        }
    }

    "confirm not found returns 404" {
        val bookingService = mockk<BookingService>()
        registerUser(telegramId = 700L, roles = setOf(Role.MANAGER), clubs = setOf(1L))
        val holdId = UUID.randomUUID()
        coEvery { bookingService.confirm(holdId, "idem-missing") } returns BookingCmdResult.NotFound

        testApplication {
            applicationDev { testModule(bookingService) }
            val authed = authenticatedClient(telegramId = 700L)
            val response =
                authed.post("/api/clubs/1/bookings/confirm") {
                    header("Idempotency-Key", "idem-missing")
                    contentType(ContentType.Application.Json)
                    setBody("""{"holdId":"$holdId"}""")
                }
            println("DBG confirm-missing: status=${response.status} body=${response.bodyAsText()}")
            response.status shouldBe HttpStatusCode.NotFound
        }
    }

    "seat requires club admin role" {
        val bookingService = mockk<BookingService>()
        registerUser(telegramId = 710L, roles = setOf(Role.PROMOTER), clubs = setOf(1L))
        val bookingId = UUID.randomUUID()

        testApplication {
            applicationDev { testModule(bookingService) }
            val authed = authenticatedClient(telegramId = 710L)
            val response = authed.post("/api/clubs/1/bookings/$bookingId/seat")
            println("DBG seat-forbidden: status=${response.status} body=${response.bodyAsText()}")
            response.status shouldBe HttpStatusCode.Forbidden
        }

        coVerify(exactly = 0) { bookingService.seat(any(), any()) }
    }

    "seat happy path returns 200" {
        val bookingService = mockk<BookingService>()
        registerUser(telegramId = 720L, roles = setOf(Role.CLUB_ADMIN), clubs = setOf(1L))
        val bookingId = UUID.randomUUID()
        coEvery { bookingService.seat(1L, bookingId) } returns
            BookingStatusUpdateResult.Success(bookingRecord(id = bookingId, clubId = 1L, status = BookingStatus.SEATED))

        testApplication {
            applicationDev { testModule(bookingService) }
            val authed = authenticatedClient(telegramId = 720L)
            val response = authed.post("/api/clubs/1/bookings/$bookingId/seat")
            println("DBG seat-success: status=${response.status} body=${response.bodyAsText()}")
            response.status shouldBe HttpStatusCode.OK
            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
            payload["status"]?.jsonPrimitive?.content shouldBe "seated"
        }

        coVerify(exactly = 1) { bookingService.seat(1L, bookingId) }
    }

    "seat conflict returns 409" {
        val bookingService = mockk<BookingService>()
        registerUser(telegramId = 730L, roles = setOf(Role.CLUB_ADMIN), clubs = setOf(1L))
        val bookingId = UUID.randomUUID()
        coEvery { bookingService.seat(1L, bookingId) } returns
            BookingStatusUpdateResult.Conflict(bookingRecord(id = bookingId, clubId = 1L, status = BookingStatus.SEATED))

        testApplication {
            applicationDev { testModule(bookingService) }
            val authed = authenticatedClient(telegramId = 730L)
            val response = authed.post("/api/clubs/1/bookings/$bookingId/seat")
            println("DBG seat-conflict: status=${response.status} body=${response.bodyAsText()}")
            response.status shouldBe HttpStatusCode.Conflict
            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
            payload["error"]?.jsonPrimitive?.content shouldBe "status_conflict"
            payload["status"]?.jsonPrimitive?.content shouldBe BookingStatus.SEATED.name
        }

        coVerify(exactly = 1) { bookingService.seat(1L, bookingId) }
    }

    "no-show happy path returns 200" {
        val bookingService = mockk<BookingService>()
        registerUser(telegramId = 740L, roles = setOf(Role.MANAGER), clubs = setOf(1L))
        val bookingId = UUID.randomUUID()
        coEvery { bookingService.markNoShow(1L, bookingId) } returns
            BookingStatusUpdateResult.Success(bookingRecord(id = bookingId, clubId = 1L, status = BookingStatus.NO_SHOW))

        testApplication {
            applicationDev { testModule(bookingService) }
            val authed = authenticatedClient(telegramId = 740L)
            val response = authed.post("/api/clubs/1/bookings/$bookingId/no-show")
            println("DBG noshow-success: status=${response.status} body=${response.bodyAsText()}")
            response.status shouldBe HttpStatusCode.OK
            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
            payload["status"]?.jsonPrimitive?.content shouldBe "no_show"
        }

        coVerify(exactly = 1) { bookingService.markNoShow(1L, bookingId) }
    }
})
