package com.example.bot.routes

import com.example.bot.availability.DefaultAvailabilityService
import com.example.bot.data.booking.core.BookingCoreError
import com.example.bot.data.booking.core.BookingCoreResult
import com.example.bot.data.booking.core.BookingHoldRepository
import com.example.bot.data.client.onboarding.ClientWorkingNightMaintenance
import com.example.bot.data.privacy.JdbcLegacyClubAccess
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

open class AutomaticClubOperationsRoutesTest : ClientManagerRoutesFixture() {
    @Test
    fun `upgrade adopts verified earlier client club with existing schedule and leaves conflicting club alone`() =
        databaseAt("86") { source ->
            val clock = DeskClock()
            val start = clock.instant().plusSeconds(75).truncatedTo(ChronoUnit.MINUTES)
            val end = start.plusSeconds(6 * 3600)
            val (club, neighbor) = prepareEarlierClientClubs(source, clock, start, end)
            val dialect =
                source.connection.use {
                    if (it.metaData.databaseProductName ==
                        "PostgreSQL"
                    ) {
                        "postgresql"
                    } else {
                        "h2"
                    }
                }
            assertEquals(1, migrateBookingRulesFixture(source, dialect, "87"))
            assertEquals(1, migrateBookingRulesFixture(source, dialect))
            clock.time = start.plusSeconds(30)
            val maintenance = ClientWorkingNightMaintenance(source, clock)
            withApp(source, clock) {
                val path = "$C/clubs/$club/operations"
                val before = ok(path, THIRD_MANAGER)
                assertEquals("working_club_preparing", before.string("blocked"))
                val currentBefore = before["night"]!!.jsonObject
                assertEquals(
                    start.atZone(ZoneId.of("Europe/Moscow")).toLocalDate().toString(),
                    currentBefore.string("businessDate"),
                )
                assertEquals(start.toString(), currentBefore.string("opensAt"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_working_clubs"))
                val base = "$C/clubs/$club"
                val hall = ok("$base/layout", SECOND_MANAGER)["halls"]!!.jsonArray.single().jsonObject
                val calendar = ok("$base/calendar", SECOND_MANAGER)
                coroutineScope { List(2) { async { maintenance.refresh() } }.awaitAll() }
                assertEquals(
                    HttpStatusCode.Conflict,
                    requestAs(
                        "$base/halls/${hall.string("id")}/tables",
                        SECOND_MANAGER,
                        JsonObject(
                            obj("label" to "Stale table", "expectedVersion" to hall.string("layoutRevision")) +
                                ("tableNumber" to JsonPrimitive(3)) + ("capacity" to JsonPrimitive(4)),
                        ),
                    ).status,
                )
                assertTrue(calendar.string("version") != ok("$base/calendar", SECOND_MANAGER).string("version"))
                val after = ok(path, THIRD_MANAGER)
                assertFalse(after.containsKey("blocked"))
                assertEquals(currentBefore, after["night"])
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM client_working_clubs"))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM events WHERE club_id=$club"))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM events WHERE club_id=$neighbor"))
                assertEquals("2", source.scalar("SELECT COUNT(*) FROM booking_cutover_fences WHERE club_id=$club"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM booking_cutover_fences WHERE club_id=$neighbor"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM tables WHERE club_id=$neighbor"))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM clubs WHERE id=$club AND is_active=FALSE"))
                assertGuestSeparation(source, club.toLong(), start, end)
                assertEquals(HttpStatusCode.NotFound, requestAs("$C/clubs/$neighbor/operations", THIRD_MANAGER).status)
                val table = source.scalar("SELECT id FROM hall_tables WHERE label='Earlier table'")
                val command = obj("nightId" to currentBefore.string("id"), "tableId" to table)
                val booked =
                    ok(
                        path,
                        THIRD_MANAGER,
                        JsonObject(
                            command + obj("action" to "create_booking", "guestName" to "Guest") +
                                ("guests" to JsonPrimitive(2)),
                        ),
                    )
                val seated =
                    ok(
                        path,
                        THIRD_MANAGER,
                        JsonObject(
                            command +
                                obj("action" to "seat", "mode" to "CLUB", "bookingId" to booked.string("bookingId")) +
                                ("expectedLastSessionId" to JsonNull),
                        ),
                    )
                assertTrue(seated.string("sessionId").isNotBlank())
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM checkins"))
                maintenance.refresh()
                assertEquals("2", source.scalar("SELECT COUNT(*) FROM booking_cutover_fences WHERE club_id=$club"))
            }
        }

    private fun prepareEarlierClientClubs(
        source: DataSource,
        clock: DeskClock,
        start: Instant,
        end: Instant,
    ): Pair<String, String> {
        lateinit var clubs: Pair<String, String>
        withApp(source, clock) {
            clubs = business(source)
            val (club, neighbor) = clubs
            val base = "$C/clubs/$club"
            claim(invite("$base/managers", RECIPIENT, "CLUB_MANAGER"), SECOND_MANAGER)
            claim(invite("$base/managers", SECOND_MANAGER, "FLOOR_MANAGER"), THIRD_MANAGER)
            val hall =
                ok(
                    "$base/halls",
                    SECOND_MANAGER,
                    obj("name" to "Saved hall"),
                )["halls"]!!.jsonArray.single().jsonObject
            val hallId = hall.string("id")
            ok(
                "$base/halls/$hallId/tables",
                SECOND_MANAGER,
                JsonObject(
                    obj("label" to "Linked table", "expectedVersion" to hall.string("layoutRevision")) +
                        ("tableNumber" to JsonPrimitive(2)) + ("capacity" to JsonPrimitive(4)),
                ),
            )
            val calendar = ok("$base/calendar", SECOND_MANAGER)
            ok(
                "$base/calendar",
                SECOND_MANAGER,
                calendarChange(calendar, start, end, ZoneId.of("Europe/Moscow")),
                HttpMethod.Patch,
            )
            source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    // An older layout-only row has survived V072–V086 without a booking projection.
                    statement.executeUpdate(
                        "INSERT INTO hall_tables(hall_id,table_number,label,capacity,zone_id) " +
                            "SELECT hall_id,1,'Earlier table',6,zone_id FROM hall_zones WHERE hall_id=$hallId",
                    )
                    statement.executeUpdate(
                        "UPDATE client_business_clubs SET evidence='VERIFIED_CREATION' WHERE club_id=$club",
                    )
                }
                val insertEvent = "INSERT INTO events(club_id,start_at,end_at) VALUES (?,?,?)"
                connection.prepareStatement(insertEvent).use { statement ->
                    statement.setLong(1, neighbor.toLong())
                    statement.setObject(2, start.atOffset(ZoneOffset.UTC))
                    statement.setObject(3, end.atOffset(ZoneOffset.UTC))
                    statement.executeUpdate()
                }
            }
            assertEquals("1", source.scalar("SELECT COUNT(*) FROM tables WHERE club_id=$club"))
        }
        return clubs
    }

    @Test
    fun `onboarding prepares future booking current mixed deposit and reuse without seeding`() =
        database { source ->
            val clock = DeskClock()
            withApp(source, clock) {
                val (club, neighbor) = business(source)
                val base = "$C/clubs/$club"
                claim(invite("$base/managers", RECIPIENT, "CLUB_MANAGER"), SECOND_MANAGER)
                claim(invite("$base/managers", SECOND_MANAGER, "FLOOR_MANAGER"), THIRD_MANAGER)
                val hall =
                    ok(
                        "$base/halls",
                        SECOND_MANAGER,
                        obj("name" to "Main"),
                    )["halls"]!!.jsonArray.single().jsonObject
                val tables =
                    ok(
                        "$base/halls/${hall.string("id")}/tables",
                        SECOND_MANAGER,
                        JsonObject(
                            obj(
                                "label" to "Table 1",
                                "expectedVersion" to hall.string("layoutRevision"),
                                "minDeposit" to "30000",
                            ) + ("tableNumber" to JsonPrimitive(1)) + ("capacity" to JsonPrimitive(6)),
                        ),
                    )
                val table =
                    tables["halls"]!!
                        .jsonArray
                        .single()
                        .jsonObject["tables"]!!
                        .jsonArray
                        .single()
                        .jsonObject
                        .string(
                            "id",
                        )
                val secondHall =
                    ok("$base/halls", SECOND_MANAGER, obj("name" to "Lounge"))["halls"]!!
                        .jsonArray
                        .last()
                        .jsonObject
                val loungeLayout =
                    ok(
                        "$base/halls/${secondHall.string("id")}/tables/batch",
                        SECOND_MANAGER,
                        JsonObject(
                            mapOf(
                                "expectedVersion" to JsonPrimitive(secondHall.string("layoutRevision")),
                                "minDeposit" to JsonPrimitive("12500.50"),
                                "tableNumbers" to JsonArray(listOf(JsonPrimitive(2))),
                                "capacity" to JsonPrimitive(4),
                            ),
                        ),
                    )
                val calendar = ok("$base/calendar", SECOND_MANAGER)
                val zone = ZoneId.of(calendar.string("timezone"))
                val start = clock.instant().plusSeconds(75).truncatedTo(ChronoUnit.MINUTES)
                val end = start.plusSeconds(120)
                val rulesPath = "$base/booking-rules"
                ok(
                    rulesPath,
                    SECOND_MANAGER,
                    JsonObject(
                        obj("expectedVersion" to ok(rulesPath, SECOND_MANAGER).string("version")) +
                            mapOf(
                                "cutoffMinutes" to JsonPrimitive(0),
                                "arrivalMinutes" to JsonPrimitive(0),
                                "holdMinutes" to JsonNull,
                                "retentionMinutes" to JsonPrimitive(1),
                            ),
                    ),
                    HttpMethod.Patch,
                )
                ok("$base/calendar", SECOND_MANAGER, calendarChange(calendar, start, end, zone), HttpMethod.Patch)
                val profile = ok("$base/profile", SECOND_MANAGER)
                val zoneChange =
                    requestAs(
                        "$base/profile",
                        SECOND_MANAGER,
                        obj("expectedVersion" to profile.string("version"), "timezone" to "Asia/Tokyo"),
                        HttpMethod.Patch,
                    )
                assertEquals(HttpStatusCode.Conflict, zoneChange.status)
                assertTrue(zoneChange.bodyAsText().contains("calendar_timezone_locked"))
                val previousEvent =
                    source.scalar(
                        "SELECT event_id FROM client_working_night_events WHERE club_id=$club",
                    )
                val editedEnd = end.plusSeconds(60)
                val editedCalendar = ok("$base/calendar", SECOND_MANAGER)
                ok(
                    "$base/calendar",
                    SECOND_MANAGER,
                    calendarChange(editedCalendar, start, editedEnd, zone),
                    HttpMethod.Patch,
                )
                val currentEvent =
                    source.scalar(
                        "SELECT event_id FROM client_working_night_events WHERE club_id=$club",
                    )
                assertTrue(previousEvent != currentEvent)
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM events WHERE club_id=$club"))
                val operations = "$base/operations"
                val desk = ok(operations, THIRD_MANAGER)
                assertEquals(JsonNull, desk["night"])
                val upcoming = desk["upcomingNights"]!!.jsonArray.single().jsonObject
                val night = upcoming.string("id")
                val baseCommand = obj("nightId" to night, "tableId" to table)
                assertEquals("2", source.scalar("SELECT COUNT(*) FROM booking_cutover_fences WHERE club_id=$club"))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM client_working_night_events WHERE club_id=$club"))
                assertEquals(
                    "0",
                    source.scalar("SELECT COUNT(*) FROM client_working_night_events WHERE club_id=$neighbor"),
                )
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs("$C/clubs/$neighbor/operations/nights/$night", THIRD_MANAGER).status,
                )
                assertGuestSeparation(source, club.toLong(), start, end)
                val future = ok("$operations/nights/$night", THIRD_MANAGER)
                assertEquals("true", future.string("future"))
                val workingTables = future["halls"]!!.jsonArray.flatMap { it.jsonObject["tables"]!!.jsonArray }
                assertEquals("30000.00", workingTables.first().jsonObject.string("minDeposit"))
                assertEquals(JsonPrimitive(6), workingTables.first().jsonObject["capacity"])
                assertEquals("12500.50", workingTables.last().jsonObject.string("minDeposit"))
                assertEquals(JsonPrimitive(4), workingTables.last().jsonObject["capacity"])
                assertFalse(future["permissions"]!!.jsonArray.contains(JsonPrimitive("client.club.operations.seat")))

                val reservation =
                    ok(
                        operations,
                        THIRD_MANAGER,
                        JsonObject(
                            baseCommand +
                                obj(
                                    "action" to "create_booking",
                                    "guestName" to "Guest",
                                    "bookingDepositMinor" to "5000000",
                                ) +
                                ("guests" to JsonPrimitive(2)),
                        ),
                    )
                val futureBooking = ok("$operations/nights/$night", THIRD_MANAGER)["bookings"]!!.jsonArray.single()
                assertEquals("5000000", futureBooking.jsonObject.string("bookingDepositMinor"))
                assertEquals("30000.00", futureBooking.jsonObject.string("minDeposit"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM table_deposit_operations"))
                val tablePath = "$base/halls/${hall.string("id")}/tables/$table"
                val revision =
                    tables["halls"]!!
                        .jsonArray
                        .first()
                        .jsonObject
                        .string("layoutRevision")
                val resize = obj("expectedVersion" to revision, "minDeposit" to "40000")
                val rejected =
                    requestAs(
                        tablePath,
                        SECOND_MANAGER,
                        JsonObject(resize + ("capacity" to JsonPrimitive(1))),
                        HttpMethod.Patch,
                    )
                assertEquals(HttpStatusCode.Conflict, rejected.status)
                assertTrue(rejected.bodyAsText().contains("table_capacity_conflict"))
                assertEquals(
                    "30000.00",
                    source.scalar("SELECT min_deposit FROM tables WHERE club_id=$club AND table_number=1"),
                )
                ok(tablePath, SECOND_MANAGER, JsonObject(resize + ("capacity" to JsonPrimitive(5))), HttpMethod.Patch)
                val saved = ok("$operations/nights/$night", THIRD_MANAGER)["bookings"]!!.jsonArray.single()
                assertEquals(futureBooking, saved)
                val loungeTable =
                    loungeLayout["halls"]!!
                        .jsonArray
                        .last()
                        .jsonObject["tables"]!!
                        .jsonArray
                        .single()
                        .jsonObject
                        .string("id")
                val savedCalendar = ok("$base/calendar", SECOND_MANAGER)
                val editBookedNight =
                    requestAs(
                        "$base/calendar",
                        SECOND_MANAGER,
                        calendarChange(savedCalendar, start, end, zone),
                        HttpMethod.Patch,
                    )
                assertEquals(HttpStatusCode.Conflict, editBookedNight.status)
                assertTrue(editBookedNight.bodyAsText().contains("calendar_night_dependencies"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM shift_reports"))
                val deposit =
                    JsonObject(
                        baseCommand +
                            obj(
                                "action" to "seat",
                                "bookingId" to reservation.string("bookingId"),
                                "mode" to "DEPOSIT",
                                "amountMinor" to "1500000",
                                "method" to "MIXED",
                                "cashMinor" to "500000",
                                "categoryVersion" to "1",
                            ) + ("expectedLastSessionId" to JsonNull) + ("extras" to JsonObject(emptyMap())),
                    )
                assertEquals(HttpStatusCode.Conflict, requestAs(operations, THIRD_MANAGER, deposit).status)
                clock.time = start.plusSeconds(1)
                exerciseMoveAndCancellation(
                    operations,
                    night,
                    table,
                    loungeTable,
                    reservation.string("bookingId"),
                    start,
                )
                assertEquals("false", ok(operations, THIRD_MANAGER).string("future"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM shift_reports"))
                val key = UUID.randomUUID().toString()
                val receipt = ok(operations, THIRD_MANAGER, deposit, key = key)
                assertEquals("1000000", receipt.string("cardMinor"))
                assertEquals(receipt, ok(operations, THIRD_MANAGER, deposit, key = key))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM shift_reports WHERE status='DRAFT'"))
                val sessionId = receipt.string("sessionId")
                val existing =
                    baseCommand + obj("sessionId" to sessionId, "expectedLastSessionId" to sessionId)
                val topUp =
                    JsonObject(
                        existing +
                            obj(
                                "action" to "top_up",
                                "amountMinor" to "500",
                                "method" to "CARD",
                                "categoryVersion" to "1",
                            ) +
                            ("extras" to JsonObject(emptyMap())),
                    )
                ok(operations, SECOND_MANAGER, topUp)
                ok(operations, THIRD_MANAGER, JsonObject(existing + obj("action" to "release")))
                val again =
                    ok(
                        operations,
                        THIRD_MANAGER,
                        JsonObject(
                            baseCommand +
                                obj("action" to "seat", "mode" to "BILL", "expectedLastSessionId" to sessionId),
                        ),
                    )
                assertTrue(again.string("sessionId") != sessionId)
                assertEquals("1500500", source.scalar("SELECT SUM(amount_minor) FROM table_deposit_operations"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM checkins"))
                clock.time = editedEnd.plusSeconds(1)
                val history = ok("$operations/nights/$night", THIRD_MANAGER)
                assertEquals("true", history.string("historical"))
                assertTrue(history["bookings"]!!.jsonArray.isEmpty())
                assertFalse(
                    history["permissions"]!!.jsonArray.contains(JsonPrimitive("client.club.operations.booking.write")),
                )
                val receipts = history["tableMoney"]!!.jsonArray.map { it.jsonObject }
                assertEquals(setOf("500", "1500000"), receipts.map { it.string("amountMinor") }.toSet())
                assertEquals(setOf("INITIAL", "TOPUP"), receipts.map { it.string("kind") }.toSet())
            }
        }

    private suspend fun ApplicationTestBuilder.exerciseMoveAndCancellation(
        operations: String,
        night: String,
        sourceTable: String,
        destination: String,
        booking: String,
        start: Instant,
    ) {
        val path = "$operations/nights/$night"

        suspend fun readBooking(id: String): JsonObject =
            ok(path, THIRD_MANAGER)["bookings"]!!
                .jsonArray
                .map { it.jsonObject }
                .single { it.string("id") == id }

        fun base(
            id: String,
            table: String,
            version: String,
        ) = obj(
            "nightId" to night,
            "tableId" to table,
            "bookingId" to id,
            "expectedUpdatedAt" to version,
        )
        val second =
            ok(
                operations,
                THIRD_MANAGER,
                JsonObject(
                    obj(
                        "action" to "create_booking",
                        "nightId" to night,
                        "tableId" to destination,
                        "guestName" to "Blocker",
                    ) +
                        ("guests" to JsonPrimitive(2)),
                ),
            ).string("bookingId")
        ok(
            operations,
            THIRD_MANAGER,
            JsonObject(
                base(booking, sourceTable, readBooking(booking).string("updatedAt")) +
                    obj("action" to "extend_wait", "waitUntil" to start.plusSeconds(10).toString()),
            ),
        )
        val before = readBooking(booking)
        val command =
            base(booking, sourceTable, before.string("updatedAt")) +
                obj(
                    "action" to "move_booking",
                    "destinationTableId" to destination,
                    "arrivalBy" to start.plusSeconds(30).toString(),
                )
        assertEquals(HttpStatusCode.Conflict, requestAs(operations, THIRD_MANAGER, JsonObject(command)).status)
        assertEquals(before, readBooking(booking))
        ok(
            operations,
            THIRD_MANAGER,
            JsonObject(
                base(second, destination, readBooking(second).string("updatedAt")) +
                    obj("action" to "cancel_booking", "reason" to "Не придёт"),
            ),
        )
        assertEquals("CANCELLED", readBooking(second).string("status"))
        assertEquals("Не придёт", readBooking(second).string("cancellationReason"))
        ok(operations, THIRD_MANAGER, JsonObject(command))
        val moved = readBooking(booking)
        assertEquals(destination, moved.string("tableId"))
        assertEquals(start.plusSeconds(30).toString(), moved.string("arrivalBy"))
        assertEquals(before.string("source"), moved.string("source"))
        assertTrue(Instant.parse(moved.string("waitUntil")) >= Instant.parse(moved.string("arrivalBy")))
        val newArrival = start.plusSeconds(61).toString()
        ok(
            operations,
            THIRD_MANAGER,
            JsonObject(
                base(booking, destination, moved.string("updatedAt")) +
                    obj("action" to "move_booking", "arrivalBy" to newArrival),
            ),
        )
        val movedTime = readBooking(booking)
        assertEquals(destination, movedTime.string("tableId"))
        assertEquals(newArrival, movedTime.string("arrivalBy"))
        ok(
            operations,
            THIRD_MANAGER,
            JsonObject(
                base(booking, destination, movedTime.string("updatedAt")) +
                    obj("action" to "move_booking", "destinationTableId" to sourceTable),
            ),
        )
        assertEquals(newArrival, readBooking(booking).string("arrivalBy"))
    }

    private suspend fun assertGuestSeparation(
        source: DataSource,
        clientClub: Long,
        start: Instant,
        end: Instant,
    ) {
        val legacy = 8181L
        source.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO clubs(id,name,timezone,is_active) VALUES ($legacy,'Public fixture','UTC',TRUE)",
                )
                statement.executeUpdate("INSERT INTO events(club_id,start_at,end_at) VALUES ($legacy,'$start','$end')")
                statement.executeUpdate(
                    "INSERT INTO tables(club_id,table_number,capacity,min_deposit,active) VALUES ($legacy,1,4,0,TRUE)",
                )
            }
        }
        val guest =
            DefaultAvailabilityService(
                Database.connect(source),
                Clock.fixed(start.minusSeconds(18000), ZoneOffset.UTC),
                legacyClubAccess = JdbcLegacyClubAccess(source),
            )
        assertTrue(guest.listOpenNights(clientClub).isEmpty())
        assertTrue(guest.listFreeTables(clientClub, start).isEmpty())
        assertEquals(1, guest.listOpenNights(legacy).size)
        assertEquals(1, guest.listFreeTables(legacy, start).size)
        val stalePreflight =
            DefaultAvailabilityService(
                Database.connect(source),
                Clock.fixed(start.minusSeconds(18000), ZoneOffset.UTC),
                legacyClubAccess = { false },
            )
        assertTrue(stalePreflight.listOpenNights(clientClub).isEmpty())
        assertTrue(stalePreflight.listFreeTables(clientClub, start).isEmpty())
    }

    private fun calendarChange(
        calendar: JsonObject,
        start: Instant,
        end: Instant,
        zone: ZoneId,
    ): JsonObject {
        val from = start.atZone(zone)
        val to = end.atZone(zone)
        val format = DateTimeFormatter.ofPattern("HH:mm")
        val exception =
            JsonObject(
                obj(
                    "date" to from.toLocalDate().toString(),
                    "mode" to "open",
                    "open" to format.format(from),
                    "close" to format.format(to),
                ) + ("nextDay" to JsonPrimitive(from.toLocalDate() != to.toLocalDate())),
            )
        return JsonObject(
            obj("expectedVersion" to calendar.string("version"), "timezone" to zone.id) +
                ("weekly" to JsonArray(emptyList())) +
                ("holidays" to JsonArray(emptyList())) +
                ("exceptions" to JsonArray(listOf(exception))),
        )
    }
}

@Tag("it")
class AutomaticClubOperationsPostgresIT : AutomaticClubOperationsRoutesTest() {
    override fun databaseAt(
        target: String?,
        block: (DataSource) -> Unit,
    ) = withClientManagerPostgres(target, block)
}

open class ClubTableCapacityRoutesTest : ClientManagerRoutesFixture() {
    @Test
    fun `capacity edits respect a live hold and new legacy admission uses the resized table`() =
        database { source ->
            val clock = DeskClock()
            withApp(source, clock) {
                val (club) = business(source)
                source.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate("DELETE FROM client_working_clubs WHERE club_id=$club")
                    }
                }
                val base = "$C/clubs/$club"
                claim(invite("$base/managers", RECIPIENT, "CLUB_MANAGER"), SECOND_MANAGER)
                val created = ok("$base/halls", SECOND_MANAGER, obj("name" to "Hall"))
                val hall = created["halls"]!!.jsonArray.single().jsonObject
                val path = "$base/halls/${hall.string("id")}/tables"
                val layout =
                    ok(
                        path,
                        SECOND_MANAGER,
                        JsonObject(
                            obj("label" to "Table 1", "expectedVersion" to hall.string("layoutRevision")) +
                                ("tableNumber" to JsonPrimitive(1)) + ("capacity" to JsonPrimitive(6)),
                        ),
                    )
                val createdHall = layout["halls"]!!.jsonArray.single().jsonObject
                val savedTable = createdHall["tables"]!!.jsonArray.single().jsonObject
                val table = savedTable.string("id")
                val bookingTable = source.scalar("SELECT booking_table_id FROM hall_tables WHERE id=$table").toLong()
                val start = clock.instant().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60)
                val end = start.plusSeconds(3600)
                source.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(
                            "INSERT INTO events(club_id,start_at,end_at) VALUES ($club,'$start','$end')",
                        )
                    }
                }
                val holds = BookingHoldRepository(Database.connect(source), clock)
                val ttl = Duration.ofMinutes(2)
                val before = holds.createHold(bookingTable, start, end, 6, ttl, "table-resize-old")
                assertTrue(before is BookingCoreResult.Success)
                val resize =
                    JsonObject(
                        obj("expectedVersion" to createdHall.string("layoutRevision")) +
                            ("capacity" to JsonPrimitive(4)),
                    )
                val blocked = requestAs("$path/$table", SECOND_MANAGER, resize, HttpMethod.Patch)
                assertEquals(HttpStatusCode.Conflict, blocked.status)
                assertTrue(blocked.bodyAsText().contains("table_capacity_conflict"))
                clock.time = clock.instant().plus(ttl).plusSeconds(1)
                ok("$path/$table", SECOND_MANAGER, resize, HttpMethod.Patch)
                assertEquals(
                    BookingCoreResult.Failure(BookingCoreError.TableOccupied),
                    holds.createHold(bookingTable, start, end, 6, ttl, "table-resize-new"),
                )
                val after = holds.createHold(bookingTable, start, end, 4, ttl, "table-resize-valid")
                assertTrue(after is BookingCoreResult.Success)
            }
        }
}

@Tag("it")
class ClubTableCapacityPostgresIT : ClubTableCapacityRoutesTest() {
    override fun databaseAt(
        target: String?,
        block: (DataSource) -> Unit,
    ) = withClientManagerPostgres(target, block)
}

private class DeskClock : Clock() {
    @Volatile var time: Instant = Instant.now().plusSeconds(30)

    override fun instant() = time

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(time, zone)
}
