package com.example.bot.workers

import com.example.bot.data.booking.core.BookingCoreResult
import com.example.bot.data.booking.core.OutboxMessage
import com.example.bot.data.booking.core.OutboxMessageStatus
import com.example.bot.data.booking.core.OutboxQueueStats
import com.example.bot.data.booking.core.OutboxRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class OutboxWorkerMetricsTest {
    @Test
    fun `retry rate counts only retryable outcomes`() =
        runTest {
            val repository = mockk<OutboxRepository>()
            val queueMetrics = mockk<OutboxQueueMetrics>(relaxed = true)
            val firstMessage = outboxMessage(id = 10)
            val secondMessage = outboxMessage(id = 20)
            val fixedClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

            coEvery { repository.queueStats() } returns OutboxQueueStats(depth = 2, oldestCreatedAt = fixedClock.instant())
            coEvery { repository.pickBatchForSend(any()) } returns listOf(firstMessage, secondMessage)
            coEvery { repository.markFailedWithRetry(id = firstMessage.id, reason = any(), nextAttemptAt = any()) } returns
                BookingCoreResult.Success(firstMessage)
            coEvery { repository.markFailedWithRetry(id = secondMessage.id, reason = any(), nextAttemptAt = any()) } returns
                BookingCoreResult.Success(secondMessage)

            val sendPort =
                mockk<SendPort> {
                    coEvery { send(any(), any()) } returnsMany
                        listOf(
                            SendOutcome.RetryableError(IllegalStateException("retry")),
                            SendOutcome.FatalError(IllegalStateException("fatal")),
                        )
                }

            val worker =
                OutboxWorker(
                    repository = repository,
                    sendPort = sendPort,
                    queueMetrics = queueMetrics,
                    parallelism = 1,
                    clock = fixedClock,
                )

            worker.runOnce()

            coVerify(exactly = 1) { queueMetrics.recordRetryRate(60) }
        }

    private fun outboxMessage(id: Long): OutboxMessage =
        OutboxMessage(
            id = id,
            topic = "notify.telegram",
            payload = buildJsonObject { },
            status = OutboxMessageStatus.NEW,
            attempts = 0,
            nextAttemptAt = Instant.parse("2026-01-01T00:00:00Z"),
            lastError = null,
        )
}
