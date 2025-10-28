package com.example.bot.security.webhook

import com.example.bot.data.security.webhook.SuspiciousIpReason
import com.example.bot.data.security.webhook.SuspiciousIpRepository
import com.example.bot.data.security.webhook.SuspiciousIpTable
import com.example.bot.data.security.webhook.WebhookUpdateDedupRepository
import com.example.bot.data.security.webhook.WebhookUpdateDedupTable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

private const val SECRET = "token"

class WebhookSecurityPluginTest :
    StringSpec({
        "missing secret returns 401 and logs" {
            withTestApp { env ->
                val response =
                    env.client.post("/webhook") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"update_id":1}""")
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
                val records = runBlocking { env.suspiciousRepo.listRecent() }
                records shouldHaveSize 1
                records.first().reason shouldBe SuspiciousIpReason.SECRET_MISMATCH
            }
        }

        "invalid content type returns 415" {
            withTestApp { env ->
                val response =
                    env.client.post("/webhook") {
                        header(TELEGRAM_SECRET_HEADER, SECRET)
                        header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                        setBody("""{"update_id":2}""")
                    }
                response.status shouldBe HttpStatusCode.UnsupportedMediaType
            }
        }

        "payload too large is rejected" {
            withTestApp({ maxBodySizeBytes = 5 }) { env ->
                val response =
                    env.client.post("/webhook") {
                        header(TELEGRAM_SECRET_HEADER, SECRET)
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"update_id":3,"data":"${"x".repeat(10)}"}""")
                    }
                response.status shouldBe HttpStatusCode.PayloadTooLarge
            }
        }

        "duplicate update returns 409 and logs after threshold" {
            withTestApp({ duplicateSuspicionThreshold = 2 }) { env ->
                suspend fun post(updateId: Long) =
                    env.client.post("/webhook") {
                        header(TELEGRAM_SECRET_HEADER, SECRET)
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"update_id":$updateId}""")
                    }

                post(5).status shouldBe HttpStatusCode.OK
                post(5).status shouldBe HttpStatusCode.Conflict
                post(5).status shouldBe HttpStatusCode.Conflict
                val response = post(5)
                response.status shouldBe HttpStatusCode.Conflict
                val reasons = runBlocking { env.suspiciousRepo.listRecent().map { it.reason } }
                reasons shouldContain SuspiciousIpReason.DUPLICATE_UPDATE
            }
        }

        "unsupported method returns 405" {
            withTestApp { env ->
                val response = env.client.get("/webhook")
                response.status shouldBe HttpStatusCode.MethodNotAllowed
            }
        }

        "valid update stores dedup entry" {
            withTestApp { env ->
                val response =
                    env.client.post("/webhook") {
                        header(TELEGRAM_SECRET_HEADER, SECRET)
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"update_id":42}""")
                    }
                response.status shouldBe HttpStatusCode.OK
                val record = runBlocking { env.dedupRepo.find(42) }
                record?.duplicateCount shouldBe 0
            }
        }
    })

private data class TestEnvironment(
    val application: ApplicationTestBuilder,
    val suspiciousRepo: SuspiciousIpRepository,
    val dedupRepo: WebhookUpdateDedupRepository,
) {
    val client get() = application.client
}

private fun Application.testModule(
    json: Json,
    suspiciousRepo: SuspiciousIpRepository,
    dedupRepo: WebhookUpdateDedupRepository,
    additionalConfig: WebhookSecurityConfig.() -> Unit,
) {
    routing {
        route("/webhook") {
            install(WebhookSecurity) {
                this.json = json
                secretToken = SECRET
                dedupRepository = dedupRepo
                suspiciousIpRepository = suspiciousRepo
                additionalConfig()
            }

            post {
                call.respond(HttpStatusCode.OK)
            }
        }
    }
}

private fun withTestApp(
    configure: WebhookSecurityConfig.() -> Unit = {},
    block: suspend (TestEnvironment) -> Unit,
) {
    val database =
        Database.connect(
            url = "jdbc:h2:mem:webhook-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
    transaction(database) {
        SchemaUtils.create(SuspiciousIpTable, WebhookUpdateDedupTable)
    }

    val suspiciousRepo = SuspiciousIpRepository(database)
    val dedupRepo = WebhookUpdateDedupRepository(database)
    val json = Json { ignoreUnknownKeys = true }

    testApplication {
        application {
            testModule(json, suspiciousRepo, dedupRepo, configure)
        }

        block(TestEnvironment(this, suspiciousRepo, dedupRepo))
    }
}
