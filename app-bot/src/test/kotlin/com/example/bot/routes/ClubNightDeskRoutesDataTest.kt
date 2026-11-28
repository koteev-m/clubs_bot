package com.example.bot.routes

import com.example.bot.club.CheckinMethod
import com.example.bot.club.CheckinResultStatus
import com.example.bot.club.CheckinSubjectType
import com.example.bot.data.booking.TableSessionRepository
import com.example.bot.data.club.CheckinDbRepository
import com.example.bot.data.club.NewCheckin
import com.example.bot.data.finance.ShiftReportRepository
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
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

open class ClubNightDeskRoutesDataTest : ClientManagerRoutesFixture() {
    @Test
    fun `cash cards allocations replays release and isolation use persistent ledger`() =
        database { source ->
            withApp(source) {
                val desk = setup(source)
                val first = seat(desk, desk.table, "1500000", "CASH")
                val key = UUID.randomUUID().toString()
                val initial = ok(desk.path, THIRD_MANAGER, first, key = key)
                assertEquals(initial, ok(desk.path, THIRD_MANAGER, first, key = key))
                assertEquals("CASH", initial.string("method"))
                assertEquals("1500000", initial.string("amountMinor"))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM table_sessions WHERE seated_guests IS NULL"))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM table_deposit_operations"))
                assertEquals(
                    "Основной депозит",
                    source.scalar("SELECT category_name_snapshot FROM table_deposit_operation_allocations"),
                )
                assertEquals("1500000", source.scalar("SELECT amount_minor FROM table_deposit_operation_allocations"))

                val configPath = "$C/clubs/${desk.club}/deposit-configuration"
                assertEquals(HttpStatusCode.NotFound, requestAs(configPath, THIRD_MANAGER).status)
                val config = saveConfig(configPath, "1", "Бар")
                val category =
                    config["extras"]!!
                        .jsonArray
                        .single()
                        .jsonObject
                        .string("id")
                val sessionId = initial.string("sessionId")
                val topup =
                    JsonObject(
                        seat(desk, desk.table, "1500000", "CARD") -
                            setOf("mode") +
                            obj(
                                "action" to "top_up",
                                "sessionId" to sessionId,
                                "expectedLastSessionId" to sessionId,
                                "categoryVersion" to "2",
                            ) + ("extras" to obj(category to "300000")),
                    )
                val topupReceipt = ok(desk.path, THIRD_MANAGER, topup)
                assertEquals("CARD", topupReceipt.string("method"))
                assertEquals(
                    "1200000",
                    source.scalar(
                        "SELECT amount_minor FROM table_deposit_operation_allocations WHERE " +
                            "category_name_snapshot='Бар'",
                    ),
                )
                assertEquals(
                    "300000",
                    source.scalar(
                        "SELECT amount_minor FROM table_deposit_operation_allocations WHERE " +
                            "category_name_snapshot='Шары'",
                    ),
                )
                saveConfig(configPath, "2", "Бар и кухня", category)
                assertEquals(
                    "Бар",
                    source.scalar(
                        "SELECT category_name_snapshot FROM table_deposit_operation_allocations " +
                            "WHERE amount_minor=1200000",
                    ),
                )
                assertEquals(HttpStatusCode.Conflict, requestAs(desk.path, THIRD_MANAGER, topup).status)
                val release =
                    obj(
                        "action" to "release",
                        "nightId" to desk.night,
                        "tableId" to desk.table,
                        "sessionId" to sessionId,
                        "expectedLastSessionId" to sessionId,
                    )
                ok(desk.path, THIRD_MANAGER, release)
                assertEquals(initial, ok(desk.path, THIRD_MANAGER, first, key = key))
                assertEquals("CLOSED", source.scalar("SELECT status FROM table_sessions"))
                assertEquals("3000000", source.scalar("SELECT SUM(amount_minor) FROM table_deposit_operations"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM checkins"))
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs("$C/clubs/${desk.other}/operations", THIRD_MANAGER).status,
                )
                assertEquals(HttpStatusCode.NotFound, requestAs(desk.path, RECIPIENT).status)
            }
        }

    @Test
    fun `mixed seat and top up preserve independent tender and allocation amounts through replay`() =
        database { source ->
            withApp(source) {
                val desk = setup(source)
                val config = saveConfig("$C/clubs/${desk.club}/deposit-configuration", "1", "Бар")
                val category =
                    config["extras"]!!
                        .jsonArray
                        .single()
                        .jsonObject
                        .string("id")
                val seat = mixedSeat(desk, "1500000", "500000")
                val initial = JsonObject(seat + obj("categoryVersion" to "2") + ("extras" to obj(category to "300000")))
                for (cash in listOf("0", "1500000", "1500001", "-1", "12.34")) {
                    val invalid = JsonObject(initial + obj("cashMinor" to cash))
                    assertEquals(HttpStatusCode.BadRequest, requestAs(desk.path, THIRD_MANAGER, invalid).status)
                }
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM table_sessions"))
                val key = UUID.randomUUID().toString()
                val receipt = ok(desk.path, THIRD_MANAGER, initial, key = key)
                assertEquals(receipt, ok(desk.path, THIRD_MANAGER, initial, key = key))
                assertEquals("MIXED", receipt.string("method"))
                assertEquals("500000", receipt.string("cashMinor"))
                assertEquals("1000000", receipt.string("cardMinor"))
                assertEquals(
                    HttpStatusCode.Conflict,
                    requestAs(
                        desk.path,
                        THIRD_MANAGER,
                        JsonObject(initial + obj("cashMinor" to "700000")),
                        key = key,
                    ).status,
                )
                val session = receipt.string("sessionId")
                val extra =
                    JsonObject(
                        mixedSeat(desk, "501", "400") - "mode" +
                            obj(
                                "action" to "top_up",
                                "categoryVersion" to "2",
                                "sessionId" to session,
                                "expectedLastSessionId" to session,
                            ),
                    )
                val topup = ok(desk.path, THIRD_MANAGER, extra)
                assertEquals("400", topup.string("cashMinor"))
                assertEquals("101", topup.string("cardMinor"))
                assertEquals("2", source.scalar("SELECT COUNT(*) FROM table_deposit_operations"))
                val start =
                    source.connection.use { conn ->
                        conn.createStatement().use { st ->
                            st.executeQuery("SELECT night_start_utc FROM shift_reports").use { rs ->
                                check(rs.next())
                                rs.getObject(1, OffsetDateTime::class.java).toInstant()
                            }
                        }
                    }
                val hints = ShiftReportRepository(Database.connect(source)).getDepositHints(desk.club.toLong(), start)
                assertEquals(1500501L, hints.sumDepositsForNight)
                assertEquals(500400L, hints.receivedCashMinor)
                assertEquals(1000101L, hints.receivedCardMinor)
                assertEquals(0L, hints.unattributedTenderMinor)
                assertEquals(
                    mapOf("primary" to 1200501L, "category_$category" to 300000L),
                    hints.allocationSummaryForNight,
                )
                assertEquals(
                    0L,
                    ShiftReportRepository(Database.connect(source))
                        .getDepositHints(desk.other.toLong(), start)
                        .sumDepositsForNight,
                )
            }
        }

    @Test
    fun `competing seat requests preserve one session and one payment`() =
        database { source ->
            withApp(source) {
                val desk = setup(source)
                val entered = mixedSeat(desk, "1500000", "500000")
                val results =
                    coroutineScope {
                        listOf(SECOND_MANAGER, THIRD_MANAGER)
                            .map { actor ->
                                async { requestAs(desk.path, actor, entered).status }
                            }.awaitAll()
                    }
                assertEquals(setOf(HttpStatusCode.OK, HttpStatusCode.Conflict), results.toSet())
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM table_sessions"))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM table_deposit_operations"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM checkins"))
            }
        }

    @Test
    fun `booking attendance and financial reports are separate from staff seating`() =
        database { source ->
            withApp(source) {
                val desk = setup(source)
                val booked =
                    ok(
                        desk.path,
                        THIRD_MANAGER,
                        JsonObject(
                            obj(
                                "action" to "create_booking",
                                "nightId" to desk.night,
                                "tableId" to desk.table,
                                "guestName" to "Guest A",
                                "phone" to "+79990000000",
                            ) +
                                ("guests" to JsonPrimitive(3)),
                        ),
                    )
                val booking = booked.string("bookingId")
                val reports = "$C/clubs/${desk.club}/operations"
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs(
                        "$reports/reports",
                        THIRD_MANAGER,
                        obj(
                            "period" to "current",
                        ),
                    ).status,
                )
                assertEquals(HttpStatusCode.NotFound, requestAs("$reports/financial-reports", SECOND_MANAGER).status)
                val grantPath = "$reports/financial-report-access"
                val assignment =
                    ok(grantPath, RECIPIENT)["assignments"]!!
                        .jsonArray
                        .single()
                        .jsonObject
                        .string("id")
                val grant =
                    JsonObject(
                        obj("assignmentId" to assignment, "expectedVersion" to "1") +
                            ("active" to JsonPrimitive(true)),
                    )
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs(grantPath, SECOND_MANAGER, grant, HttpMethod.Patch).status,
                )
                ok(grantPath, RECIPIENT, grant, HttpMethod.Patch)
                assertTrue(ok("$reports/financial-reports", SECOND_MANAGER)["reports"]!!.jsonArray.isEmpty())
                val info = ok(desk.path, THIRD_MANAGER)["bookings"]!!.jsonArray.single().jsonObject
                assertEquals("CLUB", info.string("source"))
                assertEquals(JsonNull, info["entryStatus"])
                val seated =
                    ok(
                        desk.path,
                        THIRD_MANAGER,
                        JsonObject(
                            obj(
                                "action" to "seat",
                                "nightId" to desk.night,
                                "tableId" to desk.table,
                                "bookingId" to booking,
                                "mode" to "BILL",
                            ) + ("expectedLastSessionId" to JsonNull),
                        ),
                    )
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM bookings WHERE status='SEATED'"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM table_deposit_operations"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM checkins"))
                assertFalse(seated.containsKey("operationId"))
                val analytics = ok("$reports/reports", SECOND_MANAGER, obj("period" to "current"))
                val metrics =
                    analytics["nights"]!!
                        .jsonArray
                        .single()
                        .jsonObject["metrics"]!!
                        .jsonObject
                assertEquals("1", metrics.string("seatedBookings"))
                assertEquals("0", metrics.string("entrants"))
                assertEquals(JsonNull, metrics["knownSeatedGuests"])
                assertFalse(analytics.containsKey("reports"))
                val revoke =
                    JsonObject(
                        obj("assignmentId" to assignment, "expectedVersion" to "2") +
                            ("active" to JsonPrimitive(false)),
                    )
                ok(grantPath, RECIPIENT, revoke, HttpMethod.Patch)
                assertEquals(HttpStatusCode.Conflict, requestAs(grantPath, RECIPIENT, grant, HttpMethod.Patch).status)
                assertEquals(HttpStatusCode.NotFound, requestAs("$reports/financial-reports", SECOND_MANAGER).status)

                val roster = ok("$C/clubs/${desk.club}/managers", RECIPIENT)
                val sourceRow = roster["assignments"]!!.jsonArray.single().jsonObject
                ok("$C/clubs/${desk.club}/managers", RECIPIENT, revokeSource(roster, sourceRow, "revoke_assignment"))
                assertEquals(HttpStatusCode.NotFound, requestAs("$reports/financial-reports", SECOND_MANAGER).status)
                assertEquals(1, ok(desk.path, THIRD_MANAGER)["bookings"]!!.jsonArray.size)
            }
        }

    @Test
    fun `existing entrance denial blocks seating and release preserves a reusable table`() =
        database { source ->
            withApp(source) {
                val desk = setup(source)
                val bookingCommand =
                    JsonObject(
                        obj(
                            "action" to "create_booking",
                            "nightId" to desk.night,
                            "tableId" to desk.table,
                            "guestName" to "Guest",
                        ) +
                            ("guests" to JsonPrimitive(3)),
                    )
                val booking = ok(desk.path, THIRD_MANAGER, bookingCommand).string("bookingId")
                val checkins = CheckinDbRepository(Database.connect(source))
                val entrance =
                    NewCheckin(
                        desk.club.toLong(),
                        8801L,
                        CheckinSubjectType.BOOKING,
                        booking,
                        null,
                        CheckinMethod.NAME,
                        CheckinResultStatus.DENIED,
                        "synthetic",
                        Instant.now(),
                    )
                checkins.insert(entrance)
                val withBooking =
                    JsonObject(
                        seat(desk, desk.table, "1500000", "CASH") + ("bookingId" to JsonPrimitive(booking)),
                    )
                assertEquals(HttpStatusCode.Conflict, requestAs(desk.path, THIRD_MANAGER, withBooking).status)
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM table_sessions"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM table_deposit_operations"))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM checkins"))

                // Dispose the synthetic denial and prove release/rebooking against the real reservation uniqueness constraint.
                source.execute("DELETE FROM checkins")
                val seated = ok(desk.path, THIRD_MANAGER, withBooking)
                val seatedId = seated.string("sessionId")
                val legacySessions = TableSessionRepository(Database.connect(source))
                val actor = source.scalar("SELECT id FROM users WHERE telegram_user_id=$SECOND_MANAGER").toLong()
                assertFalse(legacySessions.closeSession(seatedId.toLong(), desk.club.toLong(), actor, Instant.now()))
                ok(
                    desk.path,
                    THIRD_MANAGER,
                    obj(
                        "action" to "release",
                        "nightId" to desk.night,
                        "tableId" to desk.table,
                        "sessionId" to seatedId,
                        "expectedLastSessionId" to seatedId,
                    ),
                )
                val next = ok(desk.path, THIRD_MANAGER, bookingCommand).string("bookingId")
                assertTrue(next != booking)
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM table_deposit_operations"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM checkins"))
            }
        }

    @Test
    fun `booking agreement is independent of money and survives edits move and seating`() =
        database { source ->
            withApp(source) {
                val desk = setup(source, openShift = false)
                val original = source.scalar("SELECT booking_table_id FROM hall_tables WHERE id=${desk.table}")
                source.execute("UPDATE tables SET min_deposit=30000 WHERE id=$original")
                val create =
                    JsonObject(
                        obj("action" to "create_booking", "nightId" to desk.night, "tableId" to desk.table) +
                            obj("guestName" to "Guest") + ("guests" to JsonPrimitive(3)),
                    )
                val invalid =
                    listOf(JsonPrimitive("2999999"), JsonPrimitive("-1"), JsonPrimitive("5.00"), JsonPrimitive(5000000))
                for (amount in invalid) {
                    val command = JsonObject(create + ("bookingDepositMinor" to amount))
                    assertEquals(HttpStatusCode.BadRequest, requestAs(desk.path, THIRD_MANAGER, command).status)
                }
                val key = UUID.randomUUID().toString()
                val created = ok(desk.path, THIRD_MANAGER, create, key = key)
                assertEquals(created, ok(desk.path, THIRD_MANAGER, create, key = key))
                assertEquals("3000000", agreement(desk).string("bookingDepositMinor"))

                val edit = bookingAgreementCommand(desk, "update_booking")
                val increase =
                    JsonObject(edit + obj("bookingDepositMinor" to "5000000") + ("guests" to JsonPrimitive(3)))
                val editKey = UUID.randomUUID().toString()
                val edited = ok(desk.path, THIRD_MANAGER, increase, key = editKey)
                assertEquals(edited, ok(desk.path, THIRD_MANAGER, increase, key = editKey))
                assertEquals(HttpStatusCode.Conflict, requestAs(desk.path, THIRD_MANAGER, increase).status)
                val destination = higherMinimumTable(desk, source)
                val move =
                    JsonObject(bookingAgreementCommand(desk, "move_booking") + obj("destinationTableId" to destination))
                ok(desk.path, THIRD_MANAGER, move)
                val guestUpdate =
                    bookingAgreementCommand(desk, "update_booking", destination) + ("guests" to JsonPrimitive(4))
                val lower = JsonObject(guestUpdate + obj("bookingDepositMinor" to "5100000"))
                assertEquals(HttpStatusCode.BadRequest, requestAs(desk.path, THIRD_MANAGER, lower).status)
                ok(desk.path, THIRD_MANAGER, JsonObject(guestUpdate))
                assertEquals("4", agreement(desk).string("guests"))
                assertEquals("5000000", agreement(desk).string("bookingDepositMinor"))
                assertEquals("30000.00", agreement(desk).string("minDeposit"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM bookings WHERE total_deposit <> 0"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM shift_reports"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM table_deposit_operations"))
                val attemptedEdit =
                    JsonObject(
                        bookingAgreementCommand(desk, "update_booking", destination) +
                            obj("bookingDepositMinor" to "6000000") + ("guests" to JsonPrimitive(4)),
                    )
                ok(
                    desk.path,
                    THIRD_MANAGER,
                    JsonObject(
                        obj(
                            "action" to "seat",
                            "nightId" to desk.night,
                            "tableId" to destination,
                            "bookingId" to created.string("bookingId"),
                            "mode" to "BILL",
                        ) + ("expectedLastSessionId" to JsonNull),
                    ),
                )
                assertEquals("SEATED", agreement(desk).string("status"))
                assertEquals("5000000", agreement(desk).string("bookingDepositMinor"))
                assertEquals(HttpStatusCode.Conflict, requestAs(desk.path, THIRD_MANAGER, attemptedEdit).status)
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM table_deposit_operations"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM shift_reports"))
            }
        }

    private suspend fun ApplicationTestBuilder.agreement(desk: DeskFixture): JsonObject =
        ok(desk.path, THIRD_MANAGER)["bookings"]!!.jsonArray.single().jsonObject

    private suspend fun ApplicationTestBuilder.bookingAgreementCommand(
        desk: DeskFixture,
        action: String,
        tableId: String = desk.table,
    ): JsonObject {
        val booking = agreement(desk)
        return obj(
            "action" to action,
            "nightId" to desk.night,
            "tableId" to tableId,
            "bookingId" to booking.string("id"),
            "expectedUpdatedAt" to booking.string("updatedAt"),
        )
    }

    private suspend fun ApplicationTestBuilder.higherMinimumTable(
        desk: DeskFixture,
        source: DataSource,
    ): String {
        val club = "$C/clubs/${desk.club}"
        val hall = ok("$club/layout", SECOND_MANAGER)["halls"]!!.jsonArray.single().jsonObject
        val revision = hall.string("layoutRevision")
        val layout =
            ok(
                "$club/halls/${hall.string("id")}/tables",
                SECOND_MANAGER,
                JsonObject(
                    obj("label" to "Стол 2", "expectedVersion" to revision, "minDeposit" to "55000") +
                        ("tableNumber" to JsonPrimitive(2)) + ("capacity" to JsonPrimitive(6)),
                ),
            )
        val tables =
            layout["halls"]!!
                .jsonArray
                .single()
                .jsonObject["tables"]!!
                .jsonArray
        val tableId = tables.last().jsonObject.string("id")
        val bookingTable = source.scalar("SELECT booking_table_id FROM hall_tables WHERE id=$tableId")
        source.execute(
            "UPDATE tables SET active=TRUE WHERE id=$bookingTable",
            "INSERT INTO booking_cutover_fences(club_id,canonical_night_id,booking_table_id) " +
                "VALUES (${desk.club},${desk.night},$bookingTable)",
        )
        return tableId
    }

    protected suspend fun ApplicationTestBuilder.setup(
        source: DataSource,
        openShift: Boolean = true,
    ): DeskFixture {
        val (club, other) = business(source)
        val roster = "$C/clubs/$club/managers"
        claim(invite(roster, RECIPIENT, "CLUB_MANAGER"), SECOND_MANAGER)
        claim(invite(roster, SECOND_MANAGER, "FLOOR_MANAGER"), THIRD_MANAGER)
        val layout = ok("$C/clubs/$club/halls", SECOND_MANAGER, obj("name" to "Зал"))
        val hall = layout["halls"]!!.jsonArray.single().jsonObject
        val withTable =
            ok(
                "$C/clubs/$club/halls/${hall.string("id")}/tables",
                SECOND_MANAGER,
                JsonObject(
                    obj("label" to "Стол 1", "expectedVersion" to hall.string("layoutRevision")) +
                        ("tableNumber" to JsonPrimitive(1)) + ("capacity" to JsonPrimitive(6)),
                ),
            )
        val table =
            withTable["halls"]!!.jsonArray.single().jsonObject["tables"]!!.jsonArray.single().jsonObject.string(
                "id",
            )
        val slot = source.scalar("SELECT booking_table_id FROM hall_tables WHERE id=$table")
        val org = source.scalar("SELECT client_id FROM client_clubs WHERE club_id=$club")
        val tz = ok("$C/clubs/$club/profile", SECOND_MANAGER).string("timezone")
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val start = now.minusSeconds(120)
        val end = now.plusSeconds(3600)
        val date = start.atZone(ZoneId.of(tz)).toLocalDate()
        source.execute(
            "UPDATE tables SET active=TRUE WHERE id=$slot",
            "INSERT INTO events(id,club_id,start_at,end_at) VALUES (8801,$club,'$start','$end')",
            "INSERT INTO club_operational_nights(id,client_id,club_id,business_date,timezone," +
                "start_at,end_at,source,status,created_at,updated_at) " +
                "VALUES (8802,$org,$club,'$date','$tz','$start','$end','EXCEPTION','SCHEDULED','$now','$now')",
            "INSERT INTO canonical_night_event_links(club_id,canonical_night_id,event_id," +
                "legacy_start_at,legacy_end_at) " +
                "VALUES ($club,8802,8801,'$start','$end')",
            "INSERT INTO booking_cutover_fences(club_id,canonical_night_id,booking_table_id) VALUES ($club,8802,$slot)",
        )
        if (openShift) {
            source.execute(
                "INSERT INTO shift_reports(club_id,night_start_utc,status,created_at,updated_at) VALUES " +
                    "($club,'$start','DRAFT','$now','$now')",
            )
        }
        val desk = DeskFixture(club, other, "8802", table, "$C/clubs/$club/operations")
        assertEquals(
            "free",
            ok(
                desk.path,
                THIRD_MANAGER,
            )["halls"]!!.jsonArray.single().jsonObject["tables"]!!.jsonArray.single().jsonObject.string("status"),
        )
        return desk
    }

    protected fun seat(
        desk: DeskFixture,
        table: String,
        amount: String,
        method: String,
    ) = JsonObject(
        obj(
            "action" to "seat",
            "nightId" to desk.night,
            "tableId" to table,
            "mode" to "DEPOSIT",
            "amountMinor" to amount,
            "method" to method,
            "categoryVersion" to "1",
        ) +
            ("expectedLastSessionId" to JsonNull) + ("extras" to JsonObject(emptyMap())),
    )

    protected fun mixedSeat(
        desk: DeskFixture,
        total: String,
        cash: String,
    ): JsonObject = JsonObject(seat(desk, desk.table, total, "MIXED") + obj("cashMinor" to cash))

    private suspend fun ApplicationTestBuilder.saveConfig(
        path: String,
        version: String,
        primary: String,
        extraId: String? = null,
    ): JsonObject {
        val category =
            buildMap {
                put("name", JsonPrimitive("Шары"))
                put("active", JsonPrimitive(true))
                if (extraId != null) put("id", JsonPrimitive(extraId))
            }
        return ok(
            path,
            SECOND_MANAGER,
            JsonObject(
                obj("expectedVersion" to version, "primaryName" to primary) +
                    ("distribution" to JsonPrimitive(true)) + ("extras" to JsonArray(listOf(JsonObject(category)))),
            ),
            HttpMethod.Patch,
        )
    }

    protected fun DataSource.execute(vararg statements: String) {
        connection.use { connection ->
            connection.createStatement().use { statement -> statements.forEach { statement.executeUpdate(it) } }
        }
    }

    protected data class DeskFixture(
        val club: String,
        val other: String,
        val night: String,
        val table: String,
        val path: String,
    )
}

@Tag("it")
class ClubNightDeskPostgresIT : ClubNightDeskRoutesDataTest() {
    override fun databaseAt(
        target: String?,
        block: (DataSource) -> Unit,
    ) = withClientManagerPostgres(target, block)

    @Test
    fun `audit failure rolls back seating and money`() =
        database { source ->
            withApp(source) {
                val desk = setup(source)
                source.execute(
                    "ALTER TABLE audit_log ADD CONSTRAINT test_audit_failure CHECK (action <> " +
                        "'client.club.floor.seat') NOT VALID",
                )
                assertEquals(
                    HttpStatusCode.InternalServerError,
                    requestAs(desk.path, THIRD_MANAGER, mixedSeat(desk, "1500000", "500000")).status,
                )
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM table_sessions"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM table_deposit_operations"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM table_deposit_operation_allocations"))
            }
        }

    @Test
    fun `closing shift and seat serialize at the registered shift lock`() =
        database { source ->
            withApp(source) {
                val desk = setup(source)
                val shift = source.scalar("SELECT id FROM shift_reports").toLong()
                val user = source.scalar("SELECT id FROM users WHERE telegram_user_id=$SECOND_MANAGER").toLong()
                val reports = ShiftReportRepository(Database.connect(source))
                val response =
                    coroutineScope {
                        val close = async { reports.close(shift, user, Instant.now()) }
                        val seat =
                            async {
                                requestAs(
                                    desk.path,
                                    THIRD_MANAGER,
                                    seat(desk, desk.table, "1500000", "CARD"),
                                ).status
                            }
                        assertTrue(close.await())
                        seat.await()
                    }
                assertTrue(response in setOf(HttpStatusCode.OK, HttpStatusCode.Conflict))
                val result = if (response == HttpStatusCode.OK) "1" else "0"
                assertEquals(result, source.scalar("SELECT COUNT(*) FROM table_sessions"))
                assertEquals(result, source.scalar("SELECT COUNT(*) FROM table_deposit_operations"))
                assertEquals(
                    HttpStatusCode.Conflict,
                    requestAs(desk.path, THIRD_MANAGER, seat(desk, desk.table, "1500000", "CARD")).status,
                )
            }
        }
}
