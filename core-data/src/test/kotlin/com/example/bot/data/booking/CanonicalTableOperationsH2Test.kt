package com.example.bot.data.booking

import com.example.bot.data.TestDatabase
import com.example.bot.data.finance.ShiftReportRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource

internal class CanonicalTableOperationsH2Test : CanonicalTableOperationsContract {
    override fun fixture(block: suspend (TableOperationsFixture) -> Unit) {
        TestDatabase().use { db ->
            runBlocking { block(TableOperationsFixture(db.dataSource, db.database)) }
        }
    }
}

internal interface CanonicalTableOperationsContract {
    fun fixture(block: suspend (TableOperationsFixture) -> Unit)

    @Test
    fun `seat adjust and release use the linked booking table and original night`() =
        fixture { f ->
            val second = f.scope.copy(table = f.scope.table.copy(hallTableId = 320))
            val first = f.seat().value()
            val other = f.seat(scope = second).value()
            val deposit = checkNotNull(first.deposit)
            val otherDeposit = checkNotNull(other.deposit)
            assertEquals(410L, first.session.tableId)
            assertEquals(410L, deposit.tableId)
            assertEquals(420L, other.session.tableId)
            assertEquals(first.session.id, deposit.tableSessionId)
            assertEquals(f.scope.legacyNightStart, first.session.nightStartUtc)
            assertEquals(f.scope.legacyNightStart, deposit.nightStartUtc)
            assertEquals(1L, f.count("table_sessions", "table_id=410 AND club_id=100"))
            assertEquals(0L, f.count("table_sessions", "table_id=310"))
            assertEquals(TableOperationResult.Rejected.OCCUPIED, f.seat())
            assertEquals(
                listOf(deposit),
                f.ops
                    .occupancy(f.scope)
                    .value()
                    .deposits,
            )
            assertEquals(
                TableOperationResult.Rejected.DEPOSIT_UNAVAILABLE,
                f.ops.updateDeposit(f.scope, ACTOR, first.session.id, otherDeposit.id, AMOUNT, "Wrong table"),
            )
            assertEquals(
                TableOperationResult.Rejected.DEPOSIT_UNAVAILABLE,
                f.ops.updateDeposit(f.scope, ACTOR, other.session.id, deposit.id, AMOUNT, "Wrong session"),
            )
            val adjusted =
                f.ops
                    .updateDeposit(
                        f.scope,
                        ACTOR,
                        first.session.id,
                        deposit.id,
                        TableDepositAmount(90, listOf(AllocationInput("BAR", 90))),
                        "Verified correction",
                    ).value()
            assertEquals(90L, adjusted.amountMinor)
            assertEquals(100L, TableDepositRepository(f.db).findById(100, otherDeposit.id)?.amountMinor)
            assertEquals(2L, f.count("table_deposit_operations", "deposit_id=${deposit.id}"))
            assertEquals(1L, f.count("table_deposits", "id=${deposit.id} AND amount_minor=100"))
            assertEquals(TableOperationResult.Rejected.STALE_SESSION, f.ops.free(f.scope, ACTOR, other.session.id))
            f.ops.free(f.scope, ACTOR, first.session.id).value()
            assertNull(
                f.ops
                    .occupancy(f.scope)
                    .value()
                    .activeSession,
            )
            assertEquals(
                other.session,
                f.ops
                    .occupancy(second)
                    .value()
                    .activeSession,
            )
            assertEquals(TableOperationResult.Rejected.STALE_SESSION, f.seat())
            assertEquals(2L, f.count("table_deposits"))
            assertEquals(6L, f.count("audit_log", "fingerprint LIKE 'canonical-table:%'"))
            val reopened = f.seat(expected = first.session.id, deposit = null).value()
            assertNull(reopened.deposit)
            assertEquals(2L, f.count("table_deposits"))
        }

    @Test
    fun `missing foreign conflicting and inactive table identities fail closed`() =
        fixture { f ->
            val key = f.scope.table
            assertEquals(
                TableOperationResult.Rejected.TABLE_LINK_REQUIRED,
                f.seat(f.scope.copy(table = key.copy(hallTableId = 330))),
            )
            assertEquals(
                TableOperationResult.Rejected.TABLE_UNAVAILABLE,
                f.seat(f.scope.copy(table = key.copy(hallId = 999))),
            )
            assertEquals(
                TableOperationResult.Rejected.NIGHT_UNAVAILABLE,
                f.seat(f.scope.copy(table = key.copy(clubId = 200))),
            )
            expectFailure<SQLException> {
                f.write("UPDATE hall_tables SET booking_club_id=200,booking_table_id=310 WHERE id=310")
            }
            expectFailure<SQLException> {
                f.write("UPDATE hall_tables SET booking_club_id=100,booking_table_id=310 WHERE id=310")
            }
            f.write("UPDATE tables SET active=FALSE WHERE id=410")
            assertEquals(TableOperationResult.Rejected.TABLE_INACTIVE, f.seat())
            assertEquals(TableOperationResult.Rejected.TABLE_INACTIVE, f.ops.occupancy(f.scope))
            f.write("UPDATE tables SET active=TRUE WHERE id=410")
            f.write("UPDATE hall_tables SET is_active=FALSE WHERE id=310")
            assertEquals(TableOperationResult.Rejected.TABLE_INACTIVE, f.seat())
            f.write("UPDATE hall_tables SET is_active=TRUE WHERE id=310")
            f.write("UPDATE halls SET is_active=FALSE WHERE id=300")
            assertEquals(TableOperationResult.Rejected.TABLE_INACTIVE, f.seat())
            f.assertNoWrites()
        }

    @Test
    fun `closed or absent shift prevents writes and leaves the existing ledger intact`() =
        fixture { f ->
            f.write("DELETE FROM shift_reports")
            assertEquals(TableOperationResult.Rejected.SHIFT_NOT_OPEN, f.seat())
            f.assertNoWrites()
            val shifts = ShiftReportRepository(f.db)
            val report = shifts.getOrCreateDraft(100, f.scope.legacyNightStart)
            val seated = f.seat().value()
            val deposit = checkNotNull(seated.deposit)
            assertTrue(shifts.close(report.id, ACTOR, NOW))
            assertEquals(TableOperationResult.Rejected.SHIFT_NOT_OPEN, f.seat())
            assertEquals(TableOperationResult.Rejected.SHIFT_NOT_OPEN, f.ops.free(f.scope, ACTOR, seated.session.id))
            assertEquals(
                TableOperationResult.Rejected.SHIFT_NOT_OPEN,
                f.ops.updateDeposit(f.scope, ACTOR, seated.session.id, deposit.id, AMOUNT, "Closed"),
            )
            assertEquals(
                seated.session,
                f.ops
                    .occupancy(f.scope)
                    .value()
                    .activeSession,
            )
            assertEquals(1L, f.count("table_deposit_operations"))
            assertEquals(2L, f.count("audit_log", "fingerprint LIKE 'canonical-table:%'"))
        }

    @Test
    fun `cancelled unlinked stale and shifted nights reject operations`() =
        fixture { f ->
            assertEquals(TableOperationResult.Rejected.NIGHT_UNAVAILABLE, f.seat(f.scope.copy(legacyNightStart = NOW)))
            assertEquals(TableOperationResult.Rejected.NIGHT_UNAVAILABLE, f.seat(f.scope.copy(canonicalNightId = 999)))
            f.write("UPDATE club_operational_nights SET status='CANCELLED' WHERE id=610")
            assertEquals(TableOperationResult.Rejected.NIGHT_UNAVAILABLE, f.seat())
            f.write("UPDATE club_operational_nights SET status='SCHEDULED' WHERE id=610")
            f.write("UPDATE events SET end_at='2030-01-02T09:00:00Z' WHERE id=510")
            assertEquals(TableOperationResult.Rejected.NIGHT_UNAVAILABLE, f.seat())
            f.write("UPDATE events SET end_at='2030-01-02T08:00:00Z' WHERE id=510")
            f.write("UPDATE club_operational_nights SET start_at='2030-01-02T02:00:00Z' WHERE id=610")
            assertEquals(TableOperationResult.Rejected.NIGHT_UNAVAILABLE, f.seat())
            f.write("DELETE FROM canonical_night_event_links")
            assertEquals(TableOperationResult.Rejected.NIGHT_UNAVAILABLE, f.ops.occupancy(f.scope))
            f.assertNoWrites()
        }

    @Test
    fun `occupancy sees a live hold for booking identity but never confuses a foreign numeric collision`() =
        fixture { f ->
            f.hold(310, 520)
            assertFalse(
                f.ops
                    .occupancy(f.scope)
                    .value()
                    .hasBookingOrHold,
            )
            f.hold(410, 510)
            assertTrue(
                f.ops
                    .occupancy(f.scope)
                    .value()
                    .hasBookingOrHold,
            )
            assertEquals(TableOperationResult.Rejected.OCCUPIED, f.seat())
            f.assertNoWrites()
            f.write("UPDATE booking_holds SET expires_at='2030-01-02T00:30:00Z' WHERE table_id=410")
            assertFalse(
                f.ops
                    .occupancy(f.scope)
                    .value()
                    .hasBookingOrHold,
            )
            f.write(
                "INSERT INTO bookings(event_id,club_id,table_id,table_number,guests_count,min_deposit,total_deposit," +
                    "status,slot_start,slot_end,qr_secret,idempotency_key) " +
                    "VALUES (510,100,410,10,2,0,0,'BOOKED','$START','$END','synthetic-qr','synthetic-booking')",
            )
            assertTrue(
                f.ops
                    .occupancy(f.scope)
                    .value()
                    .hasBookingOrHold,
            )
            assertEquals(TableOperationResult.Rejected.OCCUPIED, f.seat())
            f.assertNoWrites()
        }

    @Test
    fun `invalid allocation and failed audit leave no partial session deposit or ledger`() =
        fixture { f ->
            expectFailure<IllegalArgumentException> {
                f.seat(deposit = TableDepositAmount(100, listOf(AllocationInput("BAR", 99))))
            }
            val overflow =
                listOf(
                    AllocationInput("BAR", Long.MAX_VALUE),
                    AllocationInput("FOOD", Long.MAX_VALUE),
                    AllocationInput("OTHER", 3),
                )
            expectFailure<IllegalArgumentException> { f.seat(deposit = TableDepositAmount(1, overflow)) }
            f.assertNoWrites()
            f.write(
                "ALTER TABLE audit_log ADD CONSTRAINT test_reject_deposit_audit CHECK (entity_type<>'TABLE_DEPOSIT')",
            )
            expectFailure<SQLException> { f.seat() }
            f.assertNoWrites()
            f.write("ALTER TABLE audit_log DROP CONSTRAINT test_reject_deposit_audit")
            f.seat().value()
            assertEquals(1L, f.count("table_deposit_operations"))
        }
}

internal class TableOperationsFixture(
    source: DataSource,
    val db: Database,
) {
    val scope = TableOperationsScope(100, PhysicalTableKey(100, 300, 310), 610, 510, START)
    val ops = operations(db)
    private val dataSource = source

    init {
        write("INSERT INTO users(id,telegram_user_id) VALUES ($ACTOR,8800)")
        write("INSERT INTO clients(id,display_name,is_active) VALUES (100,'Operations tenant',TRUE),(200,'Other',TRUE)")
        write("INSERT INTO clubs(id,name,timezone,is_active) VALUES (100,'Club','UTC',FALSE),(200,'Other','UTC',TRUE)")
        write("INSERT INTO client_clubs(client_id,club_id) VALUES (100,100),(200,200)")
        write(
            "INSERT INTO halls(id,club_id,name,geometry_json,geometry_fingerprint) " +
                "VALUES (300,100,'Hall','{}','fixture')",
        )
        write("INSERT INTO hall_zones(hall_id,zone_id,name) VALUES (300,'main','Hall')")
        write(
            "INSERT INTO tables(id,club_id,table_number,capacity,min_deposit,active) VALUES " +
                "(410,100,10,4,0,TRUE),(420,100,20,4,0,TRUE),(310,200,30,4,0,TRUE)",
        )
        write(
            "INSERT INTO hall_tables(id,hall_id,table_number,label,capacity,zone_id," +
                "booking_table_id,booking_club_id) " +
                "VALUES (310,300,10,'A',4,'main',410,100),(320,300,20,'B',4,'main',420,100)," +
                "(330,300,30,'History',4,'main',NULL,NULL)",
        )
        write(
            "INSERT INTO events(id,club_id,start_at,end_at) VALUES (510,100,'$START','$END'),(520,200,'$START','$END')",
        )
        write(
            "INSERT INTO club_operational_nights(id,client_id,club_id,business_date,timezone," +
                "start_at,end_at,source,status,created_at,updated_at) " +
                "VALUES (610,100,100,DATE '2030-01-01','UTC','$START','$END'," +
                "'EXCEPTION','SCHEDULED','$START','$START')",
        )
        write(
            "INSERT INTO canonical_night_event_links" +
                "(canonical_night_id,event_id,club_id,legacy_start_at,legacy_end_at) " +
                "VALUES (610,510,100,'$START','$END')",
        )
        write(
            "INSERT INTO shift_reports(id,club_id,night_start_utc,status,created_at,updated_at) " +
                "VALUES (710,100,'$START','DRAFT','$START','$START')",
        )
    }

    fun operations(database: Database) = CanonicalTableOperations(database, Clock.fixed(NOW, ZoneOffset.UTC))

    suspend fun seat(
        scope: TableOperationsScope = this.scope,
        expected: Long? = null,
        deposit: TableDepositAmount? = AMOUNT,
        operations: CanonicalTableOperations = ops,
    ) = operations.seat(scope, ACTOR, expected, deposit)

    fun hold(
        table: Long,
        event: Long,
    ) = write(
        "INSERT INTO booking_holds(event_id,table_id,guests_count,min_deposit,slot_start,slot_end," +
            "expires_at,idempotency_key) " +
            "VALUES ($event,$table,2,0,'$START','$END','$END','table-fixture-$table')",
    )

    fun write(sql: String) = dataSource.connection.use { it.executeTableOpsSql(sql) }

    fun count(
        table: String,
        predicate: String = "1=1",
    ): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table WHERE $predicate").use {
                    check(it.next())
                    it.getLong(1)
                }
            }
        }

    fun assertNoWrites() {
        for (table in listOf("table_sessions", "table_deposits", "table_deposit_operations", "audit_log")) {
            assertEquals(0L, count(table), table)
        }
    }
}

internal fun Connection.executeTableOpsSql(sql: String) {
    createStatement().use { it.execute(sql) }
}

internal fun <T> TableOperationResult<T>.value(): T = (this as TableOperationResult.Success<T>).value

internal const val ACTOR = 880L
internal val START: Instant = Instant.parse("2030-01-02T00:00:00Z")
internal val END: Instant = Instant.parse("2030-01-02T08:00:00Z")
internal val NOW: Instant = Instant.parse("2030-01-02T01:00:00Z")
internal val AMOUNT = TableDepositAmount(100, listOf(AllocationInput("BAR", 100)))

private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
    assertInstanceOf(T::class.java, runCatching(block).exceptionOrNull())
}
