package com.example.bot.workers

import com.example.bot.data.booking.core.BookingCoreResult
import com.example.bot.data.booking.core.OutboxMessage
import com.example.bot.data.booking.core.OutboxMessageStatus
import com.example.bot.data.booking.core.OutboxRepository
import com.example.bot.observability.MetricsProvider
import com.example.bot.di.refundWorkerModule
import com.example.bot.payments.provider.ProviderRefundClient
import com.example.bot.payments.provider.ProviderRefundCommand
import com.example.bot.payments.provider.ProviderRefundResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefundOutboxWorkerTest {
    private val now = Instant.parse("2024-01-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val config = RefundWorkerConfig(enabled = true, interval = Duration.ofMillis(10), batchSize = 5)

    @AfterEach
    fun tearDown() {
        runCatching { stopKoin() }
    }

    @Test
    fun `success flow marks message as sent`() = runTest {
        val outbox = mockk<OutboxRepository>()
        val metrics = MetricsProvider(MetricsProvider.simpleRegistry())
        val message = sampleMessage()
        coEvery { outbox.countPending(any()) } returns 1
        coEvery { outbox.pickBatchForTopics(any(), any()) } returns listOf(message)
        coEvery { outbox.markSent(message.id) } returns BookingCoreResult.Success(Unit)
        val worker = worker(outbox, metrics) { ProviderRefundResult.Success }

        val processed = worker.runOnce()

        assertTrue(processed)
        coVerify(exactly = 1) { outbox.markSent(message.id) }
        val counter = metrics.registry.find("provider.refund.ok").counter()
        assertEquals(1.0, counter?.count())
    }

    @Test
    fun `429 response schedules retry using header`() = runTest {
        val outbox = mockk<OutboxRepository>()
        val metrics = MetricsProvider(MetricsProvider.simpleRegistry())
        val message = sampleMessage()
        val retry = Duration.ofSeconds(7)
        val nextAttempt = slot<Instant>()
        coEvery { outbox.countPending(any()) } returns 1
        coEvery { outbox.pickBatchForTopics(any(), any()) } returns listOf(message)
        coEvery { outbox.markFailedWithRetry(message.id, any(), capture(nextAttempt)) } returns BookingCoreResult.Success(message)
        val worker = worker(outbox, metrics) {
            ProviderRefundResult.Retry(status = 429, retryAfter = retry)
        }

        worker.runOnce()

        assertEquals(now.plus(retry), nextAttempt.captured)
        coVerify { outbox.markFailedWithRetry(message.id, "HTTP 429", any()) }
    }

    @Test
    fun `5xx without retry-after uses backoff`() = runTest {
        val outbox = mockk<OutboxRepository>()
        val metrics = MetricsProvider(MetricsProvider.simpleRegistry())
        val message = sampleMessage()
        val nextAttempt = slot<Instant>()
        coEvery { outbox.countPending(any()) } returns 1
        coEvery { outbox.pickBatchForTopics(any(), any()) } returns listOf(message)
        coEvery { outbox.markFailedWithRetry(message.id, any(), capture(nextAttempt)) } returns BookingCoreResult.Success(message)
        val worker = worker(outbox, metrics) {
            ProviderRefundResult.Retry(status = 503, retryAfter = null)
        }

        worker.runOnce()

        assertEquals(now.plusSeconds(1), nextAttempt.captured)
        coVerify { outbox.markFailedWithRetry(message.id, "HTTP 503", any()) }
    }

    @Test
    fun `4xx response marks permanent failure`() = runTest {
        val outbox = mockk<OutboxRepository>()
        val metrics = MetricsProvider(MetricsProvider.simpleRegistry())
        val message = sampleMessage()
        coEvery { outbox.countPending(any()) } returns 1
        coEvery { outbox.pickBatchForTopics(any(), any()) } returns listOf(message)
        coEvery { outbox.markFailedPermanently(message.id, any()) } returns BookingCoreResult.Success(Unit)
        val worker = worker(outbox, metrics) {
            ProviderRefundResult.Failure(status = 400, body = "bad request")
        }

        worker.runOnce()

        coVerify { outbox.markFailedPermanently(message.id, "HTTP 400") }
        coVerify(exactly = 0) { outbox.markFailedWithRetry(any(), any(), any()) }
    }

    @Test
    fun `idempotency key uses outbox id`() = runTest {
        val outbox = mockk<OutboxRepository>()
        val metrics = MetricsProvider(MetricsProvider.simpleRegistry())
        val message = sampleMessage(id = 42)
        val commands = mutableListOf<ProviderRefundCommand>()
        coEvery { outbox.countPending(any()) } returns 1
        coEvery { outbox.pickBatchForTopics(any(), any()) } returns listOf(message)
        coEvery { outbox.markSent(message.id) } returns BookingCoreResult.Success(Unit)
        val worker = worker(outbox, metrics) {
            commands += it
            ProviderRefundResult.Success
        }

        worker.runOnce()

        assertEquals(listOf("42"), commands.map { it.idempotencyKey })
    }

    @Test
    fun `koin wiring delivers worker`() = runTest {
        val outbox = mockk<OutboxRepository>()
        val metrics = MetricsProvider(MetricsProvider.simpleRegistry())
        val message = sampleMessage()
        coEvery { outbox.countPending(any()) } returns 1
        coEvery { outbox.pickBatchForTopics(any(), any()) } returns listOf(message)
        coEvery { outbox.markSent(message.id) } returns BookingCoreResult.Success(Unit)
        val commands = mutableListOf<ProviderRefundCommand>()

        val koinApp =
            startKoin {
                // ключ: разрешаем переопределения
                allowOverride(true)
                modules(
                    refundWorkerModule,
                    module {
                        single { outbox }
                        single { metrics }
                        single { clock }
                        single { config }
                        single<ProviderRefundClient> {
                            ProviderRefundClient { command ->
                                commands += command
                                ProviderRefundResult.Success
                            }
                        }
                    },
                )
            }

        val worker = koinApp.koin.get<RefundOutboxWorker>()
        val processed = worker.runOnce()

        assertTrue(processed)
        assertEquals(1, commands.size)
    }

    private fun worker(
        outbox: OutboxRepository,
        metrics: MetricsProvider,
        sender: suspend (ProviderRefundCommand) -> ProviderRefundResult,
    ): RefundOutboxWorker {
        val client = object : ProviderRefundClient {
            override suspend fun send(command: ProviderRefundCommand): ProviderRefundResult = sender(command)
        }
        return RefundOutboxWorker(outbox, client, metrics, tracer = null, clock = clock, config = config)
    }

    private fun sampleMessage(
        id: Long = 1,
        payload: JsonObject = buildJsonObject { put("bookingId", JsonPrimitive("booking-1")) },
    ): OutboxMessage {
        return OutboxMessage(
            id = id,
            topic = "payment.refunded",
            payload = payload,
            status = OutboxMessageStatus.NEW,
            attempts = 0,
            nextAttemptAt = now,
            lastError = null,
        )
    }
}
