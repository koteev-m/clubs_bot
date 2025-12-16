package com.example.bot.routes

import com.example.bot.booking.a3.Booking
import com.example.bot.booking.a3.BookingState
import com.example.bot.booking.a3.HoldResult
import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListRepository
import com.example.bot.club.WaitlistEntry
import com.example.bot.club.WaitlistRepository
import com.example.bot.club.WaitlistStatus
import com.example.bot.clubs.Event
import com.example.bot.clubs.InMemoryEventsRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.host.BookingProvider
import com.example.bot.host.HostEntranceService
import com.example.bot.http.ErrorCodes
import com.example.bot.layout.InMemoryLayoutRepository
import com.example.bot.layout.Table
import com.example.bot.layout.TableStatus
import com.example.bot.layout.Zone
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HostEntranceRoutesTest {
    private val now = Instant.parse("2024-06-08T18:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val telegramId = 42L

    @Before
    fun setUp() {
        System.setProperty("TELEGRAM_BOT_TOKEN", "test")
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = telegramId) }
    }

    @After
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `aggregates entrance data`() = withHostApp() { fixture ->
        val response = client.get("/api/host/entrance?clubId=1&eventId=100") {
            header("X-Telegram-Init-Data", "init")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsJson()
        assertEquals(1L, body["clubId"]!!.jsonPrimitive.long)
        assertEquals(100L, body["eventId"]!!.jsonPrimitive.long)
        assertEquals(3, body["expected"]!!.jsonObject["guestList"]!!.jsonArray.size)
        assertEquals(2, body["expected"]!!.jsonObject["bookings"]!!.jsonArray.size)
        assertEquals(2, body["waitlist"]!!.jsonObject["activeCount"]!!.jsonPrimitive.int)

        val status = body["status"]!!.jsonObject
        val guestListStatus = status["guestList"]!!.jsonObject
        assertEquals(6, guestListStatus["expectedGuests"]!!.jsonPrimitive.int)
        assertEquals(3, guestListStatus["arrivedGuests"]!!.jsonPrimitive.int)
        assertEquals(2, guestListStatus["notArrivedGuests"]!!.jsonPrimitive.int)
        assertEquals(1, guestListStatus["noShowGuests"]!!.jsonPrimitive.int)

        val bookingsStatus = status["bookings"]!!.jsonObject
        assertEquals(6, bookingsStatus["expectedGuests"]!!.jsonPrimitive.int)
        assertEquals(0, bookingsStatus["arrivedGuests"]!!.jsonPrimitive.int)

        val counts = body["counts"]!!.jsonObject
        assertEquals(12, counts["expectedTotalGuests"]!!.jsonPrimitive.int)
        assertEquals(3, counts["arrivedTotalGuests"]!!.jsonPrimitive.int)
        response.assertNoStoreHeaders()
    }

    @Test
    fun `guest list statuses are lowercase and hasQr is false`() = withHostApp() { _ ->
        val response = client.get("/api/host/entrance?clubId=1&eventId=100") {
            header("X-Telegram-Init-Data", "init")
        }

        val body = response.bodyAsJson()
        val guestList = body["expected"]!!.jsonObject["guestList"]!!.jsonArray
        val checkedInGuest =
            guestList
                .first { it.jsonObject["guestName"]!!.jsonPrimitive.content == "Bob" }
                .jsonObject

        assertEquals("checked_in", checkedInGuest["status"]!!.jsonPrimitive.content)
        assertEquals(false, checkedInGuest["hasQr"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `rbac forbids non entry roles`() = withHostApp(roles = setOf(Role.PROMOTER)) {
        val response = client.get("/api/host/entrance?clubId=1&eventId=100") {
            header("X-Telegram-Init-Data", "init")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(ErrorCodes.forbidden, response.errorCode())
        response.assertNoStoreHeaders()
    }

    @Test
    fun `validates query parameters`() = withHostApp() {
        listOf(
            "/api/host/entrance",
            "/api/host/entrance?clubId=0&eventId=1",
            "/api/host/entrance?clubId=1&eventId=0",
        ).forEach { path ->
            val response = client.get(path) {
                header("X-Telegram-Init-Data", "init")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(ErrorCodes.validation_error, response.errorCode())
            response.assertNoStoreHeaders()
        }
    }

    @Test
    fun `returns not found when event missing`() = withHostApp(fixture = buildFixture(withData = false, withEvent = false)) {
        val response = client.get("/api/host/entrance?clubId=1&eventId=100") {
            header("X-Telegram-Init-Data", "init")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(ErrorCodes.not_found, response.errorCode())
        response.assertNoStoreHeaders()
    }

    @Test
    fun `returns empty snapshot when no records`() = withHostApp(fixture = buildFixture(withData = false)) {
        val response = client.get("/api/host/entrance?clubId=1&eventId=100") {
            header("X-Telegram-Init-Data", "init")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsJson()
        assertEquals(0, body["expected"]!!.jsonObject["guestList"]!!.jsonArray.size)
        assertEquals(0, body["expected"]!!.jsonObject["bookings"]!!.jsonArray.size)
        assertEquals(0, body["waitlist"]!!.jsonObject["activeCount"]!!.jsonPrimitive.int)

        val counts = body["counts"]!!.jsonObject
        assertEquals(0, counts["expectedTotalGuests"]!!.jsonPrimitive.int)
        assertEquals(0, counts["arrivedTotalGuests"]!!.jsonPrimitive.int)
        assertEquals(0, counts["noShowTotalGuests"]!!.jsonPrimitive.int)
        response.assertNoStoreHeaders()
    }

    private fun withHostApp(
        roles: Set<Role> = setOf(Role.ENTRY_MANAGER),
        fixture: TestFixture = buildFixture(),
        block: suspend ApplicationTestBuilder.(TestFixture) -> Unit,
    ) {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(roles)
                    auditLogRepository = relaxedAuditRepository()
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }

                val bookingProvider =
                    object : BookingProvider {
                        override fun findBookingsForEvent(clubId: Long, eventId: Long): List<Booking> =
                            fixture.bookingState.findBookingsForEvent(clubId, eventId)
                    }

                val service =
                    HostEntranceService(
                        guestListRepository = fixture.guestListRepository,
                        waitlistRepository = fixture.waitlistRepository,
                        bookingProvider = bookingProvider,
                        eventsRepository = fixture.eventsRepository,
                        clock = clock,
                    )
                hostEntranceRoutes(service = service, botTokenProvider = { "test" })
            }
            block(this, fixture)
        }
    }

    private fun buildFixture(withData: Boolean = true, withEvent: Boolean = true): TestFixture {
        val events = if (withEvent) {
            listOf(
                Event(
                    id = 100,
                    clubId = 1,
                    startUtc = now,
                    endUtc = now.plus(Duration.ofHours(6)),
                    title = "Party",
                    isSpecial = false,
                ),
            )
        } else {
            emptyList()
        }
        val eventsRepository = InMemoryEventsRepository(events)
        val guestListRepository = InMemoryGuestListRepository(clock)
        val waitlistRepository = InMemoryWaitlistRepository(clock)
        val layout =
            InMemoryLayoutRepository(
                listOf(
                    InMemoryLayoutRepository.LayoutSeed(
                        clubId = 1,
                        zones = listOf(Zone(id = "main", name = "Main", tags = emptyList(), order = 0)),
                        tables = listOf(
                            Table(id = 1, zoneId = "main", label = "A1", capacity = 6, minimumTier = "std", status = TableStatus.FREE),
                            Table(id = 2, zoneId = "main", label = "A2", capacity = 4, minimumTier = "std", status = TableStatus.FREE),
                        ),
                        geometryJson = InMemoryLayoutRepository.DEFAULT_GEOMETRY_JSON,
                    ),
                ),
                clock = clock,
            )
        val bookingState = BookingState(layout, eventsRepository, clock = clock)

        if (withData) {
            runBlocking {
                val list =
                    guestListRepository.createList(
                        clubId = 1,
                        eventId = 100,
                        ownerType = com.example.bot.club.GuestListOwnerType.ADMIN,
                        ownerUserId = 10,
                        title = "Main",
                        capacity = 100,
                        arrivalWindowStart = null,
                        arrivalWindowEnd = null,
                    )
                guestListRepository.addEntry(list.id, "Alice", null, 2, null, GuestListEntryStatus.PLANNED)
                guestListRepository.addEntry(list.id, "Bob", null, 3, null, GuestListEntryStatus.CHECKED_IN)
                guestListRepository.addEntry(list.id, "Carol", null, 1, null, GuestListEntryStatus.NO_SHOW)

                val hold = bookingState.hold(userId = 5, clubId = 1, tableId = 1, eventId = 100, guestCount = 4, idempotencyKey = "h1", requestHash = "h1")
                if (hold is HoldResult.Success) {
                    bookingState.confirm(userId = 5, clubId = 1, bookingId = hold.booking.id, idempotencyKey = "c1", requestHash = "h1")
                }
                bookingState.hold(userId = 6, clubId = 1, tableId = 2, eventId = 100, guestCount = 2, idempotencyKey = "h2", requestHash = "h2")

                waitlistRepository.enqueue(clubId = 1, eventId = 100, userId = 20, partySize = 3)
                waitlistRepository.enqueue(clubId = 1, eventId = 100, userId = 21, partySize = 2)
            }
        }

        return TestFixture(guestListRepository, waitlistRepository, bookingState, eventsRepository)
    }

    private data class TestFixture(
        val guestListRepository: InMemoryGuestListRepository,
        val waitlistRepository: InMemoryWaitlistRepository,
        val bookingState: BookingState,
        val eventsRepository: InMemoryEventsRepository,
    )

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
            val existingIndex = entries.indexOfFirst { it.id == entryId }
            if (existingIndex == -1) return null
            val updated = entries[existingIndex].copy(status = status, checkedInAt = at, checkedInBy = checkedInBy)
            entries[existingIndex] = updated
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
            val index = entries.indexOfFirst { it.id == entryId }
            if (index == -1) return false
            entries[index] = entries[index].copy(status = GuestListEntryStatus.CHECKED_IN, checkedInAt = at)
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

    private class InMemoryWaitlistRepository(private val clock: Clock) : WaitlistRepository {
        private val entries = mutableListOf<WaitlistEntry>()
        private val seq = AtomicLong(1)

        override suspend fun enqueue(clubId: Long, eventId: Long, userId: Long, partySize: Int): WaitlistEntry {
            val entry =
                WaitlistEntry(
                    id = seq.getAndIncrement(),
                    clubId = clubId,
                    eventId = eventId,
                    userId = userId,
                    partySize = partySize,
                    createdAt = clock.instant(),
                    calledAt = null,
                    expiresAt = null,
                    status = WaitlistStatus.WAITING,
                )
            entries += entry
            return entry
        }

        override suspend fun listQueue(clubId: Long, eventId: Long): List<WaitlistEntry> =
            entries.filter { it.clubId == clubId && it.eventId == eventId && it.status != WaitlistStatus.CANCELLED }

        override suspend fun callEntry(clubId: Long, id: Long, reserveMinutes: Int): WaitlistEntry? {
            val index = entries.indexOfFirst { it.clubId == clubId && it.id == id }
            if (index == -1) return null
            val updated =
                entries[index].copy(
                    status = WaitlistStatus.CALLED,
                    calledAt = clock.instant(),
                    expiresAt = clock.instant().plus(Duration.ofMinutes(reserveMinutes.toLong())),
                )
            entries[index] = updated
            return updated
        }

        override suspend fun expireEntry(clubId: Long, id: Long, close: Boolean): WaitlistEntry? {
            val index = entries.indexOfFirst { it.clubId == clubId && it.id == id }
            if (index == -1) return null
            val updated =
                entries[index].copy(
                    status = if (close) WaitlistStatus.EXPIRED else WaitlistStatus.WAITING,
                    calledAt = null,
                    expiresAt = null,
                )
            entries[index] = updated
            return updated
        }

        override suspend fun get(id: Long): WaitlistEntry? = entries.firstOrNull { it.id == id }
    }

    private class StubUserRepository : UserRepository {
        override suspend fun getByTelegramId(id: Long): User? = User(id = 1, telegramId = id, username = "tester")
    }

    private class StubUserRoleRepository(private val roles: Set<Role>) : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = roles

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = setOf(1)
    }

    private fun relaxedAuditRepository() = io.mockk.mockk<com.example.bot.data.booking.core.AuditLogRepository>(relaxed = true)

    private suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String {
        val raw = bodyAsText()
        val parsed = runCatching { Json.parseToJsonElement(raw).jsonObject.errorCodeOrNull() }.getOrNull()
        val extracted = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.getOrNull(1)
        return parsed ?: extracted ?: raw
    }

    private fun JsonObject.errorCodeOrNull(): String? =
        this["code"]?.jsonPrimitive?.content
            ?: this["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
            ?: this["error"]?.jsonPrimitive?.content

    private suspend fun io.ktor.client.statement.HttpResponse.bodyAsJson(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun io.ktor.client.statement.HttpResponse.assertNoStoreHeaders() {
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        assertEquals("X-Telegram-Init-Data", headers[HttpHeaders.Vary])
    }

    private val JsonPrimitive.int: Int
        get() = this.long.toInt()
}
