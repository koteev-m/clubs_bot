package com.example.bot.routes

import com.example.bot.booking.a3.BookingState
import com.example.bot.clubs.Event
import com.example.bot.clubs.InMemoryEventsRepository
import com.example.bot.layout.InMemoryLayoutRepository
import com.example.bot.layout.LayoutRepository
import com.example.bot.layout.Table
import com.example.bot.layout.TableStatus
import com.example.bot.layout.Zone
import com.example.bot.http.ErrorCodes
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.promoter.quotas.InMemoryPromoterQuotaRepository
import com.example.bot.promoter.quotas.PromoterQuota
import com.example.bot.promoter.quotas.PromoterQuotaService
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BookingWithQuotaTest {
    private val now = Instant.parse("2024-06-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val clubId = 1L
    private val eventId = 100L
    private val tableId = 10L
    private val promoterId = 42L

    @Before
    fun setUp() {
        System.setProperty("TELEGRAM_BOT_TOKEN", "test")
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = promoterId) }
    }

    @After
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `promoter hold respects quotas and confirm releases`() = testApplication {
        val quotaRepo = InMemoryPromoterQuotaRepository()
        val quotaService = PromoterQuotaService(quotaRepo, clock)
        quotaService.createOrReplace(
            PromoterQuota(
                clubId = clubId,
                promoterId = promoterId,
                tableId = tableId,
                quota = 1,
                held = 0,
                expiresAt = now.plusSeconds(3600),
            ),
        )
        val bookingState = bookingState(quotaService)

        application {
            install(ContentNegotiation) { json() }
            bookingA3Routes(bookingState = bookingState, botTokenProvider = { "test" })
        }

        val holdResponse =
            client.post("/api/clubs/$clubId/bookings/hold") {
                headers {
                    append("X-Telegram-Init-Data", "init")
                    append("Idempotency-Key", "hold-1")
                    append("X-Debug-Roles", "PROMOTER")
                }
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "tableId":$tableId,
                    "eventId":$eventId,
                    "guestCount":2
                }""",
                )
            }

        assertEquals(HttpStatusCode.OK, holdResponse.status)
        assertEquals(1, quotaRepo.find(clubId, promoterId, tableId)!!.held)
        val bookingId =
            holdResponse.bodyAsJson()["booking"]!!.jsonObject["id"]!!.jsonPrimitive.long

        val exhausted =
            client.post("/api/clubs/$clubId/bookings/hold") {
                headers {
                    append("X-Telegram-Init-Data", "init")
                    append("Idempotency-Key", "hold-2")
                    append("X-Debug-Roles", "PROMOTER")
                }
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "tableId":$tableId,
                    "eventId":$eventId,
                    "guestCount":2
                }""",
                )
            }

        assertEquals(HttpStatusCode.Conflict, exhausted.status)
        assertEquals(
            ErrorCodes.promoter_quota_exhausted,
            exhausted.bodyAsJson()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content,
        )
        assertEquals(1, quotaRepo.find(clubId, promoterId, tableId)!!.held)

        val confirm =
            client.post("/api/clubs/$clubId/bookings/confirm") {
                headers {
                    append("X-Telegram-Init-Data", "init")
                    append("Idempotency-Key", "confirm-1")
                }
                contentType(ContentType.Application.Json)
                setBody("""{ "bookingId": $bookingId }""")
            }

        assertEquals(HttpStatusCode.OK, confirm.status)
        assertEquals(0, quotaRepo.find(clubId, promoterId, tableId)!!.held)
    }

    @Test
    fun `guest hold bypasses promoter quotas`() = testApplication {
        val quotaRepo = InMemoryPromoterQuotaRepository()
        val quotaService = PromoterQuotaService(quotaRepo, clock)
        quotaService.createOrReplace(
            PromoterQuota(
                clubId = clubId,
                promoterId = promoterId,
                tableId = tableId,
                quota = 1,
                held = 0,
                expiresAt = now.plusSeconds(3600),
            ),
        )
        val bookingState = bookingState(quotaService)

        application {
            install(ContentNegotiation) { json() }
            bookingA3Routes(bookingState = bookingState, botTokenProvider = { "test" })
        }

        val guestHold =
            client.post("/api/clubs/$clubId/bookings/hold") {
                headers {
                    append("X-Telegram-Init-Data", "init")
                    append("Idempotency-Key", "hold-guest")
                }
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "tableId":$tableId,
                    "eventId":$eventId,
                    "guestCount":2
                }""",
                )
            }

        assertEquals(HttpStatusCode.OK, guestHold.status)
        assertEquals(0, quotaRepo.find(clubId, promoterId, tableId)!!.held)
    }

    private fun bookingState(quotaService: PromoterQuotaService): BookingState {
        val layout = layoutRepository()
        val events =
            InMemoryEventsRepository(
                listOf(
                    Event(
                        id = eventId,
                        clubId = clubId,
                        startUtc = now.plusSeconds(1800),
                        endUtc = now.plusSeconds(7200),
                        title = "Test",
                        isSpecial = false,
                    ),
                ),
            )
        return BookingState(
            layout,
            events,
            quotaService,
            clock = clock,
            holdTtl = Duration.ofMinutes(10),
            idempotencyTtl = Duration.ofMinutes(30),
            bookingRetention = Duration.ofHours(1),
            watermarkRetention = Duration.ofHours(1),
        )
    }

    private fun layoutRepository(): LayoutRepository {
        val zones = listOf(Zone(id = "main", name = "Main", tags = emptyList(), order = 1))
        val tables = listOf(Table(id = tableId, zoneId = "main", label = "T-1", capacity = 4, minimumTier = "standard", status = TableStatus.FREE))
        return InMemoryLayoutRepository(
            layouts =
                listOf(
                    InMemoryLayoutRepository.LayoutSeed(
                        clubId = clubId,
                        zones = zones,
                        tables = tables,
                        geometryJson = InMemoryLayoutRepository.DEFAULT_GEOMETRY_JSON,
                        statusOverrides = emptyMap(),
                    ),
                ),
            updatedAt = now,
            eventUpdatedAt = mapOf(eventId to now),
        )
    }
 }

private suspend fun io.ktor.client.statement.HttpResponse.bodyAsJson(): JsonObject =
    Json.parseToJsonElement(bodyAsText()).jsonObject
