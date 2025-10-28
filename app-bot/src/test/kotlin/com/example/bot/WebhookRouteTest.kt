package com.example.bot

import com.example.bot.data.security.webhook.SuspiciousIpRepository
import com.example.bot.data.security.webhook.SuspiciousIpTable
import com.example.bot.data.security.webhook.WebhookUpdateDedupRepository
import com.example.bot.data.security.webhook.WebhookUpdateDedupTable
import com.example.bot.security.webhook.TELEGRAM_SECRET_HEADER
import com.example.bot.telegram.TelegramClient
import com.example.bot.webhook.WebhookReply
import com.example.bot.webhook.webhookRoute
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

private const val SECRET = "token"

class WebhookRouteTest :
    StringSpec({
        "inline reply is returned to caller" {
            val db =
                Database.connect(
                    url = "jdbc:h2:mem:webhook-route-${System.nanoTime()};DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver",
                )
            transaction(db) { SchemaUtils.create(SuspiciousIpTable, WebhookUpdateDedupTable) }
            val suspiciousRepo = SuspiciousIpRepository(db)
            val dedupRepo = WebhookUpdateDedupRepository(db)
            val telegramClient = mockk<TelegramClient>(relaxed = true)

            val serializer = Json { ignoreUnknownKeys = true }
            testApplication {
                application {
                    install(ContentNegotiation) {
                        json(json = serializer)
                    }
                    routing {
                        webhookRoute(
                            security = {
                                secretToken = SECRET
                                dedupRepository = dedupRepo
                                suspiciousIpRepository = suspiciousRepo
                            },
                            handler = { WebhookReply.Inline(mapOf("status" to "ok")) },
                            client = telegramClient,
                            json = serializer,
                        )
                    }
                }

                val response =
                    client.post("/webhook") {
                        header(TELEGRAM_SECRET_HEADER, SECRET)
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"update_id":11}""")
                    }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        "async reply triggers telegram client" {
            val db =
                Database.connect(
                    url = "jdbc:h2:mem:webhook-route-${System.nanoTime()};DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver",
                )
            transaction(db) { SchemaUtils.create(SuspiciousIpTable, WebhookUpdateDedupTable) }
            val suspiciousRepo = SuspiciousIpRepository(db)
            val dedupRepo = WebhookUpdateDedupRepository(db)
            val telegramClient = mockk<TelegramClient>(relaxed = true)

            val serializer = Json { ignoreUnknownKeys = true }
            testApplication {
                application {
                    install(ContentNegotiation) {
                        json(json = serializer)
                    }
                    routing {
                        webhookRoute(
                            security = {
                                secretToken = SECRET
                                dedupRepository = dedupRepo
                                suspiciousIpRepository = suspiciousRepo
                            },
                            handler = { WebhookReply.Async(mapOf("method" to "send")) },
                            client = telegramClient,
                            json = serializer,
                        )
                    }
                }

                val response =
                    client.post("/webhook") {
                        header(TELEGRAM_SECRET_HEADER, SECRET)
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"update_id":22}""")
                    }
                response.status shouldBe HttpStatusCode.OK
                // allow launched coroutine to run
                runBlocking { delay(10) }
                coVerify { telegramClient.send(mapOf("method" to "send")) }
            }
        }
    })
