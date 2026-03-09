package com.example.bot.data.booking

import com.example.bot.data.TestDatabase
import com.example.bot.data.db.Clubs
import com.example.bot.data.db.toOffsetDateTimeUtc
import com.example.bot.data.finance.ShiftReportsTable
import com.example.bot.data.security.UsersTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.update
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
    fun `updateDeposit appends adjustment operation and keeps history`() =
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
            val operationCount =
                newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
                    TableDepositOperationsTable
                        .selectAll()
                        .where { TableDepositOperationsTable.depositId eq deposit.id }
                        .count()
                }
            assertEquals(2, operationCount)
        }

    @Test
    fun `createDeposit normalizes category codes`() =
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
                    allocations = listOf(AllocationInput(categoryCode = " bar ", amountMinor = 100)),
                    actorId = actorId,
                    now = now,
                )

            assertEquals("BAR", deposit.allocations.single().categoryCode)
        }

    @Test
    fun `createDeposit rejects duplicate category codes after normalization`() =
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
                    allocations = listOf(
                        AllocationInput(categoryCode = "bar", amountMinor = 60),
                        AllocationInput(categoryCode = " BAR ", amountMinor = 40),
                    ),
                    actorId = actorId,
                    now = now,
                )
                fail("Expected createDeposit to throw IllegalArgumentException")
            } catch (ex: IllegalArgumentException) {
                // expected
            }
        }


    @Test
    fun `createDeposit forbidden when shift is closed`() =
        runBlocking {
            val clubId = insertClub()
            val tableId = insertTable(clubId)
            val actorId = insertUser()
            val repo = TableDepositRepository(testDb.database)
            val sessionRepo = TableSessionRepository(testDb.database)
            val nightStart = Instant.parse("2024-03-01T20:00:00Z")
            val now = Instant.parse("2024-03-01T20:05:00Z")
            val session = sessionRepo.openSession(clubId, nightStart, tableId, actorId, now, note = null)
            insertClosedShiftReport(clubId, nightStart, actorId, now)

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
                    allocations = listOf(AllocationInput(categoryCode = "BAR", amountMinor = 100)),
                    actorId = actorId,
                    now = now,
                )
                fail("Expected createDeposit to throw ShiftClosedForDepositMutationException")
            } catch (ex: ShiftClosedForDepositMutationException) {
                assertEquals(clubId, ex.clubId)
                assertEquals(nightStart, ex.nightStartUtc)
            }
        }

    @Test
    fun `updateDeposit forbidden when shift is closed`() =
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
            insertClosedShiftReport(clubId, nightStart, actorId, now)

            try {
                repo.updateDeposit(
                    clubId = clubId,
                    depositId = deposit.id,
                    amountMinor = 100,
                    allocations = listOf(AllocationInput(categoryCode = "BAR", amountMinor = 100)),
                    reason = "fix",
                    actorId = actorId,
                    now = now,
                )
                fail("Expected updateDeposit to throw ShiftClosedForDepositMutationException")
            } catch (ex: ShiftClosedForDepositMutationException) {
                assertEquals(clubId, ex.clubId)
                assertEquals(nightStart, ex.nightStartUtc)
            }
        }


    @Test
    fun `multiple updates preserve ledger history and balance`() =
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

            repo.updateDeposit(
                clubId = clubId,
                depositId = deposit.id,
                amountMinor = 150,
                allocations = listOf(AllocationInput(categoryCode = "BAR", amountMinor = 120), AllocationInput(categoryCode = "VIP", amountMinor = 30)),
                reason = "topup",
                actorId = actorId,
                now = now.plusSeconds(600),
            )
            val second =
                repo.updateDeposit(
                    clubId = clubId,
                    depositId = deposit.id,
                    amountMinor = 90,
                    allocations = listOf(AllocationInput(categoryCode = "BAR", amountMinor = 90)),
                    reason = "refund",
                    actorId = actorId,
                    now = now.plusSeconds(1200),
                )

            assertEquals(90, second.amountMinor)
            assertEquals(mapOf("BAR" to 90L), second.allocations.associate { it.categoryCode to it.amountMinor })
            val ledgerAmount =
                newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
                    val sumExpr = TableDepositOperationsTable.amountMinor.sum()
                    TableDepositOperationsTable
                        .slice(sumExpr)
                        .selectAll()
                        .where { TableDepositOperationsTable.depositId eq deposit.id }
                        .first()[sumExpr] ?: 0L
                }
            assertEquals(90, ledgerAmount)
            val operationCount =
                newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
                    TableDepositOperationsTable
                        .selectAll()
                        .where { TableDepositOperationsTable.depositId eq deposit.id }
                        .count()
                }
            assertEquals(3, operationCount)
        }

    @Test
    fun `sumDepositsForNight returns total for club night`() =
        runBlocking {
            val clubId = insertClub()
            val tableId = insertTable(clubId)
            val actorId = insertUser()
            val repo = TableDepositRepository(testDb.database)
            val sessionRepo = TableSessionRepository(testDb.database)
            val nightStart = Instant.parse("2024-03-01T20:00:00Z")
            val now = Instant.parse("2024-03-01T20:05:00Z")
            val session = sessionRepo.openSession(clubId, nightStart, tableId, actorId, now, note = null)

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
            repo.createDeposit(
                clubId = clubId,
                nightStartUtc = nightStart,
                tableId = tableId,
                sessionId = session.id,
                guestUserId = null,
                bookingId = null,
                paymentId = null,
                amountMinor = 50,
                allocations = listOf(AllocationInput(categoryCode = "VIP", amountMinor = 50)),
                actorId = actorId,
                now = now,
            )

            val otherClubId = insertClub()
            val otherTableId = insertTable(otherClubId)
            val otherSession = sessionRepo.openSession(otherClubId, nightStart, otherTableId, actorId, now, note = null)
            repo.createDeposit(
                clubId = otherClubId,
                nightStartUtc = nightStart,
                tableId = otherTableId,
                sessionId = otherSession.id,
                guestUserId = null,
                bookingId = null,
                paymentId = null,
                amountMinor = 200,
                allocations = listOf(AllocationInput(categoryCode = "BAR", amountMinor = 200)),
                actorId = actorId,
                now = now,
            )

            val total = repo.sumDepositsForNight(clubId, nightStart)

            assertEquals(150, total)
        }

    @Test
    fun `allocationSummaryForNight aggregates by category`() =
        runBlocking {
            val clubId = insertClub()
            val tableId = insertTable(clubId)
            val actorId = insertUser()
            val repo = TableDepositRepository(testDb.database)
            val sessionRepo = TableSessionRepository(testDb.database)
            val nightStart = Instant.parse("2024-03-01T20:00:00Z")
            val now = Instant.parse("2024-03-01T20:05:00Z")
            val session = sessionRepo.openSession(clubId, nightStart, tableId, actorId, now, note = null)

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
                    AllocationInput(categoryCode = "bar", amountMinor = 60),
                    AllocationInput(categoryCode = "vip", amountMinor = 40),
                ),
                actorId = actorId,
                now = now,
            )
            repo.createDeposit(
                clubId = clubId,
                nightStartUtc = nightStart,
                tableId = tableId,
                sessionId = session.id,
                guestUserId = null,
                bookingId = null,
                paymentId = null,
                amountMinor = 50,
                allocations = listOf(AllocationInput(categoryCode = " BAR ", amountMinor = 50)),
                actorId = actorId,
                now = now,
            )

            val summary = repo.allocationSummaryForNight(clubId, nightStart)

            assertEquals(mapOf("BAR" to 110L, "VIP" to 40L), summary)
        }

    @Test
    fun `concurrent updateDeposit keeps target balance stable smoke`() =
        runBlocking {
            val clubId = insertClub()
            val tableId = insertTable(clubId)
            val actorId = insertUser()
            val actor2Id = insertUser()
            val repo = TableDepositRepository(testDb.database)
            val sessionRepo = TableSessionRepository(testDb.database)
            val nightStart = Instant.parse("2024-03-01T20:00:00Z")
            val createdAt = Instant.parse("2024-03-01T20:05:00Z")
            val updateAt = Instant.parse("2024-03-01T21:00:00Z")
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
                    allocations = listOf(AllocationInput(categoryCode = "BAR", amountMinor = 100)),
                    actorId = actorId,
                    now = createdAt,
                )

            coroutineScope {
                listOf(actorId, actor2Id).map { updateActorId ->
                    async(Dispatchers.IO) {
                        repo.updateDeposit(
                            clubId = clubId,
                            depositId = deposit.id,
                            amountMinor = 150,
                            allocations = listOf(AllocationInput(categoryCode = "BAR", amountMinor = 150)),
                            reason = "concurrent update",
                            actorId = updateActorId,
                            now = updateAt,
                        )
                    }
                }.awaitAll()
            }

            val loaded = repo.findById(clubId, deposit.id) ?: fail("Deposit not found")
            assertEquals(150, loaded.amountMinor)
            assertEquals(mapOf("BAR" to 150L), loaded.allocations.associate { it.categoryCode to it.amountMinor })
            val operations =
                newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
                    TableDepositOperationsTable
                        .selectAll()
                        .where { TableDepositOperationsTable.depositId eq deposit.id }
                        .orderBy(TableDepositOperationsTable.id)
                        .toList()
                }
            assertEquals(3, operations.size)
            assertEquals(TableDepositOperationType.INITIAL.name, operations[0][TableDepositOperationsTable.type])
            assertEquals(50, operations[1][TableDepositOperationsTable.amountMinor])
            assertEquals(0, operations[2][TableDepositOperationsTable.amountMinor])
        }

    @Test
    fun `findById uses legacy updated metadata when only backfilled initial exists`() =
        runBlocking {
            val clubId = insertClub()
            val tableId = insertTable(clubId)
            val actorId = insertUser()
            val legacyUpdaterId = insertUser()
            val repo = TableDepositRepository(testDb.database)
            val sessionRepo = TableSessionRepository(testDb.database)
            val nightStart = Instant.parse("2024-03-01T20:00:00Z")
            val createdAt = Instant.parse("2024-03-01T20:05:00Z")
            val legacyUpdatedAt = Instant.parse("2024-03-01T22:00:00Z")
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
                    allocations = listOf(AllocationInput(categoryCode = "BAR", amountMinor = 100)),
                    actorId = actorId,
                    now = createdAt,
                )

            newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
                TableDepositsTable.update({ TableDepositsTable.id eq deposit.id }) {
                    it[updatedAt] = legacyUpdatedAt.toOffsetDateTimeUtc()
                    it[updatedBy] = legacyUpdaterId
                    it[updateReason] = "legacy correction"
                }
            }

            val loaded = repo.findById(clubId, deposit.id) ?: fail("Deposit not found")
            assertEquals(legacyUpdatedAt, loaded.updatedAt)
            assertEquals(legacyUpdaterId, loaded.updatedBy)
            assertEquals("legacy correction", loaded.updateReason)
        }


    @Test
    fun `listDepositsForSession returns allocations`() =
        runBlocking {
            val clubId = insertClub()
            val tableId = insertTable(clubId)
            val actorId = insertUser()
            val repo = TableDepositRepository(testDb.database)
            val sessionRepo = TableSessionRepository(testDb.database)
            val nightStart = Instant.parse("2024-03-01T20:00:00Z")
            val now = Instant.parse("2024-03-01T20:05:00Z")
            val session = sessionRepo.openSession(clubId, nightStart, tableId, actorId, now, note = null)

            val first =
                repo.createDeposit(
                    clubId = clubId,
                    nightStartUtc = nightStart,
                    tableId = tableId,
                    sessionId = session.id,
                    guestUserId = null,
                    bookingId = null,
                    paymentId = null,
                    amountMinor = 120,
                    allocations = listOf(
                        AllocationInput(categoryCode = "BAR", amountMinor = 60),
                        AllocationInput(categoryCode = "VIP", amountMinor = 60),
                    ),
                    actorId = actorId,
                    now = now,
                )
            val second =
                repo.createDeposit(
                    clubId = clubId,
                    nightStartUtc = nightStart,
                    tableId = tableId,
                    sessionId = session.id,
                    guestUserId = null,
                    bookingId = null,
                    paymentId = null,
                    amountMinor = 80,
                    allocations = listOf(AllocationInput(categoryCode = "BAR", amountMinor = 80)),
                    actorId = actorId,
                    now = now,
                )

            val deposits = repo.listDepositsForSession(clubId, session.id)

            assertEquals(2, deposits.size)
            val loadedById = deposits.associateBy { it.id }
            assertEquals(first.allocations, loadedById[first.id]?.allocations)
            assertEquals(second.allocations, loadedById[second.id]?.allocations)
        }


    private suspend fun insertClosedShiftReport(
        clubId: Long,
        nightStart: Instant,
        actorId: Long,
        now: Instant,
    ) {
        newSuspendedTransaction(context = Dispatchers.IO, db = testDb.database) {
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
