package com.example.bot.routes

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Signed HTTP through Ktor and the real reconciler; inherited unchanged by the PostgreSQL adapter. */
open class ClientCalendarReconciliationRoutesDataTest : ClientOnboardingRoutesTestBase() {
    @Test
    fun `removed dated exception outside horizon reconciles fallback and preserves persistent identity`() =
        database { source ->
            withApp(source, AUGUST) {
                val club = club()
                val clubId = club.string("id")
                val path = "$C/clubs/$clubId/calendar"
                val saved =
                    patch(
                        path,
                        body(
                            ok(path, RECIPIENT),
                            weekly = SATURDAY,
                            exceptions = """[{"date":"2027-10-30",$SAFE_HOURS}]""",
                        ),
                    )
                assertEquals("2027-08-01", saved.reconciliation().string("businessDate"))
                assertTrue("2027-10-30" in saved.storedDates())
                assertFalse(saved.rows("nights").any { it.string("businessDate") == "2027-10-30" })
                val id =
                    source.scalar(
                        "SELECT id FROM club_operational_nights WHERE club_id=$clubId AND business_date='2027-10-30'",
                    )
                assertEquals(Instant.parse("2027-10-31T02:30:00Z"), source.start(clubId, "2027-10-30"))
                val removed = patch(path, body(saved, exceptions = """[{"date":"2027-10-30","mode":"remove"}]"""))
                assertEquals(
                    id,
                    source.scalar(
                        "SELECT id FROM club_operational_nights WHERE club_id=$clubId AND business_date='2027-10-30'",
                    ),
                )
                assertEquals(Instant.parse("2027-10-31T00:30:00Z"), source.start(clubId, "2027-10-30"))
                assertTrue(removed.rows("exceptions").isEmpty())
                assertEquals("WEEKEND_RULE", source.scalar("SELECT source FROM club_operational_nights WHERE id=$id"))
            }
        }

    @Test
    fun `complete metadata includes hidden cancelled stored dates reopened by weekly fallback`() =
        database { source ->
            withApp(source, AUGUST) {
                val club = club()
                val id = club.string("id")
                val path = "$C/clubs/$id/calendar"
                val dates = (0L..9).map { LocalDate.parse("2027-09-04").plusWeeks(it) }
                dates.forEach { source.seedNight(club, it, "CANCELLED") }
                source.seedNight(club, LocalDate.parse("2027-07-29"), "CANCELLED")
                source.seedNight(club, LocalDate.parse("2027-07-28"), "CANCELLED")
                val current = ok(path, RECIPIENT)
                assertEquals(8, current.rows("cancelledNights").size)
                assertFalse(current.rows("cancelledNights").any { it.string("businessDate") == "2027-10-30" })
                assertTrue("2027-10-30" in current.storedDates())
                assertTrue("2027-07-29" in current.storedDates())
                assertFalse("2027-07-28" in current.storedDates())
                val nightId =
                    source.scalar(
                        "SELECT id FROM club_operational_nights WHERE club_id=$id AND business_date='2027-10-30'",
                    )
                patch(path, body(current, weekly = SATURDAY))
                assertEquals("SCHEDULED", source.scalar("SELECT status FROM club_operational_nights WHERE id=$nightId"))
                assertEquals(Instant.parse("2027-10-31T00:30:00Z"), source.start(id, "2027-10-30"))
            }
        }

    @Test
    fun `spring gap uses canonical shifted instant and Friday midnight remains an eight hour night`() =
        database { source ->
            withApp(source, MARCH) {
                val club = club()
                val id = club.string("id")
                val path = "$C/clubs/$id/calendar"
                val saved =
                    patch(
                        path,
                        body(
                            ok(path, RECIPIENT),
                            exceptions = """
                                [{"date":"2027-03-27","mode":"open","open":"01:30","close":"05:00",
                                  "nextDay":false,"startNextDay":true},
                                 {"date":"2027-08-06","mode":"open","open":"00:00","close":"08:00",
                                  "nextDay":false,"startNextDay":true}]
                            """,
                        ),
                    )
                assertTrue("2027-03-27" in saved.storedDates())
                // java.time's existing gap policy shifts 01:30 to02:30 BST. The Mini App must block this proposal.
                assertEquals(Instant.parse("2027-03-28T01:30:00Z"), source.start(id, "2027-03-27"))
                assertEquals(Instant.parse("2027-08-06T23:00:00Z"), source.start(id, "2027-08-06"))
                assertEquals("8", source.durationHours(id, "2027-08-06"))
                assertEquals(saved, patch(path, body(saved, reconcile = true)))
            }
        }

    @Test
    fun `legacy dated DTOs preserve valid inherited partial and explicit server windows`() {
        val cases =
            listOf(
                listOf(null, null, null, "2027-10-31T00:30:00Z"),
                listOf("23:00", null, null, "2027-10-31T00:30:00Z"),
                listOf(null, "02:00", null, "2027-10-31T02:00:00Z"),
                listOf("00:30", "01:30", null, "2027-10-30T00:30:00Z"),
                listOf("00:30", "01:30", "FALSE", "2027-10-30T00:30:00Z"),
                listOf("00:30", "01:30", "TRUE", "2027-10-31T00:30:00Z"),
            )
        for (window in cases) {
            val (open, close, override) = window
            val expectedEnd = checkNotNull(window.last())
            database { source ->
                withApp(source, AUGUST) {
                    val club = club()
                    val id = club.string("id")
                    val path = "$C/clubs/$id/calendar"
                    patch(path, body(ok(path, RECIPIENT), weekly = LATE_SATURDAY))
                    val opening = open?.let { "'$it'" } ?: "NULL"
                    val closing = close?.let { "'$it'" } ?: "NULL"
                    source.execute(
                        "INSERT INTO club_holidays(club_id,date,is_open,override_open_local,override_close_local," +
                            "close_next_day,start_next_day) " +
                            "VALUES ($id,'2027-10-30',TRUE,$opening,$closing,${override ?: "NULL"},FALSE)",
                    )
                    val current = ok(path, RECIPIENT)
                    assertEquals(JsonPrimitive(override == "TRUE"), current.rows("holidays").single()["nextDay"])
                    val saved = patch(path, body(current, reconcile = true))
                    assertEquals(JsonPrimitive(override == "TRUE"), saved.rows("holidays").single()["nextDay"])
                    assertEquals(Instant.parse(expectedEnd), source.instant(id, "2027-10-30", "end_at"))
                }
            }
        }
    }

    @Test
    fun `a skipped Apia wall date in lookback still materializes a future canonical interval`() =
        database { source ->
            withApp(source, Clock.fixed(Instant.parse("2011-12-30T10:00:00Z"), ZoneOffset.UTC)) {
                val club = club()
                val id = club.string("id")
                val profilePath = "$C/clubs/$id/profile"
                val profile = ok(profilePath, RECIPIENT)
                ok(
                    profilePath,
                    RECIPIENT,
                    obj("expectedVersion" to profile.string("version"), "timezone" to "Pacific/Apia"),
                    HttpMethod.Patch,
                )
                val path = "$C/clubs/$id/calendar"
                val initial = ok(path, RECIPIENT)
                assertEquals("2011-12-31", initial.reconciliation().string("businessDate"))
                val saved = patch(path, body(initial, weekly = APIA_FRIDAY))
                assertTrue("2011-12-30" in saved.storedDates())
                // The old wall-date shortcut must not call this interval expired: java.time advances both boundaries.
                assertEquals(Instant.parse("2011-12-30T10:30:00Z"), source.start(id, "2011-12-30"))
                assertEquals(Instant.parse("2011-12-30T18:00:00Z"), source.instant(id, "2011-12-30", "end_at"))
                val night = saved.rows("nights").first()
                assertEquals("2011-12-30", night.string("businessDate"))
                assertEquals("2011-12-31T00:30+14:00", night.string("opensAt"))
                assertEquals("2011-12-31T08:00+14:00", night.string("closesAt"))
            }
        }

    @Test
    fun `touched dates older than lookback still reject started changes atomically`() =
        database { source ->
            lateinit var path: String
            lateinit var clubId: String
            withApp(source, JULY) {
                clubId = club().string("id")
                path = "$C/clubs/$clubId/calendar"
                patch(path, body(ok(path, RECIPIENT), exceptions = """[{"date":"2027-07-20",$SAFE_HOURS}]"""))
            }
            withApp(source, AUGUST) {
                val current = ok(path, RECIPIENT)
                assertFalse("2027-07-20" in current.storedDates())
                val counts = source.counts()
                requestAs(
                    path,
                    RECIPIENT,
                    body(current, exceptions = """[{"date":"2027-07-20","mode":"remove"}]"""),
                    HttpMethod.Patch,
                ).assertClientError(HttpStatusCode.Conflict, "calendar_night_started")
                assertEquals(current, ok(path, RECIPIENT))
                assertEquals(counts, source.counts())
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM club_exceptions WHERE club_id=$clubId"))
            }
        }

    @Test
    fun `day bound command rejects midnight without partial state and can safely retry same key`() =
        database { source ->
            lateinit var path: String
            lateinit var before: JsonObject
            lateinit var oldBody: JsonObject
            withApp(source, Clock.fixed(Instant.parse("2027-08-01T22:59:00Z"), ZoneOffset.UTC)) {
                path = "$C/clubs/${club().string("id")}/calendar"
                before = ok(path, RECIPIENT)
                oldBody = body(before, exceptions = """[{"date":"2027-10-30",$SAFE_HOURS}]""")
            }
            withApp(source, Clock.fixed(Instant.parse("2027-08-01T23:01:00Z"), ZoneOffset.UTC)) {
                val counts = source.counts()
                requestAs(path, RECIPIENT, oldBody, HttpMethod.Patch, "day-fence-safe-retry")
                    .assertClientError(HttpStatusCode.Conflict, "version_conflict")
                assertEquals(counts, source.counts())
                val refreshed = ok(path, RECIPIENT)
                assertEquals(before.string("version"), refreshed.string("version"))
                assertEquals("2027-08-02", refreshed.reconciliation().string("businessDate"))
                val corrected = body(refreshed, exceptions = """[{"date":"2027-10-30",$SAFE_HOURS}]""")
                val saved = patch(path, corrected, "day-fence-safe-retry")
                assertEquals(saved, patch(path, corrected, "day-fence-safe-retry"))
                assertEquals(
                    "1",
                    source.scalar("SELECT COUNT(*) FROM client_commands WHERE result_type='club_calendar'"),
                )
                assertEquals(
                    "1",
                    source.scalar("SELECT COUNT(*) FROM audit_log WHERE action='client.club.calendar.update'"),
                )
                for (bad in listOf(JsonPrimitive("2027-02-30"), JsonPrimitive("not-a-date"), JsonPrimitive(true))) {
                    val invalid = JsonObject(body(saved, reconcile = true) + ("reconciliationDate" to bad))
                    requestAs(path, RECIPIENT, invalid, HttpMethod.Patch)
                        .assertClientError(HttpStatusCode.BadRequest, "validation_error")
                }
                // Optional fence is additive: existing calendar clients retain their old request contract.
                val legacy = JsonObject(body(saved, reconcile = true) - "reconciliationDate")
                assertEquals(saved, patch(path, legacy))
            }
        }

    @Test
    fun `metadata uses exact current read authority and never reveals another clients stored dates`() =
        database { source ->
            withApp(source, AUGUST) {
                val own = club()
                val foreign = club(FOREIGN)
                val path = "$C/clubs/${own.string("id")}/calendar"
                source.seedNight(own, LocalDate.parse("2027-10-30"), "CANCELLED")
                source.seedNight(foreign, LocalDate.parse("2029-12-01"), "CANCELLED")
                source.execute(
                    "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=${own.string("id")} " +
                        "AND permission_code='client.club.calendar.write'",
                )
                val readOnly = ok(path, RECIPIENT)
                assertEquals(JsonPrimitive(false), readOnly["canWrite"])
                assertEquals(listOf("2027-10-30"), readOnly.storedDates())
                requestAs(path, FOREIGN).assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs("$C/clubs/${foreign.string("id")}/calendar", RECIPIENT)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs(path, RECIPIENT, body(readOnly, reconcile = true), HttpMethod.Patch)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                source.execute(
                    "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=${own.string("id")} " +
                        "AND permission_code='client.club.read'",
                )
                requestAs(path, RECIPIENT).assertClientError(HttpStatusCode.NotFound, "not_found")
            }
        }

    private suspend fun ApplicationTestBuilder.club(user: Long = RECIPIENT): JsonObject {
        val invite = ok("$P/invitations", OWNER, obj("clientName" to "Calendar $user", "recipientLabel" to "Owner"))
        ok(
            "$C/invitations/${invite.string("id")}/claim",
            user,
            obj("token" to invite.string("token"), "expectedVersion" to invite.string("version")),
        )
        val club =
            ok(
                "$C/clients/${invite.string("clientId")}/clubs",
                user,
                obj("venueName" to "Calendar club", "countryCode" to "RU", "cityId" to "524901", "address" to "Street"),
            )
        ok(
            "$C/clubs/${club.string("id")}/profile",
            user,
            obj("expectedVersion" to club.string("version"), "timezone" to "Europe/London"),
            HttpMethod.Patch,
        )
        return club
    }

    private suspend fun ApplicationTestBuilder.patch(
        path: String,
        body: JsonObject,
        key: String =
            java.util.UUID
                .randomUUID()
                .toString(),
    ) = ok(path, RECIPIENT, body, HttpMethod.Patch, key)

    private fun body(
        view: JsonObject,
        weekly: String = "[]",
        exceptions: String = "[]",
        reconcile: Boolean = false,
    ) = JsonObject(
        obj(
            "expectedVersion" to view.string("version"),
            "timezone" to view.string("timezone"),
            "reconciliationDate" to view.reconciliation().string("businessDate"),
        ) +
            mapOf(
                "weekly" to array(weekly),
                "holidays" to array("[]"),
                "exceptions" to array(exceptions),
            ) + if (reconcile) mapOf("reconcile" to JsonPrimitive(true)) else emptyMap(),
    )

    private fun array(value: String): JsonArray = Json.parseToJsonElement(value).jsonArray

    private fun JsonObject.rows(key: String) = getValue(key).jsonArray.map { it.jsonObject }

    private fun JsonObject.reconciliation() = getValue("reconciliation").jsonObject

    private fun JsonObject.storedDates() =
        reconciliation().getValue("storedDates").jsonArray.map {
            it.jsonPrimitive.content
        }

    private fun DataSource.counts() =
        listOf("audit_log", "client_commands", "club_operational_nights", "club_exceptions").map {
            scalar("SELECT COUNT(*) FROM $it")
        }

    private fun DataSource.start(
        clubId: String,
        date: String,
    ): Instant = instant(clubId, date, "start_at")

    private fun DataSource.instant(
        clubId: String,
        date: String,
        column: String,
    ): Instant =
        connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT $column FROM club_operational_nights WHERE club_id=$clubId AND business_date='$date'",
                    ).use {
                        check(it.next())
                        it.getTimestamp(1).toInstant()
                    }
            }
        }

    private fun DataSource.durationHours(
        clubId: String,
        date: String,
    ): String =
        connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT start_at,end_at FROM club_operational_nights WHERE club_id=$clubId AND business_date='$date'",
                    ).use {
                        check(it.next())
                        java.time.Duration
                            .between(
                                it.getTimestamp(1).toInstant(),
                                it.getTimestamp(2).toInstant(),
                            ).toHours()
                            .toString()
                    }
            }
        }

    private fun DataSource.seedNight(
        club: JsonObject,
        date: LocalDate,
        status: String,
    ) {
        val start =
            date
                .plusDays(1)
                .atTime(LocalTime.of(3, 0))
                .atZone(ZoneId.of("Europe/London"))
                .toInstant()
        val end = start.plusSeconds(7200)
        execute(
            "INSERT INTO club_operational_nights(client_id,club_id,business_date,timezone,start_at,end_at," +
                "source,status,created_at,updated_at) " +
                "VALUES (${club.string(
                    "clientId",
                )},${club.string(
                    "id",
                )},'$date','Europe/London','$start','$end','EXCEPTION','$status','$start','$start')",
        )
    }

    private fun DataSource.execute(sql: String) {
        connection.use { it.createStatement().use { statement -> statement.execute(sql) } }
    }

    private companion object {
        val AUGUST: Clock = Clock.fixed(Instant.parse("2027-08-01T12:00:00Z"), ZoneOffset.UTC)
        val MARCH: Clock = Clock.fixed(Instant.parse("2027-03-01T12:00:00Z"), ZoneOffset.UTC)
        val JULY: Clock = Clock.fixed(Instant.parse("2027-07-01T12:00:00Z"), ZoneOffset.UTC)
        const val SAFE_HOURS =
            "\"mode\":\"open\",\"open\":\"02:30\",\"close\":\"05:00\"," +
                "\"nextDay\":false,\"startNextDay\":true"
        const val APIA_FRIDAY =
            "[{\"day\":5,\"mode\":\"open\",\"open\":\"00:30\",\"close\":\"08:00\"," +
                "\"nextDay\":false}]"
        const val LATE_SATURDAY =
            "[{\"day\":6,\"mode\":\"open\",\"open\":\"22:00\",\"close\":\"01:30\"," +
                "\"nextDay\":true}]"
        const val SATURDAY =
            "[{\"day\":6,\"mode\":\"open\",\"open\":\"01:30\",\"close\":\"05:00\"," +
                "\"nextDay\":false,\"startNextDay\":true}]"
    }
}
