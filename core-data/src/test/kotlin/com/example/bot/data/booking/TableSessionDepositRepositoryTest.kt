package com.example.bot.data.booking

import com.example.bot.data.TestDatabase
import com.example.bot.data.db.Clubs
import com.example.bot.data.db.toOffsetDateTimeUtc
import com.example.bot.data.security.UsersTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class TableSessionDepositRepositoryTest {
    private lateinit var testDb: TestDatabase

    @BeforeEach
    fun setUp() {
        testDb = TestDatabase()
    }

    @AfterEach
    fun tearDown() {
        testDb.close()
    }

    @Test
    fun `openSession idempotent`() =
        runBlocking {
            val clubId = insertClub()
            val tableId = insertTable(clubId)
            val actorId = insertUser()
            val repo = TableSessionRepository(testDb.database)
            val nightStart = Instant.parse("2024-03-01T20:00:00Z")
            val now = Instant.parse("2024-03-01T20:05:00Z")

            val first = repo.openSession(clubId, nightStart, tableId, actorId, now, note = "init")
            val second = repo.openSession(clubId, nightStart, tableId, actorId, now, note = "ignored")

            assertEquals(first.id, second.id)
            val total =
                newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
                    TableSessionsTable.selectAll().count()
                }
            assertEquals(1, total)
        }

    @Test
    fun `openSession returns existing on unique conflict`() =
        runBlocking {
            val clubId = insertClub()
            val tableId = insertTable(clubId)
            val actorId = insertUser()
            val repo = TableSessionRepository(testDb.database)
            val nightStart = Instant.parse("2024-03-01T20:00:00Z")
            val now = Instant.parse("2024-03-01T20:05:00Z")
            val existingId =
                newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
                    TableSessionsTable.insert {
                        it[TableSessionsTable.clubId] = clubId
                        it[TableSessionsTable.nightStartUtc] = nightStart.toOffsetDateTimeUtc()
                        it[TableSessionsTable.tableId] = tableId
                        it[TableSessionsTable.status] = TableSessionStatus.OPEN.name
                        it[TableSessionsTable.openMarker] = 1.toShort()
                        it[TableSessionsTable.openedAt] = now.toOffsetDateTimeUtc()
                        it[TableSessionsTable.openedBy] = actorId
                        it[TableSessionsTable.note] = "seed"
                    }[TableSessionsTable.id]
                }

            val loaded = repo.openSession(clubId, nightStart, tableId, actorId, now, note = "ignored")

            assertEquals(existingId, loaded.id)
        }

    @Test
    fun `closeSession idempotent`() =
        runBlocking {
            val clubId = insertClub()
            val tableId = insertTable(clubId)
            val actorId = insertUser()
            val repo = TableSessionRepository(testDb.database)
            val nightStart = Instant.parse("2024-03-01T20:00:00Z")
            val openedAt = Instant.parse("2024-03-01T20:05:00Z")
            val closedAt = Instant.parse("2024-03-01T22:00:00Z")
            val session = repo.openSession(clubId, nightStart, tableId, actorId, openedAt, note = null)

            val first = repo.closeSession(session.id, clubId, actorId, closedAt)
            val second = repo.closeSession(session.id, clubId, actorId, closedAt)

            assertTrue(first)
            assertFalse(second)
        }

    @Test
    fun `createDeposit validates allocations sum`() =
        runBlocking {
            val clubId = insertClub()
            val tableId = insertTable(clubId)
            val actorId = insertUser()
            val repo = TableDepositRepository(testDb.database)
            val sessionRepo = TableSessionRepository(testDb.database)
            val nightStart = Instant.parse("2024-03-01T20:00:00Z")
            val now = Instant.parse("2024-03-01T20:05:00Z")
            val session = sessionRepo.openSession(clubId, nightStart, tableId, actorId, now, note = null)

            try {
                repo.createDeposit(
                    clubId = clubId,
                    nightStartUtc = nightStart,
                    tableId = tableId,
                    sessionId = session.id,
                    guestUserId = null,
                    bookingId = null,
                    paymentId = null,
                    amountMinor = 100,
                    allocations = listOf(AllocationInput(categoryCode = "FOOD", amountMinor = 50)),
                    actorId = actorId,
                    now = now,
                )
                fail("Expected createDeposit to throw IllegalArgumentException")
            } catch (ex: IllegalArgumentException) {
                // expected
            }
        }

    @Test
    fun `updateDeposit requires reason`() =
        runBlocking {
            val clubId = insertClub()
            val tableId = insertTable(clubId)
            val actorId = insertUser()
            val repo = TableDepositRepository(testDb.database)
            val sessionRepo = TableSessionRepository(testDb.database)
            val nightStart = Instant.parse("2024-03-01T20:00:00Z")
            val now = Instant.parse("2024-03-01T20:05:00Z")
            val session = sessionRepo.openSession(clubId, nightStart, tableId, actorId, now, note = null)
            val deposit =
                repo.createDeposit(
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

            try {
                repo.updateDeposit(
                    clubId = clubId,
                    depositId = deposit.id,
                    amountMinor = 100,
                    allocations = listOf(AllocationInput(categoryCode = "BAR", amountMinor = 100)),
                    reason = "   ",
                    actorId = actorId,
                    now = now,
                )
                fail("Expected updateDeposit to throw IllegalArgumentException")
            } catch (ex: IllegalArgumentException) {
                // expected
            }
        }

    @Test
    fun `updateDeposit updates fields and allocations`() =
        runBlocking {
            val clubId = insertClub()
            val tableId = insertTable(clubId)
            val actorId = insertUser()
            val actor2Id = insertUser()
            val repo = TableDepositRepository(testDb.database)
            val sessionRepo = TableSessionRepository(testDb.database)
            val nightStart = Instant.parse("2024-03-01T20:00:00Z")
            val createdAt = Instant.parse("2024-03-01T20:05:00Z")
            val updatedAt = Instant.parse("2024-03-01T21:00:00Z")
            val session = sessionRepo.openSession(clubId, nightStart, tableId, actorId, createdAt, note = null)
            val deposit =
                repo.createDeposit(
                    clubId = clubId,
                    nightStartUtc = nightStart,
                    tableId = tableId,
                    sessionId = session.id,
                    guestUserId = null,
                    bookingId = null,
                    paymentId = null,
                    amountMinor = 100,
                    allocations = listOf(
                        AllocationInput(categoryCode = "BAR", amountMinor = 60),
                        AllocationInput(categoryCode = "VIP", amountMinor = 40),
                    ),
                    actorId = actorId,
                    now = createdAt,
                )

            val updated =
                repo.updateDeposit(
                    clubId = clubId,
                    depositId = deposit.id,
                    amountMinor = 80,
                    allocations = listOf(AllocationInput(categoryCode = "BAR", amountMinor = 80)),
                    reason = "Fix allocation",
                    actorId = actor2Id,
                    now = updatedAt,
                )

            assertEquals(updatedAt, updated.updatedAt)
            assertEquals(actor2Id, updated.updatedBy)
            assertEquals("Fix allocation", updated.updateReason)
            assertEquals(1, updated.allocations.size)
            assertEquals(80, updated.allocations.first().amountMinor)
        }

    private suspend fun insertClub(): Long =
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
            Clubs.insert {
                it[name] = "Test Club"
            }[Clubs.id].value.toLong()
        }

    private suspend fun insertUser(): Long =
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
            UsersTable.insert {
                it[telegramUserId] = System.nanoTime()
                it[username] = "tester"
                it[displayName] = "Tester"
            }[UsersTable.id]
        }

    private suspend fun insertTable(clubId: Long): Long =
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
            TablesTable.insert {
                it[TablesTable.clubId] = clubId
                it[TablesTable.zoneId] = null
                it[TablesTable.tableNumber] = 7
                it[TablesTable.capacity] = 4
                it[TablesTable.minDeposit] = BigDecimal("100.00")
                it[TablesTable.active] = true
            }[TablesTable.id]
        }
}
