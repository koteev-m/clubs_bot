package com.example.bot.routes

import com.example.bot.config.BotRunMode
import com.example.bot.data.security.webhook.SuspiciousIpRepository
import com.example.bot.data.security.webhook.SuspiciousIpTable
import com.example.bot.data.security.webhook.WebhookUpdateDedupRepository
import com.example.bot.data.security.webhook.WebhookUpdateDedupTable
import com.example.bot.security.webhook.TELEGRAM_SECRET_HEADER
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private const val SECRET = "secret-token"

class TelegramWebhookRoutesTest :
    StringSpec({
        "same update twice calls handler once" {
            withTelegramWebhookApp { env ->
                val first = env.client.post("/telegram/webhook") { validRequest(101) }
                val second = env.client.post("/telegram/webhook") { validRequest(101) }

                first.status shouldBe HttpStatusCode.OK
                second.status shouldBe HttpStatusCode.OK
                env.handledUpdates.get() shouldBe 1
            }
        }

        "wrong secret returns unauthorized" {
            withTelegramWebhookApp { env ->
                val response =
                    env.client.post("/telegram/webhook") {
                        header(TELEGRAM_SECRET_HEADER, "wrong")
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"update_id":102}""")
                    }

                response.status shouldBe HttpStatusCode.Unauthorized
                env.handledUpdates.get() shouldBe 0
            }
        }

        "wrong content type returns unsupported media type" {
            withTelegramWebhookApp { env ->
                val response =
                    env.client.post("/telegram/webhook") {
                        header(TELEGRAM_SECRET_HEADER, SECRET)
                        header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                        setBody("""{"update_id":103}""")
                    }

                response.status shouldBe HttpStatusCode.UnsupportedMediaType
                env.handledUpdates.get() shouldBe 0
            }
        }

        "oversized body returns payload too large" {
            withTelegramWebhookApp(maxBodySizeBytes = 5) { env ->
                val response =
                    env.client.post("/telegram/webhook") {
                        header(TELEGRAM_SECRET_HEADER, SECRET)
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"update_id":104,"x":"${"a".repeat(20)}"}""")
                    }

                response.status shouldBe HttpStatusCode.PayloadTooLarge
                env.handledUpdates.get() shouldBe 0
            }
        }

        "invalid json returns bad request" {
            withTelegramWebhookApp { env ->
                val response =
                    env.client.post("/telegram/webhook") {
                        header(TELEGRAM_SECRET_HEADER, SECRET)
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("{invalid json")
                    }

                response.status shouldBe HttpStatusCode.BadRequest
                env.handledUpdates.get() shouldBe 0
            }
        }

        "blank secret in webhook mode fails fast and does not expose route" {
            val database =
                Database.connect(
                    url = "jdbc:h2:mem:telegram-webhook-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                    driver = "org.h2.Driver",
                )
            transaction(database) {
                SchemaUtils.create(SuspiciousIpTable, WebhookUpdateDedupTable)
            }
            val suspiciousRepository = SuspiciousIpRepository(database)
            val dedupRepository = WebhookUpdateDedupRepository(database)
            val handledUpdates = AtomicInteger(0)

            testApplication {
                application {
                    telegramWebhookRoutes(
                        expectedSecret = " ",
                        runMode = BotRunMode.WEBHOOK,
                        dedupRepository = dedupRepository,
                        suspiciousIpRepository = suspiciousRepository,
                        onUpdate = { handledUpdates.incrementAndGet() },
                    )
                }

                shouldThrow<IllegalStateException> {
                    startApplication()
                }

                handledUpdates.get() shouldBe 0
            }
        }
    })

private data class TelegramWebhookTestEnv(
    val app: ApplicationTestBuilder,
    val handledUpdates: AtomicInteger,
) {
    val client get() = app.client
}

private fun withTelegramWebhookApp(
    maxBodySizeBytes: Long? = null,
    block: suspend (TelegramWebhookTestEnv) -> Unit,
) {
    val database =
        Database.connect(
            url = "jdbc:h2:mem:telegram-webhook-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
    transaction(database) {
        SchemaUtils.create(SuspiciousIpTable, WebhookUpdateDedupTable)
    }

    val suspiciousRepository = SuspiciousIpRepository(database)
    val dedupRepository = WebhookUpdateDedupRepository(database)
    val handledUpdates = AtomicInteger(0)

    testApplication {
        application {
            telegramWebhookRoutes(
                expectedSecret = SECRET,
                runMode = BotRunMode.WEBHOOK,
                dedupRepository = dedupRepository,
                suspiciousIpRepository = suspiciousRepository,
                security = {
                    if (maxBodySizeBytes != null) {
                        this.maxBodySizeBytes = maxBodySizeBytes
                    }
                },
                onUpdate = { handledUpdates.incrementAndGet() },
            )
        }

        block(TelegramWebhookTestEnv(this, handledUpdates))
    }
}

private fun HttpRequestBuilder.validRequest(updateId: Long) {
    header(TELEGRAM_SECRET_HEADER, SECRET)
    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    setBody("""{"update_id":$updateId}""")
}
