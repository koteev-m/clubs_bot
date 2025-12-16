package com.example.bot.routes

import com.example.bot.booking.a3.BookingState
import com.example.bot.booking.a3.BookingStatus
import com.example.bot.clubs.Club
import com.example.bot.clubs.Event
import com.example.bot.clubs.EventsRepository
import com.example.bot.clubs.InMemoryClubsRepository
import com.example.bot.clubs.InMemoryEventsRepository
import com.example.bot.layout.InMemoryLayoutRepository
import com.example.bot.layout.Table
import com.example.bot.layout.TableStatus
import com.example.bot.layout.Zone
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import io.ktor.client.request.get
import io.ktor.client.request.headers
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
import java.time.ZoneId

class MeBookingsRoutesTest {
    private val states = mutableListOf<BookingState>()

    @Before
    fun setUp() {
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = 42) }
        System.setProperty("TELEGRAM_BOT_TOKEN", "test")
    }

    @After
    fun tearDown() {
        states.forEach { it.close() }
        states.clear()
        resetMiniAppValidator()
    }

    @Test
    fun `list defaults to upcoming`() = runBlockingUnit {
        val clock = TestMutableClock(Instant.parse("2024-05-02T20:00:00Z"))
        val fixture = bookingFixture(clock)
        createBooked(fixture.state, userId = 42)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                meBookingsRoutes(
                    bookingState = fixture.state,
                    eventsRepository = fixture.eventsRepository,
                    clubsRepository = fixture.clubsRepository,
                    qrSecretProvider = { "secret" },
                )
            }

            val response = client.get("/api/me/bookings") {
                headers { append("X-Telegram-Init-Data", "init") }
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals("X-Telegram-Init-Data", response.headers[HttpHeaders.Vary])
            val body = response.bodyAsJson()
            val bookings = body["bookings"]!!.jsonArray
            assertEquals(1, bookings.size)
            val booking = bookings.first().jsonObject
            assertEquals("false", booking["isPast"]!!.jsonPrimitive.content)
            assertEquals("true", booking["canPlusOne"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `status filters past`() = runBlockingUnit {
        val clock = TestMutableClock(Instant.parse("2024-05-02T22:00:00Z"))
        val fixture = bookingFixture(clock)
        createBooked(fixture.state, userId = 42)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                meBookingsRoutes(
                    bookingState = fixture.state,
                    eventsRepository = fixture.eventsRepository,
                    clubsRepository = fixture.clubsRepository,
                    qrSecretProvider = { "secret" },
                )
            }

            val response = client.get("/api/me/bookings?status=past") {
                headers { append("X-Telegram-Init-Data", "init") }
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsJson()
            val bookings = body["bookings"]!!.jsonArray
            assertEquals(1, bookings.size)
            val booking = bookings.first().jsonObject
            assertEquals("true", booking["isPast"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `invalid status returns validation error`() = runBlockingUnit {
        val fixture = bookingFixture()
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                meBookingsRoutes(
                    bookingState = fixture.state,
                    eventsRepository = fixture.eventsRepository,
                    clubsRepository = fixture.clubsRepository,
                    qrSecretProvider = { "secret" },
                )
            }

            val response = client.get("/api/me/bookings?status=bad") {
                headers { append("X-Telegram-Init-Data", "init") }
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsJson()
            assertEquals("validation_error", body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals("X-Telegram-Init-Data", response.headers[HttpHeaders.Vary])
        }
    }

    @Test
    fun `booking is hidden from another user`() = runBlockingUnit {
        val fixture = bookingFixture()
        createBooked(fixture.state, userId = 42)

        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = 777) }

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                meBookingsRoutes(
                    bookingState = fixture.state,
                    eventsRepository = fixture.eventsRepository,
                    clubsRepository = fixture.clubsRepository,
                    qrSecretProvider = { "secret" },
                )
            }

            val response = client.get("/api/me/bookings") {
                headers { append("X-Telegram-Init-Data", "init") }
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val bookings = response.bodyAsJson()["bookings"]!!.jsonArray
            assertTrue(bookings.isEmpty())
        }
    }

    @Test
    fun `qr endpoint rejects foreign user`() = runBlockingUnit {
        val fixture = bookingFixture()
        val bookingId = createBooked(fixture.state, userId = 42)

        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = 777) }

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                meBookingsRoutes(
                    bookingState = fixture.state,
                    eventsRepository = fixture.eventsRepository,
                    clubsRepository = fixture.clubsRepository,
                    qrSecretProvider = { "secret" },
                )
            }

            val response = client.get("/api/bookings/$bookingId/qr") {
                headers { append("X-Telegram-Init-Data", "init") }
            }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            val body = response.bodyAsJson()
            assertEquals("forbidden", body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }
    }

    @Test
    fun `qr endpoint rejects non booked status`() = runBlockingUnit {
        val fixture = bookingFixture()
        val bookingId = createHoldOnly(fixture.state, userId = 42)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                meBookingsRoutes(
                    bookingState = fixture.state,
                    eventsRepository = fixture.eventsRepository,
                    clubsRepository = fixture.clubsRepository,
                    qrSecretProvider = { "secret" },
                )
            }

            val response = client.get("/api/bookings/$bookingId/qr") {
                headers { append("X-Telegram-Init-Data", "init") }
            }

            assertEquals(HttpStatusCode.Conflict, response.status)
            val body = response.bodyAsJson()
            assertEquals("invalid_state", body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `ics endpoint rejects foreign user`() = runBlockingUnit {
        val fixture = bookingFixture()
        val bookingId = createBooked(fixture.state, userId = 42)

        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = 777) }

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                meBookingsRoutes(
                    bookingState = fixture.state,
                    eventsRepository = fixture.eventsRepository,
                    clubsRepository = fixture.clubsRepository,
                    qrSecretProvider = { "secret" },
                )
            }

            val response = client.get("/api/bookings/$bookingId/ics") {
                headers { append("X-Telegram-Init-Data", "init") }
            }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            val body = response.bodyAsJson()
            assertEquals("forbidden", body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals("X-Telegram-Init-Data", response.headers[HttpHeaders.Vary])
            assertEquals(null, response.headers[HttpHeaders.ETag])
        }
    }

    @Test
    fun `ics endpoint returns not found for missing booking`() = runBlockingUnit {
        val fixture = bookingFixture()

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                meBookingsRoutes(
                    bookingState = fixture.state,
                    eventsRepository = fixture.eventsRepository,
                    clubsRepository = fixture.clubsRepository,
                    qrSecretProvider = { "secret" },
                )
            }

            val response = client.get("/api/bookings/9999/ics") {
                headers { append("X-Telegram-Init-Data", "init") }
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
            val body = response.bodyAsJson()
            assertEquals("not_found", body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals("X-Telegram-Init-Data", response.headers[HttpHeaders.Vary])
            assertEquals(null, response.headers[HttpHeaders.ETag])
        }
    }

    @Test
    fun `qr endpoint fails when secret is blank`() = runBlockingUnit {
        val fixture = bookingFixture()
        val bookingId = createBooked(fixture.state, userId = 42)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                meBookingsRoutes(
                    bookingState = fixture.state,
                    eventsRepository = fixture.eventsRepository,
                    clubsRepository = fixture.clubsRepository,
                    qrSecretProvider = { "" },
                )
            }

            val response = client.get("/api/bookings/$bookingId/qr") {
                headers { append("X-Telegram-Init-Data", "init") }
            }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val body = response.bodyAsJson()
            assertEquals("internal_error", body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }
    }

    @Test
    fun `qr endpoint supports etag`() = runBlockingUnit {
        val fixture = bookingFixture()
        val bookingId = createBooked(fixture.state, userId = 42)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                meBookingsRoutes(
                    bookingState = fixture.state,
                    eventsRepository = fixture.eventsRepository,
                    clubsRepository = fixture.clubsRepository,
                    qrSecretProvider = { "secret" },
                )
            }

            val first = client.get("/api/bookings/$bookingId/qr") {
                headers { append("X-Telegram-Init-Data", "init") }
            }
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals("max-age=60, must-revalidate", first.headers[HttpHeaders.CacheControl])
            assertEquals("X-Telegram-Init-Data", first.headers[HttpHeaders.Vary])
            val etag = first.headers[HttpHeaders.ETag]!!
            val qrPayload = first.bodyAsJson()["qrPayload"]!!.jsonPrimitive.content
            assertTrue(qrPayload.startsWith("BK:"))

            val cached = client.get("/api/bookings/$bookingId/qr") {
                headers {
                    append("X-Telegram-Init-Data", "init")
                    append(HttpHeaders.IfNoneMatch, etag)
                }
            }
            assertEquals(HttpStatusCode.NotModified, cached.status)
            assertEquals("max-age=60, must-revalidate", cached.headers[HttpHeaders.CacheControl])
            assertEquals("X-Telegram-Init-Data", cached.headers[HttpHeaders.Vary])
        }
    }

    @Test
    fun `ics endpoint emits calendar`() = runBlockingUnit {
        val fixture = bookingFixture()
        val bookingId = createBooked(fixture.state, userId = 42)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                meBookingsRoutes(
                    bookingState = fixture.state,
                    eventsRepository = fixture.eventsRepository,
                    clubsRepository = fixture.clubsRepository,
                    qrSecretProvider = { "secret" },
                )
            }

            val first = client.get("/api/bookings/$bookingId/ics") {
                headers { append("X-Telegram-Init-Data", "init") }
            }
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals("max-age=60, must-revalidate", first.headers[HttpHeaders.CacheControl])
            assertEquals("X-Telegram-Init-Data", first.headers[HttpHeaders.Vary])
            assertEquals(ContentType.parse("text/calendar; charset=utf-8"), first.contentType())
            val body = first.bodyAsText()
            assertTrue(body.contains("BEGIN:VCALENDAR"))
            assertTrue(body.contains("BEGIN:VEVENT"))
            assertTrue(body.contains("UID:"))
            assertTrue(body.contains("DTSTART:"))
            assertTrue(body.contains("DTEND:"))

            val etag = first.headers[HttpHeaders.ETag]!!
            val cached = client.get("/api/bookings/$bookingId/ics") {
                headers {
                    append("X-Telegram-Init-Data", "init")
                    append(HttpHeaders.IfNoneMatch, etag)
                }
            }
            assertEquals(HttpStatusCode.NotModified, cached.status)
            assertEquals("max-age=60, must-revalidate", cached.headers[HttpHeaders.CacheControl])
            assertEquals("X-Telegram-Init-Data", cached.headers[HttpHeaders.Vary])
        }
    }

    private fun bookingFixture(
        clock: Clock = Clock.fixed(Instant.parse("2024-05-02T20:30:00Z"), ZoneId.of("UTC")),
        tables: List<Table> = listOf(Table(id = 1, zoneId = "vip", label = "VIP-1", capacity = 4, minimumTier = "vip", status = TableStatus.FREE)),
    ): MyBookingFixture {
        val zones = listOf(Zone(id = "vip", name = "VIP", tags = emptyList(), order = 1))
        val layout =
            InMemoryLayoutRepository(
                layouts =
                    listOf(
                        InMemoryLayoutRepository.LayoutSeed(
                            clubId = 1,
                            zones = zones,
                            tables = tables,
                            geometryJson = InMemoryLayoutRepository.DEFAULT_GEOMETRY_JSON,
                        ),
                    ),
                clock = clock,
            )
        val clubs = InMemoryClubsRepository(listOf(Club(id = 1, city = "msc", name = "Club", genres = emptyList(), tags = emptyList(), logoUrl = null)))
        val events: EventsRepository =
            InMemoryEventsRepository(
                listOf(
                    Event(
                        id = 100,
                        clubId = 1,
                        startUtc = Instant.parse("2024-05-02T21:00:00Z"),
                        endUtc = Instant.parse("2024-05-02T23:00:00Z"),
                        title = "Test",
                        isSpecial = false,
                    ),
                ),
            )
        val state = BookingState(layout, events, clock = clock)
        states += state
        return MyBookingFixture(state, events, clubs)
    }

    private suspend fun createBooked(state: BookingState, userId: Long): Long {
        val hold =
            state.hold(
                userId = userId,
                clubId = 1,
                tableId = 1,
                eventId = 100,
                guestCount = 2,
                idempotencyKey = "idem-$userId-${System.nanoTime()}",
                requestHash = "hash",
            )
        val booking = (hold as com.example.bot.booking.a3.HoldResult.Success).booking
        val confirm =
            state.confirm(
                userId = userId,
                clubId = 1,
                bookingId = booking.id,
                idempotencyKey = "confirm-${booking.id}",
                requestHash = "confirm",
            )
        val confirmed = (confirm as com.example.bot.booking.a3.ConfirmResult.Success).booking
        return confirmed.id
    }

    private suspend fun createHoldOnly(state: BookingState, userId: Long): Long {
        val hold =
            state.hold(
                userId = userId,
                clubId = 1,
                tableId = 1,
                eventId = 100,
                guestCount = 2,
                idempotencyKey = "hold-only-$userId-${System.nanoTime()}",
                requestHash = "hash",
            ) as com.example.bot.booking.a3.HoldResult.Success
        return hold.booking.id
    }
}

private data class MyBookingFixture(
    val state: BookingState,
    val eventsRepository: EventsRepository,
    val clubsRepository: InMemoryClubsRepository,
)

private class TestMutableClock(
    private var current: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = current
}

private suspend fun io.ktor.client.statement.HttpResponse.bodyAsJson(): JsonObject =
    Json.parseToJsonElement(bodyAsText()).jsonObject

private fun runBlockingUnit(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
