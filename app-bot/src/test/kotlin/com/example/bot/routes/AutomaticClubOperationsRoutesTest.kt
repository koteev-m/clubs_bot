package com.example.bot.routes

import com.example.bot.availability.DefaultAvailabilityService
import com.example.bot.data.privacy.JdbcLegacyClubAccess
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
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
                            obj("label" to "Table 1", "expectedVersion" to hall.string("layoutRevision")) +
                                ("tableNumber" to JsonPrimitive(1)) + ("capacity" to JsonPrimitive(6)),
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
                ok(
                    "$base/halls/${secondHall.string("id")}/tables",
                    SECOND_MANAGER,
                    JsonObject(
                        obj("label" to "Lounge table", "expectedVersion" to secondHall.string("layoutRevision")) +
                            ("tableNumber" to JsonPrimitive(2)) + ("capacity" to JsonPrimitive(4)),
                    ),
                )
                val calendar = ok("$base/calendar", SECOND_MANAGER)
                val zone = ZoneId.of(calendar.string("timezone"))
                val start = clock.instant().plusSeconds(125).truncatedTo(ChronoUnit.MINUTES)
                val end = start.plusSeconds(6 * 3600)
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
                val editedEnd = end.plusSeconds(900)
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
                assertFalse(future["permissions"]!!.jsonArray.contains(JsonPrimitive("client.club.operations.seat")))

                val reservation =
                    ok(
                        operations,
                        THIRD_MANAGER,
                        JsonObject(
                            baseCommand + obj("action" to "create_booking", "guestName" to "Guest") +
                                ("guests" to JsonPrimitive(2)),
                        ),
                    )
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
            }
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

    private class DeskClock : Clock() {
        @Volatile var time: Instant = Instant.now().plusSeconds(30)

        override fun instant() = time

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = Clock.fixed(time, zone)
    }
}

@Tag("it")
class AutomaticClubOperationsPostgresIT : AutomaticClubOperationsRoutesTest() {
    override fun databaseAt(
        target: String?,
        block: (DataSource) -> Unit,
    ) = withClientManagerPostgres(target, block)
}
