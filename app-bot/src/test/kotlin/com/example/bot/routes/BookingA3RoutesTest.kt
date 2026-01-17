package com.example.bot.routes

import com.example.bot.booking.a3.BookingError
import com.example.bot.booking.a3.BookingState
import com.example.bot.booking.a3.BookingStatus
import com.example.bot.booking.a3.ConfirmResult
import com.example.bot.booking.a3.HoldResult
import com.example.bot.booking.a3.PlusOneCanonicalPayload
import com.example.bot.booking.a3.PlusOneResult
import com.example.bot.booking.a3.hashRequestCanonical
import com.example.bot.clubs.Event
import com.example.bot.clubs.EventsRepository
import com.example.bot.clubs.InMemoryEventsRepository
import com.example.bot.layout.BookingAwareLayoutRepository
import com.example.bot.layout.LayoutAssetsRepository
import com.example.bot.layout.InMemoryLayoutRepository
import com.example.bot.layout.LayoutRepository
import com.example.bot.layout.Table
import com.example.bot.layout.TableStatus
import com.example.bot.layout.Zone
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.plugins.TelegramMiniUser
import io.ktor.client.request.headers
import io.ktor.client.request.head
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class BookingA3RoutesTest {
    private val emptyAssets =
        object : LayoutAssetsRepository {
            override suspend fun loadGeometry(clubId: Long, fingerprint: String): String? = null
        }

    private val json = Json { ignoreUnknownKeys = true }
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
    fun `happy path hold confirm plus one`() = runBlocking {
        val clock = MutableClock(Instant.parse("2024-05-02T20:30:00Z"))
        val state = bookingState(clock)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                bookingA3Routes(state, botTokenProvider = { "token" })
            }

            val hold = hold(client, guests = 2)
            assertEquals(HttpStatusCode.OK, hold.status)
            val bookingId = hold.bookingId()
            val arrivalWindow = hold.bodyJson()["arrivalWindow"]!!.jsonArray
            assertEquals(2, arrivalWindow.size)

            val confirm = confirm(client, bookingId)
            assertEquals(HttpStatusCode.OK, confirm.status)

            val plusOne = plusOne(client, bookingId)
            assertEquals(HttpStatusCode.OK, plusOne.status)
            val booked = plusOne.bodyJson()["booking"]!!.jsonObject
            assertEquals("BOOKED", booked["status"]!!.jsonPrimitive.content)
            assertEquals(3, booked["guestCount"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun `idempotency conflict on different body`() = runBlocking {
        val state = bookingState()
        testApplication {
            application { install(ContentNegotiation) { json() }; bookingA3Routes(state, botTokenProvider = { "token" }) }

            val key = "same-key"
            val first = hold(client, guests = 2, idem = key)
            assertEquals(HttpStatusCode.OK, first.status)
            val second = hold(client, guests = 3, idem = key)
            assertEquals(HttpStatusCode.Conflict, second.status)
            val errorCode = second.bodyJson()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
            assertEquals("idempotency_conflict", errorCode)
            assertEquals(key, second.headers["Idempotency-Key"])
        }
    }

    @Test
    fun `idempotent replay adds marker header`() = runBlocking {
        val state = bookingState()
        testApplication {
            application { install(ContentNegotiation) { json() }; bookingA3Routes(state, botTokenProvider = { "token" }) }

            val first = hold(client, guests = 2, idem = "marker")
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(null, first.headers["Idempotency-Replay"])

            val replay = hold(client, guests = 2, idem = "marker")
            assertEquals(HttpStatusCode.OK, replay.status)
            assertEquals("true", replay.headers["Idempotency-Replay"])
        }
    }

    @Test
    fun `idempotent replay marks confirm and plus one`() = runBlocking {
        val state = bookingState()
        testApplication {
            application { install(ContentNegotiation) { json() }; bookingA3Routes(state, botTokenProvider = { "token" }) }

            val hold = hold(client, guests = 2, idem = "marker-hold")
            val bookingId = hold.bookingId()

            val confirmFirst = confirm(client, bookingId)
            assertEquals(null, confirmFirst.headers["Idempotency-Replay"])
            val confirmReplay = confirm(client, bookingId)
            assertEquals("true", confirmReplay.headers["Idempotency-Replay"])

            val plusFirst = plusOne(client, bookingId, idem = "marker-plus")
            assertEquals(null, plusFirst.headers["Idempotency-Replay"])
            val plusReplay = plusOne(client, bookingId, idem = "marker-plus")
            assertEquals("true", plusReplay.headers["Idempotency-Replay"])
        }
    }

    @Test
    fun `cannot hold booked table`() = runBlocking {
        val state = bookingState()
        testApplication {
            application { install(ContentNegotiation) { json() }; bookingA3Routes(state, botTokenProvider = { "token" }) }

            val first = hold(client, guests = 2)
            assertEquals(HttpStatusCode.OK, first.status)
            val second = hold(client, guests = 2, idem = "other")
            assertEquals(HttpStatusCode.Conflict, second.status)
            val errorCode = second.bodyJson()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
            assertEquals("table_not_available", errorCode)
        }
    }

    @Test
    fun `confirm after ttl expires`() = runBlocking {
        val clock = MutableClock(Instant.parse("2024-05-02T20:30:00Z"))
        val state = bookingState(clock = clock, holdTtl = Duration.ofSeconds(5))
        testApplication {
            application { install(ContentNegotiation) { json() }; bookingA3Routes(state, botTokenProvider = { "token" }) }

            val hold = hold(client, guests = 1)
            val bookingId = hold.bookingId()
            clock.advance(Duration.ofSeconds(6))

            val confirm = confirm(client, bookingId)
            assertEquals(HttpStatusCode.Gone, confirm.status)
            val errorCode = confirm.bodyJson()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
            assertEquals("hold_expired", errorCode)
        }
    }

    @Test
    fun `plus one deadline and reuse`() = runBlocking {
        val clock = MutableClock(Instant.parse("2024-05-02T20:30:00Z"))
        val state = bookingState(clock = clock)
        testApplication {
            application { install(ContentNegotiation) { json() }; bookingA3Routes(state, botTokenProvider = { "token" }) }

            val hold = hold(client, guests = 2)
            val bookingId = hold.bookingId()
            confirm(client, bookingId)

            clock.advance(Duration.ofMinutes(70))
            val expired = plusOne(client, bookingId)
            assertEquals(HttpStatusCode.Gone, expired.status)
            val errorCode = expired.bodyJson()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
            assertEquals("late_plus_one_expired", errorCode)

            clock.reset(Instant.parse("2024-05-02T21:10:00Z"))
            val ok = plusOne(client, bookingId, idem = "new-key")
            assertEquals(HttpStatusCode.OK, ok.status)
            val again = plusOne(client, bookingId, idem = "third-key")
            assertEquals(HttpStatusCode.Conflict, again.status)
            val againCode = again.bodyJson()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
            assertEquals("plus_one_already_used", againCode)
        }
    }

    @Test
    fun `etag bumps on hold and ttl expiry`() = runBlocking {
        val clock = MutableClock(Instant.parse("2024-05-02T20:30:00Z"))
        val fixture = bookingFixture(clock = clock, holdTtl = Duration.ofSeconds(5))
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                layoutRoutes(fixture.layoutRepository, emptyAssets)
                bookingA3Routes(fixture.state, botTokenProvider = { "token" })
            }

            val initial = client.get("/api/clubs/1/layout?eventId=100") { headers { append("X-Telegram-Init-Data", "init") } }
            val etag1 = initial.headers[HttpHeaders.ETag]

            hold(client, guests = 2)

            val held = client.get("/api/clubs/1/layout?eventId=100") { headers { append("X-Telegram-Init-Data", "init") } }
            val etag2 = held.headers[HttpHeaders.ETag]
            assertNotEquals(etag1, etag2)

            clock.advance(Duration.ofSeconds(6))
            val expired = client.get("/api/clubs/1/layout?eventId=100") { headers { append("X-Telegram-Init-Data", "init") } }
            val etag3 = expired.headers[HttpHeaders.ETag]
            assertNotEquals(etag2, etag3)
            val tableStatus = expired.bodyJson()["tables"]!!.jsonArray.first().jsonObject["status"]!!.jsonPrimitive.content
            assertEquals("free", tableStatus)
        }
    }

    @Test
    fun `single winner under concurrent holds`() = runBlocking {
        val state = bookingState()
        val hash = hashRequestCanonical(mapOf("tableId" to 1, "eventId" to 100, "guestCount" to 2))
        val results =
            coroutineScope {
                listOf(
                    async { state.hold(42, 1, 1, 100, 2, "idem-a", hash) },
                    async { state.hold(43, 1, 1, 100, 2, "idem-b", hash) },
                ).map { it.await() }
            }

        val successes = results.filterIsInstance<HoldResult.Success>()
        val errors = results.filterIsInstance<HoldResult.Error>()
        assertEquals(1, successes.size)
        assertEquals(BookingError.TABLE_NOT_AVAILABLE, errors.single().code)
    }

    @Test
    fun `idempotent replay returns original snapshot`() = runBlocking {
        val state = bookingState()
        val hash = hashRequestCanonical(mapOf("tableId" to 1, "eventId" to 100, "guestCount" to 2))
        val result = state.hold(42, 1, 1, 100, 2, "reuse-key", hash) as HoldResult.Success
        state.confirm(42, 1, result.booking.id, "confirm-key", hashRequestCanonical(mapOf("bookingId" to result.booking.id)))

        val replay = state.hold(42, 1, 1, 100, 2, "reuse-key", hash)
        assertTrue(replay is HoldResult.Success)
        assertEquals(BookingStatus.HOLD, (replay as HoldResult.Success).booking.status)
    }

    @Test
    fun `idempotency scoped by route`() = runBlocking {
        val tables =
            listOf(
                Table(id = 1, zoneId = "vip", label = "VIP-1", capacity = 4, minimumTier = "vip", status = TableStatus.FREE),
                Table(id = 2, zoneId = "vip", label = "VIP-2", capacity = 4, minimumTier = "vip", status = TableStatus.FREE),
            )
        val fixture = bookingFixture(tables = tables)
        val firstHold =
            fixture.state.hold(42, 1, 1, 100, 2, "a1", hashRequestCanonical(mapOf("tableId" to 1, "eventId" to 100, "guestCount" to 2)))
                as HoldResult.Success
        val secondHold =
            fixture.state.hold(42, 1, 2, 100, 2, "a2", hashRequestCanonical(mapOf("tableId" to 2, "eventId" to 100, "guestCount" to 2)))
                as HoldResult.Success

        val confirmKey = "shared"
        val firstConfirm =
            fixture.state.confirm(
                userId = 42,
                clubId = 1,
                bookingId = firstHold.booking.id,
                idempotencyKey = confirmKey,
                requestHash = hashRequestCanonical(mapOf("bookingId" to firstHold.booking.id)),
            )
        val secondConfirm =
            fixture.state.confirm(
                userId = 42,
                clubId = 1,
                bookingId = secondHold.booking.id,
                idempotencyKey = confirmKey,
                requestHash = hashRequestCanonical(mapOf("bookingId" to secondHold.booking.id)),
            )

        assertTrue(firstConfirm is ConfirmResult.Success)
        assertTrue(secondConfirm is ConfirmResult.Success)
    }

    @Test
    fun `head layout works after hold`() = runBlocking {
        val fixture = bookingFixture()
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                layoutRoutes(fixture.layoutRepository, emptyAssets)
                bookingA3Routes(fixture.state, botTokenProvider = { "token" })
            }

            hold(client, guests = 2)
            val head = client.head("/api/clubs/1/layout?eventId=100") { headers { append("X-Telegram-Init-Data", "init") } }
            assertEquals(HttpStatusCode.OK, head.status)
            assertTrue(head.headers[HttpHeaders.ETag]?.isNotBlank() == true)
        }
    }

    @Test
    fun `cannot confirm booking of another user`() = runBlocking {
        val userId = AtomicLong(42)
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = userId.get()) }
        val state = bookingState()
        testApplication {
            application { install(ContentNegotiation) { json() }; bookingA3Routes(state, botTokenProvider = { "token" }) }

            val hold = hold(client, guests = 2)
            val bookingId = hold.bookingId()

            userId.set(99)
            val confirm = confirm(client, bookingId)
            assertEquals(HttpStatusCode.Forbidden, confirm.status)
            assertEquals("forbidden", confirm.bodyJson()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `confirm rejects mismatched club`() = runBlocking {
        val state = bookingState()
        testApplication {
            application { install(ContentNegotiation) { json() }; bookingA3Routes(state, botTokenProvider = { "token" }) }

            val hold = hold(client, guests = 2)
            val bookingId = hold.bookingId()

            val confirm = confirm(client, bookingId, clubId = 2)
            assertEquals(HttpStatusCode.Forbidden, confirm.status)
            assertEquals("club_scope_mismatch", confirm.bodyJson()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `rate limit kicks in for rapid holds`() = runBlocking {
        val state = bookingState()
        testApplication {
            application { install(ContentNegotiation) { json() }; bookingA3Routes(state, botTokenProvider = { "token" }) }

            val responses = (1..6).map { idx -> hold(client, guests = 1, idem = "rl-$idx") }

            val last = responses.last()
            assertEquals(HttpStatusCode.TooManyRequests, last.status)
            val code = last.bodyJson()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
            assertEquals("rate_limited", code)
            assertEquals("5", last.headers["X-RateLimit-Limit"])
            assertNotNull(last.headers["X-RateLimit-Remaining"])
            assertNotNull(last.headers[HttpHeaders.RetryAfter])
        }
    }

    @Test
    fun `rate limit headers and idempotency echo stay present`() = runBlocking {
        val state = bookingState()
        testApplication {
            application { install(ContentNegotiation) { json() }; bookingA3Routes(state, botTokenProvider = { "token" }) }

            val idem = "echo-hold"
            val ok = hold(client, guests = 2, idem = idem)
            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals(idem, ok.headers["Idempotency-Key"])
            assertEquals("5", ok.headers["X-RateLimit-Limit"])
            assertNotNull(ok.headers["X-RateLimit-Remaining"])

            val throttled = (1..6).map { idx -> hold(client, guests = 1, idem = "burst-$idx") }.last()
            assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
            assertEquals("rate_limited", throttled.bodyJson()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            assertEquals("5", throttled.headers["X-RateLimit-Limit"])
            assertNotNull(throttled.headers["X-RateLimit-Remaining"])
            assertNotNull(throttled.headers[HttpHeaders.RetryAfter])
        }
    }

    @Test
    fun `idempotency echo and rate limit headers for confirm and plus one`() = runBlocking {
        val state = bookingState()
        testApplication {
            application { install(ContentNegotiation) { json() }; bookingA3Routes(state, botTokenProvider = { "token" }) }

            val hold = hold(client, guests = 2)
            val bookingId = hold.bookingId()

            val confirmIdem = "echo-confirm"
            val confirm = confirm(client, bookingId, idem = confirmIdem)
            assertEquals(HttpStatusCode.OK, confirm.status)
            assertEquals(confirmIdem, confirm.headers["Idempotency-Key"])
            assertEquals("5", confirm.headers["X-RateLimit-Limit"])
            assertNotNull(confirm.headers["X-RateLimit-Remaining"])

            val plusIdem = "echo-plus"
            val plus = plusOne(client, bookingId, idem = plusIdem)
            assertEquals(HttpStatusCode.OK, plus.status)
            assertEquals(plusIdem, plus.headers["Idempotency-Key"])
            assertEquals("5", plus.headers["X-RateLimit-Limit"])
            assertNotNull(plus.headers["X-RateLimit-Remaining"])
        }
    }

    @Test
    fun `capacity enforced on hold and plus one`() = runBlocking {
        val smallTables =
            listOf(Table(id = 1, zoneId = "vip", label = "VIP-1", capacity = 2, minimumTier = "vip", status = TableStatus.FREE))
        val fixture = bookingFixture(tables = smallTables)
        testApplication {
            application { install(ContentNegotiation) { json() }; bookingA3Routes(fixture.state, botTokenProvider = { "token" }) }

            val hold = hold(client, guests = 3)
            assertEquals(HttpStatusCode.Conflict, hold.status)
            assertEquals("capacity_exceeded", hold.bodyJson()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

            val okHold = hold(client, guests = 2, idem = "ok-hold")
            assertEquals(HttpStatusCode.OK, okHold.status)
            val bookingId = okHold.bookingId()
            val confirm = confirm(client, bookingId)
            assertEquals(HttpStatusCode.OK, confirm.status)

            val plusOne = plusOne(client, bookingId, idem = "cap-plus")
            assertEquals(HttpStatusCode.Conflict, plusOne.status)
            assertEquals("capacity_exceeded", plusOne.bodyJson()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `plus one applied only once under concurrency`() = runBlocking {
        val state = bookingState()
        val hashHold = hashRequestCanonical(mapOf("tableId" to 1, "eventId" to 100, "guestCount" to 2))
        val hold = state.hold(42, 1, 1, 100, 2, "k1", hashHold) as HoldResult.Success
        state.confirm(42, 1, hold.booking.id, "c1", hashRequestCanonical(mapOf("bookingId" to hold.booking.id)))

        val hashPlus = hashRequestCanonical(PlusOneCanonicalPayload(hold.booking.id))
        val results =
            coroutineScope {
                listOf(
                    async { state.plusOne(42, hold.booking.id, "p1", hashPlus) },
                    async { state.plusOne(42, hold.booking.id, "p2", hashPlus) },
                ).map { it.await() }
            }

        val successCount = results.count { it is PlusOneResult.Success }
        val errors = results.filterIsInstance<PlusOneResult.Error>()
        assertEquals(1, successCount)
        assertEquals(1, errors.size)
        assertEquals(BookingError.PLUS_ONE_ALREADY_USED, errors.single().code)
    }

    @Test
    fun `validation and headers`() = runBlocking {
        val state = bookingState()
        testApplication {
            application { install(ContentNegotiation) { json() }; bookingA3Routes(state, botTokenProvider = { "token" }) }

            val bad = hold(client, guests = 0)
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertEquals("validation_error", bad.bodyJson()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            assertTrue(bad.headers[HttpHeaders.ETag] == null)
            assertEquals("no-store", bad.headers[HttpHeaders.CacheControl])
            assertEquals("X-Telegram-Init-Data", bad.headers[HttpHeaders.Vary])
            assertNotNull(bad.headers["X-RateLimit-Limit"])
            assertNotNull(bad.headers["X-RateLimit-Remaining"])

            val missingIdem =
                client.post("/api/clubs/1/bookings/hold") {
                    contentType(ContentType.Application.Json)
                    headers { append("X-Telegram-Init-Data", "init") }
                    setBody("""{"tableId":1,"eventId":100,"guestCount":1}""")
                }
            assertEquals(HttpStatusCode.BadRequest, missingIdem.status)
            assertEquals("missing_idempotency_key", missingIdem.bodyJson()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            assertEquals("5", missingIdem.headers["X-RateLimit-Limit"])
            assertNotNull(missingIdem.headers["X-RateLimit-Remaining"])

            val invalidIdem =
                client.post("/api/clubs/1/bookings/hold") {
                    contentType(ContentType.Application.Json)
                    headers {
                        append("X-Telegram-Init-Data", "init")
                        append("Idempotency-Key", "bad key")
                    }
                    setBody("""{"tableId":1,"eventId":100,"guestCount":1}""")
                }
            assertEquals(HttpStatusCode.BadRequest, invalidIdem.status)
            assertEquals("validation_error", invalidIdem.bodyJson()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            assertEquals("5", invalidIdem.headers["X-RateLimit-Limit"])
            assertNotNull(invalidIdem.headers["X-RateLimit-Remaining"])
        }
    }

    private suspend fun HttpResponse.bookingId(): Long =
        bodyJson()["booking"]!!.jsonObject["id"]!!.jsonPrimitive.content.toLong()

    private suspend fun HttpResponse.bodyJson(): JsonObject =
        json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun hold(
        client: io.ktor.client.HttpClient,
        guests: Int,
        idem: String = "hold-key",
        tableId: Long = 1,
        eventId: Long = 100,
    ): HttpResponse =
        client.post("/api/clubs/1/bookings/hold") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Telegram-Init-Data", "init")
                append("Idempotency-Key", idem)
            }
            setBody(
                """{"tableId":$tableId,"eventId":$eventId,"guestCount":$guests}""",
            )
        }

    private suspend fun confirm(
        client: io.ktor.client.HttpClient,
        bookingId: Long,
        clubId: Long = 1,
        idem: String = "confirm-$bookingId",
    ): HttpResponse =
        client.post("/api/clubs/$clubId/bookings/confirm") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-Telegram-Init-Data", "init")
                append("Idempotency-Key", idem)
            }
            setBody("""{"bookingId":$bookingId}""")
        }

    private suspend fun plusOne(client: io.ktor.client.HttpClient, bookingId: Long, idem: String = "plus-one-key"): HttpResponse =
        client.post("/api/bookings/$bookingId/plus-one") {
            headers {
                append("X-Telegram-Init-Data", "init")
                append("Idempotency-Key", idem)
            }
        }

    private fun bookingState(
        clock: Clock = Clock.fixed(Instant.parse("2024-05-02T20:30:00Z"), ZoneId.of("UTC")),
        holdTtl: Duration = Duration.ofMinutes(10),
    ): BookingState = bookingFixture(clock, holdTtl).state

    private fun bookingFixture(
        clock: Clock = Clock.fixed(Instant.parse("2024-05-02T20:30:00Z"), ZoneId.of("UTC")),
        holdTtl: Duration = Duration.ofMinutes(10),
        tables: List<Table> = listOf(Table(id = 1, zoneId = "vip", label = "VIP-1", capacity = 4, minimumTier = "vip", status = TableStatus.FREE)),
    ): BookingFixture {
        val zones = listOf(Zone(id = "vip", name = "VIP", tags = emptyList(), order = 1))
        val layout: LayoutRepository =
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
        val state = BookingState(layout, events, clock = clock, holdTtl = holdTtl)
        states += state
        val bookingAware = BookingAwareLayoutRepository(layout, state)
        return BookingFixture(state, bookingAware)
    }
}

private data class BookingFixture(
    val state: BookingState,
    val layoutRepository: LayoutRepository,
)

private class MutableClock(
    private var current: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = current

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }

    fun reset(instant: Instant) {
        current = instant
    }
}
