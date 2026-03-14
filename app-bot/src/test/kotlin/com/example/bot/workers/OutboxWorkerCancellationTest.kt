package com.example.bot.workers

import com.example.bot.data.booking.core.OutboxRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class OutboxWorkerCancellationTest {
    @Test
    fun `runOnce rethrows cancellation from queue metrics and does not pick batch`() =
        runTest {
            val repository = mockk<OutboxRepository>()
            val sendPort = mockk<SendPort>()
            val queueMetrics = mockk<OutboxQueueMetrics>(relaxed = true)
            val cancellation = CancellationException("metrics refresh cancelled")

            coEvery { repository.queueStats() } throws cancellation

            val worker =
                OutboxWorker(
                    repository = repository,
                    sendPort = sendPort,
                    queueMetrics = queueMetrics,
                )

            val thrown = assertFailsWith<CancellationException> { worker.runOnce() }

            kotlin.test.assertSame(cancellation, thrown)
            coVerify(exactly = 1) { repository.queueStats() }
            coVerify(exactly = 0) { repository.pickBatchForSend(any()) }
            coVerify(exactly = 0) { sendPort.send(any(), any()) }
            coVerify(exactly = 0) { queueMetrics.record(any(), any()) }
        }
}
