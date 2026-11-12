package com.example.bot.data.booking

import com.example.bot.data.booking.core.PostgresIntegrationTest
import com.example.bot.data.finance.ShiftReportRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection

@Timeout(30)
internal class CanonicalTableOperationsPostgresIT :
    PostgresIntegrationTest(),
    CanonicalTableOperationsContract {
    private val observer by lazy { source("observer") }

    override fun fixture(block: suspend (TableOperationsFixture) -> Unit) {
        runBlocking { block(TableOperationsFixture(observer, database)) }
    }

    @Test
    fun `independent PostgreSQL seats serialize on the club row and post one deposit`() =
        fixture { f ->
            pausedDeposit(f) { control, seatPid ->
                val second = async { f.seat(operations = f.operations(Database.connect(source("second")))) }
                val secondPid = control.awaitBlocked("second", seatPid, "SELECT timezone FROM clubs")
                assertNotEquals(seatPid, secondPid)
                f.assertNoWrites()
                control.releaseGate()
                assertEquals(TableOperationResult.Rejected.OCCUPIED, second.await())
            }
            assertEquals(1L, f.count("table_sessions"))
            assertEquals(1L, f.count("table_deposits"))
            assertEquals(1L, f.count("table_deposit_operations"))
            assertEquals(2L, f.count("audit_log"))
        }

    @Test
    fun `seat holds shift lock until ledger and audit are committed before concurrent close`() =
        fixture { f ->
            pausedDeposit(f) { control, seatPid ->
                val shifts = ShiftReportRepository(Database.connect(source("close")))
                val close = async { shifts.close(710, ACTOR, NOW) }
                val closePid = control.awaitBlocked("close", seatPid, "SELECT shift_reports.")
                assertNotEquals(seatPid, closePid)
                f.assertNoWrites()
                control.releaseGate()
                assertTrue(close.await())
            }
            assertEquals(1L, f.count("table_deposit_operations"))
            assertEquals(1L, f.count("shift_reports", "status='CLOSED'"))
        }

    @Test
    fun `committed close wins against a seat waiting for the same shift row`() =
        fixture { f ->
            observer.connection.use { control ->
                control.autoCommit = false
                control.executeTableOpsSql(
                    "UPDATE shift_reports SET status='CLOSED',closed_at='$NOW',closed_by=$ACTOR WHERE id=710",
                )
                coroutineScope {
                    val seat = async { f.seat(operations = f.operations(Database.connect(source("seat")))) }
                    try {
                        val pid =
                            observer.connection.use {
                                it.awaitBlocked(
                                    "seat",
                                    control.pid(),
                                    "SELECT status FROM shift_reports",
                                )
                            }
                        assertNotEquals(control.pid(), pid)
                        f.assertNoWrites()
                    } finally {
                        control.commit()
                    }
                    assertEquals(TableOperationResult.Rejected.SHIFT_NOT_OPEN, seat.await())
                }
            }
            f.assertNoWrites()
        }

    @Test
    fun `club lock precedes event and table locks and stale event is rechecked after waiting`() =
        fixture { f ->
            observer.connection.use { control ->
                control.autoCommit = false
                control.executeTableOpsSql("SELECT id FROM clubs WHERE id=100 FOR UPDATE")
                coroutineScope {
                    val seat = async { f.seat(operations = f.operations(Database.connect(source("seat")))) }
                    try {
                        val pid =
                            observer.connection.use {
                                it.awaitBlocked(
                                    "seat",
                                    control.pid(),
                                    "SELECT timezone FROM clubs",
                                )
                            }
                        assertNotEquals(control.pid(), pid)
                        control.executeTableOpsSql("SELECT id FROM events WHERE id=510 FOR UPDATE NOWAIT")
                        control.executeTableOpsSql("SELECT id FROM tables WHERE id=410 FOR UPDATE NOWAIT")
                        control.executeTableOpsSql("UPDATE events SET end_at='2030-01-02T09:00:00Z' WHERE id=510")
                    } finally {
                        control.commit()
                    }
                    assertEquals(TableOperationResult.Rejected.NIGHT_UNAVAILABLE, seat.await())
                }
            }
            f.assertNoWrites()
        }

    private suspend fun pausedDeposit(
        fixture: TableOperationsFixture,
        compete: suspend kotlinx.coroutines.CoroutineScope.(Connection, Int) -> Unit,
    ) {
        fixture.write(
            "CREATE OR REPLACE FUNCTION pause_canonical_table_deposit() RETURNS trigger LANGUAGE plpgsql AS " +
                "'BEGIN PERFORM pg_advisory_xact_lock($GATE); RETURN NEW; END;'",
        )
        fixture.write(
            "CREATE TRIGGER pause_canonical_table_deposit BEFORE INSERT ON table_deposits " +
                "FOR EACH ROW EXECUTE FUNCTION pause_canonical_table_deposit()",
        )
        try {
            observer.connection.use { control ->
                control.executeTableOpsSql("SELECT pg_advisory_lock($GATE)")
                coroutineScope {
                    val seat = async { fixture.seat(operations = fixture.operations(Database.connect(source("seat")))) }
                    try {
                        val seatPid = control.awaitBlocked("seat", control.pid(), "INSERT INTO table_deposits")
                        assertNotEquals(control.pid(), seatPid)
                        compete(control, seatPid)
                    } finally {
                        control.releaseGate()
                    }
                    seat.await().value()
                }
            }
        } finally {
            fixture.write("DROP TRIGGER pause_canonical_table_deposit ON table_deposits")
        }
    }

    private fun Connection.releaseGate() = executeTableOpsSql("SELECT pg_advisory_unlock($GATE)")

    private fun Connection.pid(): Int =
        createStatement().use { query ->
            query.executeQuery("SELECT pg_backend_pid()").use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }

    private suspend fun Connection.awaitBlocked(
        actor: String,
        blocker: Int,
        prefix: String,
    ): Int =
        withTimeout(6_000) {
            var found: Int? = null
            while (found == null) {
                prepareStatement(
                    "SELECT pid,query FROM pg_stat_activity WHERE application_name=? AND ?=ANY(pg_blocking_pids(pid))",
                ).use { query ->
                    query.setString(1, "canonical-table-$actor")
                    query.setInt(2, blocker)
                    query.executeQuery().use { rows ->
                        if (rows.next()) {
                            val sql = rows.getString("query")
                            assertTrue(sql.startsWith(prefix, ignoreCase = true), "Expected $prefix, got $sql")
                            found = rows.getInt("pid")
                        }
                    }
                }
                if (found == null) delay(10)
            }
            checkNotNull(found)
        }

    private fun source(name: String) =
        PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
            applicationName = "canonical-table-$name"
            options = "-c lock_timeout=10s -c statement_timeout=12s"
            connectTimeout = 5
            socketTimeout = 15
        }

    private companion object {
        const val GATE = 28_046_572
    }
}
