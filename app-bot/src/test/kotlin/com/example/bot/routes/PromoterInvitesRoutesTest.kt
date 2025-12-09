package com.example.bot.routes

import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.promoter.invites.InMemoryPromoterInviteRepository
import com.example.bot.promoter.invites.PromoterInviteService
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PromoterInvitesRoutesTest {
    private val clock = Clock.fixed(Instant.parse("2024-06-01T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var repository: InMemoryPromoterInviteRepository
    private lateinit var service: PromoterInviteService

    @Before
    fun setUp() {
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = 42) }
        System.setProperty("TELEGRAM_BOT_TOKEN", "test")
        repository = InMemoryPromoterInviteRepository()
        service = PromoterInviteService(repository, clock)
    }

    @After
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `issue invite returns qr payload`() = runBlockingUnit {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                promoterInvitesRoutes(
                    promoterInviteService = service,
                    qrSecretProvider = { "secret" },
                    clock = clock,
                )
            }

            val response = client.post("/api/promoter/invites") {
                headers { append("X-Telegram-Init-Data", "init") }
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "clubId":1,
                    "eventId":100,
                    "guestName":"Guest",
                    "guestCount":2
                }""",
                )
            }

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals("X-Telegram-Init-Data", response.headers[HttpHeaders.Vary])
            val body = response.bodyAsJson()
            val invite = body["invite"]!!.jsonObject
            assertEquals("Guest", invite["guestName"]!!.jsonPrimitive.content)
            val qr = body["qr"]!!.jsonObject
            assertTrue(qr["payload"]!!.jsonPrimitive.content.startsWith("INV:"))
        }
    }

    @Test
    fun `list invite filters by promoter`() = runBlockingUnit {
        service.issueInvite(promoterId = 42, clubId = 1, eventId = 100, guestName = "Mine", guestCount = 2)
        service.issueInvite(promoterId = 99, clubId = 1, eventId = 100, guestName = "Other", guestCount = 2)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                promoterInvitesRoutes(promoterInviteService = service, qrSecretProvider = { "secret" }, clock = clock)
            }

            val response = client.get("/api/promoter/invites?eventId=100") {
                headers { append("X-Telegram-Init-Data", "init") }
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val invites = response.bodyAsJson()["invites"]!!.jsonArray
            assertEquals(1, invites.size)
            val invite = invites.first().jsonObject
            assertEquals("Mine", invite["guestName"]!!.jsonPrimitive.content)
            assertEquals(1, invite["timeline"]!!.jsonArray.size)
        }
    }

    @Test
    fun `list invite fails on invalid eventId`() = runBlockingUnit {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                promoterInvitesRoutes(promoterInviteService = service, qrSecretProvider = { "secret" }, clock = clock)
            }

            val response = client.get("/api/promoter/invites?eventId=0") {
                headers { append("X-Telegram-Init-Data", "init") }
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val error = response.bodyAsJson()["error"]!!.jsonObject
            assertEquals("validation_error", error["code"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `revoke invite lifecycle`() = runBlockingUnit {
        val issued = service.issueInvite(promoterId = 42, clubId = 1, eventId = 100, guestName = "ToRevoke", guestCount = 2)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                promoterInvitesRoutes(promoterInviteService = service, qrSecretProvider = { "secret" }, clock = clock)
            }

            val response = client.post("/api/promoter/invites/${issued.id}/revoke") {
                headers { append("X-Telegram-Init-Data", "init") }
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val invite = response.bodyAsJson()
            assertEquals("revoked", invite["status"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `revoke invite errors`() = runBlockingUnit {
        val issued = service.issueInvite(promoterId = 42, clubId = 1, eventId = 100, guestName = "ToFail", guestCount = 2)
        service.markArrivedById(issued.id)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                promoterInvitesRoutes(promoterInviteService = service, qrSecretProvider = { "secret" }, clock = clock)
            }

            val conflict = client.post("/api/promoter/invites/${issued.id}/revoke") {
                headers { append("X-Telegram-Init-Data", "init") }
            }
            assertEquals(HttpStatusCode.Conflict, conflict.status)

            overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = 999) }
            val forbiddenResp = client.post("/api/promoter/invites/${issued.id}/revoke") {
                headers { append("X-Telegram-Init-Data", "init") }
            }
            assertEquals(HttpStatusCode.Forbidden, forbiddenResp.status)

            val notFound = client.post("/api/promoter/invites/9999/revoke") {
                headers { append("X-Telegram-Init-Data", "init") }
            }
            assertEquals(HttpStatusCode.NotFound, notFound.status)
        }
    }

    @Test
    fun `csv export returns header and rows`() = runBlockingUnit {
        service.issueInvite(promoterId = 42, clubId = 1, eventId = 100, guestName = "Csv, \"Guest\"", guestCount = 3)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                promoterInvitesRoutes(promoterInviteService = service, qrSecretProvider = { "secret" }, clock = clock)
            }

            val response = client.get("/api/promoter/invites/export.csv?eventId=100") {
                headers { append("X-Telegram-Init-Data", "init") }
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("text/csv; charset=utf-8", response.headers[HttpHeaders.ContentType])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals("X-Telegram-Init-Data", response.headers[HttpHeaders.Vary])
            val lines = response.bodyAsText().lines()
            assertTrue(lines.first().startsWith("inviteId,promoterId,clubId"))
            assertEquals(2, lines.size)
            assertTrue(lines[1].contains("\"Csv, \"\"Guest\"\"\""))
        }
    }
}

private suspend fun io.ktor.client.statement.HttpResponse.bodyAsJson(): JsonObject =
    Json.parseToJsonElement(bodyAsText()).jsonObject

private fun runBlockingUnit(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
