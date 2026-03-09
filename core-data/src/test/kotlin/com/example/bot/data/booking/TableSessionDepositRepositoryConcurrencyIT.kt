package com.example.bot.data.booking

import com.example.bot.data.club.PostgresClubIntegrationTest
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TableSessionDepositRepositoryConcurrencyIT : PostgresClubIntegrationTest() {
    @Test
    fun `concurrent updateDeposit to same target keeps single delta under postgres row lock`() =
        runBlocking {
            val clubId = insertClub("Concurrency Club")
            val tableId = insertTable(clubId, tableNumber = 10, capacity = 4, minDeposit = BigDecimal("100.00"))
            val actorId = insertUser(username = "actor-1", displayName = "Actor 1")
            val actor2Id = insertUser(username = "actor-2", displayName = "Actor 2")
            val nightStart = Instant.parse("2024-06-01T20:00:00Z")
            val createdAt = Instant.parse("2024-06-01T20:05:00Z")
            val updateAt = Instant.parse("2024-06-01T21:00:00Z")

            val sessionRepo = TableSessionRepository(database)
            val depositRepo = TableDepositRepository(database)
            val session = sessionRepo.openSession(clubId, nightStart, tableId, actorId, createdAt, note = null)
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
                    now = createdAt,
                )

            val readyBarrier = CountDownLatch(2)
            val startGate = CompletableDeferred<Unit>()
            val updates =
                listOf(actorId, actor2Id).map { updateActorId ->
                    async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                        readyBarrier.countDown()
                        assertTrue(
                            readyBarrier.await(5, TimeUnit.SECONDS),
                            "Timed out waiting for concurrent workers",
                        )
                        startGate.await()
                        depositRepo.updateDeposit(
                            clubId = clubId,
                            depositId = deposit.id,
                            amountMinor = 150,
                            allocations = listOf(AllocationInput(categoryCode = "BAR", amountMinor = 150)),
                            reason = "concurrent update",
                            actorId = updateActorId,
                            now = updateAt,
                        )
                    }
                }
            updates.forEach { it.start() }
            assertTrue(readyBarrier.await(5, TimeUnit.SECONDS), "Workers did not reach start barrier")
            startGate.complete(Unit)
            updates.awaitAll()

            val loaded = depositRepo.findById(clubId, deposit.id)
            checkNotNull(loaded)
            assertEquals(150, loaded.amountMinor)
            assertEquals(mapOf("BAR" to 150L), loaded.allocations.associate { it.categoryCode to it.amountMinor })

            val operations =
                transaction(database) {
                    TableDepositOperationsTable
                        .selectAll()
                        .where { TableDepositOperationsTable.depositId eq deposit.id }
                        .orderBy(TableDepositOperationsTable.id to SortOrder.ASC)
                        .toList()
                }
            assertEquals(3, operations.size)
            assertEquals(TableDepositOperationType.INITIAL.name, operations[0][TableDepositOperationsTable.type])
            assertEquals(100, operations[0][TableDepositOperationsTable.amountMinor])
            assertEquals(TableDepositOperationType.ADJUSTMENT.name, operations[1][TableDepositOperationsTable.type])
            assertEquals(TableDepositOperationType.ADJUSTMENT.name, operations[2][TableDepositOperationsTable.type])

            val adjustmentAmounts = operations.drop(1).map { it[TableDepositOperationsTable.amountMinor] }.sorted()
            assertEquals(listOf(0L, 50L), adjustmentAmounts)

            val adjustmentAllocationsByOperation =
                transaction(database) {
                    TableDepositOperationAllocationsTable
                        .selectAll()
                        .where {
                            TableDepositOperationAllocationsTable.operationId inList
                                operations.drop(1).map { it[TableDepositOperationsTable.id] }
                        }.toList()
                        .groupBy { it[TableDepositOperationAllocationsTable.operationId] }
                        .mapValues { (_, rows) ->
                            rows.associate {
                                it[TableDepositOperationAllocationsTable.categoryCode] to
                                    it[TableDepositOperationAllocationsTable.amountMinor]
                            }
                        }
                }

            val nonZeroAdjustmentOperationId =
                operations
                    .drop(1)
                    .first { it[TableDepositOperationsTable.amountMinor] == 50L }[TableDepositOperationsTable.id]
            val zeroAdjustmentOperationId =
                operations
                    .drop(1)
                    .first { it[TableDepositOperationsTable.amountMinor] == 0L }[TableDepositOperationsTable.id]

            assertEquals(mapOf("BAR" to 50L), adjustmentAllocationsByOperation[nonZeroAdjustmentOperationId])
            assertTrue(adjustmentAllocationsByOperation[zeroAdjustmentOperationId].isNullOrEmpty())
        }
}
