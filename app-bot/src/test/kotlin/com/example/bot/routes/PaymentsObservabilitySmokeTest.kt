package com.example.bot.routes

import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.core.BookingCancellationResult
import com.example.bot.data.booking.core.BookingRecord
import com.example.bot.data.booking.core.PaymentsBookingRepository
import com.example.bot.di.DefaultPaymentsService
import com.example.bot.di.PaymentsService
import com.example.bot.observability.MetricsProvider
import com.example.bot.payments.PaymentsRepository
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.telemetry.PaymentsMetrics
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.micrometer.prometheus.PrometheusMeterRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val TEST_TOKEN = "test-token"

class PaymentsObservabilitySmokeTest : StringSpec() {
    private val user = TelegramMiniUser(id = 999L, username = "observer")

    override suspend fun beforeEach(testCase: TestCase) {
        PaymentsMetrics.resetForTest()
        overrideMiniAppValidatorForTesting { _, _ -> user }
    }

    override suspend fun afterEach(
        testCase: TestCase,
        result: TestResult,
    ) {
        PaymentsMetrics.resetForTest()
        resetMiniAppValidator()
    }

    init {
        "exports metrics for cancel idempotency and refund errors" {
            val registry = MetricsProvider.prometheusRegistry()
            val metricsProvider = MetricsProvider(registry)
            val paymentsRepository = InMemoryPaymentsRepository()
            val bookingId = UUID.randomUUID()
            paymentsRepository.seedCaptured(bookingId, 1_500)
            val bookingRepository =
                TestPaymentsBookingRepository().apply {
                    seed(bookingId = bookingId, clubId = 1L, status = BookingStatus.BOOKED)
                }
            val service =
                DefaultPaymentsService(
                    finalizeService = FakeFinalizeService(),
                    paymentsRepository = paymentsRepository,
                    bookingRepository = bookingRepository,
                    metricsProvider = metricsProvider,
                    tracer = null,
                )

            testApplication {
                application {
                    configurePaymentsTestApp(
                        paymentsService = service,
                        metricsProvider = metricsProvider,
                        registry = registry,
                    )
                }

                val cancelPayload = Json.encodeToString(CancelRequest(reason = "test"))
                val cancelUrl = "/api/clubs/1/bookings/$bookingId/cancel"
                val refundUrl = "/api/clubs/1/bookings/$bookingId/refund"

                client
                    .post(cancelUrl) {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        header("Idempotency-Key", "idem-cancel")
                        header("X-Telegram-Init-Data", "stub")
                        setBody(cancelPayload)
                    }.status shouldBe HttpStatusCode.OK

                client
                    .post(cancelUrl) {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        header("Idempotency-Key", "idem-cancel")
                        header("X-Telegram-Init-Data", "stub")
                        setBody(cancelPayload)
                    }.status shouldBe HttpStatusCode.OK

                client
                    .post(refundUrl) {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        header("Idempotency-Key", "idem-refund")
                        header("X-Telegram-Init-Data", "stub")
                        setBody(Json.encodeToString(RefundRequest(amountMinor = 2_000)))
                    }.status shouldBe HttpStatusCode.UnprocessableEntity

                val metricsBody =
                    client
                        .get("/metrics") {
                            header("X-Telegram-Init-Data", "stub")
                        }.bodyAsText()

                metricsBody.shouldContain(
                    "payments_cancel_duration_seconds_count{path=\"cancel\",result=\"ok\",source=\"miniapp\"",
                )
                metricsBody.shouldContain("payments_idempotent_hit_total{path=\"cancel\"")
                val errorLineOne = "payments_errors_total{kind=\"unprocessable\",path=\"refund\""
                val errorLineTwo = "payments_errors_total{path=\"refund\",kind=\"unprocessable\""
                (metricsBody.contains(errorLineOne) || metricsBody.contains(errorLineTwo)) shouldBe true
            }
        }
    }
}

private fun Application.configurePaymentsTestApp(
    paymentsService: PaymentsService,
    metricsProvider: MetricsProvider,
    registry: PrometheusMeterRegistry,
) {
    install(ContentNegotiation) { json() }
    install(CallId) {
        generate { UUID.randomUUID().toString() }
    }
    install(Koin) {
        modules(
            module {
                single { paymentsService }
                single { metricsProvider }
            },
        )
    }
    routing {
        withMiniAppAuth { TEST_TOKEN }
        paymentsCancelRefundRoutes { TEST_TOKEN }
        metricsRoute(registry)
    }
}

private class InMemoryPaymentsRepository : PaymentsRepository {
    private val actions = ConcurrentHashMap<String, PaymentsRepository.SavedAction>()
    private val capturedByBooking = ConcurrentHashMap<UUID, Long>()
    private val refundedByBooking = ConcurrentHashMap<UUID, Long>()
    private val cancelBookings = ConcurrentHashMap<UUID, Pair<Long, Boolean>>()
    private val refundResults = ConcurrentHashMap<String, StoredRefund>()
    private val idSequence = AtomicLong(0)

    fun seedCaptured(
        bookingId: UUID,
        amountMinor: Long,
    ) {
        capturedByBooking[bookingId] = amountMinor
        cancelBookings.putIfAbsent(bookingId, 1L to false)
    }

    override suspend fun createInitiated(
        bookingId: UUID?,
        provider: String,
        currency: String,
        amountMinor: Long,
        payload: String,
        idempotencyKey: String,
    ): PaymentsRepository.PaymentRecord = throw UnsupportedOperationException("not used in test")

    override suspend fun markPending(id: UUID): Unit = throw UnsupportedOperationException("not used in test")

    override suspend fun markCaptured(
        id: UUID,
        externalId: String?,
    ): Unit = throw UnsupportedOperationException("not used in test")

    override suspend fun markDeclined(
        id: UUID,
        reason: String,
    ): Unit = throw UnsupportedOperationException("not used in test")

    override suspend fun markRefunded(
        id: UUID,
        externalId: String?,
    ): Unit = throw UnsupportedOperationException("not used in test")

    override suspend fun findByPayload(payload: String): PaymentsRepository.PaymentRecord? = null

    override suspend fun findByIdempotencyKey(idempotencyKey: String): PaymentsRepository.PaymentRecord? = null

    override suspend fun updateStatus(
        id: UUID,
        status: String,
        externalId: String?,
    ): Unit = throw UnsupportedOperationException("not used in test")

    override suspend fun recordAction(
        bookingId: UUID,
        key: String,
        action: PaymentsRepository.Action,
        result: PaymentsRepository.Result,
    ): PaymentsRepository.SavedAction {
        val saved =
            PaymentsRepository.SavedAction(
                id = idSequence.incrementAndGet(),
                bookingId = bookingId,
                idempotencyKey = key,
                action = action,
                result = result,
                createdAt = Instant.now(),
            )
        return actions.putIfAbsent(key, saved) ?: saved
    }

    override suspend fun findActionByIdempotencyKey(key: String): PaymentsRepository.SavedAction? = actions[key]

    override suspend fun executeCancelIdempotently(
        clubId: Long,
        bookingId: UUID,
        idempotencyKey: String,
        reason: String?,
    ): PaymentsRepository.CancelExecution =
        synchronized(this) {
            actions[idempotencyKey]?.let { existing ->
                if (existing.bookingId != bookingId || existing.action != PaymentsRepository.Action.CANCEL) {
                    return@synchronized PaymentsRepository.CancelExecution.IdempotencyBindingMismatch
                }
                return@synchronized when (existing.result.status) {
                    PaymentsRepository.Result.Status.OK,
                    PaymentsRepository.Result.Status.ALREADY,
                    ->
                        PaymentsRepository.CancelExecution.Success(
                            clubId = clubId,
                            bookingId = bookingId,
                            slotStart = Instant.EPOCH,
                            idempotent = true,
                            alreadyCancelled = existing.result.status == PaymentsRepository.Result.Status.ALREADY,
                        )
                    PaymentsRepository.Result.Status.CONFLICT ->
                        PaymentsRepository.CancelExecution.Conflict(
                            existing.result.reason ?: "cannot cancel",
                            idempotent = true,
                        )
                    PaymentsRepository.Result.Status.ERROR ->
                        PaymentsRepository.CancelExecution.StoredError(
                            existing.result.reason ?: "cannot cancel",
                            idempotent = true,
                        )
                }
            }
            val state =
                cancelBookings[bookingId]
                    ?: return@synchronized PaymentsRepository.CancelExecution.NotFound
            if (state.first != clubId) {
                return@synchronized PaymentsRepository.CancelExecution.NotFound
            }
            val alreadyCancelled = state.second
            cancelBookings[bookingId] = clubId to true
            val status =
                if (alreadyCancelled) {
                    PaymentsRepository.Result.Status.ALREADY
                } else {
                    PaymentsRepository.Result.Status.OK
                }
            actions[idempotencyKey] =
                PaymentsRepository.SavedAction(
                    id = idSequence.incrementAndGet(),
                    bookingId = bookingId,
                    idempotencyKey = idempotencyKey,
                    action = PaymentsRepository.Action.CANCEL,
                    result =
                        PaymentsRepository.Result(
                            status,
                            reason ?: "already_cancelled".takeIf { alreadyCancelled },
                        ),
                    createdAt = Instant.now(),
                )
            PaymentsRepository.CancelExecution.Success(
                clubId = clubId,
                bookingId = bookingId,
                slotStart = Instant.EPOCH,
                idempotent = false,
                alreadyCancelled = alreadyCancelled,
            )
        }

    override suspend fun executeRefundIdempotently(
        clubId: Long,
        bookingId: UUID,
        idempotencyKey: String,
        fingerprint: PaymentsRepository.RefundFingerprint,
    ): PaymentsRepository.RefundExecution =
        synchronized(this) {
            actions[idempotencyKey]?.let { existing ->
                if (existing.bookingId != bookingId || existing.action != PaymentsRepository.Action.REFUND) {
                    return@synchronized PaymentsRepository.RefundExecution.IdempotencyBindingMismatch
                }
                val stored =
                    refundResults[idempotencyKey]
                        ?: return@synchronized PaymentsRepository.RefundExecution.IdempotencyPayloadMismatch
                if (stored.fingerprint != fingerprint) {
                    return@synchronized PaymentsRepository.RefundExecution.IdempotencyPayloadMismatch
                }
                return@synchronized stored.execution.asIdempotent()
            }

            val captured = capturedByBooking[bookingId] ?: 0L
            val refunded = refundedByBooking[bookingId] ?: 0L
            val remainder = captured - refunded
            val requested = fingerprint.requestAmountMinor ?: remainder
            val execution =
                when {
                    fingerprint.mode == PaymentsRepository.RefundRequestMode.ALL_REMAINING && remainder <= 0 ->
                        PaymentsRepository.RefundExecution.Conflict("nothing to refund", idempotent = false)
                    remainder <= 0 && requested > 0 ->
                        PaymentsRepository.RefundExecution.Conflict("nothing to refund", idempotent = false)
                    requested > remainder ->
                        PaymentsRepository.RefundExecution.Unprocessable("exceeds remainder", idempotent = false)
                    else -> {
                        refundedByBooking[bookingId] = refunded + requested
                        PaymentsRepository.RefundExecution.Success(
                            amountMinor = requested,
                            remainingMinor = remainder - requested,
                            idempotent = false,
                        )
                    }
                }
            if (idempotencyKey.isNotBlank()) {
                refundResults[idempotencyKey] = StoredRefund(bookingId, fingerprint, execution)
                val result =
                    when (execution) {
                        is PaymentsRepository.RefundExecution.Success ->
                            PaymentsRepository.Result(
                                PaymentsRepository.Result.Status.OK,
                                execution.amountMinor.toString(),
                            )
                        is PaymentsRepository.RefundExecution.Conflict ->
                            PaymentsRepository.Result(PaymentsRepository.Result.Status.CONFLICT, execution.reason)
                        is PaymentsRepository.RefundExecution.Unprocessable ->
                            PaymentsRepository.Result(PaymentsRepository.Result.Status.ERROR, execution.reason)
                        PaymentsRepository.RefundExecution.IdempotencyBindingMismatch,
                        PaymentsRepository.RefundExecution.IdempotencyPayloadMismatch,
                        -> error("mismatch cannot be persisted")
                    }
                actions[idempotencyKey] =
                    PaymentsRepository.SavedAction(
                        id = idSequence.incrementAndGet(),
                        bookingId = bookingId,
                        idempotencyKey = idempotencyKey,
                        action = PaymentsRepository.Action.REFUND,
                        result = result,
                        createdAt = Instant.now(),
                        refundFingerprint = fingerprint,
                        refundResultAmountMinor =
                            (execution as? PaymentsRepository.RefundExecution.Success)?.amountMinor,
                    )
            }
            execution
        }

    private fun PaymentsRepository.RefundExecution.asIdempotent(): PaymentsRepository.RefundExecution =
        when (this) {
            is PaymentsRepository.RefundExecution.Success -> copy(idempotent = true, remainingMinor = null)
            is PaymentsRepository.RefundExecution.Conflict -> copy(idempotent = true)
            is PaymentsRepository.RefundExecution.Unprocessable -> copy(idempotent = true)
            PaymentsRepository.RefundExecution.IdempotencyBindingMismatch,
            PaymentsRepository.RefundExecution.IdempotencyPayloadMismatch,
            -> this
        }

    private data class StoredRefund(
        val bookingId: UUID,
        val fingerprint: PaymentsRepository.RefundFingerprint,
        val execution: PaymentsRepository.RefundExecution,
    )
}

private class TestPaymentsBookingRepository : PaymentsBookingRepository {
    private val bookings = ConcurrentHashMap<Pair<Long, UUID>, BookingRecord>()

    fun seed(
        bookingId: UUID,
        clubId: Long,
        status: BookingStatus,
    ) {
        bookings[clubId to bookingId] = newRecord(bookingId, clubId, status)
    }

    override suspend fun cancel(
        bookingId: UUID,
        clubId: Long,
    ): BookingCancellationResult {
        val key = clubId to bookingId
        val current = bookings[key] ?: return BookingCancellationResult.NotFound
        return when (current.status) {
            BookingStatus.CANCELLED -> BookingCancellationResult.AlreadyCancelled(current)
            BookingStatus.BOOKED -> {
                val updated = current.copy(status = BookingStatus.CANCELLED)
                bookings[key] = updated
                BookingCancellationResult.Cancelled(updated)
            }
            else -> BookingCancellationResult.ConflictingStatus(current)
        }
    }

    private fun newRecord(
        bookingId: UUID,
        clubId: Long,
        status: BookingStatus,
    ): BookingRecord =
        BookingRecord(
            id = bookingId,
            clubId = clubId,
            tableId = 1L,
            tableNumber = 1,
            eventId = 1L,
            guests = 2,
            minRate = BigDecimal.ZERO,
            totalRate = BigDecimal.ZERO,
            slotStart = Instant.EPOCH,
            slotEnd = Instant.EPOCH,
            status = status,
            arrivalBy = null,
            qrSecret = "qr",
            idempotencyKey = "seed",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
}

private class FakeFinalizeService : com.example.bot.payments.finalize.PaymentsFinalizeService {
    override suspend fun finalize(
        clubId: Long,
        bookingId: UUID,
        paymentToken: String?,
        idemKey: String,
        actorUserId: Long,
    ): com.example.bot.payments.finalize.PaymentsFinalizeService.FinalizeResult =
        com.example.bot.payments.finalize.PaymentsFinalizeService
            .FinalizeResult(paymentStatus = "TEST")
}

@kotlinx.serialization.Serializable
private data class CancelRequest(
    val reason: String? = null,
)

@kotlinx.serialization.Serializable
private data class RefundRequest(
    val amountMinor: Long? = null,
)

private fun Application.metricsRoute(registry: PrometheusMeterRegistry) {
    routing {
        get("/metrics") {
            call.respondText(
                registry.scrape(),
                ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
            )
        }
    }
}
