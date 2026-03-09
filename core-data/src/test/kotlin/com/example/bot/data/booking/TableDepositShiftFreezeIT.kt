package com.example.bot.data.booking

import com.example.bot.data.club.PostgresClubIntegrationTest
import com.example.bot.data.db.toOffsetDateTimeUtc
import com.example.bot.data.finance.ShiftReportsTable
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TableDepositShiftFreezeIT : PostgresClubIntegrationTest() {
    @Test
    fun `db trigger blocks ledger appends after shift close`() = runBlocking {
        val clubId = insertClub("Freeze Club")
        val tableId = insertTable(clubId, tableNumber = 1, capacity = 4, minDeposit = BigDecimal("100.00"))
        val actorId = insertUser(username = "actor", displayName = "Actor")
        val nightStart = Instant.parse("2024-05-01T20:00:00Z")
        val now = Instant.parse("2024-05-01T20:10:00Z")
        val sessionRepo = TableSessionRepository(database)
        val depositRepo = TableDepositRepository(database)
        val session = sessionRepo.openSession(clubId, nightStart, tableId, actorId, now, note = null)
        val deposit =
            depositRepo.createDeposit(
                clubId = clubId,
                nightStartUtc = nightStart,
                tableId = tableId,
                sessionId = session.id,
                guestUserId = null,
                bookingId = null,
                paymentId = null,
                amountMinor = 100,
                allocations = listOf(AllocationInput(categoryCode = "BAR", amountMinor = 100)),
                actorId = actorId,
                now = now,
            )

        transaction(database) {
            ShiftReportsTable.insert {
                it[ShiftReportsTable.clubId] = clubId
                it[ShiftReportsTable.nightStartUtc] = nightStart.toOffsetDateTimeUtc()
                it[ShiftReportsTable.status] = "CLOSED"
                it[ShiftReportsTable.peopleWomen] = 0
                it[ShiftReportsTable.peopleMen] = 0
                it[ShiftReportsTable.peopleRejected] = 0
                it[ShiftReportsTable.comment] = null
                it[ShiftReportsTable.closedAt] = now.toOffsetDateTimeUtc()
                it[ShiftReportsTable.closedBy] = actorId
                it[ShiftReportsTable.createdAt] = now.toOffsetDateTimeUtc()
                it[ShiftReportsTable.updatedAt] = now.toOffsetDateTimeUtc()
            }
        }

        assertFreezeViolation {
            transaction(database) {
                exec(
                    """
                    INSERT INTO table_deposit_operations
                        (deposit_id, session_id, club_id, night_start_utc, type, amount_minor, created_at, actor_id, reason, payment_id)
                    VALUES
                        (${deposit.id}, ${session.id}, $clubId, TIMESTAMP WITH TIME ZONE '$nightStart', 'TOPUP', 1, TIMESTAMP WITH TIME ZONE '$now', $actorId, 'late topup', NULL)
                    """.trimIndent(),
                )
            }
        }
        assertFreezeViolation {
            transaction(database) {
                exec(
                    """
                    INSERT INTO table_deposit_operation_allocations (operation_id, category_code, amount_minor)
                    SELECT id, 'VIP', 1
                    FROM table_deposit_operations
                    WHERE deposit_id = ${deposit.id}
                    ORDER BY id DESC
                    LIMIT 1
                    """.trimIndent(),
                )
            }
        }
    }

    private fun assertFreezeViolation(block: () -> Unit) {
        val ex = kotlin.runCatching(block).exceptionOrNull()
        assertTrue(ex != null, "Expected freeze mutation to fail")
        val hasFreezeSignal =
            generateSequence(ex) { it.cause }
                .mapNotNull { it.message }
                .any { it.contains("shift_report_closed", ignoreCase = true) }
        assertTrue(hasFreezeSignal, "Expected shift_report_closed freeze signal, got: ${ex?.message}")
    }
}
