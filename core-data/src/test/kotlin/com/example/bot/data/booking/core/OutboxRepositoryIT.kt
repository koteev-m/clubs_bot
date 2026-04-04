package com.example.bot.data.booking.core

import com.example.bot.config.BotLimits
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import testing.RequiresDocker
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

@RequiresDocker
@Tag("it")
class OutboxRepositoryIT : PostgresIntegrationTest() {
    @Test
    fun `enqueue pick and update outbox`() =
        runBlocking {
            val fixedNow = Instant.parse("2025-03-01T12:00:00Z")
            val repo = OutboxRepository(database, Clock.fixed(fixedNow, ZoneOffset.UTC))
            val payload = buildJsonObject { put("event", "BOOKING_CREATED") }
            val id1 = repo.enqueue("booking.created", payload)
            val id2 = repo.enqueue("booking.updated", payload)
            val batch = repo.pickBatchForSend(10)
            assertEquals(2, batch.size)
            assertEquals(id1, batch[0].id)
            val sent = repo.markSent(id1)
            assertTrue(sent is BookingCoreResult.Success)
            val sentRow =
                transaction(database) {
                    BookingOutboxTable
                        .selectAll()
                        .where { BookingOutboxTable.id eq id1 }
                        .first()
                }
            assertEquals("SENT", sentRow[BookingOutboxTable.status])
            assertEquals(1, sentRow[BookingOutboxTable.attempts])
            val expectedNext = fixedNow.plus(BotLimits.notifySendBaseBackoff)
            val markFailed =
                repo.markFailedWithRetry(id2, "temporary", expectedNext) as BookingCoreResult.Success
            assertEquals(expectedNext, markFailed.value.nextAttemptAt)
            assertEquals(1, markFailed.value.attempts)
            val stored =
                transaction(database) {
                    BookingOutboxTable
                        .selectAll()
                        .where { BookingOutboxTable.id eq id2 }
                        .first()
                }
            assertEquals("NEW", stored[BookingOutboxTable.status])
            assertEquals(1, stored[BookingOutboxTable.attempts])
            assertEquals(expectedNext, stored[BookingOutboxTable.nextAttemptAt].toInstant())
        }

    @Test
    fun `claimBatchWithLease uses skip locked across concurrent workers`() =
        runBlocking {
            val fixedNow = Instant.parse("2025-03-01T12:00:00Z")
            val repo = OutboxRepository(database, Clock.fixed(fixedNow, ZoneOffset.UTC))
            repeat(8) {
                repo.enqueue("booking.created", buildJsonObject { put("n", it) })
            }
            val claimed =
                coroutineScope {
                    listOf("worker-a", "worker-b")
                        .map { owner ->
                            async {
                                repo.claimBatchWithLease(limit = 4, leaseOwner = owner, leaseTtl = 30.seconds).map { it.id }.toSet()
                            }
                        }.awaitAll()
                }
            assertEquals(4, claimed[0].size)
            assertEquals(4, claimed[1].size)
            assertTrue(claimed[0].intersect(claimed[1]).isEmpty(), "claimed sets must not overlap")
        }

    @Test
    fun `pickBatchForTopics claims without overlap across concurrent workers`() =
        runBlocking {
            val fixedNow = Instant.parse("2025-03-01T12:00:00Z")
            val repo = OutboxRepository(database, Clock.fixed(fixedNow, ZoneOffset.UTC))
            repeat(10) {
                repo.enqueue("payment.refunded", buildJsonObject { put("n", it) })
            }

            val claimed =
                coroutineScope {
                    listOf("worker-a", "worker-b")
                        .map {
                            async {
                                repo.pickBatchForTopics(limit = 5, topics = setOf("payment.refunded")).map { msg -> msg.id }.toSet()
                            }
                        }.awaitAll()
                }

            assertEquals(5, claimed[0].size)
            assertEquals(5, claimed[1].size)
            assertTrue(claimed[0].intersect(claimed[1]).isEmpty(), "topic claimed sets must not overlap")
            assertTrue(repo.pickBatchForTopics(limit = 1, topics = setOf("payment.refunded")).isEmpty())
        }

}
