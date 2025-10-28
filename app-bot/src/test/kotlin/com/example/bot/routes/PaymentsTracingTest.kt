package com.example.bot.routes

import com.example.bot.data.booking.core.BookingCancellationResult
import com.example.bot.data.booking.core.PaymentsBookingRepository
import com.example.bot.di.DefaultPaymentsService
import com.example.bot.observability.TracingProvider
import com.example.bot.payments.PaymentsRepository
import com.example.bot.payments.finalize.PaymentsFinalizeService
import com.example.bot.telemetry.PaymentsObservability
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import kotlinx.coroutines.runBlocking
import java.util.UUID

class PaymentsTracingTest : StringSpec({
    beforeTest { PaymentsObservability.overrideEnabled(true) }
    afterTest { PaymentsObservability.resetOverrides() }

    "finalize emits tracing span" {
        val exporter = InMemorySpanExporter.create()
        val tracing = TracingProvider.create(exporter)
        try {
            val service =
                DefaultPaymentsService(
                    finalizeService = SuccessFinalizeService(),
                    paymentsRepository = NoopPaymentsRepository(),
                    bookingRepository = NoopPaymentsBookingRepository(),
                    metricsProvider = null,
                    tracer = tracing.tracer,
                )

            runBlocking {
                service.finalize(
                    clubId = 7L,
                    bookingId = UUID.randomUUID(),
                    paymentToken = null,
                    idemKey = "trace-test",
                    actorUserId = 13L,
                )
            }

            exporter.finishedSpanItems.any { it.name == "payments.finalize" }.shouldBeTrue()
        } finally {
            tracing.sdk.close()
            exporter.reset()
        }
    }
})

private class SuccessFinalizeService : PaymentsFinalizeService {
    override suspend fun finalize(
        clubId: Long,
        bookingId: UUID,
        paymentToken: String?,
        idemKey: String,
        actorUserId: Long,
    ): PaymentsFinalizeService.FinalizeResult {
        return PaymentsFinalizeService.FinalizeResult(paymentStatus = "CAPTURED")
    }
}

private class NoopPaymentsRepository : PaymentsRepository {
    override suspend fun createInitiated(
        bookingId: UUID?,
        provider: String,
        currency: String,
        amountMinor: Long,
        payload: String,
        idempotencyKey: String,
    ): PaymentsRepository.PaymentRecord {
        throw UnsupportedOperationException("not used in test")
    }

    override suspend fun markPending(id: UUID) {
        throw UnsupportedOperationException("not used in test")
    }

    override suspend fun markCaptured(id: UUID, externalId: String?) {
        throw UnsupportedOperationException("not used in test")
    }

    override suspend fun markDeclined(id: UUID, reason: String) {
        throw UnsupportedOperationException("not used in test")
    }

    override suspend fun markRefunded(id: UUID, externalId: String?) {
        throw UnsupportedOperationException("not used in test")
    }

    override suspend fun findByPayload(payload: String): PaymentsRepository.PaymentRecord? {
        throw UnsupportedOperationException("not used in test")
    }

    override suspend fun findByIdempotencyKey(idempotencyKey: String): PaymentsRepository.PaymentRecord? {
        throw UnsupportedOperationException("not used in test")
    }

    override suspend fun recordAction(
        bookingId: UUID,
        key: String,
        action: PaymentsRepository.Action,
        result: PaymentsRepository.Result,
    ): PaymentsRepository.SavedAction {
        throw UnsupportedOperationException("not used in test")
    }

    override suspend fun findActionByIdempotencyKey(key: String): PaymentsRepository.SavedAction? {
        throw UnsupportedOperationException("not used in test")
    }

    override suspend fun updateStatus(id: UUID, status: String, externalId: String?) {
        throw UnsupportedOperationException("not used in test")
    }
}

private class NoopPaymentsBookingRepository : PaymentsBookingRepository {
    override suspend fun cancel(bookingId: UUID, clubId: Long): BookingCancellationResult {
        return BookingCancellationResult.NotFound
    }
}
