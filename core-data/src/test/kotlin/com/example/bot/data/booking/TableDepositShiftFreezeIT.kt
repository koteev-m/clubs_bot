package com.example.bot.data.booking

import com.example.bot.data.club.PostgresClubIntegrationTest
import com.example.bot.data.db.toOffsetDateTimeUtc
import com.example.bot.data.finance.ShiftReportsTable
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TableDepositShiftFreezeIT : PostgresClubIntegrationTest() {
    @Test
    fun `db trigger blocks deposit update after shift close`() = runBlocking {
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

        assertThrows(Exception::class.java) {
            transaction(database) {
                exec("UPDATE table_deposits SET amount_minor = 101 WHERE id = ${deposit.id}")
            }
        }
        assertThrows(Exception::class.java) {
            transaction(database) {
                exec("INSERT INTO table_deposit_allocations (deposit_id, category_code, amount_minor) VALUES (${deposit.id}, 'VIP', 1)")
            }
        }
    }
}
