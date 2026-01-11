package com.example.bot.routes

import com.example.bot.booking.a3.BookingState
import com.example.bot.booking.a3.HoldResult
import com.example.bot.clubs.Event
import com.example.bot.clubs.InMemoryEventsRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListRepository
import com.example.bot.http.ErrorCodes
import com.example.bot.layout.ClubLayout
import com.example.bot.layout.InMemoryLayoutRepository
import com.example.bot.layout.LayoutRepository
import com.example.bot.layout.Table
import com.example.bot.layout.TableStatus
import com.example.bot.layout.Zone
import com.example.bot.owner.OwnerHealthService
import com.example.bot.owner.OwnerHealthServiceImpl
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OwnerHealthRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val telegramId = 321L
    private val clock: Clock = Clock.fixed(Instant.parse("2024-05-05T00:00:00Z"), ZoneOffset.UTC)
    private val byEventLimit = 10

    @BeforeTest
    fun setup() {
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = telegramId) }
    }

    @AfterTest
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `health full returns aggregates`() = withApp() { fixture ->
        val response = client.get("/api/owner/health?clubId=1&period=week&granularity=full") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.OK, response.status)

        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val tables = payload["tables"]!!.jsonObject
        assertEquals(10, tables["totalTableCapacity"]!!.jsonPrimitive.content.toInt())
        assertEquals(6, tables["bookedSeats"]!!.jsonPrimitive.content.toInt())

        val attendance = payload["attendance"]!!.jsonObject
        val bookings = attendance["bookings"]!!.jsonObject
        assertEquals(6, bookings["plannedGuests"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, bookings["arrivedGuests"]!!.jsonPrimitive.content.toInt())
        val guestLists = attendance["guestLists"]!!.jsonObject
        assertEquals(5, guestLists["plannedGuests"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, guestLists["arrivedGuests"]!!.jsonPrimitive.content.toInt())

        val channels = attendance["channels"]!!.jsonObject
        val directBookings = channels["directBookings"]!!.jsonObject
        assertTrue(directBookings["plannedGuests"]!!.jsonPrimitive.content.toInt() > 0)
        val promoterBookings = channels["promoterBookings"]!!.jsonObject
        assertTrue(promoterBookings["plannedGuests"]!!.jsonPrimitive.content.toInt() > 0)

        val alerts = payload["alerts"]!!.jsonObject
        assertTrue(alerts["highNoShowEvents"]!!.jsonArray.isNotEmpty())
        assertEquals("max-age=60, must-revalidate", response.headers[HttpHeaders.CacheControl])
        assertTrue(response.headers[HttpHeaders.ETag]?.isNotBlank() == true)
    }

    @Test
    fun `promoters totals reflect invited guests from bookings`() = withApp() { _ ->
        val response = client.get("/api/owner/health?clubId=1&period=week&granularity=full") {
            header("X-Telegram-Init-Data", "init")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val promoters = payload["promoters"]!!.jsonObject
        val totals = promoters["totals"]!!.jsonObject

        // In the fixture we have exactly one promoter booking with 2 guests
        assertEquals(2, totals["invitedGuests"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, totals["arrivedGuests"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, totals["noShowGuests"]!!.jsonPrimitive.content.toInt())

        val byPromoter = promoters["byPromoter"]!!.jsonArray
        assertEquals(1, byPromoter.size)

        val p0 = byPromoter.first().jsonObject
        assertEquals(99L, p0["promoterId"]!!.jsonPrimitive.content.toLong())
        assertEquals(2, p0["invitedGuests"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, p0["arrivedGuests"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `no events in period returns empty snapshot`() =
        withApp(fixtureFactory = { buildFixtureWithNoEvents() }) { _ ->
            val response = client.get("/api/owner/health?clubId=1&period=week&granularity=full") {
                header("X-Telegram-Init-Data", "init")
            }

            assertEquals(HttpStatusCode.OK, response.status)

            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject

            val meta = payload["meta"]!!.jsonObject
            assertEquals(0, meta["eventsCount"]!!.jsonPrimitive.content.toInt())

            val tables = payload["tables"]!!.jsonObject
            assertEquals(0, tables["eventsCount"]!!.jsonPrimitive.content.toInt())
            assertEquals(0, tables["totalTableCapacity"]!!.jsonPrimitive.content.toInt())
            assertEquals(0, tables["bookedSeats"]!!.jsonPrimitive.content.toInt())
            assertEquals(0.0, tables["occupancyRate"]!!.jsonPrimitive.content.toDouble())

            val attendance = payload["attendance"]!!.jsonObject
            assertEquals(0, attendance["bookings"]!!.jsonObject["plannedGuests"]!!.jsonPrimitive.content.toInt())
            assertEquals(0, attendance["guestLists"]!!.jsonObject["plannedGuests"]!!.jsonPrimitive.content.toInt())

            val promoters = payload["promoters"]!!.jsonObject
            assertEquals(0, promoters["totals"]!!.jsonObject["invitedGuests"]!!.jsonPrimitive.content.toInt())

            val alerts = payload["alerts"]!!.jsonObject
            assertTrue(alerts["lowOccupancyEvents"]!!.jsonArray.isEmpty())
            assertTrue(alerts["highNoShowEvents"]!!.jsonArray.isEmpty())

            val breakdowns = payload["breakdowns"]!!.jsonObject
            assertTrue(breakdowns["byWeekday"]!!.jsonArray.isEmpty())

            val period = payload["period"]!!.jsonObject
            val trend = payload["trend"]!!.jsonObject
            val baseline = trend["baselinePeriod"]!!.jsonObject

            val periodFrom = Instant.parse(period["from"]!!.jsonPrimitive.content)
            val baselineFrom = Instant.parse(baseline["from"]!!.jsonPrimitive.content)
            val baselineTo = Instant.parse(baseline["to"]!!.jsonPrimitive.content)

            assertEquals(periodFrom, baselineTo)
            assertEquals(Duration.ofDays(7), Duration.between(baselineFrom, baselineTo))
        }

    @Test
    fun `event without layout is marked as incomplete`() =
        withApp(fixtureFactory = { buildFixtureWithoutLayout() }) { _ ->
            val response = client.get("/api/owner/health?clubId=1&period=week&granularity=full") {
                header("X-Telegram-Init-Data", "init")
            }

            assertEquals(HttpStatusCode.OK, response.status)

            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject

            val meta = payload["meta"]!!.jsonObject
            assertEquals(1, meta["eventsCount"]!!.jsonPrimitive.content.toInt())
            assertTrue(meta["hasIncompleteData"]!!.jsonPrimitive.content.toBooleanStrict())

            val tables = payload["tables"]!!.jsonObject
            assertEquals(0, tables["totalTableCapacity"]!!.jsonPrimitive.content.toInt())
            assertEquals(0, tables["bookedSeats"]!!.jsonPrimitive.content.toInt())
            assertEquals(0.0, tables["occupancyRate"]!!.jsonPrimitive.content.toDouble())
        }

    @Test
    fun `summary trims details`() = withApp() { _ ->
        val response = client.get("/api/owner/health?clubId=1&period=week&granularity=summary") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val tables = payload["tables"]!!.jsonObject
        assertTrue(tables["byZone"]!!.jsonArray.isEmpty())
        assertTrue(tables["byEvent"]!!.jsonArray.isEmpty())

        val promoters = payload["promoters"]!!.jsonObject
        assertTrue(promoters["byPromoter"]!!.jsonArray.isEmpty())

        val top = promoters["top"]!!.jsonObject
        assertTrue(top["byArrivedGuests"]!!.jsonArray.isEmpty())
        assertTrue(top["byInvitedGuests"]!!.jsonArray.isEmpty())

        val alerts = payload["alerts"]!!.jsonObject
        assertTrue(alerts["lowOccupancyEvents"]!!.jsonArray.size <= 1)
        assertTrue(alerts["highNoShowEvents"]!!.jsonArray.size <= 1)
        assertTrue(alerts["weakPromoters"]!!.jsonArray.size <= 1)

        val breakdowns = payload["breakdowns"]!!.jsonObject
        assertTrue(breakdowns["byWeekday"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `weekday breakdown aggregates friday and saturday separately`() =
        withApp(fixtureFactory = { buildFixtureForWeekdayBreakdown() }) { _ ->
            val response =
                client.get("/api/owner/health?clubId=1&period=week&granularity=full") {
                    header("X-Telegram-Init-Data", "init")
                }

            assertEquals(HttpStatusCode.OK, response.status)

            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val breakdowns = payload["breakdowns"]!!.jsonObject
            val byWeekday = breakdowns["byWeekday"]!!.jsonArray

            val friday =
                byWeekday
                    .map { it.jsonObject }
                    .first { it["dayOfWeek"]!!.jsonPrimitive.content == "FRIDAY" }
            val saturday =
                byWeekday
                    .map { it.jsonObject }
                    .first { it["dayOfWeek"]!!.jsonPrimitive.content == "SATURDAY" }

            assertEquals(1, friday["eventsCount"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, saturday["eventsCount"]!!.jsonPrimitive.content.toInt())

            val fridayTables = friday["tables"]!!.jsonObject
            val saturdayTables = saturday["tables"]!!.jsonObject

            assertEquals(10, fridayTables["totalTableCapacity"]!!.jsonPrimitive.content.toInt())
            assertEquals(10, saturdayTables["totalTableCapacity"]!!.jsonPrimitive.content.toInt())
            assertEquals(4, fridayTables["bookedSeats"]!!.jsonPrimitive.content.toInt())
            assertEquals(8, saturdayTables["bookedSeats"]!!.jsonPrimitive.content.toInt())

            val friOcc = fridayTables["occupancyRate"]!!.jsonPrimitive.content.toDouble()
            val satOcc = saturdayTables["occupancyRate"]!!.jsonPrimitive.content.toDouble()
            assertTrue(satOcc > friOcc)
        }


    @Test
    fun `validation errors when clubId missing`() = withApp() { _ ->
        val response = client.get("/api/owner/health") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCodes.validation_error, response.errorCode())
    }

    @Test
    fun `rbac forbids non owner`() = withApp(roles = emptySet()) { _ ->
        val response = client.get("/api/owner/health?clubId=1") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `etag returns not modified`() = withApp() { _ ->
        val first = client.get("/api/owner/health?clubId=1&period=week") { header("X-Telegram-Init-Data", "init") }
        val etag = first.headers[HttpHeaders.ETag]!!
        val second = client.get("/api/owner/health?clubId=1&period=week") {
            header("X-Telegram-Init-Data", "init")
            header(HttpHeaders.IfNoneMatch, etag)
        }
        assertEquals(HttpStatusCode.NotModified, second.status)
        assertEquals(etag, second.headers[HttpHeaders.ETag])
    }

    @Test
    fun `tables aggregates include all events and limit byEvent`() =
        withApp(fixtureFactory = { buildFixtureWithManyEvents() }) { fixture ->
            val response = client.get("/api/owner/health?clubId=1&period=week&granularity=full") {
                header("X-Telegram-Init-Data", "init")
            }

            assertEquals(HttpStatusCode.OK, response.status)

            val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val tables = payload["tables"]!!.jsonObject

            val expectedEventsCount = fixture.events.size
            val expectedTotalCapacity = expectedEventsCount * 10
            val expectedBookedSeats = fixture.events.mapIndexed { index, _ -> (index % 4) + 1 + (index % 5) + 1 }.sum()

            assertEquals(expectedEventsCount, tables["eventsCount"]!!.jsonPrimitive.content.toInt())
            assertEquals(expectedTotalCapacity, tables["totalTableCapacity"]!!.jsonPrimitive.content.toInt())
            assertEquals(expectedBookedSeats, tables["bookedSeats"]!!.jsonPrimitive.content.toInt())

            val byEvent = tables["byEvent"]!!.jsonArray
            val expectedEventIds = fixture.events.sortedBy { it.startUtc }.take(byEventLimit).map { it.id }
            val returnedEventIds = byEvent.map { it.jsonObject["eventId"]!!.jsonPrimitive.content.toLong() }

            assertTrue(byEvent.size <= byEventLimit)
            assertEquals(expectedEventIds, returnedEventIds)
        }

    private fun withApp(
        roles: Set<Role> = setOf(Role.OWNER),
        fixtureFactory: () -> Fixture = { buildFixture() },
        block: suspend ApplicationTestBuilder.(Fixture) -> Unit,
    ) {
        val fixture = fixtureFactory()
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(roles)
                    auditLogRepository = io.mockk.mockk(relaxed = true)
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }

                ownerHealthRoutes(
                    service = fixture.service,
                    layoutRepository = fixture.layoutRepository,
                    clock = clock,
                    botTokenProvider = { "test" },
                )
            }

            block(this, fixture)
        }
    }

    private fun buildFixture(): Fixture {
        val events =
            listOf(
                Event(
                    id = 100,
                    clubId = 1,
                    startUtc = Instant.parse("2024-05-02T21:00:00Z"),
                    endUtc = Instant.parse("2024-05-03T04:00:00Z"),
                    title = "Opening",
                    isSpecial = false,
                ),
            )
        val eventsRepository = InMemoryEventsRepository(events)
        val layoutRepository =
            InMemoryLayoutRepository(
                listOf(
                    InMemoryLayoutRepository.LayoutSeed(
                        clubId = 1,
                        zones = listOf(Zone(id = "main", name = "Main", tags = emptyList(), order = 0)),
                        tables =
                            listOf(
                                Table(id = 1, zoneId = "main", label = "T1", capacity = 4, minimumTier = "std", status = TableStatus.FREE),
                                Table(id = 2, zoneId = "main", label = "T2", capacity = 6, minimumTier = "std", status = TableStatus.FREE),
                            ),
                        geometryJson = InMemoryLayoutRepository.DEFAULT_GEOMETRY_JSON,
                    ),
                ),
                clock = clock,
            )

        val bookingState = BookingState(layoutRepository, eventsRepository, clock = clock)
        val guestListRepository = InMemoryGuestListRepository(clock)

        runBlockingBookings(bookingState) {
            val hold = bookingState.hold(userId = 10, clubId = 1, tableId = 1, eventId = 100, guestCount = 4, idempotencyKey = "h1", requestHash = "h1")
            if (hold is HoldResult.Success) {
                bookingState.confirm(userId = 10, clubId = 1, bookingId = hold.booking.id, idempotencyKey = "c1", requestHash = "h1")
            }

            val hold2 = bookingState.hold(userId = 11, clubId = 1, tableId = 2, eventId = 100, guestCount = 2, idempotencyKey = "h2", requestHash = "h2", promoterId = 99)
            if (hold2 is HoldResult.Success) {
                bookingState.confirm(userId = 11, clubId = 1, bookingId = hold2.booking.id, idempotencyKey = "c2", requestHash = "h2")
            }
        }

        runBlockingBookings(bookingState) {
            val list = guestListRepository.createList(clubId = 1, eventId = 100, ownerType = com.example.bot.club.GuestListOwnerType.ADMIN, ownerUserId = 1, title = "Main", capacity = 50, arrivalWindowStart = null, arrivalWindowEnd = null)
            guestListRepository.addEntry(list.id, "Alice", null, 3, null, GuestListEntryStatus.PLANNED)
            guestListRepository.addEntry(list.id, "Bob", null, 2, null, GuestListEntryStatus.CHECKED_IN)
        }

        val service: OwnerHealthService =
            OwnerHealthServiceImpl(
                layoutRepository = layoutRepository,
                eventsRepository = eventsRepository,
                bookingState = bookingState,
                guestListRepository = guestListRepository,
                clock = clock,
            )

        return Fixture(layoutRepository, guestListRepository, service, events)
    }

    private fun buildFixtureForWeekdayBreakdown(): Fixture {
        val events =
            listOf(
                Event(
                    id = 400,
                    clubId = 1,
                    startUtc = Instant.parse("2024-05-03T20:00:00Z"),
                    endUtc = Instant.parse("2024-05-04T02:00:00Z"),
                    title = "Friday party",
                    isSpecial = false,
                ),
                Event(
                    id = 401,
                    clubId = 1,
                    startUtc = Instant.parse("2024-05-04T20:00:00Z"),
                    endUtc = Instant.parse("2024-05-05T02:00:00Z"),
                    title = "Saturday party",
                    isSpecial = false,
                ),
            )

        val eventsRepository = InMemoryEventsRepository(events)
        val layoutRepository =
            InMemoryLayoutRepository(
                listOf(
                    InMemoryLayoutRepository.LayoutSeed(
                        clubId = 1,
                        zones = listOf(Zone(id = "main", name = "Main", tags = emptyList(), order = 0)),
                        tables =
                            listOf(
                                Table(id = 1, zoneId = "main", label = "T1", capacity = 4, minimumTier = "std", status = TableStatus.FREE),
                                Table(id = 2, zoneId = "main", label = "T2", capacity = 6, minimumTier = "std", status = TableStatus.FREE),
                            ),
                        geometryJson = InMemoryLayoutRepository.DEFAULT_GEOMETRY_JSON,
                    ),
                ),
                clock = clock,
            )

        val bookingState = BookingState(layoutRepository, eventsRepository, clock = clock)
        val guestListRepository = InMemoryGuestListRepository(clock)

        runBlockingBookings(bookingState) {
            val fridayHold = bookingState.hold(userId = 1, clubId = 1, tableId = 1, eventId = 400, guestCount = 4, idempotencyKey = "fri-1", requestHash = "fri-1")
            if (fridayHold is HoldResult.Success) {
                bookingState.confirm(userId = 1, clubId = 1, bookingId = fridayHold.booking.id, idempotencyKey = "fri-c1", requestHash = "fri-1")
            }

            val satHold1 = bookingState.hold(userId = 2, clubId = 1, tableId = 1, eventId = 401, guestCount = 4, idempotencyKey = "sat-1", requestHash = "sat-1")
            if (satHold1 is HoldResult.Success) {
                bookingState.confirm(userId = 2, clubId = 1, bookingId = satHold1.booking.id, idempotencyKey = "sat-c1", requestHash = "sat-1")
            }
            val satHold2 = bookingState.hold(userId = 3, clubId = 1, tableId = 2, eventId = 401, guestCount = 4, idempotencyKey = "sat-2", requestHash = "sat-2")
            if (satHold2 is HoldResult.Success) {
                bookingState.confirm(userId = 3, clubId = 1, bookingId = satHold2.booking.id, idempotencyKey = "sat-c2", requestHash = "sat-2")
            }
        }

        val service: OwnerHealthService =
            OwnerHealthServiceImpl(
                layoutRepository = layoutRepository,
                eventsRepository = eventsRepository,
                bookingState = bookingState,
                guestListRepository = guestListRepository,
                clock = clock,
            )

        return Fixture(layoutRepository, guestListRepository, service, events)
    }

    private fun buildFixtureWithManyEvents(): Fixture {
        val events =
            (1..12).map { index ->
                val start = Instant.parse("2024-05-01T12:00:00Z").plus(Duration.ofHours(index.toLong()))
                Event(
                    id = 200L + index.toLong(),
                    clubId = 1,
                    startUtc = start,
                    endUtc = start.plus(Duration.ofHours(3)),
                    title = "Event $index",
                    isSpecial = false,
                )
            }
        val eventsRepository = InMemoryEventsRepository(events)
        val layoutRepository =
            InMemoryLayoutRepository(
                listOf(
                    InMemoryLayoutRepository.LayoutSeed(
                        clubId = 1,
                        zones = listOf(Zone(id = "main", name = "Main", tags = emptyList(), order = 0)),
                        tables =
                            listOf(
                                Table(id = 1, zoneId = "main", label = "T1", capacity = 4, minimumTier = "std", status = TableStatus.FREE),
                                Table(id = 2, zoneId = "main", label = "T2", capacity = 6, minimumTier = "std", status = TableStatus.FREE),
                            ),
                        geometryJson = InMemoryLayoutRepository.DEFAULT_GEOMETRY_JSON,
                    ),
                ),
                clock = clock,
            )

        val bookingState = BookingState(layoutRepository, eventsRepository, clock = clock)
        val guestListRepository = InMemoryGuestListRepository(clock)

        runBlockingBookings(bookingState) {
            events.forEachIndexed { index, event ->
                val firstCount = (index % 4) + 1
                val secondCount = (index % 5) + 1
                val hold = bookingState.hold(userId = 20L + index.toLong(), clubId = 1, tableId = 1L, eventId = event.id, guestCount = firstCount, idempotencyKey = "h${event.id}-1", requestHash = "h${event.id}-1")
                if (hold is HoldResult.Success) {
                    bookingState.confirm(userId = 20L + index.toLong(), clubId = 1, bookingId = hold.booking.id, idempotencyKey = "c${event.id}-1", requestHash = "h${event.id}-1")
                }

                val hold2 = bookingState.hold(userId = 200L + index.toLong(), clubId = 1, tableId = 2L, eventId = event.id, guestCount = secondCount, idempotencyKey = "h${event.id}-2", requestHash = "h${event.id}-2")
                if (hold2 is HoldResult.Success) {
                    bookingState.confirm(userId = 200L + index.toLong(), clubId = 1, bookingId = hold2.booking.id, idempotencyKey = "c${event.id}-2", requestHash = "h${event.id}-2")
                }
            }
        }

        val service: OwnerHealthService =
            OwnerHealthServiceImpl(
                layoutRepository = layoutRepository,
                eventsRepository = eventsRepository,
                bookingState = bookingState,
                guestListRepository = guestListRepository,
                clock = clock,
            )

        return Fixture(layoutRepository, guestListRepository, service, events)
    }

    private fun buildFixtureWithNoEvents(): Fixture {
        val events = emptyList<Event>()
        val eventsRepository = InMemoryEventsRepository(events)
        val layoutRepository = InMemoryLayoutRepository(emptyList(), clock = clock)
        val bookingState = BookingState(layoutRepository, eventsRepository, clock = clock)
        val guestListRepository = InMemoryGuestListRepository(clock)

        val service: OwnerHealthService =
            OwnerHealthServiceImpl(
                layoutRepository = layoutRepository,
                eventsRepository = eventsRepository,
                bookingState = bookingState,
                guestListRepository = guestListRepository,
                clock = clock,
            )

        return Fixture(layoutRepository, guestListRepository, service, events)
    }

    private fun buildFixtureWithoutLayout(): Fixture {
        val events =
            listOf(
                Event(
                    id = 300,
                    clubId = 1,
                    startUtc = clock.instant().minus(Duration.ofDays(1)),
                    endUtc = clock.instant(),
                    title = "Layoutless",
                    isSpecial = false,
                ),
            )

        val eventsRepository = InMemoryEventsRepository(events)
        val layoutRepository =
            object : LayoutRepository {
                override suspend fun getLayout(clubId: Long, eventId: Long?): ClubLayout? = null

                override suspend fun lastUpdatedAt(clubId: Long, eventId: Long?): Instant? = null
            }
        val bookingState = BookingState(layoutRepository, eventsRepository, clock = clock)
        val guestListRepository = InMemoryGuestListRepository(clock)

        val service: OwnerHealthService =
            OwnerHealthServiceImpl(
                layoutRepository = layoutRepository,
                eventsRepository = eventsRepository,
                bookingState = bookingState,
                guestListRepository = guestListRepository,
                clock = clock,
            )

        return Fixture(layoutRepository, guestListRepository, service, events)
    }

    private inline fun runBlockingBookings(state: BookingState, crossinline block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }

    private class InMemoryGuestListRepository(private val clock: Clock) : GuestListRepository {
        private val lists = mutableListOf<com.example.bot.club.GuestList>()
        private val entries = mutableListOf<com.example.bot.club.GuestListEntry>()
        private val listSeq = AtomicLong(1)
        private val entrySeq = AtomicLong(1)

        override suspend fun createList(
            clubId: Long,
            eventId: Long,
            ownerType: com.example.bot.club.GuestListOwnerType,
            ownerUserId: Long,
            title: String,
            capacity: Int,
            arrivalWindowStart: Instant?,
            arrivalWindowEnd: Instant?,
            status: com.example.bot.club.GuestListStatus,
        ): com.example.bot.club.GuestList {
            val list =
                com.example.bot.club.GuestList(
                    id = listSeq.getAndIncrement(),
                    clubId = clubId,
                    eventId = eventId,
                    ownerType = ownerType,
                    ownerUserId = ownerUserId,
                    title = title,
                    capacity = capacity,
                    arrivalWindowStart = arrivalWindowStart,
                    arrivalWindowEnd = arrivalWindowEnd,
                    status = status,
                    createdAt = clock.instant(),
                )
            lists += list
            return list
        }

        override suspend fun getList(id: Long): com.example.bot.club.GuestList? = lists.firstOrNull { it.id == id }

        override suspend fun findEntry(id: Long): com.example.bot.club.GuestListEntry? = entries.firstOrNull { it.id == id }

        override suspend fun listListsByClub(clubId: Long, page: Int, size: Int): List<com.example.bot.club.GuestList> {
            val offset = page * size
            return lists.filter { it.clubId == clubId }.drop(offset).take(size)
        }

        override suspend fun addEntry(
            listId: Long,
            fullName: String,
            phone: String?,
            guestsCount: Int,
            notes: String?,
            status: GuestListEntryStatus,
        ): com.example.bot.club.GuestListEntry {
            val entry =
                com.example.bot.club.GuestListEntry(
                    id = entrySeq.getAndIncrement(),
                    listId = listId,
                    fullName = fullName,
                    phone = phone,
                    guestsCount = guestsCount,
                    notes = notes,
                    status = status,
                    checkedInAt = null,
                    checkedInBy = null,
                )
            entries += entry
            return entry
        }

        override suspend fun setEntryStatus(
            entryId: Long,
            status: GuestListEntryStatus,
            checkedInBy: Long?,
            at: Instant?,
        ): com.example.bot.club.GuestListEntry? {
            val idx = entries.indexOfFirst { it.id == entryId }
            if (idx == -1) return null
            val updated = entries[idx].copy(status = status, checkedInAt = at, checkedInBy = checkedInBy)
            entries[idx] = updated
            return updated
        }

        override suspend fun listEntries(
            listId: Long,
            page: Int,
            size: Int,
            statusFilter: GuestListEntryStatus?,
        ): List<com.example.bot.club.GuestListEntry> {
            val offset = page * size
            return entries
                .filter { it.listId == listId }
                .filter { statusFilter == null || it.status == statusFilter }
                .drop(offset)
                .take(size)
        }

        override suspend fun markArrived(entryId: Long, at: Instant): Boolean {
            val idx = entries.indexOfFirst { it.id == entryId }
            if (idx == -1) return false
            entries[idx] = entries[idx].copy(status = GuestListEntryStatus.CHECKED_IN, checkedInAt = at)
            return true
        }

        override suspend fun bulkImport(
            listId: Long,
            rows: List<com.example.bot.club.ParsedGuest>,
            dryRun: Boolean,
        ): com.example.bot.club.GuestListEntryPage = throw UnsupportedOperationException()

        override suspend fun searchEntries(
            filter: com.example.bot.club.GuestListEntrySearch,
            page: Int,
            size: Int,
        ): com.example.bot.club.GuestListEntryPage = throw UnsupportedOperationException()
    }

    private class StubUserRepository : UserRepository {
        override suspend fun getByTelegramId(id: Long): User? = User(id = 1, telegramId = id, username = "tester")

        override suspend fun getById(id: Long): User? = User(id = id, telegramId = id, username = "tester")
    }

    private class StubUserRoleRepository(
        private val roles: Set<Role>,
    ) : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = roles

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = setOf(1)
    }

    private data class Fixture(
        val layoutRepository: LayoutRepository,
        val guestListRepository: GuestListRepository,
        val service: OwnerHealthService,
        val events: List<Event>,
    )

    private suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String {
        val raw = bodyAsText()
        val parsed = runCatching { Json.parseToJsonElement(raw).jsonObject.errorCodeOrNull() }.getOrNull()
        val extracted = Regex("\\\"error\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(raw)?.groupValues?.getOrNull(1)
        return parsed ?: extracted ?: raw
    }

    private fun kotlinx.serialization.json.JsonObject.errorCodeOrNull(): String? =
        this["code"]?.jsonPrimitive?.content
            ?: this["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
            ?: this["error"]?.jsonPrimitive?.content
}
