package com.example.bot.routes

import com.example.bot.data.booking.core.BookingOutboxTable
import com.example.bot.data.notifications.NotificationsOutboxTable
import com.example.bot.data.notifications.OutboxStatus
import com.example.bot.data.repo.OutboxAdminRepository
import com.example.bot.data.repo.OutboxAdminRepositoryImpl
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.plugins.DataSourceHolder
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import java.time.OffsetDateTime
import java.time.ZoneOffset

class OutboxAdminRoutesTest : StringSpec() {
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var dataSource: JdbcDataSource
    private lateinit var database: Database

    init {
        beforeTest {
            val setup = prepareDatabase()
            this@OutboxAdminRoutesTest.dataSource = setup.dataSource
            this@OutboxAdminRoutesTest.database = setup.database
            DataSourceHolder.dataSource = this@OutboxAdminRoutesTest.dataSource
            transaction(this@OutboxAdminRoutesTest.database) {
                BookingOutboxTable.deleteAll()
                NotificationsOutboxTable.deleteAll()
            }
        }

        afterTest {
            DataSourceHolder.dataSource = null
        }

        "list with filters returns items and stats" {
            transaction(this@OutboxAdminRoutesTest.database) {
                val now = OffsetDateTime.now(ZoneOffset.UTC)
                BookingOutboxTable.insert {
                    it[topic] = "payment.refunded"
                    it[payload] = JsonObject(emptyMap())
                    it[status] = "FAILED"
                    it[attempts] = 3
                    it[nextAttemptAt] = now
                    it[lastError] = "boom"
                    it[createdAt] = now.minusHours(2)
                    it[updatedAt] = now.minusHours(1)
                }
                BookingOutboxTable.insert {
                    it[topic] = "payment.refunded"
                    it[payload] = JsonObject(emptyMap())
                    it[status] = "SENT"
                    it[attempts] = 1
                    it[nextAttemptAt] = now
                    it[lastError] = null
                    it[createdAt] = now.minusHours(3)
                    it[updatedAt] = now.minusHours(2)
                }
                NotificationsOutboxTable.insert {
                    it[kind] = "promo.notification"
                    it[status] = OutboxStatus.FAILED.name
                    it[attempts] = 4
                    it[nextAttemptAt] = now
                    it[lastError] = "oops"
                    it[createdAt] = now.minusHours(4)
                    it[targetChatId] = 1
                    it[messageThreadId] = null
                    it[payload] = JsonObject(emptyMap())
                    it[recipientType] = "chat"
                    it[recipientId] = 1
                    it[priority] = 100
                    it[method] = "TEXT"
                    it[clubId] = null
                    it[campaignId] = null
                    it[language] = null
                }
            }

        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "app.profile" to "DEV",
                        "app.OUTBOX_ADMIN_ENABLED" to "true",
                    )
            }
            application {
                install(ContentNegotiation) { json() }
                installTestKoin(database)
                outboxAdminRoutes(get(), metricsProvider = null, tracer = null)
            }

            val response =
                client.get("/api/admin/outbox") {
                    url.parameters.append("topic", "payment.refunded")
                    url.parameters.append("status", "FAILED")
                    url.parameters.append("attemptsMin", "2")
                    url.parameters.append("limit", "5")
                    url.parameters.append("sort", "created_at")
                    url.parameters.append("dir", "desc")
                }

            response.status shouldBe HttpStatusCode.OK
            val payload = json.decodeFromString(OutboxAdminPage.serializer(), response.bodyAsText())
            payload.total shouldBe 1
            payload.items shouldHaveSize 1
            payload.items.first().topic shouldBe "payment.refunded"
            payload.stats?.total shouldBe 1
            payload.stats?.byStatus?.get("FAILED") shouldBe 1
        }
        }

        "replay by ids dry run does not mutate" {
        val id =
            transaction(this@OutboxAdminRoutesTest.database) {
                val now = OffsetDateTime.now(ZoneOffset.UTC)
                BookingOutboxTable.insert {
                    it[topic] = "payment.refunded"
                    it[payload] = JsonObject(emptyMap())
                    it[status] = "FAILED"
                    it[attempts] = 5
                    it[nextAttemptAt] = now
                    it[lastError] = "fail"
                    it[createdAt] = now.minusDays(1)
                    it[updatedAt] = now.minusHours(10)
                }[BookingOutboxTable.id]
            }

        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "app.profile" to "DEV",
                        "app.OUTBOX_ADMIN_ENABLED" to "true",
                    )
            }
            application {
                install(ContentNegotiation) { json() }
                installTestKoin(database)
                outboxAdminRoutes(get(), metricsProvider = null, tracer = null)
            }

            val response =
                client.post("/api/admin/outbox/replay") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            ReplayRequest.serializer(),
                            ReplayRequest(ids = listOf(id), dryRun = true),
                        ),
                    )
                }

            response.status shouldBe HttpStatusCode.OK
            val body = json.decodeFromString(ReplayResponse.serializer(), response.bodyAsText())
            body.candidates shouldBe 1
            body.affected shouldBe 0

            val statusAfter =
                transaction(this@OutboxAdminRoutesTest.database) {
                    BookingOutboxTable.selectAll().single()[BookingOutboxTable.status]
                }
            statusAfter shouldBe "FAILED"
        }
        }

        "replay by filter updates status" {
        transaction(this@OutboxAdminRoutesTest.database) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            BookingOutboxTable.insert {
                it[topic] = "payment.refunded"
                it[payload] = JsonObject(emptyMap())
                it[status] = "FAILED"
                it[attempts] = 2
                it[nextAttemptAt] = now.minusHours(5)
                it[lastError] = "fail"
                it[createdAt] = now.minusDays(2)
                it[updatedAt] = now.minusDays(2)
            }
            NotificationsOutboxTable.insert {
                it[kind] = "payment.refunded"
                it[status] = OutboxStatus.FAILED.name
                it[attempts] = 1
                it[nextAttemptAt] = now.minusHours(4)
                it[lastError] = "oops"
                it[createdAt] = now.minusDays(3)
                it[targetChatId] = 42
                it[messageThreadId] = null
                it[payload] = JsonObject(emptyMap())
                it[recipientType] = "chat"
                it[recipientId] = 42
                it[priority] = 100
                it[method] = "TEXT"
                it[clubId] = null
                it[campaignId] = null
                it[language] = null
            }
        }

        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "app.profile" to "DEV",
                        "app.OUTBOX_ADMIN_ENABLED" to "true",
                    )
            }
            application {
                install(ContentNegotiation) { json() }
                installTestKoin(database)
                outboxAdminRoutes(get(), metricsProvider = null, tracer = null)
            }

            val requestPayload =
                ReplayRequest(
                    filter = OutboxAdminQuery(topic = "payment.refunded", status = "FAILED"),
                    dryRun = false,
                )

            val response =
                client.post("/api/admin/outbox/replay") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(ReplayRequest.serializer(), requestPayload))
                }

            response.status shouldBe HttpStatusCode.OK
            val body = json.decodeFromString(ReplayResponse.serializer(), response.bodyAsText())
            body.dryRun shouldBe false
            body.affected shouldBe 2

            transaction(this@OutboxAdminRoutesTest.database) {
                val booking = BookingOutboxTable.selectAll().single()[BookingOutboxTable.status]
                booking shouldBe "NEW"
                val notification = NotificationsOutboxTable.selectAll().single()[NotificationsOutboxTable.status]
                notification shouldBe OutboxStatus.NEW.name
            }
        }
        }

        "rbac denies without role when enabled" {
        transaction(this@OutboxAdminRoutesTest.database) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            BookingOutboxTable.insert {
                it[topic] = "payment.refunded"
                it[payload] = JsonObject(emptyMap())
                it[status] = "FAILED"
                it[attempts] = 1
                it[nextAttemptAt] = now
                it[lastError] = "fail"
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "app.profile" to "DEV",
                        "app.OUTBOX_ADMIN_ENABLED" to "true",
                        "app.RBAC_ENABLED" to "true",
                    )
            }
            application {
                install(ContentNegotiation) { json() }
                installTestKoin(database, roles = emptySet())
                install(RbacPlugin) {
                    userRepository = get()
                    userRoleRepository = get()
                    auditLogRepository = relaxedAuditRepository()
                    principalExtractor = { TelegramPrincipal(42, "tester") }
                }
                outboxAdminRoutes(get(), metricsProvider = null, tracer = null)
            }

            val response = client.get("/api/admin/outbox")
            response.status shouldBe HttpStatusCode.Forbidden
        }
        }

        "rbac allows admin role" {
        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "app.profile" to "DEV",
                        "app.OUTBOX_ADMIN_ENABLED" to "true",
                        "app.RBAC_ENABLED" to "true",
                    )
            }
            application {
                install(ContentNegotiation) { json() }
                installTestKoin(database, roles = setOf(Role.GLOBAL_ADMIN))
                install(RbacPlugin) {
                    userRepository = get()
                    userRoleRepository = get()
                    auditLogRepository = relaxedAuditRepository()
                    principalExtractor = { TelegramPrincipal(42, "tester") }
                }
                outboxAdminRoutes(get(), metricsProvider = null, tracer = null)
            }

            val response = client.get("/api/admin/outbox")
            response.status shouldBe HttpStatusCode.OK
        }
        }
    }
}

private data class DbSetup(val dataSource: JdbcDataSource, val database: Database)

private fun prepareDatabase(): DbSetup {
    val ds =
        JdbcDataSource().apply {
            setURL("jdbc:h2:mem:outbox_admin_${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
    val database = Database.connect(ds)
    transaction(database) {
        SchemaUtils.create(BookingOutboxTable, NotificationsOutboxTable)
    }
    return DbSetup(ds, database)
}

private fun Application.installTestKoin(
    database: Database,
    roles: Set<Role> = emptySet(),
) {
    val testModule: Module =
        module {
            single { database }
            single<OutboxAdminRepository> { OutboxAdminRepositoryImpl(get()) }
            single<UserRepository> { StubUserRepository() }
            single<UserRoleRepository> { StubUserRoleRepository(roles) }
        }
    install(Koin) { modules(testModule) }
}

private class StubUserRepository : UserRepository {
    override suspend fun getByTelegramId(id: Long): User? = User(id = 1, telegramId = id, username = "tester")
}

private class StubUserRoleRepository(private val roles: Set<Role>) : UserRoleRepository {
    override suspend fun listRoles(userId: Long): Set<Role> = roles

    override suspend fun listClubIdsFor(userId: Long): Set<Long> = emptySet()
}

private fun relaxedAuditRepository(): com.example.bot.data.booking.core.AuditLogRepository = mockk(relaxed = true)
