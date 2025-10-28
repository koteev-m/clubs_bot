package com.example.bot

import com.example.bot.di.notifyModule
import com.example.bot.plugins.DataSourceHolder
import com.example.bot.plugins.configureSecurity
import com.example.bot.routes.CampaignDto
import com.example.bot.routes.CampaignService
import com.example.bot.routes.CampaignStatus
import com.example.bot.routes.TxNotifyService
import com.example.bot.routes.notifyRoutes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

private object UsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val telegramUserId = long("telegram_user_id")
    val username = text("username").nullable()

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

class NotifyRoutesTest :
    StringSpec({
        fun Application.testModule() {
            val dataSource = prepareSecurityData()
            DataSourceHolder.dataSource = dataSource
            install(ContentNegotiation) { json() }
            install(Koin) { modules(notifyModule) }
            configureSecurity()
            notifyRoutes(
                tx = get<TxNotifyService>(),
                campaigns = get<CampaignService>(),
            )
        }

        "enqueue tx" {
            testApplication {
                application { testModule() }
                val client = createClient { }
                val resp =
                    client.post("/api/notify/tx") {
                        header("X-Telegram-Id", "1")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                                {
                                  "chatId": 1,
                                  "messageThreadId": null,
                                  "method": "TEXT",
                                  "text": "hi",
                                  "parseMode": null,
                                  "photoUrl": null,
                                  "album": null,
                                  "buttons": null,
                                  "dedupKey": null
                                }
                            """
                                .trimIndent(),
                        )
                    }
                resp.status shouldBe HttpStatusCode.Accepted
            }
        }

        "campaign lifecycle" {
            testApplication {
                application { testModule() }
                val client = createClient { }

                val create =
                    client.post("/api/campaigns") {
                        header("X-Telegram-Id", "1")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"t","text":"hello"}""")
                    }
                create.status shouldBe HttpStatusCode.OK
                val dto = Json.decodeFromString<CampaignDto>(create.bodyAsText())
                dto.status shouldBe CampaignStatus.DRAFT

                val id = dto.id

                val update =
                    client.put("/api/campaigns/$id") {
                        header("X-Telegram-Id", "1")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"t2"}""")
                    }
                update.status shouldBe HttpStatusCode.OK

                client.post("/api/campaigns/$id:preview?user_id=1") { header("X-Telegram-Id", "1") }

                client.post("/api/campaigns/$id:schedule") {
                    header("X-Telegram-Id", "1")
                    contentType(ContentType.Application.Json)
                    setBody("""{"cron":"* * * * *"}""")
                }

                client.post("/api/campaigns/$id:send-now") { header("X-Telegram-Id", "1") }

                client.post("/api/campaigns/$id:pause") { header("X-Telegram-Id", "1") }

                client.post("/api/campaigns/$id:resume") { header("X-Telegram-Id", "1") }

                val get = client.get("/api/campaigns/$id") { header("X-Telegram-Id", "1") }
                get.status shouldBe HttpStatusCode.OK

                val list = client.get("/api/campaigns") { header("X-Telegram-Id", "1") }
                list.status shouldBe HttpStatusCode.OK
            }
        }
    })

private fun prepareSecurityData(): JdbcDataSource {
    val dbName = "notify_${UUID.randomUUID()}"
    val dataSource =
        JdbcDataSource().apply {
            setURL("jdbc:h2:mem:$dbName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
    Flyway
        .configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/common", "classpath:db/migration/h2")
        .target("8")
        .load()
        .migrate()
    val database = Database.connect(dataSource)
    transaction(database) {
        listOf(
            "action",
            "result",
        ).forEach { column ->
            exec("""ALTER TABLE audit_log ALTER COLUMN $column RENAME TO "$column"""")
        }
        exec("ALTER TABLE audit_log ALTER COLUMN resource_id DROP NOT NULL")
        val userId =
            UsersTable.insert {
                it[telegramUserId] = 1L
                it[username] = "tester"
            } get UsersTable.id
        UserRolesTable.insert {
            it[this.userId] = userId
            it[roleCode] = "GLOBAL_ADMIN"
            it[scopeType] = "GLOBAL"
            it[scopeClubId] = null
        }
    }
    return dataSource
}
