package com.example.bot.routes

import com.example.bot.data.booking.core.BookingOutboxTable
import com.example.bot.data.notifications.NotificationsOutboxTable
import com.example.bot.data.notifications.OutboxStatus
import com.example.bot.data.repo.OutboxAdminRepository
import com.example.bot.data.repo.OutboxAdminRepositoryImpl
import com.example.bot.observability.MetricsProvider
import com.example.bot.plugins.DataSourceHolder
import io.kotest.core.spec.style.StringSpec
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import java.time.OffsetDateTime
import java.time.ZoneOffset

class OutboxAdminMetricsTest : StringSpec() {
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var dataSource: JdbcDataSource
    private lateinit var database: Database

    init {
        beforeTest {
            val setup = prepareDatabase()
            this@OutboxAdminMetricsTest.dataSource = setup.dataSource
            this@OutboxAdminMetricsTest.database = setup.database
            DataSourceHolder.dataSource = this@OutboxAdminMetricsTest.dataSource
            transaction(this@OutboxAdminMetricsTest.database) {
                BookingOutboxTable.deleteAll()
                NotificationsOutboxTable.deleteAll()
            }
        }

        afterTest {
            DataSourceHolder.dataSource = null
        }

        "list request records timer and counter" {
            transaction(this@OutboxAdminMetricsTest.database) {
                val now = OffsetDateTime.now(ZoneOffset.UTC)
                BookingOutboxTable.insert {
                    it[topic] = "payment.refunded"
                    it[payload] = JsonObject(emptyMap())
                    it[status] = "FAILED"
                    it[attempts] = 2
                    it[nextAttemptAt] = now
                    it[lastError] = null
                    it[createdAt] = now.minusHours(1)
                    it[updatedAt] = now.minusMinutes(30)
                }
            }
            val metricsProvider = MetricsProvider(MetricsProvider.simpleRegistry())

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
                outboxAdminRoutes(get(), metricsProvider = metricsProvider, tracer = null)
            }

            val response =
                client.get("/api/admin/outbox") {
                    url.parameters.append("topic", "payment.refunded")
                    url.parameters.append("status", "FAILED")
                }
            response.status shouldBe HttpStatusCode.OK
        }

        val timer =
            metricsProvider.registry
                .find("outbox.admin.list.timer")
                .tags("topic", "payment.refunded", "status", "FAILED")
                .timer()
        timer?.count() shouldBe 1L

        val counter =
            metricsProvider.registry
                .find("outbox.admin.list.total")
                .tags("topic", "payment.refunded", "status", "FAILED")
                .counter()
        counter?.count() shouldBe 1.0
        }

        "replay updates success counter" {
        transaction(this@OutboxAdminMetricsTest.database) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            NotificationsOutboxTable.insert {
                it[kind] = "payment.refunded"
                it[status] = OutboxStatus.FAILED.name
                it[attempts] = 1
                it[nextAttemptAt] = now.minusHours(1)
                it[lastError] = "boom"
                it[createdAt] = now.minusHours(2)
                it[targetChatId] = 99
                it[messageThreadId] = null
                it[payload] = JsonObject(emptyMap())
                it[recipientType] = "chat"
                it[recipientId] = 99
                it[priority] = 100
                it[method] = "TEXT"
                it[clubId] = null
                it[campaignId] = null
                it[language] = null
            }
        }
        val metricsProvider = MetricsProvider(MetricsProvider.simpleRegistry())

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
                outboxAdminRoutes(get(), metricsProvider = metricsProvider, tracer = null)
            }

            val request =
                ReplayRequest(
                    filter = OutboxAdminQuery(topic = "payment.refunded", status = "FAILED"),
                    dryRun = false,
                )
            val response =
                client.post("/api/admin/outbox/replay") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(ReplayRequest.serializer(), request))
                }
            response.status shouldBe HttpStatusCode.OK
        }

        val counter =
            metricsProvider.registry
                .find("outbox.admin.replay.total")
                .tags("result", "success", "topic", "payment.refunded", "status", "FAILED")
                .counter()
        counter?.count() shouldBe 1.0

        val timer =
            metricsProvider.registry
                .find("outbox.admin.replay.timer")
                .tags("topic", "payment.refunded", "status", "FAILED")
                .timer()
        timer?.count() shouldBe 1L
        }
    }
}

private data class MetricsDbSetup(val dataSource: JdbcDataSource, val database: Database)

private fun prepareDatabase(): MetricsDbSetup {
    val ds =
        JdbcDataSource().apply {
            setURL("jdbc:h2:mem:outbox_admin_metrics_${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
    val database = Database.connect(ds)
    transaction(database) {
        SchemaUtils.create(BookingOutboxTable, NotificationsOutboxTable)
    }
    return MetricsDbSetup(ds, database)
}

private fun Application.installTestKoin(database: Database) {
    val module: Module =
        module {
            single { database }
            single<OutboxAdminRepository> { OutboxAdminRepositoryImpl(get()) }
        }
    install(Koin) { modules(module) }
}
