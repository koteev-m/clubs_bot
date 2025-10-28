package com.example.bot.routes

import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.core.BookingCancellationResult
import com.example.bot.data.booking.core.BookingRecord
import com.example.bot.data.booking.core.PaymentsBookingRepository
import com.example.bot.di.DefaultPaymentsService
import com.example.bot.di.PaymentsService
import com.example.bot.observability.MetricsProvider
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.plugins.metricsRoute
import com.example.bot.telemetry.PaymentsMetrics
import com.example.bot.payments.PaymentsRepository
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
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.ktor.server.routing.routing
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

    override suspend fun afterEach(testCase: TestCase, result: TestResult) {
        PaymentsMetrics.resetForTest()
        resetMiniAppValidator()
    }

    init {
        "exports metrics for cancel idempotency and refund errors" {
            val registry = MetricsProvider.prometheusRegistry()
            val metricsProvider = MetricsProvider(registry)
            val paymentsRepository = InMemoryPaymentsRepository()
            val bookingId = UUID.randomUUID()
            val bookingRepository = TestPaymentsBookingRepository().apply {
                seed(bookingId = bookingId, clubId = 1L, status = BookingStatus.BOOKED)
            }
            val service = DefaultPaymentsService(
                finalizeService = FakeFinalizeService(),
                paymentsRepository = paymentsRepository,
                bookingRepository = bookingRepository,
                metricsProvider = metricsProvider,
                tracer = null,
            )

            service.seedLedger(
                clubId = 1L,
                bookingId = bookingId,
                status = "BOOKED",
                capturedMinor = 1_500,
                refundedMinor = 0,
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
                val cancelUrl = "/api/clubs/1/bookings/${bookingId}/cancel"
                val refundUrl = "/api/clubs/1/bookings/${bookingId}/refund"

                client.post(cancelUrl) {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    header("Idempotency-Key", "idem-cancel")
                    header("X-Telegram-Init-Data", "stub")
                    setBody(cancelPayload)
                }.status shouldBe HttpStatusCode.OK

                client.post(cancelUrl) {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    header("Idempotency-Key", "idem-cancel")
                    header("X-Telegram-Init-Data", "stub")
                    setBody(cancelPayload)
                }.status shouldBe HttpStatusCode.OK

                client.post(refundUrl) {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    header("Idempotency-Key", "idem-refund")
                    header("X-Telegram-Init-Data", "stub")
                    setBody(Json.encodeToString(RefundRequest(amountMinor = 2_000)))
                }.status shouldBe HttpStatusCode.UnprocessableEntity

                val metricsBody =
                    client.get("/metrics") {
                        header("X-Telegram-Init-Data", "stub")
                    }.bodyAsText()

                metricsBody.shouldContain("payments_cancel_duration_seconds_count{path=\"cancel\",result=\"ok\",source=\"miniapp\"")
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
    private val idSequence = AtomicLong(0)

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

    override suspend fun markCaptured(
        id: UUID,
        externalId: String?,
    ) {
        throw UnsupportedOperationException("not used in test")
    }

    override suspend fun markDeclined(
        id: UUID,
        reason: String,
    ) {
        throw UnsupportedOperationException("not used in test")
    }

    override suspend fun markRefunded(
        id: UUID,
        externalId: String?,
    ) {
        throw UnsupportedOperationException("not used in test")
    }

    override suspend fun findByPayload(payload: String): PaymentsRepository.PaymentRecord? = null

    override suspend fun findByIdempotencyKey(idempotencyKey: String): PaymentsRepository.PaymentRecord? = null

    override suspend fun updateStatus(
        id: UUID,
        status: String,
        externalId: String?,
    ) {
        throw UnsupportedOperationException("not used in test")
    }

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
        actions[key] = saved
        return saved
    }

    override suspend fun findActionByIdempotencyKey(key: String): PaymentsRepository.SavedAction? = actions[key]
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
    ): BookingRecord {
        return BookingRecord(
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
            qrSecret = "qr",
            idempotencyKey = "seed",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
    }
}

private class FakeFinalizeService : com.example.bot.payments.finalize.PaymentsFinalizeService {
    override suspend fun finalize(
        clubId: Long,
        bookingId: UUID,
        paymentToken: String?,
        idemKey: String,
        actorUserId: Long,
    ): com.example.bot.payments.finalize.PaymentsFinalizeService.FinalizeResult {
        return com.example.bot.payments.finalize.PaymentsFinalizeService.FinalizeResult(paymentStatus = "TEST")
    }
}

@kotlinx.serialization.Serializable
private data class CancelRequest(val reason: String? = null)

@kotlinx.serialization.Serializable
private data class RefundRequest(val amountMinor: Long? = null)
